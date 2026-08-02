(ns jolt.sim.journal-writer
  "Synchronous single-owner writer for the framed crash-safe worker journal
  specified in proofs/journal-wal/README.md, over injected write!/flush!/
  sync! seams. No clojure.core/agent, no asynchronous queue, no file I/O,
  no EDN serialization: the caller supplies the frame sink.

  The writer owns one lockable state atom. Every complete header or record
  transition -- full-write loop, then flush, then (in :power mode) sync --
  runs under locking of that same object, so concurrent append! calls
  serialize whole transitions and underlying seam calls never overlap.

  Full-write loop (proved bounded in proofs/journal-wal): the same frame is
  passed with the exact remaining offset/length on every attempt. A
  positive partial write advances exactly :count and resets the consecutive
  EINTR count; an EINTR advances zero bytes and increments it; the first
  EINTR making the count exceed :max-eintr-retries fails the writer
  permanently. Zero/negative/oversized/noninteger counts, malformed
  results, explicit errors, and throws all fail the same absorbing way.
  Only a completely written frame advances accepted-end; a complete record
  advances sequence and the previous-CRC chain exactly once before any
  flush/sync result. Flush follows every complete frame. :process-crash
  never syncs; :power syncs only after a successful flush, and only a
  successful sync sets synced-end = accepted-end.

  Fail-closed diagnostics: status and failure state hold only narrow
  immutable values -- keyword phase/reason, scalar code, class and message
  strings, and numeric context. They never retain a Throwable, returned host
  object, frame/payload array, or seam function. The first failure is
  preserved; once failed, later appends are no-I/O no-ops.

  Jolt v0.5.17 notes: integer arithmetic is exact (bignum promotion), and
  the consecutive-EINTR counter is bounded by the documented safe range
  max-safe-eintr-retries so machine-counter overflow cannot occur."
  (:require [jolt.sim.journal :as jrn]))

;; ---- configuration constants ------------------------------------------------

(def default-max-eintr-retries
  "Default consecutive-EINTR allowance per append."
  64)

(def max-safe-eintr-retries
  "Documented inclusive upper bound for :max-eintr-retries. Values outside
  0..max-safe-eintr-retries are rejected at construction. The bound keeps
  every retry counter inside the exact signed-64-bit host range, so a later
  native adapter can pass counts to C without overflow; Jolt integer
  arithmetic is exact everywhere below it."
  9223372036854775806)

;; ---- narrow diagnostics -------------------------------------------------------

(defn- scalar?
  "True for immutable self-contained values safe to retain in public
  diagnostics: nil, keywords, strings, numbers, booleans, chars. Anything
  else -- host objects, byte arrays, throwables, functions -- is never
  retained."
  [x]
  (or (nil? x)
      (keyword? x)
      (string? x)
      (number? x)
      (boolean? x)
      (char? x)))

(defn- class-string
  "Best-effort immutable class string for diagnostics; never the object
  itself."
  [x]
  (try
    (str (class x))
    (catch :default _
      "<unknown>")))

(defn- code-diag
  "Retains a scalar seam error code as-is; represents anything else only by
  its class string."
  [code]
  (if (scalar? code)
    {:code code}
    {:code-class (class-string code)}))

(defn- throw-diag
  "Narrow diagnostics for a caught throwable at one phase: class and
  message strings only, never the throwable."
  [phase reason t]
  (let [base {:phase phase :reason reason :class (class-string t)}
        message (ex-message t)]
    (if (some? message)
      (assoc base :message message)
      base)))

;; ---- configuration ------------------------------------------------------------

(defn- checked-config
  "Validates opts into a private writer config. Returns {:config config} or
  {:failure narrow-map}; never throws. The retained :sync! defaults to an
  ok no-op in :process-crash mode only, where it is never called."
  [opts]
  (cond
    (not (map? opts))
    {:failure {:phase :config :reason :opts-must-be-map}}

    (not (and (bytes? (:run-id opts)) (= 16 (alength (:run-id opts)))))
    {:failure {:phase :config :reason :invalid-run-id}}

    (not (and (integer? (:max-payload opts))
              (<= 0 (:max-payload opts))
              (<= (:max-payload opts) 0xffffffff)))
    {:failure {:phase :config :reason :invalid-max-payload}}

    (not (#{:process-crash :power} (:durability opts)))
    {:failure {:phase :config :reason :invalid-durability}}

    (not (let [r (:max-eintr-retries opts)]
           (or (nil? r)
               (and (integer? r)
                    (<= 0 r)
                    (<= r max-safe-eintr-retries)))))
    {:failure {:phase :config :reason :invalid-max-eintr-retries}}

    (not (fn? (:write! opts)))
    {:failure {:phase :config :reason :invalid-write-seam}}

    (not (fn? (:flush! opts)))
    {:failure {:phase :config :reason :invalid-flush-seam}}

    (and (= :power (:durability opts)) (not (fn? (:sync! opts))))
    {:failure {:phase :config :reason :sync-seam-required}}

    (and (some? (:sync! opts)) (not (fn? (:sync! opts))))
    {:failure {:phase :config :reason :invalid-sync-seam}}

    :else
    {:config {:run-id (:run-id opts)
              :max-payload (:max-payload opts)
              :durability (:durability opts)
              :max-eintr-retries (let [r (:max-eintr-retries opts)]
                                   (if (nil? r)
                                     default-max-eintr-retries
                                     r))
              :write! (:write! opts)
              :flush! (:flush! opts)
              :sync! (let [s (:sync! opts)]
                       (if (nil? s)
                         (fn [] {:status :ok})
                         s))}}))

;; ---- seam drivers (fail closed, never retaining seam values) -------------------

(defn- run-write-loop
  "Drives the whole frame through write! with the bounded consecutive-EINTR
  policy. Passes the same frame with the exact remaining offset/length on
  every attempt; a positive partial write advances exactly :count and
  resets the consecutive count; an EINTR advances zero bytes and increments
  it; the first EINTR beyond max-eintr-retries fails. Returns nil once
  every byte is written, else a narrow failure map. Never throws; the
  frame, seam results, and throwables are never retained."
  [write! max-eintr-retries frame phase]
  (let [total (alength frame)]
    (loop [offset 0
           consecutive-eintrs 0]
      (if (>= offset total)
        nil
        (let [remaining (- total offset)
              result (try
                       {:value (write! frame offset remaining)}
                       (catch :default t
                         {:thrown t}))]
          (if (contains? result :thrown)
            (throw-diag phase :write-threw (:thrown result))
            (let [v (:value result)]
              (cond
                (not (map? v))
                {:phase phase :reason :malformed-write-result}

                (= :written (:status v))
                (let [c (:count v)]
                  (cond
                    (not (integer? c))
                    {:phase phase :reason :malformed-write-result}

                    (<= c 0)
                    {:phase phase
                     :reason :write-count-not-positive
                     :count c
                     :remaining remaining}

                    (> c remaining)
                    {:phase phase
                     :reason :write-count-exceeds-remaining
                     :count c
                     :remaining remaining}

                    :else
                    (recur (+ offset c) 0)))

                (= :eintr (:status v))
                (let [n (inc consecutive-eintrs)]
                  (if (> n max-eintr-retries)
                    {:phase phase
                     :reason :eintr-exhausted
                     :consecutive-eintrs n
                     :max-eintr-retries max-eintr-retries}
                    (recur offset n)))

                (= :error (:status v))
                (merge {:phase phase :reason :write-error}
                       (code-diag (:code v)))

                :else
                {:phase phase :reason :malformed-write-result}))))))))

(defn- run-barrier
  "Invokes one flush!/sync! seam fail-closed. Returns nil on
  {:status :ok}, else a narrow failure map. Never throws; the seam result
  and throwables are never retained."
  [f phase]
  (let [result (try
                 {:value (f)}
                 (catch :default t
                   {:thrown t}))]
    (if (contains? result :thrown)
      (throw-diag phase
                  (if (= phase :flush) :flush-threw :sync-threw)
                  (:thrown result))
      (let [v (:value result)]
        (cond
          (and (map? v) (= :ok (:status v)))
          nil

          (and (map? v) (= :error (:status v)))
          (merge {:phase phase
                  :reason (if (= phase :flush) :flush-error :sync-error)}
                 (code-diag (:code v)))

          :else
          {:phase phase
           :reason (if (= phase :flush)
                     :malformed-flush-result
                     :malformed-sync-result)})))))

;; ---- state transitions (all under locking of the state atom) --------------------

(defn- fail-state!
  "Transitions to the absorbing failed state, preserving the first
  failure. Must be called under the writer lock."
  [state failure]
  (swap! state
         (fn [s]
           (if (:failure s)
             (assoc s :health :failed)
             (assoc s :health :failed :failure failure))))
  nil)

(defn- run-barriers!
  "Flush follows every complete frame. Power mode syncs only after a
  successful flush, and only a successful sync sets synced-end =
  accepted-end. Process-crash mode never syncs. Any barrier failure is
  absorbing and prevents later I/O. Under the writer lock."
  [state config]
  (let [flush-failure (run-barrier (:flush! config) :flush)]
    (if flush-failure
      (fail-state! state flush-failure)
      (when (= :power (:durability config))
        (let [sync-failure (run-barrier (:sync! config) :sync)]
          (if sync-failure
            (fail-state! state sync-failure)
            (swap! state assoc :synced-end (:accepted-end @state))))))))

(defn- run-header-transition!
  "Encodes the segment header and writes it with the full barrier policy.
  Only a completely written header advances accepted-end to the header
  boundary and seeds the previous-CRC chain. Under the writer lock."
  [state config]
  (let [encoded (try
                  {:value (jrn/encode-header {:run-id (:run-id config)
                                              :max-payload (:max-payload config)})}
                  (catch :default t
                    {:thrown t}))]
    (if (contains? encoded :thrown)
      (fail-state! state
                   (throw-diag :header :header-encode-threw (:thrown encoded)))
      (let [frame (:value encoded)
            ;; Capture chain metadata before exposing the mutable frame to
            ;; the injected seam. A conforming seam treats the frame as a
            ;; borrowed read-only value, but state must not silently depend
            ;; on its contents still being intact after the final write.
            frame-crc32c (jrn/crc32c frame 0 (- jrn/header-size 4))
            write-failure (run-write-loop (:write! config)
                                          (:max-eintr-retries config)
                                          frame
                                          :header)]
        (if write-failure
          (fail-state! state write-failure)
          (do
            (swap! state assoc
                   :previous-crc32c frame-crc32c
                   :accepted-end jrn/header-size)
            (run-barriers! state config)))))))

(defn- encode-failure
  "Narrow diagnostics for a codec throw: the stable keyword reason plus
  class/message strings. The ex-data map and the throwable are never
  retained."
  [t]
  (let [data (ex-data t)
        reason (when (and (map? data) (keyword? (:reason data)))
                 (:reason data))
        base {:phase :encode
              :reason (or reason :record-encode-threw)
              :class (class-string t)}
        message (ex-message t)]
    (if (some? message)
      (assoc base :message message)
      base)))

(defn- run-record-transition!
  "Validates and encodes one record, writes it with the full-write loop,
  advances sequence/previous-CRC/accepted-end exactly once, then runs the
  barrier policy. Any validation, encode, or write failure is absorbing and
  performs no further I/O. Under the writer lock."
  [state config record]
  (cond
    (not (map? record))
    (fail-state! state {:phase :encode :reason :record-must-be-map})

    (not (bytes? (:payload record)))
    (fail-state! state {:phase :encode :reason :payload-must-be-byte-array})

    (> (alength (:payload record)) (:max-payload config))
    (fail-state! state {:phase :encode
                        :reason :payload-exceeds-max-payload
                        :payload-length (alength (:payload record))
                        :max-payload (:max-payload config)})

    :else
    (let [s @state
          encoded (try
                    {:value (jrn/encode-record
                             {:kind (:kind record)
                              :flags (:flags record)
                              :sequence (:sequence s)
                              :previous-crc32c (:previous-crc32c s)
                              :payload (:payload record)})}
                    (catch :default t
                      {:thrown t}))]
      (if (contains? encoded :thrown)
        (fail-state! state (encode-failure (:thrown encoded)))
        (let [frame (:value encoded)
              ;; Snapshot both values before the mutable frame crosses the
              ;; seam boundary; neither accepted accounting nor the CRC link
              ;; may depend on post-write buffer contents.
              frame-length (alength frame)
              frame-crc32c (jrn/crc32c frame 0 (- frame-length 4))
              write-failure (run-write-loop (:write! config)
                                            (:max-eintr-retries config)
                                            frame
                                            :record)]
          (if write-failure
            (fail-state! state write-failure)
            (do
              (swap! state
                     (fn [s]
                       (assoc s
                              :sequence (inc (:sequence s))
                              :previous-crc32c frame-crc32c
                              :accepted-end (+ (:accepted-end s) frame-length))))
              (run-barriers! state config))))))))

;; ---- public API ------------------------------------------------------------------

(defn- status-of
  "Immutable status snapshot: scalar values and the narrow failure map
  only. Never a seam function, byte array, throwable, or host object."
  [s]
  {:health (:health s)
   :failure (:failure s)
   :sequence (:sequence s)
   :previous-crc32c (:previous-crc32c s)
   :accepted-end (:accepted-end s)
   :synced-end (:synced-end s)
   :durability (:durability s)})

(defn make-writer
  "Creates a synchronous single-owner journal writer and immediately
  encodes and writes the segment header under the writer lock. Never
  throws: any configuration, header encode, write, flush, or sync failure
  returns a writer in the absorbing :failed state.

  opts:
    :run-id             required 16-byte byte-array
    :max-payload        required u32 maximum payload length
    :durability         required :process-crash or :power
    :max-eintr-retries  consecutive-EINTR allowance, integer in
                        0..max-safe-eintr-retries, default 64
    :write!             (fn [frame offset remaining] result) where result is
                        {:status :written :count positive-integer<=remaining}
                        | {:status :eintr} | {:status :error :code scalar}
    :flush!             (fn [] {:status :ok} | {:status :error :code scalar})
    :sync!              as flush!; required in :power mode, defaults to an
                        ok no-op in :process-crash (never called there)

  The frame passed to :write! is borrowed, read-only storage. A conforming
  seam neither mutates nor retains it and does not reenter this writer. Chain metadata
  is nevertheless captured before the first seam call so post-write buffer
  mutation cannot silently change writer state. Thrown exceptions and
  malformed seam results fail closed."
  [opts]
  (let [{:keys [config failure]}
        (try
          (checked-config opts)
          (catch :default t
            {:failure (throw-diag :config :config-validation-threw t)}))
        durability (if config
                     (:durability config)
                     (when (and (map? opts)
                                (#{:process-crash :power} (:durability opts)))
                       (:durability opts)))
        state (atom {:health (if failure :failed :healthy)
                     :failure failure
                     :sequence 0
                     :previous-crc32c 0
                     :accepted-end 0
                     :synced-end 0
                     :durability durability})]
    (when config
      (locking state
        (run-header-transition! state config)))
    {:state state
     ;; The run id is needed only to construct the header. Do not retain the
     ;; caller-owned byte array for the lifetime of the writer.
     :config (when config (dissoc config :run-id))}))

(defn append!
  "Appends one framed record {:kind :flags :payload} and returns the
  current status snapshot. For a writer returned by make-writer, seam and
  validation failures do not escape. The complete write/flush/(sync)
  transition runs under the writer lock, so concurrent appends serialize
  whole transitions. A failed writer performs no I/O and returns its
  preserved first failure; an invalid record fails the writer without I/O."
  [writer record]
  (let [state (:state writer)
        config (:config writer)]
    (locking state
      (when (and config (= :healthy (:health @state)))
        (run-record-transition! state config record))
      (status-of @state))))

(defn writer-status
  "Returns the immutable status snapshot:
    {:health :healthy|:failed
     :failure nil|narrow-map
     :sequence next-sequence
     :previous-crc32c last-complete-frame-crc
     :accepted-end byte-offset
     :synced-end byte-offset
     :durability mode}
  Contains no seam functions, byte arrays, throwables, or host objects. For a
  writer returned by make-writer, taking this snapshot does not throw."
  [writer]
  (let [state (:state writer)]
    (locking state
      (status-of @state))))
