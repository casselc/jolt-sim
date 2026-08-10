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
            [jolt.sim.sqlite :as sqlite]
            [jolt.sim.trace :as trace]))

(def ^:private input-keys #{:payload :ack-outcome})
(def ^:private ack-outcomes #{:accepted :hostile})
(def ^:private override-keys #{:future-schedule})
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

(defn- throw-with-cleanup! [primary cleanup-errors]
  (cond
    (and primary (seq cleanup-errors))
    (throw
     (ex-info (or (ex-message primary) (str primary))
              (assoc (or (ex-data primary) {})
                     ::cleanup-errors cleanup-errors)
              primary))

    primary
    (throw primary)

    (seq cleanup-errors)
    (throw
     (ex-info "live lifecycle cleanup failed"
              {:type ::cleanup-failed
               ::cleanup-errors cleanup-errors}))

    :else nil))

(defn- stop-lifecycle [lifecycle]
  (let [attempt! #(try {:value (live/stop! lifecycle)}
                       (catch :default error {:error error}))
        first-attempt (attempt!)
        second-attempt (attempt!)
        ;; A first cleanup failure leaves stop! explicitly retryable. If the
        ;; second attempt becomes the owner, make one final idempotency check.
        third-attempt (when (or (:error first-attempt)
                                (true? (:value second-attempt)))
                        (attempt!))
        attempts (cond-> [first-attempt second-attempt]
                   third-attempt (conj third-attempt))
        values (mapv :value (filter #(contains? % :value) attempts))
        stopped-outcome
        (try {:value (live/snapshot! lifecycle)}
             (catch :default error {:error error}))
        cleanup-failures
        (cond-> (mapv :error (filter :error attempts))
          (:error stopped-outcome) (conj (:error stopped-outcome))
          (or (not (some true? values))
              (not= false (peek values))
              (not= :stopped (get-in stopped-outcome [:value :status])))
          (conj
           (ex-info "live lifecycle did not quiesce"
                    {:type ::cleanup-incomplete
                     :stop-results values
                     :status (get-in stopped-outcome [:value :status])})))
        errors (mapv trace/normalize-error cleanup-failures)]
    {:values values
     :errors errors
     :stopped (:value stopped-outcome)}))

(defn- exercise-body [command ack-outcome]
  (let [lifecycle
        (live/start!
         (cond-> {:retained-evidence 8}
           (= :hostile ack-outcome) (assoc :reply-for hostile-ack)))]
    (let [body
          (try
            {:value
             (let [initial (live/snapshot! lifecycle)
                   submission (live/submit-command! lifecycle command)
                   pending (live/snapshot! lifecycle)
                   delivery-outcome
                   (try
                     {:value (live/deliver-next! lifecycle)}
                     (catch :default error
                       {:error {:type (:type (ex-data error))
                                :reason (:reason (ex-data error))}}))
                   resulting (live/snapshot! lifecycle)]
               {:initial initial
                :submission submission
                :pending pending
                :delivery delivery-outcome
                :resulting resulting})}
            (catch :default error {:error error}))
          ;; Cleanup finishes inside the controller scope even when the body
          ;; failed. Its bounded diagnostics augment rather than replace the
          ;; first application/controller failure.
          cleanup (stop-lifecycle lifecycle)]
      (throw-with-cleanup! (:error body) (:errors cleanup))
      (assoc (:value body)
             :stop-results (:values cleanup)
             :stopped (:stopped cleanup)))))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true
        :jolt.sim/activity-lifecycle-owned true}
  exercise-live-lifecycle
  "Runs one validated payload and acknowledgement outcome through the
   unchanged persistent lifecycle inside a fresh hermetic controller scope."
  ([input]
   (exercise-live-lifecycle {} input))
  ([overrides input]
   (validate-input! input)
   (when-not (and (map? overrides)
                  (every? override-keys (keys overrides)))
     (throw (invalid-input :overrides overrides)))
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
