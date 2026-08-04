(ns jolt.sim.posix-loopback-http-test-main
  (:require [clojure.test :as test]
            [jolt.sim.posix-loopback-http-integration-test]))

(def ^:private progress-file "/tmp/jolt-sim-posix-loopback-http-test.progress")
(def ^:private watchdog-timeout-ms 60000)

(defn -main [& _]
  (spit progress-file "starting\n")
  (let [worker (future (test/run-tests 'jolt.sim.posix-loopback-http-integration-test))
        result (deref worker watchdog-timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do
        (spit progress-file "timed out\n")
        (println (str "FAILURE: posix-loopback-http-test-main timed out after "
                      watchdog-timeout-ms "ms"))
        (flush)
        (System/exit 1))
      (let [failures (+ (:fail result) (:error result))]
        (spit progress-file "done\n")
        (println (str (:test result) " tests, "
                      (:pass result) " assertions passed"))
        (flush)
        ;; jolt-http loads core.async, which starts non-daemon threads; the
        ;; process will not exit on its own.
        (System/exit (if (zero? failures) 0 1))))))
