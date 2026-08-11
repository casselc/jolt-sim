(ns jolt.maelstrom.broadcast-live-test
  "Direct contract tests for the interactive Broadcast lifecycle.

  Test helpers choose the next ready mailbox, but every delivery still crosses
  broadcast-live/step! one envelope at a time."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.maelstrom.fixtures.broadcast-live :as live]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- drain-ready!
  [application]
  (loop [steps 0]
    (when (> steps 100)
      (throw (ex-info "interactive Broadcast test exceeded step bound"
                      {:steps steps})))
    (let [snapshot (live/snapshot! application)
          node-id (first (:ready-mailboxes snapshot))]
      (if node-id
        (let [result (live/step! application node-id)]
          (is (true? (:delivered? result)) (pr-str result))
          (recur (inc steps)))
        steps))))

(defn- node-messages [snapshot]
  (into {} (map (fn [[id state]] [id (:messages state)]) (:nodes snapshot))))

(defn- node-pending [snapshot]
  (into {} (map (fn [[id state]] [id (:pending state)]) (:nodes snapshot))))

(deftest healthy-cluster-is-controlled-one-mailbox-delivery-at-a-time
  (let [application (live/start! {:message 42 :regime :healthy})]
    (try
      (testing "start constructs the real cluster without running it"
        (let [snapshot (live/snapshot! application)]
          (is (= :created (:status snapshot)))
          (is (= [] (:ready-mailboxes snapshot)))
          (is (= 0 (get-in snapshot [:transport :history-count])))))

      (testing "bootstrap enqueues openers but performs no hidden delivery"
        (is (= {:operation :bootstrap :enqueued 7}
               (live/bootstrap! application)))
        (let [snapshot (live/snapshot! application)]
          (is (= ["n1" "n2" "n3"] (:ready-mailboxes snapshot)))
          (is (= {"n1" 3 "n2" 2 "n3" 2}
                 (into {} (map (fn [[id mailbox]] [id (:count mailbox)])
                               (:mailboxes snapshot)))))
          (is (= 0 (get-in snapshot [:client-replies :count])))))

      (testing "one selected step changes only one node mailbox head"
        (let [step (live/step! application "n2")
              snapshot (live/snapshot! application)]
          (is (= {:node-id "n2" :delivered? true}
                 (select-keys step [:node-id :delivered?])))
          (is (= "init" (get-in step [:envelope :body :type])))
          (is (= {"n1" 3 "n2" 1 "n3" 2}
                 (into {} (map (fn [[id mailbox]] [id (:count mailbox)])
                               (:mailboxes snapshot)))))
          (is (= 1 (get-in snapshot [:client-replies :count])))))

      (testing "the unchanged handlers converge after explicit stepping"
        (is (pos? (drain-ready! application)))
        (let [snapshot (live/snapshot! application)]
          (is (= {"n1" [42] "n2" [42] "n3" [42]}
                 (node-messages snapshot)))
          (is (= {"n1" [] "n2" [] "n3" []}
                 (node-pending snapshot)))
          (is (= [] (:ready-mailboxes snapshot)))
          (is (= 7 (get-in snapshot [:client-replies :count])))))

      (testing "read is also enqueued and delivered only by a selected step"
        (is (= :read (:operation (live/read! application))))
        (is (= ["n3"] (:ready-mailboxes (live/snapshot! application))))
        (is (pos? (drain-ready! application)))
        (let [reply (last (get-in (live/snapshot! application)
                                  [:client-replies :tail]))]
          (is (= "read_ok" (get-in reply [:body :type])))
          (is (= 30 (get-in reply [:body :in_reply_to])))
          (is (= [42] (get-in reply [:body :messages])))))
      (finally
        (live/stop! application)))))

(deftest partition-needs-heal-and-real-application-retry
  (let [application (live/start! {:message -7 :regime :partition-heal})]
    (try
      (live/bootstrap! application)
      (drain-ready! application)
      (let [partitioned (live/snapshot! application)
            pending (get-in partitioned [:nodes "n2" :pending 0])
            dropped (get-in partitioned [:drops :records 0])]
        (is (= {"n1" [-7] "n2" [-7] "n3" []}
               (node-messages partitioned)))
        (is (= 1 (get-in partitioned [:drops :dropped-total])))
        (is (= ["n2" "n3"] [(:src dropped) (:dest dropped)]))
        (is (= (:msg_id pending) (get-in dropped [:body :msg_id])))

        (testing "healing alone cannot manufacture the dropped envelope"
          (live/heal! application)
          (is (= 0 (drain-ready! application)))
          (is (= [] (get-in (live/snapshot! application)
                             [:nodes "n3" :messages]))))

        (testing "the real retry-pending! reuses identity and converges"
          (let [retry (live/retry! application)]
            (is (= pending (get-in retry [:evidence "n2" 0])))
            (is (= [] (get-in retry [:evidence "n1"])))
            (is (= [] (get-in retry [:evidence "n3"]))))
          (is (pos? (drain-ready! application)))
          (let [converged (live/snapshot! application)]
            (is (= {"n1" [-7] "n2" [-7] "n3" [-7]}
                   (node-messages converged)))
            (is (= {"n1" [] "n2" [] "n3" []}
                   (node-pending converged))))))
      (finally
        (live/stop! application)))))

(deftest exact-input-phase-and-command-boundaries-fail-closed
  (doseq [[input reason]
          [[nil :input-not-a-map]
           [{:message 1} :input-wrong-keys]
           [{:message "1" :regime :healthy} :message-not-integer]
           [{:message 1 :regime :unknown} :unsupported-regime]]]
    (is (= reason (:reason (ex-data-of #(live/start! input)))) (pr-str input)))
  (let [application (live/start! {:message 1 :regime :healthy})]
    (is (= :invalid-phase
           (:reason (ex-data-of #(live/step! application "n1")))))
    (live/bootstrap! application)
    (is (= :invalid-phase
           (:reason (ex-data-of #(live/bootstrap! application)))))
    (is (= :invalid-node-id
           (:reason (ex-data-of #(live/step! application "c1")))))
    (is (= :healthy-regime-has-no-partition
           (:reason (ex-data-of #(live/heal! application)))))
    (live/read! application)
    (is (= :read-already-issued
           (:reason (ex-data-of #(live/read! application)))))
    (is (true? (live/stop! application)))
    (is (false? (live/stop! application)))
    (is (= :stopped (:status (live/snapshot! application))))
    (is (= :invalid-phase
           (:reason (ex-data-of #(live/retry! application)))))))

(deftest drop-and-history-evidence-remain-bounded
  (let [application (live/start! {:message 9 :regime :partition-heal})]
    (try
      (live/bootstrap! application)
      (drain-ready! application)
      ;; Each retry while the partition is active exercises the unchanged app's
      ;; same-msg-id retry and adds one gate drop, without growing drop records
      ;; past broadcast-scenario's existing bound.
      (dotimes [_ 80] (live/retry! application))
      (let [snapshot (live/snapshot! application)]
        (is (= 81 (get-in snapshot [:drops :dropped-total])))
        (is (= 16 (count (get-in snapshot [:drops :records]))))
        (is (<= (count (get-in snapshot [:transport :history-tail])) 64))
        (is (<= (count (get-in snapshot [:client-replies :tail])) 32)))
      (finally
        (live/stop! application)))))
