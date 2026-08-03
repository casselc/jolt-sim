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

  A plan may carry one closed :tx-effect directive describing a physical
  transaction-boundary transition (:op :begin/:commit/:rollback, gated by
  :when :on-success/:always/:never). The model applies it at most once at the
  statement's first terminal step and never infers transaction behavior from
  SQL text. sqlite3_get_autocommit observes the physical autocommit state.

  handlers returns the 16 native-operation handlers from the memory world
  merged with exactly the 23 SQLite foreign-function keys required by
  db.sqlite, so a single :ffi-handlers map drives both layers unchanged.

  hybrid-handlers returns the same 39 keys classified for jolt.sim.runtime
  :hybrid routing: memory/hybrid-handlers plus hybrid-foreign-handlers, which
  reuses foreign-handlers exactly once per call for every key except
  sqlite3_column_blob and substitutes each such result. sqlite3_column_blob
  instead runs its own model transition exactly once, classifying its
  positive borrowed pointer as a runtime/modeled-resource spanning its live
  BLOB allocation and substituting a null pointer."
  (:require [jolt.sim.ffi-memory :as memory]
            [jolt.sim.runtime :as runtime]))

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

;; ---- the exact 23 db.sqlite foreign-function keys ----------------------

(def
 ^{:doc "The exact 23 SQLite foreign-function handler keys required by
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
  [:foreign-function "sqlite3_get_autocommit" [:pointer] :int false]
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

(def ^:private tx-effect-ops #{:begin :commit :rollback})

(def ^:private tx-effect-whens #{:on-success :always :never})

(defn- normalize-tx-effect [plan-index tx-effect]
  (when-not (or (nil? tx-effect) (map? tx-effect))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :tx-effect must be a map with :op and an optional :when"
           {:plan-index plan-index :tx-effect tx-effect}))
  (when (some? tx-effect)
    (when-not (contains? tx-effect-ops (:op tx-effect))
      (fail! :jolt.sim.sqlite/invalid-plan
             "plan :tx-effect :op must be :begin, :commit, or :rollback"
             {:plan-index plan-index
              :tx-effect tx-effect
              :supported-ops tx-effect-ops}))
    (when-not (or (nil? (:when tx-effect))
                  (contains? tx-effect-whens (:when tx-effect)))
      (fail! :jolt.sim.sqlite/invalid-plan
             "plan :tx-effect :when must be :on-success, :always, or :never"
             {:plan-index plan-index
              :tx-effect tx-effect
              :supported-whens tx-effect-whens}))
    (let [unknown (seq (sort (remove #{:op :when} (keys tx-effect))))]
      (when unknown
        (fail! :jolt.sim.sqlite/invalid-plan
               "plan :tx-effect is closed: only :op and :when are allowed"
               {:plan-index plan-index
                :tx-effect tx-effect
                :unknown-keys (vec unknown)})))
    {:op (:op tx-effect)
     :when (or (:when tx-effect) :on-success)}))

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
                 (pos? (:code error))
                 (not (contains? #{100 101} (:code error)))
                 (or (nil? (:msg error)) (string? (:msg error)))))
           (fail! :jolt.sim.sqlite/invalid-plan
                  "plan :error must contain a positive non-ROW/non-DONE :code and optional string :msg"
                  {:index i :error error}))
         (assoc plan
                :params (normalize-params i (:params plan))
                :columns columns
                :rows (normalize-rows i columns (:rows plan))
                :tx-effect (normalize-tx-effect i (:tx-effect plan)))))
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
      :error {:code 1 :msg \"...\"}    ;; optional soft step error
      :tx-effect {:op :begin :when :on-success}} ;; optional physical
      ;; transaction-boundary directive

  Cell types are :integer :float :text :blob :null. A :blob cell's :value is a
  byte array or unsigned byte vector and an empty BLOB may opt into
  :null-pointer? true; :null cells carry no :value.

  A plan's :tx-effect is the only source of physical transaction transitions;
  the model never infers transaction behavior from SQL text. :op is :begin,
  :commit, or :rollback; :when is :on-success (the default), :always, or
  :never. The effect applies at most once, at the statement's first terminal
  step: :on-success applies only when the step completes done, :always
  applies the physical transition even when the plan then reports its :error
  (an uncertain BEGIN), and :never reports the step outcome with no physical
  transition (an adversarial success-withheld control). A transition that is
  impossible for the connection's physical autocommit state -- :begin while a
  transaction is active, :commit or :rollback while in autocommit -- throws
  :jolt.sim.sqlite/impossible-tx-transition (a hard failure). Each declared
  directive is evaluated exactly once and recorded in the connection's
  address-free :tx-evidence vector, whether its transition applies, is gated,
  is deliberately withheld, or is physically impossible.
  sqlite3_get_autocommit returns 1 in autocommit and 0 inside a transaction;
  every observation is retained in :autocommit-evidence. Closing a connection
  with an active transaction discards it and appends the complete immutable
  connection record to :closed-db-evidence after the live record is removed."
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
                  :next-connection-id 0
                  :stmts {}
                  :closed-dbs #{}
                  :closed-db-evidence []
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
        [idx (nth plans idx)]
        :else (recur)))))

(defn- register-db! [w handle]
  (loop []
    (let [st (:state w)
          s @st
          connection-id (:next-connection-id s)
          db {:connection-id connection-id
              :errcode 0 :errmsg "not an error" :changes 0 :rowid 0
              :autocommit? true :tx nil :tx-evidence []
              :autocommit-evidence []}
          next-state (-> s
                         (assoc-in [:dbs handle] db)
                         (update :next-connection-id inc))]
      (if (compare-and-set! st s next-state)
        connection-id
        (recur)))))

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

;; ---- physical transaction boundary ---------------------------------------

(defn- tx-effect-decision [db stmt reported]
  (let [tx-effect (:tx-effect (:plan stmt))
        op (:op tx-effect)
        mode (:when tx-effect)
        requested? (case mode
                     :on-success (= :done reported)
                     :always true
                     :never false)
        before (:autocommit? db)
        impossible? (and requested?
                         (or (and (= :begin op) (not before))
                             (and (not= :begin op) before)))
        applied? (and requested? (not impossible?))
        after (if applied? (not= :begin op) before)
        sequence (count (:tx-evidence db))
        event {:sequence sequence
               :plan-index (:plan-index stmt)
               :op op
               :when mode
               :reported reported
               :applied? applied?
               :reason (cond
                         impossible? :impossible-state
                         (= :never mode) :withheld
                         (not requested?) :reported-error
                         :else nil)
               :before-autocommit? before
               :after-autocommit? after}
        next-db (cond-> (update db :tx-evidence conj event)
                  applied?
                  (assoc :autocommit? after
                         :tx (when (= :begin op)
                               {:begin-event sequence
                                :begin-plan-index (:plan-index stmt)})))]
    {:db next-db
     :impossible? impossible?
     :error-data {:op op
                  :connection-id (:connection-id db)
                  :plan-index (:plan-index stmt)
                  :autocommit? before
                  :reported reported}}))

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

(defn- complete-tx-terminal!
  "Atomically owns one transaction statement's terminal step, records its
  boundary decision, updates the physical connection and publishes the
  reported SQLite result. A competing terminal step observes misuse; a close
  that wins the same CAS makes the step fail as use-after-close."
  [w stmt-addr reported]
  (loop []
    (let [st (:state w)
          s @st
          stmt (get-in s [:stmts stmt-addr])]
      (cond
        (nil? stmt)
        (if (contains? (:finalized-stmts s) stmt-addr)
          (fail! :jolt.sim.sqlite/use-after-finalize
                 "statement handle was already finalized"
                 {:handle stmt-addr})
          (fail! :jolt.sim.sqlite/unknown-handle
                 "pointer is not a known statement handle"
                 {:handle stmt-addr}))

        (or (:done? stmt) (:errored? stmt))
        (:misuse result-codes)

        :else
        (let [db-addr (:db stmt)
              live-db (get-in s [:dbs db-addr])]
          (cond
            (nil? live-db)
            (if (contains? (:closed-dbs s) db-addr)
              (fail! :jolt.sim.sqlite/use-after-close
                     "statement belongs to a closed connection"
                     {:connection-id (:connection-id stmt)
                      :plan-index (:plan-index stmt)})
              (fail! :jolt.sim.sqlite/unknown-handle
                     "statement refers to an unknown connection handle"
                     {:plan-index (:plan-index stmt)}))

            :else
            (let [plan (:plan stmt)
                  _ (require-bindings-complete! stmt)
                  decision (tx-effect-decision live-db stmt reported)
                  next-db (:db decision)
                  impossible? (:impossible? decision)
                  error-data (:error-data decision)
                  err (:error plan)
                  terminal-db
                  (if impossible?
                    next-db
                    (if (= :error reported)
                      (assoc next-db
                             :errcode (:code err)
                             :errmsg (or (:msg err) "database error")
                             :changes 0
                             :rowid 0)
                      (assoc next-db
                             :errcode 0
                             :errmsg "not an error"
                             :changes (or (:changes plan) 0)
                             :rowid (or (:last-row-id plan) 0))))
                  terminal-stmt
                  (assoc stmt
                         :tx-effect-evaluated? true
                         :done? (and (= :done reported) (not impossible?))
                         :errored? (or (= :error reported) impossible?)
                         :borrowed []
                         :blob-cache {})
                  next-state (-> s
                                 (assoc-in [:dbs db-addr] terminal-db)
                                 (assoc-in [:stmts stmt-addr] terminal-stmt))]
              (if (compare-and-set! st s next-state)
                (if impossible?
                  (fail!
                   :jolt.sim.sqlite/impossible-tx-transition
                   "declared :tx-effect is impossible for the connection's physical autocommit state"
                   error-data)
                  (if (= :error reported)
                    (:code err)
                    (:done result-codes)))
                (recur)))))))))

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
    (register-db! w handle)
    (:ok result-codes)))

(defn- claim-close! [w db-addr]
  (loop []
    (let [st (:state w)
          s @st]
      (cond
        (contains? (:closed-dbs s) db-addr)
        (fail! :jolt.sim.sqlite/use-after-close
               "connection handle was already closed"
               {:handle db-addr})

        (not (contains? (:dbs s) db-addr))
        (fail! :jolt.sim.sqlite/unknown-handle
               "pointer is not a known connection handle"
               {:handle db-addr})

        :else
        (let [db (get-in s [:dbs db-addr])
              evidence (cond-> (assoc db
                                      :close-index
                                      (count (:closed-db-evidence s)))
                         (not (:autocommit? db))
                         (assoc :discarded-transaction (:tx db)))
              next-state (-> s
                             (update :dbs dissoc db-addr)
                             (update :closed-dbs conj db-addr)
                             (update :closed-db-evidence conj evidence))]
          (if (compare-and-set! st s next-state)
            evidence
            (recur)))))))

(defn- h-close [w {:keys [arguments]}]
  (let [[db-addr] (vec arguments)]
    ;; Closing with an active transaction discards it (SQLite rolls back on
    ;; close). First atomically claim the close and retain the latest complete
    ;; record; then free the opaque handle. A probe or terminal transaction
    ;; transition either wins before this CAS and is captured, or observes the
    ;; closed state afterwards.
    (claim-close! w db-addr)
    (invoke-mem w :free [db-addr])
    (:ok result-codes)))

(defn- h-errmsg [w {:keys [arguments]}]
  (:errmsg (require-db! w (first (vec arguments)))))

(defn- h-errcode [w {:keys [arguments]}]
  (:errcode (require-db! w (first (vec arguments)))))

(defn- h-get-autocommit [w {:keys [arguments]}]
  (let [db-addr (first (vec arguments))]
    (loop []
      (let [st (:state w)
            s @st]
        (cond
          (contains? (:closed-dbs s) db-addr)
          (fail! :jolt.sim.sqlite/use-after-close
                 "connection handle was already closed"
                 {:handle db-addr})

          (not (contains? (:dbs s) db-addr))
          (fail! :jolt.sim.sqlite/unknown-handle
                 "pointer is not a known connection handle"
                 {:handle db-addr})

          :else
          (let [result (if (get-in s [:dbs db-addr :autocommit?]) 1 0)
                next-state (update-in s [:dbs db-addr :autocommit-evidence]
                                      conj result)]
            (if (compare-and-set! st s next-state)
              result
              (recur))))))))

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
    (let [connection-id (:connection-id (require-db! w db-addr))
          sql (read-sql w sql-ptr nbytes)
          [plan-index plan] (claim-plan! w)]
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
               {:db db-addr :connection-id connection-id
                :plan-index plan-index :plan plan :sql sql
                :columns (column-names plan)
                :bindings {} :row-index -1 :done? false :errored? false
                :borrowed [] :blob-cache {} :tx-effect-evaluated? false})
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
          (if (:tx-effect plan)
            ;; The physical transition (when the directive applies) precedes
            ;; the reported error, so an :always plan models an uncertain
            ;; BEGIN: in-transaction physically, error over the wire. The
            ;; transaction decision and terminal statement result share one
            ;; CAS, so a competing step cannot report the terminal result too.
            (complete-tx-terminal! w stmt-addr :error)
            (do
              (swap! st assoc-in [:stmts stmt-addr :errored?] true)
              (swap! st update-in [:dbs (:db stmt)]
                     assoc :errcode (:code err)
                     :errmsg (or (:msg err) "database error")
                     :changes 0 :rowid 0)
              (:code err)))
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
                (if (:tx-effect plan)
                  (complete-tx-terminal! w stmt-addr :done)
                  (do
                    (swap! st update-in [:stmts stmt-addr]
                           assoc :done? true :borrowed [] :blob-cache {})
                    (swap! st update-in [:dbs (:db stmt)]
                           assoc :errcode 0
                           :errmsg "not an error"
                           :changes (or (:changes plan) 0)
                           :rowid (or (:last-row-id plan) 0))
                    (:done result-codes)))))))))))

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

(defn- blob-borrow-outcome
  "Runs the exact sqlite3_column_blob model transition exactly once for
  stmt-addr/col: one read of the current row's cell drives both the pointer
  and, for a non-null result, its exact live span, so neither hermetic nor
  hybrid callers ever reread current-row state after the fact or borrow
  twice for one call. NULL and an opted-in null-pointer empty BLOB return
  {:pointer 0}. Every other BLOB returns the borrowed (or already-cached)
  pointer paired with :span, the exact byte count of the allocation it names
  -- at least one byte even for an empty non-null BLOB, matching
  borrow-bytes!'s own size."
  [w stmt-addr col]
  (let [stmt (require-stmt! w stmt-addr)
        cell (require-column stmt col)]
    (if (or (= :null (:type cell))
            (true? (:null-pointer? cell)))
      {:pointer 0}
      (let [bytes (cell->bytes cell)
            span (max 1 (count bytes))
            cached (get-in @(:state w) [:stmts stmt-addr :blob-cache col])
            pointer (or cached (borrow-bytes! w stmt-addr stmt col bytes))]
        {:pointer pointer :span span}))))

(defn- h-column-blob [w {:keys [arguments]}]
  (let [[stmt-addr col] (vec arguments)]
    (:pointer (blob-borrow-outcome w stmt-addr col))))

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
   "sqlite3_get_autocommit" (partial h-get-autocommit w)
   "sqlite3_changes" (partial h-changes w)
   "sqlite3_last_insert_rowid" (partial h-last-rowid w)})

(defn foreign-handlers
  "Returns only the exact 23 SQLite foreign-function handlers keyed by the
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
  the 23 SQLite foreign-function keys, as one :ffi-handlers map. Both layers
  share the single memory world."
  [w]
  (merge (:memory-handlers w) (foreign-handlers w)))

;; ---- hybrid classification ------------------------------------------------

(defn- classify-foreign-result
  "Classifies one raw non-pointer SQLite foreign-function result for
  jolt.sim.runtime :hybrid routing as an ordinary runtime/substitute.
  sqlite3_column_blob is the sole pointer-returning key (:return-type
  :pointer) and is classified separately by classify-column-blob, from the
  same model transition that produces its pointer, so this generic path never
  needs to inspect the descriptor to find a fake pointer."
  [result]
  (runtime/substitute result))

(defn- classify-column-blob
  "Classifies sqlite3_column_blob for jolt.sim.runtime :hybrid routing by
  running blob-borrow-outcome exactly once: a positive pointer becomes a
  runtime/modeled-resource spanning its live BLOB allocation (both drawn from
  that one transition), and a null pointer becomes runtime/substitute like
  every other SQLite result. Never reruns or rereads current-row state after
  the fact, and never borrows twice for one call."
  [w descriptor]
  (let [[stmt-addr col] (:arguments descriptor)
        {:keys [pointer span]} (blob-borrow-outcome w stmt-addr col)]
    (if (pos? pointer)
      (runtime/modeled-resource pointer span)
      (runtime/substitute pointer))))

(defn hybrid-foreign-handlers
  "Returns exactly the 23 SQLite foreign-function handlers, compatible with
  jolt.sim.runtime :hybrid :ffi-handlers. Every key except sqlite3_column_blob
  reuses the corresponding foreign-handlers result exactly once per call and
  classifies it with classify-foreign-result. sqlite3_column_blob bypasses
  foreign-handlers entirely in hybrid mode and instead runs
  classify-column-blob, so its pointer and span always come from the same
  single model transition. No handler here returns runtime/proceed, so a
  caller must override a returned handler explicitly to select native routing
  for that key."
  [w]
  (let [base (foreign-handlers w)]
    (into {}
          (map (fn [[key f]]
                 [key
                  (if (= "sqlite3_column_blob" (nth key 1))
                    (fn sqlite-hybrid-column-blob-handler [descriptor]
                      (classify-column-blob w descriptor))
                    (fn sqlite-hybrid-handler [descriptor]
                      (classify-foreign-result (f descriptor))))]))
          base)))

(defn hybrid-handlers
  "Returns the memory world's 16 hybrid native-operation handlers merged with
  the 23 hybrid SQLite foreign-function handlers, as one :ffi-handlers map for
  jolt.sim.runtime :hybrid routing. The two key sets never collide: every
  memory key is a 2-element [:native-operation op] vector and every SQLite key
  is a 5-element [:foreign-function ...] vector, so no key is shared."
  [w]
  (merge (memory/hybrid-handlers (:memory w)) (hybrid-foreign-handlers w)))

;; ---- evidence -----------------------------------------------------------

(defn state
  "Returns the current SQLite state (plans, plan-index, dbs, stmts) for
  evidence. Handle addresses are the keys; connection records carry errcode,
  errmsg, changes, and rowid plus the physical transaction boundary:
  :autocommit?, the active address-free :tx descriptor, stable :tx-evidence,
  and every :autocommit-evidence observation. Statement records carry the
  plan, stable plan index, bindings, current row index, done/errored flags, and
  :tx-effect-evaluated?. Removed connection records are appended in close order
  to :closed-db-evidence without their raw handle addresses."
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
