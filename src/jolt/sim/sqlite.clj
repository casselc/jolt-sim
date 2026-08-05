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

  A plan may instead carry one closed :store-effect directive describing a
  physical key/value visibility transition over the connection's committed
  and staged storage (:op :put with :key-param/:value-param, or :op :get with
  :key-param; never both :tx-effect and :store-effect on one plan). A put
  writes to the connection's active staging while a transaction is open and
  directly to committed storage otherwise; BEGIN/COMMIT/ROLLBACK move staged
  writes exactly like the physical transaction boundary they already model. A
  get derives at most one result row from current visible state at execution
  time -- its own staged value first, then committed, otherwise no row -- and
  never serves a predeclared row. Both ops apply once, at the statement's
  first terminal step, and are never inferred from SQL text.

  A plan may instead carry one closed :row-effect directive describing a
  physical table-row transition over the same committed/staged overlay (:op
  :insert-row with :table/:key-params/:row, :op :update-row with
  :table/:key-params/:key-columns/:set and an optional :where equality guard,
  or :op :scan-rows with :table/:project/:order-key;
  never more than one of :tx-effect, :store-effect, and :row-effect on one
  plan). Complete rows live under tagged [:jolt.sim.sqlite/row table
  key-cells] identities that cannot collide with legacy :store-effect cell
  keys, and BEGIN/COMMIT/ROLLBACK/close move staged rows exactly like staged
  key/value writes. An insert writes its full typed row once from the actual
  exactly matched bindings, or reports SQLite constraint code 19 with changes
  0 and no mutation when its primary key is already visible; an update
  applies its declared typed column replacements to one visible existing row
  (changes 1) or reports DONE with changes 0 and no write when the key is
  absent; a scan snapshots its table's visible typed rows at its first
  sqlite3_step, projects cells in :project order, and serves them sorted by
  :order-key under a stable total ordering over the supported cell types,
  immune to any later mutation. Each row directive applies at most once,
  linearized with statement/connection lifetime exactly like the tx/store
  effects, and is recorded in the connection's address-free :row-evidence
  vector whether it applies, finds nothing, hits a constraint, or is reported
  as a plan-level error without mutating or exposing rows.

  An optional third world argument opts exact filenames into the sequential
  file-image substrate. sqlite3_open decodes the real filename pointer; only
  a filename in the world's closed :persistent-filenames set receives an
  :image-key and a durable image under the state's immutable :images map. The
  first open of a selected filename creates an empty image; a reopen after
  close starts a fresh connection from the prior committed image. A selected
  connection's :committed storage publishes to :images atomically in the same
  world CAS as any terminal DB transition, so transaction COMMIT and
  autocommit store/row writes survive close/reopen while staging, rollback,
  failed effects, and close with an active transaction never publish. At most
  one live connection per selected filename: a second simultaneous open fails
  with :jolt.sim.sqlite/connection-already-open before the output pointer is
  written and frees its provisional handle. Every other filename, including
  \":memory:\" and unselected file: names, keeps independent ephemeral
  per-connection storage.

  handlers returns the 15 native-operation handlers from the memory world
  merged with exactly the 23 SQLite foreign-function keys required by
  db.sqlite, so a single :ffi-handlers map drives both layers unchanged.

  hybrid-handlers returns the same 38 keys classified for jolt.sim.runtime
  :hybrid routing: memory/hybrid-handlers plus hybrid-foreign-handlers, which
  reuses foreign-handlers exactly once per call for every key except
  sqlite3_column_blob and substitutes each such result. sqlite3_column_blob
  instead runs its own model transition exactly once, classifying its
  positive borrowed pointer as a runtime/modeled-resource spanning its live
  BLOB allocation and substituting a null pointer."
  (:require [jolt.sim.ffi-memory :as memory]
            [jolt.sim.runtime :as runtime]))

;; ---- codes --------------------------------------------------------------

(def ^:private result-codes
  {:ok 0 :row 100 :done 101 :misuse 21 :constraint 19})

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

(def ^:private store-effect-ops #{:put :get})

(def ^:private store-effect-keys
  {:put #{:op :key-param :value-param}
   :get #{:op :key-param}})

(defn- require-positive-index! [plan-index field value]
  (when-not (and (integer? value) (pos? value))
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :store-effect " field
                " must be a positive integer parameter index")
           {:plan-index plan-index field value})))

(defn- require-declared-param! [plan-index field index params]
  (when-not (contains? params index)
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :store-effect " field
                " must reference a :params index the plan declares")
           {:plan-index plan-index field index})))

(defn- normalize-store-effect [plan-index store-effect params columns rows]
  (when-not (or (nil? store-effect) (map? store-effect))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :store-effect must be a map with :op and parameter indices"
           {:plan-index plan-index :store-effect store-effect}))
  (when (some? store-effect)
    (let [op (:op store-effect)]
      (when-not (contains? store-effect-ops op)
        (fail! :jolt.sim.sqlite/invalid-plan
               "plan :store-effect :op must be :put or :get"
               {:plan-index plan-index
                :store-effect store-effect
                :supported-ops store-effect-ops}))
      (let [allowed (get store-effect-keys op)
            unknown (seq (sort-by pr-str
                                  (remove allowed (keys store-effect))))]
        (when unknown
          (fail! :jolt.sim.sqlite/invalid-plan
                 "plan :store-effect is closed for its :op"
                 {:plan-index plan-index
                  :store-effect store-effect
                  :unknown-keys (vec unknown)})))
      (require-positive-index! plan-index :key-param (:key-param store-effect))
      (require-declared-param! plan-index :key-param (:key-param store-effect)
                                params)
      (when (= :put op)
        (require-positive-index! plan-index :value-param
                                  (:value-param store-effect))
        (require-declared-param! plan-index :value-param
                                  (:value-param store-effect) params)
        (when (seq rows)
          (fail! :jolt.sim.sqlite/invalid-plan
                 "a :store-effect :put plan must not predeclare :rows"
                 {:plan-index plan-index :store-effect store-effect})))
      (let [key-cell (get params (:key-param store-effect))]
        (when (= :null (:type key-cell))
          (fail! :jolt.sim.sqlite/invalid-plan
                 "plan :store-effect :key-param must not declare a null key"
                 {:plan-index plan-index :store-effect store-effect})))
      (when (= :get op)
        (when (seq rows)
          (fail! :jolt.sim.sqlite/invalid-plan
                 "a :store-effect :get plan must not predeclare :rows"
                 {:plan-index plan-index :store-effect store-effect}))
        (when-not (= 1 (count columns))
          (fail! :jolt.sim.sqlite/invalid-plan
                 "a :store-effect :get plan must declare exactly one column"
                 {:plan-index plan-index
                  :store-effect store-effect
                  :columns columns})))
      (cond-> {:op op :key-param (:key-param store-effect)}
        (= :put op) (assoc :value-param (:value-param store-effect))))))

(def ^:private row-effect-ops #{:insert-row :update-row :scan-rows})

(def ^:private row-effect-keys
  {:insert-row #{:op :table :key-params :row}
   :update-row #{:op :table :key-params :key-columns :set :where}
   :scan-rows #{:op :table :project :order-key}})

(defn- require-row-positive-index! [plan-index field value]
  (when-not (and (integer? value) (pos? value))
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :row-effect " field
                " must be a positive integer parameter index")
           {:plan-index plan-index field value})))

(defn- require-row-declared-param! [plan-index field index params]
  (when-not (contains? params index)
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :row-effect " field
                " must reference a :params index the plan declares")
           {:plan-index plan-index field index})))

(defn- require-row-unique! [plan-index field values]
  (let [valuev (vec values)]
    (when-not (= (count valuev) (count (set valuev)))
      (fail! :jolt.sim.sqlite/invalid-plan
             (str "plan :row-effect " field " must not contain duplicates")
             {:plan-index plan-index field valuev}))))

(defn- normalize-row-key-params [plan-index key-params params]
  (when-not (and (vector? key-params) (seq key-params))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :row-effect :key-params must be a nonempty vector of parameter indices"
           {:plan-index plan-index :key-params key-params}))
  (doseq [index key-params]
    (require-row-positive-index! plan-index :key-params index)
    (require-row-declared-param! plan-index :key-params index params)
    (when (= :null (:type (get params index)))
      (fail! :jolt.sim.sqlite/invalid-plan
             "plan :row-effect :key-params must not declare a null key"
             {:plan-index plan-index :key-params key-params})))
  (require-row-unique! plan-index :key-params key-params)
  (vec key-params))

(defn- normalize-row-column-bindings [plan-index field bindings params]
  (when-not (and (vector? bindings) (seq bindings))
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :row-effect " field
                " must be a nonempty vector of [column param-index] pairs")
           {:plan-index plan-index field bindings}))
  (let [pairs
        (mapv
         (fn [pair]
           (when-not (and (vector? pair) (= 2 (count pair)))
             (fail! :jolt.sim.sqlite/invalid-plan
                    (str "plan :row-effect " field
                         " entries must be [column param-index] pairs")
                    {:plan-index plan-index field pair}))
           (let [[column index] pair]
             (when-not (and (string? column) (pos? (count column)))
               (fail! :jolt.sim.sqlite/invalid-plan
                      (str "plan :row-effect " field
                           " column names must be nonempty strings")
                      {:plan-index plan-index field pair}))
             (require-row-positive-index! plan-index field index)
             (require-row-declared-param! plan-index field index params)
             [column index]))
         bindings)]
    (require-row-unique! plan-index field (map first pairs))
    (require-row-unique! plan-index field (map second pairs))
    pairs))

(defn- normalize-row-column-names [plan-index field columns]
  (when-not (vector? columns)
    (fail! :jolt.sim.sqlite/invalid-plan
           (str "plan :row-effect " field " must be a vector of column names")
           {:plan-index plan-index field columns}))
  (doseq [column columns]
    (when-not (and (string? column) (pos? (count column)))
      (fail! :jolt.sim.sqlite/invalid-plan
             (str "plan :row-effect " field
                  " column names must be nonempty strings")
             {:plan-index plan-index field columns})))
  (require-row-unique! plan-index field columns)
  (vec columns))

(defn- normalize-row-effect
  "Normalizes one closed :row-effect plan directive. Table ids are canonical
  keywords; parameter indices are positive, declared in :params, non-NULL for
  primary keys, and unique within each list; column names are nonempty strings
  unique within their list. :insert-row requires every key parameter to be
  named by a :row column so the stored complete row carries its primary key;
  :update-row requires one :key-columns name per :key-params entry and forbids
  replacing any of those columns through :set; :update-row's :where is an
  optional closed vector of [column param-index] equality guard pairs,
  separate from :key-params/:key-columns, and may name a column also named by
  :set; :scan-rows requires every :order-key column to be projected and its
  declared :columns, when present, to equal :project. No :row-effect plan may
  declare :rows, even an empty one."
  [plan-index row-effect params columns columns-present? rows-present?]
  (when-not (or (nil? row-effect) (map? row-effect))
    (fail! :jolt.sim.sqlite/invalid-plan
           "plan :row-effect must be a map with :op, :table, and its per-op fields"
           {:plan-index plan-index :row-effect row-effect}))
  (when (some? row-effect)
    (let [op (:op row-effect)]
      (when-not (contains? row-effect-ops op)
        (fail! :jolt.sim.sqlite/invalid-plan
               "plan :row-effect :op must be :insert-row, :update-row, or :scan-rows"
               {:plan-index plan-index
                :row-effect row-effect
                :supported-ops row-effect-ops}))
      (let [allowed (get row-effect-keys op)
            unknown (seq (sort-by pr-str
                                  (remove allowed (keys row-effect))))]
        (when unknown
          (fail! :jolt.sim.sqlite/invalid-plan
                 "plan :row-effect is closed for its :op"
                 {:plan-index plan-index
                  :row-effect row-effect
                  :unknown-keys (vec unknown)})))
      (when-not (keyword? (:table row-effect))
        (fail! :jolt.sim.sqlite/invalid-plan
               "plan :row-effect :table must be a canonical keyword"
               {:plan-index plan-index :row-effect row-effect}))
      (when rows-present?
        (fail! :jolt.sim.sqlite/invalid-plan
               "a :row-effect plan must not predeclare :rows"
               {:plan-index plan-index :row-effect row-effect}))
      (case op
        :insert-row
        (let [key-params (normalize-row-key-params
                          plan-index (:key-params row-effect) params)
              row (normalize-row-column-bindings
                   plan-index :row (:row row-effect) params)
              row-params (set (map second row))
              param->column (into {} (map (fn [[column index]]
                                            [index column])
                                          row))]
          (doseq [index key-params]
            (when-not (contains? row-params index)
              (fail! :jolt.sim.sqlite/invalid-plan
                     "plan :row-effect :row must name a column for every :key-params index"
                     {:plan-index plan-index
                      :row-effect row-effect
                      :missing-key-param index})))
          {:op op
           :table (:table row-effect)
           :key-params key-params
           :key-columns (mapv param->column key-params)
           :row row})

        :update-row
        (let [key-params (normalize-row-key-params
                          plan-index (:key-params row-effect) params)
              key-columns (normalize-row-column-names
                           plan-index :key-columns
                           (:key-columns row-effect))
              set-pairs (normalize-row-column-bindings
                         plan-index :set (:set row-effect) params)
              where-pairs (when (contains? row-effect :where)
                            (normalize-row-column-bindings
                             plan-index :where (:where row-effect) params))
              key-column-set (set key-columns)]
          (when-not (seq key-columns)
            (fail! :jolt.sim.sqlite/invalid-plan
                   "plan :row-effect :key-columns must be nonempty"
                   {:plan-index plan-index :row-effect row-effect}))
          (when-not (= (count key-params) (count key-columns))
            (fail! :jolt.sim.sqlite/invalid-plan
                   "plan :row-effect :key-columns must align one-to-one with :key-params"
                   {:plan-index plan-index
                    :row-effect row-effect
                    :key-param-count (count key-params)
                    :key-column-count (count key-columns)}))
          (doseq [[column _] set-pairs]
            (when (contains? key-column-set column)
              (fail! :jolt.sim.sqlite/invalid-plan
                     "plan :row-effect :set must not replace a primary-key column"
                     {:plan-index plan-index
                      :row-effect row-effect
                      :column column})))
          (cond-> {:op op
                   :table (:table row-effect)
                   :key-params key-params
                   :key-columns key-columns
                   :set set-pairs}
            (some? where-pairs) (assoc :where where-pairs)))

        :scan-rows
        (let [project (normalize-row-column-names
                       plan-index :project (:project row-effect))
              order-key (normalize-row-column-names
                         plan-index :order-key (:order-key row-effect))
              project-set (set project)]
          (when-not (seq project)
            (fail! :jolt.sim.sqlite/invalid-plan
                   "plan :row-effect :project must be nonempty"
                   {:plan-index plan-index :row-effect row-effect}))
          (doseq [column order-key]
            (when-not (contains? project-set column)
              (fail! :jolt.sim.sqlite/invalid-plan
                     "plan :row-effect :order-key columns must be included in :project"
                     {:plan-index plan-index
                      :row-effect row-effect
                      :order-column column})))
          (when (and columns-present? (not= (vec columns) project))
            (fail! :jolt.sim.sqlite/invalid-plan
                   "a :scan-rows plan's declared :columns must equal its :project"
                   {:plan-index plan-index
                    :row-effect row-effect
                    :columns (vec columns)}))
          {:op op
           :table (:table row-effect)
           :project project
           :order-key order-key})))))

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
        (when (< 1 (count (filter some? [(:tx-effect plan)
                                         (:store-effect plan)
                                         (:row-effect plan)])))
          (fail! :jolt.sim.sqlite/invalid-plan
                 "a plan may declare at most one of :tx-effect, :store-effect, or :row-effect"
                 {:index i}))
       (let [columns (or (:columns plan) [])
             error (:error plan)
             params (normalize-params i (:params plan))]
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
         (let [row-effect (normalize-row-effect
                           i (:row-effect plan) params columns
                           (contains? plan :columns)
                           (contains? plan :rows))
               columns (if (and row-effect (= :scan-rows (:op row-effect)))
                         (:project row-effect)
                         columns)]
           (assoc plan
                  :params params
                  :columns columns
                  :rows (normalize-rows i columns (:rows plan))
                  :tx-effect (normalize-tx-effect i (:tx-effect plan))
                  :store-effect (normalize-store-effect
                                 i (:store-effect plan) params columns
                                 (:rows plan))
                  :row-effect row-effect))))
     (range (count planv))
     planv)))

(defn- column-names [plan]
  (if (seq (:columns plan))
    (vec (:columns plan))
    (mapv #(str "c" %) (range (count (first (:rows plan)))))))

;; ---- world --------------------------------------------------------------

(defn- normalize-options
  "Validates the closed world options map. The only supported key is
  :persistent-filenames, an exact set of nonempty strings that must not name
  the SQLite \":memory:\" sentinel; it defaults to the empty set. A non-map
  options value, any extra key, a non-set :persistent-filenames, or a
  malformed entry fails closed."
  [options]
  (when-not (map? options)
    (fail! :jolt.sim.sqlite/invalid-options
           "world options must be a closed map"
           {:options options}))
  (let [unknown (seq (sort-by pr-str
                              (remove #{:persistent-filenames}
                                      (keys options))))]
    (when unknown
      (fail! :jolt.sim.sqlite/invalid-options
             "world options are closed: only :persistent-filenames is allowed"
             {:unknown-keys (vec unknown)}))
    (if-not (contains? options :persistent-filenames)
      #{}
      (let [filenames (:persistent-filenames options)]
        (when-not (set? filenames)
          (fail! :jolt.sim.sqlite/invalid-options
                 ":persistent-filenames must be an exact set of nonempty strings"
                 {:persistent-filenames filenames}))
        (doseq [filename filenames]
          (when-not (and (string? filename) (pos? (count filename)))
            (fail! :jolt.sim.sqlite/invalid-options
                   ":persistent-filenames entries must be nonempty strings"
                   {:filename filename}))
          (when (= ":memory:" filename)
            (fail! :jolt.sim.sqlite/invalid-options
                   ":persistent-filenames must not name the SQLite \":memory:\" sentinel"
                   {:filename filename})))
        filenames))))

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
      :tx-effect {:op :begin :when :on-success}   ;; optional physical
      ;; transaction-boundary directive
      :store-effect {:op :put :key-param 1 :value-param 2} ;; optional
      ;; physical key/value directive; :op :get takes only :key-param
      :row-effect {:op :insert-row :table :outbox/entities
                   :key-params [1]
                   :row [[\"entity_id\" 1] [\"version\" 2] [\"payload\" 3]]}}
      ;; optional physical table-row directive; :op :update-row takes
      ;; :key-params, aligned :key-columns [\"entity_id\"], and
      ;; :set [[\"version\" 2] [\"payload\" 3]], plus an optional :where
      ;; [[\"status\" 4]] closed vector of equality guard pairs, and
      ;; :op :scan-rows takes :project [\"entity_id\" \"version\" \"payload\"]
      ;; and :order-key [\"entity_id\"]. At most one of :tx-effect,
      ;; :store-effect, and :row-effect may appear on one plan.

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
  connection record to :closed-db-evidence after the live record is removed.

  A plan's :store-effect is the only source of physical key/value writes and
  reads; the model never infers store behavior from SQL text. :op :put
  requires positive :key-param/:value-param indices into the plan's declared
  :params and writes the bound value cell to the connection's active staging
  when a transaction is open, otherwise directly to committed storage, and
  does not permit predeclared result rows; :op :get requires a positive
  :key-param, exactly one declared :column, and no
  predeclared :rows, and derives at most one result row from the current
  visible state at execution time -- its own staged value first, then
   committed, otherwise no row. Each executed put or get is recorded in the
   connection's address-free :store-evidence vector. Closing a connection with
   active staging discards it; the discarded map is retained in
   :closed-db-evidence alongside the surviving committed storage.

   A plan's :row-effect is the only source of physical table-row writes and
   row scans; the model never infers row behavior from SQL text. Every
   :row-effect names a canonical keyword :table. :op :insert-row requires
   :key-params and a :row of [column param-index] pairs naming every key
   parameter, writes the full typed row built from the actual exactly matched
   bindings under a tagged [:jolt.sim.sqlite/row table key-cells] identity
   into active staging (or committed storage in autocommit), and reports
   changes 1 -- or, when the key is already visible, reports SQLite
   constraint code 19 with changes 0 and no write. :op :update-row requires
   aligned :key-params/:key-columns and a :set of [column param-index] pairs,
   replaces the named non-key columns of the one visible existing row (changes
   1) without ever manufacturing or replacing key columns, and reports DONE
   with changes 0 and no write when the key is absent. An optional :where is a
   closed vector of [column param-index] equality guard pairs, separate from
   the physical key, evaluated after the key is found and key-schema
   validated; a :where column may also appear in :set. Guard evaluation is
   exact typed cell equality on the actual exactly matched bindings, with SQL
   NULL semantics: a null bound guard, or a stored null cell, never matches.
   A guard mismatch performs no write and reports DONE with changes 0,
   recording the row as present but not applied; a guard match applies :set
   and reports changes 1 like an unguarded update. A :where column absent
   from the visible row is schema corruption and fails closed like a
   :key-columns mismatch, mutating neither storage nor evidence.
   :op :scan-rows requires :project and :order-key,
   snapshots its table's visible rows at its first sqlite3_step, projects
    typed cells in :project order (its exposed :columns are exactly :project),
    sorts them by :order-key under a stable total ordering over the supported
    cell types, and serves ordinary ROW/DONE from that immutable snapshot. A
    plan-level :error records the attempted row effect without mutating or
    exposing rows. Each executed row directive is recorded in the connection's
    address-free :row-evidence vector with its sequence, plan-index, op,
    table, reported outcome, key (when applicable), write/snapshot location,
    update source location, whether a row was present/applied, and changes.

  The optional third argument is a closed options map whose only key is
  :persistent-filenames, an exact set of nonempty strings (never \":memory:\")
  defaulting to empty. sqlite3_open decodes the real filename pointer and
  compares it exactly: a selected filename's connection carries :image-key and
  starts from the world's immutable :images entry for that filename (created
  empty at first open), its :committed storage publishes back to :images
  atomically inside every terminal DB transition's world CAS, and a second
  simultaneous open of the same selected filename fails with
  :jolt.sim.sqlite/connection-already-open before the output pointer is
  written. Every unselected filename keeps independent ephemeral
  per-connection storage, byte-for-byte as without the option."
    ([plans]
    (world (memory/world) plans))
  ([memory-world plans]
   (world memory-world plans {}))
  ([memory-world plans options]
   (when-not (and (map? memory-world) (map? (:config memory-world)))
     (fail! :jolt.sim.sqlite/invalid-world
            "first argument must be a jolt.sim.ffi-memory world"
            {:provided memory-world}))
   {:memory memory-world
    :memory-handlers (memory/handlers memory-world)
    :persistent-filenames (normalize-options options)
    :state (atom {:plans (validate-plans plans)
                  :plan-index 0
                  :dbs {}
                  :next-connection-id 0
                  :stmts {}
                  :closed-dbs #{}
                  :closed-db-evidence []
                  :finalized-stmts #{}
                  :images {}
                  :open-images #{}})
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

(defn- register-db!
  "Registers one freshly opened connection under handle. When image-key is
  non-nil the filename was selected for the file-image substrate: the claim
  rejects a second simultaneous live connection for the same filename, the
  first open seeds an empty :images entry, and the fresh connection starts
  from the filename's last committed image with fresh connection/tx/error/
  evidence fields. Returns the connection id, or ::image-busy when the claim
  fails."
  [w handle image-key]
  (loop []
    (let [st (:state w)
          s @st]
      (if (and (some? image-key) (contains? (:open-images s) image-key))
        ::image-busy
        (let [connection-id (:next-connection-id s)
              db (cond-> {:connection-id connection-id
                          :errcode 0 :errmsg "not an error" :changes 0 :rowid 0
                          :autocommit? true :tx nil :tx-evidence []
                          :autocommit-evidence []
                          :committed (if (some? image-key)
                                       (get-in s [:images image-key] {})
                                       {})
                          :staging nil :store-evidence []
                          :row-evidence []}
                   (some? image-key) (assoc :image-key image-key))
              next-state (cond-> (-> s
                                     (assoc-in [:dbs handle] db)
                                     (update :next-connection-id inc))
                           (some? image-key)
                           (-> (update :open-images conj image-key)
                               (update :images
                                       (fn [images]
                                         (if (contains? images image-key)
                                           images
                                           (assoc images image-key {}))))))]
          (if (compare-and-set! st s next-state)
            connection-id
            (recur)))))))

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

(defn- publish-image
  "Mirrors a terminal connection record's :committed storage into the world's
  immutable :images when the connection owns a selected persistent filename,
  inside the same state transition as the terminal DB change. Ephemeral
  connections leave next-state untouched, and only :committed is ever
  published, so staging, rollback, failed effects, and close with an active
  transaction cannot leak uncommitted storage into the durable image."
  [next-state terminal-db]
  (let [image-key (:image-key terminal-db)]
    (if (nil? image-key)
      next-state
      (assoc-in next-state [:images image-key] (:committed terminal-db)))))

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
                                :begin-plan-index (:plan-index stmt)}))

                  (and applied? (= :begin op))
                  (assoc :staging {})

                  (and applied? (= :commit op))
                  (assoc :committed (merge (:committed db) (:staging db))
                         :staging nil)

                  (and applied? (= :rollback op))
                  (assoc :staging nil))]
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
                  borrowed (:borrowed stmt)
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
                                 (assoc-in [:stmts stmt-addr] terminal-stmt)
                                 (publish-image terminal-db))]
              (if (compare-and-set! st s next-state)
                (do
                  (free-list! w borrowed)
                  (if impossible?
                    (fail!
                     :jolt.sim.sqlite/impossible-tx-transition
                     "declared :tx-effect is impossible for the connection's physical autocommit state"
                     error-data)
                    (if (= :error reported)
                      (:code err)
                      (:done result-codes))))
                (recur)))))))))

;; ---- physical store visibility boundary ---------------------------------

(defn- canonical-cell-identity
  "Returns the typed value identity of a cell. Result-only presentation
  metadata such as an empty BLOB's :null-pointer? mode is deliberately absent."
  [{:keys [type value]}]
  (case type
    :float {:type :float :value (double value)}
    :blob  {:type :blob :value (normalize-bytes value)}
    :null  {:type :null}
    {:type type :value value}))

(defn- canonical-bound-key
  "Returns value identity from the actual exactly-matched binding. FFI doubles
  are canonicalized independently of whether their plan literal was written as
  1 or 1.0."
  [stmt index]
  (canonical-cell-identity (get (:bindings stmt) index)))

(defn- declared-value-cell
  "Returns the normalized declared result cell for an exactly matched value
  binding, retaining NULL and empty-BLOB pointer-mode semantics."
  [stmt index]
  (get-in stmt [:plan :params index]))

(defn- bound-value-cell
  "Returns a stored/result cell from the actual exactly-matched binding. The
  only plan presentation metadata retained is the requested null-pointer mode
  for an empty BLOB; it does not participate in row identity."
  [stmt index]
  (let [bound (canonical-bound-key stmt index)
        declared (get-in stmt [:plan :params index])]
    (cond-> bound
      (and (= :blob (:type bound))
           (empty? (:value bound))
           (true? (:null-pointer? declared)))
      (assoc :null-pointer? true))))

(defn- store-put-decision
  "Pure decision for one :store-effect :put terminal step: derives the bound
  key/value cells, decides the write's target (the connection's active
  staging when a transaction is open, otherwise committed storage) only when
  reported is :done, and appends one :store-evidence event either way. Never
  mutates storage for a reported :error."
  [db stmt reported]
  (let [store-effect (:store-effect (:plan stmt))
        key-cell (canonical-bound-key stmt (:key-param store-effect))
        value-cell (declared-value-cell stmt (:value-param store-effect))
        success? (= :done reported)
        location (when success?
                   (if (some? (:staging db)) :staging :committed))
        sequence (count (:store-evidence db))
        event {:sequence sequence
               :plan-index (:plan-index stmt)
               :op :put
               :reported reported
               :key key-cell
               :location location
               :present? (boolean success?)}
        next-db (cond-> (update db :store-evidence conj event)
                  success?
                  (update location assoc key-cell value-cell))]
    {:db next-db}))

(defn- complete-store-put-terminal!
  "Atomically owns one :store-effect :put statement's terminal step: derives
  and applies its storage decision, updates the physical connection, and
  publishes the reported SQLite result in one CAS, exactly like
  complete-tx-terminal!. A competing terminal step observes misuse; a close
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
                  err (:error plan)
                  borrowed (:borrowed stmt)
                  decision (store-put-decision live-db stmt reported)
                  next-db (:db decision)
                  terminal-db
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
                           :rowid (or (:last-row-id plan) 0)))
                  terminal-stmt
                  (assoc stmt
                         :store-effect-evaluated? true
                         :done? (= :done reported)
                         :errored? (= :error reported)
                         :borrowed []
                         :blob-cache {})
                  next-state (-> s
                                 (assoc-in [:dbs db-addr] terminal-db)
                                 (assoc-in [:stmts stmt-addr] terminal-stmt)
                                 (publish-image terminal-db))]
              (if (compare-and-set! st s next-state)
                (do
                  (free-list! w borrowed)
                  (if (= :error reported) (:code err) (:done result-codes)))
                (recur)))))))))

(defn- store-get-snapshot-decision
  "Pure first-step decision for one :store-effect :get. A successful get
  snapshots its staged-then-committed visible row; an errored get records an
  attempted read without consulting or exposing storage."
  [db stmt reported]
  (let [store-effect (:store-effect (:plan stmt))
        key-cell (canonical-bound-key stmt (:key-param store-effect))
        staging (:staging db)
        [value location]
        (if (= :done reported)
          (cond
            (and (some? staging) (contains? staging key-cell))
            [(get staging key-cell) :staging]

            (contains? (:committed db) key-cell)
            [(get (:committed db) key-cell) :committed]

            :else [nil nil])
          [nil nil])
        present? (some? location)
        sequence (count (:store-evidence db))
        event {:sequence sequence
               :plan-index (:plan-index stmt)
               :op :get
               :reported reported
               :key key-cell
               :location location
               :present? present?}]
    {:db (update db :store-evidence conj event)
     :stmt (assoc stmt
                  :store-effect-evaluated? true
                  :store-rows (if present? [[value]] []))}))

(defn- complete-store-get-step!
  "Linearizes one :store-effect :get step with connection/statement lifetime.
  Its first call atomically owns the visible-state snapshot, evidence event,
  and first ROW/DONE/error publication. A caller that started from the same
  unsnapshotted statement but loses that CAS observes misuse; a later ordinary
  call advances the already-snapshotted row to DONE. Close/finalize winners are
  reported without recreating removed state."
  [w stmt-addr first-call?]
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

        (and first-call? (:store-effect-evaluated? stmt))
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
                  err (:error plan)
                  snapshot? (not (:store-effect-evaluated? stmt))
                  decision (when snapshot?
                             (store-get-snapshot-decision
                              live-db stmt (if err :error :done)))
                  db (if snapshot? (:db decision) live-db)
                  stmt (if snapshot? (:stmt decision) stmt)
                  rows (vec (:store-rows stmt))
                  next-idx (inc (:row-index stmt))
                  borrowed (:borrowed stmt)
                  [next-db next-stmt result]
                  (cond
                    err
                    [(assoc db
                            :errcode (:code err)
                            :errmsg (or (:msg err) "database error")
                            :changes 0
                            :rowid 0)
                     (assoc stmt
                            :errored? true
                            :borrowed []
                            :blob-cache {})
                     (:code err)]

                    (< next-idx (count rows))
                    [(assoc db
                            :errcode (:row result-codes)
                            :errmsg "not an error")
                     (assoc stmt
                            :row-index next-idx
                            :borrowed []
                            :blob-cache {})
                     (:row result-codes)]

                    :else
                    [(assoc db
                            :errcode 0
                            :errmsg "not an error"
                            :changes (or (:changes plan) 0)
                            :rowid (or (:last-row-id plan) 0))
                     (assoc stmt
                            :done? true
                            :borrowed []
                            :blob-cache {})
                     (:done result-codes)])
                  next-state (-> s
                                 (assoc-in [:dbs db-addr] next-db)
                                 (assoc-in [:stmts stmt-addr] next-stmt))]
              (if (compare-and-set! st s next-state)
                (do
                  (free-list! w borrowed)
                  result)
                (recur)))))))))

;; ---- physical table-row boundary ----------------------------------------

(def ^:private row-tag :jolt.sim.sqlite/row)

(defn- row-key
  "Tagged identity for one stored table row: [tag table-id canonical
  primary-key-cells]. The vector tag can never collide with a legacy
  :store-effect key, which is always a cell map."
  [table key-cells]
  [row-tag table key-cells])

(defn- table-row-key? [table k]
  (and (vector? k)
       (= 3 (count k))
       (= row-tag (nth k 0))
       (= table (nth k 1))))

(defn- visible-row
  "Returns [row location] for one tagged row key in the connection's current
  visible view -- its own staged row first, then committed, otherwise
  [nil nil] -- exactly like a store get's staged-then-committed lookup."
  [db key]
  (let [staging (:staging db)]
    (cond
      (and (some? staging) (contains? staging key))
      [(get staging key) :staging]

      (contains? (:committed db) key)
      [(get (:committed db) key) :committed]

      :else [nil nil])))

(defn- visible-table-rows
  "Returns tagged row identity to {:row row :location source} for exactly one
  table in the connection's current visible view. Staged rows shadow committed
  rows under the same identity without discarding source provenance."
  [db table]
  (let [pick (fn [storage location]
               (into {}
                     (keep (fn [[k v]]
                             (when (table-row-key? table k)
                               [k {:row v :location location}])))
                     (or storage {})))]
    (merge (pick (:committed db) :committed)
           (pick (:staging db) :staging))))

;; Deterministic model ordering over the supported cell types:
;; NULL < INTEGER < FLOAT < TEXT < BLOB. INTEGER and FLOAT are intentionally
;; distinct ranks: coercing a 64-bit integer to double loses identity above
;; 2^53 and can make the comparator non-transitive. Within each rank values use
;; their native exact/double ordering. Rows whose order cells compare equal
;; fall back to their canonical primary-key cells; row-key uniqueness means a
;; complete tie denotes the same row, never map iteration order.

(defn- compare-byte-vectors [a b]
  (let [limit (min (count a) (count b))]
    (loop [i 0]
      (cond
        (== i limit)            (compare (count a) (count b))
        (< (nth a i) (nth b i)) -1
        (> (nth a i) (nth b i)) 1
        :else                   (recur (inc i))))))

(defn- compare-floats [a b]
  ;; Jolt's generic numeric compare treats NaN as equal to every number because
  ;; neither `<` nor `>` holds. Put all NaNs after ordinary/infinite floats so
  ;; the row comparator remains total even for adversarial plan cells.
  (let [a-nan? (not (= a a))
        b-nan? (not (= b b))]
    (cond
      (and a-nan? b-nan?) 0
      a-nan? 1
      b-nan? -1
      :else (compare a b))))

(defn- cell-sort-rank [cell]
  (case (:type cell)
    :null 0
    :integer 1
    :float 2
    :text 3
    :blob 4))

(defn- compare-cells [a b]
  (let [ra (cell-sort-rank a)
        rb (cell-sort-rank b)]
    (cond
      (< ra rb) -1
      (> ra rb) 1
      :else
      (case (:type a)
        :null 0
        :integer (compare (:value a) (:value b))
        :float (compare-floats (:value a) (:value b))
        :text (compare (:value a) (:value b))
        :blob (compare-byte-vectors (normalize-bytes (:value a))
                                    (normalize-bytes (:value b)))))))

(defn- compare-cell-vectors [as bs]
  (let [limit (min (count as) (count bs))]
    (loop [i 0]
      (if (== i limit)
        (compare (count as) (count bs))
        (let [c (compare-cells (nth as i) (nth bs i))]
          (if (zero? c) (recur (inc i)) c))))))

(defn- compare-scan-entries [a b]
  (let [c (compare-cell-vectors (:order-cells a) (:order-cells b))]
    (if (not (zero? c))
      c
      (compare-cell-vectors (:key-cells a) (:key-cells b)))))

(defn- scan-project-cell [table row column]
  (if (contains? row column)
    (get row column)
    (fail! :jolt.sim.sqlite/scan-projection
           "a stored row does not contain a column the scan projects"
           {:table table :column column :row row})))

(defn- require-visible-row-key!
  "Fails closed when an update plan's declared key columns do not describe the
  visible row found under its canonical physical key. This prevents a mistaken
  per-statement schema from mutating a row while retaining a stale identity."
  [table row key-columns key-cells]
  (let [missing-columns (vec (remove #(contains? row %) key-columns))]
    (when (seq missing-columns)
      (fail! :jolt.sim.sqlite/row-key-schema-mismatch
             "update :key-columns are absent from the visible row"
             {:table table
              :key-columns key-columns
              :missing-columns missing-columns
              :expected-key key-cells})))
  (let [stored-key (mapv #(canonical-cell-identity (get row %)) key-columns)]
    (when-not (= key-cells stored-key)
      (fail! :jolt.sim.sqlite/row-key-schema-mismatch
             "update :key-columns do not match the visible row identity"
             {:table table
              :key-columns key-columns
              :expected-key key-cells
              :stored-key stored-key}))))

(defn- require-where-columns-present!
  "Fails closed when an update plan's declared :where guard columns are not
  present in the visible row, addressed by physical key and already key-schema
  validated. This is column-presence schema corruption, distinct from an
  ordinary guard-value mismatch, which never mutates storage or evidence."
  [table row where-pairs]
  (let [where-columns (mapv first where-pairs)
        missing-columns (vec (remove #(contains? row %) where-columns))]
    (when (seq missing-columns)
      (fail! :jolt.sim.sqlite/row-key-schema-mismatch
             "update :where columns are absent from the visible row"
             {:table table
              :where-columns where-columns
              :missing-columns missing-columns}))))

(defn- where-guard-matches?
  "Exact typed cell equality for a closed :where vector of [column
  param-index] guards against the visible row, using the same actual
  exactly-matched binding identity as row keys. This is not general SQL
  three-valued logic: it only implements the one rule this seam needs -- a
  null bound guard, or a stored null cell, never matches, even against
  another null. An empty/absent guard vacuously matches, so plans without
  :where retain their unguarded behavior."
  [stmt row where-pairs]
  (every?
   (fn [[column index]]
     (let [param-cell (canonical-bound-key stmt index)
           stored-cell (canonical-cell-identity (get row column))]
       (and (not= :null (:type param-cell))
            (not= :null (:type stored-cell))
            (= param-cell stored-cell))))
   where-pairs))

(defn- row-mutation-decision
  "Pure decision for one :row-effect :insert-row/:update-row terminal step.
  The primary key and every stored cell come from the actual exactly matched
  bindings. An insert whose key is already visible in the current view
  (staged rows included) is a soft SQLite constraint outcome: evidence, no
  write. An update replaces one visible existing row's declared columns --
  writing to active staging when a transaction is open -- and an absent key
  is ordinary DONE with changes 0. A reported :error records the attempted
  effect without consulting or mutating storage."
  [db stmt reported]
  (let [row-effect (:row-effect (:plan stmt))
        op (:op row-effect)
        table (:table row-effect)
        key-cells (mapv #(canonical-bound-key stmt %)
                        (:key-params row-effect))
        key (row-key table key-cells)
        base-event {:sequence (count (:row-evidence db))
                    :plan-index (:plan-index stmt)
                    :op op
                    :table table
                    :key key-cells
                    :source-location nil}]
    (if (not= :done reported)
      {:db (update db :row-evidence conj
                   (assoc base-event
                          :reported :error
                          :location nil
                          :present? false
                          :applied? false
                          :changes 0))
       :outcome :error}
      (case op
        :insert-row
        (let [[_ found-location] (visible-row db key)]
          (if (some? found-location)
            {:db (update db :row-evidence conj
                         (assoc base-event
                                :reported :constraint
                                :location found-location
                                :source-location found-location
                                :present? true
                                :applied? false
                                :changes 0))
             :outcome :constraint}
            (let [location (if (some? (:staging db)) :staging :committed)
                  row (into {}
                            (map (fn [[column index]]
                                   [column
                                    (bound-value-cell stmt index)]))
                            (:row row-effect))]
              {:db (-> db
                       (update :row-evidence conj
                               (assoc base-event
                                      :reported :done
                                      :location location
                                      :present? false
                                      :applied? true
                                      :changes 1))
                       (update location assoc key row))
               :outcome :written})))
        :update-row
        (let [[existing found-location] (visible-row db key)]
          (if (nil? found-location)
            {:db (update db :row-evidence conj
                         (assoc base-event
                                :reported :done
                                :location nil
                                :present? false
                                :applied? false
                                :changes 0))
             :outcome :unchanged}
            (let [where-pairs (:where row-effect)
                  _ (require-visible-row-key!
                     table existing (:key-columns row-effect) key-cells)
                  _ (when (seq where-pairs)
                      (require-where-columns-present!
                       table existing where-pairs))]
              (if-not (where-guard-matches? stmt existing where-pairs)
                {:db (update db :row-evidence conj
                             (assoc base-event
                                    :reported :done
                                    ;; The row was found but no write target
                                    ;; was selected. Keep provenance in
                                    ;; :source-location and reserve :location
                                    ;; for an applied mutation.
                                    :location nil
                                    :source-location found-location
                                    :present? true
                                    :applied? false
                                    :changes 0))
                 :outcome :guard-mismatch}
                (let [location (if (some? (:staging db)) :staging :committed)
                      replacement
                      (reduce
                       (fn [row [column index]]
                         (assoc row column
                                (bound-value-cell stmt index)))
                       existing
                       (:set row-effect))]
                  {:db (-> db
                           (update :row-evidence conj
                                   (assoc base-event
                                          :reported :done
                                          :location location
                                          :source-location found-location
                                          :present? true
                                          :applied? true
                                          :changes 1))
                           (update location assoc key replacement))
                   :outcome :written})))))))))

(defn- complete-row-mutation-terminal!
  "Atomically owns one :row-effect :insert-row/:update-row statement's
  terminal step: derives and applies its row decision, updates the physical
  connection, and publishes the reported SQLite result in one CAS, exactly
  like complete-store-put-terminal!. A competing terminal step observes
  misuse; a close that wins the same CAS makes the step fail as
  use-after-close. The dynamic duplicate-insert constraint outcome sets
  errcode 19 and changes 0 like any soft SQLite error; it never throws."
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
                  err (:error plan)
                  borrowed (:borrowed stmt)
                  decision (row-mutation-decision live-db stmt reported)
                  next-db (:db decision)
                  outcome (:outcome decision)
                  terminal-db
                  (case outcome
                    :error
                    (assoc next-db
                           :errcode (:code err)
                           :errmsg (or (:msg err) "database error")
                           :changes 0
                           :rowid 0)
                    :constraint
                    (assoc next-db
                           :errcode (:constraint result-codes)
                           :errmsg "UNIQUE constraint failed"
                           :changes 0
                           :rowid 0)
                    :written
                    (assoc next-db
                           :errcode 0
                           :errmsg "not an error"
                           :changes 1
                           :rowid (or (:last-row-id plan) 0))
                    (:unchanged :guard-mismatch)
                    (assoc next-db
                           :errcode 0
                           :errmsg "not an error"
                           :changes 0
                           :rowid (or (:last-row-id plan) 0)))
                  terminal-stmt
                  (assoc stmt
                         :row-effect-evaluated? true
                         :done? (or (= :written outcome)
                                    (= :unchanged outcome)
                                    (= :guard-mismatch outcome))
                         :errored? (or (= :error outcome)
                                       (= :constraint outcome))
                         :borrowed []
                         :blob-cache {})
                  next-state (-> s
                                 (assoc-in [:dbs db-addr] terminal-db)
                                 (assoc-in [:stmts stmt-addr] terminal-stmt)
                                 (publish-image terminal-db))]
              (if (compare-and-set! st s next-state)
                (do
                  (free-list! w borrowed)
                  (case outcome
                    :error (:code err)
                    :constraint (:constraint result-codes)
                    (:done result-codes)))
                (recur)))))))))

(defn- scan-snapshot-decision
  "Pure first-step decision for one :row-effect :scan-rows. A successful scan
  snapshots the current visible rows of exactly its own table, projects each
  row's typed cells in :project order, and sorts them deterministically by
  the declared :order-key; the snapshot is then immune to any later mutation.
  An errored scan records an attempted effect without consulting or exposing
  storage."
  [db stmt reported]
  (let [row-effect (:row-effect (:plan stmt))
        table (:table row-effect)
        base-event {:sequence (count (:row-evidence db))
                    :plan-index (:plan-index stmt)
                    :op :scan-rows
                    :table table
                    :key nil}]
    (if (not= :done reported)
      {:db (update db :row-evidence conj
                   (assoc base-event
                          :reported :error
                          :location nil
                          :present? false
                          :applied? false
                          :changes 0))
       :stmt (assoc stmt
                    :row-effect-evaluated? true
                    :scan-rows [])}
      (let [project (:project row-effect)
            order-key (:order-key row-effect)
            visible (visible-table-rows db table)
            entries
            (mapv
             (fn [[key {:keys [row location]}]]
               {:key-cells (nth key 2)
                :source-location location
                :order-cells (mapv #(scan-project-cell table row %)
                                   order-key)
                :cells (mapv #(scan-project-cell table row %) project)})
             visible)
            sorted (vec (sort compare-scan-entries entries))
            rows (mapv :cells sorted)
            locations (set (map :source-location sorted))
            snapshot-location (cond
                                (empty? locations) nil
                                (= 1 (count locations)) (first locations)
                                :else :mixed)]
        {:db (update db :row-evidence conj
                     (assoc base-event
                            :reported :done
                            :location snapshot-location
                            :present? (pos? (count rows))
                            :applied? false
                            :changes 0))
         :stmt (assoc stmt
                      :row-effect-evaluated? true
                      :scan-rows rows)}))))

(defn- complete-scan-step!
  "Linearizes one :row-effect :scan-rows step with connection/statement
  lifetime, exactly like complete-store-get-step!. Its first call atomically
  owns the visible-table snapshot, evidence event, and first ROW/DONE/error
  publication. A caller that started from the same unsnapshotted statement
  but loses that CAS observes misuse; a later ordinary call advances the
  already-snapshotted rows. Close/finalize winners are reported without
  recreating removed state."
  [w stmt-addr first-call?]
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

        (and first-call? (:row-effect-evaluated? stmt))
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
                  err (:error plan)
                  snapshot? (not (:row-effect-evaluated? stmt))
                  decision (when snapshot?
                             (scan-snapshot-decision
                              live-db stmt (if err :error :done)))
                  db (if snapshot? (:db decision) live-db)
                  stmt (if snapshot? (:stmt decision) stmt)
                  rows (vec (:scan-rows stmt))
                  next-idx (inc (:row-index stmt))
                  borrowed (:borrowed stmt)
                  [next-db next-stmt result]
                  (cond
                    err
                    [(assoc db
                            :errcode (:code err)
                            :errmsg (or (:msg err) "database error")
                            :changes 0
                            :rowid 0)
                     (assoc stmt
                            :errored? true
                            :borrowed []
                            :blob-cache {})
                     (:code err)]

                    (< next-idx (count rows))
                    [(assoc db
                            :errcode (:row result-codes)
                            :errmsg "not an error")
                     (assoc stmt
                            :row-index next-idx
                            :borrowed []
                            :blob-cache {})
                     (:row result-codes)]

                    :else
                    [(assoc db
                            :errcode 0
                            :errmsg "not an error"
                            :changes (or (:changes plan) 0)
                            :rowid (or (:last-row-id plan) 0))
                     (assoc stmt
                            :done? true
                            :borrowed []
                            :blob-cache {})
                     (:done result-codes)])
                  next-state (-> s
                                 (assoc-in [:dbs db-addr] next-db)
                                 (assoc-in [:stmts stmt-addr] next-stmt))]
              (if (compare-and-set! st s next-state)
                (do
                  (free-list! w borrowed)
                  result)
                (recur)))))))))

;; ---- result cell access -------------------------------------------------

(defn- current-row [stmt]
  (let [idx (:row-index stmt)
        rows (cond
               (contains? stmt :scan-rows) (:scan-rows stmt)
               (contains? stmt :store-rows) (:store-rows stmt)
               :else (:rows (:plan stmt)))]
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
  (let [size (max 1 (count bytes))]
    (loop [fresh nil]
      (let [st (:state w)
            s @st
            live-stmt (get-in s [:stmts stmt-addr])]
        (cond
          (nil? live-stmt)
          (do
            (when fresh (invoke-mem w :free [fresh]))
            (if (contains? (:finalized-stmts s) stmt-addr)
              (fail! :jolt.sim.sqlite/use-after-finalize
                     "statement handle was already finalized"
                     {:handle stmt-addr})
              (fail! :jolt.sim.sqlite/unknown-handle
                     "pointer is not a known statement handle"
                     {:handle stmt-addr})))

          (or (:done? live-stmt)
              (:errored? live-stmt)
              (neg? (:row-index live-stmt))
              (not= (:row-index stmt) (:row-index live-stmt)))
          (do
            (when fresh (invoke-mem w :free [fresh]))
            (fail! :jolt.sim.sqlite/no-current-row
                   "BLOB borrow lost its current statement row"
                   {:row-index (:row-index live-stmt)
                    :expected-row-index (:row-index stmt)
                    :done? (:done? live-stmt)
                    :errored? (:errored? live-stmt)}))

          (nil? (get-in s [:dbs (:db live-stmt)]))
          (do
            (when fresh (invoke-mem w :free [fresh]))
            (if (contains? (:closed-dbs s) (:db live-stmt))
              (fail! :jolt.sim.sqlite/use-after-close
                     "statement belongs to a closed connection"
                     {:connection-id (:connection-id live-stmt)
                      :plan-index (:plan-index live-stmt)})
              (fail! :jolt.sim.sqlite/unknown-handle
                     "statement refers to an unknown connection handle"
                     {:plan-index (:plan-index live-stmt)})))

          (get-in live-stmt [:blob-cache col])
          (let [cached (get-in live-stmt [:blob-cache col])]
            (when fresh (invoke-mem w :free [fresh]))
            cached)

          (nil? fresh)
          (let [addr (invoke-mem w :alloc [size])]
            (when (pos? (count bytes))
              (invoke-mem w :write-array [addr (uvec->byte-array bytes)]))
            (recur addr))

          :else
          (let [next-state
                (-> s
                    (update-in [:stmts stmt-addr :borrowed] conj fresh)
                    (assoc-in [:stmts stmt-addr :blob-cache col] fresh))]
            (if (compare-and-set! st s next-state)
              fresh
              (recur fresh))))))))

;; ---- per-function handlers ----------------------------------------------

(defn- h-open [w {:keys [arguments]}]
  (let [[filename-ptr db-out-ptr] (vec arguments)
        ;; Decode the real filename pointer (nil for a null pointer, matching
        ;; ptr->string). Only exact members of the world's
        ;; :persistent-filenames set opt into the file-image substrate; every
        ;; other name keeps independent ephemeral storage.
        filename (invoke-mem w :ptr->string [filename-ptr])
        image-key (when (contains? (:persistent-filenames w) filename)
                    filename)
        handle-size (invoke-mem w :sizeof [:pointer])
        handle (invoke-mem w :alloc [handle-size])]
    (if (nil? image-key)
      (do
        (invoke-mem w :write [db-out-ptr :pointer 0 handle])
        (register-db! w handle nil)
        (:ok result-codes))
      (let [claim (register-db! w handle image-key)]
        (if (= ::image-busy claim)
          (do
            ;; The claim failed, so the provisional handle never reaches the
            ;; caller's output cell: free it before failing closed, leaving
            ;; the first connection and the image untouched.
            (invoke-mem w :free [handle])
            (fail! :jolt.sim.sqlite/connection-already-open
                   "a connection for this persistent filename is already open"
                   {:filename filename}))
          (do
            (invoke-mem w :write [db-out-ptr :pointer 0 handle])
            (:ok result-codes)))))))

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
                         (assoc :discarded-transaction (:tx db)
                                :discarded-staging (:staging db)))
              ;; A selected connection's close releases the per-filename
              ;; claim so a later reopen can start from the published image;
              ;; the image itself is never touched here, so discarded staging
              ;; cannot reach :images.
              next-state (cond-> (-> s
                                     (update :dbs dissoc db-addr)
                                     (update :closed-dbs conj db-addr)
                                     (update :closed-db-evidence conj evidence))
                           (:image-key db)
                           (update :open-images disj (:image-key db)))]
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
                :borrowed [] :blob-cache {}
                 :tx-effect-evaluated? false
                 :store-effect-evaluated? false
                 :row-effect-evaluated? false})
        (:ok result-codes)))))

(defn- h-step [w {:keys [arguments]}]
  (let [[stmt-addr] (vec arguments)
        stmt (require-stmt! w stmt-addr)
        st (:state w)]
    (cond
      (:done? stmt)   (:misuse result-codes)
      (:errored? stmt) (:misuse result-codes)
      :else
      (let [plan (:plan stmt)
            store-effect (:store-effect plan)
            row-effect (:row-effect plan)
            get-effect? (and store-effect (= :get (:op store-effect)))
            scan-effect? (and row-effect (= :scan-rows (:op row-effect)))
            row-mutation? (and row-effect
                               (contains? #{:insert-row :update-row}
                                          (:op row-effect)))]
        (require-bindings-complete! stmt)
        (cond
          get-effect?
          (complete-store-get-step!
           w stmt-addr (not (:store-effect-evaluated? stmt)))

          ;; A scan owns its first step completely -- snapshot, evidence, and
          ;; any plan-level error publication -- ahead of every generic path,
          ;; exactly like a store get.
          scan-effect?
          (complete-scan-step!
           w stmt-addr (not (:row-effect-evaluated? stmt)))

          (:error plan)
          (let [err (:error plan)]
          (cond
            ;; The physical transition (when the directive applies) precedes
            ;; the reported error, so an :always plan models an uncertain
            ;; BEGIN: in-transaction physically, error over the wire. The
            ;; transaction decision and terminal statement result share one
            ;; CAS, so a competing step cannot report the terminal result too.
            (:tx-effect plan)
            (complete-tx-terminal! w stmt-addr :error)

            ;; A reported error on a :put never writes; the write decision and
            ;; terminal statement result share one CAS, mirroring :tx-effect.
            (and store-effect (= :put (:op store-effect)))
            (complete-store-put-terminal! w stmt-addr :error)

            ;; A reported error on a row mutation records the attempted row
            ;; effect and never reads or writes a row, in the same one CAS.
            row-mutation?
            (complete-row-mutation-terminal! w stmt-addr :error)

            :else
            (do
              (swap! st assoc-in [:stmts stmt-addr :errored?] true)
              (swap! st update-in [:dbs (:db stmt)]
                     assoc :errcode (:code err)
                     :errmsg (or (:msg err) "database error")
                     :changes 0 :rowid 0)
              (:code err))))

          :else
          (let [rows (vec (:rows plan))
                next-idx (inc (:row-index stmt))]
            (if (< next-idx (count rows))
              (do
                (swap! st update-in [:stmts stmt-addr]
                       assoc :borrowed [] :blob-cache {})
                (free-list! w (:borrowed stmt))
                (swap! st assoc-in [:stmts stmt-addr :row-index] next-idx)
                (swap! st update-in [:dbs (:db stmt)]
                       assoc :errcode (:row result-codes)
                       :errmsg "not an error")
                (:row result-codes))
              (cond
                (:tx-effect plan)
                (complete-tx-terminal! w stmt-addr :done)

                (and store-effect (= :put (:op store-effect)))
                (complete-store-put-terminal! w stmt-addr :done)

                row-mutation?
                (complete-row-mutation-terminal! w stmt-addr :done)

                :else
                (do
                  (swap! st update-in [:stmts stmt-addr]
                         assoc :borrowed [] :blob-cache {})
                  (free-list! w (:borrowed stmt))
                  (swap! st update-in [:stmts stmt-addr]
                         assoc :done? true :borrowed [] :blob-cache {})
                  (swap! st update-in [:dbs (:db stmt)]
                         assoc :errcode 0
                         :errmsg "not an error"
                         :changes (or (:changes plan) 0)
                         :rowid (or (:last-row-id plan) 0))
                  (:done result-codes))))))))))

(defn- claim-finalize! [w stmt-addr]
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

        :else
        (let [next-state (-> s
                             (update :stmts dissoc stmt-addr)
                             (update :finalized-stmts conj stmt-addr))]
          (if (compare-and-set! st s next-state)
            stmt
            (recur)))))))

(defn- h-finalize [w {:keys [arguments]}]
  (let [[stmt-addr] (vec arguments)
        ;; Remove the live statement atomically before freeing anything. A
        ;; racing step either linearizes before this claim, or observes
        ;; use-after-finalize; it can never publish against already-freed
        ;; statement/BLOB memory.
        stmt (claim-finalize! w stmt-addr)]
    (free-list! w (:borrowed stmt))
    (invoke-mem w :free [stmt-addr])
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
            pointer (borrow-bytes! w stmt-addr stmt col bytes)]
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
  "Returns the memory world's 15 native-operation handlers merged with exactly
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
  "Returns the memory world's 15 hybrid native-operation handlers merged with
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
  and every :autocommit-evidence observation, plus the physical store
  boundary: :committed and :staging (nil in autocommit, a map inside a
  transaction) and stable :store-evidence, plus the physical table-row
  boundary: complete rows live in the same :committed/:staging maps under
  tagged [:jolt.sim.sqlite/row table key-cells] identities and every row
  directive lands in stable :row-evidence. Statement records carry the plan,
  stable plan index, bindings, current row index, done/errored flags,
  :tx-effect-evaluated?, :store-effect-evaluated?,
  :row-effect-evaluated?, (for a :store-effect :get, after its first step)
  :store-rows, and (for a :row-effect :scan-rows, after its first step)
  :scan-rows. Removed connection records are
  appended in close order to
  :closed-db-evidence without their raw handle addresses, retaining their
  final :committed/:staging and, if closed with an active transaction,
  :discarded-transaction and :discarded-staging. The file-image substrate adds
  :images (selected filename to its last published committed storage) and
  :open-images (selected filenames with a live connection), and a selected
  connection's record carries :image-key."
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
