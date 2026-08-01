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
               :fake-child
               "unused-result"
               "unused-stdout"
               "unused-stderr"))))]
    (is (identical? unobserved thrown))
    (is (true? (@retain-var thrown)))))
