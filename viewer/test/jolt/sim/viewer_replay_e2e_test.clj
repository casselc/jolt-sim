(ns jolt.sim.viewer-replay-e2e-test
  "One real browser-facing replay of the checked-in canonical outbox case.

  The parent is the actual loopback viewer server. POST /api/replay delegates
  through jolt.sim.repl to the existing process explorer, which launches one
  fresh sim-enabled Jolt worker running the unchanged HTTP/SQLite/TCP/bencode
  application. The worker directory is retained even on completion and CI
  uploads the entire configured parent plus this best-effort append-only phase
  log. The log is diagnostic evidence, not the later durable journal."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.repl :as sim-repl]
            [jolt.sim.report :as report]
            [jolt.sim.trace :as trace]
            [jolt.sim.viewer :as viewer]
            [jolt.sim.viewer-test :as viewer-test]))

(def ^:private capability-token
  "viewer-e2e-capability-0123456789abcdef")

(def ^:private replay-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset)

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when (or (nil? value) (string/blank? value))
      (throw (ex-info "viewer replay E2E requires an environment value"
                      {:type ::missing-environment :name name})))
    value))

(defn- append-phase! [journal event]
  ;; One open/write/close per record intentionally favors crash forensics over
  ;; throughput. Earlier complete EDN lines normally survive an abrupt exit;
  ;; the final line can be absent or partial, so this makes no fsync, checksum,
  ;; framing, or machine-crash durability claim.
  (spit journal (str (trace/canonical-edn event) "\n") :append true))

(defn- append-phase-best-effort! [journal event]
  (try
    (append-phase! journal event)
    (catch :default _ nil)))

(defn- bounded-error-phase [error]
  (let [message (try (ex-message error) (catch :default _ "unavailable"))]
    (try
      (let [data (pr-str (ex-data error))]
        {:phase :error
         :message message
         :data (subs data 0 (min 4096 (count data)))})
      (catch :default _
        {:phase :error
         :message message
         :data "unprintable exception data"}))))

(defn- response-body [raw]
  (let [boundary (string/index-of raw "\r\n\r\n")]
    (when (nil? boundary)
      (throw (ex-info "viewer replay response has no HTTP header boundary"
                      {:type ::malformed-response
                       :prefix (subs raw 0 (min 256 (count raw)))})))
    (subs raw (+ boundary 4))))

(deftest retained-outbox-case-replays-through-the-live-viewer
  (let [artifact-root (required-environment
                       "JOLT_SIM_VIEWER_ARTIFACT_DIR")
        journal (str artifact-root "/viewer-replay-progress.edn")
        server* (atom nil)
        primary* (atom nil)]
    (append-phase! journal {:phase :started})
    (try
      (let [bin (required-environment "JOLT_SIM_BIN")
            project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
            document-path (required-environment
                           "JOLT_SIM_VIEWER_DOCUMENT")
            document (case-outcome/read-edn (slurp document-path))
            _ (append-phase! journal {:phase :document-validated
                                      :scenario replay-scenario
                                      :document-path document-path})
            server
            (viewer/start!
             {:port 0
              :capability-token capability-token
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{replay-scenario}
              :runtime-config
              {:worker-command [bin "-M:outbox-delivery-explore-worker"]
               :dir project-dir
               :timeout-ms 60000
               :startup-timeout-ms 30000
               :kill-grace-ms 500
               :temp-dir artifact-root
               :retain-completed-artifacts? true}}
             {:render-document report/case-outcome->html
              :replay-document
              (fn [document runtime]
                (append-phase! journal {:phase :replay-delegated})
                (let [outcome (sim-repl/replay-document! document runtime)]
                  (append-phase! journal
                                 {:phase :replay-service-returned
                                  :status (:status outcome)
                                  :exit (:exit outcome)
                                  :artifact-dir (:artifact-dir outcome)})
                  outcome))})
            _ (reset! server* server)
            port (:port server)
            _ (append-phase! journal {:phase :viewer-started :port port})
            raw
            (viewer-test/request-over-loopback!
             port "POST" "/api/replay"
             {"Content-Type" "application/edn"
              "X-Jolt-Sim-Capability" capability-token}
             (case-outcome/canonical-edn document)
             120000)
            body (response-body raw)
            outcome (edn/read-string body)]
        (append-phase!
         journal
         {:phase :replay-returned
          :status (:status outcome)
          :exit (:exit outcome)
          :artifact-dir (:artifact-dir outcome)})
        (is (string/starts-with? raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (and (string? (:artifact-dir outcome))
                 (not (string/blank? (:artifact-dir outcome)))))
        (is (= true (get-in outcome
                            [:result :application :marking :changed?])))
        (is (= 27 (get-in outcome [:result :sqlite :plan-index])))
        (is (= {:memory true :sqlite true :posix true}
               (get-in outcome [:result :clean?]))))
      (catch :default error
        (reset! primary* error)
        ;; Error data can carry host objects outside the canonical trace
        ;; domain. Keep the exact exception primary and bound this lossy
        ;; side-channel rather than risking a second serialization failure.
        (append-phase-best-effort! journal (bounded-error-phase error))
        (throw error))
      (finally
        (let [cleanup-error
              (try
                (when-let [server @server*]
                  (viewer/stop! server))
                nil
                (catch :default error error))]
          (append-phase-best-effort!
           journal
           (cond-> {:phase :viewer-stopped}
             cleanup-error
             (assoc :cleanup-error (ex-message cleanup-error))))
          ;; Cleanup failure is primary only when no earlier application,
          ;; protocol, or replay failure is already propagating.
          (when (and cleanup-error (nil? @primary*))
            (throw cleanup-error)))))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-replay-e2e-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    ;; jolt-http loads core.async, whose non-daemon threads intentionally keep
    ;; a normal process alive. The E2E main owns this isolated process.
    (System/exit (if (zero? failures) 0 1))))
