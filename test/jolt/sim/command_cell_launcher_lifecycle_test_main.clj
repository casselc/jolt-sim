(ns jolt.sim.command-cell-launcher-lifecycle-test-main
  (:require [clojure.test :as test]
            [jolt.sim.command-cell-launcher-lifecycle-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.sim.command-cell-launcher-lifecycle-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
