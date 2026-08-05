(ns jolt.sim.fixtures.outbox-http-webhook-scenarios
  "Fresh-worker controller wrapper for the ordinary JSON HTTP webhook app.

  This namespace builds only the shared FFI-memory, exact-plan SQLite, and
  POSIX loopback worlds. The application remains
  jolt.sim.fixtures.outbox-http-webhook/exercise-command, which runs the
  routed JSON command, db.sqlite adapter, public jolt.http-client sender, and
  ordinary jolt-http receiver unchanged."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-http-webhook :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private input-keys #{:payload :response-mode})
(def ^:private response-modes
  (set (concat fixture/accepted-scenarios fixture/hostile-scenarios)))
(def ^:private max-payload-octets 32)

(defn- invalid-input [reason input]
  (ex-info
   "invalid JSON HTTP webhook scenario input"
   {:type :jolt.sim.fixtures.outbox-http-webhook-scenarios/invalid-input
    :reason reason
    :input input}))

(defn- validate-input! [input]
  (when-not (and (map? input) (= input-keys (set (keys input))))
    (throw (invalid-input :shape input)))
  (when-not (contains? response-modes (:response-mode input))
    (throw (invalid-input :response-mode input)))
  (when-not (and (vector? (:payload input))
                 (<= (count (:payload input)) max-payload-octets)
                 (every? #(and (integer? %) (<= 0 % 255))
                         (:payload input)))
    (throw (invalid-input :payload input)))
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

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise
  "Runs one validated payload/response-mode case in a fresh worker.

  Capacities are deliberately fixed at the already-proven smoke boundary:
  response authorization does not depend on fragmentation or captured EINTR,
  and those independent axes have dedicated whole-application campaigns."
  ([input]
   (exercise {} input))
  ([overrides input]
   (validate-input! input)
   (let [payload (:payload input)
         response-mode (:response-mode input)
         command (assoc fixture/default-command :payload payload)
         accepted? (contains? (set fixture/accepted-scenarios) response-mode)
         statement-plans (if accepted?
                           (plans/delivery-statement-plans payload)
                           (plans/webhook-rejected-statement-plans payload))
         mem (memory/world)
         sqlite-world (sqlite/world mem statement-plans)
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
          #(fixture/exercise-command
            response-mode command fixture/default-attempt-id))
         effect-trace (:effect-trace controlled)]
     {:application (:result controlled)
      :routes {:count (count effect-trace)
               :all-handled? (every? #(= :handler (:route %)) effect-trace)
               :foreign-symbols (foreign-symbols effect-trace)}
      :sqlite (sqlite/summary sqlite-world)
      :capacity {:stream (posix/capacity-summary posix-world)
                 :pipe (posix/pipe-capacity-summary posix-world)}
      :clean? {:memory (memory/clean? mem)
               :sqlite (sqlite/clean? sqlite-world)
               :posix (posix/clean? posix-world)}})))
