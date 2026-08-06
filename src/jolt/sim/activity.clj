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
  Ordinary execution has no observer, making emit! an immediate no-op.

  recover-page is the strictly read-only recovery side: it consumes one
  bounded read-result (jolt.sim.journal-file/read-bounded-path or
  read-bounded-image) plus a record-ordinal cursor and returns one bounded
  page of accepted events with a closed key set. It accepts only physical
  kind 1 flags 0 records whose payload is exactly one canonical EDN v1
  event, preserves the valid prefix at the first physical or semantic
  corruption, never resynchronizes, and never returns bytes, paths, or
  Throwables. A cap-truncated read never reports :complete."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.sim.journal :as journal]
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

(def ^:private bounded-failure-keys
  #{:phase :reason :class
    :payload-length :max-payload
    :count :remaining
    :consecutive-eintrs :max-eintr-retries})

(defn- natural-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- valid-bounded-failure? [failure]
  (and (map? failure)
       (seq failure)
       (every? bounded-failure-keys (keys failure))
       (keyword? (:phase failure))
       (keyword? (:reason failure))
       (or (not (contains? failure :class))
           (string? (:class failure)))
       (every? (fn [key]
                 (or (not (contains? failure key))
                     (natural-integer? (get failure key))))
               [:payload-length :max-payload :count :remaining
                :consecutive-eintrs :max-eintr-retries])))

(defn valid-observer-status?
  "True only for the exact bounded immutable observer-status contract shared
  by the worker result, retained activity view, REPL, and UI adapters."
  [status]
  (and (map? status)
       (= #{:health :failure :sequence :accepted :capped? :durability :closed?}
          (set (keys status)))
       (contains? #{:healthy :failed} (:health status))
       (if (= :failed (:health status))
         (valid-bounded-failure? (:failure status))
         (nil? (:failure status)))
       (natural-integer? (:sequence status))
       (<= (:sequence status) max-records)
       (natural-integer? (:accepted status))
       (<= (:accepted status) (:sequence status))
       (boolean? (:capped? status))
       (= :process-crash (:durability status))
       (boolean? (:closed? status))))

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

;; ---- recovery paging -----------------------------------------------------------

(def page-version
  "Closed recover-page output version."
  1)

(def max-page-events
  "Maximum number of events in one recovery page."
  32)

(def max-page-payload-bytes
  "Maximum total canonical payload bytes in one recovery page. Every
  accepted payload is at most max-payload-bytes, which is strictly below
  this budget, so the byte budget can bind before the event count but can
  never reject the first event of a page."
  131072)

(defn- invalid-cursor!
  "Throws typed ex-info for an out-of-contract cursor. ex-data carries only
  bounded scalar data; a non-integer cursor value is never retained."
  [reason data]
  (throw (ex-info "recover-page cursor is invalid"
                  (assoc data
                         :type :jolt.sim.activity/invalid-cursor
                         :reason reason))))

(defn- invalid-read-result!
  "Throws typed ex-info for a malformed read-result. The read-result itself
  is never retained in ex-data because it may hold byte arrays."
  [reason]
  (throw (ex-info "recover-page read-result is invalid"
                  {:type :jolt.sim.activity/invalid-read-result
                   :reason reason})))

(defn- checked-cursor
  "Validates the record-ordinal cursor shape; throws typed ex-info on a
  non-integer or negative cursor."
  [cursor]
  (cond
    (not (integer? cursor))
    (invalid-cursor! :non-integer-cursor {})

    (neg? cursor)
    (invalid-cursor! :negative-cursor {:cursor cursor})

    :else cursor))

(defn- checked-read-result
  "Validates the bounded read-result contract; throws typed ex-info on any
  deviation. Only the shape is inspected, never the byte content."
  [read-result]
  (when-not (map? read-result)
    (invalid-read-result! :read-result-must-be-map))
  (let [status (:status read-result)]
    (cond
      (= :error status)
      (when-not (and (keyword? (:reason read-result))
                     (or (not (contains? read-result :class))
                         (nil? (:class read-result))
                         (string? (:class read-result))))
        (invalid-read-result! :invalid-error-reason))

      (= :ok status)
      (when-not (and (bytes? (:image read-result))
                     (integer? (:bytes-read read-result))
                     (= (:bytes-read read-result)
                        (alength (:image read-result)))
                     (<= 0 (:bytes-read read-result) max-image-bytes)
                     (boolean? (:truncated? read-result)))
        (invalid-read-result! :invalid-ok-read-result))

      :else
      (invalid-read-result! :invalid-status)))
  read-result)

(defn- payload-bytes=
  "Content equality for byte arrays with per-byte unsigned normalization.
  Ordinary = on byte arrays is reference equality, and raw aget sign
  depends on array provenance, so every byte is compared with
  (bit-and b 0xff)."
  [a b]
  (and (= (alength a) (alength b))
       (loop [i 0]
         (cond
           (= i (alength a)) true
           (not= (bit-and (aget a i) 0xff)
                 (bit-and (aget b i) 0xff)) false
           :else (recur (inc i))))))

(defn- accept-payload
  "Returns {:event event} when payload is exactly the canonical EDN
  encoding of one closed v1 event, else {:reason keyword}. Decoding
  UTF-8 lossily, parsing fewer or more than one form, an out-of-shape
  value, and any byte-level deviation from trace/canonical-edn are all
  rejected; the canonical byte round-trip is exact, so trailing forms,
  comments, noncanonical whitespace or key order, and lossy decodings
  cannot pass."
  [payload]
  (let [decoded (try
                  {:text (String. payload "UTF-8")}
                  (catch :default _
                    {:reason :invalid-event-encoding}))]
    (if-let [reason (:reason decoded)]
      {:reason reason}
      (let [text (:text decoded)]
        (if (not (payload-bytes= payload (.getBytes text "UTF-8")))
          {:reason :invalid-event-encoding}
          (if (str/blank? text)
          {:reason :invalid-event-encoding}
          (let [parsed (try
                         {:event (edn/read-string text)}
                         (catch :default _
                           {:reason :invalid-event-encoding}))]
            (if-let [reason (:reason parsed)]
              {:reason reason}
              (let [event (:event parsed)]
                (if-not (valid-shape? event)
                  {:reason :invalid-event-shape}
                  (let [canonical (try
                                    {:text (trace/canonical-edn event)}
                                    (catch :default _
                                      {:reason :non-canonical-event}))]
                    (if-let [reason (:reason canonical)]
                      {:reason reason}
                      (if (payload-bytes= payload
                                          (.getBytes (:text canonical) "UTF-8"))
                        {:event event}
                        {:reason :non-canonical-event})))))))))))))

(defn- accept-record
  "Physical acceptance gate: only kind 1 flags 0 records carry activity
  events. Returns {:event event} or {:reason keyword}."
  [record]
  (cond
    (not= record-kind (:kind record)) {:reason :unexpected-kind}
    (not= record-flags (:flags record)) {:reason :unexpected-flags}
    :else (accept-payload (:payload record))))

(defn- scan-events
  "Walks the physically valid record prefix, accepting activity events
  until the first semantic corruption and never resynchronizing. Returns
  {:accepted [{:sequence :event :payload-bytes} ...] :halt nil} when every
  physically valid record is an accepted event, or {:accepted prefix :halt
  {:reason :sequence :offset}} describing the first unaccepted record."
  [records]
  (loop [rs (seq records)
         accepted []]
    (if-let [record (first rs)]
      (if (>= (count accepted) max-records)
        {:accepted accepted
         :halt {:reason :record-quota-exceeded
                :sequence (:sequence record)
                :offset (:offset record)}}
        (let [verdict (accept-record record)]
          (if-let [reason (:reason verdict)]
            {:accepted accepted
             :halt {:reason reason
                    :sequence (:sequence record)
                    :offset (:offset record)}}
            (recur (next rs)
                   (conj accepted
                         {:sequence (:sequence record)
                          :event (:event verdict)
                          :payload-bytes (alength (:payload record))})))))
      {:accepted accepted :halt nil})))

(defn- failed-read-page
  "The absorbing page for a read-level failure: no journal bytes were
  available, so recovery failed with the bounded read reason. The bounded
  :class string from the read diagnostic is propagated; it is never a
  Throwable or a message."
  [cursor read-result]
  {:version page-version
   :cursor cursor
   :next-cursor cursor
   :accepted-count 0
   :remaining? false
   :events []
   :recovery {:status :failed
              :reason (:reason read-result)
              :sequence 0
              :last-good-offset 0
              :raw-tail-bytes 0
              :image-truncated? false
              :class (:class read-result)}})

(defn recover-page
  "Recovers one bounded page of activity events from a single bounded
  read-result (jolt.sim.journal-file/read-bounded-path or
  read-bounded-image) plus a record-ordinal cursor. Strictly read-only:
  no I/O, no locking, no allocation beyond the recovered page, and no
  bytes, paths, or Throwables in the output.

  cursor is a zero-based ordinal into the accepted-event prefix; because
  the journal enforces contiguous sequences from zero, ordinals and
  sequences coincide. A non-integer, negative, or beyond-the-prefix cursor
  fails closed with typed ex-info :jolt.sim.activity/invalid-cursor; a
  malformed read-result fails closed with typed ex-info
  :jolt.sim.activity/invalid-read-result.

  Acceptance is strict: the journal codec first recovers the physically
  valid record prefix (header plus CRC-chained records, stopping at the
  first invalid or incomplete frame), then each record must carry physical
  kind 1 flags 0 and a payload whose UTF-8 decodes and parses as exactly
  one canonical EDN [namespaced-keyword nil nil map] event -- the payload
  must equal the trace/canonical-edn encoding of the parsed event
  byte-for-byte, so multiple forms, trailing content, noncanonical
  formatting, out-of-domain values, and lossy UTF-8 are all corruption.
  The valid event prefix is preserved at the first physical or semantic
  corruption and recovery never resynchronizes.

  Page limits: at most max-page-events (32) events and at most
  max-page-payload-bytes (131072) total canonical payload bytes per page.

  Returns a map with exactly the keys:
    :version        page format version (1)
    :cursor         the validated input cursor
    :next-cursor    ordinal one past the last event in this page
    :accepted-count number of events in the complete semantic valid prefix
    :remaining?     true when accepted events exist beyond next-cursor
    :events         vector of {:sequence n :event v}, in sequence order
    :recovery       {:status :complete|:partial|:failed
                     :reason keyword|nil
                     :sequence count of accepted records
                     :last-good-offset byte offset where recovery stopped
                     :raw-tail-bytes count of unaccepted image bytes
                     :image-truncated? true when the read hit its byte cap
                     :class bounded class string|nil}

  :complete means the scan reached the image end with every record
  accepted and no read truncation. :partial preserves a valid header (and
  possibly empty event prefix) but stopped at the first physical or
  semantic corruption, or the read was cap-truncated: a truncated read
  never looks complete even when the image ends exactly on a record
  boundary (:reason :input-truncated). :failed means no valid journal
  prefix exists at all: a read-level error, or a header the codec
  rejected. :raw-tail-bytes is a count, never the bytes themselves.
  :class is always present and bounded: the class string propagated from
  a read-level diagnostic, else nil -- never a Throwable, a message, or
  a path."
  [read-result cursor]
  (checked-read-result read-result)
  (checked-cursor cursor)
  (if (= :error (:status read-result))
    (do
      (when (pos? cursor)
        (invalid-cursor! :cursor-beyond-recovery {:cursor cursor :accepted 0}))
      (failed-read-page cursor read-result))
    (let [image (:image read-result)
          image-length (alength image)
          truncated? (true? (:truncated? read-result))
          recovered (journal/recover image {:max-payload max-payload-bytes})
          scan (scan-events (:records recovered))
          accepted (:accepted scan)
          total (count accepted)
          _ (when (> cursor total)
              (invalid-cursor! :cursor-beyond-recovery
                               {:cursor cursor :accepted total}))
          recovery
          (cond
            (:halt scan)
            {:status :partial
             :reason (get-in scan [:halt :reason])
             :sequence total
             :last-good-offset (get-in scan [:halt :offset])
             :raw-tail-bytes (- image-length (get-in scan [:halt :offset]))
             :image-truncated? truncated?
             :class nil}

            (some? (:failure-reason recovered))
            {:status (if (pos? (:last-good-offset recovered))
                       :partial
                       :failed)
             :reason (:failure-reason recovered)
             :sequence total
             :last-good-offset (:last-good-offset recovered)
             :raw-tail-bytes (alength (:raw-tail recovered))
             :image-truncated? truncated?
             :class nil}

            truncated?
            {:status :partial
             :reason :input-truncated
             :sequence total
             :last-good-offset image-length
             :raw-tail-bytes 0
             :image-truncated? true
             :class nil}

            :else
            {:status :complete
             :reason nil
             :sequence total
             :last-good-offset image-length
             :raw-tail-bytes 0
             :image-truncated? false
             :class nil})
          page (loop [index cursor
                      events []
                      bytes 0]
                 (if (and (< index total)
                          (< (count events) max-page-events))
                   (let [entry (nth accepted index)
                         next-bytes (+ bytes (:payload-bytes entry))]
                     (if (<= next-bytes max-page-payload-bytes)
                       (recur (inc index)
                              (conj events {:sequence (:sequence entry)
                                            :event (:event entry)})
                              next-bytes)
                       {:events events :next-cursor index}))
                   {:events events :next-cursor index}))
          next-cursor (:next-cursor page)]
      {:version page-version
       :cursor cursor
       :next-cursor next-cursor
       :accepted-count total
       :remaining? (< next-cursor total)
       :events (:events page)
       :recovery recovery})))
