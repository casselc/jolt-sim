(ns jolt.sim.command-cell-session-test-main
  (:require [clojure.test :as test]
            [jolt.sim.command-cell-session-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.command-cell-session-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
