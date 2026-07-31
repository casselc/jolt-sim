(ns jolt.sim.sqlite-integration-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.sqlite-roundtrip :as fixture]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected
  {:ddl 0
   :inserted 1
   :empty-inserted 1
   :row {:id 1
         :payload [0 127 128 255]}
   :empty-row {:id 2
               :payload []}})

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
    "sqlite3_bind_int64"
    "sqlite3_bind_blob64"
    "sqlite3_column_blob"
    "sqlite3_column_bytes"
    "sqlite3_errcode"
    "sqlite3_changes"})

(def ^:private expected-foreign-sequence
  ["sqlite3_open"
   "sqlite3_prepare_v2" "sqlite3_column_count"
   "sqlite3_step" "sqlite3_finalize"
   "sqlite3_prepare_v2" "sqlite3_column_count"
   "sqlite3_step" "sqlite3_finalize" "sqlite3_changes"
   "sqlite3_prepare_v2" "sqlite3_bind_int64" "sqlite3_bind_blob64"
   "sqlite3_column_count" "sqlite3_step" "sqlite3_finalize" "sqlite3_changes"
   "sqlite3_prepare_v2" "sqlite3_bind_int64" "sqlite3_bind_blob64"
   "sqlite3_column_count" "sqlite3_step" "sqlite3_finalize" "sqlite3_changes"
   "sqlite3_prepare_v2" "sqlite3_bind_int64" "sqlite3_column_count"
   "sqlite3_step"
   "sqlite3_column_name" "sqlite3_column_type" "sqlite3_column_int64"
   "sqlite3_column_name" "sqlite3_column_type"
   "sqlite3_column_blob" "sqlite3_column_bytes"
   "sqlite3_step" "sqlite3_finalize"
   "sqlite3_prepare_v2" "sqlite3_bind_int64" "sqlite3_column_count"
   "sqlite3_step"
   "sqlite3_column_name" "sqlite3_column_type" "sqlite3_column_int64"
   "sqlite3_column_name" "sqlite3_column_type"
   "sqlite3_column_blob" "sqlite3_errcode"
   "sqlite3_step" "sqlite3_finalize"
   "sqlite3_close_v2"])

(defn- statement-plans []
  [{:sql "PRAGMA foreign_keys=1;"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "create table sim_probe (id integer primary key, payload blob)"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "insert into sim_probe (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 1}
             2 {:type :blob
                :value (byte-array [0 127 128 255])}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 1}
   {:sql "insert into sim_probe (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 2}
             2 {:type :blob
                :value (byte-array 0)}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 2}
   {:sql "select id, payload from sim_probe where id = ?"
    :params {1 {:type :integer :value 1}}
    :columns ["id" "payload"]
    :rows [[{:type :integer :value 1}
            {:type :blob
             :value (byte-array [0 127 128 255])}]]
    :changes 0
    :last-row-id 1}
   {:sql "select id, payload from sim_probe where id = ?"
    :params {1 {:type :integer :value 2}}
    :columns ["id" "payload"]
    :rows [[{:type :integer :value 2}
            {:type :blob
             :value (byte-array 0)
             :null-pointer? true}]]
    :changes 0
    :last-row-id 2}])

(defn- foreign-symbols [effects]
  (set
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(defn- foreign-sequence [effects]
  (vec
   (keep (fn [effect]
           (when (= :foreign-function (:kind effect))
             (:symbol effect)))
         effects)))

(deftest unchanged-db-code-runs-against-real-and-simulated-sqlite
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-sqlite))
        memory-world (memory/world)
        sqlite-world (sqlite/world memory-world (statement-plans))
        controlled
        (runtime/run-controlled
         {:ffi-handlers (sqlite/handlers sqlite-world)}
         fixture/exercise-sqlite)
        simulated-result (:result controlled)
        snapshot (sqlite/snapshot sqlite-world)]
    (when-not *sim-only?*
      (is (= expected real-result))
      (is (= real-result simulated-result)))
    (is (= expected simulated-result))
    (is (= expected-foreign-symbols
           (foreign-symbols (:effects controlled))))
    (is (= expected-foreign-sequence
           (foreign-sequence (:effects controlled))))
    (is (= {:plan-index 6
            :plan-count 6
            :open-dbs 0
            :active-stmts 0}
           (sqlite/summary sqlite-world)))
    (is (seq snapshot))
    (is (every? :freed? snapshot))
    (is (true? (sqlite/clean? sqlite-world)))))

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))
        result
        (binding [*sim-only?* sim-only?]
          (test/run-tests 'jolt.sim.sqlite-integration-test))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
