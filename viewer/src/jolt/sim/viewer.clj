(ns jolt.sim.viewer
  "Loopback-only retained-case inspection and fresh-process replay UI.

  This optional dependency root is a thin HTTP adapter over the existing
  Case/Outcome validator, report view, and `jolt.sim.repl/replay-document!`.
  It is not a scheduler, controller, monitor, evidence store, or second replay
  implementation.

  Browser requests carry only one retained Case/Outcome document. Trusted
  runtime configuration (worker command, project directory, timeout, artifact
  roots, and environment) is fixed when the server starts and can never be
  supplied by a browser request. Replays are additionally restricted to an
  explicit scenario allowlist and require a process capability token.

  `jolt-tcp`, underneath jolt-http, binds its listener to 127.0.0.1. The token
  remains required because other local processes and browser-origin attacks
  are still inside that network boundary."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.repl :as sim-repl]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]))

(def invalid-config ::invalid-config)
(def request-too-large ::request-too-large)

(def ^:private config-keys
  #{:port :capability-token :max-document-bytes
    :allowed-scenarios :runtime-config})

(def ^:private replay-coordinate-keys
  #{:scenario :mode :input :schedule})

(def ^:private runtime-config-keys
  #{:worker-command :timeout-ms :startup-timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts?})

(def ^:private service-keys
  #{:render-document :replay-document})

(def ^:private default-max-document-bytes (* 1024 1024))
(def ^:private maximum-max-document-bytes (* 16 1024 1024))
(def ^:private minimum-token-length 32)

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

(defn- request-document [config request]
  (let [bytes (bounded-body-bytes request (:max-document-bytes config))]
    (case-outcome/read-edn (String. bytes "UTF-8"))))

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

      (= case-outcome/invalid-document type)
      (error-response 400 :invalid-document
                      (select-keys data [:reason]))

      (= ::scenario-not-allowed type)
      (error-response 403 :scenario-not-allowed
                      (select-keys data [:scenario]))

      :else nil)))

(defn- execute-document-request [config request operation]
  (cond
    (not (authorized? config request))
    (error-response 403 :forbidden nil)

    (not (edn-content-type? request))
    (error-response 415 :expected-application-edn nil)

    :else
    (try
      (operation (request-document config request))
      (catch :default error
        (if-let [expected (expected-request-error-response error)]
          expected
          (throw error))))))

(defn make-handler
  "Creates a synchronous jolt-http handler.

  The optional `services` map is a narrow embedding/test seam. Its only keys
  are `:render-document` (`doc -> html`) and `:replay-document`
  (`doc runtime-config -> outcome`). Browser data never selects either
  function or supplies runtime configuration."
  ([config]
   (make-handler config
                 {:render-document report/case-outcome->html
                  :replay-document sim-repl/replay-document!}))
  ([config services]
   (let [config (validate-config! config)
         unknown-services (into #{} (remove service-keys) (keys services))]
     (when (seq unknown-services)
       (throw (config-error :unknown-service-keys unknown-services)))
     (when-not (and (fn? (:render-document services))
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

           (and (= :post method) (= "/api/render" uri))
           (execute-document-request
            config request
            (fn [document]
              (response 200 "text/html; charset=utf-8"
                        ((:render-document services) document))))

           (and (= :post method) (= "/api/replay" uri))
           (execute-document-request
            config request
            (fn [document]
              (allowed-replay! config document)
              (response 200 "application/edn; charset=utf-8"
                        (trace/canonical-edn
                         ((:replay-document services)
                          document (:runtime-config config))))))

           :else
           (error-response 404 :not-found nil)))))))

(defn start!
  "Starts the loopback viewer and returns the jolt-http server handle."
  [config]
  (let [config (validate-config! config)]
    (http/run-server (make-handler config)
                     :port (:port config)
                     ;; One handler means at most one fresh-process replay can
                     ;; run even when several browser tabs share the token.
                     ;; The viewer favors bounded resource use over serving an
                     ;; inspection request concurrently with a replay.
                     :pool-size 1
                     :reuse-address? true)))

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
  config file. Port 0 selects an ephemeral loopback port. The process remains
  alive until stopped externally."
  [& args]
  (when-not (= 1 (count args))
    (throw (config-error :wrong-argument-count {:args (vec args)})))
  (let [config (validate-config! (read-main-config (first args)))
        server (start! config)]
    (println (str "jolt-sim viewer: http://127.0.0.1:" (:port server)))
    (flush)))
