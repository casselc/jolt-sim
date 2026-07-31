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

(defn- ex-data-of [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- hybrid-loan-handlers [world include-release?]
  (let [handlers (memory/handlers world)
        borrow-key [:native-operation :borrow-byte-array]
        release-key [:native-operation :release-byte-array]
        write-key [:native-operation :write]
        read-key [:native-operation :read]
        substitute-handler
        (fn [key]
          (fn [descriptor]
            (runtime/substitute ((get handlers key) descriptor))))]
    (cond->
     {borrow-key
      (fn [descriptor]
        ;; Span is inferred from [array offset length] in the descriptor.
        (runtime/modeled-resource
         ((get handlers borrow-key) descriptor)))
      write-key (substitute-handler write-key)
      read-key (substitute-handler read-key)}
      include-release?
      (assoc release-key (substitute-handler release-key)))))

(deftest ordinary-pointer-loan-fixture-reaches-real-native-memory
  (is (= successful-result (fixture/exercise-loan)))
  (is (= throwing-result (fixture/exercise-throwing-loan))))

(deftest unchanged-pointer-loan-fixture-runs-against-simulated-memory
  (is (true? (runtime/available?)))
  (when (runtime/available?)
    (is (= 5 (:abi-version (runtime/capabilities))))
    (is (= 4
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

(deftest unchanged-pointer-loan-fixture-can-observe-real-native-memory
  ;; Current routing lets the same ordinary body proceed through the exact real
  ;; borrow/callback/release path while jolt-sim records every native route.
  ;; Core Jolt, not a handler pack, remains responsible for unlock ownership.
  (is (= 5 (:abi-version (runtime/capabilities))))
  (let [controlled
        (runtime/run-controlled {:ffi-mode :observe} fixture/exercise-loan)]
    (is (= successful-result (:result controlled)))
    (is (= [:borrow-byte-array :write :read :release-byte-array]
           (operations controlled)))
    (is (every? #(= :native (:route %)) (:effect-trace controlled))))
  (let [controlled
        (runtime/run-controlled
         {:ffi-mode :observe}
         fixture/exercise-throwing-loan)]
    (is (= throwing-result (:result controlled)))
    (is (= [:borrow-byte-array :write :release-byte-array]
           (operations controlled)))
    (is (every? #(= :native (:route %)) (:effect-trace controlled)))))

(deftest unchanged-pointer-loan-fixture-runs-through-hybrid-model-handlers
  (let [world (memory/world)
        controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (hybrid-loan-handlers world true)}
         fixture/exercise-loan)]
    (is (= successful-result (:result controlled)))
    (is (= [:borrow-byte-array :write :read :release-byte-array]
           (operations controlled)))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (is (true? (memory/clean? world)))
    (is (empty? (memory/leaks world))))
  (let [world (memory/world)
        controlled
        (runtime/run-controlled
         {:ffi-mode :hybrid
          :ffi-handlers (hybrid-loan-handlers world true)}
         fixture/exercise-throwing-loan)]
    (is (= throwing-result (:result controlled)))
    (is (= [:borrow-byte-array :write :release-byte-array]
           (operations controlled)))
    (is (every? #(= :handler (:route %)) (:effect-trace controlled)))
    (is (true? (memory/clean? world)))
    (is (empty? (memory/leaks world)))))

(deftest hybrid-modeled-loan-without-release-handler-fails-closed
  ;; The ordinary fixture catches the immediate cleanup error. The routing
  ;; policy latch must still reject the enclosing run, and the modeled pointer
  ;; must never reach the runtime-owned real release continuation.
  (let [world (memory/world)
        data
        (ex-data-of
         #(runtime/run-controlled
           {:ffi-mode :hybrid
            :ffi-handlers (hybrid-loan-handlers world false)}
           fixture/exercise-throwing-loan))]
    (is (= :jolt.sim.runtime/controller-error (:type data)))
    (is (= [:borrow-byte-array :write :release-byte-array]
           (mapv :operation (:effects data))))
    (is (= [:handler :handler :blocked]
           (mapv :route (:effect-trace data))))
    (is (= :modeled-resource-native-fallback
           (:reason (last (:effect-trace data)))))
    (is (some #(= :modeled-resource-native-fallback (:ffi-error %))
              (:errors data)))))
