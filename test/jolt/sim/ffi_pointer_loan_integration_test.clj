(ns jolt.sim.ffi-pointer-loan-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.ffi-pointer-loan :as fixture]
            [jolt.sim.runtime :as runtime]))

(def ^:private successful-result
  {:length 2
   :native-read 202
   :bytes [10 201 202 40]})

(def ^:private throwing-result
  {:caught :fixture/pointer-loan-failure
   :bytes [9 77 7]})

(defn- operations [controlled]
  (mapv :operation (:effects controlled)))

(deftest ordinary-pointer-loan-fixture-reaches-real-native-memory
  (is (= successful-result (fixture/exercise-loan)))
  (is (= throwing-result (fixture/exercise-throwing-loan))))

(deftest unchanged-pointer-loan-fixture-runs-against-simulated-memory
  (is (true? (runtime/available?)))
  (when (runtime/available?)
    (is (= 3
           (get-in (runtime/capabilities)
                   [:ffi-interception :descriptor-version])))
    (let [world (memory/world)
          controlled
          (runtime/run-controlled
           {:ffi-handlers (memory/handlers world)}
           fixture/exercise-loan)]
      (is (= successful-result (:result controlled)))
      (is (= [:borrow-byte-array :write :read :release-byte-array]
             (operations controlled)))
      (is (true? (memory/clean? world)))
      (is (empty? (memory/leaks world))))
    (let [world (memory/world)
          controlled
          (runtime/run-controlled
           {:ffi-handlers (memory/handlers world)}
           fixture/exercise-throwing-loan)]
      (is (= throwing-result (:result controlled)))
      (is (= [:borrow-byte-array :write :release-byte-array]
             (operations controlled)))
      (is (true? (memory/clean? world)))
      (is (empty? (memory/leaks world))))))
