(ns jolt.sim.viewer
  "Loopback-only offline document inspection and fresh-process replay UI.

  This optional dependency root is a thin HTTP adapter over the existing
  trace, Case/Outcome, and inert experiment-plan validators, report views, and
  `jolt.sim.repl/replay-document!`. It is not a scheduler, controller,
  monitor, evidence store, or second replay implementation.

  Every browser request must declare its document kind explicitly
  (`:trace`, `:case-outcome`, or `:experiment-plan`) through the
  `X-Jolt-Sim-Document-Kind`
  header; the server never infers or guesses a schema from the uploaded
  bytes. Trace documents render through `jolt.sim.report/trace->html`;
  experiment-plan documents render only their safe inert projection. Neither
  kind is replayable. Case/Outcome documents render through
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
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.repl :as sim-repl]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer.experiment :as experiment-viewer]
            [jolt.sim.viewer.session :as viewer-session]))

(def invalid-config ::invalid-config)
(def request-too-large ::request-too-large)
(def document-kind-required ::document-kind-required)
(def unknown-document-kind ::unknown-document-kind)
(def trace-not-replayable ::trace-not-replayable)
(def experiment-plan-not-replayable ::experiment-plan-not-replayable)
(def invalid-session-cursor ::invalid-session-cursor)
(def invalid-session-step ::invalid-session-step)
(def invalid-activity-cursor ::invalid-activity-cursor)
(def ^:private invalid-session-frame ::invalid-session-frame)
(def ^:private invalid-session-step-result ::invalid-session-step-result)

(def ^:private config-keys
  #{:port :capability-token :max-document-bytes
    :allowed-scenarios :runtime-config :presentation-registry
    :activity-presentation-registry})

(def ^:private replay-coordinate-keys
  #{:scenario :mode :input :schedule})

(def ^:private runtime-config-keys
  #{:worker-command :timeout-ms :startup-timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts?
    :activity-journal?})

(def ^:private service-keys
  #{:render-trace :render-case-outcome :replay-document
    :read-session-frame :step-session-frame!})

(def ^:private default-max-document-bytes (* 1024 1024))
(def ^:private maximum-max-document-bytes (* 16 1024 1024))
(def ^:private minimum-token-length 32)

(def ^:private progress-log-byte-limit 65536)
(def ^:private session-journal-page-size 256)
(def ^:private maximum-session-cursor-digits 19)
(def ^:private session-step-body-limit 4096)
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
  [present entry]
  (let [row (present (:sequence entry) (:event entry))]
    {"sequence" (:sequence entry)
     "tag" (:tag row)
     "kind" (:kind-name row)
     "summary" (:summary row)
     "fields" (mapv (fn [field]
                      {"label" (:label field)
                       "valueEdn" (:value-edn field)})
                    (:fields row))
     "edn" (:edn row)}))

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
  [present page]
  {"version" 1
   "status" "ok"
   "cursor" (:cursor page)
   "nextCursor" (:next-cursor page)
   "acceptedCount" (:accepted-count page)
   "remaining" (boolean (:remaining? page))
   "events" (mapv #(activity-event-wire present %) (:events page))
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
  [config present state cursor]
  (when (and (= :terminal (:phase state))
             (activity-journal-enabled? config))
    (if-let [outcome (:outcome state)]
      (try
        (let [page (process-explorer/read-activity-page outcome cursor)]
          (try
            [(activity-page-wire present page) (:next-cursor page)]
            (catch :default _
              [(activity-unavailable-wire cursor "presentation-failed")
               cursor])))
        (catch :default error
          (let [data (ex-data error)]
            (cond
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
  [config present active-replay request]
  (try
    (let [cursor (activity-cursor! request)
          state (swap! active-replay observe-active-replay)
          base (progress-wire (replay-progress-state state))
          activity (terminal-activity-projection config present state cursor)
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
      :jolt.sim.viewer.session/invalid-cursor
      (error-response 400 :invalid-session-cursor
                      (select-keys data [:reason :cursor :journal-count]))

      :jolt.sim.viewer.session/coherence-failed
      (error-response 409 :session-frame-incoherent
                      (select-keys data [:attempts :max-attempts]))

      ::invalid-session-cursor
      (error-response 400 :invalid-session-cursor
                      (select-keys data [:reason]))

      nil)))

(defn- bounded-session-frame
  "Validates the trusted reader's closed journal coordinates and returns one
  bounded page. The Session adapter may have read a longer tail internally,
  but neither the HTTP response nor browser retains more than this page."
  [frame cursor]
  (let [journal (:journal frame)
        count (:count journal)
        entries (:entries journal)]
    (when-not (and (map? frame)
                   (= :frame (:jolt.sim.viewer.session/type frame))
                   (map? journal)
                   (= cursor (:cursor journal))
                   (integer? count)
                   (<= cursor count)
                   (vector? entries)
                   (= (- count cursor) (clojure.core/count entries)))
      (throw (ex-info "trusted session reader returned an invalid frame"
                      {:type invalid-session-frame})))
    (let [page (vec (take session-journal-page-size entries))
          next-cursor (+ cursor (clojure.core/count page))]
      (assoc frame :journal
             (assoc journal
                    :entries page
                    :next-cursor next-cursor
                    :page-size (clojure.core/count page)
                    :remaining? (< next-cursor count))))))

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
    (error-response 429 :session-frame-busy nil)

    :else
    (try
      (session-frame-response config services request)
      (finally
        (reset! session-active? false)))))

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
                    (:jolt.sim.viewer.session/type result)))
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
           (= :frame (:jolt.sim.viewer.session/type frame))
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
        type (:type data)]
    (cond
      (= request-too-large type)
      (error-response 413 :request-too-large
                      (select-keys data [:limit :actual]))

      (= invalid-session-step type)
      (error-response 400 :invalid-session-step
                      (select-keys data [:reason]))

      (= :jolt.sim.viewer.session/invalid-cursor type)
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
  checked before service availability, the admission gates, and the body so
  an untrusted local caller cannot observe step service state. The session
  gate is shared with `GET /api/session-frame`, so a simultaneous frame read
  and step rejects with 429 before either the trusted reader or stepper is
  invoked. The body is consumed only while holding the shared
  document/body-consumer gate, so a busy response precedes any streaming
  body read on the pool shared with jolt-http's parser."
  [config services session-active? document-active? request]
  (cond
    (not (authorized? config request))
    (negotiated-session-error-response request 403 :forbidden nil)

    (not (fn? (:step-session-frame! services)))
    (negotiated-session-error-response request 404 :session-step-unavailable nil)

    (not (json-content-type? request))
    (negotiated-session-error-response request 415 :expected-application-json nil)

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
        (reset! session-active? false)))))

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
    :experiment-plan (experiment-viewer/read-edn text)))

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
          (= experiment-viewer/invalid-document type))
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

  `GET /api/session-frame` is an optional embedding-only, inspection surface.
  When the trusted services map supplies `:read-session-frame`, it returns one
  coherent canonical Session projection, enabled branch references, isolated
  successor previews, and append-only journal tail. Browser data supplies
  only an unsigned journal cursor. Computing successor previews does invoke
  the Session's cooperative step callback, so attached sessions must satisfy
  its deterministic, effect-free contract. A dedicated single-flight gate,
  shared with `POST /api/session-step`, admits only one frame computation or
  step at a time.

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
  authorization is checked before that availability, the admission gates, and
  the body.

  `GET /api/replay-progress` reports the one active or most recently
  completed replay's status (`:idle`, `:starting`, `:worker-ready`,
  `:running`, `:completed`, or `:failed`) plus bounded stdout/stderr text, by
  reading only fixed basenames (`worker-ready.edn`, `stdout.log`,
  `stderr.log`, and -- existence only, never parsed -- `result.edn`) from the
  trusted active run directory. It never accepts a filesystem path from the
  browser.

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
          unknown-services (into #{} (remove service-keys) (keys services))
          document-active? (atom false)
          session-active? (atom false)
          active-replay (atom (initial-replay-state))
          activity-present
          (presentation/activity-event-presenter
           (presentation/activity-registry
            presentation/default-activity-registry
            (:activity-presentation-registry config)))]
     (when (seq unknown-services)
       (throw (config-error :unknown-service-keys unknown-services)))
     (when-not (and (fn? (:render-trace services))
                    (fn? (:render-case-outcome services))
                    (fn? (:replay-document services))
                    (or (not (contains? services :read-session-frame))
                        (fn? (:read-session-frame services)))
                    (or (not (contains? services :step-session-frame!))
                        (fn? (:step-session-frame! services))))
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
               config activity-present active-replay request))

           (and (= :get method) (= "/api/session-frame" uri))
           (execute-session-frame-request
            config services session-active? request)

           (and (= :post method) (= "/api/session-step" uri))
           (execute-session-step-request
            config services session-active? document-active? request)

           (and (= :post method) (= "/api/render" uri))
           (execute-document-request
            config document-active? request
            (fn [kind document]
              (response 200 "text/html; charset=utf-8"
                        (if (= :experiment-plan kind)
                          (experiment-viewer/document->html
                           document (:presentation-registry config))
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
                  {:type (if (= :experiment-plan kind)
                           experiment-plan-not-replayable
                           trace-not-replayable)
                   :kind kind})))
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
                          (cond-> {:phase :terminal
                                   :status (outcome->progress-status outcome)
                                   :stdout (get-in outcome [:diagnostics :stdout]
                                                   empty-progress-diagnostic)
                                   :stderr (get-in outcome [:diagnostics :stderr]
                                                   empty-progress-diagnostic)}
                            ;; The trusted outcome is retained only for the
                            ;; opt-in activity journal; its artifact path never
                            ;; crosses the wire.
                            (activity-journal-enabled? config)
                            (assoc :outcome outcome)))
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
          (assoc (default-services config)
                 :read-session-frame
                 (fn [cursor]
                   (viewer-session/read-frame sim-session cursor)))))

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
          (assoc (default-services config)
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
