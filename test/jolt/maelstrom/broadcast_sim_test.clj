(ns jolt.maelstrom.broadcast-sim-test
  "Focused sim-lane tests for the input-capable three-node line-topology
  Broadcast scenario in jolt.maelstrom.fixtures.broadcast-scenario.

  The scenario runs under the sim controller only on a sim-enabled Jolt
  image; on an ordinary image jolt.sim.runtime/available? is false and the
  scenario tests assert that guard instead, exactly like the Echo sim lane.
  The input contract, the defsim var metadata, and the withheld-retry
  non-vacuity control use no controller services and run on every image."
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.fixtures.broadcast-scenario :as fixture]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.activity :as activity]
            [jolt.sim.maelstrom.history :as history]
            [jolt.sim.runtime :as rt]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- reply-triple
  [reply]
  [(:src reply) (get-in reply [:body :type]) (get-in reply [:body :in_reply_to])])

(def ^:private official-client-replies
  [["n1" "init_ok" 1] ["n2" "init_ok" 2] ["n3" "init_ok" 3]
   ["n1" "topology_ok" 10] ["n2" "topology_ok" 11] ["n3" "topology_ok" 12]
   ["n1" "broadcast_ok" 20]
   ["n3" "read_ok" 30]])

(def ^:private activity-run-id (byte-array (range 16)))
(def ^:private activity-path-counter (atom 0))

(defn- fresh-activity-path
  []
  (str (System/getProperty "java.io.tmpdir")
       "/jolt-broadcast-activity-"
       (swap! activity-path-counter inc) "-"
       (System/nanoTime) ".journal"))

(defn- peer-broadcast-enqueues
  [obs src dest]
  (filterv #(and (= :enqueue (:op %))
                 (= src (get-in % [:envelope :src]))
                 (= dest (get-in % [:envelope :dest]))
                 (= "broadcast" (get-in % [:envelope :body :type])))
           (get-in obs [:transport :history])))

(defn- run-with-activity
  [input]
  (let [events (atom [])
        result (with-redefs [activity/emit! #(swap! events conj %)]
                 (:result (fixture/broadcast-partition-heal {} input)))]
    {:result result :events @events}))

;; ---- scenario declaration and input contract (any image) ---------------------

(deftest scenario-declares-the-input-capable-defsim-contract
  (is (true? (:jolt.sim/scenario (meta #'fixture/broadcast-partition-heal))))
  (is (true? (:jolt.sim/accepts-input (meta #'fixture/broadcast-partition-heal)))))

(deftest input-validation-fails-closed
  (doseq [bad [nil
               "not-a-map"
               {}
               {:message 42}
               {:partition-links []}
               {:message 42 :partition-links [] :extra true}
               {:message "42" :partition-links []}
               {:message 42.0 :partition-links []}
               {:message true :partition-links []}
               {:message 42 :partition-links nil}
               {:message 42 :partition-links "n2-n3"}
               {:message 42 :partition-links [["n3" "n2"]]}
               {:message 42 :partition-links [["n1" "n2"]]}
               {:message 42 :partition-links [["n2" "n3"] ["n1" "n2"]]}
               {:message 42 :partition-links [["n2" "n3" "n1"]]}]]
    (is (= :jolt.maelstrom.fixtures.broadcast-scenario/invalid-input
           (:type (ex-data-of #(fixture/validate-input! bad))))
        (pr-str bad)))
  (is (nil? (fixture/validate-input! {:message 42 :partition-links []})))
  (is (nil? (fixture/validate-input! {:message -7 :partition-links [["n2" "n3"]]}))))

(deftest scenario-rejects-invalid-input-before-building-a-cluster
  (if (rt/available?)
    (do
      (is (= :jolt.maelstrom.fixtures.broadcast-scenario/invalid-input
             (:type (ex-data-of #(fixture/broadcast-partition-heal
                                  {} {:message "42" :partition-links []})))))
      (is (= :jolt.maelstrom.fixtures.broadcast-scenario/invalid-input
             (:type (ex-data-of #(fixture/broadcast-partition-heal {}))))
          "the one-argument arity passes nil input, which is invalid here"))
    (is (false? (rt/available?)))))

;; ---- healthy run ---------------------------------------------------------------

(deftest broadcast-sim-healthy-converges
  (if (rt/available?)
    (let [obs (:result (fixture/broadcast-partition-heal
                        {} {:message 42 :partition-links []}))]
      ;; All three nodes converge on the one value with empty pending, at the
      ;; pre-heal observation and at the end.
      (doseq [id fixture/cluster-ids]
        (is (= [42] (get-in obs [:phases :pre-heal id :messages])) (str id))
        (is (= [] (get-in obs [:phases :pre-heal id :pending])) (str id))
        (is (= [42] (get-in obs [:phases :final id :messages])) (str id))
        (is (= [] (get-in obs [:phases :final id :pending])) (str id)))
      ;; No link was selected: nothing was dropped and retry was a no-op.
      (is (= {:records [] :dropped-total 0} (:drops obs)))
      (is (= {"n1" [] "n2" [] "n3" []} (:retry obs)))
      ;; The official client conversation, in order, with n3's read_ok last
      ;; and correlated to the read's msg_id.
      (is (= official-client-replies
             (mapv reply-triple (:client-replies obs))))
      (is (every? #(pos? (get-in % [:body :msg_id])) (:client-replies obs)))
      (is (= "n3" (:src (last (:client-replies obs)))))
      (is (= [42] (get-in (last (:client-replies obs)) [:body :messages])))
      ;; Every queue is empty, the external transport history checker passes,
      ;; and no error envelope was ever produced.
      (is (= {} (get-in obs [:transport :queues])))
      (is (= :pass (:status (history/check-snapshot (:transport obs)))))
      (is (not-any? #(= "error" (get-in % [:envelope :body :type]))
                    (get-in obs [:transport :history])))
      (is (empty? (peer-broadcast-enqueues obs "n1" "n3"))
          "the line topology never bypasses n2")
      ;; The returned observations are immutable data only.
      (is (not-any? fn? (tree-seq coll? seq obs))))
    (is (false? (rt/available?)))))

;; ---- partition, heal, real retry ------------------------------------------------

(deftest broadcast-sim-partition-retry-converges
  (if (rt/available?)
    (let [obs (:result (fixture/broadcast-partition-heal
                        {} {:message 42 :partition-links [["n2" "n3"]]}))
          pending-pair (first (get-in obs [:phases :pre-heal "n2" :pending]))
          pending-id (:msg_id pending-pair)]
      ;; Pre-heal nonconvergence: n1 and n2 carry the message, n3 does not.
      (is (= [42] (get-in obs [:phases :pre-heal "n1" :messages])))
      (is (= [42] (get-in obs [:phases :pre-heal "n2" :messages])))
      (is (= [] (get-in obs [:phases :pre-heal "n3" :messages])))
      ;; n1 retired its own obligation; n2 retains the exact pending pair and
      ;; its application msg_id; n3 never saw the message.
      (is (= [] (get-in obs [:phases :pre-heal "n1" :pending])))
      (is (= {:message 42 :neighbor "n3" :status :awaiting-reply}
             (dissoc pending-pair :msg_id)))
      (is (and (integer? pending-id) (pos? pending-id)))
      (is (= [] (get-in obs [:phases :pre-heal "n3" :pending])))
      ;; Exactly one node-to-node envelope was dropped: the n2 -> n3
      ;; broadcast carrying message 42 with n2's pending msg_id.
      (is (= 1 (get-in obs [:drops :dropped-total])))
      (is (= [{:src "n2" :dest "n3"
               :body {:type "broadcast" :message 42 :msg_id pending-id}}]
             (get-in obs [:drops :records])))
      ;; Healing and the real retry-pending! resend the same application
      ;; msg_id; the evidence still shows the pair awaiting its reply.
      (is (= {"n1" [] "n2" [pending-pair] "n3" []} (:retry obs)))
      ;; The transport history confirms the retry reused the exact msg_id:
      ;; precisely one n2 -> n3 envelope was ever enqueued, after the heal.
      (let [n2-to-n3-enqueues
            (filterv #(and (= :enqueue (:op %))
                           (= "n2" (get-in % [:envelope :src]))
                           (= "n3" (get-in % [:envelope :dest])))
                     (get-in obs [:transport :history]))]
        (is (= 1 (count n2-to-n3-enqueues))
            "the retried n2 -> n3 broadcast is the only one ever enqueued")
        (is (= pending-id
               (get-in (first n2-to-n3-enqueues) [:envelope :body :msg_id]))
            "the retry reused the exact application msg_id"))
      ;; After the retry fixpoint every node converges and pending is empty.
      (doseq [id fixture/cluster-ids]
        (is (= [42] (get-in obs [:phases :final id :messages])) (str id))
        (is (= [] (get-in obs [:phases :final id :pending])) (str id)))
      ;; The official client conversation, including n3's correlated read_ok.
      (is (= official-client-replies
             (mapv reply-triple (:client-replies obs))))
      (is (= "n3" (:src (last (:client-replies obs)))))
      (is (= 30 (get-in (last (:client-replies obs)) [:body :in_reply_to])))
      (is (= [42] (get-in (last (:client-replies obs)) [:body :messages])))
      ;; Every queue is empty, the external history checker passes, and no
      ;; error envelope was ever produced.
      (is (= {} (get-in obs [:transport :queues])))
      (is (= :pass (:status (history/check-snapshot (:transport obs)))))
      (is (not-any? #(= "error" (get-in % [:envelope :body :type]))
                    (get-in obs [:transport :history])))
      (is (empty? (peer-broadcast-enqueues obs "n1" "n3"))
          "the partition never creates a topology-bypassing request")
      (is (not-any? fn? (tree-seq coll? seq obs))))
    (is (false? (rt/available?)))))

;; ---- gate and semantic activity controls ------------------------------------

(deftest selected-undirected-link-gates-requests-responses-not-client-traffic
  (let [{:keys [transport nodes gate]} (fixture/build-world [["n2" "n3"]])]
    ;; Initialize both endpoints. Their client-bound init_ok replies must pass
    ;; through the active gate unchanged.
    (doseq [[id msg-id] (map vector fixture/cluster-ids [1 2 3])]
      (memory/enqueue! transport
                       {:src fixture/client-id :dest id
                        :body {:type "init" :msg_id msg-id
                               :node_id id :node_ids fixture/cluster-ids}}))
    (fixture/drive-to-fixpoint! transport nodes)
    (is (= [["n1" "init_ok" 1]
            ["n2" "init_ok" 2]
            ["n3" "init_ok" 3]]
           (mapv reply-triple (memory/drain! transport fixture/client-id))))

    ;; Install the ordinary topology through the public request path before
    ;; asking the real Broadcast handler to produce a peer response.
    (doseq [[id msg-id] (map vector fixture/cluster-ids [10 11 12])]
      (memory/enqueue! transport
                       {:src fixture/client-id :dest id
                        :body {:type "topology" :msg_id msg-id
                               :topology fixture/line-topology}}))
    (fixture/drive-to-fixpoint! transport nodes)
    (is (= [["n1" "topology_ok" 10]
            ["n2" "topology_ok" 11]
            ["n3" "topology_ok" 12]]
           (mapv reply-triple (memory/drain! transport fixture/client-id))))

    ;; A real node request is blocked in the reverse n3 -> n2 direction,
    ;; regardless of its non-Broadcast body type.
    (node/send! (get nodes "n3") "n2" {:type "probe"})

    ;; Deliver a peer request directly to n2 so its real response path emits a
    ;; broadcast_ok over n2 -> n3. The response must also be gated.
    (memory/enqueue! transport
                     {:src "n3" :dest "n2"
                      :body {:type "broadcast" :msg_id 77 :message 9}})
    (fixture/drive-to-fixpoint! transport nodes)
    (let [records (:records (fixture/drop-evidence gate))]
      (is (= 2 (count records)))
      (is (= [["n3" "n2" "probe"] ["n2" "n3" "broadcast_ok"]]
             (mapv (fn [record]
                     [(:src record) (:dest record)
                      (get-in record [:body :type])])
                   records)))
      (is (= 77 (get-in (second records) [:body :in_reply_to]))))
    (is (= {} (:queues (memory/snapshot transport))))))

(deftest activity-milestones-are-truthful-bounded-and-match-observations
  (if (rt/available?)
    (let [{healthy :result healthy-events :events}
          (run-with-activity {:message 42 :partition-links []})
          {partitioned :result partition-events :events}
          (run-with-activity
           {:message 42 :partition-links [["n2" "n3"]]})
          healthy-tags (mapv first healthy-events)
          partition-tags (mapv first partition-events)
          payload (fn [events tag]
                    (nth (first (filter #(= tag (first %)) events)) 3))]
      (is (= [:jolt.maelstrom.broadcast/cluster-ready
              :jolt.maelstrom.broadcast/post-retry-state-observed
              :jolt.maelstrom.broadcast/read-observed]
             healthy-tags))
      (is (= [:jolt.maelstrom.broadcast/cluster-ready
              :jolt.maelstrom.broadcast/link-partitioned
              :jolt.maelstrom.broadcast/delivery-dropped
              :jolt.maelstrom.broadcast/link-healed
              :jolt.maelstrom.broadcast/delivery-retried
              :jolt.maelstrom.broadcast/post-retry-state-observed
              :jolt.maelstrom.broadcast/read-observed]
             partition-tags))
      (is (every? #(and (vector? %) (= 4 (count %))
                        (nil? (nth % 1)) (nil? (nth % 2))
                        (map? (nth % 3))
                        (< (count (pr-str %)) 1024))
                  (concat healthy-events partition-events)))
      (is (= {:node-ids fixture/cluster-ids
              :partition-links []}
             (payload healthy-events
                      :jolt.maelstrom.broadcast/cluster-ready)))
      (is (= {:node-ids fixture/cluster-ids
              :partition-links [["n2" "n3"]]}
             (payload partition-events
                      :jolt.maelstrom.broadcast/cluster-ready)))
      (let [dropped (payload partition-events
                             :jolt.maelstrom.broadcast/delivery-dropped)
            retried (payload partition-events
                             :jolt.maelstrom.broadcast/delivery-retried)
            converged (payload partition-events
                               :jolt.maelstrom.broadcast/post-retry-state-observed)
            read (payload partition-events
                          :jolt.maelstrom.broadcast/read-observed)]
        (is (= (:dropped-total (:drops partitioned))
               (:dropped-total dropped)))
        (is (= (get-in partitioned [:drops :records 0 :body :msg_id])
               (:msg_id dropped)
               (first (get-in retried [:msg_ids "n2"]))))
        (is (= {"n1" 1 "n2" 1 "n3" 1}
               (:message-counts converged)))
        (is (= {"n1" 0 "n2" 0 "n3" 0}
               (:pending-counts converged)))
        (is (= {:node-id "n3" :in_reply_to 30 :message-count 1} read)))
      (is (= (get-in healthy [:phases :final])
             (get-in partitioned [:phases :final]))
          "both regimes reach the same public terminal application state"))
    (is (false? (rt/available?)))))

(deftest large-valid-message-does-not-poison-real-activity-observer
  (if (rt/available?)
    (let [message (read-string (apply str (repeat 17000 "9")))
          observer (activity/open-observer!
                    {:path (fresh-activity-path)
                     :run-id activity-run-id})
          result (activity/call-with-observer
                  observer
                  #(fixture/broadcast-partition-heal
                    {} {:message message :partition-links []}))
          closed (activity/close-observer! observer)]
      (is (= message
             (get-in result [:result :phases :final "n3" :messages 0])))
      (is (= :healthy (:health closed)))
      (is (nil? (:failure closed)))
      (is (= 3 (:accepted closed)))
      (is (= 3 (:sequence closed))))
    (is (false? (rt/available?)))))

;; ---- non-vacuity control ---------------------------------------------------------

(deftest withheld-retry-cannot-satisfy-convergence
  ;; Non-vacuity control, requiring no sim controller: the same real cluster
  ;; construction and driving machinery and the same partition, but with the
  ;; application retry withheld. The final convergence and pending-empty
  ;; obligations asserted of the scenario cannot be satisfied without it.
  ;; Nothing here reimplements Broadcast semantics -- the real applications
  ;; and nodes are observed directly.
  (let [{:keys [transport nodes apps gate]} (fixture/build-world [["n2" "n3"]])]
    (fixture/enqueue-official-openers! transport 42)
    (fixture/drive-to-fixpoint! transport nodes)
    ;; Retry withheld: the value reached n2 but can go no further.
    (is (= [42] (:messages ((:snapshot (get apps "n1"))))))
    (is (= [42] (:messages ((:snapshot (get apps "n2"))))))
    (is (= [] (:messages ((:snapshot (get apps "n3")))))
        "control: without retry-pending! n3 never converges")
    (let [pending (first (:pending ((:snapshot (get apps "n2")))))]
      (is (= {:message 42 :neighbor "n3" :status :awaiting-reply}
             (dissoc pending :msg_id)))
      (is (and (integer? (:msg_id pending)) (pos? (:msg_id pending)))
        "control: without retry-pending! n2's obligation never retires"))
    ;; No amount of extra driving helps: the dropped delivery now exists only
    ;; as the application's retained retry obligation.
    (is (= 0 (fixture/drive-to-fixpoint! transport nodes))
        "control: the withheld-retry world is already at its fixpoint")
    (is (= [] (:messages ((:snapshot (get apps "n3"))))))
    (is (= 1 (count (:pending ((:snapshot (get apps "n2")))))))
    ;; The gate recorded the same single drop the scenario evidences.
    (let [evidence (fixture/drop-evidence gate)]
      (is (= 1 (:dropped-total evidence)))
      (is (= {:src "n2" :dest "n3"}
             (select-keys (first (:records evidence)) [:src :dest])))
      (is (= {:type "broadcast" :message 42}
             (dissoc (:body (first (:records evidence))) :msg_id))))
    ;; The transport's own evidence stays consistent; the failure to converge
    ;; is purely the withheld application retry.
    (is (= :pass (:status (history/check-snapshot (memory/snapshot transport)))))))
