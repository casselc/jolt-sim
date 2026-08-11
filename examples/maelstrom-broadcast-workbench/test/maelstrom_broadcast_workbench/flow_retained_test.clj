(ns maelstrom-broadcast-workbench.flow-retained-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]
            [maelstrom-broadcast-workbench.flow-retained :as flow-retained]))

(def sample-snapshot
  {:kind :jolt.sim/maelstrom-broadcast-live
   :status :running
   :input {:message 42 :regime :healthy}
   :topology {"n1" ["n2"] "n2" ["n1" "n3"] "n3" ["n2"]}
   :connections {["n1" "n2"] :normal ["n2" "n3"] :drop}
   :regime-revision 4
   :partition {:links [["n2" "n3"]] :active? true}
   :nodes {"n1" {:messages [42] :pending []}
           "n2" {:messages [42] :pending [{:dest "n3" :msg_id 9}]}
           "n3" {:messages [] :pending []}}
   :ready-mailboxes ["n2"]
   :mailboxes {"n1" {:count 0 :head nil}
               "n2" {:count 1 :head {:src "n1" :dest "n2"}}
               "n3" {:count 0 :head nil}}
   :client-replies
   {:count 2
    :tail [{:body {:type "broadcast_ok"}}
           {:body {:type "read_ok" :messages [42]}}]}
   :drops {:records [] :dropped-total 3}
   :transport {:history-count 8 :history-tail []}
   :control {:bootstrap-count 1 :step-count 8 :retry-count 1
             :read-issued? true :last-step nil :last-retry nil}})

(defn- restored-world [snapshot]
  (:world (trace/restore-value (:projection snapshot))))

(deftest command-cells-emit-one-exact-intent-without-preview-effects
  (doseq [command [{:op :inspect}
                   {:op :bootstrap}
                   {:op :step :node-id "n2"}
                   {:op :set-connection-regime
                    :connection ["n2" "n3"]
                    :expected-revision 4
                    :regime :normal}
                   {:op :heal}
                   {:op :retry}
                   {:op :read}
                   {:op :stop}]]
    (testing (pr-str command)
      (let [sim (flow-retained/command-flow command)
            capability (session/start sim)
            before (session/snapshot capability)
            branches (session/branches capability)
            after-preview (session/snapshot capability)
            committed (session/step! capability (:branch (first branches)))
            intents (:effect-intents (restored-world committed))]
        (is (= 1 (count branches)))
        (is (= before after-preview))
        (is (= [{:id [:jolt.sim.flow/intent :command 0 0]
                 :kind flow-retained/effect-kind
                 :payload command
                 :source {:cell :command :message-id 0 :ordinal 0}}]
               intents))))))

(deftest command-contracts-fail-before-an-intent-exists
  (doseq [command [{:op :unknown}
                   {:op :step :node-id "n4"}
                   {:op :step :node-id "n1" :extra true}
                   {:op :set-connection-regime
                    :connection ["n1" "n3"]
                    :expected-revision 0
                    :regime :drop}
                   {:op :set-connection-regime
                    :connection ["n1" "n2"]
                    :expected-revision -1
                    :regime :drop}
                   {:op :set-connection-regime
                    :connection ["n1" "n2"]
                    :expected-revision 0
                    :regime :delay}]]
    (is (thrown? Exception (flow-retained/command-flow command))
        (pr-str command))))

(deftest completed-receipts-enter-a-closed-pure-continuation
  (let [expected {:operation :inspect
                  :status :running
                  :regime-revision 4
                  :connections [{:nodes ["n1" "n2"] :regime :normal}
                                {:nodes ["n2" "n3"] :regime :drop}]
                  :ready-mailboxes ["n2"]
                  :mailbox-counts {"n1" 0 "n2" 1 "n3" 0}
                  :messages {"n1" [42] "n2" [42] "n3" []}
                  :pending-counts {"n1" 0 "n2" 1 "n3" 0}
                  :dropped-total 3
                  :client-reply-count 2
                  :read-messages [42]
                  :observed true}]
    (is (= expected (flow-retained/observe sample-snapshot)))
    (is (= (assoc expected :operation :retry)
           (flow-retained/observe
            {:kind :maelstrom-broadcast-workbench/command-result
             :operation :retry
             :result {:operation :retry}
             :snapshot sample-snapshot})))
    (is (thrown? Exception
                 (flow-retained/receipt-flow
                  {:kind :maelstrom-broadcast-workbench/command-result
                   :operation :retry
                   :snapshot (dissoc sample-snapshot :regime-revision)})))))

(defn -main [& _]
  (let [result (clojure.test/run-tests
                'maelstrom-broadcast-workbench.flow-retained-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
