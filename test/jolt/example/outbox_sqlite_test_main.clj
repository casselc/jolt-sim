(ns jolt.example.outbox-sqlite-test-main
  (:require [clojure.test :as test]
            [jolt.example.outbox-sqlite-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.example.outbox-sqlite-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
