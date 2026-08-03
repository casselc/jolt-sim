(ns jolt.sim.outbox-delivery-integration-test-main
  (:require [clojure.test :as test]
            [jolt.sim.outbox-delivery-integration-test :as integration]))

(def ^:private watchdog-timeout-ms 180000)

(defn- nonempty-env [name]
  (let [value (System/getenv name)]
    (when (seq value) value)))

(defn- progress-file []
  (or (nonempty-env "JOLT_SIM_OUTBOX_DELIVERY_PROGRESS_FILE")
      (str (or (nonempty-env "TMPDIR") "/tmp")
           "/jolt-sim-outbox-delivery-"
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
    (println (str "outbox-delivery progress: " progress))
    (flush)
    (binding [integration/*sim-only?* sim-only?]
      (let [worker
            (future
             (test/run-tests
              'jolt.sim.outbox-delivery-integration-test))
            result (deref worker watchdog-timeout-ms ::timeout)]
        (if (= ::timeout result)
          (do
            (append-progress! progress
                              {:phase :timeout
                               :mode mode
                               :status :timed-out
                               :counts nil
                               :watchdog-timeout-ms watchdog-timeout-ms})
            (println
             (str "FAILURE: outbox-delivery test timed out after "
                  watchdog-timeout-ms "ms"))
            (flush)
            (System/exit 1))
          (let [counts (select-keys result [:test :pass :fail :error])
                failures (+ (:fail result) (:error result))
                status (if (zero? failures) :passed :failed)]
            (append-progress! progress
                              {:phase :finish
                               :mode mode
                               :status status
                               :counts counts})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (flush)
            ;; jolt-http loads core.async, whose non-daemon threads keep the
            ;; process alive after a successful test run.
            (System/exit (if (zero? failures) 0 1))))))))
