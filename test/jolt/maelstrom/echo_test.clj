(ns jolt.maelstrom.echo-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.node :as node]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- new-node []
  (let [sent (atom [])]
    {:node (node/create-node {:handlers echo/handlers
                               :send! (fn [envelope] (swap! sent conj envelope))})
     :sent sent}))

(defn- init!
  ([n] (init! n {}))
  ([{:keys [node]} opts]
   (let [{:keys [src node_id node_ids msg_id]
          :or {src "c1" node_id "n1" node_ids ["n1"] msg_id 1}} opts]
     (node/handle! node {:src src :dest node_id
                         :body {:type "init" :msg_id msg_id
                                :node_id node_id :node_ids node_ids}}))))

(deftest node-config-fails-closed
  (let [send! (fn [_])]
    (doseq [handlers [nil
                      {"echo" :not-a-function}
                      {:echo echo/handle-echo}
                      {"init" echo/handle-echo}]]
      (is (= :jolt.maelstrom.node/invalid-config
             (:type (ex-data-of
                     #(node/create-node {:handlers handlers :send! send!}))))))
    (is (= :jolt.maelstrom.node/invalid-config
           (:type (ex-data-of
                   #(node/create-node {:handlers echo/handlers
                                       :send! :not-a-function})))))))

;; ---- init ------------------------------------------------------------------

(deftest init-replies-init-ok-and-records-node-identity
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (is (= "n1" (node/node-id node)))
    (is (= ["n1"] (node/node-ids node)))
    (is (= [{:src "n1" :dest "c1" :body {:type "init_ok" :msg_id 1 :in_reply_to 1}}]
           @sent))))

(deftest init-remembers-multiple-node-ids
  (let [{:keys [node] :as n} (new-node)]
    (init! n {:node_ids ["n1" "n2" "n3"]})
    (is (= ["n1" "n2" "n3"] (node/node-ids node)))))

(deftest duplicate-init-fails-closed
  (let [n (new-node)]
    (init! n)
    (let [data (ex-data-of #(init! n))]
      (is (= :jolt.maelstrom.node/already-initialized (:type data)))
      (is (= {:node_id "n1" :node_ids ["n1"]} (:existing data)))
      (is (= {:node_id "n1" :node_ids ["n1"]} (:received data))))))

(deftest concurrent-init-has-exactly-one-winner
  (let [{:keys [sent] :as n} (new-node)
        start (promise)
        attempt (fn []
                  (future
                    @start
                    (try
                      (init! n)
                      :initialized
                      (catch :default error
                        (:type (ex-data error))))))
        a (attempt)
        b (attempt)]
    (deliver start true)
    (is (= #{:initialized :jolt.maelstrom.node/already-initialized}
           (set [@a @b])))
    (is (= 1 (count @sent))
        "only the winning init publishes init_ok")))

(deftest conflicting-init-fails-closed
  (let [n (new-node)]
    (init! n)
    (let [data (ex-data-of #(init! n {:node_id "n2" :node_ids ["n1" "n2"]}))]
      (is (= :jolt.maelstrom.node/already-initialized (:type data)))
      (is (= {:node_id "n1" :node_ids ["n1"]} (:existing data)))
      (is (= {:node_id "n2" :node_ids ["n1" "n2"]} (:received data))))))

(deftest malformed-init-fails-closed
  (let [n (new-node)]
    (is (= :jolt.maelstrom.node/invalid-init
           (:type
            (ex-data-of
             #(node/handle! (:node n)
                            {:src "c1" :dest "n1"
                             :body {:type "init" :msg_id 1
                                    :node_id 42 :node_ids ["n1"]}})))))
    (is (= :jolt.maelstrom.node/invalid-init
           (:type (ex-data-of #(init! n {:node_ids "n1"})))))
    (is (= :jolt.maelstrom.node/invalid-init
           (:type (ex-data-of #(init! n {:node_ids ["n2"]})))))
    (is (= :jolt.maelstrom.node/invalid-init
           (:type (ex-data-of #(init! n {:node_ids ["n1" "n1"]})))))
    (is (= :jolt.maelstrom.node/invalid-init
           (:type
            (ex-data-of
             #(node/handle! (:node n)
                            {:src "c1" :dest "different"
                             :body {:type "init" :msg_id 1
                                    :node_id "n1" :node_ids ["n1"]}})))))
    (is (= :jolt.maelstrom.node/invalid-init
           (:type
            (ex-data-of
             #(node/handle! (:node n)
                            {:src "c1" :dest "n1"
                             :body {:type "init"
                                    :node_id "n1" :node_ids ["n1"]}})))))))

;; ---- malformed envelopes -----------------------------------------------------

(deftest malformed-envelope-fails-closed
  (let [{:keys [node]} (new-node)]
    (doseq [bad [nil
                 {}
                 {:src "c1" :dest "n1"}
                 {:src "c1" :dest "n1" :body {:type 5}}
                 {:src "c1" :dest "n1" :body {:type "echo" :msg_id -1}}
                 {:src "c1" :dest "n1" :body {:type "echo" :msg_id "1"}}
                 {:src 1 :dest "n1" :body {:type "echo"}}
                 {:src "c1" :dest "n1" :body "not-a-map"}]]
      (is (= :jolt.maelstrom.node/invalid-envelope
             (:type (ex-data-of #(node/handle! node bad))))
          (pr-str bad)))))

(deftest non-init-messages-require-initialization-and-correct-destination
  (let [{:keys [node] :as n} (new-node)
        request {:src "c1" :dest "n1"
                 :body {:type "echo" :msg_id 1 :echo "hello"}}]
    (is (= :jolt.maelstrom.node/not-initialized
           (:type (ex-data-of #(node/handle! node request)))))
    (init! n)
    (is (= :jolt.maelstrom.node/wrong-destination
           (:type (ex-data-of
                   #(node/handle! node (assoc request :dest "n2"))))))))

;; ---- echo --------------------------------------------------------------------

(defn- echo-request [msg_id echo-body]
  {:src "c1" :dest "n1" :body (merge {:type "echo" :msg_id msg_id} echo-body)})

(deftest echo-preserves-arbitrary-values-exactly
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (doseq [[idx value] (map-indexed vector
                                     [nil
                                      0
                                      false
                                      ""
                                      {}
                                      []
                                      "hello, 世界 🌍"
                                      {:a [1 {:b [1 2 3]} "x"] :c nil}
                                      (vec (range 50))])]
      (node/handle! node (echo-request (+ 100 idx) {:echo value}))
      (let [reply (last @sent)]
        (is (= "echo_ok" (get-in reply [:body :type])) (pr-str value))
        (is (contains? (:body reply) :echo) (pr-str value))
        (is (= value (get-in reply [:body :echo])) (pr-str value))))))

(deftest echo-distinguishes-missing-echo-from-nil-echo
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (node/handle! node (echo-request 1 {:echo nil}))
    (is (contains? (:body (last @sent)) :echo))
    (is (nil? (get-in (last @sent) [:body :echo])))
    (let [data (ex-data-of #(node/handle! node {:src "c1" :dest "n1"
                                                :body {:type "echo" :msg_id 2}}))]
      (is (= :jolt.maelstrom.echo/missing-echo (:type data)))
      (is (= {:type "echo" :msg_id 2} (:body data))))
    (let [data (ex-data-of #(node/handle! node {:src "c1" :dest "n1"
                                                :body {:type "echo" :echo nil}}))]
      (is (= :jolt.maelstrom.echo/missing-msg-id (:type data))))))

(deftest echo-reply-correlates-exactly-to-the-request
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (node/handle! node (echo-request 42 {:echo "whatever"}))
    (let [reply (last @sent)]
      (is (= "n1" (:src reply)))
      (is (= "c1" (:dest reply)))
      (is (= 42 (get-in reply [:body :in_reply_to]))))))

(deftest malformed-handler-replies-fail-closed
  (doseq [reply [nil {:echo "missing type"}
                  {:type "bad" :msg_id 12}
                  {:type "bad" :in_reply_to 12}]]
    (let [sent (atom [])
          n {:node (node/create-node
                    {:handlers {"bad" (fn [_ _] reply)}
                     :send! (fn [envelope] (swap! sent conj envelope))})
             :sent sent}]
      (init! n)
      (is (= :jolt.maelstrom.node/invalid-reply
             (:type (ex-data-of
                     #(node/handle! (:node n)
                                    {:src "c1" :dest "n1"
                                     :body {:type "bad" :msg_id 2}})))))
      (is (= 1 (count @sent))
          "a malformed handler reply must not reach the transport"))))

(deftest msg-ids-are-unique-and-monotonically-increasing
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (dotimes [i 10]
      (node/handle! node (echo-request (+ 200 i) {:echo i})))
    (let [ids (map (comp :msg_id :body) @sent)]
      (is (= (count ids) (count (set ids))))
      (is (apply < ids)))))

(deftest unknown-type-replies-not-supported-when-msg-id-present
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (node/handle! node {:src "c1" :dest "n1" :body {:type "bogus" :msg_id 7}})
    (let [reply (last @sent)]
      (is (= "error" (get-in reply [:body :type])))
      (is (= 10 (get-in reply [:body :code])))
      (is (= 7 (get-in reply [:body :in_reply_to]))))))

(deftest unknown-type-without-msg-id-sends-nothing
  (let [{:keys [node sent] :as n} (new-node)]
    (init! n)
    (node/handle! node {:src "c1" :dest "n1" :body {:type "bogus"}})
    (is (= 1 (count @sent))
        "only the init reply was sent")))
