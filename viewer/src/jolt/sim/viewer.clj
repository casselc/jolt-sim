(ns jolt.sim.viewer
  "Loopback-only offline document inspection and fresh-process replay UI.

  This optional dependency root is a thin HTTP adapter over the existing
  trace and Case/Outcome validators, report views, and
  `jolt.sim.repl/replay-document!`. It is not a scheduler, controller,
  monitor, evidence store, or second replay implementation.

  Every browser request must declare its document kind explicitly
  (`:trace` or `:case-outcome`) through the `X-Jolt-Sim-Document-Kind`
  header; the server never infers or guesses a schema from the uploaded
  bytes. Trace documents render through `jolt.sim.report/trace->html` and
  are never replayable; Case/Outcome documents render through
  `jolt.sim.report/case-outcome->html` and keep the existing replay path.

  Browser requests carry only one retained document. Trusted
  runtime configuration (worker command, project directory, timeout, artifact
  roots, and environment) is fixed when the server starts and can never be
  supplied by a browser request. Replays are additionally restricted to an
  explicit scenario allowlist and require a process capability token.

  `jolt-tcp`, underneath jolt-http, binds its listener to 127.0.0.1. The token
  remains required because other local processes and browser-origin attacks
  are still inside that network boundary."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.repl :as sim-repl]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]))

(def invalid-config ::invalid-config)
(def request-too-large ::request-too-large)
(def document-kind-required ::document-kind-required)
(def unknown-document-kind ::unknown-document-kind)
(def trace-not-replayable ::trace-not-replayable)

(def ^:private config-keys
  #{:port :capability-token :max-document-bytes
    :allowed-scenarios :runtime-config :presentation-registry})

(def ^:private replay-coordinate-keys
  #{:scenario :mode :input :schedule})

(def ^:private runtime-config-keys
  #{:worker-command :timeout-ms :startup-timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts?})

(def ^:private service-keys
  #{:render-trace :render-case-outcome :replay-document})

(def ^:private default-max-document-bytes (* 1024 1024))
(def ^:private maximum-max-document-bytes (* 16 1024 1024))
(def ^:private minimum-token-length 32)

(def ^:private progress-log-byte-limit 65536)

(def ^:private empty-progress-diagnostic
  {:bytes 0 :truncated? false :text ""})

(defn- config-error [reason detail]
  (ex-info "jolt-sim viewer rejected its startup configuration"
           {:type invalid-config :reason reason :detail detail}))

(defn- namespaced-symbol? [value]
  (and (symbol? value) (some? (namespace value))))

(defn- valid-worker-command? [value]
  (and (vector? value)
       (seq value)
       (every? #(and (string? %) (not (string/blank? %))) value)))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- string-map? [value]
  (and (map? value)
       (every? (fn [[key value]]
                 (and (string? key) (string? value)))
               value)))

(defn validate-config!
  "Validates and normalizes trusted viewer startup configuration.

  The capability token must contain at least 32 characters. Runtime config is
  the exact ambient map later passed to `replay-document!`; replay-coordinate
  keys are rejected at startup as well as by the replay API."
  [config]
  (when-not (map? config)
    (throw (config-error :not-a-map (str (class config)))))
  (let [unknown (into #{} (remove config-keys) (keys config))]
    (when (seq unknown)
      (throw (config-error :unknown-keys unknown))))
  (let [port (get config :port 8788)
        token (:capability-token config)
        max-bytes (get config :max-document-bytes
                       default-max-document-bytes)
        scenarios (:allowed-scenarios config)
        runtime (:runtime-config config)
        collisions (when (map? runtime)
                     (into #{}
                           (filter replay-coordinate-keys)
                           (keys runtime)))
        unknown-runtime-keys (when (map? runtime)
                               (into #{}
                                     (remove runtime-config-keys)
                                     (keys runtime)))]
    (when-not (and (integer? port) (<= 0 (long port) 65535))
      (throw (config-error :invalid-port port)))
    (when-not (and (string? token)
                   (>= (count token) minimum-token-length))
      (throw (config-error :weak-capability-token
                           {:minimum-length minimum-token-length})))
    (when-not (and (integer? max-bytes)
                   (<= 1 (long max-bytes) maximum-max-document-bytes))
      (throw (config-error :invalid-max-document-bytes
                           {:value max-bytes
                            :maximum maximum-max-document-bytes})))
    (when-not (and (set? scenarios)
                   (seq scenarios)
                   (every? namespaced-symbol? scenarios))
      (throw (config-error :invalid-allowed-scenarios scenarios)))
    (when-not (map? runtime)
      (throw (config-error :runtime-config-not-a-map
                           (str (class runtime)))))
    (when (contains? config :presentation-registry)
      (try
        (presentation/validate-registry! (:presentation-registry config))
        (catch :default error
          (throw
           (ex-info
            "jolt-sim viewer rejected its presentation registry"
            {:type invalid-config
             :reason :invalid-presentation-registry
             :detail (select-keys (ex-data error) [:reason :detail])}
            error)))))
    (when (seq collisions)
      (throw (config-error :runtime-coordinate-collision collisions)))
    (when (seq unknown-runtime-keys)
      (throw (config-error :unknown-runtime-keys unknown-runtime-keys)))
    (when-not (valid-worker-command? (:worker-command runtime))
      (throw (config-error :invalid-worker-command
                           (:worker-command runtime))))
    (when-not (and (string? (:dir runtime))
                   (not (string/blank? (:dir runtime))))
      (throw (config-error :invalid-project-directory (:dir runtime))))
    (when-not (positive-integer? (:timeout-ms runtime))
      (throw (config-error :invalid-timeout-ms (:timeout-ms runtime))))
    (doseq [[key reason] [[:startup-timeout-ms :invalid-startup-timeout-ms]
                          [:kill-grace-ms :invalid-kill-grace-ms]]]
      (when (and (contains? runtime key)
                 (not (positive-integer? (get runtime key))))
        (throw (config-error reason (get runtime key)))))
    (when (and (contains? runtime :extra-env)
               (not (string-map? (:extra-env runtime))))
      (throw (config-error :invalid-extra-env (:extra-env runtime))))
    (when (and (contains? runtime :temp-dir)
               (not (and (string? (:temp-dir runtime))
                         (not (string/blank? (:temp-dir runtime))))))
      (throw (config-error :invalid-temp-dir (:temp-dir runtime))))
    (when (and (contains? runtime :retain-completed-artifacts?)
               (not (boolean? (:retain-completed-artifacts? runtime))))
      (throw (config-error :invalid-retain-completed-artifacts
                           (:retain-completed-artifacts? runtime))))
    (assoc config
           :port port
           :max-document-bytes max-bytes)))

(defmacro ^:private embedded-resource [resource]
  (let [found (io/resource resource)]
    (when-not found
      (throw (ex-info "jolt-sim viewer resource is missing during analysis"
                      {:type ::missing-resource :resource resource})))
    (slurp found)))

(def ^:private viewer-html
  (embedded-resource "jolt/sim/viewer.html"))

(def ^:private viewer-js
  (embedded-resource "jolt/sim/viewer.js"))

(def ^:private common-headers
  {"Cache-Control" "no-store"
   "Referrer-Policy" "no-referrer"
   "X-Content-Type-Options" "nosniff"
   "Content-Security-Policy"
   (str "default-src 'none'; script-src 'self'; "
        "style-src 'self' 'unsafe-inline'; connect-src 'self'; "
        "frame-src 'self'; frame-ancestors 'none'; base-uri 'none'; "
        "form-action 'none'")})

(defn- response [status content-type body]
  {:status status
   :headers (assoc common-headers "Content-Type" content-type)
   :body body})

(defn- error-response [status reason detail]
  (update
   (response status
             "application/edn; charset=utf-8"
             (trace/canonical-edn
              (cond-> {:error reason}
                (some? detail) (assoc :detail detail))))
   :headers assoc "Connection" "close"))

(defn- bounded-progress-text
  "Reads at most `progress-log-byte-limit` bytes of a live, possibly
  partially-written log file, through a bounded FileInputStream prefix read.
  The read itself enforces the bound, so a file that grows between opening
  the stream and finishing the read can never push more than the limit
  through this path -- unlike measuring the file's length first and then
  slurping it whole. Tolerates a missing or disappearing file. Reports the
  file's length observed once the bounded read completes and whether the
  file held more bytes than the returned prefix, and always closes the
  stream."
  [path]
  (try
    (let [stream (java.io.FileInputStream. path)]
      (try
        (let [buffer (byte-array progress-log-byte-limit)
              filled (loop [offset 0]
                       (if (>= offset progress-log-byte-limit)
                         offset
                         (let [read-count
                               (.read stream buffer offset
                                      (- progress-log-byte-limit offset))]
                           (if (neg? read-count)
                             offset
                             (recur (+ offset read-count))))))
              extra-byte? (and (= filled progress-log-byte-limit)
                               (not (neg? (.read stream))))
              observed-length (try
                                (.length (java.io.File. path))
                                (catch :default _ filled))
              length (max filled observed-length)]
          {:bytes length
           :truncated? (or extra-byte? (> length filled))
           :text (String. (java.util.Arrays/copyOf buffer filled)
                          "UTF-8")})
        (finally (.close stream))))
    (catch :default _
      empty-progress-diagnostic)))

(defn- initial-replay-state [] {:phase :idle})

(def ^:private active-status-rank
  {:starting 0 :worker-ready 1 :running 2})

(defn- later-active-status [previous observed]
  (if (> (get active-status-rank observed -1)
         (get active-status-rank previous -1))
    observed
    previous))

(defn- later-diagnostic [previous observed]
  (cond
    (and (number? (:bytes observed))
         (or (not (number? (:bytes previous)))
             (>= (:bytes observed) (:bytes previous))))
    observed

    (number? (:bytes previous)) previous
    :else observed))

(defn- observe-active-replay
  "Samples trusted replay artifacts and monotonically latches active
   milestones. Artifact cleanup or a transient read cannot regress a replay
   from running to starting, clear result-observed?, or discard a longer
   bounded diagnostic prefix before the terminal outcome is installed."
  [state]
  (if (and (= :active (:phase state)) (:run-dir state))
    (let [run-dir (:run-dir state)
          ready-path (str (fs/path run-dir "worker-ready.edn"))
          result-path (str (fs/path run-dir "result.edn"))
          stdout (bounded-progress-text (str (fs/path run-dir "stdout.log")))
          stderr (bounded-progress-text (str (fs/path run-dir "stderr.log")))
          ready? (fs/exists? ready-path)
          result? (fs/exists? result-path)
          any-output? (or (pos? (or (:bytes stdout) 0))
                          (pos? (or (:bytes stderr) 0)))
          observed-status (cond
                            (or result? (and ready? any-output?)) :running
                            ready? :worker-ready
                            any-output? :running
                            :else :starting)]
      (-> state
          (assoc :status (later-active-status
                          (get state :status :starting)
                          observed-status)
                 :result-observed? (or (:result-observed? state) result?)
                 :stdout (later-diagnostic (:stdout state) stdout)
                 :stderr (later-diagnostic (:stderr state) stderr))))
    state))

(defn- replay-progress-state
  "Derives the small closed `/api/replay-progress` status from the trusted
  active run directory's fixed basenames. `result.edn` existence is checked
  but never parsed here: the terminal snapshot instead reuses the already
  safely-decoded outcome diagnostics recorded once the replay call returns."
  [state]
  (case (:phase state)
    :idle {:status :idle}

    :terminal
    {:status (:status state)
     :stdout (:stdout state)
     :stderr (:stderr state)}

    :active
    (cond-> {:status (get state :status :starting)
             :result-observed? (boolean (:result-observed? state))}
      (contains? state :stdout) (assoc :stdout (:stdout state))
      (contains? state :stderr) (assoc :stderr (:stderr state)))))

(defn- diagnostic-wire
  "The closed wire projection of one bounded stdout/stderr diagnostic."
  [{:keys [bytes truncated? text]}]
  {"bytes" bytes "truncated?" (boolean truncated?) "text" text})

(defn- progress-wire
  "The closed wire projection of `replay-progress-state`'s small status shape."
  [state]
  (cond-> {"status" (name (:status state))}
    (contains? state :result-observed?)
    (assoc "result-observed?" (boolean (:result-observed? state)))
    (contains? state :stdout) (assoc "stdout" (diagnostic-wire (:stdout state)))
    (contains? state :stderr) (assoc "stderr" (diagnostic-wire (:stderr state)))))

(defn- replay-progress-json [active-replay]
  (json/write-str
   (progress-wire
    (replay-progress-state (swap! active-replay observe-active-replay)))))

(defn- outcome->progress-status [outcome]
  (if (= :completed (:status outcome)) :completed :failed))

(defn- secure-string= [expected supplied]
  (and (string? supplied)
       (= (count expected) (count supplied))
       (zero?
        (loop [index 0 difference 0]
          (if (= index (count expected))
            difference
            (recur (inc index)
                   (bit-or difference
                           (bit-xor (int (.charAt ^String expected index))
                                    (int (.charAt ^String supplied index))))))))))

(defn- authorized? [config request]
  (secure-string= (:capability-token config)
                  (get-in request [:headers "x-jolt-sim-capability"])))

(defn- edn-content-type? [request]
  (let [value (get-in request [:headers "content-type"])]
    (and (string? value)
         (= "application/edn"
            (-> value
                string/lower-case
                (string/split #";" 2)
                first
                string/trim)))))

(defn- content-length-too-large? [request limit]
  (let [raw (get-in request [:headers "content-length"])
        parsed (when (string? raw) (parse-long raw))]
    (and (some? parsed) (> (long parsed) (long limit)))))

(defn- concat-chunks [chunks total]
  (let [output (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [length (alength ^bytes chunk)]
          (dotimes [index length]
            (aset output (+ offset index) (aget ^bytes chunk index)))
          (recur (rest remaining) (+ offset length)))
        output))))

(defn- bounded-body-bytes [request limit]
  (when (content-length-too-large? request limit)
    (throw (ex-info "viewer request body exceeds its configured limit"
                    {:type request-too-large :limit limit})))
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv (:body request))]
      (let [total (+ total (alength ^bytes chunk))]
        (when (> total limit)
          (throw (ex-info "viewer request body exceeds its configured limit"
                          {:type request-too-large
                           :limit limit
                           :actual total})))
        (recur (conj chunks chunk) total))
      (concat-chunks chunks total))))

(defn- required-document-kind!
  "Returns the declared document kind or throws a typed error. The kind is
  never inferred from the document shape: a missing or unknown kind is
  rejected before the request body is read. Closed string matching avoids
  interning attacker-controlled header values as keywords."
  [request]
  (let [raw (get-in request [:headers "x-jolt-sim-document-kind"])
        normalized (when (string? raw)
                     (string/lower-case (string/trim raw)))]
    (when (string/blank? normalized)
      (throw (ex-info "viewer request is missing its document kind"
                      {:type document-kind-required})))
    (case normalized
      "trace" :trace
      "case-outcome" :case-outcome
      (throw (ex-info "viewer request has an unknown document kind"
                      {:type unknown-document-kind :kind normalized})))))

(defn- read-document-by-kind
  "Reads and fail-closed validates one document through the codec selected by
  the explicitly declared kind. Each codec rejects the other document's
  shape, so a misdeclared kind can never be silently reinterpreted."
  [kind text]
  (case kind
    :trace (trace/read-edn text)
    :case-outcome (case-outcome/read-edn text)))

(defn- request-document [config request kind]
  (let [bytes (bounded-body-bytes request (:max-document-bytes config))]
    (read-document-by-kind kind (String. bytes "UTF-8"))))

(defn- allowed-replay! [config document]
  (let [scenario (:scenario (case-outcome/restore-case document))]
    (when-not (contains? (:allowed-scenarios config) scenario)
      (throw (ex-info "viewer replay scenario is not allowlisted"
                      {:type ::scenario-not-allowed :scenario scenario})))
    document))

(defn- expected-request-error-response [error]
  (let [data (ex-data error)
        type (:type data)]
    (cond
      (= request-too-large type)
      (error-response 413 :request-too-large
                      (select-keys data [:limit :actual]))

      (or (= case-outcome/invalid-document type)
          (= trace/invalid-document type))
      (error-response 400 :invalid-document
                      (select-keys data [:reason]))

      (= document-kind-required type)
      (error-response 400 :document-kind-required nil)

      (= unknown-document-kind type)
      (error-response 400 :unknown-document-kind
                      (select-keys data [:kind]))

      (= trace-not-replayable type)
      (error-response 400 :trace-not-replayable
                      (select-keys data [:kind]))

      (= ::scenario-not-allowed type)
      (error-response 403 :scenario-not-allowed
                      (select-keys data [:scenario]))

      :else nil)))

(defn- execute-document-request [config document-active? request operation]
  (cond
    (not (authorized? config request))
    (error-response 403 :forbidden nil)

    (not (edn-content-type? request))
    (error-response 415 :expected-application-edn nil)

    (not (compare-and-set! document-active? false true))
    ;; Reject before touching the streaming request body. jolt-http's parser
    ;; and Ring handlers share a bounded executor, so admitting two blocking
    ;; body consumers into the two-thread viewer pool could starve the parser
    ;; work required to make either body complete.
    (error-response 429 :viewer-busy nil)

    :else
    (try
      (let [kind (required-document-kind! request)]
        (operation kind (request-document config request kind)))
      (catch :default error
        (if-let [expected (expected-request-error-response error)]
          expected
          (throw error)))
      (finally
        (reset! document-active? false)))))

(defn- render-service
  "Selects the render service for the explicitly declared document kind.
  Trace documents render through the trace report path and Case/Outcome
  documents through the Case/Outcome report path; the two schemas are never
  guessed at."
  [services kind]
  (case kind
    :trace (:render-trace services)
    :case-outcome (:render-case-outcome services)))

(defn- default-services [config]
  {:render-trace
   (fn [document]
     (report/trace->html
      document
      {:presentation-registry (:presentation-registry config)}))
   :render-case-outcome report/case-outcome->html
   :replay-document sim-repl/replay-document!})

(defn make-handler
  "Creates a synchronous jolt-http handler.

  Every `POST /api/render` and `POST /api/replay` request must declare its
  document kind explicitly through the `X-Jolt-Sim-Document-Kind` header
  (`trace` or `case-outcome`); a missing or unknown kind is rejected before
  the request body is read, and the server never infers a schema from the
  uploaded bytes.

  The optional `services` map is a narrow embedding/test seam. Its keys are
  `:render-trace` (`trace-doc -> html`), `:render-case-outcome`
  (`case-outcome-doc -> html`), and `:replay-document`
  (`case-outcome-doc runtime-config -> outcome`), all required. Browser data
  never selects any function or supplies runtime configuration. Replay
  accepts only Case/Outcome documents: a declared `:trace` kind is rejected
  explicitly before any restore or worker execution.

  `GET /api/replay-progress` reports the one active or most recently
  completed replay's status (`:idle`, `:starting`, `:worker-ready`,
  `:running`, `:completed`, or `:failed`) plus bounded stdout/stderr text, by
  reading only fixed basenames (`worker-ready.edn`, `stdout.log`,
  `stderr.log`, and -- existence only, never parsed -- `result.edn`) from the
  trusted active run directory. It never accepts a filesystem path from the
  browser."
  ([config]
   (make-handler config (default-services config)))
  ([config services]
   (let [config (validate-config! config)
         unknown-services (into #{} (remove service-keys) (keys services))
         document-active? (atom false)
         active-replay (atom (initial-replay-state))]
     (when (seq unknown-services)
       (throw (config-error :unknown-service-keys unknown-services)))
     (when-not (and (fn? (:render-trace services))
                    (fn? (:render-case-outcome services))
                    (fn? (:replay-document services)))
       (throw (config-error :invalid-services (set (keys services)))))
     (fn [request]
       (let [method (:request-method request)
             uri (:uri request)]
         (cond
           (and (= :get method) (= "/" uri))
           (response 200 "text/html; charset=utf-8" viewer-html)

           (and (= :get method) (= "/viewer.js" uri))
           (response 200 "application/javascript; charset=utf-8" viewer-js)

           (and (= :get method) (= "/api/replay-progress" uri))
           (if-not (authorized? config request)
             (error-response 403 :forbidden nil)
             (response 200 "application/json; charset=utf-8"
                       (replay-progress-json active-replay)))

           (and (= :post method) (= "/api/render" uri))
           (execute-document-request
            config document-active? request
            (fn [kind document]
              (response 200 "text/html; charset=utf-8"
                        ((render-service services kind) document))))

           (and (= :post method) (= "/api/replay" uri))
           (execute-document-request
            config document-active? request
            (fn [kind document]
              ;; Reject trace documents before any restore or worker
              ;; execution: replay is a Case/Outcome-only path.
              (when-not (= :case-outcome kind)
                (throw (ex-info "viewer replay accepts only Case/Outcome documents"
                                {:type trace-not-replayable :kind kind})))
              (allowed-replay! config document)
              (reset! active-replay
                      {:phase :active
                       :run-dir nil
                       :status :starting
                       :result-observed? false})
              (try
                (let [observer
                      (fn [run-dir]
                        (swap! active-replay assoc :run-dir run-dir))
                      runtime (assoc (:runtime-config config)
                                     :on-run-dir observer)
                      outcome ((:replay-document services) document runtime)]
                  (reset! active-replay
                          {:phase :terminal
                           :status (outcome->progress-status outcome)
                           :stdout (get-in outcome [:diagnostics :stdout]
                                           empty-progress-diagnostic)
                           :stderr (get-in outcome [:diagnostics :stderr]
                                           empty-progress-diagnostic)})
                  (response 200 "application/edn; charset=utf-8"
                            (trace/canonical-edn outcome)))
                (catch :default error
                  (reset! active-replay
                          {:phase :terminal
                           :status :failed
                           :stdout empty-progress-diagnostic
                           :stderr empty-progress-diagnostic})
                  (throw error)))))

           :else
           (error-response 404 :not-found nil)))))))

(defn start!
  "Starts the loopback viewer and returns the jolt-http server handle.

  The optional services arity is the same narrow embedding/test seam accepted
  by `make-handler`; ordinary callers always use the real trace/Case/Outcome
  report and replay services."
  ([config]
   (start! config (default-services config)))
  ([config services]
   (let [config (validate-config! config)]
     (http/run-server (make-handler config services)
                      :port (:port config)
                      ;; jolt-http's parser and blocking Ring body consumer use
                      ;; this same executor. Two threads guarantee parser
                      ;; progress for the one body-consuming POST admitted by
                      ;; make-handler's shared single-flight gate.
                      :pool-size 2
                      :reuse-address? true))))

(defn stop!
  "Stops a viewer returned by `start!`."
  [server]
  (http/stop-server server))

(def ^:private end-of-config (atom nil))

(defn- read-config-edn [text]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-config)
          [trailing _] (read+string reader false end-of-config)]
      (when (identical? value end-of-config)
        (throw (config-error :empty-config nil)))
      (when-not (identical? trailing end-of-config)
        (throw (config-error :trailing-config nil)))
      (edn/read-string text))
    (catch :default error
      (if (= invalid-config (:type (ex-data error)))
        (throw error)
        (throw (config-error :unreadable-config (ex-message error)))))))

(defn- read-main-config [path]
  (let [value (read-config-edn (slurp path))]
    (when-not (map? value)
      (throw (config-error :config-file-not-a-map (str (class value)))))
    (when (contains? value :capability-token)
      (throw (config-error :token-must-come-from-environment nil)))
    (assoc value :capability-token
           (System/getenv "JOLT_SIM_VIEWER_TOKEN"))))

(defn -main
  "Starts a loopback viewer from one trusted EDN config file.

  Usage: JOLT_SIM_VIEWER_TOKEN=<32+ chars> jolt -M:viewer CONFIG.edn

  The token is deliberately supplied through the environment rather than the
  config file. Port 0 selects an ephemeral loopback port. The primordial
  thread owns SIGINT. On POSIX, server workers inherit its blocked signal mask;
  on Windows, the host uses console-interrupt delivery. In both cases,
  `park-until-interrupt` handles Ctrl+C, runs the registered server shutdown,
  and exits cleanly. Programmatic callers continue to use `start!`/`stop!`."
  [& args]
  (when-not (= 1 (count args))
    (throw (config-error :wrong-argument-count {:args (vec args)})))
  (let [config (validate-config! (read-main-config (first args)))]
    ;; On POSIX, block before the listener, accept loop, and handler executor
    ;; start so every worker inherits the blocked SIGINT mask. On Windows this
    ;; host operation is intentionally a no-op and the primordial thread owns
    ;; console-interrupt delivery instead. This prevents Ctrl+C from landing in
    ;; a worker's foreign wait or interrupting a mutex-backed promise deref.
    (jolt.host/block-sigint)
    (let [server (start! config)
          stopped? (atom false)
          stop-once! (fn []
                       (when (compare-and-set! stopped? false true)
                         (stop! server)))]
      (try
        (jolt.host/add-shutdown-hook stop-once!)
        (println (str "Ripple: http://127.0.0.1:" (:port server)))
        (flush)
        ;; On POSIX this interruptible main-thread pump installs the SIGINT
        ;; handler and unblocks SIGINT only here. On every host it invokes the
        ;; registered hooks and exits 0 for Ctrl+C.
        (jolt.host/park-until-interrupt)
        (finally
          ;; Also cover output, hook-registration, or host-pump failures. The
          ;; hook and fallback share one ownership transition.
          (stop-once!))))))
