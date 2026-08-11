(ns jolt.sim.retained-outbox-test-main
  (:require [clojure.test :as test]
            [jolt.sim.retained-outbox-test]))

(def ^:private watchdog-timeout-ms 240000)

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_RETAINED_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-retained-outbox-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "retained outbox progress:" progress)
    (flush)
    (let [run (future (test/run-tests 'jolt.sim.retained-outbox-test))
          result (deref run watchdog-timeout-ms ::timeout)]
      (cond
        (= ::timeout result)
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :watchdog-timeout-ms watchdog-timeout-ms})
          (println "FAILURE: retained outbox gate timed out")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (pos? (+ (:fail result) (:error result)))
        (do
          (append-progress! progress
                            {:phase :finish :status :failed
                             :counts (select-keys result
                                                  [:test :pass :fail :error])})
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        :else
        (do
          (append-progress! progress
                            {:phase :finish :status :passed
                             :counts (select-keys result
                                                  [:test :pass :fail :error])})
          (println "progress:" progress)
          (flush)
          ;; The real HTTP graph loads core.async non-daemon workers.
          (System/exit 0))))))
