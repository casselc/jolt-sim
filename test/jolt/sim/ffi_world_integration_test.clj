(ns jolt.sim.ffi-world-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.ffi-roundtrip :as fixture]
            [jolt.sim.runtime :as runtime]))

(defn- abi-version []
  (when (runtime/available?)
    (:abi-version (runtime/capabilities))))

(defn- assert-roundtrip [result]
  (is (= -1234567 (:integer result)))
  (is (= [0 127 128 255 10] (:bytes result)))
  (is (= "native \u00e9" (:text result)))
  (is (= 5 (:written result)))
  (is (= 4 (:int-size result)))
  (is (contains? #{4 8} (:pointer-size result))))

(deftest unchanged-ffi-namespace-runs-against-real-native-memory
  (assert-roundtrip (fixture/exercise-memory)))

(deftest unchanged-ffi-namespace-runs-against-the-simulated-world
  (when (contains? #{2 3} (abi-version))
    (let [real-result (fixture/exercise-memory)
          world
          (memory/world {:pointer-size (:pointer-size real-result)})
          controlled
          (runtime/run-controlled
           {:ffi-handlers (memory/handlers world)}
           fixture/exercise-memory)
          operations (mapv :operation (:effects controlled))]
      (is (= real-result (:result controlled)))
      (assert-roundtrip (:result controlled))
      (is (= [:load-library :sizeof :sizeof :alloc :string->ptr
              :write :write-array :read :read-array :ptr->string
              :free :free]
             operations))
      (is (= 0 (get-in controlled [:effects 5 :arguments 2])))
      (is (true? (memory/clean? world)))
      (is (empty? (memory/leaks world))))))
