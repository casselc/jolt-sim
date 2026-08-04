(ns jolt.sim.http-sqlite-test-main
  (:require [clojure.test :as test]
            [jolt.sim.http-sqlite-integration-test :as integration]))

(def ^:private progress-file "/tmp/jolt-sim-http-sqlite-test.progress")
;; Six serial end-to-end cases now each start and stop a real HTTP/TCP/SQLite
;; lifecycle. The prior two-case gate completed in roughly 34 seconds on the
;; reference Linux host, so its 60-second suite-wide bound cannot distinguish
;; the expanded healthy workload from a hang. Retain one bounded outer
;; watchdog with headroom for slower CI architectures.
(def ^:private watchdog-timeout-ms 240000)

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))]
    (spit progress-file "starting\n")
    (binding [integration/*sim-only?* sim-only?]
      (let [worker (future
                     (test/run-tests 'jolt.sim.http-sqlite-integration-test))
            result (deref worker watchdog-timeout-ms ::timeout)]
        (if (= ::timeout result)
          (do
            (spit progress-file "timed out\n")
            (println (str "FAILURE: http-sqlite-test-main timed out after "
                          watchdog-timeout-ms "ms"))
            (flush)
            (System/exit 1))
          (let [failures (+ (:fail result) (:error result))]
            (spit progress-file "done\n")
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (flush)
            ;; jolt-http loads core.async, which starts non-daemon threads;
            ;; the process will not exit on its own.
            (System/exit (if (zero? failures) 0 1))))))))
