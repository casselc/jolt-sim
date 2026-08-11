(ns jolt.sim.retained-process
  "Parent-owned process and mailbox lifecycle for one retained Jolt worker.

  This namespace is a control-plane supervisor.  It runs outside
  `runtime/run-controlled`, owns a fresh artifact directory forever, and
  communicates with one cooperative (not adversarial) child through closed,
  versioned EDN documents.  Commands are published exactly once by moving a
  closed partial file within the command directory.  A timeout after that move
  is deliberately ambiguous: the handle enters `:uncertain`, never retries,
  and accepts only `reconcile!` or `terminate!`.

  The child process owns application execution.  This parent never interprets
  an application `:failed` receipt as a transport failure and never deletes
  retained evidence."
  (:require [clojure.core.protocols :as protocols]
            [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.process :as process]
            [jolt.sim.trace :as trace]))

(def protocol-version 1)

(def ^:private protocol-key :jolt.sim.retained/protocol)
(def ^:private instance-key :jolt.sim.retained/instance-id)
(def ^:private adapter-key :jolt.sim.retained/adapter)
(def ^:private input-key :jolt.sim.retained/input)
(def ^:private sequence-key :jolt.sim.retained/sequence)
(def ^:private command-key :jolt.sim.retained/command)
(def ^:private status-key :jolt.sim.retained/status)
(def ^:private value-key :jolt.sim.retained/value)
(def ^:private error-key :jolt.sim.retained/error)

(def ^:private config-keys
  #{:worker-command :adapter :input :dir :temp-dir :extra-env
    :startup-timeout-ms :command-timeout-ms :kill-grace-ms})

(def ^:private ready-keys
  #{protocol-key instance-key status-key})

(def ^:private receipt-common-keys
  #{protocol-key instance-key sequence-key status-key})

(def ^:private diagnostic-byte-limit 65536)
(def ^:private document-byte-limit 1048576)
(def ^:private error-text-limit 4096)
(def ^:private wait-poll-ms 10)

(declare snapshot command! reconcile! terminate!)

(def ^:private snapshot-operation (Object.))
(def ^:private command-operation (Object.))
(def ^:private reconcile-operation (Object.))
(def ^:private terminate-operation (Object.))

(defn- invalid-config [reason data]
  (ex-info
   "jolt-sim retained process rejected malformed configuration"
   (merge {:type ::invalid-config :reason reason} data)))

(defn- transport-error [reason state data]
  (ex-info
   "jolt-sim retained process control transport failed"
   (merge {:type ::transport-error
           :reason reason
           :status (:status state)
           :uncertain-sequence (:uncertain-sequence state)
           :artifact-dir (:artifact-dir state)}
          data)))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- non-negative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- namespaced-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- string-map? [value]
  (and (map? value)
       (every? (fn [[key value]]
                 (and (string? key) (string? value)))
               value)))

(defn- validate-timeout! [field value]
  (when-not (positive-integer? value)
    (throw (invalid-config :invalid-timeout
                           {:field field :value value})))
  value)

(defn- checked-config [config]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map
                           {:value-class (str (class config))})))
  (let [unknown (seq (sort-by pr-str (remove config-keys (keys config))))]
    (when unknown
      (throw (invalid-config :unknown-key {:keys (vec unknown)}))))
  (let [worker-command (:worker-command config)]
    (when-not (and (vector? worker-command)
                   (seq worker-command)
                   (every? string? worker-command))
      (throw (invalid-config :invalid-worker-command
                             {:value worker-command}))))
  (when-not (namespaced-symbol? (:adapter config))
    (throw (invalid-config :invalid-adapter {:value (:adapter config)})))
  (let [dir (:dir config)]
    (when-not (and (string? dir) (seq dir))
      (throw (invalid-config :invalid-dir {:value dir}))))
  (when (contains? config :temp-dir)
    (let [temp-dir (:temp-dir config)]
      (when-not (and (string? temp-dir) (seq temp-dir))
        (throw (invalid-config :invalid-temp-dir {:value temp-dir})))))
  (when (contains? config :extra-env)
    (when-not (string-map? (:extra-env config))
      (throw (invalid-config :invalid-extra-env
                             {:value (:extra-env config)}))))
  (doseq [[field default]
          [[:startup-timeout-ms 10000]
           [:command-timeout-ms 30000]
           [:kill-grace-ms 250]]]
    (validate-timeout! field (get config field default)))
  (let [input (get config :input nil)]
    (try
      (trace/canonical-value input [:retained :startup :input])
      (catch :default error
        (throw
         (invalid-config
          :invalid-input
          {:error (select-keys (ex-data error)
                               [:type :reason :path :value-class])})))))
  (assoc config
         :input (get config :input nil)
         :extra-env (get config :extra-env {})
         :startup-timeout-ms (get config :startup-timeout-ms 10000)
         :command-timeout-ms (get config :command-timeout-ms 30000)
         :kill-grace-ms (get config :kill-grace-ms 250)))

(defn- path-in [dir filename]
  (str (fs/path dir filename)))

(defn- create-run-dir [temp-dir]
  (str
   (fs/create-temp-dir
    (cond-> {:prefix "jolt-sim-retained-"}
      temp-dir (assoc :dir temp-dir)))))

(defn- create-paths! [run-dir]
  (let [commands (path-in run-dir "commands")
        receipts (path-in run-dir "receipts")]
    (fs/create-dirs commands)
    (fs/create-dirs receipts)
    {:startup (path-in run-dir "startup.edn")
     :ready (path-in run-dir "ready.edn")
     :terminal (path-in run-dir "terminal.edn")
     :commands commands
     :receipts receipts
     :stdout (path-in run-dir "stdout.log")
     :stderr (path-in run-dir "stderr.log")}))

(defn- bounded-text [value]
  (let [text (str (or value ""))]
    (if (<= (count text) error-text-limit)
      text
      (str (subs text 0 error-text-limit) "..."))))

(defn- error-summary [phase error]
  {:phase phase
   :kind :jolt.sim/exception
   :class (bounded-text (class error))
   :message (bounded-text (or (ex-message error) (str error)))})

(defn- read-prefix [path limit]
  (if-not (fs/exists? path)
    {:bytes 0 :truncated? false :text ""}
    (let [stream (java.io.FileInputStream. path)]
      (try
        (let [buffer (byte-array limit)
              filled (loop [offset 0]
                       (if (>= offset limit)
                         offset
                         (let [n (.read stream buffer offset (- limit offset))]
                           (if (neg? n)
                             offset
                             (recur (+ offset n))))))
              extra? (and (= filled limit) (not (neg? (.read stream))))
              observed (try
                         (.length (java.io.File. path))
                         (catch :default _ filled))
              bytes (max filled observed)]
          {:bytes bytes
           :truncated? (or extra? (> bytes filled))
           :text (String. (java.util.Arrays/copyOf buffer filled) "UTF-8")})
        (finally
          (.close stream))))))

(defn- file-diagnostic [path]
  (try
    (read-prefix path diagnostic-byte-limit)
    (catch :default error
      {:bytes nil
       :truncated? false
       :text nil
       :error (error-summary :diagnostic-reading error)})))

(defn- diagnostics [paths]
  {:stdout (file-diagnostic (:stdout paths))
   :stderr (file-diagnostic (:stderr paths))})

(def ^:private end-of-input (Object.))

(defn- read-one-edn [text]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-input)
          [trailing _] (read+string reader false end-of-input)]
      (when (identical? value end-of-input)
        (throw (ex-info "empty EDN document" {:reason :empty-document})))
      (when-not (identical? trailing end-of-input)
        (throw (ex-info "trailing EDN form" {:reason :trailing-document})))
      (edn/read-string text))
    (catch :default error
      (throw
       (ex-info "retained process document is not exactly one EDN form"
                {:type ::protocol-error
                 :reason :malformed-edn
                 :error (error-summary :document-reading error)})))))

(defn- read-document [path]
  (let [{:keys [bytes truncated? text]} (read-prefix path document-byte-limit)]
    (when (or truncated? (> bytes document-byte-limit))
      (throw
       (ex-info "retained process document exceeds its bounded transport"
                {:type ::protocol-error
                 :reason :document-too-large
                 :max-bytes document-byte-limit
                 :observed-bytes bytes})))
    (read-one-edn text)))

(defn- exact-keys! [kind expected document]
  (when-not (map? document)
    (throw
     (ex-info "retained process document is not a map"
              {:type ::protocol-error
               :reason :not-a-map
               :document kind
               :value-class (str (class document))})))
  (when-not (= expected (set (keys document)))
    (throw
     (ex-info "retained process document has the wrong keys"
              {:type ::protocol-error
               :reason :wrong-keys
               :document kind
               :expected expected
               :actual (set (keys document))})))
  document)

(defn- validate-coordinate! [kind instance-id sequence document]
  (when-not (= protocol-version (get document protocol-key))
    (throw
     (ex-info "retained process protocol version does not match"
              {:type ::protocol-error :reason :protocol-version
               :document kind :expected protocol-version
               :actual (get document protocol-key)})))
  (when-not (= instance-id (get document instance-key))
    (throw
     (ex-info "retained process instance does not match"
              {:type ::protocol-error :reason :instance-id
               :document kind})))
  (when (some? sequence)
    (when-not (= sequence (get document sequence-key))
      (throw
       (ex-info "retained process sequence does not match"
                {:type ::protocol-error :reason :sequence
                 :document kind :expected sequence
                 :actual (get document sequence-key)}))))
  document)

(defn- decode-ready [instance-id document]
  (->> document
       (exact-keys! :ready ready-keys)
       (validate-coordinate! :ready instance-id nil))
  (when-not (= :ready (get document status-key))
    (throw
     (ex-info "retained process ready document has the wrong status"
              {:type ::protocol-error :reason :ready-status
               :actual (get document status-key)})))
  :ready)

(defn- decode-receipt [instance-id sequence document]
  (let [status (get document status-key)
        expected (case status
                   :completed (conj receipt-common-keys value-key)
                   :failed (conj receipt-common-keys error-key)
                   nil)]
    (when-not expected
      (throw
       (ex-info "retained process receipt has an unknown status"
                {:type ::protocol-error :reason :receipt-status
                 :actual status})))
    (->> document
         (exact-keys! :receipt expected)
         (validate-coordinate! :receipt instance-id sequence))
    (let [payload-key (if (= :completed status) value-key error-key)
          payload (get document payload-key)]
      (when-not (trace/canonical-form? payload)
        (throw
         (ex-info "retained process receipt payload is not canonical"
                  {:type ::protocol-error :reason :receipt-payload
                   :status status})))
      (cond-> {:status status :sequence sequence}
        (= :completed status) (assoc :value (trace/restore-value payload))
        (= :failed status) (assoc :error (trace/restore-value payload))))))

(defn- command-basename [sequence]
  (format "command-%020d.edn" sequence))

(defn- receipt-basename [sequence]
  (format "receipt-%020d.edn" sequence))

(defn- command-path [paths sequence]
  (path-in (:commands paths) (command-basename sequence)))

(defn- receipt-path [paths sequence]
  (path-in (:receipts paths) (receipt-basename sequence)))

(defn- partial-command-path [paths sequence]
  (str (command-path paths sequence) ".partial"))

(defn- monotonic-nanos []
  (System/nanoTime))

(defn- sleep-ms! [millis]
  (Thread/sleep millis))

(defn- child-alive? [child]
  (.isAlive (:proc child)))

(defn- child-pid [child]
  (.pid (:proc child)))

(defn- reaped-exit [child]
  (.exitValue (:proc child)))

(defn- timed-wait! [child timeout-ms]
  (let [deadline (+ (monotonic-nanos) (* timeout-ms 1000000))]
    (loop []
      (if-not (child-alive? child)
        true
        (let [remaining (- deadline (monotonic-nanos))]
          (if (<= remaining 0)
            false
            (do
              (sleep-ms! (min wait-poll-ms
                              (max 1 (quot remaining 1000000))))
              (recur))))))))

(defn- terminate-and-reap-child! [child kill-grace-ms]
  (if-not (child-alive? child)
    (reaped-exit child)
    (let [term-error (try
                       (process/destroy child)
                       nil
                       (catch :default error error))
          exited-after-term? (try
                               (timed-wait! child kill-grace-ms)
                               (catch :default _ false))]
      (if exited-after-term?
        (reaped-exit child)
        (let [kill-error (try
                           (.destroyForcibly (:proc child))
                           nil
                           (catch :default error error))
              exited-after-kill? (try
                                   (timed-wait! child kill-grace-ms)
                                   (catch :default _ false))]
          (if exited-after-kill?
            (reaped-exit child)
            (throw
             (ex-info
              "retained child survived forced termination"
              {:type ::worker-survived-kill
               :termination-errors
               (vec
                (keep identity
                      [(when term-error
                         (error-summary :term-signal term-error))
                       (when kill-error
                         (error-summary :kill-signal kill-error))]))}))))))))

(defn- wait-for-path-or-exit [child path timeout-ms]
  (let [deadline (+ (monotonic-nanos) (* timeout-ms 1000000))]
    (loop []
      (cond
        (fs/exists? path) :present
        (not (child-alive? child))
        ;; The child may have closed and moved the document immediately before
        ;; exit; one final observation gives that committed receipt priority.
        (if (fs/exists? path) :present :exited)
        :else
        (let [remaining (- deadline (monotonic-nanos))]
          (if (<= remaining 0)
            ;; Same priority at the deadline boundary.
            (if (fs/exists? path) :present :deadline)
            (do
              (sleep-ms! (min wait-poll-ms
                              (max 1 (quot remaining 1000000))))
              (recur))))))))

(defn- observe-exit [state]
  (let [child (:child state)]
    (if (and child (nil? (:exit state)) (not (child-alive? child)))
      (assoc state
             :exit (reaped-exit child)
             :status (if (= :terminated (:status state))
                       :terminated
                       :exited))
      state)))

(defn- bounded-receipt [receipt]
  (select-keys receipt [:status :sequence]))

(defn- snapshot-state! [state]
  (let [current (swap! state observe-exit)]
    {:protocol protocol-version
     :instance-id (:instance-id current)
     :status (:status current)
     :next-sequence (:next-sequence current)
     :uncertain-sequence (:uncertain-sequence current)
     :last-receipt (some-> (:last-receipt current) bounded-receipt)
     :child {:pid (:pid current)
             :alive? (and (nil? (:exit current))
                          (boolean (child-alive? (:child current))))
             :exit (:exit current)}
     :artifact {:dir (:artifact-dir current)
                :startup (get-in current [:paths :startup])
                :ready (get-in current [:paths :ready])
                :terminal (get-in current [:paths :terminal])
                :commands (get-in current [:paths :commands])
                :receipts (get-in current [:paths :receipts])
                :stdout (get-in current [:paths :stdout])
                :stderr (get-in current [:paths :stderr])}
     :diagnostics (when (contains? #{:exited :terminated :failed}
                                    (:status current))
                    (diagnostics (:paths current)))}))

(defn snapshot
  "Returns one bounded immutable status and artifact-coordinate snapshot."
  [handle]
  (handle snapshot-operation nil))

(defn- ensure-commandable! [state]
  (let [current (swap! state observe-exit)]
    (case (:status current)
      :ready current
      :uncertain
      (throw (transport-error :uncertain-command current
                              {:sequence (:uncertain-sequence current)}))
      :exited
      (throw (transport-error :child-exited current
                              {:exit (:exit current)}))
      :terminated
      (throw (transport-error :terminated current {:exit (:exit current)}))
      (throw (transport-error :not-ready current {})))))

(defn- command-document [state sequence value]
  {protocol-key protocol-version
   instance-key (:instance-id state)
   sequence-key sequence
   command-key (trace/canonical-value value [:retained :command sequence])})

(defn- publish-command! [state sequence value]
  (let [paths (:paths state)
        partial (partial-command-path paths sequence)
        target (command-path paths sequence)
        document (command-document state sequence value)]
    (when (or (fs/exists? partial) (fs/exists? target))
      (throw (transport-error :command-path-exists state
                              {:sequence sequence})))
    (spit partial (trace/canonical-edn document))
    ;; Verify the closed partial before the irreversible publication move.
    (when-not (= document (read-document partial))
      (throw (transport-error :partial-verification state
                              {:sequence sequence})))
    (try
      (fs/move partial target {:atomic-move true})
      {:published? true :path target}
      (catch :default error
        ;; A move can report failure after the namespace entry changed.  Once
        ;; either observation admits publication, fail closed as ambiguous.
        (if (fs/exists? target)
          {:published? true :path target :move-error error}
          (throw error))))))

(defn- accept-receipt! [state receipt]
  (swap! state
         (fn [current]
           (assoc current
                  ;; Reconciliation may recover a receipt after the child's
                  ;; exit was already observed. The receipt resolves command
                  ;; ambiguity, but cannot make a dead process commandable.
                  :status (if (some? (:exit current))
                            (if (= :terminated (:status current))
                              :terminated
                              :exited)
                            :ready)
                  :uncertain-sequence nil
                  :next-sequence (inc (:sequence receipt))
                  :last-receipt receipt)))
  receipt)

(defn- await-receipt! [state sequence timeout-ms]
  (let [{:keys [child paths instance-id] :as current} @state
        path (receipt-path paths sequence)]
    (case (wait-for-path-or-exit child path timeout-ms)
      :present
      (try
        (accept-receipt!
         state
         (decode-receipt instance-id sequence (read-document path)))
        (catch :default error
          (swap! state assoc :status :uncertain
                 :uncertain-sequence sequence)
          (throw (transport-error :invalid-receipt @state
                                  {:sequence sequence
                                   :error (error-summary
                                           :receipt-protocol error)}))))

      :exited
      (let [_ (swap! state assoc :uncertain-sequence sequence)
            after (swap! state observe-exit)]
        (throw (transport-error :child-exited after
                                {:sequence sequence :exit (:exit after)
                                 :diagnostics (diagnostics paths)})))

      :deadline
      (do
        (swap! state assoc :status :uncertain
               :uncertain-sequence sequence)
        (throw (transport-error :receipt-deadline @state
                                {:sequence sequence
                                 :timeout-ms timeout-ms}))))))

(defn- command-state! [state value timeout-ms]
  (validate-timeout! :timeout-ms timeout-ms)
  (let [current (ensure-commandable! state)
        sequence (:next-sequence current)]
    ;; Canonicalize before creating a partial or consuming the sequence.
    (trace/canonical-value value [:retained :command sequence])
    (try
      (let [{:keys [move-error]} (publish-command! current sequence value)]
        (when move-error
          (swap! state assoc :status :uncertain
                 :uncertain-sequence sequence)
          (throw (transport-error :publication-ambiguous @state
                                  {:sequence sequence
                                   :error (error-summary
                                           :command-publication
                                           move-error)})))
        (await-receipt! state sequence timeout-ms))
      (catch :default error
        ;; A failure before target publication is an ordinary transport error,
        ;; not an ambiguous application command. Preserve its forensic partial
        ;; and fail the handle rather than silently skipping or reusing it.
        (if (= ::transport-error (:type (ex-data error)))
          (do
            ;; A publication-stage refusal before any target exists leaves no
            ;; safe automatic continuation: its forensic partial/name must not
            ;; be overwritten and this sequence must not be silently skipped.
            (when (= :ready (:status @state))
              (swap! state assoc :status :failed))
            (throw error))
          (let [published? (fs/exists? (command-path (:paths current) sequence))]
            (when published?
              (swap! state assoc :status :uncertain
                     :uncertain-sequence sequence))
            (when-not published?
              (swap! state assoc :status :failed))
            (throw
             (transport-error
              (if published? :publication-ambiguous :publication-failed)
              @state
              {:sequence sequence
               :error (error-summary :command-publication error)}))))))))

(defn command!
  "Publishes one command exactly once and waits for its exact receipt.

  Returns `{:status :completed :sequence n :value ...}` or
  `{:status :failed :sequence n :error ...}`.  `:failed` is an application
  result. Transport failures throw typed ex-info. A post-publication deadline
  leaves the handle `:uncertain`; call `reconcile!` or `terminate!`, never
  retry the command."
  ([handle value]
   (handle command-operation nil value))
  ([handle value timeout-ms]
   (handle command-operation timeout-ms value)))

(defn- reconcile-state! [state timeout-ms]
  (validate-timeout! :timeout-ms timeout-ms)
  (let [current @state
        sequence (:uncertain-sequence current)]
    ;; Do not collapse `:uncertain` into `:exited` before checking the exact
    ;; receipt. The child may have committed that receipt immediately before
    ;; exit; await-receipt! always gives the receipt observation priority.
    (when-not (non-negative-integer? sequence)
      (throw (transport-error :nothing-to-reconcile current {})))
    ;; Even if the child has exited, a committed receipt wins.  The shared
    ;; waiter checks the exact receipt before consulting liveness.
    (await-receipt! state sequence timeout-ms)))

(defn reconcile!
  "Waits for the exact receipt of the one uncertain published sequence.

  It never republishes or advances the sequence. A further deadline preserves
  uncertainty."
  ([handle]
   (handle reconcile-operation nil))
  ([handle timeout-ms]
   (handle reconcile-operation timeout-ms)))

(defn- terminate-state! [state]
  (let [current (swap! state observe-exit)]
    (if (some? (:exit current))
      (do
        (swap! state assoc :status :terminated)
        (snapshot-state! state))
      (try
        (let [exit (terminate-and-reap-child!
                    (:child current) (:kill-grace-ms current))]
          (swap! state assoc :status :terminated :exit exit)
          (snapshot-state! state))
        (catch :default error
          (swap! state assoc :status :failed)
          (throw
           (transport-error :termination-failed @state
                            {:error (error-summary :termination error)
                             :diagnostics (diagnostics (:paths current))})))))))

(defn terminate!
  "Terminates and reaps the retained child (TERM, then forced kill) and keeps
  every artifact. Repeated calls return the current bounded snapshot."
  [handle]
  (handle terminate-operation nil))

(defn- make-handle [initial-state]
  (let [state (atom initial-state)
        lock (Object.)]
    (reify
      clojure.lang.IFn
      (invoke [_ operation argument]
        (locking lock
          (cond
            (identical? operation snapshot-operation)
            (snapshot-state! state)

            (identical? operation reconcile-operation)
            (reconcile-state!
             state (or argument (:command-timeout-ms @state)))

            (identical? operation terminate-operation)
            (terminate-state! state)

            :else
            (throw
             (ex-info "unknown retained process capability operation"
                      {:type ::invalid-operation})))))
      (invoke [_ operation argument value]
        (locking lock
          (if (identical? operation command-operation)
            (command-state!
             state value (or argument (:command-timeout-ms @state)))
            (throw
             (ex-info "unknown retained process capability operation"
                      {:type ::invalid-operation})))))

      protocols/Datafiable
      (datafy [this]
        (snapshot this))

      protocols/Navigable
      (nav [_ key value]
        (case key
          :artifact value
          :diagnostics value
          value)))))

(defn- startup-document [instance-id config]
  {protocol-key protocol-version
   instance-key instance-id
   adapter-key (:adapter config)
   input-key (trace/canonical-value (:input config)
                                    [:retained :startup :input])})

(defn- process-options [config paths]
  {:dir (:dir config)
   :out (:stdout paths)
   :err (:stderr paths)
   :extra-env (:extra-env config)})

(defn start!
  "Starts one separate retained worker and waits for its ready document.

  Required config: `:worker-command` (nonempty vector of strings), `:adapter`
  (namespaced symbol), and `:dir`. Optional canonical `:input`, `:temp-dir`,
  string `:extra-env`, and positive `:startup-timeout-ms`,
  `:command-timeout-ms`, and `:kill-grace-ms`.

  The exact worker argv is the configured prefix followed by startup, ready,
  command-directory, receipt-directory, and terminal paths.  Startup or spawn
  failures throw typed transport errors carrying the permanently retained
  artifact directory."
  [raw-config]
  (let [config (checked-config raw-config)
        run-dir (create-run-dir (:temp-dir config))
        paths (create-paths! run-dir)
        instance-id (str "retained-" (System/currentTimeMillis)
                         "-" (System/nanoTime))
        startup (startup-document instance-id config)
        command (into (:worker-command config)
                      [(:startup paths) (:ready paths)
                       (:commands paths) (:receipts paths)
                       (:terminal paths)])
        child-holder (volatile! nil)]
    (try
      (spit (:startup paths) (trace/canonical-edn startup))
      (when-not (= startup (read-document (:startup paths)))
        (throw (ex-info "startup document verification failed" {})))
      (let [child (process/process command (process-options config paths))
            _ (vreset! child-holder child)
            pid (try (child-pid child) (catch :default _ nil))]
        (case (wait-for-path-or-exit
               child (:ready paths) (:startup-timeout-ms config))
          :present
          (do
            (decode-ready instance-id (read-document (:ready paths)))
            (make-handle
             {:status :ready
              :instance-id instance-id
              :artifact-dir run-dir
              :paths paths
              :child child
              :pid pid
              :exit nil
              :next-sequence 0
              :uncertain-sequence nil
              :last-receipt nil
              :command-timeout-ms (:command-timeout-ms config)
              :kill-grace-ms (:kill-grace-ms config)}))

          :exited
          (let [exit (reaped-exit child)]
            (throw
             (transport-error
              :startup-child-exited
              {:status :exited :artifact-dir run-dir}
              {:exit exit :diagnostics (diagnostics paths)})))

          :deadline
          (let [exit (terminate-and-reap-child! child (:kill-grace-ms config))]
            (throw
             (transport-error
              :startup-deadline
              {:status :failed :artifact-dir run-dir}
              {:timeout-ms (:startup-timeout-ms config)
               :exit exit :diagnostics (diagnostics paths)})))))
      (catch :default error
        ;; If startup validation fails after spawn but before handle ownership
        ;; transfers, this scope still owns bounded termination and reaping.
        (let [child @child-holder
              pid (when child
                    (try (child-pid child) (catch :default _ nil)))
              cleanup-error
              (when child
                (try
                  (when (child-alive? child)
                    (terminate-and-reap-child! child (:kill-grace-ms config)))
                  nil
                  (catch :default cleanup cleanup)))]
          (if cleanup-error
            (throw
             (transport-error
              :startup-cleanup-failed
              {:status :failed :artifact-dir run-dir}
              {:pid pid
               :startup-error (error-summary :startup error)
               :cleanup-error (error-summary :startup-cleanup cleanup-error)
               :diagnostics (diagnostics paths)}))
            (if (= ::transport-error (:type (ex-data error)))
              (throw error)
              (throw
               (transport-error
                :startup-failed
                {:status :failed :artifact-dir run-dir}
                {:pid pid
                 :error (error-summary :startup error)
                 :diagnostics (diagnostics paths)})))))))))
