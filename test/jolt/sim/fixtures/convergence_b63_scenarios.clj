(ns jolt.sim.fixtures.convergence-b63-scenarios
  "Worker-side scenarios for the convergence slice's STEP 4
  (PROGRESSIVE-FORMALISM-DESIGN 7.2b), driven by jolt.sim.convergence-b63-test
  through jolt.sim.process-explorer/run-case.

  `run-b63` re-runs ONE perturb.layercheck fixture — A known-good or B'
  edge-declared, reached through layercheck's own vars exactly as
  perturb.slicecheck does — projects it with
  perturb.layer/semantic-b63-events, and evaluates the B6.3 monitor through
  perturb.semantic/run-monitor with perturb.b63's UNARY spec factory closing
  over the projected declarations. Two ordinary independent futures make the
  supplied exact :future-schedule [1 0] discriminating: under [1 0] the
  second-spawned body must start first, and run-controlled's deterministic
  [:admit 1] [:complete 1] [:admit 0] [:complete 0] admission log is the
  schedule witness. The monitor decision, the observed start order, and a
  same-document agreement check against the incumbent perturb.b63/spec are
  the case's canonical result.

  `run-b63-blocked` is the SUPERVISION CONTROL: the same dependency
  inversion as jolt.sim.fixtures.explore-scenarios/dependent. Under [1 0]
  the first-spawned future cannot be admitted and the second is never
  spawned, so the worker blocks until the supervisor's deadline terminates
  it. A blocked worker is a :timeout outcome — never a deadlock
  classification."
  (:require [jolt.sim.runtime :as rt]
            [perturb.b63 :as b63]
            [perturb.layer :as lay]
            [perturb.layercheck]
            [perturb.semantic :as sem]))

(defn- v [s] (deref (resolve s)))

(defn- convergence-fixture
  "ONE slicecheck fixture, reached through perturb.layercheck's own vars
  rather than rebuilt: a differential test between two inputs measures
  nothing. Returns {:declarations [...] :recorded ...}. Re-recording the
  run here is what makes the fresh worker evaluate the property itself
  rather than trusting a parent-computed trace."
  [selector]
  (let [record-layer (v 'perturb.layercheck/record-layer)]
    (case selector
      :a
      (let [kg ((v 'perturb.layercheck/known-good))]
        {:declarations [(record-layer (:handler kg) [])]
         :recorded (:recorded kg)})

      :b-prime
      (let [ld ((v 'perturb.layercheck/laundering))]
        {:declarations [(record-layer (:handler ld)
                                      (v 'perturb.layercheck/eof-mapping-edge))]
         :recorded (:recorded ld)})

      (throw
       (ex-info
        "unknown convergence fixture selector"
        {:type :jolt.sim.fixtures.convergence-b63-scenarios/unknown-fixture
         :fixture selector})))))

(rt/defsim run-b63 [input] {}
  (let [{:keys [declarations recorded]} (convergence-fixture (:fixture input))
        [case-m events] (lay/semantic-b63-events declarations recorded)
        doc (sem/document case-m events)
        observed (atom [])
        a (future (swap! observed conj :a) :a)
        b (future (swap! observed conj :b) :b)
        ;; The property runs HERE, inside the scheduled scope: the unary
        ;; factory closes over the projected declarations, and the incumbent
        ;; document-case spec must agree with it on this same document.
        factory-decision (sem/run-monitor (b63/spec-for case-m) doc)
        incumbent-decision (sem/run-monitor b63/spec doc)]
    {:fixture (:fixture input)
     :event-count (count events)
     :values [@a @b]
     :start-order @observed
     :monitor factory-decision
     :spec-agreement (= factory-decision incumbent-decision)}))

(rt/defsim run-b63-blocked [input] {}
  ;; Future A is dereferenced before future B is even spawned. Schedule
  ;; [1 0] blocks forever: A's ordinal cannot be admitted until B's ordinal
  ;; finishes, but B is never spawned because this thread is stuck
  ;; dereferencing A first. The supervisor's deadline — never a deadlock
  ;; classifier — is what ends this run.
  (let [a (future :a)
        a-result @a
        b (future :b)
        b-result @b]
    {:fixture (:fixture input)
     :a-result a-result
     :b-result b-result}))
