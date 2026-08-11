(ns jolt.sim.command-cell-session-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.command-cell-session :as command-session]
            [jolt.sim.command-cell-view :as command-view]
            [jolt.sim.flow :as flow]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench :as workbench]
            [jolt.sim.workbench-session :as workbench-session]))

(def submit-schema
  [:map {:closed true}
   [:op [:= :submit]]
   [:value :int]])

(def deliver-schema
  [:map {:closed true}
   [:op [:= :deliver]]])

(def projected-schema
  [:map {:closed true}
   [:answer :int]])

(def descriptors
  {:test/submit
   {:effect-kind :test/command
    :input-schema submit-schema
    :output-schema projected-schema
    :projector :test/project
    :suggested-kind nil}
   :test/deliver
   {:effect-kind :test/command
    :input-schema deliver-schema
    :output-schema projected-schema
    :projector :test/project
    :suggested-kind nil}})

(defn- command-sim [cell-id input input-schema]
  (flow/compile-workflow
   {:cells {:command cell-id}
    :edges []
    :start :command
    :input input
    :resources {}}
   {cell-id
    {:handler :test/emit-command
     :schema {:input input-schema
              :output [:map {:closed true}]}
     :emits #{:test/command}}}
   {:test/emit-command
    (fn [_ state command]
      {:state (inc (or state 0))
       :data {}
       :intents [{:kind :test/command :payload command}]})}))

(defn- compiler [calls cell-id input-schema]
  (fn [input]
    (swap! calls conj [cell-id input])
    (command-sim cell-id input input-schema)))

(defn- completed [sequence value]
  {:receipt {:status :completed :sequence sequence :value value}})

(defn- failed [sequence reason]
  {:receipt {:status :failed :sequence sequence :error {:reason reason}}})

(defn- scripted-worker
  ([commands] (scripted-worker commands []))
  ([commands reconciliations]
   (let [commands (atom (vec commands))
         reconciliations (atom (vec reconciliations))
         state (atom {:status :ready
                      :next-sequence 0
                      :uncertain-sequence nil
                      :command-calls 0
                      :reconcile-calls 0
                      :snapshot-calls 0
                      :published []})
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
               (throw (ex-info "uncertain" {:reason (:uncertain outcome)})))

             :else
             (do
               (swap! state assoc :status :failed :uncertain-sequence nil)
               (throw (ex-info "failed" {:reason (:failure outcome)})))))]
     {:state state
      :service
      {:command!
       (fn [command]
         (swap! state #(-> %
                           (update :command-calls inc)
                           (update :published conj command)))
         (let [outcome (first @commands)]
           (swap! commands #(vec (rest %)))
           (apply-outcome! outcome)))
       :reconcile!
       (fn []
         (swap! state update :reconcile-calls inc)
         (let [outcome (first @reconciliations)]
           (swap! reconciliations #(vec (rest %)))
           (apply-outcome! outcome)))
       :snapshot
       (fn []
         (swap! state update :snapshot-calls inc)
         (select-keys @state
                      [:status :next-sequence :uncertain-sequence]))}})))

(defn- caught-data [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- index-of [values target]
  (or (first (keep-indexed #(when (= target %2) %1) values)) -1))

(defn- start-session
  ([worker] (start-session worker {}))
  ([worker {:keys [projector workbench tap! compiler-calls evidence-stream-id]
            :or {projector (fn [value] {:answer (:answer value)})
                 compiler-calls (atom [])
                 evidence-stream-id "test"}}]
   (let [items (or workbench (workbench-session/start))]
     {:compiler-calls compiler-calls
      :items items
      :session
      (command-session/start
       {:descriptors descriptors
        :evidence-stream-id evidence-stream-id
        :trusted
        (cond->
         {:compilers
          {:test/submit (compiler compiler-calls :test/submit submit-schema)
           :test/deliver (compiler compiler-calls :test/deliver deliver-schema)}
          :workers {:test/submit (:service worker)
                    :test/deliver (:service worker)}
          :projectors {:test/project projector}
          :workbench (if (map? items)
                       items
                       (command-session/workbench-service items))}
          tap! (assoc :tap! tap!))})})))

(defn- prepare [session revision cell-id input]
  (command-session/prepare!
   session {:revision revision :cell-id cell-id :input input}))

(deftest catalog-boundaries-prevalidate-before-trusted-compile
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        calls (atom [])
        {:keys [session]} (start-session worker {:compiler-calls calls})]
    (is (= [:test/deliver :test/submit]
           (mapv :id (command-session/catalog session))))
    (is (every? #(not-any? fn? (tree-seq coll? seq %))
                (command-session/catalog session)))
    (is (some? (caught-data #(prepare session 0 :test/submit
                                     {:op :submit :value "wrong"}))))
    (is (= [] @calls))
    (is (= 0 (:revision (command-session/snapshot session))))
    (prepare session 0 :test/submit {:op :submit :value 7})
    (is (= [[:test/submit {:op :submit :value 7}]] @calls))))

(deftest prepare-preview-datafy-and-nav-never-call-the-worker
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        {:keys [session]} (start-session worker)
        prepared (prepare session 0 :test/submit {:op :submit :value 7})
        previews (command-session/branches session)
        summary (datafy/datafy session)]
    (is (= :prepared (:status prepared)))
    (is (= "test" (:evidence-stream-id prepared)))
    (is (= 1 (count previews)))
    (is (= 1 (get-in previews [0 :coordinate :revision])))
    (is (= (get-in previews [0 :branch])
           (get-in previews [0 :coordinate :branch])))
    (is (= {:command-calls 0 :reconcile-calls 0 :snapshot-calls 0}
           (select-keys @(:state worker)
                        [:command-calls :reconcile-calls :snapshot-calls])))
    (is (= 2 (count (protocols/nav session :catalog (:catalog summary)))))
    (is (= previews
           (:branches (datafy/nav session :active (:active summary)))))
    (is (= 0 (:snapshot-calls @(:state worker))))))

(deftest evidence-capture-stays-in-revision-order-under-concurrency
  (let [worker (scripted-worker [(completed 0 {:answer 7})
                                 (completed 1 {:answer 8})])
        document-session (workbench-session/start)
        commit-entered (promise)
        release-commit (promise)
        evidence
        {:append-item!
         (fn [item]
           (when (and (= "command-cell/ordered/commit" (:id item))
                      (= 2 (:source-revision item)))
             (deliver commit-entered true)
             @release-commit)
           (workbench-session/append-item! document-session item))}
        {:keys [session]}
        (start-session worker {:workbench evidence
                               :evidence-stream-id "ordered"})
        _ (prepare session 0 :test/submit {:op :submit :value 7})
        first-coordinate
        (get-in (command-session/branches session) [0 :coordinate])
        first-step (future (command-session/step! session first-coordinate))]
    (is (= true (deref commit-entered 1000 ::timeout)))
    (let [later
          (future
            (prepare session 2 :test/deliver {:op :deliver})
            (command-session/step!
             session (get-in (command-session/branches session)
                             [0 :coordinate])))]
      ;; The rev-2 mutation still owns evidence serialization. Rev 3/4 cannot
      ;; enter even though the authoritative rev-2 worker result is definite.
      (is (= ::waiting (deref later 50 ::waiting)))
      (deliver release-commit true)
      (let [first-result (deref first-step 1000 ::timeout)
            later-result (deref later 1000 ::timeout)
            document (workbench-session/document document-session)
            journal (:jolt.sim.workbench/journal document)
            coordinates (mapv (fn [entry]
                                [(get-in entry [:item :id])
                                 (get-in entry [:item :source-revision])])
                              (filter #(= :item/append (:op %)) journal))]
        (is (nil? (:capture-errors first-result)))
        (is (nil? (:capture-errors later-result)))
        (is (some #{["command-cell/ordered/commit" 2]} coordinates))
        (is (some #{["command-cell/ordered/commit" 4]} coordinates))
        (is (< (index-of coordinates ["command-cell/ordered/commit" 2])
               (index-of coordinates ["command-cell/ordered/commit" 4])))))))

(deftest mutation-boundary-is-explicitly-non-reentrant
  (let [worker (scripted-worker [])
        document-session (workbench-session/start)
        session* (atom nil)
        reentrant-result (promise)
        evidence
        {:append-item!
         (fn [item]
           (when (= "command-cell/reentrant/prepare" (:id item))
             (deliver reentrant-result
                      (caught-data
                       #(prepare @session* 1 :test/deliver {:op :deliver}))))
           (workbench-session/append-item! document-session item))}
        started (start-session worker {:workbench evidence
                                       :evidence-stream-id "reentrant"})
        _ (reset! session* (:session started))
        result (prepare (:session started) 0 :test/submit
                        {:op :submit :value 7})]
    (is (= :prepared (:status result)))
    (is (nil? (:capture-errors result)))
    (is (= :reentrant-mutation (:reason @reentrant-result)))
    (is (= 1 (:revision (command-session/snapshot (:session started)))))
    (is (= 0 (:command-calls @(:state worker))))))

(deftest exact-outer-and-inner-coordinates-prevent-republication
  (let [worker (scripted-worker [(completed 0 {:answer 7})
                                 (completed 1 {:answer 8})])
        {:keys [session compiler-calls]} (start-session worker)
        _ (prepare session 0 :test/submit {:op :submit :value 7})
        coordinate (get-in (command-session/branches session) [0 :coordinate])
        wrong {:revision (:revision coordinate)
               :branch {:revision (get-in coordinate [:branch :revision])
                        :action [:not-enabled 0]}}
        session-before (command-session/snapshot session)
        worker-before (select-keys @(:state worker)
                                   [:status :next-sequence
                                    :uncertain-sequence :command-calls
                                    :reconcile-calls :published])
        rejected (caught-data #(command-session/step! session wrong))]
    ;; The outer coordinate is current, but the inner action is not a member of
    ;; the Session's enabled set. The existing kernel owns that fail-closed
    ;; contract; CommandCellSession must not translate it into a stale result.
    (is (= :jolt.sim.kernel/invalid-machine-action (:type rejected)))
    (is (= session-before (command-session/snapshot session)))
    (is (= worker-before
           (select-keys @(:state worker)
                        [:status :next-sequence :uncertain-sequence
                         :command-calls :reconcile-calls :published])))
    (is (= 0 (:command-calls @(:state worker))))
    (let [result (command-session/step! session coordinate)]
      (is (true? (:committed? result)))
      (is (= :projected (get-in result [:projection :status])))
      (is (= 1 (:command-calls @(:state worker)))))
    (is (= :stale-revision
           (:reason (caught-data #(command-session/step! session coordinate)))))
    (is (= :stale-revision
           (:reason (caught-data #(prepare session 1 :test/deliver
                                          {:op :deliver})))))
    (is (= 1 (count @compiler-calls)))
    (prepare session 2 :test/deliver {:op :deliver})
    (is (= :stale-revision
           (:reason (caught-data #(command-session/step! session coordinate)))))
    (is (= 1 (:command-calls @(:state worker))))))

(deftest uncertainty-blocks-replacement-and-reconciliation-never-republishes
  (let [worker (scripted-worker [{:uncertain :deadline}]
                                [(completed 0 {:answer 9})])
        {:keys [session]} (start-session worker)
        _ (prepare session 0 :test/submit {:op :submit :value 9})
        coordinate (get-in (command-session/branches session) [0 :coordinate])
        result (command-session/step! session coordinate)]
    (is (= :uncertain (:status result)))
    (is (= 1 (:command-calls @(:state worker))))
    (is (= :uncertain
           (:reason (caught-data #(prepare session 2 :test/deliver
                                          {:op :deliver})))))
    (let [settled (command-session/reconcile! session)]
      (is (= :ready (:status settled)))
      (is (= :projected (get-in settled [:projection :status])))
      (is (= 1 (:command-calls @(:state worker))))
      (is (= 1 (:reconcile-calls @(:state worker)))))
    (is (= :prepared
           (:status (prepare session 3 :test/deliver {:op :deliver}))))))

(deftest output-validation-fails-closed-after-a-definite-commit
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        {:keys [session]}
        (start-session worker {:projector (fn [_] {:answer "wrong"})})
        _ (prepare session 0 :test/submit {:op :submit :value 7})
        coordinate (get-in (command-session/branches session) [0 :coordinate])
        result (command-session/step! session coordinate)]
    (is (true? (:committed? result)))
    (is (= :ready (:status result)))
    (is (= :failed (get-in result [:projection :status])))
    (is (= :jolt.sim.schema/invalid-value
           (get-in result [:projection :error :type])))
    (is (= 1 (:command-calls @(:state worker))))
    ;; The external result is definite, so a corrected/new cell can proceed.
    (is (= :prepared
           (:status (prepare session 2 :test/deliver {:op :deliver}))))))

(deftest failed-receipts-remain-definite-and-skip-the-projector
  (let [projector-calls (atom 0)
        worker (scripted-worker [(failed 0 :rejected)])
        {:keys [session]}
        (start-session worker
                       {:projector (fn [_]
                                     (swap! projector-calls inc)
                                     {:answer 0})})
        _ (prepare session 0 :test/submit {:op :submit :value 7})
        result (command-session/step!
                session (get-in (command-session/branches session)
                                [0 :coordinate]))]
    (is (true? (:committed? result)))
    (is (= :ready (:status result)))
    (is (nil? (:projection result)))
    (is (= :failed
           (get-in result [:delivery :effects :records 0 :status])))
    (is (= 0 @projector-calls))
    (is (= :prepared
           (:status (prepare session 2 :test/deliver {:op :deliver}))))))

(deftest ui-neutral-command-cell-frames-expose-only-canonical-data
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        {:keys [session]} (start-session worker)
        catalog (command-view/catalog-frame session)
        initial (command-view/read-frame session)
        prepared
        (command-view/prepare-frame!
         session {:revision 0 :cell-id :test/submit
                  :input {:op :submit :value 7}})
        previewed (command-view/read-frame session)]
    (is (= :catalog-frame (:jolt.sim.command-cell-view/type catalog)))
    (is (= [:test/deliver :test/submit]
           (mapv :id (:cells catalog))))
    (is (= [] (:branches initial)))
    (is (= :prepared (:status prepared)))
    (is (= 1 (count (:branches previewed))))
    (is (not-any? fn? (tree-seq coll? seq [catalog initial prepared previewed])))
    (is (= 0 (:command-calls @(:state worker))))))

(deftest command-cell-view-keeps-a-definite-result-when-refresh-fails
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        {:keys [session]} (start-session worker)
        _ (prepare session 0 :test/submit {:op :submit :value 7})
        coordinate (get-in (command-session/branches session) [0 :coordinate])
        result
        (with-redefs [command-view/read-frame
                      (fn [_]
                        (throw (ex-info "simulated concurrent refresh failure"
                                        {:type :test/refresh-failed})))]
          (command-view/step-frame! session coordinate))]
    (is (true? (get-in result [:result :committed?])))
    (is (= :ready (:status result)))
    (is (nil? (:frame result)))
    (is (= :post-operation-frame (get-in result [:frame-error :phase])))
    (is (= 1 (:command-calls @(:state worker))))))

(deftest failed-capture-and-tap-cannot-obscure-definite-outcomes
  (let [worker (scripted-worker [(completed 0 {:answer 7})])
        captures (atom 0)
        taps (atom 0)
        broken-workbench
        {:append-item!
         (fn [_]
           (swap! captures inc)
           (throw (ex-info "capture failed" {:reason :disk-full})))}
        {:keys [session]}
        (start-session worker
                       {:workbench broken-workbench
                        :tap! (fn [_]
                                (swap! taps inc)
                                (throw (ex-info "tap failed"
                                                {:reason :observer-down})))})
        prepared (prepare session 0 :test/submit {:op :submit :value 7})
        coordinate (get-in (command-session/branches session) [0 :coordinate])
        result (command-session/step! session coordinate)]
    (is (= :prepared (:status prepared)))
    (is (= 1 (count (:capture-errors prepared))))
    (is (= :observer-down (get-in prepared [:tap-error :reason])))
    (is (true? (:committed? result)))
    (is (= :ready (:status result)))
    (is (= 2 (count (:capture-errors result))))
    (is (= :observer-down (get-in result [:tap-error :reason])))
    (is (= 1 (:command-calls @(:state worker))))
    (is (= 3 @captures))
    (is (= 2 @taps))))

(deftest evidence-items-retain-immutable-source-and-stable-coordinates
  (let [projection-source (atom 11)
        worker (scripted-worker [(completed 0 {:answer 11})])
        {:keys [session items]}
        (start-session worker
                       {:projector (fn [_] {:answer @projection-source})})
        input {:op :submit :value 11}
        _ (prepare session 0 :test/submit input)
        result (command-session/step!
                session (get-in (command-session/branches session)
                                [0 :coordinate]))
        _ (reset! projection-source 99)
        document (workbench-session/document items)
        prepared (workbench/item document "command-cell/test/prepare" 1)
        committed (workbench/item document "command-cell/test/commit" 2)
        projected (workbench/item document
                                  "command-cell/test/projected-receipt" 2)]
    (is (= input
           (get (trace/restore-value (:value prepared)) :input)))
    (is (= true
           (:committed? (trace/restore-value (:value committed)))))
    (is (= {:answer 11} (trace/restore-value (:value projected))))
    (is (= {:event :projected-receipt
            :evidence-stream-id "test"
            :revision 2
            :cell-id :test/submit}
           (trace/restore-value
            (get-in projected [:provenance :coordinate]))))
    (is (= {:answer 11} (get-in result [:projection :value])))))

(deftest distinct-evidence-streams-coexist-and-reused-incarnations-fail-explicitly
  (let [items (workbench-session/start)
        worker-a (scripted-worker [])
        worker-b (scripted-worker [])
        a (start-session worker-a {:workbench items
                                   :evidence-stream-id "alpha"})
        b (start-session worker-b {:workbench items
                                   :evidence-stream-id "beta"})
        result-a (prepare (:session a) 0 :test/deliver {:op :deliver})
        result-b (prepare (:session b) 0 :test/deliver {:op :deliver})
        restored (workbench/read-edn (workbench-session/canonical-edn items))]
    (is (nil? (:capture-errors result-a)))
    (is (nil? (:capture-errors result-b)))
    (is (some? (workbench/item restored "command-cell/alpha/prepare" 1)))
    (is (some? (workbench/item restored "command-cell/beta/prepare" 1)))
    (let [reused (start-session (scripted-worker [])
                                {:workbench items
                                 :evidence-stream-id "alpha"})
          result (prepare (:session reused) 0 :test/deliver {:op :deliver})]
      (is (= :prepared (:status result)))
      (is (= 1 (count (:capture-errors result))))
      (is (= "command-cell/alpha/prepare"
             (get-in result [:capture-errors 0 :item-id]))))))

(deftest close-is-idempotent-and-never-owns-the-worker
  (let [worker (scripted-worker [])
        {:keys [session]} (start-session worker)
        _ (prepare session 0 :test/deliver {:op :deliver})
        before @(:state worker)
        first-close (command-session/close! session)
        after-first @(:state worker)
        second-close (command-session/close! session)
        after-second @(:state worker)]
    (is (= first-close second-close))
    (is (= :closed (:status first-close)))
    ;; Closing bridge admission returns its summary, which performs exactly one
    ;; read-only worker snapshot. CommandCellSession never publishes,
    ;; reconciles, terminates, or assumes ownership of the borrowed worker.
    (is (= (inc (:snapshot-calls before)) (:snapshot-calls after-first)))
    (is (= (:snapshot-calls after-first) (:snapshot-calls after-second)))
    (is (= (select-keys before
                        [:status :next-sequence :uncertain-sequence
                         :command-calls :reconcile-calls :published])
           (select-keys after-second
                        [:status :next-sequence :uncertain-sequence
                         :command-calls :reconcile-calls :published])))
    (is (= #{:command! :reconcile! :snapshot}
           (set (keys (:service worker)))))
    (is (= :closed
           (:reason (caught-data #(command-session/branches session)))))
    (let [summary (datafy/datafy session)
          navigated (datafy/nav session :active (:active summary))]
      (is (= "test" (:evidence-stream-id summary)))
      (is (= :test/deliver (get-in navigated [:cell :cell-id])))
      (is (= [] (:branches navigated))))))
