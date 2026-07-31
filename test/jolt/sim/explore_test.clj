(ns jolt.sim.explore-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.explore :as explore]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(deftest single-future-yields-the-identity-plan
  (is (= [[0]] (explore/schedule-plans {:future-count 1 :max-schedules 1}))))

(deftest three-futures-enumerate-all-six-plans-in-lexicographic-order
  ;; The exact canonical lexicographic sequence; any reordering or omission
  ;; here fails.
  (is (= [[0 1 2]
          [0 2 1]
          [1 0 2]
          [1 2 0]
          [2 0 1]
          [2 1 0]]
         (explore/schedule-plans {:future-count 3 :max-schedules 6}))))

(deftest prefixes-respect-the-limit-at-one-and-two
  (is (= [[0 1 2]]
         (explore/schedule-plans {:future-count 3 :max-schedules 1})))
  (is (= [[0 1 2] [0 2 1]]
         (explore/schedule-plans {:future-count 3 :max-schedules 2}))))

(deftest limit-beyond-n-factorial-stops-at-the-last-permutation
  ;; 3! = 6; requesting 100 must still return exactly the six plans, in order,
  ;; with no padding or repetition.
  (is (= 6 (count (explore/schedule-plans {:future-count 3 :max-schedules 100}))))
  (is (= [[0 1 2] [0 2 1] [1 0 2] [1 2 0] [2 0 1] [2 1 0]]
         (explore/schedule-plans {:future-count 3 :max-schedules 100}))))

(deftest large-n-small-limit-does-not-materialize-the-factorial-space
  ;; 20! is ~2.4e18. An eager `(take limit (all-perms 20))` would never return;
  ;; completing this assertion at all discriminates a bounded generator. The
  ;; first two lexicographic permutations of 0..19 are the identity and the
  ;; trailing swap of 18 and 19.
  (let [plans (explore/schedule-plans {:future-count 20 :max-schedules 2})]
    (is (= 2 (count plans)))
    (is (= (vec (range 20)) (first plans)))
    (is (= (vec (concat (range 18) [19 18])) (second plans)))))

(deftest repeated-calls-are-deterministic
  (let [a (explore/schedule-plans {:future-count 4 :max-schedules 9})
        b (explore/schedule-plans {:future-count 4 :max-schedules 9})]
    (is (= a b))
    ;; Sanity: the deterministic 4-future prefixes begin with the identity.
    (is (= [0 1 2 3] (first a)))))

(deftest malformed-configurations-fail-closed-with-typed-evidence
  (testing "config must be a map"
    (let [data (caught-data #(explore/schedule-plans 5))]
      (is (= :jolt.sim.explore/invalid-config (:type data)))
      (is (= :config-not-a-map (:reason data)))
      (is (= 5 (:value data)))))
  (testing "unknown keys are rejected"
    (let [data (caught-data
                #(explore/schedule-plans
                  (array-map :future-count 2
                             :max-schedules 2
                             :surprise 1
                             "alpha" 2)))]
      (is (= :jolt.sim.explore/invalid-config (:type data)))
      (is (= :unknown-key (:reason data)))
      (is (= ["alpha" :surprise] (:keys data)))))
  (testing "missing keys are rejected"
    (let [missing-n (caught-data #(explore/schedule-plans {:max-schedules 2}))
          missing-limit (caught-data
                         #(explore/schedule-plans {:future-count 2}))]
      (is (= :jolt.sim.explore/invalid-config (:type missing-n)))
      (is (= :missing-key (:reason missing-n)))
      (is (= :future-count (:key missing-n)))
      (is (= :jolt.sim.explore/invalid-config (:type missing-limit)))
      (is (= :missing-key (:reason missing-limit)))
      (is (= :max-schedules (:key missing-limit)))))
  (testing "values must be positive integers"
    (let [zero-n (caught-data
                  #(explore/schedule-plans {:future-count 0 :max-schedules 2}))
          negative-limit (caught-data
                          #(explore/schedule-plans
                            {:future-count 2 :max-schedules -1}))
          non-integer-n (caught-data
                         #(explore/schedule-plans
                           {:future-count 3.0 :max-schedules 2}))
          non-integer-limit (caught-data
                             #(explore/schedule-plans
                              {:future-count 2 :max-schedules "two"}))]
      (is (= :not-a-positive-integer (:reason zero-n)))
      (is (= 0 (get-in zero-n [:value])))
      (is (= :future-count (:key zero-n)))
      (is (= :not-a-positive-integer (:reason negative-limit)))
      (is (= :max-schedules (:key negative-limit)))
      (is (= :not-a-positive-integer (:reason non-integer-n)))
      (is (= 3.0 (:value non-integer-n)))
      (is (= :not-a-positive-integer (:reason non-integer-limit)))
      (is (= "two" (:value non-integer-limit))))))

(deftest present-sentinel-shaped-values-are-not-reported-as-missing
  (let [sentinel-shaped :jolt.sim.explore/absent
        bad-n
        (caught-data
         #(explore/schedule-plans
           {:future-count sentinel-shaped :max-schedules 1}))
        bad-limit
        (caught-data
         #(explore/schedule-plans
           {:future-count 1 :max-schedules sentinel-shaped}))]
    (doseq [[data key] [[bad-n :future-count]
                        [bad-limit :max-schedules]]]
      (is (= :jolt.sim.explore/invalid-config (:type data)))
      (is (= :not-a-positive-integer (:reason data)))
      (is (= key (:key data)))
      (is (= sentinel-shaped (:value data))))))

(deftest empty-config-map-reports-the-first-missing-key
  (let [data (caught-data #(explore/schedule-plans {}))]
    (is (= :jolt.sim.explore/invalid-config (:type data)))
    (is (= :missing-key (:reason data)))
    (is (= :future-count (:key data)))))
