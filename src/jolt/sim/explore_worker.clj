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
  request is rejected as an ordinary protocol-version mismatch."
  (:require [clojure.edn :as edn]
            [jolt.sim.future-schedule :as future-schedule]
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

(defn- resolve-scenario! [scenario]
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

(defn execute-request
  "Executes one already-materialized request and returns a result document.

  Application-body exceptions are valid `:failed` exploration outcomes. A
  marked scenario declared without an input binding rejecting nonnil input is
  a `:worker-error` in phase `:scenario-input`, because that is an invalid case
  contract rather than a discovered application counterexample. Request
  validation, resolution, and result/error/input serialization failures are
  likewise `:worker-error`. A nil request schedule drives no
  `:future-schedule` override. The one-argument arity does no file I/O; the
  optional `on-ready` callback may publish a sideband readiness marker."
  ([request]
   (execute-request request (fn [] nil)))
  ([request on-ready]
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
                 scenario-var (resolve-scenario! scenario)
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
                            :value (@scenario-var overrides input)}
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
  `:result` or `:error`. Byte arrays and collection containers are freshly
  restored from the canonical payload."
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
          error-result-keys)]
    (when-not (contains? #{:completed :failed :worker-error} status)
      (throw (protocol-error :result-status {:status status})))
    (when-not (= expected-keys (set (keys document)))
      (throw (protocol-error :result-keys
                             {:status status
                              :expected expected-keys
                              :actual (set (keys document))})))
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
        (assoc :error (trace/restore-value encoded))))))

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
        (write-document!
         result-path
         (execute-request
          request
          (fn []
            (when ready-path
              (write-ready! ready-path))))))
      (catch :default error
        ;; A malformed request generated outside this library may not contain a
        ;; usable schedule. The parent will reject the nil echo as a protocol
        ;; mismatch, but still gets narrow diagnostics when the file is writable.
        (let [request @request-holder
              schedule (when (map? request) (get request schedule-key))]
          (write-document!
           result-path
           (worker-error-document schedule :request-reading error)))))))
