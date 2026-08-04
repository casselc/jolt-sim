(ns jolt.sim.explore-process-test
  "Process-isolated integration gate for exact future-schedule exploration.

  This namespace is intentionally absent from the shared test runner. Its
  `-main` obtains the canonical sim-enabled Jolt image and project directory from
  the environment, then launches one fresh worker for every schedule."
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.host :as host]
            [jolt.sim.explore :as explore]
            [jolt.sim.explore-worker :as worker]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

;; Completion includes a cold launcher, dependency resolution, and namespace
;; loading on hosted runners; it is not an application deadline. Keep the
;; intentionally blocked control on its shorter, discriminating budget below.
(def ^:private completion-timeout-ms 20000)
(def ^:private expected-block-timeout-ms 5000)
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

(defn- case-config
  ([scenario timeout-ms]
   (case-config scenario timeout-ms {}))
  ([scenario timeout-ms extra]
   (merge
    (process-config)
    {:scenario scenario
     :timeout-ms timeout-ms
     :kill-grace-ms kill-grace-ms}
    extra)))

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

(defn- artifact-path [outcome filename]
  (when-let [dir (:artifact-dir outcome)]
    (str (fs/path dir filename))))

(defn- retained-artifacts-match?
  [outcome {:keys [present absent]}]
  (let [dir (:artifact-dir outcome)
        safe-dir?
        (and (string? dir)
             (.startsWith (.getName (java.io.File. dir))
                          "jolt-sim-explore-"))
        present? (fn [filename]
                   (boolean
                    (and dir (fs/exists? (artifact-path outcome filename)))))
        valid?
        (and safe-dir?
             (fs/exists? dir)
             (every? present? present)
             (not-any? present? absent))]
    (is safe-dir? (str "unsafe or missing artifact directory " (pr-str dir)))
    (is (and dir (fs/exists? dir))
        (str "retained artifact directory must exist " (pr-str dir)))
    (doseq [filename present]
      (is (present? filename)
          (str "expected retained artifact " filename " under " dir)))
    (doseq [filename absent]
      (is (not (present? filename))
          (str "artifact must remain honestly absent: " filename)))
    valid?))

(defn- cleanup-expected-artifacts! [outcome expected?]
  (when-let [dir (:artifact-dir outcome)]
    (if expected?
      (fs/delete-tree dir)
      (println "Retained unexpected process-explorer artifacts at" dir))))

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
            completion-timeout-ms))]
      (is (= [[0 1] [1 0]] schedules))
      (is (= schedules (mapv :schedule outcomes)))
      (is (= [:completed :completed] (mapv :status outcomes)))
      (is (= [nil nil] (mapv :artifact-dir outcomes)))
      (is (= [6 6] (mapv child-abi-version outcomes)))
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
            expected-block-timeout-ms))
          completed (nth outcomes 0)
          timed-out (nth outcomes 1)
          artifacts-ok?
          (retained-artifacts-match?
           timed-out
           {:present ["request.edn" "stdout.log" "stderr.log"]
            :absent ["result.edn"]})]
      (is (= schedules (mapv :schedule outcomes)))
      (is (= [:completed :timeout] (mapv :status outcomes)))
      (is (nil? (:artifact-dir completed)))
      (is (= 6 (child-abi-version completed)))
      (is (= {:a-result :a :b-result :b} (body-result completed)))
      (is (= :deadline (:reason timed-out)))
      (is (not= :deadlock (:status timed-out))
          "a deadline is not a deadlock classification")
      (is (not= :deadlock (:reason timed-out))
          "the timeout reason must remain the neutral deadline label")
      (cleanup-expected-artifacts!
       timed-out
       (and artifacts-ok? (= :timeout (:status timed-out))))))

  (testing "a scenario exception is a failed exploration outcome"
    (let [outcome
          (process-explorer/run-schedule
           (run-config
            'jolt.sim.fixtures.explore-scenarios/fails
            [0]
            completion-timeout-ms))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]})
          retained-request
          (edn/read-string (slurp (artifact-path outcome "request.edn")))
          retained-result
          (worker/decode-result-edn
           [0]
           (slurp (artifact-path outcome "result.edn")))
          content-ok?
          (and (= (worker/request-document
                   'jolt.sim.fixtures.explore-scenarios/fails [0])
                  retained-request)
               (= :failed (:status retained-result))
               (= :jolt.sim.fixtures.explore-scenarios/deliberate-failure
                  (get-in retained-result [:error :data :type])))]
      (is (= :failed (:status outcome)))
      (is (= 0 (:exit outcome)))
      (is (= [0] (:schedule outcome)))
      (is (= :jolt.sim.fixtures.explore-scenarios/deliberate-failure
             (get-in outcome [:error :data :type])))
      (is (= (worker/request-document
              'jolt.sim.fixtures.explore-scenarios/fails [0])
             retained-request))
      (is (= :failed (:status retained-result)))
      (is (= :jolt.sim.fixtures.explore-scenarios/deliberate-failure
             (get-in retained-result [:error :data :type])))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok? content-ok? (= :failed (:status outcome))))))

  (testing "an unencodable scenario result is a worker encoding error"
    (let [outcome
          (process-explorer/run-schedule
           (run-config
            'jolt.sim.fixtures.explore-scenarios/noncanonical
            [0]
            completion-timeout-ms))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]})]
      (is (= :worker-error (:status outcome)))
      (is (= [0] (:schedule outcome)))
      (is (= 0 (:exit outcome)))
      (is (= :result-encoding (get-in outcome [:error :phase])))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok? (= :worker-error (:status outcome))))))

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
               :worker-command [missing-bin "-M:explore-worker-test"]
               :temp-dir temp-dir)
              outcome
              (deref
               (future (process-explorer/run-schedule config))
               3000
               ::bounded-wait-expired)]
          (let [artifacts-ok?
                (and
                 (not= ::bounded-wait-expired outcome)
                 (retained-artifacts-match?
                  outcome
                  {:present ["request.edn"]
                   :absent ["result.edn"]}))]
            (when (and artifacts-ok?
                       (= :worker-error (:status outcome))
                       (= :process-spawn (get-in outcome [:error :phase])))
              (vreset! safe-to-clean? true)))
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
             completion-timeout-ms)
            ;; The supervisor appends request and result paths. `sh -c` places
            ;; them at $1 and $2 after this explicit argument zero.
            :worker-command
            ["sh" "-c" "printf 'not-edn' > \"$2\""
             "jolt-sim-malformed-worker"]))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]})]
      (is (= :worker-error (:status outcome)))
      (is (= :result-protocol (get-in outcome [:error :phase])))
      (is (= 0 (:exit outcome)))
      (is (= "not-edn" (slurp (artifact-path outcome "result.edn"))))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok?
            (= "not-edn" (slurp (artifact-path outcome "result.edn")))
            (= :worker-error (:status outcome))))))

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
            :kill-grace-ms 100))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "stdout.log" "stderr.log"]
            :absent ["result.edn"]})]
      (is (= :timeout (:status outcome)))
      (is (= :deadline (:reason outcome)))
      (is (= 137 (:exit outcome)))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok? (= :timeout (:status outcome))))))

  (testing "the timeout path really kills and reaps the child"
    (let [temp-dir
          (str (fs/create-temp-dir {:prefix "jolt-sim-kill-witness-"}))
          started-path (str (fs/path temp-dir "started"))
          late-path (str (fs/path temp-dir "late"))
          worker-timeout-ms 5000
          late-delay-ms 8000
          post-timeout-wait-ms 8500
          safe-to-clean? (volatile! false)]
      (try
        (let [config
              (assoc
               (run-config
                'jolt.sim.fixtures.explore-scenarios/kill-witness
                [1 0]
                worker-timeout-ms)
               :temp-dir temp-dir
               :extra-env
               {"JOLT_SIM_STARTED_PATH" started-path
                "JOLT_SIM_LATE_PATH" late-path
                "JOLT_SIM_LATE_DELAY_MS" (str late-delay-ms)})
              supervisor-start (host/mono-nanos)
              outcome (process-explorer/run-schedule config)
              supervisor-end (host/mono-nanos)
              started
              (when (fs/exists? started-path)
                (edn/read-string (slurp started-path)))]
          ;; A returned timeout means terminate-and-reap! observed child exit.
          ;; If it throws because death was not observed, retain this directory
          ;; and its witnesses for diagnosis instead of racing a live child.
          (vreset! safe-to-clean? true)
          (is (= :timeout (:status outcome)))
          (is (= :deadline (:reason outcome)))
          (is (fs/exists? started-path)
              "the child must reach the fixture before its deadline")
          (is (= (get-in outcome [:diagnostics :worker-pid]) (:pid started))
              (str "the supervisor must track the actual Jolt worker: "
                   (pr-str {:tracked-pid
                            (get-in outcome [:diagnostics :worker-pid])
                            :started started})))
          ;; Hosted runners may spend more than 750 ms starting a fresh Jolt
          ;; image. Give startup a real budget, then wait longer than the
          ;; fixture's independently configured poison delay after the
          ;; supervisor returns. The poison delay also exceeds the deadline
          ;; plus TERM grace, so it cannot race a correctly reaped child.
          ;; Since the started witness predates the supervisor return, absence
          ;; of the late witness still proves process death.
          (Thread/sleep post-timeout-wait-ms)
          (let [late
                (when (fs/exists? late-path)
                  (edn/read-string (slurp late-path)))]
            (is (nil? late)
                (str
                 "a reaped worker cannot write its delayed witness: "
                 (pr-str
                  {:tracked-pid (get-in outcome [:diagnostics :worker-pid])
                   :exit (:exit outcome)
                   :supervisor-elapsed-ms
                   (/ (- supervisor-end supervisor-start) 1000000.0)
                   :started started
                   :late late
                   :worker-delay-ms
                   (when (and started late)
                     (/ (- (:monotonic-nanos late)
                           (:monotonic-nanos started))
                        1000000.0))})))))
        (finally
          (when (and @safe-to-clean? (fs/exists? temp-dir))
            (fs/delete-tree temp-dir)))))))

(deftest run-case-drives-a-fresh-child-with-canonical-input-and-optional-schedule
  (testing "a fresh no-schedule case round-trips canonical scenario input"
    (let [outcome
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/echoes-input
            completion-timeout-ms
            {:input {:answer 42 :label "ok"}}))]
      (is (= :completed (:status outcome)))
      (is (nil? (:schedule outcome)))
      (is (= 6 (child-abi-version outcome)))
      (is (= {:echoed {:answer 42 :label "ok"}} (body-result outcome)))))

  (testing "one case carries scenario input and a future schedule together"
    (let [input {:workload [:echo]
                 :faults []
                 :payload (byte-array [0 1 127])}
          outcome
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/scheduled-echoes-input
            completion-timeout-ms
            {:schedule [1 0]
             :input input}))
          body (body-result outcome)]
      (is (= :completed (:status outcome)))
      (is (= [1 0] (:schedule outcome)))
      (is (= [:b :a] (:start-order body)))
      (is (= [:a :b] (:values body)))
      (is (= [:echo] (get-in body [:echoed :workload])))
      (is (= [] (get-in body [:echoed :faults])))
      (is (= [0 1 127] (vec (seq (get-in body [:echoed :payload])))))
      (is (= [[:admit 1] [:complete 1]
              [:admit 0] [:complete 0]]
             (get-in outcome [:result :schedule-events])))))

  (testing "no :input defaults to nil, still with no :future-schedule override"
    (let [outcome
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/echoes-input
            completion-timeout-ms))]
      (is (= :completed (:status outcome)))
      (is (= {:echoed nil} (body-result outcome)))))

  (testing "an old-form scenario rejects non-nil input as an invalid case"
    (let [outcome
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/independent
            completion-timeout-ms
            {:input :unexpected}))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]})]
      (is (= :worker-error (:status outcome)))
      (is (nil? (:schedule outcome)))
      (is (= :scenario-input (get-in outcome [:error :phase])))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok?
            (= :scenario-input (get-in outcome [:error :phase]))))))

  (testing "a real input-capable defsim body cannot spoof contract rejection"
    (let [outcome
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/rejection-keyword-collision
            completion-timeout-ms
            {:input {:workload :collision-control}}))
          artifacts-ok?
          (retained-artifacts-match?
           outcome
           {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]})]
      (is (= :failed (:status outcome)))
      (is (= "application deliberately collides with the input-rejection keyword"
             (get-in outcome [:error :message])))
      (is (= :jolt.sim.runtime/scenario-rejects-input
             (get-in outcome [:error :data :type])))
      (is (= {:workload :collision-control}
             (get-in outcome [:error :data :input])))
      (cleanup-expected-artifacts!
       outcome
       (and artifacts-ok? (= :failed (:status outcome)))))))

(def ^:private admission-order-timeout-ms 20000)

(deftest fresh-workers-drive-real-posix-ffi-admission-order
  (testing "poll-then-write and write-then-poll admit real poll/write calls in
            the barrier-fixed arrival order but release in distinct plan
            orders"
    (let [poll-id :jolt.sim.fixtures.ffi-schedule-scenarios/poll
          write-id :jolt.sim.fixtures.ffi-schedule-scenarios/write
          run! (fn [plan-key]
                 (process-explorer/run-case
                  (case-config
                   'jolt.sim.fixtures.ffi-schedule-scenarios/exercise-admission-order
                   admission-order-timeout-ms
                   {:input plan-key})))
          poll-then-write (run! :poll-then-write)
          write-then-poll (run! :write-then-poll)
          evidence (fn [outcome] (:result outcome))
          expected-fixture-result
          {:parked? true
           :awaited []
           :await-completed? true
           :close-results [true false]}]
      (doseq [outcome [poll-then-write write-then-poll]]
        (is (= :completed (:status outcome)))
        (is (nil? (:schedule outcome))))
      (is (= expected-fixture-result
             (:fixture-result (evidence poll-then-write))))
      (is (= expected-fixture-result
             (:fixture-result (evidence write-then-poll))))
      (is (= [poll-id write-id]
             (get-in (evidence poll-then-write)
                     [:coordinator-diagnostics :arrival-order])))
      (is (= [poll-id write-id]
             (get-in (evidence write-then-poll)
                     [:coordinator-diagnostics :arrival-order])))
      (is (= [poll-id write-id]
             (get-in (evidence poll-then-write)
                     [:coordinator-diagnostics :release-order]))
          "poll-then-write releases poll's gate before write's")
      (is (= [write-id poll-id]
             (get-in (evidence write-then-poll)
                     [:coordinator-diagnostics :release-order]))
          "write-then-poll releases write's gate before poll's")
      (is (= [[:release poll-id] [:release write-id]]
             (:release-evidence (evidence poll-then-write))))
      (is (= [[:release write-id] [:release poll-id]]
             (:release-evidence (evidence write-then-poll))))
      (doseq [outcome [poll-then-write write-then-poll]]
        (let [diag (:coordinator-diagnostics (evidence outcome))]
          (is (zero? (:in-flight diag)))
          (is (false? (:aborted? diag)))
          (is (= #{poll-id write-id} (:completed diag)))))
      (doseq [outcome [poll-then-write write-then-poll]]
        (let [routes (:native-routes (evidence outcome))]
          ;; The coordinator selects occurrence 1 of each key. Later native
          ;; retries and close-wake writes are intentionally not schedule
          ;; evidence: poll may retry after EINTR, while jolt-net may omit its
          ;; terminal write when the acknowledged clear wake already let the
          ;; waiter retire the transport.
          (is (<= 2 (count routes)))
          (is (every? #(= :native (:route %)) routes))
          (is (pos? (count (filter #(= "poll" (:symbol %)) routes))))
          (is (pos? (count (filter #(= "write" (:symbol %)) routes)))))))))

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
