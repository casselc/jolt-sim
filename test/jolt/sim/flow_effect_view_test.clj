(ns jolt.sim.flow-effect-view-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.flow-effect-view :as view]
            [jolt.sim.kernel :as kernel]))

(def ^:private step-frame-ops-var
  (resolve 'jolt.sim.flow-effect-view/step-frame*))

(def ^:private reconcile-effect-frame-ops-var
  (resolve 'jolt.sim.flow-effect-view/reconcile-effect-frame*))

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

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
               (kernel/with-world (update world :effect-intents into intents))
               (kernel/at-site {:kind :test/effect})))})

(defn- completed [sequence value]
  {:receipt {:status :completed :sequence sequence :value value}})

(defn- application-failed [sequence reason]
  {:receipt {:status :failed :sequence sequence :error {:reason reason}}})

(defn- scripted-worker [commands reconciliations]
  (let [commands (atom (vec commands))
        reconciliations (atom (vec reconciliations))
        state (atom {:status :ready
                     :next-sequence 0
                     :uncertain-sequence nil
                     :command-calls []
                     :reconcile-calls 0})
        apply-outcome!
        (fn [outcome]
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
                              {:type ::transport :sequence sequence})))

            :else
            (do
              (swap! state assoc :status :failed :uncertain-sequence nil)
              (throw (ex-info "scripted publication failure"
                              {:type ::transport})))))]
    {:state state
     :service
     {:command!
      (fn [command]
        (swap! state update :command-calls conj command)
        (let [outcome (first @commands)]
          (swap! commands #(vec (rest %)))
          (apply-outcome! outcome)))
      :reconcile!
      (fn []
        (swap! state update :reconcile-calls inc)
        (let [outcome (first @reconciliations)]
          (swap! reconciliations #(vec (rest %)))
          (apply-outcome! outcome)))
      :snapshot (fn [] @state)}}))

(defn- attach [worker intents]
  (effect-session/attach!
   {:sim (intent-sim intents)
    :worker (:service worker)
    :effect-kind :example.outbox/command}))

(deftest frames-preview-without-publication-and-commit-exactly-once
  (let [command {:op :submit :request-id "req-1"}
        worker (scripted-worker [(completed 0 {:http-status 201})] [])
        bridge (attach worker [(intent 0 command)])
        frame (view/read-frame bridge 0)
        branch (first (:branches frame))]
    (is (= :frame (:jolt.sim.session-view/type frame)))
    (is (= :jolt.sim.kind/flow-effect-session-frame (:kind frame)))
    (is (= [] (:command-calls @(:state worker))))
    (is (= 0 (:reconcile-calls @(:state worker))))
    (is (true? (get-in frame [:flow-effect :commands :step?])))
    (is (= {:worker :borrowed}
           (get-in frame [:flow-effect :ownership])))

    (let [result (view/step-frame! bridge branch 0)]
      (is (= :committed (:status result)))
      (is (true? (:committed? result)))
      (is (= branch (get-in result [:ack :branch])))
      (is (= [command] (:command-calls @(:state worker))))
      (is (= :settled
             (get-in result [:flow-effect :effects :records 0 :state]))))

    (testing "the stale branch is never retried or republished"
      (let [result (view/step-frame! bridge branch 0)]
        (is (= :stale (:status result)))
        (is (false? (:committed? result)))
        (is (= [command] (:command-calls @(:state worker))))))))

(deftest application-failure-is-a-definite-settled-effect
  (let [worker (scripted-worker [(application-failed 0 :ack-mismatch)] [])
        bridge (attach worker [(intent 0 {:op :deliver})])
        branch (first (:branches (view/read-frame bridge 0)))
        result (view/step-frame! bridge branch 0)]
    (is (= :committed (:status result)))
    (is (= :ready (get-in result [:flow-effect :status])))
    (is (= :settled
           (get-in result [:flow-effect :effects :records 0 :state])))
    (is (= :failed
           (get-in result [:flow-effect :effects :records 0 :status])))
    (is (= :ack-mismatch
           (get-in result
                   [:flow-effect :effects :records 0 :receipt :error :reason])))))

(deftest uncertainty-disables-step-and-exact-reconcile-never-republishes
  (let [command {:op :submit :request-id "req-uncertain"}
        worker (scripted-worker [{:uncertain :receipt-timeout}]
                                [(completed 0 {:http-status 201})])
        bridge (attach worker [(intent 0 command)])
        branch (first (:branches (view/read-frame bridge 0)))
        committed (view/step-frame! bridge branch 0)
        uncertain-frame (:frame committed)
        journal-before (:journal uncertain-frame)
        record-before (get-in uncertain-frame
                              [:flow-effect :effects :records 0])]
    (is (true? (:committed? committed)))
    (is (= :uncertain (get-in committed [:flow-effect :status])))
    (is (false? (get-in uncertain-frame
                        [:flow-effect :commands :step?])))
    (is (true? (get-in uncertain-frame
                       [:flow-effect :commands :reconcile-effect?])))
    (is (= [] (:branches uncertain-frame)))
    (is (= [] (:previews uncertain-frame)))
    (let [rejected (caught-data #(view/step-frame! bridge branch 0))]
      (is (= :jolt.sim.flow-effect-session/rejected (:type rejected)))
      (is (= :uncertain (:reason rejected)))
      (is (= [command] (:command-calls @(:state worker)))))

    (let [reconciled (view/reconcile-effect-frame! bridge 0)
          final-frame (:frame reconciled)]
      (is (= :effect-reconcile-result
             (:jolt.sim.flow-effect-view/type reconciled)))
      (is (true? (:flow-committed? reconciled)))
      (is (= :settled (:status reconciled)))
      (is (= 0 (get-in reconciled [:target :sequence])))
      (is (= :settled (get-in reconciled [:effect :state])))
      (is (= :ready (get-in reconciled [:flow-effect :status])))
      (is (= [command] (:command-calls @(:state worker))))
      (is (= 1 (:reconcile-calls @(:state worker))))
      (is (= (:entries journal-before)
             (get-in final-frame [:journal :entries])))
      (is (= (:count journal-before)
             (get-in final-frame [:journal :count])))
      (is (not= record-before
                (get-in final-frame [:flow-effect :effects :records 0]))))))

(deftest step-reconciliation-is-read-only
  (let [command {:op :once}
        worker (scripted-worker [(completed 0 {:ok true})] [])
        bridge (attach worker [(intent 0 command)])
        branch (first (:branches (view/read-frame bridge 0)))]
    (let [missing (view/reconcile-step-frame bridge branch 0)]
      (is (= :missing (:status missing)))
      (is (false? (:committed? missing))))
    (view/step-frame! bridge branch 0)
    (let [committed (view/reconcile-step-frame bridge branch 0)]
      (is (= :committed (:status committed)))
      (is (true? (:committed? committed))))
    (is (= [command] (:command-calls @(:state worker))))
    (is (= 0 (:reconcile-calls @(:state worker))))))

(deftest reconciliation-labels-the-target-captured-under-the-bridge-lock
  (let [branch {:revision 1 :action [:run 0]}
        intent-a [:jolt.sim.flow/intent :effect 0 0]
        intent-b [:jolt.sim.flow/intent :effect 1 0]
        record-a {:id [:jolt.sim.flow-effect/commit 1 intent-a]
                  :intent-id intent-a :state :settled :status :completed}
        record-b {:id [:jolt.sim.flow-effect/commit 2 intent-b]
                  :intent-id intent-b :state :settled :status :completed}
        before {:status :uncertain
                :closed? false
                :session {:revision 1 :status :running
                          :projection {} :branches [] :journal {:count 1}}
                :effects {:seen-intents 1
                          :records [(assoc record-a :state :uncertain)]
                          :pending (assoc record-a :state :uncertain)
                          :remaining []}
                :commits {:count 1}
                :worker {:status :uncertain :next-sequence 0
                         :uncertain-sequence 0}}
        commit {:event :jolt.sim.flow-effect/committed
                :revision 1 :branch branch
                :intent-ids [(:id record-a)]}
        ops {:snapshot (fn [] before)
             :previews (fn [] [])
             :journal (fn [] [commit (assoc record-a :state :uncertain)])
             ;; This represents another caller settling A and advancing to B
             ;; after the validation read but before our bridge operation.
             :reconcile-effect!
             (fn []
               {:status :ready
                :target {:id (:id record-b)
                         :intent-id intent-b
                         :sequence 1}
                :effects {:seen-intents 2
                          :records [record-a record-b]
                          :pending nil :remaining []}
                :worker {:status :ready :next-sequence 2
                         :uncertain-sequence nil}})}
        result (@reconcile-effect-frame-ops-var ops 0)]
    (is (= {:intent-id intent-b :sequence 1} (:target result)))
    (is (= (:id record-b) (get-in result [:effect :id])))
    (is (= :settled (:status result)))))

(deftest committed-ack-survives-post-commit-frame-error
  (let [committed? (atom false)
        revision-call (atom 0)
        branch {:revision 0 :action [:run 0]}
        base-snapshot
        (fn [revision]
          {:status :ready
           :closed? false
           :session {:revision revision
                     :status :completed
                     :projection {:revision revision}
                     :branches [{:revision revision :action [:run 0]}]
                     :journal {:count (inc revision)}}
           :effects {:seen-intents 0 :records []
                     :pending nil :remaining []}
           :commits {:count (if @committed? 1 0)}
           :worker {:status :ready :next-sequence 0
                    :uncertain-sequence nil}})
        ops {:snapshot
             (fn []
               (if @committed?
                 (base-snapshot (if (odd? (swap! revision-call inc)) 1 2))
                 (base-snapshot 0)))
             :previews
             (fn []
               [{:branch (if @committed?
                           {:revision 1 :action [:run 0]}
                           branch)
                 :status :completed :projection nil :events []}])
             :journal (fn [] (if @committed?
                               [{:event :jolt.sim.flow-effect/committed
                                 :revision 1 :branch branch :intent-ids []}]
                               []))
             :step!
             (fn [_]
               (reset! committed? true)
               {:committed? true
                :branch branch
                :revision 1
                :delivery {:status :uncertain
                           :effects {:seen-intents 1 :records []
                                     :pending {:intent-id :intent-1}
                                     :remaining []}
                           :worker {:status :uncertain :next-sequence 0
                                    :uncertain-sequence 0}}})}
        result (@step-frame-ops-var ops branch 0)]
    (is (= :committed (:status result)))
    (is (true? (:committed? result)))
    (is (= {:branch branch :revision 1} (:ack result)))
    (is (= :uncertain (get-in result [:flow-effect :status])))
    (is (nil? (:frame result)))
    (is (= :jolt.sim.flow-effect-view/coherence-failed
           (get-in result [:frame-error :type])))))

(deftest close-is-idempotent-and-never-operates-on-the-borrowed-worker
  (let [worker (scripted-worker [] [])
        bridge (attach worker [])
        first-close (view/close-frame! bridge 0)
        second-close (view/close-frame! bridge 0)]
    (is (= first-close second-close))
    (is (= :closed (:status first-close)))
    (is (= :closed (get-in first-close [:flow-effect :status])))
    (is (= {:ownership :borrowed :operation :none}
           (:worker first-close)))
    (is (= :closed (get-in first-close [:frame :flow-effect :status])))
    (is (= [] (:command-calls @(:state worker))))
    (is (= 0 (:reconcile-calls @(:state worker))))))
