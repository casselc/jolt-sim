(ns jolt.sim.flow-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.flow :as flow]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]))

(def data-schema
  [:map {:closed true} [:n :int]])

(def cell-specs
  {:increment {:handler :increment
               :schema {:input data-schema :output data-schema}
               :requires #{:amount}}
   :identity {:handler :identity
              :schema {:input data-schema :output data-schema}}})

(def handlers
  {:increment (fn [ctx state data]
                {:state (inc (or state 0))
                 :data (update data :n + (get-in ctx [:resources :amount]))})
   :identity (fn [_ state data]
               {:state (inc (or state 0)) :data data})})

(defn- workflow
  ([capacity] (workflow capacity :normal))
  ([capacity regime]
   {:cells {:producer {:id :increment}
            :consumer :identity}
    :edges [{:id :items
             :from :producer
             :to :consumer
             :capacity capacity
             :regime regime}]
    ;; The finite batch seam lets this focused gate discriminate FIFO,
    ;; backpressure, and blocking without claiming one workflow is a service.
    :entries [{:cell :producer :data {:n 0}}
              {:cell :producer :data {:n 10}}]
    :resources {:amount 1}}))

(defn- restored-projection [snapshot]
  (trace/restore-value (:projection snapshot)))

(defn- branch [s action]
  (some #(when (= action (:action %)) %) (session/actions s)))

(defn- step-action! [s action]
  (session/step! s (branch s action)))

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest cell-task-assignment-is-stable-across-declaration-order
  (let [a (flow/compile-workflow (workflow 2) cell-specs handlers)
        b (flow/compile-workflow
           (-> (workflow 2)
               (assoc :cells (array-map :consumer :identity
                                        :producer {:id :increment}))
               (assoc :edges (vec (reverse (:edges (workflow 2))))))
           (array-map :identity (:identity cell-specs)
                      :increment (:increment cell-specs))
           handlers)]
    (is (= {:consumer 0 :producer 1}
           (get-in a [:world :task-ids])))
    (is (= (get-in a [:world :task-ids])
           (get-in b [:world :task-ids])))
    (is (= (keys (:tasks a)) (keys (:tasks b))))))

(deftest output-deltas-accumulate-prior-input-for-downstream-cells
  (let [x-schema [:map {:closed true} [:x :int]]
        y-schema [:map {:closed true} [:y :int]]
        xy-schema [:map {:closed true} [:x :int] [:y :int]]
        z-schema [:map {:closed true} [:z :int]]
        specs {:add-y {:handler :add-y
                       :schema {:input x-schema :output y-schema}}
               :add-z {:handler :add-z
                       :schema {:input xy-schema :output z-schema}}}
        local-handlers
        {:add-y (fn [_ state data]
                  {:state state :data {:y (inc (:x data))}})
         :add-z (fn [_ state data]
                  {:state state :data {:z (+ (:x data) (:y data))}})}
        manifest {:cells {:a :add-y :b :add-z}
                  :edges [{:id :a-to-b
                           :from :a :to :b :capacity 1}]
                  :start :a
                  :input {:x 2}
                  :resources {}}
        result (kernel/run
                (flow/compile-workflow manifest specs local-handlers))
        missing-specs
        (assoc-in specs [:add-z :schema :input]
                  [:map {:closed true}
                   [:x :int] [:y :int] [:missing :int]])
        missing (caught-data
                 #(flow/compile-workflow manifest missing-specs
                                         local-handlers))
        optional-output-specs
        (assoc-in specs [:add-y :schema :output]
                  [:map {:closed true} [:y {:optional true} :int]])
        optional-output
        (caught-data
         #(flow/compile-workflow manifest optional-output-specs
                                 local-handlers))]
    (is (= :completed (:status result)))
    (is (= {:x 2 :y 3 :z 5} (get-in result [:tasks 1 :result])))
    (is (= [:missing] (:missing-keys missing)))
    (is (= [:a :b] (:path missing)))
    (is (= [:y] (:missing-keys optional-output)))
    (is (= [:a :b] (:path optional-output)))))

(deftest finite-workflow-preserves-fifo-blocking-wakes-and-close
  (let [s (session/start (flow/compile-workflow (workflow 2)
                                                cell-specs handlers))]
    (is (= [{:revision 0 :action [:run 1]}] (session/actions s)))

    ;; First producer output wakes the initially blocked consumer while the
    ;; still-fed producer remains runnable: this is a real schedule choice.
    (step-action! s [:run 1])
    (is (= [[:run 0] [:run 1]]
           (mapv :action (session/actions s))))

    ;; Consuming before the producer closes its edge blocks the consumer.
    (step-action! s [:run 0])
    (let [projection (restored-projection (session/snapshot s))]
      (is (= :blocked (get-in projection [:tasks 0 :status])))
      (is (= :blocked (get-in projection [:world :cell-status :consumer]))))

    ;; The final producer input emits id 3, closes the edge, and wakes the
    ;; consumer. The consumer then drains the closed-but-nonempty FIFO.
    (step-action! s [:run 1])
    (is (= [{:revision 3 :action [:run 0]}] (session/actions s)))
    (let [terminal (step-action! s [:run 0])
          projection (restored-projection terminal)
          events (get-in projection [:world :events])]
      (is (= :completed (:status terminal)))
      (is (= :completed (get-in projection [:tasks 0 :status])))
      (is (= :completed (get-in projection [:tasks 1 :status])))
      (is (= {:consumer 2 :producer 2}
             (get-in projection [:world :cell-state])))
      (is (= [] (get-in projection [:world :links :items :queue])))
      (is (true? (get-in projection [:world :links :items :closed?])))
      (is (= [0 2 2 1 3 3]
             (mapv :message-id
                   (filter #(contains? % :message-id) events))))
      (is (= [:message/received :message/enqueued
              :message/received :message/received
              :message/enqueued :edge/closed
              :message/received]
             (mapv :op events))))))

(deftest session-branch-preview-is-pure-and-journal-sites-are-exact
  (let [tapped (atom [])
        done (promise)
        receiver (fn [value]
                   (let [values (swap! tapped conj value)]
                     (when (= 5 (count values)) (deliver done true))))]
    (add-tap receiver)
    (try
      (let [s (session/start (flow/compile-workflow (workflow 2)
                                                    cell-specs handlers))
            before (session/snapshot s)
            preview (first (session/branches s))]
        (is (= before (session/snapshot s)))
        (is (= [:run 1] (get-in preview [:branch :action])))
        (is (= {:ns 'jolt.sim.flow :cell :producer :phase :handle}
               (trace/restore-value (:site preview))))
        (step-action! s [:run 1])
        (step-action! s [:run 0])
        (step-action! s [:run 1])
        (step-action! s [:run 0])
        (is (= true (deref done 1000 ::tap-timeout)))
        (is (= [0 1 2 3 4] (mapv :seq (session/journal s))))
        (is (= [:start :step :step :step :step]
               (mapv :command (session/journal s))))
        (is (= [nil
                {:ns 'jolt.sim.flow :cell :producer :phase :handle}
                {:ns 'jolt.sim.flow :cell :consumer :phase :handle}
                {:ns 'jolt.sim.flow :cell :producer :phase :complete}
                {:ns 'jolt.sim.flow :cell :consumer :phase :complete}]
               (mapv #(some-> % :site trace/restore-value)
                     (session/journal s))))
        (is (= [:jolt.sim.session/started
                :jolt.sim.session/stepped
                :jolt.sim.session/stepped
                :jolt.sim.session/stepped
                :jolt.sim.session/stepped]
               (mapv :event @tapped))))
      (finally
        (remove-tap receiver)))))

(deftest capacity-overflow-and-reject-regime-fail-closed
  (testing "capacity is checked against the undrained FIFO"
    (let [s (session/start
             (flow/compile-workflow (workflow 1 :normal)
                                    cell-specs handlers))]
      (step-action! s [:run 1])
      (let [before (restored-projection (session/snapshot s))
            after (step-action! s [:run 1])
            projection (restored-projection after)]
        (is (= :failed (:status after)))
        (is (= :edge-capacity
               (get-in projection [:tasks 1 :error :reason])))
        (is (= :failed
               (get-in projection [:world :cell-status :producer])))
        (is (= (get-in before [:world :entries])
               (get-in projection [:world :entries])))
        (is (= (get-in before [:world :links])
               (get-in projection [:world :links])))
        (is (= (get-in before [:world :events])
               (get-in projection [:world :events])))
        (is (= {:ns 'jolt.sim.flow :cell :producer :phase :route}
               (trace/restore-value (:site (last (session/journal s)))))))))
  (testing "a reject regime rejects the first routed value"
    (let [s (session/start
             (flow/compile-workflow (workflow 1 :reject)
                                    cell-specs handlers))
          before (restored-projection (session/snapshot s))
          after (step-action! s [:run 1])
          projection (restored-projection after)]
      (is (= :failed (:status after)))
      (is (= :rejected-edge
             (get-in projection [:tasks 1 :error :reason])))
      (is (= :failed
             (get-in projection [:world :cell-status :producer])))
      (is (= (get-in before [:world :entries])
             (get-in projection [:world :entries])))
      (is (= (get-in before [:world :links])
             (get-in projection [:world :links])))
      (is (= (get-in before [:world :events])
             (get-in projection [:world :events])))
      (is (= {:ns 'jolt.sim.flow :cell :producer :phase :route}
             (trace/restore-value (:site (last (session/journal s)))))))))

(deftest fixed-drop-regime-is-observable-and-terminates-cleanly
  (let [calls (atom 0)
        one-input (-> (workflow 1 :drop)
                      (dissoc :entries)
                      (assoc :start :producer :input {:n 7}))
        local-handlers (assoc handlers :identity
                              (fn [_ state data]
                                (swap! calls inc)
                                {:state state :data data}))
        result (kernel/run
                (flow/compile-workflow one-input cell-specs local-handlers))]
    (is (= :completed (:status result)))
    (is (zero? @calls) "a dropped value never invokes the downstream cell")
    (is (= [:message/received :message/dropped :edge/closed]
           (mapv :op (get-in result [:world :events]))))
    (is (= [] (get-in result [:world :links :items :queue])))
    (is (true? (get-in result [:world :links :items :closed?])))))

(deftest closed-empty-lifecycle-completes-without-invoking-a-sink-handler
  (let [calls (atom 0)
        specs {:only {:handler :only
                      :schema {:input data-schema :output data-schema}}}
        result (kernel/run
                (flow/compile-workflow
                 {:cells {:only :only}
                  :edges []
                  :start :only
                  :input {:n 4}
                  :resources {}}
                 specs
                 {:only (fn [_ state data]
                          (swap! calls inc)
                          {:state state :data data})}))]
    (is (= :completed (:status result)))
    (is (= 1 @calls))
    (is (= {:n 4} (get-in result [:tasks 0 :result])))))

(deftest malformed-topologies-and-schema-boundaries-fail-with-coordinates
  (let [bad-capacity (caught-data
                      #(flow/compile-workflow (workflow 0)
                                              cell-specs handlers))
        bad-resource (caught-data
                      #(flow/compile-workflow
                        (assoc (workflow 2) :resources {})
                        cell-specs handlers))
        bad-input (caught-data
                   #(flow/compile-workflow
                     (assoc (workflow 2) :entries [{:cell :producer
                                                    :data {:n "wrong"}}])
                     cell-specs handlers))
        unreachable (caught-data
                     #(flow/compile-workflow
                       (assoc-in (workflow 2) [:cells :orphan] :identity)
                       cell-specs handlers))
        cyclic (caught-data
                #(flow/compile-workflow
                  (update (workflow 2) :edges conj
                          {:id :return
                           :from :consumer
                           :to :producer
                           :capacity 1})
                  cell-specs handlers))
        string-schema [:map {:closed true} [:s :string]]
        incompatible-specs
        (assoc cell-specs :identity
               {:handler :identity
                :schema {:input string-schema :output string-schema}})
        incompatible (caught-data
                      #(flow/compile-workflow
                        (workflow 2) incompatible-specs handlers))]
    (is (= :jolt.sim.flow/invalid-workflow (:type bad-capacity)))
    (is (= :jolt.sim.flow/invalid-workflow (:type bad-resource)))
    (is (= :jolt.sim.schema/invalid-value (:type bad-input)))
    (is (= :input (:field bad-input)))
    (is (= :increment (:pack-id bad-input)))
    (is (= [:orphan] (:unreachable unreachable)))
    (is (= :jolt.sim.flow/invalid-workflow (:type cyclic)))
    (is (= :jolt.sim.flow/invalid-workflow (:type incompatible)))))

(deftest unused-registry-specs-do-not-constrain-a-selected-workflow
  (let [unused {:handler :missing-unused-handler
                :schema {:input data-schema :output data-schema}
                :requires #{:missing-unused-resource}}
        config (flow/compile-workflow (workflow 2)
                                      (assoc cell-specs :unused unused)
                                      handlers)]
    (is (= {:consumer 0 :producer 1}
           (get-in config [:world :task-ids])))
    (is (= #{0 1} (set (keys (:tasks config)))))))

(deftest handler-failures-retain-a-semantic-site-without-consuming-input
  (let [bad-handlers (assoc handlers :increment
                            (fn [_ _ _]
                              (throw (ex-info "boom" {:cause :test}))))
        s (session/start
           (flow/compile-workflow (workflow 2) cell-specs bad-handlers))
        before (restored-projection (session/snapshot s))
        after (step-action! s [:run 1])
        projection (restored-projection after)]
    (is (= :failed (:status after)))
    (is (= {:ns 'jolt.sim.flow :cell :producer :phase :handle}
           (trace/restore-value (:site (last (session/journal s))))))
    (is (= (get-in before [:world :entries :producer])
           (get-in projection [:world :entries :producer])))
    (is (= :failed
           (get-in projection [:world :cell-status :producer])))
    (is (= [] (get-in projection [:world :events])))))
