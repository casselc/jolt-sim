(ns jolt.example.outbox-sqlite-test
  "Focused real-SQLite tests for jolt.example.outbox.sqlite. Every test owns
  exactly one connection to a fresh :memory: database; tests run sequentially.
  The physical table names below are pinned deliberately: they are the
  adapter's durable storage contract."
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [jolt.example.outbox :as outbox]
            [jolt.example.outbox.sqlite :as store]))

;; ---- harness --------------------------------------------------------------

(def ^:private entities-table "outbox_example_entities")
(def ^:private requests-table "outbox_example_requests")
(def ^:private outbox-table "outbox_example_outbox")

(def ^:private outbox-insert-sql
  (str "insert into " outbox-table
       " (outbox_id, request_id, entity_id, version, payload, status)"
       " values (?, ?, ?, ?, ?, ?)"))

(def ^:private mark-delivered-sql
  (str "update " outbox-table
       " set status = ? where outbox_id = ? and status = ?"))

(defn- fresh-conn []
  (jdbc/connection "sqlite::memory:"))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- table-count [conn table]
  (:n (jdbc/fetch-one conn (str "select count(*) as n from " table))))

(defn- table-counts [conn]
  {:entities (table-count conn entities-table)
   :requests (table-count conn requests-table)
   :outbox (table-count conn outbox-table)})

(defn- command [request-id entity-id payload]
  {:request-id request-id :entity-id entity-id :payload payload})

(defn- status-of [conn outbox-id]
  (:status (jdbc/fetch-one
            conn
            [(str "select status from " outbox-table " where outbox_id = ?")
             outbox-id])))

;; ---- vendor rejection -------------------------------------------------------

(deftest vendor-rejection-test
  (testing "a postgresql connection vendor is rejected by every public fn"
    (let [fake-pg {:vendor :postgresql :handle nil :close (fn [] nil)}]
      (is (= :jolt.example.outbox.sqlite/unsupported-vendor
             (:type (ex-data-of #(store/init-schema! fake-pg)))))
      (is (= :jolt.example.outbox.sqlite/unsupported-vendor
             (:type (ex-data-of #(store/load-state fake-pg)))))
      (is (= :jolt.example.outbox.sqlite/unsupported-vendor
             (:type (ex-data-of #(store/apply-command!
                                  fake-pg (command "r" "e" []))))))))
  (testing "non-connection values and non-sqlite vendors are rejected"
    (doseq [bad [nil {} "sqlite::memory:" {:vendor "sqlite"}
                 {:vendor :sqlite}
                 {:vendor :sqlite
                  :handle (atom nil)
                  :close (fn [] nil)}]]
      (is (= :jolt.example.outbox.sqlite/unsupported-vendor
             (:type (ex-data-of #(store/init-schema! bad))))
          (str "spec " (pr-str bad)))))
  (testing "rejection happens before any statement runs"
    (let [fake-pg {:vendor :postgresql
                   :handle (atom nil)
                   :close (fn [] nil)}
          data (ex-data-of #(store/init-schema! fake-pg))]
      (is (= :unsupported-vendor (:reason data)))
      (is (= :postgresql (:vendor (:detail data)))))))

(deftest malformed-transaction-state-rejected-test
  (doseq [[label tx-state]
          [["not dereferenceable" {}]
           ["missing depth" (atom {})]
           ["non-integer depth" (atom {:depth "0"})]
           ["negative depth" (atom {:depth -1})]]]
    (let [fake-sqlite {:vendor :sqlite
                       :handle (atom nil)
                       :tx-state tx-state
                       :close (fn [] nil)}
          data (ex-data-of
                #(store/apply-command!
                  fake-sqlite (command "req" "entity" [])))]
      (is (= :jolt.example.outbox.sqlite/invalid-transaction-state
             (:type data))
          label)
      (is (= :invalid-transaction-depth (:reason data)) label))))

;; ---- idempotent schema init and empty initial state ---------------------------

(deftest idempotent-schema-empty-state-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/init-schema! conn)
    (testing "all three tables exist and are physically empty"
      (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn))))
    (testing "the loaded state is exactly the canonical initial state"
      (is (= (outbox/initial-state) (store/load-state conn))))
    (testing "schema init over committed data is a no-op"
      (store/apply-command! conn (command "req-1" "entity-a" [1]))
      (store/init-schema! conn)
      (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))
      (is (= {:version 1 :payload [1]}
             (get-in (store/load-state conn) [:entities "entity-a"]))))))

;; ---- fresh command persistence --------------------------------------------

(deftest fresh-command-persistence-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [0 127 128 255])
          step (store/apply-command! conn cmd)]
      (testing "the returned step map is the pure core's"
        (is (= {:status :committed
                :request-id "req-1"
                :entity-id "entity-a"
                :version 1
                :outbox-id 1}
               (:result step)))
        (is (= [{:outbox-id 1
                  :request-id "req-1"
                  :entity-id "entity-a"
                  :version 1
                  :payload [0 127 128 255]
                  :status :pending}]
               (:emitted step))))
      (testing "entity, request, and outbox each gained exactly one row"
        (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn))))
      (testing "the stored BLOB is byte-exact across the signed boundary"
        (let [row (jdbc/fetch-one
                   conn
                   ["select payload from outbox_example_outbox where outbox_id = ?"
                    1])]
          (is (= [0 127 -128 -1] (vec (:payload row))))))
      (testing "load-state reconstructs the committed canonical state"
        (is (= (:state step) (store/load-state conn))))
      (testing "the pure core accepts the reconstructed state for a transition"
        (is (= 2 (:next-outbox-id (store/load-state conn))))))
    (testing "an empty payload round-trips as a zero-length BLOB, never NULL"
      (let [step (store/apply-command! conn (command "req-2" "entity-b" []))
            row (jdbc/fetch-one
                 conn
                 ["select payload from outbox_example_outbox where outbox_id = ?"
                  2])]
        (is (= 2 (get-in step [:result :outbox-id])))
        (is (bytes? (:payload row)))
        (is (zero? (alength (:payload row))))
        (is (= [] (get-in (store/load-state conn) [:entities "entity-b" :payload]))))
      (is (= {:entities 2 :requests 2 :outbox 2} (table-counts conn))))))

;; ---- exact replay -----------------------------------------------------------

(deftest exact-replay-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [7 7 7])
          first-step (store/apply-command! conn cmd)
          counts-before (table-counts conn)
          state-before (store/load-state conn)
          replay (store/apply-command! conn cmd)]
      (testing "replay returns an equal result and state, and emits nothing"
        (is (= (:result first-step) (:result replay)))
        (is (= (:state first-step) (:state replay)))
        (is (= [] (:emitted replay))))
      (testing "replay leaves every table row count and the next id unchanged"
        (is (= counts-before (table-counts conn)))
        (is (= state-before (store/load-state conn)))
        (is (= 2 (:next-outbox-id (store/load-state conn)))))
      (testing "a third identical replay is still stable"
        (let [third (store/apply-command! conn cmd)]
          (is (= (:result first-step) (:result third)))
          (is (= [] (:emitted third)))
          (is (= counts-before (table-counts conn))))))))

;; ---- conflicting request-id ------------------------------------------------

(deftest request-id-conflict-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [1])
          committed (store/apply-command! conn cmd)
          counts-before (table-counts conn)
          state-before (store/load-state conn)]
      (testing "same request-id with a different payload conflicts"
        (let [data (ex-data-of
                    #(store/apply-command! conn (command "req-1" "entity-a" [2])))]
          (is (= :jolt.example.outbox/request-id-conflict (:type data)))
          (is (= "req-1" (:request-id data)))
          (is (= cmd (:recorded data)))
          (is (= (command "req-1" "entity-a" [2]) (:received data)))))
      (testing "same request-id with a different entity-id conflicts"
        (is (= :jolt.example.outbox/request-id-conflict
               (:type (ex-data-of
                       #(store/apply-command!
                         conn (command "req-1" "entity-b" [1])))))))
      (testing "the conflict left the database exactly unchanged"
        (is (= counts-before (table-counts conn)))
        (is (= state-before (store/load-state conn)))
        (is (= (:state committed) state-before))))))

;; ---- per-entity versions and global outbox ids ------------------------------

(deftest multiple-entities-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [commands [(command "r1" "a" [1])
                    (command "r2" "b" [2])
                    (command "r3" "a" [3])
                    (command "r4" "b" [4 5])
                    (command "r5" "a" [6])]
          steps (mapv #(store/apply-command! conn %) commands)]
      (testing "versions are per entity while outbox ids are global"
        (is (= [1 1 2 2 3] (mapv #(get-in % [:result :version]) steps)))
        (is (= [1 2 3 4 5] (mapv #(get-in % [:result :outbox-id]) steps))))
      (testing "the persisted projections reflect the latest committed values"
        (let [state (store/load-state conn)]
          (is (= {:version 3 :payload [6]} (get-in state [:entities "a"])))
          (is (= {:version 2 :payload [4 5]} (get-in state [:entities "b"])))
          (is (= 6 (:next-outbox-id state)))
          (is (= [1 2 3 4 5] (mapv :outbox-id (:outbox state))))))
      (testing "row counts match the accepted history exactly"
        (is (= {:entities 2 :requests 5 :outbox 5} (table-counts conn))))
      (testing "load-state equals the final in-memory step state"
        (is (= (:state (nth steps 4)) (store/load-state conn)))))))

;; ---- durable return requires the outer transaction boundary ----------------

(deftest ambient-transaction-rejected-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [4 2])
          inner-outcome (atom nil)
          outer-sentinel (ex-info "test outer rollback"
                                  {:test/outer-rollback true})
          outer-data
          (ex-data-of
           #(jdbc/atomic-apply
             conn
             (fn [c]
               (try
                 (reset! inner-outcome
                         {:step (store/apply-command! c cmd)})
                 (catch :default e
                   (reset! inner-outcome {:error (ex-data e)})))
               (throw outer-sentinel))))]
      (testing "the nested call returns no falsely committed step"
        (is (nil? (:step @inner-outcome)))
        (is (= :jolt.example.outbox.sqlite/ambient-transaction
               (get-in @inner-outcome [:error :type])))
        (is (= :durable-boundary-required
               (get-in @inner-outcome [:error :reason])))
        (is (= 1 (get-in @inner-outcome [:error :detail :depth]))))
      (testing "the caller's outer rollback remains the primary boundary outcome"
        (is (true? (:test/outer-rollback outer-data))))
      (testing "neither the rejected inner call nor outer rollback persisted data"
        (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn)))
        (is (= (outbox/initial-state) (store/load-state conn)))))))

;; ---- injected failure rolls the whole transaction back -------------------------

(deftest injected-failure-rollback-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [9])
          observed (atom nil)
          sentinel (ex-info "test-injected failure before outbox insert"
                            {:test/injected true})
          real-execute! jdbc/execute!
          data
          (ex-data-of
           (fn []
             (with-redefs
              [jdbc/execute!
               (fn
                 ([c q]
                  (if (= outbox-insert-sql
                         (if (vector? q) (first q) q))
                    (do
                      (reset! observed (table-counts c))
                      (throw sentinel))
                    (real-execute! c q)))
                 ([c q opts]
                  (real-execute! c q opts)))]
              (store/apply-command! conn cmd))))]
      (testing "the injected failure propagates as primary; no step is returned"
        (is (true? (:test/injected data))))
      (testing "entity and request writes happened before the injection point"
        (is (= {:entities 1 :requests 1 :outbox 0} @observed)))
      (testing "the whole transaction rolled back; load-state is unchanged"
        (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn)))
        (is (= (outbox/initial-state) (store/load-state conn))))
      (testing "a clean retry reuses the uncommitted outbox id safely"
        (let [step (store/apply-command! conn cmd)]
          (is (= {:status :committed
                  :request-id "req-1"
                  :entity-id "entity-a"
                  :version 1
                  :outbox-id 1}
                 (:result step)))
          (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))
          (is (= (:state step) (store/load-state conn))))))))

(deftest embedded-nul-id-rejected-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (doseq [[label cmd]
            [["request id" (command (str "req" (char 0) "tail") "entity" [1])]
             ["entity id" (command "req" (str "entity" (char 0) "tail") [1])]]]
      (let [data (ex-data-of #(store/apply-command! conn cmd))]
        (is (= :jolt.example.outbox.sqlite/unstorable-id (:type data)) label)
        (is (= :embedded-nul (:reason data)) label)))
    (is (= {:entities 0 :requests 0 :outbox 0} (table-counts conn)))
    (is (= (outbox/initial-state) (store/load-state conn)))))

;; ---- persisted corruption is rejected fail closed ------------------------------

(deftest request-outbox-mismatch-rejected-test
  (testing "a request row whose command payload diverges from its outbox row"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [1 2]))
      (jdbc/execute! conn
                     [(str "update " requests-table
                           " set payload = ? where request_id = ?")
                      (byte-array [9 9])
                      "req-1"])
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(store/load-state conn)))))))
  (testing "a request row whose result version diverges from the outbox history"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [1 2]))
      (is (some? (store/load-state conn)))
      (jdbc/execute! conn
                     [(str "update " requests-table
                           " set version = ? where request_id = ?")
                      7
                      "req-1"])
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(store/load-state conn)))))))
  (testing "TEXT stored in a required BLOB column is rejected at the boundary"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [1 2]))
      (jdbc/execute! conn
                     [(str "update " outbox-table
                           " set payload = ? where outbox_id = ?")
                      "not-a-blob"
                      1])
      (is (= :jolt.example.outbox.sqlite/corrupt-row
             (:type (ex-data-of #(store/load-state conn)))))))
  (testing "unknown persisted status is rejected without interning a keyword"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [1]))
      (jdbc/execute! conn
                     [(str "update " outbox-table
                           " set status = ? where outbox_id = ?")
                      "unexpected"
                      1])
      (let [data (ex-data-of #(store/load-state conn))]
        (is (= :jolt.example.outbox.sqlite/corrupt-row (:type data)))
        (is (= :invalid-status (:reason data)))))))

(deftest noncontiguous-outbox-id-rejected-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "req-1" "entity-a" [1]))
    (store/apply-command! conn (command "req-2" "entity-a" [2]))
    (testing "the untampered history loads"
      (is (some? (store/load-state conn))))
    (jdbc/execute! conn
                   [(str "update " outbox-table
                         " set outbox_id = ? where outbox_id = ?")
                    3
                    2])
    (testing "a gap in the persisted outbox id sequence is rejected"
      (is (= :jolt.example.outbox/invalid-state
             (:type (ex-data-of #(store/load-state conn))))))))

;; ---- NULL in a required field is rejected, never read as empty -------------------

(deftest null-required-field-rejected-test
  (testing "NULL payloads are rejected in every row converter"
    (doseq [[label row-converter row]
            [["entity" (var jolt.example.outbox.sqlite/entity-row->entry)
              {:entity_id "e" :version 1 :payload nil}]
             ["request" (var jolt.example.outbox.sqlite/request-row->entry)
              {:request_id "r" :entity_id "e" :payload nil
               :version 1 :outbox_id 1}]
             ["outbox" (var jolt.example.outbox.sqlite/outbox-row->row)
              {:outbox_id 1 :request_id "r" :entity_id "e" :version 1
               :payload nil :status "pending"}]]]
      (is (= :jolt.example.outbox.sqlite/corrupt-row
             (:type (ex-data-of #(row-converter row))))
          label)))
  (testing "NULL scalar fields are rejected"
    (doseq [[label row-converter row]
            [["entity version"
              (var jolt.example.outbox.sqlite/entity-row->entry)
              {:entity_id "e" :version nil :payload (byte-array 0)}]
            ["entity id"
              (var jolt.example.outbox.sqlite/entity-row->entry)
              {:entity_id nil :version 1 :payload (byte-array 0)}]
            ["outbox id"
              (var jolt.example.outbox.sqlite/outbox-row->row)
              {:outbox_id nil :request_id "r" :entity_id "e" :version 1
               :payload (byte-array 0) :status "pending"}]
             ["outbox status"
              (var jolt.example.outbox.sqlite/outbox-row->row)
              {:outbox_id 1 :request_id "r" :entity_id "e" :version 1
               :payload (byte-array 0) :status nil}]]]
      (is (= :jolt.example.outbox.sqlite/corrupt-row
             (:type (ex-data-of #(row-converter row))))
          label))))

;; ---- durable delivery marking -----------------------------------------------

(deftest mark-delivered-persistence-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [step1 (store/apply-command! conn (command "req-1" "entity-a" [0 255]))
          step2 (store/apply-command! conn (command "req-2" "entity-b" [1 2 3]))
          counts-before (table-counts conn)
          mark (store/mark-delivered! conn 1)]
      (testing "the returned step is the pure core's exact step map"
        (is (= #{:state :row :changed?} (set (keys mark))))
        (is (true? (:changed? mark)))
        (is (= {:outbox-id 1
                :request-id "req-1"
                :entity-id "entity-a"
                :version 1
                :payload [0 255]
                :status :delivered}
               (:row mark))))
      (testing "only the targeted row's status changed durably"
        (is (= counts-before (table-counts conn)))
        (is (= "delivered" (status-of conn 1)))
        (is (= "pending" (status-of conn 2))))
      (testing "marking touched no entity, request, or id-allocation history"
        (is (= (:entities (:state step2)) (:entities (:state mark))))
        (is (= (:request-log (:state step2)) (:request-log (:state mark))))
        (is (= (:next-outbox-id (:state step2))
               (:next-outbox-id (:state mark))))
        (is (= (dissoc (first (:outbox (:state step1))) :status)
               (dissoc (:row mark) :status))))
      (testing "load-state reconstructs the marked canonical state"
        (is (= (:state mark) (store/load-state conn)))
        (is (= [:delivered :pending]
               (mapv :status (:outbox (store/load-state conn)))))))))

(deftest mark-delivered-idempotent-no-write-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "req-1" "entity-a" [1]))
    (let [first-mark (store/mark-delivered! conn 1)
          updates (atom [])
          real-execute! jdbc/execute!
          second-mark
          (with-redefs
           [jdbc/execute!
            (fn
              ([c q]
               (when (= mark-delivered-sql (if (vector? q) (first q) q))
                 (swap! updates conj q))
               (real-execute! c q))
              ([c q opts]
               (real-execute! c q opts)))]
            (store/mark-delivered! conn 1))]
      (testing "the first mark changed; the second is idempotent"
        (is (true? (:changed? first-mark)))
        (is (false? (:changed? second-mark)))
        (is (= (:state first-mark) (:state second-mark)))
        (is (= (:row first-mark) (:row second-mark))))
      (testing "the idempotent call issued no guarded update"
        (is (= [] @updates)))
      (testing "the durable status stays delivered"
        (is (= "delivered" (status-of conn 1)))))))

(deftest command-replay-after-marking-persistence-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (let [cmd (command "req-1" "entity-a" [7 7 7])
          applied (store/apply-command! conn cmd)
          marked (store/mark-delivered! conn 1)
          counts-before (table-counts conn)
          replay (store/apply-command! conn cmd)]
      (testing "replay after marking is exact and performs no writes"
        (is (= (:result applied) (:result replay)))
        (is (= [] (:emitted replay)))
        (is (= (:state marked) (:state replay)))
        (is (= counts-before (table-counts conn)))
        (is (= "delivered" (status-of conn 1))))
      (testing "a fresh command after marking keeps the delivered row delivered"
        (let [step2 (store/apply-command! conn (command "req-2" "entity-a" [8]))]
          (is (= 2 (get-in step2 [:result :outbox-id])))
          (is (= "delivered" (status-of conn 1)))
          (is (= "pending" (status-of conn 2)))
          (is (= {:entities 1 :requests 2 :outbox 2} (table-counts conn)))
          (is (= (:state step2) (store/load-state conn))))))))

(deftest mark-delivered-id-failures-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "req-1" "entity-a" [1]))
    (let [state-before (store/load-state conn)]
      (testing "invalid ids fail closed with the core's distinct typed ex-info"
        (doseq [bad [nil 0 -3 1.5 "1"]]
          (let [data (ex-data-of #(store/mark-delivered! conn bad))]
            (is (= :jolt.example.outbox/invalid-outbox-id (:type data))
                (str "id " (pr-str bad)))
            (is (= :bad-outbox-id (:reason data)) (str "id " (pr-str bad))))))
      (testing "unknown positive ids fail closed with a distinct typed ex-info"
        (doseq [unknown [2 99]]
          (let [data (ex-data-of #(store/mark-delivered! conn unknown))]
            (is (= :jolt.example.outbox/unknown-outbox-id (:type data))
                (str "id " unknown))
            (is (= unknown (:outbox-id data)) (str "id " unknown)))))
      (testing "every failure left the database byte-identical"
        (is (= state-before (store/load-state conn)))
        (is (= {:entities 1 :requests 1 :outbox 1} (table-counts conn)))
        (is (= "pending" (status-of conn 1)))))))

(deftest mark-delivered-vendor-and-boundary-rejection-test
  (testing "a non-sqlite vendor is rejected before any statement runs"
    (let [fake-pg {:vendor :postgresql
                   :handle (atom nil)
                   :close (fn [] nil)}
          data (ex-data-of #(store/mark-delivered! fake-pg 1))]
      (is (= :jolt.example.outbox.sqlite/unsupported-vendor (:type data)))
      (is (= :postgresql (:vendor (:detail data))))))
  (testing "malformed transaction bookkeeping fails closed"
    (doseq [[label tx-state]
            [["not dereferenceable" {}]
             ["missing depth" (atom {})]
             ["non-integer depth" (atom {:depth "0"})]
             ["negative depth" (atom {:depth -1})]]]
      (let [fake-sqlite {:vendor :sqlite
                         :handle (atom nil)
                         :tx-state tx-state
                         :close (fn [] nil)}
            data (ex-data-of #(store/mark-delivered! fake-sqlite 1))]
        (is (= :jolt.example.outbox.sqlite/invalid-transaction-state
               (:type data))
            label))))
  (testing "an ambient transaction is rejected and nothing is marked"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [4 2]))
      (let [inner-outcome (atom nil)
            outer-sentinel (ex-info "test outer rollback"
                                    {:test/outer-rollback true})
            outer-data
            (ex-data-of
             #(jdbc/atomic-apply
               conn
               (fn [c]
                 (try
                   (reset! inner-outcome {:step (store/mark-delivered! c 1)})
                   (catch :default e
                     (reset! inner-outcome {:error (ex-data e)})))
                 (throw outer-sentinel))))]
        (is (nil? (:step @inner-outcome)))
        (is (= :jolt.example.outbox.sqlite/ambient-transaction
               (get-in @inner-outcome [:error :type])))
        (is (= :durable-boundary-required
               (get-in @inner-outcome [:error :reason])))
        (is (= 1 (get-in @inner-outcome [:error :detail :depth])))
        (is (true? (:test/outer-rollback outer-data)))
        (is (= "pending" (status-of conn 1)))))))

(deftest mixed-status-reload-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "r1" "a" [1]))
    (store/apply-command! conn (command "r2" "a" [2]))
    (store/apply-command! conn (command "r3" "b" [3]))
    (store/mark-delivered! conn 2)
    (testing "a middle row marked delivered reloads in exact id order"
      (let [state (store/load-state conn)]
        (is (= [:pending :delivered :pending]
               (mapv :status (:outbox state))))
        (is (= [1 2 3] (mapv :outbox-id (:outbox state))))
        (is (= {:version 2 :payload [2]} (get-in state [:entities "a"])))
        (is (= 4 (:next-outbox-id state)))))
    (testing "marking the remaining rows one at a time stays exact"
      (store/mark-delivered! conn 3)
      (is (= [:pending :delivered :delivered]
             (mapv :status (:outbox (store/load-state conn)))))
      (store/mark-delivered! conn 1)
      (is (= [:delivered :delivered :delivered]
             (mapv :status (:outbox (store/load-state conn)))))
      (is (= (outbox/validate-state! (store/load-state conn))
             (store/load-state conn))))))

(deftest corrupt-status-rejection-test
  (testing "non-canonical status strings are rejected without interning keywords"
    (doseq [bad ["DELIVERED" "Pending" "delivered " " pending" ""]]
      (with-open [conn (fresh-conn)]
        (store/init-schema! conn)
        (store/apply-command! conn (command "req-1" "entity-a" [1]))
        (jdbc/execute! conn
                       [(str "update " outbox-table
                             " set status = ? where outbox_id = ?")
                        bad
                        1])
        (let [data (ex-data-of #(store/load-state conn))]
          (is (= :jolt.example.outbox.sqlite/corrupt-row (:type data))
              (str "status " (pr-str bad)))
          (is (= :invalid-status (:reason data)) (str "status " (pr-str bad)))))))
  (testing "a non-string status storage class is rejected fail closed"
    (with-open [conn (fresh-conn)]
      (store/init-schema! conn)
      (store/apply-command! conn (command "req-1" "entity-a" [1]))
      (jdbc/execute! conn
                     [(str "update " outbox-table
                           " set status = ? where outbox_id = ?")
                      1
                      1])
      (is (= :jolt.example.outbox.sqlite/corrupt-row
             (:type (ex-data-of #(store/load-state conn))))))))

(deftest mark-delivered-injected-failure-rollback-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "req-1" "entity-a" [9]))
    (let [state-before (store/load-state conn)
          sentinel (ex-info "test-injected failure after the guarded update"
                            {:test/injected true})
          real-execute! jdbc/execute!
          data
          (ex-data-of
           (fn []
             (with-redefs
              [jdbc/execute!
               (fn
                 ([c q]
                  (if (= mark-delivered-sql (if (vector? q) (first q) q))
                    (do
                      (real-execute! c q)
                      (throw sentinel))
                    (real-execute! c q)))
                 ([c q opts]
                  (real-execute! c q opts)))]
               (store/mark-delivered! conn 1))))]
      (testing "the injected failure propagates as primary; no step is returned"
        (is (true? (:test/injected data))))
      (testing "the transaction rolled back; the durable status stays pending"
        (is (= "pending" (status-of conn 1)))
        (is (= state-before (store/load-state conn))))
      (testing "a clean retry marks delivered exactly once"
        (let [step (store/mark-delivered! conn 1)]
          (is (true? (:changed? step)))
          (is (= :delivered (:status (:row step))))
          (is (= "delivered" (status-of conn 1)))
          (is (= (:state step) (store/load-state conn))))))))

(deftest mark-delivered-unexpected-write-count-rollback-test
  (with-open [conn (fresh-conn)]
    (store/init-schema! conn)
    (store/apply-command! conn (command "req-1" "entity-a" [5]))
    (let [state-before (store/load-state conn)
          real-execute! jdbc/execute!
          data
          (ex-data-of
           (fn []
             (with-redefs
              [jdbc/execute!
               (fn
                 ([c q]
                  (if (= mark-delivered-sql (if (vector? q) (first q) q))
                    (do
                      (real-execute! c q)
                      0)
                    (real-execute! c q)))
                 ([c q opts]
                  (real-execute! c q opts)))]
               (store/mark-delivered! conn 1))))]
      (testing "a zero affected-row count fails closed after the actual update"
        (is (= :jolt.example.outbox.sqlite/unexpected-write-count (:type data)))
        (is (= :unexpected-write-count (:reason data)))
        (is (= 1 (get-in data [:detail :expected])))
        (is (= 0 (get-in data [:detail :actual])))
        (is (= :outbox-mark-delivered
               (get-in data [:detail :statement])))
        (is (= 1 (get-in data [:detail :outbox-id]))))
      (testing "the failed invariant check rolls the actual update back"
        (is (= "pending" (status-of conn 1)))
        (is (= state-before (store/load-state conn))))
      (testing "a clean retry still marks the row exactly once"
        (let [step (store/mark-delivered! conn 1)]
          (is (true? (:changed? step)))
          (is (= "delivered" (status-of conn 1)))
          (is (= (:state step) (store/load-state conn))))))))
