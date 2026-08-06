(ns jolt.sim.session-test-main
  (:require [clojure.test :as test]
            [jolt.sim.explore-states-test]
            [jolt.sim.session-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.explore-states-test
                               'jolt.sim.session-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
