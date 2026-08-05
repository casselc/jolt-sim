(ns jolt.sim.viewer-replay-e2e-test
  "One real browser-facing replay of the checked-in canonical outbox case.

  The parent is the actual loopback viewer server. POST /api/replay delegates
  through jolt.sim.repl to the existing process explorer, which launches one
  fresh sim-enabled Jolt worker running the unchanged HTTP/SQLite/TCP/bencode
  application. The worker directory is retained even on completion and CI
  uploads the entire configured parent plus this best-effort append-only phase
  log. The log is diagnostic evidence, not the later durable journal."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.test :as test :refer [deftest is]]
            [jolt.fs :as fs]
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

(defn- wait-for-checkpoint!
  "Waits for one complete canonical EDN checkpoint line. A partially written
   final line is retained but never accepted as evidence."
  [path timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [text (try
                   (when (fs/exists? path) (slurp path))
                   (catch :default _ nil))
            parsed
            (when (and (string? text) (string/ends-with? text "\n"))
              (try
                {:value (edn/read-string (first (string/split-lines text)))}
                (catch :default _ nil)))]
        (cond
          parsed (:value parsed)

          (< (System/nanoTime) deadline)
          (do (Thread/sleep 10) (recur))

          :else :timeout)))))

(defn- release-worker-best-effort! [path]
  (try
    (spit path (str (trace/canonical-edn {:release true}) "\n"))
    (catch :default _ nil)))

(deftest retained-outbox-case-replays-through-the-live-viewer
  (let [artifact-root (required-environment
                       "JOLT_SIM_VIEWER_ARTIFACT_DIR")
        journal (str artifact-root "/viewer-replay-progress.edn")
        server* (atom nil)
        primary* (atom nil)
        replay-outcome* (atom nil)
        replay-service-finished (promise)
        gate-id (str (System/nanoTime))
        activity-checkpoint
        (str artifact-root "/outbox-activity-" gate-id ".edn")
        activity-release
        (str artifact-root "/outbox-activity-release-" gate-id ".edn")]
    (append-phase-best-effort! journal {:phase :started})
    (try
      (let [bin (required-environment "JOLT_SIM_BIN")
            project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
            document-path (required-environment
                           "JOLT_SIM_VIEWER_DOCUMENT")
            document (case-outcome/read-edn (slurp document-path))
            _ (append-phase-best-effort!
               journal
               {:phase :document-validated
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
               :extra-env
               {"JOLT_SIM_OUTBOX_ACTIVITY_CHECKPOINT" activity-checkpoint
                "JOLT_SIM_OUTBOX_ACTIVITY_RELEASE" activity-release}
               :retain-completed-artifacts? true}}
             {:render-document report/case-outcome->html
              :replay-document
              (fn [document runtime]
                (try
                  (append-phase-best-effort! journal
                                             {:phase :replay-delegated})
                  (let [outcome (sim-repl/replay-document! document runtime)]
                    (append-phase-best-effort!
                     journal
                     {:phase :replay-service-returned
                      :status (:status outcome)
                      :exit (:exit outcome)
                      :artifact-dir (:artifact-dir outcome)})
                    outcome)
                  (finally
                    ;; The client can fail independently of the server-side
                    ;; handler. This promise denotes the lifecycle that owns
                    ;; and reaps the process-explorer child.
                    (deliver replay-service-finished true))))})
            _ (reset! server* server)
            port (:port server)
            _ (append-phase-best-effort! journal
                                         {:phase :viewer-started :port port})
            replay-thread-outcome (promise)
            replay-thread
            (Thread.
             (fn []
               (deliver
                replay-thread-outcome
                (try
                  {:raw
                   (viewer-test/request-over-loopback!
                    port "POST" "/api/replay"
                    {"Content-Type" "application/edn"
                     "X-Jolt-Sim-Capability" capability-token}
                    (case-outcome/canonical-edn document)
                    120000)}
                  (catch :default error {:error error})))))
            _ (.setDaemon replay-thread true)
            _ (.start replay-thread)
            _ (reset! replay-outcome* replay-thread-outcome)
            activity (wait-for-checkpoint! activity-checkpoint 60000)
            _ (when (= :timeout activity)
                (throw
                 (ex-info "ordinary outbox application checkpoint timed out"
                          {:type ::activity-checkpoint-timeout
                           :checkpoint activity-checkpoint})))
            _ (is (= {:phase :pending-delivery
                      :request-id "req-1"
                      :outbox-id 1
                      :status :pending
                      :attempt 1}
                     activity)
                  "the ordinary app must pause after durable reload and before delivery")
            _ (append-phase-best-effort!
               journal {:phase :application-checkpoint-observed
                        :activity activity})
            progress-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/replay-progress"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            progress-body (response-body progress-raw)
            progress (json/read-str progress-body)
            _ (append-phase-best-effort!
               journal {:phase :progress-observed :body progress-body})
            _ (is (string/starts-with? progress-raw "HTTP/1.1 200"))
            _ (is (contains? #{"worker-ready" "running"}
                             (get progress "status"))
                  "live progress must observe the active ordinary application")
            _ (is (false? (get progress "result-observed?"))
                  "the canonical result artifact must not exist at the in-flight checkpoint")
            _ (spit activity-release
                    (str (trace/canonical-edn {:release true}) "\n"))
            _ (append-phase-best-effort! journal
                                         {:phase :application-released})
            replay-result (deref replay-thread-outcome 120000 :timeout)
            _ (when (= :timeout replay-result)
                (throw (ex-info "viewer replay POST did not return in time"
                                {:type ::replay-thread-timeout})))
            _ (when-let [error (:error replay-result)]
                (throw error))
            raw (:raw replay-result)
            body (response-body raw)
            outcome (edn/read-string body)
            terminal-progress-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/replay-progress"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            terminal-progress-body (response-body terminal-progress-raw)]
        (append-phase-best-effort!
         journal
         {:phase :replay-returned
          :status (:status outcome)
          :exit (:exit outcome)
          :artifact-dir (:artifact-dir outcome)})
        (append-phase-best-effort!
         journal
         {:phase :terminal-progress-observed :body terminal-progress-body})
        (is (string/starts-with? raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (and (string? (:artifact-dir outcome))
                 (not (string/blank? (:artifact-dir outcome)))))
        (is (= true (get-in outcome
                            [:result :application :marking :changed?])))
        (is (= 27 (get-in outcome [:result :sqlite :plan-index])))
        (is (= {:memory true :sqlite true :posix true}
               (get-in outcome [:result :clean?])))
        (is (string/starts-with? terminal-progress-raw "HTTP/1.1 200"))
        (is (string/includes? terminal-progress-body "\"status\":\"completed\""))
        (is (fs/exists? (:artifact-dir outcome))))
      (catch :default error
        (reset! primary* error)
        ;; Error data can carry host objects outside the canonical trace
        ;; domain. Keep the exact exception primary and bound this lossy
        ;; side-channel rather than risking a second serialization failure.
        (append-phase-best-effort! journal (bounded-error-phase error))
        (throw error))
      (finally
        ;; Never strand the ordinary application at its post-COMMIT,
        ;; pre-delivery checkpoint when an assertion, progress request, or
        ;; response decode fails. The release artifact is retained too.
        (release-worker-best-effort! activity-release)
        (let [service-cleanup
              (when @replay-outcome*
                (deref replay-service-finished 120000 :timeout))
              service-cleanup-error
              (when (= :timeout service-cleanup)
                (ex-info "server-side replay lifecycle did not finish during cleanup"
                         {:type ::replay-service-cleanup-timeout
                          :timeout-ms 120000}))
              replay-cleanup
              (when-let [pending @replay-outcome*]
                (deref pending 5000 :timeout))
              replay-cleanup-error
              (cond
                (= :timeout replay-cleanup)
                (ex-info "loopback replay client did not finish during cleanup"
                         {:type ::replay-client-cleanup-timeout
                          :timeout-ms 5000})

                (:error replay-cleanup) (:error replay-cleanup)
                :else nil)
              cleanup-error
              (try
                (when-let [server @server*]
                  (viewer/stop! server))
                nil
                (catch :default error error))]
          (append-phase-best-effort!
           journal
           (cond-> {:phase :viewer-stopped}
             service-cleanup-error
             (assoc :replay-service-cleanup-error
                    (ex-message service-cleanup-error))
             replay-cleanup-error
             (assoc :replay-client-cleanup-error
                    (ex-message replay-cleanup-error))
             cleanup-error
             (assoc :cleanup-error (ex-message cleanup-error))))
          ;; Cleanup failure is primary only when no earlier application,
          ;; protocol, or replay failure is already propagating.
          (when (nil? @primary*)
            (when-let [secondary (or service-cleanup-error
                                     replay-cleanup-error
                                     cleanup-error)]
              (throw secondary))))))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.viewer-replay-e2e-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    ;; jolt-http loads core.async, whose non-daemon threads intentionally keep
    ;; a normal process alive. The E2E main owns this isolated process.
    (System/exit (if (zero? failures) 0 1))))
