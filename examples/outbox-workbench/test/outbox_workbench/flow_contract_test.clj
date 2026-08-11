(ns outbox-workbench.flow-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]
            [outbox-workbench.flow-retained :as flow-retained]
            [outbox-workbench.flow-ripple-main :as flow-ripple]))

(defn- restored-world [snapshot]
  (:world (trace/restore-value (:projection snapshot))))

(deftest exact-operation-contracts-emit-one-inert-intent
  (doseq [command [{:op :submit :command outbox/default-command}
                   {:op :deliver}]]
    (testing (pr-str command)
      (let [capability (session/start
                        (flow-retained/command-flow command))
            before (session/snapshot capability)
            branches (session/branches capability)
            after-preview (session/snapshot capability)
            committed (session/step! capability
                                     (:branch (first branches)))]
        (is (= 1 (count branches)))
        (is (= before after-preview))
        (is (= [{:id [:jolt.sim.flow/intent :emit 0 0]
                 :kind :example.outbox/command
                 :payload command
                 :source {:cell :emit :message-id 0 :ordinal 0}}]
               (:effect-intents (restored-world committed))))))))

(deftest dependent-command-shapes-fail-before-compilation
  (doseq [command [{:op :submit}
                   {:op :submit :command outbox/default-command :extra true}
                   {:op :deliver :command outbox/default-command}
                   {:op :deliver :extra true}
                   {:op :unknown}
                   nil]]
    (testing (pr-str command)
      (let [error (try
                    (flow-retained/command-flow command)
                    nil
                    (catch :default error error))]
        (is (some? error))
        (is (= ::flow-retained/invalid-command
               (:type (ex-data error))))))))

(deftest viewer-config-reader-requires-exactly-one-form
  (is (= {:port 0}
         (flow-ripple/parse-config-edn "{:port 0}" "config.edn")))
  (doseq [[text reason]
          [["" :empty-document]
           ["{:port 0} {:extra true}" :trailing-document]
           ["{:port" :malformed-edn]]]
    (let [error (try
                  (flow-ripple/parse-config-edn text "config.edn")
                  nil
                  (catch :default error error))]
      (is (= ::flow-ripple/invalid-viewer-config
             (:type (ex-data error))))
      (is (= reason (:reason (ex-data error)))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests
                'outbox-workbench.flow-contract-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
