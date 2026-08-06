(ns jolt.sim.sqlite-integration-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.sqlite-roundtrip :as fixture]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

;; Binding is safe on every image because native symbol resolution is lazy.
;; Invoking it inside a controlled scope is intercepted before resolution.
(ffi/defcfn getpid "getpid" [] :int)

(def ^:private expected
  {:ddl 0
   :inserted 1
   :empty-inserted 1
   :row {:id 1
         :payload [0 127 -128 -1]}
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

(defn- foreign-routes [effect-trace symbol]
  (filter #(= symbol (:symbol (:descriptor %))) effect-trace))

(deftest unchanged-db-code-runs-against-real-hermetic-and-hybrid-sqlite
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-sqlite))
        memory-world (memory/world)
        hermetic-world (sqlite/world memory-world (statement-plans))
        hermetic-controlled
        (runtime/run-controlled
         {:ffi-handlers (sqlite/handlers hermetic-world)}
         fixture/exercise-sqlite)
        hybrid-world (sqlite/world (statement-plans))
        hybrid-controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (sqlite/hybrid-handlers hybrid-world)}
         fixture/exercise-sqlite)
        hermetic-result (:result hermetic-controlled)
        hybrid-result (:result hybrid-controlled)
        blob-routes
        (foreign-routes (:effect-trace hybrid-controlled)
                        "sqlite3_column_blob")]
    (when-not *sim-only?*
      (is (= expected real-result))
      (is (= real-result hermetic-result))
      (is (= real-result hybrid-result)))
    (is (= expected hermetic-result))
    (is (= expected hybrid-result))
    (doseq [controlled [hermetic-controlled hybrid-controlled]]
      (is (= expected-foreign-symbols
             (foreign-symbols (:effects controlled))))
      (is (= expected-foreign-sequence
             (foreign-sequence (:effects controlled)))))
    ;; This is the public end-to-end hybrid classification witness. The body is
    ;; ordinary db code. A positive BLOB pointer classified as substitute would
    ;; fail run-controlled validation; a wrong span would corrupt these bytes.
    (is (= 2 (count blob-routes)))
    (is (every? #(= :handler (:route %)) blob-routes))
    (doseq [world [hermetic-world hybrid-world]]
      (is (= {:plan-index 6
              :plan-count 6
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary world)))
      (let [snapshot (sqlite/snapshot world)]
        (is (seq snapshot))
        (is (every? :freed? snapshot)))
      (is (true? (sqlite/clean? world))))))

(deftest hybrid-acceptance-routes-real-getpid-natively-and-modeled-sqlite-through-handlers
  ;; Minimal hybrid acceptance: one real zero-argument getpid reaches its
  ;; exact native branch through an ordinary unhandled-descriptor miss while
  ;; every SQLite foreign call and every required native-memory operation --
  ;; including the descriptor-8 null? predicate -- is answered by its
  ;; registered handler. The modeled BLOB round trip stays byte-exact and
  ;; both worlds retire every handle and allocation.
  (let [memory-world (memory/world)
        world (sqlite/world memory-world (statement-plans))
        controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (sqlite/hybrid-handlers world)}
         (fn [] [(getpid) (fixture/exercise-sqlite)]))
        [pid result] (:result controlled)
        trace (:effect-trace controlled)
        getpid-routes
        (filter #(= "getpid" (:symbol (:descriptor %))) trace)
        sqlite-routes
        (filter #(contains? expected-foreign-symbols
                            (:symbol (:descriptor %)))
                trace)
        native-operation-routes
        (filter #(= :native-operation (:kind (:descriptor %))) trace)
        null-routes
        (filter #(= :null? (:operation (:descriptor %))) trace)]
    (is (pos? pid))
    (is (= 1 (count getpid-routes)))
    (is (= :native (:route (first getpid-routes))))
    (is (= expected result))
    (is (= [0 127 -128 -1] (get-in result [:row :payload])))
    (is (seq sqlite-routes))
    (is (every? #(= :handler (:route %)) sqlite-routes))
    (is (seq native-operation-routes))
    (is (every? #(= :handler (:route %)) native-operation-routes))
    (is (seq null-routes)
        "the descriptor-8 null? predicate must be intercepted and handled")
    (is (every? #(= :handler (:route %)) null-routes))
    (is (= {:plan-index 6
            :plan-count 6
            :open-dbs 0
            :active-stmts 0}
           (sqlite/summary world)))
    (is (true? (sqlite/clean? world)))
    (is (true? (memory/clean? memory-world)))
    (is (empty? (memory/leaks memory-world)))))

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
