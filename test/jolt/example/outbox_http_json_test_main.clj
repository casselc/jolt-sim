(ns jolt.example.outbox-http-json-test-main
  (:require [clojure.test :as test]
            [jolt.example.outbox-http-json-test]))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.example.outbox-http-json-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
