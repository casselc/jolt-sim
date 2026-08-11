(ns jolt.sim.retained-broadcast-test
  "Fresh-process proof that one retained worker keeps the actual interactive
  Maelstrom Broadcast cluster alive across operator commands."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.retained-process :as retained]))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required")
                      {:type ::missing-environment :name name})))
    value))

(defn- retained-config [input]
  {:worker-command
   [(required-environment "JOLT_SIM_BIN")
    "-M:maelstrom-broadcast-retained-worker"]
   :adapter 'jolt.sim.fixtures.broadcast-retained/run!
   :input input
   :dir (required-environment "JOLT_SIM_PROJECT_DIR")
   :temp-dir (or (System/getenv "JOLT_SIM_RETAINED_ARTIFACT_DIR")
                 (or (System/getenv "TMPDIR") "/tmp"))
   :extra-env {"JOLT_AOT_CACHE" "0"}
   :startup-timeout-ms 60000
   :command-timeout-ms 60000
   :kill-grace-ms 1000})

(defn- completed! [handle command]
  (let [receipt (retained/command! handle command)]
    (is (= :completed (:status receipt)) (pr-str receipt))
    receipt))

(defn- inspect! [handle]
  (:value (completed! handle {:op :inspect})))

(defn- drain-ready! [handle]
  (loop [steps 0]
    (when (> steps 100)
      (throw (ex-info "retained Broadcast test exceeded step bound"
                      {:steps steps})))
    (if-let [node-id (first (:ready-mailboxes (inspect! handle)))]
      (let [receipt (completed! handle {:op :step :node-id node-id})]
        (is (true? (get-in receipt [:value :result :delivered?]))
            (pr-str receipt))
        (recur (inc steps)))
      steps)))

(defn- wait-for-exit [handle]
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (let [snapshot (retained/snapshot handle)]
        (if (or (not (get-in snapshot [:child :alive?]))
                (>= (System/nanoTime) deadline))
          snapshot
          (do (Thread/sleep 10) (recur)))))))

(defn- assert-clean-terminal! [handle]
  (let [stopped (wait-for-exit handle)
        terminal-path (get-in stopped [:artifact :terminal])
        terminal (when (fs/exists? terminal-path)
                   (edn/read-string (slurp terminal-path)))]
    (is (false? (get-in stopped [:child :alive?])))
    (is (= 0 (get-in stopped [:child :exit])))
    (is (= :completed (:jolt.sim.retained/status terminal)))
    stopped))

(defn- node-messages [snapshot]
  (into {} (map (fn [[id state]] [id (:messages state)]) (:nodes snapshot))))

(deftest retained-worker-controls-partition-heal-retry-and-read
  (let [handle (retained/start!
                (retained-config {:message 42 :regime :partition-heal}))]
    (println "retained Broadcast artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      (testing "the fresh worker waits at the constructed boundary"
        (let [snapshot (inspect! handle)]
          (is (= :created (:status snapshot)))
          (is (= [] (:ready-mailboxes snapshot)))))

      (completed! handle {:op :bootstrap})
      (is (pos? (drain-ready! handle)))
      (let [partitioned (inspect! handle)
            pending (get-in partitioned [:nodes "n2" :pending 0])]
        (is (= {"n1" [42] "n2" [42] "n3" []}
               (node-messages partitioned)))
        (is (= 1 (get-in partitioned [:drops :dropped-total])))

        (testing "heal alone remains observably insufficient"
          (completed! handle {:op :heal})
          (is (= 0 (drain-ready! handle)))
          (is (= [] (get-in (inspect! handle) [:nodes "n3" :messages]))))

        (testing "the later retry retains the application's request identity"
          (let [receipt (completed! handle {:op :retry})]
            (is (= pending
                   (get-in receipt [:value :result :evidence "n2" 0]))))
          (is (pos? (drain-ready! handle)))
          (is (= {"n1" [42] "n2" [42] "n3" [42]}
                 (node-messages (inspect! handle))))))

      (testing "a read is explicit and its request is stepped normally"
        (completed! handle {:op :read})
        (is (= ["n3"] (:ready-mailboxes (inspect! handle))))
        (is (pos? (drain-ready! handle)))
        (let [reply (last (get-in (inspect! handle) [:client-replies :tail]))]
          (is (= "read_ok" (get-in reply [:body :type])))
          (is (= [42] (get-in reply [:body :messages])))))

      (testing "terminal stop publishes clean child evidence"
        (let [receipt (completed! handle {:op :stop})]
          (is (true? (get-in receipt [:value :owner?])))
          (is (= :stopped (get-in receipt [:value :snapshot :status])))
          (assert-clean-terminal! handle)))
      (finally
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))

(deftest rejected-command-does-not-kill-the-retained-cluster
  (let [handle (retained/start!
                (retained-config {:message 1 :regime :healthy}))]
    (println "retained Broadcast rejection artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      (let [failed (retained/command! handle {:op :step :node-id "n1"})]
        (is (= :failed (:status failed)))
        (is (= :jolt.maelstrom.fixtures.broadcast-live/invalid-operation
               (get-in failed [:error :type])))
        (is (= :invalid-phase (get-in failed [:error :reason]))))
      (is (= :created (:status (inspect! handle))))
      (let [failed (retained/command! handle
                                      {:op :step :node-id "n1" :extra true})]
        (is (= :failed (:status failed)))
        (is (= :jolt.sim.fixtures.broadcast-retained/invalid-command
               (get-in failed [:error :type])))
        (is (= :wrong-keys (get-in failed [:error :reason]))))
      (is (= :ready (:status (retained/snapshot handle))))
      (completed! handle {:op :stop})
      (assert-clean-terminal! handle)
      (finally
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))

(deftest retained-worker-receipts-carry-exact-connection-regime-results
  (let [handle (retained/start!
                (retained-config {:message 5 :regime :healthy}))]
    (println "retained Broadcast connection-regime artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      (completed! handle {:op :bootstrap})
      (let [receipt (completed! handle
                                {:op :set-connection-regime
                                 :connection ["n3" "n2"]
                                 :expected-revision 0
                                 :regime :drop})]
        (is (= :set-connection-regime
               (get-in receipt [:value :operation])))
        (is (= {:operation :set-connection-regime
                :connection ["n2" "n3"]
                :previous-regime :normal
                :regime :drop
                :previous-revision 0
                :regime-revision 1}
               (get-in receipt [:value :result])))
        (is (= {["n1" "n2"] :normal ["n2" "n3"] :drop}
               (get-in receipt [:value :snapshot :connections])))
        (is (= 1 (get-in receipt [:value :snapshot :regime-revision]))))
      (testing "stale application rejection preserves state and worker readiness"
        (let [before (inspect! handle)
              failed (retained/command!
                      handle {:op :set-connection-regime
                              :connection ["n1" "n2"]
                              :expected-revision 0
                              :regime :drop})
              after (inspect! handle)]
          (is (= :failed (:status failed)))
          (is (= :jolt.maelstrom.fixtures.broadcast-scenario/stale-regime-revision
                 (get-in failed [:error :type])))
          (is (= before after))
          (is (= :ready (:status (retained/snapshot handle))))))
      (completed! handle {:op :stop})
      (assert-clean-terminal! handle)
      (finally
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))
