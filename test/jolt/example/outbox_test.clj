(ns jolt.example.outbox-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.example.outbox :as outbox]))

;; ---- harness --------------------------------------------------------------

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- command
  ([request-id entity-id payload]
   {:request-id request-id :entity-id entity-id :payload payload}))

(defn- apply-seq
  "Folds commands left to right, returning the final state and every
  per-step apply-command return value."
  [commands]
  (reduce
   (fn [{:keys [state] :as acc} cmd]
     (let [step (outbox/apply-command state cmd)]
       (-> acc
           (assoc :state (:state step))
           (update :steps conj step))))
   {:state (outbox/initial-state) :steps []}
   commands))

;; ---- fresh command --------------------------------------------------------

(deftest fresh-command-test
  (let [initial (outbox/initial-state)
        cmd (command "req-1" "entity-a" [1 2 3])
        {:keys [state result emitted]} (outbox/apply-command initial cmd)]
    (testing "stable result describes the committed version and row id"
      (is (= {:status :committed
              :request-id "req-1"
              :entity-id "entity-a"
              :version 1
              :outbox-id 1}
             result)))
    (testing "exactly one pending outbox row matching the committed value"
      (is (= [{:outbox-id 1
               :request-id "req-1"
               :entity-id "entity-a"
               :version 1
               :payload [1 2 3]
               :status :pending}]
             emitted)))
    (testing "next state commits the entity version and records the request"
      (is (= {:version 1 :payload [1 2 3]}
             (get-in state [:entities "entity-a"])))
      (is (= cmd (get-in state [:request-log "req-1" :command])))
      (is (= result (get-in state [:request-log "req-1" :result])))
      (is (= 2 (:next-outbox-id state)))
      (is (= (vec emitted) (:outbox state))))
    (testing "prior state is untouched"
      (is (= initial (outbox/initial-state))))))

;; ---- same-id replay -------------------------------------------------------

(deftest same-id-replay-test
  (let [cmd (command "req-1" "entity-a" [7 7 7])
        first-step (outbox/apply-command (outbox/initial-state) cmd)
        replay (outbox/apply-command (:state first-step) cmd)]
    (is (= (:result first-step) (:result replay)))
    (is (= (:state first-step) (:state replay)))
    (is (= [] (:emitted replay)))
    (testing "a third identical replay is still stable"
      (let [third (outbox/apply-command (:state replay) cmd)]
        (is (= (:result first-step) (:result third)))
        (is (= (:state first-step) (:state third)))
        (is (= [] (:emitted third)))))))

;; ---- conflicting-id rejection ---------------------------------------------

(deftest conflicting-request-id-test
  (let [cmd (command "req-1" "entity-a" [1])
        committed (:state (outbox/apply-command (outbox/initial-state) cmd))]
    (testing "same request-id with a different payload conflicts"
      (let [data (ex-data-of
                  #(outbox/apply-command
                    committed (command "req-1" "entity-a" [2])))]
        (is (= :jolt.example.outbox/request-id-conflict (:type data)))
        (is (= "req-1" (:request-id data)))
        (is (= cmd (:recorded data)))
        (is (= (command "req-1" "entity-a" [2]) (:received data)))))
    (testing "same request-id with a different entity-id conflicts"
      (is (= :jolt.example.outbox/request-id-conflict
             (:type (ex-data-of
                     #(outbox/apply-command
                       committed (command "req-1" "entity-b" [1])))))))
    (testing "conflict throws before any state is produced"
      (is (= 1 (count (:outbox committed))))
      (is (= 2 (:next-outbox-id committed))))))

;; ---- per-entity version monotonicity --------------------------------------

(deftest per-entity-version-monotonicity-test
  (let [commands [(command "r1" "a" [1])
                  (command "r2" "a" [2])
                  (command "r3" "b" [9])
                  (command "r4" "a" [3])
                  (command "r5" "b" [8])]
        {:keys [state steps]} (apply-seq commands)
        versions (mapv #(get-in % [:result :version]) steps)]
    (is (= [1 2 1 3 2] versions))
    (is (= 3 (get-in state [:entities "a" :version])))
    (is (= 2 (get-in state [:entities "b" :version])))
    (is (= [3] (get-in state [:entities "a" :payload])))
    (is (= [8] (get-in state [:entities "b" :payload])))))

;; ---- global outbox-id monotonicity across entities ------------------------

(deftest global-outbox-id-monotonicity-test
  (let [commands (mapv #(command (str "r" %) (str "entity-" (mod % 3)) [%])
                       (range 1 8))
        {:keys [state steps]} (apply-seq commands)
        allocated (mapv #(get-in % [:result :outbox-id]) steps)]
    (is (= [1 2 3 4 5 6 7] allocated))
    (is (= 8 (:next-outbox-id state)))
    (testing "replay allocates nothing and reuses no id"
      (let [replay (outbox/apply-command state (first commands))]
        (is (= 1 (get-in replay [:result :outbox-id])))
        (is (= [] (:emitted replay)))
        (is (= 8 (get-in replay [:state :next-outbox-id])))))
    (testing "outbox rows are stored in ascending id order"
      (is (= allocated (mapv :outbox-id (:outbox state)))))))

;; ---- byte boundaries ------------------------------------------------------

(deftest payload-byte-boundaries-test
  (testing "0 and 255 are accepted octets"
    (let [cmd (command "req-1" "entity-a" [0 255 0 128 255])
          {:keys [result emitted]}
          (outbox/apply-command (outbox/initial-state) cmd)]
      (is (= :committed (:status result)))
      (is (= [0 255 0 128 255] (:payload (first emitted))))))
  (testing "empty payload is a canonical octet vector"
    (is (= :committed
           (:status
            (:result
             (outbox/apply-command (outbox/initial-state)
                                   (command "req-1" "entity-a" [])))))))
  (testing "octets outside 0..255 are rejected"
    (doseq [bad [[256] [-1] [0 256] [255 -1]]]
      (is (= :jolt.example.outbox/invalid-command
             (:type (ex-data-of
                     #(outbox/apply-command (outbox/initial-state)
                                            (command "req-1" "entity-a" bad)))))
          (str "payload " bad))))
  (testing "non-integer and non-vector payloads are rejected"
    (doseq [bad [["x"] [1.5] [nil] '(1 2) "abc" nil]]
      (is (= :jolt.example.outbox/invalid-command
             (:type (ex-data-of
                     #(outbox/apply-command (outbox/initial-state)
                                            {:request-id "req-1"
                                             :entity-id "entity-a"
                                             :payload bad}))))
          (str "payload " (pr-str bad))))))

;; ---- unknown keys ---------------------------------------------------------

(deftest unknown-keys-test
  (testing "commands are closed maps"
    (doseq [bad [(assoc (command "req-1" "entity-a" [1]) :trace-id "t")
                 (dissoc (command "req-1" "entity-a" [1]) :payload)]]
      (is (= :jolt.example.outbox/invalid-command
             (:type (ex-data-of
                     #(outbox/apply-command (outbox/initial-state) bad))))
          (str "command " (pr-str bad)))))
  (testing "states are closed maps"
    (let [committed (:state (outbox/apply-command
                             (outbox/initial-state)
                             (command "req-1" "entity-a" [1])))]
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of
                     #(outbox/apply-command
                       (assoc committed :debug true)
                       (command "req-2" "entity-a" [2]))))))
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of
                     #(outbox/apply-command
                       (dissoc committed :outbox)
                       (command "req-2" "entity-a" [2])))))))))

;; ---- malformed state ------------------------------------------------------

(deftest malformed-state-test
  (let [committed (:state (outbox/apply-command
                           (outbox/initial-state)
                           (command "req-1" "entity-a" [1])))
        cmd (command "req-2" "entity-a" [2])]
    (doseq [[label bad-state]
            [["non-map state" nil]
             ["entity with tampered version"
              (assoc-in committed [:entities "entity-a" :version] 0)]
             ["entity with non-octet payload"
              (assoc-in committed [:entities "entity-a" :payload] [256])]
             ["entity with unknown key"
              (assoc-in committed [:entities "entity-a" :note] "x")]
             ["non-vector outbox"
              (assoc committed :outbox (list (first (:outbox committed))))]
             ["outbox row with unknown key"
              (assoc-in committed [:outbox 0 :extra] 1)]
             ["outbox row/request-log request-id mismatch"
              (assoc-in committed [:outbox 0 :request-id] "other")]
             ["outbox row/request command payload mismatch"
              (assoc-in committed [:outbox 0 :payload] [2])]
             ["outbox row/result entity mismatch"
              (assoc-in committed
                        [:request-log "req-1" :result :entity-id]
                        "entity-b")]
             ["result points at no matching outbox row"
              (assoc-in committed
                        [:request-log "req-1" :result :outbox-id]
                        2)]
             ["request log entry has no outbox row"
              (assoc committed :outbox [])]
             ["reused next outbox id"
              (assoc committed :next-outbox-id 1)]
             ["skipped next outbox id"
              (assoc committed :next-outbox-id 3)]
             ["request-log entry with unknown key"
              (assoc-in committed [:request-log "req-1" :note] "x")]
             ["request-log key/command mismatch"
              (assoc-in committed
                        [:request-log "req-1" :command :request-id]
                        "other")]]]
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(outbox/apply-command bad-state cmd))))
          label))))

(deftest validate-state-public-boundary-test
  (let [initial (outbox/initial-state)
        invalid (assoc initial :unexpected true)]
    (testing "a canonical state is returned unchanged"
      (is (= initial (outbox/validate-state! initial))))
    (testing "the public adapter boundary rejects invalid state directly"
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(outbox/validate-state! invalid))))))))

;; ---- invariant: one matching pending row per entity change ----------------

(deftest entity-change-has-one-matching-pending-row-test
  (let [commands [(command "r1" "a" [0])
                  (command "r2" "b" [255])
                  (command "r1" "a" [0]) ;; exact replay: no change, no row
                  (command "r3" "a" [1 2])
                  (command "r4" "c" [])]
        {:keys [state steps]} (apply-seq commands)
        changes (filter #(seq (:emitted %)) steps)
        emitted (vec (mapcat :emitted steps))]
    (testing "every accepted change emits exactly one row, replays none"
      (is (= 4 (count changes)))
      (is (every? #(= 1 (count (:emitted %))) changes))
      (is (= [] (:emitted (nth steps 2)))))
    (testing "each row's version and payload match the value committed then"
      (doseq [{:keys [state emitted]} changes]
        (let [row (first emitted)
              entity (get-in state [:entities (:entity-id row)])]
          (is (= (:version entity) (:version row)))
          (is (= (:payload entity) (:payload row)))
          (is (= (get-in state
                         [:request-log (:request-id row) :command :request-id])
                 (:request-id row)))
          (is (= :pending (:status row))))))
    (testing "the final outbox is exactly the concatenation of emitted rows"
      (is (= emitted (:outbox state)))
      (is (= (count (filter #(seq (:emitted %)) steps))
             (count (:outbox state)))))))

;; ---- delivery marking -------------------------------------------------------

(deftest mark-delivered-transition-locality-test
  (let [cmd1 (command "req-1" "entity-a" [1 2 3])
        cmd2 (command "req-2" "entity-b" [9])
        {:keys [state]} (apply-seq [cmd1 cmd2])
        step (outbox/mark-delivered state 1)]
    (testing "the return shape is exactly {:state :row :changed?}"
      (is (= #{:state :row :changed?} (set (keys step)))))
    (testing "changed? is true and :row is the delivered row, otherwise equal"
      (is (true? (:changed? step)))
      (is (= :delivered (:status (:row step))))
      (is (= (assoc (nth (:outbox state) 0) :status :delivered)
             (:row step))))
    (testing "transition locality: only that row's :status differs in state"
      (is (= (:entities state) (:entities (:state step))))
      (is (= (:request-log state) (:request-log (:state step))))
      (is (= (:next-outbox-id state) (:next-outbox-id (:state step))))
      (is (= 2 (count (:outbox (:state step)))))
      (is (= (nth (:outbox state) 1) (nth (:outbox (:state step)) 1)))
      (is (= (dissoc (nth (:outbox state) 0) :status)
             (dissoc (nth (:outbox (:state step)) 0) :status)))
      (is (= :delivered (get-in (:state step) [:outbox 0 :status])))
      (is (= :pending (get-in (:state step) [:outbox 1 :status]))))
    (testing "the marked state remains exactly canonical"
      (is (= (:state step) (outbox/validate-state! (:state step)))))
    (testing "marking a later row leaves earlier rows untouched"
      (let [step2 (outbox/mark-delivered state 2)]
        (is (true? (:changed? step2)))
        (is (= (nth (:outbox state) 0) (nth (:outbox (:state step2)) 0)))
        (is (= :delivered (get-in (:state step2) [:outbox 1 :status])))))))

(deftest mark-delivered-idempotency-test
  (let [state (:state (apply-seq [(command "req-1" "entity-a" [1])]))
        first-step (outbox/mark-delivered state 1)
        second-step (outbox/mark-delivered (:state first-step) 1)]
    (is (true? (:changed? first-step)))
    (testing "a second marking returns the state byte-equal and changed? false"
      (is (false? (:changed? second-step)))
      (is (= (:state first-step) (:state second-step)))
      (is (identical? (:state first-step) (:state second-step)))
      (is (= (:row first-step) (:row second-step))))
    (testing "a third marking is still stable"
      (let [third (outbox/mark-delivered (:state second-step) 1)]
        (is (false? (:changed? third)))
        (is (= (:state first-step) (:state third)))
        (is (= (:row first-step) (:row third)))))))

(deftest command-replay-after-marking-test
  (let [cmd (command "req-1" "entity-a" [7 7 7])
        applied (outbox/apply-command (outbox/initial-state) cmd)
        marked (outbox/mark-delivered (:state applied) 1)
        replay (outbox/apply-command (:state marked) cmd)]
    (testing "an exact replay of the marked command returns the recorded result"
      (is (= (:result applied) (:result replay)))
      (is (= [] (:emitted replay)))
      (is (= (:state marked) (:state replay)))
      (is (= :delivered (get-in replay [:state :outbox 0 :status]))))
    (testing "a fresh command after marking keeps the delivered row delivered"
      (let [step2 (outbox/apply-command (:state replay)
                                        (command "req-2" "entity-a" [8]))]
        (is (= 2 (get-in step2 [:result :outbox-id])))
        (is (= :delivered (get-in step2 [:state :outbox 0 :status])))
        (is (= :pending (get-in step2 [:state :outbox 1 :status])))
        (is (= [{:outbox-id 1
                 :request-id "req-1"
                 :entity-id "entity-a"
                 :version 1
                 :payload [7 7 7]
                 :status :delivered}
                {:outbox-id 2
                 :request-id "req-2"
                 :entity-id "entity-a"
                 :version 2
                 :payload [8]
                 :status :pending}]
               (:outbox (:state step2))))
        (is (= (:state step2) (outbox/validate-state! (:state step2))))))))

(deftest mark-delivered-invalid-id-test
  (let [state (:state (apply-seq [(command "req-1" "entity-a" [1])]))]
    (testing "non-positive-integer ids fail closed with a distinct type"
      (doseq [bad [nil 0 -1 -42 1.5 "1" [1] :one]]
        (let [data (ex-data-of #(outbox/mark-delivered state bad))]
          (is (= :jolt.example.outbox/invalid-outbox-id (:type data))
              (str "id " (pr-str bad)))
          (is (= :bad-outbox-id (:reason data)) (str "id " (pr-str bad)))
          (is (= bad (:outbox-id (:detail data))) (str "id " (pr-str bad))))))
    (testing "the invalid-id type is distinct from unknown-id and invalid-state"
      (is (not= :jolt.example.outbox/unknown-outbox-id
                (:type (ex-data-of #(outbox/mark-delivered state 0)))))
      (is (not= :jolt.example.outbox/invalid-state
                (:type (ex-data-of #(outbox/mark-delivered state 0))))))
    (testing "an invalid id wins over a malformed prior state, input first"
      (is (= :jolt.example.outbox/invalid-outbox-id
             (:type (ex-data-of #(outbox/mark-delivered :not-a-state 0))))))
    (testing "a malformed prior state with a valid id fails as invalid-state"
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(outbox/mark-delivered
                                  (assoc state :debug true) 1)))))
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of
                     #(outbox/mark-delivered
                       (assoc (outbox/initial-state) :next-outbox-id 2)
                       1))))))))

(deftest mark-delivered-unknown-id-test
  (testing "unknown positive ids on an empty outbox fail closed"
    (let [data (ex-data-of #(outbox/mark-delivered (outbox/initial-state) 1))]
      (is (= :jolt.example.outbox/unknown-outbox-id (:type data)))
      (is (= :unknown-outbox-id (:reason data)))
      (is (= 1 (:outbox-id data)))))
  (let [state (:state (apply-seq [(command "r1" "a" [1])
                                  (command "r2" "b" [2])]))]
    (testing "unknown positive ids past the allocated range fail closed"
      (doseq [unknown [3 4 999]]
        (let [data (ex-data-of #(outbox/mark-delivered state unknown))]
          (is (= :jolt.example.outbox/unknown-outbox-id (:type data))
              (str "id " unknown))
          (is (= unknown (:outbox-id data)) (str "id " unknown)))))
    (testing "the failed lookups produced no state"
      (is (= [1 2] (mapv :outbox-id (:outbox state))))
      (is (= [:pending :pending] (mapv :status (:outbox state)))))
    (testing "unknown is distinct from invalid even at the same magnitude"
      (is (not= (:type (ex-data-of #(outbox/mark-delivered state 3)))
                (:type (ex-data-of #(outbox/mark-delivered state "3"))))))))

(deftest state-validation-with-delivered-status-test
  (let [state (:state (apply-seq [(command "r1" "a" [1])
                                  (command "r2" "b" [2])
                                  (command "r3" "a" [3])]))
        marked (:state (outbox/mark-delivered state 2))]
    (testing "mixed pending/delivered states validate exactly"
      (is (= marked (outbox/validate-state! marked)))
      (is (= [:pending :delivered :pending] (mapv :status (:outbox marked)))))
    (testing "marking changes no entity, request, or id-allocation history"
      (is (= (:entities state) (:entities marked)))
      (is (= (:request-log state) (:request-log marked)))
      (is (= (:next-outbox-id state) (:next-outbox-id marked))))
    (testing "every row status delivered still validates"
      (let [all-marked (:state (outbox/mark-delivered
                                (:state (outbox/mark-delivered marked 1))
                                3))]
        (is (= [:delivered :delivered :delivered]
               (mapv :status (:outbox all-marked))))
        (is (= all-marked (outbox/validate-state! all-marked)))))
    (testing "any non-canonical status is still rejected fail closed"
      (doseq [bad-status [:acked :failed "delivered" "pending" nil]]
        (let [bad-state (assoc-in marked [:outbox 0 :status] bad-status)]
          (is (= :jolt.example.outbox/invalid-state
                 (:type (ex-data-of #(outbox/validate-state! bad-state))))
              (str "status " (pr-str bad-status)))
          (is (= :jolt.example.outbox/invalid-state
                 (:type (ex-data-of #(outbox/mark-delivered bad-state 1))))
              (str "status " (pr-str bad-status))))))
    (testing "history consistency stays exact under marking"
      (doseq [[label bad-state]
              [["delivered row with tampered payload"
                (assoc-in marked [:outbox 1 :payload] [0])]
               ["delivered row with tampered version"
                (assoc-in marked [:outbox 1 :version] 9)]
               ["delivered row with tampered request-id"
                (assoc-in marked [:outbox 1 :request-id] "other")]]]
        (is (= :jolt.example.outbox/invalid-state
               (:type (ex-data-of #(outbox/validate-state! bad-state))))
            label)))))
