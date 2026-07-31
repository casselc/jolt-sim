(ns jolt.sim.ffi-aggregate-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.fixtures.ffi-aggregate :as fixture]
            [jolt.sim.handler-pack :as handler-pack]
            [jolt.sim.runtime :as runtime]))

(def ^:private symbol
  "definitely_not_a_real_jolt_sim_aggregate_symbol_zzz9")

(defn- ex-data-of [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(deftest unchanged-aggregate-binding-routes-by-recursive-identity
  (is (true? (runtime/available?)))
  (is (= 5 (:abi-version (runtime/capabilities))))
  (is (= 4 (get-in (runtime/capabilities)
                   [:ffi-interception :descriptor-version])))
  (let [calls (atom [])
        key (handler-pack/foreign-function-key
             symbol [fixture/date-argument-type] :int32 true true)
        controlled
        (runtime/run-controlled
         {:ffi-handlers
          {key (fn [descriptor]
                 (swap! calls conj descriptor)
                 [314 73])}}
         fixture/exercise-aggregate)
        descriptor (first @calls)]
    (is (= [314 73] (:result controlled)))
    (is (= 1 (count @calls)))
    (is (= [fixture/date-argument-type] (:argument-types descriptor)))
    (is (= [0] (:arguments descriptor)))
    (is (= true (:blocking? descriptor)))
    (is (= true (:capture-native-error? descriptor)))
    (is (= [:handler] (mapv :route (:effect-trace controlled))))
    (is (= [key] (mapv :handler-key (:effect-trace controlled))))))

(deftest unchanged-aggregate-binding-routes-through-the-hybrid-controller
  (let [key (handler-pack/foreign-function-key
             symbol [fixture/date-argument-type] :int32 true true)
        controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers
          {key (fn [_descriptor]
                 (runtime/substitute [271 19]))}}
         fixture/exercise-aggregate)]
    (is (= [271 19] (:result controlled)))
    (is (= [:hybrid] (mapv :mode (:effect-trace controlled))))
    (is (= [:handler] (mapv :route (:effect-trace controlled))))
    (is (= [key] (mapv :handler-key (:effect-trace controlled))))
    (is (= [fixture/date-argument-type]
           (get-in controlled [:effects 0 :argument-types])))))

(deftest a-different-recursive-shape-does-not-match
  (let [different
        [:by-value
         [:struct
          [[:date [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]]
           [:different-zone :int16]]]]
        wrong-key (handler-pack/foreign-function-key
                   symbol [different] :int32 true true)
        data
        (ex-data-of
         #(runtime/run-controlled
           {:ffi-handlers {wrong-key (fn [_] [0 0])}}
           fixture/exercise-aggregate))]
    (is (= :jolt.sim.runtime/unhandled-native-effect (:type data)))
    (is (= [fixture/date-argument-type]
           (get-in data [:descriptor :argument-types])))))
