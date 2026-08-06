(ns jolt.sim.kernel
  "A small, immutable cooperative scheduler with virtual time and exact replay."
  (:require [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(def replay-diverged
  "Compatibility facade for the trace-owned replay/schema error type."
  trace/replay-diverged)

(def ^:private task-statuses
  #{:runnable :blocked :sleeping :completed :failed})

(def ^:private transition-ops
  #{:yield :block :sleep :complete :fail})

(defn runnable [state]
  {:status :runnable :state state})

(defn blocked [state]
  {:status :blocked :state state})

(defn sleeping [state wake-at]
  {:status :sleeping :state state :wake-at wake-at})

(defn completed [result]
  {:status :completed :result result})

(defn failed [error]
  {:status :failed :error error})

(defn step-yield [state]
  {:op :yield :state state})

(defn step-block [state]
  {:op :block :state state})

(defn step-sleep [state wake-at]
  {:op :sleep :state state :wake-at wake-at})

(defn step-complete [result]
  {:op :complete :result result})

(defn step-fail [error]
  {:op :fail :error error})

(defn with-world
  "Associates the next immutable shared world with a task transition."
  [transition world]
  (assoc transition :world world))

(defn waking
  "Associates blocked task IDs to wake atomically with a transition."
  [transition task-ids]
  (assoc transition :wake task-ids))

(defn at-site
  "Adds a stable, caller-defined site label to a trace transition."
  [transition site]
  (assoc transition :site site))

(defn- config-error! [message data]
  (throw (ex-info message (assoc data :type ::invalid-config))))

(defn- transition-error! [message data]
  (throw (ex-info message (assoc data :type ::invalid-transition))))

(defn- task-id? [value]
  (and (integer? value) (not (neg? value))))

(defn- validate-task-id! [task-id type]
  (when-not (task-id? task-id)
    (throw
     (ex-info
      "Task IDs must be non-negative integers"
      {:type type
       :task-id-description (pr-str task-id)}))))

(defn- sorted-ids [ids]
  (vec (sort ids)))

(defn- normalize-task-error [task]
  (if (= :failed (:status task))
    (update task :error trace/normalize-error)
    task))

(defn- validate-task! [task-id task]
  (when-not (map? task)
    (config-error! "Each task must be a task-state map" {:task task-id}))
  (let [status (:status task)]
    (when-not (contains? task-statuses status)
      (config-error! "Unknown task status"
                     {:task task-id :status status}))
    (when (and (= :sleeping status)
               (not (integer? (:wake-at task))))
      (config-error! "A sleeping task requires an integer wake time"
                     {:task task-id :wake-at (:wake-at task)})))
  (trace/canonical-value task [:tasks task-id])
  task)

(defn- prepare-config [config]
  (let [tasks (:tasks config)
        world (get config :world nil)
        now (get config :now 0)
        max-steps (get config :max-steps 1000)
        step-fn (:step config)]
    (when-not (map? tasks)
      (config-error! "Simulation requires a task map" {}))
    (when-not (integer? now)
      (config-error! "Virtual time must use integer ticks" {:now now}))
    (when-not (and (integer? max-steps) (not (neg? max-steps)))
      (config-error! "Step limit must be a non-negative integer"
                     {:max-steps max-steps}))
    (when-not (fn? step-fn)
      (config-error! "Simulation requires a task step function" {}))
    (trace/canonical-value world [:world])
    (doseq [task-id (keys tasks)]
      (validate-task-id! task-id ::invalid-config))
    (let [ids (sorted-ids (keys tasks))
          ordered-tasks
          (reduce (fn [result task-id]
                    (let [task (-> (get tasks task-id)
                                   normalize-task-error)]
                      (validate-task! task-id task)
                      (assoc result task-id task)))
                  (sorted-map)
                  ids)]
      {:tasks ordered-tasks
       :world world
       :now now
       :max-steps max-steps
       :step step-fn
       :strategy (get config :strategy (strategy/seeded 1))})))

(defn- ids-with-status [tasks status]
  (reduce-kv (fn [result task-id task]
               (if (= status (:status task))
                 (conj result task-id)
                 result))
             []
             tasks))

(defn- wake-due [tasks now]
  (reduce-kv
   (fn [result task-id task]
     (if (and (= :sleeping (:status task))
              (<= (:wake-at task) now))
       (assoc result task-id (runnable (:state task)))
       result))
   tasks
   tasks))

(defn- next-wake-time [tasks]
  (reduce-kv
   (fn [earliest _ task]
     (if (= :sleeping (:status task))
       (let [wake-at (:wake-at task)]
         (if (or (nil? earliest) (< wake-at earliest))
           wake-at
           earliest))
       earliest))
   nil
   tasks))

(defn- normalize-wake-ids [task-ids]
  (let [task-ids
        (cond
          (nil? task-ids) []
          (sequential? task-ids) (vec task-ids)
          :else
          (transition-error!
           "Transition wake IDs must be a finite sequential collection"
           {:wake-description (pr-str task-ids)}))]
    (doseq [task-id task-ids]
      (validate-task-id! task-id ::invalid-transition))
    (when-not (= (count task-ids) (count (set task-ids)))
      (transition-error! "A transition cannot wake the same task twice"
                         {:wake task-ids}))
    (sorted-ids task-ids)))

(defn- apply-wakes [tasks task-ids]
  (reduce
   (fn [result task-id]
     (let [task (get result task-id ::missing)]
       (when (= ::missing task)
         (transition-error! "A transition tried to wake an unknown task"
                            {:task task-id}))
       (when-not (= :blocked (:status task))
         (transition-error! "Only a blocked task can be explicitly woken"
                            {:task task-id
                             :status (:status task)}))
       (assoc result task-id (runnable (:state task)))))
   tasks
   task-ids))

(defn- task-after-transition [transition]
  (case (:op transition)
    :yield (runnable (:state transition))
    :block (blocked (:state transition))
    :sleep
    (let [wake-at (:wake-at transition)]
      (when-not (integer? wake-at)
        (transition-error! "Sleep transition requires an integer wake time"
                           {:wake-at wake-at}))
      (sleeping (:state transition) wake-at))
    :complete (completed (:result transition))
    :fail (failed (:error transition))))

(defn- normalize-transition [task-id transition]
  (when-not (map? transition)
    (transition-error! "Task step must return a transition map"
                       {:task task-id
                        :transition-class (str (class transition))}))
  (let [op (:op transition)]
    (when-not (contains? transition-ops op)
      (transition-error! "Unknown task transition"
                         {:task task-id :op op}))
    (let [transition
          (cond-> transition
            (= :fail op) (update :error trace/normalize-error))
          wake-ids (normalize-wake-ids (:wake transition))
          task-after (task-after-transition transition)
          world-present? (contains? transition :world)]
      (trace/canonical-value task-after [:transition task-id :task])
      (trace/canonical-value (:site transition)
                             [:transition task-id :site])
      (when world-present?
        (trace/canonical-value (:world transition)
                               [:transition task-id :world]))
      [(assoc transition :wake wake-ids)
       task-after
       wake-ids])))

(defn- apply-transition [state task-id transition]
  (let [[transition task-after wake-ids]
        (normalize-transition task-id transition)]
    (when (some #(= task-id %) wake-ids)
      (transition-error! "A task cannot wake itself"
                         {:task task-id :wake wake-ids}))
    (let [tasks (assoc (:tasks state) task-id task-after)
          tasks (apply-wakes tasks wake-ids)
          world (if (contains? transition :world)
                  (:world transition)
                  (:world state))]
      [(assoc state :tasks tasks :world world)
       wake-ids
       transition])))

(defn- state-projection [state]
  (trace/canonical-value
   (cond-> {:tasks (:tasks state)
            :world (:world state)
            :now (:now state)
            :steps (:steps state)}
     (contains? state :status) (assoc :status (:status state)))))

(defn- choose-replay [selector enabled]
  (let [index (:index selector)
        choices (:choices selector)]
    (when (>= index (count choices))
      (throw
       (ex-info
        "Replay trace ended before the simulation"
        {:type replay-diverged
         :reason :missing-choice
         :choice-index index
         :actual-enabled enabled})))
    (let [event (nth choices index)
          expected-enabled (trace/choice-enabled event)
          chosen (trace/choice-task event)]
      (when-not (= expected-enabled enabled)
        (throw
         (ex-info
          "Replay runnable set diverged"
          {:type replay-diverged
           :reason :enabled-set
           :choice-index index
           :expected-enabled expected-enabled
           :actual-enabled enabled
           :chosen chosen})))
      (when-not (some #(= chosen %) enabled)
        (throw
         (ex-info
          "Replay chose a task outside the runnable set"
          {:type replay-diverged
           :reason :chosen-not-enabled
           :choice-index index
           :enabled enabled
           :chosen chosen})))
      [chosen (assoc selector :index (inc index))])))

(defn- select-task [selector enabled]
  (case (:mode selector)
    :strategy
    (let [[chosen next-strategy]
          (strategy/choose (:strategy selector) enabled)]
      [chosen (assoc selector :strategy next-strategy)])

    :replay
    (choose-replay selector enabled)))

(defn- terminal-result [state status event-fn]
  (let [selector (:selector state)
        state (assoc state :status status)
        event (event-fn (state-projection state))]
    [(-> state
         (update :trace conj event)
         (dissoc :step :max-steps :selector))
     selector]))

(defn- classify
  "Pure one-step decision for a wake-due-normalized task map.

  The single shared owner of the kernel's classification/transition/time-advance
  semantics: both `execute` (with its selector) and the explicit-state machine
  API below interpret the same decision. `tasks` must already reflect every
  sleeping task due at or before `now`; `steps`/`max-steps` carry the budget."
  [tasks steps max-steps]
  (let [failed-ids (ids-with-status tasks :failed)
        completed-ids (ids-with-status tasks :completed)
        runnable-ids (ids-with-status tasks :runnable)
        wake-at (next-wake-time tasks)]
    (cond
      (seq failed-ids)
      (let [task-id (first failed-ids)]
        {:kind :failed
         :task-id task-id
         :error (trace/canonical-value (:error (get tasks task-id)))})

      (= (count completed-ids) (count tasks))
      {:kind :completed}

      (seq runnable-ids)
      (if (>= steps max-steps)
        {:kind :step-limit}
        {:kind :runnable :enabled runnable-ids})

      (some? wake-at)
      {:kind :advance :wake-at wake-at}

      :else
      {:kind :deadlock :blocked (ids-with-status tasks :blocked)})))

(defn- apply-run-action
  "Applies one chosen run action to `state` and returns the next state.

  `state` must already be wake-due-normalized and `enabled` must be its sorted
  runnable task-ID vector. Returns the post-transition state with `:steps`
  incremented and `:trace` extended with the choice and transition events. It
  does not touch `:selector` (the caller owns that) and does not re-run
  `wake-due` (the caller applies it at its next step, mirroring `execute`)."
  [state enabled task-id]
  (let [step-count (:steps state)
        now (:now state)
        choice-event (trace/choose-event step-count now enabled task-id)
        task (get (:tasks state) task-id)
        context {:task task-id :now now :world (:world state)}
        transition
        (try
          ((:step state) context (:state task))
          (catch :default error
            (step-fail (trace/normalize-error error))))
        [next-state wake-ids transition]
        (apply-transition state task-id transition)
        next-state (update next-state :steps inc)
        transition-event
        (trace/transition-event
          step-count
          now
          task-id
          (:op transition)
          (trace/canonical-value (:site transition))
          wake-ids
          (when (= :sleep (:op transition))
            (:wake-at transition))
          (state-projection next-state))]
    (update next-state :trace conj choice-event transition-event)))

(defn- apply-time-advance
  "Advances virtual time to `wake-at`, waking every due task, and returns the
  next state with `:trace` extended with the time/advance event."
  [state wake-at]
  (let [step-count (:steps state)
        now (:now state)
        woken-tasks (wake-due (:tasks state) wake-at)
        awakened (ids-with-status woken-tasks :runnable)
        next-state (assoc state :now wake-at :tasks woken-tasks)
        event (trace/time-event
                step-count now wake-at awakened
                (state-projection next-state))]
    (update next-state :trace conj event)))

(defn- execute [config selector]
  (let [prepared (prepare-config config)
        initial-state {:tasks (:tasks prepared)
                       :world (:world prepared)
                       :now (:now prepared)
                       :steps 0
                       :step (:step prepared)
                       :max-steps (:max-steps prepared)
                       :selector selector}
        initial-event (trace/initial-event
                        (state-projection initial-state))]
    (loop [state (assoc initial-state :trace [initial-event])]
      (let [now (:now state)
            tasks (wake-due (:tasks state) now)
            state (assoc state :tasks tasks)
            step-count (:steps state)
            decision (classify tasks step-count (:max-steps state))]
        (case (:kind decision)
          :failed
          (terminal-result
           state
           :failed
           #(trace/failed-event
             step-count now (:task-id decision) (:error decision) %))

          :completed
          (terminal-result
           state
           :completed
           #(trace/completed-event step-count now %))

          :step-limit
          (terminal-result
           state
           :step-limit
           #(trace/step-limit-event step-count now %))

          :deadlock
          (terminal-result
           state
           :deadlock
           #(trace/deadlock-event step-count now (:blocked decision) %))

          :advance
          (recur (apply-time-advance state (:wake-at decision)))

          :runnable
          (let [enabled (:enabled decision)
                [task-id next-selector]
                (select-task (:selector state) enabled)
                next-state (apply-run-action state enabled task-id)]
            (recur (assoc next-state :selector next-selector))))))))

;; Explicit-state machine over the same one-step semantics.
;;
;; The machine exposes the cooperative kernel as a finite transition system for
;; scoped reachability/completeness exploration. It is constructed from the same
;; config map `run` accepts (its `:strategy` is ignored: the machine enumerates
;; every enabled action rather than selecting one) and reuses `classify`,
;; `apply-run-action`, and `apply-time-advance`, so a single owner owns every
;; classification/transition/time-advance decision.
;;
;; A machine value carries the prepared tasks/world/now/steps plus the kernel
;; trace. Its canonical semantic projection includes the task-transition budget
;; because classification and enabled actions depend on that budget. The trace
;; and step function remain engine/path data rather than canonical state.

(defn- machine-error! [message data]
  (throw (ex-info message (assoc data :type ::invalid-machine-action))))

(defn- machine-normalized
  "Returns `machine` with every sleeping task due at its current tick woken.

  Mirrors `execute`'s top-of-loop `wake-due`: a machine stores the raw
  post-action task map so its execution-prefix events use the same projections
  as `run`, then applies `wake-due` lazily at every observation. Terminal
  observation does not append `run`'s terminal trace event."
  [machine]
  (assoc machine :tasks (wake-due (:tasks machine) (:now machine))))

(defn- stable-copy [value]
  (trace/restore-value (trace/canonical-value value)))

(defn- stable-task-copy [tasks]
  (reduce-kv (fn [result task-id task]
               (assoc result task-id task))
             (sorted-map)
             (stable-copy tasks)))

(defn- machine-branch-copy
  "Returns an isolated value-semantic branch of `machine`.

  Canonical round-tripping freezes mutable byte-array leaves before invoking a
  step callback, so expanding one sibling action cannot mutate the parent seen
  by another sibling. The callback must still be deterministic and free of
  effects through closed-over host state, entropy, clocks, or I/O; those values
  are outside the cooperative completeness boundary."
  [machine]
  (assoc machine
         :tasks (stable-task-copy (:tasks machine))
         :world (stable-copy (:world machine))))

(defn machine
  "Constructs an explicit-state machine from a kernel config map.

  Accepts the same map `run` consumes (`:tasks`, `:world`, `:step`, `:now`,
  `:max-steps`) and validates it through the same `prepare-config` path, so a
  malformed config, task state, or non-canonical value fails closed identically.
  `:strategy` is accepted for source compatibility but has no effect: the
  machine exposes every enabled action instead of selecting one."
  [config]
  (let [prepared (prepare-config config)
        initial {:tasks (stable-task-copy (:tasks prepared))
                 :world (stable-copy (:world prepared))
                 :now (:now prepared)
                 :steps 0
                 :step (:step prepared)
                 :max-steps (:max-steps prepared)}
        initial-event (trace/initial-event (state-projection initial))]
    (assoc initial :trace [initial-event])))

(defn machine-projection
  "Returns the stable canonical semantic projection of `machine`.

  The projection is `{:tasks :world :now :steps :max-steps}` projected through
  `jolt.sim.trace/canonical-value` after wake-due normalization. It deliberately
  excludes the trace and step function. Consumed and maximum budget are state:
  they affect terminal classification and the enabled action set even though a
  step callback cannot observe them directly."
  [machine]
  (let [normalized (machine-normalized machine)]
    (trace/canonical-value
     {:tasks (:tasks normalized)
      :world (:world normalized)
      :now (:now normalized)
      :steps (:steps normalized)
      :max-steps (:max-steps normalized)})))

(defn machine-trace
  "Returns the immutable canonical event trace accumulated by `machine`.

  This accessor lets drivers inspect newly appended events without depending
  on the machine map's internal representation. It does not expose the opaque
  step callback."
  [machine]
  (vec (:trace machine)))

(defn machine-status
  "Returns the terminal status of `machine`, or nil when non-terminal.

  Non-terminal means at least one enabled action remains (a runnable task or a
  forced earliest time advance). Terminal statuses mirror `run`: `:failed`,
  `:completed`, `:deadlock`, and `:step-limit`."
  [machine]
  (let [normalized (machine-normalized machine)
        decision (classify (:tasks normalized)
                           (:steps normalized)
                           (:max-steps normalized))]
    (case (:kind decision)
      (:runnable :advance) nil
      (:kind decision))))

(defn machine-actions
  "Returns the enabled actions of `machine` as a deterministic vector.

  A runnable state yields one `[:run task-id]` action per runnable task in
  ascending task-ID order. A state with no runnable task but a pending timer
  yields exactly one forced `[:advance wake-at]` action whose `wake-at` is the
  earliest sleeping time. A terminal state yields an empty vector."
  [machine]
  (let [normalized (machine-normalized machine)
        decision (classify (:tasks normalized)
                           (:steps normalized)
                           (:max-steps normalized))]
    (case (:kind decision)
      :runnable (mapv #(vector :run %) (:enabled decision))
      :advance [[:advance (:wake-at decision)]]
      [])))

(defn machine-apply
  "Applies one validated enabled `action` to `machine`, returning the next
  machine.

  `action` must be a member of `(machine-actions machine)`. A `[:run task-id]`
  action whose task is not currently runnable, an `[:advance wake-at]` action
  whose wake time is not the current earliest timer, any action applied to a
  terminal machine, or any malformed action shape, fails closed with an
  ex-info tagged `::invalid-machine-action` carrying the offending action, the
  current decision kind, and the enabled set."
  [machine action]
  (when-not (and (vector? action) (= 2 (count action)))
    (machine-error! "Machine action must be a two-element vector"
                    {:action action :reason :shape}))
  (let [[tag payload] action]
    (when-not (contains? #{:run :advance} tag)
      (machine-error! "Unknown machine action"
                      {:action action :reason :tag :tag tag}))
    (when (and (= :run tag) (not (task-id? payload)))
      (machine-error! "Run action requires a non-negative integer task ID"
                      {:action action :reason :task-id :task-id payload}))
    (when (and (= :advance tag) (not (integer? payload)))
      (machine-error! "Advance action requires an integer wake time"
                      {:action action :reason :wake-at :wake-at payload})))
  (let [normalized (-> machine machine-branch-copy machine-normalized)
        decision (classify (:tasks normalized)
                           (:steps normalized)
                           (:max-steps normalized))
        tag (nth action 0 nil)]
    (case (:kind decision)
      :runnable
      (do
        (when-not (= :run tag)
          (machine-error! "Only a :run action is enabled at this machine state"
                          {:action action :kind :runnable
                           :enabled (:enabled decision)}))
        (let [task-id (nth action 1 nil)]
          (when-not (some #(= task-id %) (:enabled decision))
            (machine-error!
             "Run action targets a task outside the runnable set"
             {:action action :kind :runnable
              :task-id task-id :enabled (:enabled decision)}))
          (apply-run-action normalized (:enabled decision) task-id)))

      :advance
      (do
        (when-not (= :advance tag)
          (machine-error! "Only an :advance action is enabled at this machine state"
                          {:action action :kind :advance
                           :enabled (:wake-at decision)}))
        (let [wake-at (nth action 1 nil)]
          (when-not (= wake-at (:wake-at decision))
            (machine-error!
             "Advance action wake time is not the current earliest timer"
             {:action action :kind :advance
              :wake-at wake-at :earliest (:wake-at decision)}))
        (apply-time-advance normalized (:wake-at decision))))

      (machine-error! "No action is enabled at a terminal machine state"
                      {:action action :kind (:kind decision)}))))

(defn run
  "Runs a cooperative simulation to completion, failure, deadlock, or its bound.

  Configuration keys:

  * `:tasks`      map of non-negative integer task IDs to task records from
                  `runnable`, `blocked`, `sleeping`, `completed`, or `failed`
  * `:world`      immutable shared application state in the stable trace domain
  * `:step`       `(fn [{:keys [task now world]} task-state] transition)`
  * `:strategy`   a strategy value; defaults to `(strategy/seeded 1)`
  * `:now`        initial virtual integer tick, default 0
  * `:max-steps`  maximum task transitions, default 1000

  Task states, results, worlds, sites, and errors must use the stable value
  domain documented by `jolt.sim.trace/canonical-value`. Semantic task labels
  belong in state, sites, or future resource identities—not in task IDs."
  [config]
  (let [selected-strategy
        (get config :strategy (strategy/seeded 1))
        [result _]
        (execute config {:mode :strategy
                         :strategy selected-strategy})]
    result))

(defn validate-trace!
  "Validates a complete replay trace's event schema and returns it unchanged.

  Thin facade over `jolt.sim.trace/validate-trace!`, kept here because
  `replay` depends on this name and other callers already use it to validate
  a trace up front. See that function for the fixed-position shape checked
  per event and the malformed-trace error it throws."
  [expected-trace]
  (trace/validate-trace! expected-trace))

(defn- first-difference-index [expected actual]
  (loop [index 0]
    (cond
      (and (= index (count expected))
           (= index (count actual))) nil
      (or (= index (count expected))
          (= index (count actual))) index
      (= (nth expected index) (nth actual index)) (recur (inc index))
      :else index)))

(defn replay
  "Re-executes `config` using choices from the generated `expected-trace`.

  Replay validates the complete event schema before extracting any choice.
  Enabled sets and choices must match, and canonical initial, transition, time,
  and terminal projections make state or outcome drift visible even when the
  selected task and operation labels are unchanged."
  [config expected-trace]
  (validate-trace! expected-trace)
  (let [choices (trace/choice-events expected-trace)
        [result selector]
        (execute config {:mode :replay
                         :choices choices
                         :index 0})]
    (when-not (= (:index selector) (count choices))
      (throw
       (ex-info
        "Replay simulation ended before its recorded choices"
        {:type replay-diverged
         :reason :unused-choices
         :used (:index selector)
         :recorded (count choices)})))
    (when-let [index (first-difference-index expected-trace (:trace result))]
      (throw
       (ex-info
        "Replay trace diverged"
        {:type replay-diverged
         :reason :event
         :event-index index
         :expected (nth expected-trace index nil)
         :actual (nth (:trace result) index nil)})))
    result))
