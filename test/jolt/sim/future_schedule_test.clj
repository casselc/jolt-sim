(ns jolt.sim.future-schedule-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.future-schedule :as future-schedule]))

;; Pure, ABI-independent shape validation: these run unconditionally, on any
;; image including an ordinary released one, because run-controlled checks
;; :future-schedule shape before ABI resolution.

(deftest valid-schedule-accepts-exact-permutations
  (is (true? (future-schedule/valid-schedule? [0])))
  (is (true? (future-schedule/valid-schedule? [2 0 1])))
  (is (true? (future-schedule/valid-schedule? [0 1 2 3 4]))))

(deftest valid-schedule-rejects-malformed-shapes
  (is (false? (future-schedule/valid-schedule? [])))
  (is (false? (future-schedule/valid-schedule? '(0 1))))
  (is (false? (future-schedule/valid-schedule? #{0 1})))
  (is (false? (future-schedule/valid-schedule? [1 2])))
  (is (false? (future-schedule/valid-schedule? [0 0])))
  (is (false? (future-schedule/valid-schedule? [0 2])))
  (is (false? (future-schedule/valid-schedule? [-1 0])))
  (is (false? (future-schedule/valid-schedule? [0 1.0])))
  (is (false? (future-schedule/valid-schedule? [0 "1"])))
  (is (false? (future-schedule/valid-schedule? nil))))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- ex-of [f]
  (try (f) ::not-thrown (catch :default error error)))

(deftest validate-schedule-throws-invalid-config-with-the-value-attached
  (let [data (ex-data-of #(future-schedule/validate-schedule! [1 2]))]
    (is (= :jolt.sim.runtime/invalid-config (:type data)))
    (is (= [1 2] (:future-schedule data))))
  (is (= [0 1] (future-schedule/validate-schedule! [0 1]))))

(deftest accepted-preworker-abort-is-a-primary-schedule-failure
  ;; This is the exact external-controller event stream produced when :spawn
  ;; registration succeeds but the core cannot create its worker. The core ABI
  ;; owns the fork-failure witness; this pure test proves the external scheduler
  ;; does not mistake its balancing :abort for successful completion.
  (let [scheduler (future-schedule/scheduler [0] nil)
        run
        ((:wrap-thunk scheduler)
         (fn []
           ((:on-event scheduler) {:event :spawn :task 41 :parent 0})
           ((:on-event scheduler) {:event :abort :task 41 :parent 0})))
        data (ex-data-of run)]
    (is (= :jolt.sim.runtime/schedule-error (:type data)))
    (is (= :preworker-abort (:reason data)))
    (is (= 0 (:ordinal data)))
    (is (= data (ex-data ((:failure scheduler)))))))

(deftest finish-admits-the-next-ordinal-before-any-exit-event
  ;; Feed the lifecycle interpreter directly so the claim cannot accidentally
  ;; depend on OS thread ordering or on where an :exit callback happens to
  ;; block. No :exit has been supplied when ordinal 1 becomes admitted.
  (let [scheduler (future-schedule/scheduler [0 1] nil)
        on-event (:on-event scheduler)]
    (on-event {:event :spawn :task 41 :parent 0})
    (on-event {:event :spawn :task 42 :parent 0})
    (on-event {:event :start :task 41 :parent 0})
    (on-event {:event :finish :task 41 :parent 0})
    (is (= [[:admit 0] [:complete 0] [:admit 1]]
           ((:evidence scheduler))))
    (on-event {:event :exit :task 41 :parent 0})
    (is (= [[:admit 0] [:complete 0] [:admit 1]]
           ((:evidence scheduler))))))

(deftest cancel-before-start-retains-the-primary-cancellation-failure
  ;; Ordinal 0 is gated behind ordinal 1. A lifecycle :cancel therefore arrives
  ;; before task 41's :start callback is invoked. The later start sees only the
  ;; internal gate-abort sentinel; it cannot replace the retained cancellation
  ;; failure.
  (let [scheduler (future-schedule/scheduler [1 0] nil)
        on-event (:on-event scheduler)
        _ (on-event {:event :spawn :task 41 :parent 0})
        _ (on-event {:event :spawn :task 42 :parent 0})
        cancel-error
        (ex-of #(on-event {:event :cancel :task 41 :parent 0}))
        start-error
        (ex-of #(on-event {:event :start :task 41 :parent 0}))]
    (is (= :jolt.sim.runtime/schedule-error
           (:type (ex-data cancel-error))))
    (is (= :cancellation-unsupported
           (:reason (ex-data cancel-error))))
    (is (future-schedule/gate-aborted? start-error))
    (is (identical? cancel-error ((:failure scheduler))))
    (is (= [[:admit 1]] ((:evidence scheduler))))))
