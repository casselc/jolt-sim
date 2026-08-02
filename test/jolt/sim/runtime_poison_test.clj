(ns jolt.sim.runtime-poison-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.ffi :as ffi]
            [jolt.sim.runtime :as rt]))

(defn- ex-data-of [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(deftest undrained-worker-poisons-without-restoring
  (is (= 6 (:abi-version (rt/capabilities))))
  (let [worker-started (promise)
        worker-release (promise)
        worker (atom nil)
        data
        (ex-data-of
         #(rt/run-controlled
           {:drain-timeout-ms 75}
           (fn []
             (reset! worker
                     (future
                       (deliver worker-started true)
                       @worker-release))
             (when (= :timeout (deref worker-started 5000 :timeout))
               (throw (ex-info "worker did not start" {})))
             :body-returned)))]
    (is (= :jolt.sim.runtime/tasks-outlive-scope (:type data)))
    (is (seq (:tasks data)))
    (is (every? pos? (:tasks data)))
    ;; The process is not merely marked poisoned: both exact controller hooks
    ;; remain installed. The still-closed future hook rejects an out-of-run
    ;; worker before it can fork, and the FFI hook intercepts before native
    ;; sizeof reaches the host.
    (is (= :jolt.sim.runtime/invalid-controller-event
           (:type (ex-data-of #(future :must-not-run)))))
    (is (= :jolt.sim.runtime/unhandled-native-effect
           (:type (ex-data-of #(ffi/sizeof :int)))))
    (deliver worker-release :released)
    (is (= :released @@worker))
    ;; Even after the worker later exits, the timed-out caller has relinquished
    ;; neither exact controller token. Reusing that process must fail closed.
    (is (= :jolt.sim.runtime/session-poisoned
           (:type
            (ex-data-of
             #(rt/run-controlled {} (fn [] :unreachable))))))))

(defn -main [& _]
  (let [result (run-tests 'jolt.sim.runtime-poison-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
