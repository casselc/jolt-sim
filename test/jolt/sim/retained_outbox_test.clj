(ns jolt.sim.retained-outbox-test
  "Fresh-process proof that a retained cooperative worker runs the unchanged
  HTTP/SQLite/TCP outbox lifecycle across multiple interactive commands."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
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
    "-M:outbox-json-delivery-test:retained-outbox-worker"]
   :adapter
   'jolt.sim.fixtures.outbox-json-delivery-retained/run!
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

(deftest retained-worker-keeps-the-real-outbox-application-alive-between-commands
  (let [handle (retained/start! (retained-config {}))]
    (println "retained accepted-outbox artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      (testing "the same child exposes an empty durable application"
        (let [receipt (completed! handle {:op :inspect})]
          (is (= {:entities {} :request-log {} :next-outbox-id 1 :outbox []}
                 (get-in receipt [:value :store-state])))))

      (testing "submit commits a pending row without delivering it"
        (let [receipt (completed! handle
                                  {:op :submit :command outbox/default-command})
              snapshot (get-in receipt [:value :snapshot])]
          (is (= 201 (get-in receipt [:value :result :status])))
          (is (= :pending (get-in snapshot [:store-state :outbox 0 :status])))
          (is (= 0 (get-in snapshot [:receiver-requests :count])))))

      (testing "a later command uses the retained TCP receiver and marks it"
        (let [receipt (completed! handle {:op :deliver})
              snapshot (get-in receipt [:value :snapshot])]
          (is (= :delivered (get-in snapshot [:store-state :outbox 0 :status])))
          (is (= 1 (get-in snapshot [:receiver-requests :count])))))

      (testing "the terminal command stops application resources and child"
        (let [receipt (completed! handle {:op :stop})]
          (is (true? (get-in receipt [:value :owner?])))
          (is (= :stopped (get-in receipt [:value :snapshot :status])))
          (assert-clean-terminal! handle)))
      (finally
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))

(deftest application-failure-does-not-kill-the-retained-worker
  (let [handle (retained/start! (retained-config {:ack-outcome :hostile}))]
    (println "retained hostile-ack artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      (completed! handle {:op :submit :command outbox/default-command})
      (let [failed (retained/command! handle {:op :deliver})]
        (is (= :failed (:status failed)))
        (is (= :application-command (get-in failed [:error :phase])))
        (is (= :ack-mismatch (get-in failed [:error :reason]))))
      (let [receipt (completed! handle {:op :inspect})]
        (is (= :pending
               (get-in receipt [:value :store-state :outbox 0 :status])))
        (is (= 1 (get-in receipt [:value :receiver-requests :count])))
        (is (= 1 (get-in receipt [:value :deliveries :count])))
        (is (= :ready (:status (retained/snapshot handle)))))
      (completed! handle {:op :stop})
      (assert-clean-terminal! handle)
      (finally
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))
