(ns jolt.example.outbox
  "Pure, simulator-free canonical application core for the later
  HTTP command -> SQLite transaction -> TCP bencode delivery flow.

  This slice owns only the deterministic domain decision: given a prior
  canonical state and one closed command map, decide the committed entity
  version, the idempotent result, and the newly emitted pending outbox rows.
  It deliberately knows nothing about HTTP, SQLite, TCP, bencode, or jolt-sim:
  there are no atoms, threads, I/O, clocks, entropy, or host identity here.
  Every output is a pure function of the inputs, so the same boundary can
  later run unchanged over a real database/transport or a simulated one.

  Canonical shapes (all maps are closed -- unknown keys are rejected):

  state    {:entities    {entity-id {:version v :payload octet-vector}}
            :request-log {request-id {:command command :result result}}
            :next-outbox-id positive-integer
            :outbox       [row ...]}
  command  {:request-id non-empty-string
            :entity-id  non-empty-string
            :payload    vector of integer octets 0..255}
  result   {:status :committed :request-id s :entity-id s
            :version positive-integer :outbox-id positive-integer}
  row      {:outbox-id id :request-id s :entity-id s :version v
            :payload octet-vector :status (:pending or :delivered)}

  apply-command returns {:state next-state :result result :emitted [row ...]}.
  An exact replay (same request-id, equal command) returns the recorded
  result, the unchanged state, and no emitted rows. Reusing a request-id for
  a different command throws a typed ex-info before any state is produced.
  Outbox row ids are allocated from :next-outbox-id and are never reused.
  Newly emitted rows are always :pending.

  mark-delivered returns {:state next-state :row delivered-row
  :changed? boolean}. A pending row transitions to :delivered, changing only
  its :status. Marking an already :delivered row is idempotent: the prior
  state is returned byte-equal and :changed? is false. An id that is not a
  positive integer fails closed with :jolt.example.outbox/invalid-outbox-id;
  a positive id with no matching row fails closed with
  :jolt.example.outbox/unknown-outbox-id. State validation accepts only
  :pending or :delivered row statuses, and history consistency remains exact
  under marking because the replay never compares :status.")

;; ---- canonical shapes -----------------------------------------------------

(def ^:private state-keys
  #{:entities :request-log :next-outbox-id :outbox})

(def ^:private command-keys
  #{:request-id :entity-id :payload})

(def ^:private entity-keys
  #{:version :payload})

(def ^:private log-entry-keys
  #{:command :result})

(def ^:private result-keys
  #{:status :request-id :entity-id :version :outbox-id})

(def ^:private outbox-row-keys
  #{:outbox-id :request-id :entity-id :version :payload :status})

;; ---- predicates -----------------------------------------------------------

(defn- closed-map?
  "True when `m` is a map with exactly the key set `ks` -- no missing and no
  unknown keys. Fail-closed canonical validation never repairs input."
  [m ks]
  (and (map? m) (= ks (set (keys m)))))

(defn- id-string?
  "request-ids and entity-ids are canonical non-empty strings."
  [x]
  (and (string? x) (not (empty? x))))

(defn- positive-integer? [x]
  (and (integer? x) (<= 1 x)))

(defn- octet? [x]
  (and (integer? x) (<= 0 x) (<= x 255)))

(defn- payload?
  "A payload is a vector of integer octets 0 through 255."
  [x]
  (and (vector? x) (every? octet? x)))

(defn- valid-command? [command]
  (and (closed-map? command command-keys)
       (id-string? (:request-id command))
       (id-string? (:entity-id command))
       (payload? (:payload command))))

(defn- valid-entity? [entity]
  (and (closed-map? entity entity-keys)
       (positive-integer? (:version entity))
       (payload? (:payload entity))))

(defn- valid-result? [result]
  (and (closed-map? result result-keys)
       (= :committed (:status result))
       (id-string? (:request-id result))
       (id-string? (:entity-id result))
       (positive-integer? (:version result))
       (positive-integer? (:outbox-id result))))

(defn- valid-outbox-row? [row]
  (and (closed-map? row outbox-row-keys)
       (positive-integer? (:outbox-id row))
       (id-string? (:request-id row))
       (id-string? (:entity-id row))
       (positive-integer? (:version row))
       (payload? (:payload row))
       (contains? #{:pending :delivered} (:status row))))

(defn- valid-log-entry? [request-id entry]
  (and (closed-map? entry log-entry-keys)
       (valid-command? (:command entry))
       (= request-id (:request-id (:command entry)))
       (valid-result? (:result entry))
       (= request-id (:request-id (:result entry)))))

(defn- history-consistent?
  "Replays the canonical outbox history as validation, without calling the
   public transition. Every accepted command owns exactly one row and result;
   row ids and per-entity versions are contiguous because this pure core has no
   deletion, reservation, or failed-after-allocation transition. The rebuilt
   entity projection and the complete request-id set must equal the stored
   projections exactly. Delivery marking changes only a row's :status, which
   this replay deliberately never compares, so a marked history stays exactly
   consistent."
  [state]
  (loop [rows (seq (:outbox state))
         expected-outbox-id 1
         rebuilt-entities {}
         seen-request-ids #{}]
    (if-let [row (first rows)]
      (let [request-id (:request-id row)
            entity-id (:entity-id row)
            entry (get (:request-log state) request-id)
            command (:command entry)
            result (:result entry)
            expected-version
            (inc (get-in rebuilt-entities [entity-id :version] 0))]
        (if (and (not (contains? seen-request-ids request-id))
                 (= expected-outbox-id (:outbox-id row))
                 (= expected-version (:version row))
                 (= {:request-id request-id
                     :entity-id entity-id
                     :payload (:payload row)}
                    command)
                 (= {:status :committed
                     :request-id request-id
                     :entity-id entity-id
                     :version expected-version
                     :outbox-id expected-outbox-id}
                    result))
          (recur (next rows)
                 (inc expected-outbox-id)
                 (assoc rebuilt-entities entity-id
                        {:version expected-version :payload (:payload row)})
                 (conj seen-request-ids request-id))
          false))
      (and (= expected-outbox-id (:next-outbox-id state))
           (= rebuilt-entities (:entities state))
           (= seen-request-ids (set (keys (:request-log state))))))))

(defn- valid-state?
  "Fail-closed validation of a prior state: exact key sets and scalar shapes,
  followed by a replay of the stored history that proves every request,
  result, row, and current entity projection agree. Input is never repaired."
  [state]
  (and (closed-map? state state-keys)
       (map? (:entities state))
       (every? (fn [[entity-id entity]]
                 (and (id-string? entity-id) (valid-entity? entity)))
               (:entities state))
       (map? (:request-log state))
       (every? (fn [[request-id entry]]
                 (and (id-string? request-id)
                      (valid-log-entry? request-id entry)))
               (:request-log state))
       (positive-integer? (:next-outbox-id state))
       (vector? (:outbox state))
       (every? valid-outbox-row? (:outbox state))
       (history-consistent? state)))

;; ---- typed failures -------------------------------------------------------

(defn- throw-invalid!
  [type reason detail]
  (throw
   (ex-info
    (str "jolt.example.outbox: " (name type) " (" (name reason) ")")
    {:type type :reason reason :detail detail})))

(defn- check-command! [command]
  (when-not (valid-command? command)
    (throw-invalid! :jolt.example.outbox/invalid-command
                    :bad-command
                    {:command command})))

(defn- check-state! [state]
  (when-not (valid-state? state)
    (throw-invalid! :jolt.example.outbox/invalid-state
                    :bad-state
                    {:state state})))

(defn- throw-request-id-conflict! [request-id recorded received]
  (throw
   (ex-info
    (str "jolt.example.outbox: request-id reused for a different command "
         request-id)
    {:type :jolt.example.outbox/request-id-conflict
     :reason :request-id-conflict
     :request-id request-id
     :recorded recorded
     :received received})))

(defn- check-outbox-id!
  "An outbox id is canonical only as a positive integer. Anything else is
   rejected fail closed before state validation, mirroring apply-command's
   input-first ordering."
  [outbox-id]
  (when-not (positive-integer? outbox-id)
    (throw-invalid! :jolt.example.outbox/invalid-outbox-id
                    :bad-outbox-id
                    {:outbox-id outbox-id})))

(defn- throw-unknown-outbox-id! [outbox-id]
  (throw
   (ex-info
    (str "jolt.example.outbox: no outbox row with id " outbox-id)
    {:type :jolt.example.outbox/unknown-outbox-id
     :reason :unknown-outbox-id
     :outbox-id outbox-id})))

(defn- outbox-index-of
  "Returns the index of the outbox row carrying `outbox-id`, or nil when no
   row matches. Rows are validated before lookup, so at most one can match."
  [rows outbox-id]
  (loop [i 0]
    (cond
      (>= i (count rows)) nil
      (= outbox-id (:outbox-id (nth rows i))) i
      :else (recur (inc i)))))

;; ---- public API -----------------------------------------------------------

(defn initial-state
  "Returns the canonical immutable initial state: no entities, no recorded
  request-ids, no outbox rows, and the first outbox id waiting to be
  allocated."
  []
  {:entities {}
   :request-log {}
   :next-outbox-id 1
   :outbox []})

(defn validate-state!
  "Fail-closed canonical-state assertion for storage adapters that rebuild a
  state from durable rows. Returns `state` unchanged when it is exactly
  canonical; throws :jolt.example.outbox/invalid-state ex-info otherwise.
  Input is never repaired. apply-command runs this same validation on its
  prior state; this public entry point lets an adapter reject a corrupted
  database at load time, before any transition is attempted."
  [state]
  (check-state! state)
  state)

(defn apply-command
  "Applies one closed command map to a canonical prior state, purely.

  Returns {:state next-state :result result :emitted [row ...]} where
  :emitted holds exactly the outbox rows newly emitted by this call.

  A first-seen request-id creates or updates one entity version (starting at
  1 and incrementing per committed command on that entity), allocates a
  monotonic never-reused outbox row id, records the request-id with its
  command and result, and emits exactly one pending outbox row whose entity
  version and payload equal the committed domain value.

  An exact replay of the same request-id with an equal command returns the
  identical recorded result, the unchanged state, and an empty :emitted
  vector. Reusing a request-id for any different command throws
  :jolt.example.outbox/request-id-conflict ex-info before any state is
  produced. Malformed commands and malformed prior states are rejected
  fail-closed with typed ex-info; nothing is repaired."
  [state command]
  (check-command! command)
  (check-state! state)
  (let [request-id (:request-id command)]
    (if-let [entry (get (:request-log state) request-id)]
      (if (= (:command entry) command)
        {:state state :result (:result entry) :emitted []}
        (throw-request-id-conflict! request-id (:command entry) command))
      (let [entity-id (:entity-id command)
            version (inc (get-in state [:entities entity-id :version] 0))
            outbox-id (:next-outbox-id state)
            row {:outbox-id outbox-id
                 :request-id request-id
                 :entity-id entity-id
                 :version version
                 :payload (:payload command)
                 :status :pending}
            result {:status :committed
                    :request-id request-id
                    :entity-id entity-id
                    :version version
                    :outbox-id outbox-id}
            next-state
            (-> state
                (assoc-in [:entities entity-id]
                          {:version version :payload (:payload command)})
                (assoc-in [:request-log request-id]
                          {:command command :result result})
                (update :outbox conj row)
                (assoc :next-outbox-id (inc outbox-id)))]
        {:state next-state :result result :emitted [row]}))))

(defn mark-delivered
  "Marks one outbox row delivered, purely.

   Returns exactly {:state next-state :row delivered-row :changed? boolean}.

   A pending row transitions to :delivered: next-state differs from the prior
   state only in that row's :status, :changed? is true, and :row is the
   delivered row. Marking an already :delivered row is idempotent: the prior
   state is returned byte-equal, :changed? is false, and :row is the existing
   delivered row. Entities, request-log, next-outbox-id, and every other row
   are never touched, and the marked state remains exactly canonical under
   validate-state!.

   An id that is not a positive integer throws
   :jolt.example.outbox/invalid-outbox-id ex-info. A positive integer with no
   matching row throws :jolt.example.outbox/unknown-outbox-id ex-info. A
   malformed prior state throws :jolt.example.outbox/invalid-state ex-info.
   Nothing is repaired. This function records the marking decision only; it
   knows nothing about acknowledgements or transport."
  [state outbox-id]
  (check-outbox-id! outbox-id)
  (check-state! state)
  (let [rows (:outbox state)
        index (outbox-index-of rows outbox-id)]
    (if (nil? index)
      (throw-unknown-outbox-id! outbox-id)
      (let [row (nth rows index)]
        (if (= :delivered (:status row))
          {:state state :row row :changed? false}
          (let [delivered-row (assoc row :status :delivered)]
            {:state (assoc state :outbox (assoc rows index delivered-row))
             :row delivered-row
             :changed? true}))))))
