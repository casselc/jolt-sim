(ns jolt.example.outbox-regimes-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [jolt.example.outbox.regimes :as regimes]))

(defn- ex-data-of [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- namespaced-keyword? [value]
  (and (keyword? value)
       (string? (namespace value))
       (not (string/blank? (namespace value)))
       (not (string/blank? (name value)))))

(deftest finite-regime-catalog-is-the-exact-cross-product
  (let [descriptors regimes/regimes
        ids (mapv :id descriptors)
        coordinates (mapv :coordinates descriptors)
        expected
        (set
         (for [admission-plan regimes/admission-plans
               poll-eintr-ordinal regimes/poll-eintr-ordinals]
           {:admission-plan admission-plan
            :poll-eintr-ordinal poll-eintr-ordinal}))]
    (is (= 10 (count descriptors)))
    (is (= 10 (count (set ids))))
    (is (= 10 (count (set coordinates))))
    (is (= expected (set coordinates)))
    (doseq [descriptor descriptors]
      (is (nil? (meta descriptor)))
      (is (= #{:id :label :summary :scope :coordinates}
             (set (keys descriptor))))
      (is (namespaced-keyword? (:id descriptor)))
      (is (and (string? (:label descriptor))
               (not (string/blank? (:label descriptor)))))
      (is (and (string? (:summary descriptor))
               (not (string/blank? (:summary descriptor)))))
      (is (and (vector? (:scope descriptor))
               (seq (:scope descriptor))
               (= (count (:scope descriptor))
                  (count (set (:scope descriptor))))
               (every? namespaced-keyword? (:scope descriptor))))
      (is (nil? (meta (:coordinates descriptor))))
      (is (= #{:admission-plan :poll-eintr-ordinal}
             (set (keys (:coordinates descriptor))))))))

(deftest lookup-and-require-regime-fail-closed
  (let [descriptor (first regimes/regimes)
        id (:id descriptor)]
    (is (= descriptor (regimes/regime id)))
    (is (= descriptor (regimes/require-regime id)))
    (is (nil? (regimes/regime :jolt.example.outbox.regime/missing)))
    (is (= {:type :jolt.example.outbox.regimes/unknown-regime
            :regime-id :jolt.example.outbox.regime/missing}
           (ex-data-of
            #(regimes/require-regime
              :jolt.example.outbox.regime/missing))))
    (is (= :jolt.example.outbox.regimes/unknown-regime
           (:type
            (ex-data-of
             #(regimes/scenario-input
               :jolt.example.outbox.regime/missing)))))))

(deftest scenario-inputs-match-the-unchanged-scenario-domain
  (doseq [{:keys [id coordinates]} regimes/regimes]
    (let [input (regimes/scenario-input id)]
      (is (= #{:payload :stream-capacity :pipe-capacity
               :admission-plan :poll-eintr-ordinal}
             (set (keys input))))
      (is (= regimes/lab-base-input
             (select-keys input [:payload :stream-capacity :pipe-capacity])))
      (is (= coordinates
             (select-keys input [:admission-plan :poll-eintr-ordinal])))
      (is (contains? (set regimes/admission-plans) (:admission-plan input)))
      (is (contains? (set regimes/poll-eintr-ordinals)
                     (:poll-eintr-ordinal input))))))

(deftest scenario-input-rejects-unknown-or-invalid-base-data
  (let [id (:id (first regimes/regimes))]
    (doseq [[base reason]
            [[nil :not-a-map]
             [(dissoc regimes/lab-base-input :payload) :unexpected-keys]
             [(assoc regimes/lab-base-input :extra true) :unexpected-keys]
             [(assoc regimes/lab-base-input :payload [256]) :invalid-payload]
             [(assoc regimes/lab-base-input :payload (vec (repeat 33 0)))
              :invalid-payload]
             [(assoc regimes/lab-base-input :stream-capacity 1)
              :invalid-stream-capacity]
             [(assoc regimes/lab-base-input :pipe-capacity 8)
              :invalid-pipe-capacity]]]
      (let [data (ex-data-of #(regimes/scenario-input base id))]
        (is (= :jolt.example.outbox.regimes/invalid-base-input (:type data)))
        (is (= reason (:reason data)))))
    (let [data
          (ex-data-of
           #(regimes/scenario-input
             (with-meta regimes/lab-base-input {:mutable-context true}) id))]
      (is (= :jolt.example.outbox.regimes/invalid-base-input (:type data)))
      (is (= :noncanonical-value (:reason data))))))
