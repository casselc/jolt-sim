(ns jolt.sim.kernel
  "A small, immutable cooperative scheduler with virtual time and exact replay."
  (:require [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(def replay-diverged :jolt.sim/replay-diverged)

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
            failed-ids (ids-with-status tasks :failed)
            completed-ids (ids-with-status tasks :completed)
            runnable-ids (ids-with-status tasks :runnable)
            step-count (:steps state)]
        (cond
          (seq failed-ids)
          (let [task-id (first failed-ids)
                error (:error (get tasks task-id))]
            (terminal-result
             state
             :failed
             #(trace/failed-event
               step-count now task-id (trace/canonical-value error) %)))

          (= (count completed-ids) (count tasks))
          (terminal-result
           state
           :completed
           #(trace/completed-event step-count now %))

          (seq runnable-ids)
          (if (>= step-count (:max-steps state))
            (terminal-result
             state
             :step-limit
             #(trace/step-limit-event step-count now %))
            (let [[task-id next-selector]
                  (select-task (:selector state) runnable-ids)
                  choice-event
                  (trace/choose-event step-count now runnable-ids task-id)
                  task (get tasks task-id)
                  context {:task task-id
                           :now now
                           :world (:world state)}
                  transition
                  (try
                    ((:step state) context (:state task))
                    (catch :default error
                      (step-fail (trace/normalize-error error))))
                  [next-state wake-ids transition]
                  (apply-transition state task-id transition)
                  next-state
                  (-> next-state
                      (assoc :selector next-selector)
                      (update :steps inc))
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
              (recur (update next-state
                             :trace conj choice-event transition-event))))

          :else
          (if-let [wake-at (next-wake-time tasks)]
            (let [tasks (wake-due tasks wake-at)
                  awakened (ids-with-status tasks :runnable)
                  next-state (assoc state :now wake-at :tasks tasks)
                  event (trace/time-event
                         step-count now wake-at awakened
                         (state-projection next-state))]
              (recur (update next-state :trace conj event)))
            (let [blocked-ids (ids-with-status tasks :blocked)]
              (terminal-result
               state
               :deadlock
               #(trace/deadlock-event step-count now blocked-ids %)))))))))

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

(defn- malformed-trace! [event-index detail]
  (throw
   (ex-info
    "Replay trace is malformed"
    {:type replay-diverged
     :reason :malformed-trace
     :event-index event-index
     :detail detail})))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- sorted-task-id-vector? [value]
  (and (vector? value)
       (every? task-id? value)
       (= value (sorted-ids value))
       (= (count value) (count (set value)))))

(defn- state-projection? [value]
  (and (trace/canonical-form? value)
       (= :jolt.sim.value/map (first value))))

(defn- valid-step-and-time? [step time]
  (and (non-negative-integer? step)
       (integer? time)))

(defn- validate-event! [index event]
  (when-not (vector? event)
    (malformed-trace! index :event-must-be-vector))
  (when (empty? event)
    (malformed-trace! index :empty-event))
  (let [tag (nth event 0)]
    (case tag
      :run/initial
      (when-not (and (= 2 (count event))
                     (state-projection? (nth event 1)))
        (malformed-trace! index :invalid-initial-event))

      :schedule/choose
      (when-not
       (and (= 5 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (sorted-task-id-vector? (nth event 3))
            (task-id? (nth event 4)))
        (malformed-trace! index :invalid-choice-event))

      :task/transition
      (when-not
       (and (= 9 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (task-id? (nth event 3))
            (contains? transition-ops (nth event 4))
            (trace/canonical-form? (nth event 5))
            (sorted-task-id-vector? (nth event 6))
            (or (nil? (nth event 7))
                (integer? (nth event 7)))
            (state-projection? (nth event 8)))
        (malformed-trace! index :invalid-transition-event))

      :time/advance
      (when-not
       (and (= 6 (count event))
            (non-negative-integer? (nth event 1))
            (integer? (nth event 2))
            (integer? (nth event 3))
            (sorted-task-id-vector? (nth event 4))
            (state-projection? (nth event 5)))
        (malformed-trace! index :invalid-time-event))

      :run/completed
      (when-not
       (and (= 4 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (state-projection? (nth event 3)))
        (malformed-trace! index :invalid-completed-event))

      :run/failed
      (when-not
       (and (= 6 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (task-id? (nth event 3))
            (trace/canonical-form? (nth event 4))
            (state-projection? (nth event 5)))
        (malformed-trace! index :invalid-failed-event))

      :run/deadlock
      (when-not
       (and (= 5 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (sorted-task-id-vector? (nth event 3))
            (state-projection? (nth event 4)))
        (malformed-trace! index :invalid-deadlock-event))

      :run/step-limit
      (when-not
       (and (= 4 (count event))
            (valid-step-and-time? (nth event 1) (nth event 2))
            (state-projection? (nth event 3)))
        (malformed-trace! index :invalid-step-limit-event))

      (malformed-trace! index :unknown-event-tag))))

(defn- validate-replay-trace! [expected-trace]
  (when-not (vector? expected-trace)
    (malformed-trace! 0 :trace-must-be-vector))
  (when (empty? expected-trace)
    (malformed-trace! 0 :empty-trace))
  (doseq [[index event] (map-indexed vector expected-trace)]
    (validate-event! index event))
  (when-not (= :run/initial (first (first expected-trace)))
    (malformed-trace! 0 :missing-initial-event))
  (let [initial-indices
        (keep-indexed (fn [index event]
                        (when (= :run/initial (first event))
                          index))
                      expected-trace)]
    (when-not (= [0] (vec initial-indices))
      (malformed-trace! (or (second initial-indices) 0)
                        :duplicate-initial-event))))

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
  (validate-replay-trace! expected-trace)
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
