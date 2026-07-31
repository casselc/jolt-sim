(ns jolt.sim.hegel-explore-test
  "Isolated Hegel-to-process-explorer integration. The property runs unchanged
  ordinary futures in fresh ABI-v3 Jolt children, finds the start order that
  violates a deliberately narrow invariant, and replays the same minimized
  schedule without flakiness."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [jolt.sim.explore :as explore]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.hegel-adapter-test]))

(def ^:dynamic *process-config* nil)

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.hegel-explore-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Hegel exploration tests must be run through -main"
        {:type :jolt.sim.hegel-explore-test/not-configured}))))

(deftest hegel-minimizes-and-replays-a-real-future-start-order
  (let [plans
        (explore/schedule-plans
         {:future-count 3 :max-schedules 6})
        failing-schedules (atom [])
        final-schedules (atom [])
        result
        (h/run-test!
         {:test-cases 20
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [outcome
                 (sim-hegel/require-completed!
                  (sim-hegel/run-schedule!
                   (merge
                    (process-config)
                    {:scenario
                     'jolt.sim.fixtures.explore-scenarios/independent-three
                     :timeout-ms 5000
                     :kill-grace-ms 500})
                   plans))
                 schedule (:schedule outcome)
                 start-order
                 (get-in outcome [:result :result :start-order])]
             (when (h/final?)
               (swap! final-schedules conj schedule))
             ;; The first two lexicographic plans start A first and pass. Four
             ;; later plans fail. With the pinned engine and seed, exploration
             ;; first finds [1 2 0], then genuinely shrinks to the earliest
             ;; failing plan [1 0 2] before final replay.
             (when-not (= :a (first start-order))
               (swap! failing-schedules conj schedule)
               (throw
                (ex-info
                 "future A did not start first"
                 {:hegel/origin
                  "jolt.sim.hegel-explore-test/start-order"
                  :schedule schedule
                  :start-order start-order}))))))
        failure (first (:failures result))
        final-exception (-> result :final first :exception)]
    (is (false? (:passed? result)))
    (is (= :failed (:status result)))
    (is (= 1 (:n-failures result)))
    (is (= "jolt.sim.hegel-explore-test/start-order"
           (:origin failure)))
    (is (true? (:reproduced? failure)))
    (is (false? (:flaky? result)))
    (is (= [1 2 0] (first @failing-schedules)))
    (is (= [1 0 2] (-> final-exception ex-data :schedule)))
    (is (= [:b :a :c] (-> final-exception ex-data :start-order)))
    (is (= [[1 0 2]] @final-schedules))))

(deftest hegel-directly-generates-and-minimizes-a-real-future-start-order
  ;; The direct, count-based generator drives the same ABI-v3 fresh-process
  ;; independent-three scenario with no explicit schedule domain. Under the
  ;; fixed seed below, exploration first fails on [1 2 0] (A does not start
  ;; first), then shrinks the Lehmer digit draws toward 0 until the final
  ;; replay re-throws on [1 0 2], and does so without flakiness. (Confirmed
  ;; against the pinned Hegel build by an external probe of this tuple/fmap
  ;; design.)
  (let [failing-schedules (atom [])
        final-schedules (atom [])
        result
        (h/run-test!
         {:test-cases 20
          :seed 3
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [outcome
                 (sim-hegel/require-completed!
                  (sim-hegel/run-direct-schedule!
                   (merge
                    (process-config)
                    {:scenario
                     'jolt.sim.fixtures.explore-scenarios/independent-three
                     :timeout-ms 5000
                     :kill-grace-ms 500})
                   3))
                 schedule (:schedule outcome)
                 start-order
                 (get-in outcome [:result :result :start-order])]
             (when (h/final?)
               (swap! final-schedules conj schedule))
             (when-not (= :a (first start-order))
               (swap! failing-schedules conj schedule)
               (throw
                (ex-info
                 "future A did not start first"
                 {:hegel/origin
                  "jolt.sim.hegel-explore-test/direct-start-order"
                  :schedule schedule
                  :start-order start-order}))))))
        failure (first (:failures result))
        final-exception (-> result :final first :exception)]
    (is (false? (:passed? result)))
    (is (= :failed (:status result)))
    (is (= 1 (:n-failures result)))
    (is (= "jolt.sim.hegel-explore-test/direct-start-order"
           (:origin failure)))
    (is (true? (:reproduced? failure)))
    (is (false? (:flaky? result)))
    (is (= [1 2 0] (first @failing-schedules))
        (str "expected the first failure to be [1 2 0]; saw "
             (pr-str @failing-schedules)))
    (is (= [1 0 2] (-> final-exception ex-data :schedule)))
    (is (= [:b :a :c] (-> final-exception ex-data :start-order)))
    (is (= [[1 0 2]] @final-schedules))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:explore-worker-test"]
                   :dir project-dir}]
          (test/run-tests 'jolt.sim.hegel-adapter-test
                          'jolt.sim.hegel-explore-test))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
