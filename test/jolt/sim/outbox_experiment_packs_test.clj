(ns jolt.sim.outbox-experiment-packs-test
  "Focused tests for the trusted cancel-before-ack outbox experiment packs
   (jolt.sim.fixtures.outbox-experiment-packs):

   * the versioned connection descriptors and the fixed closed manifest
     compile through their declared Malli schemas and capabilities;
   * every trusted connection compiler runs exactly once per case, and plan validation or
     inspection never recompiles;
   * the per-case shared-world identity and the physical handler-ownership
     partition are non-vacuous: one memory world backs both modeled worlds,
     each physical pack is claimed exactly once, double-claiming fails
     closed, and the compiled plan installs the bundle's identical handler
     functions;
   * the opaque plan carries the expected hermetic runtime config (no clock,
     no schedule state), probes, and projectors.

   Nothing here executes the application: this slice assembles and inspects
   plans only, so it needs no sim image, no ffi-schedule coordinator, and no
   virtual clock."
  (:require [clojure.set :as set]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.sim.experiment :as experiment]
            [jolt.sim.fixtures.outbox-experiment-packs :as experiment-packs]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.pack-registry :as packs]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- compile-case
  ([bundle]
   (compile-case bundle experiment-packs/cancel-before-ack-manifest))
  ([bundle manifest]
   (experiment/compile-plan (experiment-packs/case-registry bundle)
                            manifest
                            :hermetic)))

;; ---- descriptor and manifest compilation --------------------------------------

(deftest descriptors-compile-with-declared-schemas-and-capabilities
  (let [bundle (experiment-packs/new-case-bundle)
        registry (experiment-packs/case-registry bundle)]
    (testing "the fixed manifest validates as closed data"
      (is (= experiment-packs/cancel-before-ack-manifest
             (experiment/validate-manifest!
              experiment-packs/cancel-before-ack-manifest))))
    (testing "connection descriptors expose data-only discovery projections"
      (doseq [[ref capabilities]
              [[:outbox.http/command-v1
                {:from #{:outbox.http/client-v1}
                 :to #{:outbox.http/server-v1}}]
               [:outbox.tcp/delivery-v1
                {:from #{:outbox.tcp/client-v1}
                 :to #{:outbox.tcp/server-v1}}]
               [:outbox.sqlite/store-v1
                {:from #{:outbox.sqlite/client-v1}
                 :to #{:outbox.sqlite/server-v1}}]]]
        (let [description (packs/describe registry [:connection ref])]
          (is (= :connection (:kind description)))
          (is (= ref (:id description)))
          (is (= capabilities (:capabilities description)))
          (is (= {:simulate {:params-schema [:map {:closed true}]}}
                 (:modes description)))
          (is (= {} (:faults description)))
          (is (not (contains? description :compile))))))
    (testing "templates validate through the registry as data-only values"
      (is (= {:contract :outbox/command-v1}
             (packs/template registry [:connection :outbox.http/command-v1])))
      (is (= {:contract :outbox/delivery-v1}
             (packs/template registry [:connection :outbox.tcp/delivery-v1])))
      (is (= {:contract :outbox/store-v1}
             (packs/template registry [:connection :outbox.sqlite/store-v1]))))
    (testing "unknown and kind-mismatched references fail closed"
      (is (= :jolt.sim.pack-registry/unknown-pack
             (:type (ex-data-of
                     #(packs/resolve-pack registry :missing/connection-v1)))))
      (is (= :jolt.sim.pack-registry/unknown-pack
             (:type (ex-data-of
                     #(packs/resolve-pack
                       registry [:check :outbox.http/command-v1]))))))))

;; ---- exactly-once trusted callbacks -------------------------------------------

(deftest each-trusted-callback-runs-exactly-once-per-case
  (let [bundle (experiment-packs/new-case-bundle)
        plan (compile-case bundle)]
    (is (= 1 @(get-in bundle [:callbacks :compile :command])))
    (is (= 1 @(get-in bundle [:callbacks :compile :delivery])))
    (is (= 1 @(get-in bundle [:callbacks :compile :store])))
    (testing "plan validation and inspection never recompile"
      (is (= plan (experiment/validate-plan! plan)))
      (is (some? (experiment/plan-data plan)))
      (is (= 1 @(get-in bundle [:callbacks :compile :command])))
      (is (= 1 @(get-in bundle [:callbacks :compile :delivery])))
      (is (= 1 @(get-in bundle [:callbacks :compile :store]))))
    (testing "a fresh case compiles against a fresh bundle and fresh counters"
      (let [other (experiment-packs/new-case-bundle)]
        (is (some? (compile-case other)))
        (is (= 1 @(get-in other [:callbacks :compile :command])))
        (is (= 1 @(get-in other [:callbacks :compile :delivery])))
        (is (= 1 @(get-in other [:callbacks :compile :store])))
        (is (= 1 @(get-in bundle [:callbacks :compile :command])))
        (is (not (identical? (:memory bundle) (:memory other))))
        (is (not (identical? (:sqlite bundle) (:sqlite other))))
        (is (not (identical? (:posix bundle) (:posix other))))))))

;; ---- shared-world identity and handler ownership -------------------------------

(defn- normalized-handlers [bundle physical]
  (runtime/normalize-ffi-handlers (get-in bundle [:handlers physical])))

(deftest shared-world-identity-and-handler-ownership-are-non-vacuous
  (let [bundle (experiment-packs/new-case-bundle)
        mem (:memory bundle)
        sqlite-world (:sqlite bundle)
        posix-world (:posix bundle)]
    (testing "exactly one memory world backs both modeled worlds"
      (is (identical? mem (:memory sqlite-world)))
      (is (identical? mem (:memory posix-world)))
      (is (identical? (:memory sqlite-world) (:memory posix-world))))
    (testing "the ownership partition claims each physical pack exactly once"
      (is (= #{:command :delivery :store}
             (set (keys experiment-packs/physical-ownership))))
      (is (= #{:memory :posix :sqlite}
             (set (map :physical
                       (vals experiment-packs/physical-ownership)))))
      (is (= 3
             (count (set (map :pack-id
                              (vals experiment-packs/physical-ownership)))))))
    (testing "the physical handler maps are pairwise disjoint and complete"
      (let [key-sets (into {}
                           (map (fn [physical]
                                  [physical
                                   (set (keys (normalized-handlers bundle
                                                                   physical)))]))
                           [:memory :sqlite :posix])]
        (is (seq (get key-sets :memory)))
        (is (seq (get key-sets :sqlite)))
        (is (seq (get key-sets :posix)))
        (is (empty? (set/intersection (get key-sets :memory)
                                      (get key-sets :sqlite))))
        (is (empty? (set/intersection (get key-sets :memory)
                                      (get key-sets :posix))))
        (is (empty? (set/intersection (get key-sets :sqlite)
                                      (get key-sets :posix))))))
    (testing "double-claiming a physical pack fails closed"
      (is (= :jolt.sim.handler-pack/duplicate-handler-key
             (:type (ex-data-of
                     #(hp/compose
                       (hp/pack :outbox.experiment/memory
                                (get-in bundle [:handlers :memory]))
                       (hp/pack :outbox.experiment/other-memory
                                (get-in bundle [:handlers :memory])))))))
      (is (= :jolt.sim.handler-pack/duplicate-handler-key
             (:type (ex-data-of
                     #(hp/compose
                       (hp/pack :outbox.experiment/sqlite
                                (get-in bundle [:handlers :sqlite]))
                       (hp/pack :outbox.experiment/sqlite-again
                                (sqlite/foreign-handlers sqlite-world))))))))
    (testing "each connection binding owns exactly one declared handler pack"
      (let [data (experiment/plan-data (compile-case bundle))]
        (doseq [[connection-id compiled] (:connections data)]
          (is (= [(get-in experiment-packs/physical-ownership
                          [connection-id :pack-id])]
                 (mapv :id
                       (get-in compiled
                               [:binding :fields :handler-packs])))))))
    (testing "the plan installs the bundle's identical handler functions"
      (let [data (experiment/plan-data (compile-case bundle))
            plan-handlers (get-in data [:runtime-config :ffi-handlers])
            expected (reduce merge {}
                             (map #(normalized-handlers bundle %)
                                  [:memory :sqlite :posix]))]
        (is (= (count expected) (count plan-handlers)))
        (is (= (set (keys expected)) (set (keys plan-handlers))))
        (doseq [[key handler] expected]
          (is (identical? handler (get plan-handlers key))))))))

;; ---- hermetic plan shape --------------------------------------------------------

(deftest hermetic-plan-carries-expected-runtime-config-probes-and-projectors
  (let [bundle (experiment-packs/new-case-bundle)
        plan (compile-case bundle)
        data (experiment/plan-data plan)]
    (testing "the opaque plan has the fixed identity and version"
      (is (= :outbox.experiment/cancel-before-ack-v1 (:experiment-id data)))
      (is (= :hermetic (:profile-id data)))
      (is (= 1 (:jolt.sim.experiment-plan/version data)))
      (is (= experiment-packs/cancel-before-ack-manifest (:manifest data))))
    (testing "hermetic runtime config with no clock and no schedule state"
      (is (= #{:ffi-mode :ffi-handlers}
             (set (keys (:runtime-config data)))))
      (is (= :hermetic (get-in data [:runtime-config :ffi-mode])))
      (is (not (contains? (:runtime-config data) :clock)))
      (is (nil? (:clock (:posix bundle))))
      (is (not-any? #(contains? data %)
                    [:seed :schedule :choices :runnable :search-state])))
    (testing "every connection compiled with fixed identity and capabilities"
      (is (= #{:command :delivery :store} (set (keys (:connections data)))))
      (doseq [[connection-id pack-id capabilities from to]
              [[:command :outbox.http/command-v1
                {:from #{:outbox.http/client-v1}
                 :to #{:outbox.http/server-v1}}
                [:client :commands] [:app :commands]]
               [:delivery :outbox.tcp/delivery-v1
                {:from #{:outbox.tcp/client-v1}
                 :to #{:outbox.tcp/server-v1}}
                [:app :deliveries] [:receiver :deliveries]]
               [:store :outbox.sqlite/store-v1
                {:from #{:outbox.sqlite/client-v1}
                 :to #{:outbox.sqlite/server-v1}}
                [:app :store] [:sqlite :database]]]]
        (let [compiled (get-in data [:connections connection-id])]
          (is (= pack-id (:pack-id compiled)))
          (is (= :simulate (:mode compiled)))
          (is (= capabilities (:capabilities compiled)))
          (is (= from (:from compiled)))
          (is (= to (:to compiled)))
          (is (= capabilities
                 (get-in compiled [:binding :fields :consumes])))
          (is (nil? (get-in compiled [:binding :fields :clock])))
          (is (= #{} (get-in compiled [:binding :fields :native-fallback]))))))
    (testing "probes report physical activity without causal attribution"
      (is (= #{:command :delivery :store}
             (set (keys (:mechanism-probes data)))))
      (doseq [connection-id [:command :delivery :store]]
        (let [probe (get-in data [:mechanism-probes connection-id])]
          (is (fn? probe))
          (let [report (probe nil)]
            (is (= connection-id (:connection-id report)))
            (is (= :physical-handler-substrate (:scope report)))
            (is (= (get-in experiment-packs/physical-ownership
                            [connection-id :pack-id])
                   (:physical-pack-id report)))
            (is (false? (:activity? report)))
            (is (not (contains? report :traversed?)))
            (is (map? (:evidence report)))))))
    (testing "projectors select exactly their owned physical events"
      (is (= #{:command :delivery :store}
             (set (keys (:history-projectors data)))))
      (let [memory-key (first (keys (normalized-handlers bundle :memory)))
            sqlite-key (first (keys (normalized-handlers bundle :sqlite)))
            posix-key (first (keys (normalized-handlers bundle :posix)))
            unowned-key [:foreign-function "not_a_modeled_symbol" []
                         :void false false nil]
            trace [{:handler-key memory-key :index 0}
                   {:handler-key sqlite-key :index 1}
                   {:handler-key posix-key :index 2}
                   {:handler-key unowned-key :index 3}]]
        (doseq [[connection-id expected-events]
                [[:command [{:handler-key memory-key :index 0}]]
                 [:delivery [{:handler-key posix-key :index 2}]]
                 [:store [{:handler-key sqlite-key :index 1}]]]]
          (let [projector (get-in data [:history-projectors connection-id])]
            (is (fn? projector))
            (is (= {:connection-id connection-id :events expected-events}
                   (projector trace)))))))
    (testing "semantic checks remain deferred until the executor binds observations"
      (is (= [] (:checks data)))
      (is (= [] (:monitor-specs data)))
      (is (= {} (:presentation-registry data))))))

;; ---- fail-closed compilation controls ---------------------------------------------

(deftest capability-mismatch-fails-closed-before-any-compilation
  (let [bundle (experiment-packs/new-case-bundle)
        mismatched
        (assoc-in experiment-packs/cancel-before-ack-manifest
                  [:nodes :app :ports :commands :capabilities]
                  #{:outbox.http/other-v1})
        data (ex-data-of #(compile-case bundle mismatched))]
    (is (= :jolt.sim.experiment/capability-mismatch (:type data)))
    (is (= 0 @(get-in bundle [:callbacks :compile :command])))
    (is (= 0 @(get-in bundle [:callbacks :compile :delivery])))
    (is (= 0 @(get-in bundle [:callbacks :compile :store])))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.outbox-experiment-packs-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
