(ns jolt.maelstrom.broadcast-live-test-main
  (:require [clojure.test :as test]
            [jolt.maelstrom.broadcast-live-test]))

(def ^:private watchdog-timeout-ms 120000)

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_BROADCAST_LIVE_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-broadcast-live-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "interactive Broadcast progress:" progress)
    (flush)
    (let [run (future (test/run-tests 'jolt.maelstrom.broadcast-live-test))
          result (deref run watchdog-timeout-ms ::timeout)]
      (cond
        (= ::timeout result)
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :watchdog-timeout-ms watchdog-timeout-ms})
          (println "FAILURE: interactive Broadcast gate timed out")
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
          (System/exit 0))))))
