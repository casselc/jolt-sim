(ns jolt.sim.future-schedule-poison-test
  "The scheduler makes no deadlock-recovery claim (see jolt.sim.future-schedule):
  a schedule whose admission order is incompatible with how the thunk's own
  thread actually depends on its futures can block indefinitely, as unchanged
  application code can. This test makes only a bounded no-progress observation
  after proving the first spawn reached its gate; it runs on an isolated daemon
  thread and then exits the process instead of hanging the shared test session."
  (:require [clojure.test :refer [deftest is run-tests]]
            [jolt.sim.runtime :as rt]))

(deftest incompatible-order-reaches-a-gate-and-makes-no-bounded-progress
  (is (= 5 (:abi-version (rt/capabilities))))
  (let [first-spawned (promise)
        outcome (promise)
        runner
        (Thread.
         (fn []
           (deliver
            outcome
            (try
              (rt/run-controlled
               {:future-schedule [1 0]
                :on-event
                (fn [{:keys [event]}]
                  (when (= :spawn event)
                    (deliver first-spawned true)))}
               (fn []
                 ;; The thunk blocks on the first future before the schedule
                 ;; ever lets it run: ordinal 0 cannot be admitted until
                 ;; ordinal 1's :finish, but ordinal 1 is never spawned
                 ;; because this very thread is stuck waiting on ordinal 0's
                 ;; body.
                 (let [first-future (future :first)
                       _ @first-future
                       second-future (future :second)]
                   [@first-future @second-future])))
              :completed
              (catch :default _ :threw)))))]
    (.setDaemon runner true)
    (.start runner)
    (is (true? (deref first-spawned 5000 :timeout))
        "the first ordinal must reach its scheduler gate")
    (is (= :timeout (deref outcome 500 :timeout))
        "the scheduler must not invent progress during the observation")))

(defn -main [& _]
  (let [result (run-tests 'jolt.sim.future-schedule-poison-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    ;; The blocked runner thread is a daemon; exiting reclaims it.
    (System/exit (if (zero? failures) 0 1))))
