(ns jolt.sim.outbox-json-delivery-integration-test-main
  (:require [clojure.test :as test]
            [jolt.sim.outbox-json-delivery-integration-test :as integration]))

(def ^:private watchdog-timeout-ms 180000)

(defn- nonempty-env [name]
  (let [value (System/getenv name)]
    (when (seq value) value)))

(defn- progress-file []
  (or (nonempty-env "JOLT_SIM_OUTBOX_JSON_DELIVERY_PROGRESS_FILE")
      (str (or (nonempty-env "TMPDIR") "/tmp")
           "/jolt-sim-outbox-json-delivery-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  ;; Best-effort append-only breadcrumbs for a single test process. This is not
  ;; the later crash-safe journal/WAL contract.
  (spit path (str (pr-str record) "\n") :append true))

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))
        mode (if sim-only? :hermetic-only :real-and-hermetic)
        progress (progress-file)]
    (append-progress! progress
                      {:phase :start
                       :mode mode
                       :status :running
                       :counts nil})
    (println (str "outbox-json-delivery progress: " progress))
    (flush)
    (binding [integration/*sim-only?* sim-only?]
      (let [worker
            (future
             (test/run-tests
              'jolt.sim.outbox-json-delivery-integration-test))
            result (deref worker watchdog-timeout-ms ::timeout)]
        (cond
          (= ::timeout result)
          (do
            (append-progress! progress
                              {:phase :timeout
                               :mode mode
                               :status :timed-out
                               :counts nil
                               :watchdog-timeout-ms watchdog-timeout-ms})
            (println
             (str "FAILURE: outbox-json-delivery test timed out after "
                  watchdog-timeout-ms "ms"))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          (pos? (+ (:fail result) (:error result)))
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish
                               :mode mode
                               :status :failed
                               :counts counts})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          :else
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish
                               :mode mode
                               :status :passed
                               :counts counts})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (flush)
            ;; jolt-http loads core.async, whose non-daemon threads keep the
            ;; process alive after a successful test run.
            (System/exit 0)))))))
