(ns jolt.sim.flow-effect-view-test-main
  (:require [clojure.test :as test]
            [jolt.sim.flow-effect-view-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.flow-effect-view-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
