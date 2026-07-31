(ns jolt.sim.test-main
  (:require [clojure.test :as test]
            [jolt.sim.completion-test]
            [jolt.sim.kernel-test]
            [jolt.sim.monitor-test]
            [jolt.sim.replay-test]
            [jolt.sim.runtime-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.sim.completion-test
                        'jolt.sim.kernel-test
                        'jolt.sim.monitor-test
                        'jolt.sim.replay-test
                        'jolt.sim.runtime-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
