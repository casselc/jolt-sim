(ns jolt.sim.fixtures.explore-scenarios
  "Marked `defsim` scenarios exercising the process explorer worker protocol
  (`jolt.sim.explore-worker` / `jolt.sim.process-explorer`) end to end.

  Each scenario is ordinary unchanged Jolt code; `defsim` only installs the
  controller, event capture, and cleanup around it. A calling test resolves one
  scenario by its namespaced symbol and drives it through a fresh worker
  process with an explicit `:future-schedule` runtime override."
  (:require [jolt.sim.runtime :as rt]))

(rt/defsim independent {}
  ;; Two ordinary futures with no dependency between them: both are spawned
  ;; before either is dereferenced, so either admission order ([0 1] or
  ;; [1 0]) lets the scope complete. The atom's conj order is the observed
  ;; body-start order under whichever schedule drove this run.
  (let [observed (atom [])
        a (future (swap! observed conj :a) :a)
        b (future (swap! observed conj :b) :b)]
    {:a-result @a
     :b-result @b
     :start-order @observed}))

(rt/defsim dependent {}
  ;; Future A is dereferenced before future B is even spawned. Schedule
  ;; [0 1] completes normally: A's ordinal is due first, its body runs, A's
  ;; deref returns, and only then is B spawned and admitted. Schedule [1 0]
  ;; blocks forever: A's ordinal cannot be admitted until B's ordinal
  ;; finishes, but B is never spawned because this very thread is stuck
  ;; dereferencing A first.
  (let [a (future :a)
        a-result @a
        b (future :b)
        b-result @b]
    {:a-result a-result
     :b-result b-result}))

(rt/defsim fails {}
  (throw
   (ex-info
    "jolt.sim.fixtures.explore-scenarios deliberate failure"
    {:type :jolt.sim.fixtures.explore-scenarios/deliberate-failure})))

(rt/defsim noncanonical {}
  ;; One ordinary future satisfies a single-ordinal schedule so the scheduler
  ;; observes the spawn it expects; the scenario result is a function, which
  ;; jolt.sim.trace/canonical-value rejects, so the worker's result encoding
  ;; fails instead of completing.
  (let [worker (future :spawned)]
    @worker
    (fn [] :unencodable)))

(rt/defsim kill-witness {}
  ;; Writes a started witness immediately, then a raw daemon thread sleeps
  ;; past any short test timeout before writing a late witness. A supervisor
  ;; that actually kills this worker on timeout observes the started file but
  ;; never the late one; a supervisor that merely waited out the deadlock
  ;; would let the late witness land. The same [1 0] dependency deadlock as
  ;; `dependent` follows so the process explorer's kill path has something to
  ;; interrupt.
  (let [started-path (System/getenv "JOLT_SIM_STARTED_PATH")
        late-path (System/getenv "JOLT_SIM_LATE_PATH")]
    (spit started-path "started")
    (let [late-writer
          (Thread.
           (fn []
             (Thread/sleep 1500)
             (spit late-path "late")))]
      (.setDaemon late-writer true)
      (.start late-writer))
    (let [a (future :a)
          a-result @a
          b (future :b)
          b-result @b]
      {:a-result a-result
       :b-result b-result})))
