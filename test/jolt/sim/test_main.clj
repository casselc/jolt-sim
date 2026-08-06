(ns jolt.sim.test-main
  (:require [clojure.test :as test]
            [jolt.example.outbox-test]
            [jolt.maelstrom.echo-sim-test]
            [jolt.maelstrom.echo-test]
            [jolt.sim.case-outcome-test]
            [jolt.sim.clock-test]
            [jolt.sim.completion-test]
            [jolt.sim.explore-states-test]
            [jolt.sim.explore-test]
            [jolt.sim.explore-worker-test]
            [jolt.sim.experiment-test]
            [jolt.sim.fault-test]
            [jolt.sim.ffi-world-integration-test]
            [jolt.sim.ffi-memory-test]
            [jolt.sim.ffi-schedule-test]
            [jolt.sim.future-schedule-test]
            [jolt.sim.handler-pack-test]
            [jolt.sim.kernel-test]
            [jolt.sim.maelstrom-history-test]
            [jolt.sim.monitor-test]
            [jolt.sim.pack-registry-test]
            [jolt.sim.posix-fault-test]
            [jolt.sim.posix-loopback-model-test]
            [jolt.sim.process-explorer-test]
            [jolt.sim.repl-test]
            [jolt.sim.replay-test]
            [jolt.sim.runtime-test]
            [jolt.sim.schema-test]
            [jolt.sim.session-test]
            [jolt.sim.sqlite-test]))

(defn -main [& _]
  (let [result
        (test/run-tests 'jolt.example.outbox-test
                        'jolt.maelstrom.echo-sim-test
                        'jolt.maelstrom.echo-test
                        'jolt.sim.case-outcome-test
                        'jolt.sim.clock-test
                        'jolt.sim.completion-test
                        'jolt.sim.explore-states-test
                        'jolt.sim.explore-test
                        'jolt.sim.explore-worker-test
                        'jolt.sim.experiment-test
                        'jolt.sim.fault-test
                        'jolt.sim.ffi-world-integration-test
                        'jolt.sim.ffi-memory-test
                        'jolt.sim.ffi-schedule-test
                        'jolt.sim.future-schedule-test
                        'jolt.sim.handler-pack-test
                        'jolt.sim.kernel-test
                        'jolt.sim.maelstrom-history-test
                        'jolt.sim.monitor-test
                        'jolt.sim.pack-registry-test
                        'jolt.sim.posix-fault-test
                        'jolt.sim.posix-loopback-model-test
                        'jolt.sim.process-explorer-test
                        'jolt.sim.repl-test
                        'jolt.sim.replay-test
                        'jolt.sim.runtime-test
                        'jolt.sim.schema-test
                        'jolt.sim.session-test
                        'jolt.sim.sqlite-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
