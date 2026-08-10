(ns jolt.sim.fixtures.outbox-json-delivery-live-scenarios
  "Hermetic fresh-worker scenarios for the real live outbox lifecycle API.

   The body starts and drives the same persistent HTTP, SQLite, TCP/bencode
   application used by Ripple and EvalSession. Only the native boundaries are
   supplied by the existing memory, exact-plan SQLite, and POSIX loopback
   controller worlds. No simulator-side application, database, or protocol
   implementation is introduced."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-delivery :as delivery]
            [jolt.sim.fixtures.outbox-json-delivery :as fixture]
            [jolt.sim.fixtures.outbox-json-delivery-live :as live]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private input-keys #{:payload :ack-outcome})
(def ^:private ack-outcomes #{:accepted :hostile})
(def ^:private max-payload-octets 32)

(defn- invalid-input [reason input]
  (ex-info "invalid live outbox lifecycle scenario input"
           {:type ::invalid-input :reason reason :input input}))

(defn- validate-input! [input]
  (when-not (and (map? input) (= input-keys (set (keys input))))
    (throw (invalid-input :shape input)))
  (when-not (contains? ack-outcomes (:ack-outcome input))
    (throw (invalid-input :ack-outcome input)))
  (when-not (and (vector? (:payload input))
                 (<= (count (:payload input)) max-payload-octets)
                 (every? #(and (integer? %) (<= 0 % 255)) (:payload input)))
    (throw (invalid-input :payload input)))
  input)

(defn- hostile-ack [message]
  {"type" "outbox_delivery_ok"
   "outbox-id" (inc (get message "outbox-id"))
   "attempt" (get message "attempt")})

(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn- exercise-body [command ack-outcome]
  (let [lifecycle
        (live/start!
         (cond-> {:retained-evidence 8}
           (= :hostile ack-outcome) (assoc :reply-for hostile-ack)))]
    (try
      (let [initial (live/snapshot! lifecycle)
            submission (live/submit-command! lifecycle command)
            pending (live/snapshot! lifecycle)
            delivery-outcome
            (try
              {:value (live/deliver-next! lifecycle)}
              (catch :default error
                {:error {:type (:type (ex-data error))
                         :reason (:reason (ex-data error))}}))
            resulting (live/snapshot! lifecycle)
            stop-results [(live/stop! lifecycle) (live/stop! lifecycle)]
            stopped (live/snapshot! lifecycle)]
        {:initial initial
         :submission submission
         :pending pending
         :delivery delivery-outcome
         :resulting resulting
         :stop-results stop-results
         :stopped stopped})
      (finally
        ;; Idempotent cleanup also owns every failure path before controller
        ;; restoration; no raw server thread may outlive this body.
        (live/stop! lifecycle)))))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true}
  exercise-live-lifecycle
  "Runs one validated payload and acknowledgement outcome through the
   unchanged persistent lifecycle inside a fresh hermetic controller scope."
  ([input]
   (exercise-live-lifecycle {} input))
  ([overrides input]
   (validate-input! input)
   (when-not (map? overrides)
     (throw (invalid-input :overrides input)))
   (let [command (assoc fixture/default-command :payload (:payload input))
         mem (memory/world)
         sqlite-world
         (sqlite/world
          mem (plans/live-lifecycle-statement-plans
               command (:ack-outcome input)))
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
          #(exercise-body command (:ack-outcome input)))
         effect-trace (:effect-trace controlled)]
     {:application (:result controlled)
      :input input
      :routes {:count (count effect-trace)
               :all-handled? (every? #(= :handler (:route %)) effect-trace)
               :foreign-symbols (foreign-symbols effect-trace)}
      :sqlite (sqlite/summary sqlite-world)
      :capacity {:stream (posix/capacity-summary posix-world)
                 :pipe (posix/pipe-capacity-summary posix-world)}
      :clean? {:memory (memory/clean? mem)
               :sqlite (sqlite/clean? sqlite-world)
               :posix (posix/clean? posix-world)}})))
