(ns jolt.sim.fixtures.tcp-bencode-scenarios
  "Narrow scenario wrapper that runs the UNCHANGED
  jolt.sim.fixtures.tcp-bencode application under the shared hermetic POSIX
  loopback handler pack, parameterized by one canonical scenario input.

  This namespace knows nothing about Hegel or the process-explorer parent. A
  fresh worker resolves one scenario here by its namespaced symbol and drives
  it with one canonical input value naming the stream capacity, pipe
  capacity, an optional captured poll EINTR activation ordinal, one
  discriminating request text, and whether this case is the one real/sim
  parity witness. The application, TCP, net, and POSIX implementations are
  all reused unchanged; only the deterministic world-building glue is
  parameterized here, following the same shape as
  jolt.sim.fixtures.http-sqlite-scenarios/exercise-with-capacities: build the
  hermetic world, run run-controlled directly, and project one canonical
  evidence map.

  This first slice omits ffi-schedule admission-order search: capacity
  fragmentation, pipelining, real/sim parity, and captured EINTR are the
  feature boundary."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.tcp-bencode :as fixture]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]))

(def ^:private scenario-input-keys
  #{:stream-capacity :pipe-capacity :poll-eintr-ordinal
    :request-text :check-parity?})

(def ^:private supported-capacities #{1 2 4 8})
(def ^:private supported-poll-eintr-ordinals #{nil 1 2 4 8})
(def ^:private max-request-text-length 32)

(defn- invalid-scenario-input [reason data]
  (ex-info
   "invalid tcp-bencode scenario input"
   (merge {:type :jolt.sim.fixtures.tcp-bencode-scenarios/invalid-input
           :reason reason}
          data)))

(defn- validate-scenario-input!
  "Validates the closed generated-scenario schema before any real socket or
   simulated world is created. The capacity and EINTR ranges are deliberately
   the same bounded domains exercised by this scenario's Hegel lane; request
   text is capped at that lane's UTF-8 generator maximum."
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
  (when-not (contains? supported-capacities (:stream-capacity input))
    (throw (invalid-scenario-input
            :invalid-stream-capacity
            {:value (:stream-capacity input)
             :supported (vec (sort supported-capacities))})))
  (when-not (contains? supported-capacities (:pipe-capacity input))
    (throw (invalid-scenario-input
            :invalid-pipe-capacity
            {:value (:pipe-capacity input)
             :supported (vec (sort supported-capacities))})))
  (when-not (contains? supported-poll-eintr-ordinals
                       (:poll-eintr-ordinal input))
    (throw (invalid-scenario-input
            :invalid-poll-eintr-ordinal
            {:value (:poll-eintr-ordinal input)
             :supported [nil 1 2 4 8]})))
  (when-not (string? (:request-text input))
    (throw (invalid-scenario-input
            :invalid-request-text
            {:value (:request-text input)})))
  (when (> (count (:request-text input)) max-request-text-length)
    (throw (invalid-scenario-input
            :request-text-too-long
            {:length (count (:request-text input))
             :max-length max-request-text-length})))
  (when-not (boolean? (:check-parity? input))
    (throw (invalid-scenario-input
            :invalid-check-parity
            {:value (:check-parity? input)})))
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
  [{:id :tcp-bencode/interrupt-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match ordinal :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

(defn- evidence-for
  "Builds the hermetic POSIX handler pack from `input`, runs the unchanged
  fixture once under run-controlled (and, when `:check-parity?` is true, once
  more against real loopback sockets first), and returns one canonical
  evidence map: the ordinary TCP response projection plus route, capacity,
  fault, and cleanup evidence sufficient to catch a model bypass."
  [overrides input]
  (let [{:keys [stream-capacity pipe-capacity poll-eintr-ordinal
                request-text check-parity?]} input
        real-result
        (when check-parity?
          (fixture/exercise-tcp-bencode ["" request-text]))
        mem (memory/world)
        ;; The finite per-socket receive FIFO and self-pipe capacities are the
        ;; per-case variables; every framed octet crosses the capacity model.
        posix-world
        (posix/world mem (net/target-descriptor)
                     {:stream-capacity stream-capacity
                      :pipe-capacity pipe-capacity})
        fault? (some? poll-eintr-ordinal)
        fault-frontend
        (when fault?
          (posix-fault/frontend posix-world
                                (interrupt-poll-plan poll-eintr-ordinal)))
        ;; Two named packs over one shared memory world: memory handlers plus
        ;; the POSIX foreign set (fault-interposed at poll when an EINTR
        ;; ordinal was drawn, plain otherwise). No plain merge across packs.
        posix-foreign
        (if fault?
          (posix-fault/foreign-handlers fault-frontend)
          (posix/foreign-handlers posix-world))
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/posix posix-foreign))
        controlled
        (rt/run-controlled
         (merge {:ffi-handlers handlers :drain-timeout-ms 10000}
                overrides)
         #(fixture/exercise-tcp-bencode ["" request-text]))
        result (:result controlled)
        effect-trace (:effect-trace controlled)
        evidence
        (cond-> {:tcp {:sent-bytes (:sent-bytes result)
                       :received-bytes (:received-bytes result)
                       :replies (:replies result)
                       :server-errors (:server-errors result)
                       :close-results (:close-results result)}
                 :routes
                 {:count (count effect-trace)
                  :all-handled?
                  (every? #(= :handler (:route %)) effect-trace)
                  :foreign-symbols (foreign-symbols effect-trace)}
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
                          :posix (posix/clean? posix-world)}}
          check-parity?
          (assoc :parity
                 {:real-replies (:replies real-result)
                  :real-server-errors (:server-errors real-result)
                  :real-close-results (:close-results real-result)}))]
    evidence))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-with-capacities
  "Runs the unchanged jolt.sim.fixtures.tcp-bencode application once under the
  shared hermetic POSIX loopback handler pack, parameterized by one canonical
  input map:

    {:stream-capacity    1 | 2 | 4 | 8
     :pipe-capacity      1 | 2 | 4 | 8
     :poll-eintr-ordinal nil | 1 | 2 | 4 | 8
     :request-text       string of at most 32 characters
     :check-parity?      boolean}

  A nil :poll-eintr-ordinal drives no fault frontend; a positive ordinal
  fires one captured EINTR on that per-poll attempt ordinal. A true
  :check-parity? additionally runs the same fixture once against real
  loopback sockets first, and the evidence map then carries :parity with the
  real run's replies, server errors, and close results. Accepts the standard
  jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used by
  jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-with-capacities {} input))
  ([overrides input]
   (validate-scenario-input! input)
   (evidence-for overrides input)))
