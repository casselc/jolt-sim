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
  evidence for the unchanged application. This slice also omits ffi-schedule
  admission-order search and extreme 1--2 byte HTTP fragmentation: generated
  payload semantics, conservative capacity fragmentation, and captured EINTR
  are the feature boundary."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private scenario-input-keys
  #{:payload :stream-capacity :pipe-capacity :poll-eintr-ordinal})

;; Conservative bounds: the stream floor 8 is the fixed smoke capacity already
;; proven against the fixture's 5 second client deadline; 1--2 byte HTTP
;; fragmentation is explicitly later work. The pipe floor 1 is the smoke
;; self-pipe bound. The closed domains deliberately mirror the Hegel lane's
;; generator; both sides reject anything outside them before a world exists.
(def ^:private max-payload-octets 32)
(def ^:private supported-stream-capacities #{8 16 32})
(def ^:private supported-pipe-capacities #{1 2 4})
(def ^:private supported-poll-eintr-ordinals #{nil 1 2 4 8})

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

(defn- evidence-for
  "Builds the hermetic SQLite and POSIX handler packs from `input`, runs the
   unchanged outbox-delivery fixture once under run-controlled, and returns
   one canonical evidence map: the ordinary application/HTTP/receiver
   projections plus route, SQLite, capacity, fault, and cleanup evidence
   sufficient to catch a model bypass. The command is the fixture's
   default-command with its :payload rebound to the drawn octet vector; the
   exact SQLite plan world is parameterized with the same payload, so plan
   binds and application binds cannot diverge."
  [overrides input]
  (let [{:keys [payload stream-capacity pipe-capacity poll-eintr-ordinal]}
        input
        command (assoc fixture/default-command :payload payload)
        mem (memory/world)
        sqlite-world
        (sqlite/world mem (plans/delivery-statement-plans payload))
        ;; The 64-octet native progress ceiling and the finite per-socket
        ;; receive FIFO and self-pipe capacities are the per-case variables;
        ;; every framed octet crosses the capacity model.
        posix-world
        (posix/world mem (net/target-descriptor)
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
        controlled
        (rt/run-controlled
         (merge {:ffi-handlers handlers :drain-timeout-ms 10000}
                overrides)
         #(fixture/exercise-outbox-delivery command))
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
     :poll-eintr-ordinal nil | 1 | 2 | 4 | 8}

  A nil :poll-eintr-ordinal drives no fault frontend; a positive ordinal
  fires one captured EINTR on that per-poll attempt ordinal. Request and
  entity IDs stay fixed at the fixture's default-command values. Accepts the
  standard jolt.sim.explore-worker protocol-v2 (runtime-overrides, input)
  arity used by jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-with-capacities {} input))
  ([overrides input]
   (validate-scenario-input! input)
   (evidence-for overrides input)))
