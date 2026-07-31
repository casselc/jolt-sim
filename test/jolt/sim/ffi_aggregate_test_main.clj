(ns jolt.sim.ffi-aggregate-test-main
  (:require [clojure.test :as test]
            [jolt.sim.ffi-aggregate-integration-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.ffi-aggregate-integration-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
