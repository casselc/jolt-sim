(ns jolt.sim.workbench-test-main
  (:require [clojure.test :as test]
            [jolt.sim.workbench-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.workbench-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
