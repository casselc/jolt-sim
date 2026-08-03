(ns jolt.sim.fixtures.ffi-schedule-scenarios
  "Runs the UNCHANGED jolt.sim.fixtures.net-poller
  exercise-active-poller-close application against the REAL operating system
  through jolt.sim.runtime/run-controlled's :hybrid FFI routing, with only the
  poll(2) and write(2) admission order controlled by jolt.sim.ffi-schedule.

  No posix-loopback or ffi-memory handler is used and no modeled resource is
  ever registered: poll and write are the only two handler-pack keys present,
  and each registered handler immediately returns jolt.sim.runtime/proceed, so
  every intercepted call -- scheduled or not -- still reaches its real native
  implementation. The coordinator therefore only orders *admission* (arrival
  and gate release); it makes no native execution-order claim.

  Two plans exist over poll occurrence 1 and write occurrence 1:
  :poll-then-write releases poll's gate before write's; :write-then-poll
  releases write's gate first. In both, the fixture's own future reaches the
  intercepted poll boundary before the owning thread issues the write that
  wakes it: the supplied await-parked! barrier bounded-waits for the poll step
  to appear in the coordinator's :arrived diagnostics before returning. In the
  reversed plan that arrival is intentionally still gated and has not yet
  invoked the OS poll, so this wrapper narrows the fixture barrier's usual
  native-parking interpretation to intercepted-boundary arrival.

  This is a plain function carrying the same :jolt.sim/scenario and
  :jolt.sim/accepts-input markers defsim produces (mirroring
  jolt.sim.fixtures.http-sqlite-scenarios), because handler construction
  depends on input and defsim's declared-config form cannot build an
  input-dependent handler pack. Each run's canonical evidence is the fixture's
  own ordinary result, the coordinator's plan-order release evidence, bounded
  coordinator diagnostics, and a poll/write effect-trace projection proving
  :native routing."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-schedule :as ffi-schedule]
            [jolt.sim.fixtures.net-poller :as net-poller]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.runtime :as rt]))

(def ^:private drain-timeout-ms 10000)
(def ^:private poll-arrival-timeout-ms 5000)
(def ^:private in-flight-drain-timeout-ms 5000)

(def ^:private poll-step-id ::poll)
(def ^:private write-step-id ::write)

(def ^:private plan-orders
  {:poll-then-write [poll-step-id write-step-id]
   :write-then-poll [write-step-id poll-step-id]})

(defn- bounded-wait
  "Sleeps in small bounded intervals until pred is true or timeout-ms elapses.
  Returns whether pred was observed true; never blocks past the deadline."
  [pred timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (cond
        (pred) true
        (< (System/nanoTime) deadline) (do (Thread/sleep 2) (recur))
        :else false))))

(defn- native-handler-keys
  "The exact canonical poll/write handler keys for the live target descriptor's
  nfds_t width. poll is [pointer nfds-type int] -> int, blocking, captured;
  write is [int pointer size_t] -> ssize_t, nonblocking, captured."
  []
  (let [target (net/target-descriptor)]
    {:poll (hp/foreign-function-key
            "poll" [:pointer (:nfds-type target) :int] :int true true)
     :write (hp/foreign-function-key
             "write" [:int :pointer :size_t] :ssize_t false true)}))

(defn- plan-for [plan-key poll-key write-key]
  (let [handler-key-for {poll-step-id poll-key write-step-id write-key}]
    (mapv (fn [id] {:id id :handler-key (get handler-key-for id) :occurrence 1})
          (get plan-orders plan-key))))

(defn- native-route-projection
  "Projects only the poll/write effect-trace entries to {:symbol :route},
  proving every scheduled and unscheduled call among them reached :native."
  [effect-trace poll-key write-key]
  (into []
        (comp (filter #(contains? #{poll-key write-key} (:handler-key %)))
              (map (fn [entry]
                     {:symbol (:symbol (:descriptor entry))
                      :route (:route entry)})))
        effect-trace))

(defn- evidence-for
  "Builds the pass-through poll/write handler pack, wraps it with the
  ffi-schedule coordinator for `plan-key`, and runs the unchanged fixture once
  under run-controlled against the real OS. `overrides` is the process-explorer
  worker's runtime-overrides map, merged over this wrapper's own config so the
  worker protocol's override path is retained."
  [overrides plan-key]
  (let [{:keys [poll write]} (native-handler-keys)
        proceed-handler (fn [_] (rt/proceed))
        handlers
        (hp/compose
         (hp/pack ::posix-native {poll proceed-handler write proceed-handler}))
        coord (ffi-schedule/coordinator handlers (plan-for plan-key poll write))
        diagnostics (:diagnostics coord)
        await-parked!
        (fn [_waiting _poller]
          (bounded-wait
           #(contains? (:arrived (diagnostics)) poll-step-id)
           poll-arrival-timeout-ms))
        controlled
        (rt/run-controlled
         (merge {:ffi-handlers (:handlers coord)
                 :ffi-mode :hybrid
                 :drain-timeout-ms drain-timeout-ms}
                overrides)
         (fn []
           (try
             (let [fixture-result
                   (net-poller/exercise-active-poller-close await-parked!)]
               ((:check-complete! coord))
               fixture-result)
             (catch :default error
               ((:abort! coord))
               (bounded-wait
                #(zero? (:in-flight (diagnostics)))
                in-flight-drain-timeout-ms)
               ;; Diagnostics only: this call's own failure or success must not
               ;; replace the original body exception below.
               (try ((:check-complete! coord)) (catch :default _ nil))
               (throw error)))))]
    {:fixture-result (:result controlled)
     :release-evidence ((:evidence coord))
     :coordinator-diagnostics (diagnostics)
     :native-routes
     (native-route-projection (:effect-trace controlled) poll write)}))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-admission-order
  "Runs the unchanged jolt.net poller fixture's exercise-active-poller-close
  against the real OS under :ffi-mode :hybrid, admission-ordering the real
  poll(2) occurrence 1 and write(2) occurrence 1 calls with
  jolt.sim.ffi-schedule. input is :poll-then-write or :write-then-poll,
  selecting which gate releases first. Accepts the standard
  jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used by
  jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-admission-order {} input))
  ([overrides input]
   (evidence-for overrides input)))
