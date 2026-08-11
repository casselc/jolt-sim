(ns outbox-workbench.retained-workbench-test-main
  "Focused fresh-process gate for the combined EvalSession + retained outbox
  Ripple launcher."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
            [jolt.sim.retained-process :as retained]
            [outbox-workbench.retained-main :as workbench]))

(def ^:private token "retained-outbox-workbench-test-token-0001")
(def ^:private watchdog-timeout-ms 180000)
(def ^:private failures (atom 0))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required") {:name name})))
    value))

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_RETAINED_WORKBENCH_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-retained-outbox-workbench-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

(defn- completed! [handle command]
  (let [receipt (retained/command! handle command)]
    (check (str "command " (:op command) " completed")
           :completed (:status receipt))
    receipt))

(defn- run-scenario []
  (let [instance
        (workbench/start!
         {:viewer-config {:port 0 :capability-token token}
          :retained-config
          (workbench/retained-config
           (required-environment "JOLT_SIM_BIN")
           (required-environment "JOLT_SIM_PROJECT_DIR"))})
        worker (:worker instance)]
    (println "retained workbench artifacts:"
             (get-in (retained/snapshot worker) [:artifact :dir]))
    (flush)
    (try
      (check "Ripple bound an ephemeral loopback port"
             true (pos? (get-in instance [:server :port])))
      (check "retained worker starts ready"
             :ready (:status (retained/snapshot worker)))
      (let [evaluation (eval-session/evaluate! (:session instance)
                                               {:form "(+ 20 22)"})]
        (check "the attached persistent EvalSession evaluates ordinary Jolt"
               42 (some #(when (= :ret (:tag %)) (:val %))
                        (:events evaluation))))
      (let [empty (completed! worker {:op :inspect})]
        (check "initial durable outbox is empty"
               [] (get-in empty [:value :store-state :outbox])))
      (let [submitted
            (completed! worker {:op :submit :command outbox/default-command})]
        (check "real HTTP command returns 201"
               201 (get-in submitted [:value :result :status]))
        (check "post-COMMIT row is pending before explicit delivery"
               :pending
               (get-in submitted
                       [:value :snapshot :store-state :outbox 0 :status]))
        (check "receiver has not seen a delivery before explicit step"
               0 (get-in submitted
                         [:value :snapshot :receiver-requests :count])))
      (let [delivered (completed! worker {:op :deliver})]
        (check "real TCP/bencode acknowledgement gates durable delivery"
               :delivered
               (get-in delivered
                       [:value :snapshot :store-state :outbox 0 :status]))
        (check "receiver observed exactly one request"
               1 (get-in delivered
                         [:value :snapshot :receiver-requests :count])))
      (let [first-stop (workbench/stop! instance)
            second-stop (workbench/stop! instance)
            terminal-path
            (get-in first-stop [:child :snapshot :artifact :terminal])
            terminal (when (fs/exists? terminal-path)
                       (edn/read-string (slurp terminal-path)))]
        (check "launcher stop is idempotent" first-stop second-stop)
        (check "launcher used the graceful terminal command"
               :completed (get-in first-stop [:child :receipt :status]))
        (check "retained child exited cleanly"
               0 (get-in first-stop [:child :snapshot :child :exit]))
        (check "retained child is reaped"
               false (get-in first-stop [:child :snapshot :child :alive?]))
        (check "retained child published completed terminal evidence"
               :completed (:jolt.sim.retained/status terminal)))
      {:port (get-in instance [:server :port])
       :artifact-dir (get-in (retained/snapshot worker) [:artifact :dir])}
      (finally
        (workbench/stop! instance)))))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "retained outbox workbench progress:" progress)
    (flush)
    (let [task (future (run-scenario))
          outcome (try
                    {:result (deref task watchdog-timeout-ms ::timeout)}
                    (catch :default error {:error error}))]
      (cond
        (:error outcome)
        (do
          (append-progress! progress
                            {:phase :error :status :errored
                             :message (ex-message (:error outcome))})
          (println "FAILURE:" (ex-message (:error outcome)))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (= ::timeout (:result outcome))
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :timeout-ms watchdog-timeout-ms})
          (println "FAILURE: retained outbox workbench timed out")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (pos? @failures)
        (do
          (append-progress! progress
                            {:phase :finish :status :failed
                             :failures @failures
                             :result (:result outcome)})
          (println "FAILURE:" @failures "checks failed")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        :else
        (do
          (append-progress! progress
                            {:phase :finish :status :passed
                             :result (:result outcome)})
          (println "PASS: retained outbox workbench")
          (println "progress:" progress)
          (flush)
          (System/exit 0))))))
