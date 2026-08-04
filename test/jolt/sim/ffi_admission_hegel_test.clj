(ns jolt.sim.ffi-admission-hegel-test
  "Bounded Hegel slice over the UNCHANGED
  jolt.sim.fixtures.ffi-schedule-scenarios/exercise-admission-order scenario.

  Hegel owns generation over exactly the two valid admission inputs
  (:poll-then-write and :write-then-poll); each drawn case runs once in a fresh
  sim-enabled Jolt worker through jolt.sim.process-explorer/run-case (protocol-v2
  :input). The worker loads the unchanged ffi-schedule-scenarios fixture, which
  runs the unchanged jolt.net poller application against the real OS under
  :ffi-mode :hybrid with only poll(2) occurrence 1 and write(2) occurrence 1
  admission-ordered by jolt.sim.ffi-schedule.

  Each completed case asserts ordinary fixture success, the exact plan-order
  release evidence for the drawn input, a completed clean coordinator state, and
  that every projected poll/write effect-trace entry routes :native with at
  least one poll and one write symbol. Exact syscall totals are deliberately not
  asserted: poll may retry after EINTR and a redundant terminal write may be
  omitted, so only the symbol set and the all-:native fact are canonical.

  A bounded same-seed replay check confirms identical generated plan choices
  across two successful real-worker runs. A separate, deliberately-failing
  pure property demonstrates that Hegel's final replay isolates the single
  violating plan without weakening the real passing property.

  This parent namespace never requires jolt.sim.fixtures.ffi-schedule-scenarios
  (jolt.net is not on the :hegel-explore-test classpath): the scenario is named
  by symbol and its step ids are reconstructed as keywords. Process-isolated
  like :hegel-explore-test: each case spawns a fresh worker, and the parent
  supplies the worker command through the shared *process-config* bound by
  jolt.sim.hegel-explore-test/-main."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

(def ^:private scenario-ns "jolt.sim.fixtures.ffi-schedule-scenarios")

(def ^:private scenario-sym
  'jolt.sim.fixtures.ffi-schedule-scenarios/exercise-admission-order)

;; Reconstructed step ids matching ::poll / ::write in the fixture namespace.
;; This parent does not load that namespace (jolt.net is absent here); the
;; worker child resolves it under :explore-worker-test.
(def ^:private poll-id (keyword scenario-ns "poll"))
(def ^:private write-id (keyword scenario-ns "write"))

;; Exactly the two valid admission inputs. Generation is engine-owned via
;; g/sampled-from, which shrinks toward index 0 (:poll-then-write).
(def ^:private admission-inputs [:poll-then-write :write-then-poll])

;; Proven deterministic fixture result for both plans (see
;; jolt.sim.explore-process-test/fresh-workers-drive-real-posix-ffi-admission-order).
;; awaited is [] because close! retires the transport with no ready registrations;
;; close-results is [true false] because the second close is a no-op on a retired poller.
(def ^:private expected-fixture-result
  {:parked? true
   :awaited []
   :await-completed? true
   :close-results [true false]})

(def ^:private expected-release-evidence
  {:poll-then-write [[:release poll-id] [:release write-id]]
   :write-then-poll [[:release write-id] [:release poll-id]]})

(def ^:private expected-release-order
  {:poll-then-write [poll-id write-id]
   :write-then-poll [write-id poll-id]})

(defn- input-generator
  "Returns a Hegel generator over exactly the two valid admission inputs.
  Selection and shrinking stay engine-owned: this wrapper performs no selection
  of its own."
  []
  (g/sampled-from admission-inputs))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Hegel ffi-admission tests must be run through jolt.sim.hegel-explore-test/-main"
        {:type :jolt.sim.ffi-admission-hegel-test/not-configured}))))

(defn- require-completed-carrying-input!
  "Wraps jolt.sim.hegel/require-completed! so a non-:completed outcome fails
  fast with the adapter's stable bounded supervisor fields AND the drawn
  admission input, giving Hegel the exact case data to replay and shrink. The
  underlying :hegel/origin is preserved."
  [outcome input]
  (try
    (sim-hegel/require-completed! outcome)
    (catch :default error
      (let [data (ex-data error)]
        (if (and (map? data) (not (contains? data :input)))
          (throw (ex-info (ex-message error) (assoc data :input input)))
          (throw error))))))

(defn- violation
  "Throws the stable typed property failure for one assertion site, carrying
  :hegel/origin, the drawn admission input, and a small bounded :actual
  projection of the evidence that failed. Hegel replays and shrinks against
  this ex-data."
  [origin input actual]
  (throw
   (ex-info
    "jolt.sim.ffi-admission-hegel-test property invariant violated"
    {:hegel/origin origin
     :input input
     :actual actual})))

(defn- check-case!
  "Runs one fresh-worker case for the drawn admission `input`, asserts the full
  invariant set, and returns the raw evidence map on success. Throws on the
  first violation (rather than returning false): throwing is required inside a
  property passed to a custom Hegel runner, and the thrown ex-data carries the
  stable :hegel/origin and the drawn input so Hegel can replay and shrink."
  [input]
  (let [base-config
        (merge (process-config)
               {:scenario scenario-sym
                :timeout-ms 20000
                :kill-grace-ms 500})
        outcome (process-explorer/run-case
                 (assoc base-config :input input))
        completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)]
    (when-not (map? evidence)
      (violation "jolt.sim.ffi-admission-hegel-test/evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [fixture (:fixture-result evidence)
          release (:release-evidence evidence)
          diag (:coordinator-diagnostics evidence)
          routes (:native-routes evidence)]
      ;; Ordinary fixture success: the unchanged jolt.net poller reached its
      ;; parked native wait, was woken by the close, and drained both closes.
      (when-not (= expected-fixture-result fixture)
        (violation "jolt.sim.ffi-admission-hegel-test/fixture-result"
                   input
                   {:fixture fixture}))
      ;; Exact release evidence for the drawn input: release is plan-driven and
      ;; therefore an exact replay witness (unlike arrival, which is observed).
      (when-not (= (get expected-release-evidence input) release)
        (violation "jolt.sim.ffi-admission-hegel-test/release-evidence"
                   input
                   {:release release
                    :expected (get expected-release-evidence input)}))
      ;; Completed clean coordinator state: nothing in flight, not aborted,
      ;; both planned steps completed, and release-order matches the plan.
      (when-not (zero? (:in-flight diag))
        (violation "jolt.sim.ffi-admission-hegel-test/in-flight"
                   input
                   {:in-flight (:in-flight diag)}))
      (when-not (false? (:aborted? diag))
        (violation "jolt.sim.ffi-admission-hegel-test/aborted"
                   input
                   {:aborted? (:aborted? diag)}))
      (when-not (= #{poll-id write-id} (:completed diag))
        (violation "jolt.sim.ffi-admission-hegel-test/completed"
                   input
                   {:completed (:completed diag)}))
      (when-not (= (get expected-release-order input) (:release-order diag))
        (violation "jolt.sim.ffi-admission-hegel-test/release-order"
                   input
                   {:release-order (:release-order diag)
                    :expected (get expected-release-order input)}))
      ;; Every projected poll/write call routes :native with at least one poll
      ;; and one write. Exact totals are deliberately not asserted: poll may
      ;; retry and a redundant terminal write may be omitted.
      (when-not (and (seq routes)
                     (every? #(= :native (:route %)) routes))
        (violation "jolt.sim.ffi-admission-hegel-test/native-routes"
                   input
                   {:routes routes}))
      (let [symbols (set (map :symbol routes))]
        (when-not (contains? symbols "poll")
          (violation "jolt.sim.ffi-admission-hegel-test/native-poll"
                     input
                     {:routes routes}))
        (when-not (contains? symbols "write")
          (violation "jolt.sim.ffi-admission-hegel-test/native-write"
                     input
                     {:routes routes})))
      evidence)))

(def ^:private hegel-run-opts
  {:test-cases 20
   ;; Each case launches a fresh isolated Jolt worker, so generation is
   ;; intentionally slower than Hegel's unit-test health threshold.
   :suppress-health-checks [:too-slow]
   :seed 1
   :database ""
   :report-multiple-failures? false
   :verbosity :quiet})

(deftest hegel-ffi-admission-holds-over-both-plans
  ;; The real passing property. Hegel owns selection over exactly the two valid
  ;; admission inputs; each drawn case runs the unchanged fixture once in a
  ;; fresh worker and must satisfy the full invariant set without flakiness.
  (let [seen-inputs (atom #{})
        result
        (h/run-test!
         hegel-run-opts
         (fn [_]
           (let [input (h/draw! (input-generator) "admission-input")]
             (check-case! input)
             (swap! seen-inputs conj input)
             nil)))]
    (is (true? (:passed? result))
        (pr-str {:status (:status result)
                 :n-failures (:n-failures result)
                 :flaky? (:flaky? result)
                 :failures (:failures result)
                 :final (:final result)}))
    (is (false? (:flaky? result))
        (pr-str {:flaky? (:flaky? result)
                 :observed-failures (:observed-failures result)}))
    (is (= (set admission-inputs) @seen-inputs)
        (str "Hegel did not exercise both admission plans: "
             (pr-str @seen-inputs)))))

(deftest same-seed-replays-produce-identical-plan-choices
  ;; Bounded replay witness: two independent successful real-worker runs under
  ;; the same seed draw the same admission-input sequence. The application and
  ;; release invariants are checked inside check-case! on every case, while this
  ;; assertion stays narrowly about replay choices and makes no claim that
  ;; intentionally variable native diagnostics are deterministic.
  (let [run-opts (fn [seed]
                   {:test-cases 6
                    :suppress-health-checks [:too-slow]
                    :seed seed
                    :database ""
                    :report-multiple-failures? false
                    :verbosity :quiet})
        collect
        (fn [seed]
          (let [seen (atom [])]
            (let [result
                  (h/run-test!
                   (run-opts seed)
                   (fn [_]
                     (let [input (h/draw! (input-generator) "admission-input")
                           _ (check-case! input)]
                       (swap! seen conj input)
                       nil)))]
              {:result result :seen @seen})))
        {r1 :result seen-1 :seen} (collect 5)
        {r2 :result seen-2 :seen} (collect 5)]
    (is (true? (:passed? r1))
        (pr-str {:label "first-replay"
                 :status (:status r1)
                 :n-failures (:n-failures r1)
                 :flaky? (:flaky? r1)
                 :error (:error r1)
                 :failures (:failures r1)
                 :observed-failures (:observed-failures r1)
                 :seen seen-1}))
    (is (true? (:passed? r2))
        (pr-str {:label "second-replay"
                 :status (:status r2)
                 :n-failures (:n-failures r2)
                 :flaky? (:flaky? r2)
                 :error (:error r2)
                 :failures (:failures r2)
                 :observed-failures (:observed-failures r2)
                 :seen seen-2}))
    (is (= seen-1 seen-2)
        (str "same-seed plan choices diverged across replays: "
             (pr-str {:first seen-1 :second seen-2})))
    (is (= (set admission-inputs) (set seen-1))
        (str "same-seed replay did not cover both admission plans: "
             (pr-str seen-1)))))

(deftest controlled-failure-isolates-the-only-violating-plan
  ;; A deliberately-failing PURE property (no worker spawn) over the same
  ;; two-element admission-input domain. :write-then-poll is the only violating
  ;; plan; :poll-then-write satisfies the artificial invariant. This mirrors
  ;; the proven jolt.sim.hegel-adapter-test/only-the-reverse-schedule-fails
  ;; shape: a 2-element sampled-from domain that fails only for index 1
  ;; minimizes to index 1 and replays it without flakiness. It demonstrates
  ;; Hegel's final-replay machinery over the exact domain used by the real
  ;; passing property without weakening that property's strict invariants.
  (let [result
        (h/run-test!
         {:test-cases 20
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [input (h/draw! (input-generator) "admission-input")]
             (when (= input :write-then-poll)
               (throw
                (ex-info
                 ":write-then-poll is the only violating admission plan"
                 {:hegel/origin
                  "jolt.sim.ffi-admission-hegel-test/only-violating-plan"
                  :input input}))))))]
    (is (false? (:passed? result)))
    (is (= :failed (:status result)))
    (is (= 1 (:n-failures result)))
    (is (= "jolt.sim.ffi-admission-hegel-test/only-violating-plan"
           (-> result :failures first :origin)))
    (is (true? (-> result :failures first :reproduced?)))
    (is (false? (:flaky? result)))
    (is (= :write-then-poll
           (-> result :final first :exception ex-data :input)))))
