(ns jolt.sim.repl-test
  "Focused tests for the jolt.sim.repl last-run convenience.

  with-redefs stubs jolt.sim.process-explorer/run-case so the ordinary suite
  never launches a child worker. The rebind is on the public var, so the
  delegation through jolt.sim.repl sees the same stub."
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.process-explorer :as process-explorer]
            [jolt.sim.repl :as repl]))

(def base-config
  {:worker-command ["jolt" "-M:sim-worker"]
   :scenario 'example/scenario
   :timeout-ms 1000
   :dir "/tmp"})

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
