(ns jolt.sim.flow-test-main
  (:require [clojure.test :as test]
            [jolt.sim.flow-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.flow-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
