(ns jolt.sim.experiment-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.sim.experiment :as experiment]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.pack-registry :as packs]
            [jolt.sim.runtime :as runtime]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(def ^:private client-cap :example.http/client-v1)
(def ^:private server-cap :example.http/server-v1)

(defn- binding-fields [pack-id operation mode overrides]
  (merge
   {:consumes {:from #{client-cap} :to #{server-cap}}
    :handler-packs
    (if (= :simulate mode)
      [(hp/pack pack-id {(hp/native-operation-key operation)
                         (fn [_] :handled)})]
      [])
    :clock nil
    :mechanism-probe (fn [_] {:traversed? true})
    :history-projector (fn [trace] trace)
    :monitor-specs []
    :presentation-registry {}
    :native-fallback #{}}
   overrides))

(defn- connection-pack
  ([id operation calls]
   (connection-pack id operation calls (fn [_] {})))
  ([id operation calls overrides-for]
   (packs/connection-pack
    {:id id
     :doc "test connection"
     :capabilities {:from #{client-cap} :to #{server-cap}}
     :config-schema [:map {:closed true}
                     [:contract [:= :example/contract-v1]]]
     :template {:contract :example/contract-v1}
     :modes {:simulate {:params-schema [:map {:closed true}]}
             :record {:params-schema [:map {:closed true}]}
             :pass-through {:params-schema [:map {:closed true}]}
             :hybrid {:params-schema [:map {:closed true}]}}
     :faults {}
     :compile
     (fn [request]
       (swap! calls conj request)
       (packs/connection-binding
        (:id request) (:mode request)
        (binding-fields id operation (:mode request)
                        (overrides-for request))))})))

(defn- check-pack [calls]
  (packs/check-pack
   {:id :example/check-v1
    :doc "test check"
    :config-schema [:map {:closed true}]
    :template {}
    :observations []
    :check (fn [request]
             (swap! calls conj request)
             (packs/check-binding
              (:id request)
              {:monitor-specs [] :presentation-registry {}}))}))

(defn- registry-and-calls
  ([] (registry-and-calls (fn [_] {})))
  ([overrides-for]
   (let [connection-calls (atom [])
         check-calls (atom [])]
     {:registry
      (packs/registry
       [(connection-pack :example/http-v1 :alloc connection-calls
                         overrides-for)
        (check-pack check-calls)])
      :connection-calls connection-calls
      :check-calls check-calls})))

(defn- base-manifest []
  {:jolt.sim.experiment/version 1
   :id :example/outbox-v1
   :nodes
   {:client {:ports {:http {:direction :out
                            :capabilities #{client-cap}}}}
    :app {:ports {:http {:direction :in
                         :capabilities #{server-cap}}}}}
   :connections
   {:command {:from [:client :http]
              :to [:app :http]
              :pack :example/http-v1
              :config {:contract :example/contract-v1}}}
   :profiles
   {:hermetic {:connections {:command {:mode :simulate :params {}}}}
    :native {:connections {:command {:mode :record :params {}}}}}
   :checks [{:pack :example/check-v1 :config {}}]})

(deftest compiles-one-immutable-hermetic-plan
  (let [{:keys [registry connection-calls check-calls]} (registry-and-calls)
        manifest (base-manifest)
        plan (experiment/compile-plan registry manifest :hermetic)
        data (experiment/plan-data plan)]
    (is (= :example/outbox-v1 (:experiment-id data)))
    (is (= :hermetic (:profile-id data)))
    (is (= :hermetic (get-in data [:runtime-config :ffi-mode])))
    (is (= 1 (count (get-in data [:runtime-config :ffi-handlers]))))
    (is (= #{:command} (set (keys (:mechanism-probes data)))))
    (is (= #{:command} (set (keys (:history-projectors data)))))
    (is (fn? (get-in data [:mechanism-probes :command])))
    (is (= 1 (count @connection-calls)))
    (is (= 1 (count @check-calls)))
    (is (= manifest (:manifest data)))
    (is (not-any? #(contains? data %)
                  [:seed :schedule :choices :runnable :search-state]))
    (is (= plan (experiment/validate-plan! plan)))))

(deftest native-recording-plan-observes-without-simulated-handlers
  (let [{:keys [registry]} (registry-and-calls)
        plan (experiment/compile-plan registry (base-manifest) :native)
        data (experiment/plan-data plan)]
    (is (= :observe (get-in data [:runtime-config :ffi-mode])))
    (is (not (contains? (:runtime-config data) :ffi-handlers)))
    (is (= :record (get-in data [:connections :command :mode])))
    (is (= 42 (:result (runtime/run-controlled (:runtime-config data)
                                               (fn [] 42)))))))

(deftest manifest-maps-are-closed-at-every-level
  (let [base (base-manifest)
        invalid
        [(assoc base :extra true)
         (assoc-in base [:nodes :client :extra] true)
         (assoc-in base [:nodes :client :ports :http :extra] true)
         (assoc-in base [:connections :command :extra] true)
         (assoc-in base [:profiles :hermetic :extra] true)
         (assoc-in base [:profiles :hermetic :connections :command :extra]
                   true)
         (update base :checks #(assoc-in % [0 :extra] true))]]
    (doseq [manifest invalid]
      (is (= :jolt.sim.experiment/invalid-manifest
             (:type (ex-data-of #(experiment/validate-manifest! manifest))))))))

(deftest manifest-key-errors-redact-unknown-submitted-keys
  (let [secret-key (keyword "credential" "do-not-retain")
        data (ex-data-of #(experiment/validate-manifest!
                          (assoc (base-manifest) secret-key true)))
        nested-data
        (ex-data-of
         #(experiment/validate-manifest!
           (assoc-in (base-manifest)
                     [:profiles :hermetic :connections secret-key]
                     {:mode :simulate :params {}})))]
    (is (= :jolt.sim.experiment/invalid-manifest (:type data)))
    (is (= 1 (:unknown-key-count data)))
    (is (not (.contains (pr-str data) (name secret-key))))
    (is (= :jolt.sim.experiment/invalid-manifest (:type nested-data)))
    (is (= 1 (:unknown-key-count nested-data)))
    (is (not (.contains (pr-str nested-data) (name secret-key))))))

(deftest manifest-validation-rejects-executable-or-lazy-data
  (let [realized? (atom false)
        deferred (lazy-seq (reset! realized? true) (list :fault))
        with-function (assoc-in (base-manifest)
                                [:connections :command :config :callback]
                                (fn [_] :boom))
        with-lazy (assoc-in (base-manifest)
                            [:profiles :hermetic :connections :command :params]
                            {:faults deferred})]
    (doseq [manifest [with-function with-lazy]]
      (is (= :jolt.sim.pack-registry/non-data-value
             (:type (ex-data-of #(experiment/validate-manifest! manifest))))))
    (is (false? @realized?))))

(deftest manifest-endpoints-and-profile-coverage-fail-closed
  (let [base (base-manifest)
        unknown-port (assoc-in base [:connections :command :to]
                               [:app :missing])
        wrong-direction (assoc-in base [:connections :command :from]
                                  [:app :http])
        missing-selection (assoc-in base [:profiles :hermetic :connections]
                                    {})]
    (doseq [manifest [unknown-port wrong-direction missing-selection]]
      (is (= :jolt.sim.experiment/invalid-manifest
             (:type (ex-data-of #(experiment/validate-manifest! manifest))))))))

(deftest selected-pack-schemas-run-before-pack-compilers
  (let [{:keys [registry connection-calls]} (registry-and-calls)
        invalid (assoc-in (base-manifest)
                          [:connections :command :config :contract]
                          :wrong/contract)
        data (ex-data-of #(experiment/compile-plan registry invalid :hermetic))]
    (is (= :jolt.sim.schema/invalid-value (:type data)))
    (is (empty? @connection-calls))))

(deftest capability-mismatches-and-missing-interception-fail
  (let [{mismatch-registry :registry mismatch-calls :connection-calls}
        (registry-and-calls)
        incompatible-manifest
        (assoc-in (base-manifest)
                  [:nodes :app :ports :http :capabilities]
                  #{:other/server-v1})
        mismatch (ex-data-of #(experiment/compile-plan mismatch-registry
                                                      incompatible-manifest
                                                      :hermetic))
        {missing-registry :registry}
        (registry-and-calls
         (fn [_] {:handler-packs [] :clock nil}))
        missing (ex-data-of #(experiment/compile-plan missing-registry
                                                     (base-manifest)
                                                     :hermetic))]
    (is (= :jolt.sim.experiment/capability-mismatch (:type mismatch)))
    (is (empty? @mismatch-calls))
    (is (= :jolt.sim.experiment/missing-interception (:type missing)))))

(deftest unsupported-profile-modes-fail-before-execution
  (let [{:keys [registry connection-calls]} (registry-and-calls)
        hybrid (assoc-in (base-manifest)
                         [:profiles :hermetic :connections :command :mode]
                         :hybrid)
        data (ex-data-of #(experiment/compile-plan registry hybrid :hermetic))]
    (is (= :jolt.sim.experiment/unsupported-mode (:type data)))
    (is (= :hybrid (:mode data)))
    (is (empty? @connection-calls))))

(deftest binding-envelope-and-plan-tampering-fail-closed
  (let [{bad-registry :registry}
        (registry-and-calls (fn [_] {:mechanism-probe nil}))
        error-data (ex-data-of #(experiment/compile-plan bad-registry
                                                        (base-manifest)
                                                        :hermetic))
        {registry :registry} (registry-and-calls)
        plan (experiment/compile-plan registry (base-manifest) :hermetic)
        data (experiment/plan-data plan)]
    (is (= :jolt.sim.experiment/invalid-binding (:type error-data)))
    (is (= :jolt.sim.experiment/invalid-plan
           (:type (ex-data-of #(experiment/validate-plan!
                               (assoc data :extra true))))))))

(deftest nested-plan-tampering-is-reconstructed-and-rejected
  (let [{registry :registry} (registry-and-calls)
        plan (experiment/compile-plan registry (base-manifest) :hermetic)
        data (experiment/plan-data plan)
        tampered
        [(assoc-in data [:runtime-config :ffi-handlers] {})
         (assoc-in data [:mechanism-probes :command] (fn [_] :forged))
         (assoc-in data [:connections :command :binding :fields
                         :history-projector]
                   (fn [_] :forged))
         (assoc-in data [:manifest :profiles :hermetic :connections
                         :command :mode]
                   :record)
         ;; Even coordinated copies cannot become the opaque executable type.
         (let [forged (fn [_] :forged)]
           (-> data
               (assoc-in [:connections :command :binding :fields
                          :mechanism-probe] forged)
               (assoc-in [:mechanism-probes :command] forged)))]]
    (doseq [value tampered]
      (is (some? (ex-data-of #(experiment/validate-plan! value)))))))

(deftest connection-derived-artifacts-follow-sorted-id-order-past-array-maps
  (let [operations [:load-library :loaded? :alloc :free :read :write
                    :sizeof :null? :read-bytes]
        ids (mapv #(keyword "example" (str "connection-" % "-v1"))
                  (range 9))
        connection-ids (mapv #(keyword (str "c" %)) (range 9))
        port-ids (mapv #(keyword (str "p" %)) (range 9))
        calls (atom [])
        monitor-spec
        (fn [id]
          {:id id :initial nil
           :step (fn [state _ _] {:state state})
           :finish (fn [_] {:status :pass})})
        pack-values
        (mapv (fn [id operation connection-id]
                (connection-pack
                 id operation calls
                 (fn [_] {:monitor-specs [(monitor-spec connection-id)]})))
              ids operations connection-ids)
        registry (packs/registry pack-values)
        nodes
        {:client {:ports (into {}
                               (map (fn [port-id]
                                      [port-id {:direction :out
                                                :capabilities #{client-cap}}])
                                    port-ids))}
         :app {:ports (into {}
                            (map (fn [port-id]
                                   [port-id {:direction :in
                                             :capabilities #{server-cap}}])
                                 port-ids))}}
        connections
        (into {}
              (map (fn [connection-id port-id pack-id]
                     [connection-id
                      {:from [:client port-id] :to [:app port-id]
                       :pack pack-id
                       :config {:contract :example/contract-v1}}])
                   (reverse connection-ids) (reverse port-ids) (reverse ids)))
        selections
        (into {} (map (fn [id] [id {:mode :simulate :params {}}])
                      connection-ids))
        manifest {:jolt.sim.experiment/version 1
                  :id :example/many-v1
                  :nodes nodes
                  :connections connections
                  :profiles {:hermetic {:connections selections}}
                  :checks []}
        plan (experiment/compile-plan registry manifest :hermetic)
        data (experiment/plan-data plan)]
    (is (= (sort-by pr-str connection-ids)
           (mapv :id (:monitor-specs data))))
    (is (= 9 (count (get-in data [:runtime-config :ffi-handlers]))))))

(deftest unknown-profile-and-pack-are-distinct-errors
  (let [{:keys [registry]} (registry-and-calls)
        unknown-pack (assoc-in (base-manifest)
                               [:connections :command :pack]
                               :missing/connection-v1)]
    (is (= :jolt.sim.experiment/unknown-profile
           (:type (ex-data-of #(experiment/compile-plan
                               registry (base-manifest) :missing)))))
    (is (= :jolt.sim.pack-registry/unknown-pack
           (:type (ex-data-of #(experiment/compile-plan
                               registry unknown-pack :hermetic)))))))
