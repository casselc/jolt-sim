(ns jolt.sim.maelstrom.echo-evidence-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.maelstrom.echo-evidence :as evidence]
            [jolt.sim.trace :as trace]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---- real-transport round-trip fixture ---------------------------------------

(def ^:private node-id "n1")
(def ^:private client-id "c1")

(def ^:private unicode-payload
  {"greeting" "héllo, 世界 🌍"
   "language" "日本語"
   "nested" {"letters" "ελληνικά"
             "list" [" 한글 " "português" 42 nil false]}
   "emoji" "🚀🎉"})

(def ^:private canonical-unicode-payload
  (trace/canonical-value unicode-payload))

(defn- roundtrip-snapshot
  "Drives a real init + echo round trip over a fresh in-memory transport using
  the unchanged node/echo handlers, then delivers reply-count replies off the
  client endpoint in FIFO order (0, 1, or 2). Returns the transport snapshot."
  ([payload reply-count]
   (roundtrip-snapshot payload reply-count 1 2))
  ([payload reply-count init-msg-id echo-msg-id]
   (let [transport (memory/create-transport)
         n (node/create-node {:handlers echo/handlers
                              :send! (memory/send-fn transport)})]
     (memory/enqueue! transport {:src client-id :dest node-id
                                 :body {:type "init" :msg_id init-msg-id
                                        :node_id node-id :node_ids [node-id]}})
     (memory/enqueue! transport {:src client-id :dest node-id
                                 :body {:type "echo" :msg_id echo-msg-id
                                        :echo payload}})
     (loop []
       (when-some [inbound (memory/take! transport node-id)]
         (node/handle! n inbound)
         (recur)))
     (dotimes [_ reply-count] (memory/take! transport client-id))
     (memory/snapshot transport))))

(defn- complete-evidence
  ([] (complete-evidence unicode-payload))
  ([payload] (evidence/project-evidence (roundtrip-snapshot payload 2) payload)))

;; ---- hand-built transport-level history fixtures -----------------------------
;;
;; These histories are built (and checked below) to satisfy
;; jolt.sim.maelstrom.history/check-snapshot on their own terms -- contiguous
;; ordinals, stable enqueue identity, per-endpoint FIFO delivery, and residual
;; queue agreement -- so project-evidence's own rejections are exercised
;; through a genuinely passing transport, not a malformed one.

(defn- enq [ordinal endpoint envelope]
  {:ordinal ordinal :op :enqueue :endpoint endpoint
   :envelope envelope :message-id ordinal})

(defn- del [ordinal message-id endpoint envelope]
  {:ordinal ordinal :op :deliver :endpoint endpoint
   :envelope envelope :message-id message-id})

(defn- snap [history]
  {:queues {} :history history})

(def ^:private init-req
  {:src client-id :dest node-id
   :body {:type "init" :msg_id 1 :node_id node-id :node_ids [node-id]}})

(def ^:private echo-req
  {:src client-id :dest node-id
   :body {:type "echo" :msg_id 2 :echo "payload"}})

(def ^:private init-rep
  {:src node-id :dest client-id
   :body {:type "init_ok" :msg_id 1 :in_reply_to 1}})

(def ^:private init-rep-2
  {:src node-id :dest client-id
   :body {:type "init_ok" :msg_id 3 :in_reply_to 1}})

(def ^:private echo-rep
  {:src node-id :dest client-id
   :body {:type "echo_ok" :msg_id 2 :echo "payload" :in_reply_to 2}})

(def ^:private echo-rep-2
  {:src node-id :dest client-id
   :body {:type "echo_ok" :msg_id 3 :echo "payload" :in_reply_to 2}})

(def ^:private unknown-req
  {:src client-id :dest node-id
   :body {:type "topology" :msg_id 3 :topology {}}})

(def ^:private other-echo-req
  {:src "c2" :dest "n2"
   :body {:type "echo" :msg_id 2 :echo "payload"}})

(def ^:private other-echo-rep
  {:src "n2" :dest "c2"
   :body {:type "echo_ok" :msg_id 2 :echo "payload" :in_reply_to 2}})

(def ^:private missing-echo-reply-history
  ;; init round-trips completely; echo_ok is simply never enqueued.
  [(enq 1 node-id init-req) (enq 2 node-id echo-req)
   (del 3 1 node-id init-req)
   (enq 4 client-id init-rep)
   (del 5 2 node-id echo-req)
   (del 6 4 client-id init-rep)])

(def ^:private missing-init-reply-history
  ;; echo round-trips completely; init_ok is simply never enqueued.
  [(enq 1 node-id init-req) (enq 2 node-id echo-req)
   (del 3 1 node-id init-req)
   (del 4 2 node-id echo-req)
   (enq 5 client-id echo-rep)
   (del 6 5 client-id echo-rep)])

(def ^:private duplicate-echo-ok-history
  [(enq 1 node-id init-req) (enq 2 node-id echo-req)
   (del 3 1 node-id init-req)
   (enq 4 client-id init-rep)
   (del 5 2 node-id echo-req)
   (enq 6 client-id echo-rep)
   (enq 7 client-id echo-rep-2)
   (del 8 4 client-id init-rep)
   (del 9 6 client-id echo-rep)
   (del 10 7 client-id echo-rep-2)])

(def ^:private duplicate-init-ok-history
  [(enq 1 node-id init-req) (enq 2 node-id echo-req)
   (del 3 1 node-id init-req)
   (enq 4 client-id init-rep)
   (enq 5 client-id init-rep-2)
   (del 6 2 node-id echo-req)
   (enq 7 client-id echo-rep)
   (del 8 4 client-id init-rep)
   (del 9 5 client-id init-rep-2)
   (del 10 7 client-id echo-rep)])

(def ^:private unknown-type-history
  [(enq 1 node-id init-req) (enq 2 node-id echo-req) (enq 3 node-id unknown-req)
   (del 4 1 node-id init-req) (del 5 2 node-id echo-req) (del 6 3 node-id unknown-req)])

(def ^:private mismatched-topology-history
  [(enq 1 node-id init-req) (enq 2 "n2" other-echo-req)
   (del 3 1 node-id init-req)
   (enq 4 client-id init-rep)
   (del 5 2 "n2" other-echo-req)
   (enq 6 "c2" other-echo-rep)
   (del 7 4 client-id init-rep)
   (del 8 6 "c2" other-echo-rep)])

(def ^:private echo-before-init-history
  [(enq 1 node-id echo-req) (enq 2 node-id init-req)
   (del 3 1 node-id echo-req)
   (enq 4 client-id echo-rep)
   (del 5 2 node-id init-req)
   (enq 6 client-id init-rep)
   (del 7 4 client-id echo-rep)
   (del 8 6 client-id init-rep)])

;; ---- valid complete trace, nested Unicode payload -----------------------------

(deftest valid-complete-trace-with-nested-unicode-payload
  (let [doc (complete-evidence)]
    (is (= :jolt.sim.maelstrom.echo-evidence/v1
           (:jolt.sim.maelstrom.echo-evidence/schema doc)))
    (is (= 1 (:jolt.sim.maelstrom.echo-evidence/version doc)))
    (is (= evidence/recorded-assumptions (:assumptions doc)))
    (is (= 8 (:event-count doc)))
    (is (= {:status :pass :events 8 :enqueued 4 :delivered 4}
           (:transport-integrity doc)))
    (is (= [] (:terminal-queues doc)))
    (is (= canonical-unicode-payload (:echo-input doc)))
    (is (= canonical-unicode-payload (:echo (:echo-request doc))))
    (is (= canonical-unicode-payload (:echo (:echo-reply doc))))
    ;; init request/reply
    (is (= {:msg-id 1 :in-reply-to nil :src client-id :dest node-id
            :transport-enqueue-ordinal 1 :transport-enqueue-message-id 1
            :transport-deliver-ordinal 3 :transport-deliver-message-id 1}
           (:init-request doc)))
    (is (= {:msg-id 1 :in-reply-to 1 :src node-id :dest client-id
            :transport-enqueue-ordinal 4 :transport-enqueue-message-id 4
            :transport-deliver-ordinal 7 :transport-deliver-message-id 4}
           (:init-reply doc)))
    ;; echo request/reply (minus the already-asserted :echo payload)
    (is (= {:msg-id 2 :in-reply-to nil :src client-id :dest node-id
            :transport-enqueue-ordinal 2 :transport-enqueue-message-id 2
            :transport-deliver-ordinal 5 :transport-deliver-message-id 2}
           (dissoc (:echo-request doc) :echo)))
    (is (= {:msg-id 2 :in-reply-to 2 :src node-id :dest client-id
            :transport-enqueue-ordinal 6 :transport-enqueue-message-id 6
            :transport-deliver-ordinal 8 :transport-deliver-message-id 6}
           (dissoc (:echo-reply doc) :echo)))
    (is (= {:status :pass} (evidence/check-safety doc)))
    (is (= {:status :pass} (evidence/check-completion doc)))))

;; ---- successful empty-diagnostics result --------------------------------------

(deftest check-safety-and-check-completion-pass-with-empty-diagnostics
  (let [doc (complete-evidence)]
    (is (= {:status :pass} (evidence/check-safety doc)))
    (is (= {:status :pass} (evidence/check-completion doc)))))

;; ---- determinism and EDN stability --------------------------------------------

(deftest project-evidence-is-deterministic-and-edn-stable
  (let [doc1 (evidence/project-evidence (roundtrip-snapshot unicode-payload 2)
                                        unicode-payload)
        doc2 (evidence/project-evidence (roundtrip-snapshot unicode-payload 2)
                                        unicode-payload)]
    (is (= doc1 doc2)
        "two independently-driven, structurally identical round trips project equal evidence")
    (is (= doc1 (edn/read-string (pr-str doc1)))
        "evidence round-trips exactly through pr-str/read-string")
    (is (= (trace/canonical-edn doc1) (trace/canonical-edn doc2))
        "canonical EDN is byte-stable for independently projected evidence")))

(deftest byte-array-payload-is-value-equal-and-frozen-at-projection
  (let [transport-payload (byte-array [0 1 128 255])
        caller-input (byte-array [0 1 128 255])
        expected (trace/canonical-value caller-input)
        doc (evidence/project-evidence
             (roundtrip-snapshot transport-payload 2)
             caller-input)]
    (is (= expected (:echo-input doc)))
    (is (= expected (:echo (:echo-request doc))))
    (is (= expected (:echo (:echo-reply doc))))
    (is (= {:status :pass} (evidence/check-safety doc))
        "distinct byte arrays with equal octets compare by canonical value")
    (aset transport-payload 0 99)
    (aset caller-input 1 88)
    (is (= expected (:echo-input doc)))
    (is (= expected (:echo (:echo-request doc))))
    (is (= expected (:echo (:echo-reply doc))))
    (is (= {:status :pass} (evidence/check-safety doc))
        "caller mutation cannot change projected evidence")))

(deftest unsupported-echo-payload-fails-closed
  (let [unsupported (atom 1)
        data (ex-data-of
              #(evidence/project-evidence
                (roundtrip-snapshot unsupported 2)
                unsupported))]
    (is (= trace/unsupported-value (:type data)))
    (is (= [:echo-input] (:path data)))))

(deftest semantic-message-id-zero-is-accepted
  (let [payload "zero-id"
        doc (evidence/project-evidence
             (roundtrip-snapshot payload 2 0 1)
             payload)]
    (is (= 0 (get-in doc [:init-request :msg-id])))
    (is (= 0 (get-in doc [:init-reply :in-reply-to])))
    (is (= {:status :pass} (evidence/check-safety doc)))
    (is (= {:status :pass} (evidence/check-completion doc)))))

;; ---- missing replies -----------------------------------------------------------

(deftest missing-echo-reply-is-projected-and-flagged
  (let [doc (evidence/project-evidence (snap missing-echo-reply-history) "payload")]
    (is (nil? (:echo-reply doc)))
    (is (some? (:init-reply doc)))
    (is (= {:status :inconclusive
            :detail {:reason :replies-missing
                     :missing [:echo-reply]}}
           (evidence/check-safety doc)))
    (is (= {:status :violation
            :detail {:reason :incomplete
                     :missing [:echo-reply]
                     :undelivered []
                     :terminal-queues []}}
           (evidence/check-completion doc)))))

(deftest missing-init-reply-is-projected-and-flagged
  (let [doc (evidence/project-evidence (snap missing-init-reply-history) "payload")]
    (is (nil? (:init-reply doc)))
    (is (some? (:echo-reply doc)))
    (is (= {:status :inconclusive
            :detail {:reason :replies-missing
                     :missing [:init-reply]}}
           (evidence/check-safety doc)))
    (is (= {:status :violation
            :detail {:reason :incomplete
                     :missing [:init-reply]
                     :undelivered []
                     :terminal-queues []}}
           (evidence/check-completion doc)))))

;; ---- enqueued but undelivered reply --------------------------------------------

(deftest enqueued-but-undelivered-echo-reply-passes-safety-fails-completion
  (let [doc (evidence/project-evidence (roundtrip-snapshot "payload" 1) "payload")]
    (is (some? (:echo-reply doc)))
    (is (nil? (:transport-deliver-ordinal (:echo-reply doc))))
    (is (= ["c1"] (:terminal-queues doc)))
    (is (= {:status :pass} (evidence/check-safety doc))
        "safety only inspects already-enqueued reply content, not delivery")
    (is (= {:status :violation
            :detail {:reason :incomplete
                     :missing []
                     :undelivered [:echo-reply]
                     :terminal-queues ["c1"]}}
           (evidence/check-completion doc)))))

;; ---- unknown event types --------------------------------------------------------

(deftest unknown-body-type-is-rejected
  (let [data (ex-data-of #(evidence/project-evidence (snap unknown-type-history) "payload"))]
    (is (= :jolt.sim.maelstrom.echo-evidence/unknown-message-type (:type data)))
    (is (= #{"topology"} (:unknown-types data)))))

(deftest mismatched-client-node-topology-is-rejected
  (let [data (ex-data-of
              #(evidence/project-evidence
                (snap mismatched-topology-history)
                "payload"))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= [client-id node-id] (:init-route data)))
    (is (= ["c2" "n2"] (:echo-route data)))))

(deftest echo-before-init-causal-order-is-rejected
  (let [data (ex-data-of
              #(evidence/project-evidence
                (snap echo-before-init-history)
                "payload"))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= 2 (:init-enqueue-ordinal data)))
    (is (= 1 (:echo-enqueue-ordinal data)))))

;; ---- duplicate replies ------------------------------------------------------------

(defn- thrown-ex [f]
  (try (f) nil (catch :default e e)))

(deftest duplicate-echo-ok-replies-are-rejected
  (let [ex (thrown-ex #(evidence/project-evidence (snap duplicate-echo-ok-history) "payload"))]
    (is (= :jolt.sim.maelstrom.echo-evidence/missing-observation (:type (ex-data ex))))
    (is (= "multiple echo_ok replies found in transport history" (ex-message ex)))
    (is (= :echo-reply (:role (ex-data ex))))
    (is (= 2 (:count (ex-data ex))))))

(deftest duplicate-init-ok-replies-are-rejected
  (let [ex (thrown-ex #(evidence/project-evidence (snap duplicate-init-ok-history) "payload"))]
    (is (= :jolt.sim.maelstrom.echo-evidence/missing-observation (:type (ex-data ex))))
    (is (= "multiple init_ok replies found in transport history" (ex-message ex)))
    (is (= :init-reply (:role (ex-data ex))))
    (is (= 2 (:count (ex-data ex))))))

;; ---- closed schema/version/top-level validation controls ------------------------

(deftest wrong-top-level-keys-are-rejected
  (let [base (complete-evidence)]
    (let [data (ex-data-of #(evidence/validate-evidence! (dissoc base :event-count)))]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
      (is (= (set (keys base)) (:expected data)))
      (is (= (set (keys (dissoc base :event-count))) (:actual data))))
    (let [data (ex-data-of #(evidence/validate-evidence! (assoc base :extra-key 1)))]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
      (is (= (set (keys base)) (:expected data)))
      (is (= (set (keys (assoc base :extra-key 1))) (:actual data))))))

(deftest schema-mismatch-is-rejected
  (let [base (complete-evidence)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc base :jolt.sim.maelstrom.echo-evidence/schema :bogus)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= evidence/schema (:expected data)))
    (is (= :bogus (:actual data)))))

(deftest version-mismatch-is-rejected
  (let [base (complete-evidence)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc base :jolt.sim.maelstrom.echo-evidence/version 2)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= evidence/evidence-version (:expected data)))
    (is (= 2 (:actual data)))))

(deftest assumptions-mismatch-is-rejected
  (let [base (complete-evidence)
        data (ex-data-of #(evidence/validate-evidence! (assoc base :assumptions [:wrong])))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= evidence/recorded-assumptions (:expected data)))
    (is (= [:wrong] (:actual data)))))

(deftest event-count-must-be-a-non-negative-integer
  (let [base (complete-evidence)]
    (doseq [bad [-1 "8"]]
      (let [data (ex-data-of #(evidence/validate-evidence! (assoc base :event-count bad)))]
        (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)) bad)
        (is (= bad (:event-count data)) bad)))))

(deftest transport-integrity-wrong-shape-is-rejected
  (let [base (complete-evidence)
        bad-ti (dissoc (:transport-integrity base) :status)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc base :transport-integrity bad-ti)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= bad-ti (:transport-integrity data)))))

(deftest transport-integrity-not-passing-is-rejected
  (let [base (complete-evidence)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc-in base [:transport-integrity :status] :violation)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= :violation (:status data)))))

(deftest transport-integrity-invalid-counters-are-rejected
  (let [base (complete-evidence)
        bad-ti (assoc (:transport-integrity base) :events -1)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc base :transport-integrity bad-ti)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= bad-ti (:transport-integrity data)))))

(deftest transport-integrity-event-count-disagreement-is-rejected
  (let [base (complete-evidence)
        data (ex-data-of #(evidence/validate-evidence!
                            (assoc-in base [:transport-integrity :events] 99)))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= 8 (:event-count data)))
    (is (= 99 (:transport-events data)))))

(deftest terminal-queues-must-be-a-sorted-distinct-vector-of-strings
  (let [base (complete-evidence)]
    (let [data (ex-data-of #(evidence/validate-evidence! (assoc base :terminal-queues #{})))]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
      (is (= #{} (:terminal-queues data))))
    (let [data (ex-data-of #(evidence/validate-evidence!
                              (assoc base :terminal-queues ["b" "a"])))]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
      (is (= ["b" "a"] (:terminal-queues data))))
    (let [data (ex-data-of #(evidence/validate-evidence!
                              (assoc base :terminal-queues [1 2])))]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
      (is (= 1 (:endpoint data))))))

(deftest request-roles-must-be-present
  (let [base (complete-evidence)]
    (doseq [role [:init-request :echo-request]]
      (let [data (ex-data-of #(evidence/validate-evidence! (assoc base role nil)))]
        (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)) role)
        (is (= role (:role data)) role)
        (is (nil? (:record data)) role)))))

(deftest client-and-node-endpoints-must-be-distinct
  (let [base (complete-evidence)
        doc (-> base
                (assoc-in [:init-request :dest] client-id)
                (assoc-in [:echo-request :dest] client-id))
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= client-id (:endpoint data)))))

(deftest init-delivery-must-precede-echo-delivery
  (let [base (complete-evidence)
        doc (-> base
                (assoc-in [:init-request :transport-deliver-ordinal] 5)
                (assoc-in [:echo-request :transport-deliver-ordinal] 3))
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= 5 (:init-deliver-ordinal data)))
    (is (= 3 (:echo-deliver-ordinal data)))))

(deftest reply-deliveries-must-preserve-endpoint-fifo
  (let [base (complete-evidence)
        doc (-> base
                (assoc-in [:init-reply :transport-deliver-ordinal] 8)
                (assoc-in [:echo-reply :transport-deliver-ordinal] 7))
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= client-id (:endpoint data)))
    (is (= 4 (:earlier-enqueue-message-id data)))
    (is (= 8 (:earlier-deliver-ordinal data)))
    (is (= 6 (:later-enqueue-message-id data)))
    (is (= 7 (:later-deliver-ordinal data)))))

(deftest later-reply-cannot-deliver-past-an-undelivered-fifo-prefix
  (let [base (complete-evidence)
        doc (-> base
                (assoc-in [:init-reply :transport-deliver-ordinal] nil)
                (assoc-in [:init-reply :transport-deliver-message-id] nil)
                (assoc-in [:echo-reply :transport-deliver-ordinal] 7)
                (assoc :event-count 7)
                (assoc :terminal-queues [client-id])
                (assoc :transport-integrity
                       {:status :pass :events 7 :enqueued 4 :delivered 3}))
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= client-id (:endpoint data)))
    (is (= 4 (:earlier-enqueue-message-id data)))
    (is (= 6 (:later-enqueue-message-id data)))
    (is (= 7 (:later-deliver-ordinal data)))))

(deftest reply-message-ids-must-be-source-local-monotonic
  (let [base (complete-evidence)
        doc (-> base
                (assoc-in [:init-reply :msg-id] 2)
                (assoc-in [:echo-reply :msg-id] 1))
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= 2 (:init-reply-msg-id data)))
    (is (= 1 (:echo-reply-msg-id data)))))

(deftest echo-values-must-be-canonical-trace-forms
  (let [base (complete-evidence)]
    (doseq [doc [(assoc base :echo-input unicode-payload)
                 (assoc-in base [:echo-request :echo] unicode-payload)
                 (assoc-in base [:echo-reply :echo] unicode-payload)]]
      (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence
             (:type (ex-data-of #(evidence/validate-evidence! doc))))))))

;; ---- safety diagnostic branches --------------------------------------------------

(deftest check-safety-echo-input-mismatch
  (let [other (trace/canonical-value "not-the-payload")
        doc (assoc (complete-evidence) :echo-input other)]
    (is (= {:status :violation
            :detail {:reason :echo-input-mismatch
                     :echo-input other
                     :request-echo canonical-unicode-payload}}
           (evidence/check-safety doc)))))

(deftest check-safety-echo-value-mismatch
  (let [tampered (trace/canonical-value "tampered")
        doc (assoc-in (complete-evidence) [:echo-reply :echo] tampered)]
    (is (= {:status :violation
            :detail {:reason :echo-value-mismatch
                     :request-echo canonical-unicode-payload
                     :reply-echo tampered}}
           (evidence/check-safety doc)))))

(deftest check-safety-init-source-dest-not-reversed
  (let [doc (assoc-in (complete-evidence) [:init-reply :src] "n9")]
    (is (= {:status :violation
            :detail {:reason :init-source-dest-not-reversed
                     :request-src client-id :request-dest node-id
                     :reply-src "n9" :reply-dest client-id}}
           (evidence/check-safety doc)))))

(deftest check-safety-init-dest-src-not-reversed
  (let [doc (assoc-in (complete-evidence) [:init-reply :dest] "c9")]
    (is (= {:status :violation
            :detail {:reason :init-dest-src-not-reversed
                     :request-src client-id :request-dest node-id
                     :reply-src node-id :reply-dest "c9"}}
           (evidence/check-safety doc)))))

(deftest check-safety-echo-source-dest-not-reversed
  (let [doc (assoc-in (complete-evidence) [:echo-reply :src] "n9")]
    (is (= {:status :violation
            :detail {:reason :echo-source-dest-not-reversed
                     :request-src client-id :request-dest node-id
                     :reply-src "n9" :reply-dest client-id}}
           (evidence/check-safety doc)))))

(deftest check-safety-echo-dest-src-not-reversed
  (let [doc (assoc-in (complete-evidence) [:echo-reply :dest] "c9")]
    (is (= {:status :violation
            :detail {:reason :echo-dest-src-not-reversed
                     :request-src client-id :request-dest node-id
                     :reply-src node-id :reply-dest "c9"}}
           (evidence/check-safety doc)))))

(deftest check-safety-init-correlation-mismatch
  (let [doc (assoc-in (complete-evidence) [:init-reply :in-reply-to] 999)]
    (is (= {:status :violation
            :detail {:reason :init-correlation-mismatch
                     :request-msg-id 1 :reply-in-reply-to 999}}
           (evidence/check-safety doc)))))

(deftest check-safety-echo-correlation-mismatch
  (let [doc (assoc-in (complete-evidence) [:echo-reply :in-reply-to] 999)]
    (is (= {:status :violation
            :detail {:reason :echo-correlation-mismatch
                     :request-msg-id 2 :reply-in-reply-to 999}}
           (evidence/check-safety doc)))))

;; ---- completion evidence consistency -----------------------------------------

(deftest terminal-queues-must-match-present-undelivered-destinations
  (let [doc (assoc (complete-evidence) :terminal-queues ["ghost"])
        data (ex-data-of #(evidence/validate-evidence! doc))]
    (is (= :jolt.sim.maelstrom.echo-evidence/invalid-evidence (:type data)))
    (is (= [] (:expected data)))
    (is (= ["ghost"] (:actual data)))))

(deftest closed-event-coverage-derives-the-completion-bound
  (doseq [doc [(complete-evidence)
               (evidence/project-evidence
                (snap missing-echo-reply-history)
                "payload")
               (evidence/project-evidence
                (roundtrip-snapshot "payload" 1)
                "payload")]]
    (is (<= (:event-count doc) evidence/completion-event-bound))))
