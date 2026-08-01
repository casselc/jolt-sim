(ns jolt.sim.maelstrom-history-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.fixtures.echo-scenario :refer [echo-roundtrip]]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.maelstrom.history :as history]
            [jolt.sim.runtime :as rt]))

(defn- violation? [v]
  (and (map? v) (= :violation (:status v))))

(defn- reason [v] (:reason v))

(defn- enq
  ([ordinal endpoint envelope]
   {:ordinal ordinal :op :enqueue :endpoint endpoint
    :envelope envelope :message-id ordinal})
  ([ordinal message-id endpoint envelope]
   {:ordinal ordinal :op :enqueue :endpoint endpoint
    :envelope envelope :message-id message-id}))

(defn- del
  ([ordinal message-id endpoint envelope]
   {:ordinal ordinal :op :deliver :endpoint endpoint
    :envelope envelope :message-id message-id}))

(defn- snap [history & [queues]]
  {:queues (or queues {}) :history history})

(def ^:private env-a {:src "c1" :dest "n1" :body {:type "echo" :msg_id 1 :echo "a"}})
(def ^:private env-b {:src "c1" :dest "n1" :body {:type "echo" :msg_id 2 :echo "b"}})
(def ^:private env-c {:src "c1" :dest "n2" :body {:type "echo" :msg_id 3 :echo "c"}})

;; ---- valid real-transport snapshots -----------------------------------------

(deftest empty-snapshot-passes
  (let [result (history/check-snapshot {:queues {} :history []})]
    (is (= :pass (:status result)))
    (is (zero? (:events result)))
    (is (zero? (:enqueued result)))
    (is (zero? (:delivered result)))))

(deftest real-single-enqueue-passes
  (let [t (memory/create-transport)
        ord (memory/enqueue! t env-a)
        snap (memory/snapshot t)]
    (is (= ord (:message-id (first (:history snap)))))
    (is (= :pass (:status (history/check-snapshot snap))))))

(deftest real-enqueue-and-deliver-passes
  (let [t (memory/create-transport)]
    (memory/enqueue! t env-a)
    (memory/take! t "n1")
    (is (= :pass (:status (history/check-snapshot (memory/snapshot t)))))))

(deftest real-identical-envelopes-distinct-message-ids-pass
  (let [t (memory/create-transport)
        id1 (memory/enqueue! t env-a)
        id2 (memory/enqueue! t env-a)
        snap (memory/snapshot t)
        enqs (filterv #(= :enqueue (:op %)) (:history snap))]
    (is (not= id1 id2))
    (is (= [id1 id2] (mapv :message-id enqs)))
    (is (= :pass (:status (history/check-snapshot snap))))))

(deftest real-partial-drain-of-identical-envelopes-passes
  (let [t (memory/create-transport)]
    (memory/enqueue! t env-a)
    (memory/enqueue! t env-a)
    (memory/take! t "n1")
    (is (= :pass (:status (history/check-snapshot (memory/snapshot t)))))))

(deftest real-multi-endpoint-fifo-and-residual-passes
  (let [t (memory/create-transport)]
    (memory/enqueue! t env-a)   ; n1
    (memory/enqueue! t env-c)   ; n2
    (memory/enqueue! t env-b)   ; n1
    (memory/take! t "n1")
    ;; n1 still has env-b; n2 still has env-c; both residuals must agree.
    (is (= :pass (:status (history/check-snapshot (memory/snapshot t)))))))

(deftest echo-scenario-snapshot-passes
  (if (rt/available?)
    (let [snap (:transport (:result (echo-roundtrip {} "payload")))]
      (is (= :pass (:status (history/check-snapshot snap)))))
    (is (false? (rt/available?)))))

;; ---- valid hand-built histories ---------------------------------------------

(deftest hand-built-identical-envelopes-distinct-ids-pass
  ;; Two structurally identical envelopes enqueued at distinct ordinals (and
  ;; thus distinct message-ids) pass even though the envelopes compare equal.
  (let [history [(enq 1 "n1" env-a) (enq 2 "n1" env-a)]
        queues {"n1" [env-a env-a]}]
    (is (= :pass (:status (history/check-snapshot (snap history queues)))))))

(deftest hand-built-delivery-retains-enqueue-message-id-pass
  (let [history [(enq 1 "n1" env-a) (enq 2 "n2" env-c)
                 (del 3 1 "n1" env-a) (del 4 2 "n2" env-c)]]
    (is (= :pass (:status (history/check-snapshot (snap history)))))))

;; ---- malformed shape --------------------------------------------------------

(deftest malformed-snapshot-is-rejected
  (doseq [[s detail-reason]
          [[nil :snapshot-not-a-map]
           [{:queues {} :history [] :extra :nope} :snapshot-wrong-keys]
           [{:queues [] :history []} :queues-not-a-map]
           [{:queues {} :history {}} :history-not-a-vector]
           [{:queues {"n1" []} :history []} :queues-malformed]
           [{:queues {"n1" ["not a map"]} :history []} :queues-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (dissoc :message-id))]}
            :event-malformed]
           [{:queues {} :history [(assoc (enq 1 "n1" env-a) :extra :nope)]}
            :event-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (assoc :op :unknown))]}
            :event-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (assoc :endpoint 5))]}
            :event-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (assoc :envelope "nope"))]}
            :event-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (assoc :ordinal -1))]}
            :event-malformed]
           [{:queues {} :history [(-> (enq 1 "n1" env-a)
                                      (assoc :message-id 0))]}
            :event-malformed]]]
    (let [result (history/check-snapshot s)]
      (is (violation? result) s)
      (is (= :malformed-snapshot (reason result)) s)
      (is (= detail-reason (get-in result [:detail :reason])) s))))

;; ---- corrupted histories ----------------------------------------------------

(deftest noncontiguous-ordinals-are-rejected
  (let [history [(enq 1 "n1" env-a) (enq 3 "n1" env-b)]]
    (is (= :noncontiguous-ordinals (reason (history/check-snapshot (snap history)))))))

(deftest duplicate-enqueue-identity-is-rejected
  ;; Two enqueue events with the same message-id despite distinct ordinals.
  (let [history [(enq 1 1 "n1" env-a) (enq 2 1 "n1" env-b)]]
    (is (= :duplicate-enqueue-identity
           (reason (history/check-snapshot (snap history)))))))

(deftest enqueue-identity-must-equal-its-event-ordinal
  (let [history [(enq 1 9 "n1" env-a)]
        queues {"n1" [env-a]}]
    (is (= :enqueue-identity-mismatch
           (reason (history/check-snapshot (snap history queues)))))))

(deftest enqueue-endpoint-must-match-envelope-destination
  (let [history [(enq 1 "n2" env-a)]
        queues {"n2" [env-a]}]
    (is (= :enqueue-route-mismatch
           (reason (history/check-snapshot (snap history queues)))))))

(deftest delivery-without-enqueue-is-rejected
  (let [history [(del 1 99 "n1" env-a)]]
    (is (= :delivery-without-enqueue
           (reason (history/check-snapshot (snap history)))))))

(deftest delivery-before-enqueue-is-rejected
  (let [history [(del 1 2 "n1" env-a) (enq 2 "n1" env-a)]]
    (is (= :delivery-before-enqueue
           (reason (history/check-snapshot (snap history)))))))

(deftest duplicate-delivery-is-rejected
  (let [history [(enq 1 "n1" env-a)
                 (del 2 1 "n1" env-a)
                 (del 3 1 "n1" env-a)]]
    (is (= :duplicate-delivery
           (reason (history/check-snapshot (snap history)))))))

(deftest endpoint-mismatch-is-rejected
  (let [history [(enq 1 "n2" env-c) (del 2 1 "n1" env-c)]]
    (is (= :endpoint-mismatch
           (reason (history/check-snapshot (snap history)))))))

(deftest envelope-mismatch-is-rejected
  (let [history [(enq 1 "n1" env-a) (del 2 1 "n1" env-b)]]
    (is (= :envelope-mismatch
           (reason (history/check-snapshot (snap history)))))))

(deftest fifo-violation-is-rejected
  ;; Two enqueues at n1 (ids 1 and 2); the first delivery serves id 2 out of
  ;; FIFO order.
  (let [history [(enq 1 "n1" env-a) (enq 2 "n1" env-b)
                 (del 3 2 "n1" env-b)]]
    (is (= :fifo-violation
           (reason (history/check-snapshot (snap history)))))))

(deftest residual-queue-disagreement-wrong-envelope-is-rejected
  (let [history [(enq 1 "n1" env-a)]
        queues {"n1" [env-b]}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))

(deftest residual-queue-disagreement-extra-envelope-is-rejected
  (let [history [(enq 1 "n1" env-a)]
        queues {"n1" [env-a env-b]}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))

(deftest residual-queue-disagreement-missing-queue-is-rejected
  (let [history [(enq 1 "n1" env-a)]
        queues {}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))

(deftest residual-queue-disagreement-extra-endpoint-is-rejected
  (let [history [(enq 1 "n1" env-a)]
        queues {"n1" [env-a] "n2" [env-c]}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))

(deftest residual-queue-disagreement-wrong-order-is-rejected
  (let [history [(enq 1 "n1" env-a) (enq 2 "n1" env-b)]
        queues {"n1" [env-b env-a]}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))

(deftest fully-drained-endpoint-must-not-appear-in-residual
  (let [history [(enq 1 "n1" env-a) (del 2 1 "n1" env-a)]
        queues {"n1" [env-a]}]
    (is (= :residual-queue-disagreement
           (reason (history/check-snapshot {:queues queues :history history}))))))
