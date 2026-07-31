(ns jolt.sim.explore-process-test
  "Process-isolated integration gate for exact future-schedule exploration.

  This namespace is intentionally absent from the shared test runner. Its
  `-main` obtains the canonical sim-enabled Jolt image and project directory from
  the environment, then launches one fresh worker for every schedule."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.explore :as explore]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

(def ^:private scenario-timeout-ms 5000)
(def ^:private kill-grace-ms 500)

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.explore-process-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Process exploration tests must be run through -main"
        {:type :jolt.sim.explore-process-test/not-configured}))))

(defn- run-config [scenario schedule timeout-ms]
  (merge
   (process-config)
   {:scenario scenario
    :schedule schedule
    :timeout-ms timeout-ms
    :kill-grace-ms kill-grace-ms}))

(defn- explore-config [scenario schedules timeout-ms]
  (merge
   (process-config)
   {:scenario scenario
    :schedules schedules
    :timeout-ms timeout-ms
    :kill-grace-ms kill-grace-ms}))

(defn- body-result [outcome]
  (get-in outcome [:result :result]))

(defn- child-abi-version [outcome]
  (get-in outcome [:result :capabilities :abi-version]))

(deftest fresh-workers-explore-canonical-worker-lifecycle-scenarios
  (testing "independent futures complete in plan order and discriminate starts"
    (let [schedules
          (explore/schedule-plans
           {:future-count 2 :max-schedules 2})
          outcomes
          (process-explorer/explore
           (explore-config
            'jolt.sim.fixtures.explore-scenarios/independent
            schedules
            scenario-timeout-ms))]
      (is (= [[0 1] [1 0]] schedules))
      (is (= schedules (mapv :schedule outcomes)))
      (is (= [:completed :completed] (mapv :status outcomes)))
      (is (= [4 4] (mapv child-abi-version outcomes)))
      (is (= [[:a :b] [:b :a]]
             (mapv (fn [outcome]
                     (:start-order (body-result outcome)))
                   outcomes)))))

  (testing "a dependency-compatible plan completes and its inverse times out"
    (let [schedules [[0 1] [1 0]]
          outcomes
          (process-explorer/explore
           (explore-config
            'jolt.sim.fixtures.explore-scenarios/dependent
            schedules
            scenario-timeout-ms))
          completed (nth outcomes 0)
          timed-out (nth outcomes 1)]
      (is (= schedules (mapv :schedule outcomes)))
      (is (= [:completed :timeout] (mapv :status outcomes)))
      (is (= 4 (child-abi-version completed)))
      (is (= {:a-result :a :b-result :b} (body-result completed)))
      (is (= :deadline (:reason timed-out)))
      (is (not= :deadlock (:status timed-out))
          "a deadline is not a deadlock classification")
      (is (not= :deadlock (:reason timed-out))
          "the timeout reason must remain the neutral deadline label")))

  (testing "a scenario exception is a failed exploration outcome"
    (let [outcome
          (process-explorer/run-schedule
           (run-config
            'jolt.sim.fixtures.explore-scenarios/fails
            [0]
            scenario-timeout-ms))]
      (is (= :failed (:status outcome)))
      (is (= [0] (:schedule outcome)))
      (is (= :jolt.sim.fixtures.explore-scenarios/deliberate-failure
             (get-in outcome [:error :data :type])))))

  (testing "an unencodable scenario result is a worker encoding error"
    (let [outcome
          (process-explorer/run-schedule
           (run-config
            'jolt.sim.fixtures.explore-scenarios/noncanonical
            [0]
            scenario-timeout-ms))]
      (is (= :worker-error (:status outcome)))
      (is (= [0] (:schedule outcome)))
      (is (= :result-encoding (get-in outcome [:error :phase])))))

  (testing "a missing executable returns a bounded spawn error"
    (let [temp-dir
          (str (fs/create-temp-dir
                {:prefix "jolt-sim-missing-executable-"}))
          safe-to-clean? (volatile! false)]
      (try
        (let [missing-bin (str (fs/path temp-dir "missing-jolt"))
              config
              (assoc
               (run-config
                'jolt.sim.fixtures.explore-scenarios/independent
                [0]
                750)
               :worker-command [missing-bin "-M:explore-worker-test"])
              outcome
              (deref
               (future (process-explorer/run-schedule config))
               3000
               ::bounded-wait-expired)]
          (when-not (= ::bounded-wait-expired outcome)
            (vreset! safe-to-clean? true))
          (is (not= ::bounded-wait-expired outcome)
              "spawning a missing executable must return within the watchdog")
          (is (= :worker-error (:status outcome)))
          (is (= :process-spawn (get-in outcome [:error :phase]))))
        (finally
          (when (and @safe-to-clean? (fs/exists? temp-dir))
            (fs/delete-tree temp-dir))))))

  (testing "a zero-exit worker with malformed output is a protocol error"
    (let [outcome
          (process-explorer/run-schedule
           (assoc
            (run-config
             'jolt.sim.fixtures.explore-scenarios/independent
             [0]
             scenario-timeout-ms)
            ;; The supervisor appends request and result paths. `sh -c` places
            ;; them at $1 and $2 after this explicit argument zero.
            :worker-command
            ["sh" "-c" "printf 'not-edn' > \"$2\""
             "jolt-sim-malformed-worker"]))]
      (is (= :worker-error (:status outcome)))
      (is (= :result-protocol (get-in outcome [:error :phase])))
      (is (= 0 (:exit outcome)))))

  (testing "a TERM-resistant worker is forcibly killed and reaped"
    (let [outcome
          (process-explorer/run-schedule
           (assoc
            (run-config
             'jolt.sim.fixtures.explore-scenarios/independent
             [0]
             100)
            ;; Ignored signal dispositions survive exec on POSIX. The shell
            ;; becomes sleep, so SIGKILL targets the worker itself rather than
            ;; leaving a descendant behind.
            :worker-command
            ["sh" "-c" "trap '' TERM; exec sleep 10"
             "jolt-sim-term-resistant-worker"]
            :kill-grace-ms 100))]
      (is (= :timeout (:status outcome)))
      (is (= :deadline (:reason outcome)))
      (is (= 137 (:exit outcome)))))

  (testing "the timeout path really kills and reaps the child"
    (let [temp-dir
          (str (fs/create-temp-dir {:prefix "jolt-sim-kill-witness-"}))
          started-path (str (fs/path temp-dir "started"))
          late-path (str (fs/path temp-dir "late"))
          safe-to-clean? (volatile! false)]
      (try
        (let [config
              (assoc
               (run-config
                'jolt.sim.fixtures.explore-scenarios/kill-witness
                [1 0]
                750)
               :temp-dir temp-dir
               :extra-env
               {"JOLT_SIM_STARTED_PATH" started-path
                "JOLT_SIM_LATE_PATH" late-path})
              outcome (process-explorer/run-schedule config)]
          ;; A returned timeout means terminate-and-reap! observed child exit.
          ;; If it throws because death was not observed, retain this directory
          ;; and its witnesses for diagnosis instead of racing a live child.
          (vreset! safe-to-clean? true)
          (is (= :timeout (:status outcome)))
          (is (= :deadline (:reason outcome)))
          (is (fs/exists? started-path)
              "the child must reach the fixture before its deadline")
          ;; The fixture's daemon waits 1500 ms from its started witness. Wait
          ;; longer than that after the supervisor returns, so absence of the
          ;; late witness demonstrates process death rather than a short wait.
          (Thread/sleep 1750)
          (is (not (fs/exists? late-path))
              "a reaped worker cannot write its delayed witness"))
        (finally
          (when (and @safe-to-clean? (fs/exists? temp-dir))
            (fs/delete-tree temp-dir)))))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:explore-worker-test"]
                   :dir project-dir}]
          (test/run-tests 'jolt.sim.explore-process-test))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
