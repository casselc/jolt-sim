(ns jolt.sim.convergence-b63-test
  "The convergence slice's STEP 4 (PROGRESSIVE-FORMALISM-DESIGN 7.2b),
  executed as a test-only harness.

  Steps 1--3 live in perturb (perturb.evidence, perturb.semantic,
  perturb.b63, gated by perturb.slicecheck): ONE B6 clause re-expressed as a
  pure fold over a separately versioned semantic-event document and
  differentially tested against perturb.layer's own arm. Step 4 is the
  bounded-schedule arm that slice gate could not run: evaluate that SAME
  monitor under ONE exact bounded schedule in ONE fresh exploration worker
  per fixture, and store the returned monitor decision in a parent-side
  jolt.sim.case-outcome v1 document.

  THREE KINDS OF FACTS, KEPT DISTINCT:

    * the CASE -- scenario, :hermetic mode, the fixture selector input, and
      the exact [1 0] future-schedule permutation -- stored in the document;
    * the worker's CANONICAL RESULT -- the monitor decision, the projected
      event count, the observed body-start order, and run-controlled's
      deterministic [:admit ...] [:complete ...] admission log -- asserted
      here and stored as the document's outcome and monitors;
    * HOST SUPERVISION FACTS -- parent-observed exit codes, PIDs,
      stdout/stderr diagnostics, retained artifact directories, and the
      deadline classification -- asserted on the raw process-explorer
      outcome and NEVER stored in the deterministic document. A blocked
      worker is a :timeout with :reason :deadline; nothing here is ever
      classified :deadlock, because a deadline is not a deadlock proof.

  IMAGE-FREE UNIT COVERAGE vs. THE WORKER ACCEPTANCE LANE. The Case/Outcome
  adapter tests need no worker and run on any image. The fixture lanes need
  a sim-enabled image named by JOLT_SIM_BIN (plus JOLT_SIM_PROJECT_DIR).
   When no image is supplied, -main runs the unit coverage and reports the
   worker acceptance lane as SKIPPED with exit status 2; it never fabricates
   the acceptance."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

(def ^:private scenario-sym
  'jolt.sim.fixtures.convergence-b63-scenarios/run-b63)

(def ^:private blocked-scenario-sym
  'jolt.sim.fixtures.convergence-b63-scenarios/run-b63-blocked)

(def ^:private monitor-id :perturb.b63/error-mapping)
(def ^:private exact-schedule [1 0])
(def ^:private expected-admission-log
  [[:admit 1] [:complete 1] [:admit 0] [:complete 0]])

;; Budgets mirror the proven lanes: the separate startup deadline carries
;; cold child bootstrap (dependency resolution and namespace loading) so the
;; execution deadline measures only the scenario. The blocked control keeps
;; the explore-process lane's shorter, discriminating budget.
(def ^:private startup-timeout-ms 120000)
(def ^:private case-timeout-ms 20000)
(def ^:private blocked-timeout-ms 5000)
(def ^:private kill-grace-ms 500)

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.convergence-b63-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "convergence-b63 worker lanes must be run through -main"
        {:type :jolt.sim.convergence-b63-test/not-configured}))))

(defn- case-for
  "The closed case description stored in every document this lane writes."
  ([fixture] (case-for fixture scenario-sym))
  ([fixture scenario]
   {:scenario scenario
    :mode :hermetic
    :input {:fixture fixture}
    :schedule exact-schedule}))

(defn- ordinary-process-outcome
  "Projects process-explorer's supervisor result onto the closed ordinary
  outcome accepted by case-outcome/document. PIDs, diagnostics, worker-ready
  flags, and artifact paths are HOST SUPERVISION FACTS: they stay on the raw
  outcome (and in the retained forensic directory) and never enter the
  deterministic document. Fails closed on any foreign status label -- a
  :deadlock claim could not be stored even if one were made."
  [outcome]
  (case (:status outcome)
    :completed {:status :completed
                :result (:result outcome)
                :exit (:exit outcome)}
    :failed {:status :failed
             :error (:error outcome)
             :exit (:exit outcome)}
    :timeout {:status :timeout
               ;; Do not normalize a future supervisor reason into :deadline:
               ;; Case/Outcome validates the one currently supported reason
               ;; fail-closed. Relabeling here would turn an infrastructure
               ;; contract change into a false successful artifact.
               :reason (:reason outcome)
              :exit (:exit outcome)}
    :worker-error {:status :worker-error
                   :error (:error outcome)
                   :exit (get outcome :exit)}
    (throw
     (ex-info
      "process explorer returned an unsupported outcome status"
      {:type :jolt.sim.convergence-b63-test/unsupported-outcome
       :status (:status outcome)}))))

(defn- run-fixture-case!
  "Runs ONE convergence fixture in ONE exact-scheduled fresh worker."
  [fixture]
  (process-explorer/run-case
   (merge
    (process-config)
    {:scenario scenario-sym
     :schedule exact-schedule
     :input {:fixture fixture}
     :timeout-ms case-timeout-ms
     :kill-grace-ms kill-grace-ms})))

(defn- cleanup-expected-artifacts!
  "Deletes the retained artifact directory only when the outcome matched
  every expectation; an unexpected outcome keeps its forensic directory and
  prints its location."
  [outcome expected?]
  (when-let [dir (:artifact-dir outcome)]
    (if expected?
      (fs/delete-tree dir)
      (println "Retained unexpected convergence-b63 artifacts at" dir))))

;; ---- Image-free unit coverage ---------------------------------------------
;;
;; These deftests exercise the parent-side adapter -- the outcome projection,
;; document construction, validation, byte-stable EDN, and restoration --
;; against representative values. They make no claim about the property
;; itself: the acceptance decisions come only from a fresh worker.

(deftest case-outcome-adapter-stores-one-monitor-decision
  (testing "a representative completed outcome and B6.3 decision"
    (let [decision {:id monitor-id
                    :status :pass
                    :detail {:exercised 1 :dangling 0}
                    :index nil}
          canonical-result
          {:result {:monitor decision
                    :fixture :b-prime
                    :event-count 8
                    :values [:a :b]
                    :start-order [:b :a]
                    :spec-agreement true}
           :events []
           :schedule-events expected-admission-log}
          ;; Host supervision facts ride the raw supervisor outcome; the
          ;; projection must drop every one of them.
          raw-outcome {:status :completed
                       :result canonical-result
                       :exit 0
                       :schedule exact-schedule
                       :worker-ready? true
                       :diagnostics {:stdout {:bytes 0 :text ""}
                                     :stderr {:bytes 0 :text ""}
                                     :worker-pid 4242}
                       :artifact-dir "/tmp/jolt-sim-explore-representative"}
          doc (case-outcome/document
               (case-for :b-prime)
               (ordinary-process-outcome raw-outcome)
               [decision])]
      (is (= case-outcome/version (:jolt.sim.case-outcome/version doc)))
      (is (= doc (case-outcome/validate-document! doc)))
      (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc)))
          "the stored document is byte-stable EDN")
      (is (= (case-for :b-prime) (case-outcome/restore-case doc)))
      (is (= [decision] (case-outcome/restore-monitors doc)))
      (is (= {:status :completed :result canonical-result :exit 0}
             (case-outcome/restore-outcome doc)))
      (testing "supervision facts never enter the stored document"
        (is (= #{:status :value :exit}
               (set (keys (:jolt.sim.case-outcome/outcome doc)))))
        (is (nil? (re-find #"diagnostics|artifact|worker-pid|worker-ready"
                           (case-outcome/canonical-edn doc))))
        (is (= expected-admission-log
               (get-in (case-outcome/restore-outcome doc)
                       [:result :schedule-events]))
            "the schedule witness is part of the canonical result, not of supervision")))))

(deftest timeout-outcome-stores-only-the-neutral-deadline-label
  (testing "a :timeout outcome stores as :timeout/:deadline, never :deadlock"
    (let [doc (case-outcome/document
               (case-for :a blocked-scenario-sym)
               (ordinary-process-outcome
                {:status :timeout
                 :reason :deadline
                 :exit 137
                 :schedule exact-schedule
                 :diagnostics {:stdout {:bytes 0 :text ""}
                               :stderr {:bytes 0 :text ""}}
                 :artifact-dir "/tmp/jolt-sim-explore-representative"})
               [])]
      (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
      (let [outcome (case-outcome/restore-outcome doc)]
        (is (= :timeout (:status outcome)))
        (is (= :deadline (:reason outcome)))
        (is (not= :deadlock (:status outcome))
            "a deadline is not a deadlock classification")
        (is (not= :deadlock (:reason outcome))
            "the timeout reason must remain the neutral deadline label"))
      (is (= [] (case-outcome/restore-monitors doc))
          "no monitor decision exists to store for a case that never ran"))))

(deftest the-outcome-projection-fails-closed-on-foreign-labels
  (testing ":deadlock is not a status this harness can store"
    (is (= :jolt.sim.convergence-b63-test/unsupported-outcome
           (:type
            (ex-data
             (try
               (ordinary-process-outcome {:status :deadlock})
               (catch :default error
                 error))))))))

;; ---- The worker acceptance lane --------------------------------------------

(defn- run-and-store-fixture!
  "Runs ONE convergence fixture in ONE exact-scheduled fresh worker, asserts
  the completed canonical result and schedule witness, builds the v1
  Case/Outcome document with the returned monitor decision, and validates,
  round-trips, and restores it. Returns {:outcome :document :decision}.

  expected-dangling is the monitor's missing-correlation count, asserted
  exactly rather than assumed zero: B' ends with a trailing :eof refusal
  (id nil) arriving after the declared recv request has already replied --
  outside every declared extent, so the monitor RECORDS it (dangling 1)
  instead of silently dropping it or treating it as evidence."
  [fixture expected-status expected-exercised expected-event-count
   expected-dangling]
  (let [outcome (run-fixture-case! fixture)]
    (is (= :completed (:status outcome))
        (str "the " (name fixture) " worker must complete; got "
             (pr-str (select-keys outcome [:status :reason :exit]))))
    (when (= :completed (:status outcome))
      (is (= 0 (:exit outcome)))
      (is (= exact-schedule (:schedule outcome)))
      (is (= expected-admission-log
             (get-in outcome [:result :schedule-events]))
          "the exact [1 0] schedule's deterministic admission log")
      (let [body (get-in outcome [:result :result])
            decision (:monitor body)]
        (is (= [:b :a] (:start-order body))
            "under [1 0] the second-spawned body starts first")
        (is (= [:a :b] (:values body)))
        (is (= expected-event-count (:event-count body))
            "the worker re-recorded the same fixture the slice gate projects")
        (is (true? (:spec-agreement body))
            "the unary factory and the incumbent spec agree on the same document")
        (is (= monitor-id (:id decision)))
        (is (= expected-status (:status decision)))
        (is (= expected-exercised (:exercised (:detail decision))))
        (is (= expected-dangling (:dangling (:detail decision))))
        (is (nil? (:index decision))
            "B6.3 decides at :finish, never early")
        (let [doc (case-outcome/document
                   (case-for fixture)
                   (ordinary-process-outcome outcome)
                   [decision])]
          (is (= doc (case-outcome/validate-document! doc)))
          (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc)))
              "the stored Case/Outcome document is byte-stable EDN")
          (is (= (case-for fixture) (case-outcome/restore-case doc)))
          (is (= [decision] (case-outcome/restore-monitors doc))
              "the document stores the returned monitor decision")
          (let [stored (case-outcome/restore-outcome doc)]
            (is (= :completed (:status stored)))
            (is (= 0 (:exit stored)))
            (is (= expected-admission-log
                   (get-in stored [:result :schedule-events]))
                "the schedule witness survives storage")
            (is (= decision (get-in stored [:result :result :monitor]))))
          {:outcome outcome :document doc :decision decision})))))

(deftest fixture-b-prime-passes-under-one-exact-schedule
  (testing "B' edge declared: one refusal inside a declared extent, mapped"
    (run-and-store-fixture! :b-prime :pass 1 8 1)))

(deftest fixture-a-is-inconclusive-under-one-exact-schedule
  (testing "A known-good: no refusal inside any declared extent is NOT a pass"
    (run-and-store-fixture! :a :inconclusive 0 28 0)))

(deftest a-blocked-worker-is-reported-as-a-timeout-never-a-deadlock
  (let [outcome
        (process-explorer/run-case
         (merge
          (process-config)
          {:scenario blocked-scenario-sym
           :schedule exact-schedule
           :input {:fixture :a}
           :timeout-ms blocked-timeout-ms
           :kill-grace-ms kill-grace-ms}))
        expected? (and (= :timeout (:status outcome))
                       (= :deadline (:reason outcome))
                       (= exact-schedule (:schedule outcome)))]
    (is (= :timeout (:status outcome)))
    (is (= :deadline (:reason outcome)))
    (is (= exact-schedule (:schedule outcome)))
    (is (not= :deadlock (:status outcome))
        "a deadline is not a deadlock classification")
    (is (not= :deadlock (:reason outcome))
        "the timeout reason must remain the neutral deadline label")
    (testing "the neutral classification stores unchanged, with no fabricated decision"
      (let [doc (case-outcome/document
                 (case-for :a blocked-scenario-sym)
                 (ordinary-process-outcome outcome)
                 [])]
        (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
        (is (= :timeout (:status (case-outcome/restore-outcome doc))))
        (is (= :deadline (:reason (case-outcome/restore-outcome doc))))
        (is (= [] (case-outcome/restore-monitors doc))
            "no monitor decision is fabricated for a case that never produced one")))
    (cleanup-expected-artifacts! outcome expected?)))

;; ---- -main: unit coverage always; the worker lane only with an image -------

(def ^:private unit-test-vars
  [#'case-outcome-adapter-stores-one-monitor-decision
   #'timeout-outcome-stores-only-the-neutral-deadline-label
   #'the-outcome-projection-fails-closed-on-foreign-labels])

(def ^:private worker-test-vars
  [#'fixture-b-prime-passes-under-one-exact-schedule
   #'fixture-a-is-inconclusive-under-one-exact-schedule
   #'a-blocked-worker-is-reported-as-a-timeout-never-a-deadlock])

(defn- counter-snapshot []
  {:test (:test @test/counters)
   :pass (test/n-pass)
   :fail (test/n-fail)
   :error (test/n-error)})

(defn- counter-delta [before after]
  (into {}
        (map (fn [key] [key (- (get after key) (get before key))]))
        [:test :pass :fail :error]))

(defn- run-serial-test-var!
  "Runs one test var synchronously. Each process-explorer case owns and
  reaps its worker before this function can advance to the next var."
  [var]
  (let [before (counter-snapshot)]
    (test/test-var var)
    (counter-delta before (counter-snapshot))))

(defn -main [& _]
  (let [bin (System/getenv "JOLT_SIM_BIN")
        supplied? (and (string? bin) (seq bin))
        summary
        (reduce (fn [summary test-var]
                  (merge-with + summary (run-serial-test-var! test-var)))
                {:test 0 :pass 0 :fail 0 :error 0}
                unit-test-vars)
        summary
        (if supplied?
          (let [project-dir (required-environment "JOLT_SIM_PROJECT_DIR")]
            (binding [*process-config*
                      {:worker-command [bin "-M:convergence-b63-explore-worker"]
                       :dir project-dir
                       :startup-timeout-ms startup-timeout-ms}]
              (reduce (fn [summary test-var]
                        (merge-with + summary (run-serial-test-var! test-var)))
                      summary
                      worker-test-vars)))
          (do
            (println)
            (println "WORKER ACCEPTANCE LANE SKIPPED -- JOLT_SIM_BIN is not supplied.")
            (println "  The fixture lanes (B' :pass exercised 1, A :inconclusive")
            (println "  exercised 0, the [1 0] schedule witness, and the")
            (println "  timeout-never-deadlock supervision control) DID NOT RUN;")
            (println "  no acceptance claim is made. Supply")
            (println "  JOLT_SIM_BIN=/absolute/path/to/sim-enabled/jolt and")
            (println "  JOLT_SIM_PROJECT_DIR to execute the full gate.")
            (println)
            summary))
        failures (+ (:fail summary) (:error summary))]
    (println (str (:test summary) " tests, "
                  (:pass summary) " assertions passed, "
                  (:fail summary) " failures, "
                  (:error summary) " errors"
                  (when-not supplied?
                    " (worker acceptance lane SKIPPED -- not a pass)")))
    (flush)
    ;; The worker launcher may leave non-daemon threads alive. Every test var
    ;; and process-explorer call has returned here, so no worker is owned.
    ;; A skipped worker lane is deliberately not a passing acceptance gate.
    ;; Keep the unit-adapter result visible, but make an image-less invocation
    ;; unusable as a green CI substitute for step 4.
    (System/exit (cond
                   (not (zero? failures)) 1
                   supplied? 0
                   :else 2))))
