(ns jolt.sim.future-schedule
  "The first coarse deterministic scheduler for unchanged ordinary Jolt future
  code, driven entirely off the current controller lifecycle events. It does
  not add a new controller hook or change core Jolt semantics: it is a pure
  interpreter over the same `{:event :task :parent}` records `run-controlled`
  already forwards to `:on-event`.

  A `:future-schedule` is a nonempty vector that is an exact permutation of
  `0..N-1`. Ordinals are assigned, in arrival order, to `:spawn` events whose
  `:parent` is `0`. The current contract uses parent zero for every non-hooked
  spawning thread, not only the thread running the thunk, so this first slice requires
  a caller-enforced quiescent scope with exactly one parent-zero spawner. A
  nonzero parent is rejected; a competing raw-thread spawner is outside the
  enforceable ABI boundary and must not be present. The schedule vector then
  states the admission order for those ordinals' *bodies*: `schedule[0]` runs
  first, then, only after that task's `:finish`, `schedule[1]`, and so on. At
  most one admitted body ever runs at a time.

  Mechanically, an immutable single-use gate (a promise) is created the
  moment a scheduled ordinal's `:spawn` is observed. The `:start` hook for
  that task blocks on the gate. A gate is delivered `:admit` either the
  instant it is created (if its ordinal is already due) or when the
  currently-due ordinal's `:finish` arrives (if the next ordinal's gate
  already exists). `:exit`/`:abort` are current worker-ownership/drain
  evidence only -- they never advance the schedule.

  Any violation -- a nested spawn, more spawns than the schedule
  declares, fewer spawns than the schedule declares (checked once the owner
  thunk returns), a pre-worker `:abort`, an out-of-order terminal event, or an
  application cancellation (unsupported in this first slice) -- aborts the
  scheduler, delivers an internal abort decision to every gate not yet
  delivered (so blocked `:start` calls fail fast instead of hanging and can
  still reach `:exit` for drainage), and throws an `ex-info` tagged
  `:jolt.sim.runtime/schedule-error`. Because it is thrown from the composed
  `:on-event` callback, `run-controlled`'s existing supervisor-latch
  machinery records it even when application code catches the propagated
  exception at the call site. The first schedule failure is retained so the
  enclosing run can surface that exact typed failure after drainage.

  Successful logical evidence contains only deterministic `[:admit ordinal]`
  and `[:complete ordinal]` entries. Raw spawn/start/exit arrival order remains
  in `run-controlled`'s compatibility `:events` vector; it is intentionally not
  mislabeled as replay-stable scheduler evidence.

  This scheduler does not recover an application-level deadlock: if the
  scheduled admission order is incompatible with how the thunk's own thread
  actually depends on its futures (for example, blocking on an
  already-spawned future before spawning the one the schedule requires to
  run first), the blocked `:start`/`deref` simply never returns. That is a
  property of the unchanged application code under the requested order, not
  a scheduler failure, and is not something this slice claims to detect or
  recover from -- it still requires external/subprocess supervision.")

(defn valid-schedule?
  "True when `value` is a nonempty vector that is an exact permutation of the
  integers `0..(count value)-1`."
  [value]
  (boolean
   (and (vector? value)
        (seq value)
        (every? #(and (integer? %) (not (neg? %))) value)
        (= (count value) (count (set value)))
        (= (set value) (set (range (count value)))))))

(defn validate-schedule!
  "Throws :jolt.sim.runtime/invalid-config unless `value` satisfies
  valid-schedule?. Pure and ABI-independent, so it runs before controller
  installation on any image, including an ordinary released one."
  [value]
  (when-not (valid-schedule? value)
    (throw
     (ex-info
      "run-controlled :future-schedule must be a nonempty vector that is an exact permutation of 0..N-1"
      {:type :jolt.sim.runtime/invalid-config
       :future-schedule value})))
  value)

(defn- schedule-error [reason data]
  (ex-info
   "run-controlled :future-schedule detected a scheduling violation"
   (merge {:type :jolt.sim.runtime/schedule-error :reason reason} data)))

(defn- gate-aborted-error [data]
  (ex-info
   "run-controlled :future-schedule gate was aborted"
   (merge {:type :jolt.sim.future-schedule/gate-aborted} data)))

(defn gate-aborted?
  "True only when `error` or one of its first eight causes is the scheduler's
  internal gate-abort sentinel. Future deref publishes a failed `:start`
  callback through an ExecutionException, so recognizing only the outer
  throwable would let that transport wrapper mask the callback failure that
  caused the gate to close."
  [error]
  (loop [current error
         remaining 8]
    (cond
      (nil? current)
      false

      (= :jolt.sim.future-schedule/gate-aborted
         (:type (ex-data current)))
      true

      (zero? remaining)
      false

      :else
      (recur (ex-cause current) (dec remaining)))))

(defn- deliver-abort-gates!
  "Delivers the internal abort decision to every gate in an already-atomic
  scheduler snapshot. Delivery is idempotent."
  [snapshot]
  (doseq [[_ gate] (:gates snapshot)]
    (deliver gate ::abort))
  snapshot)

(defn- abort-all-gates!
  "Marks the scheduler aborting, optionally retains the first primary failure,
  and delivers the internal abort decision to every gate created so far.
  Idempotent: delivering to an already-delivered promise is a no-op."
  ([state]
   (abort-all-gates! state nil))
  ([state failure]
   (let [snapshot
         (swap! state
                (fn [s]
                  (cond-> (assoc s :aborting? true)
                    failure (update :failure #(or % failure)))))]
     (deliver-abort-gates! snapshot))))

(defn- fail-schedule! [state reason data]
  (let [candidate (schedule-error reason data)
        snapshot (abort-all-gates! state candidate)]
    (throw (:failure snapshot))))

(defn- apply-spawn [s task parent]
  (cond
    (:aborting? s)
    (assoc s :reject {:reason :aborted :task task :parent parent})

    (not (zero? parent))
    (let [data {:task task :parent parent}
          failure (schedule-error :nested-spawn data)]
      (assoc s
             :aborting? true
             :failure (or (:failure s) failure)
             :reject (assoc data :reason :nested-spawn)))

    (>= (:next-ordinal s) (:n s))
    (let [data {:task task :parent parent :scheduled (:n s)}
          failure (schedule-error :extra-spawn data)]
      (assoc s
             :aborting? true
             :failure (or (:failure s) failure)
             :reject (assoc data :reason :extra-spawn)))

    :else
    (let [ordinal (:next-ordinal s)
          gate (promise)]
      (-> s
          (update :task->ordinal assoc task ordinal)
          (update :gates assoc ordinal gate)
          (update :next-ordinal inc)
          (dissoc :reject)))))

(defn- admit-gate!
  "Atomically records one deterministic admission before releasing its gate."
  [state ordinal]
  (loop []
    (let [before @state
          gate (get (:gates before) ordinal)]
      (cond
        (or (:aborting? before)
            (nil? gate)
            (contains? (:admitted before) ordinal))
        false

        :else
        (let [after (-> before
                        (update :admitted conj ordinal)
                        (update :log conj [:admit ordinal]))]
          (if (compare-and-set! state before after)
            (do
              (deliver gate ::admit)
              true)
            (recur)))))))

(defn- handle-spawn! [state task parent]
  (let [new (swap! state apply-spawn task parent)]
    (if-let [reject (:reject new)]
      (do
        ;; Nested/extra recognition, the abort latch, and first-failure
        ;; retention happen in the single apply-spawn transition above. No
        ;; concurrent :finish can therefore admit a later gate after the
        ;; violation has been recognized.
        (deliver-abort-gates! new)
        (if (= :aborted (:reason reject))
          (throw (gate-aborted-error
                  {:task task :reason :scheduler-aborting}))
          (throw (:failure new))))
      (let [ordinal (dec (:next-ordinal new))]
        (when (= ordinal
                 (nth (:schedule new) (:admitted-index new) ::none))
          (admit-gate! state ordinal))))))

(defn- handle-start! [state task]
  (let [snapshot @state
        ordinal (get (:task->ordinal snapshot) task)]
    (if (nil? ordinal)
      (fail-schedule! state :unscheduled-start {:task task})
      (let [gate (get (:gates snapshot) ordinal)
            outcome (deref gate)]
        (when (or (= outcome ::abort)
                  (:aborting? @state))
          (throw
           (gate-aborted-error {:task task :ordinal ordinal})))))))

(defn- apply-finish [s ordinal]
  (cond
    (:aborting? s)
    (dissoc s :reject)

    (not= ordinal (nth (:schedule s) (:admitted-index s) ::none))
    (assoc s :reject
           {:reason :impossible-transition :ordinal ordinal
            :expected-ordinal
            (nth (:schedule s) (:admitted-index s) nil)})

    :else
    (-> s
        (update :admitted-index inc)
        (update :log conj [:complete ordinal])
        (dissoc :reject))))

(defn- validate-finish! [state task]
  (let [snapshot @state
        ordinal (get (:task->ordinal snapshot) task)]
    (cond
      (nil? ordinal)
      (fail-schedule! state :unscheduled-finish {:task task})

      (:aborting? snapshot)
      nil

      (not= ordinal
            (nth (:schedule snapshot)
                 (:admitted-index snapshot) ::none))
      (fail-schedule!
       state :impossible-transition
       {:ordinal ordinal
        :expected-ordinal
        (nth (:schedule snapshot) (:admitted-index snapshot) nil)})

      :else
      ordinal)))

(defn- handle-finish! [state task]
  (let [ordinal (get (:task->ordinal @state) task)]
    (if (nil? ordinal)
      (fail-schedule! state :unscheduled-finish {:task task})
      (let [new (swap! state apply-finish ordinal)]
        (if-let [reject (:reject new)]
          (fail-schedule! state
                          (:reason reject)
                          (dissoc reject :reason))
          (let [next-index (:admitted-index new)]
            (when (and (not (:aborting? new))
                       (< next-index (:n new)))
              (let [next-ordinal (nth (:schedule new) next-index)
                    gate (get (:gates new) next-ordinal)]
                (when gate (admit-gate! state next-ordinal))))))))))

(defn- handle-cancel! [state task]
  (fail-schedule! state :cancellation-unsupported {:task task}))

(defn- handle-abort! [state task]
  (let [snapshot @state
        ordinal (get (:task->ordinal snapshot) task)]
    (cond
      (:aborting? snapshot)
      nil

      (nil? ordinal)
      (fail-schedule! state :unscheduled-abort {:task task})

      :else
      (fail-schedule! state :preworker-abort
                      {:task task :ordinal ordinal}))))

(defn- dispatch! [state event task parent]
  (case event
    :spawn (handle-spawn! state task parent)
    :start (handle-start! state task)
    :finish (handle-finish! state task)
    :cancel (handle-cancel! state task)
    :abort (handle-abort! state task)
    :exit nil
    nil))

(defn- check-complete!
  "Called once the thunk has returned normally. Under the required quiescent,
  single-parent-zero-spawner contract, no later accepted ordinal can appear;
  fewer spawns than declared is therefore a final violation."
  [state]
  (let [snapshot @state]
    (when (and (not (:aborting? snapshot))
               (< (:next-ordinal snapshot) (:n snapshot)))
      (fail-schedule! state :missing-spawn
                      {:expected (:n snapshot)
                       :spawned (:next-ordinal snapshot)}))))

(defn- invoke-user! [state user-on-event record]
  (when user-on-event
    (try
      (user-on-event record)
      (catch :default error
        ;; A callback failure must not leave any later :start gate stranded.
        ;; It remains a controller callback failure, not a fabricated schedule
        ;; violation, so retain no :failure here.
        (abort-all-gates! state)
        (throw error)))))

(defn- on-event
  "Returns a composed on-event fn. Scheduler validation and :start admission
  run before the user callback. For :finish, validation runs first but schedule
  advancement waits until the user callback returns, so a callback failure
  cannot release the next body."
  [state user-on-event]
  (fn [{:keys [event task parent] :as record}]
    (if (= :finish event)
      (do
        (validate-finish! state task)
        (invoke-user! state user-on-event record)
        (handle-finish! state task))
      (do
        (dispatch! state event task parent)
        (invoke-user! state user-on-event record)))))

(defn- wrap-thunk [state thunk]
  (fn wrapped-thunk []
    (try
      (let [result (thunk)]
        (check-complete! state)
        result)
      (catch :default error
        (abort-all-gates! state)
        (throw error)))))

(defn scheduler
  "Returns a scheduler for the already-validated `schedule` permutation and
  optional `user-on-event`, as a map of:

    :on-event   the composed on-event fn to install in place of the caller's
    :wrap-thunk (fn [thunk]) -> the thunk wrapped with the post-return
                completeness check and gate-abort-on-failure
    :evidence   (fn []) -> the current scheduler-specific logical evidence
                log, a deterministic vector of [:admit ordinal] and
                [:complete ordinal] pairs
    :failure    (fn []) -> the retained first primary schedule failure, or nil."
  [schedule user-on-event]
  (let [state (atom {:schedule schedule
                     :n (count schedule)
                     :next-ordinal 0
                     :task->ordinal {}
                     :gates {}
                     :admitted #{}
                     :admitted-index 0
                     :aborting? false
                     :failure nil
                     :log []})]
    {:on-event (on-event state user-on-event)
     :wrap-thunk (fn [thunk] (wrap-thunk state thunk))
     :evidence (fn [] (:log @state))
     :failure (fn [] (:failure @state))}))
