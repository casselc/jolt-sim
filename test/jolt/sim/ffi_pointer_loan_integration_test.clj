(ns jolt.sim.ffi-pointer-loan-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.ffi-pointer-loan :as fixture]
            [jolt.sim.runtime :as runtime]))

;; Descriptor-version 9 keeps the scoped byte-array loan lifecycle
;; runtime-owned: no borrow or release operation ever crosses the controller
;; boundary, and jolt-sim deliberately does not recreate loans in its
;; deterministic memory world. Its array-transfer handlers may copy through
;; the active runtime view while the loan is live; they cannot retain, retire,
;; or otherwise own it. Only the ordinary operations enclosed in the loan
;; scope are intercepted; these gates pin that exact boundary.

(def ^:private successful-result
  {:length 2
   :native-read 202
   :bytes [10 -55 -54 40]})

(def ^:private throwing-result
  {:caught :fixture/pointer-loan-failure
   :bytes [9 77 7]})

(defn- operations [effects]
  (mapv :operation effects))

(defn- ex-data-of [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- native-selecting-handlers
  "Explicit native selection for the enclosed read/write operations of one
  runtime-owned loan scope. The loan lifecycle itself never crosses the
  controller boundary, so there are no loan operations left to handle."
  []
  {[:native-operation :write] (fn [_] (runtime/proceed))
   [:native-operation :read] (fn [_] (runtime/proceed))})

(deftest ordinary-pointer-loan-fixture-reaches-real-native-memory
  (is (= successful-result (fixture/exercise-loan)))
  (is (= throwing-result (fixture/exercise-throwing-loan))))

(deftest unchanged-pointer-loan-fixture-can-observe-real-native-memory
  ;; Observe routing lets the same ordinary body proceed through the exact
  ;; real loan/callback/copy-back path while jolt-sim records every native
  ;; route. Core Jolt, not a handler pack, remains responsible for unlock
  ;; ownership; the recorded boundary contains only the enclosed operations.
  (is (= 6 (:abi-version (runtime/capabilities))))
  (is (= 9
         (get-in (runtime/capabilities)
                 [:ffi-interception :descriptor-version])))
  (let [controlled
        (runtime/run-controlled {:ffi-mode :observe} fixture/exercise-loan)]
    (is (= successful-result (:result controlled)))
    (is (= [:write :write :read] (operations (:effects controlled))))
    (is (every? #(= :native (:route %)) (:effect-trace controlled))))
  (let [controlled
        (runtime/run-controlled
         {:ffi-mode :observe}
         fixture/exercise-throwing-loan)]
    (is (= throwing-result (:result controlled)))
    (is (= [:write] (operations (:effects controlled))))
    (is (every? #(= :native (:route %)) (:effect-trace controlled)))))

(deftest unchanged-pointer-loan-fixture-selects-native-routes-through-hybrid
  ;; A registered hybrid handler may explicitly select the native branch for
  ;; the loan scope's enclosed read/write operations. The runtime-owned loan
  ;; pointer is real, so each exact native branch performs the mutation and
  ;; the ordinary copy-back publishes it; every route records :native while
  ;; retaining the selecting handler's identity, distinguishing an explicit
  ;; selection from an ordinary unhandled-descriptor miss.
  (let [controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (native-selecting-handlers)}
         fixture/exercise-loan)]
    (is (= successful-result (:result controlled)))
    (is (= [:write :write :read] (operations (:effects controlled))))
    (is (every? #(= :native (:route %)) (:effect-trace controlled)))
    (is (every? #(contains? % :handler-key) (:effect-trace controlled))))
  (let [controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (native-selecting-handlers)}
         fixture/exercise-throwing-loan)]
    (is (= throwing-result (:result controlled)))
    (is (= [:write] (operations (:effects controlled))))
    (is (every? #(= :native (:route %)) (:effect-trace controlled)))))

(deftest simulated-scalar-memory-operation-rejects-the-runtime-owned-loan-pointer
  ;; jolt-sim does not recreate scoped loans. The deterministic memory world
  ;; owns only its own fake allocations, so a scalar :write against the
  ;; runtime-owned real loan pointer still fails closed as unknown. Boundary
  ;; providers use the explicit array-view copying seam tested below; they do
  ;; not gain general raw scalar access to host memory.
  ;; The ordinary fixture catches that immediate error; the routing policy
  ;; latch must still reject the enclosing run, and the recorded boundary
  ;; contains no loan descriptor at all.
  (is (= 6 (:abi-version (runtime/capabilities))))
  (is (= 9
         (get-in (runtime/capabilities)
                 [:ffi-interception :descriptor-version])))
  (let [world (memory/world)
        data
        (ex-data-of
         #(runtime/run-controlled
           {:ffi-handlers (memory/handlers world)}
           fixture/exercise-throwing-loan))]
    (is (= :jolt.sim.runtime/controller-error (:type data)))
    (is (= [:write] (operations (:effects data))))
    (is (= [:handler] (mapv :route (:effect-trace data))))
    (is (some #(= :handler-error (:ffi-error %)) (:errors data)))
    (is (some #(= :jolt.sim.ffi-memory/unknown-pointer
                  (get-in % [:error :data :type]))
              (:errors data)))
    (is (true? (memory/clean? world)))
    (is (empty? (memory/leaks world)))))

(deftest deterministic-array-handlers-copy-through-only-the-active-loan-view
  (let [world (memory/world)
        handlers (memory/handlers world)
        read-array (get handlers [:native-operation :read-array])
        write-array (get handlers [:native-operation :write-array])
        bytes (byte-array [10 20 30 40])
        captured (atom nil)
        inside
        (ffi/with-byte-array-pointer
          bytes
          (fn [pointer length]
            (reset! captured pointer)
            {:before
             (vec
              (read-array
               {:kind :native-operation :task 0 :operation :read-array
                :arguments [pointer length]}))
             :written
             (write-array
              {:kind :native-operation :task 0 :operation :write-array
               :arguments [(inc pointer) (byte-array [9 -1])]})
             :after
             (vec
              (read-array
               {:kind :native-operation :task 0 :operation :read-array
                :arguments [pointer length]}))}))]
    (is (= {:before [10 20 30 40]
            :written 2
            :after [10 9 -1 40]}
           inside))
    (is (= [10 9 -1 40] (vec bytes)))
    (let [data
          (ex-data-of
           #(read-array
             {:kind :native-operation :task 0 :operation :read-array
              :arguments [@captured 1]}))]
      (is (= :jolt.sim.ffi-memory/unknown-pointer (:type data))))
    (is (true? (memory/clean? world)))
    (is (empty? (memory/leaks world)))))
