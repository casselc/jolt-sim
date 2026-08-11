(ns outbox-workbench.flow-ripple-main
  "Interactive Ripple launcher for the real retained outbox flow.

  One finite pure flow emits the unchanged submit and deliver commands in
  order.  jolt.sim.flow-effect-session commit-gates those intents onto the
  unchanged retained outbox worker; Ripple and a REPL share the same opaque
  bridge, revision, and effect ledger.

  The returned lifecycle deliberately keeps ownership explicit. Stopping
  Ripple alone does not close the bridge or touch the worker. `shutdown!`
  first closes bridge admission, then stops Ripple and gracefully stops and
  reaps the worker, using forced termination only as a bounded fallback."
  (:require [clojure.edn :as edn]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
            [jolt.sim.flow :as flow]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.workbench-session :as workbench-session]
            [outbox-workbench.flow-retained :as flow-retained]))

(def default-config-path "config/ripple-eval.edn")

(defonce ^:private active-workbench* (atom nil))
(def ^:private end-of-config (Object.))

(defn active-bridge
  "Returns the exact flow/effect bridge owned by the foreground workbench.

  This trusted REPL seam and Ripple address the same revision/effect ledger.
  It is unavailable for programmatic `start!` lifecycles and outside `-main`."
  []
  (if-let [workbench @active-workbench*]
    (:bridge workbench)
    (throw (ex-info "No foreground Outbox flow workbench is active"
                    {:type ::not-running}))))

(defn active-items
  "Returns the generic WorkbenchSession shared by Ripple and this REPL.

  `datafy`, `nav`, `frame`, and `canonical-edn` remain ordinary UI-neutral
  data operations. Applications may append additional values explicitly; the
  generic Ripple composer also records definite evaluation, simulation, and
  retained-worker results after they complete."
  []
  (if-let [workbench @active-workbench*]
    (:workbench-session workbench)
    (throw (ex-info "No foreground Outbox flow workbench is active"
                    {:type ::not-running}))))

(defn- effect-result-presentation [value]
  (let [records (get-in value [:flow-effect :effects :records])
        latest (when (seq records) (peek records))]
    {:summary "Outbox flow result"
     :fields [{:label "Effect records" :value (count records)}
              {:label "Effect status"
               :value (get-in value [:flow-effect :status])}
              {:label "Latest receipt"
               :value (get-in latest [:receipt :status])}
              {:label "Operation status" :value (:status value)}
              {:label "Outbox row"
               :value (get-in latest
                              [:receipt :value :snapshot :store-state
                               :outbox 0 :status])}]}))

(def workbench-kind-registry
  "Trusted app-owned renderer offered to the generic WorkbenchSession.

  Only its namespaced kind is persisted. The function remains process-local
  and neither the browser nor exported EDN can install or select executable
  code."
  {:example.outbox/effect-result {:present effect-result-presentation}})

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required")
                      {:type ::missing-environment :name name})))
    value))

(defn parse-config-edn
  "Reads exactly one EDN form before any worker or server allocation."
  [text path]
  (try
    (let [reader (__string-reader text)
          [value _] (read+string reader false end-of-config)
          [trailing _] (read+string reader false end-of-config)]
      (when (identical? value end-of-config)
        (throw (ex-info "Outbox workbench config is empty"
                        {:type ::invalid-viewer-config
                         :reason :empty-document :path path})))
      (when-not (identical? trailing end-of-config)
        (throw (ex-info "Outbox workbench config has a trailing EDN form"
                        {:type ::invalid-viewer-config
                         :reason :trailing-document :path path})))
      (edn/read-string text))
    (catch :default error
      (if (= ::invalid-viewer-config (:type (ex-data error)))
        (throw error)
        (throw (ex-info "Outbox workbench config is not exactly one EDN form"
                        {:type ::invalid-viewer-config
                         :reason :malformed-edn :path path}
                        error))))))

(defn- read-viewer-config [path]
  (let [config (parse-config-edn (slurp path) path)]
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

(defn interactive-flow
  "Builds one finite two-command flow using the existing outbox command cell.

  The finite-entry seam is simulator scheduling data, not an application
  replacement: both payloads pass unchanged through flow-retained's existing
  schema and handler. The first exact branch emits `submit-command`; the next
  exact branch emits `{:op :deliver}` on the same Session and effect ledger."
  [submit-command]
  (flow-retained/validate-command! submit-command)
  (flow/compile-workflow
   {:cells {:outbox :emit-command}
    :edges []
    :entries [{:cell :outbox :data submit-command}
              {:cell :outbox :data {:op :deliver}}]
    :resources {}}
   flow-retained/command-specs
   flow-retained/command-handlers))

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

(defn- stop-worker! [worker]
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
  "Starts the real retained worker, one two-command bridge, and Ripple.

  `:bridge` is the same opaque capability used by Ripple and available to a
  trusted REPL caller. The caller owns the returned lifecycle and must invoke
  `shutdown!`; `stop-ripple!` is intentionally only a UI-server operation."
  [{:keys [viewer-config retained-config submit-command]}]
  ;; Compile the pure flow before allocating a child process. A malformed
  ;; workflow therefore cannot create retained artifacts that the caller has
  ;; not yet had an opportunity to record.
  (let [sim (interactive-flow submit-command)
        worker* (volatile! nil)
        bridge* (volatile! nil)
        eval-session* (volatile! nil)
        server* (volatile! nil)
        startup-phase* (volatile! :retained-start)]
    (try
      (let [worker (retained/start! retained-config)
            _ (vreset! worker* worker)
            _ (vreset! startup-phase* :bridge-attach)
            bridge (effect-session/attach!
                    {:sim sim
                     :worker (effect-session/retained-worker-service worker)
                     :effect-kind :example.outbox/command})
            _ (vreset! bridge* bridge)
            _ (vreset! startup-phase* :eval-session-start)
            eval-session (eval-session/start)
            _ (vreset! eval-session* eval-session)
            items (workbench-session/start
                   {:kind-registry workbench-kind-registry})
            _ (vreset! startup-phase* :viewer-start)
            server (viewer/start-workbench!
                    viewer-config
                    {:flow-effect-bridge bridge
                     :retained-process worker
                     :eval-session eval-session
                     :workbench-session items})
            _ (vreset! server* server)]
        {:worker worker
         :bridge bridge
         :eval-session eval-session
         :workbench-session items
         :server server
         :viewer-stopped? (atom false)
         :stopping? (atom false)
         :stop-result (promise)})
      (catch :default primary
        (let [cleanup-errors (atom [])
              worker-before-cleanup
              (when-let [worker @worker*]
                (try
                  (retained/snapshot worker)
                  (catch :default error
                    {:snapshot-error (ex-message error)})))
              worker-cleanup (volatile! nil)]
          (when-let [server @server*]
            (try
              (viewer/stop! server)
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :viewer-stop :message (ex-message error)}))))
          (when-let [bridge @bridge*]
            (try
              (effect-session/close! bridge)
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :bridge-close :message (ex-message error)}))))
          (when-let [session @eval-session*]
            (try
              (eval-session/close! session)
              (catch :default error
                (swap! cleanup-errors conj
                       {:phase :eval-close :message (ex-message error)}))))
          (when-let [worker @worker*]
            (try
              (let [result (stop-worker! worker)]
                (vreset! worker-cleanup result)
                (swap! cleanup-errors into (:errors result)))
              (catch :default error
                (vreset! worker-cleanup
                         {:snapshot (try
                                      (retained/snapshot worker)
                                      (catch :default _ nil))
                          :errors [{:phase :worker-stop
                                    :message (ex-message error)}]})
                (swap! cleanup-errors conj
                       {:phase :worker-stop :message (ex-message error)}))))
          ;; Always wrap a post-worker startup failure, even when cleanup was
          ;; successful. The retained directory is intentionally preserved;
          ;; these bounded owner-side coordinates make that rare evidence
          ;; discoverable before `start!` has returned a lifecycle handle.
          (throw (ex-info (or (ex-message primary)
                              "flow Ripple startup failed")
                          (cond-> (assoc (or (ex-data primary) {})
                                         ::startup-phase @startup-phase*
                                         ::startup-error
                                         (trace/normalize-error primary))
                            worker-before-cleanup
                            (assoc ::worker-before-cleanup worker-before-cleanup)

                            @worker-cleanup
                            (assoc ::worker-cleanup @worker-cleanup)

                            (seq @cleanup-errors)
                            (assoc ::cleanup-errors @cleanup-errors))
                          primary)))))))

(defn stop-ripple!
  "Stops only the Ripple HTTP server. The bridge and worker remain usable."
  [{:keys [server viewer-stopped?]}]
  (locking viewer-stopped?
    (when-not @viewer-stopped?
      (let [result (viewer/stop! server)]
        ;; Publish the terminal UI state only after shutdown succeeds.  A
        ;; failed stop therefore remains retryable by application cleanup.
        (reset! viewer-stopped? true)
        result))))

(defn shutdown!
  "Closes bridge admission, stops Ripple, and gracefully stops/reaps worker.

  The operation is idempotent. Closing the bridge never terminates its
  borrowed worker; this launcher performs the worker transition afterward.
  EvalSession closure is intentionally omitted because an arbitrary evaluation
  may own its lock forever; this command-line-owned session is reclaimed when
  its process exits."
  [{:keys [bridge worker stopping? stop-result] :as workbench}]
  (if (compare-and-set! stopping? false true)
    (let [bridge-error
          (try
            (effect-session/close! bridge)
            nil
            (catch :default error
              {:phase :bridge-close :message (ex-message error)}))
          viewer-error
          (try
            (stop-ripple! workbench)
            nil
            (catch :default error
              {:phase :viewer-stop :message (ex-message error)}))
          child
          (try
            (stop-worker! worker)
            (catch :default error
              {:receipt nil
               :snapshot (try
                           (retained/snapshot worker)
                           (catch :default _ nil))
               :errors [{:phase :worker-stop
                         :message (ex-message error)}]}))
          result (cond-> {:child child}
                   bridge-error (assoc :bridge-error bridge-error)
                   viewer-error (assoc :viewer-error viewer-error))]
      (deliver stop-result result)
      result)
    @stop-result))

(defn -main [& [config-path]]
  ;; The primordial thread alone receives Ctrl+C; worker/server threads start
  ;; only after SIGINT is blocked.
  (jolt.host/block-sigint)
  (let [workbench
        (start! {:viewer-config
                 (read-viewer-config (or config-path default-config-path))
                 :retained-config
                 (flow-retained/retained-config
                  (required-environment "JOLT_SIM_BIN")
                  (required-environment "JOLT_SIM_PROJECT_DIR"))
                 :submit-command {:op :submit
                                  :command outbox/default-command}})
        shutdown-once! #(shutdown! workbench)]
    (try
      (when-not (compare-and-set! active-workbench* nil workbench)
        (throw (ex-info "An Outbox flow workbench is already active"
                        {:type ::already-running})))
      (jolt.host/add-shutdown-hook shutdown-once!)
      (println (str "Ripple flow/effect outbox: http://127.0.0.1:"
                    (get-in workbench [:server :port])))
      (println (str "Retained outbox artifacts: "
                    (get-in (retained/snapshot (:worker workbench))
                            [:artifact :dir])))
      (flush)
      (jolt.host/park-until-interrupt)
      (finally
        (when (identical? workbench @active-workbench*)
          (reset! active-workbench* nil))
        (shutdown-once!)))))
