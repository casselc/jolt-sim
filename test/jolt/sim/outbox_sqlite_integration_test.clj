(ns jolt.sim.outbox-sqlite-integration-test
  "Real/hermetic/hybrid parity fixture for the unchanged
  jolt.example.outbox.sqlite adapter.

  One ordinary jdbc.core + adapter scenario (exercise-outbox-sqlite) runs
  three times: against real system SQLite, under jolt.sim.runtime
  run-controlled with the hermetic jolt.sim.sqlite handler pack, and under
  run-controlled :hybrid with the hybrid handler pack. jolt-sim supplies only
  the handler worlds and the exact 28-statement plan world around that one
  shared function; the adapter itself is never modified or wrapped.

  The statement plans pin the adapter's literal SQL and parameter order
  exactly, in FIFO order:

    0  PRAGMA foreign_keys=1;            (connection open)
    1-3 three CREATE TABLE IF NOT EXISTS (init-schema!)
    4  BEGIN                             (first fresh command)
    5-7 three scans                      (load-state inside the transaction)
    8  entity insert
    9  request insert
    10 outbox insert
    11 COMMIT
    12 BEGIN                             (exact replay)
    13-15 three scans
    16 COMMIT                            (replay performs no writes)
    17 BEGIN                             (second fresh command)
    18-20 three scans
    21 entity update
    22 request insert
    23 outbox insert
    24 COMMIT
    25-27 three scans                    (explicit final load-state)

  :tx-effect appears only on the BEGIN/COMMIT plans; :row-effect appears only
  on the row mutations and scans; the static PRAGMA/DDL plans carry no effect.
  Scan plans never predeclare rows: the row model derives them from the
  connection's visible storage at execution time. Canonical table ids are
  :outbox/entities, :outbox/requests, and :outbox/rows.

  The row model (:row-effect) is integrated by the sibling slice and is not
  present in this worktree, so this gate cannot run here. It is deliberately
  not weakened or faked to make it run; Codex integrates the sibling model and
  runs the executable gates."
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [jolt.example.outbox.sqlite :as store]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

;; ---- fixed private table names (the adapter's durable storage contract) ----

(def ^:private entities-table "outbox_example_entities")
(def ^:private requests-table "outbox_example_requests")
(def ^:private outbox-table "outbox_example_outbox")

;; ---- the adapter's literal SQL, byte for byte ------------------------------

(def ^:private entity-scan-sql
  (str "select entity_id, version, payload from " entities-table
       " order by entity_id"))

(def ^:private request-scan-sql
  (str "select request_id, entity_id, payload, version, outbox_id from "
       requests-table " order by request_id"))

(def ^:private outbox-scan-sql
  (str "select outbox_id, request_id, entity_id, version, payload, status from "
       outbox-table " order by outbox_id"))

(def ^:private entity-insert-sql
  (str "insert into " entities-table
       " (entity_id, version, payload) values (?, ?, ?)"))

(def ^:private entity-update-sql
  (str "update " entities-table
       " set version = ?, payload = ? where entity_id = ?"))

(def ^:private request-insert-sql
  (str "insert into " requests-table
       " (request_id, entity_id, payload, version, outbox_id)"
       " values (?, ?, ?, ?, ?)"))

(def ^:private outbox-insert-sql
  (str "insert into " outbox-table
       " (outbox_id, request_id, entity_id, version, payload, status)"
       " values (?, ?, ?, ?, ?, ?)"))

;; ---- plan builders ---------------------------------------------------------

(defn- static-plan
  "A PRAGMA/DDL plan: no :tx-effect, no :row-effect."
  [sql]
  {:sql sql :params {} :columns [] :rows [] :changes 0 :last-row-id 0})

(defn- tx-plan
  "A BEGIN/COMMIT plan carrying exactly one :tx-effect directive."
  [sql op]
  (assoc (static-plan sql) :tx-effect {:op op}))

(defn- scan-plan
  "A scan plan whose rows are derived by the row model from visible storage;
  :rows is deliberately never predeclared."
  [sql columns table project order-key]
  {:sql sql
   :params {}
   :columns columns
   :changes 0
   :last-row-id 0
   :row-effect {:op :scan-rows
                :table table
                :project project
                :order-key order-key}})

(defn- insert-plan
  "A row-mutation plan writing the bound parameters into the model's storage
  under the declared column names."
  [sql params table key-params row changes last-row-id]
  {:sql sql
   :params params
   :columns []
   :changes changes
   :last-row-id last-row-id
   :row-effect {:op :insert-row
                :table table
                :key-params key-params
                :row row}})

(defn- update-plan
  "A row-mutation plan updating the row selected by :key-params from the
  declared :set column/parameter pairs."
  [sql params table key-params set-pairs changes]
  {:sql sql
   :params params
   :columns []
   :changes changes
   :last-row-id 0
   :row-effect {:op :update-row
                :table table
                :key-params key-params
                :set set-pairs}})

(defn- statement-plans
  "The exact 28-statement plan world for the shared scenario, in FIFO order.
  Every :params map matches the adapter's literal bind order; BLOB cells
  preserve 0xff and the empty BLOB."
  []
  [(static-plan "PRAGMA foreign_keys=1;")
   (static-plan
    (str "create table if not exists " entities-table " ("
         "entity_id text primary key, "
         "version integer not null, "
         "payload blob not null)"))
   (static-plan
    (str "create table if not exists " requests-table " ("
         "request_id text primary key, "
         "entity_id text not null, "
         "payload blob not null, "
         "version integer not null, "
         "outbox_id integer not null unique)"))
   (static-plan
    (str "create table if not exists " outbox-table " ("
         "outbox_id integer primary key, "
         "request_id text not null unique, "
         "entity_id text not null, "
         "version integer not null, "
         "payload blob not null, "
         "status text not null)"))
   ;; first fresh command: BEGIN, three empty scans, three staged writes, COMMIT
   (tx-plan "BEGIN" :begin)
   (scan-plan entity-scan-sql ["entity_id" "version" "payload"]
              :outbox/entities ["entity_id" "version" "payload"] ["entity_id"])
   (scan-plan request-scan-sql
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              :outbox/requests
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              ["request_id"])
   (scan-plan outbox-scan-sql
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              :outbox/rows
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              ["outbox_id"])
   (insert-plan entity-insert-sql
                {1 {:type :text :value "entity-a"}
                 2 {:type :integer :value 1}
                 3 {:type :blob :value (byte-array [0 127 128 255])}}
                :outbox/entities [1]
                [["entity_id" 1] ["version" 2] ["payload" 3]]
                1 1)
   (insert-plan request-insert-sql
                {1 {:type :text :value "req-1"}
                 2 {:type :text :value "entity-a"}
                 3 {:type :blob :value (byte-array [0 127 128 255])}
                 4 {:type :integer :value 1}
                 5 {:type :integer :value 1}}
                :outbox/requests [1]
                [["request_id" 1] ["entity_id" 2] ["payload" 3]
                 ["version" 4] ["outbox_id" 5]]
                1 2)
   (insert-plan outbox-insert-sql
                {1 {:type :integer :value 1}
                 2 {:type :text :value "req-1"}
                 3 {:type :text :value "entity-a"}
                 4 {:type :integer :value 1}
                 5 {:type :blob :value (byte-array [0 127 128 255])}
                 6 {:type :text :value "pending"}}
                :outbox/rows [1]
                [["outbox_id" 1] ["request_id" 2] ["entity_id" 3]
                 ["version" 4] ["payload" 5] ["status" 6]]
                1 3)
   (tx-plan "COMMIT" :commit)
   ;; exact replay: BEGIN, three scans, COMMIT -- no writes at all
   (tx-plan "BEGIN" :begin)
   (scan-plan entity-scan-sql ["entity_id" "version" "payload"]
              :outbox/entities ["entity_id" "version" "payload"] ["entity_id"])
   (scan-plan request-scan-sql
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              :outbox/requests
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              ["request_id"])
   (scan-plan outbox-scan-sql
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              :outbox/rows
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              ["outbox_id"])
   (tx-plan "COMMIT" :commit)
   ;; second fresh command: BEGIN, three scans, entity UPDATE + two inserts,
   ;; COMMIT -- empty payload exercises the zero-length BLOB end to end
   (tx-plan "BEGIN" :begin)
   (scan-plan entity-scan-sql ["entity_id" "version" "payload"]
              :outbox/entities ["entity_id" "version" "payload"] ["entity_id"])
   (scan-plan request-scan-sql
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              :outbox/requests
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              ["request_id"])
   (scan-plan outbox-scan-sql
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              :outbox/rows
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              ["outbox_id"])
   (update-plan entity-update-sql
                {1 {:type :integer :value 2}
                 2 {:type :blob :value (byte-array 0)}
                 3 {:type :text :value "entity-a"}}
                :outbox/entities [3]
                [["version" 1] ["payload" 2]]
                1)
   (insert-plan request-insert-sql
                {1 {:type :text :value "req-2"}
                 2 {:type :text :value "entity-a"}
                 3 {:type :blob :value (byte-array 0)}
                 4 {:type :integer :value 2}
                 5 {:type :integer :value 2}}
                :outbox/requests [1]
                [["request_id" 1] ["entity_id" 2] ["payload" 3]
                 ["version" 4] ["outbox_id" 5]]
                1 4)
   (insert-plan outbox-insert-sql
                {1 {:type :integer :value 2}
                 2 {:type :text :value "req-2"}
                 3 {:type :text :value "entity-a"}
                 4 {:type :integer :value 2}
                 5 {:type :blob :value (byte-array 0)}
                 6 {:type :text :value "pending"}}
                :outbox/rows [1]
                [["outbox_id" 1] ["request_id" 2] ["entity_id" 3]
                 ["version" 4] ["payload" 5] ["status" 6]]
                1 5)
   (tx-plan "COMMIT" :commit)
   ;; explicit final load-state: three scans over the committed storage
   (scan-plan entity-scan-sql ["entity_id" "version" "payload"]
              :outbox/entities ["entity_id" "version" "payload"] ["entity_id"])
   (scan-plan request-scan-sql
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              :outbox/requests
              ["request_id" "entity_id" "payload" "version" "outbox_id"]
              ["request_id"])
   (scan-plan outbox-scan-sql
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              :outbox/rows
              ["outbox_id" "request_id" "entity_id" "version" "payload" "status"]
              ["outbox_id"])])

;; ---- the one shared ordinary scenario --------------------------------------

(defn exercise-outbox-sqlite
  "Ordinary jdbc.core + jolt.example.outbox.sqlite application code, run
  unchanged against real SQLite and under the hermetic/hybrid SQLite handler
  worlds. The caller decides which substrate executes it.

  Opens sqlite::memory: (whose connection initialization runs
  \"PRAGMA foreign_keys=1;\"), initializes the schema once, applies a fresh
  command for req-1/entity-a with payload [0 127 128 255], applies that exact
  command again (an exact replay that must emit nothing and change no
  persisted state), applies a fresh req-2 to the same entity with an empty
  payload (exercising the entity UPDATE and global outbox id 2), and finally
  calls load-state after the second commit.

  Returns only canonical immutable application values suitable for exact
  equality: the two step maps, the replay step map, and the final loaded
  state. No native handles, pointers, byte arrays, controller objects, or
  mutable state ever escape."
  []
  (with-open [conn (jdbc/connection "sqlite::memory:")]
    (store/init-schema! conn)
    (let [cmd-1 {:request-id "req-1" :entity-id "entity-a" :payload [0 127 128 255]}
          step-1 (store/apply-command! conn cmd-1)
          replay (store/apply-command! conn cmd-1)
          cmd-2 {:request-id "req-2" :entity-id "entity-a" :payload []}
          step-2 (store/apply-command! conn cmd-2)
          state (store/load-state conn)]
      {:step-1 step-1
       :replay replay
       :step-2 step-2
       :state state})))

;; ---- expected canonical application values ---------------------------------

(def ^:private expected-step-1
  {:result {:status :committed
            :request-id "req-1"
            :entity-id "entity-a"
            :version 1
            :outbox-id 1}
   :emitted [{:outbox-id 1
              :request-id "req-1"
              :entity-id "entity-a"
              :version 1
              :payload [0 127 128 255]
              :status :pending}]})

(def ^:private expected-step-2
  {:result {:status :committed
            :request-id "req-2"
            :entity-id "entity-a"
            :version 2
            :outbox-id 2}
   :emitted [{:outbox-id 2
              :request-id "req-2"
              :entity-id "entity-a"
              :version 2
              :payload []
              :status :pending}]})

(def ^:private expected-final-state
  {:entities {"entity-a" {:version 2 :payload []}}
   :request-log
   {"req-1" {:command {:request-id "req-1"
                       :entity-id "entity-a"
                       :payload [0 127 128 255]}
             :result {:status :committed
                      :request-id "req-1"
                      :entity-id "entity-a"
                      :version 1
                      :outbox-id 1}}
    "req-2" {:command {:request-id "req-2"
                       :entity-id "entity-a"
                       :payload []}
             :result {:status :committed
                      :request-id "req-2"
                      :entity-id "entity-a"
                      :version 2
                      :outbox-id 2}}}
   :next-outbox-id 3
   :outbox [{:outbox-id 1
             :request-id "req-1"
             :entity-id "entity-a"
             :version 1
             :payload [0 127 128 255]
             :status :pending}
            {:outbox-id 2
             :request-id "req-2"
             :entity-id "entity-a"
             :version 2
             :payload []
             :status :pending}]})

;; ---- expected sim evidence -------------------------------------------------

;; Three verified-begin pre-probes (one per atomic-apply), all observing
;; autocommit, then three BEGIN/COMMIT pairs with no rollback.
(def ^:private expected-tx-evidence
  [{:sequence 0 :plan-index 4 :op :begin :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? true :after-autocommit? false}
   {:sequence 1 :plan-index 11 :op :commit :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? false :after-autocommit? true}
   {:sequence 2 :plan-index 12 :op :begin :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? true :after-autocommit? false}
   {:sequence 3 :plan-index 16 :op :commit :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? false :after-autocommit? true}
   {:sequence 4 :plan-index 17 :op :begin :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? true :after-autocommit? false}
   {:sequence 5 :plan-index 24 :op :commit :when :on-success :reported :done
    :applied? true :reason nil :before-autocommit? false :after-autocommit? true}])

;; Row evidence projected to the documented surface keys [:op :table
;; :plan-index :location]. The first load's scans see no rows (location nil);
;; every later scan reads committed storage; every mutation inside a
;; transaction stages (:staging); the replay transaction (plans 12-16) carries
;; no mutation at all.
(def ^:private expected-row-evidence
  [[:scan-rows :outbox/entities 5 nil]
   [:scan-rows :outbox/requests 6 nil]
   [:scan-rows :outbox/rows 7 nil]
   [:insert-row :outbox/entities 8 :staging]
   [:insert-row :outbox/requests 9 :staging]
   [:insert-row :outbox/rows 10 :staging]
   [:scan-rows :outbox/entities 13 :committed]
   [:scan-rows :outbox/requests 14 :committed]
   [:scan-rows :outbox/rows 15 :committed]
   [:scan-rows :outbox/entities 18 :committed]
   [:scan-rows :outbox/requests 19 :committed]
   [:scan-rows :outbox/rows 20 :committed]
   [:update-row :outbox/entities 21 :staging]
   [:insert-row :outbox/requests 22 :staging]
   [:insert-row :outbox/rows 23 :staging]
   [:scan-rows :outbox/entities 25 :committed]
   [:scan-rows :outbox/requests 26 :committed]
   [:scan-rows :outbox/rows 27 :committed]])

;; Every SQLite foreign symbol the unchanged adapter + pinned db.sqlite can
;; emit on the sim lanes. sqlite3_get_autocommit is the verified-begin
;; pre-probe; sqlite3_errcode is absent because the model serves every BLOB
;; (including the empty one) with a non-null pointer.
(def ^:private expected-foreign-symbols
  #{"sqlite3_open"
    "sqlite3_close_v2"
    "sqlite3_prepare_v2"
    "sqlite3_step"
    "sqlite3_finalize"
    "sqlite3_column_count"
    "sqlite3_column_name"
    "sqlite3_column_type"
    "sqlite3_column_int64"
    "sqlite3_column_text"
    "sqlite3_column_blob"
    "sqlite3_column_bytes"
    "sqlite3_bind_text"
    "sqlite3_bind_int64"
    "sqlite3_bind_blob64"
    "sqlite3_changes"
    "sqlite3_get_autocommit"})

;; One BLOB column read per scanned row: entity 3 rows, request 4 rows,
;; outbox 4 rows.
(def ^:private expected-blob-reads 11)

;; ---- evidence helpers ------------------------------------------------------

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(defn- foreign-routes [effect-trace symbol]
  (filter #(= symbol (:symbol (:descriptor %))) effect-trace))

(defn- row-evidence-projection
  "Projects one row-evidence entry onto the documented surface keys
  [:op :table :plan-index :location]."
  [evidence]
  (mapv (fn [entry]
          [(:op entry) (:table entry) (:plan-index entry) (:location entry)])
        evidence))

(defn- replay-mutations
  "Row mutations recorded inside the replay transaction (plan indices 12-16).
  The parity contract requires this to be empty: an exact replay performs no
  writes."
  [projected]
  (filterv (fn [[op _table plan-index _location]]
             (and (<= 12 plan-index 16)
                  (contains? #{:insert-row :update-row} op)))
           projected))

;; ---- the parity gate -------------------------------------------------------

(deftest outbox-sqlite-parity-test
  (let [real-result (when-not *sim-only?*
                      (exercise-outbox-sqlite))
        hermetic-world (sqlite/world (statement-plans))
        hermetic-controlled
        (runtime/run-controlled
         {:ffi-handlers (sqlite/handlers hermetic-world)}
         exercise-outbox-sqlite)
        hybrid-world (sqlite/world (statement-plans))
        hybrid-controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (sqlite/hybrid-handlers hybrid-world)}
         exercise-outbox-sqlite)
        hermetic-result (:result hermetic-controlled)
        hybrid-result (:result hybrid-controlled)]
    (testing "exact real == hermetic == hybrid semantic result"
      (when-not *sim-only?*
        (is (= real-result hermetic-result))
        (is (= real-result hybrid-result)))
      (is (= hermetic-result hybrid-result)))
    (testing "first fresh command commits version/outbox 1 with one emission"
      (is (= expected-step-1
             (select-keys (:step-1 hermetic-result) [:result :emitted])))
      (is (= expected-step-1
             (select-keys (:step-1 hybrid-result) [:result :emitted]))))
    (testing "exact replay emits nothing and changes no persisted state"
      (doseq [result [hermetic-result hybrid-result]]
        (is (= (get-in result [:step-1 :result])
               (get-in result [:replay :result])))
        (is (= (get-in result [:step-1 :state])
               (get-in result [:replay :state])))
        (is (= [] (get-in result [:replay :emitted])))))
    (testing "second fresh command updates the entity to version/outbox 2 with an empty payload"
      (doseq [result [hermetic-result hybrid-result]]
        (is (= expected-step-2
               (select-keys (:step-2 result) [:result :emitted])))))
    (testing "final canonical state: one entity at version 2, two immutable request records, pending outbox ids [1 2], next id 3"
      (doseq [result [hermetic-result hybrid-result]]
        (is (= expected-final-state (:state result)))))
    (testing "exact plan consumption and clean memory worlds"
      (doseq [world [hermetic-world hybrid-world]]
        (is (= {:plan-index 28
                :plan-count 28
                :open-dbs 0
                :active-stmts 0}
               (sqlite/summary world)))
        (is (true? (sqlite/clean? world)))))
    (testing "physical transaction evidence: three BEGIN/COMMIT pairs, no rollback"
      (doseq [world [hermetic-world hybrid-world]]
        (let [closed (get-in (sqlite/state world) [:closed-db-evidence])]
          (is (= 1 (count closed)))
          (let [db (first closed)]
            (is (true? (:autocommit? db)))
            (is (nil? (:tx db)))
            (is (= [1 1 1] (:autocommit-evidence db)))
            (is (= expected-tx-evidence (:tx-evidence db)))
            (is (nil? (:staging db)))))))
    (testing "row evidence: three scans per load, one entity insert, one entity update, two request inserts, two outbox inserts, staging locations, no replay mutation"
      (doseq [world [hermetic-world hybrid-world]]
        (let [db (first (get-in (sqlite/state world) [:closed-db-evidence]))
              projected (row-evidence-projection (:row-evidence db))]
          (is (= expected-row-evidence projected))
          (is (= [] (replay-mutations projected)))
          (is (= 12 (count (filter #(= :scan-rows (first %)) projected))))
          (is (= 5 (count (filter #(contains? #{:insert-row :update-row}
                                              (first %))
                                  projected)))))))
    (testing "hermetic/hybrid effect traces route every SQLite call to a handler, including BLOB bindings and reads"
      (doseq [controlled [hermetic-controlled hybrid-controlled]]
        (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
        (is (= expected-foreign-symbols
               (foreign-symbols (:effects controlled)))))
      (let [blob-routes (foreign-routes (:effect-trace hybrid-controlled)
                                        "sqlite3_column_blob")]
        (is (= expected-blob-reads (count blob-routes)))
        (is (every? #(= :handler (:route %)) blob-routes))))))
