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
            [jolt.sim.viewer.experiment :as viewer-experiment]
            [jolt.sim.viewer-test :as viewer-test]))

(def ^:private capability-token
  "viewer-e2e-capability-0123456789abcdef")

(def ^:private replay-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset)

(def ^:private webhook-replay-scenario
  'jolt.sim.fixtures.outbox-http-webhook-scenarios/exercise)

(def ^:private run-new-scenario
  'jolt.sim.fixtures.outbox-experiment-scenarios/exercise-cancel-before-ack-compiled)

(def ^:private run-new-preset-id
  :jolt.sim.preset/outbox-cancel-before-ack-v1)

(def ^:private run-new-input
  {:payload [0 127 128 255]
   :stream-capacity 8
   :pipe-capacity 1
   :poll-eintr-ordinal nil})

(def ^:private echo-run-scenario
  'jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip)

(def ^:private echo-run-preset-id
  :jolt.sim.preset/maelstrom-echo-roundtrip-v1)

(def ^:private echo-run-label
  "Maelstrom Echo: init and echo round trip")

(def ^:private echo-run-input
  "The exact nested Unicode echo payload the trusted preset owns. The
   unchanged jolt.maelstrom.echo handler must round-trip every codepoint
   through the in-memory transport, the fresh worker, and the canonical
   result document."
  {"greeting" "héllo, 世界 🌍"
   "lang" "日本語"
   "nested" {"a" "ελληνικά"
              "b" [" 한글 " "português" 42 nil]}
   "emoji" "🚀"})

(def ^:private echo-expected-replies
  "The exact envelopes the unchanged jolt.maelstrom.node boundary builds for
   the official init and echo requests: node-local msg_ids 1 and 2, replied
   in order to c1 from n1."
  [{:src "n1" :dest "c1"
    :body {:type "init_ok" :msg_id 1 :in_reply_to 1}}
   {:src "n1" :dest "c1"
    :body {:type "echo_ok" :msg_id 2 :in_reply_to 2
            :echo echo-run-input}}])

(def ^:private webhook-semantic-identities
  "The exact five semantic identities the unchanged JSON HTTP webhook
   application records in its command evidence for the accepted empty-payload
   case. Mirrors the exported PR61 evidence asserted by the Hegel gate."
  {:request-id "req-1"
   :transaction-id [:outbox/command "req-1"]
   :outbox-id 1
   :delivery-id [:outbox/delivery 1]
   :attempt-id [:outbox/delivery-attempt 1 1]})

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
        trusted-outcome* (atom nil)
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
             {:render-trace report/trace->html
              :render-case-outcome report/case-outcome->html
              :replay-document
              (fn [document runtime]
                (try
                  (append-phase-best-effort! journal
                                             {:phase :replay-delegated})
                  (let [outcome (sim-repl/replay-document! document runtime)]
                    (reset! trusted-outcome* outcome)
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
                     "X-Jolt-Sim-Capability" capability-token
                     "X-Jolt-Sim-Document-Kind" "case-outcome"}
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
            trusted-outcome @trusted-outcome*
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
          :artifact-dir (:artifact-dir trusted-outcome)})
        (append-phase-best-effort!
         journal
         {:phase :terminal-progress-observed :body terminal-progress-body})
        (is (string/starts-with? raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (not (contains? outcome :artifact-dir))
            "the browser response must not disclose its host artifact path")
        (is (and (string? (:artifact-dir trusted-outcome))
                 (not (string/blank? (:artifact-dir trusted-outcome))))
            "the trusted replay service retains the forensic coordinate")
        (is (= true (get-in outcome
                            [:result :application :marking :changed?])))
        (is (= 27 (get-in outcome [:result :sqlite :plan-index])))
        (is (= {:memory true :sqlite true :posix true}
               (get-in outcome [:result :clean?])))
        (is (string/starts-with? terminal-progress-raw "HTTP/1.1 200"))
        (is (string/includes? terminal-progress-body "\"status\":\"completed\""))
        (is (fs/exists? (:artifact-dir trusted-outcome))))
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

(defn- webhook-document
  "Builds the retained Case/Outcome document for the accepted empty-payload
   JSON HTTP webhook case. The completed outcome is placeholder provenance
   only: the live replay replaces it with the fresh worker's evidence. The
   passing monitor decision records the exported webhook invariant id."
  []
  (case-outcome/document
   {:scenario webhook-replay-scenario
    :mode :hermetic
    :input {:payload [] :response-mode :accepted}
    :schedule nil}
   {:status :completed
    :result {:application {:status :ok}}
    :exit 0}
   [{:id :outbox/http-webhook-invariants
     :status :pass
     :detail nil
     :index nil}]))

(deftest webhook-case-replays-through-the-live-viewer
  (let [artifact-root (required-environment
                       "JOLT_SIM_VIEWER_ARTIFACT_DIR")
        journal (str artifact-root "/viewer-replay-progress.edn")
        server* (atom nil)
        primary* (atom nil)
        trusted-outcome* (atom nil)]
    (append-phase-best-effort! journal {:phase :webhook-started})
    (try
      (let [bin (required-environment "JOLT_SIM_BIN")
            project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
            document (webhook-document)
            encoded (case-outcome/canonical-edn document)
            server
            (viewer/start!
             {:port 0
              :capability-token capability-token
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{webhook-replay-scenario}
              :runtime-config
              {:worker-command [bin "-M:outbox-http-webhook-explore-worker"]
               :dir project-dir
               :timeout-ms 60000
               :startup-timeout-ms 30000
               :kill-grace-ms 500
               :temp-dir artifact-root
               :retain-completed-artifacts? true}}
             {:render-trace report/trace->html
              :render-case-outcome report/case-outcome->html
              :replay-document
              (fn [document runtime]
                (let [outcome (sim-repl/replay-document! document runtime)]
                  (reset! trusted-outcome* outcome)
                  outcome))})
            _ (reset! server* server)
            port (:port server)
            _ (append-phase-best-effort! journal
                                         {:phase :webhook-viewer-started
                                          :port port})
            render-raw
            (viewer-test/request-over-loopback!
             port "POST" "/api/render"
             {"Content-Type" "application/edn"
              "X-Jolt-Sim-Capability" capability-token
              "X-Jolt-Sim-Document-Kind" "case-outcome"}
             encoded
             5000)
            render-body (response-body render-raw)
            replay-raw
            (viewer-test/request-over-loopback!
             port "POST" "/api/replay"
             {"Content-Type" "application/edn"
              "X-Jolt-Sim-Capability" capability-token
              "X-Jolt-Sim-Document-Kind" "case-outcome"}
             encoded
             120000)
            replay-body (response-body replay-raw)
            outcome (edn/read-string replay-body)
            trusted-outcome @trusted-outcome*]
        (append-phase-best-effort!
         journal
         {:phase :webhook-replay-returned
          :status (:status outcome)
          :exit (:exit outcome)
          :artifact-dir (:artifact-dir trusted-outcome)})
        (is (string/starts-with? render-raw "HTTP/1.1 200"))
        (is (string/includes? render-body
                              "jolt.sim.fixtures.outbox-http-webhook-scenarios/exercise")
            "the rendered report must name the allowlisted webhook scenario")
        (is (string/includes? render-body ":outbox/http-webhook-invariants")
            "the rendered report must show the passing webhook monitor decision")
        (is (string/starts-with? replay-raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (= webhook-semantic-identities
               (get-in outcome
                       [:result :application :command :evidence :identities]))
            "the fresh worker must record the exact five webhook semantic identities")
        (is (= true (get-in outcome
                            [:result :application :delivery :marking :changed?]))
            "the accepted webhook must mark the delivered outbox row changed")
        (is (= {:memory true :sqlite true :posix true}
               (get-in outcome [:result :clean?]))
            "the webhook world must release memory, sqlite, and posix cleanly")
        (is (not (contains? outcome :artifact-dir))
            "the browser response must not disclose its host artifact path")
        (is (and (string? (:artifact-dir trusted-outcome))
                 (not (string/blank? (:artifact-dir trusted-outcome))))
            "the trusted replay service retains the forensic coordinate")
        (is (fs/exists? (:artifact-dir trusted-outcome))
            "completed webhook artifacts must be retained under the artifact root"))
      (catch :default error
        (reset! primary* error)
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
           (cond-> {:phase :webhook-viewer-stopped}
             cleanup-error
             (assoc :cleanup-error (ex-message cleanup-error))))
          ;; Cleanup failure is primary only when no earlier render, replay,
          ;; or protocol failure is already propagating.
          (when (nil? @primary*)
            (when-let [secondary cleanup-error]
              (throw secondary))))))))

(deftest trusted-run-preset-launches-the-ordinary-compiled-outbox-app
  (let [artifact-root (required-environment
                       "JOLT_SIM_VIEWER_ARTIFACT_DIR")
        journal (str artifact-root "/viewer-run-new-progress.edn")
        server* (atom nil)
        primary* (atom nil)]
    (append-phase-best-effort! journal {:phase :run-new-started})
    (sim-repl/clear!)
    (try
      (let [bin (required-environment "JOLT_SIM_BIN")
            project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
            plan (viewer-experiment/read-edn
                  (slurp "examples/outbox-cancel-before-ack-plan.edn"))
            server
            (viewer/start!
             {:port 0
              :capability-token capability-token
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{run-new-scenario}
              :run-presets
              [{:id run-new-preset-id
                :label "Outbox: cancel before acknowledgment"
                :scenario run-new-scenario
                :profile-id :hermetic
                :input run-new-input
                :schedule nil
                :plan-document plan}]
              :runtime-config
              {:worker-command [bin "-M:outbox-delivery-explore-worker"]
               :dir project-dir
               :timeout-ms 60000
               :startup-timeout-ms 120000
               :kill-grace-ms 500
               :temp-dir artifact-root
               :retain-completed-artifacts? true
               :activity-journal? true}})
            _ (reset! server* server)
            port (:port server)
            _ (append-phase-best-effort!
               journal {:phase :run-new-viewer-started :port port})
            catalog-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/run-presets"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            catalog-body (response-body catalog-raw)
            catalog (json/read-str catalog-body)
            run-command
            (json/write-str
             {"version" 1
              "presetId" "jolt.sim.preset/outbox-cancel-before-ack-v1"})
            run-raw
            (viewer-test/request-over-loopback!
             port "POST" "/api/run"
             {"Content-Type" "application/json"
              "X-Jolt-Sim-Capability" capability-token}
             run-command
             180000)
            run-body (response-body run-raw)
            outcome (edn/read-string run-body)
            recorded (sim-repl/last-run)
            trusted-outcome (:outcome recorded)
            executed-plan (get-in trusted-outcome [:result :plan])
            displayed-connections
            (into {}
                  (map (fn [connection]
                         [(:id connection)
                          (update
                           (select-keys connection
                                        [:from :to :pack-id :capabilities
                                         :mode])
                           :capabilities
                           (fn [capabilities]
                             (into {}
                                   (map (fn [[direction values]]
                                          [direction (set values)]))
                                   capabilities)))]))
                  (:connections plan))
            progress-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/replay-progress"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            progress-body (response-body progress-raw)
            progress (json/read-str progress-body)
            activity-page (get progress "activity")]
        (append-phase-best-effort!
         journal
         {:phase :run-new-returned
          :status (:status outcome)
          :artifact-dir (:artifact-dir trusted-outcome)
          :progress progress})
        (is (string/starts-with? catalog-raw "HTTP/1.1 200"))
        (is (= #{{"id" "jolt.sim.preset/outbox-cancel-before-ack-v1"
                  "label" "Outbox: cancel before acknowledgment"
                  "profileId" "hermetic"
                  "planEdn" (viewer-experiment/canonical-edn plan)}}
               (set (get catalog "presets"))))
        (is (not (string/includes? catalog-body (str run-new-scenario))))
        (is (not (string/includes? catalog-body "stream-capacity")))
        (is (string/starts-with? run-raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (not (contains? outcome :artifact-dir)))
        (is (= :jolt.sim.fixtures.outbox-delivery/cancel-before-ack-v1
               (get-in outcome [:result :application-body-id])))
        (is (= 200 (get-in outcome [:result :http :status])))
        (is (= {:plan-index 18 :plan-count 18
                :open-dbs 0 :active-stmts 0}
               (get-in outcome [:result :sqlite])))
        (is (= {:memory true :sqlite true :posix true}
               (get-in outcome [:result :clean?])))
        (is (= {:command 1 :delivery 1 :store 1}
               (get-in outcome [:result :callbacks :compile])))
        (is (= {:experiment-id (:experiment-id plan)
                :profile-id (:profile-id plan)
                :plan-version (:jolt.sim.viewer.experiment/version plan)
                :connections displayed-connections
                :checks-count (count (:checks plan))
                :ffi-mode (get-in plan [:runtime :ffi-mode])
                :ffi-handler-count (get-in plan [:runtime :handler-count])}
               {:experiment-id (:experiment-id executed-plan)
                :profile-id (:profile-id executed-plan)
                :plan-version (:plan-version executed-plan)
                :connections (:connections executed-plan)
                :checks-count (:checks-count executed-plan)
                :ffi-mode (get-in executed-plan [:runtime-config :ffi-mode])
                :ffi-handler-count
                (get-in executed-plan [:runtime-config :ffi-handler-count])})
            "the inspected plan is bound to the compiled plan actually run")
        (is (not (contains? (get-in outcome [:result :application])
                            :marking)))
        (is (not (contains? (get-in outcome [:result :application])
                            :delivery)))
        (is (= {:scenario run-new-scenario
                :input run-new-input
                :schedule nil}
               (select-keys (:config recorded)
                            [:scenario :input :schedule])))
        (is (and (string? (:artifact-dir trusted-outcome))
                 (fs/exists? (:artifact-dir trusted-outcome))))
        (is (string/starts-with? progress-raw "HTTP/1.1 200"))
        (is (= "completed" (get progress "status")))
        (is (= ["jolt.sim.explore/scenario-started"
                "jolt.example.outbox/experiment-compiled"
                "jolt.example.outbox/application-started"
                "jolt.example.outbox/cancel-before-ack-observed"
                "jolt.example.outbox/application-completed"
                "jolt.sim.explore/scenario-completed"]
               (mapv #(get % "tag") (get activity-page "events"))))
        (is (= "complete"
               (get-in activity-page ["recovery" "status"]))))
      (catch :default error
        (reset! primary* error)
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
           (cond-> {:phase :run-new-viewer-stopped}
             cleanup-error
             (assoc :cleanup-error (ex-message cleanup-error))))
          (sim-repl/clear!)
          (when (nil? @primary*)
            (when-let [secondary cleanup-error]
              (throw secondary))))))))

(deftest trusted-run-preset-runs-the-unchanged-maelstrom-echo-scenario
  (let [artifact-root (required-environment
                       "JOLT_SIM_VIEWER_ARTIFACT_DIR")
        journal (str artifact-root "/viewer-run-new-echo-progress.edn")
        server* (atom nil)
        primary* (atom nil)]
    (append-phase-best-effort! journal {:phase :echo-run-new-started})
    (sim-repl/clear!)
    (try
      (let [bin (required-environment "JOLT_SIM_BIN")
            project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
            outbox-plan (viewer-experiment/read-edn
                         (slurp "examples/outbox-cancel-before-ack-plan.edn"))
            echo-plan (viewer-experiment/read-edn
                       (slurp "examples/maelstrom-echo-plan.edn"))
            server
            (viewer/start!
             {:port 0
              :capability-token capability-token
              :max-document-bytes (* 1024 1024)
              :allowed-scenarios #{run-new-scenario echo-run-scenario}
              ;; The existing outbox preset stays first and byte-identical;
              ;; the Maelstrom Echo preset is the second trusted example and
              ;; reuses the same existing fresh worker command unchanged --
              ;; that worker alias already resolves the defsim fixture from
              ;; this repository's own src/test roots.
              :run-presets
              [{:id run-new-preset-id
                :label "Outbox: cancel before acknowledgment"
                :scenario run-new-scenario
                :profile-id :hermetic
                :input run-new-input
                :schedule nil
                :plan-document outbox-plan}
               {:id echo-run-preset-id
                :label echo-run-label
                :scenario echo-run-scenario
                :profile-id :hermetic
                :input echo-run-input
                :schedule nil
                :plan-document echo-plan}]
              :runtime-config
              {:worker-command [bin "-M:outbox-delivery-explore-worker"]
               :dir project-dir
               :timeout-ms 60000
               :startup-timeout-ms 120000
               :kill-grace-ms 500
               :temp-dir artifact-root
               :retain-completed-artifacts? true
               :activity-journal? true}})
            _ (reset! server* server)
            port (:port server)
            _ (append-phase-best-effort!
               journal {:phase :echo-run-new-viewer-started :port port})
            catalog-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/run-presets"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            catalog-body (response-body catalog-raw)
            catalog (json/read-str catalog-body)
            run-command
            (json/write-str
             {"version" 1
              "presetId" "jolt.sim.preset/maelstrom-echo-roundtrip-v1"})
            run-raw
            (viewer-test/request-over-loopback!
             port "POST" "/api/run"
             {"Content-Type" "application/json"
              "X-Jolt-Sim-Capability" capability-token}
             run-command
             180000)
            run-body (response-body run-raw)
            outcome (edn/read-string run-body)
            recorded (sim-repl/last-run)
            trusted-outcome (:outcome recorded)
            ;; The worker outcome retains the simulator envelope; the
            ;; unchanged Maelstrom application's value is its nested :result.
            result (get-in outcome [:result :result])
            history (get-in result [:transport :history])
            progress-raw
            (viewer-test/request-over-loopback!
             port "GET" "/api/replay-progress"
             {"X-Jolt-Sim-Capability" capability-token}
             ""
             5000)
            progress-body (response-body progress-raw)
            progress (json/read-str progress-body)
            activity-page (get progress "activity")]
        (append-phase-best-effort!
         journal
         {:phase :echo-run-new-returned
          :status (:status outcome)
          :artifact-dir (:artifact-dir trusted-outcome)
          :progress progress})
        (is (string/starts-with? catalog-raw "HTTP/1.1 200"))
        ;; The closed catalog distinguishes exactly the two trusted presets;
        ;; the existing outbox entry is unchanged, and the catalog never
        ;; discloses either scenario, either input, or any host coordinate.
        (is (= [{"id" "jolt.sim.preset/outbox-cancel-before-ack-v1"
                 "label" "Outbox: cancel before acknowledgment"
                 "profileId" "hermetic"
                 "planEdn" (viewer-experiment/canonical-edn outbox-plan)}
                {"id" "jolt.sim.preset/maelstrom-echo-roundtrip-v1"
                 "label" echo-run-label
                 "profileId" "hermetic"
                 "planEdn" (viewer-experiment/canonical-edn echo-plan)}]
               (get catalog "presets")))
        (is (not (string/includes? catalog-body (str echo-run-scenario))))
        (is (not (string/includes? catalog-body (str run-new-scenario))))
        (is (not (string/includes? catalog-body "greeting")))
        (is (not (string/includes? catalog-body "日本語")))
        (is (not (string/includes? catalog-body "stream-capacity")))
        (is (not (string/includes? catalog-body bin)))
        (is (not (string/includes? catalog-body artifact-root)))
        (is (not (string/includes? catalog-body
                                  "-M:outbox-delivery-explore-worker")))
        (is (string/starts-with? run-raw "HTTP/1.1 200"))
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (not (contains? outcome :artifact-dir)))
        ;; The fresh worker ran the unchanged defsim scenario: the exact node
        ;; identity, the exact init_ok/echo_ok reply envelopes, and the exact
        ;; nested Unicode payload all crossed the process boundary unchanged.
        (is (= "n1" (:node-id result)))
        (is (= echo-expected-replies (:replies result)))
        (is (= echo-run-input
               (get-in (second (:replies result)) [:body :echo])))
        ;; The in-memory transport's own local history is retained in the
        ;; canonical result: the full enqueue/deliver sequence, with the
        ;; Unicode payload visible in the echo request and reply events.
        (is (= {} (get-in result [:transport :queues])))
        (is (= (vec (range 1 9)) (mapv :ordinal history)))
        (is (= [:enqueue :enqueue :deliver :enqueue
                :deliver :enqueue :deliver :deliver]
               (mapv :op history)))
        (is (= ["n1" "n1" "n1" "c1" "n1" "c1" "c1" "c1"]
               (mapv :endpoint history)))
        (is (= echo-run-input
               (get-in (nth history 1) [:envelope :body :echo])))
        (is (= echo-run-input
               (get-in (nth history 5) [:envelope :body :echo])))
        (is (= echo-run-input
               (get-in (nth history 7) [:envelope :body :echo])))
        (is (= {:scenario echo-run-scenario
                :input echo-run-input
                :schedule nil}
               (select-keys (:config recorded)
                            [:scenario :input :schedule])))
        (is (and (string? (:artifact-dir trusted-outcome))
                 (fs/exists? (:artifact-dir trusted-outcome))))
        (is (string/starts-with? progress-raw "HTTP/1.1 200"))
        (is (= "completed" (get progress "status")))
        (is (= ["jolt.sim.explore/scenario-started"
                "jolt.sim.explore/scenario-completed"]
               (mapv #(get % "tag") (get activity-page "events"))))
        (is (every? #(string/includes?
                      (get % "edn")
                      "jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip")
                    (get activity-page "events")))
        (is (= "complete"
               (get-in activity-page ["recovery" "status"]))))
      (catch :default error
        (reset! primary* error)
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
           (cond-> {:phase :echo-run-new-viewer-stopped}
             cleanup-error
             (assoc :cleanup-error (ex-message cleanup-error))))
          (sim-repl/clear!)
          (when (nil? @primary*)
            (when-let [secondary cleanup-error]
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
