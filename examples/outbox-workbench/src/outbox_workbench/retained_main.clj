(ns outbox-workbench.retained-main
  "Foreground Ripple launcher for one persistent evaluator and one retained
  canonical outbox application worker."
  (:require [clojure.edn :as edn]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.fixtures.outbox-json-delivery]
            [jolt.sim.fixtures.outbox-json-delivery-live]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.viewer :as viewer]))

(def default-config-path "config/ripple-eval.edn")

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required")
                      {:type ::missing-environment :name name})))
    value))

(defn- read-viewer-config [path]
  (let [config (edn/read-string (slurp path))]
    (when-not (map? config)
      (throw (ex-info "Ripple config must be an EDN map"
                      {:type ::invalid-viewer-config :path path})))
    (when (contains? config :capability-token)
      (throw (ex-info "capability token must come from the environment"
                      {:type ::invalid-viewer-config
                       :reason :token-in-config
                       :path path})))
    (assoc config :capability-token
           (required-environment "JOLT_SIM_VIEWER_TOKEN"))))

(defn retained-config
  "Builds the exact retained outbox child configuration from explicit
  executable and repository-root coordinates."
  [jolt-bin project-dir]
  {:worker-command
   [jolt-bin "-M:outbox-json-delivery-test:retained-outbox-worker"]
   :adapter
   'jolt.sim.fixtures.outbox-json-delivery-retained/run!
   :input {}
   :dir project-dir
   :temp-dir (or (System/getenv "JOLT_SIM_RETAINED_ARTIFACT_DIR")
                 (or (System/getenv "TMPDIR") "/tmp"))
   :extra-env {"JOLT_AOT_CACHE" "0"}
   :startup-timeout-ms 60000
   :command-timeout-ms 60000
   :kill-grace-ms 1000})

(defn- child-alive? [handle]
  (boolean (get-in (retained/snapshot handle) [:child :alive?])))

(defn- wait-for-child-exit [handle timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [snapshot (retained/snapshot handle)]
      (if (or (not (get-in snapshot [:child :alive?]))
              (>= (System/nanoTime) deadline))
        snapshot
        (do
          (Thread/sleep 10)
          (recur (retained/snapshot handle)))))))

(defn- stop-child! [handle]
  (let [errors (atom [])
        receipt
        (when (child-alive? handle)
          (try
            (retained/command! handle {:op :stop} 5000)
            (catch :default error
              (swap! errors conj {:phase :graceful-stop
                                  :message (ex-message error)})
              nil)))]
    (when receipt
      (wait-for-child-exit handle 3000))
    (when (child-alive? handle)
      (try
        (retained/terminate! handle)
        (catch :default error
          (swap! errors conj {:phase :terminate
                              :message (ex-message error)}))))
    {:receipt receipt
     :snapshot (retained/snapshot handle)
     :errors @errors}))

(defn start!
  "Starts one retained outbox worker, one EvalSession, and Ripple.

  The returned map is intentionally an example-local lifecycle value. The
  viewer and evaluator never own the retained child; `stop!` owns the single
  shutdown transition and retains all child artifact coordinates."
  [{:keys [viewer-config retained-config]}]
  (let [worker* (volatile! nil)
        session* (volatile! nil)
        server* (volatile! nil)]
    (try
      (let [worker (retained/start! retained-config)
            _ (vreset! worker* worker)
            session (eval-session/start)
            _ (vreset! session* session)
            server (viewer/start-retained-eval-session!
                    viewer-config worker session)
            _ (vreset! server* server)]
        {:worker worker
         :session session
         :server server
         :stopping? (atom false)
         :stop-result (promise)})
      (catch :default primary
        (let [cleanup-errors (atom [])]
          (when-let [server @server*]
            (try
              (viewer/stop! server)
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :viewer-stop :message (ex-message error)}))))
          (when-let [session @session*]
            (try
              (eval-session/close! session)
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :eval-close :message (ex-message error)}))))
          (when-let [worker @worker*]
            (try
              (let [result (stop-child! worker)]
                (swap! cleanup-errors into (:errors result)))
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :worker-stop :message (ex-message error)}))))
          (if (seq @cleanup-errors)
            (throw (ex-info (or (ex-message primary)
                                "retained workbench startup failed")
                            (assoc (or (ex-data primary) {})
                                   ::cleanup-errors @cleanup-errors)
                            primary))
            (throw primary)))))))

(defn stop!
  "Stops Ripple, then gracefully stops the retained application when possible
  and forcibly reaps it only if it remains alive. Repeated calls return the
  same bounded result. EvalSession closure is intentionally omitted: an
  arbitrary evaluation may own its lock forever, and process exit reclaims
  this command-line-owned session."
  [{:keys [server worker stopping? stop-result]}]
  (if (compare-and-set! stopping? false true)
    (let [viewer-error (try
                         (viewer/stop! server)
                         nil
                         (catch :default error
                           {:phase :viewer-stop
                            :message (ex-message error)}))
          child (try
                  (stop-child! worker)
                  (catch :default error
                    {:receipt nil
                     :snapshot (try
                                 (retained/snapshot worker)
                                 (catch :default _ nil))
                     :errors [{:phase :worker-stop
                               :message (ex-message error)}]}))
          result (cond-> {:child child}
                   viewer-error (assoc :viewer-error viewer-error))]
      (deliver stop-result result)
      result)
    @stop-result))

(defn -main [& [config-path]]
  ;; Block before the retained child supervision and HTTP worker threads are
  ;; created. The primordial thread alone receives Ctrl+C.
  (jolt.host/block-sigint)
  (let [workbench
        (start! {:viewer-config
                 (read-viewer-config (or config-path default-config-path))
                 :retained-config
                 (retained-config
                  (required-environment "JOLT_SIM_BIN")
                  (required-environment "JOLT_SIM_PROJECT_DIR"))})
        stop-once! #(stop! workbench)]
    (try
      (jolt.host/add-shutdown-hook stop-once!)
      (println (str "Ripple: http://127.0.0.1:"
                    (get-in workbench [:server :port])))
      (println (str "Retained outbox artifacts: "
                    (get-in (retained/snapshot (:worker workbench))
                            [:artifact :dir])))
      (flush)
      (jolt.host/park-until-interrupt)
      (finally
        (stop-once!)))))
