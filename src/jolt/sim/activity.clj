(ns jolt.sim.activity
  "Bounded opt-in semantic activity over the process-crash journal adapter.

  Events have one closed v1 shape: [tag nil nil data]. tag is a namespaced
  keyword, the two reserved positions are nil, and data is a plain map in the
  stable EDN domain accepted by jolt.sim.trace/canonical-edn. Raw byte arrays
  are rejected; emit bounded descriptive data rather than borrowed buffers or
  secrets. Accepted records use journal kind 1 and flags 0.

  An observer owns the lock that serializes validation, quota reservation, and
  append, so explicit cross-thread sharing cannot exceed 256 records. Invalid
  events, unsupported values, oversized payloads, and journal failures put the
  observer into one absorbing, path-free failed state. Calls after failure do
  no further encoding or I/O. Reaching the record quota is a healthy capped
  state rather than a failure.

  Jolt futures inherit a snapshot of dynamic bindings. A future started inside
  call-with-observer therefore inherits the observer automatically. The caller
  owns lifecycle and must join inherited work before close-observer!; callers
  may also capture current-observer and bind it explicitly in other threads.
  Ordinary execution has no observer, making emit! an immediate no-op."
  (:require [jolt.sim.journal :as journal]
            [jolt.sim.journal-file :as jf]
            [jolt.sim.trace :as trace]))

(def max-payload-bytes 16384)
(def max-records 256)

(def max-image-bytes
  "Maximum valid v1 activity journal image, including framing."
  (+ journal/header-size
     (* max-records (+ journal/record-overhead max-payload-bytes))))

(def ^:private record-kind 1)
(def ^:private record-flags 0)

(def ^:dynamic *observer* nil)

(defn current-observer [] *observer*)

(defn- contains-bytes?
  [value]
  (cond
    (bytes? value) true
    (map? value) (boolean (some (fn [[k v]]
                                  (or (contains-bytes? k)
                                      (contains-bytes? v)))
                                value))
    (or (set? value) (sequential? value))
    (boolean (some contains-bytes? value))
    :else false))

(defn- valid-shape?
  [event]
  (and (vector? event)
       (= 4 (count event))
       (keyword? (nth event 0))
       (some? (namespace (nth event 0)))
       (nil? (nth event 1))
       (nil? (nth event 2))
       (map? (nth event 3))))

(defn- encode-event
  [event]
  (when (contains-bytes? event)
    (throw (ex-info "Raw byte arrays are outside the activity event domain"
                    {:type ::unsupported-event-data})))
  (.getBytes (trace/canonical-edn event) "UTF-8"))

(defn- fail-state!
  [state failure]
  (swap! state
         (fn [s]
           (if (:failure s)
             s
             (assoc s :failure failure))))
  nil)

(defn- adapter-failure!
  [state adapter-status]
  (when (= :failed (:health adapter-status))
    (fail-state! state (:failure adapter-status))))

(defn open-observer!
  "Opens an observer in a single-writer, parent-owned run directory.
  Returns a handle even when the underlying open fails; observer-status then
  reports the absorbing bounded failure."
  [opts]
  (let [adapter (jf/open-process-crash-writer!
                 {:path (:path opts)
                  :run-id (:run-id opts)
                  :max-payload max-payload-bytes
                  :max-image-bytes max-image-bytes})
        adapter-status (jf/writer-status adapter)]
    {:adapter adapter
     :state (atom {:accepted (:sequence adapter-status)
                   :capped? false
                   :closed? (:closed? adapter-status)
                   :failure (when (= :failed (:health adapter-status))
                              (:failure adapter-status))})}))

(defn- status-under-lock
  [observer]
  (let [state (:state observer)
        local @state
        adapter-status (jf/writer-status (:adapter observer))
        failure (or (:failure local) (:failure adapter-status))]
    {:health (if failure :failed :healthy)
     :failure failure
     :sequence (:sequence adapter-status)
     :accepted (:accepted local)
     :capped? (:capped? local)
     :durability (:durability adapter-status)
     :closed? (or (:closed? local) (:closed? adapter-status))}))

(defn observer-status
  "Returns bounded immutable observer state with no path, event, function,
  byte array, observer, adapter, or Throwable."
  [observer]
  (let [state (:state observer)]
    (locking state
      (status-under-lock observer))))

(defn close-observer!
  "Closes the journal idempotently and returns the resulting observer status."
  [observer]
  (let [state (:state observer)]
    (locking state
      (let [adapter-status (jf/close! (:adapter observer))]
        (swap! state assoc :closed? true)
        (adapter-failure! state adapter-status)
        (status-under-lock observer)))))

(defn emit!
  "Emits one closed v1 event vector under the current observer. Always returns
  nil. With no observer it performs no validation, encoding, or locking."
  [event]
  (when-let [observer *observer*]
    (let [state (:state observer)]
      (locking state
        (let [{:keys [failure capped? closed? accepted]} @state]
          (cond
            failure nil
            capped? nil
            closed? (fail-state! state {:phase :emit :reason :observer-closed})
            (not (valid-shape? event))
            (fail-state! state {:phase :schema :reason :invalid-event})
            :else
            (let [encoded (try
                            {:payload (encode-event event)}
                            (catch :default _
                              {:failure {:phase :encode
                                         :reason :unsupported-event-data}}))]
              (if-let [encode-failure (:failure encoded)]
                (fail-state! state encode-failure)
                (let [payload (:payload encoded)
                      payload-length (alength payload)]
                  (cond
                    (> payload-length max-payload-bytes)
                    (fail-state! state
                                 {:phase :encode
                                  :reason :payload-exceeds-max-payload
                                  :payload-length payload-length
                                  :max-payload max-payload-bytes})

                    (>= accepted max-records)
                    (swap! state assoc :capped? true)

                    :else
                    (let [adapter-status
                          (jf/append! (:adapter observer)
                                      {:kind record-kind
                                       :flags record-flags
                                       :payload payload})]
                      (if (= :failed (:health adapter-status))
                        (adapter-failure! state adapter-status)
                        (let [next-accepted (inc accepted)]
                          (swap! state assoc
                                 :accepted next-accepted
                                 :capped? (>= next-accepted max-records))))))))))))))
  nil)

(defn call-with-observer
  "Runs zero-argument f with observer dynamically bound. Jolt futures created
  by f inherit this binding; join them before closing the observer. Returns
  f's value and preserves f's exception unchanged. Observer lifecycle remains
  explicitly caller-owned."
  [observer f]
  (binding [*observer* observer]
    (f)))
