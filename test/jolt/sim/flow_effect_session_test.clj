(ns jolt.sim.flow-effect-session-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.kernel :as kernel]))

(defn- intent [id payload]
  {:id [:jolt.sim.flow/intent :effect id 0]
   :kind :example.outbox/command
   :payload payload
   :source {:cell :effect :message-id id :ordinal 0}})

(defn- intent-sim [intents]
  {:tasks {0 (kernel/runnable nil)}
   :world {:effect-intents []}
   :step (fn [{:keys [world]} _]
           (-> (kernel/step-complete :done)
               (kernel/with-world
                (update world :effect-intents into intents))
               (kernel/at-site {:kind :test/effect})))})

(defn- scripted-worker
  ([commands] (scripted-worker commands []))
  ([commands reconciliations]
   (let [commands (atom (vec commands))
         reconciliations (atom (vec reconciliations))
         state (atom {:status :ready
                      :next-sequence 0
                      :uncertain-sequence nil
                      :attempts []
                      :reconciliations 0})
         apply-outcome!
         (fn [source outcome]
           (cond
             (:receipt outcome)
             (let [receipt (:receipt outcome)]
               (swap! state assoc
                      :status :ready
                      :next-sequence (inc (:sequence receipt))
                      :uncertain-sequence nil)
               receipt)

             (:uncertain outcome)
             (let [sequence (:next-sequence @state)]
               (swap! state assoc :status :uncertain
                      :uncertain-sequence sequence)
               (throw (ex-info "scripted uncertain publication"
                               {:type ::transport
                                :reason (:uncertain outcome)
                                :source source
                                :sequence sequence})))

             :else
             (do
               (swap! state assoc :status :failed
                      :uncertain-sequence nil)
               (throw (ex-info "scripted prepublication failure"
                               {:type ::transport
                                :reason (:failure outcome)
                                :source source})))))]
     {:state state
      :service
      {:command!
       (fn [command]
         (swap! state update :attempts conj command)
         (let [outcome (first @commands)]
           (swap! commands #(vec (rest %)))
           (apply-outcome! :command outcome)))
       :reconcile!
       (fn []
         (swap! state update :reconciliations inc)
         (let [outcome (first @reconciliations)]
           (swap! reconciliations #(vec (rest %)))
           (apply-outcome! :reconcile outcome)))
       :snapshot (fn [] @state)}})))

(defn- attach [worker intents]
  (effect-session/attach!
   {:sim (intent-sim intents)
    :worker (:service worker)
    :effect-kind :example.outbox/command}))

(defn- completed [sequence value]
  {:receipt {:status :completed :sequence sequence :value value}})

(defn- failed [sequence reason]
  {:receipt {:status :failed :sequence sequence :error {:reason reason}}})

(deftest preview-is-inert-and-one-exact-commit-publishes-once
  (let [command {:op :submit :command {:request-id "req-1"}}
        worker (scripted-worker [(completed 0 {:http-status 201})])
        bridge (attach worker [(intent 0 command)])
        before (effect-session/snapshot bridge)
        previews (effect-session/branches bridge)
        branch (get-in previews [0 :branch])]
    (is (= 1 (count previews)))
    (is (= before (effect-session/snapshot bridge)))
    (is (= [] (:attempts @(:state worker))))

    (let [result (effect-session/step! bridge branch)]
      (is (true? (:committed? result)))
      (is (= :ready (get-in result [:delivery :status])))
      (is (= [command] (:attempts @(:state worker))))
      (is (= :settled
             (get-in result [:delivery :effects :records 0 :state])))
      (is (= :completed
             (get-in result [:delivery :effects :records 0 :status]))))

    (testing "the exact branch is stale and cannot authorize again"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stale"
                            (effect-session/step! bridge branch)))
      (is (= [command] (:attempts @(:state worker)))))

    (is (= :committed
           (:status (effect-session/reconcile-step bridge branch))))))

(deftest application-failure-is-a-definite-settled-receipt
  (let [command {:op :deliver}
        worker (scripted-worker [(failed 0 :ack-mismatch)])
        bridge (attach worker [(intent 0 command)])
        result (effect-session/step! bridge
                                     (get-in (effect-session/branches bridge)
                                             [0 :branch]))]
    (is (true? (:committed? result)))
    (is (= :ready (get-in result [:delivery :status])))
    (is (= :settled (get-in result [:delivery :effects :records 0 :state])))
    (is (= :failed (get-in result [:delivery :effects :records 0 :status])))
    (is (= :ack-mismatch
           (get-in result
                   [:delivery :effects :records 0 :receipt :error :reason])))))

(deftest uncertainty-blocks-steps-and-reconciliation-never-republishes
  (let [command {:op :submit :command {:request-id "req-uncertain"}}
        worker (scripted-worker [{:uncertain :receipt-deadline}]
                                [(completed 0 {:http-status 201})])
        bridge (attach worker [(intent 0 command)])
        branch (get-in (effect-session/branches bridge) [0 :branch])
        result (effect-session/step! bridge branch)]
    (is (true? (:committed? result)))
    (is (= :uncertain (get-in result [:delivery :status])))
    (is (= 0 (get-in result
                     [:delivery :effects :pending :worker
                      :uncertain-sequence])))
    (is (= [command] (:attempts @(:state worker))))
    (is (= :uncertain
           (:reason (ex-data
                     (try
                       (effect-session/step! bridge branch)
                       (catch :default error error))))))

    (let [reconciled (effect-session/reconcile! bridge)]
      (is (= :ready (:status reconciled)))
      (is (= :settled (get-in reconciled [:effects :records 0 :state])))
      (is (= :completed
             (get-in reconciled [:effects :records 0 :status])))
      (is (= [command] (:attempts @(:state worker))))
      (is (= 1 (:reconciliations @(:state worker)))))))

(deftest prepublication-failure-keeps-the-flow-commit-explicit
  (let [command {:op :submit :command {:request-id "req-failed"}}
        worker (scripted-worker [{:failure :command-path-exists}])
        bridge (attach worker [(intent 0 command)])
        branch (get-in (effect-session/branches bridge) [0 :branch])
        result (effect-session/step! bridge branch)]
    (is (true? (:committed? result)))
    (is (= :failed (get-in result [:delivery :status])))
    (is (= :failed (get-in result [:delivery :effects :records 0 :state])))
    (is (= [command] (:attempts @(:state worker))))
    (is (= :failed
           (:reason (ex-data
                     (try
                       (effect-session/step! bridge branch)
                       (catch :default error error))))))))

(deftest multiple-intents-publish-in-deterministic-order
  (let [commands [{:op :first} {:op :second}]
        worker (scripted-worker [(completed 0 {:n 1})
                                 (completed 1 {:n 2})])
        bridge (attach worker [(intent 0 (first commands))
                               (intent 1 (second commands))])
        result (effect-session/step! bridge
                                     (get-in (effect-session/branches bridge)
                                             [0 :branch]))]
    (is (= commands (:attempts @(:state worker))))
    (is (= [0 1]
           (mapv :sequence (get-in result [:delivery :effects :records]))))
    (is (= [:completed :completed]
           (mapv :status (get-in result [:delivery :effects :records]))))))

(deftest reconciliation-resumes-the-same-committed-intent-batch
  (let [commands [{:op :first} {:op :second}]
        worker (scripted-worker [{:uncertain :receipt-deadline}
                                 (completed 1 {:n 2})]
                                [(completed 0 {:n 1})])
        bridge (attach worker [(intent 0 (first commands))
                               (intent 1 (second commands))])
        branch (get-in (effect-session/branches bridge) [0 :branch])
        result (effect-session/step! bridge branch)]
    (is (true? (:committed? result)))
    (is (= :uncertain (get-in result [:delivery :status])))
    (is (= [(first commands)] (:attempts @(:state worker))))

    (let [reconciled (effect-session/reconcile! bridge)]
      (is (= :ready (:status reconciled)))
      (is (= commands (:attempts @(:state worker))))
      (is (= 1 (:reconciliations @(:state worker))))
      (is (= [0 1]
             (mapv :sequence (get-in reconciled [:effects :records]))))
      (is (= [:completed :completed]
             (mapv :status (get-in reconciled [:effects :records])))))))

(deftest step-reconciliation-is-read-only-and-revision-exact
  (let [worker (scripted-worker [(completed 0 {:ok true})])
        bridge (attach worker [(intent 0 {:op :once})])
        branch (get-in (effect-session/branches bridge) [0 :branch])
        different {:revision (:revision branch) :action [:run 99]}]
    (is (= :missing (:status (effect-session/reconcile-step bridge branch))))
    (is (= [] (:attempts @(:state worker))))
    (effect-session/step! bridge branch)
    (is (= :committed (:status (effect-session/reconcile-step bridge branch))))
    (is (= :different
           (:status (effect-session/reconcile-step bridge different))))
    (is (= [{:op :once}] (:attempts @(:state worker))))))

(deftest unsupported-or-absent-intents-never-contact-the-worker
  (testing "an unsupported committed intent becomes an explicit committed failure"
    (let [worker (scripted-worker [])
          other (assoc (intent 0 {:op :other}) :kind :other/effect)
          bridge (attach worker [other])
          result (effect-session/step! bridge
                                       (get-in (effect-session/branches bridge)
                                               [0 :branch]))]
      (is (true? (:committed? result)))
      (is (= :failed (get-in result [:delivery :status])))
      (is (= [] (:attempts @(:state worker))))))

  (testing "a branch with no intents is an ordinary settled commit"
    (let [worker (scripted-worker [])
          bridge (attach worker [])
          result (effect-session/step! bridge
                                       (get-in (effect-session/branches bridge)
                                               [0 :branch]))]
      (is (true? (:committed? result)))
      (is (= :ready (get-in result [:delivery :status])))
      (is (= [] (:attempts @(:state worker)))))))

(deftest close-is-idempotent-and-never-owns-the-worker
  (let [worker (scripted-worker [])
        bridge (attach worker [])
        first-close (effect-session/close! bridge)
        second-close (effect-session/close! bridge)]
    (is (= first-close second-close))
    (is (= :closed (:status first-close)))
    (is (= [] (:attempts @(:state worker))))))
