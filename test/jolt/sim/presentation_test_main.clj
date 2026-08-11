(ns jolt.sim.presentation-test-main
  (:require [clojure.test :as test]
            [jolt.sim.presentation-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.presentation-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
