(ns jolt.sim.sqlite-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.sqlite :as sqlite]))

;; ---- harness ------------------------------------------------------------

(def ^:private key-by-symbol
  (into {} (map (fn [k] [(nth k 1) k]) sqlite/handler-keys)))

(def ^:private native-ops
  [:load-library :loaded? :alloc :free :read :write :sizeof :read-bytes
   :write-bytes :read-array :read-array! :write-array
   :borrow-byte-array :release-byte-array
   :ptr->string :string->ptr])

(defn- native [H op & args]
  ((get H [:native-operation op])
   {:kind :native-operation :task 0 :arguments (vec args) :operation op}))

(defn- ff [H sym args]
  (let [key (key-by-symbol sym)]
    ((get H key)
     {:kind :foreign-function :task 0 :arguments (vec args)
      :symbol sym :argument-types (nth key 2)
      :return-type (nth key 3) :blocking? (nth key 4)})))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- open-db [H]
  (let [filename (native H :string->ptr "file:test.db")
        cell (native H :alloc 8)]
    (is (= 0 (ff H "sqlite3_open" [filename cell])))
    (let [db (native H :read cell :pointer)]
      (native H :free filename)
      (native H :free cell)
      db)))

(defn- prepare [H db sql]
  (let [sql-ptr (native H :string->ptr sql)
        stmt-cell (native H :alloc 8)]
    ;; db.sqlite always passes ffi/null for the unused tail output.
    (is (= 0 (ff H "sqlite3_prepare_v2" [db sql-ptr -1 stmt-cell 0])))
    (let [stmt (native H :read stmt-cell :pointer)]
      (native H :free sql-ptr)
      (native H :free stmt-cell)
      stmt)))

(defn- close-db [H db]
  (is (= 0 (ff H "sqlite3_close_v2" [db]))))

;; clojure.test `is` swallows exceptions, so a prepare that is expected to
;; throw must bypass the `is` in `prepare` and surface its ex-data directly.
(defn- prepare-ex-data [H db sql]
  (let [sql-ptr (native H :string->ptr sql)
        stmt-cell (native H :alloc 8)
        data (ex-data-of
              #(ff H "sqlite3_prepare_v2" [db sql-ptr -1 stmt-cell 0]))]
    (native H :free sql-ptr)
    (native H :free stmt-cell)
    data))

;; ---- handler shape ------------------------------------------------------

(deftest handlers-merge-22-sqlite-keys-and-16-native-ops
  (let [w (sqlite/world [])
        h (sqlite/handlers w)
        h-keys (set (keys h))
        ff-keys (set sqlite/handler-keys)
        native-keys (set (map #(vec [:native-operation %]) native-ops))]
    (is (= 22 (count ff-keys)))
    (is (= ff-keys (set (filter #(= :foreign-function (nth % 0)) h-keys))))
    (is (= native-keys (set (filter #(= :native-operation (nth % 0)) h-keys))))
    (is (= 38 (count h-keys)))
    (doseq [k sqlite/handler-keys]
      (is (ifn? (get h k))))))

(deftest world-validates-memory-world-and-plans
  (is (= :jolt.sim.sqlite/invalid-world
         (:type (ex-data-of #(sqlite/world "not-a-world" [])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of #(sqlite/world ["not-a-map"])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of #(sqlite/world [{:no-sql 1}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type
          (ex-data-of
           #(sqlite/world
             [{:sql "Q"
               :params {0 {:type :integer :value 1}}}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type
          (ex-data-of
           #(sqlite/world
             [{:sql "Q"
               :columns ["a" "b"]
               :rows [[{:type :integer :value 1}]]}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type
          (ex-data-of
           #(sqlite/world
             [{:sql "Q"
               :rows [[{:type :blob :value [256]}]]}])))))
  (is (map? (sqlite/world [])))
  (is (map? (sqlite/world (memory/world) []))))

;; ---- FIFO plan serving --------------------------------------------------

(deftest fifo-plans-are-consumed-in-order-with-columns-rows-changes-rowid
  (let [plans [{:sql "SELECT id, name FROM t"
                :columns ["id" "name"]
                :rows [[{:type :integer :value 1} {:type :text :value "ann"}]
                       [{:type :integer :value 2} {:type :text :value "bo"}]]}
               {:sql "INSERT INTO t VALUES(3)"
                :changes 1 :last-row-id 7}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "SELECT id, name FROM t")]
    (is (= 1 (:plan-index (sqlite/summary w))))
    ;; first row
    (is (= 100 (ff H "sqlite3_step" [s1])))
    (is (= 2 (ff H "sqlite3_column_count" [s1])))
    (is (= "id" (ff H "sqlite3_column_name" [s1 0])))
    (is (= "name" (ff H "sqlite3_column_name" [s1 1])))
    (is (= 1 (ff H "sqlite3_column_type" [s1 0])))
    (is (= 3 (ff H "sqlite3_column_type" [s1 1])))
    (is (= 1 (ff H "sqlite3_column_int64" [s1 0])))
    (is (= "ann" (ff H "sqlite3_column_text" [s1 1])))
    ;; second row
    (is (= 100 (ff H "sqlite3_step" [s1])))
    (is (= 2 (ff H "sqlite3_column_int64" [s1 0])))
    (is (= "bo" (ff H "sqlite3_column_text" [s1 1])))
    ;; done
    (is (= 101 (ff H "sqlite3_step" [s1])))
    (is (= :jolt.sim.sqlite/no-current-row
           (:type (ex-data-of #(ff H "sqlite3_column_int64" [s1 0])))))
    (is (= 21 (ff H "sqlite3_step" [s1])))            ; step after done is misuse
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    ;; second plan (insert) consumed next in FIFO order
    (is (= 1 (:plan-index (sqlite/summary w))))
    (let [s2 (prepare H db "INSERT INTO t VALUES(3)")]
      (is (= 2 (:plan-index (sqlite/summary w))))
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 1 (ff H "sqlite3_changes" [db])))
      (is (= 7 (ff H "sqlite3_last_insert_rowid" [db])))
      (is (= 0 (ff H "sqlite3_errcode" [db])))
      (is (= "not an error" (ff H "sqlite3_errmsg" [db])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- NULL vs empty BLOB + borrowed BLOB lifetime ------------------------

(deftest null-and-empty-blobs-are-distinct
  (let [plans [{:sql "SELECT b FROM t"
                :columns ["b"]
                :rows [[{:type :blob :value [1 2 3]}]
                       [{:type :blob :value []}]
                       [{:type :blob :value [] :null-pointer? true}]
                       [{:type :null}]]}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "SELECT b FROM t")]
    ;; nonempty blob: type 4, borrowed pointer, 3 bytes
    (is (= 100 (ff H "sqlite3_step" [s])))
    (is (= 4 (ff H "sqlite3_column_type" [s 0])))
    (is (= 3 (ff H "sqlite3_column_bytes" [s 0])))
    (let [addr (ff H "sqlite3_column_blob" [s 0])]
      (is (pos? addr))
      (is (= [1 2 3] (vec (native H :read-array addr 3))))
      ;; empty blob: type 4, non-null pointer, 0 bytes; previous borrow freed
      (is (= 100 (ff H "sqlite3_step" [s])))
      (is (= :jolt.sim.ffi-memory/use-after-free
             (:type (ex-data-of #(native H :read-array addr 3)))))
      (is (= 4 (ff H "sqlite3_column_type" [s 0])))
      (is (= 0 (ff H "sqlite3_column_bytes" [s 0])))
      (is (pos? (ff H "sqlite3_column_blob" [s 0])))
      ;; SQLite may also return NULL for an empty BLOB. Its storage class stays
      ;; BLOB and the connection errcode from the current row is SQLITE_ROW.
      (is (= 100 (ff H "sqlite3_step" [s])))
      (is (= 4 (ff H "sqlite3_column_type" [s 0])))
      (is (= 0 (ff H "sqlite3_column_bytes" [s 0])))
      (is (zero? (ff H "sqlite3_column_blob" [s 0])))
      (is (= 100 (ff H "sqlite3_errcode" [db])))
      ;; NULL: type 5, null pointer, 0 bytes
      (is (= 100 (ff H "sqlite3_step" [s])))
      (is (= 5 (ff H "sqlite3_column_type" [s 0])))
      (is (= 0 (ff H "sqlite3_column_bytes" [s 0])))
      (is (zero? (ff H "sqlite3_column_blob" [s 0]))))
    (is (= 101 (ff H "sqlite3_step" [s])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest finalize-releases-borrowed-blob-memory
  (let [plans [{:sql "SELECT b FROM t"
                :columns ["b"]
                :rows [[{:type :blob :value [9 9]}]]}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "SELECT b FROM t")]
    (is (= 100 (ff H "sqlite3_step" [s])))
    (let [addr (ff H "sqlite3_column_blob" [s 0])]
      (is (= [9 9] (vec (native H :read-array addr 2))))
      (is (= 0 (ff H "sqlite3_finalize" [s])))
      (is (= :jolt.sim.ffi-memory/use-after-free
             (:type (ex-data-of #(native H :read-array addr 2))))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- bind: copy-in and exact matching -----------------------------------

(deftest bound-blob-bytes-are-copied-immediately
  (let [plans [{:sql "INSERT INTO t(b) VALUES(?)"
                :params {1 {:type :blob :value [1 2 3]}}}]]
    (let [w (sqlite/world plans)
          H (sqlite/handlers w)
          db (open-db H)
          s (prepare H db "INSERT INTO t(b) VALUES(?)")
          src (native H :alloc 3)]
      (native H :write-array src (byte-array [1 2 3]))
      (is (= 0 (ff H "sqlite3_bind_blob64" [s 1 src 3 0])))
      ;; mutate the source allocation after the bind
      (native H :write-array src (byte-array [9 9 9]))
      ;; the stored binding is an independent immutable copy
      (is (= [1 2 3]
             (get-in (sqlite/state w) [:stmts s :bindings 1 :value])))
      (is (= 0 (ff H "sqlite3_finalize" [s])))
      (native H :free src)
      (close-db H db)
      (is (true? (sqlite/clean? w))))))

(deftest binds-of-every-type-match-the-plan
  (let [plans [{:sql "INSERT INTO t VALUES(?,?,?,?,?)"
                :params {1 {:type :integer :value 5}
                         2 {:type :float :value 1.25}
                         3 {:type :text :value "hi"}
                         4 {:type :blob :value [7 8]}
                         5 {:type :null}}}]]
    (let [w (sqlite/world plans)
          H (sqlite/handlers w)
          db (open-db H)
          s (prepare H db "INSERT INTO t VALUES(?,?,?,?,?)")]
      (is (= 0 (ff H "sqlite3_bind_int64" [s 1 5])))
      (is (= 0 (ff H "sqlite3_bind_double" [s 2 1.25])))
      (is (= 0 (ff H "sqlite3_bind_text" [s 3 "hi" 2 0])))
      (let [blob (native H :alloc 2)]
        (native H :write-array blob (byte-array [7 8]))
        (is (= 0 (ff H "sqlite3_bind_blob64" [s 4 blob 2 0])))
        (native H :free blob))
      (is (= 0 (ff H "sqlite3_bind_null" [s 5])))
      (is (= 0 (ff H "sqlite3_finalize" [s])))
      (close-db H db)
      (is (true? (sqlite/clean? w))))))

(deftest parameter-mismatches-are-typed
  (let [plans [{:sql "Q" :params {1 {:type :integer :value 5}}}]]
    (let [w (sqlite/world plans)
          H (sqlite/handlers w)
          db (open-db H)
          s (prepare H db "Q")]
      (is (= :jolt.sim.sqlite/parameter-mismatch
             (:type (ex-data-of #(ff H "sqlite3_bind_int64" [s 1 6])))))
      (is (= :jolt.sim.sqlite/parameter-mismatch
             (:type (ex-data-of #(ff H "sqlite3_bind_text" [s 1 "x" 1 0])))))
      (is (= :jolt.sim.sqlite/parameter-mismatch
             (:type (ex-data-of #(ff H "sqlite3_bind_int64" [s 2 5])))))
      (ff H "sqlite3_finalize" [s])
      (close-db H db))))

(deftest step-rejects-required-parameters-that-were-never-bound
  (let [w (sqlite/world
           [{:sql "INSERT INTO t VALUES(?)"
             :params {1 {:type :integer :value 5}}
             :changes 1}])
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "INSERT INTO t VALUES(?)")]
    (is (= :jolt.sim.sqlite/parameter-mismatch
           (:type (ex-data-of #(ff H "sqlite3_step" [s])))))
    (is (= 0 (ff H "sqlite3_bind_int64" [s 1 5])))
    (is (= 101 (ff H "sqlite3_step" [s])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest plan-blob-values-are-copied-when-the-world-is-created
  (let [configured (byte-array [1 2 3])
        w (sqlite/world
           [{:sql "SELECT b"
             :columns ["b"]
             :rows [[{:type :blob :value configured}]]}])
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "SELECT b")]
    (aset configured 0 9)
    (is (= 100 (ff H "sqlite3_step" [s])))
    (let [addr (ff H "sqlite3_column_blob" [s 0])]
      (is (= [1 2 3] (vec (native H :read-array addr 3)))))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- soft error plan ----------------------------------------------------

(deftest soft-step-error-is-served-via-errcode-and-errmsg
  (let [plans [{:sql "INSERT INTO uniq(x) VALUES(1)"
                :error {:code 19 :msg "UNIQUE constraint failed"}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "INSERT INTO uniq(x) VALUES(1)")]
    (is (= 19 (ff H "sqlite3_step" [s])))
    (is (= 19 (ff H "sqlite3_errcode" [db])))
    (is (= "UNIQUE constraint failed" (ff H "sqlite3_errmsg" [db])))
    (is (= 21 (ff H "sqlite3_step" [s])))             ; step after error is misuse
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- mismatch / lifetime / range errors ---------------------------------

(deftest sql-mismatch-and-plan-exhaustion-are-typed
  (let [w (sqlite/world [{:sql "RIGHT"}])
        H (sqlite/handlers w)
        db (open-db H)]
    (is (= :jolt.sim.sqlite/sql-mismatch
           (:type (prepare-ex-data H db "WRONG"))))
    ;; the mismatched prepare still consumed the one plan: next prepare exhausts
    (is (= :jolt.sim.sqlite/plan-exhausted
           (:type (prepare-ex-data H db "anything"))))
    (close-db H db)))

(deftest handle-lifetime-and-range-errors-are-typed
  (let [w (sqlite/world [{:sql "SELECT 1" :columns ["c"]
                          :rows [[{:type :integer :value 1}]]}])
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "SELECT 1")]
    (is (= :jolt.sim.sqlite/unknown-handle
           (:type (ex-data-of #(ff H "sqlite3_changes" [99999])))))
    (is (= :jolt.sim.sqlite/no-current-row
           (:type (ex-data-of #(ff H "sqlite3_column_text" [s 0])))))
    (is (= :jolt.sim.sqlite/column-out-of-range
           (:type (ex-data-of #(ff H "sqlite3_column_name" [s 99])))))
    (ff H "sqlite3_finalize" [s])
    (is (= :jolt.sim.sqlite/use-after-finalize
           (:type (ex-data-of #(ff H "sqlite3_step" [s])))))
    (close-db H db)
    (is (= :jolt.sim.sqlite/use-after-close
           (:type (ex-data-of #(ff H "sqlite3_changes" [db])))))
    ;; after close, the connection handle is freed in the memory world
    (is (= :jolt.sim.ffi-memory/use-after-free
           (:type (ex-data-of #(native H :read db :pointer)))))))

;; ---- merged native operations still work --------------------------------

(deftest foreign-handlers-exclude-shared-native-operations
  (let [w (sqlite/world [])
        foreign (sqlite/foreign-handlers w)]
    (is (= 22 (count foreign)))
    (is (= (set sqlite/handler-keys) (set (keys foreign))))
    (is (every? fn? (vals foreign)))
    (is (every? #(= :foreign-function (first %)) (keys foreign)))
    (is (empty? (filter #(= :native-operation (first %)) (keys foreign))))))

(deftest native-memory-operations-still-work-through-the-merged-handlers
  (let [w (sqlite/world [])
        H (sqlite/handlers w)
        p (native H :alloc 4)]
    (native H :write p :int 0 42)
    (is (= 42 (native H :read p :int)))
    (is (false? (sqlite/clean? w)))
    (native H :free p)
    (is (true? (sqlite/clean? w)))
    (is (empty? (sqlite/leaks w)))))
