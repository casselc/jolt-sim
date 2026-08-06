(ns jolt.sim.journal-file
  "Process-crash diagnostic file adapter for the framed worker journal:
  the existing synchronous writer (jolt.sim.journal-writer) over a real
  java.io.FileOutputStream.

  Contract (narrowed, honest): the caller supplies a trusted fresh path in
  a parent-owned run directory. The caller guarantees single-writer
  ownership of that directory and target-path namespace: no concurrent
  creator, deleter, or replacer may operate on the target between the
  refusal check and descriptor open. Opening performs a sequential
  best-effort refusal -- an .exists check followed by
  java.io.File/createNewFile. Jolt's createNewFile shim is itself
  check-then-truncate, so neither step is atomic. An existing target is
  refused (:target-exists) and is not touched under the ownership
  precondition. This is not a race guarantee: Jolt exposes no
  single-descriptor exclusive open (probed: java.nio.file.Files/
  newOutputStream ignores CREATE_NEW and truncates an existing target;
  RandomAccessFile and FileChannel are unavailable), so under concurrent
  concurrent creation/deletion/replacement the descriptor opened may not
  be the file created. Under the trusted-parent precondition the adapter
  never overwrites existing forensic data.

  The adapter adapts the real Java file output to the existing synchronous
  writer with all-or-throw complete writes: java.io.FileOutputStream.write
  writes every requested byte or throws, so the :write! seam reports a
  complete {:status :written :count remaining} and the writer's full-write
  loop never observes a partial count from this adapter. Every accepted
  frame (header and each record) is flushed through the :flush! seam.
  Durability is :process-crash only: this layer claims survival from
  worker termination, makes no fsync/power-loss/kernel-crash/ARIES claim,
  and never calls sync.

  Fail-closed diagnostics: status and failure maps hold only narrow
  immutable values -- keyword phase/reason, scalar code, class strings,
  and numeric context. Exception messages are omitted entirely: raw
  exception messages can embed the target path and are unbounded. No
  diagnostic ever retains a Throwable, stream, byte array, seam function,
  or path. The first failure is preserved: open refusal, writer
  (encode/write/flush) failure, and close failure are surfaced in that
  precedence order, and append!/close! never throw, so this layer can
  never mask an earlier application exception.

  read-bounded-image caps the bytes read before jolt.sim.journal/recover
  because recover retains the raw tail: an unbounded read of a corrupt or
  hostile file would retain an unbounded tail. Caps (configured at open
  and per-call overrides) are validated against the documented inclusive
  bound max-safe-image-bytes; allocation, read-loop, and truncation-probe
  failures are caught into bounded error data while the stream is closed.

  Current Jolt semantics: byte-array reads are normalized with
  (bit-and b 0xff); java.io.File/createNewFile returns false (rather than
  throwing) when the parent directory is missing, so open distinguishes an
  existing target (:target-exists) from an otherwise failed create
  (:create-failed) by checking .exists first."
  (:require [jolt.sim.journal-writer :as jw]))

;; ---- narrow diagnostics -------------------------------------------------------

(defn- class-string
  "Best-effort immutable class string for diagnostics; never the object
  itself."
  [x]
  (try
    (str (class x))
    (catch :default _
      "<unknown>")))

(defn- throw-diag
  "Narrow diagnostics for a caught throwable at one phase: reason keyword
  and class string only. Messages are omitted entirely because raw
  exception messages can embed the target path and are unbounded."
  [phase reason t]
  {:phase phase :reason reason :class (class-string t)})

(def max-safe-image-bytes
  "Documented inclusive upper bound for image caps, configured at open
  (:max-image-bytes) and per read-bounded-image override. Values outside
  1..max-safe-image-bytes are rejected. The bound keeps the byte-array
  allocation inside the host representable range and bounds the worst-case
  raw-tail retention; allocation and read failures are still caught into
  bounded error data."
  16777216)

;; ---- adapter construction ------------------------------------------------------

(defn- make-adapter
  "Builds the adapter handle: a state atom plus the immutable path and
  read cap. The path is retained only for read-bounded-image; it never
  appears in status or failure maps."
  [state path max-image-bytes]
  {:state state
   :path path
   :max-image-bytes max-image-bytes})

(defn- failed-adapter
  "Adapter for an open-time refusal or failure: no stream, no writer, the
  open failure preserved as the first failure."
  [path max-image-bytes failure]
  (make-adapter (atom {:stream nil
                       :writer nil
                       :closed? false
                       :open-failure failure
                       :close-failure nil})
                path
                max-image-bytes))

(defn- checked-config
  "Validates open opts into a private config. Returns {:config config} or
  {:failure narrow-map}; never throws."
  [opts]
  (cond
    (not (map? opts))
    {:failure {:phase :open :reason :opts-must-be-map}}

    (not (string? (:path opts)))
    {:failure {:phase :open :reason :invalid-path}}

    (not (and (bytes? (:run-id opts)) (= 16 (alength (:run-id opts)))))
    {:failure {:phase :open :reason :invalid-run-id}}

    (not (and (integer? (:max-payload opts))
              (<= 0 (:max-payload opts))
              (<= (:max-payload opts) 0xffffffff)))
    {:failure {:phase :open :reason :invalid-max-payload}}

    (not (and (integer? (:max-image-bytes opts))
              (pos? (:max-image-bytes opts))
              (<= (:max-image-bytes opts) max-safe-image-bytes)))
    {:failure {:phase :open :reason :invalid-max-image-bytes}}

    (not (let [r (:max-eintr-retries opts)]
           (or (nil? r)
               (and (integer? r)
                    (<= 0 r)
                    (<= r jw/max-safe-eintr-retries)))))
    {:failure {:phase :open :reason :invalid-max-eintr-retries}}

    :else
    {:config {:path (:path opts)
              :run-id (:run-id opts)
              :max-payload (:max-payload opts)
              :max-image-bytes (:max-image-bytes opts)
              :max-eintr-retries (let [r (:max-eintr-retries opts)]
                                   (if (nil? r)
                                     jw/default-max-eintr-retries
                                     r))}}))

(defn- open-stream
  "Opens the FileOutputStream over the freshly created path. Returns
  {:stream stream} or {:failure narrow-map}; never throws."
  [path]
  (let [result (try
                 {:value (java.io.FileOutputStream. path)}
                 (catch :default t
                   {:thrown t}))]
    (if (contains? result :thrown)
      {:failure (throw-diag :open :open-threw (:thrown result))}
      {:stream (:value result)})))

(defn- close-stream!
  "Best-effort close of a stream. Returns nil or a narrow close-failure
  map; never throws."
  [stream]
  (let [result (try
                 {:value (.close stream)}
                 (catch :default t
                   {:thrown t}))]
    (if (contains? result :thrown)
      (throw-diag :close :close-threw (:thrown result))
      nil)))

(defn- writer-seams
  "Real-file seams for the synchronous writer: all-or-throw complete
  writes and a flush after every accepted frame. The stream is borrowed
  read-only storage for the writer; the adapter lock guarantees the stream
  is never used after close!."
  [stream]
  {:write! (fn [frame offset remaining]
             (.write stream frame offset remaining)
             {:status :written :count remaining})
   :flush! (fn []
             (.flush stream)
             {:status :ok})})

(defn open-process-crash-writer!
  "Opens a process-crash diagnostic file adapter over a trusted fresh
  path in a single-writer, parent-owned run directory. Never throws: any validation,
  refusal, stream-open, or header write/flush failure returns an adapter
  in the absorbing :failed state with the first failure preserved as
  bounded data.

  opts:
    :path             required string; a trusted fresh target path in a
                      single-writer, parent-owned run directory. An existing target is
                      refused (:target-exists) by a sequential best-effort
                      check and is never touched; the refusal is not a
                      race guarantee (see the namespace contract).
    :run-id           required 16-byte byte-array
    :max-payload      required u32 maximum payload length
    :max-image-bytes  required integer cap for read-bounded-image in
                      1..max-safe-image-bytes
    :max-eintr-retries consecutive-EINTR allowance, integer in
                      0..max-safe-eintr-retries, default 64

  The header is encoded, written, and flushed immediately. Durability is
  :process-crash: full-write plus flush per accepted frame, no sync, no
  fsync/power-loss/ARIES claim."
  [opts]
  (let [{:keys [config failure]} (checked-config opts)]
    (if failure
      (failed-adapter (:path opts) (:max-image-bytes opts) failure)
      (let [path (:path config)
            max-image-bytes (:max-image-bytes config)
            file (java.io.File. path)]
        (if (.exists file)
          (failed-adapter path max-image-bytes
                          {:phase :open :reason :target-exists})
          (let [created (try
                          {:value (.createNewFile file)}
                          (catch :default t
                            {:thrown t}))]
            (if (contains? created :thrown)
              (failed-adapter path max-image-bytes
                              (throw-diag :open :create-threw (:thrown created)))
              (if-not (:value created)
                (failed-adapter path max-image-bytes
                                {:phase :open :reason :create-failed})
                (let [opened (open-stream path)]
                  (if-let [open-failure (:failure opened)]
                    (failed-adapter path max-image-bytes open-failure)
                    (let [stream (:stream opened)
                          seams (writer-seams stream)
                          writer (jw/make-writer
                                  {:run-id (:run-id config)
                                   :max-payload (:max-payload config)
                                   :durability :process-crash
                                   :max-eintr-retries (:max-eintr-retries config)
                                   :write! (:write! seams)
                                   :flush! (:flush! seams)})]
                      (if (= :failed (:health (jw/writer-status writer)))
                        ;; Header write/flush failed: close the stream
                        ;; best-effort and preserve the writer's first
                        ;; failure.
                        (make-adapter
                         (atom {:stream nil
                                :writer writer
                                :closed? true
                                :open-failure nil
                                :close-failure (close-stream! stream)})
                         path max-image-bytes)
                        (make-adapter
                         (atom {:stream stream
                                :writer writer
                                :closed? false
                                :open-failure nil
                                :close-failure nil})
                         path max-image-bytes)))))))))))))

;; ---- status ---------------------------------------------------------------------

(defn- status-of
  "Immutable status snapshot: scalar values and the narrow failure map
  only. Never a stream, seam function, byte array, throwable, or path.
  Failure precedence: open refusal, then writer failure, then close
  failure -- the first failure is preserved."
  [state]
  (let [{:keys [writer closed? open-failure close-failure]} @state
        writer-status (when writer (jw/writer-status writer))
        writer-failure (:failure writer-status)
        failure (or open-failure writer-failure close-failure)]
    {:health (if failure :failed :healthy)
     :failure failure
     :sequence (or (:sequence writer-status) 0)
     :previous-crc32c (or (:previous-crc32c writer-status) 0)
     :accepted-end (or (:accepted-end writer-status) 0)
     :synced-end (or (:synced-end writer-status) 0)
     :durability :process-crash
     :closed? (boolean closed?)}))

;; ---- public API ------------------------------------------------------------------

(defn append!
  "Appends one framed record {:kind :flags :payload} and returns the
  current status snapshot. Never throws. After close! or after the first
  failure, append! performs no I/O and returns the preserved status."
  [adapter record]
  (let [state (:state adapter)]
    (locking state
      (when (and (not (:closed? @state))
                 (some? (:writer @state)))
        (jw/append! (:writer @state) record))
      (status-of state))))

(defn writer-status
  "Returns the immutable status snapshot:
    {:health :healthy|:failed
     :failure nil|narrow-map
     :sequence next-sequence
     :previous-crc32c last-complete-frame-crc
     :accepted-end byte-offset
     :synced-end byte-offset
     :durability :process-crash
     :closed? boolean}
  Contains no streams, seam functions, byte arrays, throwables, or paths.
  Never throws."
  [adapter]
  (let [state (:state adapter)]
    (locking state
      (status-of state))))

(defn close!
  "Closes the underlying file output and marks the adapter closed.
  Idempotent: later calls are no-ops returning the same status. Never
  throws. A close failure is recorded as bounded data but never replaces
  an earlier open or writer failure."
  [adapter]
  (let [state (:state adapter)]
    (locking state
      (when-not (:closed? @state)
        (let [stream (:stream @state)]
          (if (some? stream)
            (swap! state assoc
                   :stream nil
                   :closed? true
                   :close-failure (close-stream! stream))
            (swap! state assoc :closed? true))))
      (status-of state))))

(defn- read-error-diag
  "Bounded error data for a read-phase throwable: reason keyword and class
  string only. Messages are omitted entirely because raw exception
  messages can embed the target path and are unbounded."
  [reason t]
  {:status :error
   :reason reason
   :class (class-string t)})

(defn- result-after-close
  "Preserves an earlier read failure. If the read succeeded, a bounded
  close failure replaces the apparent success so resource-release failure
  is never reported as :ok. Pure helper used by the file reader."
  [read-result close-result]
  (if (and (= :ok (:status read-result)) (some? close-result))
    close-result
    read-result))

(defn read-bounded-image
  "Reads at most max-bytes bytes of the adapter's target file into a fresh
  byte-array, capping input before jolt.sim.journal/recover because
  recover retains the raw tail. Never throws.

  Returns {:status :ok :image byte-array :bytes-read n :truncated? bool}
  where :truncated? is true when the file holds more than max-bytes bytes,
  or {:status :error :reason keyword} plus a :class string. Errors never
  contain the path, an exception message, or any live object.

  The one-arity form uses the :max-image-bytes configured at open; the
  two-arity form overrides the cap per call. Both are validated against
  max-safe-image-bytes; allocation, read-loop, and truncation-probe
  failures are caught into bounded error data while the stream is closed."
  ([adapter]
   (read-bounded-image adapter (:max-image-bytes adapter)))
  ([adapter max-bytes]
   (if-not (and (integer? max-bytes)
                (pos? max-bytes)
                (<= max-bytes max-safe-image-bytes))
     {:status :error :reason :invalid-max-bytes}
     (try
       (let [file (java.io.File. (:path adapter))]
         (cond
           (not (.exists file))
           {:status :error :reason :file-missing}

           (.isDirectory file)
           {:status :error :reason :target-is-directory}

           :else
           (let [opened (try
                          {:value (java.io.FileInputStream. (:path adapter))}
                          (catch :default t
                            {:thrown t}))]
             (if (contains? opened :thrown)
               (read-error-diag :open-threw (:thrown opened))
               (let [stream (:value opened)
                     read-result
                     (try
                       (let [buffer (byte-array max-bytes)
                             filled (loop [offset 0]
                                      (if (>= offset max-bytes)
                                        offset
                                        (let [n (.read stream buffer offset
                                                       (- max-bytes offset))]
                                          (if (neg? n)
                                            offset
                                            (recur (+ offset n))))))
                             truncated? (and (= filled max-bytes)
                                             (not (neg? (.read stream))))
                             image (java.util.Arrays/copyOf buffer filled)]
                         {:status :ok
                          :image image
                          :bytes-read filled
                          :truncated? truncated?})
                       (catch :default t
                         (read-error-diag :read-threw t)))
                     close-result
                     (try
                       (.close stream)
                       nil
                       (catch :default t
                         (read-error-diag :close-threw t)))]
                 (result-after-close read-result close-result))))))
       (catch :default t
         (read-error-diag :path-check-threw t))))))
