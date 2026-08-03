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
