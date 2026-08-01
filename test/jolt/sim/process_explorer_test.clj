(ns jolt.sim.process-explorer-test
  "Pure configuration tests for the process supervisor. Real child-process
  behavior lives in the isolated jolt.sim.explore-process-test gate."
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.process-explorer :as process-explorer]))

(def base-run-config
  {:worker-command ["jolt" "-M:explore-worker-test"]
   :scenario 'example/scenario
   :schedule [0 1]
   :timeout-ms 1000
   :dir "/tmp"})

(def base-case-config
  {:worker-command ["jolt" "-M:explore-worker-test"]
   :scenario 'example/scenario
   :timeout-ms 1000
   :dir "/tmp"})

(defn- ex-data-of [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(defn- ex-of [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      error)))

(deftest run-schedule-rejects-malformed-input-before-file-or-process-access
  (let [not-a-map (ex-data-of #(process-explorer/run-schedule :bad))
        unknown
        (ex-data-of
         #(process-explorer/run-schedule
           (assoc base-run-config :z/unknown true 4 false)))
        invalid-schedule
        (ex-data-of
         #(process-explorer/run-schedule
           (assoc base-run-config :schedule [1 1])))]
    (is (= :jolt.sim.process-explorer/invalid-config (:type not-a-map)))
    (is (= :config-not-a-map (:reason not-a-map)))
    (is (= :unknown-key (:reason unknown)))
    (is (= [4 :z/unknown] (:keys unknown)))
    (is (= :invalid-schedule (:reason invalid-schedule)))))

(deftest run-case-rejects-an-invalid-optional-schedule
  (let [invalid
        (ex-data-of
         #(process-explorer/run-case
           (assoc base-case-config :schedule [1 1])))]
    (is (= :jolt.sim.process-explorer/invalid-config (:type invalid)))
    (is (= :invalid-schedule (:reason invalid)))
    (is (= [1 1] (:value invalid)))))

(deftest run-case-rejects-malformed-input-before-file-or-process-access
  (let [not-a-map (ex-data-of #(process-explorer/run-case :bad))
        invalid-input
        (ex-data-of
         #(process-explorer/run-case
           (assoc base-case-config :input (fn [] :not-canonical))))]
    (is (= :jolt.sim.process-explorer/invalid-config (:type not-a-map)))
    (is (= :config-not-a-map (:reason not-a-map)))
    (is (= :jolt.sim.process-explorer/invalid-config (:type invalid-input)))
    (is (= :invalid-input (:reason invalid-input)))
    (is (= :jolt.sim.trace/unsupported-value
           (get-in invalid-input [:error :type])))))

(deftest explore-rejects-duplicate-and-inconsistent-plans
  (let [base (-> base-run-config
                 (dissoc :schedule)
                 (assoc :schedules [[0 1] [1 0]]))
        duplicate
        (ex-data-of
         #(process-explorer/explore
           (assoc base :schedules [[0 1] [0 1]])))
        inconsistent
        (ex-data-of
         #(process-explorer/explore
           (assoc base :schedules [[0] [0 1]])))]
    (is (= :duplicate-schedule (:reason duplicate)))
    (is (= [[0 1] [0 1]] (:value duplicate)))
    (is (= :inconsistent-schedule-size (:reason inconsistent)))
    (is (= [1 2] (:sizes inconsistent)))))

(deftest timed-wait-enforces-real-elapsed-time
  (let [wait-var (resolve 'jolt.sim.process-explorer/timed-wait!)
        alive-var (resolve 'jolt.sim.process-explorer/child-alive?)
        clock-var (resolve 'jolt.sim.process-explorer/monotonic-nanos)
        sleep-var (resolve 'jolt.sim.process-explorer/sleep-ms!)
        now (atom 0)
        sleeps (atom [])
        finished?
        (with-redefs-fn
          {alive-var
           (fn [_]
             ;; Model a native waitpid/liveness probe whose overhead already
             ;; exceeds the complete timeout budget.
             (swap! now + 50000000)
             true)
           clock-var (fn [] @now)
           sleep-var
           (fn [millis]
             (swap! sleeps conj millis)
             (swap! now + (* millis 1000000)))}
          #(@wait-var :fake-child 20))]
    (is (false? finished?))
    (is (empty? @sleeps)
        "probe overhead counts against the deadline instead of adding polls")))

(deftest an-unobserved-exit-is-never-downgraded-to-a-returned-outcome
  (let [supervise-var
        (resolve 'jolt.sim.process-explorer/supervise-child)
        wait-var
        (resolve 'jolt.sim.process-explorer/timed-wait!)
        pid-var
        (resolve 'jolt.sim.process-explorer/child-pid)
        exit-var
        (resolve 'jolt.sim.process-explorer/reaped-exit)
        retain-var
        (resolve 'jolt.sim.process-explorer/retain-run-directory?)
        unobserved
        (ex-info
         "exit not observed"
         {:type :jolt.sim.process-explorer/worker-exit-unobserved})
        thrown
        (with-redefs-fn
          {wait-var (fn [_ _] true)
           pid-var (fn [_] 42)
           exit-var (fn [_] (throw unobserved))}
          #(ex-of
            (fn []
              (@supervise-var
               {:schedule [0] :timeout-ms 1 :kill-grace-ms 1}
               [0]
               :fake-child
               "unused-result"
               "unused-stdout"
               "unused-stderr"))))]
    (is (identical? unobserved thrown))
    (is (true? (@retain-var thrown)))))

(deftest pid-diagnostic-failure-cannot-abandon-a-spawned-child
  (let [supervise-var
        (resolve 'jolt.sim.process-explorer/supervise-child)
        wait-var
        (resolve 'jolt.sim.process-explorer/timed-wait!)
        pid-var
        (resolve 'jolt.sim.process-explorer/child-pid)
        exit-var
        (resolve 'jolt.sim.process-explorer/reaped-exit)
        read-var
        (resolve 'jolt.sim.process-explorer/read-worker-outcome)
        terminate-var
        (resolve 'jolt.sim.process-explorer/terminate-and-reap!)
        terminate-called? (atom false)
        outcome
        (with-redefs-fn
          {pid-var (fn [_] (throw (ex-info "pid unavailable" {})))
           wait-var (fn [_ _] true)
           exit-var (fn [_] 0)
           read-var (fn [schedule _exit _result _stdout _stderr]
                      {:status :completed
                       :schedule schedule
                       :diagnostics {}})
           terminate-var (fn [& _]
                           (reset! terminate-called? true)
                           (throw (ex-info "must not terminate exited child" {})))}
          #(@supervise-var
            {:timeout-ms 1 :kill-grace-ms 1}
            [0]
            :fake-child
            "unused-result"
            "unused-stdout"
            "unused-stderr"))]
    (is (= :completed (:status outcome)))
    (is (false? @terminate-called?))
    (is (nil? (get-in outcome [:diagnostics :worker-pid])))
    (is (= :worker-pid
           (get-in outcome [:diagnostics :worker-pid-error :phase])))))
