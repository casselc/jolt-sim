(ns jolt.sim.explore-worker
  "Single-run child-process protocol for supervised fresh-process exploration.

  A parent writes one versioned EDN request, launches a fresh Jolt process whose
  main is this namespace, and supplies request/result paths. The worker resolves
  only a `defsim`-marked var and invokes it once with a runtime-overrides map
  plus a scenario input value, writes one canonical result document, and exits.
  Reusing a worker across cases is deliberately unsupported: deadlock and
  poisoned global controller state are reclaimed by terminating the whole
  child process.

  Protocol v2. The request's `:jolt.sim.explore/schedule` is optional: nil
  means the run drives no `:future-schedule` override at all, letting a
  no-schedule case run under the scenario's declared configuration untouched.
  A nonnil schedule is an exact permutation, same as before. The request's
  `:jolt.sim.explore/input` is always present and is a `jolt.sim.trace`
  canonical projection of the caller's scenario input value (nil by default),
  restored before the scenario is invoked. There is no v1 compatibility; a v1
  request is rejected as an ordinary protocol-version mismatch.

  Opt-in activity journal. When the trusted parent reserves the activity
  journal path in this worker's environment (exactly one key,
  `activity-journal-env-key`, naming a fixed `<run-dir>/activity.journal` the
  caller never supplies), the worker opens a `jolt.sim.activity` observer on
  that path before scenario execution and emits the exact lifecycle vectors
  for `:jolt.sim.explore/scenario-started`, `.../scenario-completed`, and
  `.../scenario-failed`. Event data carries bounded safe identifiers only: no
  Throwable, exception message, input, result, or path. The deterministic
  16-byte run-id is derived from the trusted path alone with four
  domain-separated CRC32C lanes; no random state, clock read, or new dependency
  is involved. Any open/emit/close failure invalidates the run: an apparent
  completion becomes a bounded `:worker-error`, while an application failure
  or earlier worker error remains primary and carries the bounded observer
  status as secondary diagnostics under `:jolt.sim.explore/activity`.
  Durability is `:process-crash` only; this worker makes no fsync, power-loss,
  or kernel-crash claim. When the key is absent or empty the disabled path is
  behavior-identical and never creates the journal."
  (:require [clojure.edn :as edn]
            [jolt.sim.activity :as activity]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.journal :as journal]
            [jolt.sim.trace :as trace]))

(def protocol-version 2)

(def ^:private protocol-key :jolt.sim.explore/protocol)
(def ^:private scenario-key :jolt.sim.explore/scenario)
(def ^:private schedule-key :jolt.sim.explore/schedule)
(def ^:private input-key :jolt.sim.explore/input)
(def ^:private status-key :jolt.sim.explore/status)
(def ^:private value-key :jolt.sim.explore/value)
(def ^:private error-key :jolt.sim.explore/error)
(def ^:private phase-key :jolt.sim.explore/phase)
(def ^:private activity-key :jolt.sim.explore/activity)

(def activity-journal-env-key
  "The single exact child environment key reserved for the trusted,
  parent-owned activity journal path. `jolt.sim.process-explorer` rejects any
  `:extra-env` collision with this key before launch, so a caller or browser
  can never supply the activity path."
  "JOLT_SIM_ACTIVITY_JOURNAL_PATH")

(def ^:private scenario-started-tag :jolt.sim.explore/scenario-started)
(def ^:private scenario-completed-tag :jolt.sim.explore/scenario-completed)
(def ^:private scenario-failed-tag :jolt.sim.explore/scenario-failed)

(def ^:private request-keys
  #{protocol-key scenario-key schedule-key input-key})

(def ^:private completed-result-keys
  #{protocol-key status-key schedule-key value-key})

(def ^:private error-result-keys
  #{protocol-key status-key schedule-key error-key})

(defn- protocol-error [reason data]
  (ex-info
   "jolt-sim exploration worker protocol violation"
   (merge {:type :jolt.sim.explore/worker-protocol-error
           :reason reason}
          data)))

(defn- namespaced-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- valid-request-schedule? [schedule]
  (or (nil? schedule) (future-schedule/valid-schedule? schedule)))

(defn request-document
  "Builds the exact protocol-v2 request for one marked scenario, an optional
  future schedule, and a scenario input value.

  A nil schedule means the run drives no `:future-schedule` override at all;
  a nonnil schedule must be an exact permutation. input defaults to nil and is
  stored as its `jolt.sim.trace` canonical projection."
  ([scenario schedule]
   (request-document scenario schedule nil))
  ([scenario schedule input]
   (when-not (namespaced-symbol? scenario)
     (throw (protocol-error :invalid-scenario {:scenario scenario})))
   (when-not (valid-request-schedule? schedule)
     (throw (protocol-error :invalid-schedule {:schedule schedule})))
   {protocol-key protocol-version
    scenario-key scenario
    schedule-key schedule
    input-key (trace/canonical-value input)}))

(defn- validate-request! [request]
  (when-not (map? request)
    (throw (protocol-error :request-not-a-map
                           {:request-class (str (class request))})))
  (when-not (= protocol-version (get request protocol-key))
    (throw (protocol-error :protocol-version
                           {:expected protocol-version
                            :actual (get request protocol-key)})))
  (when-not (= request-keys (set (keys request)))
    (throw (protocol-error :request-keys
                           {:expected request-keys
                            :actual (set (keys request))})))
  (let [scenario (get request scenario-key)
        schedule (get request schedule-key)
        input (get request input-key)]
    (when-not (namespaced-symbol? scenario)
      (throw (protocol-error :invalid-scenario {:scenario scenario})))
    (when-not (valid-request-schedule? schedule)
      (throw (protocol-error :invalid-schedule {:schedule schedule})))
    (when-not (trace/canonical-form? input)
      (throw (protocol-error :invalid-input {:input input})))
    request))

(defn- resolve-scenario! [scenario activity-enabled?]
  (require (symbol (namespace scenario)))
  (let [scenario-var (resolve scenario)]
    (when-not scenario-var
      (throw (protocol-error :scenario-not-found {:scenario scenario})))
    (when-not (:jolt.sim/scenario (meta scenario-var))
      (throw (protocol-error :scenario-not-marked {:scenario scenario})))
    (when-not (contains? #{true false}
                         (:jolt.sim/accepts-input (meta scenario-var)))
      (throw (protocol-error :scenario-input-contract
                             {:scenario scenario})))
    (when (and activity-enabled?
               (not (true? (:jolt.sim/activity-lifecycle-owned
                            (meta scenario-var)))))
      (throw (protocol-error :scenario-activity-lifecycle-contract
                             {:scenario scenario})))
    (when-not (fn? @scenario-var)
      (throw (protocol-error :scenario-not-callable {:scenario scenario})))
    scenario-var))

(defn- rejects-input-error [scenario input]
  (ex-info
   "defsim scenario does not declare an input binding and rejects non-nil input"
   {:type :jolt.sim.runtime/scenario-rejects-input
    :scenario scenario
    :input input}))

(defn- safe-error
  "Returns intentionally narrow canonical diagnostics for worker machinery.
  It never retains arbitrary exception data, which may itself be unencodable."
  [phase error]
  {:phase phase
   :kind :jolt.sim/exception
   :class (str (class error))
   :message (or (ex-message error) (str error))})

(defn- completed-document [schedule value]
  {protocol-key protocol-version
   status-key :completed
   schedule-key schedule
   value-key (trace/canonical-value value)})

(defn- failed-document [schedule error]
  {protocol-key protocol-version
   status-key :failed
   schedule-key schedule
   error-key (trace/canonical-value (trace/normalize-error error))})

(defn- worker-error-document [schedule phase error]
  {protocol-key protocol-version
   status-key :worker-error
   schedule-key schedule
   error-key (trace/canonical-value (safe-error phase error))})

(defn- ready-document []
  {protocol-key protocol-version
   phase-key :scenario-ready})

(defn- write-ready! [path]
  ;; One complete, closed write is enough for the parent-side readiness
  ;; boundary. The worker has already loaded this namespace, restored and
  ;; validated the request, and resolved the marked scenario before this file
  ;; appears. It contains no application value or ambient process state.
  (spit path (trace/canonical-edn (ready-document))))

;; ---- opt-in activity journal ------------------------------------------------

(defn- activity-run-id
  "Derives one deterministic 16-byte non-cryptographic identity from the full
  trusted, parent-owned journal path. Four domain-separated CRC32C lanes reuse
  the journal's proved bounded-u32 implementation, avoiding unchecked numeric
  overflow in Jolt. This identifies a retained run; it is not a security or
  content-addressing primitive."
  [path]
  (let [out (byte-array 16)]
    (dotimes [lane 4]
      (let [input (.getBytes
                   (str "jolt.sim.explore-worker/activity-journal:"
                        lane ":" path)
                   "UTF-8")
            value (journal/crc32c input)
            offset (* lane 4)]
        (dotimes [index 4]
          (aset out (+ offset index)
                (unchecked-byte
                 (bit-and
                  (unsigned-bit-shift-right value (* index 8)) 0xff))))))
    out))

(def ^:private activity-failure-keys
  #{:phase :reason :class
    :payload-length :max-payload
    :count :remaining
    :consecutive-eintrs :max-eintr-retries})

(defn- bounded-activity-failure
  "Projects an observer failure map to narrow identifier/scalar context:
  keyword phase/reason, a class-name string, and numeric context. Raw
  exception messages are dropped because they can embed the trusted journal
  path and are unbounded; Throwables and byte arrays never reach this
  boundary."
  [failure]
  (when (map? failure)
    (reduce-kv
     (fn [result key value]
       (if (and (contains? activity-failure-keys key)
                (or (keyword? value)
                    (string? value)
                    (integer? value)
                    (boolean? value)))
         (assoc result key value)
         result))
     {}
     failure)))

(defn- bounded-activity-status
  "Bounded immutable projection of one observer status: health, narrow
  failure, durability mode, and scalar counters only. Safe to retain in a
  result document or secondary diagnostics."
  [status]
  {:health (:health status)
   :failure (bounded-activity-failure (:failure status))
   :sequence (:sequence status)
   :accepted (:accepted status)
   :capped? (:capped? status)
   :durability (:durability status)
   :closed? (:closed? status)})

(defn- activity-error-document
  "Bounded worker-error document for an activity-enabled run whose journal
  observer failed. The narrow status projection carries no path, exception
  message, Throwable, or byte array."
  [schedule status]
  {protocol-key protocol-version
   status-key :worker-error
   schedule-key schedule
   error-key (trace/canonical-value
              {:phase :activity-journal
               :kind :jolt.sim/activity-failure
               :activity (bounded-activity-status status)})})

(defn- activity-adjusted-document
  "Reconciles one result document with the final activity observer status. A
  healthy observer leaves the document untouched. A failed observer
  invalidates an apparent completion into a bounded activity worker-error;
  an application failure or earlier worker error remains primary and carries
  the bounded observer status as secondary diagnostics under
  `:jolt.sim.explore/activity`."
  [document schedule status]
  (if (not= :failed (:health status))
    document
    (if (= :completed (get document status-key))
      (activity-error-document schedule status)
      (assoc document
             activity-key
             (trace/canonical-value (bounded-activity-status status))))))

(defn- lifecycle-event
  "One closed v1 activity event for a scenario lifecycle transition. The data
  map carries only the bounded scenario identifier: no input, result,
  Throwable, message, or path."
  [tag scenario]
  [tag nil nil {:scenario scenario}])

(defn- invoke-scenario
  "Invokes the resolved scenario var, emitting the exact started/completed/
  failed lifecycle vectors when an activity observer is supplied. Emission
  never throws; a substrate failure absorbs into observer state and is
  reconciled with the result document after close. A scenario exception
  propagates unchanged as the primary failure."
  [scenario-var overrides input scenario observer]
  (if (nil? observer)
    (@scenario-var overrides input)
    (activity/call-with-observer
     observer
     (fn []
       (activity/emit! (lifecycle-event scenario-started-tag scenario))
       (try
         (let [value (@scenario-var overrides input)]
           (activity/emit! (lifecycle-event scenario-completed-tag scenario))
           value)
         (catch :default error
           (activity/emit! (lifecycle-event scenario-failed-tag scenario))
           (throw error)))))))

(defn- open-activity-observer!
  "Opens the run's activity observer when the trusted parent reserved the
  activity journal path in this worker's environment. Returns nil on the
  ordinary disabled path (key absent or empty); the caller never supplies the
  path through any other channel."
  []
  (let [path (System/getenv activity-journal-env-key)]
    (when (and (string? path) (seq path))
      (activity/open-observer! {:path path :run-id (activity-run-id path)}))))

(defn- activity-open-failed?
  "True when an opened observer already sits in the absorbing failed state."
  [observer]
  (and (some? observer)
       (= :failed (:health (activity/observer-status observer)))))

(defn execute-request
  "Executes one already-materialized request and returns a result document.

  Application-body exceptions are valid `:failed` exploration outcomes. A
  marked scenario declared without an input binding rejecting nonnil input is
  a `:worker-error` in phase `:scenario-input`, because that is an invalid case
  contract rather than a discovered application counterexample. Request
  validation, resolution, and result/error/input serialization failures are
  likewise `:worker-error`. A nil request schedule drives no
  `:future-schedule` override. The one-argument arity does no file I/O; the
  optional `on-ready` callback may publish a sideband readiness marker. The
  optional third argument is an already-open `jolt.sim.activity` observer (nil
  for the ordinary disabled path): lifecycle events are emitted around the
  scenario invocation, but observer open/close and health reconciliation stay
  with the caller."
  ([request]
   (execute-request request (fn [] nil)))
  ([request on-ready]
   (execute-request request on-ready nil))
  ([request on-ready activity-observer]
   (let [validation
         (try
           {:request (validate-request! request)}
           (catch :default error
             {:error error}))]
     (if-let [error (:error validation)]
       (worker-error-document
        (when (map? request) (get request schedule-key))
        :request-validation
        error)
       (let [request (:request validation)
             scenario (get request scenario-key)
             schedule (get request schedule-key)]
         (try
           (let [input (trace/restore-value (get request input-key))
                 scenario-var (resolve-scenario!
                               scenario (some? activity-observer))
                 accepts-input? (:jolt.sim/accepts-input (meta scenario-var))]
             (if (and (some? input) (not accepts-input?))
               (worker-error-document
                schedule :scenario-input (rejects-input-error scenario input))
               (let [ready
                     (try
                       (on-ready)
                       {:ok? true}
                       (catch :default error
                         {:ok? false :error error}))]
                 (if-not (:ok? ready)
                   (worker-error-document
                    schedule :ready-signal (:error ready))
                   (let [overrides (if schedule {:future-schedule schedule} {})
                         outcome
                         (try
                           {:ok? true
                            :value (invoke-scenario
                                    scenario-var overrides input
                                    scenario activity-observer)}
                           (catch :default error
                             {:ok? false :error error}))]
                     (if (:ok? outcome)
                       (try
                         (completed-document schedule (:value outcome))
                         (catch :default error
                           (worker-error-document
                            schedule :result-encoding error)))
                       (try
                         (failed-document schedule (:error outcome))
                         (catch :default error
                           (worker-error-document
                            schedule :error-encoding error)))))))))
           (catch :default error
             (worker-error-document schedule :scenario-resolution error))))))))

(defn decode-result
  "Validates and restores one result document for `expected-schedule`.

  Returns an ordinary outcome map with `:status`, `:schedule`, and either
  `:result` or `:error`. A failed or worker-error document may additionally
  carry `:jolt.sim.explore/activity`, the bounded observer-status projection
  recorded when an activity-enabled run's journal substrate failed alongside
  the primary outcome; it is restored as `:activity`. Byte arrays and
  collection containers are freshly restored from the canonical payload."
  [expected-schedule document]
  (when-not (valid-request-schedule? expected-schedule)
    (throw (protocol-error :invalid-expected-schedule
                           {:schedule expected-schedule})))
  (when-not (map? document)
    (throw (protocol-error :result-not-a-map
                           {:result-class (str (class document))})))
  (when-not (= protocol-version (get document protocol-key))
    (throw (protocol-error :protocol-version
                           {:expected protocol-version
                            :actual (get document protocol-key)})))
  (when-not (= expected-schedule (get document schedule-key))
    (throw (protocol-error :schedule-mismatch
                           {:expected expected-schedule
                            :actual (get document schedule-key)})))
  (let [status (get document status-key)
        expected-keys
        (if (= :completed status)
          completed-result-keys
          error-result-keys)
        actual-keys (set (keys document))]
    (when-not (contains? #{:completed :failed :worker-error} status)
      (throw (protocol-error :result-status {:status status})))
    ;; The optional activity key carries bounded secondary diagnostics on
    ;; error documents only; a completed document must never claim them.
    (when-not (or (= expected-keys actual-keys)
                  (and (not= :completed status)
                       (= (conj expected-keys activity-key) actual-keys)))
      (throw (protocol-error :result-keys
                             {:status status
                              :expected expected-keys
                              :actual actual-keys})))
    (when (contains? document activity-key)
      (when-not (trace/canonical-form? (get document activity-key))
        (throw (protocol-error :result-payload
                               {:status status :field :activity})))
      (when-not (activity/valid-observer-status?
                 (trace/restore-value (get document activity-key)))
        (throw (protocol-error :result-payload
                               {:status status :field :activity}))))
    (let [encoded (get document
                       (if (= :completed status) value-key error-key))]
      (when-not (trace/canonical-form? encoded)
        (throw (protocol-error :result-payload
                               {:status status :payload encoded})))
      (cond-> {:status status
               :schedule expected-schedule}
        (= :completed status)
        (assoc :result (trace/restore-value encoded))

        (not= :completed status)
        (assoc :error (trace/restore-value encoded))

        (contains? document activity-key)
        (assoc :activity (trace/restore-value (get document activity-key)))))))

(defn decode-result-edn
  "Reads and decodes a result document from one EDN string."
  [expected-schedule text]
  (let [document
        (try
          (edn/read-string text)
          (catch :default error
            (throw
             (protocol-error :malformed-result-edn
                             {:error (safe-error :result-reading error)}))))]
    (decode-result expected-schedule document)))

(defn- write-document! [path document]
  (spit path (trace/canonical-edn document))
  document)

(defn -main [& args]
  (when-not (contains? #{2 3} (count args))
    (throw
     (protocol-error :worker-arguments
                     {:expected ["request-path" "result-path"
                                 "optional-ready-path"]
                      :actual-count (count args)})))
  (let [request-path (nth args 0)
        result-path (nth args 1)
        ready-path (nth args 2 nil)
        request-holder (atom nil)]
    (try
      (let [request (edn/read-string (slurp request-path))]
        (reset! request-holder request)
        (let [schedule (when (map? request) (get request schedule-key))
              ;; The observer opens before scenario execution. Any failure to
              ;; open is itself an invalidated activity-enabled run, reported
              ;; as bounded diagnostics rather than escaping this entrypoint.
              opened
              (try
                {:observer (open-activity-observer!)}
                (catch :default _
                  {:status {:health :failed
                            :failure {:phase :open
                                      :reason :observer-open-threw}
                            :sequence 0
                            :accepted 0
                            :capped? false
                            :durability :process-crash
                            :closed? true}}))
              observer (:observer opened)]
          (cond
            (some? (:status opened))
            (write-document!
             result-path
             (activity-error-document schedule (:status opened)))

            (nil? observer)
            ;; Ordinary disabled path: no observer, no journal, unchanged
            ;; request/result behavior.
            (write-document!
             result-path
             (execute-request
              request
              (fn []
                (when ready-path
                  (write-ready! ready-path)))))

            (activity-open-failed? observer)
            ;; Open-time refusal or failure invalidates the run before the
            ;; scenario executes; close best-effort to surface the preserved
            ;; first failure (never the target path or a Throwable).
            (write-document!
             result-path
             (activity-error-document
              schedule
              (activity/close-observer! observer)))

            :else
            (let [document
                  (execute-request
                   request
                   (fn []
                     (when ready-path
                       (write-ready! ready-path)))
                   observer)]
              (write-document!
               result-path
               (activity-adjusted-document
                document schedule (activity/close-observer! observer)))))))
      (catch :default error
        ;; A malformed request generated outside this library may not contain a
        ;; usable schedule. The parent will reject the nil echo as a protocol
        ;; mismatch, but still gets narrow diagnostics when the file is writable.
        (let [request @request-holder
              schedule (when (map? request) (get request schedule-key))]
          (write-document!
           result-path
           (worker-error-document schedule :request-reading error)))))))
