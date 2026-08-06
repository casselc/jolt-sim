(ns jolt.sim.pack-registry-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [jolt.sim.pack-registry :as packs]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(def ^:private test-capabilities
  {:from #{:test/from-v1} :to #{:test/to-v1}})

;; ---- fixtures -------------------------------------------------------------

(def ^:private http-connection
  (packs/connection-pack
   {:id :http/form-session-v1
    :doc "HTTP form session connection"
    :capabilities test-capabilities
    :config-schema [:map {:closed true} [:csrf-required :boolean]]
    :template {:csrf-required true}
    :modes {:simulate {:params-schema [:map {:closed true}
                                        [:latency-ms :int]]}
            :record {:params-schema [:map {:closed true}]}}
    :faults {:catalog #{:drop-ack :reset :half-close}}
    :compile (fn [request]
               (packs/connection-binding
                (:id request) (:mode request)
                {:received-config (:config request)
                 :received-params (:params request)
                 ;; Binding fields are opaque to the registry and may carry
                 ;; trusted executable values such as projectors.
                 :projector identity}))}))

(def ^:private atomic-check
  (packs/check-pack
   {:id :example.outbox/command-atomic-v1
    :doc "command atomicity"
    :config-schema [:map {:closed true}
                    [:when {:optional true} [:enum :always]]]
    :template {}
    :observations [:connection/history]
    :check (fn [request]
             (packs/check-binding (:id request)
                                  {:received-config (:config request)
                                   :run (fn [_] :pass)}))}))

(def ^:private test-registry
  (packs/registry [http-connection] [atomic-check] nil))

(defn- conn-pack-with
  "Builds a valid connection pack whose compiler is compile-fn."
  [compile-fn]
  (packs/connection-pack
   {:id :http/form-session-v1
    :doc "d"
    :capabilities test-capabilities
    :config-schema [:map {:closed true}]
    :template {}
    :modes {:simulate {:params-schema [:map {:closed true}]}}
    :faults {}
    :compile compile-fn}))

;; ---- valid discovery ------------------------------------------------------

(deftest describe-returns-data-only-connection-projection
  (is (= {:kind :connection
          :id :http/form-session-v1
          :doc "HTTP form session connection"
          :capabilities test-capabilities
          :config-schema [:map {:closed true} [:csrf-required :boolean]]
          :template {:csrf-required true}
          :modes {:simulate {:params-schema [:map {:closed true}
                                            [:latency-ms :int]]}
                  :record {:params-schema [:map {:closed true}]}}
          :faults {:catalog #{:drop-ack :reset :half-close}}}
         (packs/describe test-registry :http/form-session-v1)))
  (is (not (contains? (packs/describe test-registry :http/form-session-v1)
                      :compile))))

(deftest describe-returns-data-only-check-projection
  (is (= {:kind :check
          :id :example.outbox/command-atomic-v1
          :doc "command atomicity"
          :config-schema [:map {:closed true}
                          [:when {:optional true} [:enum :always]]]
          :template {}
          :observations [:connection/history]}
         (packs/describe test-registry :example.outbox/command-atomic-v1))))

(deftest template-modes-and-faults-return-data-only-values
  (is (= {:csrf-required true}
         (packs/template test-registry :http/form-session-v1)))
  (is (= {:simulate {:params-schema [:map {:closed true}
                                        [:latency-ms :int]]}
          :record {:params-schema [:map {:closed true}]}}
         (packs/modes test-registry :http/form-session-v1)))
  (is (= {:catalog #{:drop-ack :reset :half-close}}
         (packs/faults test-registry :http/form-session-v1)))
  (is (= {} (packs/template test-registry :example.outbox/command-atomic-v1))))

(deftest resolve-pack-returns-the-trusted-executable-pack
  (let [pack (packs/resolve-pack test-registry :http/form-session-v1)]
    (is (fn? (:compile pack)))
    (is (= :jolt.sim.pack-registry/connection-pack
           (:jolt.sim.pack-registry/type pack))))
  (is (fn? (:check (packs/resolve-pack test-registry
                                       :example.outbox/command-atomic-v1)))))

;; ---- descriptor validation --------------------------------------------------

(deftest descriptor-keys-must-be-exact
  (let [base {:id :http/form-session-v1
              :doc "d"
              :capabilities test-capabilities
              :config-schema [:map {:closed true}]
              :template {}
              :modes {:simulate {:params-schema [:map {:closed true}]}}
              :faults {}
              :compile (fn [_] :unused)}]
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of #(packs/connection-pack (dissoc base :compile))))))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of #(packs/connection-pack (assoc base :bogus 1))))))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of #(packs/connection-pack :not-a-map)))))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of
                   #(packs/connection-pack
                     (assoc base :jolt.sim.pack-registry/type
                            :jolt.sim.pack-registry/connection-pack))))))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of #(packs/connection-pack (assoc base :doc 42))))))))

(deftest pack-ids-must-be-versioned-namespaced-keywords
  (doseq [bad [:plain
               "http/form-session-v1"
               nil
               :http/form-session
               :http/form-session-v0
               :http/-v1]]
    (is (= :jolt.sim.pack-registry/invalid-pack-id
           (:type (ex-data-of #(packs/connection-pack
                                {:id bad
                                 :doc "d"
                                 :capabilities test-capabilities
                                 :config-schema [:map {:closed true}]
                                 :template {}
                                 :modes {:simulate {:params-schema [:map {:closed true}]}}
                                 :faults {}
                                 :compile (fn [_] :unused)}))))
        (pr-str bad)))
  (is (some? (packs/connection-pack
              {:id :a/b-v22
               :doc "d"
               :capabilities test-capabilities
               :config-schema [:map {:closed true}]
               :template {}
               :modes {:record {:params-schema [:map {:closed true}]}}
               :faults {}
               :compile (fn [_] :unused)}))))

(deftest discoverable-data-rejects-functions-recursively
  (let [base {:id :http/form-session-v1
              :doc "d"
              :capabilities test-capabilities
              :config-schema [:map {:closed true}]
              :template {}
              :modes {:simulate {:params-schema [:map {:closed true}]}}
              :faults {}
              :compile (fn [_] :unused)}
        cases [{:field :config-schema
                :descriptor (assoc base :config-schema
                                   [:map [:cb [:fn (fn [x] x)]]])}
               {:field :template
                :descriptor (assoc base :template (fn [_] 1))}
               {:field :modes
                :descriptor (assoc base :modes
                                   {:simulate
                                    {:params-schema {:retry (fn [_] true)}}})}
               {:field :faults
                :descriptor (assoc base :faults
                                   {:catalog {:reset {:activate (fn [_])}}})}
               {:field :faults
                :descriptor (assoc base :faults #{(fn [_] :not-data)})}
               ;; A function used as a map key is still executable data.
               {:field :faults
                :descriptor (assoc base :faults {(fn [_] 1) :oops})}]]
    (doseq [{:keys [field descriptor]} cases]
      (let [data (ex-data-of #(packs/connection-pack descriptor))]
        (is (= :jolt.sim.pack-registry/invalid-descriptor (:type data))
            (pr-str field))
        (is (= field (:field data)) (pr-str field))
        (is (vector? (:path data)) (pr-str field))
        (is (not-any? fn? (:path data)) (pr-str field))))))

(deftest discoverable-data-never-realizes-sequences-or-retains-metadata
  (let [realized? (atom false)
        deferred (lazy-seq (reset! realized? true) (list :value))
        with-callback-metadata (with-meta {} {:callback (fn [_] :hidden)})
        base {:id :http/form-session-v1
              :doc "d"
              :capabilities test-capabilities
              :config-schema [:map {:closed true}]
              :template {}
              :modes {:simulate {:params-schema [:map {:closed true}]}}
              :faults {}
              :compile (fn [_] :unused)}
        lazy-data (ex-data-of
                   #(packs/connection-pack (assoc base :faults deferred)))
        metadata-data (ex-data-of
                       #(packs/connection-pack
                         (assoc base :template with-callback-metadata)))]
    (is (false? @realized?))
    (is (= :jolt.sim.pack-registry/invalid-descriptor (:type lazy-data)))
    (is (= [] (:path lazy-data)))
    (is (= :jolt.sim.pack-registry/invalid-descriptor (:type metadata-data)))
    (is (= [:jolt.sim.pack-registry/metadata] (:path metadata-data)))
    (is (not-any? fn? (:path metadata-data)))))

(deftest descriptors-and-mode-maps-reject-container-metadata
  (let [base {:id :http/form-session-v1
              :doc "d"
              :capabilities test-capabilities
              :config-schema [:map {:closed true}]
              :template {}
              :modes {:simulate {:params-schema [:map {:closed true}]}}
              :faults {}
              :compile (fn [_] :unused)}
        descriptor-data
        (ex-data-of
         #(packs/connection-pack
           (with-meta base {:callback (fn [_] :hidden)})))
        modes-data
        (ex-data-of
         #(packs/connection-pack
           (assoc base :modes
                  (with-meta (:modes base)
                    {:callback (fn [_] :hidden)}))))]
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type descriptor-data)))
    (is (= :jolt.sim.pack-registry/descriptor
           (:field descriptor-data)))
    (is (= [:jolt.sim.pack-registry/metadata]
           (:path descriptor-data)))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type modes-data)))
    (is (= :modes (:field modes-data)))
    (is (= [:jolt.sim.pack-registry/metadata]
           (:path modes-data)))
    (is (not-any? fn? (:path descriptor-data)))
    (is (not-any? fn? (:path modes-data)))))

(deftest symbols-remain-inert-discoverable-identifiers
  (let [schema [:map {:closed true} [:entry [:= 'example.outbox/run]]]
        pack (packs/connection-pack
              {:id :example/entry-v1
               :doc "symbol identifiers are data, never resolved here"
               :capabilities test-capabilities
               :config-schema schema
               :template {:entry 'example.outbox/run}
               :modes {:record {:params-schema [:map {:closed true}]}}
               :faults {}
               :compile (fn [request]
                          (packs/connection-binding
                           (:id request) (:mode request) {}))})
        registry (packs/registry [pack])]
    (is (= schema (:config-schema (packs/describe registry :example/entry-v1))))
    (is (= {:entry 'example.outbox/run}
           (packs/template registry :example/entry-v1)))))

(deftest check-descriptors-reject-functions-in-observations
  (let [data (ex-data-of
              #(packs/check-pack
                {:id :example/check-v1
                 :doc "d"
                 :config-schema [:map {:closed true}]
                 :template {}
                 :observations [:ok/history (fn [_] :not-data)]
                 :check (fn [_] :unused)}))]
    (is (= :jolt.sim.pack-registry/invalid-descriptor (:type data)))
    (is (= :observations (:field data)))
    (is (= [1] (:path data)))))

(deftest mode-names-belong-to-the-core-vocabulary
  (let [data (ex-data-of
              #(conn-pack-with (fn [_] :unused)))]
    (is (nil? data)))
  (let [bad (ex-data-of
             #(packs/connection-pack
               {:id :http/form-session-v1
                :doc "d"
                :capabilities test-capabilities
                :config-schema [:map {:closed true}]
                :template {}
                :modes {:shadow {:params-schema [:map {:closed true}]}}
                :faults {}
                :compile (fn [_] :unused)}))]
    (is (= :jolt.sim.pack-registry/invalid-descriptor (:type bad)))
    (is (= :modes (:field bad)))
    (is (= :shadow (:mode bad)))))

(deftest connection-capabilities-are-declarative-and-closed
  (doseq [bad [nil
               {}
               {:from #{} :to #{:test/to-v1}}
               {:from #{:not-namespaced} :to #{:test/to-v1}}
               {:from #{:test/from-v1} :to #{:test/to-v1} :extra #{}}]]
    (let [data
          (ex-data-of
           #(packs/connection-pack
             {:id :example/capabilities-v1
              :doc "d"
              :capabilities bad
              :config-schema [:map {:closed true}]
              :template {}
              :modes {:simulate {:params-schema [:map {:closed true}]}}
              :faults {}
              :compile (fn [_] :unused)}))]
      (is (= :jolt.sim.pack-registry/invalid-descriptor (:type data)))
      (is (= :capabilities (:field data)))))
  (is (= test-capabilities
         (:capabilities (packs/describe test-registry
                                        :http/form-session-v1)))))

;; ---- registry composition ---------------------------------------------------

(deftest registry-rejects-duplicate-identities
  (let [other (packs/connection-pack
               {:id :http/form-session-v1
                :doc "different"
                :capabilities test-capabilities
                :config-schema [:map {:closed true}]
                :template {}
                :modes {:record {:params-schema [:map {:closed true}]}}
                :faults {}
                :compile (fn [_] :other)})]
    (let [data (ex-data-of #(packs/registry [http-connection] [other]))]
      (is (= :jolt.sim.pack-registry/duplicate-pack (:type data)))
      (is (= :connection (:kind data)))
      (is (= :http/form-session-v1 (:pack-id data))))
    ;; Even the identical value twice is ambiguous ownership, never last-wins.
    (let [data (ex-data-of #(packs/registry [http-connection http-connection]))]
      (is (= :jolt.sim.pack-registry/duplicate-pack (:type data))))))

(deftest registry-sources-are-nil-or-pack-collections
  (is (some? (packs/registry)))
  (is (some? (packs/registry nil nil)))
  (is (= :jolt.sim.pack-registry/invalid-registry-source
         (:type (ex-data-of #(packs/registry http-connection)))))
  (is (= :jolt.sim.pack-registry/not-a-pack
         (:type (ex-data-of #(packs/registry [:not/a-pack-v1])))))
  (is (= :jolt.sim.pack-registry/not-a-pack
         (:type (ex-data-of #(packs/registry [{:id :not/a-pack-v1}]))))))

(deftest registry-rejects-invalid-schemas-and-templates
  (let [invalid-schema
        (packs/connection-pack
         {:id :example/invalid-schema-v1
          :doc "d"
          :capabilities test-capabilities
          :config-schema [:map [:open :int]]
          :template {:open 1}
          :modes {:simulate {:params-schema [:map {:closed true}]}}
          :faults {}
          :compile (fn [_] :unused)})
        invalid-template
        (packs/connection-pack
         {:id :example/invalid-template-v1
          :doc "d"
          :capabilities test-capabilities
          :config-schema [:map {:closed true} [:required :int]]
          :template {}
          :modes {:simulate {:params-schema [:map {:closed true}]}}
          :faults {}
          :compile (fn [_] :unused)})]
    (is (= :jolt.sim.schema/unsupported-schema
           (:type (ex-data-of #(packs/registry [invalid-schema])))))
    (let [data (ex-data-of #(packs/registry [invalid-template]))]
      (is (= :jolt.sim.schema/invalid-value (:type data)))
      (is (= :template (:field data)))
      (is (not (contains? data :value))))))

(deftest registry-values-are-revalidated-on-use
  (is (= :jolt.sim.pack-registry/not-a-registry
         (:type (ex-data-of #(packs/describe {} :http/form-session-v1)))))
  (is (= :jolt.sim.pack-registry/not-a-registry
         (:type (ex-data-of #(packs/describe {:packs {}} :http/form-session-v1)))))
  (is (= :jolt.sim.pack-registry/not-a-registry
         (:type (ex-data-of
                 #(packs/describe (assoc test-registry :extra true)
                                  :http/form-session-v1)))))
  ;; An assoc'd entry does not survive revalidation at the call boundary.
  (let [tampered (assoc-in test-registry
                           [:packs [:connection :http/form-session-v1] :compile]
                           "not-a-fn")]
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of #(packs/describe tampered :http/form-session-v1))))))
  ;; An entry stored under a mismatched kind key is rejected.
  (let [misplaced (assoc-in test-registry
                            [:packs [:check :http/form-session-v1]]
                            http-connection)]
    (is (= :jolt.sim.pack-registry/not-a-registry
           (:type (ex-data-of #(packs/describe misplaced
                                               [:check :http/form-session-v1]))))))
  ;; Schema and template guarantees are rechecked on the selected entry, not
  ;; merely when registry was first constructed.
  (let [tampered-schema
        (assoc-in test-registry
                  [:packs [:connection :http/form-session-v1] :config-schema]
                  [:map [:csrf-required :boolean]])
        tampered-params
        (assoc-in test-registry
                  [:packs [:connection :http/form-session-v1]
                   :modes :simulate :params-schema]
                  [:map [:latency-ms :int]])
        tampered-template
        (assoc-in test-registry
                  [:packs [:connection :http/form-session-v1] :template]
                  {})]
    (is (= :jolt.sim.schema/unsupported-schema
           (:type (ex-data-of #(packs/describe tampered-schema
                                               :http/form-session-v1)))))
    (is (= :jolt.sim.schema/unsupported-schema
           (:type (ex-data-of #(packs/modes tampered-params
                                            :http/form-session-v1)))))
    (is (= :jolt.sim.schema/invalid-value
           (:type (ex-data-of #(packs/template tampered-template
                                               :http/form-session-v1)))))))

(deftest registry-revalidates-only-the-selected-entry
  (let [unrelated-tamper
        (assoc-in test-registry
                  [:packs [:check :example.outbox/command-atomic-v1] :check]
                  "not-a-fn")]
    (is (= :connection
           (:kind (packs/describe unrelated-tamper :http/form-session-v1))))
    (is (= :jolt.sim.pack-registry/invalid-descriptor
           (:type (ex-data-of
                   #(packs/describe unrelated-tamper
                                    :example.outbox/command-atomic-v1)))))))

;; ---- resolution, unknown and ambiguous references ----------------------------

(deftest unknown-references-fail-closed
  (let [bare (ex-data-of #(packs/describe test-registry :missing/pack-v1))
        kinded (ex-data-of #(packs/describe test-registry
                                            [:connection :missing/pack-v1]))
        ;; The id exists, but not under this kind.
        wrong-kind (ex-data-of
                    #(packs/describe test-registry
                                     [:connection :example.outbox/command-atomic-v1]))]
    (doseq [data [bare kinded wrong-kind]]
      (is (= :jolt.sim.pack-registry/unknown-pack (:type data))))))

(deftest malformed-references-fail-closed
  (doseq [bad [42
               "http/form-session-v1"
               [:bogus-kind :http/form-session-v1]
               [:connection "http/form-session-v1"]
               [:connection :http/form-session-v1 :extra]]]
    (is (= :jolt.sim.pack-registry/invalid-pack-ref
           (:type (ex-data-of #(packs/describe test-registry bad))))
        (pr-str bad))))

(deftest kind-qualified-references-disambiguate-shared-ids
  (let [dual-connection (packs/connection-pack
                         {:id :shared/dual-v1
                          :doc "dual connection"
                          :capabilities test-capabilities
                          :config-schema [:map {:closed true}]
                          :template {}
                          :modes {:record {:params-schema [:map {:closed true}]}}
                          :faults {}
                          :compile (fn [_] :unused)})
        dual-check (packs/check-pack
                    {:id :shared/dual-v1
                     :doc "dual check"
                     :config-schema [:map {:closed true}]
                     :template {}
                     :observations []
                     :check (fn [_] :unused)})
        registry (packs/registry [http-connection atomic-check
                                  dual-connection dual-check])]
    (let [data (ex-data-of #(packs/describe registry :shared/dual-v1))]
      (is (= :jolt.sim.pack-registry/ambiguous-pack (:type data)))
      (is (= :shared/dual-v1 (:ref data)))
      (is (= [:connection :check] (:kinds data))))
    (is (= :connection (:kind (packs/describe registry [:connection :shared/dual-v1]))))
    (is (= :check (:kind (packs/describe registry [:check :shared/dual-v1]))))
    (is (= {:record {:params-schema [:map {:closed true}]}}
           (packs/modes registry [:connection :shared/dual-v1])))))

(deftest connection-only-queries-fail-on-check-packs
  (doseq [query-fn [packs/modes packs/faults]]
    (let [data (ex-data-of #(query-fn test-registry :example.outbox/command-atomic-v1))]
      (is (= :jolt.sim.pack-registry/unsupported-query (:type data)))
      (is (= :check (:kind data)))
      (is (= :example.outbox/command-atomic-v1 (:pack-id data))))))

;; ---- trusted compilation ----------------------------------------------------

(deftest compile-connection-invokes-the-trusted-compiler
  (let [binding (packs/compile-connection
                 test-registry :http/form-session-v1
                 {:mode :simulate
                  :config {:csrf-required true}
                  :params {:latency-ms 1}})]
    (is (= :jolt.sim.pack-registry/connection-binding
           (:jolt.sim.pack-registry/type binding)))
    (is (= :http/form-session-v1 (:pack-id binding)))
    (is (= :simulate (:mode binding)))
    (is (= {:csrf-required true} (get-in binding [:fields :received-config])))
    (is (= {:latency-ms 1} (get-in binding [:fields :received-params])))
    ;; Binding fields are opaque and may carry trusted executable values.
    (is (fn? (get-in binding [:fields :projector])))))

(deftest compile-connection-rejects-unsupported-modes
  (let [data (ex-data-of #(packs/compile-connection
                           test-registry :http/form-session-v1
                           {:mode :hybrid :config {} :params {}}))]
    (is (= :jolt.sim.pack-registry/unsupported-mode (:type data)))
    (is (= :hybrid (:mode data)))
    (is (= #{:simulate :record} (:supported data)))))

(deftest compile-connection-validates-request-shape-and-data
  (let [valid {:mode :simulate :config {} :params {}}]
    (doseq [request [(dissoc valid :params)
                     (assoc valid :extra 1)
                     "not-a-map"
                     (assoc valid :mode "simulate")]]
      (is (= :jolt.sim.pack-registry/invalid-request
             (:type (ex-data-of #(packs/compile-connection
                                  test-registry :http/form-session-v1 request))))
          (pr-str request)))
    (let [config-fn (ex-data-of
                     #(packs/compile-connection
                       test-registry :http/form-session-v1
                       (assoc valid :config {:on-error (fn [_] :boom)})))]
      (is (= :jolt.sim.pack-registry/invalid-request (:type config-fn)))
      (is (= :config (:field config-fn))))
    (let [params-fn (ex-data-of
                     #(packs/compile-connection
                       test-registry :http/form-session-v1
                       (assoc valid :params {:retry {:when (fn [_] true)}})))]
      (is (= :jolt.sim.pack-registry/invalid-request (:type params-fn)))
      (is (= :params (:field params-fn)))
      (is (= [:retry :when] (:path params-fn))))))

(deftest compile-connection-validates-schemas-before-trusted-code
  (let [calls (atom 0)
        pack
        (packs/connection-pack
         {:id :example/strict-v1
          :doc "d"
          :capabilities test-capabilities
          :config-schema [:map {:closed true} [:enabled :boolean]]
          :template {:enabled true}
          :modes {:simulate
                  {:params-schema [:map {:closed true} [:retries :int]]}}
          :faults {}
          :compile (fn [request]
                     (swap! calls inc)
                     (packs/connection-binding (:id request)
                                               (:mode request) {}))})
        registry (packs/registry [pack])
        bad-config
        (ex-data-of #(packs/compile-connection
                      registry :example/strict-v1
                      {:mode :simulate
                       :config {:enabled "yes"}
                       :params {:retries 1}}))
        bad-params
        (ex-data-of #(packs/compile-connection
                      registry :example/strict-v1
                      {:mode :simulate
                       :config {:enabled true}
                       :params {:retries 1 :unknown true}}))]
    (is (= :jolt.sim.schema/invalid-value (:type bad-config)))
    (is (= :config (:field bad-config)))
    (is (= :jolt.sim.schema/invalid-value (:type bad-params)))
    (is (= :params (:field bad-params)))
    (is (zero? @calls))))

(deftest compile-connection-revalidates-compiler-output
  (let [valid (packs/connection-binding :http/form-session-v1 :simulate {})
        request {:mode :simulate :config {} :params {}}
        compilers {:not-a-map (fn [_] :not-a-binding)
                   :extra-key (fn [_] (assoc valid :smuggled 1))
                   :wrong-pack (fn [_] (assoc valid :pack-id :other/pack-v1))
                   :wrong-mode (fn [_] (assoc valid :mode :record))
                   :wrong-marker (fn [_] (assoc valid :jolt.sim.pack-registry/type
                                                :jolt.sim.pack-registry/check-binding))
                   :non-map-fields (fn [_] {:jolt.sim.pack-registry/type
                                            :jolt.sim.pack-registry/connection-binding
                                            :pack-id :http/form-session-v1
                                            :mode :simulate
                                            :fields []})}]
    (doseq [[label compile-fn] compilers]
      (let [registry (packs/registry [(conn-pack-with compile-fn)])
            data (ex-data-of #(packs/compile-connection
                               registry :http/form-session-v1 request))]
        (is (= :jolt.sim.pack-registry/invalid-binding (:type data))
            (pr-str label))))))

(deftest compile-check-invokes-the-trusted-binder
  (let [binding (packs/compile-check test-registry
                                     :example.outbox/command-atomic-v1
                                     {:config {:when :always}})]
    (is (= :jolt.sim.pack-registry/check-binding
           (:jolt.sim.pack-registry/type binding)))
    (is (= :example.outbox/command-atomic-v1 (:pack-id binding)))
    (is (= {:when :always} (get-in binding [:fields :received-config])))
    (is (fn? (get-in binding [:fields :run])))))

(deftest compile-check-validates-request-and-output
  (is (= :jolt.sim.pack-registry/invalid-request
         (:type (ex-data-of #(packs/compile-check
                              test-registry :example.outbox/command-atomic-v1
                              {:config {:cb (fn [_])}})))))
  (is (= :jolt.sim.pack-registry/invalid-request
         (:type (ex-data-of #(packs/compile-check
                              test-registry :example.outbox/command-atomic-v1
                              {:config {} :extra 1})))))
  (let [bad-binder (packs/check-pack
                    {:id :example/bad-binder-v1
                     :doc "d"
                     :config-schema [:map {:closed true}]
                     :template {}
                     :observations []
                     :check (fn [_] {:no :marker})})
        registry (packs/registry [bad-binder])]
    (is (= :jolt.sim.pack-registry/invalid-binding
           (:type (ex-data-of #(packs/compile-check
                                registry :example/bad-binder-v1
                                {:config {}})))))))

(deftest compile-check-validates-schema-before-trusted-code
  (let [calls (atom 0)
        pack
        (packs/check-pack
         {:id :example/strict-check-v1
          :doc "d"
          :config-schema [:map {:closed true} [:limit :int]]
          :template {:limit 1}
          :observations []
          :check (fn [request]
                   (swap! calls inc)
                   (packs/check-binding (:id request) {}))})
        registry (packs/registry [pack])
        data (ex-data-of #(packs/compile-check
                           registry :example/strict-check-v1
                           {:config {:limit "unbounded"}}))]
    (is (= :jolt.sim.schema/invalid-value (:type data)))
    (is (= :config (:field data)))
    (is (zero? @calls))))

(deftest compilation-is-kind-directed
  (is (= :jolt.sim.pack-registry/unsupported-query
         (:type (ex-data-of #(packs/compile-connection
                              test-registry :example.outbox/command-atomic-v1
                              {:mode :simulate :config {} :params {}})))))
  (is (= :jolt.sim.pack-registry/unsupported-query
         (:type (ex-data-of #(packs/compile-check
                              test-registry :http/form-session-v1
                              {:config {}}))))))

;; ---- EDN-shaped manifests cannot install executable callbacks ----------------

(deftest edn-shaped-descriptor-cannot-install-a-compiler
  ;; The canonical manifest form is closed data: stable ids and plain values.
  ;; A descriptor read from EDN carries symbols or strings where executable
  ;; entries belong, and the trusted constructor fails closed on them.
  (let [manifest (edn/read-string
                  (str "{:id :http/form-session-v1"
                       " :doc \"edn descriptor\""
                       " :capabilities {:from #{:test/from-v1} :to #{:test/to-v1}}"
                       " :config-schema [:map {:closed true}]"
                       " :template {}"
                       " :modes {:simulate {:params-schema [:map {:closed true}]}}"
                       " :faults {}"
                       " :compile some.evil/install}"))]
    (is (symbol? (:compile manifest)))
    (let [data (ex-data-of #(packs/connection-pack manifest))]
      (is (= :jolt.sim.pack-registry/invalid-descriptor (:type data)))
      (is (= :compile (:field data))))
    ;; Forging the registry type marker on pure data does not help: registry
    ;; composition reconstructs every entry through the trusted constructor.
    (let [forged (assoc manifest :jolt.sim.pack-registry/type
                        :jolt.sim.pack-registry/connection-pack)
          data (ex-data-of #(packs/registry [forged]))]
      (is (= :jolt.sim.pack-registry/invalid-descriptor (:type data)))
      (is (= :compile (:field data))))))

(deftest edn-shaped-requests-cannot-carry-callbacks-to-a-trusted-compiler
  ;; Even with a valid registry, request :config and :params are recursively
  ;; data-only, so executable values can never ride experiment data into a
  ;; trusted compiler.
  (let [data (ex-data-of
              #(packs/compile-connection
                test-registry :http/form-session-v1
                {:mode :simulate
                 :config {:handlers {:on-error (fn [_] :boom)}}
                 :params {}}))]
    (is (= :jolt.sim.pack-registry/invalid-request (:type data)))
    (is (= :config (:field data)))
    (is (= [:handlers :on-error] (:path data)))))
