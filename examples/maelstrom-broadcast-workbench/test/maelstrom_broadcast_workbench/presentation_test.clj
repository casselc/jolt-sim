(ns maelstrom-broadcast-workbench.presentation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.sim.trace :as trace]
            [maelstrom-broadcast-workbench.presentation :as presentation]))

(defn- snapshot [active? drops]
  {:kind :jolt.sim/maelstrom-broadcast-live
   :status :running
   :input {:message 42 :regime :partition-heal}
   :topology {"n3" ["n2"] "n1" ["n2"] "n2" ["n3" "n1"]}
   :partition {:links [["n2" "n3"]] :active? active?}
   :nodes {"n3" {:messages [] :pending []}
           "n1" {:messages [42] :pending []}
           "n2" {:messages [42] :pending [{:dest "n3"}]}}
   :ready-mailboxes ["n2"]
   :mailboxes {"n3" {:count 0 :head nil}
               "n1" {:count 0 :head nil}
               "n2" {:count 1 :head {:src "n1" :dest "n2"}}}
   :client-replies {:count 0 :tail []}
   :drops {:records drops :dropped-total (count drops)}
   :transport {:history-count 8 :history-tail []}
   :control {:bootstrap-count 1 :step-count 7 :retry-count 0
             :read-issued? false :last-step nil :last-retry nil}})

(defn- restored-field [fields label]
  (->> fields
       (filter #(= label (:label %)))
       first
       :value
       trace/restore-value))

(deftest snapshot-projector-is-stable-and-carries-real-evidence
  (let [drop {:src "n2" :dest "n3" :body {:type "broadcast"}}
        model (presentation/present (snapshot true [drop]))
        nodes (get-in model [:graph :nodes])
        edges (get-in model [:graph :edges])]
    (is (= :jolt.sim.kind/topology (:kind model)))
    (is (= ["n1" "n2" "n3"] (mapv :id nodes)))
    (is (= ["n1--n2" "n2--n3"] (mapv :id edges)))
    (is (= [:jolt.sim.status/connected :jolt.sim.status/partitioned]
           (mapv :status edges)))
    (is (= ["Control" "Drops" "Input" "Replies" "Status" "Transport"]
           (mapv :label (:fields model))))
    (is (= 1 (restored-field (:fields (second edges))
                             "Dropped envelopes")))
    (is (= 1 (restored-field (:fields (second nodes)) "Pending")))
    (is (= {:count 0 :tail []}
           (restored-field (:fields model) "Replies")))
    (is (= model (presentation/present (snapshot true [drop]))))))

(deftest healed-and-command-values-use-explicit-kinds
  (let [snapshot (snapshot false [])
        direct (presentation/present snapshot)
        command (presentation/present
                 {:kind :maelstrom-broadcast-workbench/command-result
                  :operation :heal :result {:operation :heal}
                  :snapshot snapshot})]
    (is (= :jolt.sim.status/healed
           (get-in direct [:graph :edges 1 :status])))
    (is (= :maelstrom-broadcast-workbench/command-result
           (:source-kind command)))
    (is (= (:graph direct) (:graph command)))
    (is (string? (:source-edn command)))
    (is (not (re-find #"#object|Function|Atom" (trace/canonical-edn command))))
    (let [created (presentation/present
                   (-> snapshot
                       (assoc :status :created)
                       (assoc :mailboxes
                              {"n1" {:count 0 :head nil}
                               "n2" {:count 0 :head nil}
                               "n3" {:count 0 :head nil}})))]
      (is (= "Broadcast cluster created" (:summary created)))
      (is (= [:jolt.sim.status/idle
              :jolt.sim.status/idle
              :jolt.sim.status/idle]
             (mapv :status (get-in created [:graph :nodes])))))
    (let [stopped (presentation/present (assoc snapshot :status :stopped))]
      (is (= [:jolt.sim.status/stopped
              :jolt.sim.status/stopped
              :jolt.sim.status/stopped]
             (mapv :status (get-in stopped [:graph :nodes])))))))

(defn -main [& _]
  (let [result (run-tests 'maelstrom-broadcast-workbench.presentation-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
