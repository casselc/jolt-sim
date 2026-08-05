(ns jolt.sim.fixtures.outbox-json-delivery-scenarios
  "Fresh-worker hermetic scenarios for the ordinary routed-JSON outbox
   replay/conflict workload. The scenario builds only the shared memory,
   exact-plan SQLite, and POSIX loopback controller worlds; the application is
   jolt.sim.fixtures.outbox-json-delivery/exercise-outbox-json-replay-conflict,
   which runs two ordinary jolt-http cycles through the production facade on
   one SQLite connection and then the existing TCP/bencode delivery path."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-json-delivery :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private input-keys #{:mode :payload :request-id :entity-id})
(def ^:private supported-modes #{:exact-replay :conflict})
(def ^:private max-payload-octets 32)

(defn- invalid-input [reason input]
  (ex-info "invalid routed JSON replay/conflict scenario input"
           {:type :jolt.sim.fixtures.outbox-json-delivery-scenarios/invalid-input
            :reason reason
            :input input}))

(defn- route-safe-id? [value]
  (and (string? value)
       (<= 1 (count value) 32)
       (every? #(or (<= (int \a) (int %) (int \z))
                    (<= (int \0) (int %) (int \9))
                    (= \- %)
                    (= \_ %))
               value)))

(defn- validate-input! [input]
  (when-not (and (map? input) (= input-keys (set (keys input))))
    (throw (invalid-input :shape input)))
  (when-not (contains? supported-modes (:mode input))
    (throw (invalid-input :mode input)))
  (when-not (route-safe-id? (:request-id input))
    (throw (invalid-input :request-id input)))
  (when-not (route-safe-id? (:entity-id input))
    (throw (invalid-input :entity-id input)))
  (when-not (and (vector? (:payload input))
                 (<= (count (:payload input)) max-payload-octets)
                 (every? #(and (integer? %) (<= 0 % 255))
                         (:payload input)))
    (throw (invalid-input :payload input)))
  input)

(defn- distinct-payload
  "Derives a valid payload unequal to `payload` without rejection sampling.
   Length stays within the generated 0..32 bound."
  [payload]
  (if (< (count payload) max-payload-octets)
    (conj payload 0)
    (assoc payload 0 (bit-xor 1 (first payload)))))

(defn- workload-for [{:keys [mode payload request-id entity-id]}]
  (let [accepted {:request-id request-id
                  :entity-id entity-id
                  :payload payload}]
    {:mode mode
     :accepted-command accepted
     :second-command
     (if (= :exact-replay mode)
       accepted
       (assoc accepted :payload (distinct-payload payload)))}))

(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-replay-or-conflict
  "Runs one validated replay/conflict input in a fresh worker. The mode and
   accepted command are converted to the ordinary fixture's closed workload;
   no simulator-side command handler or storage implementation is introduced.
   Accepts the process-explorer protocol-v2 (runtime-overrides, input) arity."
  ([input]
   (exercise-replay-or-conflict {} input))
  ([overrides input]
   (validate-input! input)
   (let [workload (workload-for input)
         mem (memory/world)
         sqlite-world
         (sqlite/world mem
                       (plans/json-replay-conflict-statement-plans workload))
         posix-world
         (posix/world mem (net/target-descriptor)
                      {:progress-limit 64
                       :stream-capacity 8
                       :pipe-capacity 1})
         handlers
         (hp/compose
          (hp/pack :jolt.sim/memory (memory/handlers mem))
          (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
          (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
         controlled
         (rt/run-controlled
          (merge {:ffi-handlers handlers :drain-timeout-ms 10000} overrides)
          #(fixture/exercise-outbox-json-replay-conflict workload))
         result (:result controlled)
         effect-trace (:effect-trace controlled)]
     {:application (:application result)
      :http (:http result)
      :workload (:workload result)
      :receiver (:receiver result)
      :routes {:count (count effect-trace)
               :all-handled? (every? #(= :handler (:route %)) effect-trace)
               :foreign-symbols (foreign-symbols effect-trace)}
      :sqlite (sqlite/summary sqlite-world)
      :capacity {:stream (posix/capacity-summary posix-world)
                 :pipe (posix/pipe-capacity-summary posix-world)}
      :clean? {:memory (memory/clean? mem)
               :sqlite (sqlite/clean? sqlite-world)
               :posix (posix/clean? posix-world)}})))
