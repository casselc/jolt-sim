(ns jolt.example.outbox.sqlite
  "Durable SQLite adapter for the pure jolt.example.outbox application core.

  This slice proves durable atomic enqueue, exact replay, and guarded
  delivery marking over real system SQLite through public jdbc.core --
  nothing more. It deliberately has no concurrency or locking claim and no
  simulator dependency. The connection is sequential and single-owner. Schema
  and load operations may be reentrant, but apply-command! and
  mark-delivered! require transaction depth zero so a returned step has
  crossed the durable outer boundary.

  Storage layout (fixed private table names; never configurable):

    outbox_example_entities  entity_id text primary key,
                             version integer not null,
                             payload blob not null
    outbox_example_requests  request_id text primary key,
                             entity_id text not null,
                             payload blob not null,
                             version integer not null,
                             outbox_id integer not null unique
    outbox_example_outbox    outbox_id integer primary key,
                             request_id text not null unique,
                             entity_id text not null,
                             version integer not null,
                             payload blob not null,
                             status text not null

  Entity rows are the current projection (one row per entity, inserted or
  updated with separate fixed statements).
  Request rows are immutable records of each accepted command and its result.
  Outbox rows are immutable issue rows except for their status, which moves
  exactly once from pending to delivered through mark-delivered!'s guarded
  update. The canonical :next-outbox-id is derived at load time as one more
  than the greatest persisted outbox id; the core's own fail-closed history
  validation proves that derivation exact (ids are contiguous from 1) before
  any state is accepted, so tampered or inconsistent rows are rejected, never
  repaired.

  BLOB columns carry the canonical unsigned octet vectors. Input converts
  octets 0..255 deliberately into ordinary Jolt byte arrays; output converts
  the driver's signed bytes back with bit-and 0xff. A zero-length BLOB is
  distinct from SQL NULL end to end; NULL (or a non-BLOB storage class) in a
  required column is rejected fail closed."
  (:require [jdbc.core :as jdbc]
            [jolt.example.outbox :as outbox]))

;; ---- fixed private table names ---------------------------------------------

(def ^:private entities-table "outbox_example_entities")
(def ^:private requests-table "outbox_example_requests")
(def ^:private outbox-table "outbox_example_outbox")

;; ---- typed failures ----------------------------------------------------------

(defn- throw-adapter!
  [type reason detail]
  (throw
   (ex-info
    (str "jolt.example.outbox.sqlite: " (name type) " (" (name reason) ")")
    {:type type :reason reason :detail detail})))

(defn- check-sqlite-connection!
  "This adapter is SQLite-only. Any other jdbc connection vendor -- or any
  value that is not an opened sqlite connection map -- is rejected with a
  typed ex-info before any statement runs."
  [conn]
  (when-not (and (map? conn)
                 (= :sqlite (:vendor conn))
                 (contains? conn :handle)
                 (some? (:handle conn))
                 (contains? conn :tx-state)
                 (some? (:tx-state conn))
                 (fn? (:close conn)))
    (throw-adapter! :jolt.example.outbox.sqlite/unsupported-vendor
                    :unsupported-vendor
                    {:vendor (when (map? conn) (:vendor conn))}))
  conn)

(defn- check-durable-call-boundary!
  "Rejects apply-command! and mark-delivered! inside an ambient jdbc
  transaction. jdbc/atomic-apply is reentrant, but its nested boundary is only
  a savepoint: returning from it cannot truthfully mean durable commit because
  the caller may still roll the outer transaction back. The pinned jdbc
  connection exposes its transaction bookkeeping atom in :tx-state; malformed
  bookkeeping also fails closed."
  [conn]
  (let [snapshot (try
                   @(:tx-state conn)
                   (catch :default _ nil))
        depth (when (map? snapshot) (:depth snapshot))]
    (cond
      (not (and (integer? depth) (not (neg? depth))))
      (throw-adapter! :jolt.example.outbox.sqlite/invalid-transaction-state
                      :invalid-transaction-depth
                      {:depth depth})

      (pos? depth)
      (throw-adapter! :jolt.example.outbox.sqlite/ambient-transaction
                      :durable-boundary-required
                      {:depth depth})))
  conn)

(defn- throw-corrupt!
  "Persisted rows that violate a storage invariant are rejected fail closed;
  they are never repaired, defaulted, or skipped."
  [reason detail]
  (throw-adapter! :jolt.example.outbox.sqlite/corrupt-row reason detail))

(defn- check-affected!
  "Asserts an affected-row count where it carries a correctness invariant:
  every correctness-sensitive write in this adapter touches exactly one row."
  [expected actual context]
  (when-not (= expected actual)
    (throw-adapter! :jolt.example.outbox.sqlite/unexpected-write-count
                    :unexpected-write-count
                    (assoc context :expected expected :actual actual))))

;; ---- octet <-> BLOB conversion -----------------------------------------------

(defn- octets->bytes
  "Converts a canonical vector of unsigned octets 0..255 into an ordinary
  Jolt byte array for BLOB binding. Each octet is narrowed deliberately with
  unchecked-byte, so 128..255 wrap to the exact signed representation the
  driver stores."
  ^bytes [octets]
  (let [n (count octets)
        arr (byte-array n)]
    (dotimes [i n]
      (aset arr i (unchecked-byte (nth octets i))))
    arr))

(defn- bytes->octets
  "Converts a BLOB byte array back to the canonical unsigned octet vector.
  Jolt bytes are signed; bit-and 0xff recovers the stored octet."
  [^bytes arr]
  (mapv (fn [b] (bit-and 0xff (int b))) arr))

;; ---- row reconstruction --------------------------------------------------------

(defn- required-string [v context]
  (if (string? v)
    v
    (throw-corrupt! :not-a-string (assoc context :value v))))

(defn- required-integer [v context]
  (if (integer? v)
    v
    (throw-corrupt! :not-an-integer (assoc context :value v))))

(defn- required-blob
  "A required BLOB column must read back as a byte array. SQL NULL reads as
  nil and a wrong storage class (for example injected TEXT) reads as some
  other type; both are rejected, preserving the empty-vs-NULL distinction."
  [v context]
  (if (bytes? v)
    v
    (throw-corrupt! :not-a-blob (assoc context :value v))))

(defn- entity-row->entry [row]
  (let [context {:table entities-table :row row}
        entity-id (required-string (:entity_id row) context)
        version (required-integer (:version row) context)
        payload (required-blob (:payload row) context)]
    [entity-id {:version version :payload (bytes->octets payload)}]))

(defn- request-row->entry [row]
  (let [context {:table requests-table :row row}
        request-id (required-string (:request_id row) context)
        entity-id (required-string (:entity_id row) context)
        payload (required-blob (:payload row) context)
        version (required-integer (:version row) context)
        outbox-id (required-integer (:outbox_id row) context)]
    [request-id
     {:command {:request-id request-id
                :entity-id entity-id
                :payload (bytes->octets payload)}
      :result {:status :committed
               :request-id request-id
               :entity-id entity-id
               :version version
               :outbox-id outbox-id}}]))

(defn- outbox-row->row [row]
  (let [context {:table outbox-table :row row}
        outbox-id (required-integer (:outbox_id row) context)
        request-id (required-string (:request_id row) context)
        entity-id (required-string (:entity_id row) context)
        version (required-integer (:version row) context)
        payload (required-blob (:payload row) context)
        status (required-string (:status row) context)
        status-kw (cond
                    (= "pending" status) :pending
                    (= "delivered" status) :delivered
                    :else (throw-corrupt!
                           :invalid-status (assoc context :value status)))]
    {:outbox-id outbox-id
     :request-id request-id
     :entity-id entity-id
     :version version
     :payload (bytes->octets payload)
     :status status-kw}))

(defn- check-storable-command!
  "SQLite's current jdbc text binding is NUL-terminated. Reject embedded NUL
  in identifiers before opening a transaction so a canonical core identifier
  can never be silently truncated in durable storage. Other command validation
  remains owned by the pure core."
  [command]
  (doseq [[field value] [[:request-id (:request-id command)]
                         [:entity-id (:entity-id command)]]]
    (when (and (string? value)
               (not= -1 (.indexOf value (char 0))))
      (throw-adapter! :jolt.example.outbox.sqlite/unstorable-id
                      :embedded-nul
                      {:field field :value value}))))

;; ---- public API -----------------------------------------------------------------

(defn init-schema!
  "Creates the adapter's fixed tables when absent. Each table gets its own
  CREATE TABLE IF NOT EXISTS statement -- never one multi-statement SQL
  string -- so initialization is idempotent and a repeated call leaves an
  existing database, including its committed rows, untouched."
  [conn]
  (check-sqlite-connection! conn)
  (jdbc/execute!
   conn
   (str "create table if not exists " entities-table " ("
        "entity_id text primary key, "
        "version integer not null, "
        "payload blob not null)"))
  (jdbc/execute!
   conn
   (str "create table if not exists " requests-table " ("
        "request_id text primary key, "
        "entity_id text not null, "
        "payload blob not null, "
        "version integer not null, "
        "outbox_id integer not null unique)"))
  (jdbc/execute!
   conn
   (str "create table if not exists " outbox-table " ("
        "outbox_id integer primary key, "
        "request_id text not null unique, "
        "entity_id text not null, "
        "version integer not null, "
        "payload blob not null, "
        "status text not null)"))
  nil)

(defn load-state
  "Reconstructs exactly jolt.example.outbox's canonical state from the
  persisted rows. Outbox rows are read in outbox-id order; BLOB payloads are
  converted back to unsigned octet vectors; stored pending and delivered
  statuses are both accepted and any other stored status is rejected fail
  closed; :next-outbox-id is derived as one more than the greatest persisted
  outbox id (1 when none). The rebuilt state is passed through the core's
  fail-closed validation before it is returned, so inconsistent or tampered
  rows throw rather than being repaired. The caller must have run
  init-schema! on this database."
  [conn]
  (check-sqlite-connection! conn)
  (let [entity-rows
        (jdbc/fetch conn
                    (str "select entity_id, version, payload from "
                         entities-table " order by entity_id"))
        request-rows
        (jdbc/fetch conn
                    (str "select request_id, entity_id, payload, version, outbox_id from "
                         requests-table " order by request_id"))
        outbox-rows
        (jdbc/fetch conn
                    (str "select outbox_id, request_id, entity_id, version, payload, status from "
                         outbox-table " order by outbox_id"))
        outbox (mapv outbox-row->row outbox-rows)
        state {:entities (into {} (map entity-row->entry) entity-rows)
               :request-log (into {} (map request-row->entry) request-rows)
               :next-outbox-id (inc (reduce max 0 (map :outbox-id outbox)))
               :outbox outbox}]
    (outbox/validate-state! state)
    state))

(defn- write-entity!
  "Inserts a new entity projection or updates an existing one using a separate,
  explicit statement selected from the validated prior state. Keeping these as
  two fixed operations makes the storage contract observable and modelable
  without requiring SQLite UPSERT semantics."
  [conn prior-state row]
  (let [entity-id (:entity-id row)
        existing? (contains? (:entities prior-state) entity-id)
        [statement params]
        (if existing?
          [(str "update " entities-table
                " set version = ?, payload = ? where entity_id = ?")
           [(:version row) (octets->bytes (:payload row)) entity-id]]
          [(str "insert into " entities-table
                " (entity_id, version, payload) values (?, ?, ?)")
           [entity-id (:version row) (octets->bytes (:payload row))]])
        affected (jdbc/execute! conn (into [statement] params))]
    (check-affected! 1 affected
                     {:statement (if existing? :entity-update :entity-insert)
                      :entity-id entity-id})))

(defn- write-emission!
  "Writes one freshly emitted outbox row and its projections. Exactly one
  entity projection is inserted or updated, exactly one immutable request
  record is inserted, and exactly one matching pending outbox row is inserted
  with the pure core's allocated outbox-id written explicitly -- SQLite rowid
  allocation is never relied on. Every affected-row count is asserted."
  [conn prior-state row]
  (write-entity! conn prior-state row)
  (let [request-affected
        (jdbc/execute!
         conn
         [(str "insert into " requests-table
               " (request_id, entity_id, payload, version, outbox_id)"
               " values (?, ?, ?, ?, ?)")
          (:request-id row)
          (:entity-id row)
          (octets->bytes (:payload row))
          (:version row)
          (:outbox-id row)])]
    (check-affected! 1 request-affected {:statement :request-insert
                                         :request-id (:request-id row)}))
  (let [outbox-affected
        (jdbc/execute!
         conn
         [(str "insert into " outbox-table
               " (outbox_id, request_id, entity_id, version, payload, status)"
               " values (?, ?, ?, ?, ?, ?)")
          (:outbox-id row)
          (:request-id row)
          (:entity-id row)
          (:version row)
          (octets->bytes (:payload row))
          "pending"])]
    (check-affected! 1 outbox-affected {:statement :outbox-insert
                                        :outbox-id (:outbox-id row)}))
  nil)

(defn apply-command!
  "Applies one command durably: loads the canonical state, runs the pure
  jolt.example.outbox/apply-command transition, and writes the resulting
  delta, all inside one jdbc/atomic-apply transaction. Returns the same
  {:state :result :emitted} step map, and returns it only after atomic-apply
  has completed COMMIT.

  An exact replay (same request-id, equal command) performs no writes and
  returns the recorded result, the unchanged state, and no emissions. A
  conflicting request-id rethrows the core's typed
  :jolt.example.outbox/request-id-conflict with no writes. A fresh command
  updates/inserts exactly one entity projection and inserts exactly one
  request row plus one matching pending outbox row, atomically; any failure
  before COMMIT rolls the whole delta back. Calls made inside an ambient jdbc
  transaction are rejected before state is loaded because a nested savepoint
  cannot satisfy this function's durable-on-return contract. The caller must
  have run init-schema! on this database."
  [conn command]
  (check-sqlite-connection! conn)
  (check-durable-call-boundary! conn)
  (check-storable-command! command)
  (jdbc/atomic-apply
   conn
   (fn [c]
     (let [state (load-state c)
           step (outbox/apply-command state command)
           emitted (:emitted step)]
       (case (count emitted)
         0 step
         1 (do
             (write-emission! c state (first emitted))
             step)
         (throw-adapter! :jolt.example.outbox.sqlite/invalid-core-step
                         :emission-count
                         {:count (count emitted)}))))))

(defn mark-delivered!
  "Marks one outbox row delivered, durably: loads the canonical state, runs
  the pure jolt.example.outbox/mark-delivered transition, and -- only when
  the pure step reports :changed? -- performs exactly one guarded UPDATE
  moving that outbox id's status from pending to delivered, all inside one
  jdbc/atomic-apply transaction. The affected-row count of the guarded update
  must be exactly 1. Returns the same {:state :row :changed?} step map the
  pure core produced, and returns it only after atomic-apply has completed
  COMMIT.

  An idempotent call (the row is already delivered) performs no write at
  all. The pure core's typed failures propagate unchanged: an id that is not
  a positive integer throws :jolt.example.outbox/invalid-outbox-id and a
  positive id with no matching row throws
  :jolt.example.outbox/unknown-outbox-id, in both cases with no writes. Any
  failure before COMMIT rolls the whole transaction back. Calls made inside
  an ambient jdbc transaction are rejected before state is loaded because a
  nested savepoint cannot satisfy this function's durable-on-return contract.

  This function deliberately does not validate acknowledgements; the caller
  validates an acknowledgement before invoking it. The caller must have run
  init-schema! on this database."
  [conn outbox-id]
  (check-sqlite-connection! conn)
  (check-durable-call-boundary! conn)
  (jdbc/atomic-apply
   conn
   (fn [c]
     (let [state (load-state c)
           step (outbox/mark-delivered state outbox-id)]
       (if (:changed? step)
         (let [affected
               (jdbc/execute!
                c
                [(str "update " outbox-table
                      " set status = ? where outbox_id = ? and status = ?")
                 "delivered"
                 outbox-id
                 "pending"])]
           (check-affected! 1 affected
                            {:statement :outbox-mark-delivered
                             :outbox-id outbox-id})
           step)
         step)))))
