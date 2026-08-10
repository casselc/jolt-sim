(ns jolt.sim.viewer
  "Loopback-only offline document inspection and fresh-process replay UI.

  This optional dependency root is a thin HTTP adapter over the existing
  trace, Case/Outcome, and inert experiment-plan validators, report views, and
  `jolt.sim.repl/replay-document!`. It is not a scheduler, controller,
  monitor, evidence store, or second replay implementation.

  Every browser request must declare its document kind explicitly
  (`:trace`, `:case-outcome`, `:experiment-plan`, or
  `:official-maelstrom-run`) through the
  `X-Jolt-Sim-Document-Kind`
  header; the server never infers or guesses a schema from the uploaded
  bytes. Trace documents render through `jolt.sim.report/trace->html`;
  experiment-plan documents render only their safe inert projection. Retained
  official Maelstrom runs render through the shared static report. None of
  these three kinds is replayable. Case/Outcome documents render through
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
            [jolt.sim.activity-view :as activity-view]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.maelstrom.official-run :as official-run]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.presentation :as presentation]
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.repl :as sim-repl]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer.experiment :as experiment-viewer]
            [jolt.sim.viewer.eval :as viewer-eval]
            [jolt.sim.viewer.remote-session :as remote-session]
            [jolt.sim.session-view :as viewer-session]))

(def invalid-config ::invalid-config)
(def request-too-large ::request-too-large)
(def document-kind-required ::document-kind-required)
(def unknown-document-kind ::unknown-document-kind)
(def trace-not-replayable ::trace-not-replayable)
(def experiment-plan-not-replayable ::experiment-plan-not-replayable)
(def official-maelstrom-run-not-replayable
  ::official-maelstrom-run-not-replayable)
(def invalid-session-cursor ::invalid-session-cursor)
(def invalid-session-step ::invalid-session-step)
(def invalid-activity-cursor ::invalid-activity-cursor)
(def ^:private invalid-session-frame ::invalid-session-frame)
(def ^:private invalid-session-step-result ::invalid-session-step-result)

(def ^:private config-keys
  #{:port :capability-token :max-document-bytes
    :allowed-scenarios :runtime-config :presentation-registry
    :activity-presentation-registry :run-presets :session-instance-id})

(def ^:private run-preset-keys
  #{:id :label :scenario :profile-id :schedule :plan-document :regimes})

(def ^:private run-regime-keys
  #{:id :label :summary :scope :input})

(def ^:private replay-coordinate-keys
  #{:scenario :mode :input :schedule})

(def ^:private runtime-config-keys
  #{:worker-command :timeout-ms :startup-timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts?
    :activity-journal?})

(def ^:private service-keys
  #{:render-trace :render-case-outcome :replay-document
    :read-session-frame :step-session-frame! :run-case :evaluate-form!})

(def ^:private default-max-document-bytes (* 1024 1024))
(def ^:private maximum-max-document-bytes (* 16 1024 1024))
(def ^:private minimum-token-length 32)
(def ^:private minimum-session-instance-id-length 16)
(def ^:private maximum-session-instance-id-length 128)
(def ^:private session-instance-header
  "X-Jolt-Sim-Session-Instance")

(def ^:private progress-log-byte-limit 65536)
(def ^:private maximum-session-cursor-digits 19)
(def ^:private session-step-body-limit 4096)
(def ^:private run-command-body-limit 4096)
(def ^:private eval-command-body-limit 65536)
(def ^:private maximum-run-regimes 32)
(def ^:private maximum-run-regime-label-length 128)
(def ^:private maximum-run-regime-summary-length 512)
(def ^:private maximum-run-regime-scope-size 16)
;; A signed decimal carries one leading minus sign plus the unsigned digit
;; budget, so Long/MIN_VALUE's 20 characters remain representable.
(def ^:private maximum-step-signed-decimal-chars
  (inc maximum-session-cursor-digits))

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

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- valid-session-instance-id? [value]
  (and (string? value)
       (<= minimum-session-instance-id-length
           (count value)
           maximum-session-instance-id-length)
       ;; Restrict the trusted value to RFC 3986 unreserved ASCII. Besides
       ;; keeping the coordinate portable, this excludes every HTTP header
       ;; delimiter, whitespace character, and CR/LF injection vector.
       (boolean (re-matches #"[A-Za-z0-9._~-]+" value))))

(defn- exact-map? [value expected-keys]
  (and (map? value)
       (nil? (meta value))
       (= expected-keys (set (keys value)))))

(defn- bounded-nonblank-string? [value maximum]
  (and (string? value)
       (not (string/blank? value))
       (<= (count value) maximum)))

(defn- validate-run-regime! [preset-id regime]
  (when-not (exact-map? regime run-regime-keys)
    (throw (config-error :invalid-run-regime-shape
                         {:preset-id preset-id
                          :keys (when (map? regime) (set (keys regime)))})))
  (when-not (namespaced-keyword? (:id regime))
    (throw (config-error :invalid-run-regime-id
                         {:preset-id preset-id :regime-id (:id regime)})))
  (when-not (bounded-nonblank-string?
             (:label regime) maximum-run-regime-label-length)
    (throw (config-error :invalid-run-regime-label
                         {:preset-id preset-id :regime-id (:id regime)})))
  (when-not (bounded-nonblank-string?
             (:summary regime) maximum-run-regime-summary-length)
    (throw (config-error :invalid-run-regime-summary
                         {:preset-id preset-id :regime-id (:id regime)})))
  (let [scope (:scope regime)]
    (when-not (and (vector? scope)
                   (nil? (meta scope))
                   (seq scope)
                   (<= (count scope) maximum-run-regime-scope-size)
                   (= (count scope) (count (set scope)))
                   (every? namespaced-keyword? scope))
      (throw (config-error :invalid-run-regime-scope
                           {:preset-id preset-id :regime-id (:id regime)}))))
  (let [input-form
        (try
          (trace/canonical-value (:input regime)
                                 [:run-preset preset-id :regime
                                  (:id regime) :input])
          (catch :default error
            (throw (config-error :invalid-run-regime-input
                                 {:preset-id preset-id
                                  :regime-id (:id regime)
                                  :error
                                  (select-keys (ex-data error)
                                               [:reason :path
                                                :value-class])}))))]
    ;; Rebuild from the canonical form so byte arrays and every other mutable
    ;; canonical leaf are snapshotted when the trusted catalog is installed.
    (assoc regime :input (trace/restore-value input-form))))

(defn- validate-run-regimes! [preset-id regimes]
  (when-not (and (vector? regimes)
                 (nil? (meta regimes))
                 (seq regimes)
                 (<= (count regimes) maximum-run-regimes))
    (throw (config-error :invalid-run-regimes
                         {:preset-id preset-id
                          :maximum maximum-run-regimes})))
  (let [validated (mapv #(validate-run-regime! preset-id %) regimes)
        ids (mapv :id validated)]
    (when-not (= (count ids) (count (set ids)))
      (throw (config-error :duplicate-run-regime-ids
                           {:preset-id preset-id :ids ids})))
    validated))

(defn- validate-run-preset! [scenarios preset]
  (when-not (exact-map? preset run-preset-keys)
    (throw (config-error :invalid-run-preset-shape
                         (when (map? preset) (set (keys preset))))))
  (when-not (namespaced-keyword? (:id preset))
    (throw (config-error :invalid-run-preset-id (:id preset))))
  (when-not (and (string? (:label preset))
                 (not (string/blank? (:label preset))))
    (throw (config-error :invalid-run-preset-label (:label preset))))
  (when-not (namespaced-symbol? (:scenario preset))
    (throw (config-error :invalid-run-preset-scenario (:scenario preset))))
  (when-not (contains? scenarios (:scenario preset))
    (throw (config-error :run-preset-scenario-not-allowed
                         (:scenario preset))))
  (when-not (keyword? (:profile-id preset))
    (throw (config-error :invalid-run-preset-profile
                         (:profile-id preset))))
  (when-not (or (nil? (:schedule preset))
                (future-schedule/valid-schedule? (:schedule preset)))
    (throw (config-error :invalid-run-preset-schedule (:schedule preset))))
  (let [plan (try
               (experiment-viewer/validate-document! (:plan-document preset))
               (catch :default error
                 (throw (config-error :invalid-run-preset-plan
                                      (select-keys (ex-data error)
                                                   [:reason])))))
        regimes (validate-run-regimes! (:id preset) (:regimes preset))]
    (when-not (= (:profile-id preset) (:profile-id plan))
      (throw (config-error :run-preset-profile-mismatch
                           {:preset (:profile-id preset)
                            :plan (:profile-id plan)})))
    (assoc preset
           :regimes regimes
           :schedule (when-let [schedule (:schedule preset)] (vec schedule))
           :plan-document plan)))

(defn- validate-run-presets! [scenarios presets]
  (when-not (and (vector? presets) (nil? (meta presets)))
    (throw (config-error :invalid-run-presets (str (class presets)))))
  (let [validated (mapv #(validate-run-preset! scenarios %) presets)
        ids (mapv :id validated)]
    (when-not (= (count ids) (count (set ids)))
      (throw (config-error :duplicate-run-preset-ids ids)))
    validated))

(defn validate-config!
  "Validates and normalizes trusted viewer startup configuration.

  The capability token must contain at least 32 characters. The optional
  session instance ID is a 16--128 character RFC 3986 unreserved-ASCII epoch,
  not an authority credential. Runtime config is the exact ambient map later
  passed to `replay-document!`; replay-coordinate keys are rejected at
  startup as well as by the replay API."
  [config]
  (when-not (map? config)
    (throw (config-error :not-a-map (str (class config)))))
  (let [unknown (into #{} (remove config-keys) (keys config))]
    (when (seq unknown)
      (throw (config-error :unknown-keys unknown))))
  (let [port (get config :port 8788)
        token (:capability-token config)
        session-instance-id (:session-instance-id config)
        max-bytes (get config :max-document-bytes
                       default-max-document-bytes)
        scenarios (:allowed-scenarios config)
        runtime (:runtime-config config)
        run-presets (get config :run-presets [])
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
    (when (and (contains? config :session-instance-id)
               (not (valid-session-instance-id? session-instance-id)))
      (throw (config-error
              :invalid-session-instance-id
              {:minimum-length minimum-session-instance-id-length
               :maximum-length maximum-session-instance-id-length})))
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
    (when (contains? config :activity-presentation-registry)
      (try
        (presentation/validate-activity-registry!
         (:activity-presentation-registry config))
        (catch :default error
          (throw
           (ex-info
            "jolt-sim viewer rejected its activity presentation registry"
            {:type invalid-config
             :reason :invalid-activity-presentation-registry
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
    ;; The opt-in worker lifecycle activity journal is a trusted runtime
    ;; toggle only; the browser never supplies it. Enabling it requires
    ;; retained completed artifacts so fast successful journal evidence
    ;; cannot disappear before a terminal replay-progress poll.
    (when (and (contains? runtime :activity-journal?)
               (not (boolean? (:activity-journal? runtime))))
      (throw (config-error :invalid-activity-journal
                           (:activity-journal? runtime))))
    (when (and (true? (:activity-journal? runtime))
               (not (true? (:retain-completed-artifacts? runtime))))
      (throw (config-error :activity-journal-requires-retention
                           {:retain-completed-artifacts?
                            (:retain-completed-artifacts? runtime)})))
    (assoc config
           :port port
           :max-document-bytes max-bytes
           :run-presets (validate-run-presets! scenarios run-presets))))

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

(defn- acceptable-json-media-range? [value]
  (let [[media & parameters] (map string/trim (string/split value #";"))
        quality-text
        (some (fn [parameter]
                (second (re-matches #"(?i)q\s*=\s*(.*)" parameter)))
              parameters)
        valid-quality? (or (nil? quality-text)
                           (boolean
                            (re-matches #"(?:0(?:\.[0-9]{0,3})?|1(?:\.0{0,3})?)"
                                        quality-text)))
        zero-quality? (and quality-text
                           (boolean
                            (re-matches #"0(?:\.0{0,3})?" quality-text)))]
    (and (= "application/json" (string/lower-case media))
         valid-quality?
         (not zero-quality?))))

(defn- json-accept? [request]
  (let [raw (get-in request [:headers "accept"])]
    (and (string? raw)
         (boolean (some acceptable-json-media-range?
                        (string/split raw #","))))))

(defn- json-response [status value]
  (response status "application/json; charset=utf-8" (json/write-str value)))

(defn- negotiated-session-error-response
  "Returns a closed, definitely-not-committed JSON error only for callers that
  explicitly request it. Unknown server failures intentionally keep the EDN
  error shape, so a browser cannot mistake a possibly post-commit failure for
  a negative acknowledgment."
  [request status reason detail]
  (if (json-accept? request)
    (update
     (json-response status
                    {"version" 1
                     "outcome" "error"
                     "committed" false
                     "error" (name reason)})
     :headers assoc "Connection" "close")
    (error-response status reason detail)))

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
    (cond-> {:status (:status state)
             :stdout (:stdout state)
             :stderr (:stderr state)}
      (:run-selection state)
      (assoc :run-selection (:run-selection state)))

    :active
    (cond-> {:status (get state :status :starting)
             :result-observed? (boolean (:result-observed? state))}
      (:run-selection state)
      (assoc :run-selection (:run-selection state))
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
    (:run-selection state)
    (assoc "selection" (:run-selection state))
    (contains? state :result-observed?)
    (assoc "result-observed?" (boolean (:result-observed? state)))
    (contains? state :stdout) (assoc "stdout" (diagnostic-wire (:stdout state)))
    (contains? state :stderr) (assoc "stderr" (diagnostic-wire (:stderr state)))))

;; ---- terminal activity page (opt-in worker lifecycle journal) ----------------

(defn- activity-journal-enabled? [config]
  (true? (get-in config [:runtime-config :activity-journal?])))

(defn- activity-cursor!
  "Reads the optional unsigned decimal `X-Jolt-Sim-Activity-Cursor` header
  without interning or evaluating browser-controlled data. Missing means the
  first activity page from cursor zero; malformed or overflowing values fail
  closed before any trusted page read."
  [request]
  (let [raw (get-in request [:headers "x-jolt-sim-activity-cursor"])]
    (cond
      (nil? raw) 0
      (and (string? raw) (> (count raw) maximum-session-cursor-digits))
      (throw
       (ex-info "viewer activity cursor is outside the integer range"
                {:type invalid-activity-cursor
                 :reason :out-of-range}))
      (and (string? raw)
           (re-matches #"(?:0|[1-9][0-9]*)" raw))
      (let [value (parse-long raw)]
        (if (and (integer? value) (<= 0 value Long/MAX_VALUE))
          value
          (throw
           (ex-info "viewer activity cursor is outside the integer range"
                    {:type invalid-activity-cursor
                     :reason :out-of-range}))))
      :else
      (throw
       (ex-info "viewer activity cursor must be unsigned decimal"
                {:type invalid-activity-cursor
                 :reason :not-unsigned-decimal})))))

(defn- activity-keyword-text [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- activity-event-wire
  "The closed JSON-safe projection of one recovered activity event:
  sequence, tag, kind, summary, EDN-string fields, and the complete canonical
  event EDN. Raw values never cross; only their EDN strings do."
  [row]
  {"sequence" (:sequence row)
   "tag" (:tag row)
   "kind" (:kind-name row)
   "summary" (:summary row)
   "fields" (mapv (fn [field]
                    {"label" (:label field)
                     "valueEdn" (:value-edn field)})
                  (:fields row))
   "edn" (:edn row)})

(defn- activity-recovery-wire
  "The closed JSON projection of the bounded `jolt.sim.activity` recovery
  diagnostics: fixed scalar keys only, never bytes, paths, or Throwables."
  [recovery]
  {"status" (activity-keyword-text (:status recovery))
   "reason" (when-let [reason (:reason recovery)]
              (activity-keyword-text reason))
   "sequence" (:sequence recovery)
   "lastGoodOffset" (:last-good-offset recovery)
   "rawTailBytes" (:raw-tail-bytes recovery)
   "imageTruncated" (boolean (:image-truncated? recovery))
   "class" (:class recovery)})

(defn- activity-observer-wire
  "The closed JSON projection of the bounded worker-side observer status, or
  nil when the outcome recorded none. Failure context is restricted to the
  bounded keyword/string/integer/boolean domain, exactly like the worker's
  own secondary diagnostics."
  [status]
  (when (map? status)
    {"health" (activity-keyword-text (:health status))
     "failure"
     (when-let [failure (:failure status)]
       (into {}
             (keep (fn [[key value]]
                     (when (and (keyword? key)
                                (or (keyword? value)
                                    (string? value)
                                    (integer? value)
                                    (boolean? value)))
                       [(name key)
                        (if (keyword? value)
                          (activity-keyword-text value)
                          value)])))
             failure))
     "sequence" (:sequence status)
     "accepted" (:accepted status)
     "capped" (boolean (:capped? status))
     "durability" (activity-keyword-text (:durability status))
     "closed" (boolean (:closed? status))}))

(defn- activity-page-wire
  "Projects one trusted `process-explorer/read-activity-page` page into the
  closed JSON-safe activity payload."
  [page]
  {"version" 1
   "status" "ok"
   "cursor" (:cursor page)
   "nextCursor" (:next-cursor page)
   "acceptedCount" (:accepted-count page)
   "remaining" (boolean (:remaining? page))
   "events" (mapv activity-event-wire (:events page))
   "recovery" (activity-recovery-wire (:recovery page))
   "observer" (activity-observer-wire (:observer-status page))})

(defn- activity-unavailable-wire
  "The closed secondary marker served when the activity projection itself
  cannot be produced. The replay's terminal status and the HTTP outcome are
  unchanged; the cursor is echoed without advancing."
  [cursor reason]
  {"version" 1
   "status" "unavailable"
   "reason" reason
   "cursor" cursor
   "nextCursor" cursor})

(defn- activity-oversized-wire
  "The closed secondary marker served when the projected page would exceed
  the configured response cap. Only the activity projection fails; the real
  next cursor is still reported so a client can advance past the page."
  [cursor next-cursor limit actual]
  {"version" 1
   "status" "too-large"
   "limit" limit
   "actual" actual
   "cursor" cursor
   "nextCursor" next-cursor})

(defn- terminal-activity-projection
  "Returns `[activity-wire next-cursor]` for a terminal replay with
  `:activity-journal?` enabled, else nil so idle and active replays carry no
  activity key at all.

  The page always comes from the trusted retained process outcome through
  `process-explorer/read-activity-page`; neither the browser nor this server
  ever supplies or returns an artifact path. A cursor outside the recovered
  prefix is a typed 400 contract error. Every other presentation or recovery
  failure degrades to a closed secondary marker: it never changes the
  replay's terminal status or the HTTP outcome."
  [config registry state cursor]
  (when (and (= :terminal (:phase state))
             (activity-journal-enabled? config))
    (if-let [outcome (:outcome state)]
      (try
        (let [page (activity-view/read-page outcome cursor registry)]
          (try
            [(activity-page-wire page) (:next-cursor page)]
            (catch :default _
              [(activity-unavailable-wire cursor "presentation-failed")
               cursor])))
        (catch :default error
          (let [data (ex-data error)]
            (cond
              (= presentation/invalid-presentation (:type data))
              [(activity-unavailable-wire cursor "presentation-failed")
               cursor]

              (= :jolt.sim.activity/invalid-cursor (:type data))
              (throw
               (ex-info "viewer activity cursor is beyond the recovered prefix"
                        {:type invalid-activity-cursor
                         :reason :beyond-recovery
                         :cursor (:cursor data)
                         :accepted (:accepted data)}))

              (= :activity-outcome-not-retained (:reason data))
              [(activity-unavailable-wire cursor "not-retained") cursor]

              :else
              [(activity-unavailable-wire cursor "recovery-failed") cursor]))))
      [(activity-unavailable-wire cursor "not-retained") cursor])))

(defn- replay-progress-response
  "Answers one authorized `GET /api/replay-progress` request.

  The closed activity page (terminal replays with the trusted
  `:activity-journal?` runtime toggle only) shares the progress body and the
  configured response cap: an oversized page fails only the activity
  projection, never the replay status or the HTTP response."
  [config registry active-replay request]
  (try
    (let [cursor (activity-cursor! request)
          state (swap! active-replay observe-active-replay)
          base (progress-wire (replay-progress-state state))
          activity (terminal-activity-projection config registry state cursor)
          [activity-wire next-cursor] activity
          body (json/write-str
                (if activity-wire
                  (assoc base "activity" activity-wire)
                  base))
          byte-count (alength (.getBytes ^String body "UTF-8"))
          limit (:max-document-bytes config)
          [activity-wire body]
          (if (and activity-wire (> byte-count limit))
            (let [marker (activity-oversized-wire
                          cursor next-cursor limit byte-count)]
              [marker (json/write-str (assoc base "activity" marker))])
            [activity-wire body])]
      (cond-> (response 200 "application/json; charset=utf-8" body)
        activity-wire
        (update :headers assoc
                "X-Jolt-Sim-Activity-Next-Cursor" (str next-cursor))))
    (catch :default error
      (let [data (ex-data error)]
        (if (= invalid-activity-cursor (:type data))
          (error-response 400 :invalid-activity-cursor
                          (select-keys data [:reason :cursor :accepted]))
          (throw error))))))

(defn- outcome->progress-status [outcome]
  (if (= :completed (:status outcome)) :completed :failed))

(defn- public-replay-outcome
  "Removes the server-private retained-artifact coordinate from the replay
  response. The complete outcome remains in `active-replay` for trusted
  terminal activity paging; browser clients receive all other forensic
  evidence without learning a host filesystem path."
  [outcome]
  (dissoc outcome :artifact-dir))

(defn- execute-run-with-progress!
  "Runs one trusted fresh-process operation through the viewer's single
  progress/activity lifecycle. `invoke` receives only the runtime map with the
  private run-directory observer installed and must return a process outcome."
  ([config active-replay invoke]
   (execute-run-with-progress! config active-replay nil invoke))
  ([config active-replay run-selection invoke]
   (reset! active-replay
           (cond-> {:phase :active
                    :run-dir nil
                    :status :starting
                    :result-observed? false}
             run-selection (assoc :run-selection run-selection)))
   (try
     (let [observer (fn [run-dir]
                      (swap! active-replay assoc :run-dir run-dir))
           runtime (assoc (:runtime-config config) :on-run-dir observer)
           outcome (invoke runtime)]
       (reset! active-replay
               (cond-> {:phase :terminal
                        :status (outcome->progress-status outcome)
                        :stdout (get-in outcome [:diagnostics :stdout]
                                        empty-progress-diagnostic)
                        :stderr (get-in outcome [:diagnostics :stderr]
                                        empty-progress-diagnostic)}
                 run-selection (assoc :run-selection run-selection)
                 (activity-journal-enabled? config)
                 (assoc :outcome outcome)))
       (response 200 "application/edn; charset=utf-8"
                 (trace/canonical-edn (public-replay-outcome outcome))))
     (catch :default error
       (let [retained-run-dir (:artifact-dir (ex-data error))
             active (cond-> @active-replay
                      retained-run-dir
                      (assoc :phase :active :run-dir retained-run-dir))
             observed (observe-active-replay active)
             run-dir (:run-dir observed)]
         (reset! active-replay
                 (cond-> {:phase :terminal
                          :status :failed
                          :stdout (or (:stdout observed)
                                      empty-progress-diagnostic)
                          :stderr (or (:stderr observed)
                                      empty-progress-diagnostic)}
                   run-selection (assoc :run-selection run-selection)
                   run-dir
                   (assoc :run-dir run-dir)

                   (and run-dir (activity-journal-enabled? config))
                   ;; An escaping retained-child-death exception has no
                   ;; ordinary outcome, but the fixed trusted journal remains
                   ;; recoverable without exposing its directory to the wire.
                   (assoc :outcome
                          {:status :worker-error
                           :artifact-dir run-dir
                           :activity {:observer-status nil}}))))
       (throw error)))))

(defn- keyword-coordinate-text [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- run-regime-wire [regime]
  {"id" (keyword-coordinate-text (:id regime))
   "label" (:label regime)
   "summary" (:summary regime)
   "scope" (mapv keyword-coordinate-text (:scope regime))})

(defn- run-preset-wire [preset]
  {"id" (keyword-coordinate-text (:id preset))
   "label" (:label preset)
   "profileId" (keyword-coordinate-text (:profile-id preset))
   "planEdn" (experiment-viewer/canonical-edn (:plan-document preset))
   "regimes" (mapv run-regime-wire (:regimes preset))})

(defn- run-catalog-validated [validated]
  {"version" 2
   "presets" (mapv run-preset-wire (:run-presets validated))})

(defn run-catalog
  "Returns the closed v2 run-catalog projection used by Ripple's HTTP API.

  `config` is validated exactly as viewer startup configuration. The returned
  value contains inert display data only: scenario symbols, trusted inputs,
  schedules, worker configuration, paths, and environment never cross this
  boundary. REPLs and alternate UIs can consume the same value without
  scraping Ripple's HTML or reimplementing catalog projection."
  [config]
  (run-catalog-validated (validate-config! config)))

(defn- run-presets-response [catalog]
  (json-response 200 catalog))

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

(defn- session-instance-matches? [config request]
  (if-let [expected (:session-instance-id config)]
    (secure-string=
     expected
     (get-in request [:headers "x-jolt-sim-session-instance"]))
    true))

(defn- with-session-instance-header [config response]
  (if-let [instance-id (:session-instance-id config)]
    (update response :headers assoc session-instance-header instance-id)
    response))

(defn- session-cursor!
  "Reads the optional unsigned decimal journal cursor without interning or
  evaluating browser-controlled data. Missing means the complete journal
  from cursor zero; malformed or overflowing values fail closed."
  [request]
  (let [raw (get-in request [:headers "x-jolt-sim-journal-cursor"])]
    (cond
      (nil? raw) 0
      (and (string? raw) (> (count raw) maximum-session-cursor-digits))
      (throw
       (ex-info "viewer session cursor is outside the integer range"
                {:type invalid-session-cursor
                 :reason :out-of-range}))
      (and (string? raw) (re-matches #"[0-9]+" raw))
      (or (parse-long raw)
          (throw
           (ex-info "viewer session cursor is outside the integer range"
                    {:type invalid-session-cursor
                     :reason :out-of-range})))
      :else
      (throw
       (ex-info "viewer session cursor must be unsigned decimal"
                {:type invalid-session-cursor
                 :reason :not-unsigned-decimal})))))

(defn- session-frame-error-response [error]
  (let [data (ex-data error)]
    (case (:type data)
      :jolt.sim.session-view/invalid-cursor
      (error-response 400 :invalid-session-cursor
                      (select-keys data [:reason :cursor :journal-count]))

      :jolt.sim.session-view/coherence-failed
      (error-response 409 :session-frame-incoherent
                      (select-keys data [:attempts :max-attempts]))

      ::invalid-session-cursor
      (error-response 400 :invalid-session-cursor
                      (select-keys data [:reason]))

      :jolt.sim.viewer.remote-session/source-restarted
      (error-response 409 :session-source-restarted nil)

      nil)))

(defn- bounded-session-frame
  "Validates the core session-view's already bounded journal page."
  [frame cursor]
  (let [journal (:journal frame)
        count (:count journal)
        entries (:entries journal)
        next-cursor (:next-cursor journal)
        page-size (:page-size journal)]
    (when-not (and (map? frame)
                   (= :frame (:jolt.sim.session-view/type frame))
                   (map? journal)
                   (= cursor (:cursor journal))
                   (integer? count)
                   (<= cursor count)
                   (integer? next-cursor)
                   (<= cursor next-cursor count)
                   (integer? page-size)
                   (= page-size (- next-cursor cursor))
                   (<= page-size viewer-session/max-journal-page-size)
                   (= (:remaining? journal) (< next-cursor count))
                   (vector? entries)
                   (= page-size (clojure.core/count entries)))
      (throw (ex-info "trusted session reader returned an invalid frame"
                      {:type invalid-session-frame})))
    frame))

(defn- wire-long? [value]
  (and (integer? value)
       (<= Long/MIN_VALUE value Long/MAX_VALUE)))

(defn- session-choice-wire [frame-revision branch]
  (let [action (:action branch)
        kind (first action)
        value (second action)]
    (when-not (and (map? branch)
                   (= #{:revision :action} (set (keys branch)))
                   (= frame-revision (:revision branch))
                   (wire-long? (:revision branch))
                   (<= 0 (:revision branch))
                   (< (:revision branch) Long/MAX_VALUE)
                   (vector? action)
                   (= 2 (count action))
                   (contains? #{:run :advance} kind)
                   (wire-long? value)
                   (or (= :advance kind) (<= 0 value)))
      (throw (ex-info "trusted session reader returned an invalid branch"
                      {:type invalid-session-frame})))
    {"revision" (str (:revision branch))
     "kind" (name kind)
     "value" (str value)
     "label" (str (name kind) " " value)}))

(defn- session-frame-wire [services frame body]
  (let [revision (:revision frame)
        next-cursor (get-in frame [:journal :next-cursor])
        branches (:branches frame)]
    (when-not (and (wire-long? revision)
                   (<= 0 revision)
                   (wire-long? next-cursor)
                   (<= 0 next-cursor)
                   (vector? branches))
      (throw (ex-info "trusted session reader returned invalid coordinates"
                      {:type invalid-session-frame})))
    {"version" 1
     "revision" (str revision)
     "nextCursor" (str next-cursor)
     "stepEnabled" (fn? (:step-session-frame! services))
     "frameEdn" body
     "choices" (mapv #(session-choice-wire revision %) branches)}))

(defn- session-frame-response [config services request]
  (if-let [read-frame (:read-session-frame services)]
    (try
      (let [cursor (session-cursor! request)
            frame (bounded-session-frame (read-frame cursor) cursor)
            frame-edn (trace/canonical-edn frame)
            json? (json-accept? request)
            body (if json?
                   (json/write-str (session-frame-wire services frame frame-edn))
                   frame-edn)
            byte-count (alength (.getBytes ^String body "UTF-8"))]
        (if (> byte-count (:max-document-bytes config))
          (error-response 413 :session-frame-too-large
                          {:limit (:max-document-bytes config)
                           :actual byte-count})
          (update
           (response 200
                     (if json?
                       "application/json; charset=utf-8"
                       "application/edn; charset=utf-8")
                     body)
           :headers assoc
           "X-Jolt-Sim-Journal-Next-Cursor"
           (str (get-in frame [:journal :next-cursor])))))
      (catch :default error
        (if-let [expected (session-frame-error-response error)]
          expected
          (error-response 500 :session-frame-unavailable nil))))
    (error-response 404 :session-unavailable nil)))

(defn- execute-session-frame-request
  "Admits at most one expensive coherent-frame read at a time. Authorization
  is checked first so an untrusted local caller cannot observe whether a
  trusted client is currently inspecting a session. The gate is shared with
  `POST /api/session-step`: a simultaneous step rejects with 429 before the
  trusted reader is invoked."
  [config services session-active? request]
  (cond
    (not (authorized? config request))
    (error-response 403 :forbidden nil)

    (not (compare-and-set! session-active? false true))
    (with-session-instance-header
     config
     (error-response 429 :session-frame-busy nil))

    :else
    (with-session-instance-header
     config
     (try
       (session-frame-response config services request)
       (finally
         (reset! session-active? false))))))

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

(defn- json-content-type?
  "True when the Content-Type value carries the application/json media type:
  case-insensitive, optional ; parameters accepted and ignored. The body is
  always decoded as UTF-8 per RFC 8259, whatever charset parameter appears."
  [request]
  (let [value (get-in request [:headers "content-type"])]
    (and (string? value)
         (= "application/json"
            (-> value
                string/lower-case
                (string/split #";" 2)
                first
                string/trim)))))

(defn- json-whitespace?
  "The complete RFC 8259 whitespace set. clojure.string/trim is deliberately
  broader and would accept Unicode separators that are not JSON syntax."
  [c]
  (or (= c \space)
      (= c \tab)
      (= c \return)
      (= c \newline)))

(defn- trim-json-whitespace
  "Removes only RFC 8259 whitespace from both ends of the text."
  [text]
  (let [length (count text)
        start (loop [index 0]
                (if (and (< index length)
                         (json-whitespace? (nth text index)))
                  (recur (inc index))
                  index))
        end (loop [index length]
              (if (and (> index start)
                       (json-whitespace? (nth text (dec index))))
                (recur (dec index))
                index))]
    (subs text start end)))

(defn- canonical-unsigned-decimal?
  "True only for the canonical unsigned decimal strings: `0` or a nonzero
  digit followed by digits. Leading zeros, signs, whitespace, and every
  non-string value are rejected."
  [text]
  (and (string? text)
       (or (= "0" text)
           (re-matches #"[1-9][0-9]*" text))))

(defn- canonical-signed-decimal?
  "True only for canonical optionally-signed decimal strings. `-0` is
  noncanonical: the canonical zero is unsigned."
  [text]
  (and (string? text)
       (or (canonical-unsigned-decimal? text)
           (re-matches #"-[1-9][0-9]*" text))))

(defn- step-decimal!
  "Returns the long named by one canonical decimal string from the step
  contract. The digit count is bounded before parsing so an oversized or
  overflowing literal fails closed instead of exercising an unbounded parse.
  `reason` names the contract field in the typed error."
  [text signed? reason]
  (when-not (if signed?
              (canonical-signed-decimal? text)
              (canonical-unsigned-decimal? text))
    (throw
     (ex-info "viewer session step decimal is not canonical"
              {:type invalid-session-step :reason reason})))
  (when (> (count text)
           (if signed?
             maximum-step-signed-decimal-chars
             maximum-session-cursor-digits))
    (throw
     (ex-info "viewer session step decimal is outside the integer range"
              {:type invalid-session-step :reason :decimal-out-of-range})))
  (let [value (parse-long text)]
    ;; Jolt's numeric tower permits parse-long to return an integer outside the
    ;; signed 64-bit range.  The HTTP coordinate contract is deliberately
    ;; narrower, so enforce the value bounds explicitly after the cheap length
    ;; bound instead of relying on the parser's host-specific overflow policy.
    (when-not (and (integer? value)
                   (<= Long/MIN_VALUE value Long/MAX_VALUE)
                   (or signed? (not (neg? value))))
      (throw
       (ex-info "viewer session step decimal is outside the integer range"
                {:type invalid-session-step :reason :decimal-out-of-range})))
    value))

(def ^:private session-step-request-keys
  #{"version" "cursor" "branch"})

(def ^:private session-step-branch-keys
  #{"revision" "kind" "value"})

(defn- session-step-command!
  "Reads and fail-closed validates the closed session-step request contract,
  returning `[branch cursor]`.

  The body is bounded at `session-step-body-limit` bytes and must hold exactly
  one JSON object with exactly the keys `version` (the integer 1), `cursor`
  (a canonical nonnegative decimal string), and `branch` (an object with
  exactly `revision`, `kind`, and `value`). `kind` is the closed string `run`
  or `advance`; `value` is a canonical decimal string, nonnegative for `run`
  and optionally signed for `advance`. Unknown or missing keys, wrong types,
  noncanonical decimals, trailing JSON, and overflowing literals are all
  rejected before any Session function is invoked.

  The branch is reconstructed from the closed string match as exactly
  `{:revision N :action [:run N]}` or `[:advance N]`. Browser strings are
  never interned as keywords or symbols: the action tag is one of two fixed
  keywords selected by `case`, and JSON object keys remain plain strings."
  [request]
  (let [bytes (bounded-body-bytes request session-step-body-limit)
        seen-keys (atom #{})
        reject-duplicate
        (fn [key value]
          ;; data.json normally keeps the last occurrence of an object key.
          ;; This contract has disjoint outer/branch key names, so one
          ;; request-local set detects duplicates at either depth without a
          ;; custom JSON parser.
          (when (contains? @seen-keys key)
            (throw
             (ex-info "viewer session step body repeats an object key"
                      {:type invalid-session-step
                       :reason :duplicate-key})))
          (swap! seen-keys conj key)
          value)
        value (try
                (json/read-str (trim-json-whitespace (String. bytes "UTF-8"))
                               :extra-data-fn json/on-extra-throw
                               :value-fn reject-duplicate)
                (catch :default error
                  (if (= invalid-session-step (:type (ex-data error)))
                    (throw error)
                    (throw
                     (ex-info
                      "viewer session step body is not exactly one JSON value"
                      {:type invalid-session-step
                       :reason :malformed-json})))))]
    (when-not (and (map? value)
                   (= session-step-request-keys (set (keys value))))
      (throw
       (ex-info "viewer session step request keys are not the closed set"
                {:type invalid-session-step :reason :unexpected-keys})))
    (when-not (and (integer? (get value "version"))
                   (= 1 (get value "version")))
      (throw
       (ex-info "viewer session step version is not the integer 1"
                {:type invalid-session-step :reason :unsupported-version})))
    (let [cursor (step-decimal! (get value "cursor") false :invalid-cursor)
          branch (get value "branch")]
      (when-not (and (map? branch)
                     (= session-step-branch-keys (set (keys branch))))
        (throw
         (ex-info "viewer session step branch keys are not the closed set"
                  {:type invalid-session-step :reason :invalid-branch})))
      (let [revision (step-decimal! (get branch "revision")
                                    false
                                    :invalid-branch)
            kind (get branch "kind")
            tag (case kind
                  "run" :run
                  "advance" :advance
                  (throw
                   (ex-info "viewer session step branch kind is unknown"
                            {:type invalid-session-step
                             :reason :unknown-kind})))
            payload (step-decimal! (get branch "value")
                                   (= :advance tag)
                                   :invalid-value)]
        ;; A successful command acknowledges revision+1. Keep both the request
        ;; and acknowledgment inside the same signed-64 coordinate domain.
        (when (= Long/MAX_VALUE revision)
          (throw
           (ex-info "viewer session step revision cannot be incremented"
                    {:type invalid-session-step
                     :reason :decimal-out-of-range})))
        [{:revision revision :action [tag payload]} cursor]))))

(def ^:private run-command-keys #{"version" "presetId" "regimeId"})

(defn- wire-run-id? [value]
  (and (string? value)
       (<= 3 (count value) 256)
       (boolean (re-matches #"[^\s/:]+(?:\.[^\s/:]+)*/[^\s/]+" value))))

(defn- run-command!
  "Reads the closed v2 run command and returns opaque preset/regime IDs.
  Browser text is compared with trusted catalog IDs and is never interned."
  [request]
  (let [bytes (bounded-body-bytes request run-command-body-limit)
        seen-keys (atom #{})
        value (try
                (json/read-str
                 (trim-json-whitespace (String. bytes "UTF-8"))
                 :extra-data-fn json/on-extra-throw
                 :value-fn
                 (fn [key entry-value]
                   (when (contains? @seen-keys key)
                     (throw
                      (ex-info "viewer run command repeats an object key"
                               {:type ::invalid-run-command
                                :reason :duplicate-key})))
                   (swap! seen-keys conj key)
                   entry-value))
                (catch :default error
                  (if (= ::invalid-run-command (:type (ex-data error)))
                    (throw error)
                    (throw
                     (ex-info
                      "viewer run command is not exactly one JSON value"
                      {:type ::invalid-run-command
                       :reason :malformed-json})))))]
    (when-not (and (map? value)
                   (= run-command-keys (set (keys value))))
      (throw
       (ex-info "viewer run command keys are not the closed set"
                {:type ::invalid-run-command :reason :unexpected-keys})))
    (when-not (and (integer? (get value "version"))
                   (= 2 (get value "version")))
      (throw
       (ex-info "viewer run command version is not the integer 2"
                {:type ::invalid-run-command
                 :reason :unsupported-version})))
    (let [preset-id (get value "presetId")
          regime-id (get value "regimeId")]
      (when-not (wire-run-id? preset-id)
        (throw
         (ex-info "viewer run command preset ID is invalid"
                  {:type ::invalid-run-command
                   :reason :invalid-preset-id})))
      (when-not (wire-run-id? regime-id)
        (throw
         (ex-info "viewer run command regime ID is invalid"
                  {:type ::invalid-run-command
                   :reason :invalid-regime-id})))
      {:preset-id preset-id :regime-id regime-id})))

(def ^:private eval-command-keys #{"version" "form"})

(defn- eval-command!
  "Reads the closed v1 evaluation command. Browser text remains one opaque
  form string and is passed only to the trusted evaluation service."
  [request]
  (let [bytes (bounded-body-bytes request eval-command-body-limit)
        seen-keys (atom #{})
        value (try
                (json/read-str
                 (trim-json-whitespace (String. bytes "UTF-8"))
                 :extra-data-fn json/on-extra-throw
                 :value-fn
                 (fn [key entry-value]
                   (when (contains? @seen-keys key)
                     (throw
                      (ex-info "viewer eval command repeats an object key"
                               {:type ::invalid-eval-command
                                :reason :duplicate-key})))
                   (swap! seen-keys conj key)
                   entry-value))
                (catch :default error
                  (if (= ::invalid-eval-command (:type (ex-data error)))
                    (throw error)
                    (throw
                     (ex-info
                      "viewer eval command is not exactly one JSON value"
                      {:type ::invalid-eval-command
                       :reason :malformed-json})))))]
    (when-not (and (map? value)
                   (= eval-command-keys (set (keys value))))
      (throw
       (ex-info "viewer eval command keys are not the closed set"
                {:type ::invalid-eval-command :reason :unexpected-keys})))
    (when-not (and (integer? (get value "version"))
                   (= 1 (get value "version")))
      (throw
       (ex-info "viewer eval command version is not the integer 1"
                {:type ::invalid-eval-command
                 :reason :unsupported-version})))
    (let [form (get value "form")]
      (when-not (string? form)
        (throw
         (ex-info "viewer eval command form is not a string"
                  {:type ::invalid-eval-command :reason :invalid-form})))
      {:form form})))

(defn- execute-eval-request
  [config services document-active? request]
  (cond
    (not (authorized? config request))
    (error-response 403 :forbidden nil)

    (not (fn? (:evaluate-form! services)))
    (error-response 404 :eval-unavailable nil)

    (not (json-content-type? request))
    (error-response 415 :expected-application-json nil)

    ;; Evaluation consumes a streaming body through the same two-thread HTTP
    ;; pool as document/run requests, so it shares their admission lease.
    (not (compare-and-set! document-active? false true))
    (error-response 429 :viewer-busy nil)

    :else
    (try
      (let [{:keys [form]} (eval-command! request)
            result ((:evaluate-form! services) form)
            body (json/write-str result)]
        ;; Once evaluation commits its bounded receipt must remain a definite
        ;; 200 response. Returning a size error here would make a non-idempotent
        ;; form's outcome ambiguous and tempt an unsafe retry.
        (response 200 "application/json; charset=utf-8" body))
      (catch :default error
        (let [data (ex-data error)]
          (cond
            (= request-too-large (:type data))
            (error-response 413 :request-too-large
                            (select-keys data [:limit :actual]))

            (= ::invalid-eval-command (:type data))
            (error-response 400 :invalid-eval-command
                            (select-keys data [:reason]))

            (and (= :jolt.sim.eval-session/rejected (:type data))
                 (= :closed (:reason data)))
            (error-response 409 :eval-session-closed nil)

            :else
            (throw error))))
      (finally
        (reset! document-active? false)))))

(defn- find-run-preset [config wire-id]
  (some #(when (= wire-id (keyword-coordinate-text (:id %))) %)
        (:run-presets config)))

(defn- find-run-regime [preset wire-id]
  (some #(when (= wire-id (keyword-coordinate-text (:id %))) %)
        (:regimes preset)))

(defn- fresh-run-input [preset regime]
  ;; Startup validation owns one immutable catalog snapshot. Each execution
  ;; restores only the selected input so mutable canonical leaves (notably
  ;; byte arrays) cannot leak mutations into later runs. Unrelated regimes are
  ;; never revisited on the request path.
  (trace/restore-value
   (trace/canonical-value
    (:input regime)
    [:run-preset (:id preset) :regime (:id regime) :selected-input])))

(defn- run-selection-wire [preset regime]
  {"catalogVersion" 2
   "presetId" (keyword-coordinate-text (:id preset))
   "regimeId" (keyword-coordinate-text (:id regime))
   "scope" (mapv keyword-coordinate-text (:scope regime))})

(defn- resolve-run-selection-validated [validated preset-id regime-id]
  (if-let [preset (find-run-preset validated preset-id)]
    (if-let [regime (find-run-regime preset regime-id)]
      {:coordinates
       {:scenario (:scenario preset)
        :input (fresh-run-input preset regime)
        :schedule (:schedule preset)}
       :run-selection (run-selection-wire preset regime)}
      (throw (ex-info "Ripple run regime was not found"
                      {:type ::run-selection-not-found
                       :reason :regime-not-found})))
    (throw (ex-info "Ripple run preset was not found"
                    {:type ::run-selection-not-found
                     :reason :preset-not-found}))))

(defn resolve-run-selection
  "Resolves one exact v2 preset/regime wire coordinate to trusted execution
  coordinates.

  Both IDs remain strings and are compared only with the validated immutable
  catalog; caller text is never interned or resolved as code. Returns exactly
  `{:scenario :input :schedule}`. Unknown presets and regimes fail with a
  bounded typed error suitable for REPLs, alternate UIs, and the HTTP adapter."
  [config preset-id regime-id]
  (:coordinates
   (resolve-run-selection-validated
    (validate-config! config) preset-id regime-id)))

(defn- execute-run-request
  [config services document-active? active-replay request]
  (cond
    (not (authorized? config request))
    (error-response 403 :forbidden nil)

    (not (fn? (:run-case services)))
    (error-response 404 :run-unavailable nil)

    (not (json-content-type? request))
    (error-response 415 :expected-application-json nil)

    (not (compare-and-set! document-active? false true))
    (error-response 429 :viewer-busy nil)

    :else
    (try
      (let [{:keys [preset-id regime-id]} (run-command! request)
            {:keys [coordinates run-selection]}
            (resolve-run-selection-validated config preset-id regime-id)]
        (execute-run-with-progress!
         config active-replay run-selection
         (fn [runtime]
           ((:run-case services) (merge runtime coordinates)))))
      (catch :default error
        (let [data (ex-data error)]
          (cond
            (= request-too-large (:type data))
            (error-response 413 :request-too-large
                            (select-keys data [:limit :actual]))

            (= ::invalid-run-command (:type data))
            (error-response 400 :invalid-run-command
                            (select-keys data [:reason]))

            (= ::run-selection-not-found (:type data))
            (error-response
             404
             (if (= :preset-not-found (:reason data))
               :run-preset-not-found
               :run-regime-not-found)
             nil)

            :else
            (throw error))))
      (finally
        (reset! document-active? false)))))

(defn- invalid-step-result! []
  (throw (ex-info "trusted session stepper returned an invalid result"
                  {:type invalid-session-step-result})))

(defn- valid-frame-error? [value phase]
  (and (map? value)
       (keyword? (:type value))
       (= phase (:phase value))
       (= (contains? value :attempts)
          (contains? value :max-attempts))
       (or (not (contains? value :attempts))
           (and (integer? (:attempts value))
                (pos? (:attempts value))
                (integer? (:max-attempts value))
                (pos? (:max-attempts value))
                (<= (:attempts value) (:max-attempts value))))))

(defn- session-step-receipt
  "Projects the trusted stepper's closed result envelope into the compact
  wire receipt: version, status, committed?, and either the exact commit
  acknowledgment or the safe stale coordinates, plus `:frame-status`
  (`:available` or `:unavailable`) with the bounded typed `:frame-error` when
  the post-command frame could not be obtained. The full frame, projection,
  world, previews, and events never cross this boundary. A defective envelope
  fails closed as a server-owned error, never as a partial receipt."
  [submitted-branch result]
  (when-not (and (map? result)
                 (= :step-result
                    (:jolt.sim.session-view/type result)))
    (invalid-step-result!))
  (let [status (:status result)
        committed? (:committed? result)
        ack (:ack result)
        stale (:stale result)
        expected-revision (:revision submitted-branch)
        base
        (case status
          :committed
          (do
            (when-not (and (true? committed?)
                           (map? ack)
                           (map? (:branch ack))
                           (= submitted-branch
                              (select-keys (:branch ack)
                                           [:revision :action]))
                           (= (inc expected-revision) (:revision ack)))
              (invalid-step-result!))
            {:version 1
             :status :committed
             :committed? true
             ;; Reconstruct the acknowledgment from validated coordinates so
             ;; nested extras from a defective service can never cross the
             ;; wire even if this validation is later relaxed.
             :ack {:branch submitted-branch
                   :revision (:revision ack)}})

          :stale
          (do
            (when-not (and (false? committed?)
                           (nil? ack)
                           (map? stale)
                           (= :jolt.sim.session/stale-branch (:type stale))
                           (= expected-revision (:expected-revision stale))
                           (map? (:branch stale))
                           (= submitted-branch
                              (select-keys (:branch stale)
                                           [:revision :action]))
                           (integer? (:actual-revision stale))
                           (<= 0 (:actual-revision stale) Long/MAX_VALUE)
                           (not= (:actual-revision stale)
                                 expected-revision))
              (invalid-step-result!))
            {:version 1
             :status :stale
             :committed? false
             :stale {:expected-revision expected-revision
                     :actual-revision (:actual-revision stale)
                     :branch submitted-branch}})

          (invalid-step-result!))
        frame (:frame result)
        frame-error (:frame-error result)
        phase (if (= :committed status) :post-commit :stale-refresh)]
    (cond
      (and (map? frame)
           (= :frame (:jolt.sim.session-view/type frame))
           (nil? frame-error))
      (assoc base :frame-status :available)

      (and (nil? frame)
           (valid-frame-error? frame-error phase))
      (assoc base
             :frame-status :unavailable
             :frame-error (select-keys frame-error
                                       [:type :phase
                                        :attempts :max-attempts]))

      :else
      (invalid-step-result!))))

(defn- session-step-error-response [error]
  (let [data (ex-data error)
        type (:type data)
        remote-definite?
        (contains?
         #{[400 :invalid-session-step]
           [400 :invalid-session-cursor]
           [403 :forbidden]
           [404 :session-step-unavailable]
           [409 :session-instance-mismatch]
           [409 :session-step-rejected]
           [413 :request-too-large]
           [415 :expected-application-json]
           [429 :session-step-busy]
           [429 :viewer-busy]}
         [(:status data) (:reason data)])]
    (cond
      (= request-too-large type)
      (error-response 413 :request-too-large
                      (select-keys data [:limit :actual]))

      (= invalid-session-step type)
      (error-response 400 :invalid-session-step
                      (select-keys data [:reason]))

      (= :jolt.sim.session-view/invalid-cursor type)
      (error-response 400 :invalid-session-cursor
                      (select-keys data [:reason :cursor :journal-count]))

      ;; The action was well-formed and revision-current but is not enabled at
      ;; the current machine state (for example a disabled run target, a
      ;; non-earliest timer, or a terminal machine). Only the kernel's fixed
      ;; reason keyword may cross; the enabled set and machine state never do.
      (= :jolt.sim.kernel/invalid-machine-action type)
      (error-response 409 :session-step-rejected
                      (when (keyword? (:reason data))
                        (select-keys data [:reason])))

      (= :jolt.sim.viewer.remote-session/source-restarted type)
      (error-response 409 :session-source-restarted nil)

      (and (= :jolt.sim.viewer.remote-session/source-unavailable type)
           remote-definite?)
      (error-response (:status data) (:reason data) nil)

      (= :jolt.sim.viewer.remote-session/step-outcome-unknown type)
      (error-response 503 :session-step-outcome-unknown
                      (select-keys data [:phase :cause-type :status]))

      :else nil)))

(defn- branch-wire-coordinate [branch]
  (let [[kind value] (:action branch)]
    {"revision" (str (:revision branch))
     "kind" (name kind)
     "value" (str value)}))

(defn- session-step-wire [branch receipt]
  (merge
   {"version" 1
    "outcome" (name (:status receipt))
    "committed" (boolean (:committed? receipt))
    "receiptEdn" (trace/canonical-edn receipt)}
   (branch-wire-coordinate branch)))

(defn- session-step-response [services request]
  (try
    (let [[branch cursor] (session-step-command! request)
          result ((:step-session-frame! services) branch cursor)
          receipt (session-step-receipt branch result)]
      (if (json-accept? request)
        (json-response (if (:committed? receipt) 200 409)
                       (session-step-wire branch receipt))
        (response (if (:committed? receipt) 200 409)
                  "application/edn; charset=utf-8"
                  (trace/canonical-edn receipt))))
    (catch :default error
      (if-let [expected (session-step-error-response error)]
        (if (json-accept? request)
          (let [body (edn/read-string (:body expected))]
            (negotiated-session-error-response
             request (:status expected) (:error body) (:detail body)))
          expected)
        (error-response 500 :session-step-error nil)))))

(defn- execute-session-step-request
  "Admits one exact revision-scoped session step at a time. Authorization is
  checked before the optional producer-instance epoch, and that epoch is
  checked before service availability, the admission gates, and the body. An
  untrusted local caller therefore cannot observe step service state, and a
  stale authorized caller cannot command a restarted producer. The session
  gate is shared with `GET /api/session-frame`, so a simultaneous frame read
  and step rejects with 429 before either the trusted reader or stepper is
  invoked. The body is consumed only while holding the shared
  document/body-consumer gate, so a busy response precedes any streaming body
  read on the pool shared with jolt-http's parser."
  [config services session-active? document-active? request]
  (if-not (authorized? config request)
    ;; Never disclose a producer epoch to an unauthorized caller.
    (negotiated-session-error-response request 403 :forbidden nil)
    (with-session-instance-header
     config
     (cond
       (not (session-instance-matches? config request))
       (negotiated-session-error-response
        request 409 :session-instance-mismatch nil)

       (not (fn? (:step-session-frame! services)))
       (negotiated-session-error-response
        request 404 :session-step-unavailable nil)

       (not (json-content-type? request))
       (negotiated-session-error-response
        request 415 :expected-application-json nil)

       (not (compare-and-set! session-active? false true))
       (negotiated-session-error-response request 429 :session-step-busy nil)

       :else
       (try
         (if-not (compare-and-set! document-active? false true)
           (negotiated-session-error-response request 429 :viewer-busy nil)
           (try
             (session-step-response services request)
             (finally
               (reset! document-active? false))))
         (finally
           (reset! session-active? false)))))))

(defn- edn-content-type? [request]
  (let [value (get-in request [:headers "content-type"])]
    (and (string? value)
         (= "application/edn"
            (-> value
                string/lower-case
                (string/split #";" 2)
                first
                string/trim)))))

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
      "experiment-plan" :experiment-plan
      "official-maelstrom-run" :official-maelstrom-run
      (throw (ex-info "viewer request has an unknown document kind"
                      {:type unknown-document-kind :kind normalized})))))

(defn- read-document-by-kind
  "Reads and fail-closed validates one document through the codec selected by
  the explicitly declared kind. Each codec rejects the other document's
  shape, so a misdeclared kind can never be silently reinterpreted."
  [kind text]
  (case kind
    :trace (trace/read-edn text)
    :case-outcome (case-outcome/read-edn text)
    :experiment-plan (experiment-viewer/read-edn text)
    :official-maelstrom-run (official-run/read-edn text)))

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
          (= trace/invalid-document type)
          (= experiment-viewer/invalid-document type)
          (= official-run/invalid-document type))
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

      (= experiment-plan-not-replayable type)
      (error-response 400 :experiment-plan-not-replayable
                      (select-keys data [:kind]))

      (= official-maelstrom-run-not-replayable type)
      (error-response 400 :official-maelstrom-run-not-replayable
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
   :replay-document sim-repl/replay-document!
   ;; Keep GUI-launched runs visible to the same REPL last-run/rerun workflow
   ;; as programmatic runs; sim-repl delegates to the existing supervisor.
   :run-case sim-repl/run-case!})

(defn make-handler
  "Creates a synchronous jolt-http handler.

  Every `POST /api/render` and `POST /api/replay` request must declare its
  document kind explicitly through the `X-Jolt-Sim-Document-Kind` header
  (`trace`, `case-outcome`, or `experiment-plan`); a missing or unknown kind
  is rejected before the request body is read, and the server never infers a
  schema from the uploaded bytes.

  The optional `services` map is a narrow embedding/test seam. Its required
  keys are
  `:render-trace` (`trace-doc -> html`), `:render-case-outcome`
  (`case-outcome-doc -> html`), and `:replay-document`
  (`case-outcome-doc runtime-config -> outcome`). The optional
  `:read-session-frame` key is a trusted `(cursor -> coherent-frame)` closure
  and the optional `:step-session-frame!` key is a trusted
  `(branch cursor -> step-result)` closure; no other Session operation is
  accepted. Browser data never selects any
  function or supplies runtime configuration. Replay
  accepts only Case/Outcome documents: declared `:trace` and
  `:experiment-plan` kinds are rejected explicitly before any restore or
  worker execution. Experiment-plan rendering bypasses the service seam and
  accepts only the closed inert inspector document.

  The optional startup `:run-presets` vector is a closed trusted catalog.
  Each preset owns its namespaced ID, label, allowlisted scenario, profile,
  optional exact schedule, nonempty finite regime catalog, and validated inert
  plan document. Each regime owns its namespaced ID, bounded display metadata,
  nonempty namespaced scope, and canonical input snapshot.
  `GET /api/run-presets` returns catalog v2 with preset display data, canonical
  plan EDN, and regime ID/label/summary/scope only; execution coordinates never
  cross the wire. `POST /api/run` accepts only
  `{version: 2, presetId: namespace/name, regimeId: namespace/name}` JSON and,
  when the optional `:run-case` service is installed, resolves that exact
  trusted pair, merges its coordinates into the ambient runtime config, and
  invokes that service exactly once.
  Browser strings are never interned or treated as execution coordinates.
  Run and replay share the same body-consuming admission lease, progress
  state, public artifact-path redaction, and terminal activity paging.

  `GET /api/session-frame` is an optional embedding-only, inspection surface.
  When the trusted services map supplies `:read-session-frame`, it returns one
  coherent canonical Session projection, enabled branch references, isolated
  successor previews, and append-only journal tail. Browser data supplies
  only an unsigned journal cursor. Computing successor previews does invoke
  the Session's cooperative step callback, so attached sessions must satisfy
  its deterministic, effect-free contract. A dedicated single-flight gate,
  shared with `POST /api/session-step`, admits only one frame computation or
  step at a time.

  When startup config contains `:session-instance-id`, every authorized frame
  or step response carries it in `X-Jolt-Sim-Session-Instance`; unauthorized
  responses never do. The ID is a producer epoch, not authority; the
  capability token remains required. A configured step request must echo the
  exact epoch header after authorization and before service availability,
  media-type, admission, body, or service evaluation. Missing and stale epochs
  therefore cannot command a restarted producer. Omitting the startup key
  preserves the original frame and step protocol.

  `POST /api/session-step` is an optional embedding-only command surface,
  present only when the trusted services map supplies `:step-session-frame!`.
  It applies exactly one exact revision-scoped branch supplied in the closed
  JSON contract (`application/json`, at most 4096 bytes, exactly the keys
  `version`/`cursor`/`branch` with canonical decimal strings) and answers
  with a compact canonical-EDN receipt: version, status, committed?, the
  exact ack or the safe stale coordinates, and `:frame-status`. It never
  returns a full frame, projection, world, previews, or events. There is no
  run-all, no automatic retry, and no rebasing: the exact branch revision is
  the only duplicate-execution guard, so the identical request retried after
  a commit is stale and never advances the revision again. Without the
  service key the route is absent and answers 404 `:session-step-unavailable`;
  authorization is checked before the optional producer epoch, which is
  checked before that availability, the admission gates, and the body.

  `GET /api/replay-progress` reports the one active or most recently
  completed replay's status (`:idle`, `:starting`, `:worker-ready`,
  `:running`, `:completed`, or `:failed`) plus bounded stdout/stderr text, by
  reading only fixed basenames (`worker-ready.edn`, `stdout.log`,
  `stderr.log`, and -- existence only, never parsed -- `result.edn`) from the
  trusted active run directory. It never accepts a filesystem path from the
  browser. A catalog run additionally carries its trusted catalog version,
  preset ID, regime ID, and declared scope through active and terminal
  progress, including launch failures; ordinary document replay has no such
  selection field.

  When the trusted runtime config enables `:activity-journal?` (which
  requires `:retain-completed-artifacts?` true), a terminal replay's response
  additionally carries one closed JSON-safe activity page recovered from the
  trusted retained process outcome through
  `process-explorer/read-activity-page`. The browser supplies only the
  optional unsigned decimal `X-Jolt-Sim-Activity-Cursor` header (default 0);
  the response returns events as sequence/tag/kind/summary/EDN-string fields
  plus bounded observer/recovery diagnostics and the
  `X-Jolt-Sim-Activity-Next-Cursor` header. Idle and active replays carry no
  activity key. A malformed or out-of-range cursor fails closed with 400
  before any page read; every other presentation or recovery failure remains
  secondary and never converts a completed replay into an HTTP error or a
  failed status. An oversized page fails only the activity projection against
  the configured response cap."
  ([config]
   (make-handler config (default-services config)))
  ([config services]
    (let [config (validate-config! config)
          run-catalog (run-catalog-validated config)
          unknown-services (into #{} (remove service-keys) (keys services))
          document-active? (atom false)
          session-active? (atom false)
          active-replay (atom (initial-replay-state))
          activity-registry
          (presentation/activity-registry
           presentation/default-activity-registry
           (:activity-presentation-registry config))]
     (when (seq unknown-services)
       (throw (config-error :unknown-service-keys unknown-services)))
     (when-not (and (fn? (:render-trace services))
                    (fn? (:render-case-outcome services))
                    (fn? (:replay-document services))
                    (or (not (contains? services :run-case))
                        (fn? (:run-case services)))
                    (or (not (contains? services :read-session-frame))
                        (fn? (:read-session-frame services)))
                    (or (not (contains? services :step-session-frame!))
                        (fn? (:step-session-frame! services)))
                    (or (not (contains? services :evaluate-form!))
                        (fn? (:evaluate-form! services))))
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
              (replay-progress-response
               config activity-registry active-replay request))

           (and (= :get method) (= "/api/run-presets" uri))
           (if-not (authorized? config request)
             (error-response 403 :forbidden nil)
             (run-presets-response run-catalog))

           (and (= :get method) (= "/api/session-frame" uri))
           (execute-session-frame-request
            config services session-active? request)

           (and (= :post method) (= "/api/session-step" uri))
           (execute-session-step-request
            config services session-active? document-active? request)

           (and (= :post method) (= "/api/run" uri))
           (execute-run-request
            config services document-active? active-replay request)

           (and (= :post method) (= "/api/eval" uri))
           (execute-eval-request
            config services document-active? request)

           (and (= :post method) (= "/api/render" uri))
           (execute-document-request
            config document-active? request
            (fn [kind document]
              (response 200 "text/html; charset=utf-8"
                        (case kind
                          :experiment-plan
                          (experiment-viewer/document->html
                           document (:presentation-registry config))
                          :official-maelstrom-run
                          (report/official-run->html document)
                          ((render-service services kind) document)))))

           (and (= :post method) (= "/api/replay" uri))
           (execute-document-request
            config document-active? request
            (fn [kind document]
              ;; Reject trace documents before any restore or worker
              ;; execution: replay is a Case/Outcome-only path.
              (when-not (= :case-outcome kind)
                (throw
                 (ex-info
                  "viewer replay accepts only Case/Outcome documents"
                  {:type (case kind
                           :experiment-plan experiment-plan-not-replayable
                           :official-maelstrom-run
                           official-maelstrom-run-not-replayable
                           trace-not-replayable)
                   :kind kind})))
              (allowed-replay! config document)
              (execute-run-with-progress!
               config active-replay
               (fn [runtime]
                 ((:replay-document services) document runtime)))))

           :else
           (error-response 404 :not-found nil)))))))

(defn start!
  "Starts the loopback viewer and returns the jolt-http server handle.

  The optional services arity is the same narrow embedding/test seam accepted
  by `make-handler`; ordinary callers always use the real trace/Case/Outcome
  report and replay services plus the inert experiment-plan inspector.
  `start-session!` is the narrower convenience for a trusted, read-only
  in-process Session attachment."
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

(defn start-session!
  "Starts Ripple with one trusted in-process Session attached for inspection.

  The resulting HTTP handler receives only a closure over
  `viewer-session/read-frame`. It does not receive `step-frame!`, the Session
  value, or any browser-selected function. This is the initial REPL/embedding
  seam for inspecting canonical projections, enabled choices, successor
  previews, and journal tails without calling `session/step!`. Previewing does
  evaluate the Session's cooperative step callback; attach only sessions whose
  callback obeys the deterministic, effect-free contract. Frames can contain
  application data, so callers must keep the loopback capability token
  private."
  [config sim-session]
  (start! config
          (assoc (dissoc (default-services config) :run-case)
                 :read-session-frame
                 (fn [cursor]
                   (viewer-session/read-frame sim-session cursor)))))

(defn start-eval-session!
  "Starts Ripple with one explicitly trusted EvalSession attached.

  This is an opt-in arbitrary-code execution surface protected by the normal
  loopback capability token. The browser can submit only the closed v1 form
  request; the attached closure delegates once to the supplied EvalSession and
  returns its bounded JSON-safe projection. Ordinary `start!` does not install
  this service and `/api/eval` remains unavailable."
  [config eval-session]
  (start! config
          (assoc (default-services config)
                 :evaluate-form! (viewer-eval/service eval-session))))

(defn start-remote-session!
  "Starts Ripple with one separately running loopback Session attached read-only.

  `source` is the closed coordinate accepted by
  `viewer.remote-session/validate-source!`: port, capability token, pinned
  producer epoch, and optional timeout. The validated outer response limit is
  also the remote body limit. Every read uses a fresh connection under one
  absolute deadline. A producer restart pauses the
  attachment with `409 :session-source-restarted`; this function never adopts
  a new epoch or supplies remote stepping."
  [config source]
  (let [config (validate-config! config)]
    (start! config
            (assoc (dissoc (default-services config) :run-case)
                   :read-session-frame
                   (remote-session/reader source
                                          (:max-document-bytes config))))))

(defn start-remote-steppable-session!
  "Starts Ripple with an explicitly command-capable remote Session attachment.

  This is separate from `start-remote-session!`, which remains read-only. The
  closed source coordinate pins one inner producer epoch. Frame reads and each
  exact revision-scoped step use fresh loopback connections and one absolute
  deadline. A command is sent at most once: an ambiguous transport, malformed
  response, or 5xx result becomes typed
  `:session-step-outcome-unknown`, never an automatic retry. Use
  `viewer.remote-session/attachment` directly when a REPL or another UI needs
  its explicit read-only `:reconcile-step!` journal operation."
  [config source]
  (let [config (validate-config! config)
        attachment (remote-session/attachment
                    source (:max-document-bytes config))]
    (start! config
            (assoc (dissoc (default-services config) :run-case)
                   :read-session-frame (:read-frame attachment)
                   :step-session-frame! (:step-frame! attachment)))))

(defn start-steppable-session!
  "Starts Ripple with one trusted in-process Session attached for inspection
  and exact revision-scoped stepping.

  The resulting HTTP handler receives only two closures over the trusted
  Session: `read-session-frame` over `viewer-session/read-frame` and
  `step-session-frame!` over `viewer-session/step-frame!`. It never receives
  the Session value, `session/step!`, or any browser-selected function, and
  browser data selects only the closed JSON step contract's fixed action
  tags.

  Attach only sessions whose cooperative step callback obeys the Session's
  deterministic, effect-free contract: reading a frame computes successor
  previews through that callback, and stepping evaluates it inside the
  Session's command lock.

  Command durability is exactly the Session journal's process lifetime: a
  committed command is appended to the in-memory journal before its
  acknowledgment is returned, but nothing survives process exit. There is no
  durable ledger, no automatic retry, and no rebasing. Within one process
  lifetime the exact branch revision prevents duplicate execution: the
  identical request retried after a commit is stale and is answered with the
  safe stale coordinates instead of a second transition."
  [config sim-session]
  (start! config
          (assoc (dissoc (default-services config) :run-case)
                 :read-session-frame
                 (fn [cursor]
                   (viewer-session/read-frame sim-session cursor))
                 :step-session-frame!
                 (fn [branch cursor]
                   (viewer-session/step-frame! sim-session branch cursor)))))

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

  Usage: JOLT_SIM_VIEWER_TOKEN=<32+ chars> jolt -M:viewer [--eval] CONFIG.edn

  The token is deliberately supplied through the environment rather than the
  config file. `--eval` explicitly installs one trusted persistent EvalSession;
  without it the arbitrary-code endpoint is absent. Port 0 selects an
  ephemeral loopback port. The primordial
  thread owns SIGINT. On POSIX, server workers inherit its blocked signal mask;
  on Windows, the host uses console-interrupt delivery. In both cases,
  `park-until-interrupt` handles Ctrl+C, runs the registered server shutdown,
  and exits cleanly. Programmatic callers continue to use `start!`/`stop!`."
  [& args]
  (let [[eval? config-path]
        (cond
          (= 1 (count args)) [false (first args)]
          (and (= 2 (count args)) (= "--eval" (first args)))
          [true (second args)]
          :else
          (throw (config-error :wrong-arguments {:args (vec args)})))
        config (validate-config! (read-main-config config-path))]
    ;; On POSIX, block before the listener, accept loop, and handler executor
    ;; start so every worker inherits the blocked SIGINT mask. On Windows this
    ;; host operation is intentionally a no-op and the primordial thread owns
    ;; console-interrupt delivery instead. This prevents Ctrl+C from landing in
    ;; a worker's foreign wait or interrupting a mutex-backed promise deref.
    (jolt.host/block-sigint)
    (let [attached-eval-session (when eval? (eval-session/start))
          server (if attached-eval-session
                   (start-eval-session! config attached-eval-session)
                   (start! config))
          stopped? (atom false)
          stop-once! (fn []
                       (when (compare-and-set! stopped? false true)
                         ;; stop-server is bounded by jolt-tcp. Do not call
                         ;; EvalSession/close! here: arbitrary user code may
                         ;; hold its lock forever. The command-line session is
                         ;; process-owned and process exit reclaims it.
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
