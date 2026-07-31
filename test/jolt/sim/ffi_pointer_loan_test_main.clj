(ns jolt.sim.ffi-pointer-loan-test-main
  (:require [clojure.test :as test]
            [jolt.sim.ffi-pointer-loan-integration-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.sim.ffi-pointer-loan-integration-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
