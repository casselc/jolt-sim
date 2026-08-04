(ns jolt.sim.clock-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.host :as host]
            [jolt.sim.clock :as clock]
            [jolt.sim.runtime :as runtime]))

(defn- thrown-data [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(deftest virtual-clock-controls-the-runtime-clock-domain
  (let [c (clock/virtual-clock 17)
        controller (clock/controller c)]
    (is (= 17 (clock/now-nanos c)))
    (is (= 17 (controller {:kind :clock :operation :mono-nanos} nil)))
    (is (= {:now-nanos 17 :next-alarm-id 0 :alarms []}
           (clock/snapshot c)))
    (is (= :invalid-clock-descriptor
           (:reason
            (thrown-data
             #(controller {:kind :clock :operation :wall-time} nil)))))))

(deftest one-runtime-controller-drives-both-ordinary-clock-facades
  (if (runtime/available?)
    (let [c (clock/virtual-clock 4242)
          controlled
          (runtime/run-controlled
           {:clock (clock/controller c)}
           (fn []
             [(System/nanoTime) (host/mono-nanos)]))]
      (is (= [4242 4242] (:result controlled))))
    ;; The ordinary source suite intentionally runs on a non-sim image. The
    ;; direct controller contract above remains portable there; this branch
    ;; records that only the ABI integration witness requires a sim image.
    (is (false? (runtime/available?)))))

(deftest already-due-registration-delivers-before-returning
  ;; Regression for the proof's SAT buggy control: retaining this alarm as
  ;; pending would lose the only wake when time had advanced before register.
  (let [c (clock/virtual-clock 10)
        alarm (clock/register-alarm! c 10)]
    (is (= :deadline (deref (:signal alarm) 0 :not-delivered)))
    (is (= {:now-nanos 10 :next-alarm-id 1 :alarms []}
           (clock/snapshot c)))
    (is (false? (clock/cancel-alarm! c alarm)))
    (is (= 0 (clock/advance-to! c 10)))))

(deftest future-alarms-fire-once-in-deadline-and-id-order
  (let [c (clock/virtual-clock)
        later (clock/register-alarm! c 20)
        first-at-ten (clock/register-alarm! c 10)
        second-at-ten (clock/register-alarm! c 10)]
    (is (= 10 (clock/next-deadline c)))
    (is (= [{:id 1 :deadline-nanos 10}
            {:id 2 :deadline-nanos 10}
            {:id 0 :deadline-nanos 20}]
           (:alarms (clock/snapshot c))))
    (is (= 0 (clock/advance-to! c 9)))
    (is (= :pending (deref (:signal first-at-ten) 0 :pending)))
    (is (= 2 (clock/advance-to! c 10)))
    (is (= :deadline (deref (:signal first-at-ten) 0 :pending)))
    (is (= :deadline (deref (:signal second-at-ten) 0 :pending)))
    (is (= :pending (deref (:signal later) 0 :pending)))
    (is (= 0 (clock/advance-to! c 10)))
    (is (= 1 (clock/advance-to! c 20)))
    (is (= :deadline (deref (:signal later) 0 :pending)))
    (is (nil? (clock/next-deadline c)))
    (is (= 0 (clock/advance-to! c 30)))))

(deftest cancellation-is-idempotent-and-prevents-later-delivery
  (let [c (clock/virtual-clock 5)
        alarm (clock/register-alarm! c 8)]
    (is (true? (clock/cancel-alarm! c alarm)))
    (is (false? (clock/cancel-alarm! c alarm)))
    (is (= 0 (clock/advance-to! c 8)))
    (is (= :cancelled (deref (:signal alarm) 0 :cancelled)))
    (is (= [] (:alarms (clock/snapshot c))))))

(deftest invalid-movement-and-foreign-tokens-fail-without-mutation
  (let [c (clock/virtual-clock 11)
        other (clock/virtual-clock 11)
        alarm (clock/register-alarm! c 12)
        before (clock/snapshot c)]
    (testing "time cannot move backward"
      (is (= :clock-moved-backward
             (:reason (thrown-data #(clock/advance-to! c 10)))))
      (is (= before (clock/snapshot c))))
    (testing "an alarm token is owned by exactly one clock"
      (is (= :invalid-alarm-token
             (:reason
              (thrown-data #(clock/cancel-alarm! other alarm)))))
      (is (= before (clock/snapshot c))))
    (is (true? (clock/cancel-alarm! c alarm)))))

(deftest advance-by-uses-the-current-virtual-time
  (let [c (clock/virtual-clock 100)
        alarm (clock/register-alarm! c 105)]
    (is (= 0 (clock/advance-by! c 4)))
    (is (= 104 (clock/now-nanos c)))
    (is (= 1 (clock/advance-by! c 1)))
    (is (= :deadline (deref (:signal alarm) 0 :pending)))
    (is (= :invalid-time-delta
           (:reason (thrown-data #(clock/advance-by! c -1)))))))
