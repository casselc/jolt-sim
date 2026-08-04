(ns jolt.maelstrom.echo-sim-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.fixtures.echo-scenario :refer [echo-roundtrip]]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.runtime :as rt]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---- FIFO order and endpoint isolation ---------------------------------------

(deftest fifo-order-and-endpoint-isolation
  (let [t (memory/create-transport)]
    (is (= 1 (memory/enqueue! t {:src "c1" :dest "n1"
                                 :body {:type "echo" :msg_id 1 :echo "a"}})))
    (is (= 2 (memory/enqueue! t {:src "c1" :dest "n1"
                                 :body {:type "echo" :msg_id 2 :echo "b"}})))
    (is (= 3 (memory/enqueue! t {:src "c1" :dest "n2"
                                 :body {:type "echo" :msg_id 3 :echo "c"}})))
    ;; n1 is FIFO (a then b); n2 is fully isolated from n1.
    (is (= "a" (get-in (memory/take! t "n1") [:body :echo])))
    (is (= "b" (get-in (memory/take! t "n1") [:body :echo])))
    (is (= "c" (get-in (memory/take! t "n2") [:body :echo])))
    (is (nil? (memory/take! t "n1")))
    (is (nil? (memory/take! t "n2")))))

;; ---- history ordinals --------------------------------------------------------

(deftest history-records-stable-monotonic-ordinals
  (let [t (memory/create-transport)]
    (is (= 1 (memory/enqueue! t {:src "c1" :dest "n1"
                                 :body {:type "echo" :msg_id 1 :echo "x"}})))
    (is (= 2 (memory/enqueue! t {:src "c1" :dest "n1"
                                 :body {:type "echo" :msg_id 2 :echo "y"}})))
    (is (= "x" (get-in (memory/take! t "n1") [:body :echo])))
    (let [history (:history (memory/snapshot t))]
      (is (= [1 2 3] (mapv :ordinal history)))
      (is (= [:enqueue :enqueue :deliver] (mapv :op history)))
      (is (= ["n1" "n1" "n1"] (mapv :endpoint history)))
      ;; The deliver event carries the FIFO-front envelope, not the last one.
      (is (= ["x" "y" "x"] (mapv #(get-in % [:envelope :body :echo]) history)))
      (is (apply < (mapv :ordinal history)))
      ;; The transport tags each event with a positive :message-id; the
      ;; deliver event retains the enqueue's message-id rather than carrying
      ;; its own event ordinal.
      (is (every? #(and (integer? (:message-id %)) (pos? (:message-id %))) history))
      (let [enqs (filterv #(= :enqueue (:op %)) history)
            dels (filterv #(= :deliver (:op %)) history)]
        (is (= (mapv :ordinal enqs) (mapv :message-id enqs)))
        (is (= (:message-id (first enqs)) (:message-id (first dels))))
        (is (not= (:ordinal (first dels)) (:message-id (first dels))))))))

;; ---- message-id stability ---------------------------------------------------

(deftest identical-envelopes-receive-distinct-message-ids
  (let [t (memory/create-transport)
        env {:src "c1" :dest "n1" :body {:type "echo" :msg_id 7 :echo "same"}}
        ord1 (memory/enqueue! t env)
        ord2 (memory/enqueue! t env)]
    ;; take!/drain! still expose only the original bare envelope.
    (is (= env (memory/take! t "n1")))
    (let [history (:history (memory/snapshot t))
          enqs (filterv #(= :enqueue (:op %)) history)
          dels (filterv #(= :deliver (:op %)) history)]
      ;; Two structurally identical envelopes get distinct message-ids because
      ;; the message-id is the enqueue event's ordinal, not the envelope value.
      (is (not= ord1 ord2))
      (is (= [ord1 ord2] (mapv :message-id enqs)))
      (is (= [env env] (mapv :envelope enqs)))
      ;; The single delivery served the first enqueue's message-id.
      (is (= [ord1] (mapv :message-id dels)))))
  ;; snapshot :queues still holds bare envelopes only.
  (let [t (memory/create-transport)
        env {:src "c1" :dest "n1" :body {:type "echo" :msg_id 9 :echo "q"}}]
    (memory/enqueue! t env)
    (is (= {"n1" [env]} (:queues (memory/snapshot t))))
    (is (= [env] (memory/drain! t "n1")))))

;; ---- concurrent enqueue loses no updates -------------------------------------

(deftest concurrent-enqueue-loses-no-updates
  (let [t (memory/create-transport)
        producers 8
        per-producer 500
        total (* producers per-producer)
        latch (promise)
        workers (mapv (fn [i]
                        (future
                          @latch
                          (dotimes [j per-producer]
                            (memory/enqueue! t
                                             {:src "c1" :dest "n1"
                                              :body {:type "echo"
                                                     :msg_id (+ (* i 1000) j)
                                                     :echo (str i "-" j)}}))))
                      (range producers))]
    (deliver latch true)
    (doseq [w workers] @w)
    (let [drained (memory/drain! t "n1")
          msg-ids (mapv #(get-in % [:body :msg_id]) drained)]
      (is (= total (count drained)))
      (is (= total (count (set msg-ids)))
          "no msg_id lost and none duplicated under concurrent producers"))))

;; ---- empty take/drain --------------------------------------------------------

(deftest empty-take-and-drain-record-nothing
  (let [t (memory/create-transport)]
    (is (nil? (memory/take! t "absent")))
    (is (= [] (memory/drain! t "absent")))
    (is (= {} (:queues (memory/snapshot t))))
    (is (= [] (:history (memory/snapshot t))))
    (memory/enqueue! t {:src "c1" :dest "n1"
                        :body {:type "echo" :msg_id 1 :echo "z"}})
    (memory/take! t "n1")
    (is (= [] (memory/drain! t "n1"))
        "drain after the FIFO is emptied yields no envelopes")))

;; ---- snapshot isolation ------------------------------------------------------

(deftest snapshot-is-structurally-stable-and-leaks-no-atoms-or-functions
  (let [t (memory/create-transport)]
    (memory/enqueue! t {:src "c1" :dest "n1"
                        :body {:type "echo" :msg_id 1 :echo "a"}})
    (let [snap (memory/snapshot t)]
      ;; Mutate the transport after capturing the snapshot.
      (memory/enqueue! t {:src "c1" :dest "n1"
                          :body {:type "echo" :msg_id 2 :echo "b"}})
      (memory/take! t "n1")
      ;; The persistent queue/history containers are frozen at capture time.
      (is (= #{:queues :history} (set (keys snap))))
      (is (= ["n1"] (keys (:queues snap))))
      (is (= 1 (count (get-in snap [:queues "n1"]))))
      (is (= "a" (get-in (first (get-in snap [:queues "n1"])) [:body :echo])))
      (is (= 1 (count (:history snap))))
      ;; No send function leaks anywhere in the returned data; the world atom
      ;; is never surfaced (only :queues/:history keys are present).
      (is (not-any? fn? (tree-seq coll? seq snap)))
      (is (vector? (:history snap)))
      (is (every? vector? (vals (:queues snap)))))))

(deftest invalid-endpoints-and-transports-fail-closed
  (let [t (memory/create-transport)]
    (doseq [endpoint [nil :n1 1]]
      (is (= :jolt.maelstrom.transport.memory/invalid-endpoint
             (:type (ex-data-of #(memory/take! t endpoint))))))
    (is (= :jolt.maelstrom.transport.memory/invalid-transport
           (:type (ex-data-of #(memory/snapshot {})))))))

;; ---- thin validation: malformed body reaches node validation -----------------

(deftest malformed-body-reaches-node-validation
  (let [t (memory/create-transport)]
    ;; The body is malformed (non-string :type) but :dest routes fine, so the
    ;; transport accepts it; node/handle! must be the one to reject it.
    (is (= 1 (memory/enqueue! t {:src "c1" :dest "n1" :body {:type 5}})))
    (let [node (node/create-node
                {:handlers echo/handlers
                 :send! (memory/send-fn t)})
          inbound (memory/take! t "n1")]
      (is (= "n1" (:dest inbound)))
      (is (= :jolt.maelstrom.node/invalid-envelope
             (:type (ex-data-of #(node/handle! node inbound))))))))

;; ---- defsim init + nested Unicode echo round trip ----------------------------

(deftest defsim-init-and-nested-unicode-echo-roundtrip
  (if (rt/available?)
    (let [payload {"greeting" "héllo, 世界 🌍"
                   "lang" "日本語"
                   "nested" {"a" "ελληνικά"
                              "b" [" 한글 " "português" 42 nil]}
                   "emoji" "🚀"}
          result (:result (echo-roundtrip {} payload))
          replies (:replies result)
          history (get-in result [:transport :history])]
      (is (= "n1" (:node-id result)))
      (is (= 2 (count replies)))
      (is (= "init_ok" (get-in (first replies) [:body :type])))
      (is (= 1 (get-in (first replies) [:body :in_reply_to])))
      (is (= "echo_ok" (get-in (second replies) [:body :type])))
      (is (= 2 (get-in (second replies) [:body :in_reply_to])))
      ;; The same unchanged echo handler round-trips a nested Unicode body
      ;; exactly, end to end, over the transport.
      (is (= payload (get-in (second replies) [:body :echo])))
      (is (= "n1" (:src (second replies))))
      (is (= "c1" (:dest (second replies))))
      (is (= (vec (range 1 9)) (mapv :ordinal history)))
      (is (= [:enqueue :enqueue :deliver :enqueue
              :deliver :enqueue :deliver :deliver]
             (mapv :op history)))
      (is (= {} (get-in result [:transport :queues]))))
    (is (false? (rt/available?)))))
