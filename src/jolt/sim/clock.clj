(ns jolt.sim.clock
  "Deterministic virtual monotonic time and exactly-once alarm publication.

  A clock is an explicit simulator value. `controller` adapts it to
  jolt.sim.runtime's ordinary clock callback, while modeled blocking resources
  can register alarms against the same value. Time never advances implicitly:
  a scheduler, scenario, or test calls `advance-to!`/`advance-by!` only when its
  runnable-task policy permits virtual time to move.

  Alarm registration, cancellation, and time advancement share one clock lock.
  Due alarms are removed under that lock and their private promises are
  delivered afterward. A returned alarm token may therefore be cancelled
  idempotently, but cancellation cannot revoke a delivery already committed by
  registration or advancement. Snapshots contain no promises or host objects.")

(def ^:private clock-type ::virtual-clock)
(def ^:private alarm-token-type ::alarm-token)
(def ^:private clock-keys #{:identity :lock :state :type})
(def ^:private state-keys #{:now-nanos :next-alarm-id :alarms})
(def ^:private alarm-keys #{:deadline-nanos :signal})
(def ^:private token-keys #{:clock-identity :deadline-nanos :id :signal :type})
(def ^:private mono-descriptor {:kind :clock :operation :mono-nanos})

(defn- fail! [reason message detail]
  (throw
   (ex-info message
            {:type :jolt.sim.clock/invalid-value
             :reason reason
             :detail detail})))

(defn virtual-clock
  "Creates a virtual monotonic clock at exact integer `initial-nanos` (zero by
  default)."
  ([]
   (virtual-clock 0))
  ([initial-nanos]
   (when-not (integer? initial-nanos)
     (fail! :invalid-initial-time
            "virtual clock initial time must be exact integer nanoseconds"
            {:initial-nanos initial-nanos}))
   {:identity (Object.)
    :lock (Object.)
    :state (atom {:now-nanos initial-nanos
                  :next-alarm-id 0
                  :alarms {}})
    :type clock-type}))

(defn validate-clock
  "Validates and returns a live value produced by [[virtual-clock]]."
  [clock]
  (when-not (and (map? clock)
                 (= clock-keys (set (keys clock)))
                 (= clock-type (:type clock))
                 (some? (:identity clock))
                 (some? (:lock clock)))
    (fail! :not-a-virtual-clock
           "value is not a jolt.sim.clock virtual clock"
           {:provided-class (str (class clock))}))
  (let [state
        (try
          (locking (:lock clock) @(:state clock))
          (catch :default _
            (fail! :invalid-state-owner
                   "virtual clock contains non-derefable state"
                   {})))]
    (when-not
     (and (map? state)
          (= state-keys (set (keys state)))
          (integer? (:now-nanos state))
          (integer? (:next-alarm-id state))
          (not (neg? (:next-alarm-id state)))
          (map? (:alarms state))
          (every?
           (fn [[id alarm]]
             (and (integer? id)
                  (not (neg? id))
                  (< id (:next-alarm-id state))
                  (map? alarm)
                  (= alarm-keys (set (keys alarm)))
                  (integer? (:deadline-nanos alarm))
                  (> (:deadline-nanos alarm) (:now-nanos state))
                  (some? (:signal alarm))))
           (:alarms state)))
      (fail! :invalid-state
             "virtual clock contains invalid alarm state"
             {})))
  clock)

(defn now-nanos
  "Returns the clock's current exact integer monotonic time."
  [clock]
  (validate-clock clock)
  (locking (:lock clock)
    (:now-nanos @(:state clock))))

(defn controller
  "Returns the exact arity-2 controller accepted by jolt.sim.runtime :clock."
  [clock]
  (validate-clock clock)
  (fn virtual-clock-controller [descriptor _proceed]
    (when-not (= mono-descriptor descriptor)
      (fail! :invalid-clock-descriptor
             "virtual clock controller received an unknown descriptor"
             {:descriptor descriptor}))
    (now-nanos clock)))

(defn register-alarm!
  "Atomically registers one alarm for absolute `deadline-nanos` and returns an
  opaque token containing its private promise under :signal. If the deadline
  is already due, the token is never retained and its signal is delivered
  exactly once before this call returns."
  [clock deadline-nanos]
  (validate-clock clock)
  (when-not (integer? deadline-nanos)
    (fail! :invalid-deadline
           "virtual clock alarm deadline must be exact integer nanoseconds"
           {:deadline-nanos deadline-nanos}))
  (let [signal (promise)
        result
        (locking (:lock clock)
          (let [state @(:state clock)
                id (:next-alarm-id state)
                due? (<= deadline-nanos (:now-nanos state))
                token {:clock-identity (:identity clock)
                       :deadline-nanos deadline-nanos
                       :id id
                       :signal signal
                       :type alarm-token-type}
                next-state
                (cond-> (update state :next-alarm-id inc)
                  (not due?)
                  (assoc-in [:alarms id]
                            {:deadline-nanos deadline-nanos
                             :signal signal}))]
            (reset! (:state clock) next-state)
            {:due? due? :token token}))]
    (when (:due? result)
      (deliver signal :deadline))
    (:token result)))

(defn- validate-token! [clock token]
  (when-not (and (map? token)
                 (= token-keys (set (keys token)))
                 (= alarm-token-type (:type token))
                 (identical? (:identity clock) (:clock-identity token))
                 (integer? (:id token))
                 (not (neg? (:id token)))
                 (integer? (:deadline-nanos token))
                 (some? (:signal token)))
    (fail! :invalid-alarm-token
           "alarm token does not belong to this virtual clock"
           {}))
  token)

(defn cancel-alarm!
  "Removes a pending alarm and returns true. Returns false when it was already
  cancelled, delivered, or committed for delivery. Cancellation is
  idempotent and never delivers the alarm signal."
  [clock token]
  (validate-clock clock)
  (validate-token! clock token)
  (locking (:lock clock)
    (let [id (:id token)
          pending? (contains? (:alarms @(:state clock)) id)]
      (when pending?
        (swap! (:state clock) update :alarms dissoc id))
      pending?)))

(defn next-deadline
  "Returns the earliest pending absolute alarm deadline, or nil."
  [clock]
  (validate-clock clock)
  (locking (:lock clock)
    (when-let [alarms (seq (vals (:alarms @(:state clock))))]
      (reduce min (map :deadline-nanos alarms)))))

(defn- commit-advance! [clock target-fn]
  (let [due
        (locking (:lock clock)
          (let [state @(:state clock)
                now (:now-nanos state)
                target-nanos (target-fn now)]
            (when (< target-nanos now)
              (fail! :clock-moved-backward
                     "virtual clock cannot move backward"
                     {:now-nanos now :target-nanos target-nanos}))
            (let [due
                  (->> (:alarms state)
                       (keep (fn [[id alarm]]
                               (when (<= (:deadline-nanos alarm) target-nanos)
                                 (assoc alarm :id id))))
                       (sort-by (juxt :deadline-nanos :id))
                       vec)
                  due-ids (mapv :id due)]
              (reset! (:state clock)
                      (-> state
                          (assoc :now-nanos target-nanos)
                          (update :alarms #(apply dissoc % due-ids))))
              due)))]
    (doseq [alarm due]
      (deliver (:signal alarm) :deadline))
    (count due)))

(defn advance-to!
  "Monotonically advances to exact integer `target-nanos`, removes every due
  alarm atomically, delivers those alarms in [deadline,id] order, and returns
  the number delivered. Backward movement fails without changing state."
  [clock target-nanos]
  (validate-clock clock)
  (when-not (integer? target-nanos)
    (fail! :invalid-target-time
           "virtual clock target time must be exact integer nanoseconds"
           {:target-nanos target-nanos}))
  (commit-advance! clock (fn [_now] target-nanos)))

(defn advance-by!
  "Advances by non-negative exact integer `delta-nanos`."
  [clock delta-nanos]
  (when-not (and (integer? delta-nanos) (not (neg? delta-nanos)))
    (fail! :invalid-time-delta
           "virtual clock delta must be a non-negative exact integer"
           {:delta-nanos delta-nanos}))
  (validate-clock clock)
  (commit-advance! clock (fn [now] (+ now delta-nanos))))

(defn snapshot
  "Returns immutable, replay-safe time/alarm evidence without promises or host
  identities."
  [clock]
  (validate-clock clock)
  (locking (:lock clock)
    (let [state @(:state clock)]
      {:now-nanos (:now-nanos state)
       :next-alarm-id (:next-alarm-id state)
       :alarms
       (->> (:alarms state)
            (map (fn [[id alarm]]
                   {:id id :deadline-nanos (:deadline-nanos alarm)}))
            (sort-by (juxt :deadline-nanos :id))
            vec)})))
