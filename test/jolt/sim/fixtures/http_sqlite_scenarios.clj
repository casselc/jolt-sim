(ns jolt.sim.fixtures.http-sqlite-scenarios
  "Narrow scenario wrappers that run the UNCHANGED
  jolt.sim.fixtures.http-sqlite application under the shared hermetic POSIX
  loopback plus SQLite handler packs, parameterized by one canonical scenario
  input.

  This namespace knows nothing about Hegel or the process-explorer parent. A
  fresh worker resolves one scenario here by its namespaced symbol and drives it
  with one canonical input value naming the stream capacity, pipe capacity, an
  optional captured poll EINTR activation ordinal, and one closed FFI admission
  plan. The application, HTTP, TCP, net, DB, SQLite, and POSIX implementations
  are all reused unchanged from their existing namespaces; only the
  deterministic world-building glue that the in-process integration test already
  performs is re-parameterized here.

  Each scenario is a plain function carrying the same :jolt.sim/scenario and
  :jolt.sim/accepts-input markers defsim produces, so it satisfies the
  jolt.sim.explore-worker single-run protocol v2 without depending on the defsim
  macro: defsim's declared-config form cannot both build the input-dependent
  handler packs and publish world-owned cleanup evidence from one body, so this
  wrapper calls jolt.sim.runtime/run-controlled directly and returns one
  canonical evidence map (HTTP response projection plus route, cleanup,
  capacity, fault, and admission-plan/coordinator evidence) sized to catch a
  model bypass. A regression failure carries bounded case data because the
  caller's drawn input is the only per-case variable.

  The transaction section below owns scenario selection, exact SQLite plan
  construction, runtime handler composition, execution, and canonical
  evidence for the fixture's ordinary jdbc/atomic scenarios
  (:commit-visible and :rollback-invisible). It reuses the same composed
  memory + SQLite + POSIX handler packs and the same unchanged fixture
  lifecycle; transaction logic itself lives only in the pinned db.jdbc
  library and the closed :tx-effect/:store-effect plan directives."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.ffi-schedule :as ffi-schedule]
            [jolt.sim.fixtures.http-sqlite :as fixture]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private expected-blob-octets [0 65 127 128 255])

(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn- statement-plans
  "The four exact SQLite plans the unchanged fixture drives over an in-memory
  connection: PRAGMA foreign_keys=1 (run by db.sqlite connection init), create,
  insert, and select. Reused unchanged from the in-process integration test."
  []
  [{:sql "PRAGMA foreign_keys=1;"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "create table sim_blob (id integer primary key, payload blob)"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "insert into sim_blob (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 1}
             2 {:type :blob
                :value (byte-array expected-blob-octets)}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 1}
   {:sql "select payload from sim_blob where id = ?"
    :params {1 {:type :integer :value 1}}
    :columns ["payload"]
    :rows [[{:type :blob
             :value (byte-array expected-blob-octets)}]]
    :changes 0
    :last-row-id 1}])

(defn- interrupt-poll-plan
  "One captured-EINTR fault plan that fires exactly once on the poll call whose
  frontend-owned per-poll ordinal equals `ordinal`. Mirrors the in-process
  integration test's interrupt-first-poll plan with a parameterized
  :on-match activation."
  [ordinal]
  [{:id :http-sqlite/interrupt-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match ordinal :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

;; ---- FFI admission order over the existing composed handler packs --------

(def ^:private in-flight-drain-timeout-ms 5000)

(def ^:private poll-step-id ::poll)
(def ^:private connect-step-id ::connect)

;; Two causal admission plans over poll occurrence 1 and the captured
;; nonblocking connect occurrence 1. :poll-then-connect releases poll's gate
;; before connect's (the natural arrival order); :connect-then-poll releases
;; connect's gate first while poll's arrival remains parked at its gate. Both
;; name occurrence 1 of each handler key, so the coordinator's per-key counters
;; engage exactly one selected arrival each. This is admission order only: the
;; plan does not claim handler execution or completion order.
(def ^:private plan-orders
  {:poll-then-connect [poll-step-id connect-step-id]
   :connect-then-poll [connect-step-id poll-step-id]})

(defn- bounded-wait
  "Busy-polls pred until it is true or timeout-ms elapses. Returns whether pred
  was observed true; never blocks past the deadline."
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (< (System/nanoTime) deadline) (do (Thread/yield) (recur))
        :else false))))

(defn- admission-handler-keys
  "The exact canonical poll and nonblocking connect handler keys for the live
  target descriptor's nfds_t width. poll is [pointer nfds-type int] -> int,
  blocking, captured; the captured nonblocking connect is [int pointer uint]
  -> int, nonblocking, captured. Both keys exist in the composed POSIX pack,
  so the ffi-schedule coordinator validates them against the unchanged
  handlers rather than inventing new aliases."
  []
  (let [target (net/target-descriptor)]
    {:poll (hp/foreign-function-key
            "poll" [:pointer (:nfds-type target) :int] :int true true)
     :connect (hp/foreign-function-key
               "connect" [:int :pointer :uint] :int false true)}))

(defn- admission-plan-for
  "Returns the exact closed ffi-schedule step vector for `plan-key`, selecting
  occurrence 1 of poll and occurrence 1 of the nonblocking connect in the
  plan's release order."
  [plan-key poll-key connect-key]
  (let [handler-key-for {poll-step-id poll-key connect-step-id connect-key}]
    (mapv (fn [id] {:id id :handler-key (get handler-key-for id) :occurrence 1})
          (get plan-orders plan-key))))

(defn- admission-route-projection
  "Projects only the poll/connect effect-trace entries to {:symbol :route},
  proving every selected and unselected poll/connect call among them was served
  by its existing modeled handler (:route :handler) under hermetic routing."
  [effect-trace poll-key connect-key]
  (into []
        (comp (filter #(contains? #{poll-key connect-key} (:handler-key %)))
              (map (fn [entry]
                     {:symbol (:symbol (:descriptor entry))
                      :route (:route entry)})))
        effect-trace))

(defn- evidence-for
  "Builds the hermetic POSIX + SQLite handler packs from `input`, runs the
  unchanged fixture once under run-controlled, and returns one canonical
  evidence map: the ordinary HTTP response projection plus route, cleanup,
  capacity, fault, and (when an admission plan is drawn) coordinator evidence
  sufficient to catch a model bypass.

  When `input` carries a non-nil :admission-plan (one of :poll-then-connect or
  :connect-then-poll), the already-composed canonical handlers are wrapped by
  jolt.sim.ffi-schedule/coordinator over poll occurrence 1 and the captured
  nonblocking connect occurrence 1, in the plan's release order. The plan only
  orders admission (arrival gate release); it makes no handler execution or
  completion order claim, and both handler keys resolve against the unchanged
  composed packs. check-complete! runs only after exercise-http-sqlite returns
  -- and therefore after client close, server stop/join, and SQLite cleanup --
  so the coordinator's completeness check observes a quiesced application. On
  body failure the coordinator is aborted, in-flight selected calls are
  bounded-drained, and the original application exception is rethrown as the
  primary cause.

  `overrides` is the process-explorer worker's runtime-overrides map (empty for
  a no-schedule case; :future-schedule when supplied). It is merged over this
  wrapper's :ffi-handlers/:drain-timeout-ms config so the worker protocol's
  override path is retained."
  [overrides input]
  (let [{:keys [stream-capacity pipe-capacity
                poll-eintr-ordinal admission-plan]} input
        mem (memory/world)
        sqlite-world (sqlite/world mem (statement-plans))
        ;; The finite per-socket receive FIFO and self-pipe capacities are the
        ;; per-case variables; every HTTP octet crosses the capacity model.
        posix-world (posix/world mem (net/target-descriptor)
                                 {:stream-capacity stream-capacity
                                  :pipe-capacity pipe-capacity})
        fault? (some? poll-eintr-ordinal)
        fault-frontend (when fault?
                         (posix-fault/frontend posix-world
                                               (interrupt-poll-plan
                                                poll-eintr-ordinal)))
        ;; Three named packs over one shared memory world: memory + SQLite
        ;; foreign handlers, plus the POSIX foreign set (fault-interposed at
        ;; poll when an EINTR ordinal was drawn, plain otherwise). No plain
        ;; merge across complete packs.
        posix-foreign (if fault?
                        (posix-fault/foreign-handlers fault-frontend)
                        (posix/foreign-handlers posix-world))
        base-handlers (hp/compose
                       (hp/pack :jolt.sim/memory (memory/handlers mem))
                       (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
                       (hp/pack :jolt.sim/posix posix-foreign))
        admission? (some? admission-plan)
        {:keys [poll connect]} (when admission? (admission-handler-keys))
        ;; The coordinator wraps the unchanged composed handlers: keys not
        ;; named by the plan delegate unchanged, while the two named keys gain
        ;; a per-key occurrence gate released in exact plan order. No bespoke
        ;; socket/HTTP/scheduler implementation is introduced.
        coord (when admission?
                (ffi-schedule/coordinator
                 base-handlers
                 (admission-plan-for admission-plan poll connect)))
        effective-handlers (if admission? (:handlers coord) base-handlers)
        controlled (rt/run-controlled
                    (merge {:ffi-handlers effective-handlers
                            :drain-timeout-ms 10000}
                           overrides)
                    (fn []
                      (if-not admission?
                        (fixture/exercise-http-sqlite)
                        (try
                          (let [fixture-result (fixture/exercise-http-sqlite)]
                            ;; check-complete! runs only after the fixture
                            ;; returns, so client close, server stop/join, and
                            ;; SQLite cleanup precede the coordinator's
                            ;; completeness check over a quiesced application.
                            ((:check-complete! coord))
                            fixture-result)
                          (catch :default error
                            ((:abort! coord))
                            (bounded-wait
                             #(zero? (:in-flight ((:diagnostics coord))))
                             in-flight-drain-timeout-ms)
                            ;; Diagnostics only: this call's own failure or
                            ;; success must not replace the original body
                            ;; exception rethrown below.
                            (try ((:check-complete! coord))
                                 (catch :default _ nil))
                            (throw error))))))
        parsed (get-in controlled [:result :parsed])
        effect-trace (:effect-trace controlled)]
    (cond-> {:http {:status (:status parsed)
                    :content-type (get (:headers parsed) "content-type")
                    :content-length (get (:headers parsed) "content-length")
                    :raw-length (get-in controlled [:result :raw-length])
                    :server-errors (get-in controlled [:result :server-errors])
                    ;; vec over the byte-array body matches the in-process
                    ;; integration test's exact-octets projection and keeps the
                    ;; result canonical.
                    :body-octets (vec (:body parsed))}
             :routes {:count (count effect-trace)
                      :all-handled? (every? #(= :handler (:route %)) effect-trace)
                      :foreign-symbols (foreign-symbols effect-trace)}
             :sqlite (sqlite/summary sqlite-world)
             :capacity {:stream (posix/capacity-summary posix-world)
                        :pipe (posix/pipe-capacity-summary posix-world)}
             :fault (if fault?
                      (let [history (posix-fault/evidence-history fault-frontend)]
                        {:attempts (posix-fault/attempts fault-frontend)
                         :firings (:firings (posix-fault/snapshot fault-frontend))
                         :fired-attempts
                         (mapv #(select-keys % [:attempt-id :firing])
                               (filter :firing history))})
                      {:attempts 0
                       :firings 0
                       :fired-attempts []})
             :clean? {:memory (memory/clean? mem)
                      :sqlite (sqlite/clean? sqlite-world)
                      :posix (posix/clean? posix-world)}}
      admission?
      (assoc :admission
             {:plan admission-plan
              ;; Release evidence is plan-driven and therefore an exact replay
              ;; witness; arrival/completion order remain diagnostic only.
              :release-evidence ((:evidence coord))
              :coordinator-diagnostics ((:diagnostics coord))
              :routes (admission-route-projection effect-trace poll connect)}))))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-with-capacities
  "Runs the unchanged jolt-http + db.sqlite fixture once under the shared
  hermetic POSIX loopback plus SQLite handler packs, parameterized by one
  canonical input map:

    {:stream-capacity   pos-int
      :pipe-capacity     pos-int
      :poll-eintr-ordinal nil | pos-int
      :admission-plan    nil | :poll-then-connect | :connect-then-poll}

  Returns one canonical evidence map. A nil :poll-eintr-ordinal drives no fault
  frontend; a positive ordinal fires one captured EINTR on that per-poll
  attempt ordinal. A non-nil :admission-plan wraps the composed handlers with
  jolt.sim.ffi-schedule over poll occurrence 1 and the captured nonblocking
  connect occurrence 1, released in the plan's order; the evidence map then
  carries :admission with plan-derived release evidence, coordinator
  diagnostics, and a poll/connect route projection. Accepts the standard
  jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used by
  jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-with-capacities {} input))
  ([overrides input]
   (evidence-for overrides input)))

;; ---- Ordinary jdbc/atomic transaction scenarios ---------------------------

(def ^:private transaction-scenarios #{:commit-visible :rollback-invisible})

(defn- transaction-statement-plans
  "The six exact SQLite plans the unchanged transaction fixture drives for
   `scenario` over one in-memory connection: PRAGMA foreign_keys=1 (run by
   db.sqlite connection init), create, BEGIN, insert, COMMIT or ROLLBACK, and
   select. SQL text and parameter types/values are exactly what the pinned
   db.jdbc depth-0 sqlite atomic path and db.sqlite driver emit; nothing is
   inferred from SQL text here or by the model. BEGIN/COMMIT/ROLLBACK carry
   the closed :tx-effect physical boundary directive, the insert carries
   :store-effect :put over its bound key/value parameters, and the select
   carries :store-effect :get so the model derives the row from physical
   staged/committed visibility instead of serving a predeclared row."
  [scenario]
  [{:sql "PRAGMA foreign_keys=1;"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "create table sim_blob (id integer primary key, payload blob)"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "BEGIN"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0
    :tx-effect {:op :begin}}
   {:sql "insert into sim_blob (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 1}
             2 {:type :blob
                :value (byte-array expected-blob-octets)}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 1
    :store-effect {:op :put :key-param 1 :value-param 2}}
   (case scenario
     :commit-visible
     {:sql "COMMIT"
      :params {}
      :columns []
      :rows []
      :changes 0
      :last-row-id 0
      :tx-effect {:op :commit}}
     :rollback-invisible
     {:sql "ROLLBACK"
      :params {}
      :columns []
      :rows []
      :changes 0
      :last-row-id 0
      :tx-effect {:op :rollback}})
   {:sql "select payload from sim_blob where id = ?"
    :params {1 {:type :integer :value 1}}
    :columns ["payload"]
    :rows []
    :changes 0
    :last-row-id 1
    :store-effect {:op :get :key-param 1}}])

(defn- canonical-http-evidence
  "Projects one raw fixture result into canonical application evidence. The
   projection keeps exactly the response status, content type, content
   length, raw response length, server errors, and exact body octets.
   Host-only incidental fields are excluded by name: the ephemeral :port,
   :connection-info addresses, :sent-result/:close-results, the Date response
   header value, and the reason phrase never enter canonical evidence."
  [fixture-result]
  (let [parsed (:parsed fixture-result)]
    {:status (:status parsed)
     :content-type (get (:headers parsed) "content-type")
     :content-length (get (:headers parsed) "content-length")
     :raw-length (:raw-length fixture-result)
     :server-errors (:server-errors fixture-result)
     ;; vec over the byte-array body matches the existing exact-octets
     ;; projection and keeps the result canonical.
     :body-octets (vec (:body parsed))}))

(defn- closed-db-evidence
  "Projects each closed SQLite connection record to its address-free physical
   transaction/store boundary evidence."
  [sqlite-world]
  (mapv #(select-keys % [:autocommit? :tx :tx-evidence :autocommit-evidence
                         :committed :staging :store-evidence
                         :discarded-transaction :discarded-staging])
        (:closed-db-evidence (sqlite/state sqlite-world))))

(defn run-transaction-scenario
  "Runs the unchanged ordinary transaction fixture once for `scenario` (one of
   :commit-visible or :rollback-invisible) in `mode`:

   - :real executes
     jolt.sim.fixtures.http-sqlite/exercise-http-sqlite-transaction directly
     against host sockets and system SQLite and returns only the canonical
     application evidence.
   - :simulated executes the same fixture function unchanged under one
     controlled run whose POSIX loopback (one-byte stream and self-pipe
     capacities) and SQLite worlds share one FFI-memory heap, then adds
     route, plan-consumption, physical transaction/store, and cleanup
     evidence sized to catch a model bypass.

   The scenario and mode sets are closed; anything else fails before any
   world, server, or connection is created."
  [scenario mode]
  (when-not (contains? transaction-scenarios scenario)
    (throw (ex-info "unknown http-sqlite transaction scenario"
                    {:scenario scenario
                     :supported [:commit-visible :rollback-invisible]})))
  (case mode
    :real
    {:http (canonical-http-evidence
            (fixture/exercise-http-sqlite-transaction scenario))}

    :simulated
    (let [mem (memory/world)
          sqlite-world (sqlite/world mem
                                     (transaction-statement-plans scenario))
          ;; The same one-byte capacities as the BLOB hermetic run: every HTTP
          ;; octet still crosses the capacity model.
          posix-world (posix/world mem (net/target-descriptor)
                                   {:stream-capacity 1
                                    :pipe-capacity 1})
          ;; The same three named packs over one shared memory world: memory +
          ;; SQLite foreign handlers + the POSIX foreign set. No fault
          ;; frontend interposes in this slice.
          handlers (hp/compose
                    (hp/pack :jolt.sim/memory (memory/handlers mem))
                    (hp/pack :jolt.sim/sqlite
                             (sqlite/foreign-handlers sqlite-world))
                    (hp/pack :jolt.sim/posix
                             (posix/foreign-handlers posix-world)))
          controlled (rt/run-controlled
                      {:ffi-handlers handlers
                       :drain-timeout-ms 10000}
                      #(fixture/exercise-http-sqlite-transaction scenario))
          effect-trace (:effect-trace controlled)]
      {:http (canonical-http-evidence (:result controlled))
       :routes {:count (count effect-trace)
                :all-handled? (every? #(= :handler (:route %)) effect-trace)
                :foreign-symbols (foreign-symbols effect-trace)}
       :sqlite {:summary (sqlite/summary sqlite-world)
                :closed-db-evidence (closed-db-evidence sqlite-world)}
       :clean? {:memory (memory/clean? mem)
                :sqlite (sqlite/clean? sqlite-world)
                :posix (posix/clean? posix-world)}})

    (throw (ex-info "unknown http-sqlite transaction mode"
                    {:mode mode
                     :supported [:real :simulated]}))))
