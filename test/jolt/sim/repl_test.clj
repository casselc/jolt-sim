(ns jolt.sim.repl-test
  "Focused tests for the jolt.sim.repl last-run convenience.

  with-redefs stubs jolt.sim.process-explorer/run-case so the ordinary suite
  never launches a child worker. The rebind is on the public var, so the
  delegation through jolt.sim.repl sees the same stub."
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.repl :as repl]))

(def base-config
  {:worker-command ["jolt" "-M:sim-worker"]
   :scenario 'example/scenario
   :timeout-ms 1000
   :dir "/tmp"})

(def replay-case
  {:scenario 'example/replayed-scenario
   :mode :hermetic
   :input {:request-id 17 :payload [0 255]}
   :schedule [1 0]})

(def replay-runtime-config
  {:worker-command ["jolt" "-M:sim-worker"]
   :timeout-ms 2500
   :startup-timeout-ms 5000
   :dir "/replay/project"
   :extra-env {"JOLT_SIM_REPLAY" "1"}
   :retain-completed-artifacts? true})

(defn- replay-document [outcome]
  (case-outcome/document replay-case outcome []))

(defn- ex-data-of [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(defn- captured-stub
  "Returns [stub calls submitted] where stub conjures each submitted config
  onto submitted and each outcome from outcomes in order."
  [outcomes]
  (let [calls (atom 0)
        submitted (atom [])
        remaining (atom outcomes)
        stub (fn [config]
               (swap! calls inc)
               (swap! submitted conj config)
               (let [out (first @remaining)]
                 (swap! remaining rest)
                 out))]
    [stub calls submitted]))

(deftest run-case-delegates-exactly-once-and-records-config-and-outcome
  (repl/clear!)
  (let [outcome {:status :completed :schedule [1 0] :result {:order :ok}}
        [stub calls submitted] (captured-stub [outcome])
        returned
        (with-redefs [process-explorer/run-case stub]
          (repl/run-case! base-config))]
    (is (= 1 @calls) "run-case! delegates exactly once to run-case")
    (is (= [base-config] @submitted)
        "run-case! submits the exact caller config unchanged")
    (is (identical? outcome returned)
        "run-case! returns the outcome from run-case unchanged")
    (is (= {:config base-config :outcome outcome} (repl/last-run))
        "last-run records the exact submitted config and outcome")))

(deftest last-run-is-nil-until-a-run-and-removed-by-clear
  (repl/clear!)
  (is (nil? (repl/last-run)) "last-run is nil before any run")
  (let [[stub] (captured-stub [{:status :completed}])]
    (with-redefs [process-explorer/run-case stub]
      (repl/run-case! base-config)))
  (is (some? (repl/last-run)) "last-run is populated after a run")
  (is (nil? (repl/clear!)) "clear! returns nil")
  (is (nil? (repl/last-run)) "clear! removes the record"))

(deftest rerun-repeats-the-exact-last-config-in-a-fresh-worker
  (repl/clear!)
  (let [first-outcome {:status :completed :schedule [1 0] :result :a}
        second-outcome {:status :completed :schedule [1 0] :result :b}
        [stub calls submitted] (captured-stub [first-outcome second-outcome])
        first-returned
        (with-redefs [process-explorer/run-case stub]
          (repl/run-case! base-config))
        second-returned
        (with-redefs [process-explorer/run-case stub]
          (repl/rerun!))]
    (is (= 2 @calls)
        "run-case! then rerun! delegate once each, in fresh workers")
    (is (= [base-config base-config] @submitted)
        "rerun! re-submits the exact last run-case config unchanged")
    (is (identical? first-outcome first-returned))
    (is (identical? second-outcome second-returned)
        "rerun! returns the new outcome, not the cached one")
    (is (= {:config base-config :outcome second-outcome} (repl/last-run))
        "rerun! records the new outcome over the prior one")))

(deftest rerun-uses-the-most-recent-config-after-multiple-runs
  (repl/clear!)
  (let [config-a (assoc base-config :scenario 'example/a)
        config-b (assoc base-config :scenario 'example/b)
        outcome-a {:status :completed :result :a}
        outcome-b {:status :completed :result :b}
        rerun-outcome {:status :timeout :reason :deadline}
        [stub _ submitted] (captured-stub [outcome-a outcome-b rerun-outcome])
        returned
        (with-redefs [process-explorer/run-case stub]
          (repl/run-case! config-a)   ; recorded then superseded
          (repl/run-case! config-b)   ; this is the last run
          (repl/rerun!))]
    (is (= [config-a config-b config-b] @submitted)
        "rerun! repeats the LAST recorded config, not an earlier one")
    (is (identical? rerun-outcome returned))
    (is (= {:config config-b :outcome rerun-outcome} (repl/last-run)))))

(deftest rerun-throws-a-typed-ex-info-without-a-prior-case
  (repl/clear!)
  (let [data (ex-data-of #(repl/rerun!))]
    (is (= :jolt.sim.repl/no-prior-run (:type data)))
    (is (nil? (repl/last-run))
        "a rejected rerun! must not create a record")))

(deftest run-case-propagates-throw-and-records-nothing
  (repl/clear!)
  (let [thrown (ex-info "worker spawn failed" {:type :infra})]
    (with-redefs [process-explorer/run-case (fn [_] (throw thrown))]
      (is (identical? thrown
                      (try (repl/run-case! base-config)
                           (catch :default error error)))
          "the run-case exception propagates unchanged")))
  (is (nil? (repl/last-run))
      "a failed run-case! records no outcome"))

(deftest rerun-propagates-throw-and-keeps-the-prior-record
  (repl/clear!)
  (let [prior-outcome {:status :completed}
        [first-stub] (captured-stub [prior-outcome])
        thrown (ex-info "fresh worker failed" {:type :infra})]
    (with-redefs [process-explorer/run-case first-stub]
      (repl/run-case! base-config))
    (with-redefs [process-explorer/run-case (fn [_] (throw thrown))]
      (is (identical? thrown
                      (try (repl/rerun!)
                           (catch :default error error)))
          "the run-case exception propagates unchanged from rerun!")))
  (is (= {:config base-config :outcome {:status :completed}}
         (repl/last-run))
      "a failed rerun! leaves the prior record unchanged"))

(deftest replay-document-restores-case-coordinates-over-ambient-config
  (repl/clear!)
  (let [outcome {:status :failed
                 :schedule [1 0]
                 :error {:type :application/rejected}
                 :exit 0}
        document
        (replay-document
         {:status :failed :error {:type :previous/failure} :exit 0})
        expected-config
        (merge replay-runtime-config
               (select-keys replay-case [:scenario :input :schedule]))
        [stub calls submitted] (captured-stub [outcome])
        returned
        (with-redefs [process-explorer/run-case stub]
          (repl/replay-document! document replay-runtime-config))]
    (is (= 1 @calls) "a document replay delegates exactly once")
    (is (= [expected-config] @submitted)
        "the replay submits exact stored coordinates plus ambient settings")
    (is (not (contains? (first @submitted) :mode))
        "mode is validated provenance, not an unsupported worker override")
    (is (identical? outcome returned)
        "the fresh failed outcome is returned without success conversion")
    (is (= {:config expected-config :outcome outcome} (repl/last-run))
        "document replay uses the ordinary last-run record contract")))

(deftest replay-document-reads-canonical-edn
  (repl/clear!)
  (let [document
        (replay-document {:status :completed :result {:old true} :exit 0})
        encoded (case-outcome/canonical-edn document)
        outcome {:status :timeout
                 :schedule [1 0]
                 :reason :deadline
                 :exit 143}
        [stub calls submitted] (captured-stub [outcome])
        returned
        (with-redefs [process-explorer/run-case stub]
          (repl/replay-document! encoded replay-runtime-config))]
    (is (= 1 @calls))
    (is (= (select-keys replay-case [:scenario :input :schedule])
           (select-keys (first @submitted)
                        [:scenario :input :schedule]))
        "EDN replay restores scenario/input/schedule; mode remains provenance")
    ;; select-keys omits :mode from the submitted config, so compare it
    ;; separately to the validated source coordinate instead of fabricating a
    ;; process-explorer override.
    (is (= :hermetic (:mode (case-outcome/restore-case document))))
    (is (identical? outcome returned)
        "a timeout remains the exact timeout returned by the supervisor")))

(deftest replay-preserves-every-process-outcome-status
  (let [document
        (replay-document {:status :completed :result :old :exit 0})
        outcomes
        [{:status :completed :schedule [1 0] :result :fresh :exit 0}
         {:status :failed :schedule [1 0] :error {:type :boom} :exit 0}
         {:status :timeout :schedule [1 0] :reason :deadline :exit 143}
         {:status :worker-error
          :schedule [1 0]
          :error {:phase :spawn}
          :exit nil}]]
    (doseq [outcome outcomes]
      (repl/clear!)
      (let [[stub calls] (captured-stub [outcome])
            returned
            (with-redefs [process-explorer/run-case stub]
              (repl/replay-document! document replay-runtime-config))]
        (is (= 1 @calls))
        (is (identical? outcome returned))
        (is (identical? outcome (:outcome (repl/last-run))))))))

(deftest replay-rejects-all-ambient-coordinate-collisions-before-delegation
  (repl/clear!)
  (let [document
        (replay-document {:status :completed :result :old :exit 0})
        calls (atom 0)]
    (doseq [[coordinate value]
            [[:scenario 'ambient/wrong]
             [:mode :real]
             [:input :wrong]
             [:schedule [0]]]]
      (let [data
            (with-redefs
              [process-explorer/run-case (fn [_] (swap! calls inc))]
              (ex-data-of
               #(repl/replay-document!
                 document
                 (assoc replay-runtime-config coordinate value))))]
        (is (= :jolt.sim.repl/invalid-replay (:type data)))
        (is (= :replay-coordinate-collision (:reason data)))
        (is (= [coordinate] (:keys data)))))
    (is (zero? @calls) "coordinate collisions never reach a worker")
    (is (nil? (repl/last-run)))))

(deftest replay-rejects-non-map-runtime-config-before-delegation
  (repl/clear!)
  (let [document
        (replay-document {:status :completed :result :old :exit 0})
        calls (atom 0)
        data
        (with-redefs
          [process-explorer/run-case (fn [_] (swap! calls inc))]
          (ex-data-of #(repl/replay-document! document [:not :ambient])))]
    (is (= :jolt.sim.repl/invalid-replay (:type data)))
    (is (= :runtime-config-not-a-map (:reason data)))
    (is (zero? @calls))
    (is (nil? (repl/last-run)))))

(deftest rejected-document-and-thrown-replay-preserve-prior-last-run
  (repl/clear!)
  (let [prior-outcome {:status :completed :result :prior}
        [prior-stub] (captured-stub [prior-outcome])]
    (with-redefs [process-explorer/run-case prior-stub]
      (repl/run-case! base-config))
    (let [prior (repl/last-run)
          calls (atom 0)
          invalid
          {:jolt.sim.case-outcome/version 99}
          invalid-data
          (with-redefs
            [process-explorer/run-case (fn [_] (swap! calls inc))]
            (ex-data-of
             #(repl/replay-document! invalid replay-runtime-config)))]
      (is (= case-outcome/invalid-document (:type invalid-data)))
      (is (zero? @calls) "invalid documents are rejected before delegation")
      (is (= prior (repl/last-run))))
    (let [prior (repl/last-run)
          thrown (ex-info "worker unavailable" {:type :infra})
          document
          (replay-document {:status :completed :result :old :exit 0})]
      (with-redefs [process-explorer/run-case (fn [_] (throw thrown))]
        (is (identical? thrown
                        (try
                          (repl/replay-document!
                           document replay-runtime-config)
                          (catch :default error error)))))
      (is (= prior (repl/last-run))
          "a thrown replay leaves the prior successful record intact"))))

(deftest rerun-after-document-replay-reuses-the-restored-worker-config
  (repl/clear!)
  (let [document
        (replay-document {:status :completed :result :old :exit 0})
        first-outcome {:status :failed :error {:attempt 1} :exit 0}
        second-outcome {:status :worker-error :error {:attempt 2} :exit nil}
        [stub calls submitted]
        (captured-stub [first-outcome second-outcome])
        returned
        (with-redefs [process-explorer/run-case stub]
          (repl/replay-document! document replay-runtime-config)
          (repl/rerun!))]
    (is (= 2 @calls))
    (is (= (first @submitted) (second @submitted))
        "rerun! reuses the exact executable config restored by replay")
    (is (identical? second-outcome returned))
    (is (identical? second-outcome (:outcome (repl/last-run))))))
