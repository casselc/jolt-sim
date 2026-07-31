(ns jolt.sim.hegel-adapter-test
  "Unit tests for the optional jolt.sim.hegel adapter. These run in-process
  under the :hegel-explore-test alias (which supplies hegel.core and
  hegel.generator) and never spawn a real worker process: the process-explorer
  supervisor is redefined for the injection test, and the property tests only
  draw schedules."
  (:require [clojure.test :refer [deftest is testing]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:private two-future-domain [[0 1] [1 0]])

(def ^:private hegel-run-opts
  {:test-cases 20
   :seed 1
   :database ""
   :report-multiple-failures? false
   :verbosity :quiet})

(def ^:private base-run-config
  {:worker-command ["fake-jolt"]
   :scenario 'demo/scenario
   :timeout-ms 1000
   :dir "/tmp"})

(defn- caught-data [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(deftest schedule-generator-validates-the-domain-synchronously
  (testing "a valid ordered domain returns a generator without throwing"
    (is (some? (sim-hegel/schedule-generator two-future-domain))))
  (testing "malformed domains fail closed with :jolt.sim.hegel/invalid-config"
    (let [cases [[:schedules-not-a-vector :not-a-vector]
                 [:schedules-not-a-vector (list [0 1])]
                 [:empty-schedules []]
                 [:invalid-schedule [[0 1] [1 1]]]
                 [:duplicate-schedule [[0 1] [0 1]]]
                 [:inconsistent-schedule-size [[0 1] [0]]]]]
      (doseq [[reason schedules] cases]
        (let [data (caught-data #(sim-hegel/schedule-generator schedules))]
          (is (= :jolt.sim.hegel/invalid-config (:type data))
              (pr-str {:schedules schedules}))
          (is (= reason (:reason data))
              (pr-str {:schedules schedules})))))))

(deftest sampled-from-is-the-engine-owned-selection-primitive
  ;; Direct API fact: g/sampled-from over a vector yields only domain members,
  ;; and the adapter delegates to it so selection/shrinking stay engine-owned.
  (let [result
        (h/run-test! hegel-run-opts
          (fn [_]
            (let [schedule (h/draw! (g/sampled-from two-future-domain))]
              (when-not (contains? (set two-future-domain) schedule)
                (throw
                 (ex-info "drew a schedule outside the sampled domain"
                          {:hegel/origin
                           "jolt.sim.hegel-adapter-test/sampled-from"
                           :schedule schedule}))))))]
    (is (true? (:passed? result)))))

(deftest schedule-generator-preserves-input-order-under-shrinking
  ;; An always-failing property over the ordered domain minimizes to the first
  ;; input element [0 1]: g/sampled-from shrinks toward index 0 and [0 1] is
  ;; also the lexicographically smallest schedule. An adapter that reordered or
  ;; deduped the domain would shrink elsewhere.
  (let [result
        (h/run-test! hegel-run-opts
          (fn [_]
            (let [schedule (sim-hegel/draw-schedule! two-future-domain)]
              (throw
               (ex-info "order probe always fails"
                        {:hegel/origin
                         "jolt.sim.hegel-adapter-test/order-probe"
                         :schedule schedule})))))]
    (is (false? (:passed? result)))
    (is (= [0 1] (-> result :final first :exception ex-data :schedule)))))

(deftest run-schedule-injects-exactly-one-drawn-schedule
  ;; The draw and supervisor are wrapped so no real worker is spawned and both
  ;; calls are observable. Each property case must make exactly one draw and
  ;; one supervisor call, handing the drawn schedule through unchanged.
  (let [real-draw sim-hegel/draw-schedule!
        draws-per-case (volatile! 0)
        injected (atom [])
        per-case (volatile! 0)
        counted-draw
        (fn [schedules]
          (vswap! draws-per-case inc)
          (real-draw schedules))
        fake-run
        (fn [config]
          (vswap! per-case inc)
          (swap! injected conj (:schedule config))
          {:status :completed
           :schedule (:schedule config)
           :result {:start-order []}})]
    (with-redefs [sim-hegel/draw-schedule! counted-draw
                  process-explorer/run-schedule fake-run]
      (let [result
            (h/run-test! hegel-run-opts
              (fn [_]
                (vreset! draws-per-case 0)
                (vreset! per-case 0)
                (let [outcome
                      (sim-hegel/run-schedule!
                       base-run-config two-future-domain)]
                  (when-not (= 1 @draws-per-case)
                    (throw
                     (ex-info
                      "run-schedule! must draw exactly once per case"
                      {:hegel/origin
                       "jolt.sim.hegel-adapter-test/draw-once"
                       :draws @draws-per-case})))
                  (when-not (= 1 @per-case)
                    (throw
                     (ex-info
                      "run-schedule! must invoke the supervisor exactly once per case"
                      {:hegel/origin
                       "jolt.sim.hegel-adapter-test/inject-once"
                       :calls @per-case})))
                  (when-not (= :completed (:status outcome))
                    (throw
                     (ex-info "injected outcome was not completed"
                              {:hegel/origin
                               "jolt.sim.hegel-adapter-test/inject-completed"
                               :outcome outcome}))))))]
        (is (true? (:passed? result)))
        (is (pos? (count @injected)))
        (is (every? (set two-future-domain) @injected))))))

(deftest run-schedule-rejects-a-bad-base-config-before-drawing
  ;; These rejections are synchronous and happen before any draw, so they need
  ;; no active Hegel property.
  (let [not-a-map (caught-data #(sim-hegel/run-schedule! :bad two-future-domain))
        has-schedule
        (caught-data
         #(sim-hegel/run-schedule!
           (assoc base-run-config :schedule [0 1]) two-future-domain))
        has-schedules
        (caught-data
         #(sim-hegel/run-schedule!
           (assoc base-run-config :schedules two-future-domain)
           two-future-domain))
        has-nil-schedule
        (caught-data
         #(sim-hegel/run-schedule!
           (assoc base-run-config :schedule nil) two-future-domain))
        has-nil-schedules
        (caught-data
         #(sim-hegel/run-schedule!
           (assoc base-run-config :schedules nil) two-future-domain))
        has-both
        (caught-data
         #(sim-hegel/run-schedule!
           (assoc base-run-config
                  :schedule [0 1]
                  :schedules two-future-domain)
           two-future-domain))]
    (is (= :jolt.sim.hegel/invalid-config (:type not-a-map)))
    (is (= :config-not-a-map (:reason not-a-map)))
    (is (= :jolt.sim.hegel/invalid-config (:type has-schedule)))
    (is (= :schedule-already-present (:reason has-schedule)))
    (is (= [:schedule] (:keys has-schedule)))
    (is (= :schedule-already-present (:reason has-schedules)))
    (is (= [:schedules] (:keys has-schedules)))
    (is (= :schedule-already-present (:reason has-nil-schedule)))
    (is (= [:schedule] (:keys has-nil-schedule)))
    (is (= :schedule-already-present (:reason has-nil-schedules)))
    (is (= [:schedules] (:keys has-nil-schedules)))
    (is (= [:schedule :schedules] (:keys has-both)))))

(deftest require-completed-returns-an-identical-completed-outcome
  (let [outcome {:status :completed :schedule [0 1] :result {:value 7}}]
    (is (identical? outcome (sim-hegel/require-completed! outcome)))))

(deftest require-completed-throws-a-bounded-typed-failure-for-non-completed
  (let [timeout {:status :timeout
                 :schedule [1 0]
                 :reason :deadline
                 :exit 9
                 :diagnostics {:stdout {}}}
        data (caught-data #(sim-hegel/require-completed! timeout))]
    (is (= :jolt.sim.hegel/non-completed (:type data)))
    (is (= "jolt.sim.hegel/require-completed" (:hegel/origin data)))
    (is (= :timeout (:status data)))
    (is (= [1 0] (:schedule data)))
    (is (= :deadline (:reason data)))
    (is (= 9 (:exit data)))
    (is (not (contains? data :error)))
    (is (not (contains? data :diagnostics)))
    (is (= #{:type :hegel/origin :status :schedule :reason :exit}
           (set (keys data))))))

(deftest only-the-reverse-schedule-fails-and-replays-without-flakiness
  ;; Deterministic failing property: over the two-element domain, only [1 0]
  ;; violates the invariant. The minimized final replay re-throws with that
  ;; exact schedule in its ex-data and is not flaky.
  (let [result
        (h/run-test! hegel-run-opts
          (fn [_]
            (let [schedule (sim-hegel/draw-schedule! two-future-domain)]
              (when (= [1 0] schedule)
                (throw
                 (ex-info "[1 0] is the only failing schedule"
                          {:hegel/origin
                           "jolt.sim.hegel-adapter-test/only-1-0-fails"
                           :schedule schedule}))))))]
    (is (false? (:passed? result)))
    (is (= :failed (:status result)))
    (is (false? (:flaky? result)))
    (is (= [1 0] (-> result :final first :exception ex-data :schedule)))))

(deftest direct-schedule-generator-rejects-a-bad-future-count
  (testing "a positive integer future-count returns a generator without throwing"
    (is (some? (sim-hegel/direct-schedule-generator 1)))
    (is (some? (sim-hegel/direct-schedule-generator 4))))
  (testing "non-positive or non-integer counts fail closed synchronously with evidence"
    (let [cases [[:not-a-positive-integer 0]
                 [:not-a-positive-integer -3]
                 [:not-a-positive-integer 1.5]
                 [:not-a-positive-integer "3"]
                 [:not-a-positive-integer true]
                 [:not-a-positive-integer nil]]]
      (doseq [[reason future-count] cases]
        (let [data (caught-data #(sim-hegel/direct-schedule-generator future-count))]
          (is (= :jolt.sim.hegel/invalid-config (:type data))
              (pr-str {:future-count future-count}))
          (is (= reason (:reason data))
              (pr-str {:future-count future-count}))
          (is (= :future-count (:key data))
              (pr-str {:future-count future-count}))
          (is (= future-count (:value data))
              (pr-str {:future-count future-count})))))))

(deftest direct-generator-produces-exact-permutations
  ;; Over a range of positive future-counts, every directly-generated schedule is
  ;; an exact permutation of 0..N-1 of the right size. No draw is filtered or
  ;; assumed away: bounded integer indices over the remaining ordinals always
  ;; select a valid distinct ordinal.
  (let [result
        (h/run-test! hegel-run-opts
          (fn [_]
            ;; Exercise every promised count on every case; do not rely on a
            ;; generated count happening to cover the whole range.
            (doseq [future-count (range 1 7)]
              (let [schedule
                    (sim-hegel/draw-direct-schedule! future-count)]
                (when-not (future-schedule/valid-schedule? schedule)
                  (throw
                   (ex-info "direct draw was not an exact permutation"
                            {:hegel/origin
                             "jolt.sim.hegel-adapter-test/direct-perm"
                             :future-count future-count
                             :schedule schedule})))
                (when-not (= future-count (count schedule))
                  (throw
                   (ex-info "direct draw had the wrong size"
                            {:hegel/origin
                             "jolt.sim.hegel-adapter-test/direct-size"
                             :future-count future-count
                             :schedule schedule})))))))]
    (is (true? (:passed? result)))))

(deftest direct-generator-covers-n-1-without-a-draw
  ;; N=1 has exactly one permutation and the generator is a pure g/just with no
  ;; integer draw span: calling it outside any Hegel property (with nil) returns
  ;; [0] synchronously. A generator that needed a bounded integer draw could not
  ;; be evaluated this way without an active property, so this detects the
  ;; absence of a draw rather than merely checking the [0] output.
  (let [generator (sim-hegel/direct-schedule-generator 1)
        schedule (generator nil)]
    (is (= [0] schedule))
    (is (future-schedule/valid-schedule? schedule))))

(deftest direct-generator-minimizes-an-always-failure-to-identity
  ;; Every Lehmer digit draw shrinks toward 0; all-zero digits decode to the
  ;; identity permutation. An always-failing property over N=3 therefore
  ;; empirically minimizes to [0 1 2].
  (let [result
        (h/run-test! hegel-run-opts
          (fn [_]
            (let [schedule (sim-hegel/draw-direct-schedule! 3)]
              (throw
               (ex-info "direct order probe always fails"
                        {:hegel/origin
                         "jolt.sim.hegel-adapter-test/direct-identity"
                         :schedule schedule})))))]
    (is (false? (:passed? result)))
    (is (= :failed (:status result)))
    (is (true? (-> result :failures first :reproduced?)))
    (is (false? (:flaky? result)))
    (is (= [0 1 2] (-> result :final first :exception ex-data :schedule)))))

(deftest run-direct-schedule-injects-exactly-one-drawn-schedule
  ;; The direct draw and supervisor are wrapped so no real worker is spawned and
  ;; both calls are observable. Each property case must make exactly one draw and
  ;; one supervisor call, handing the drawn permutation through unchanged.
  (let [real-draw sim-hegel/draw-direct-schedule!
        draws-per-case (volatile! 0)
        drawn (atom [])
        injected (atom [])
        per-case (volatile! 0)
        counted-draw
        (fn [future-count]
          (vswap! draws-per-case inc)
          (let [schedule (real-draw future-count)]
            (swap! drawn conj schedule)
            schedule))
        fake-run
        (fn [config]
          (vswap! per-case inc)
          (swap! injected conj (:schedule config))
          {:status :completed
           :schedule (:schedule config)
           :result {:start-order []}})]
    (with-redefs [sim-hegel/draw-direct-schedule! counted-draw
                  process-explorer/run-schedule fake-run]
      (let [result
            (h/run-test! hegel-run-opts
              (fn [_]
                (vreset! draws-per-case 0)
                (vreset! per-case 0)
                (let [outcome
                      (sim-hegel/run-direct-schedule! base-run-config 2)]
                  (when-not (= 1 @draws-per-case)
                    (throw
                     (ex-info
                      "run-direct-schedule! must draw exactly once per case"
                      {:hegel/origin
                       "jolt.sim.hegel-adapter-test/direct-draw-once"
                       :draws @draws-per-case})))
                  (when-not (= 1 @per-case)
                    (throw
                     (ex-info
                      "run-direct-schedule! must invoke the supervisor exactly once per case"
                      {:hegel/origin
                       "jolt.sim.hegel-adapter-test/direct-inject-once"
                       :calls @per-case})))
                  (when-not (= :completed (:status outcome))
                    (throw
                     (ex-info "injected outcome was not completed"
                              {:hegel/origin
                               "jolt.sim.hegel-adapter-test/direct-inject-completed"
                               :outcome outcome}))))))]
        (is (true? (:passed? result)))
        (is (pos? (count @injected)))
        (is (= @drawn @injected))
        (is (every? future-schedule/valid-schedule? @injected))))))

(deftest run-direct-schedule-rejects-a-bad-base-config-before-drawing
  ;; These rejections are synchronous and happen before any draw, so they need
  ;; no active Hegel property. A bad future-count is validated inside the
  ;; generator before h/draw!, also synchronously.
  (let [not-a-map (caught-data #(sim-hegel/run-direct-schedule! :bad 2))
        has-schedule
        (caught-data
         #(sim-hegel/run-direct-schedule!
           (assoc base-run-config :schedule [0 1]) 2))
        has-schedules
        (caught-data
         #(sim-hegel/run-direct-schedule!
           (assoc base-run-config :schedules two-future-domain) 2))
        bad-count
        (caught-data #(sim-hegel/run-direct-schedule! base-run-config 0))]
    (is (= :jolt.sim.hegel/invalid-config (:type not-a-map)))
    (is (= :config-not-a-map (:reason not-a-map)))
    (is (= :jolt.sim.hegel/invalid-config (:type has-schedule)))
    (is (= :schedule-already-present (:reason has-schedule)))
    (is (= [:schedule] (:keys has-schedule)))
    (is (= :schedule-already-present (:reason has-schedules)))
    (is (= [:schedules] (:keys has-schedules)))
    (is (= :jolt.sim.hegel/invalid-config (:type bad-count)))
    (is (= :not-a-positive-integer (:reason bad-count)))
    (is (= :future-count (:key bad-count)))
    (is (= 0 (:value bad-count)))))
