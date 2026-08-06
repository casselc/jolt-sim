(ns jolt.sim.monitor
  "Versioned offline trace documents and a pure fold-based monitor runner.

  A trace document is `{:jolt.sim.trace/version trace-version
  :jolt.sim.trace/events events}`, where `events` is a complete replay trace
  validated by `jolt.sim.trace/validate-trace!`. Monitors run as a pure fold
  over a validated document's events; see `run-monitor`."
  (:require [jolt.sim.trace :as trace]))

(def trace-version
  "The only trace document version this namespace accepts.

  Thin facade over `jolt.sim.trace/trace-version`."
  trace/trace-version)

(defn document
  "Builds a versioned trace document from `events`, validating exact keys,
  supported version, and the complete event schema before returning it.

  Thin facade over `jolt.sim.trace/document`."
  [events]
  (trace/document events))

(defn read-edn
  "Reads a versioned trace document from the EDN string `s`, validating exact
  keys, supported version, and the complete event schema before returning it.
  Exactly one EDN form is required. Throws a typed, fail-closed error on
  unreadable or trailing EDN and on any malformed document shape.

  Thin facade over `jolt.sim.trace/read-edn`."
  [s]
  (trace/read-edn s))

(def ^:private decision-statuses #{:pass :violation :inconclusive})
(def ^:private monitor-spec-keys #{:id :initial :step :finish})

(defn- fail-closed! [monitor-id reason detail]
  (throw
   (ex-info
    "Monitor produced an invalid result"
    {:type ::invalid-monitor-result
     :monitor monitor-id
     :reason reason
     :detail detail})))

(defn validate-spec!
  "Returns spec when it has the exact executable monitor shape accepted by
  run-monitor. Intended for trusted plan assembly before a run begins."
  [spec]
  (when-not (map? spec)
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :not-a-map
       :detail (str (class spec))})))
  (when-not (= monitor-spec-keys (set (keys spec)))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :wrong-keys
       :detail (set (keys spec))})))
  (when-not (fn? (:step spec))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :step-not-a-function})))
  (when-not (fn? (:finish spec))
    (throw
     (ex-info
      "Monitor specification is invalid"
      {:type ::invalid-monitor-spec
       :reason :finish-not-a-function})))
  (trace/canonical-value (:id spec) [:monitor :id])
  (trace/canonical-value (:initial spec) [:monitor :initial])
  spec)

(defn- decision-keys? [value]
  (contains? #{#{:status} #{:status :detail}} (set (keys value))))

(defn- validate-decision! [monitor-id value]
  (when-not (map? value)
    (fail-closed! monitor-id :decision-not-a-map (str (class value))))
  (when-not (decision-keys? value)
    (fail-closed! monitor-id :decision-wrong-keys (set (keys value))))
  (when-not (contains? decision-statuses (:status value))
    (fail-closed! monitor-id :decision-bad-status (:status value)))
  (when (contains? value :detail)
    (trace/canonical-value (:detail value) [:detail]))
  value)

(defn- validate-step-result! [monitor-id value]
  (when-not (map? value)
    (fail-closed! monitor-id :step-result-not-a-map (str (class value))))
  (if (= #{:state} (set (keys value)))
    (do
      (trace/canonical-value (:state value) [:state])
      value)
    (validate-decision! monitor-id value)))

(defn run-monitor
  "Folds `spec` over a validated trace `doc`'s events.

  `spec` is a map of:
  * `:id`      an opaque monitor identity, returned unchanged
  * `:initial` the starting monitor state
  * `:step`    `(fn [state index event] result)`, where `result` is either
               `{:state next-state}` or a terminal decision map with
               `:status` (one of `:pass`, `:violation`, `:inconclusive`) and
               optional `:detail`
  * `:finish`  `(fn [final-state] decision)`, called once all events are
               folded without an earlier decision, and must itself return a
               decision

  Returns `{:id monitor-id :status status :detail detail :index index}`,
  where `index` is the event index of the first decision, or `nil` when the
  decision came from `:finish`. Every `:state` and `:detail` value is checked
  with `jolt.sim.trace/canonical-value`, including the initial state before any
  callback runs. Values retain their caller-visible types; serialize the
  result through `jolt.sim.trace/canonical-edn` for byte-stable EDN. Malformed
  specs and step or finish results throw fail-closed typed errors rather than
  being coerced; exceptions `:step` or `:finish` raise propagate uncaught
  rather than becoming a decision."
  [spec doc]
  (trace/validate-document! doc)
  (validate-spec! spec)
  (let [monitor-id (:id spec)
        step (:step spec)
        finish (:finish spec)
        events (:jolt.sim.trace/events doc)
        event-count (count events)]
    (loop [state (:initial spec)
           index 0]
      (if (= index event-count)
        (let [decision (validate-decision! monitor-id (finish state))]
          {:id monitor-id
           :status (:status decision)
           :detail (:detail decision)
           :index nil})
        (let [result (validate-step-result!
                      monitor-id (step state index (nth events index)))]
          (if (contains? result :state)
            (recur (:state result) (inc index))
            {:id monitor-id
             :status (:status result)
             :detail (:detail result)
             :index index}))))))

(def ^:private terminal-tags
  #{:run/completed :run/failed :run/deadlock :run/step-limit})

(def ^:private trace-grammar-initial-state
  {:step 0 :time nil :pending nil :terminated? false})

(defn- grammar-violation [reason detail]
  {:status :violation :detail (assoc detail :reason reason)})

(defn- grammar-step [state index event]
  (cond
    (zero? index)
    {:state state}

    (:terminated? state)
    (grammar-violation :event-after-terminal {:index index})

    :else
    (let [tag (nth event 0)
          pending (:pending state)]
      (cond
        (some? pending)
        (if (not= :task/transition tag)
          (grammar-violation :missing-transition
                              {:index index :pending pending :tag tag})
          (let [actual {:step (nth event 1)
                        :time (nth event 2)
                        :task (nth event 3)}]
            (if (not= actual pending)
              (grammar-violation :choice-transition-mismatch
                                  {:index index
                                   :expected pending
                                   :actual actual})
              {:state (assoc state
                             :pending nil
                             :step (inc (:step state)))})))

        (= :task/transition tag)
        (grammar-violation :orphan-transition {:index index})

        (= :schedule/choose tag)
        (let [step (nth event 1)
              time (nth event 2)
              chosen (nth event 4)]
          (cond
            (not= step (:step state))
            (grammar-violation :bad-step
                                {:index index
                                 :expected (:step state)
                                 :actual step})

            (and (some? (:time state)) (not= time (:time state)))
            (grammar-violation :time-mismatch
                                {:index index
                                 :expected (:time state)
                                 :actual time})

            :else
            {:state (assoc state
                           :time (or (:time state) time)
                           :pending {:step step :time time :task chosen})}))

        (= :time/advance tag)
        (let [step (nth event 1)
              from (nth event 2)
              to (nth event 3)
              awakened (nth event 4)]
          (cond
            (not= step (:step state))
            (grammar-violation :bad-step
                                {:index index
                                 :expected (:step state)
                                 :actual step})

            (and (some? (:time state)) (not= from (:time state)))
            (grammar-violation :time-advance-from-mismatch
                                {:index index
                                 :expected (:time state)
                                 :actual from})

            (not (> to from))
            (grammar-violation :time-not-advancing
                                {:index index :from from :to to})

            (empty? awakened)
            (grammar-violation :empty-awakened {:index index})

            :else
            {:state (assoc state :time to)}))

        (contains? terminal-tags tag)
        (let [step (nth event 1)
              time (nth event 2)]
          (cond
            (not= step (:step state))
            (grammar-violation :bad-step
                                {:index index
                                 :expected (:step state)
                                 :actual step})

            (and (some? (:time state)) (not= time (:time state)))
            (grammar-violation :terminal-time-mismatch
                                {:index index
                                 :expected (:time state)
                                 :actual time})

            :else
            {:state (assoc state
                           :time (or (:time state) time)
                           :terminated? true)}))

        :else
        (grammar-violation :unknown-event-tag {:index index :tag tag})))))

(defn- grammar-finish [state]
  (cond
    (some? (:pending state))
    (grammar-violation :missing-transition {:pending (:pending state)})

    (not (:terminated? state))
    (grammar-violation :missing-terminal {})

    :else
    {:status :pass}))

(def ^:private trace-grammar-spec
  {:id ::trace-grammar
   :initial trace-grammar-initial-state
   :step grammar-step
   :finish grammar-finish})

(defn check-trace-grammar
  "Checks the temporal grammar of a structurally validated trace `doc`,
  beyond what `jolt.sim.trace/validate-trace!` already enforces.

  Verifies: exactly one terminal event and it is the trailing event; every
  `:schedule/choose` is immediately followed by exactly one `:task/transition`
  sharing its step, time, and chosen task; no `:task/transition` appears
  without a pending choice; the shared step counter starts at 0 and advances
  by exactly one per completed transition; virtual time never reverses;
  `:time/advance` carries the current step, a `from` equal to the current
  time, a strictly greater `to`, and a non-empty `awakened`; and the terminal
  event's step and time match the tracked state. Initial virtual time is
  learned from the first post-initial event rather than assumed to be zero.

  Returns the standard `run-monitor` decision map."
  [doc]
  (run-monitor trace-grammar-spec doc))
