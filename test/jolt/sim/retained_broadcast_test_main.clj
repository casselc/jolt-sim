(ns jolt.sim.retained-broadcast-test-main
  (:require [clojure.test :as test]
            [jolt.sim.retained-broadcast-test]))

(def ^:private watchdog-timeout-ms 240000)

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_RETAINED_BROADCAST_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-retained-broadcast-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "retained Broadcast progress:" progress)
    (flush)
    (let [run (future (test/run-tests 'jolt.sim.retained-broadcast-test))
          result (deref run watchdog-timeout-ms ::timeout)]
      (cond
        (= ::timeout result)
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :watchdog-timeout-ms watchdog-timeout-ms})
          (println "FAILURE: retained Broadcast gate timed out")
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
          ;; The parent-side process supervisor owns threads until explicit
          ;; process exit; the worker itself must already be reaped above.
          (System/exit 0))))))
