(ns jolt.sim.fixtures.outbox-delivery-scenarios
  "Narrow scenario wrapper that runs the UNCHANGED
  jolt.sim.fixtures.outbox-delivery whole-application fixture under the shared
  hermetic SQLite plus POSIX loopback handler packs, parameterized by one
  canonical scenario input.

  This namespace knows nothing about Hegel or the process-explorer parent. A
  fresh worker resolves one scenario here by its namespaced symbol and drives
  it with one canonical input value naming the command payload octet vector,
  the stream capacity, the pipe capacity, and an optional captured poll EINTR
  activation ordinal. Request and entity IDs stay fixed at the fixture's
  default-command values for this slice. The application, HTTP, SQLite
  adapter, TCP, net, codec, and POSIX implementations are all reused
  unchanged; only the deterministic world-building glue is parameterized here,
  following the same shape as
  jolt.sim.fixtures.tcp-bencode-scenarios/exercise-with-capacities: validate
  the closed schema, build the hermetic worlds, run run-controlled directly,
  and project one canonical evidence map.

    Real/hermetic parity is intentionally not rerun per generated case: the
    dedicated close/reopen integration gate (:outbox-delivery-test) proves the
    canonical command through this same ordinary two-connection application
    path in both modes. The generated payload campaign remains hermetic-only.
    Every case additionally wraps the already composed canonical handlers with
    jolt.sim.ffi-schedule semantic selector steps over occurrence 1 of the
    receiver-poll role and occurrence 1 of the HTTP-poll role, in one of two
    drawn release orders (see the admission section below). Broader schedule
    search and extreme 1--2 byte HTTP fragmentation remain omitted: generated
    payload semantics, conservative capacity fragmentation, captured EINTR,
    and the two discriminating first-poll admission orders are the feature
    boundary.

    The exercise-retry-recv-reset scenario at the bottom of this namespace is
    the two-attempt at-least-once companion: it drives the UNCHANGED
    jolt.sim.fixtures.outbox-delivery/exercise-outbox-delivery-retry
    application under the same hermetic packs, a posix-fault plan whose scoped
    recv rule fires one captured ECONNRESET on the delivery client's first
    recv, and a two-step semantic ffi-schedule gate that admits the receiver's
    first ack-send boundary before the delivery client's first recv boundary.
    This proves receiver processing reached its reply boundary before reset;
    admission does not order the original handler executions or claim that ack
    bytes were already queued."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.ffi-schedule :as ffi-schedule]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private scenario-input-keys
  #{:payload :stream-capacity :pipe-capacity :poll-eintr-ordinal
    :admission-plan})

;; Conservative bounds: the stream floor 8 is the fixed smoke capacity already
;; proven against the fixture's 5 second client deadline; 1--2 byte HTTP
;; fragmentation is explicitly later work. The pipe floor 1 is the smoke
;; self-pipe bound. The closed domains deliberately mirror the Hegel lane's
;; generator; both sides reject anything outside them before a world exists.
(def ^:private max-payload-octets 32)
(def ^:private supported-stream-capacities #{8 16 32})
(def ^:private supported-pipe-capacities #{1 2 4})
(def ^:private supported-poll-eintr-ordinals #{nil 1 2 4 8})
;; Exactly two discriminating first-poll admission orders: the receiver
;; reactor's first poll released before the HTTP reactor's first poll, or the
;; reverse. There is deliberately no nil/unscheduled value: every case in this
;; lane exercises the semantic-selector coordinator.
(def ^:private supported-admission-plans
  #{:receiver-poll-then-http-poll :http-poll-then-receiver-poll})

;; The bare filename selected by the hermetic SQLite file-image substrate for
;; the ordinary close/reopen lane. Only the ordinary lane opts this exact
;; filename into the persistent file-image substrate; the retry lane keeps its
;; same-open in-memory connection and is untouched here. This is a fixed
;; scenario-side constant, not part of the closed generated input schema.
(def ^:private outbox-db-filename "outbox.db")

(defn- invalid-scenario-input [reason data]
  (ex-info
   "invalid outbox-delivery scenario input"
   (merge {:type :jolt.sim.fixtures.outbox-delivery-scenarios/invalid-input
           :reason reason}
          data)))

(defn- octet? [value]
  (and (integer? value) (<= 0 value 255)))

(defn- validate-scenario-input!
  "Validates the closed generated-scenario schema before any real socket,
   SQLite handle, or simulated world is created. The capacity and EINTR
   ranges are deliberately the same bounded domains exercised by this
   scenario's Hegel lane; the payload is capped at that lane's octet-vector
   generator maximum."
  [input]
  (when-not (map? input)
    (throw (invalid-scenario-input :not-a-map {:input input})))
  (let [actual-keys (set (keys input))
        unknown (seq (sort-by pr-str
                              (remove scenario-input-keys actual-keys)))
        missing (seq (sort-by pr-str
                              (remove actual-keys scenario-input-keys)))]
    (when unknown
      (throw (invalid-scenario-input
              :unknown-keys
              {:unknown-keys (vec unknown) :input input})))
    (when missing
      (throw (invalid-scenario-input
              :missing-keys
              {:missing-keys (vec missing) :input input}))))
  (let [payload (:payload input)]
    (when-not (vector? payload)
      (throw (invalid-scenario-input
              :invalid-payload
              {:value payload})))
    (when (> (count payload) max-payload-octets)
      (throw (invalid-scenario-input
              :payload-too-long
              {:length (count payload)
               :max-length max-payload-octets})))
    (when-not (every? octet? payload)
      (throw (invalid-scenario-input
              :invalid-payload-octet
              {:value payload}))))
  (when-not (contains? supported-stream-capacities (:stream-capacity input))
    (throw (invalid-scenario-input
            :invalid-stream-capacity
            {:value (:stream-capacity input)
             :supported (vec (sort supported-stream-capacities))})))
  (when-not (contains? supported-pipe-capacities (:pipe-capacity input))
    (throw (invalid-scenario-input
            :invalid-pipe-capacity
            {:value (:pipe-capacity input)
             :supported (vec (sort supported-pipe-capacities))})))
  (when-not (contains? supported-poll-eintr-ordinals
                        (:poll-eintr-ordinal input))
    (throw (invalid-scenario-input
            :invalid-poll-eintr-ordinal
            {:value (:poll-eintr-ordinal input)
             :supported [nil 1 2 4 8]})))
  (when-not (contains? supported-admission-plans (:admission-plan input))
    (throw (invalid-scenario-input
            :invalid-admission-plan
            {:value (:admission-plan input)
             :supported [:receiver-poll-then-http-poll
                         :http-poll-then-receiver-poll]})))
  input)

(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn- effect-foreign-symbol
  "Returns the foreign-function symbol for one effect-trace entry, or nil when
   the entry is not a foreign-function call. Used by the persistence projection
   to count sqlite3_open / sqlite3_close_v2 entries directly from the
   controlled effect trace."
  [entry]
  (let [descriptor (:descriptor entry)]
    (when (= :foreign-function (:kind descriptor))
      (:symbol descriptor))))

(defn- interrupt-poll-plan
  "One captured-EINTR fault plan that fires exactly once on the poll call
   whose frontend-owned per-poll ordinal equals `ordinal`."
  [ordinal]
  [{:id :outbox-delivery/interrupt-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match ordinal :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

;; ---- Semantic first-poll admission over the composed handler packs -------
;;
;; Both servers in the unchanged application -- the delivery receiver
;; (teensyp.server) and the HTTP server (jolt-http, itself built on
;; teensyp.server) -- run one readiness reactor each, and every reactor waits
;; through the ONE canonical poll handler key shared with the fixture's
;; connect/read/write client pollers. Global poll occurrence counting cannot
;; address one reactor's first poll, so the plan below uses ffi-schedule
;; semantic :selector steps: one classifier labels each poll invocation from
;; stable modeled resource facts, and occurrences are counted per
;; [handler-key selector].
;;
;; Stable classification invariant. The unchanged fixture creates exactly two
;; modeled listening sockets in ordinary, sequential application code order:
;; the receiver listener first, the HTTP listener second. bind(2) on port 0
;; claims the world's deterministic ephemeral ports in that same order, so the
;; receiver listener is always :ephemeral-base and the HTTP listener is always
;; :ephemeral-base + 1; no client bind can interleave because no client exists
;; before both listeners are bound, and each reactor registers only its own
;; listener (accepted connections join its poll set later without changing the
;; label). A poll(2) call is therefore classified by the modeled local-port of
;; the listening socket in its own decoded pollfd set: :role/receiver when the
;; set contains exactly one listening socket and its local-port is the
;; receiver's, :role/http likewise for the HTTP listener, and nil for every
;; other call (client connect/read/write pollers never carry a listening
;; socket). The label of one call is a pure function of that call's exact
;; invocation descriptor and the modeled world state -- never the raw global
;; poll occurrence, poll arrival order, task/thread identity or name, or any
;; incidental host identity. Any drift in the invariant (a missing or extra
;; listener, a rebound port, a poll set with two listeners) yields nil labels,
;; the selected occurrences never arrive, and check-complete! fails the case
;; loudly as an incomplete schedule instead of silently misclassifying.

(def ^:private in-flight-drain-timeout-ms 5000)

(def ^:private receiver-poll-step-id ::receiver-poll)
(def ^:private http-poll-step-id ::http-poll)

(def ^:private selector-by-step-id
  {receiver-poll-step-id :role/receiver
   http-poll-step-id :role/http})

;; The two discriminating first-poll release orders. This is admission order
;; only: the plan makes no handler execution or completion order claim.
(def ^:private plan-orders
  {:receiver-poll-then-http-poll [receiver-poll-step-id http-poll-step-id]
   :http-poll-then-receiver-poll [http-poll-step-id receiver-poll-step-id]})

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

(defn- poll-handler-key
  "The exact canonical poll handler key for the live target descriptor's
   nfds_t width: [pointer nfds-type int] -> int, blocking, captured. The key
   exists in the composed POSIX pack (plain or fault-interposed), so the
   ffi-schedule coordinator validates against the unchanged handlers rather
   than inventing new aliases."
  [target]
  (hp/foreign-function-key
   "poll" [:pointer (:nfds-type target) :int] :int true true))

(defn- mem-scalar-reader
  "Returns a read-only scalar reader over the shared FFI memory world through
   its public native-operation handler contract -- the same descriptor idiom
   jolt.sim.sqlite's hybrid seam and the loopback world itself use. The
   poll-role classifier uses it to decode pollfd entries; it never writes."
  [memory-world]
  (let [h (get (memory/handlers memory-world) [:native-operation :read])]
    (fn [ptr type offset]
      (h {:kind :native-operation :task 0 :arguments [ptr type offset]
          :operation :read}))))

(defn- poll-role-classifier
  "Builds the ffi-schedule poll classifier and its bounded label-count evidence
   atom. The classifier decodes the invocation's own pollfd array from the
   exact descriptor through the shared memory world (read-only), resolves each
   fd against the live modeled world state, and returns :role/receiver,
   :role/http, or nil per the stable classification invariant above. It never
   mutates the world, never parks, and is total: any descriptor it cannot
   classify is simply unlabeled and delegates unchanged. The counts atom
   carries three integer counters (:role/receiver, :role/http, :unlabeled) so
   the parent can prove both roles were exercised and unlabeled polls
   delegated; it is scenario-side observation, not model state."
  [posix-world memory-world]
  (let [read (mem-scalar-reader memory-world)
        {:keys [size fd]} (get-in posix-world [:target :layout :pollfd])
        receiver-port (:ephemeral-base (:config posix-world))
        http-port (inc receiver-port)
        counts (atom {:role/receiver 0 :role/http 0 :unlabeled 0})]
    {:classifier
     (fn [descriptor]
       (let [[buf n _timeout-ms] (vec (:arguments descriptor))
             state (posix/state posix-world)
             sockets (:sockets state)
             fds (mapv (fn [i] (read (+ buf (* i size)) :int fd))
                       (range n))
             listener-ports
             (into []
                   (comp (filter (every-pred integer? #(not (neg? %))))
                         (keep (fn [fd-value]
                                 (when-let [s (get sockets fd-value)]
                                   (when (= :listening (:state s))
                                     (:local-port s))))))
                   fds)
             label (when (= 1 (count listener-ports))
                     (let [port (nth listener-ports 0)]
                       (cond
                         (= port receiver-port) :role/receiver
                         (= port http-port) :role/http
                         :else nil)))]
         (swap! counts update (or label :unlabeled) inc)
         label))
     :counts counts}))

(defn- admission-plan-for
  "Returns the exact closed semantic ffi-schedule step vector for `plan-key`:
   occurrence 1 of the receiver-poll role and occurrence 1 of the HTTP-poll
   role on the one shared poll handler key, in the plan's release order. Both
   steps use :selector, so occurrence counting is per [handler-key selector]
   and interleaved unlabeled or client polls cannot perturb either count."
  [plan-key poll-key]
  (mapv (fn [id] {:id id
                  :handler-key poll-key
                  :selector (get selector-by-step-id id)
                  :occurrence 1})
        (get plan-orders plan-key)))

(defn- poll-route-summary
  "Returns constant-size evidence for the poll effect-trace entries, proving
   every selected and unselected poll call among them was served by its
   existing modeled handler (:route :handler) under hermetic routing. The
   summary deliberately does not copy one record per poll across the worker
   boundary, so a poll storm cannot inflate result.edn a second time."
  [effect-trace poll-key]
  (let [poll-entries (filter #(= poll-key (:handler-key %)) effect-trace)]
    {:count (count poll-entries)
     :all-handled? (every? #(= :handler (:route %)) poll-entries)}))

(defn- evidence-for
  "Builds the hermetic SQLite and POSIX handler packs from `input`, wraps the
   already composed canonical handlers with jolt.sim.ffi-schedule semantic
   selector steps for the drawn first-poll admission plan, runs the unchanged
   outbox-delivery-reopen fixture (a clean two-connection close/reopen of one
   selected file-backed SQLite filename) once under run-controlled, and
   returns one canonical evidence map: the ordinary application/HTTP/receiver
   projections plus route, SQLite, capacity, fault, admission, persistence,
   and cleanup evidence sufficient to catch a model bypass. The command is
   the fixture's default-command with its :payload rebound to the drawn octet
   vector; the exact SQLite plan world is built from the reopen-delivery
   statement plans parameterized with the same payload and selects the bare
   outbox.db filename into the file-image substrate, so plan binds and
   application binds cannot diverge.

   The coordinator wraps only the one canonical poll handler key with two
   semantic :selector steps (occurrence 1 of each reactor's poll role, in the
   plan's release order); every other key and every unlabeled or client poll
   delegates to the original handler unchanged. check-complete! runs only
   after exercise-outbox-delivery-reopen returns -- and therefore after HTTP
   client close, HTTP server stop/join, both SQLite connection close/reopen
   boundaries, delivery close, and receiver stop -- so the coordinator's
   completeness check observes a quiesced application. On body failure the
   coordinator is aborted, the wrapper waits up to one bounded deadline for
   selected calls to drain, records bounded coordinator diagnostics, and
   rethrows the original application exception as the primary cause. Final
   lifecycle drainage remains run-controlled's responsibility.

   `overrides` is the process-explorer worker's runtime-overrides map (empty
   for a no-schedule case; :future-schedule when supplied). It is merged over
   this wrapper's :ffi-handlers/:drain-timeout-ms config so the worker
   protocol's override path is retained."
  [overrides input]
  (let [{:keys [payload stream-capacity pipe-capacity poll-eintr-ordinal
                admission-plan]}
        input
        command (assoc fixture/default-command :payload payload)
        mem (memory/world)
        target (net/target-descriptor)
        sqlite-world
        (sqlite/world mem (plans/reopen-delivery-statement-plans payload)
                       {:persistent-filenames #{outbox-db-filename}})
        ;; The 64-octet native progress ceiling and the finite per-socket
        ;; receive FIFO and self-pipe capacities are the per-case variables;
        ;; every framed octet crosses the capacity model.
        posix-world
        (posix/world mem target
                     {:progress-limit 64
                      :stream-capacity stream-capacity
                      :pipe-capacity pipe-capacity})
        fault? (some? poll-eintr-ordinal)
        fault-frontend
        (when fault?
          (posix-fault/frontend posix-world
                                (interrupt-poll-plan poll-eintr-ordinal)))
        ;; Three named packs over one shared memory world: memory handlers,
        ;; the exact-plan SQLite foreign set, and the POSIX foreign set
        ;; (fault-interposed at poll when an EINTR ordinal was drawn, plain
        ;; otherwise). No plain merge across packs.
        posix-foreign
        (if fault?
          (posix-fault/foreign-handlers fault-frontend)
          (posix/foreign-handlers posix-world))
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix posix-foreign))
        poll-key (poll-handler-key target)
        {:keys [classifier counts]} (poll-role-classifier posix-world mem)
        ;; The coordinator wraps the unchanged composed handlers: keys not
        ;; named by the plan delegate unchanged, while the one poll key gains
        ;; per-role occurrence gates released in exact plan order. No bespoke
        ;; socket/HTTP/scheduler implementation is introduced.
        coord
        (ffi-schedule/coordinator
         handlers
         (admission-plan-for admission-plan poll-key)
         {:classifiers {poll-key classifier}})
        diagnostics (:diagnostics coord)
        controlled
        (rt/run-controlled
         (merge {:ffi-handlers (:handlers coord) :drain-timeout-ms 10000}
                overrides)
         (fn []
           (try
             (let [fixture-result
                   (fixture/exercise-outbox-delivery-reopen
                    (str "sqlite:" outbox-db-filename)
                    command)]
               ;; check-complete! runs only after the fixture returns, so
               ;; client close, both server stop/join boundaries, and both
               ;; SQLite connection close/reopen boundaries precede the
               ;; coordinator's completeness check over a quiesced
               ;; application.
               ((:check-complete! coord))
               fixture-result)
             (catch :default error
               ((:abort! coord))
               (let [drained?
                     (bounded-wait #(zero? (:in-flight (diagnostics)))
                                   in-flight-drain-timeout-ms)]
                 ;; One constant-size forensic record survives in the worker
                 ;; artifact without replacing or wrapping the application
                 ;; exception. run-controlled still owns final scope drainage.
                 (println
                  (pr-str
                   {:event :outbox-delivery/admission-abort
                    :drained? drained?
                    :diagnostics (diagnostics)}))
                 (flush))
               ;; Diagnostics only: this call's own failure or success must
               ;; not replace the original body exception rethrown below.
               (try ((:check-complete! coord))
                    (catch :default _ nil))
               (throw error)))))
        result (:result controlled)
        effect-trace (:effect-trace controlled)
        ;; A small immutable persistence projection derived purely from the
        ;; SQLite world state plus the controlled effect trace. It carries no
        ;; handles, addresses, controllers, paths, or mutable values: the
        ;; close-time/final images are the connection's immutable committed
        ;; storage maps (tagged row identities to typed cell maps) and the
        ;; open-image set holds selected filename strings. This is a
        ;; file-image continuity witness over two sequential modeled
        ;; connections in one fresh hermetic worker; it is not process
        ;; restart, real-SQLite parity, power-loss/WAL/torn-write,
        ;; multi-connection concurrency, or exactly-once delivery evidence.
        sqlite-state (sqlite/state sqlite-world)
        closed-db-evidence (:closed-db-evidence sqlite-state)
        persistence
        {:filename outbox-db-filename
         :connection-count (:next-connection-id sqlite-state)
         :open-count
         (count (filter #(= "sqlite3_open" (effect-foreign-symbol %))
                        effect-trace))
         :close-count
         (count (filter #(= "sqlite3_close_v2" (effect-foreign-symbol %))
                        effect-trace))
         :first-close-image (:committed (first closed-db-evidence))
         :second-close-image (:committed (second closed-db-evidence))
         :final-image (get (:images sqlite-state) outbox-db-filename)
         :open-images (:open-images sqlite-state)}]
    {:application (:application result)
     :http (:http result)
     :receiver (:receiver result)
     :routes
     {:count (count effect-trace)
      :all-handled?
      (every? #(= :handler (:route %)) effect-trace)
      :foreign-symbols (foreign-symbols effect-trace)}
     :sqlite (sqlite/summary sqlite-world)
     :persistence persistence
     :capacity {:stream (posix/capacity-summary posix-world)
                :pipe (posix/pipe-capacity-summary posix-world)}
     :fault
     (if fault?
       (let [history
             (posix-fault/evidence-history fault-frontend)]
         {:attempts (posix-fault/attempts fault-frontend)
          :firings
          (:firings (posix-fault/snapshot fault-frontend))
          :fired-attempts
          (mapv #(select-keys % [:attempt-id :firing])
                (filter :firing history))})
       {:attempts 0
        :firings 0
        :fired-attempts []})
     :admission
     {:plan admission-plan
      ;; Release evidence is plan-driven and therefore an exact replay
      ;; witness; arrival/completion order remain diagnostic only.
      :release-evidence ((:evidence coord))
      :coordinator-diagnostics (diagnostics)
      ;; Bounded per-label poll counts: both reactor roles exercised,
      ;; unlabeled (client/other) polls delegated unchanged.
      :label-counts @counts
      :routes (poll-route-summary effect-trace poll-key)}
     :clean? {:memory (memory/clean? mem)
              :sqlite (sqlite/clean? sqlite-world)
              :posix (posix/clean? posix-world)}}))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-reopen-with-capacities
  "Runs the unchanged jolt.sim.fixtures.outbox-delivery-reopen application once
   under the shared hermetic SQLite plus POSIX loopback handler packs,
   parameterized by one canonical input map:

     {:payload           vector of at most 32 unsigned octets
      :stream-capacity   8 | 16 | 32
      :pipe-capacity     1 | 2 | 4
      :poll-eintr-ordinal nil | 1 | 2 | 4 | 8
      :admission-plan    :receiver-poll-then-http-poll |
                         :http-poll-then-receiver-poll}

   The ordinary application commits the pending outbox row over one HTTP ->
   SQLite connection, that connection closes cleanly, and a freshly reopened
   connection to the same selected file-backed 'outbox.db' filename reloads
   the committed row, delivers it over the existing framed TCP/bencode path,
   validates the correlated ack, durably marks it delivered, and closes. This
   witnesses the committed pending row surviving a clean sequential
   single-owner close/reopen of one modeled file-backed SQLite database in
   each fresh hermetic worker; it does not prove process restart, real-SQLite
   parity in this lane, power-loss/WAL/torn-write behavior, multi-connection
   concurrency, or exactly-once delivery.

   A nil :poll-eintr-ordinal drives no fault frontend; a positive ordinal
   fires one captured EINTR on that per-poll attempt ordinal. The
   :admission-plan wraps the composed handlers with jolt.sim.ffi-schedule
   semantic :selector steps over occurrence 1 of the receiver-poll role and
   occurrence 1 of the HTTP-poll role on the shared poll handler key, released
   in the plan's order; the evidence map carries :admission with plan-derived
   release evidence, coordinator diagnostics, bounded per-label poll counts,
   and a poll route projection, plus a small immutable :persistence
   projection (filename, sequential connection/open/close counts, the two
   close-time committed images, the final published image, and the open-image
   set) derived from the SQLite world state and the controlled effect trace.
   Request and entity IDs stay fixed at the fixture's default-command values.
   Accepts the standard jolt.sim.explore-worker protocol-v2
   (runtime-overrides, input) arity used by
   jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-reopen-with-capacities {} input))
  ([overrides input]
   (validate-scenario-input! input)
   (evidence-for overrides input)))

;; ---- Two-attempt retry under a scoped recv ECONNRESET ---------------------
;;
;; This lane drives the unchanged
;; jolt.sim.fixtures.outbox-delivery/exercise-outbox-delivery-retry
;; application: one committed outbox row, an ordinary delivery attempt that
;; fails on a captured ECONNRESET, an ordinary catch/close/reload through the
;; same still-open SQLite connection, and a second ordinary delivery attempt
;; that succeeds. Attempt 1 never marks; the validated attempt-2 ack gates
;; one durable mark-delivered! and a final reload through the same
;; connection. The receiver records exactly attempts [1 2] -- this is an
;; at-least-once duplicate-delivery witness, not an exactly-once or receiver
;; idempotence claim.
;;
;; Fault scoping. One posix-fault recv rule matches exactly
;; {:boundary :posix :operation :recv :peer-port P} where P is the receiver
;; listener's modeled port -- the same world-owned ephemeral-base fact the
;; poll-role classifier above uses. In the loopback world a connected client
;; socket's :peer-port is the port it connected TO, and an accepted server
;; socket's :peer-port is its client's ephemeral port, so P matches only the
;; delivery client's recvs -- never the receiver's own reads, never the HTTP
;; exchange (its listener binds ephemeral-base + 1). The rule fires once
;; (:on-match 1 :times 1), returning the captured target ECONNRESET without
;; invoking or mutating the modeled recv, so the failed attempt consumes no
;; modeled receive bytes regardless of when the ack-send handler executes.
;; Scoping is by modeled resource facts
;; only: never task/thread identity, raw pointer identity, global arrival
;; order, or a mutable classifier.
;;
;; Deterministic receiver-processing-before-reset admission. Without an
;; admission edge, the delivery client's first recv boundary could arrive
;; before the receiver reached its ack-send boundary. The recv rule fires on
;; the first matching recv boundary. Two ffi-schedule semantic steps order
;; those boundary admissions:
;;
;;   1. occurrence 1 of :role/receiver-ack-send on the shared send handler
;;      key -- a send whose fd is a connected socket whose modeled
;;      :local-port is the receiver's;
;;   2. occurrence 1 of :role/delivery-client-recv on the shared recv
;;      handler key -- a recv whose fd is a connected socket whose modeled
;;      :peer-port is the receiver's.
;;
;; The client's first recv boundary parks at its gate until the receiver's
;; first ack-send boundary has arrived and been released in plan order. This
;; proves the receiver decoded attempt 1 and reached its reply boundary before
;; the reset boundary. ffi-schedule is admission-only: the original handlers
;; may execute and complete in ordinary runtime order, so this does not prove
;; any ack bytes were queued before reset. Both labels come from
;; jolt.sim.net.posix-loopback/socket-facts locked snapshots over each call's
;; own descriptor -- pure functions of the invocation and modeled world
;; state. Later occurrences (the second attempt's sends/recvs and its ack
;; send) delegate unchanged, as does every unlabeled call. check-complete!
;; runs only after the fixture returns, over a quiesced application; on body
;; failure the coordinator is aborted and drained exactly as in the
;; admission lane above. Poll attempt IDs are unaffected: the posix-fault
;; frontend keeps per-operation ordinals, so an optional captured poll EINTR
;; keeps its historical [::poll N] identity alongside [::recv K fd P].
;;
;; Receiver-side EPIPE evidence is exact within this bounded model, but the
;; failing chunk is not identified. The ack frame is a constant 59 octets
;; (4-octet prefix plus the constant bencoded ack map; the payload never
;; reaches the ack), larger than every supported stream capacity (8/16/32).
;; It therefore cannot be completely written without a client read. The
;; client's only attempt-1 recv is faulted and it closes without consuming
;; modeled bytes, so either the first ack send (if reset wins execution) or a
;; later send (if a prefix was written first) surfaces one ordinary EPIPE
;; classified by jolt.net as a write-side connection reset. The handler stops
;; on that error, so the parent observes one classified entry. A capacity at
;; or above the ack size could permit the whole frame to complete before reset
;; and is deliberately outside this lane's domain.

(def ^:private retry-input-keys
  #{:payload :stream-capacity :pipe-capacity :poll-eintr-ordinal})

(def ^:private receiver-ack-send-step-id ::receiver-ack-send)
(def ^:private delivery-client-recv-step-id ::delivery-client-recv)

(defn- validate-retry-input!
  "Validates the closed retry-scenario schema before any real socket, SQLite
   handle, or simulated world is created. The payload, capacity, and EINTR
   domains are deliberately the same bounded domains as the non-retry lane;
   the retry/fault structure itself (one scoped recv reset, one two-step
   admission gate) is fixed by the scenario, not drawn."
  [input]
  (when-not (map? input)
    (throw (invalid-scenario-input :not-a-map {:input input})))
  (let [actual-keys (set (keys input))
        unknown (seq (sort-by pr-str
                              (remove retry-input-keys actual-keys)))
        missing (seq (sort-by pr-str
                              (remove actual-keys retry-input-keys)))]
    (when unknown
      (throw (invalid-scenario-input
              :unknown-keys
              {:unknown-keys (vec unknown) :input input})))
    (when missing
      (throw (invalid-scenario-input
              :missing-keys
              {:missing-keys (vec missing) :input input}))))
  (let [payload (:payload input)]
    (when-not (vector? payload)
      (throw (invalid-scenario-input
              :invalid-payload
              {:value payload})))
    (when (> (count payload) max-payload-octets)
      (throw (invalid-scenario-input
              :payload-too-long
              {:length (count payload)
               :max-length max-payload-octets})))
    (when-not (every? octet? payload)
      (throw (invalid-scenario-input
              :invalid-payload-octet
              {:value payload}))))
  (when-not (contains? supported-stream-capacities (:stream-capacity input))
    (throw (invalid-scenario-input
            :invalid-stream-capacity
            {:value (:stream-capacity input)
             :supported (vec (sort supported-stream-capacities))})))
  (when-not (contains? supported-pipe-capacities (:pipe-capacity input))
    (throw (invalid-scenario-input
            :invalid-pipe-capacity
            {:value (:pipe-capacity input)
             :supported (vec (sort supported-pipe-capacities))})))
  (when-not (contains? supported-poll-eintr-ordinals
                        (:poll-eintr-ordinal input))
    (throw (invalid-scenario-input
            :invalid-poll-eintr-ordinal
            {:value (:poll-eintr-ordinal input)
             :supported [nil 1 2 4 8]})))
  input)

(defn- recv-reset-rule
  "The one scoped recv rule for the retry lane: fire once, on the first recv
   whose fd's modeled :peer-port is the receiver's, returning the captured
   target ECONNRESET without invoking or mutating modeled recv state."
  [receiver-port]
  {:id :outbox-delivery/reset-delivery-recv
   :match {:boundary :posix :operation :recv :peer-port receiver-port}
   :activation {:on-match 1 :times 1}
   :outcome {:kind :captured-error :errno :econnreset}})

(defn- send-handler-key
  "The exact canonical send handler key, target-independent like the poll
   key's send/recv siblings in posix-loopback's common handler keys."
  []
  (hp/foreign-function-key "send" [:int :pointer :size_t :int]
                           :ssize_t false true))

(defn- recv-handler-key
  "The exact canonical recv handler key."
  []
  (hp/foreign-function-key "recv" [:int :pointer :size_t :int]
                           :ssize_t false true))

(defn- delivery-role-classifiers
  "Builds the send/recv classifiers for the retry admission gate and their
   bounded label-count evidence atom. Each classifier resolves the
   invocation's own fd argument through the world-owned locked socket-facts
   snapshot and labels only from modeled connection facts: a send on a
   connected socket whose :local-port is the receiver's is
   :role/receiver-ack-send; a recv on a connected socket whose :peer-port is
   the receiver's is :role/delivery-client-recv; anything else is unlabeled
   and delegates unchanged. Classifiers never mutate the world, never park,
   and are total; the counts atom carries three integer counters so the
   parent can prove both roles were exercised and unlabeled calls
   delegated."
  [posix-world]
  (let [receiver-port (:ephemeral-base (:config posix-world))
        counts (atom {:role/receiver-ack-send 0
                      :role/delivery-client-recv 0
                      :unlabeled 0})
        facts-for
        (fn [descriptor]
          (let [fd (nth (:arguments descriptor) 0 nil)]
            (when (integer? fd)
              (posix/socket-facts posix-world fd))))
        classify
        (fn [facts-key descriptor]
          (let [facts (facts-for descriptor)
                label (when (and (= :connected (:state facts))
                                 (= receiver-port (get facts facts-key)))
                        (case facts-key
                          :local-port :role/receiver-ack-send
                          :peer-port :role/delivery-client-recv))]
            (swap! counts update (or label :unlabeled) inc)
            label))]
    {:classify-send (fn [descriptor] (classify :local-port descriptor))
     :classify-recv (fn [descriptor] (classify :peer-port descriptor))
     :counts counts}))

(defn- retry-schedule-plan
  "The exact closed two-step semantic ffi-schedule plan for the retry lane:
   occurrence 1 of the receiver's ack-send boundary admitted before occurrence
   1 of the delivery client's recv boundary. This orders admission only, not
   original-handler execution or completion. Occurrence counting is per
   [handler-key selector], so the second attempt's labeled sends/recvs and every
   unlabeled call delegate unchanged."
  [send-key recv-key]
  [{:id receiver-ack-send-step-id
    :handler-key send-key
    :selector :role/receiver-ack-send
    :occurrence 1}
   {:id delivery-client-recv-step-id
    :handler-key recv-key
    :selector :role/delivery-client-recv
    :occurrence 1}])

(defn- key-route-summary
  "Constant-size route evidence for one handler key's effect-trace entries:
   every intercepted call among them was served by its modeled handler. One
   record per call is deliberately not copied across the worker boundary."
  [effect-trace handler-key]
  (let [entries (filter #(= handler-key (:handler-key %)) effect-trace)]
    {:count (count entries)
     :all-handled? (every? #(= :handler (:route %)) entries)}))

(defn- retry-fault-evidence
  "Constant-size fault evidence for the retry lane: poll and recv attempt
   counts (independent per-operation ordinals -- a poll ID is never
   renumbered by recv interposition), total firings, the bounded fired
   entries themselves, and the modeled receiver peer port the recv rule was
   scoped by, echoed so the parent can validate the fired attempt-id's
   scoping without re-deriving world facts."
  [fault-frontend receiver-port]
  (let [history (posix-fault/evidence-history fault-frontend)
        recv-attempts
        (count
         (filter #(= :jolt.sim.net.posix-fault/recv
                     (nth (:attempt-id %) 0 nil))
                 history))]
    {:attempts (posix-fault/attempts fault-frontend)
     :recv-attempts recv-attempts
     :firings (:firings (posix-fault/snapshot fault-frontend))
     :fired-attempts
     (mapv #(select-keys % [:attempt-id :firing])
           (filter :firing history))
     :recv-reset-peer-port receiver-port}))

(defn- retry-evidence-for
  "Builds the hermetic SQLite and POSIX handler packs from `input`,
   interposes the scoped recv-reset rule (plus one captured poll EINTR when
   an ordinal was drawn), wraps the composed handlers with the two-step
   retry admission gate, runs the unchanged retry application once under
   run-controlled, and returns one canonical constant-size evidence map.
   Coordinator lifecycle, abort/drain, and primary-exception preservation
   mirror evidence-for exactly; `overrides` retains the worker protocol's
   runtime-override path."
  [overrides input]
  (let [{:keys [payload stream-capacity pipe-capacity poll-eintr-ordinal]}
        input
        command (assoc fixture/default-command :payload payload)
        mem (memory/world)
        target (net/target-descriptor)
        sqlite-world
        (sqlite/world mem (plans/retry-statement-plans payload))
        posix-world
        (posix/world mem target
                     {:progress-limit 64
                      :stream-capacity stream-capacity
                      :pipe-capacity pipe-capacity})
        receiver-port (:ephemeral-base (:config posix-world))
        ;; The recv reset is structural to this lane; a drawn poll EINTR is
        ;; an optional independent second rule whose poll attempt IDs keep
        ;; their historical numbering beside the recv ordinals.
        fault-plan
        (into [(recv-reset-rule receiver-port)]
              (if (some? poll-eintr-ordinal)
                (interrupt-poll-plan poll-eintr-ordinal)
                []))
        fault-frontend (posix-fault/frontend posix-world fault-plan)
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix
                  (posix-fault/foreign-handlers fault-frontend)))
        send-key (send-handler-key)
        recv-key (recv-handler-key)
        {:keys [classify-send classify-recv counts]}
        (delivery-role-classifiers posix-world)
        ;; The coordinator wraps the fault-composed handlers: send and recv
        ;; keys gain per-role occurrence gates released in exact plan order;
        ;; every other key and every unlabeled or later-occurrence call
        ;; delegates to the original handler unchanged.
        coord
        (ffi-schedule/coordinator
         handlers
         (retry-schedule-plan send-key recv-key)
         {:classifiers {send-key classify-send
                        recv-key classify-recv}})
        diagnostics (:diagnostics coord)
        controlled
        (rt/run-controlled
         (merge {:ffi-handlers (:handlers coord) :drain-timeout-ms 10000}
                overrides)
         (fn []
           (try
             (let [fixture-result
                   (fixture/exercise-outbox-delivery-retry command)]
               ;; check-complete! runs only after the fixture returns, so
               ;; HTTP client close, HTTP server stop/join, both delivery
               ;; connection lifecycles, receiver stop, and SQLite close all
               ;; precede the completeness check over a quiesced
               ;; application.
               ((:check-complete! coord))
               fixture-result)
             (catch :default error
               ((:abort! coord))
               (let [drained?
                     (bounded-wait #(zero? (:in-flight (diagnostics)))
                                   in-flight-drain-timeout-ms)]
                 ;; One constant-size forensic record survives in the
                 ;; worker artifact without replacing or wrapping the
                 ;; application exception. The bounded fired-attempt
                 ;; projection identifies exactly which rule fired before
                 ;; the failure escaped.
                 (println
                  (pr-str
                   {:event :outbox-delivery/retry-schedule-abort
                    :drained? drained?
                    :diagnostics (diagnostics)
                    :fault (select-keys
                            (retry-fault-evidence fault-frontend
                                                  receiver-port)
                            [:attempts :recv-attempts :firings
                             :fired-attempts])}))
                 (flush))
               ;; Diagnostics only: never replace the original body
               ;; exception rethrown below.
               (try ((:check-complete! coord))
                    (catch :default _ nil))
               (throw error)))))
        result (:result controlled)
        effect-trace (:effect-trace controlled)]
    {:application (:application result)
     :http (:http result)
     :receiver (:receiver result)
     :routes
     {:count (count effect-trace)
      :all-handled?
      (every? #(= :handler (:route %)) effect-trace)
      :foreign-symbols (foreign-symbols effect-trace)}
     :sqlite (sqlite/summary sqlite-world)
     :capacity {:stream (posix/capacity-summary posix-world)
                :pipe (posix/pipe-capacity-summary posix-world)}
     :fault (retry-fault-evidence fault-frontend receiver-port)
     :schedule
     {;; Release evidence is plan-driven and therefore an exact replay
      ;; witness; arrival/completion order remain diagnostic only.
      :release-evidence ((:evidence coord))
      :coordinator-diagnostics (diagnostics)
      ;; Bounded per-label send/recv counts: both roles exercised,
      ;; unlabeled calls delegated unchanged.
      :label-counts @counts
      :routes {:send (key-route-summary effect-trace send-key)
               :recv (key-route-summary effect-trace recv-key)}}
     :clean? {:memory (memory/clean? mem)
              :sqlite (sqlite/clean? sqlite-world)
              :posix (posix/clean? posix-world)}}))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-retry-recv-reset
  "Runs the unchanged jolt.sim.fixtures.outbox-delivery-retry application
   once under the shared hermetic SQLite plus POSIX loopback handler packs,
   parameterized by one canonical input map:

     {:payload           vector of at most 32 unsigned octets
      :stream-capacity   8 | 16 | 32
      :pipe-capacity     1 | 2 | 4
      :poll-eintr-ordinal nil | 1 | 2 | 4 | 8}

   One scoped posix-fault recv rule always fires one captured ECONNRESET on
   the delivery client's first recv (scoped by the modeled receiver peer
   port) without invoking or mutating modeled recv state; a drawn
   :poll-eintr-ordinal additionally fires one captured EINTR on that
   per-poll attempt ordinal. A two-step jolt.sim.ffi-schedule semantic plan
   admits the receiver's first ack-send boundary before the delivery client's
   first recv boundary, proving receiver processing reached its reply boundary
   before reset without claiming original-handler execution order. The
   evidence map carries
   the ordinary application/HTTP/receiver projections, route and exact
   27-statement SQLite plan evidence, capacity summaries, per-operation
   fault evidence with the exact scoped firing identities, plan-order
   coordinator release evidence with diagnostics and bounded label counts,
   and all-world cleanup. Accepts the standard
   jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used
   by jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-retry-recv-reset {} input))
  ([overrides input]
   (validate-retry-input! input)
   (retry-evidence-for overrides input)))
