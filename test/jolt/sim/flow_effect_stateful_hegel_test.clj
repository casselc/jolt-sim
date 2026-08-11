(ns jolt.sim.flow-effect-stateful-hegel-test
  "Fast stateful model for shared REPL/Ripple flow-effect control.

  Generated cases use the production flow-effect bridge and UI-neutral view
  over a real cooperative Session. Only the borrowed retained-worker service
  is scripted. The real 20/20 retained outbox smoke remains the semantic oracle
  for HTTP/SQLite/TCP publication; this lane explores control-plane ordering
  without launching that stack once per generated operation."
  (:require [clojure.test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [hegel.stateful :as hs]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.flow-effect-view :as effect-view]
            [jolt.sim.kernel :as kernel]))

(def ^:private effect-kind :example.outbox/command)
(def ^:private actors [:repl :ripple])

(defn- violation! [origin expected actual]
  (throw
   (ex-info "Flow-effect stateful model diverged"
            {:type ::model-diverged
             :hegel/origin origin
             :expected expected
             :actual actual})))

(defn- require-equal! [origin expected actual]
  (when-not (= expected actual)
    (violation! origin expected actual))
  actual)

(defn- caught [f]
  (try
    {:value (f)}
    (catch :default error {:error error})))

(defn- intent [task-id]
  {:id [:jolt.sim.flow/intent :effect task-id 0]
   :kind effect-kind
   :payload {:op :scripted-effect :task task-id}
   :source {:cell :effect :message-id task-id :ordinal 0}})

(defn- intent-sim [task-count]
  {:tasks (into (sorted-map)
                (map (fn [task-id]
                       [task-id (kernel/runnable nil)])
                     (range task-count)))
   :world {:effect-intents []}
   :step (fn [{:keys [task world]} _]
           (-> (kernel/step-complete :done)
               (kernel/with-world
                (update world :effect-intents conj (intent task)))
               (kernel/at-site {:kind :test/flow-effect
                                :task task})))})

(defn- scripted-worker []
  (let [state
        (atom {:status :ready
               :next-sequence 0
               :uncertain-sequence nil
               :next-command-outcome nil
               :next-reconcile-outcome nil
               :attempts []
               :reconciliations []})
        settle!
        (fn [outcome sequence]
          (swap! state assoc
                 :status :ready
                 :next-sequence (inc sequence)
                 :uncertain-sequence nil)
          (case outcome
            :completed
            {:status :completed
             :sequence sequence
             :value {:accepted true :sequence sequence}}

            :application-failed
            {:status :failed
             :sequence sequence
             :error {:reason :scripted-application-failure}}

            (violation! "flow-effect-stateful/worker-definite-outcome"
                        #{:completed :application-failed}
                        outcome)))]
    {:state state
     :service
     {:command!
      (fn [command]
        (let [{:keys [next-sequence next-command-outcome]} @state]
          (swap! state
                 (fn [current]
                   (-> current
                       (assoc :next-command-outcome nil)
                       (update :attempts conj
                               {:sequence next-sequence
                                :command command}))))
          (case next-command-outcome
            :uncertain
            (do
              (swap! state assoc
                     :status :uncertain
                     :uncertain-sequence next-sequence)
              (throw
               (ex-info "Scripted command response was lost"
                        {:type ::transport-uncertain
                         :sequence next-sequence})))

            :completed
            (settle! next-command-outcome next-sequence)

            :application-failed
            (settle! next-command-outcome next-sequence)

            (violation! "flow-effect-stateful/command-not-programmed"
                        #{:completed :application-failed :uncertain}
                        next-command-outcome))))

      :reconcile!
      (fn []
        (let [{:keys [uncertain-sequence next-reconcile-outcome]} @state]
          (swap! state
                 (fn [current]
                   (-> current
                       (assoc :next-reconcile-outcome nil)
                       (update :reconciliations conj uncertain-sequence))))
          (case next-reconcile-outcome
            :still-uncertain
            (throw
             (ex-info "Scripted reconciliation response was lost"
                      {:type ::transport-uncertain
                       :sequence uncertain-sequence}))

            :completed
            (settle! next-reconcile-outcome uncertain-sequence)

            :application-failed
            (settle! next-reconcile-outcome uncertain-sequence)

            (violation! "flow-effect-stateful/reconcile-not-programmed"
                        #{:completed :application-failed :still-uncertain}
                        next-reconcile-outcome))))

      :snapshot
      (fn []
        (select-keys @state
                     [:status :next-sequence :uncertain-sequence]))}}))

(defn- remember [known actor previews]
  (update known actor
          (fn [branches]
            (vec (distinct (into (vec branches) (map :branch previews)))))))

(defn- note-actor [state actor]
  (-> state
      (update-in [:coverage :actors] conj actor)
      (update-in [:coverage :actor-handoffs]
                 + (if (and (:last-actor state)
                            (not= actor (:last-actor state)))
                     1
                     0))
      (assoc :last-actor actor)))

(defn- current-branches [{:keys [known revision]}]
  (vec
   (distinct
    (for [actor actors
          branch (get known actor)
          :when (= revision (:revision branch))]
      branch))))

(defn- actor-current-branches [{:keys [known revision]} actor]
  (vec (filter #(= revision (:revision %)) (get known actor))))

(defn- actors-with-current-branches [state]
  (vec (filter #(seq (actor-current-branches state %)) actors)))

(defn- stale-branches [{:keys [known revision]}]
  (vec
   (distinct
    (for [actor actors
          branch (get known actor)
          :when (< (:revision branch) revision)]
      branch))))

(defn- actor-stale-branches [{:keys [known revision]} actor]
  (vec (filter #(< (:revision %) revision) (get known actor))))

(defn- actors-with-stale-branches [state]
  (vec (filter #(seq (actor-stale-branches state %)) actors)))

(defn- expected-step-reconciliation [{:keys [commits]} branch]
  (let [committed (get commits (:revision branch))]
    (cond
      (nil? committed) :missing
      (= committed branch) :committed
      :else :different)))

(defn- read-previews [bridge actor]
  (if (= :repl actor)
    (effect-session/branches bridge)
    (:previews (effect-view/read-frame bridge 0))))

(defn- response-previews [actor result]
  (if (= :ripple actor)
    (vec (or (get-in result [:frame :previews]) []))
    []))

(defn- refresh-rule []
  (hs/rule
   :refresh
   {:precondition
    (fn [{:keys [bridge closed?]}]
      (and (not closed?)
           (= :ready (:status (effect-session/snapshot bridge)))))}
   (fn [{:keys [bridge worker] :as state}]
     (let [actor (h/draw! (g/sampled-from actors))
           before (effect-session/snapshot bridge)
           worker-before @worker
           previews (read-previews bridge actor)]
       (require-equal! "flow-effect-stateful/refresh-is-inert-bridge"
                       before (effect-session/snapshot bridge))
       (require-equal! "flow-effect-stateful/refresh-is-inert-worker"
                       worker-before @worker)
       (-> state
           (update :known remember actor previews)
           (note-actor actor))))))

(defn- commit-rule []
  (hs/rule
   :commit-current
   {:precondition
    (fn [{:keys [bridge closed?] :as state}]
      (and (not closed?)
           (= :ready (:status (effect-session/snapshot bridge)))
           (seq (current-branches state))))}
   (fn [{:keys [bridge worker] :as state}]
     (let [actor (h/draw! (g/sampled-from
                           (actors-with-current-branches state)))
           branch (h/draw! (g/sampled-from
                            (actor-current-branches state actor)))
           outcome (h/draw! (g/sampled-from
                             [:completed :application-failed :uncertain]))
           response-visible? (h/draw! (g/boolean))
           attempts-before (count (:attempts @worker))
           _ (swap! worker assoc :next-command-outcome outcome)
           result (if (= :repl actor)
                    (effect-session/step! bridge branch)
                    (effect-view/step-frame! bridge branch 0))
           revision (if (= :repl actor)
                      (:revision result)
                      (get-in result [:ack :revision]))
           delivery-status
           (if (= :repl actor)
             (get-in result [:delivery :status])
             (get-in result [:flow-effect :status]))]
       (require-equal! "flow-effect-stateful/commit-acknowledged"
                       true (:committed? result))
       (require-equal! "flow-effect-stateful/commit-revision"
                       (inc (:revision branch)) revision)
       (require-equal! "flow-effect-stateful/one-command-attempt"
                       (inc attempts-before)
                       (count (:attempts @worker)))
       (require-equal! "flow-effect-stateful/command-outcome"
                       (if (= :uncertain outcome) :uncertain :ready)
                       delivery-status)
       (let [committed (-> state
                           (assoc :revision revision)
                           (assoc-in [:commits (:revision branch)] branch)
                           (update-in [:coverage outcome] inc))
             refreshed (if (and response-visible? (= :ripple actor))
                         (update committed :known remember actor
                                 (response-previews actor result))
                         committed)
             observed (if response-visible?
                        refreshed
                        (-> refreshed
                            (update :lost-step-responses conj branch)
                            (update-in [:coverage :lost-response] inc)))]
         (note-actor observed actor))))))

(defn- stale-rule []
  (hs/rule
   :retry-stale
   {:precondition
    (fn [{:keys [bridge closed?] :as state}]
      (and (not closed?)
           (= :ready (:status (effect-session/snapshot bridge)))
           (seq (stale-branches state))))}
   (fn [{:keys [bridge worker] :as state}]
     (let [actor (h/draw! (g/sampled-from
                           (actors-with-stale-branches state)))
           branch (h/draw! (g/sampled-from
                            (actor-stale-branches state actor)))
           before (effect-session/snapshot bridge)
           worker-before @worker
           result (if (= :repl actor)
                    (caught #(effect-session/step! bridge branch))
                    {:value (effect-view/step-frame! bridge branch 0)})]
       (if (= :repl actor)
         (require-equal! "flow-effect-stateful/repl-stale-type"
                         :jolt.sim.session/stale-branch
                         (:type (ex-data (:error result))))
         (do
           (require-equal! "flow-effect-stateful/ripple-stale-status"
                           :stale (get-in result [:value :status]))
           (require-equal! "flow-effect-stateful/ripple-stale-not-committed"
                           false (get-in result [:value :committed?]))))
       (require-equal! "flow-effect-stateful/stale-is-inert-bridge"
                       before (effect-session/snapshot bridge))
       (require-equal! "flow-effect-stateful/stale-is-inert-worker"
                       worker-before @worker)
       (-> state
           (update-in [:coverage :stale] inc)
           (note-actor actor))))))

(defn- reconcile-effect-rule []
  (hs/rule
   :reconcile-effect
   {:precondition
    (fn [{:keys [bridge closed?]}]
      (and (not closed?)
           (= :uncertain (:status (effect-session/snapshot bridge)))))}
   (fn [{:keys [bridge worker] :as state}]
     (let [actor (h/draw! (g/sampled-from actors))
           outcome (h/draw! (g/sampled-from
                             [:completed :application-failed
                              :still-uncertain]))
           attempts-before (:attempts @worker)
           pending (get-in (effect-session/snapshot bridge)
                           [:effects :pending])
           _ (swap! worker assoc :next-reconcile-outcome outcome)
           result (if (= :repl actor)
                    (effect-session/reconcile! bridge)
                    (effect-view/reconcile-effect-frame! bridge 0))
           target (:target result)
           status (if (= :repl actor)
                    (:status result)
                    (get-in result [:flow-effect :status]))]
       (require-equal! "flow-effect-stateful/reconcile-never-republishes"
                       attempts-before (:attempts @worker))
       (require-equal! "flow-effect-stateful/reconcile-intent"
                       (:intent-id pending) (:intent-id target))
       (require-equal! "flow-effect-stateful/reconcile-sequence"
                       (get-in pending [:worker :uncertain-sequence])
                       (:sequence target))
       (require-equal! "flow-effect-stateful/reconcile-outcome"
                       (if (= :still-uncertain outcome) :uncertain :ready)
                       status)
       (-> state
           (update-in [:coverage :reconciled] inc)
           (update-in [:coverage
                       (case outcome
                         :completed :reconcile-completed
                         :application-failed
                         :reconcile-application-failed
                         :still-uncertain :reconcile-still-uncertain)]
                      inc)
           (note-actor actor))))))

(defn- reconciliation-candidate [state mode]
  (let [known (vec (distinct (mapcat #(get-in state [:known %]) actors)))
        committed (seq (:commits state))]
    (case mode
      :known (first known)
      :different (if committed
                   {:revision (ffirst committed) :action [:run 9999]}
                   {:revision (:revision state) :action [:run 9999]})
      :missing {:revision (:revision state) :action [:run 9998]})))

(defn- reconcile-step-rule []
  (hs/rule
   :reconcile-step
   (fn [{:keys [bridge worker] :as state}]
     (let [actor (h/draw! (g/sampled-from actors))
           mode (h/draw! (g/sampled-from [:known :different :missing]))
           branch (or (when (seq (:lost-step-responses state))
                        (h/draw! (g/sampled-from
                                  (:lost-step-responses state))))
                      (reconciliation-candidate state mode))
           before (effect-session/snapshot bridge)
           worker-before @worker
           expected (expected-step-reconciliation state branch)
           result (if (= :repl actor)
                    (effect-session/reconcile-step bridge branch)
                    (effect-view/reconcile-step-frame bridge branch 0))]
       (require-equal! "flow-effect-stateful/exact-step-reconciliation"
                       expected (:status result))
       (require-equal! "flow-effect-stateful/step-reconcile-is-inert-bridge"
                       before (effect-session/snapshot bridge))
       (require-equal! "flow-effect-stateful/step-reconcile-is-inert-worker"
                       worker-before @worker)
       (let [reconciled (update-in state
                                   [:coverage :step-reconciled] inc)
             observed (if (some #(= branch %)
                                (:lost-step-responses state))
                        (update-in reconciled
                                   [:coverage :lost-step-reconciled] inc)
                        reconciled)]
         (note-actor observed actor))))))

(defn- close-rule []
  (hs/rule
   :close
   {:precondition (fn [{:keys [closed?]}] (not closed?))}
   (fn [{:keys [bridge worker known] :as state}]
     (let [actor (h/draw! (g/sampled-from actors))
           worker-before @worker
           first-close (if (= :repl actor)
                         (effect-session/close! bridge)
                         (:flow-effect (effect-view/close-frame! bridge 0)))
           second-close (effect-session/close! bridge)
           third-close (effect-session/close! bridge)
           branch (first (get known actor))
           rejected (when branch
                      (caught #(effect-session/step! bridge branch)))]
       (require-equal! "flow-effect-stateful/close-idempotent"
                       second-close third-close)
       (require-equal! "flow-effect-stateful/first-close-status"
                       :closed (:status first-close))
       (require-equal! "flow-effect-stateful/close-status"
                       :closed (:status second-close))
       (require-equal! "flow-effect-stateful/close-borrows-worker"
                       worker-before @worker)
       (when branch
         (require-equal! "flow-effect-stateful/post-close-step-rejected"
                         :closed (:reason (ex-data (:error rejected)))))
       (-> state
           (assoc :closed? true)
           (update-in [:coverage :closed] inc)
           (note-actor actor))))))

(defn- model-agrees? [{:keys [bridge revision commits closed?]}]
  (let [snapshot (effect-session/snapshot bridge)]
    (and (= revision (get-in snapshot [:session :revision]))
         (= revision (get-in snapshot [:commits :count]))
         (= revision (count commits))
         (= closed? (:closed? snapshot))
         (= (if closed? :closed nil)
            (when closed? (:status snapshot))))))

(defn- publications-agree? [{:keys [bridge worker revision commits]}]
  (let [snapshot (effect-session/snapshot bridge)
        records (get-in snapshot [:effects :records])
        attempts (:attempts @worker)]
    (and (= revision (count records) (count attempts))
         (= revision (get-in snapshot [:effects :seen-intents]))
         (= (count records) (count (distinct (map :id records))))
         (every?
          true?
          (map-indexed
           (fn [index record]
             (let [branch (get commits index)
                   task-id (get-in branch [:action 1])
                   attempt (nth attempts index)]
               (and branch
                    (= index (:revision branch))
                    (= (inc index) (:revision record))
                    (= task-id (get-in record [:source :message-id]))
                    (= {:op :scripted-effect :task task-id}
                       (:payload record)
                       (:command attempt))
                    (= index (:sequence attempt)))))
           records)))))

(defn- ledger-agrees? [{:keys [bridge revision commits]}]
  (let [ledger (effect-session/journal bridge)
        commit-prefix (subvec ledger 0 revision)
        records (subvec ledger revision)]
    (and (= (* 2 revision) (count ledger))
         (= revision (count records))
         (every?
          true?
          (map-indexed
           (fn [index commit]
             (and (= :jolt.sim.flow-effect/committed (:event commit))
                  (= (inc index) (:revision commit))
                  (= (get commits index) (:branch commit))
                  (= [(:id (nth records index))] (:intent-ids commit))))
           commit-prefix)))))

(defn- uncertainty-agrees? [{:keys [bridge worker revision closed?]}]
  (let [snapshot (effect-session/snapshot bridge)
        worker-state @worker
        pending (get-in snapshot [:effects :pending])
        uncertain? (some? pending)
        expected-next (if uncertain? (dec revision) revision)]
    (and (= expected-next (:next-sequence worker-state))
         (= expected-next (get-in snapshot [:worker :next-sequence]))
         (= (when uncertain? expected-next)
            (:uncertain-sequence worker-state)
            (get-in snapshot [:worker :uncertain-sequence]))
         (= uncertain? (= :uncertain (:status worker-state)))
         (if closed?
           (= :closed (:status snapshot))
           (= (if uncertain? :uncertain :ready) (:status snapshot)))
         (or (nil? pending)
             (and (= :uncertain (:state pending))
                  (= expected-next
                     (get-in pending [:worker :uncertain-sequence])))))))

(defn- settled-receipts-agree? [{:keys [bridge]}]
  (every?
   (fn [record]
     (case (:state record)
       :uncertain (nil? (:status record))
       :settled (and (contains? #{:completed :failed} (:status record))
                     (= (:status record)
                        (get-in record [:receipt :status])))
       false))
   (get-in (effect-session/snapshot bridge) [:effects :records])))

(defn- coverage-score [coverage]
  (+ (count (:actors coverage))
     (min 2 (:actor-handoffs coverage))
     (if (pos? (:stale coverage)) 2 0)
     (if (pos? (:lost-response coverage)) 2 0)
     (if (pos? (:uncertain coverage)) 2 0)
     (if (pos? (:reconciled coverage)) 2 0)
     (if (pos? (:reconcile-completed coverage)) 1 0)
     (if (pos? (:reconcile-application-failed coverage)) 1 0)
     (if (pos? (:reconcile-still-uncertain coverage)) 1 0)
     (if (pos? (:step-reconciled coverage)) 1 0)
     (if (pos? (:lost-step-reconciled coverage)) 2 0)
     (if (pos? (:closed coverage)) 1 0)))

(defn- observed-features [revision coverage]
  (reduce (fn [features [present? feature]]
            (if present? (conj features feature) features))
          (set (:actors coverage))
          [[(pos? revision) :commit]
           [(pos? (:actor-handoffs coverage)) :actor-handoff]
           [(pos? (:completed coverage)) :completed]
           [(pos? (:application-failed coverage)) :application-failed]
           [(pos? (:stale coverage)) :stale]
           [(pos? (:lost-response coverage)) :lost-response]
           [(pos? (:uncertain coverage)) :uncertain]
           [(pos? (:reconciled coverage)) :reconciled]
           [(pos? (:reconcile-completed coverage)) :reconcile-completed]
           [(pos? (:reconcile-application-failed coverage))
            :reconcile-application-failed]
           [(pos? (:reconcile-still-uncertain coverage))
            :reconcile-still-uncertain]
           [(pos? (:step-reconciled coverage)) :step-reconciled]
           [(pos? (:lost-step-reconciled coverage))
            :lost-step-reconciled]
           [(pos? (:closed coverage)) :closed]]))

(defn- run-case! [run-coverage]
  (let [task-count (h/draw! (g/integer 2 4) "flow-task-count")
        scripted (scripted-worker)
        bridge (effect-session/attach!
                {:sim (intent-sim task-count)
                 :worker (:service scripted)
                 :effect-kind effect-kind})
        repl-previews (effect-session/branches bridge)
        ripple-previews (:previews (effect-view/read-frame bridge 0))
        final
        (hs/run!
         {:initial-state
          {:bridge bridge
           :worker (:state scripted)
           :revision 0
           :commits (sorted-map)
           :known {:repl (mapv :branch repl-previews)
                   :ripple (mapv :branch ripple-previews)}
           :lost-step-responses []
           :closed? false
           :last-actor nil
           :coverage {:actors #{}
                      :actor-handoffs 0
                      :stale 0
                      :lost-response 0
                      :completed 0
                      :application-failed 0
                      :uncertain 0
                      :reconciled 0
                      :reconcile-completed 0
                      :reconcile-application-failed 0
                      :reconcile-still-uncertain 0
                      :step-reconciled 0
                      :lost-step-reconciled 0
                      :closed 0}}
          :rules [(refresh-rule)
                  (commit-rule)
                  (stale-rule)
                  (reconcile-effect-rule)
                  (reconcile-step-rule)
                  (close-rule)]
          :invariants
          [(hs/invariant :model-agrees model-agrees?)
           (hs/invariant :one-publication-per-commit publications-agree?)
           (hs/invariant :exact-commit-ledger ledger-agrees?)
           (hs/invariant :uncertainty-is-singleton uncertainty-agrees?)
           (hs/invariant :definite-receipts-are-settled
                         settled-receipts-agree?)]})]
    (h/target! (coverage-score (:coverage final))
               "flow-effect-control-coverage")
    (h/target! (get-in final [:coverage :actor-handoffs])
               "flow-effect-actor-handoffs")
    ;; This run-wide observation is intentionally downstream of every case and
    ;; is never consulted by generation, rule preconditions, or invariants. It
    ;; therefore cannot change a case outcome or its shrink path, but lets the
    ;; enclosing test reject a green campaign that never exercised a claimed
    ;; control-plane boundary.
    (swap! run-coverage into
           (observed-features (:revision final) (:coverage final)))
    nil))

(def ^:private hegel-run-opts
  {:test-cases 200
   :seed 2
   :database ""
   :report-multiple-failures? true
   :verbosity :quiet})

(deftest shared-repl-ripple-flow-effect-state-machine
  (let [run-coverage (atom #{})
        result (h/run-test! hegel-run-opts
                            (fn [_] (run-case! run-coverage)))
        required-coverage
        #{:repl :ripple :actor-handoff :commit :completed
          :application-failed :stale :lost-response :uncertain :reconciled
          :reconcile-completed :reconcile-application-failed
          :reconcile-still-uncertain :step-reconciled
          :lost-step-reconciled :closed}]
    (is (true? (:passed? result))
        (pr-str (select-keys result
                             [:status :seed :n-failures :flaky? :failures
                              :final :observed-failures])))
    (is (false? (:flaky? result)))
    (is (pos? (:valid-test-cases result)))
    (is (= 0 (:invalid-test-cases result)))
    (is (< (:overrun-test-cases result)
           (:valid-test-cases result))
        (pr-str (select-keys result
                             [:valid-test-cases :overrun-test-cases])))
    (is (every? @run-coverage required-coverage)
        (pr-str {:missing (vec (sort (remove @run-coverage
                                             required-coverage)))
                 :observed (vec (sort @run-coverage))}))))
