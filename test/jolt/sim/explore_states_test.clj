(ns jolt.sim.explore-states-test
  "Focused tests for the cooperative-track explicit-state explorer.

  Covers the kernel machine API, deterministic full-branching BFS (completion,
  state-limit, and first-shortest invariant witness with replayable path),
  malformed action/config/bound rejection, machine/kernel agreement, and a
  byte-identical kernel run/replay trace regression.

  The capacity-one mailbox/timer model exercises same-tick reply-vs-timeout
  choices, the cancellation/first-terminal outcome, and exactly-once cleanup.
  A deliberately buggy cleanup control has a stable minimal violation path that
  the explorer finds and replays; the corrected control has no violation within
  the complete finite graph."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.explore-states :as explore]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- restore-projection [projection]
  (trace/restore-value projection))

(defn- trace->machine-path [trace]
  "Reconstructs the machine action path recorded in a kernel event trace:
  one `[:run chosen]` per schedule/choose and one `[:advance to]` per time
  advance, in execution order."
  (vec
   (for [event trace
         :when (#{:schedule/choose :time/advance} (first event))]
     (case (first event)
       :schedule/choose [:run (nth event 4)]
       :time/advance [:advance (nth event 3)]))))

(defn- timer-wake-config [seed]
  "A kernel scenario that exercises run, sleep, forced time advance, and an
  explicit wake, so machine/kernel agreement and trace regression cover every
  one-step branch."
  {:tasks {0 (kernel/runnable :work)
           1 (kernel/blocked :wait)
           2 (kernel/sleeping :armed 5)}
   :world {:ticks 0}
   :step (fn [{:keys [task now world]} state]
           (case task
             0 (case state
                 :work (-> (kernel/step-sleep :more 3) (kernel/at-site :nap))
                 :more (-> (kernel/step-complete :done)
                           (kernel/waking [1])
                           (kernel/with-world (assoc world :ticks now))
                           (kernel/at-site :finish)))
             1 (kernel/step-complete :released)
             2 (kernel/step-complete :timer)))
   :strategy (strategy/seeded seed)})

;;; Machine API basics

(deftest machine-enumerates-sorted-run-actions-and-a-forced-advance
  (let [config {:tasks {3 (kernel/runnable :a)
                        1 (kernel/runnable :b)
                        2 (kernel/sleeping :c 7)}
                :step (fn [_ _] (kernel/step-complete :ok))}
        m (kernel/machine config)]
    (is (= [[:run 1] [:run 3]] (kernel/machine-actions m)))
    (is (nil? (kernel/machine-status m)))
    (let [m2 (-> m
                 (kernel/machine-apply [:run 1])
                 (kernel/machine-apply [:run 3]))]
      (is (= [[:advance 7]] (kernel/machine-actions m2))))))

(deftest machine-projection-includes-budget-and-excludes-trace
  (let [m (kernel/machine {:tasks {0 (kernel/runnable :x)}
                           :world {:k 1}
                           :step (fn [_ _] (kernel/step-complete :done))})
        restored (restore-projection (kernel/machine-projection m))]
    (is (= #{:tasks :world :now :steps :max-steps}
           (set (keys restored))))
    (is (= 0 (:steps restored)))
    (is (= 1000 (:max-steps restored)))
    (is (= {:k 1} (:world restored)))))

(deftest machine-terminal-state-has-no-actions-and-exposes-status
  (let [m (kernel/machine {:tasks {0 (kernel/completed :done)}
                           :step (fn [_ _] (kernel/step-complete :done))})]
    (is (= :completed (kernel/machine-status m)))
    (is (= [] (kernel/machine-actions m)))))

(deftest machine-branches-isolate-mutable-byte-array-leaves
  (let [source (byte-array [0])
        sim {:tasks {0 (kernel/runnable :mutate)
                     1 (kernel/runnable :read)}
             :world {:payload source}
             :step (fn [{:keys [task world]} _]
                     (let [payload (:payload world)]
                       (case task
                         0 (do
                             (aset payload 0 7)
                             (-> (kernel/step-complete :mutated)
                                 (kernel/with-world world)))
                         1 (kernel/step-complete (aget payload 0))))) }
        parent (kernel/machine sim)
        mutated (kernel/machine-apply parent [:run 0])
        observed (kernel/machine-apply parent [:run 1])
        mutated-state (restore-projection
                       (kernel/machine-projection mutated))
        observed-state (restore-projection
                        (kernel/machine-projection observed))]
    (is (= 7 (aget (get-in mutated-state [:world :payload]) 0)))
    (is (= 0 (get-in observed-state [:tasks 1 :result])))
    (is (= 0 (aget source 0)))))

;;; Malformed machine actions

(deftest machine-apply-rejects-malformed-or-disabled-actions
  (let [m (kernel/machine {:tasks {0 (kernel/runnable :x)}
                           :step (fn [_ _] (kernel/step-complete :done))})]
    (doseq [action [nil :run 5 [] [:run] [:run -1] [:run "0"]
                    [:run 0 :extra] [:run 1] [:bogus 0]
                    [:advance :now] [:advance 0] [:advance 0 :extra]]]
      (testing (pr-str action)
        (let [data (caught-data #(kernel/machine-apply m action))]
          (is (= ::kernel/invalid-machine-action (:type data)))
          (is (= action (:action data))))))
    (let [terminal (kernel/machine {:tasks {0 (kernel/completed :done)}
                                    :step (fn [_ _] (kernel/step-complete :done))})
          data (caught-data #(kernel/machine-apply terminal [:run 0]))]
      (is (= ::kernel/invalid-machine-action (:type data)))
      (is (= :completed (:kind data))))))

(deftest machine-apply-rejects-an-advance-with-the-wrong-wake-time
  (let [m (kernel/machine {:tasks {0 (kernel/sleeping :a 4)}
                           :step (fn [_ _] (kernel/step-complete :done))})]
    (is (= [[:advance 4]] (kernel/machine-actions m)))
    (let [data (caught-data #(kernel/machine-apply m [:advance 9]))]
      (is (= ::kernel/invalid-machine-action (:type data)))
      (is (= 9 (:wake-at data)))
      (is (= 4 (:earliest data))))))

;;; Explorer basics: completion, state-limit, witness

(deftest explore-states-completes-a-finite-graph
  (let [sim {:tasks {0 (kernel/runnable 2)}
             :step (fn [_ n]
                     (if (pos? n)
                       (kernel/step-yield (dec n))
                       (kernel/step-complete :zero)))}
        result (explore/explore-states {:sim sim :max-states 100})]
    (is (= :completed (:status result)))
    ;; Distinct projections: state 2, 1, 0 (runnable), then :zero (completed).
    (is (= 4 (:visited result)))
    (is (= 1 (get-in result [:terminals :completed] 0)))))

(deftest explore-states-reports-state-limit-when-the-cap-is-below-reach
  ;; A monotonically growing state never revisits a projection, so a tiny cap
  ;; is hit before the (infinite) frontier can exhaust.
  (let [sim {:tasks {0 (kernel/runnable 0)}
             :step (fn [_ n] (kernel/step-yield (inc n)))
             :max-steps 1000}
        result (explore/explore-states {:sim sim :max-states 3})]
    (is (= :state-limit (:status result)))
    (is (= 3 (:visited result)))
    (is (not (contains? result :witness)))))

(deftest explore-states-finds-the-first-shortest-invariant-witness
  ;; Two runnable tasks; the invariant fires the moment the world has seen both
  ;; task ids. BFS must report the length-2 path, not a longer one.
  (let [sim {:tasks {0 (kernel/runnable :a)
                     1 (kernel/runnable :b)}
             :world {:seen #{}}
             :step (fn [{:keys [task] :as ctx} _]
                     (-> (kernel/step-complete :done)
                         (kernel/with-world
                          (update (:world ctx) :seen conj task))))
             :max-steps 10}
        invariant (fn [{:keys [projection]}]
                    (let [seen (:seen (:world (restore-projection projection)))]
                      (when (= #{0 1} (set seen))
                        {:seen seen})))
        result (explore/explore-states {:sim sim :max-states 100
                                         :invariant invariant})
        witness (:witness result)]
    (is (= :violation (:status result)))
    (is (= 2 (count (:path witness))))
    (is (or (= [[:run 0] [:run 1]] (:path witness))
            (= [[:run 1] [:run 0]] (:path witness))))
    (is (= #{0 1} (:seen (restore-projection (:evidence witness)))))
    ;; Replaying the path through a fresh machine reproduces the witness.
    (let [m (reduce kernel/machine-apply (kernel/machine sim) (:path witness))]
      (is (= #{0 1}
             (:seen (:world (restore-projection (kernel/machine-projection m)))))))))

(deftest budget-bearing-state-preserves-a-reachable-step-limit
  (let [sim {:tasks {0 (kernel/runnable :same)}
             :step (fn [_ state] (kernel/step-yield state))
             :max-steps 1}
        result (explore/explore-states
                {:sim sim
                 :max-states 10
                 :invariant (fn [{:keys [status]}]
                              (when (= :step-limit status)
                                {:reason :reachable-step-limit}))})]
    (is (= :violation (:status result)))
    (is (= [[:run 0]] (get-in result [:witness :path])))
    (is (= :step-limit (get-in result [:witness :status])))
    (is (= {:reason :reachable-step-limit}
           (restore-projection (get-in result [:witness :evidence]))))))

(deftest state-cap-precedes-invariant-evaluation-for-an-unseen-child
  (let [calls (atom 0)
        sim {:tasks {0 (kernel/runnable :start)}
             :world {:child? false}
             :step (fn [{:keys [world]} _]
                     (-> (kernel/step-complete :done)
                         (kernel/with-world (assoc world :child? true))))}
        result (explore/explore-states
                {:sim sim
                 :max-states 1
                 :invariant (fn [{:keys [projection]}]
                              (swap! calls inc)
                              (when (:child? (:world (restore-projection projection)))
                                {:reason :outside-cap}))})]
    (is (= :state-limit (:status result)))
    (is (= 1 (:visited result)))
    (is (= 1 @calls))
    (is (not (contains? result :witness)))))

(deftest invariant-evidence-is-validated-and-frozen
  (let [source (byte-array [1 2 3])
        sim {:tasks {0 (kernel/runnable :start)}
             :step (fn [_ _] (kernel/step-complete :done))}
        result (explore/explore-states
                {:sim sim
                 :max-states 10
                 :invariant (fn [{:keys [status]}]
                              (when (= :completed status) source))})
        frozen (restore-projection (get-in result [:witness :evidence]))]
    (aset source 0 99)
    (is (= :violation (:status result)))
    (is (= [1 2 3] (mapv #(bit-and (long %) 0xff) (seq frozen)))))
  (let [sim {:tasks {}
             :step (fn [_ _] (kernel/step-complete :unreachable))}
        data (caught-data
              #(explore/explore-states
                {:sim sim
                 :max-states 1
                 :invariant (fn [_] (fn [] :unsupported))}))]
    (is (= trace/unsupported-value (:type data)))
    (is (= [:invariant-evidence] (:path data)))))

;;; Malformed explorer configuration

(deftest explore-states-rejects-malformed-configuration
  (let [sim {:tasks {0 (kernel/runnable 0)}
             :step (fn [_ n] (kernel/step-complete n))}]
    (doseq [[label cfg]
            [[:not-a-map 5]
             [:unknown-key {:sim sim :max-states 5 :bogus 1}]
             [:missing-sim {:max-states 5}]
             [:sim-not-a-map {:sim 5 :max-states 5}]
             [:missing-max-states {:sim sim}]
             [:max-states-zero {:sim sim :max-states 0}]
             [:max-states-negative {:sim sim :max-states -2}]
             [:invariant-not-a-fn {:sim sim :max-states 5 :invariant 5}]]]
      (testing (name label)
        (let [data (caught-data #(explore/explore-states cfg))]
          (is (= ::explore/invalid-config (:type data))))))))

;;; Capacity-one mailbox/timer model: reply vs timeout, cancel, exactly-once.

(defn- mailbox-cleanup [world]
  (update world :cleanups inc))

(defn- mailbox-client-step [{:keys [world]} state]
  (case state
    :send
    (-> (kernel/step-block :waiting)
        (kernel/with-world (assoc world :inbox :req))
        (kernel/at-site :send))
    :waiting
    (case (:outcome world)
      :timed-out
      (-> (kernel/step-complete :timed-out)
          (kernel/with-world (mailbox-cleanup world))
          (kernel/at-site :cleanup-timeout))
      :replied
      (-> (kernel/step-complete :replied)
          (kernel/with-world (mailbox-cleanup world))
          (kernel/at-site :cleanup-reply))
      :cancelled
      (-> (kernel/step-complete :cancelled)
          (kernel/with-world (mailbox-cleanup world))
          (kernel/at-site :cleanup-cancel))
      ;; Spurious wake before either resolver set :outcome: stay blocked.
      (kernel/step-block :waiting))))

(defn- mailbox-server-step [{:keys [world]} state]
  (if (and (= :idle state) (= :req (:inbox world)) (nil? (:outcome world)))
    (-> (kernel/step-complete :done)
        (kernel/with-world (-> world
                               (assoc :inbox :ack)
                               (assoc :outcome :replied)
                               (update :claims inc)))
        (kernel/waking [0])
        (kernel/at-site :reply))
    (kernel/step-complete :noop)))

(defn- mailbox-timer-step [buggy? {:keys [world]} _state]
  (if (and (= :req (:inbox world)) (nil? (:outcome world)))
    (let [world (-> world
                    (assoc :outcome :timed-out)
                    (update :claims inc))
          world (if buggy? (mailbox-cleanup world) world)]
      (-> (kernel/step-complete :fired)
          (kernel/with-world world)
          (kernel/waking [0])
          (kernel/at-site (if buggy? :buggy-timeout :timeout))))
    (kernel/step-complete :noop)))

(defn- mailbox-cancel-step [{:keys [world]} _state]
  (if (and (= :req (:inbox world)) (nil? (:outcome world)))
    (-> (kernel/step-complete :cancelled)
        (kernel/with-world (-> world
                               (assoc :outcome :cancelled)
                               (update :claims inc)))
        (kernel/waking [0])
        (kernel/at-site :cancel))
    (kernel/step-complete :noop)))

(defn- mailbox-config [{:keys [buggy? max-steps] :or {max-steps 100}}]
  (let [b (boolean buggy?)]
    {:tasks {0 (kernel/runnable :send)
             1 (kernel/sleeping :idle 1)
             2 (kernel/sleeping :armed 1)
             3 (kernel/sleeping :cancel 1)}
     :world {:inbox nil :outcome nil :claims 0 :cleanups 0}
     :step (fn [ctx state]
             (case (:task ctx)
               0 (mailbox-client-step ctx state)
               1 (mailbox-server-step ctx state)
               2 (mailbox-timer-step b ctx state)
               3 (mailbox-cancel-step ctx state)))
     :max-steps max-steps}))

(defn- mailbox-invariant [{:keys [projection status]}]
  (let [world (:world (restore-projection projection))
        claims (:claims world)
        cleanups (:cleanups world)]
    (cond
      (< 1 claims)
      {:claims claims :reason :more-than-one-terminal-claim}

      (< 1 cleanups)
      {:cleanups cleanups :reason :more-than-once-cleanup}

      (and (= :completed status) (not= 1 claims))
      {:claims claims :reason :missing-terminal-claim}

      (and (= :completed status) (not= 1 cleanups))
      {:cleanups cleanups :reason :missing-cleanup})))

(deftest mailbox-model-explores-reply-timeout-and-cancel-at-one-tick
  (let [sim (mailbox-config {:buggy? false})
        result (explore/explore-states {:sim sim :max-states 500})]
    (is (= :completed (:status result)))
    (is (< 4 (:visited result) 500))
    (is (= {:completed 3} (:terminals result)))
    (doseq [[outcome path]
            [[:replied [[:run 0] [:advance 1] [:run 1] [:run 0]
                        [:run 2] [:run 3]]]
             [:timed-out [[:run 0] [:advance 1] [:run 2] [:run 0]
                          [:run 1] [:run 3]]]
             [:cancelled [[:run 0] [:advance 1] [:run 3] [:run 0]
                          [:run 1] [:run 2]]]]]
      (let [machine (reduce kernel/machine-apply (kernel/machine sim) path)
            world (:world (restore-projection
                           (kernel/machine-projection machine)))]
        (is (= :completed (kernel/machine-status machine)))
        (is (= outcome (:outcome world)))
        (is (= 1 (:claims world)))
        (is (= 1 (:cleanups world)))))))

(deftest buggy-mailbox-control-has-a-stable-minimal-double-cleanup-witness
  (let [sim (mailbox-config {:buggy? true})
        result (explore/explore-states
                {:sim sim :max-states 100 :invariant mailbox-invariant})
        witness (:witness result)]
    (is (= :violation (:status result)))
    (is (= [[:run 0] [:advance 1] [:run 2] [:run 0]]
           (:path witness)))
    (is (= :more-than-once-cleanup
           (:reason (restore-projection (:evidence witness)))))
    ;; Replaying the witness path reproduces the double cleanup independently.
    (let [final (restore-projection
                 (kernel/machine-projection
                  (reduce kernel/machine-apply (kernel/machine sim) (:path witness))))]
      (is (= 2 (:cleanups (:world final)))))
    ;; Re-exploration is deterministic: the same shortest witness is returned.
    (is (= (:path witness)
           (:path (:witness
                   (explore/explore-states
                    {:sim sim :max-states 100 :invariant mailbox-invariant})))))))

(deftest corrected-mailbox-control-has-no-cleanup-violation-in-the-full-graph
  (let [result (explore/explore-states
                {:sim (mailbox-config {:buggy? false})
                 :max-states 500
                 :invariant mailbox-invariant})]
    (is (= :completed (:status result)))
    (is (not (contains? result :witness)))))

;;; Machine/kernel agreement and byte-identical trace regression

(deftest machine-and-kernel-agree-on-final-state-and-status
  (doseq [seed [1 7 42 99 1234]]
    (let [cfg (timer-wake-config seed)
          run (kernel/run cfg)
          path (trace->machine-path (:trace run))
          m (reduce kernel/machine-apply (kernel/machine cfg) path)]
      (is (= (:status run) (kernel/machine-status m)))
      (is (= (trace/canonical-value
              {:tasks (:tasks run)
               :world (:world run)
               :now (:now run)
               :steps (:steps run)
               :max-steps (get cfg :max-steps 1000)})
             (kernel/machine-projection m))))))

(deftest kernel-run-and-replay-traces-remain-byte-identical
  (doseq [seed [1 7 42 99 1234]]
    (let [cfg (timer-wake-config seed)
          run (kernel/run cfg)
          trace1 (:trace run)
          replayed (kernel/replay cfg trace1)
          trace2 (:trace replayed)]
      (is (= trace1 trace2))
      (is (= (trace/canonical-edn trace1) (trace/canonical-edn trace2)))
      (is (= (:tasks run) (:tasks replayed)))
      (is (= (:world run) (:world replayed)))
      (is (= (:status run) (:status replayed))))))
