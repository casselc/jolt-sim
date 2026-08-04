(ns jolt.sim.outbox-sqlite-integration-test-main
  "Executable gate for jolt.sim.outbox-sqlite-integration-test. Accepts
  --sim-only to skip the real-SQLite lane, following the existing sqlite
  integration pattern."
  (:require [clojure.test :as test]
            [jolt.sim.outbox-sqlite-integration-test :as integration]))

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))
        result (binding [integration/*sim-only?* sim-only?]
                 (test/run-tests 'jolt.sim.outbox-sqlite-integration-test))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
