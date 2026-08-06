(ns jolt.sim.viewer-browser-server
  "Deterministic Playwright server over the real Ripple HTTP handler.

  The browser uploads a checked-in Case/Outcome, while the trusted replay
  seam returns one real retained activity journal with forty small semantic
  events. This keeps browser acceptance independent of the expensive
  whole-application worker already covered by the raw HTTP E2E lane."
  (:require [jolt.fs :as fs]
            [jolt.sim.activity :as activity]
            [jolt.sim.report :as report]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer.experiment :as viewer-experiment]))

(def ^:private capability-token
  "ripple-browser-test-capability-0123456789abcdef")

(def ^:private scenario
  'jolt.sim.browser-test/activity-scenario)

(defn- required-port []
  (let [raw (or (System/getenv "JOLT_SIM_BROWSER_PORT") "8791")
        port (parse-long raw)]
    (when-not (and (integer? port) (<= 1 port 65535))
      (throw (ex-info "invalid JOLT_SIM_BROWSER_PORT"
                      {:type ::invalid-port :value raw})))
    port))

(defn- browser-event [ordinal]
  [:jolt.sim.browser-test/activity nil nil
   {:ordinal ordinal :scenario scenario}])

(defn- browser-presentation [event]
  (let [ordinal (get-in event [3 :ordinal])]
    {:summary (str "Browser activity event " ordinal)
     :fields [{:label "Ordinal" :value ordinal}
              {:label "Scenario" :value (get-in event [3 :scenario])}]}))

(defn- retained-outcome! []
  (let [artifact-root
        (or (System/getenv "JOLT_SIM_BROWSER_ARTIFACT_DIR")
            (str (fs/temp-dir)))
        _ (fs/create-dirs artifact-root)
        run-dir (str (fs/create-temp-dir
                      {:dir artifact-root
                       :prefix "jolt-sim-ripple-browser-activity-"}))
        observer (activity/open-observer!
                  {:path (str (fs/path run-dir "activity.journal"))
                   :run-id (byte-array 16)})]
    (activity/call-with-observer
     observer
     (fn []
       (doseq [ordinal (range 40)]
         (activity/emit! (browser-event ordinal)))))
    (let [status (activity/close-observer! observer)]
      (when-not (and (= :healthy (:health status))
                     (= 40 (:accepted status))
                     (= 40 (:sequence status))
                     (true? (:closed? status)))
        (throw (ex-info "browser activity fixture journal failed"
                        {:type ::activity-fixture-failed
                         :status status
                         :retained-directory run-dir})))
      {:status :completed
       :exit 0
       :artifact-dir run-dir
       :activity {:observer-status status}
       :diagnostics
       {:stdout {:bytes 0 :truncated? false :text ""}
        :stderr {:bytes 0 :truncated? false :text ""}}})))

(defn- config [port]
  (let [plan (viewer-experiment/read-edn
              (slurp "examples/outbox-cancel-before-ack-plan.edn"))]
    {:port port
     :capability-token capability-token
     :max-document-bytes (* 1024 1024)
     :allowed-scenarios #{scenario}
     :run-presets
     [{:id :jolt.sim.preset/outbox-cancel-before-ack-v1
       :label "Outbox: cancel before acknowledgment"
       :scenario scenario
       :profile-id :hermetic
       :input nil
       :schedule nil
       :plan-document plan}]
     :activity-presentation-registry
     {:jolt.sim.browser-test/activity
      {:kind :jolt.sim.kind/browser-test-activity
       :present browser-presentation}}
     :runtime-config
     {:worker-command ["browser-test-does-not-launch-a-worker"]
      :dir "."
      :timeout-ms 1000
      :retain-completed-artifacts? true
      :activity-journal? true}}))

(defn- services [outcome]
  {:render-trace report/trace->html
   :render-case-outcome report/case-outcome->html
   :replay-document (fn [_document _runtime] outcome)
   :run-case (fn [_runtime] outcome)})

(defn -main [& args]
  (when (seq args)
    (throw (ex-info "browser server accepts no arguments"
                    {:type ::unexpected-arguments
                     :arguments (vec args)})))
  (let [port (required-port)
        outcome (retained-outcome!)]
    (jolt.host/block-sigint)
    (let [server (viewer/start! (config port) (services outcome))
          stopped? (atom false)
          stop-once! (fn []
                       (when (compare-and-set! stopped? false true)
                         (viewer/stop! server)))]
      (try
        (jolt.host/add-shutdown-hook stop-once!)
        (println (str "Ripple browser fixture: http://127.0.0.1:" port))
        (flush)
        (jolt.host/park-until-interrupt)
        (finally
          (stop-once!))))))
