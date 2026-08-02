(ns jolt.sim.evidence.http-sqlite
  "Canonical evidence-v1 document and two pure post-hoc monitors for the
  HTTP/SQLite vertical slice (jolt.sim.fixtures.http-sqlite,
  jolt.sim.http-sqlite-integration-test).

  `build-evidence` takes the already-completed pieces of one controlled run --
  the jolt.sim.runtime/run-controlled result, the shared jolt.sim.sqlite and
  jolt.sim.net.posix-loopback worlds, and the jolt.sim.net.posix-fault frontend
  that drove it -- and assembles one canonical, jolt.sim.trace-stable EDN map
  tagged with schema `:jolt.sim/http-sqlite-evidence-v1`. It performs no
  additional FFI, scheduling, or I/O; every field is a summary of state that
  already exists at the end of a successful run.

  Explicit hermetic assumptions this evidence-v1 document depends on
  (`hermetic-assumptions`): one synchronous HTTP request/response over one
  SQLite connection whose statements execute strictly sequentially with no
  overlapping prepare/finalize or open/close lifecycles; finite one-byte
  stream and self-pipe capacity; exactly one fault-plan rule, firing a single
  captured EINTR at the first poll attempt; no virtual/simulated clock (the
  loopback world uses real monotonic waits); no concurrent requests; a
  single application thread driving the controlled scope; and an external
  test deadline (teensyp.client's `:timeout-ms` plus `run-controlled`'s
  `:drain-timeout-ms`) enforced outside this document, so a non-completing run
  never reaches evidence construction in the first place. These are the same
  assumptions already documented in the project README's HTTP/SQLite section;
  this namespace does not re-derive or re-prove them, only records them
  alongside the evidence they qualify.

  Per-document encounter identity: every id emitted here is a fixture-owned pair of a
  named domain keyword plus a small encounter ordinal, derived from this one
  document's own observed effect order -- never a raw task id, native
  pointer/handle/fd, object identity, timestamp, or the FFI effect vector's
  raw arrival index. These are per-document/per-run encounter ids, not
  cross-run semantic identities: the Nth `sqlite3_step` call in one run and
  the Nth in another share the same id shape but are never asserted to be the
  same logical call. Two id domains are used:

  * an *operation* id `[:jolt.sim.evidence.http-sqlite/operation kind symbol
    ordinal]`, where `ordinal` counts encounters of that exact `[kind
    symbol]` pair (e.g. the 3rd `sqlite3_step` call) independently of every
    other symbol's count and of the effect vector's mixed global order; and
  * a *resource* id `[domain ordinal]` (domain one of `:sqlite-statement` or
    :sqlite-connection`), assigned when an opening call
    (`sqlite3_prepare_v2`/`sqlite3_open`) is encountered and carried by its
    paired terminal call (`sqlite3_finalize`/`sqlite3_close_v2`). Pairing
    assumes non-overlapping lifecycles (see assumptions above); the safety
    monitor below independently re-derives and checks that assumption rather
    than trusting it.

  TODO(hegel-fault-selector): the task that produced this namespace asked for
  a tiny two-case `[]` (no fault) versus `interrupt-first-poll-plan` selector
  built on the existing jolt.sim.hegel alias/API, if one is available without
  inventing new API surface. It is not: jolt.sim.hegel currently exposes only
  `schedule-generator`/`direct-schedule-generator`, generators over *future
  admission permutations* validated by `jolt.sim.future-schedule/valid-schedule?`
  and consumed specifically by `jolt.sim.process-explorer/run-schedule`. The
  HTTP/SQLite lane instead drives `jolt.sim.runtime/run-controlled` directly
  in-process (see jolt.sim.http-sqlite-integration-test), never through
  process-explorer/defsim, so there is no existing Hegel entry point that
  selects between two arbitrary fault-plan values. Adding one would mean
  inventing a new generator/wiring point this namespace does not have
  standing to introduce; left undone rather than speculative."
  (:require [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.sqlite :as sqlite]
            [jolt.sim.trace :as trace]))

(def schema
  "The only evidence-v1 schema tag this namespace produces or accepts."
  :jolt.sim/http-sqlite-evidence-v1)

(def evidence-version
  "The only evidence-v1 document version this namespace accepts."
  1)

(def invalid-document
  :jolt.sim.evidence.http-sqlite/invalid-document)

(def hermetic-assumptions
  "Closed, explicit vector of this evidence-v1 slice's hermetic modeling
  assumptions, recorded verbatim in every document. See the namespace
  docstring for what each assumption means."
  [:jolt.sim.evidence.http-sqlite.assumption/single-synchronous-request
   :jolt.sim.evidence.http-sqlite.assumption/one-connection-sequential-statements
   :jolt.sim.evidence.http-sqlite.assumption/one-byte-stream-and-pipe-capacity
   :jolt.sim.evidence.http-sqlite.assumption/single-first-poll-eintr-fault
   :jolt.sim.evidence.http-sqlite.assumption/no-virtual-time
   :jolt.sim.evidence.http-sqlite.assumption/no-concurrent-requests
   :jolt.sim.evidence.http-sqlite.assumption/single-threaded-controlled-scope
   :jolt.sim.evidence.http-sqlite.assumption/external-test-deadline])

(def ^:private expected-response-bytes
  "The fixture's fixed BLOB octets, spanning the signed/unsigned byte
  boundary. jolt.sim.fixtures.http-sqlite/blob-octets is the source of truth;
  this is a private literal copy so the evidence namespace does not depend on
  the ordinary application fixture."
  [0 65 127 128 255])

;; ---- resource domains -----------------------------------------------------

(def ^:private resource-domains
  {"sqlite3_prepare_v2" :sqlite-statement
   "sqlite3_finalize"   :sqlite-statement
   "sqlite3_open"       :sqlite-connection
   "sqlite3_close_v2"   :sqlite-connection})

(def ^:private open-symbols #{"sqlite3_prepare_v2" "sqlite3_open"})
(def ^:private terminal-symbols #{"sqlite3_finalize" "sqlite3_close_v2"})

(defn- resource-domain [symbol] (get resource-domains symbol))
(defn- open-symbol? [symbol] (contains? open-symbols symbol))
(defn- terminal-symbol? [symbol] (contains? terminal-symbols symbol))

;; ---- closed nested shape/type/id validation primitives --------------------
;;
;; These predicates check only self-contained shape/type facts -- never facts
;; that require correlating one document section against another (resource-id
;; identity tracking, fault history/snapshot coherence, response-vs-fixture
;; equality). Cross-section correlation is monitor-owned invariant logic, not
;; document shape, so a well-shaped document that is semantically wrong (a
;; bad route, a mismatched resource id, a re-firing rule) must still validate
;; here and be caught as a monitor :violation instead of an exception.

(defn- exact-keys? [expected value]
  (and (map? value) (= expected (set (keys value)))))

(defn- positive-int? [value]
  (and (integer? value) (pos? value)))

(defn- nonnegative-int? [value]
  (and (integer? value) (not (neg? value))))

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- byte-value? [value]
  (and (integer? value) (<= 0 value 255)))

(defn- valid-resource-id? [value]
  (or (nil? value)
      (and (vector? value)
           (= 2 (count value))
           (contains? #{:sqlite-statement :sqlite-connection} (nth value 0))
           (positive-int? (nth value 1)))))

;; ---- building ---------------------------------------------------------

(defn- unsigned-bytes [^bytes ba]
  (mapv #(bit-and (long %) 0xff) (seq ba)))

(defn- route-symbol [descriptor]
  (case (:kind descriptor)
    :foreign-function (:symbol descriptor)
    :native-operation (:operation descriptor)
    (throw
     (ex-info "http-sqlite evidence: unknown effect descriptor kind"
              {:type invalid-document
               :reason :unknown-descriptor-kind
               :descriptor descriptor}))))

(defn- normalize-routes
  "Walks `effect-trace` once, in its existing (arrival) order, purely to fold
  deterministic per-symbol operation ordinals and per-domain resource ordinals.
  That arrival order is used only to sequence the fold -- never retained as an
  id itself; every emitted :id/:resource-id is domain+ordinal, per the
  namespace docstring."
  [effect-trace]
  (:routes
   (reduce
    (fn [{:keys [op-counters resource-next resource-active routes]} entry]
      (let [descriptor (:descriptor entry)
            kind (:kind descriptor)
            symbol (route-symbol descriptor)
            op-key [kind symbol]
            op-ordinal (inc (get op-counters op-key 0))
            domain (resource-domain symbol)
            opening? (and domain (open-symbol? symbol))
            closing? (and domain (terminal-symbol? symbol))
            resource-id
            (cond
              opening? [domain (inc (get resource-next domain 0))]
              closing? (when-let [active (get resource-active domain)]
                         [domain active])
              :else nil)]
        {:op-counters (assoc op-counters op-key op-ordinal)
         :resource-next (if opening?
                          (update resource-next domain (fnil inc 0))
                          resource-next)
         :resource-active
         (cond
           opening? (assoc resource-active domain (second resource-id))
           closing? (dissoc resource-active domain)
           :else resource-active)
         :routes
         (conj routes
               {:id [:jolt.sim.evidence.http-sqlite/operation
                     kind symbol op-ordinal]
                :kind kind
                :symbol symbol
                :resource-id resource-id
                :route (:route entry)})}))
    {:op-counters {} :resource-next {} :resource-active {} :routes []}
    effect-trace)))

(defn- statement-sites
  "Fixture-owned :sqlite-statement site records, one per entry in
  `statement-plans` in plan order (1-based plan-index, never a raw prepared
  statement handle)."
  [statement-plans]
  (mapv (fn [index plan]
          {:id [:jolt.sim.evidence.http-sqlite/site
                :sqlite-statement (inc index)]
           :sql (:sql plan)
           :param-count (count (:params plan))})
        (range)
        statement-plans))

(defn- sqlite-resource-evidence [sqlite-world]
  (let [summary (sqlite/summary sqlite-world)]
    {:plan-index (:plan-index summary)
     :plan-count (:plan-count summary)
     :open-connections (:open-dbs summary)
     :open-statements (:active-stmts summary)
     :clean? (sqlite/clean? sqlite-world)}))

(defn- posix-resource-evidence [posix-world]
  {:open-sockets (count (posix/snapshot posix-world))
   :open-pipes (count (posix/pipe-snapshot posix-world))
   :open-listeners (count (get (posix/state posix-world) :listeners))
   :open-addrinfo (count (get (posix/state posix-world) :addrinfo-allocations))
   :waiter-count (:waiter-count (posix/readiness-snapshot posix-world))
   :clean? (posix/clean? posix-world)})

(defn- posix-capacity-evidence [posix-world]
  {:stream (posix/capacity-summary posix-world)
   :pipe (posix/pipe-capacity-summary posix-world)})

(defn- posix-fault-evidence [fault-frontend]
  {:snapshot (posix-fault/snapshot fault-frontend)
   :history (posix-fault/evidence-history fault-frontend)
   :attempts (posix-fault/attempts fault-frontend)})

(defn- response-evidence [parsed]
  {:status (:status parsed)
   :content-type (get (:headers parsed) "content-type")
   :content-length (get (:headers parsed) "content-length")
   :body-bytes (unsigned-bytes (:body parsed))})

(def ^:private document-keys
  #{:jolt.sim.evidence.http-sqlite/schema
    :jolt.sim.evidence.http-sqlite/version
    :jolt.sim.evidence.http-sqlite/assumptions
    :jolt.sim.evidence.http-sqlite/sqlite-sites
    :jolt.sim.evidence.http-sqlite/ffi-routes
    :jolt.sim.evidence.http-sqlite/posix-fault
    :jolt.sim.evidence.http-sqlite/posix-capacity
    :jolt.sim.evidence.http-sqlite/sqlite-resources
    :jolt.sim.evidence.http-sqlite/posix-resources
    :jolt.sim.evidence.http-sqlite/response})

(defn- invalid! [reason data]
  (throw (ex-info (str "http-sqlite evidence document " (name reason))
                   (assoc data :type invalid-document :reason reason))))

(defn- validate-sqlite-sites! [sites]
  (when-not (vector? sites)
    (invalid! :sqlite-sites-must-be-vector {:value sites}))
  (doseq [[index site] (map-indexed vector sites)]
    (when-not (exact-keys? #{:id :sql :param-count} site)
      (invalid! :invalid-sqlite-site-shape {:index index :site site}))
    (when-not (= (:id site)
                 [:jolt.sim.evidence.http-sqlite/site :sqlite-statement
                  (inc index)])
      (invalid! :invalid-sqlite-site-id {:index index :id (:id site)}))
    (when-not (string? (:sql site))
      (invalid! :invalid-sqlite-site-sql {:index index :sql (:sql site)}))
    (when-not (nonnegative-int? (:param-count site))
      (invalid! :invalid-sqlite-site-param-count
                {:index index :param-count (:param-count site)}))))

(defn- validate-ffi-routes! [routes]
  (when-not (vector? routes)
    (invalid! :ffi-routes-must-be-vector {:value routes}))
  ;; Folds op-counters across the whole vector so each `[kind symbol]` pair's
  ;; operation ordinal is checked to be exactly contiguous (1, 2, 3, ...) in
  ;; the route list's own order -- a self-contained shape fact about this one
  ;; section, not a cross-section correlation.
  (loop [index 0
         op-counters {}
         remaining routes]
    (when (seq remaining)
      (let [route (first remaining)]
        (when-not (exact-keys? #{:id :kind :symbol :resource-id :route} route)
          (invalid! :invalid-ffi-route-shape {:index index :route route}))
        (let [kind (:kind route)
              symbol (:symbol route)
              resource-id (:resource-id route)
              op-key [kind symbol]
              expected-op-ordinal (inc (get op-counters op-key 0))]
          (when-not (contains? #{:foreign-function :native-operation} kind)
            (invalid! :invalid-ffi-route-kind {:index index :kind kind}))
          (when-not (if (= :foreign-function kind) (string? symbol) (keyword? symbol))
            (invalid! :invalid-ffi-route-symbol
                      {:index index :kind kind :symbol symbol}))
          (when-not (contains? #{:handler :native :blocked} (:route route))
            (invalid! :invalid-ffi-route-route
                      {:index index :route (:route route)}))
          (when-not (and (vector? (:id route))
                         (= 4 (count (:id route)))
                         (= :jolt.sim.evidence.http-sqlite/operation
                            (nth (:id route) 0))
                         (= kind (nth (:id route) 1))
                         (= symbol (nth (:id route) 2))
                         (= expected-op-ordinal (nth (:id route) 3)))
            (invalid! :invalid-ffi-route-id
                      {:index index :id (:id route)
                       :expected-ordinal expected-op-ordinal}))
          (when-not (valid-resource-id? resource-id)
            (invalid! :invalid-ffi-route-resource-id
                      {:index index :resource-id resource-id}))
          (let [domain (and (string? symbol) (resource-domain symbol))]
            (if domain
              (when-not (and (some? resource-id) (= domain (nth resource-id 0)))
                (invalid! :ffi-route-resource-id-domain-mismatch
                          {:index index :symbol symbol :expected-domain domain
                           :resource-id resource-id}))
              (when-not (nil? resource-id)
                (invalid! :unexpected-ffi-route-resource-id
                          {:index index :symbol symbol :resource-id resource-id}))))
          (recur (inc index)
                 (assoc op-counters op-key expected-op-ordinal)
                 (rest remaining)))))))

(defn- validate-fault-outcome! [index outcome]
  ;; Shape-only: exact keys and keyword-typed values. Whether the outcome is
  ;; actually the one accepted captured-EINTR shape is a semantic fact the
  ;; liveness monitor checks itself, not a document-shape exception -- a
  ;; well-shaped outcome naming the wrong errno must survive here and surface
  ;; as a monitor :violation instead.
  (when-not (exact-keys? #{:kind :errno} outcome)
    (invalid! :invalid-fault-outcome-shape {:index index :outcome outcome}))
  (when-not (and (keyword? (:kind outcome)) (keyword? (:errno outcome)))
    (invalid! :invalid-fault-outcome-type {:index index :outcome outcome})))

(defn- validate-fault-match! [index match-index m]
  (when-not (exact-keys? #{:rule-id :match-ordinal :activated? :fired?} m)
    (invalid! :invalid-fault-match-shape
              {:index index :match-index match-index :match m}))
  (when-not (namespaced-keyword? (:rule-id m))
    (invalid! :invalid-fault-match-rule-id
              {:index index :match-index match-index :rule-id (:rule-id m)}))
  (when-not (positive-int? (:match-ordinal m))
    (invalid! :invalid-fault-match-ordinal
              {:index index :match-index match-index
               :match-ordinal (:match-ordinal m)}))
  (when-not (and (boolean? (:activated? m)) (boolean? (:fired? m)))
    (invalid! :invalid-fault-match-flags
              {:index index :match-index match-index :match m})))

(defn- validate-fault-firing! [index firing]
  (when (some? firing)
    (when-not (exact-keys? #{:rule-id :match-ordinal :firing-ordinal
                              :rule-firing-ordinal :outcome}
                            firing)
      (invalid! :invalid-fault-firing-shape {:index index :firing firing}))
    (when-not (namespaced-keyword? (:rule-id firing))
      (invalid! :invalid-fault-firing-rule-id
                {:index index :rule-id (:rule-id firing)}))
    (when-not (and (positive-int? (:match-ordinal firing))
                   (positive-int? (:firing-ordinal firing))
                   (positive-int? (:rule-firing-ordinal firing)))
      (invalid! :invalid-fault-firing-ordinals {:index index :firing firing}))
    (validate-fault-outcome! index (:outcome firing))))

(defn- validate-fault-history! [history]
  (when-not (vector? history)
    (invalid! :fault-history-must-be-vector {:value history}))
  (doseq [[index entry] (map-indexed vector history)]
    (when-not (exact-keys? #{:attempt-id :matches :firing} entry)
      (invalid! :invalid-fault-history-entry-shape {:index index :entry entry}))
    (when-not (and (vector? (:attempt-id entry))
                   (= 2 (count (:attempt-id entry)))
                   (= :jolt.sim.net.posix-fault/poll (nth (:attempt-id entry) 0))
                   (positive-int? (nth (:attempt-id entry) 1)))
      (invalid! :invalid-fault-attempt-id
                {:index index :attempt-id (:attempt-id entry)}))
    (when-not (vector? (:matches entry))
      (invalid! :fault-matches-must-be-vector {:index index}))
    (doseq [[match-index m] (map-indexed vector (:matches entry))]
      (validate-fault-match! index match-index m))
    (validate-fault-firing! index (:firing entry))))

(defn- validate-fault-snapshot! [snapshot]
  (when-not (exact-keys? #{:next-attempt :firings :rules} snapshot)
    (invalid! :invalid-fault-snapshot-shape {:snapshot snapshot}))
  (when-not (positive-int? (:next-attempt snapshot))
    (invalid! :invalid-fault-snapshot-next-attempt
              {:next-attempt (:next-attempt snapshot)}))
  (when-not (nonnegative-int? (:firings snapshot))
    (invalid! :invalid-fault-snapshot-firings {:firings (:firings snapshot)}))
  (when-not (vector? (:rules snapshot))
    (invalid! :fault-snapshot-rules-must-be-vector {:rules (:rules snapshot)}))
  (doseq [[index rule] (map-indexed vector (:rules snapshot))]
    (when-not (exact-keys? #{:rule-id :on-match :times :matches :firings} rule)
      (invalid! :invalid-fault-snapshot-rule-shape {:index index :rule rule}))
    (when-not (namespaced-keyword? (:rule-id rule))
      (invalid! :invalid-fault-snapshot-rule-id
                {:index index :rule-id (:rule-id rule)}))
    (when-not (and (positive-int? (:on-match rule)) (positive-int? (:times rule)))
      (invalid! :invalid-fault-snapshot-rule-activation {:index index :rule rule}))
    (when-not (and (nonnegative-int? (:matches rule))
                   (nonnegative-int? (:firings rule)))
      (invalid! :invalid-fault-snapshot-rule-counters {:index index :rule rule}))))

(defn- validate-posix-fault! [fault]
  (when-not (exact-keys? #{:snapshot :history :attempts} fault)
    (invalid! :invalid-posix-fault-shape {:fault fault}))
  (validate-fault-snapshot! (:snapshot fault))
  (validate-fault-history! (:history fault))
  (when-not (nonnegative-int? (:attempts fault))
    (invalid! :invalid-posix-fault-attempts {:attempts (:attempts fault)})))

(defn- validate-posix-capacity! [capacity]
  (when-not (exact-keys? #{:stream :pipe} capacity)
    (invalid! :invalid-posix-capacity-shape {:capacity capacity}))
  (let [stream (:stream capacity)
        pipe (:pipe capacity)]
    (when-not (exact-keys? #{:stream-capacity :stream-would-blocks
                              :stream-capacity-limited-writes
                              :max-stream-recv-bytes}
                            stream)
      (invalid! :invalid-stream-capacity-shape {:stream stream}))
    (when-not (positive-int? (:stream-capacity stream))
      (invalid! :invalid-stream-capacity
                {:stream-capacity (:stream-capacity stream)}))
    (when-not (every? nonnegative-int?
                       [(:stream-would-blocks stream)
                        (:stream-capacity-limited-writes stream)
                        (:max-stream-recv-bytes stream)])
      (invalid! :invalid-stream-capacity-counters {:stream stream}))
    (when-not (exact-keys? #{:pipe-capacity :pipe-would-blocks
                              :max-pipe-fifo-bytes}
                            pipe)
      (invalid! :invalid-pipe-capacity-shape {:pipe pipe}))
    (when-not (positive-int? (:pipe-capacity pipe))
      (invalid! :invalid-pipe-capacity {:pipe-capacity (:pipe-capacity pipe)}))
    (when-not (every? nonnegative-int?
                       [(:pipe-would-blocks pipe) (:max-pipe-fifo-bytes pipe)])
      (invalid! :invalid-pipe-capacity-counters {:pipe pipe}))))

(defn- validate-sqlite-resources! [resources]
  (when-not (exact-keys? #{:plan-index :plan-count
                            :open-connections :open-statements :clean?}
                          resources)
    (invalid! :invalid-sqlite-resources-shape {:resources resources}))
  (when-not (every? nonnegative-int?
                     [(:plan-index resources) (:plan-count resources)
                      (:open-connections resources) (:open-statements resources)])
    (invalid! :invalid-sqlite-resources-counters {:resources resources}))
  (when-not (boolean? (:clean? resources))
    (invalid! :invalid-sqlite-resources-flags {:resources resources})))

(defn- validate-posix-resources! [resources]
  (when-not (exact-keys? #{:open-sockets :open-pipes :open-listeners
                            :open-addrinfo :waiter-count :clean?}
                          resources)
    (invalid! :invalid-posix-resources-shape {:resources resources}))
  (when-not (every? nonnegative-int?
                     [(:open-sockets resources) (:open-pipes resources)
                      (:open-listeners resources) (:open-addrinfo resources)
                      (:waiter-count resources)])
    (invalid! :invalid-posix-resources-counters {:resources resources}))
  (when-not (boolean? (:clean? resources))
    (invalid! :invalid-posix-resources-flag {:resources resources})))

(defn- validate-response! [response]
  (when-not (exact-keys? #{:status :content-type :content-length :body-bytes}
                          response)
    (invalid! :invalid-response-shape {:response response}))
  (when-not (positive-int? (:status response))
    (invalid! :invalid-response-status {:status (:status response)}))
  (when-not (string? (:content-type response))
    (invalid! :invalid-response-content-type
              {:content-type (:content-type response)}))
  (when-not (string? (:content-length response))
    (invalid! :invalid-response-content-length
              {:content-length (:content-length response)}))
  (when-not (and (vector? (:body-bytes response))
                 (every? byte-value? (:body-bytes response)))
    (invalid! :invalid-response-body-bytes {:body-bytes (:body-bytes response)})))

(defn validate-document!
  "Validates an evidence-v1 document's exact key set, schema tag, version,
  membership in jolt.sim.trace's canonical value domain, and every section's
  closed nested shape/type/id -- e.g. that `:sqlite-sites`/`:ffi-routes`
  entries carry well-formed fixture-owned ids, that `:resource-id` is either
  nil or an exact `[domain ordinal]` pair whose domain matches its route's
  SQLite symbol, that `:posix-fault` history/snapshot entries carry exactly
  the fields `jolt.sim.fault/step` and `jolt.sim.net.posix-fault/snapshot`
  produce, and that every `?`-suffixed field is a closed boolean. There is no
  cached `:verdicts` section -- every derived fact a caller needs is
  recomputed directly by the monitors below from the sections here, never
  trusted from a redundant precomputed field in the document itself.

  This check is deliberately shape-only: it never correlates one section
  against another (e.g. whether a `:resource-id` is currently active, whether
  the fault history is internally coherent, whether the response matches the
  fixture). Those are monitor invariants -- see
  `check-handler-only-cleanup-safety` and
  `check-bounded-request-completes-after-retry` -- so a well-shaped document
  that is semantically wrong returns a monitor `:violation` instead of
  throwing here.

  Returns `doc` unchanged; throws a typed ex-info on any shape violation."
  [doc]
  (when-not (map? doc)
    (invalid! :not-a-map {:value-class (str (class doc))}))
  (when-not (= document-keys (set (keys doc)))
    (invalid! :wrong-keys {:keys (set (keys doc))}))
  (when-not (= schema (:jolt.sim.evidence.http-sqlite/schema doc))
    (invalid! :wrong-schema {:schema (:jolt.sim.evidence.http-sqlite/schema doc)}))
  (when-not (= evidence-version (:jolt.sim.evidence.http-sqlite/version doc))
    (invalid! :unsupported-version
              {:version (:jolt.sim.evidence.http-sqlite/version doc)}))
  (trace/canonical-value doc)
  (when-not (= hermetic-assumptions
               (:jolt.sim.evidence.http-sqlite/assumptions doc))
    (invalid! :invalid-assumptions
              {:assumptions (:jolt.sim.evidence.http-sqlite/assumptions doc)}))
  (validate-sqlite-sites! (:jolt.sim.evidence.http-sqlite/sqlite-sites doc))
  (validate-ffi-routes! (:jolt.sim.evidence.http-sqlite/ffi-routes doc))
  (validate-posix-fault! (:jolt.sim.evidence.http-sqlite/posix-fault doc))
  (validate-posix-capacity! (:jolt.sim.evidence.http-sqlite/posix-capacity doc))
  (validate-sqlite-resources!
   (:jolt.sim.evidence.http-sqlite/sqlite-resources doc))
  (validate-posix-resources!
   (:jolt.sim.evidence.http-sqlite/posix-resources doc))
  (validate-response! (:jolt.sim.evidence.http-sqlite/response doc))
  doc)

(defn build-evidence
  "Builds and validates the canonical :jolt.sim/http-sqlite-evidence-v1
  document from one completed jolt.sim.runtime/run-controlled result plus the
  shared SQLite/POSIX-fault worlds that drove it. Must be called only after
  the controlled run has returned successfully; this function performs no
  further FFI, scheduling, or I/O of its own.

  `input` is a map:
  * `:controlled`      the full jolt.sim.runtime/run-controlled result;
  * `:sqlite-world`     the jolt.sim.sqlite world;
  * `:posix-world`      the jolt.sim.net.posix-loopback world;
  * `:fault-frontend`   the jolt.sim.net.posix-fault frontend;
  * `:statement-plans`  the exact ordered plan vector passed to
                        jolt.sim.sqlite/world, used only to emit fixture-owned
                        :sqlite-statement site records."
  [{:keys [controlled sqlite-world posix-world fault-frontend statement-plans]}]
  (let [parsed (get-in controlled [:result :parsed])
        routes (normalize-routes (:effect-trace controlled))
        sqlite-resources (sqlite-resource-evidence sqlite-world)
        posix-resources (posix-resource-evidence posix-world)
        fault (posix-fault-evidence fault-frontend)
        response (response-evidence parsed)]
    (validate-document!
     {:jolt.sim.evidence.http-sqlite/schema schema
      :jolt.sim.evidence.http-sqlite/version evidence-version
      :jolt.sim.evidence.http-sqlite/assumptions hermetic-assumptions
      :jolt.sim.evidence.http-sqlite/sqlite-sites (statement-sites statement-plans)
      :jolt.sim.evidence.http-sqlite/ffi-routes routes
      :jolt.sim.evidence.http-sqlite/posix-fault fault
      :jolt.sim.evidence.http-sqlite/posix-capacity (posix-capacity-evidence posix-world)
      :jolt.sim.evidence.http-sqlite/sqlite-resources sqlite-resources
      :jolt.sim.evidence.http-sqlite/posix-resources posix-resources
      :jolt.sim.evidence.http-sqlite/response response})))

(defn canonical-edn
  "Returns byte-stable, directly readable EDN for an evidence-v1 document.
  Validates the document's closed nested shape first (`validate-document!`),
  then thin call-throughs to jolt.sim.trace/canonical-edn, which validates the
  whole value tree before printing."
  [doc]
  (validate-document! doc)
  (trace/canonical-edn doc))

;; ---- shared monitor helper: full resource retirement -----------------------

(defn- retirement-violation
  "Pure helper shared by both monitors below. Independently recomputes --
  never trusting a cached `:clean?` boolean alone -- exact SQLite plan
  consistency (`:plan-index` = `:plan-count`), zero open SQLite
  connections/statements, zero open POSIX sockets/pipes/listeners/addrinfo
  allocations/waiters, and both worlds' own `:clean?` flags. Returns nil when
  every fact holds, or a `{:reason ... }` detail map naming the first failing
  fact in the same fixed check order both monitors already used."
  [sqlite-resources posix-resources]
  (cond
    (not= (:plan-index sqlite-resources) (:plan-count sqlite-resources))
    {:reason :sqlite-plans-not-consumed :sqlite-resources sqlite-resources}

    (not (zero? (:open-connections sqlite-resources)))
    {:reason :sqlite-connections-open :sqlite-resources sqlite-resources}

    (not (zero? (:open-statements sqlite-resources)))
    {:reason :sqlite-statements-open :sqlite-resources sqlite-resources}

    (not (:clean? sqlite-resources))
    {:reason :sqlite-not-clean :sqlite-resources sqlite-resources}

    (not (every? zero? [(:open-sockets posix-resources)
                         (:open-pipes posix-resources)
                         (:open-listeners posix-resources)
                         (:open-addrinfo posix-resources)
                         (:waiter-count posix-resources)]))
    {:reason :posix-resources-open :posix-resources posix-resources}

    (not (:clean? posix-resources))
    {:reason :posix-not-clean :posix-resources posix-resources}

    :else nil))

;; ---- safety monitor: handler-only-cleanup ---------------------------------

(def handler-only-cleanup-safety-id
  :jolt.sim.evidence.http-sqlite/handler-only-cleanup-safety)

(defn check-handler-only-cleanup-safety
  "Pure post-hoc safety monitor over an evidence-v1 document. Validates the
  document's closed nested shape first (`validate-document!`).

  Folds `:ffi-routes` in evidence order (never trusting the document, only
  what each route entry's :symbol/:route/:resource-id independently implies)
  and tracks the single active resource id per SQLite lifecycle domain
  (:sqlite-statement/:sqlite-connection). It checks:

  * every route's :route is :handler -- nothing ever reached native code or
    was blocked;
  * an opening call (`sqlite3_prepare_v2`/`sqlite3_open`) never occurs while
    its domain already has an active resource (no overlapping lifecycle);
  * an opening call's resource-id ordinal is exactly contiguous with every
    earlier opening call in the same domain (`(inc previous-open-count)`) --
    this subsumes and generalizes a narrower no-reused-id check: a reused
    ordinal and a skipped-ahead ordinal are both non-contiguous;
  * a terminal call (`sqlite3_finalize`/`sqlite3_close_v2`) never occurs
    without a currently active resource in its domain (narrowly
    :terminal-without-open -- this is a claim about the terminal call itself,
    not a general use-after-terminal claim about intermediate SQLite calls,
    which this evidence does not correlate to resource identity); and
  * a terminal call's :resource-id exactly matches its domain's active id
    (no :terminal-id-mismatch).

  Once the fold completes without an earlier violation, it independently
  recomputes -- via the shared [[retirement-violation]] helper, never
  trusting a cached document field or a `:clean?` boolean alone -- every
  final safety requirement: no domain left with an active (unterminated)
  resource id; exact SQLite plan consistency, zero open SQLite
  connections/statements, zero open POSIX sockets/pipes/listeners/addrinfo
  allocations/waiters, and both worlds' own `:clean?` flags (an aggregate
  world-truth check -- this monitor does not itself correlate individual
  POSIX resource ids; see the namespace docstring); that `:sqlite-sites`'
  count equals the SQLite plan count (`:plan-count`); that the number of
  observed statement openings equals both of those counts (one lifecycle per
  consumed plan/site); that this fixed scenario's required connection and
  statement resource lifecycles are each nonvacuously observed at least once
  (not merely absent-and-therefore vacuously well-formed); an HTTP 200 status
  with the exact
  `application/octet-stream` content type and matching content-length; and
  the response body is exactly the fixture's expected octets
  #[0 65 127 128 255].

  Returns `{:id handler-only-cleanup-safety-id :status (:pass|:violation)
  :detail ... :index i}`, where `:index` is the :ffi-routes index of the
  shortest offending prefix, or nil when the violation (or the pass) is only
  decidable once the whole route list has been folded."
  [doc]
  (validate-document! doc)
  (let [routes (:jolt.sim.evidence.http-sqlite/ffi-routes doc)
        sites (:jolt.sim.evidence.http-sqlite/sqlite-sites doc)
        response (:jolt.sim.evidence.http-sqlite/response doc)
        sqlite-resources (:jolt.sim.evidence.http-sqlite/sqlite-resources doc)
        posix-resources (:jolt.sim.evidence.http-sqlite/posix-resources doc)]
    (loop [index 0
           active {}
           open-counts {}
           remaining routes]
      (if (empty? remaining)
        (let [retirement (retirement-violation sqlite-resources posix-resources)]
          (cond
            (seq active)
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :resource-not-terminated :active active}}

            (some? retirement)
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail retirement}

            (not= (count sites) (:plan-count sqlite-resources))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :sqlite-site-count-mismatches-plan-count
                       :site-count (count sites)
                       :plan-count (:plan-count sqlite-resources)}}

            (zero? (get open-counts :sqlite-connection 0))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :no-sqlite-connection-lifecycle}}

            (zero? (get open-counts :sqlite-statement 0))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :no-sqlite-statement-lifecycle}}

            (not= (get open-counts :sqlite-statement 0)
                  (:plan-count sqlite-resources))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :sqlite-statement-lifecycle-count-mismatch
                      :statement-open-count
                      (get open-counts :sqlite-statement 0)
                      :site-count (count sites)
                      :plan-count (:plan-count sqlite-resources)}}

            (not= 200 (:status response))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :unexpected-status :actual (:status response)}}

            (not= "application/octet-stream" (:content-type response))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :unexpected-content-type
                       :actual (:content-type response)}}

            (not= (str (count expected-response-bytes)) (:content-length response))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :unexpected-content-length
                       :actual (:content-length response)}}

            (not= expected-response-bytes (:body-bytes response))
            {:id handler-only-cleanup-safety-id :status :violation :index nil
             :detail {:reason :unexpected-response-bytes
                       :actual (:body-bytes response)}}

            :else
            {:id handler-only-cleanup-safety-id :status :pass :index nil
             :detail nil}))
        (let [entry (first remaining)
              symbol (:symbol entry)
              domain (resource-domain symbol)
              resource-id (:resource-id entry)
              ordinal (when resource-id (nth resource-id 1))
              opening? (and domain (open-symbol? symbol))
              expected-open-ordinal (inc (get open-counts domain 0))]
          (cond
            (not= :handler (:route entry))
            {:id handler-only-cleanup-safety-id :status :violation :index index
             :detail {:reason :non-handler-route :entry entry}}

            (and opening? (contains? active domain))
            {:id handler-only-cleanup-safety-id :status :violation :index index
             :detail {:reason :overlapping-resource-lifecycle :entry entry
                       :active-id (get active domain)}}

            (and opening? (not= expected-open-ordinal ordinal))
            {:id handler-only-cleanup-safety-id :status :violation :index index
             :detail {:reason :non-contiguous-resource-open-ordinal :entry entry
                       :expected-ordinal expected-open-ordinal}}

            (and domain (terminal-symbol? symbol) (not (contains? active domain)))
            {:id handler-only-cleanup-safety-id :status :violation :index index
             :detail {:reason :terminal-without-open :entry entry}}

            (and domain (terminal-symbol? symbol)
                 (not= (get active domain) ordinal))
            {:id handler-only-cleanup-safety-id :status :violation :index index
             :detail {:reason :terminal-id-mismatch :entry entry
                       :active-id (get active domain)}}

            :else
            (recur (inc index)
                   (cond
                     opening? (assoc active domain ordinal)
                     (and domain (terminal-symbol? symbol)) (dissoc active domain)
                     :else active)
                   (if opening?
                     (assoc open-counts domain expected-open-ordinal)
                     open-counts)
                   (rest remaining))))))))

;; ---- liveness monitor: bounded-request-completes-after-retry --------------

(def bounded-request-completes-after-retry-id
  :jolt.sim.evidence.http-sqlite/bounded-request-completes-after-retry)

(def ^:private expected-fault-rule-id :http-sqlite/interrupt-first-poll)
(defn- poll-attempt-id [index]
  [:jolt.sim.net.posix-fault/poll (inc index)])

(defn- expected-history-match [index]
  ;; The rule's matcher is {:boundary :posix :operation :poll}, and every
  ;; poll attempt this frontend builds carries exactly that boundary and
  ;; operation, so the rule matches every single attempt in the run -- not
  ;; only the first. Its match ordinal therefore increments once per history
  ;; entry (1, 2, 3, ...); only the very first attempt is also activated and
  ;; fired, since :times 1 exhausts the rule after that.
  [{:rule-id expected-fault-rule-id
    :match-ordinal (inc index)
    :activated? (zero? index)
    :fired? (zero? index)}])

(defn- expected-history-firing []
  {:rule-id expected-fault-rule-id
   :match-ordinal 1
   :firing-ordinal 1
   :rule-firing-ordinal 1
   :outcome {:kind :captured-error :errno :eintr}})

(defn- expected-history-reason [index]
  (cond
    (zero? index) :expected-first-poll-eintr-not-observed
    (= 1 index) :expected-delegated-retry-not-observed
    :else :unexpected-additional-firing))

(defn check-bounded-request-completes-after-retry
  "Pure post-hoc bounded liveness monitor over an evidence-v1 document.
  Validates the document's closed nested shape first (`validate-document!`).

  Under the explicit assumptions that (a) the fault plan injects exactly one
  captured EINTR at the very first poll attempt (rule id
  :http-sqlite/interrupt-first-poll), (b) POSIX stream/pipe capacity is
  finite, and (c) the harness that produced this evidence enforces its own
  external deadline (the `:jolt.sim.evidence.http-sqlite.assumption/external-test-deadline`
  entry in `:assumptions`; teensyp.client's :timeout-ms plus run-controlled's
  :drain-timeout-ms) -- so a non-completing run never reaches evidence
  construction in the first place -- this folds `:posix-fault`'s `:history` in
  evidence order, requiring attempt ids to be exactly the contiguous
  sequence `[:jolt.sim.net.posix-fault/poll 1]`, `... 2`, ..., `... N`, and
  checking every history entry's own `:matches`/`:firing` shape and content
  against exactly what the one accepted rule (id
  :http-sqlite/interrupt-first-poll, matching every poll attempt) would
  produce: the very first attempt captures exactly `{:kind :captured-error
  :errno :eintr}` at match/firing/rule-firing ordinal 1; every later attempt
  matches again (its match ordinal keeps incrementing) but is never
  activated or fired again, so no later history entry fires. This witnesses
  that the fault fired on the global first poll attempt and that the very
  next global poll attempt (by frontend-owned ordinal) delegated without
  firing; it is evidence about global poll-attempt ordinals, not proof that
  the second attempt is a retry of the *same logical call* the first attempt
  interrupted -- this evidence does not correlate poll attempts to a
  higher-level request/connection identity.

  Once the fold completes without an earlier violation, it independently
  recomputes -- rather than trusts any cached document field -- that
  `:attempts`, the snapshot's `:next-attempt`/`:firings` are coherent with
  the actual history; that the fault plan's snapshot carries exactly one
  rule, with id :http-sqlite/interrupt-first-poll and exact activation
  `:on-match 1 :times 1`, whose own snapshot `:firings` is 1 and whose own
  snapshot `:matches` equals the history length (since the rule matches
  every attempt); that the stream and self-pipe capacities are finite
  positive integers; and, via the shared [[retirement-violation]] helper, that
  the run completed
  with an HTTP 200 response and a fully retired SQLite/POSIX resource state.
  `validate-document!` has already required the complete closed assumption
  vector, including the external harness deadline, before this monitor runs;
  the monitor does not pretend that a missing structural assumption is a
  semantic trace violation.

  This makes NO fairness or deadlock claim, and no claim about the temporal
  order of completion versus cleanup beyond what this one bounded,
  already-completed evidence trace records after the fact. It does not show
  that every schedule interleaving retries successfully, that a retry always
  happens within any general step bound, or characterize unbounded liveness
  or non-termination: a hung run simply never produces an evidence document
  for this monitor to examine, and this monitor only assembles a completed
  bounded witness from evidence already gathered after the run finished.

  Returns `{:id bounded-request-completes-after-retry-id :status
  (:pass|:violation) :detail ... :index i}`, where `:index` is the `:history`
  index of the shortest offending prefix, or nil when the violation (or pass)
  is only decidable once the whole history has been folded."
  [doc]
  (validate-document! doc)
  (let [fault (:jolt.sim.evidence.http-sqlite/posix-fault doc)
        history (:history fault)
        sqlite-resources (:jolt.sim.evidence.http-sqlite/sqlite-resources doc)
        posix-resources (:jolt.sim.evidence.http-sqlite/posix-resources doc)
        response (:jolt.sim.evidence.http-sqlite/response doc)
        capacity (:jolt.sim.evidence.http-sqlite/posix-capacity doc)
        rules (get-in fault [:snapshot :rules])
        rule (first rules)]
    (loop [index 0
           remaining history]
      (if (empty? remaining)
        (let [retirement (retirement-violation sqlite-resources posix-resources)]
          (cond
            (< (count history) 2)
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :retry-not-observed :history-count (count history)}}

            (not (and (integer? (get-in capacity [:stream :stream-capacity]))
                       (pos? (get-in capacity [:stream :stream-capacity]))))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :stream-capacity-not-finite-positive
                       :capacity capacity}}

            (not (and (integer? (get-in capacity [:pipe :pipe-capacity]))
                       (pos? (get-in capacity [:pipe :pipe-capacity]))))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :pipe-capacity-not-finite-positive
                       :capacity capacity}}

            (not= (count history) (:attempts fault))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :attempt-count-incoherent
                       :attempts (:attempts fault) :history-count (count history)}}

            (not= (inc (count history)) (get-in fault [:snapshot :next-attempt]))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :snapshot-next-attempt-incoherent
                       :snapshot (:snapshot fault)}}

            (not= 1 (count rules))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :expected-rule-not-singleton :rules rules}}

            (not= expected-fault-rule-id (:rule-id rule))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :expected-rule-missing-from-snapshot :rule rule}}

            (not (and (= 1 (:on-match rule)) (= 1 (:times rule))))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :expected-rule-activation-incoherent :rule rule}}

            (not= 1 (get-in fault [:snapshot :firings]))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :snapshot-firings-incoherent
                       :snapshot (:snapshot fault)}}

            (not= 1 (:firings rule))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :rule-firing-count-incoherent :rule rule}}

            (not= (count history) (:matches rule))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :rule-match-count-incoherent :rule rule}}

            (not= 200 (:status response))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail {:reason :request-not-completed :response response}}

            (some? retirement)
            {:id bounded-request-completes-after-retry-id :status :violation
             :index nil
             :detail retirement}

            :else
            {:id bounded-request-completes-after-retry-id :status :pass
             :index nil :detail nil}))
        (let [entry (first remaining)
              expected-attempt-id (poll-attempt-id index)
              expected-firing (when (zero? index) (expected-history-firing))
              expected-matches (expected-history-match index)]
          (cond
            (not= expected-attempt-id (:attempt-id entry))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index index
             :detail {:reason (expected-history-reason index) :entry entry}}

            (not= expected-firing (:firing entry))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index index
             :detail {:reason (expected-history-reason index) :entry entry}}

            (not= expected-matches (:matches entry))
            {:id bounded-request-completes-after-retry-id :status :violation
             :index index
             :detail {:reason (expected-history-reason index) :entry entry}}

            :else
            (recur (inc index) (rest remaining))))))))
