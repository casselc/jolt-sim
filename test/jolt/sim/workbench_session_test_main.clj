(ns jolt.sim.workbench-session-test-main
  (:require [clojure.test :as test]
            [jolt.sim.workbench-session-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.workbench-session-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
