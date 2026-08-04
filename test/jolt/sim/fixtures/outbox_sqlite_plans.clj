(ns jolt.sim.fixtures.outbox-sqlite-plans
  "Test-only exact SQLite row-effect plans shared by the ordinary
  outbox parity fixture and the whole-application delivery fixture.

  This namespace is simulator configuration, not an alternate application or
  storage implementation. The SQL and bind positions mirror
  jolt.example.outbox.sqlite byte for byte. Keeping the plans here gives both
  gates one source of truth without exposing example-specific plan mechanics as
  a production API.")

;; ---- fixed table names and literal SQL ------------------------------------

(def ^:private entities-table "outbox_example_entities")
(def ^:private requests-table "outbox_example_requests")
(def ^:private outbox-table "outbox_example_outbox")

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

;; The historical req-1 command payload pinned by the zero-argument plan
;; arities below; it must stay byte-identical to
;; jolt.sim.fixtures.outbox-delivery/default-command's :payload.
(def ^:private default-command-payload [0 127 128 255])

;; ---- closed plan builders -------------------------------------------------

(defn- static-plan [sql]
  {:sql sql :params {} :columns [] :rows [] :changes 0 :last-row-id 0})

(defn- tx-plan [sql op]
  (assoc (static-plan sql) :tx-effect {:op op}))

(defn- scan-plan [sql columns table project order-key]
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
  [sql params table key-params key-columns set-pairs changes]
  {:sql sql
   :params params
   :columns []
   :changes changes
   :last-row-id 0
   :row-effect {:op :update-row
                :table table
                :key-params key-params
                :key-columns key-columns
                :set set-pairs}})

(defn parity-statement-plans
  "Returns the exact 28-statement FIFO plan used by the existing adapter parity
   scenario: schema setup, a fresh command, its exact replay, a second fresh
   command, and a final state load.

   The zero-argument arity pins the historical req-1 payload
   [0 127 128 255] and is byte-for-byte the plan the old parity test has
   always consumed. The one-argument arity rebinds only the first fresh
   command's three BLOB params (entity insert ?3, request insert ?3, outbox
   insert ?5) to `command-payload`, a vector of unsigned octets; every other
   statement, bind, and row effect is unchanged."
  ([]
   (parity-statement-plans default-command-payload))
  ([command-payload]
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
                 3 {:type :blob :value (byte-array command-payload)}}
                :outbox/entities [1]
                [["entity_id" 1] ["version" 2] ["payload" 3]]
                1 1)
    (insert-plan request-insert-sql
                 {1 {:type :text :value "req-1"}
                  2 {:type :text :value "entity-a"}
                  3 {:type :blob :value (byte-array command-payload)}
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
                  5 {:type :blob :value (byte-array command-payload)}
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
   ;; second fresh command: BEGIN, scans, entity update, two inserts, COMMIT
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
                ["entity_id"]
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
   ;; explicit final load-state: three scans over committed storage
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
              ["outbox_id"])]))

(defn delivery-statement-plans
  "Returns the exact 15-statement whole-app plan: schema setup, the first fresh
   req-1/entity-a command and successful COMMIT, then one explicit post-COMMIT
   load-state used by the delivery worker. This is deliberately a projection of
   parity-statement-plans so the command's SQL, binds, and row effects cannot
   diverge between the two gates.

   The zero-argument arity is byte-for-byte the plan the existing delivery
   gates have always consumed. The one-argument arity carries the scenario
   command's payload (a vector of unsigned octets) into the first command's
   three BLOB binds; everything else is unchanged."
  ([]
   (delivery-statement-plans default-command-payload))
  ([command-payload]
   (let [plans (parity-statement-plans command-payload)]
     (vec (concat (subvec plans 0 12)
                  (subvec plans 25 28))))))

(defn retry-statement-plans
  "Returns the exact 18-statement two-attempt retry plan: schema setup, the
   first fresh req-1/entity-a command and successful COMMIT, then TWO explicit
   post-COMMIT load-states over the same still-open connection -- the
   pre-delivery reload and the post-failure reload that proves the committed
   outbox/application state unchanged before the second delivery attempt. No
   statement runs during either TCP attempt, so the two load-states are
   adjacent in the plan.

   The count is derived from the implementation, not provisioned: the retry
   flow is delivery-statement-plans plus exactly one more load-state, and one
   load-state is the three final scan plans of parity-statement-plans. Like
   delivery-statement-plans this is a projection of parity-statement-plans, so
   the command's SQL, binds, and row effects cannot diverge between gates.
   It is an exact result-producing fixture for this unchanged application
   path, not a claim that every legal SQLite implementation must expose this
   transcript. General statement/transaction legality and handle ownership
   belong to a separate model rather than being inferred from plan count.

   The zero-argument arity pins the historical req-1 payload; the one-argument
   arity rebinds the first command's three BLOB params exactly as
   delivery-statement-plans does."
  ([]
   (retry-statement-plans default-command-payload))
  ([command-payload]
   (let [plans (parity-statement-plans command-payload)]
     (vec (concat (subvec plans 0 12)
                  (subvec plans 25 28)
                  (subvec plans 25 28))))))
