(ns jolt.sim.viewer-browser-server
  "Deterministic Playwright server over the real Ripple HTTP handler.

  The browser uploads a checked-in Case/Outcome, while the trusted replay
  seam returns one real retained activity journal with forty small semantic
  events. This keeps browser acceptance independent of the expensive
  whole-application worker already covered by the raw HTTP E2E lane."
  (:require [jolt.example.outbox.regimes :as outbox-regimes]
            [jolt.fs :as fs]
            [jolt.sim.activity :as activity]
            [jolt.sim.report :as report]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer.experiment :as viewer-experiment]))

(def ^:private capability-token
  "ripple-browser-test-capability-0123456789abcdef")

(def ^:private scenario
  'jolt.sim.browser-test/activity-scenario)

(def ^:private echo-scenario
  'jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip)

(def ^:private outbox-regime-lab-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities)

(def ^:private broadcast-scenario
  'jolt.maelstrom.fixtures.broadcast-scenario/broadcast-partition-heal)

(def ^:private broadcast-scope
  "Both Broadcast regimes select only the scenario's partition-link
   coordinate; every other behavior belongs to the unchanged scenario,
   Broadcast application, node boundary, and memory transport."
  [:jolt.maelstrom.broadcast/link-partition-selection])

(def ^:private broadcast-healthy-regime
  {:id :jolt.sim.regime/maelstrom-broadcast-healthy
   :label "Healthy three-node line"
   :summary (str "Run the trusted Maelstrom Broadcast example on the "
                 "unpartitioned n1-n2-n3 line.")
   :scope broadcast-scope
   :input {:message 42 :partition-links []}})

(def ^:private broadcast-partition-regime
  {:id :jolt.sim.regime/maelstrom-broadcast-partition-heal
   :label "Partition n2-n3, heal, retry"
   :summary (str "Run the trusted Maelstrom Broadcast example with the n2-n3 "
                 "link partitioned until the post-broadcast heal.")
   :scope broadcast-scope
   :input {:message 42 :partition-links [["n2" "n3"]]}})

(def ^:private echo-input
  "The exact nested Unicode payload the trusted Echo preset owns."
  {"greeting" "héllo, 世界 🌍"
   "lang" "日本語"
   "nested" {"a" "ελληνικά"
              "b" [" 한글 " "português" 42 nil]}
   "emoji" "🚀"})

(def ^:private outbox-cancel-regime
  {:id :jolt.sim.regime/outbox-cancel-before-ack-canonical
   :label "Canonical cancellation path"
   :summary (str "Run the trusted cancel-before-ack example with its fixed "
                 "server-owned coordinates.")
   :scope [:jolt.example.outbox/cancellation]
   :input {:payload [0 127 128 255]
           :stream-capacity 8
           :pipe-capacity 1
           :poll-eintr-ordinal nil}})

(def ^:private echo-regime
  {:id :jolt.sim.regime/maelstrom-echo-canonical
   :label "Canonical Echo round trip"
   :summary (str "Run the trusted Maelstrom Echo example with its fixed "
                 "server-owned Unicode payload.")
   :scope [:jolt.maelstrom.echo/roundtrip]
   :input echo-input})

(defn- outbox-lab-regime [{:keys [id label summary scope]}]
  {:id id
   :label label
   :summary summary
   :scope scope
   :input (outbox-regimes/scenario-input id)})

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
              (slurp "examples/outbox-cancel-before-ack-plan.edn"))
        echo-plan (viewer-experiment/read-edn
                   (slurp "examples/maelstrom-echo-plan.edn"))
        outbox-regime-lab-plan
        (viewer-experiment/read-edn
         (slurp "examples/outbox-regime-lab-plan.edn"))
        broadcast-plan
        (viewer-experiment/read-edn
         (slurp "examples/maelstrom-broadcast-plan.edn"))]
    {:port port
     :capability-token capability-token
     :max-document-bytes (* 1024 1024)
     :allowed-scenarios #{scenario echo-scenario outbox-regime-lab-scenario
                          broadcast-scenario}
     ;; Each preset uses the same v2 shape. The first two expose one canonical
     ;; regime; the lab exposes the application's complete finite catalog; the
     ;; Broadcast preset exposes its scenario's exact healthy and
     ;; partition/heal inputs. The stubbed run-case service below never
     ;; launches a worker for any preset.
     :run-presets
     [{:id :jolt.sim.preset/outbox-cancel-before-ack-v1
       :label "Outbox: cancel before acknowledgment"
       :scenario scenario
       :profile-id :hermetic
       :schedule nil
       :plan-document plan
       :regimes [outbox-cancel-regime]}
      {:id :jolt.sim.preset/maelstrom-echo-roundtrip-v1
       :label "Maelstrom Echo: init and echo round trip"
       :scenario echo-scenario
       :profile-id :hermetic
       :schedule nil
       :plan-document echo-plan
       :regimes [echo-regime]}
      {:id :jolt.sim.preset/outbox-first-poll-regime-lab-v1
       :label "Outbox: poll admission and EINTR regime lab"
       :scenario outbox-regime-lab-scenario
       :profile-id :hermetic
       :schedule nil
       :plan-document outbox-regime-lab-plan
       :regimes (mapv outbox-lab-regime outbox-regimes/regimes)}
      {:id :jolt.sim.preset/maelstrom-broadcast-partition-heal-v1
       :label "Maelstrom Broadcast: healthy line and partition/heal"
       :scenario broadcast-scenario
       :profile-id :hermetic
       :schedule nil
       :plan-document broadcast-plan
       :regimes [broadcast-healthy-regime broadcast-partition-regime]}]
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
