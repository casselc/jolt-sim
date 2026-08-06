(ns jolt.sim.explore-process-test
  "Process-isolated integration gate for exact future-schedule exploration.

  This namespace is intentionally absent from the shared test runner. Its
  `-main` obtains the canonical sim-enabled Jolt image and project directory from
  the environment, then launches one fresh worker for every schedule."
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.host :as host]
            [jolt.sim.activity :as activity]
            [jolt.sim.explore :as explore]
            [jolt.sim.explore-worker :as worker]
            [jolt.sim.journal :as journal]
            [jolt.sim.journal-file :as journal-file]
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.trace :as trace]))

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
    :startup-timeout-ms completion-timeout-ms
    :timeout-ms timeout-ms
    :kill-grace-ms kill-grace-ms}))

(defn- case-config
  ([scenario timeout-ms]
   (case-config scenario timeout-ms {}))
  ([scenario timeout-ms extra]
   (merge
    (process-config)
    {:scenario scenario
     :startup-timeout-ms completion-timeout-ms
     :timeout-ms timeout-ms
     :kill-grace-ms kill-grace-ms}
    extra)))

(defn- explore-config [scenario schedules timeout-ms]
  (merge
   (process-config)
   {:scenario scenario
    :schedules schedules
    :startup-timeout-ms completion-timeout-ms
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
           (dissoc
            (assoc
             (run-config
              'jolt.sim.fixtures.explore-scenarios/independent
              [0]
              100)
             ;; Ignored signal dispositions survive exec on POSIX. The shell
             ;; becomes sleep, so SIGKILL targets the worker itself rather than
             ;; leaving a descendant behind. This synthetic worker cannot
             ;; participate in the Jolt ready-marker handshake.
             :worker-command
             ["sh" "-c" "trap '' TERM; exec sleep 10"
              "jolt-sim-term-resistant-worker"]
             :kill-grace-ms 100)
            :startup-timeout-ms))
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

;;; ---------------------------------------------------------------------------
;;; Opt-in worker lifecycle activity journal
;;; ---------------------------------------------------------------------------

(defn- ex-data-of [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(defn- read-activity-journal
  "Reads one retained activity journal image and returns its header run-id,
  recovery failure reason, and decoded lifecycle event vectors."
  [path]
  (let [file (java.io.File. path)
        length (.length file)]
    (when (and (fs/exists? path)
               (<= length activity/max-image-bytes))
      (let [stream (java.io.FileInputStream. path)]
        (try
          (let [buffer (byte-array length)
                filled (loop [offset 0]
                         (if (>= offset length)
                           offset
                           (let [read-count
                                 (.read stream buffer offset (- length offset))]
                             (if (neg? read-count)
                               offset
                               (recur (+ offset read-count))))))
                image (java.util.Arrays/copyOf buffer filled)
                recovery (journal/recover image)]
            {:run-id (java.util.Arrays/copyOfRange image 16 32)
             :failure-reason (:failure-reason recovery)
             :events (mapv (fn [record]
                             (edn/read-string (String. (:payload record) "UTF-8")))
                           (:records recovery))})
          (finally (.close stream)))))))

(deftest activity-journal-config-is-rejected-fail-closed
  (let [base (case-config
              'jolt.sim.fixtures.explore-scenarios/echoes-input
              completion-timeout-ms)
        missing-retention
        (ex-data-of
         #(process-explorer/run-case
           (assoc base :activity-journal? true)))
        explicit-false-retention
        (ex-data-of
         #(process-explorer/run-case
           (assoc base :activity-journal? true
                  :retain-completed-artifacts? false)))
        run-schedule-missing-retention
        (ex-data-of
         #(process-explorer/run-schedule
           (assoc (run-config
                   'jolt.sim.fixtures.explore-scenarios/fails [0]
                   completion-timeout-ms)
                  :activity-journal? true)))
        non-boolean
        (ex-data-of
         #(process-explorer/run-case
           (assoc base :activity-journal? :yes
                  :retain-completed-artifacts? true)))
        collision-enabled
        (ex-data-of
         #(process-explorer/run-case
           (assoc base :activity-journal? true
                  :retain-completed-artifacts? true
                  :extra-env {worker/activity-journal-env-key
                              "/tmp/caller-supplied-activity.journal"})))
        collision-disabled
        (ex-data-of
         #(process-explorer/run-case
           (assoc base :extra-env {worker/activity-journal-env-key
                                   "/tmp/caller-supplied-activity.journal"})))
        process-options-var
        (resolve 'jolt.sim.process-explorer/process-options)
        disabled-process-options
        (@process-options-var base "stdout.log" "stderr.log" nil)]
    (is (= "JOLT_SIM_ACTIVITY_JOURNAL_PATH" worker/activity-journal-env-key)
        "the single reserved child environment key is exact")
    (is (= :jolt.sim.process-explorer/invalid-config
           (:type missing-retention)))
    (is (= :activity-journal-requires-retention (:reason missing-retention)))
    (is (= :activity-journal-requires-retention
           (:reason explicit-false-retention)))
    (is (= :activity-journal-requires-retention
           (:reason run-schedule-missing-retention)))
    (is (= :invalid-activity-journal (:reason non-boolean)))
    (is (= :yes (:value non-boolean)))
    (is (= :jolt.sim.process-explorer/invalid-config (:type collision-enabled)))
    (is (= :reserved-activity-env-key (:reason collision-enabled)))
    (is (= :reserved-activity-env-key (:reason collision-disabled))
        "the reserved key is rejected even with the journal disabled")
    (is (= ""
           (get-in disabled-process-options
                   [:extra-env worker/activity-journal-env-key]))
        "disabled children explicitly shadow an ambient reserved key")))

(deftest activity-run-id-is-deterministic-valid-and-path-derived
  (let [run-id-var (resolve 'jolt.sim.explore-worker/activity-run-id)
        first-id (@run-id-var "/tmp/jolt-sim-explore-a/activity.journal")
        repeated (@run-id-var "/tmp/jolt-sim-explore-a/activity.journal")
        other (@run-id-var "/tmp/jolt-sim-explore-b/activity.journal")]
    (is (bytes? first-id))
    (is (= 16 (alength first-id)))
    (is (= (seq first-id) (seq repeated))
        "the same trusted run path derives the same run-id")
    (is (not= (seq first-id) (seq other))
        "distinct run directories derive distinct run-ids")))

(deftest disabled-activity-journal-creates-no-file-and-changes-nothing
  (let [outcome
        (process-explorer/run-case
         (case-config
          'jolt.sim.fixtures.explore-scenarios/echoes-input
          completion-timeout-ms
          {:retain-completed-artifacts? true}))
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"]
          :absent ["activity.journal"]})]
    (is (= :completed (:status outcome)))
    (is (= 0 (:exit outcome)))
    (is (nil? (:activity outcome)))
    (is (= {:echoed nil} (body-result outcome)))
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? (= :completed (:status outcome))))))

(deftest enabled-activity-journal-records-a-completed-lifecycle
  (let [scenario 'jolt.sim.fixtures.explore-scenarios/echoes-input
        outcome
        (process-explorer/run-case
         (case-config
          scenario
          completion-timeout-ms
          {:activity-journal? true
           :retain-completed-artifacts? true}))
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"
                    "activity.journal"]})
        journal-path (artifact-path outcome "activity.journal")
        recovery (when journal-path (read-activity-journal journal-path))
        run-id-var (resolve 'jolt.sim.explore-worker/activity-run-id)
        expected-run-id (when journal-path (@run-id-var journal-path))
        events-ok?
        (and (some? recovery)
             (nil? (:failure-reason recovery))
             (= [[:jolt.sim.explore/scenario-started nil nil
                  {:scenario scenario}]
                 [:jolt.sim.explore/scenario-completed nil nil
                  {:scenario scenario}]]
                (:events recovery)))
        run-id-ok?
        (and (some? recovery)
             (= (seq expected-run-id) (seq (:run-id recovery))))
        attached-events (mapv :event (get-in outcome [:activity :events]))
        later-page (process-explorer/read-activity-page outcome 2)
        activity-ok?
        (and (= :complete (get-in outcome [:activity :recovery :status]))
             (nil? (get-in outcome [:activity :observer-status]))
             (= (:events recovery) attached-events)
             (= {:cursor 2 :next-cursor 2 :remaining? false :events []}
                (select-keys later-page
                             [:cursor :next-cursor :remaining? :events])))]
    (is (= :completed (:status outcome)))
    (is (= 0 (:exit outcome)))
    (is (some? (:artifact-dir outcome))
        "retention is mandatory so the journal survives for polling")
    (is (true? activity-ok?)
        "the parent attaches the complete prefix and supports the end cursor")
    (is (= {:echoed nil} (body-result outcome))
        "ordinary result behavior is preserved")
    (is (true? events-ok?)
        (str "journal must hold exactly the started/completed lifecycle: "
             (pr-str (:events recovery))))
    (is (true? run-id-ok?)
        "the header run-id is the deterministic path-derived 16-byte id")
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? events-ok? run-id-ok? activity-ok?
          (= :completed (:status outcome))))))

(deftest enabled-activity-journal-records-a-failed-lifecycle-with-primary-error
  (let [scenario 'jolt.sim.fixtures.explore-scenarios/fails
        outcome
        (process-explorer/run-case
         (case-config
          scenario
          completion-timeout-ms
          {:activity-journal? true
           :retain-completed-artifacts? true}))
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"
                    "activity.journal"]})
        journal-path (artifact-path outcome "activity.journal")
        recovery (when journal-path (read-activity-journal journal-path))
        events-ok?
        (and (some? recovery)
             (nil? (:failure-reason recovery))
             (= [[:jolt.sim.explore/scenario-started nil nil
                  {:scenario scenario}]
                 [:jolt.sim.explore/scenario-failed nil nil
                  {:scenario scenario}]]
                (:events recovery)))
        activity-ok?
        (and (= :complete (get-in outcome [:activity :recovery :status]))
             (nil? (get-in outcome [:activity :observer-status]))
             (= (:events recovery)
                (mapv :event (get-in outcome [:activity :events]))))]
    (is (= :failed (:status outcome)))
    (is (= 0 (:exit outcome)))
    (is (= :jolt.sim.fixtures.explore-scenarios/deliberate-failure
           (get-in outcome [:error :data :type]))
        "the original application failure remains the primary outcome error")
    (is (true? activity-ok?)
        "the application failure stays primary beside a complete journal")
    (is (true? events-ok?)
        (str "journal must hold exactly the started/failed lifecycle: "
             (pr-str (:events recovery))))
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? events-ok? activity-ok?
          (= :failed (:status outcome))))))

(deftest parent-activity-recovery-failure-cannot-mask-a-completed-worker
  (let [outcome
        (with-redefs
         [journal-file/read-bounded-path
          (fn [_ _]
            {:status :ok
             :image (byte-array [1 2 3])
             :bytes-read 3
             :truncated? false})]
          (process-explorer/run-case
           (case-config
            'jolt.sim.fixtures.explore-scenarios/echoes-input
            completion-timeout-ms
            {:activity-journal? true
             :retain-completed-artifacts? true})))
        recovery (get-in outcome [:activity :recovery])
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"
                    "activity.journal"]})
        activity-ok?
        (and (= :failed (:status recovery))
             (keyword? (:reason recovery))
             (= 0 (:accepted-count (:activity outcome)))
             (= [] (get-in outcome [:activity :events]))
             (nil? (get-in outcome [:activity :observer-status]))
             (not (.contains (pr-str recovery)
                             (str (:artifact-dir outcome)))))]
    (is (= :completed (:status outcome)))
    (is (= 0 (:exit outcome)))
    (is (= {:echoed nil} (body-result outcome))
        "the already decoded application result remains primary")
    (is (true? activity-ok?)
        "only the secondary parent recovery page reports failure")
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? activity-ok? (= :completed (:status outcome))))))

(deftest enabled-activity-journal-survives-a-reaped-timeout
  (let [scenario 'jolt.sim.fixtures.explore-scenarios/dependent
        outcome
        (process-explorer/run-schedule
         (assoc (run-config scenario [1 0] expected-block-timeout-ms)
                :startup-timeout-ms completion-timeout-ms
                :activity-journal? true
                :retain-completed-artifacts? true))
        events (mapv :event (get-in outcome [:activity :events]))
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "stdout.log" "stderr.log"
                    "activity.journal"]
          :absent ["result.edn"]})
        activity-ok?
        (and (= [[:jolt.sim.explore/scenario-started nil nil
                  {:scenario scenario}]]
                events)
             (= :complete (get-in outcome [:activity :recovery :status]))
             (nil? (get-in outcome [:activity :observer-status])))]
    (is (= :timeout (:status outcome)))
    (is (= :deadline (:reason outcome))
        "activity recovery must not relabel a neutral deadline")
    (is (integer? (:exit outcome))
        "a returned timeout carries the observed reaped child exit")
    (is (true? activity-ok?)
        "the flushed valid prefix survives forced child termination")
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? activity-ok? (= :timeout (:status outcome))))))

(deftest activity-substrate-failure-invalidates-the-run-without-leakage
  (let [scenario 'jolt.sim.fixtures.explore-scenarios/echoes-input
        occupied (atom nil)
        outcome
        (process-explorer/run-case
         (case-config
          scenario
          completion-timeout-ms
          {:activity-journal? true
           :retain-completed-artifacts? true
           ;; The trusted hook pre-occupies the fixed journal path before
           ;; spawn, so the child's open hits the adapter's :target-exists
           ;; refusal. The child must never overwrite existing data.
           :on-run-dir
           (fn [dir]
             (let [path (str (fs/path dir "activity.journal"))]
               (spit path "occupied")
               (reset! occupied path)))}))
        artifacts-ok?
        (retained-artifacts-match?
         outcome
         {:present ["request.edn" "result.edn" "stdout.log" "stderr.log"
                    "activity.journal"]})
        error-text (pr-str (:error outcome))
        leakage-ok?
        (and (some? @occupied)
             (not (.contains error-text @occupied))
             (not (.contains error-text "Exception"))
             (not (.contains error-text "occupied")))
        refused-untouched?
        (and (some? @occupied)
             (fs/exists? @occupied)
             (= "occupied" (slurp @occupied)))
        activity-ok?
        (and (= :failed (get-in outcome [:activity :recovery :status]))
             (= :failed
                (get-in outcome [:activity :observer-status :health]))
             (= :target-exists
                (get-in outcome
                        [:activity :observer-status :failure :reason])))]
    (is (= :worker-error (:status outcome))
        "an activity open failure invalidates the activity-enabled run")
    (is (= 0 (:exit outcome)))
    (is (= :activity-journal (get-in outcome [:error :phase])))
    (is (= :jolt.sim/activity-failure (get-in outcome [:error :kind])))
    (is (= :open (get-in outcome [:error :activity :failure :phase])))
    (is (= :target-exists (get-in outcome [:error :activity :failure :reason])))
    (is (true? activity-ok?)
        "recovery and primary observer failure coexist in one envelope")
    (is (true? leakage-ok?)
        (str "bounded diagnostics carry no path, Throwable, or raw content: "
             error-text))
    (is (true? refused-untouched?)
        "the refused existing target is never overwritten")
    (cleanup-expected-artifacts!
     outcome
     (and artifacts-ok? leakage-ok? refused-untouched? activity-ok?
          (= :worker-error (:status outcome))))))

(deftest activity-failure-after-application-failure-keeps-the-primary-error
  (let [adjust-var
        (resolve 'jolt.sim.explore-worker/activity-adjusted-document)
        protocol-key :jolt.sim.explore/protocol
        status-key :jolt.sim.explore/status
        schedule-key :jolt.sim.explore/schedule
        value-key :jolt.sim.explore/value
        error-key :jolt.sim.explore/error
        failed-status
        {:health :failed
         :failure {:phase :write
                   :reason :write-threw
                   :class "java.io.IOException"
                   :message "/unbounded/secret-path raw message"}
         :sequence 1
         :accepted 1
         :capped? false
         :durability :process-crash
         :closed? true}
        original (ex-info "primary application failure" {:type ::primary})
        failed-document
        {protocol-key 2
         status-key :failed
         schedule-key nil
         error-key (trace/canonical-value (trace/normalize-error original))}
        adjusted-failed (@adjust-var failed-document nil failed-status)
        failed-outcome (worker/decode-result nil adjusted-failed)
        completed-document
        {protocol-key 2
         status-key :completed
         schedule-key nil
         value-key (trace/canonical-value :apparent-success)}
        adjusted-completed (@adjust-var completed-document nil failed-status)
        completed-outcome (worker/decode-result nil adjusted-completed)]
    (testing "an application failure remains primary with bounded secondary diagnostics"
      (is (= :failed (:status failed-outcome)))
      (is (= "primary application failure"
             (get-in failed-outcome [:error :message])))
      (is (= ::primary (get-in failed-outcome [:error :data :type])))
      (is (= :failed (get-in failed-outcome [:activity :health])))
      (is (= :write-threw
             (get-in failed-outcome [:activity :failure :reason])))
      (is (nil? (get-in failed-outcome [:activity :failure :message])))
      (is (not (.contains (pr-str (:activity failed-outcome)) "secret-path"))
          "secondary diagnostics drop raw messages that can embed paths"))
    (testing "an activity failure invalidates an apparent completion"
      (is (= :worker-error (:status completed-outcome)))
      (is (= :activity-journal (get-in completed-outcome [:error :phase])))
      (is (= :jolt.sim/activity-failure
             (get-in completed-outcome [:error :kind])))
      (is (nil? (:result completed-outcome)))
      (is (not (.contains (pr-str (:error completed-outcome)) "secret-path"))))
    (testing "secondary activity diagnostics have one exact bounded schema"
      (doseq [bad-activity
              [{:health :failed :failure {:phase :write :reason :failed}}
               (assoc (worker/decode-result
                       nil
                       adjusted-failed)
                      :unexpected "do-not-retain-this-value")]]
        (let [bad-document
              (assoc failed-document
                     :jolt.sim.explore/activity
                     (trace/canonical-value bad-activity))
              data (ex-data-of #(worker/decode-result nil bad-document))]
          (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
          (is (= :result-payload (:reason data)))
          (is (= :activity (:field data)))
          (is (not (.contains (pr-str data) "do-not-retain-this-value"))))))))

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
