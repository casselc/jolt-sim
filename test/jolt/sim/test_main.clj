(ns jolt.sim.test-main
  (:require [clojure.test :as test]
            [jolt.sim.completion-test]
            [jolt.sim.ffi-world-integration-test]
            [jolt.sim.ffi-memory-test]
            [jolt.sim.future-schedule-test]
            [jolt.sim.kernel-test]
            [jolt.sim.monitor-test]
            [jolt.sim.replay-test]
            [jolt.sim.runtime-test]
            [jolt.sim.sqlite-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.sim.completion-test
                        'jolt.sim.ffi-world-integration-test
                        'jolt.sim.ffi-memory-test
                        'jolt.sim.future-schedule-test
                        'jolt.sim.kernel-test
                        'jolt.sim.monitor-test
                        'jolt.sim.replay-test
                        'jolt.sim.runtime-test
                        'jolt.sim.sqlite-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
