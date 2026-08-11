(ns jolt.sim.retained-test-main
  (:require [clojure.test :as test]
            [jolt.sim.retained-process-test]
            [jolt.sim.retained-worker-test]))

(defn -main [& _]
  (let [worker (future
                 (test/run-tests 'jolt.sim.retained-process-test
                                 'jolt.sim.retained-worker-test))
        result (deref worker 30000 ::timeout)
        _ (when (= ::timeout result)
            (println "retained protocol gate exceeded 30000ms")
            (flush)
            (System/exit 124))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
