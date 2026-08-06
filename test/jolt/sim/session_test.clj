(ns jolt.sim.session-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]))

(defn- sim-config []
  {:tasks {2 (kernel/runnable :finish)
           0 (kernel/runnable :sleep)}
   :world {:seen []}
   :step (fn [{:keys [task now world]} state]
           (case state
             :sleep (-> (kernel/step-sleep :wake (+ now 5))
                        (kernel/with-world (update world :seen conj task))
                        (kernel/at-site {:ns 'demo.worker :phase :wait}))
             :wake (-> (kernel/step-complete :woke)
                       (kernel/at-site {:ns 'demo.worker :phase :finish}))
             :finish (-> (kernel/step-complete :done)
                         (kernel/with-world (update world :seen conj task))
                         (kernel/at-site {:ns 'demo.fast :phase :finish}))))})

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest a-session-exposes-and-chooses-all-current-kernel-branches
  (let [s (session/start (sim-config))
        before (session/snapshot s)
        previews (session/branches s)]
    (is (= 0 (:revision before)))
    (is (= [{:revision 0 :action [:run 0]}
            {:revision 0 :action [:run 2]}]
           (:branches before)))
    (is (= [[:run 0] [:run 2]]
           (mapv #(get-in % [:branch :action]) previews)))
    (is (= [{:ns 'demo.worker :phase :wait}
            {:ns 'demo.fast :phase :finish}]
           (mapv #(trace/restore-value (:site %)) previews)))
    (is (= before (session/snapshot s))
        "previewing isolated children does not advance the session")
    (let [after (session/step! s {:revision 0 :action [:run 2]})]
      (is (= 1 (:revision after)))
      (is (= [[:run 0]] (mapv :action (:branches after))))
      (is (= :done
             (get-in (trace/restore-value (:projection after))
                     [:tasks 2 :result]))))))

(deftest stale-or-disabled-actions-fail-without-a-journal-entry
  (let [s (session/start (sim-config))]
    (let [before (session/snapshot s)
          data (caught-data
                #(session/step! s {:revision 0 :action [:run 99]}))]
      (is (= :jolt.sim.kernel/invalid-machine-action (:type data)))
      (is (= before (session/snapshot s)))
      (is (= 1 (count (session/journal s)))))
    (session/step! s {:revision 0 :action [:run 2]})
    (let [before (session/snapshot s)
          data (caught-data
                #(session/step! s {:revision 0 :action [:run 2]}))]
      (is (= :jolt.sim.session/stale-branch (:type data)))
      (is (= before (session/snapshot s)))
      (is (= 2 (count (session/journal s)))))))

(deftest malformed-branches-fail-closed-before-transition
  (doseq [[branch reason]
          [[{:revision 0 :action [:run 0] :extra true} nil]
           [{:revision -1 :action [:run 0]} :revision]
           [{:revision 0 :action [:run]} :action]
           [{:revision 0 :action '(:run 0)} :action]]]
    (let [calls (atom 0)
          s (session/start
             {:tasks {0 (kernel/runnable :done)}
              :step (fn [_ _]
                      (swap! calls inc)
                      (kernel/step-complete :ok))})
          before (session/snapshot s)
          data (caught-data #(session/step! s branch))]
      (is (= :jolt.sim.session/invalid-branch (:type data)))
      (is (= reason (:reason data)))
      (is (zero? @calls))
      (is (= before (session/snapshot s)))
      (is (= 1 (count (session/journal s)))))))

(deftest branch-input-is-reconstructed-as-canonical-plain-data
  (let [s (session/start (sim-config))
        supplied (with-meta {:revision 0
                             :action (with-meta [:run 2] {:action-secret true})}
                            {:branch-secret true})]
    (session/step! s supplied)
    (let [stored (:branch (last (session/journal s)))]
      (is (nil? (meta stored)))
      (is (nil? (meta (:action stored))))
      (is (= {:revision 0 :action [:run 2]} stored))
      (is (= (session/journal s)
             (trace/restore-value
              (trace/canonical-value (session/journal s))))))))

(deftest previewed-branches-commit-the-exact-cached-child
  (let [calls (atom 0)
        s (session/start
           {:tasks {0 (kernel/runnable :done)}
            :step (fn [_ _]
                    (swap! calls inc)
                    (kernel/step-complete :ok))})]
    (is (= [{:revision 0 :action [:run 0]}] (session/actions s)))
    (is (= 0 @calls) "snapshot/actions do not evaluate a branch")
    (is (= 1 (count (session/branches s))))
    (is (= 1 @calls))
    (is (= 1 (count (session/branches s))))
    (is (= 1 @calls) "repeat preview reuses the revision cache")
    (session/step! s {:revision 0 :action [:run 0]})
    (is (= 1 @calls) "step commits the already previewed child")))

(deftest journal-sequences-only-committed-commands
  (let [s (session/start (sim-config))]
    (session/step! s {:revision 0 :action [:run 0]})
    (session/step! s {:revision 1 :action [:run 2]})
    (session/step! s {:revision 2 :action [:advance 5]})
    (session/step! s {:revision 3 :action [:run 0]})
    (let [entries (session/journal s)]
      (is (= [0 1 2 3 4] (mapv :seq entries)))
      (is (= [:start :step :step :step :step]
             (mapv :command entries)))
      (is (= :completed (:status (last entries))))
      (is (= :completed (:status (session/snapshot s)))))))

(deftest datafy-is-cheap-and-nav-expands-branches-and-journal
  (let [s (session/start (sim-config))
        summary (datafy/datafy s)
        original (::datafy/obj (meta summary))]
    (is (= (session/snapshot s) summary))
    (is (identical? s original))
    (is (= (session/branches s)
           (datafy/nav original :branches (:branches summary))))
    (is (= (session/journal s)
           (datafy/nav original :journal (:journal summary))))
    (is (= :unchanged
           (protocols/nav s :other :unchanged)))))

(deftest datafied-navigation-has-explicit-revision-semantics
  (let [s (session/start (sim-config))
        summary (datafy/datafy s)
        original (::datafy/obj (meta summary))
        branch-value (:branches summary)
        journal-value (:journal summary)]
    (session/step! s {:revision 0 :action [:run 2]})
    (let [data (caught-data
                #(datafy/nav original :branches branch-value))]
      (is (= :jolt.sim.session/stale-navigation (:type data)))
      (is (= 0 (:captured-revision data)))
      (is (= 1 (:current-revision data))))
    (is (= [(first (session/journal s))]
           (datafy/nav original :journal journal-value))
        "journal navigation returns the captured immutable prefix")))

(deftest navigation-values-have-exact-fail-closed-schemas
  (let [s (session/start (sim-config))]
    (doseq [[key value]
            [[:branches [{:revision 0 :action [:run 0] :extra true}]]
             [:branches [{:revision 0 :action [:run 99]}]]
             [:branches '({:revision 0 :action [:run 0]})]
             [:journal {:count 1 :extra true}]
             [:journal {:count -1}]]]
      (is (= :jolt.sim.session/invalid-navigation
             (:type (caught-data #(datafy/nav s key value))))))))

(deftest branch-navigation-and-step-share-one-revision-lock
  (let [entered (promise)
        release (promise)
        step-attempted (promise)
        calls (atom 0)
        s (session/start
           {:tasks {0 (kernel/runnable :done)}
            :step (fn [_ _]
                    (swap! calls inc)
                    (deliver entered true)
                    @release
                    (kernel/step-complete :ok))})
        summary (datafy/datafy s)
        original (::datafy/obj (meta summary))
        nav-result (future
                     (datafy/nav original :branches (:branches summary)))]
    (try
      (is (= true (deref entered 1000 ::timeout)))
      (let [step-result (future
                          (deliver step-attempted true)
                          (session/step! s {:revision 0 :action [:run 0]}))]
        (is (= true (deref step-attempted 1000 ::timeout)))
        (is (= ::blocked (deref step-result 50 ::blocked))
            "step cannot cross navigation's revision/preview critical section")
        (deliver release true)
        (let [nav-value (deref nav-result 1000 ::timeout)
              step-value (deref step-result 1000 ::timeout)]
          (is (not= ::timeout nav-value))
          (is (not= ::timeout step-value))
          (is (= 0 (get-in (first nav-value) [:branch :revision])))
          (is (= 1 (:revision step-value)))
          (is (= 1 @calls) "step commits navigation's cached child")))
      (finally
        (deliver release true)))))

(deftest session-is-an-opaque-control-capability
  (let [s (session/start (sim-config))
        public-symbols (set (keys (ns-publics 'jolt.sim.session)))]
    (is (not (map? s)))
    (is (nil? (get s :state)))
    (is (not (contains? public-symbols '->Session)))
    (is (not (contains? public-symbols 'map->Session)))
    (is (not (contains? public-symbols '-state)))
    (is (not (contains? public-symbols '-lock)))
    (is (= :jolt.sim.session/invalid-operation
           (:type (caught-data #(s :forged-operation nil)))))))
