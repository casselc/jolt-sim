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

   Real/sim parity is intentionally NOT rerun here: the existing
   real-plus-hermetic PR #28 gate (:outbox-delivery-test) remains the parity
   evidence for the unchanged application. Every case additionally wraps the
   already composed canonical handlers with jolt.sim.ffi-schedule semantic
   selector steps over occurrence 1 of the receiver-poll role and occurrence 1
   of the HTTP-poll role, in one of two drawn release orders (see the
   admission section below). Broader schedule search and extreme 1--2 byte
   HTTP fragmentation remain omitted: generated payload semantics,
   conservative capacity fragmentation, captured EINTR, and the two
   discriminating first-poll admission orders are the feature boundary."
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
   outbox-delivery fixture once under run-controlled, and returns one
   canonical evidence map: the ordinary application/HTTP/receiver projections
   plus route, SQLite, capacity, fault, admission, and cleanup evidence
   sufficient to catch a model bypass. The command is the fixture's
   default-command with its :payload rebound to the drawn octet vector; the
   exact SQLite plan world is parameterized with the same payload, so plan
   binds and application binds cannot diverge.

   The coordinator wraps only the one canonical poll handler key with two
   semantic :selector steps (occurrence 1 of each reactor's poll role, in the
   plan's release order); every other key and every unlabeled or client poll
   delegates to the original handler unchanged. check-complete! runs only
   after exercise-outbox-delivery returns -- and therefore after HTTP client
   close, HTTP server stop/join, SQLite close, delivery close, and receiver
   stop -- so the coordinator's completeness check observes a quiesced
   application. On body failure the coordinator is aborted, the wrapper waits
   up to one bounded deadline for selected calls to drain, records bounded
   coordinator diagnostics, and rethrows the original application exception
   as the primary cause. Final lifecycle drainage remains run-controlled's
   responsibility.

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
        (sqlite/world mem (plans/delivery-statement-plans payload))
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
             (let [fixture-result (fixture/exercise-outbox-delivery command)]
               ;; check-complete! runs only after the fixture returns, so
               ;; client close, both server stop/join boundaries, and SQLite
               ;; cleanup precede the coordinator's completeness check over a
               ;; quiesced application.
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
        :jolt.sim/accepts-input true} exercise-with-capacities
  "Runs the unchanged jolt.sim.fixtures.outbox-delivery application once under
  the shared hermetic SQLite plus POSIX loopback handler packs, parameterized
  by one canonical input map:

    {:payload           vector of at most 32 unsigned octets
     :stream-capacity   8 | 16 | 32
     :pipe-capacity     1 | 2 | 4
     :poll-eintr-ordinal nil | 1 | 2 | 4 | 8
     :admission-plan    :receiver-poll-then-http-poll |
                        :http-poll-then-receiver-poll}

  A nil :poll-eintr-ordinal drives no fault frontend; a positive ordinal
  fires one captured EINTR on that per-poll attempt ordinal. The
  :admission-plan wraps the composed handlers with jolt.sim.ffi-schedule
  semantic :selector steps over occurrence 1 of the receiver-poll role and
  occurrence 1 of the HTTP-poll role on the shared poll handler key, released
  in the plan's order; the evidence map carries :admission with plan-derived
  release evidence, coordinator diagnostics, bounded per-label poll counts,
  and a poll route projection. Request and entity IDs stay fixed at the
  fixture's default-command values. Accepts the standard
  jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used
  by jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-with-capacities {} input))
  ([overrides input]
   (validate-scenario-input! input)
   (evidence-for overrides input)))
