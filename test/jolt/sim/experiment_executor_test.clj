(ns jolt.sim.experiment-executor-test
  (:require [clojure.test :as test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [jolt.sim.experiment :as experiment]
            [jolt.sim.experiment-executor :as executor]
            [jolt.sim.handler-pack :as handler-pack]
            [jolt.sim.pack-registry :as packs]
            [jolt.sim.runtime :as runtime]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- connection-fields [handler clock]
  {:consumes {:from #{:example/client-v1} :to #{:example/server-v1}}
   :handler-packs [(handler-pack/pack :example/handler-v1
                                     {[:native-operation :sizeof] handler})]
   :clock clock
   :mechanism-probe (fn [_] {:status :unexercised})
   :history-projector (fn [history] history)
   :monitor-specs []
   :presentation-registry {}
   :native-fallback #{}})

(defn- compiled-plan [handler clock]
  (let [pack-id :example/connection-v1
        registry
        (packs/registry
         [(packs/connection-pack
           {:id pack-id
            :doc "executor test connection"
            :capabilities {:from #{:example/client-v1}
                           :to #{:example/server-v1}}
            :config-schema [:map {:closed true}]
            :template {}
            :modes {:simulate {:params-schema [:map {:closed true}]}}
            :faults {}
            :compile
            (fn [{:keys [id mode]}]
              (packs/connection-binding id mode
                                        (connection-fields handler clock)))})])
        manifest
        {:jolt.sim.experiment/version 1
         :id :example/execution-v1
         :nodes
         {:client {:ports {:request {:direction :out
                                     :capabilities #{:example/client-v1}}}}
          :server {:ports {:request {:direction :in
                                     :capabilities #{:example/server-v1}}}}}
         :connections
         {:request {:from [:client :request]
                    :to [:server :request]
                    :pack pack-id
                    :config {}}}
         :profiles {:hermetic {:connections
                               {:request {:mode :simulate :params {}}}}}
         :checks []}]
    (experiment/compile-plan registry manifest :hermetic)))

(deftest forwards-compiled-runtime-identities-and-body-unchanged
  (let [handler (fn [_descriptor] :handled)
        clock (fn [_descriptor _proceed] 17)
        plan (compiled-plan handler clock)
        body-calls (atom 0)
        body (fn [] (swap! body-calls inc) :body-result)
        calls (atom [])
        sentinel {:result :body-result :effect-trace []}]
    (with-redefs [runtime/run-controlled
                  (fn [config received-body]
                    (is (= 0 @body-calls))
                    (swap! calls conj [config received-body])
                    (is (= :body-result (received-body)))
                    sentinel)]
      (is (= sentinel (executor/execute! plan body)))
      (is (= 1 (count @calls)))
      (is (= 1 @body-calls))
      (let [[config received-body] (first @calls)
            installed (get-in config [:ffi-handlers
                                      [:native-operation :sizeof]])]
        (is (= #{:ffi-mode :ffi-handlers :clock} (set (keys config))))
        (is (= :hermetic (:ffi-mode config)))
        (is (identical? handler installed))
        (is (identical? clock (:clock config)))
        (is (identical? body received-body))))))

(deftest compiled-handler-intercepts-an-unchanged-ordinary-ffi-call
  (let [descriptors (atom [])
        handler (fn [descriptor]
                  (swap! descriptors conj descriptor)
                  (runtime/substitute 73))
        plan (compiled-plan handler nil)
        data (experiment/plan-data plan)]
    ;; Compilation and handler identity remain portable assertions. Only the
    ;; ABI interception witness requires a sim-enabled Jolt image.
    (is (= :hermetic (get-in data [:runtime-config :ffi-mode])))
    (is (identical? handler
                    (get-in data [:runtime-config :ffi-handlers
                                  [:native-operation :sizeof]])))
    (if (runtime/available?)
      (let [controlled (executor/execute! plan #(ffi/sizeof :int))]
        (is (= 73 (:result controlled)))
        (is (= 1 (count @descriptors)))
        (is (= :sizeof (:operation (first @descriptors))))
        (is (= [:handler] (mapv :route (:effect-trace controlled))))
        (is (= [:sizeof] (mapv :operation (:effects controlled)))))
      (is (false? (runtime/available?))))))

(deftest optional-drain-timeout-cannot-override-plan-control
  (let [plan (compiled-plan (fn [_] nil) nil)
        seen (atom nil)]
    (with-redefs [runtime/run-controlled
                  (fn [config body]
                    (reset! seen [config body])
                    {:result :ok :effect-trace []})]
      (is (= :ok (:result (executor/execute! plan {:drain-timeout-ms 7000}
                                                  (fn [] :ok)))))
      (is (= 7000 (get-in @seen [0 :drain-timeout-ms])))
      (is (= :hermetic (get-in @seen [0 :ffi-mode])))
      (is (map? (get-in @seen [0 :ffi-handlers]))))))

(deftest rejects-copied-plans-functions-and-control-overrides
  (let [plan (compiled-plan (fn [_] nil) nil)
        copied (experiment/plan-data plan)
        runtime-calls (atom 0)
        body-calls (atom 0)
        body (fn [] (swap! body-calls inc))]
    (with-redefs [runtime/run-controlled
                  (fn [& _] (swap! runtime-calls inc) :unexpected)]
      (testing "only the opaque direct compiler result is executable"
        (is (= :jolt.sim.experiment/invalid-plan
               (:type (ex-data-of
                       #(executor/execute! copied {:unknown true} body))))))
      (testing "valid plan precedes closed option and body validation"
        (is (= :invalid-options
               (:reason (ex-data-of
                         #(executor/execute! plan {:unknown true} body)))))
        (is (= :body-not-function
               (:reason (ex-data-of
                         #(executor/execute! plan {} :not-a-function))))))
      (testing "call data cannot replace compiler-owned control"
        (doseq [options [{:ffi-mode :observe}
                         {:ffi-handlers {}}
                         {:clock (fn [_ _] 0)}
                         {:drain-timeout-ms 0}
                         {:drain-timeout-ms -1}
                         {:drain-timeout-ms 1 :unknown true}]]
          (is (= executor/invalid-execution
                 (:type (ex-data-of
                         #(executor/execute! plan options body)))))))
      (is (= 0 @runtime-calls))
      (is (= 0 @body-calls)))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.experiment-executor-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, " (:pass result)
                  " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
