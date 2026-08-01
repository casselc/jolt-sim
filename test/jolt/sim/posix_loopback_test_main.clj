(ns jolt.sim.posix-loopback-test-main
  (:require [clojure.test :as test]
            [jolt.sim.posix-loopback-integration-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.sim.posix-loopback-integration-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
