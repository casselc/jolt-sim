(ns jolt.sim.sqlite
  "Deterministic data-driven SQLite handler pack for simulation FFI interception.

  This is NOT a SQL parser. A world owns a jolt.sim.ffi-memory world plus an
  ordered vector of exact statement plans. db.sqlite's foreign-function calls
  are intercepted before the OS; each sqlite3_prepare_v2 consumes the next plan
  in FIFO order and the model serves the plan's declared columns, rows, change
  count, and last row id, while enforcing that the prepared SQL and every bound
  parameter match the plan exactly. A mismatch or a use of a finalized/closed
  handle throws a typed ex-info (a hard failure) so an application cannot catch
  it and make a controlled run succeed.

  Fake connection and statement handles are real allocations in the memory
  world, so close/finalize free them and clean?/leaks/snapshot reflect them.
  Bound BLOB bytes are copied into an immutable vector at bind time. Nonempty
  result BLOBs live in borrowed memory-world allocations that are freed on the
  next step or on finalize, so holding a BLOB pointer past row consumption
  surfaces as :jolt.sim.ffi-memory/use-after-free. NULL and empty BLOBs remain
  distinct by sqlite3_column_type. Empty BLOBs normally return a non-null
  borrowed pointer; an empty :blob cell with :null-pointer? true returns 0 so
  callers can exercise SQLite's ambiguous empty-BLOB pointer contract.

  handlers returns the 16 native-operation handlers from the memory world
  merged with exactly the 22 SQLite foreign-function keys required by
  db.sqlite, so a single :ffi-handlers map drives both layers unchanged."
  (:require [jolt.sim.ffi-memory :as memory]))

;; ---- codes --------------------------------------------------------------

(def ^:private result-codes {:ok 0 :row 100 :done 101 :misuse 21})

(def ^:private type-codes {:integer 1 :float 2 :text 3 :blob 4 :null 5})

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

;; ---- byte helpers (private copies; ffi-memory helpers are private) -----

(defn- ba->uvec [ba]
  (let [n (alength ba)]
    (loop [i 0 out (transient [])]
      (if (== i n)
        (persistent! out)
        (recur (inc i)
               (conj! out (bit-and 0xFF (int (aget ba i)))))))))

(defn- uvec->byte-array [v]
  (let [n (count v)
        arr (byte-array n)]
    (loop [i 0]
      (when (< i n)
        (aset arr i (nth v i))
        (recur (inc i))))
    arr))

(defn- normalize-bytes [v]
  (cond
    (bytes? v)                    (ba->uvec v)
    (vector? v)
    (do
      (when-not
        (every?
         #(and (integer? %) (<= 0 %) (<= % 255))
         v)
        (fail! :jolt.sim.sqlite/invalid-plan
               "blob byte vectors must contain unsigned byte values"
               {:value v}))
      (vec v))
    (nil? v)                      []
    :else
    (fail! :jolt.sim.sqlite/invalid-plan
           "blob value must be a byte array or byte vector"
           {:value v})))

(defn- utf8-bytes [^String s]
  (.getBytes s "UTF-8"))

(defn- utf8-length [s]
  (alength (utf8-bytes s)))

;; ---- the exact 22 db.sqlite foreign-function keys ----------------------

(def
 ^{:doc "The exact 22 SQLite foreign-function handler keys required by
 db.sqlite. Each is [symbol argument-types return-type blocking?] matching the
 runtime descriptor-handler-key. Exposed so tests and callers never diverge
 from the registered handlers."}
 handler-keys
 [[:foreign-function "sqlite3_open" [:pointer :pointer] :int true]
  [:foreign-function "sqlite3_close_v2" [:pointer] :int true]
  [:foreign-function "sqlite3_errmsg" [:pointer] :string false]
  [:foreign-function "sqlite3_prepare_v2"
   [:pointer :pointer :int :pointer :pointer] :int true]
  [:foreign-function "sqlite3_step" [:pointer] :int true]
  [:foreign-function "sqlite3_finalize" [:pointer] :int true]
  [:foreign-function "sqlite3_column_count" [:pointer] :int false]
  [:foreign-function "sqlite3_column_name" [:pointer :int] :string false]
  [:foreign-function "sqlite3_column_type" [:pointer :int] :int false]
  [:foreign-function "sqlite3_column_text" [:pointer :int] :string false]
  [:foreign-function "sqlite3_column_int64" [:pointer :int] :int64 false]
  [:foreign-function "sqlite3_column_double" [:pointer :int] :double false]
  [:foreign-function "sqlite3_bind_text"
   [:pointer :int :string :int :iptr] :int false]
  [:foreign-function "sqlite3_bind_int64" [:pointer :int :int64] :int false]
  [:foreign-function "sqlite3_bind_double" [:pointer :int :double] :int false]
  [:foreign-function "sqlite3_bind_null" [:pointer :int] :int false]
  [:foreign-function "sqlite3_bind_blob64"
   [:pointer :int :pointer :uint64 :iptr] :int false]
  [:foreign-function "sqlite3_column_blob" [:pointer :int] :pointer false]
  [:foreign-function "sqlite3_column_bytes" [:pointer :int] :int false]
  [:foreign-function "sqlite3_errcode" [:pointer] :int false]
  [:foreign-function "sqlite3_changes" [:pointer] :int false]
  [:foreign-function "sqlite3_last_insert_rowid" [:pointer] :int64 false]])

;; ---- plan validation ----------------------------------------------------

(def ^:private cell-types #{:integer :float :text :blob :null})

(defn- normalize-cell [context cell]
  (when-not (map? cell)
    (fail! :jolt.sim.sqlite/invalid-plan
           "statement cells and expected parameters must be maps"
           (assoc context :cell cell)))
  (let [type (:type cell)
        value (:value cell)]
    (when-not (contains? cell-types type)
      (fail! :jolt.sim.sqlite/invalid-plan
             "statement cell has an unsupported :type"
             (assoc context :cell cell :supported-types cell-types)))
    (case type
      :integer
      (when-not (integer? value)
        (fail! :jolt.sim.sqlite/invalid-plan
               ":integer cells require an integer :value"
               (assoc context :cell cell)))

      :float
      (when-not (number? value)
        (fail! :jolt.sim.sqlite/invalid-plan
               ":float cells require a numeric :value"
               (assoc context :cell cell)))

      :text
      (when-not (string? value)
        (fail! :jolt.sim.sqlite/invalid-plan
               ":text cells require a string :value"
               (assoc context :cell cell)))

      :blob nil
      :null nil)
    (case type
      :blob
      (let [bytes (normalize-bytes value)
            null-pointer? (:null-pointer? cell)]
        (when-not (or (nil? null-pointer?) (boolean? null-pointer?))
          (fail! :jolt.sim.sqlite/invalid-plan
                 ":blob :null-pointer? must be boolean when supplied"
                 (assoc context :cell cell)))
        (when (and null-pointer? (seq bytes))
          (fail! :jolt.sim.sqlite/invalid-plan
                 ":blob :null-pointer? is valid only for an empty BLOB"
                 (assoc context :cell cell)))
        (assoc cell :value bytes))

      :null {:type :null}
      cell)))

(defn- normalize-params [plan-index params]
  (when-not (or (nil? params) (map? params))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :params must be a map keyed by positive 1-based indices"
           {:plan-index plan-index :params params}))
  (into {}
        (map
         (fn [[index cell]]
           (when-not (and (integer? index) (pos? index))
             (fail! :jolt.sim.sqlite/invalid-plan
                    "plan parameter indices must be positive integers"
                    {:plan-index plan-index :index index}))
           [index
            (normalize-cell
             {:plan-index plan-index :parameter-index index}
             cell)]))
        (or params {})))

(defn- normalize-rows [plan-index columns rows]
  (when-not (or (nil? rows) (vector? rows))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :rows must be a vector of row vectors"
           {:plan-index plan-index :rows rows}))
  (let [rowv (or rows [])
        expected-width
        (if (seq columns)
          (count columns)
          (when-first [row rowv]
            (when-not (vector? row)
              (fail! :jolt.sim.sqlite/invalid-plan
                     "each result row must be a vector"
                     {:plan-index plan-index :row-index 0 :row row}))
            (count row)))]
    (mapv
     (fn [row-index row]
       (when-not (vector? row)
         (fail! :jolt.sim.sqlite/invalid-plan
                "each result row must be a vector"
                {:plan-index plan-index
                 :row-index row-index
                 :row row}))
       (when (and (some? expected-width)
                  (not= expected-width (count row)))
         (fail! :jolt.sim.sqlite/invalid-plan
                "every result row must match the declared column width"
                {:plan-index plan-index
                 :row-index row-index
                 :expected-width expected-width
                 :actual-width (count row)}))
       (mapv
        (fn [column-index cell]
          (normalize-cell
           {:plan-index plan-index
            :row-index row-index
            :column-index column-index}
           cell))
        (range (count row))
        row))
     (range (count rowv))
     rowv)))

(defn- validate-plans [plans]
  (let [planv (vec plans)]
    (mapv
     (fn [i plan]
       (when-not (map? plan)
         (fail! :jolt.sim.sqlite/invalid-plan
                "each plan must be a map"
                {:index i :plan plan}))
       (when-not (string? (:sql plan))
         (fail! :jolt.sim.sqlite/invalid-plan
                "each plan must have a :sql string"
                {:index i :plan plan}))
       (let [columns (or (:columns plan) [])
             error (:error plan)]
         (when-not
           (and (vector? columns) (every? string? columns))
           (fail! :jolt.sim.sqlite/invalid-plan
                  "plan :columns must be a vector of strings"
                  {:index i :columns columns}))
         (when-not
           (or (nil? (:changes plan))
               (and (integer? (:changes plan))
                    (not (neg? (:changes plan)))))
           (fail! :jolt.sim.sqlite/invalid-plan
                  "plan :changes must be a non-negative integer"
                  {:index i :changes (:changes plan)}))
         (when-not
           (or (nil? (:last-row-id plan))
               (integer? (:last-row-id plan)))
           (fail! :jolt.sim.sqlite/invalid-plan
                  "plan :last-row-id must be an integer"
                  {:index i :last-row-id (:last-row-id plan)}))
         (when-not
           (or
            (nil? error)
            (and (map? error)
                 (integer? (:code error))
                 (or (nil? (:msg error)) (string? (:msg error)))))
           (fail! :jolt.sim.sqlite/invalid-plan
                  "plan :error must contain an integer :code and optional string :msg"
                  {:index i :error error}))
         (assoc plan
                :params (normalize-params i (:params plan))
                :columns columns
                :rows (normalize-rows i columns (:rows plan)))))
     (range (count planv))
     planv)))

(defn- column-names [plan]
  (if (seq (:columns plan))
    (vec (:columns plan))
    (mapv #(str "c" %) (range (count (first (:rows plan)))))))

;; ---- world --------------------------------------------------------------

(defn world
  "Returns a deterministic SQLite world over a memory world and an ordered
  vector of statement plans. With one argument the world builds a default
  LP64 little-endian jolt.sim.ffi-memory world; with two, the caller supplies
  the memory world so SQLite effects and native-memory effects share one heap.
  Each plan is a map:

    {:sql \"SELECT ...\"              ;; required, matched at prepare
     :params {1 {:type :integer :value 5} ...}  ;; 1-based; matched at bind
     :columns [\"id\" \"name\"]        ;; derived from rows when omitted
     :rows [[{:type :text :value \"a\"} ...] ...] ;; each cell typed
     :changes 0                       ;; served by sqlite3_changes after done
     :last-row-id 0                   ;; served by sqlite3_last_insert_rowid
     :error {:code 1 :msg \"...\"}}   ;; optional soft step error

  Cell types are :integer :float :text :blob :null. A :blob cell's :value is a
  byte array or unsigned byte vector and an empty BLOB may opt into
  :null-pointer? true; :null cells carry no :value."
  ([plans]
   (world (memory/world) plans))
  ([memory-world plans]
   (when-not (and (map? memory-world) (map? (:config memory-world)))
     (fail! :jolt.sim.sqlite/invalid-world
            "first argument must be a jolt.sim.ffi-memory world"
            {:provided memory-world}))
   {:memory memory-world
    :memory-handlers (memory/handlers memory-world)
    :state (atom {:plans (validate-plans plans)
                  :plan-index 0
                  :dbs {}
                  :stmts {}
                  :closed-dbs #{}
                  :finalized-stmts #{}})
    :type ::sqlite-world}))

;; ---- memory bridge ------------------------------------------------------

(defn- invoke-mem [w op args]
  (let [h (get (:memory-handlers w) [:native-operation op])]
    (h {:kind :native-operation :task 0 :arguments args :operation op})))

;; ---- state transitions --------------------------------------------------

(defn- claim-plan! [w]
  (loop []
    (let [s @(:state w)
          idx (:plan-index s)
          plans (:plans s)]
      (cond
        (>= idx (count plans))
        (fail! :jolt.sim.sqlite/plan-exhausted
               "prepare consumed more statements than the plan supplied"
               {:plan-index idx :plan-count (count plans)})
        (compare-and-set! (:state w) s (assoc s :plan-index (inc idx)))
        (nth plans idx)
        :else (recur)))))

(defn- require-db! [w addr]
  (let [s @(:state w)]
    (cond
      (contains? (:dbs s) addr)        (get-in s [:dbs addr])
      (contains? (:closed-dbs s) addr)
      (fail! :jolt.sim.sqlite/use-after-close
             "connection handle was already closed"
             {:handle addr})
      :else
      (fail! :jolt.sim.sqlite/unknown-handle
             "pointer is not a known connection handle"
             {:handle addr}))))

(defn- require-stmt! [w addr]
  (let [s @(:state w)]
    (cond
      (contains? (:stmts s) addr)      (get-in s [:stmts addr])
      (contains? (:finalized-stmts s) addr)
      (fail! :jolt.sim.sqlite/use-after-finalize
             "statement handle was already finalized"
             {:handle addr})
      :else
      (fail! :jolt.sim.sqlite/unknown-handle
             "pointer is not a known statement handle"
             {:handle addr}))))

(defn- free-list! [w addrs]
  (doseq [addr addrs]
    (invoke-mem w :free [addr])))

;; ---- bind matching ------------------------------------------------------

(defn- param-value-matches? [type value expected]
  (case type
    :integer (= value (:value expected))
    :float   (= (double value) (double (:value expected)))
    :text    (= value (:value expected))
    :blob    (= (normalize-bytes value) (normalize-bytes (:value expected)))
    :null    true))

(defn- bind! [w stmt-addr index type value]
  (let [stmt (require-stmt! w stmt-addr)
        plan (:plan stmt)
        expected (get (:params plan) index)]
    (when (nil? expected)
      (fail! :jolt.sim.sqlite/parameter-mismatch
             "bound a parameter index the plan did not expect"
             {:index index
              :expected-indices (sort (keys (:params plan)))}))
    (when-not (= (:type expected) type)
      (fail! :jolt.sim.sqlite/parameter-mismatch
             "bound parameter type differs from the plan"
             {:index index
              :expected-type (:type expected)
              :actual-type type}))
    (when-not (param-value-matches? type value expected)
      (fail! :jolt.sim.sqlite/parameter-mismatch
             "bound parameter value differs from the plan"
             {:index index
              :expected (:value expected)
              :actual value}))
    (swap! (:state w) assoc-in [:stmts stmt-addr :bindings index]
           {:type type :value value})
    (:ok result-codes)))

(defn- require-bindings-complete! [stmt]
  (let [expected (keys (:params (:plan stmt)))
        actual (:bindings stmt)
        missing (vec (sort (remove #(contains? actual %) expected)))]
    (when (seq missing)
      (fail! :jolt.sim.sqlite/parameter-mismatch
             "sqlite3_step reached a statement with required parameters unbound"
             {:missing-indices missing
              :bound-indices (vec (sort (keys actual)))}))))

;; ---- result cell access -------------------------------------------------

(defn- current-row [stmt]
  (let [idx (:row-index stmt)
        rows (:rows (:plan stmt))]
    (when (or (:done? stmt)
              (:errored? stmt)
              (neg? idx)
              (>= idx (count rows)))
      (fail! :jolt.sim.sqlite/no-current-row
             "column access requires the row most recently returned by sqlite3_step"
             {:row-index idx
              :done? (:done? stmt)
              :errored? (:errored? stmt)}))
    (nth rows idx)))

(defn- require-column [stmt col]
  (let [row (current-row stmt)]
    (when-not (and (integer? col) (<= 0 col) (< col (count row)))
      (fail! :jolt.sim.sqlite/column-out-of-range
             "column index is outside the current row"
             {:index col :column-count (count row)}))
    (nth row col)))

(defn- cell->bytes [cell]
  (case (:type cell)
    :blob    (normalize-bytes (:value cell))
    :text    (ba->uvec (utf8-bytes (str (:value cell))))
    :integer (ba->uvec (utf8-bytes (str (:value cell))))
    :float   (ba->uvec (utf8-bytes (str (:value cell))))
    :null    []))

(defn- borrow-bytes! [w stmt-addr stmt col bytes]
  (let [size (max 1 (count bytes))
        addr (invoke-mem w :alloc [size])]
    (when (pos? (count bytes))
      (invoke-mem w :write-array [addr (uvec->byte-array bytes)]))
    (swap! (:state w)
           (fn [s]
             (-> s
                 (update-in [:stmts stmt-addr :borrowed] conj addr)
                 (assoc-in [:stmts stmt-addr :blob-cache col] addr))))
    addr))

;; ---- per-function handlers ----------------------------------------------

(defn- h-open [w {:keys [arguments]}]
  (let [[_filename-ptr db-out-ptr] (vec arguments)
        handle-size (invoke-mem w :sizeof [:pointer])
        handle (invoke-mem w :alloc [handle-size])]
    (invoke-mem w :write [db-out-ptr :pointer 0 handle])
    (swap! (:state w) assoc-in [:dbs handle]
           {:errcode 0 :errmsg "not an error" :changes 0 :rowid 0})
    (:ok result-codes)))

(defn- h-close [w {:keys [arguments]}]
  (let [[db-addr] (vec arguments)]
    (require-db! w db-addr)
    (invoke-mem w :free [db-addr])
    (swap! (:state w)
           #(-> % (update :dbs dissoc db-addr)
                  (update :closed-dbs conj db-addr)))
    (:ok result-codes)))

(defn- h-errmsg [w {:keys [arguments]}]
  (:errmsg (require-db! w (first (vec arguments)))))

(defn- h-errcode [w {:keys [arguments]}]
  (:errcode (require-db! w (first (vec arguments)))))

(defn- h-changes [w {:keys [arguments]}]
  (:changes (require-db! w (first (vec arguments)))))

(defn- h-last-rowid [w {:keys [arguments]}]
  (:rowid (require-db! w (first (vec arguments)))))

(defn- read-sql [w sql-ptr nbytes]
  (if (neg? nbytes)
    (invoke-mem w :ptr->string [sql-ptr])
    (invoke-mem w :read-bytes [sql-ptr nbytes])))

(defn- h-prepare [w {:keys [arguments]}]
  (let [[db-addr sql-ptr nbytes stmt-out-ptr tail-out-ptr] (vec arguments)]
    (require-db! w db-addr)
    (let [sql (read-sql w sql-ptr nbytes)
          plan (claim-plan! w)]
      (when-not (= sql (:sql plan))
        (fail! :jolt.sim.sqlite/sql-mismatch
               "prepared SQL differs from the plan"
               {:expected (:sql plan) :actual sql}))
      (let [handle-size (invoke-mem w :sizeof [:pointer])
            handle (invoke-mem w :alloc [handle-size])]
        (invoke-mem w :write [stmt-out-ptr :pointer 0 handle])
        (when-not (zero? tail-out-ptr)
          (invoke-mem w :write [tail-out-ptr :pointer 0
                                (+ sql-ptr (utf8-length sql))]))
        (swap! (:state w) assoc-in [:stmts handle]
               {:db db-addr :plan plan :sql sql
                :columns (column-names plan)
                :bindings {} :row-index -1 :done? false :errored? false
                :borrowed [] :blob-cache {}})
        (:ok result-codes)))))

(defn- h-step [w {:keys [arguments]}]
  (let [[stmt-addr] (vec arguments)
        stmt (require-stmt! w stmt-addr)
        st (:state w)]
    (cond
      (:done? stmt)   (:misuse result-codes)
      (:errored? stmt) (:misuse result-codes)
      :else
      (let [plan (:plan stmt)]
        (require-bindings-complete! stmt)
        (if-let [err (:error plan)]
          (do
            (swap! st assoc-in [:stmts stmt-addr :errored?] true)
            (swap! st assoc-in [:dbs (:db stmt)]
                   {:errcode (:code err 1)
                    :errmsg (:msg err "database error")
                    :changes 0 :rowid 0})
            (:code err 1))
          (let [rows (vec (:rows plan))
                next-idx (inc (:row-index stmt))]
            (swap! st update-in [:stmts stmt-addr]
                   assoc :borrowed [] :blob-cache {})
            (free-list! w (:borrowed stmt))
            (if (< next-idx (count rows))
              (do
                (swap! st assoc-in [:stmts stmt-addr :row-index] next-idx)
                (swap! st update-in [:dbs (:db stmt)]
                       assoc :errcode (:row result-codes)
                       :errmsg "not an error")
                (:row result-codes))
              (do
                (swap! st update-in [:stmts stmt-addr]
                       assoc :done? true :borrowed [] :blob-cache {})
                (swap! st assoc-in [:dbs (:db stmt)]
                       {:errcode 0
                        :errmsg "not an error"
                        :changes (or (:changes plan) 0)
                        :rowid (or (:last-row-id plan) 0)})
                (:done result-codes)))))))))

(defn- h-finalize [w {:keys [arguments]}]
  (let [[stmt-addr] (vec arguments)
        stmt (require-stmt! w stmt-addr)]
    (free-list! w (:borrowed stmt))
    (invoke-mem w :free [stmt-addr])
    (swap! (:state w)
           #(-> % (update :stmts dissoc stmt-addr)
                  (update :finalized-stmts conj stmt-addr)))
    (:ok result-codes)))

(defn- h-column-count [w {:keys [arguments]}]
  (count (:columns (require-stmt! w (first (vec arguments))))))

(defn- h-column-name [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        stmt (require-stmt! w stmt-addr)
        names (:columns stmt)]
    (when-not (and (integer? col) (<= 0 col) (< col (count names)))
      (fail! :jolt.sim.sqlite/column-out-of-range
             "column name index is outside the statement"
             {:index col :column-count (count names)}))
    (nth names col)))

(defn- h-column-type [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        cell (require-column (require-stmt! w stmt-addr) col)]
    (type-codes (:type cell))))

(defn- h-column-text [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        cell (require-column (require-stmt! w stmt-addr) col)]
    (case (:type cell)
      :text    (:value cell)
      :integer (str (:value cell))
      :float   (str (:value cell))
      :blob    (String. (uvec->byte-array (normalize-bytes (:value cell)))
                        "UTF-8")
      :null    nil)))

(defn- h-column-int64 [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        cell (require-column (require-stmt! w stmt-addr) col)]
    (case (:type cell)
      :integer (:value cell)
      :float   (long (:value cell))
      :text    (or (parse-long (:value cell)) 0)
      :blob    0
      :null    0)))

(defn- h-column-double [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        cell (require-column (require-stmt! w stmt-addr) col)]
    (case (:type cell)
      :float   (:value cell)
      :integer (double (:value cell))
      :text    0.0
      :blob    0.0
      :null    0.0)))

(defn- h-column-bytes [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        cell (require-column (require-stmt! w stmt-addr) col)]
    (if (= :null (:type cell)) 0 (count (cell->bytes cell)))))

(defn- h-column-blob [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)
        stmt (require-stmt! w stmt-addr)
        cell (require-column stmt col)]
    (if (or (= :null (:type cell))
            (true? (:null-pointer? cell)))
      0
      (or (get-in @(:state w) [:stmts stmt-addr :blob-cache col])
          (borrow-bytes! w stmt-addr stmt col (cell->bytes cell))))))

(defn- h-bind-text [w {:keys [arguments]}]
  (let [[stmt-addr index value _nbytes _destr] (vec arguments)]
    (bind! w stmt-addr index :text value)))

(defn- h-bind-int64 [w {:keys [arguments]}]
  (let [[stmt-addr index value] (vec arguments)]
    (bind! w stmt-addr index :integer value)))

(defn- h-bind-double [w {:keys [arguments]}]
  (let [[stmt-addr index value] (vec arguments)]
    (bind! w stmt-addr index :float value)))

(defn- h-bind-null [w {:keys [arguments]}]
  (let [[stmt-addr index] (vec arguments)]
    (bind! w stmt-addr index :null nil)))

(defn- h-bind-blob64 [w {:keys [arguments]}]
  (let [[stmt-addr index blob-ptr nbytes _destr] (vec arguments)
        n (long nbytes)
        copied (if (pos? n)
                 (ba->uvec (invoke-mem w :read-array [blob-ptr n]))
                 [])]
    (bind! w stmt-addr index :blob copied)))

(defn- sqlite-fns [w]
  {"sqlite3_open" (partial h-open w)
   "sqlite3_close_v2" (partial h-close w)
   "sqlite3_errmsg" (partial h-errmsg w)
   "sqlite3_prepare_v2" (partial h-prepare w)
   "sqlite3_step" (partial h-step w)
   "sqlite3_finalize" (partial h-finalize w)
   "sqlite3_column_count" (partial h-column-count w)
   "sqlite3_column_name" (partial h-column-name w)
   "sqlite3_column_type" (partial h-column-type w)
   "sqlite3_column_text" (partial h-column-text w)
   "sqlite3_column_int64" (partial h-column-int64 w)
   "sqlite3_column_double" (partial h-column-double w)
   "sqlite3_column_bytes" (partial h-column-bytes w)
   "sqlite3_column_blob" (partial h-column-blob w)
   "sqlite3_bind_text" (partial h-bind-text w)
   "sqlite3_bind_int64" (partial h-bind-int64 w)
   "sqlite3_bind_double" (partial h-bind-double w)
   "sqlite3_bind_null" (partial h-bind-null w)
   "sqlite3_bind_blob64" (partial h-bind-blob64 w)
   "sqlite3_errcode" (partial h-errcode w)
   "sqlite3_changes" (partial h-changes w)
   "sqlite3_last_insert_rowid" (partial h-last-rowid w)})

(defn foreign-handlers
  "Returns only the exact 22 SQLite foreign-function handlers keyed by the
  runtime's accepted five-element descriptor shorthands. Handler-pack/runtime
  validation normalizes them to canonical seven-element keys with capture false
  and varargs-after nil. The memory world's
  native-operation handlers are not included; compose them separately (e.g.
  through jolt.sim.handler-pack/compose) when one memory world must back SQLite
  alongside other foreign handler packs without double-registering the shared
  native operations."
  [w]
  (let [fns (sqlite-fns w)]
    (into {}
          (map (fn [key]
                 (let [symbol (nth key 1)
                       handler (get fns symbol)]
                   (when-not (fn? handler)
                     (fail! :jolt.sim.sqlite/invalid-handler-registry
                            "SQLite handler registry is incomplete"
                            {:symbol symbol :handler handler}))
                   [key handler])))
          handler-keys)))

(defn handlers
  "Returns the memory world's 16 native-operation handlers merged with exactly
  the 22 SQLite foreign-function keys, as one :ffi-handlers map. Both layers
  share the single memory world."
  [w]
  (merge (:memory-handlers w) (foreign-handlers w)))

;; ---- evidence -----------------------------------------------------------

(defn state
  "Returns the current SQLite state (plans, plan-index, dbs, stmts) for
  evidence. Handle addresses are the keys; connection records carry errcode,
  errmsg, changes, and rowid; statement records carry the plan, bindings, the
  current row index, and done/errored flags."
  [w]
  @(:state w))

(defn clean?
  "True when the shared memory world has no live allocations. Because fake
  connection/statement handles and borrowed BLOBs are memory-world
  allocations, this reflects whether every handle and borrowed BLOB was freed."
  [w]
  (memory/clean? (:memory w)))

(defn leaks
  "Live memory-world allocation summaries (handles and borrowed BLOBs that were
  not freed), ordered by address."
  [w]
  (memory/leaks (:memory w)))

(defn snapshot
  "Stable plain-data dump of every memory-world allocation (handles, cells, and
  borrowed BLOBs), ordered by address. Freed allocations are retained as
  evidence with :freed? true."
  [w]
  (memory/snapshot (:memory w)))

(defn summary
  "Returns {:plan-index :plan-count :open-dbs :active-stmts} for quick evidence
  without the full state map."
  [w]
  (let [s @(:state w)]
    {:plan-index (:plan-index s)
     :plan-count (count (:plans s))
     :open-dbs (count (:dbs s))
     :active-stmts (count (:stmts s))}))
