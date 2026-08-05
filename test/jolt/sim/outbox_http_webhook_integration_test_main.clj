(ns jolt.sim.outbox-http-webhook-integration-test-main
  (:require [clojure.test :as test]
            [jolt.sim.outbox-http-webhook-integration-test :as integration]))

(def ^:private watchdog-timeout-ms 180000)

(defn- progress-file []
  (str (or (System/getenv "TMPDIR") "/tmp")
       "/jolt-sim-outbox-http-webhook-"
       (java.util.UUID/randomUUID)
       ".edn"))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))
        mode (if sim-only? :hermetic-only :real-and-hermetic)
        progress (progress-file)]
    (append-progress! progress {:phase :start :mode mode :status :running})
    (println (str "outbox HTTP webhook progress: " progress))
    (flush)
    (binding [integration/*sim-only?* sim-only?]
      (let [worker
            (future
             (test/run-tests
              'jolt.sim.outbox-http-webhook-integration-test))
            result (deref worker watchdog-timeout-ms ::timeout)]
        (cond
          (= ::timeout result)
          (do
            (append-progress! progress
                              {:phase :timeout :mode mode :status :timed-out
                               :watchdog-timeout-ms watchdog-timeout-ms})
            (println (str "FAILURE: outbox HTTP webhook test timed out after "
                          watchdog-timeout-ms "ms"))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          (pos? (+ (:fail result) (:error result)))
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish :mode mode :status :failed
                               :counts counts})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          :else
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish :mode mode :status :passed
                               :counts counts})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (flush)
            (System/exit 0)))))))
