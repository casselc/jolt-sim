(ns jolt.sim.sqlite-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

;; ---- harness ------------------------------------------------------------

(def ^:private key-by-symbol
  (into {} (map (fn [k] [(nth k 1) k]) sqlite/handler-keys)))

(def ^:private tx-effect-decision-var
  (resolve 'jolt.sim.sqlite/tx-effect-decision))

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

(defn- await-worker [worker]
  (let [result (deref worker 6000 ::timeout)]
    (when (= ::timeout result)
      (future-cancel worker))
    result))

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

(defn- bind-cell!
  "Binds one modeled SQLite cell through the same public FFI surface used by
  db.sqlite. BLOB storage is released immediately after sqlite3_bind_blob64,
  making every row-effect assertion also exercise the model's copy-in rule."
  [H stmt index cell]
  (case (:type cell)
    :integer
    (is (= 0 (ff H "sqlite3_bind_int64" [stmt index (:value cell)])))

    :float
    (is (= 0 (ff H "sqlite3_bind_double"
                 [stmt index (double (:value cell))])))

    :text
    (let [value (:value cell)
          n (alength (.getBytes value "UTF-8"))]
      (is (= 0 (ff H "sqlite3_bind_text" [stmt index value n 0]))))

    :blob
    (let [bytes (vec (or (:value cell) []))]
      (if (empty? bytes)
        (is (= 0 (ff H "sqlite3_bind_blob64" [stmt index 0 0 0])))
        (let [ptr (native H :alloc (count bytes))]
          (try
            (native H :write-array ptr (byte-array bytes))
            (is (= 0 (ff H "sqlite3_bind_blob64"
                         [stmt index ptr (count bytes) 0])))
            (finally
              (native H :free ptr))))))

    :null
    (is (= 0 (ff H "sqlite3_bind_null" [stmt index])))))

(defn- run-row-statement!
  [H db sql bindings]
  (let [stmt (prepare H db sql)]
    (doseq [[index cell] bindings]
      (bind-cell! H stmt index cell))
    (let [result (ff H "sqlite3_step" [stmt])]
      (is (= 0 (ff H "sqlite3_finalize" [stmt])))
      result)))

(defn- modeled-row-key [table key-cells]
  [:jolt.sim.sqlite/row table key-cells])

;; ---- handler shape ------------------------------------------------------

(deftest handlers-merge-23-sqlite-keys-and-16-native-ops
  (let [w (sqlite/world [])
        h (sqlite/handlers w)
        h-keys (set (keys h))
        ff-keys (set sqlite/handler-keys)
        native-keys (set (map #(vec [:native-operation %]) native-ops))]
    (is (= 23 (count ff-keys)))
    (is (= ff-keys (set (filter #(= :foreign-function (nth % 0)) h-keys))))
    (is (= native-keys (set (filter #(= :native-operation (nth % 0)) h-keys))))
    (is (= 39 (count h-keys)))
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

;; ---- physical transaction boundary ---------------------------------------

(defn- tx-event
  [sequence plan-index op when reported applied? reason before after]
  {:sequence sequence
   :plan-index plan-index
   :op op
   :when when
   :reported reported
   :applied? applied?
   :reason reason
   :before-autocommit? before
   :after-autocommit? after})

;; Raw-handler coverage of the R0-R4 begin-recovery outcomes: the model owns
;; the physical autocommit boundary, driven only by the closed :tx-effect plan
;; directive. R5/R7 (a probe handler that throws) are explicitly out of scope
;; here and are never faked as integer returns. R6 is a DB-level routing rule:
;; when the pre-probe observes a pre-existing transaction, ordinary db.sqlite
;; must not issue BEGIN or ROLLBACK. That belongs to the integration slice.

(deftest get-autocommit-tracks-the-physical-transaction-boundary-r0
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "INSERT INTO t VALUES(1)" :changes 1 :last-row-id 3}
               {:sql "COMMIT" :tx-effect {:op :commit}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    ;; R0 substrate: a fresh connection is in autocommit; the probe itself is
    ;; retained as evidence.
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (= {:connection-id 0
            :errcode 0 :errmsg "not an error" :changes 0 :rowid 0
            :autocommit? true :tx nil :tx-evidence []
            :autocommit-evidence [1]
            :committed {} :staging nil :store-evidence []
            :row-evidence []}
           (get-in (sqlite/state w) [:dbs db])))
    (let [s1 (prepare H db "BEGIN")]
      (is (= 101 (ff H "sqlite3_step" [s1])))
      (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
      (is (= {:begin-event 0 :begin-plan-index 0}
             (get-in (sqlite/state w) [:dbs db :tx])))
      (let [s2 (prepare H db "INSERT INTO t VALUES(1)")]
        (is (= 101 (ff H "sqlite3_step" [s2])))
        (is (= 1 (ff H "sqlite3_changes" [db])))
        (is (= 3 (ff H "sqlite3_last_insert_rowid" [db])))
        ;; ordinary statements never move the physical boundary
        (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
        (is (= 0 (ff H "sqlite3_finalize" [s2])))
        (let [s3 (prepare H db "COMMIT")]
          (is (= 101 (ff H "sqlite3_step" [s3])))
          (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
          (is (nil? (get-in (sqlite/state w) [:dbs db :tx])))
          (is (= [(tx-event 0 0 :begin :on-success :done true nil true false)
                  (tx-event 1 2 :commit :on-success :done true nil false true)]
                 (get-in (sqlite/state w) [:dbs db :tx-evidence])))
          (is (= [1 0 0 1]
                 (get-in (sqlite/state w)
                         [:dbs db :autocommit-evidence])))
          (is (= 0 (ff H "sqlite3_finalize" [s3]))))
      (is (= 0 (ff H "sqlite3_finalize" [s1])))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest begin-error-without-a-transition-leaves-autocommit-r1
  (let [plans [{:sql "BEGIN"
                :tx-effect {:op :begin}
                :error {:code 1 :msg "begin failed"}}
               {:sql "SELECT 1" :columns ["c"]
                :rows [[{:type :integer :value 1}]]}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")]
    ;; default :on-success gate: a reported failure applies no transition, so
    ;; the post-probe observes autocommit and no rollback is needed
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 1 (ff H "sqlite3_step" [s])))
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :tx])))
    (is (= [(tx-event 0 0 :begin :on-success :error false
                      :reported-error true true)]
           (get-in (sqlite/state w) [:dbs db :tx-evidence])))
    (is (true? (get-in (sqlite/state w)
                       [:stmts s :tx-effect-evaluated?])))
    (is (= 1 (ff H "sqlite3_errcode" [db])))
    (is (= "begin failed" (ff H "sqlite3_errmsg" [db])))
    (is (= [1 1]
           (get-in (sqlite/state w) [:dbs db :autocommit-evidence])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    ;; the connection stays reusable
    (let [s2 (prepare H db "SELECT 1")]
      (is (= 100 (ff H "sqlite3_step" [s2])))
      (is (= 1 (ff H "sqlite3_column_int64" [s2 0])))
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest begin-transition-then-error-then-rollback-r2
  (let [plans [{:sql "BEGIN"
                :tx-effect {:op :begin :when :always}
                :error {:code 1 :msg "begin outcome uncertain"}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")]
    ;; :always applies the physical transition before the error is reported:
    ;; the uncertain BEGIN. The post-probe observes an active transaction
    ;; despite the reported failure.
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 1 (ff H "sqlite3_step" [s])))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= {:begin-event 0 :begin-plan-index 0}
           (get-in (sqlite/state w) [:dbs db :tx])))
    (is (= [(tx-event 0 0 :begin :always :error true nil true false)]
           (get-in (sqlite/state w) [:dbs db :tx-evidence])))
    (is (= 1 (ff H "sqlite3_errcode" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    ;; the counter-rollback succeeds and the final probe observes autocommit
    (let [s2 (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
      (is (nil? (get-in (sqlite/state w) [:dbs db :tx])))
      (is (= [(tx-event 0 0 :begin :always :error true nil true false)
              (tx-event 1 1 :rollback :on-success :done true nil false true)]
             (get-in (sqlite/state w) [:dbs db :tx-evidence])))
      (is (= [1 0 1]
             (get-in (sqlite/state w) [:dbs db :autocommit-evidence])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest successful-rollback-can-withhold-the-physical-transition-r3
  (let [plans [{:sql "BEGIN"
                :tx-effect {:op :begin :when :always}
                :error {:code 1 :msg "begin outcome uncertain"}}
               {:sql "ROLLBACK"
                :tx-effect {:op :rollback :when :never}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")]
    ;; The uncertain BEGIN physically applies before reporting its error.
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 1 (ff H "sqlite3_step" [s])))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    ;; R3: cleanup reports SQLITE_DONE, but its physical transition is
    ;; deliberately withheld. The final probe therefore still observes an
    ;; active transaction and the connection must be poisoned by db.sqlite.
    (let [s2 (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (true? (get-in (sqlite/state w)
                         [:stmts s2 :tx-effect-evaluated?])))
      (is (= 21 (ff H "sqlite3_step" [s2])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= {:begin-event 0 :begin-plan-index 0}
           (get-in (sqlite/state w) [:dbs db :tx])))
    (is (= [(tx-event 0 0 :begin :always :error true nil true false)
            (tx-event 1 1 :rollback :never :done false :withheld false false)]
           (get-in (sqlite/state w) [:dbs db :tx-evidence])))
    (is (= [1 0 0]
           (get-in (sqlite/state w) [:dbs db :autocommit-evidence])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest rollback-error-keeps-the-transaction-active-r4
  (let [plans [{:sql "BEGIN"
                :tx-effect {:op :begin :when :always}
                :error {:code 1 :msg "begin outcome uncertain"}}
               {:sql "ROLLBACK"
                :tx-effect {:op :rollback}
                :error {:code 5 :msg "cannot rollback"}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "BEGIN")]
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 1 (ff H "sqlite3_step" [s1])))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    ;; a reported rollback failure under the default :on-success gate applies
    ;; no transition: the transaction is still physically active afterwards
    (let [s2 (prepare H db "ROLLBACK")]
      (is (= 5 (ff H "sqlite3_step" [s2])))
      (is (= {:begin-event 0 :begin-plan-index 0}
             (get-in (sqlite/state w) [:dbs db :tx])))
      (is (= [(tx-event 0 0 :begin :always :error true nil true false)
              (tx-event 1 1 :rollback :on-success :error false
                        :reported-error false false)]
             (get-in (sqlite/state w) [:dbs db :tx-evidence])))
      (is (= [1 0]
             (get-in (sqlite/state w) [:dbs db :autocommit-evidence])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    ;; db.sqlite does not attempt another cleanup after the rollback error.
    ;; Closing the poisoned connection discards the still-active transaction.
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest begin-while-active-is-a-hard-model-error
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [s1])))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    ;; with a pre-existing active transaction an extra BEGIN is an impossible
    ;; applied transition: a typed hard failure, never a faked return code
    (let [s2 (prepare H db "BEGIN")
          data (ex-data-of #(ff H "sqlite3_step" [s2]))]
      (is (= :jolt.sim.sqlite/impossible-tx-transition (:type data)))
      (is (= :begin (:op data)))
      (is (= false (:autocommit? data)))
      (is (= :done (:reported data)))
      (is (= 0 (:connection-id data)))
      (is (= 1 (:plan-index data)))
      (is (true? (get-in (sqlite/state w)
                         [:stmts s2 :tx-effect-evaluated?])))
      (is (true? (get-in (sqlite/state w) [:stmts s2 :errored?])))
      (is (= 21 (ff H "sqlite3_step" [s2])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    ;; the pre-existing transaction is untouched and still active
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= [(tx-event 0 0 :begin :on-success :done true nil true false)
            (tx-event 1 1 :begin :on-success :done false
                      :impossible-state false false)]
           (get-in (sqlite/state w) [:dbs db :tx-evidence])))
    (let [s3 (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [s3])))
      (is (= 0 (ff H "sqlite3_finalize" [s3]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest tx-effect-applies-at-most-once-at-the-first-terminal-step
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [s])))
    (is (true? (get-in (sqlite/state w)
                       [:stmts s :tx-effect-evaluated?])))
    (is (= 1 (count (get-in (sqlite/state w) [:dbs db :tx-evidence]))))
    ;; steps after the terminal step are misuse and never reapply the effect
    (is (= 21 (ff H "sqlite3_step" [s])))
    (is (= 21 (ff H "sqlite3_step" [s])))
    (is (= 1 (count (get-in (sqlite/state w) [:dbs db :tx-evidence]))))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (let [s2 (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 2 (count (get-in (sqlite/state w) [:dbs db :tx-evidence]))))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest concurrent-terminal-step-has-exactly-one-reported-winner
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")
        original @tx-effect-decision-var
        arrivals (atom 0)
        both-inside (promise)
        wrapped-decision
        (fn [db-state stmt reported]
          (let [arrival (swap! arrivals inc)]
            ;; The first two callers have both read the same nonterminal
            ;; statement snapshot before either can attempt its CAS. This
            ;; discriminates atomic terminal ownership from a merely
            ;; serialized scheduling of two h-step calls.
            (when (<= arrival 2)
              (when (= arrival 2)
                (deliver both-inside true))
              (when (= ::timeout (deref both-inside 5000 ::timeout))
                (throw (ex-info "terminal-step contention barrier timed out"
                                {:arrival arrival}))))
            (original db-state stmt reported)))
        run-step (fn []
                   (try
                     {:value (ff H "sqlite3_step" [s])}
                     (catch :default error
                       {:error (ex-data error)})))
        results
        (with-redefs-fn
          {tx-effect-decision-var wrapped-decision}
          #(let [a (future (run-step))
                 b (future (run-step))]
             [(deref a 5000 ::timeout)
              (deref b 5000 ::timeout)]))]
    (is (= 2 @arrivals))
    (is (not-any? #{::timeout} results) (pr-str results))
    (is (every? map? results) (pr-str results))
    (when (every? map? results)
      (is (every? #(contains? % :value) results) (pr-str results))
      (is (= #{21 101} (set (map :value results))) (pr-str results)))
    (is (= 1 (count (get-in (sqlite/state w) [:dbs db :tx-evidence]))))
    (is (true? (get-in (sqlite/state w)
                       [:stmts s :tx-effect-evaluated?])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (let [rollback (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [rollback])))
      (is (= 0 (ff H "sqlite3_finalize" [rollback]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest close-claims-current-forensic-state-before-freeing-the-handle
  (let [base (sqlite/world [{:sql "BEGIN" :tx-effect {:op :begin}}])
        target (atom nil)
        free-entered (promise)
        allow-free (promise)
        original-free (get-in base
                              [:memory-handlers
                               [:native-operation :free]])
        w (assoc-in
           base
           [:memory-handlers [:native-operation :free]]
           (fn [effect]
             (when (= @target (first (:arguments effect)))
               (deliver free-entered true)
               @allow-free)
             (original-free effect)))
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")
        _ (reset! target db)
        closing
        (future
          (try
            {:value (ff H "sqlite3_close_v2" [db])}
            (catch :default error
              {:error (ex-data error)})))]
    (try
      (is (= true (deref free-entered 5000 ::timeout)))
      ;; State ownership precedes the potentially blocking/freeing native
      ;; action. A concurrent statement cannot append to a stale DB snapshot
      ;; or recreate the removed connection record under its freed address.
      (is (not (contains? (:dbs (sqlite/state w)) db)))
      (is (= 1 (count (:closed-db-evidence (sqlite/state w)))))
      (is (= [] (get-in (sqlite/state w)
                        [:closed-db-evidence 0 :tx-evidence])))
      (let [data (ex-data-of #(ff H "sqlite3_step" [s]))]
        (is (= :jolt.sim.sqlite/use-after-close (:type data)))
        (is (= 0 (:connection-id data)))
        (is (= 0 (:plan-index data))))
      (is (not (contains? (:dbs (sqlite/state w)) db)))
      (finally
        (deliver allow-free true)))
    (is (= {:value 0} (deref closing 5000 ::timeout)))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (is (true? (sqlite/clean? w)))))

(deftest terminal-step-branches-preserve-connection-transaction-fields
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "INSERT INTO uniq(x) VALUES(1)"
                :error {:code 19 :msg "UNIQUE constraint failed"}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "BEGIN")]
    ;; the done branch publishes result state without erasing tx fields
    (is (= 101 (ff H "sqlite3_step" [s1])))
    (is (= false (get-in (sqlite/state w) [:dbs db :autocommit?])))
    (is (= {:begin-event 0 :begin-plan-index 0}
           (get-in (sqlite/state w) [:dbs db :tx])))
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    ;; the error branch updates errcode/errmsg/changes/rowid in place too
    (let [s2 (prepare H db "INSERT INTO uniq(x) VALUES(1)")]
      (is (= 19 (ff H "sqlite3_step" [s2])))
      (let [db-state (get-in (sqlite/state w) [:dbs db])]
        (is (= 19 (:errcode db-state)))
        (is (= "UNIQUE constraint failed" (:errmsg db-state)))
        (is (= false (:autocommit? db-state)))
        (is (= {:begin-event 0 :begin-plan-index 0} (:tx db-state)))
        (is (= [(tx-event 0 0 :begin :on-success :done true nil true false)]
               (:tx-evidence db-state))))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (let [s3 (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [s3])))
      (is (= 0 (ff H "sqlite3_finalize" [s3]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest close-discards-an-active-transaction-and-retains-complete-evidence
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "INSERT INTO t VALUES(9)"
                :changes 4 :last-row-id 9}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [s])))
    (is (= 0 (ff H "sqlite3_get_autocommit" [db])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (let [insert (prepare H db "INSERT INTO t VALUES(9)")]
      (is (= 101 (ff H "sqlite3_step" [insert])))
      (is (= 0 (ff H "sqlite3_finalize" [insert]))))
    (close-db H db)
    ;; the live record is gone; the close-time forensic snapshot remains
    (is (not (contains? (:dbs (sqlite/state w)) db)))
    (is (= {:connection-id 0
            :errcode 0 :errmsg "not an error" :changes 4 :rowid 9
            :autocommit? false
            :tx {:begin-event 0 :begin-plan-index 0}
            :tx-evidence
            [(tx-event 0 0 :begin :on-success :done true nil true false)]
            :autocommit-evidence [0]
            :committed {} :staging {} :store-evidence []
            :row-evidence []
            :close-index 0
            :discarded-transaction {:begin-event 0 :begin-plan-index 0}
            :discarded-staging {}}
           (first (:closed-db-evidence (sqlite/state w)))))
    (is (= :jolt.sim.sqlite/use-after-close
           (:type (ex-data-of #(ff H "sqlite3_get_autocommit" [db])))))
    ;; a connection closed in autocommit leaves evidence with no discard
    (let [db2 (open-db H)]
      (close-db H db2)
      (let [evidence (second (:closed-db-evidence (sqlite/state w)))]
        (is (= {:connection-id 1
                :errcode 0 :errmsg "not an error" :changes 0 :rowid 0
                :autocommit? true :tx nil :tx-evidence []
                :autocommit-evidence []
                :committed {} :staging nil :store-evidence []
                :row-evidence []
                :close-index 1}
               evidence))))
    (is (true? (sqlite/clean? w)))))

(deftest tx-effect-is-validated-and-nothing-is-inferred-from-sql-text
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world [{:sql "BEGIN" :tx-effect "begin"}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world [{:sql "BEGIN" :tx-effect {}}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world [{:sql "BEGIN" :tx-effect {:op :savepoint}}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "BEGIN"
                     :tx-effect {:op :begin :when :sometimes}}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "BEGIN"
                     :tx-effect {:op :begin :extra 1}}])))))
  (doseq [success-code [0 100 101]]
    (is (= :jolt.sim.sqlite/invalid-plan
           (:type
            (ex-data-of
             #(sqlite/world
               [{:sql "BEGIN"
                 :tx-effect {:op :begin}
                 :error {:code success-code}}]))))))
  (is (= [:a :z]
         (:unknown-keys
          (ex-data-of
           #(sqlite/world
             [{:sql "BEGIN"
               :tx-effect {:op :begin :z 1 :a 2}}])))))
  ;; :when defaults to :on-success at validation time
  (let [w (sqlite/world [{:sql "ROLLBACK" :tx-effect {:op :rollback}}])]
    (is (= {:op :rollback :when :on-success}
           (get-in (sqlite/state w) [:plans 0 :tx-effect]))))
  ;; transaction-control SQL text without a directive changes nothing
  (let [w (sqlite/world [{:sql "BEGIN"} {:sql "COMMIT"}])
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [s1])))
    (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :tx])))
    (is (= [] (get-in (sqlite/state w) [:dbs db :tx-evidence])))
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    (let [s2 (prepare H db "COMMIT")]
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 1 (ff H "sqlite3_get_autocommit" [db])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- physical store visibility boundary ---------------------------------

(deftest store-effect-autocommit-put-then-get-hits
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 42}
                         2 {:type :text :value "hello"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET k"
                :params {1 {:type :integer :value 42}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put (prepare H db "PUT k v")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put 1 42])))
    (is (= 0 (ff H "sqlite3_bind_text" [put 2 "hello" 5 0])))
    (is (= 101 (ff H "sqlite3_step" [put])))
    (is (= {{:type :integer :value 42} {:type :text :value "hello"}}
           (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    (is (= 0 (ff H "sqlite3_finalize" [put])))
    (let [get (prepare H db "GET k")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 42])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= 3 (ff H "sqlite3_column_type" [get 0])))
      (is (= "hello" (ff H "sqlite3_column_text" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (is (= [{:sequence 0 :plan-index 0 :op :put :reported :done
             :key {:type :integer :value 42}
             :location :committed :present? true}
            {:sequence 1 :plan-index 1 :op :get :reported :done
             :key {:type :integer :value 42}
             :location :committed :present? true}]
           (get-in (sqlite/state w) [:dbs db :store-evidence])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-rollback-discards-staged-put-so-get-misses
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "PUT k v"
                :params {1 {:type :integer :value 1}
                         2 {:type :integer :value 100}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}
               {:sql "GET k"
                :params {1 {:type :integer :value 1}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        begin (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [begin])))
    (is (= 0 (ff H "sqlite3_finalize" [begin])))
    (let [put (prepare H db "PUT k v")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put 1 1])))
      (is (= 0 (ff H "sqlite3_bind_int64" [put 2 100])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= {{:type :integer :value 1} {:type :integer :value 100}}
             (get-in (sqlite/state w) [:dbs db :staging])))
      (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [rollback (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [rollback])))
      (is (= 0 (ff H "sqlite3_finalize" [rollback]))))
    (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    (let [get (prepare H db "GET k")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 1])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-commit-merges-staged-put-so-get-hits
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "PUT k v"
                :params {1 {:type :integer :value 1}
                         2 {:type :integer :value 100}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "COMMIT" :tx-effect {:op :commit}}
               {:sql "GET k"
                :params {1 {:type :integer :value 1}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        begin (prepare H db "BEGIN")]
    (is (= 101 (ff H "sqlite3_step" [begin])))
    (is (= 0 (ff H "sqlite3_finalize" [begin])))
    (let [put (prepare H db "PUT k v")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put 1 1])))
      (is (= 0 (ff H "sqlite3_bind_int64" [put 2 100])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [commit (prepare H db "COMMIT")]
      (is (= 101 (ff H "sqlite3_step" [commit])))
      (is (= 0 (ff H "sqlite3_finalize" [commit]))))
    (is (= {{:type :integer :value 1} {:type :integer :value 100}}
           (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    (let [get (prepare H db "GET k")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 1])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= 100 (ff H "sqlite3_column_int64" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-staged-value-shadows-older-committed-value
  (let [plans [{:sql "PUT k old"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "old"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "PUT k new"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "new"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET k"
                :params {1 {:type :integer :value 1}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}
               {:sql "ROLLBACK" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put1 (prepare H db "PUT k old")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put1 1 1])))
    (is (= 0 (ff H "sqlite3_bind_text" [put1 2 "old" 3 0])))
    (is (= 101 (ff H "sqlite3_step" [put1])))
    (is (= 0 (ff H "sqlite3_finalize" [put1])))
    (let [begin (prepare H db "BEGIN")]
      (is (= 101 (ff H "sqlite3_step" [begin])))
      (is (= 0 (ff H "sqlite3_finalize" [begin]))))
    (let [put2 (prepare H db "PUT k new")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put2 1 1])))
      (is (= 0 (ff H "sqlite3_bind_text" [put2 2 "new" 3 0])))
      (is (= 101 (ff H "sqlite3_step" [put2])))
      (is (= 0 (ff H "sqlite3_finalize" [put2]))))
    ;; the staged write shadows the get; the older committed value is untouched
    (let [get (prepare H db "GET k")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 1])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= "new" (ff H "sqlite3_column_text" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (is (= "old"
           (:value (get (get-in (sqlite/state w) [:dbs db :committed])
                        {:type :integer :value 1}))))
    (is (= "new"
           (:value (get (get-in (sqlite/state w) [:dbs db :staging])
                        {:type :integer :value 1}))))
    (let [rollback (prepare H db "ROLLBACK")]
      (is (= 101 (ff H "sqlite3_step" [rollback])))
      (is (= 0 (ff H "sqlite3_finalize" [rollback]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-errored-put-does-not-write
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 5}
                         2 {:type :integer :value 9}}
                :store-effect {:op :put :key-param 1 :value-param 2}
                :error {:code 1 :msg "put failed"}}
               {:sql "GET k"
                :params {1 {:type :integer :value 5}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put (prepare H db "PUT k v")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put 1 5])))
    (is (= 0 (ff H "sqlite3_bind_int64" [put 2 9])))
    (is (= 1 (ff H "sqlite3_step" [put])))
    (is (= 1 (ff H "sqlite3_errcode" [db])))
    (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    (is (= 21 (ff H "sqlite3_step" [put])))          ; step after error is misuse
    (is (= 0 (ff H "sqlite3_finalize" [put])))
    (let [get (prepare H db "GET k")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 5])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-errored-get-records-one-attempt-not-a-miss
  (let [plans [{:sql "GET k"
                :params {1 {:type :integer :value 5}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}
                :error {:code 5 :msg "read failed"}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        get (prepare H db "GET k")]
    (is (= 0 (ff H "sqlite3_bind_int64" [get 1 5])))
    (is (= 5 (ff H "sqlite3_step" [get])))
    (is (= 5 (ff H "sqlite3_errcode" [db])))
    (is (= [{:sequence 0 :plan-index 0 :op :get :reported :error
             :key {:type :integer :value 5}
             :location nil :present? false}]
           (get-in (sqlite/state w) [:dbs db :store-evidence])))
    (is (= 21 (ff H "sqlite3_step" [get])))
    (is (= 0 (ff H "sqlite3_finalize" [get])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-preserves-null-and-empty-blob-result-shapes
  (let [plans [{:sql "PUT null"
                :params {1 {:type :integer :value 1}
                         2 {:type :null}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET null"
                :params {1 {:type :integer :value 1}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}
               {:sql "PUT empty"
                :params {1 {:type :integer :value 2}
                         2 {:type :blob :value []}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET empty"
                :params {1 {:type :integer :value 2}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}
               {:sql "PUT empty-null-pointer"
                :params {1 {:type :integer :value 3}
                         2 {:type :blob :value [] :null-pointer? true}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET empty-null-pointer"
                :params {1 {:type :integer :value 3}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    (let [put (prepare H db "PUT null")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put 1 1])))
      (is (= 0 (ff H "sqlite3_bind_null" [put 2])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [get (prepare H db "GET null")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 1])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= 5 (ff H "sqlite3_column_type" [get 0])))
      (is (nil? (ff H "sqlite3_column_text" [get 0])))
      (is (zero? (ff H "sqlite3_column_blob" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (let [put (prepare H db "PUT empty")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put 1 2])))
      (is (= 0 (ff H "sqlite3_bind_blob64" [put 2 0 0 0])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [get (prepare H db "GET empty")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 2])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= 4 (ff H "sqlite3_column_type" [get 0])))
      (is (zero? (ff H "sqlite3_column_bytes" [get 0])))
      (is (pos? (ff H "sqlite3_column_blob" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (let [put (prepare H db "PUT empty-null-pointer")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put 1 3])))
      (is (= 0 (ff H "sqlite3_bind_blob64" [put 2 0 0 0])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [get (prepare H db "GET empty-null-pointer")]
      (is (= 0 (ff H "sqlite3_bind_int64" [get 1 3])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= 4 (ff H "sqlite3_column_type" [get 0])))
      (is (zero? (ff H "sqlite3_column_bytes" [get 0])))
      (is (zero? (ff H "sqlite3_column_blob" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (is (= {:type :null}
           (get-in (sqlite/state w)
                   [:dbs db :committed {:type :integer :value 1}])))
    (is (= {:type :blob :value []}
           (get-in (sqlite/state w)
                   [:dbs db :committed {:type :integer :value 2}])))
    (is (= {:type :blob :value [] :null-pointer? true}
           (get-in (sqlite/state w)
                   [:dbs db :committed {:type :integer :value 3}])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-key-identity-comes-from-the-canonical-binding
  (let [plans [{:sql "PUT blob-key"
                :params {1 {:type :blob :value [] :null-pointer? false}
                         2 {:type :text :value "blob-hit"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET blob-key"
                :params {1 {:type :blob :value []}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}
               {:sql "PUT float-key"
                :params {1 {:type :float :value 1}
                         2 {:type :text :value "float-hit"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET float-key"
                :params {1 {:type :float :value 1.0}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    (let [put (prepare H db "PUT blob-key")]
      (is (= 0 (ff H "sqlite3_bind_blob64" [put 1 0 0 0])))
      (is (= 0 (ff H "sqlite3_bind_text" [put 2 "blob-hit" 8 0])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [get (prepare H db "GET blob-key")]
      (is (= 0 (ff H "sqlite3_bind_blob64" [get 1 0 0 0])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= "blob-hit" (ff H "sqlite3_column_text" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (let [put (prepare H db "PUT float-key")]
      (is (= 0 (ff H "sqlite3_bind_double" [put 1 1.0])))
      (is (= 0 (ff H "sqlite3_bind_text" [put 2 "float-hit" 9 0])))
      (is (= 101 (ff H "sqlite3_step" [put])))
      (is (= 0 (ff H "sqlite3_finalize" [put]))))
    (let [get (prepare H db "GET float-key")]
      (is (= 0 (ff H "sqlite3_bind_double" [get 1 1.0])))
      (is (= 100 (ff H "sqlite3_step" [get])))
      (is (= "float-hit" (ff H "sqlite3_column_text" [get 0])))
      (is (= 101 (ff H "sqlite3_step" [get])))
      (is (= 0 (ff H "sqlite3_finalize" [get]))))
    (is (contains? (get-in (sqlite/state w) [:dbs db :committed])
                   {:type :blob :value []}))
    (is (contains? (get-in (sqlite/state w) [:dbs db :committed])
                   {:type :float :value 1.0}))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest close-evidence-retains-committed-state-and-discarded-active-staging
  (let [plans [{:sql "PUT k1 v1"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "committed"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "PUT k2 v2"
                :params {1 {:type :integer :value 2}
                         2 {:type :text :value "staged"}}
                :store-effect {:op :put :key-param 1 :value-param 2}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put1 (prepare H db "PUT k1 v1")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put1 1 1])))
    (is (= 0 (ff H "sqlite3_bind_text" [put1 2 "committed" 9 0])))
    (is (= 101 (ff H "sqlite3_step" [put1])))
    (is (= 0 (ff H "sqlite3_finalize" [put1])))
    (let [begin (prepare H db "BEGIN")]
      (is (= 101 (ff H "sqlite3_step" [begin])))
      (is (= 0 (ff H "sqlite3_finalize" [begin]))))
    (let [put2 (prepare H db "PUT k2 v2")]
      (is (= 0 (ff H "sqlite3_bind_int64" [put2 1 2])))
      (is (= 0 (ff H "sqlite3_bind_text" [put2 2 "staged" 6 0])))
      (is (= 101 (ff H "sqlite3_step" [put2])))
      (is (= 0 (ff H "sqlite3_finalize" [put2]))))
    ;; close without commit: the active staged write is discarded, but the
    ;; earlier autocommit write survives in the close-time evidence
    (close-db H db)
    (let [evidence (first (:closed-db-evidence (sqlite/state w)))]
      (is (= {{:type :integer :value 1} {:type :text :value "committed"}}
             (:committed evidence)))
      (is (= {{:type :integer :value 2} {:type :text :value "staged"}}
             (:staging evidence)))
      (is (= (:staging evidence) (:discarded-staging evidence))))
    (is (true? (sqlite/clean? w)))))

(deftest store-effect-is-validated-and-nothing-is-inferred-from-sql-text
  ;; unknown op
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X" :store-effect {:op :delete :key-param 1}}])))))
  ;; unknown key for :put
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}
                              2 {:type :integer :value 2}}
                     :store-effect
                     {:op :put :key-param 1 :value-param 2 :extra 1}}])))))
  ;; unknown key for :get (:value-param is not allowed)
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}
                              2 {:type :integer :value 2}}
                     :columns ["v"]
                     :store-effect {:op :get :key-param 1 :value-param 2}}])))))
  ;; missing key-param
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world [{:sql "X" :store-effect {:op :get}}])))))
  ;; non-positive key-param
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :columns ["v"]
                     :store-effect {:op :get :key-param 0}}])))))
  ;; missing value-param for :put
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :store-effect {:op :put :key-param 1}}])))))
  ;; both :tx-effect and :store-effect on one plan
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}
                              2 {:type :integer :value 2}}
                     :tx-effect {:op :begin}
                     :store-effect {:op :put :key-param 1 :value-param 2}}])))))
  ;; a :get plan with nonempty predeclared :rows
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :columns ["v"]
                     :rows [[{:type :integer :value 9}]]
                     :store-effect {:op :get :key-param 1}}])))))
  ;; a :put plan is write-only and cannot enter the generic result-row path
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}
                              2 {:type :integer :value 2}}
                     :columns ["v"]
                     :rows [[{:type :integer :value 2}]]
                     :store-effect {:op :put :key-param 1
                                    :value-param 2}}])))))
  ;; a :get plan without exactly one declared column
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :store-effect {:op :get :key-param 1}}])))))
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :columns ["a" "b"]
                     :store-effect {:op :get :key-param 1}}])))))
  ;; a null key
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :null}}
                     :columns ["v"]
                     :store-effect {:op :get :key-param 1}}])))))
  ;; heterogeneous unknown keys still fail through the typed plan contract
  (is (= :jolt.sim.sqlite/invalid-plan
         (:type (ex-data-of
                 #(sqlite/world
                   [{:sql "X"
                     :params {1 {:type :integer :value 1}}
                     :columns ["v"]
                     :store-effect {:op :get :key-param 1
                                    :extra 1 "extra" 2}}])))))
  ;; SQL text alone never implies a store effect: PUT/GET-shaped SQL with no
  ;; directive touches neither committed nor staging
  (let [w (sqlite/world [{:sql "PUT k v"} {:sql "GET k"}])
        H (sqlite/handlers w)
        db (open-db H)
        s1 (prepare H db "PUT k v")]
    (is (= 101 (ff H "sqlite3_step" [s1])))
    (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    (is (= 0 (ff H "sqlite3_finalize" [s1])))
    (let [s2 (prepare H db "GET k")]
      (is (= 101 (ff H "sqlite3_step" [s2])))
      (is (= 0 (ff H "sqlite3_finalize" [s2]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

;; ---- physical table-row boundary ---------------------------------------

(deftest row-effect-plan-contract-is-closed-and-presence-sensitive
  (let [invalid?
        (fn [plan]
          (= :jolt.sim.sqlite/invalid-plan
             (:type (ex-data-of #(sqlite/world [plan])))))
        params {1 {:type :integer :value 1}
                2 {:type :text :value "v"}}
        insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        update {:op :update-row :table :outbox/items
                :key-params [1] :key-columns ["id"]
                :set [["value" 2]]}
        scan {:op :scan-rows :table :outbox/items
              :project ["id" "value"] :order-key ["id"]}]
    ;; Presence, rather than truthiness or seqability, owns these exclusions.
    ;; An explicitly empty generic result set or column list is still a second
    ;; producer for row-effect results and therefore fails closed.
    (is (invalid? {:sql "insert" :params params :rows []
                   :row-effect insert}))
    (is (invalid? {:sql "scan" :columns [] :row-effect scan}))

    ;; Update identity is declared by a one-to-one column/parameter mapping.
    ;; Parameter position alone must never stand in for key-column identity.
    (is (invalid? {:sql "update" :params params
                   :row-effect (dissoc update :key-columns)}))
    (is (invalid? {:sql "update" :params params
                   :row-effect (assoc update :key-columns [])}))
    (is (invalid? {:sql "update" :params params
                   :row-effect (assoc update :key-columns ["id" "tenant"])}))
    (is (invalid? {:sql "update" :params params
                   :row-effect (assoc update :key-columns ["id" "id"]
                                     :key-params [1 1])}))
    (is (invalid? {:sql "update" :params params
                   :row-effect (assoc update :key-columns [1])}))
    ;; A new value may use a different parameter index, but it may not mutate
    ;; any column that participates in row identity.
    (is (invalid? {:sql "update" :params params
                   :row-effect (assoc update :set [["id" 2]])}))
    (is (map? (sqlite/world [{:sql "update" :params params
                              :row-effect update}])))))

(deftest row-insert-uses-actual-bindings-preserves-only-empty-blob-presentation
  (let [id {:type :integer :value 7 :plan-only :discard-me}
        score {:type :float :value 1 :plan-only :discard-me}
        empty-blob {:type :blob :value [] :null-pointer? true
                    :plan-only :discard-me}
        row-effect {:op :insert-row :table :outbox/items
                    :key-params [1]
                    :row [["id" 1] ["score" 2] ["payload" 3]]}
        plans [{:sql "insert-1"
                :params {1 id 2 score 3 empty-blob}
                :row-effect row-effect}
               {:sql "insert-duplicate"
                :params {1 {:type :integer :value 7}
                         2 {:type :float :value 2}
                         3 {:type :blob :value [9]}}
                :row-effect row-effect}
               {:sql "insert-error"
                :params {1 {:type :integer :value 8}
                         2 {:type :float :value 3}
                         3 {:type :blob :value []}}
                :row-effect row-effect
                :error {:code 5 :msg "write failed"}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        key (modeled-row-key :outbox/items
                             [{:type :integer :value 7}])]
    (is (= 101 (run-row-statement!
                H db "insert-1"
                [[1 {:type :integer :value 7}]
                 [2 {:type :float :value 1.0}]
                 [3 {:type :blob :value []}]])))
    (is (= {"id" {:type :integer :value 7}
            "score" {:type :float :value 1.0}
            "payload" {:type :blob :value [] :null-pointer? true}}
           (get-in (sqlite/state w) [:dbs db :committed key])))
    (is (= 1 (ff H "sqlite3_changes" [db])))

    ;; Duplicate identity is a soft constraint and cannot replace the row.
    (is (= 19 (run-row-statement!
               H db "insert-duplicate"
               [[1 {:type :integer :value 7}]
                [2 {:type :float :value 2.0}]
                [3 {:type :blob :value [9]}]])))
    (is (= 19 (ff H "sqlite3_errcode" [db])))
    (is (= 0 (ff H "sqlite3_changes" [db])))
    (is (= 2 (count (filter #(= key (modeled-row-key
                                     :outbox/items (:key %)))
                            (get-in (sqlite/state w)
                                    [:dbs db :row-evidence])))))
    (is (= 1 (count (filter #(= key %)
                            (keys (get-in (sqlite/state w)
                                          [:dbs db :committed]))))))

    ;; A plan-level failure records one attempted effect but performs no write.
    (is (= 5 (run-row-statement!
              H db "insert-error"
              [[1 {:type :integer :value 8}]
               [2 {:type :float :value 3.0}]
               [3 {:type :blob :value []}]])))
    (is (nil? (get-in (sqlite/state w)
                      [:dbs db :committed
                       (modeled-row-key :outbox/items
                                        [{:type :integer :value 8}])])))
    (is (= [:done :constraint :error]
           (mapv :reported
                 (get-in (sqlite/state w) [:dbs db :row-evidence]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest row-update-hit-miss-and-source-provenance-are-distinct-from-write-target
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        update {:op :update-row :table :outbox/items
                :key-params [1] :key-columns ["id"]
                :set [["value" 2]]}
        plans [{:sql "insert"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "old"}}
                :row-effect insert}
               {:sql "miss"
                :params {1 {:type :integer :value 9}
                         2 {:type :text :value "absent"}}
                :row-effect update}
               {:sql "begin" :tx-effect {:op :begin}}
               {:sql "update-committed"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "staged-1"}}
                :row-effect update}
               {:sql "update-staging"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "staged-2"}}
                :row-effect update}
               {:sql "rollback" :tx-effect {:op :rollback}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        key (modeled-row-key :outbox/items
                             [{:type :integer :value 1}])]
    (is (= 101 (run-row-statement!
                H db "insert"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "old"}]])))
    (is (= 101 (run-row-statement!
                H db "miss"
                [[1 {:type :integer :value 9}]
                 [2 {:type :text :value "absent"}]])))
    (is (= 0 (ff H "sqlite3_changes" [db])))
    (is (= "old" (get-in (sqlite/state w)
                          [:dbs db :committed key "value" :value])))
    (is (= 101 (run-row-statement! H db "begin" [])))
    (is (= 101 (run-row-statement!
                H db "update-committed"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "staged-1"}]])))
    (is (= "old" (get-in (sqlite/state w)
                          [:dbs db :committed key "value" :value])))
    (is (= "staged-1" (get-in (sqlite/state w)
                               [:dbs db :staging key "value" :value])))
    (is (= 101 (run-row-statement!
                H db "update-staging"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "staged-2"}]])))
    (is (= 101 (run-row-statement! H db "rollback" [])))
    (is (= "old" (get-in (sqlite/state w)
                          [:dbs db :committed key "value" :value])))
    (is (= [{:location :committed :source-location nil
             :present? false :applied? true :changes 1}
            {:location nil :source-location nil
             :present? false :applied? false :changes 0}
            {:location :staging :source-location :committed
             :present? true :applied? true :changes 1}
            {:location :staging :source-location :staging
             :present? true :applied? true :changes 1}]
           (mapv #(select-keys % [:location :source-location :present?
                                  :applied? :changes])
                 (get-in (sqlite/state w) [:dbs db :row-evidence]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest row-overlays-scan-provenance-and-commit-rollback-are-explicit
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        scan {:op :scan-rows :table :outbox/items
              :project ["id" "value"] :order-key ["id"]}
        plans [{:sql "insert-1"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "one"}}
                :row-effect insert}
               {:sql "begin-rollback" :tx-effect {:op :begin}}
               {:sql "insert-2-rollback"
                :params {1 {:type :integer :value 2}
                         2 {:type :text :value "two"}}
                :row-effect insert}
               {:sql "scan-mixed" :row-effect scan}
               {:sql "rollback" :tx-effect {:op :rollback}}
               {:sql "scan-committed" :row-effect scan}
               {:sql "begin-commit" :tx-effect {:op :begin}}
               {:sql "insert-2-commit"
                :params {1 {:type :integer :value 2}
                         2 {:type :text :value "two"}}
                :row-effect insert}
               {:sql "commit" :tx-effect {:op :commit}}
               {:sql "scan-after-commit" :row-effect scan}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    (is (= 101 (run-row-statement!
                H db "insert-1"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "one"}]])))
    (is (= 101 (run-row-statement! H db "begin-rollback" [])))
    (is (= 101 (run-row-statement!
                H db "insert-2-rollback"
                [[1 {:type :integer :value 2}]
                 [2 {:type :text :value "two"}]])))
    (let [stmt (prepare H db "scan-mixed")]
      (is (= 100 (ff H "sqlite3_step" [stmt])))
      (is (= 1 (ff H "sqlite3_column_int64" [stmt 0])))
      (is (= 100 (ff H "sqlite3_step" [stmt])))
      (is (= 2 (ff H "sqlite3_column_int64" [stmt 0])))
      (is (= 101 (ff H "sqlite3_step" [stmt])))
      (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
    (is (= 101 (run-row-statement! H db "rollback" [])))
    (let [stmt (prepare H db "scan-committed")]
      (is (= 100 (ff H "sqlite3_step" [stmt])))
      (is (= 1 (ff H "sqlite3_column_int64" [stmt 0])))
      (is (= 101 (ff H "sqlite3_step" [stmt])))
      (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
    (is (= 101 (run-row-statement! H db "begin-commit" [])))
    (is (= 101 (run-row-statement!
                H db "insert-2-commit"
                [[1 {:type :integer :value 2}]
                 [2 {:type :text :value "two"}]])))
    (is (= 101 (run-row-statement! H db "commit" [])))
    (let [stmt (prepare H db "scan-after-commit")]
      (is (= 100 (ff H "sqlite3_step" [stmt])))
      (is (= 100 (ff H "sqlite3_step" [stmt])))
      (is (= 101 (ff H "sqlite3_step" [stmt])))
      (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
    (is (= [:mixed :committed :committed]
           (mapv :location
                 (filter #(= :scan-rows (:op %))
                         (get-in (sqlite/state w)
                                 [:dbs db :row-evidence])))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest scan-order-is-type-first-exact-and-independent-of-insertion-order
  (let [scan-order
        (fn [cells]
          (let [insert {:op :insert-row :table :outbox/order
                        :key-params [1]
                        :row [["id" 1]]}
                plans (conj (mapv (fn [i cell]
                                    {:sql (str "insert-" i)
                                     :params {1 cell}
                                     :row-effect insert})
                                  (range (count cells)) cells)
                            {:sql "scan"
                             :row-effect
                             {:op :scan-rows :table :outbox/order
                              :project ["id"] :order-key ["id"]}})
                w (sqlite/world plans)
                H (sqlite/handlers w)
                db (open-db H)]
            (doseq [[i cell] (map-indexed vector cells)]
              (is (= 101 (run-row-statement!
                          H db (str "insert-" i) [[1 cell]]))))
            (let [stmt (prepare H db "scan")
                  values
                  (loop [out []]
                    (let [result (ff H "sqlite3_step" [stmt])]
                      (if (= 100 result)
                        (let [type-code (ff H "sqlite3_column_type" [stmt 0])]
                          (recur
                           (conj out
                                 (if (= 1 type-code)
                                   [:integer
                                    (ff H "sqlite3_column_int64" [stmt 0])]
                                   [:float
                                    (ff H "sqlite3_column_double" [stmt 0])]))))
                        (do
                          (is (= 101 result))
                          out))))]
              (is (= 0 (ff H "sqlite3_finalize" [stmt])))
              (close-db H db)
              (is (true? (sqlite/clean? w)))
              values)))
        int-small {:type :integer :value 1}
        float-small {:type :float :value 1.0}
        int-wide-a {:type :integer :value 9007199254740992}
        int-wide-b {:type :integer :value 9007199254740993}
        expected [[:integer 1]
                  [:integer 9007199254740992]
                  [:integer 9007199254740993]
                  [:float 1.0]]]
    ;; Type-first means INTEGER always precedes FLOAT; exact integer compare
    ;; then distinguishes adjacent values which collapse to one IEEE double.
    (is (= expected
           (scan-order [float-small int-wide-b int-small int-wide-a])))
    (is (= expected
           (scan-order [int-wide-a int-small int-wide-b float-small])))))

(deftest defensive-float-order-puts-nan-last-with-a-stable-key-tiebreak
  ;; A NaN parameter cannot pass the public exact-binding contract because
  ;; NaN is not equal to itself. This is deliberately a narrow control of the
  ;; defensive comparator branch, not a fake row inserted into world state.
  (let [compare-entries @(resolve 'jolt.sim.sqlite/compare-scan-entries)
        entry (fn [label key value]
                {:label label
                 :key-cells [{:type :integer :value key}]
                 :order-cells [{:type :float :value value}]
                 :cells [{:type :float :value value}]})
        entries [(entry :nan-2 2 ##NaN)
                 (entry :positive-infinity 12 ##Inf)
                 (entry :ordinary 11 2.5)
                 (entry :negative-infinity 10 ##-Inf)
                 (entry :nan-1 1 ##NaN)]
        expected [:negative-infinity :ordinary :positive-infinity
                  :nan-1 :nan-2]
        labels (fn [xs]
                 (mapv :label (sort compare-entries xs)))]
    (is (= expected (labels entries)))
    (is (= expected (labels (vec (reverse entries)))))))

(deftest runtime-row-schema-failures-preserve-storage-and-evidence
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        update {:op :update-row :table :outbox/items
                :key-params [1] :key-columns ["id"]
                :set [["value" 2]]}
        update-plans
        (fn [sql]
          [{:sql "insert"
            :params {1 {:type :integer :value 1}
                     2 {:type :text :value "old"}}
            :row-effect insert}
           {:sql sql
            :params {1 {:type :integer :value 1}
                     2 {:type :text :value "new"}}
            :row-effect update}])
        exercise-update
        (fn [sql corrupt-row]
          (let [w (sqlite/world (update-plans sql))
                H (sqlite/handlers w)
                db (open-db H)
                key (modeled-row-key :outbox/items
                                     [{:type :integer :value 1}])]
            (is (= 101 (run-row-statement!
                        H db "insert"
                        [[1 {:type :integer :value 1}]
                         [2 {:type :text :value "old"}]])))
            ;; Establish a physically addressable row whose declared schema
            ;; disagrees with its tagged identity. This is corruption/setup,
            ;; not a modeled effect; the public update must fail before CAS.
            (swap! (:state w) update-in [:dbs db :committed key] corrupt-row)
            (let [before (get-in (sqlite/state w) [:dbs db])
                  stmt (prepare H db sql)]
              (bind-cell! H stmt 1 {:type :integer :value 1})
              (bind-cell! H stmt 2 {:type :text :value "new"})
              (is (= :jolt.sim.sqlite/row-key-schema-mismatch
                     (:type (ex-data-of #(ff H "sqlite3_step" [stmt])))))
              ;; The hard failure publishes neither a replacement nor an
              ;; evidence event and leaves the connection byte-for-byte equal.
              (is (= before (get-in (sqlite/state w) [:dbs db])))
              (is (= 1 (count (:row-evidence before))))
              (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
            (close-db H db)
            (is (true? (sqlite/clean? w)))))]
    (exercise-update
     "update-wrong-key-value"
     #(assoc % "id" {:type :integer :value 2}))
    (exercise-update
     "update-absent-key-column"
     #(dissoc % "id")))

  ;; A scan's project and order-key share the same fail-closed row lookup. A
  ;; missing order-key column is also projected, so this one witness covers
  ;; both consumers without altering the stored row or evidence after failure.
  (let [plans [{:sql "insert"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "old"}}
                :row-effect {:op :insert-row :table :outbox/items
                             :key-params [1]
                             :row [["id" 1] ["value" 2]]}}
               {:sql "scan-missing-column"
                :row-effect {:op :scan-rows :table :outbox/items
                             :project ["id" "missing"]
                             :order-key ["missing"]}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    (is (= 101 (run-row-statement!
                H db "insert"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "old"}]])))
    (let [before (get-in (sqlite/state w) [:dbs db])
          stmt (prepare H db "scan-missing-column")]
      (is (= :jolt.sim.sqlite/scan-projection
             (:type (ex-data-of #(ff H "sqlite3_step" [stmt])))))
      (is (= before (get-in (sqlite/state w) [:dbs db])))
      (is (= 1 (count (:row-evidence before))))
      (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest rolled-back-staged-insert-does-not-cause-a-false-constraint
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        params {1 {:type :integer :value 7}
                2 {:type :text :value "value"}}
        plans [{:sql "begin" :tx-effect {:op :begin}}
               {:sql "insert-staged" :params params :row-effect insert}
               {:sql "rollback" :tx-effect {:op :rollback}}
               {:sql "insert-after-rollback" :params params
                :row-effect insert}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        bindings [[1 {:type :integer :value 7}]
                  [2 {:type :text :value "value"}]]]
    (is (= 101 (run-row-statement! H db "begin" [])))
    (is (= 101 (run-row-statement! H db "insert-staged" bindings)))
    (is (= 101 (run-row-statement! H db "rollback" [])))
    (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
    (is (nil? (get-in (sqlite/state w) [:dbs db :staging])))
    ;; The discarded row identity is absent from the current visible view, so
    ;; the same insert is a fresh success rather than SQLITE_CONSTRAINT.
    (is (= 101 (run-row-statement!
                H db "insert-after-rollback" bindings)))
    (is (= 0 (ff H "sqlite3_errcode" [db])))
    (is (= 1 (ff H "sqlite3_changes" [db])))
    (is (= [[:done :staging] [:done :committed]]
           (mapv (juxt :reported :location)
                 (get-in (sqlite/state w) [:dbs db :row-evidence]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest scan-first-step-snapshot-and-current-row-survive-later-update
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1]
                :row [["id" 1] ["value" 2]]}
        update {:op :update-row :table :outbox/items
                :key-params [1] :key-columns ["id"]
                :set [["value" 2]]}
        scan {:op :scan-rows :table :outbox/items
              :project ["id" "value"] :order-key ["id"]}
        plans [{:sql "insert-1"
                :params {1 {:type :integer :value 1}
                         2 {:type :text :value "one"}}
                :row-effect insert}
               {:sql "insert-2"
                :params {1 {:type :integer :value 2}
                         2 {:type :text :value "old"}}
                :row-effect insert}
               {:sql "scan-snapshot" :row-effect scan}
               {:sql "update-2"
                :params {1 {:type :integer :value 2}
                         2 {:type :text :value "new"}}
                :row-effect update}
               {:sql "scan-current" :row-effect scan}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)]
    (is (= 101 (run-row-statement!
                H db "insert-1"
                [[1 {:type :integer :value 1}]
                 [2 {:type :text :value "one"}]])))
    (is (= 101 (run-row-statement!
                H db "insert-2"
                [[1 {:type :integer :value 2}]
                 [2 {:type :text :value "old"}]])))
    (let [snapshot (prepare H db "scan-snapshot")]
      (is (= 100 (ff H "sqlite3_step" [snapshot])))
      (is (= 1 (ff H "sqlite3_column_int64" [snapshot 0])))
      (is (= "one" (ff H "sqlite3_column_text" [snapshot 1])))
      (is (= 101 (run-row-statement!
                  H db "update-2"
                  [[1 {:type :integer :value 2}]
                   [2 {:type :text :value "new"}]])))
      ;; The current row remains routed through the immutable statement-local
      ;; snapshot, and the later row is the old value captured with it.
      (is (= "one" (ff H "sqlite3_column_text" [snapshot 1])))
      (is (= 100 (ff H "sqlite3_step" [snapshot])))
      (is (= 2 (ff H "sqlite3_column_int64" [snapshot 0])))
      (is (= "old" (ff H "sqlite3_column_text" [snapshot 1])))
      (is (= 101 (ff H "sqlite3_step" [snapshot])))
      (is (= 0 (ff H "sqlite3_finalize" [snapshot]))))
    (let [current (prepare H db "scan-current")]
      (is (= 100 (ff H "sqlite3_step" [current])))
      (is (= 100 (ff H "sqlite3_step" [current])))
      (is (= "new" (ff H "sqlite3_column_text" [current 1])))
      (is (= 101 (ff H "sqlite3_step" [current])))
      (is (= 0 (ff H "sqlite3_finalize" [current]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest concurrent-row-insert-terminal-step-writes-exactly-once
  (let [plan {:sql "insert"
              :params {1 {:type :integer :value 7}
                       2 {:type :text :value "value"}}
              :row-effect {:op :insert-row :table :outbox/items
                           :key-params [1]
                           :row [["id" 1] ["value" 2]]}}
        w (sqlite/world [plan])
        H (sqlite/handlers w)
        db (open-db H)
        stmt (prepare H db "insert")
        _ (bind-cell! H stmt 1 {:type :integer :value 7})
        _ (bind-cell! H stmt 2 {:type :text :value "value"})
        decision-var (resolve 'jolt.sim.sqlite/row-mutation-decision)
        original @decision-var
        arrivals (atom 0)
        both-inside (promise)
        wrapped
        (fn [db-state statement reported]
          (let [arrival (swap! arrivals inc)]
            (when (<= arrival 2)
              (when (= arrival 2) (deliver both-inside true))
              (when (= ::timeout (deref both-inside 5000 ::timeout))
                (throw (ex-info "row insert contention timed out"
                                {:arrival arrival}))))
            (original db-state statement reported)))
        run-step (fn []
                   (try {:value (ff H "sqlite3_step" [stmt])}
                        (catch :default e {:error (ex-data e)})))
        results (with-redefs-fn
                  {decision-var wrapped}
                  #(let [a (future (run-step))
                         b (future (run-step))]
                     [(await-worker a) (await-worker b)]))]
    (is (= 2 @arrivals))
    (is (not-any? #{::timeout} results) (pr-str results))
    (is (= #{21 101} (set (map :value results))) (pr-str results))
    (is (= 1 (count (get-in (sqlite/state w)
                            [:dbs db :row-evidence]))))
    (is (= 1 (count (filter vector?
                            (keys (get-in (sqlite/state w)
                                          [:dbs db :committed]))))))
    (is (= 0 (ff H "sqlite3_finalize" [stmt])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest concurrent-scan-first-step-snapshots-and-publishes-exactly-once
  (let [insert {:op :insert-row :table :outbox/items
                :key-params [1] :row [["id" 1]]}
        scan {:op :scan-rows :table :outbox/items
              :project ["id"] :order-key ["id"]}
        w (sqlite/world [{:sql "insert"
                          :params {1 {:type :integer :value 7}}
                          :row-effect insert}
                         {:sql "scan" :row-effect scan}])
        H (sqlite/handlers w)
        db (open-db H)
        _ (is (= 101 (run-row-statement!
                      H db "insert" [[1 {:type :integer :value 7}]])))
        stmt (prepare H db "scan")
        decision-var (resolve 'jolt.sim.sqlite/scan-snapshot-decision)
        original @decision-var
        arrivals (atom 0)
        both-inside (promise)
        wrapped
        (fn [db-state statement reported]
          (let [arrival (swap! arrivals inc)]
            (when (<= arrival 2)
              (when (= arrival 2) (deliver both-inside true))
              (when (= ::timeout (deref both-inside 5000 ::timeout))
                (throw (ex-info "row scan contention timed out"
                                {:arrival arrival}))))
            (original db-state statement reported)))
        run-step (fn []
                   (try {:value (ff H "sqlite3_step" [stmt])}
                        (catch :default e {:error (ex-data e)})))
        results (with-redefs-fn
                  {decision-var wrapped}
                  #(let [a (future (run-step))
                         b (future (run-step))]
                     [(await-worker a) (await-worker b)]))]
    (is (= 2 @arrivals))
    (is (= #{21 100} (set (map :value results))) (pr-str results))
    (is (= 7 (ff H "sqlite3_column_int64" [stmt 0])))
    (is (= 1 (count (filter #(= :scan-rows (:op %))
                            (get-in (sqlite/state w)
                                    [:dbs db :row-evidence])))))
    (is (= 101 (ff H "sqlite3_step" [stmt])))
    (is (= 0 (ff H "sqlite3_finalize" [stmt])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest finalize-and-close-winners-cannot-resurrect-row-state
  (let [plan {:sql "insert"
              :params {1 {:type :integer :value 7}}
              :row-effect {:op :insert-row :table :outbox/items
                           :key-params [1] :row [["id" 1]]}}
        exercise
        (fn [winner]
          (let [w (sqlite/world [plan])
                H (sqlite/handlers w)
                db (open-db H)
                stmt (prepare H db "insert")
                _ (bind-cell! H stmt 1 {:type :integer :value 7})
                decision-var (resolve 'jolt.sim.sqlite/row-mutation-decision)
                original @decision-var
                inside (promise)
                release (promise)
                wrapped (fn [db-state statement reported]
                          (deliver inside true)
                          (when (= ::timeout (deref release 5000 ::timeout))
                            (throw (ex-info "row lifetime race timed out"
                                            {:winner winner})))
                          (original db-state statement reported))
                result
                (with-redefs-fn
                  {decision-var wrapped}
                  #(let [step-result
                         (future
                           (try {:value (ff H "sqlite3_step" [stmt])}
                                (catch :default e {:error (ex-data e)})))]
                     (is (not= ::timeout (deref inside 5000 ::timeout)))
                     (try
                       (case winner
                         :finalize (is (= 0 (ff H "sqlite3_finalize" [stmt])))
                         :close (close-db H db))
                       (finally (deliver release true)))
                     (await-worker step-result)))]
            (is (= (case winner
                     :finalize :jolt.sim.sqlite/use-after-finalize
                     :close :jolt.sim.sqlite/use-after-close)
                   (get-in result [:error :type]))
                (pr-str result))
            (is (= [] (if (= :close winner)
                        (get-in (sqlite/state w)
                                [:closed-db-evidence 0 :row-evidence])
                        (get-in (sqlite/state w)
                                [:dbs db :row-evidence]))))
            (case winner
              :finalize
              (do
                (is (not (contains? (:stmts (sqlite/state w)) stmt)))
                (close-db H db))

              :close
              (do
                ;; close claims/removes the connection, but leaves its live
                ;; statement record available for the required later cleanup.
                (is (contains? (:stmts (sqlite/state w)) stmt))
                (is (= 0 (ff H "sqlite3_finalize" [stmt])))
                (is (not (contains? (:stmts (sqlite/state w)) stmt)))))
            (is (true? (sqlite/clean? w)))))]
    (exercise :finalize)
    (exercise :close)))

(deftest concurrent-put-terminal-step-writes-exactly-once
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 7}
                         2 {:type :integer :value 99}}
                :store-effect {:op :put :key-param 1 :value-param 2}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        s (prepare H db "PUT k v")
        _ (is (= 0 (ff H "sqlite3_bind_int64" [s 1 7])))
        _ (is (= 0 (ff H "sqlite3_bind_int64" [s 2 99])))
        store-put-decision-var (resolve 'jolt.sim.sqlite/store-put-decision)
        original @store-put-decision-var
        arrivals (atom 0)
        both-inside (promise)
        wrapped-decision
        (fn [db-state stmt reported]
          (let [arrival (swap! arrivals inc)]
            ;; The first two callers have both read the same nonterminal
            ;; statement snapshot before either can attempt its CAS. This
            ;; discriminates atomic terminal ownership from a merely
            ;; serialized scheduling of two h-step calls.
            (when (<= arrival 2)
              (when (= arrival 2)
                (deliver both-inside true))
              (when (= ::timeout (deref both-inside 5000 ::timeout))
                (throw (ex-info "put terminal-step contention barrier timed out"
                                {:arrival arrival}))))
            (original db-state stmt reported)))
        run-step (fn []
                   (try
                     {:value (ff H "sqlite3_step" [s])}
                     (catch :default error
                       {:error (ex-data error)})))
        results
        (with-redefs-fn
          {store-put-decision-var wrapped-decision}
          #(let [a (future (run-step))
                 b (future (run-step))]
             [(await-worker a)
              (await-worker b)]))]
    (is (= 2 @arrivals))
    (is (not-any? #{::timeout} results) (pr-str results))
    (is (every? map? results) (pr-str results))
    (when (every? map? results)
      (is (every? #(contains? % :value) results) (pr-str results))
      (is (= #{21 101} (set (map :value results))) (pr-str results)))
    (is (= 1 (count (get-in (sqlite/state w) [:dbs db :store-evidence]))))
    (is (= {{:type :integer :value 7} {:type :integer :value 99}}
           (get-in (sqlite/state w) [:dbs db :committed])))
    (is (= 0 (ff H "sqlite3_finalize" [s])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest finalize-winning-after-put-step-read-cannot-recreate-the-statement
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 7}
                         2 {:type :integer :value 99}}
                :store-effect {:op :put :key-param 1 :value-param 2}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        stmt (prepare H db "PUT k v")
        _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 1 7])))
        _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 2 99])))
        require-var (resolve 'jolt.sim.sqlite/require-bindings-complete!)
        original @require-var
        after-read (promise)
        release (promise)
        wrapped-require
        (fn [statement]
          (let [result (original statement)]
            ;; h-step has already read a live statement. The old put path then
            ;; performed a generic swap! before its terminal CAS, which could
            ;; recreate this record after finalize claimed and freed it.
            (deliver after-read true)
            (when (= ::timeout (deref release 5000 ::timeout))
              (throw (ex-info "put/finalize release timed out" {})))
            result))
        result
        (with-redefs-fn
          {require-var wrapped-require}
          #(let [step-result
                 (future
                   (try
                     {:value (ff H "sqlite3_step" [stmt])}
                   (catch :default error
                       {:error (ex-data error)})))]
             (is (not= ::timeout (deref after-read 5000 ::timeout)))
             (try
               (is (= 0 (ff H "sqlite3_finalize" [stmt])))
               (finally
                 (deliver release true)))
             (await-worker step-result)))]
    (is (not= ::timeout result) (pr-str result))
    (is (= :jolt.sim.sqlite/use-after-finalize
           (get-in result [:error :type]))
        (pr-str result))
    (is (not (contains? (:stmts (sqlite/state w)) stmt)))
    (is (= {} (get-in (sqlite/state w) [:dbs db :committed])))
    (is (= [] (get-in (sqlite/state w) [:dbs db :store-evidence])))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest concurrent-get-first-step-snapshots-and-publishes-exactly-once
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 7}
                         2 {:type :integer :value 99}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET k"
                :params {1 {:type :integer :value 7}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put (prepare H db "PUT k v")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put 1 7])))
    (is (= 0 (ff H "sqlite3_bind_int64" [put 2 99])))
    (is (= 101 (ff H "sqlite3_step" [put])))
    (is (= 0 (ff H "sqlite3_finalize" [put])))
    (let [stmt (prepare H db "GET k")
          _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 1 7])))
          decision-var (resolve 'jolt.sim.sqlite/store-get-snapshot-decision)
          original @decision-var
          arrivals (atom 0)
          both-inside (promise)
          wrapped-decision
          (fn [db-state statement reported]
            (let [arrival (swap! arrivals inc)]
              (when (<= arrival 2)
                (when (= arrival 2)
                  (deliver both-inside true))
                (when (= ::timeout (deref both-inside 5000 ::timeout))
                  (throw (ex-info "get first-step contention barrier timed out"
                                  {:arrival arrival}))))
              (original db-state statement reported)))
          run-step (fn []
                     (try
                       {:value (ff H "sqlite3_step" [stmt])}
                       (catch :default error
                         {:error (ex-data error)})))
          results
          (with-redefs-fn
            {decision-var wrapped-decision}
            #(let [a (future (run-step))
                   b (future (run-step))]
               [(await-worker a)
                (await-worker b)]))]
      (is (= 2 @arrivals))
      (is (not-any? #{::timeout} results) (pr-str results))
      (is (= #{21 100} (set (map :value results))) (pr-str results))
      (is (= 1 (count (filter #(= :get (:op %))
                              (get-in (sqlite/state w)
                                      [:dbs db :store-evidence])))))
      ;; The losing first-step call cannot advance or invalidate the winner's
      ;; current row. A later ordinary call advances that row to DONE.
      (is (= 99 (ff H "sqlite3_column_int64" [stmt 0])))
      (is (= 101 (ff H "sqlite3_step" [stmt])))
      (is (= 0 (ff H "sqlite3_finalize" [stmt]))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest close-winning-get-first-step-does-not-recreate-the-connection
  (let [plans [{:sql "GET k"
                :params {1 {:type :integer :value 7}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        stmt (prepare H db "GET k")
        _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 1 7])))
        decision-var (resolve 'jolt.sim.sqlite/store-get-snapshot-decision)
        original @decision-var
        inside (promise)
        release (promise)
        wrapped-decision
        (fn [db-state statement reported]
          (deliver inside true)
          (when (= ::timeout (deref release 5000 ::timeout))
            (throw (ex-info "get/close release timed out" {})))
          (original db-state statement reported))
        result
        (with-redefs-fn
          {decision-var wrapped-decision}
          #(let [step-result
                 (future
                   (try
                     {:value (ff H "sqlite3_step" [stmt])}
                     (catch :default error
                       {:error (ex-data error)})))]
             (is (not= ::timeout (deref inside 5000 ::timeout)))
             (try
               (close-db H db)
               (finally
                 (deliver release true)))
             (await-worker step-result)))]
    (is (not= ::timeout result) (pr-str result))
    (is (= :jolt.sim.sqlite/use-after-close
           (get-in result [:error :type]))
        (pr-str result))
    (is (not (contains? (:dbs (sqlite/state w)) db)))
    (is (= [] (get-in (sqlite/state w)
                      [:closed-db-evidence 0 :store-evidence])))
    (is (= 0 (ff H "sqlite3_finalize" [stmt])))
    (is (true? (sqlite/clean? w)))))

(deftest finalize-claims-state-before-freeing-a-store-get-and-its-blob
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 7}
                         2 {:type :blob :value [9]}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET k"
                :params {1 {:type :integer :value 7}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put (prepare H db "PUT k v")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put 1 7])))
    (let [src (native H :alloc 1)]
      (native H :write-array src (byte-array [9]))
      (is (= 0 (ff H "sqlite3_bind_blob64" [put 2 src 1 0])))
      (native H :free src))
    (is (= 101 (ff H "sqlite3_step" [put])))
    (is (= 0 (ff H "sqlite3_finalize" [put])))
    (let [stmt (prepare H db "GET k")
          _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 1 7])))
          _ (is (= 100 (ff H "sqlite3_step" [stmt])))
          borrowed (ff H "sqlite3_column_blob" [stmt 0])
          _ (is (pos? borrowed))
          invoke-mem-var (resolve 'jolt.sim.sqlite/invoke-mem)
          original @invoke-mem-var
          handle-freed (promise)
          release (promise)
          wrapped-invoke
          (fn [world op args]
            (let [result (original world op args)]
              ;; This is the exact old unsafe window: h-finalize had freed the
              ;; borrowed BLOB and statement handle but had not yet removed the
              ;; live statement record. The corrected path claims state first.
              (when (and (= :free op) (= stmt (first args)))
                (deliver handle-freed true)
                (when (= ::timeout (deref release 5000 ::timeout))
                  (throw (ex-info "finalize release timed out" {}))))
              result))
          result
          (with-redefs-fn
            {invoke-mem-var wrapped-invoke}
            #(let [finalize-result
                   (future (ff H "sqlite3_finalize" [stmt]))]
               (is (not= ::timeout (deref handle-freed 5000 ::timeout)))
               (let [step-result
                     (try
                       {:value (ff H "sqlite3_step" [stmt])}
                       (catch :default error
                         {:error (ex-data error)}))]
                 (try
                   {:step step-result}
                   (finally
                     (deliver release true)))
                 {:step step-result
                  :finalize (await-worker finalize-result)})))]
      (is (= 0 (:finalize result)) (pr-str result))
      (is (= :jolt.sim.sqlite/use-after-finalize
             (get-in result [:step :error :type]))
          (pr-str result))
      (is (not (contains? (:stmts (sqlite/state w)) stmt)))
      ;; Both the borrowed row pointer and statement handle were freed once.
      (is (= :jolt.sim.ffi-memory/use-after-free
             (:type (ex-data-of #(native H :read borrowed :uint8)))))
      (is (= :jolt.sim.ffi-memory/use-after-free
             (:type (ex-data-of #(native H :read stmt :pointer))))))
    (close-db H db)
    (is (true? (sqlite/clean? w)))))

(deftest blob-borrow-install-losing-to-finalize-frees-the-fresh-allocation
  (let [plans [{:sql "PUT k v"
                :params {1 {:type :integer :value 7}
                         2 {:type :blob :value [9]}}
                :store-effect {:op :put :key-param 1 :value-param 2}}
               {:sql "GET k"
                :params {1 {:type :integer :value 7}}
                :columns ["value"]
                :store-effect {:op :get :key-param 1}}]
        w (sqlite/world plans)
        H (sqlite/handlers w)
        db (open-db H)
        put (prepare H db "PUT k v")]
    (is (= 0 (ff H "sqlite3_bind_int64" [put 1 7])))
    (let [src (native H :alloc 1)]
      (native H :write-array src (byte-array [9]))
      (is (= 0 (ff H "sqlite3_bind_blob64" [put 2 src 1 0])))
      (native H :free src))
    (is (= 101 (ff H "sqlite3_step" [put])))
    (is (= 0 (ff H "sqlite3_finalize" [put])))
    (let [stmt (prepare H db "GET k")
          _ (is (= 0 (ff H "sqlite3_bind_int64" [stmt 1 7])))
          _ (is (= 100 (ff H "sqlite3_step" [stmt])))
          invoke-mem-var (resolve 'jolt.sim.sqlite/invoke-mem)
          original @invoke-mem-var
          allocated (promise)
          release (promise)
          wrapped-invoke
          (fn [world op args]
            (let [result (original world op args)]
              (when (= :alloc op)
                (deliver allocated result)
                (when (= ::timeout (deref release 5000 ::timeout))
                  (throw (ex-info "borrow allocation release timed out" {}))))
              result))
          result
          (with-redefs-fn
            {invoke-mem-var wrapped-invoke}
            #(let [column-result
                   (future
                     (try
                       {:value (ff H "sqlite3_column_blob" [stmt 0])}
                       (catch :default error
                         {:error (ex-data error)})))
                   fresh (deref allocated 5000 ::timeout)]
               (try
                 (is (not= ::timeout fresh))
                 (is (= 0 (ff H "sqlite3_finalize" [stmt])))
                 (finally
                   (deliver release true)))
               {:fresh fresh :column (await-worker column-result)}))]
      (is (= :jolt.sim.sqlite/use-after-finalize
             (get-in result [:column :error :type]))
          (pr-str result))
      (is (not (contains? (:stmts (sqlite/state w)) stmt)))
      (when (number? (:fresh result))
        (is (= :jolt.sim.ffi-memory/use-after-free
               (:type
                (ex-data-of #(native H :read (:fresh result) :uint8)))))))
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
    (is (= 23 (count foreign)))
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

;; ---- hybrid handlers ------------------------------------------------------

;; sqlite/handler-keys' five-element shorthand canonicalizes (per
;; jolt.sim.runtime/canonical-handler-key) to the seven-element
;; [:foreign-function symbol argument-types return-type blocking?
;; capture-native-error? varargs-after] identity, with capture-native-error?
;; false and varargs-after nil. That is this repo's exact current ABI6
;; :foreign-function descriptor identity (descriptor-version 6). Comparisons
;; below are against jolt.sim.runtime's own public substitute/modeled-resource
;; constructors -- the exact wire-format values a hybrid handler returns -- so
;; no private decode or validation var is ever resolved.
(defn- ff-hybrid-descriptor [sym args]
  (let [key (key-by-symbol sym)]
    {:kind :foreign-function :task 0 :arguments (vec args)
     :symbol sym :argument-types (nth key 2)
     :return-type (nth key 3) :blocking? (nth key 4)
     :capture-native-error? false :varargs-after nil}))

(defn- native-hybrid [H op & args]
  ((get H [:native-operation op])
   {:kind :native-operation :task 0 :arguments (vec args) :operation op}))

(defn- ff-hybrid [H sym args]
  ((get H (key-by-symbol sym)) (ff-hybrid-descriptor sym args)))

(defn- open-db-hybrid [H]
  (let [filename (:jolt.sim.runtime/value (native-hybrid H :string->ptr "file:test.db"))
        cell (:jolt.sim.runtime/value (native-hybrid H :alloc 8))]
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_open" [filename cell])))
    (let [db (:jolt.sim.runtime/value (native-hybrid H :read cell :pointer))]
      (native-hybrid H :free filename)
      (native-hybrid H :free cell)
      db)))

(defn- prepare-hybrid [H db sql]
  (let [sql-ptr (:jolt.sim.runtime/value (native-hybrid H :string->ptr sql))
        stmt-cell (:jolt.sim.runtime/value (native-hybrid H :alloc 8))]
    (is (= (runtime/substitute 0)
           (ff-hybrid H "sqlite3_prepare_v2" [db sql-ptr -1 stmt-cell 0])))
    (let [stmt (:jolt.sim.runtime/value (native-hybrid H :read stmt-cell :pointer))]
      (native-hybrid H :free sql-ptr)
      (native-hybrid H :free stmt-cell)
      stmt)))

(defn- close-db-hybrid [H db]
  (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_close_v2" [db]))))

(deftest hybrid-handlers-cover-the-same-39-keys-as-the-hermetic-handlers
  (let [w (sqlite/world [])
        h (sqlite/handlers w)
        hybrid (sqlite/hybrid-handlers w)
        hybrid-foreign (sqlite/hybrid-foreign-handlers w)]
    (is (= (set (keys h)) (set (keys hybrid))))
    (is (= 39 (count hybrid)))
    (is (= (set sqlite/handler-keys) (set (keys hybrid-foreign))))
    (is (= 23 (count hybrid-foreign)))
    (doseq [k (keys hybrid)]
      (is (ifn? (get hybrid k))))))

(deftest hybrid-sqlite-scalar-and-string-results-classify-as-substitute
  (let [plans [{:sql "SELECT id, name FROM t"
                :columns ["id" "name"]
                :rows [[{:type :integer :value 1}
                        {:type :text :value "ann"}]]
                :changes 0 :last-row-id 0}]
        w (sqlite/world plans)
        H (sqlite/hybrid-handlers w)
        db (open-db-hybrid H)
        s (prepare-hybrid H db "SELECT id, name FROM t")]
    (is (= (runtime/substitute 100) (ff-hybrid H "sqlite3_step" [s])))
    (is (= (runtime/substitute 2) (ff-hybrid H "sqlite3_column_count" [s])))
    (is (= (runtime/substitute "id") (ff-hybrid H "sqlite3_column_name" [s 0])))
    (is (= (runtime/substitute 1) (ff-hybrid H "sqlite3_column_type" [s 0])))
    (is (= (runtime/substitute 1) (ff-hybrid H "sqlite3_column_int64" [s 0])))
    (is (= (runtime/substitute "ann") (ff-hybrid H "sqlite3_column_text" [s 1])))
    (is (= (runtime/substitute 101) (ff-hybrid H "sqlite3_step" [s])))
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_errcode" [db])))
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_changes" [db])))
    (is (= (runtime/substitute 0)
           (ff-hybrid H "sqlite3_last_insert_rowid" [db])))
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_finalize" [s])))
    (close-db-hybrid H db)
    (is (true? (sqlite/clean? w)))))

(deftest hybrid-column-blob-classifies-a-positive-pointer-with-exact-span-and-null-as-substitute
  (let [plans [{:sql "SELECT b FROM t"
                :columns ["b"]
                :rows [[{:type :blob :value [1 2 3]}]
                       [{:type :blob :value []}]
                       [{:type :blob :value [] :null-pointer? true}]
                       [{:type :null}]]}]
        w (sqlite/world plans)
        H (sqlite/hybrid-handlers w)
        db (open-db-hybrid H)
        s (prepare-hybrid H db "SELECT b FROM t")]
    ;; nonempty blob: modeled-resource spanning its exact 3-byte allocation
    (is (= (runtime/substitute 100) (ff-hybrid H "sqlite3_step" [s])))
    (let [blob (ff-hybrid H "sqlite3_column_blob" [s 0])
          addr (:jolt.sim.runtime/value blob)]
      (is (pos? addr))
      (is (= (runtime/modeled-resource addr 3) blob)))
    ;; empty non-null blob: still a modeled-resource, spanning its fallback
    ;; one-byte allocation
    (is (= (runtime/substitute 100) (ff-hybrid H "sqlite3_step" [s])))
    (let [blob (ff-hybrid H "sqlite3_column_blob" [s 0])
          addr (:jolt.sim.runtime/value blob)]
      (is (pos? addr))
      (is (= (runtime/modeled-resource addr 1) blob)))
    ;; empty BLOB opted into a null pointer, and a NULL cell: both substitute 0
    (is (= (runtime/substitute 100) (ff-hybrid H "sqlite3_step" [s])))
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_column_blob" [s 0])))
    (is (= (runtime/substitute 100) (ff-hybrid H "sqlite3_step" [s])))
    (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_column_blob" [s 0])))
    (is (= (runtime/substitute 101) (ff-hybrid H "sqlite3_step" [s])))
    (ff-hybrid H "sqlite3_finalize" [s])
    (close-db-hybrid H db)
    (is (true? (sqlite/clean? w)))))

(deftest hybrid-column-blob-pointer-and-span-share-one-transition-with-no-duplicate-borrow
  (let [plans [{:sql "SELECT b FROM t" :columns ["b"]
                :rows [[{:type :blob :value [9 9]}]]}]
        w (sqlite/world plans)
        H (sqlite/hybrid-handlers w)
        db (open-db-hybrid H)
        s (prepare-hybrid H db "SELECT b FROM t")]
    (ff-hybrid H "sqlite3_step" [s])
    (let [first-result (ff-hybrid H "sqlite3_column_blob" [s 0])
          second-result (ff-hybrid H "sqlite3_column_blob" [s 0])
          addr (:jolt.sim.runtime/value first-result)]
      ;; two hybrid calls for the same current row/col observe the identical
      ;; modeled-resource pointer+span, so both are drawn from the one
      ;; borrow-or-cache transition rather than two independent ones
      (is (= (runtime/modeled-resource addr 2) first-result))
      (is (= first-result second-result))
      ;; the exactly-once witness: the statement's own borrowed-address
      ;; ledger names addr precisely once. A reintroduced duplicate borrow
      ;; would append addr (or a second address) here; a broad allocation
      ;; count could stay misleadingly stable if a duplicate borrow happened
      ;; to coincide with an unrelated free elsewhere, so this checks the
      ;; exact state transition instead.
      (is (= [addr] (get-in (sqlite/state w) [:stmts s :borrowed])))
      (is (= [{:base addr :size 2}]
             (filter #(= addr (:base %)) (sqlite/leaks w)))))
    (ff-hybrid H "sqlite3_finalize" [s])
    (close-db-hybrid H db)
    (is (true? (sqlite/clean? w)))))

(deftest hybrid-get-autocommit-classifies-as-substitute
  (let [plans [{:sql "BEGIN" :tx-effect {:op :begin}}
               {:sql "COMMIT" :tx-effect {:op :commit}}]
        w (sqlite/world plans)
        H (sqlite/hybrid-handlers w)
        db (open-db-hybrid H)]
    ;; the new nonblocking key rides the generic hybrid classification like
    ;; every other scalar SQLite result
    (is (= (runtime/substitute 1) (ff-hybrid H "sqlite3_get_autocommit" [db])))
    (let [s (prepare-hybrid H db "BEGIN")]
      (is (= (runtime/substitute 101) (ff-hybrid H "sqlite3_step" [s])))
      (is (= (runtime/substitute 0)
             (ff-hybrid H "sqlite3_get_autocommit" [db])))
      (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_finalize" [s]))))
    (let [s (prepare-hybrid H db "COMMIT")]
      (is (= (runtime/substitute 101) (ff-hybrid H "sqlite3_step" [s])))
      (is (= (runtime/substitute 1)
             (ff-hybrid H "sqlite3_get_autocommit" [db])))
      (is (= (runtime/substitute 0) (ff-hybrid H "sqlite3_finalize" [s]))))
    (close-db-hybrid H db)
    (is (true? (sqlite/clean? w)))))
