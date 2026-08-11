(ns maelstrom-broadcast-workbench.main
  "Ripple and persistent-REPL launcher for one real retained Broadcast worker.

  The worker owns the actual three-node Broadcast applications, memory
  transport, mailboxes, and lifecycle. This namespace owns only process/UI
  composition and provides UI-neutral REPL helpers over jolt.sim.retained-view.
  Ripple's retained panel and these helpers share exactly one serialized
  retained-process handle."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.retained-view :as retained-view]
            [jolt.sim.viewer :as viewer]
            [maelstrom-broadcast-workbench.presentation :as value-presentation]))

(def default-config-path "config/ripple.edn")

;; The command-line workbench is deliberately a singleton. This is not worker
;; state: it is only the trusted in-process coordinate that lets Ripple's
;; EvalSession and an attached REPL address the same caller-owned handle.
(defonce ^:private active-workbench* (atom nil))
(def ^:private end-of-config (Object.))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required")
                      {:type ::missing-environment :name name})))
    value))

(defn- optional-port []
  (when-let [raw (System/getenv "JOLT_SIM_VIEWER_PORT")]
    (let [port (parse-long raw)]
      (when-not (and (integer? port) (<= 0 port 65535))
        (throw (ex-info "JOLT_SIM_VIEWER_PORT must be an integer from 0 to 65535"
                        {:type ::invalid-port :value raw})))
      port)))

(defn parse-config-edn
  "Reads exactly one EDN form from text before any workbench allocation.

  This deliberately performs a structural reader pass and an EDN pass. The
  first pass rejects empty input and a second top-level form; the second keeps
  tagged-literal and EDN semantics identical to the rest of the workbench."
  [text path]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-config)
          [trailing _] (read+string reader false end-of-config)]
      (when (identical? value end-of-config)
        (throw (ex-info "Broadcast workbench config is empty"
                        {:type ::invalid-config
                         :reason :empty-document
                         :path path})))
      (when-not (identical? trailing end-of-config)
        (throw (ex-info "Broadcast workbench config has a trailing EDN form"
                        {:type ::invalid-config
                         :reason :trailing-document
                         :path path})))
      (edn/read-string text))
    (catch :default error
      (if (= ::invalid-config (:type (ex-data error)))
        (throw error)
        (throw (ex-info "Broadcast workbench config is not exactly one EDN form"
                        {:type ::invalid-config
                         :reason :malformed-edn
                         :path path}
                        error))))))

(defn- read-workbench-config [path]
  (let [config (parse-config-edn (slurp path) path)]
    (when-not (and (map? config)
                   (= #{:viewer :input} (set (keys config)))
                   (map? (:viewer config)))
      (throw (ex-info "Broadcast workbench config must contain exact viewer and input maps"
                      {:type ::invalid-config :path path})))
    (when (contains? (:viewer config) :capability-token)
      (throw (ex-info "capability token must come from the environment"
                      {:type ::invalid-config
                       :reason :token-in-config
                       :path path})))
    (let [port (optional-port)
          viewer-config
          (cond-> (assoc (:viewer config) :capability-token
                         (required-environment "JOLT_SIM_VIEWER_TOKEN"))
            (some? port) (assoc :port port))]
      {:viewer-config viewer-config
       :input (:input config)})))

(defn retained-config
  "Builds the exact root-worker configuration for one Broadcast input."
  [jolt-bin project-dir input]
  (let [temp-dir (or (System/getenv "JOLT_SIM_RETAINED_ARTIFACT_DIR")
                     (or (System/getenv "TMPDIR") "/tmp"))]
    ;; A dedicated Playwright/CI artifact root may not exist yet. Create only
    ;; this explicit parent before retained-process atomically allocates its
    ;; unique child directory; never clean or reuse an earlier run.
    (fs/create-dirs temp-dir)
    {:worker-command [jolt-bin "-M:maelstrom-broadcast-retained-worker"]
     :adapter 'jolt.sim.fixtures.broadcast-retained/run!
     :input input
     :dir project-dir
     :temp-dir temp-dir
     :extra-env {"JOLT_AOT_CACHE" "0"}
     :startup-timeout-ms 60000
     :command-timeout-ms 60000
     :kill-grace-ms 1000}))

(defn- active-workbench []
  (let [active @active-workbench*]
    (if (map? active)
      active
      (throw (ex-info "no Broadcast workbench is active"
                      {:type ::workbench-unavailable})))))

(defn frame
  "Returns the generic, browser-safe retained supervisor frame."
  []
  (retained-view/read-frame (:worker (active-workbench))))

(defn command!
  "Publishes one exact retained command and returns its definitive generic view."
  [command]
  (retained-view/command-frame! (:worker (active-workbench)) command))

(defn inspect! [] (command! {:op :inspect}))
(defn bootstrap! [] (command! {:op :bootstrap}))
(defn step! [node-id] (command! {:op :step :node-id node-id}))
(defn set-connection-regime!
  "Sets one undirected connection regime at the caller-observed revision."
  [connection expected-revision regime]
  (command! {:op :set-connection-regime
             :connection connection
             :expected-revision expected-revision
             :regime regime}))
(defn drop-connection!
  "Convenience REPL command for dropping future sends on one connection."
  [connection expected-revision]
  (set-connection-regime! connection expected-revision :drop))
(defn restore-connection!
  "Convenience REPL command for restoring future sends on one connection."
  [connection expected-revision]
  (set-connection-regime! connection expected-revision :normal))
(defn heal! [] (command! {:op :heal}))
(defn retry! [] (command! {:op :retry}))
(defn read! [] (command! {:op :read}))
(defn stop-worker! [] (command! {:op :stop}))

(defn present
  "Purely presents one snapshot or application command value for REPL/tap use."
  [value]
  (value-presentation/present value))

(defn reconcile!
  "Reconciles the one uncertain retained command without republishing it."
  []
  (retained-view/reconcile-frame! (:worker (active-workbench))))

(defn terminate!
  "Forcibly terminates/reaps the active child while preserving its artifacts."
  []
  (retained-view/terminate-frame! (:worker (active-workbench))))

(defn- child-alive? [worker]
  (boolean (get-in (retained/snapshot worker) [:child :alive?])))

(defn- wait-for-child-exit [worker timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [snapshot (retained/snapshot worker)]
      (if (or (not (get-in snapshot [:child :alive?]))
              (>= (System/nanoTime) deadline))
        snapshot
        (do
          (Thread/sleep 10)
          (recur (retained/snapshot worker)))))))

(defn- stop-child! [worker]
  (let [errors (atom [])
        receipt
        (when (child-alive? worker)
          (try
            (retained/command! worker {:op :stop} 5000)
            (catch :default error
              (swap! errors conj {:phase :graceful-stop
                                  :message (ex-message error)})
              nil)))]
    (when receipt
      (wait-for-child-exit worker 3000))
    (when (child-alive? worker)
      (try
        (retained/terminate! worker)
        (catch :default error
          (swap! errors conj {:phase :terminate
                              :message (ex-message error)}))))
    {:receipt receipt
     :snapshot (retained/snapshot worker)
     :errors @errors}))

(defn start!
  "Starts and owns one retained Broadcast worker, EvalSession, and Ripple.

  The active reference is installed before Ripple accepts evaluation requests,
  so the retained panel and persistent REPL cannot accidentally address
  different workers. Only one workbench may be active in a process."
  [{:keys [viewer-config retained-config] :as config}]
  (when-not (and (map? config)
                 (= #{:viewer-config :retained-config} (set (keys config))))
    (throw (ex-info "workbench start config has the wrong shape"
                    {:type ::invalid-start-config})))
  (when-not (compare-and-set! active-workbench* nil ::starting)
    (throw (ex-info "a Broadcast workbench is already active"
                    {:type ::workbench-already-active})))
  (let [worker* (volatile! nil)
        session* (volatile! nil)
        server* (volatile! nil)]
    (try
      (let [worker (retained/start! retained-config)
            _ (vreset! worker* worker)
            session (eval-session/start)
            _ (vreset! session* session)
            partial {:worker worker :session session}
            _ (reset! active-workbench* partial)
            server (viewer/start-retained-eval-session!
                    (assoc viewer-config
                           :value-presentation-registry
                           value-presentation/value-registry)
                    worker session)
            _ (vreset! server* server)
            workbench (assoc partial
                             :server server
                             :stopping? (atom false)
                             :stop-result (promise))]
        (reset! active-workbench* workbench)
        workbench)
      (catch :default primary
        (let [cleanup-errors (atom [])
              worker-before-cleanup
              (when-let [worker @worker*]
                (try
                  (retained/snapshot worker)
                  (catch :default error
                    {:snapshot-error (ex-message error)})))
              worker-cleanup (volatile! nil)]
          ;; Remove REPL addressability before unwinding partially constructed
          ;; resources. The retained artifact coordinates remain in the wrapped
          ;; startup exception even when cleanup itself succeeds.
          (reset! active-workbench* nil)
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
                (vreset! worker-cleanup result)
                (swap! cleanup-errors into (:errors result)))
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :worker-stop :message (ex-message error)}))))
          (throw
           (ex-info (or (ex-message primary)
                        "Broadcast workbench startup failed")
                    (cond-> (or (ex-data primary) {})
                      worker-before-cleanup
                      (assoc ::worker-before-cleanup worker-before-cleanup)

                      @worker-cleanup
                      (assoc ::worker-cleanup @worker-cleanup)

                      (seq @cleanup-errors)
                      (assoc ::cleanup-errors @cleanup-errors))
                    primary)))))))

(defn shutdown!
  "Stops Ripple admission, removes shared REPL access, and stops/reaps child.

  The operation is idempotent. EvalSession closure is intentionally omitted:
  arbitrary evaluated code may own its lock forever, and this command-line
  session is reclaimed at process exit."
  [{:keys [server worker stopping? stop-result] :as workbench}]
  (if (compare-and-set! stopping? false true)
    (let [viewer-error
          (try
            (viewer/stop! server)
            nil
            (catch :default error
              {:phase :viewer-stop :message (ex-message error)}))
          _ (compare-and-set! active-workbench* workbench nil)
          child
          (try
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
  ;; The primordial thread alone receives Ctrl+C. Every later server and worker
  ;; thread inherits the pinned host's safe signal disposition.
  (jolt.host/block-sigint)
  (let [{:keys [viewer-config input]}
        (read-workbench-config (or config-path default-config-path))
        workbench
        (start! {:viewer-config viewer-config
                 :retained-config
                 (retained-config
                  (required-environment "JOLT_SIM_BIN")
                  (required-environment "JOLT_SIM_PROJECT_DIR")
                  input)})
        shutdown-once! #(shutdown! workbench)]
    (try
      (jolt.host/add-shutdown-hook shutdown-once!)
      (println (str "Ripple Broadcast workbench: http://127.0.0.1:"
                    (get-in workbench [:server :port])))
      (println (str "Retained Broadcast artifacts: "
                    (get-in (retained/snapshot (:worker workbench))
                            [:artifact :dir])))
      (flush)
      (jolt.host/park-until-interrupt)
      (finally
        (shutdown-once!)))))
