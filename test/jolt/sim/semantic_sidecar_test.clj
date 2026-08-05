(ns jolt.sim.semantic-sidecar-test
  (:require [clojure.test :as test :refer [deftest is testing]]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.semantic-sidecar :as sidecar]
            [jolt.sim.trace :as trace]
            [perturb.b63 :as b63]
            [perturb.semantic :as semantic]))

(def ^:private monitor-id :perturb.b63/error-mapping)

(defn- semantic-document []
  (semantic/document
   {:layers {:record [[:recv :eof]]}}
   [[:layer/request-opened 1 0 :record :recv]
    [:layer/refusal 2 1 :eof]
    [:layer/request-replied 1 2 true]]))

(defn- adapters
  ([] (adapters nil))
  ([evaluator]
   {:semantic-validator semantic/validate-document!
    :evaluator
    (or evaluator
        (fn [doc]
          (semantic/run-monitor
           (b63/spec-for (:perturb.semantic/case doc))
           doc)))}))

(defn- decision [semantic-doc]
  ((:evaluator (adapters)) semantic-doc))

(defn- case-map []
  {:scenario 'example.semantic/run
   :mode :hermetic
   :input {:fixture :b-prime}
   :schedule [1 0]})

(defn- case-outcome-edn
  ([semantic-doc monitors]
   (case-outcome-edn semantic-doc monitors
                     {:status :completed
                      :result {:semantic-events 3}
                      :exit 0}))
  ([_semantic-doc monitors outcome]
   (case-outcome/canonical-edn
    (case-outcome/document (case-map) outcome monitors))))

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default error
      error)))

(defn- caught-data [f]
  (some-> (caught f) ex-data))

(deftest pass-round-trips-and-repeats-the-evaluator
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        calls (atom 0)
        evaluator (fn [doc]
                    (swap! calls inc)
                    (decision doc))
        adapters (adapters evaluator)
        doc (sidecar/document
             (case-outcome-edn semantic-doc [stored])
             semantic-doc
             monitor-id
             adapters)]
    (is (= 2 @calls) "construction evaluates the same document twice")
    (is (= #{:jolt.sim.semantic-sidecar/version
             :jolt.sim.semantic-sidecar/case-outcome-edn
             :jolt.sim.semantic-sidecar/semantic-document
             :jolt.sim.semantic-sidecar/monitor-id}
           (set (keys doc))))
    (is (= (trace/canonical-value monitor-id)
           (:jolt.sim.semantic-sidecar/monitor-id doc)))
    (let [printed (sidecar/canonical-edn doc adapters)
          read-back (sidecar/read-edn printed adapters)]
      (is (= doc read-back))
      (is (= printed (sidecar/canonical-edn read-back adapters)))
      (is (= 8 @calls)
          "canonical write, read, and repeated write each evaluate twice"))))

(deftest e43-deferred-correlation-nonclaim-allows-unbound-case-pairing
  (testing "E43's deferred correlation nonclaim, not desired behavior"
    (let [semantic-case {:layers {:record [[:recv :eof]]}
                         :fixture :semantic-fixture}
          case-input {:fixture :case-outcome-fixture}
          semantic-doc
          (semantic/document
           semantic-case
           (:perturb.semantic/events (semantic-document)))
          stored (decision semantic-doc)
          parent-edn
          (case-outcome/canonical-edn
           (case-outcome/document
            (assoc (case-map) :input case-input)
            {:status :completed :result {:semantic-events 3} :exit 0}
            [stored]))
          doc (sidecar/document parent-edn semantic-doc monitor-id (adapters))
          read-back (sidecar/read-edn (sidecar/canonical-edn doc (adapters))
                                      (adapters))]
      ;; E43 defers an explicit correlation reference. This passing mismatch
      ;; records that current nonclaim; it must not be read as desired pairing.
      (is (not= semantic-case case-input))
      (is (= :pass (:status stored)))
      (is (= semantic-case
             (get-in doc [:jolt.sim.semantic-sidecar/semantic-document
                          :perturb.semantic/case])))
      (is (= case-input
             (:input
              (case-outcome/restore-case
               (case-outcome/read-edn
                (:jolt.sim.semantic-sidecar/case-outcome-edn doc))))))
      (is (= doc read-back)))))

(deftest noncanonical-case-outcome-edn-fails-closed
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        canonical (case-outcome-edn semantic-doc [stored])
        calls (atom 0)
        data
        (caught-data
         #(sidecar/document
           (str " " canonical)
           semantic-doc monitor-id
           (adapters (fn [_] (swap! calls inc) stored))))]
    (is (= sidecar/invalid-document (:type data)))
    (is (= :noncanonical-case-outcome-edn (:reason data)))
    (is (zero? @calls))))

(deftest unknown-and-duplicate-monitor-controls-fail-closed
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)]
    (testing "unknown monitor id"
      (let [calls (atom 0)
            data
            (caught-data
             #(sidecar/document
               (case-outcome-edn semantic-doc [stored])
               semantic-doc :unknown/monitor
               (adapters (fn [_] (swap! calls inc) stored))))]
        (is (= sidecar/invalid-document (:type data)))
        (is (= :unknown-monitor (:reason data)))
        (is (zero? @calls))))
    (testing "Case/Outcome's public validator rejects duplicate stored ids"
      (let [valid
            (case-outcome/document
             (case-map)
             {:status :completed :result :ok :exit 0}
             [stored])
            duplicate
            (update valid :jolt.sim.case-outcome/monitors
                    conj (first (:jolt.sim.case-outcome/monitors valid)))
            duplicate-edn (trace/canonical-edn duplicate)
            calls (atom 0)
            data
            (caught-data
             #(sidecar/document
               duplicate-edn semantic-doc monitor-id
               (adapters (fn [_] (swap! calls inc) stored))))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :duplicate-monitor-id (:reason data)))
        (is (zero? @calls))))))

(deftest evaluator-mismatch-and-nondeterminism-fail-closed
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        parent-edn (case-outcome-edn semantic-doc [stored])]
    (testing "a stable different decision does not match stored evidence"
      (let [different (assoc stored :status :violation)
            data
            (caught-data
             #(sidecar/document parent-edn semantic-doc monitor-id
                                (adapters (fn [_] different))))]
        (is (= sidecar/invalid-document (:type data)))
        (is (= :evaluator-mismatch (:reason data)))))
    (testing "two different evaluator results are rejected before comparison"
      (let [calls (atom 0)
            data
            (caught-data
             #(sidecar/document
               parent-edn semantic-doc monitor-id
               (adapters
                (fn [_]
                  (if (= 1 (swap! calls inc))
                    stored
                    (assoc stored :status :violation))))))]
        (is (= sidecar/invalid-document (:type data)))
        (is (= :nondeterministic-evaluator (:reason data)))
        (is (= 2 @calls))))))

(deftest detail-only-evaluator-mismatch-fails-closed
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        different (assoc stored :detail
                         (assoc (:detail stored) :reviewer-control true))
        data
        (caught-data
         #(sidecar/document
           (case-outcome-edn semantic-doc [stored])
           semantic-doc monitor-id
           (adapters (fn [_] different))))]
    (is (= (dissoc stored :detail) (dissoc different :detail)))
    (is (not= (:detail stored) (:detail different)))
    (is (= sidecar/invalid-document (:type data)))
    (is (= :evaluator-mismatch (:reason data)))))

(deftest failed-outcome-with-stored-monitor-round-trips
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        outcome {:status :failed
                 :error {:kind :application-failure :attempt 1}
                 :exit 1}
        parent-edn (case-outcome-edn semantic-doc [stored] outcome)
        doc (sidecar/document parent-edn semantic-doc monitor-id (adapters))
        printed (sidecar/canonical-edn doc (adapters))
        read-back (sidecar/read-edn printed (adapters))
        parent (case-outcome/read-edn parent-edn)]
    (is (= outcome (case-outcome/restore-outcome parent)))
    (is (= [stored] (case-outcome/restore-monitors parent)))
    (is (= doc read-back))
    (is (= printed (sidecar/canonical-edn read-back (adapters))))))

(deftest no-monitor-timeout-and-worker-error-have-no-sidecar
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)]
    (testing "a completed case with no stored monitor has no semantic sidecar"
      (let [calls (atom 0)
            data
            (caught-data
             #(sidecar/document
               (case-outcome-edn semantic-doc [])
               semantic-doc monitor-id
               (adapters (fn [_] (swap! calls inc) stored))))]
        (is (= sidecar/invalid-document (:type data)))
        (is (= :no-monitors (:reason data)))
        (is (zero? @calls))))
    (doseq [outcome
            [{:status :timeout :reason :deadline :exit 124}
             {:status :worker-error :error {:kind :bootstrap} :exit nil}]]
      (testing (name (:status outcome))
        (let [calls (atom 0)
              data
              (caught-data
               #(sidecar/document
                 (case-outcome-edn semantic-doc [stored] outcome)
                 semantic-doc monitor-id
                 (adapters (fn [_] (swap! calls inc) stored))))]
          (is (= sidecar/invalid-document (:type data)))
          (is (= :unsupported-outcome-status (:reason data)))
          (is (= (:status outcome) (:detail data)))
          (is (zero? @calls)))))))

(deftest existing-semantic-grammar-is-the-only-payload-validator
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        parent-edn (case-outcome-edn semantic-doc [stored])
        malformed (assoc semantic-doc :perturb.semantic/events
                         [[:layer/not-in-the-existing-grammar]])
        calls (atom 0)
        data
        (caught-data
         #(sidecar/document
           parent-edn malformed monitor-id
           (adapters (fn [_] (swap! calls inc) stored))))]
    (is (= :perturb.semantic/malformed (:type data)))
    (is (= :unknown-tag (:perturb.semantic/reason data)))
    (is (zero? @calls))))

(deftest closed-sidecar-shape-rejects-identity-like-additions
  (let [semantic-doc (semantic-document)
        stored (decision semantic-doc)
        valid (sidecar/document
               (case-outcome-edn semantic-doc [stored])
               semantic-doc monitor-id (adapters))]
    (doseq [extra [:pid :path :coordinate :hash]]
      (let [data
            (caught-data
             #(sidecar/validate-document!
               (assoc valid extra "not-an-identity")
               (adapters)))]
        (is (= sidecar/invalid-document (:type data)))
        (is (= :wrong-keys (:reason data)))))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.semantic-sidecar-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
