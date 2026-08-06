(ns jolt.sim.schema-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.schema :as schema]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest closed-inspectable-schema-validates-without-coercion
  (let [form [:map {:closed true}
              [:request-id :string]
              [:mode {:optional true} [:enum :fast :careful]]
              [:payload [:vector {:max 4} [:int {:min 0 :max 255}]]]]
        compiled (schema/compile! {:pack-id :example/request-v1
                                   :field :config}
                                  form)
        value {:request-id "r-1" :mode :careful :payload [0 127 255]}]
    (is (= value (schema/validate! compiled value
                                   {:pack-id :example/request-v1
                                    :field :config})))
    (is (= value (schema/validate-form! {:field :config} form value)))))

(deftest invalid-values-report-only-stable-coordinates
  (let [secret "do-not-retain-this-value"
        secret-key "do-not-retain-this-key"
        compiled (schema/compile! {:pack-id :example/request-v1
                                   :field :config}
                                  [:map {:closed true} [:count :int]])
        wrong-type (ex-data-of #(schema/validate!
                                compiled {:count secret}
                                {:pack-id :example/request-v1
                                 :field :config}))
        unknown-key (ex-data-of #(schema/validate!
                                 compiled {:count 1 secret-key 1}
                                 {:pack-id :example/request-v1
                                  :field :config}))
        map-of (schema/compile! {:field :config}
                                [:map-of :int :string])
        invalid-map-key (ex-data-of #(schema/validate!
                                     map-of {secret-key "value"}
                                     {:pack-id :example/request-v1
                                      :field :config}))]
    (doseq [data [wrong-type unknown-key invalid-map-key]]
      (is (= :jolt.sim.schema/invalid-value (:type data)))
      (is (= :example/request-v1 (:pack-id data)))
      (is (= :config (:field data)))
      (is (vector? (:schema-path data)))
      (is (vector? (:value-path data)))
      (is (not (contains? data :value)))
      (is (not (.contains (pr-str data) secret)))
      (is (not (.contains (pr-str data) secret-key))))))

(deftest declared-fields-remain-distinguishable-after-redaction
  (let [compiled (schema/compile! {:field :config}
                                  [:map {:closed true}
                                   [:count :int]
                                   [:label :string]])
        count-data (ex-data-of #(schema/validate! compiled
                                                  {:count "one" :label "ok"}
                                                  {:field :config}))
        label-data (ex-data-of #(schema/validate! compiled
                                                  {:count 1 :label 2}
                                                  {:field :config}))]
    (is (some #{:count} (:schema-path count-data)))
    (is (some #{:count} (:value-path count-data)))
    (is (some #{:label} (:schema-path label-data)))
    (is (some #{:label} (:value-path label-data)))
    (is (not= (:schema-path count-data) (:schema-path label-data)))
    (is (not= (:value-path count-data) (:value-path label-data)))))

(deftest unsupported-or-ambient-schema-features-fail-closed
  (doseq [form [[:map [:x :int]]
                [:cat :int :string]
                [:string {:workbench/unknown true}]
                int?]]
    (let [data (ex-data-of #(schema/compile! {:field :config} form))]
      (is (contains? #{:jolt.sim.schema/unsupported-schema
                       :jolt.sim.schema/invalid-schema}
                     (:type data))
          (pr-str form))
      (is (vector? (:schema-path data)) (pr-str form)))))

(deftest malformed-bounds-fail-during-schema-compilation
  (doseq [form [[:vector {:min "secret"} :int]
                [:string {:min -1}]
                [:set {:min 3 :max 2} :int]
                [:int {:min 2 :max 1}]
                [:int {:min 1.5}]
                [:double {:min ##NaN}]]]
    (let [data (ex-data-of #(schema/compile! {:field :config} form))]
      (is (= :jolt.sim.schema/unsupported-schema (:type data))
          (pr-str form))
      (is (not (.contains (pr-str data) "secret")) (pr-str form)))))

(deftest malformed-schema-and-compiled-values-are-rejected
  (is (= :jolt.sim.schema/invalid-schema
         (:type (ex-data-of #(schema/compile! {:field :config}
                                              [:map :not-properties])))))
  (is (= :jolt.sim.schema/invalid-context
         (:type (ex-data-of #(schema/compile! :not-a-map :int)))))
  (let [secret "do-not-retain-context"
        data (ex-data-of #(schema/compile! {:field :config :secret secret}
                                           :int))]
    (is (= :jolt.sim.schema/invalid-context (:type data)))
    (is (not (.contains (pr-str data) secret))))
  (is (= :jolt.sim.schema/not-compiled
         (:type (ex-data-of #(schema/validate! {:form :int} 1
                                               {:field :config}))))))
