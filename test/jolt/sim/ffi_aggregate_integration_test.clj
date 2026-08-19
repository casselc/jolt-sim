(ns jolt.sim.ffi-aggregate-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jolt.sim.runtime :as runtime]))

(def ^:private date-type
  [:by-value
   [:struct [[:year :int32]
             [:month :uint8]
             [:day :uint8]]]])

(def ^:private nested-type
  [:by-value
   [:struct [[:tag :uint8]
             [:date [:struct [[:year :int32]
                              [:month :uint8]
                              [:day :uint8]]]]
             [:tail :uint16]]]])

;; These symbols deliberately do not exist. A passing test proves the current
;; sim image routed each call through descriptor-v9 interception before lazy
;; native symbol resolution could run.
(ffi/defcfn ghost-consume-date
  "jolt_sim_missing_consume_date_descriptor_v9"
  [[:by-value
    [:struct [[:year :int32]
              [:month :uint8]
              [:day :uint8]]]]]
  :int
  {:blocking true})

(ffi/defcfn ghost-consume-nested
  "jolt_sim_missing_consume_nested_descriptor_v9"
  [[:by-value
    [:struct [[:tag :uint8]
              [:date [:struct [[:year :int32]
                               [:month :uint8]
                               [:day :uint8]]]]
              [:tail :uint16]]]]]
  :int)

(ffi/defcfn ghost-make-date
  "jolt_sim_missing_make_date_descriptor_v9"
  []
  [:by-value
   [:struct [[:year :int32]
             [:month :uint8]
             [:day :uint8]]]])

(deftest aggregate-calls-preserve-generic-layouts-and-caller-owned-pointers
  (let [argument-pointer 4096
        nested-pointer 6144
        destination-pointer 8192
        handlers
        {[:foreign-function
          "jolt_sim_missing_consume_date_descriptor_v9"
          [date-type] :int true false nil]
         (fn [descriptor]
           (is (= [argument-pointer] (:arguments descriptor)))
           101)

         [:foreign-function
          "jolt_sim_missing_consume_nested_descriptor_v9"
          [nested-type] :int false false nil]
         (fn [descriptor]
           (is (= [nested-pointer] (:arguments descriptor)))
           202)

         [:foreign-function
          "jolt_sim_missing_make_date_descriptor_v9"
          [] date-type false false nil]
         (fn [descriptor]
           (is (= [destination-pointer] (:arguments descriptor)))
           destination-pointer)}
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers}
         (fn []
           [(ghost-consume-date argument-pointer)
            (ghost-consume-nested nested-pointer)
            (ghost-make-date destination-pointer)]))]
    (is (= [101 202 destination-pointer] (:result controlled)))
    (is (= 9
           (get-in controlled
                   [:capabilities :ffi-interception :descriptor-version])))
    (is (= [date-type nested-type]
           (mapv #(first (:argument-types %))
                 (take 2 (:effects controlled)))))
    (is (= date-type (:return-type (nth (:effects controlled) 2))))
    (is (= [argument-pointer nested-pointer destination-pointer]
           (mapv #(first (:arguments %)) (:effects controlled))))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))))
