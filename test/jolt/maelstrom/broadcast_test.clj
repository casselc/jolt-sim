(ns jolt.maelstrom.broadcast-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.broadcast :as broadcast]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.json-lines :as json-lines]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.maelstrom.history :as history]))

(def ^:private client "c1")
(def ^:private cluster-ids ["n1" "n2" "n3"])
(def ^:private line-topology {"n1" ["n2"] "n2" ["n1" "n3"] "n3" ["n2"]})

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---- cluster construction and driving ----------------------------------------

(defn- build-cluster
  "Builds one ordinary broadcast node per id over a shared memory transport.
  wrap-send!, when supplied, wraps each node's transport send fn -- a
  test-only fault seam for the non-vacuity control."
  ([ids] (build-cluster ids nil))
  ([ids wrap-send!]
   (let [transport (memory/create-transport)
         apps (into {} (map (fn [id] [id (broadcast/create-broadcast)]) ids))
         nodes (into {}
                     (map (fn [id]
                            (let [send-fn (memory/send-fn transport)
                                  send-fn (if wrap-send!
                                            (wrap-send! send-fn)
                                            send-fn)]
                              [id (node/create-node
                                   {:handlers (:handlers (get apps id))
                                    :send! send-fn
                                    :on-reply (:on-reply (get apps id))})])))
                     ids)]
     {:transport transport :nodes nodes :apps apps})))

(defn- client-send!
  "One official client request envelope into a node endpoint."
  [transport dest msg_id body]
  (memory/enqueue! transport
                   {:src client :dest dest :body (assoc body :msg_id msg_id)}))

(defn- drive-to-fixpoint!
  "Deterministic bounded round-robin: in sorted node-id order, take! one
  envelope per node endpoint and feed it to the unchanged node/handle!,
  repeating until a full pass delivers nothing. Throws rather than looping
  forever if no fixpoint is reached within the pass bound."
  [transport nodes]
  (loop [passes 0]
    (when (> passes 100)
      (throw (ex-info "no fixpoint within the pass bound" {:passes passes})))
    (let [delivered (reduce (fn [n id]
                              (if-let [envelope (memory/take! transport id)]
                                (do (node/handle! (get nodes id) envelope)
                                    (inc n))
                                n))
                            0
                            (sort (keys nodes)))]
      (if (zero? delivered)
        passes
        (recur (inc passes))))))

(defn- enqueue-events [transport]
  (filterv #(= :enqueue (:op %)) (:history (memory/snapshot transport))))

(defn- peer-broadcast?
  "History enqueue events carrying a node-to-node broadcast request."
  [event]
  (and (= "broadcast" (get-in event [:envelope :body :type]))
       (contains? (set cluster-ids) (get-in event [:envelope :src]))))

(defn- src-dest [event]
  [(get-in event [:envelope :src]) (get-in event [:envelope :dest])])

(defn- init-and-install!
  "Drives official init and topology envelopes for every cluster node."
  [transport topology]
  (doseq [[i id] (map-indexed vector cluster-ids)]
    (client-send! transport id (inc i)
                  {:type "init" :node_id id :node_ids cluster-ids}))
  (doseq [[i id] (map-indexed vector cluster-ids)]
    (client-send! transport id (+ 10 i)
                  {:type "topology" :topology topology})))

;; ---- the broadcast scenario ----------------------------------------------------

(deftest broadcast-converges-over-line-topology
  (let [{:keys [transport nodes apps]} (build-cluster cluster-ids)]
    (init-and-install! transport line-topology)
    (client-send! transport "n1" 20 {:type "broadcast" :message 42})
    (drive-to-fixpoint! transport nodes)

    ;; All three nodes converge on the one value.
    (doseq [id cluster-ids]
      (is (= [42] (:messages ((:snapshot (get apps id))))) (str id)))
    ;; Topology installed this node's own neighbor list, sorted.
    (is (= {:node_id "n1" :neighbors ["n2"] :messages [42] :pending []}
           ((:snapshot (get apps "n1")))))
    (is (= ["n1" "n3"] (:neighbors ((:snapshot (get apps "n2"))))))

    ;; Forwarding evidence: exactly two peer broadcasts, n1 -> n2 -> n3.
    ;; n1 never sends anything to n3 directly, so n3's copy of 42 must have
    ;; arrived through n2.
    (let [enqueues (enqueue-events transport)
          peer-broadcasts (filterv peer-broadcast? enqueues)]
      (is (= [["n1" "n2"] ["n2" "n3"]] (mapv src-dest peer-broadcasts)))
      (is (empty? (filter #(= ["n1" "n3"] (src-dest %)) enqueues))))

    ;; A client read against n3 returns the converged value, correlated.
    (client-send! transport "n3" 30 {:type "read"})
    (drive-to-fixpoint! transport nodes)
    (let [replies (memory/drain! transport client)]
      (is (= [["n1" "init_ok" 1] ["n2" "init_ok" 2] ["n3" "init_ok" 3]
              ["n1" "topology_ok" 10] ["n2" "topology_ok" 11]
              ["n3" "topology_ok" 12] ["n1" "broadcast_ok" 20]
              ["n3" "read_ok" 30]]
             (mapv (fn [reply]
                     [(:src reply)
                      (get-in reply [:body :type])
                      (get-in reply [:body :in_reply_to])])
                   replies)))
      (is (every? #(pos? (get-in % [:body :msg_id])) replies))
      (is (= [42] (get-in (last replies) [:body :messages]))))

    ;; Peer broadcast_ok responses terminated cleanly: the round-robin
    ;; reached its fixpoint above, no queue retains anything, and no error
    ;; envelope was ever produced (no ping-pong, no not_supported).
    (is (not-any? #(= "error" (get-in % [:envelope :body :type]))
                  (:history (memory/snapshot transport))))
    (is (= {} (:queues (memory/snapshot transport))))
    (is (= :pass (:status (history/check-snapshot (memory/snapshot transport)))))

    ;; A duplicate client broadcast of the same value is acknowledged but
    ;; never refloods: peer broadcast traffic does not grow and no node's
    ;; messages change.
    (client-send! transport "n1" 21 {:type "broadcast" :message 42})
    (drive-to-fixpoint! transport nodes)
    (is (= [["n1" "n2"] ["n2" "n3"]]
           (mapv src-dest (filterv peer-broadcast? (enqueue-events transport))))
        "duplicate broadcast produced no new peer broadcast traffic")
    (doseq [id cluster-ids]
      (is (= [42] (:messages ((:snapshot (get apps id))))) (str id)))
    (doseq [id cluster-ids]
      (is (= [] (:pending ((:snapshot (get apps id))))) (str id)))
    (let [replies (memory/drain! transport client)]
      (is (= [["n1" "broadcast_ok" 21]]
             (mapv (fn [reply]
                     [(:src reply)
                      (get-in reply [:body :type])
                      (get-in reply [:body :in_reply_to])])
                   replies))))
    (is (= {} (:queues (memory/snapshot transport))))
    (is (= :pass (:status (history/check-snapshot (memory/snapshot transport)))))))

(deftest noop-outbound-seam-breaks-the-convergence-evidence
  ;; Non-vacuity control: with a deliberately no-op outbound seam that drops
  ;; every node-to-node broadcast request, the forwarding evidence asserted
  ;; by broadcast-converges-over-line-topology cannot hold -- the value never
  ;; leaves n1, so n3 cannot converge through n2.
  (let [drop-broadcasts (fn [send!]
                          (fn [envelope]
                            (when-not (= "broadcast" (get-in envelope [:body :type]))
                              (send! envelope))))
        {:keys [transport nodes apps]} (build-cluster cluster-ids drop-broadcasts)]
    (init-and-install! transport line-topology)
    (client-send! transport "n1" 20 {:type "broadcast" :message 42})
    (drive-to-fixpoint! transport nodes)
    (is (= [42] (:messages ((:snapshot (get apps "n1")))))
        "the source node still stores the value locally")
    (is (= [] (:messages ((:snapshot (get apps "n2")))))
        "control: n2 does not converge")
    (is (= [] (:messages ((:snapshot (get apps "n3")))))
        "control: n3 does not converge")
    (is (= [] (filterv peer-broadcast? (enqueue-events transport)))
        "control: no n2 -> n3 forwarding exists to evidence")
    (is (= [{:message 42 :neighbor "n2" :msg_id 3
             :status :awaiting-reply}]
           (:pending ((:snapshot (get apps "n1")))))
        "the application retains the dropped delivery for a later retry")
    ;; The transport itself still records a consistent history.
    (is (= :pass (:status (history/check-snapshot (memory/snapshot transport)))))))

(deftest retained-delivery-recovers-a-transient-nonthrowing-drop
  (let [dropped? (atom false)
        drop-first
        (fn [send!]
          (fn [envelope]
            (if (and (= "broadcast" (get-in envelope [:body :type]))
                     (contains? (set cluster-ids) (:src envelope))
                     (compare-and-set! dropped? false true))
              nil
              (send! envelope))))
        {:keys [transport nodes apps]} (build-cluster cluster-ids drop-first)]
    (init-and-install! transport line-topology)
    (client-send! transport "n1" 20 {:type "broadcast" :message 42})
    (drive-to-fixpoint! transport nodes)
    (is (= [42] (:messages ((:snapshot (get apps "n1")))))
        "the source committed locally")
    (is (= [] (:messages ((:snapshot (get apps "n2")))))
        "the first peer delivery was really dropped")
    (is (= 1 (count (:pending ((:snapshot (get apps "n1"))))))
        "the drop did not erase delivery responsibility")

    ((:retry-pending! (get apps "n1")) (get nodes "n1"))
    (drive-to-fixpoint! transport nodes)
    (doseq [id cluster-ids]
      (is (= [42] (:messages ((:snapshot (get apps id))))) (str id))
      (is (= [] (:pending ((:snapshot (get apps id))))) (str id)))
    (is (= :pass (:status (history/check-snapshot (memory/snapshot transport)))))))

(deftest partial-fanout-throw-retains-the-suffix-and-client-retry-recovers
  (let [boom (ex-info "injected fanout failure" {:control true})
        thrown? (atom false)
        throw-once
        (fn [send!]
          (fn [envelope]
            (if (and (= "n1" (:src envelope))
                     (= "n3" (:dest envelope))
                     (= "broadcast" (get-in envelope [:body :type]))
                     (compare-and-set! thrown? false true))
              (throw boom)
              (send! envelope))))
        star-topology {"n1" ["n2" "n3"] "n2" ["n1"] "n3" ["n1"]}
        {:keys [transport nodes apps]} (build-cluster cluster-ids throw-once)]
    (init-and-install! transport star-topology)
    (client-send! transport "n1" 20 {:type "broadcast" :message 42})
    (is (= boom
           (try (drive-to-fixpoint! transport nodes)
                nil
                (catch :default error (ex-cause error))))
        "the first handler attempt preserves the transport cause")
    (is (= #{"n2" "n3"}
           (set (map :neighbor (:pending ((:snapshot (get apps "n1")))))))
        "both the sent prefix and failed suffix remain delivery obligations")

    ;; The client did not receive broadcast_ok, so its ordinary retry drives
    ;; the same pending msg_ids rather than suppressing fanout as a duplicate.
    (client-send! transport "n1" 21 {:type "broadcast" :message 42})
    (drive-to-fixpoint! transport nodes)
    (doseq [id cluster-ids]
      (is (= [42] (:messages ((:snapshot (get apps id))))) (str id)))
    (is (= [] (:pending ((:snapshot (get apps "n1"))))))))

(deftest json-lines-pump-uses-the-broadcast-workload-vocabulary
  (let [app (broadcast/create-broadcast)
        input-lines
        (atom
         ["{\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"init\",\"msg_id\":1,\"node_id\":\"n1\",\"node_ids\":[\"n1\"]}}"
          "{\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"topology\",\"msg_id\":2,\"topology\":{\"n1\":[]}}}"
          "{\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"broadcast\",\"msg_id\":3,\"message\":42}}"
          "{\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"read\",\"msg_id\":4}}"])
        output-lines (atom [])
        n (node/create-node
           {:handlers (:handlers app)
            :on-reply (:on-reply app)
            :send! (json-lines/line-sender #(swap! output-lines conj %))})
        read-line! (fn []
                     (let [line (first @input-lines)]
                       (swap! input-lines #(vec (rest %)))
                       line))]
    (is (nil? (json-lines/pump!
               read-line!
               #(node/handle! n %)
               {:body-key-map (:body-key-map app)})))
    (is (= ["init_ok" "topology_ok" "broadcast_ok" "read_ok"]
           (mapv #(get-in (json-lines/decode-line %) [:body :type])
                 @output-lines)))
    (is (= [42]
           (get-in (json-lines/decode-line (last @output-lines))
                   [:body "messages"])))
    (is (= [42] (:messages ((:snapshot app)))))))

;; ---- node/send! ----------------------------------------------------------------

(defn- bare-node
  "One node with no handlers and captured sends, optionally initialized."
  ([] (bare-node false))
  ([init?]
   (let [sent (atom [])
         n (node/create-node {:handlers {} :send! (fn [e] (swap! sent conj e))})]
     (when init?
       (node/handle! n {:src client :dest "n1"
                        :body {:type "init" :msg_id 1
                               :node_id "n1" :node_ids ["n1"]}}))
     {:node n :sent sent})))

(deftest send-request-fails-closed-before-init
  (let [{:keys [node sent]} (bare-node)]
    (is (= :jolt.maelstrom.node/not-initialized
           (:type (ex-data-of
                   #(node/send! node "n2" {:type "broadcast" :message 1})))))
    (is (= [] @sent))))

(deftest send-request-validates-dest-and-body
  (let [{:keys [node sent]} (bare-node true)]
    (doseq [[dest body] [[nil {:type "broadcast"}]
                         [:n2 {:type "broadcast"}]
                         ["n2" nil]
                         ["n2" "not-a-map"]
                         ["n2" {}]
                         ["n2" {:type 5}]
                         ["n2" {:type "broadcast" :msg_id 7}]
                         ["n2" {:type "broadcast" :in_reply_to 7}]]]
      (is (= :jolt.maelstrom.node/invalid-request
             (:type (ex-data-of #(node/send! node dest body))))
          (pr-str [dest body])))
    (is (= 1 (count @sent))
        "failed requests never reach the transport; only init_ok was sent")))

(deftest send-request-builds-and-sends-the-envelope
  (let [{:keys [node sent]} (bare-node true)
        e1 (node/send! node "n2" {:type "broadcast" :message 42})
        e2 (node/send! node "n3" {:type "read"})]
    ;; init_ok consumed msg_id 1; outbound requests share the same monotonic
    ;; source-local counter.
    (is (= {:src "n1" :dest "n2"
            :body {:type "broadcast" :message 42 :msg_id 2}} e1))
    (is (= {:src "n1" :dest "n3" :body {:type "read" :msg_id 3}} e2))
    (is (= [e1 e2] (subvec @sent 1))
        "the returned envelope is exactly what the transport received")))

(deftest retry-resends-the-exact-pending-envelope
  (let [{:keys [node sent]} (bare-node true)
        request (node/send! node "n2" {:type "broadcast" :message 42})]
    (is (= request (node/retry! node 2)))
    (is (= [request request] (subvec @sent 1)))
    (doseq [message-id [nil -1 1.5]]
      (is (= :jolt.maelstrom.node/invalid-request-id
             (:type (ex-data-of #(node/retry! node message-id))))))
    (is (= :jolt.maelstrom.node/unknown-request
           (:type (ex-data-of #(node/retry! node 99)))))))

;; ---- inbound responses ---------------------------------------------------------

(deftest reply-observer-config-fails-closed
  (is (= :jolt.maelstrom.node/invalid-config
         (:type (ex-data-of
                 #(node/create-node {:handlers {}
                                     :send! (fn [_])
                                     :on-reply :not-a-function}))))))

(deftest responses-invoke-the-observer-exactly-once-and-are-never-answered
  (let [sent (atom [])
        observed (atom [])
        n (node/create-node {:handlers {}
                             :send! (fn [e] (swap! sent conj e))
                             :on-reply (fn [node envelope context]
                                         (swap! observed conj
                                                [node envelope context]))})]
    (node/handle! n {:src client :dest "n1"
                     :body {:type "init" :msg_id 1
                            :node_id "n1" :node_ids ["n1"]}})
    (let [request (node/send! n "n2" {:type "broadcast" :message 42}
                              [:delivery 42 "n2"])
          response {:src "n2" :dest "n1"
                    :body {:type "broadcast_ok" :msg_id 9 :in_reply_to 2}}]
      (is (= 2 (get-in request [:body :msg_id])))
      (is (nil? (node/handle! n response)))
      (is (= 1 (count @observed)) "one response, one observation")
      (is (= [n response [:delivery 42 "n2"]] (first @observed))
          "the observer receives (node response-envelope request-context)")
      (is (nil? (node/handle! n response))
          "a same-source duplicate response is idempotently consumed")
      (is (= 1 (count @observed))
          "a duplicate response cannot invoke the observer twice")
      (is (nil? (node/retry! n 2))
          "retry racing an already-consumed acknowledgment is idempotent")
      (is (= 2 (count @sent))
          "a completed request is not emitted again"))
    (let [request (node/send! n "n2" {:type "read"})]
      (is (= 3 (get-in request [:body :msg_id])))
      (is (= :wrong-source
             (:reason
              (ex-data-of
               #(node/handle! n {:src "n3" :dest "n1"
                                  :body {:type "read_ok"
                                         :msg_id 10 :in_reply_to 3}})))))
      (is (= 1 (count @observed)))
      (node/handle! n {:src "n2" :dest "n1"
                       :body {:type "read_ok" :msg_id 11 :in_reply_to 3}})
      (is (= 2 (count @observed))))
    (is (= :unknown-request
           (:reason
            (ex-data-of
             #(node/handle! n {:src "n2" :dest "n1"
                                :body {:type "bogus_ok"
                                       :msg_id 12 :in_reply_to 999}})))))
    (is (= 3 (count @sent))
        "only init_ok and the two requests were sent; responses get no reply")
    ;; A malformed in_reply_to is rejected by envelope validation before the
    ;; observer is ever consulted.
    (is (= :jolt.maelstrom.node/invalid-envelope
           (:type (ex-data-of
                   #(node/handle! n {:src "n2" :dest "n1"
                                     :body {:type "broadcast_ok"
                                            :msg_id 11 :in_reply_to -1}})))))
    (is (= 2 (count @observed)))
    ;; An ordinary request (no :in_reply_to) keeps its existing behavior: it
    ;; is not a response, the observer is not consulted, and the
    ;; not_supported reply still goes out for an unregistered type.
    (node/handle! n {:src "n2" :dest "n1" :body {:type "bogus" :msg_id 12}})
    (is (= 2 (count @observed)))
    (is (= "error" (get-in (last @sent) [:body :type])))
    (is (= 12 (get-in (last @sent) [:body :in_reply_to])))))

(deftest responses-are-consumed-silently-without-an-observer
  (let [{:keys [node sent]} (bare-node true)]
    (node/send! node "n2" {:type "read"})
    (is (nil? (node/handle! node {:src "n2" :dest "n1"
                                  :body {:type "broadcast_ok"
                                         :msg_id 9 :in_reply_to 2}})))
    (is (= 2 (count @sent))
        "init_ok and the request were sent; the response got no reply")))

(deftest response-tombstones-are-physically-bounded
  (let [{:keys [node]} (bare-node true)]
    (doseq [offset (range 1025)]
      (let [request (node/send! node "n2" {:type "read"})
            request-id (get-in request [:body :msg_id])]
        (node/handle! node
                      {:src "n2" :dest "n1"
                       :body {:type "read_ok"
                              :msg_id (+ 10000 offset)
                              :in_reply_to request-id}})))
    (let [state @(get node :jolt.maelstrom.node/state)
          newest-id 1026]
      (is (= 1024 (count (:completed-order state))))
      (is (= 1024 (count (:completed-requests state))))
      (is (vector? (:completed-order state))
          "trimming materializes a fresh bounded vector")
      (is (nil? (node/retry! node newest-id)))
      (is (nil? (node/handle! node
                              {:src "n2" :dest "n1"
                               :body {:type "read_ok" :msg_id 20000
                                      :in_reply_to newest-id}})))
      (is (= :unknown-request
             (:reason
              (ex-data-of
               #(node/handle! node
                              {:src "n2" :dest "n1"
                               :body {:type "read_ok" :msg_id 20001
                                      :in_reply_to 2}}))))))))

(deftest synchronous-peer-ack-retires-the-broadcast-obligation
  (let [node* (atom nil)
        sent (atom [])
        app (broadcast/create-broadcast)
        send-fn
        (fn [envelope]
          (swap! sent conj envelope)
          (when (and (= "n2" (:dest envelope))
                     (= "broadcast" (get-in envelope [:body :type])))
            (node/handle! @node*
                          {:src "n2" :dest "n1"
                           :body {:type "broadcast_ok"
                                  :msg_id 900
                                  :in_reply_to
                                  (get-in envelope [:body :msg_id])}})))
        n (node/create-node {:handlers (:handlers app)
                             :send! send-fn
                             :on-reply (:on-reply app)})]
    (reset! node* n)
    (node/handle! n {:src client :dest "n1"
                     :body {:type "init" :msg_id 1
                            :node_id "n1" :node_ids ["n1" "n2"]}})
    (node/handle! n {:src client :dest "n1"
                     :body {:type "topology" :msg_id 2
                            :topology {"n1" ["n2"] "n2" ["n1"]}}})
    (node/handle! n {:src client :dest "n1"
                     :body {:type "broadcast" :msg_id 3 :message 42}})
    (is (= [] (:pending ((:snapshot app))))
        "the synchronous response removes the sentinel before send! returns")
    (is (= 1 (count (filter #(= "broadcast"
                                (get-in % [:body :type]))
                            @sent)))
        "the acknowledged obligation is not resurrected and resent")
    (is (= [42] (:messages ((:snapshot app)))))))

(deftest rejected-or-mismatched-peer-replies-preserve-a-fresh-retry
  (doseq [[reply-body expected-type]
          [[{:type "error" :code 10 :text "not supported"}
            :jolt.maelstrom.broadcast/delivery-rejected]
           [{:type "read_ok" :messages []}
            :jolt.maelstrom.broadcast/unexpected-reply]]]
    (let [sent (atom [])
          app (broadcast/create-broadcast)
          n (node/create-node {:handlers (:handlers app)
                               :send! #(swap! sent conj %)
                               :on-reply (:on-reply app)})]
      (node/handle! n {:src client :dest "n1"
                       :body {:type "init" :msg_id 1
                              :node_id "n1" :node_ids ["n1" "n2"]}})
      (node/handle! n {:src client :dest "n1"
                       :body {:type "topology" :msg_id 2
                              :topology {"n1" ["n2"] "n2" ["n1"]}}})
      (node/handle! n {:src client :dest "n1"
                       :body {:type "broadcast" :msg_id 3 :message 42}})
      (let [first-request (first (filter #(= "n2" (:dest %)) @sent))
            first-id (get-in first-request [:body :msg_id])
            error-data
            (ex-data-of
             #(node/handle! n
                            {:src "n2" :dest "n1"
                             :body (assoc reply-body
                                          :msg_id 900
                                          :in_reply_to first-id)}))]
        (is (= expected-type (:type error-data)) (pr-str reply-body))
        (is (= [{:message 42 :neighbor "n2" :msg_id nil
                 :status :allocating}]
               (:pending ((:snapshot app))))
            "a terminal negative response does not erase delivery")
        ((:retry-pending! app) n)
        (let [retry-request (last @sent)
              retry-id (get-in retry-request [:body :msg_id])]
          (is (= {:type "broadcast" :message 42}
                 (dissoc (:body retry-request) :msg_id)))
          (is (integer? retry-id))
          (is (not= first-id retry-id)
              "the retry uses a fresh correlation ID"))))))

;; ---- broadcast handler body validation ------------------------------------------

(defn- broadcast-node
  "One initialized broadcast node with captured sends."
  []
  (let [sent (atom [])
        app (broadcast/create-broadcast)
        n (node/create-node {:handlers (:handlers app)
                             :send! (fn [e] (swap! sent conj e))
                             :on-reply (:on-reply app)})]
    (node/handle! n {:src client :dest "n1"
                     :body {:type "init" :msg_id 1
                            :node_id "n1" :node_ids ["n1"]}})
    {:node n :app app :sent sent}))

(defn- request!
  [node body]
  (node/handle! node {:src client :dest "n1" :body body}))

(deftest topology-validation-fails-closed
  (let [{:keys [node app sent]} (broadcast-node)]
    (doseq [body [{:type "topology" :msg_id 2}
                  {:type "topology" :msg_id 2 :topology nil}
                  {:type "topology" :msg_id 2 :topology "n1"}
                  {:type "topology" :msg_id 2 :topology {1 []}}
                  {:type "topology" :msg_id 2 :topology {"n1" "n2"}}
                  {:type "topology" :msg_id 2 :topology {"n1" [2]}}
                  {:type "topology" :msg_id 2 :topology {"n2" []}}
                  {:type "topology" :msg_id 2 :topology {"n1" ["n1"]}}
                  {:type "topology" :msg_id 2 :topology {"n1" ["n2"]}}
                  {:type "topology" :msg_id 2 :topology {"n1" ["n1" "n1"]}}]]
      (is (= :jolt.maelstrom.broadcast/invalid-topology
             (:type (ex-data-of #(request! node body))))
          (pr-str body)))
    (is (= :jolt.maelstrom.broadcast/missing-msg-id
           (:type (ex-data-of
                   #(request! node {:type "topology"
                                    :topology {"n1" ["n2"]}})))))
    ;; Failed topologies install nothing.
    (is (= [] (:neighbors ((:snapshot app)))))
    (is (= 1 (count @sent)) "no reply and no flood for rejected topologies")
    ;; A valid topology still works afterwards.
    (request! node {:type "topology" :msg_id 3 :topology {"n1" []}})
    (is (= [] (:neighbors ((:snapshot app)))))
    (is (= "topology_ok" (get-in (last @sent) [:body :type])))
    (is (= 3 (get-in (last @sent) [:body :in_reply_to])))
    ;; Exact request retry is idempotent; a conflicting replacement fails.
    (request! node {:type "topology" :msg_id 4 :topology {"n1" []}})
    (is (= 4 (get-in (last @sent) [:body :in_reply_to]))))
  (let [app (broadcast/create-broadcast)
        n (node/create-node {:handlers (:handlers app) :send! (fn [_])})]
    (node/handle! n {:src client :dest "n1"
                     :body {:type "init" :msg_id 1
                            :node_id "n1" :node_ids ["n1" "n2"]}})
    (node/handle! n {:src client :dest "n1"
                     :body {:type "topology" :msg_id 2
                            :topology {"n1" ["n2"] "n2" ["n1"]}}})
    (is (= :jolt.maelstrom.broadcast/conflicting-topology
           (:type
            (ex-data-of
             #(node/handle! n {:src client :dest "n1"
                               :body {:type "topology" :msg_id 3
                                      :topology {"n1" [] "n2" []}}})))))))

(deftest conflicting-concurrent-topologies-install-exactly-one
  (let [sent (atom [])
        app (broadcast/create-broadcast)
        n (node/create-node {:handlers (:handlers app)
                             :send! #(swap! sent conj %)})
        start (promise)
        attempt (fn [message-id topology]
                  (future
                    @start
                    (try
                      (node/handle! n {:src client :dest "n1"
                                       :body {:type "topology"
                                              :msg_id message-id
                                              :topology topology}})
                      {:status :ok}
                      (catch :default error
                        {:status :error :data (ex-data error)}))))
        _ (node/handle! n {:src client :dest "n1"
                           :body {:type "init" :msg_id 1
                                  :node_id "n1" :node_ids ["n1" "n2"]}})
        first-attempt (attempt 2 {"n1" ["n2"] "n2" ["n1"]})
        second-attempt (attempt 3 {"n1" [] "n2" []})]
    (deliver start true)
    (let [results [(deref first-attempt 5000 :timeout)
                   (deref second-attempt 5000 :timeout)]
          topology-replies
          (filterv #(= "topology_ok" (get-in % [:body :type])) @sent)]
      (is (= 1 (count (filter #(= :ok (:status %)) results))) (pr-str results))
      (is (= [:jolt.maelstrom.broadcast/conflicting-topology]
             (mapv #(get-in % [:data :type])
                   (filter #(= :error (:status %)) results)))
          (pr-str results))
      (is (= 1 (count topology-replies)))
      (is (contains? #{[] ["n2"]} (:neighbors ((:snapshot app))))))))

(deftest broadcast-validation-fails-closed
  (let [{:keys [node app sent]} (broadcast-node)]
    (is (= :jolt.maelstrom.broadcast/missing-msg-id
           (:type (ex-data-of #(request! node {:type "broadcast" :message 1})))))
    (is (= :jolt.maelstrom.broadcast/topology-not-installed
           (:type (ex-data-of #(request! node {:type "broadcast" :msg_id 2})))))
    (request! node {:type "topology" :msg_id 3 :topology {"n1" []}})
    (is (= :jolt.maelstrom.broadcast/missing-message
           (:type (ex-data-of #(request! node {:type "broadcast" :msg_id 4})))))
    (doseq [message [nil false 1.5 "1" :one [1] #{1} {:value 1}
                     (atom {:x 1}) (fn [] :x)]]
      (is (= :jolt.maelstrom.broadcast/unsupported-message
             (:type (ex-data-of
                     #(request! node {:type "broadcast" :msg_id 5
                                      :message message}))))
          (pr-str message)))
    ;; Rejected messages are neither stored nor flooded nor answered.
    (is (= [] (:messages ((:snapshot app)))))
    (is (= 2 (count @sent)))))

(deftest read-validation-fails-closed
  (let [{:keys [node]} (broadcast-node)]
    (is (= :jolt.maelstrom.broadcast/missing-msg-id
           (:type (ex-data-of #(request! node {:type "read"})))))))

(deftest read-returns-integer-messages-in-numeric-order
  (let [{:keys [node app sent]} (broadcast-node)
        values [10 2 -1 0 42]]
    (request! node {:type "topology" :msg_id 2 :topology {"n1" []}})
    (doseq [[i value] (map-indexed vector values)]
      (request! node {:type "broadcast" :msg_id (+ 10 i) :message value}))
    (request! node {:type "read" :msg_id 99})
    (let [expected [-1 0 2 10 42]]
      (is (= expected (get-in (last @sent) [:body :messages])))
      (is (= 99 (get-in (last @sent) [:body :in_reply_to])))
      (is (= expected (:messages ((:snapshot app)))))
      (is (= "n1" (:node_id ((:snapshot app))))))))

(deftest snapshot-is-immutable-and-exposes-no-atom
  (let [{:keys [node app]} (broadcast-node)]
    (request! node {:type "topology" :msg_id 2 :topology {"n1" []}})
    (request! node {:type "broadcast" :msg_id 3 :message 42})
    (let [snap ((:snapshot app))]
      (request! node {:type "broadcast" :msg_id 4 :message 43})
      (is (= {:node_id "n1" :neighbors [] :messages [42] :pending []} snap)
          "the snapshot is frozen at capture time")
      (is (= [42 43] (:messages ((:snapshot app)))))
      (is (not-any? fn? (tree-seq coll? seq snap))
          "no function or atom leaks into the snapshot data"))))
