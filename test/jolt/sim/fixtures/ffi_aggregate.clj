(ns jolt.sim.fixtures.ffi-aggregate
  "Ordinary Jolt FFI code used to prove recursive aggregate interception.

  This namespace has no dependency on jolt.sim. Its caller decides whether the
  binding reaches a native symbol or an installed simulation controller."
  (:require [jolt.ffi :as ffi]))

(def date-argument-type
  [:by-value
   [:struct
    [[:date [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]]
     [:zone :int16]]]])

(ffi/defcfn ghost-date
  "definitely_not_a_real_jolt_sim_aggregate_symbol_zzz9"
  [[:by-value
    [:struct
     [[:date [:struct [[:year :int32] [:month :uint8] [:day :uint8]]]]
      [:zone :int16]]]]]
  :int32
  {:blocking true :capture-native-error true})

(defn exercise-aggregate
  "Invokes the ghost binding with a null address. A hermetic handler can model
  it without resolving the symbol or dereferencing caller memory."
  []
  (ghost-date 0))
