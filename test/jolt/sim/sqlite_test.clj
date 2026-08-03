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
            :autocommit-evidence [1]}
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
            :close-index 0
            :discarded-transaction {:begin-event 0 :begin-plan-index 0}}
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
                :autocommit-evidence [] :close-index 1}
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
