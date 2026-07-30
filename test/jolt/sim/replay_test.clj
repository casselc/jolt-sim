(ns jolt.sim.replay-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(defn- countdown-step [{:keys [task world]} remaining]
  (let [next-world (update world :order conj task)]
    (if (> remaining 1)
      (-> (kernel/step-yield (dec remaining))
          (kernel/with-world next-world)
          (kernel/at-site :countdown))
      (-> (kernel/step-complete task)
          (kernel/with-world next-world)
          (kernel/at-site :finish)))))

(defn- countdown-config [seed]
  {:tasks {2 (kernel/runnable 2)
           0 (kernel/runnable 3)
           1 (kernel/runnable 2)}
   :world {:order []}
   :step countdown-step
   :strategy (strategy/seeded seed)})

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- event-index [trace tag]
  (first
   (keep-indexed (fn [index event]
                   (when (= tag (first event))
                     index))
                 trace)))

(deftest same-seed-produces-byte-identical-canonical-edn
  (let [world-a
        (array-map
         :order []
         :nested (array-map
                  :z #{3 1 2}
                  :a [(array-map :b 2 :a 1) (list :x :y)])
         :bytes (byte-array [0 255 128]))
        world-b
        (array-map
         :bytes (byte-array [0 255 128])
         :nested (array-map
                  :a [(array-map :a 1 :b 2) (list :x :y)]
                  :z #{2 3 1})
         :order [])
        first-run (kernel/run (assoc (countdown-config 4242)
                                     :world world-a))
        second-run (kernel/run (assoc (countdown-config 4242)
                                      :world world-b))
        printed (trace/canonical-edn (:trace first-run))]
    (is (= :completed (:status first-run)))
    (is (= (:tasks first-run) (:tasks second-run)))
    (is (= printed (trace/canonical-edn (:trace second-run))))
    (is (= (:trace first-run) (:trace second-run)))
    (is (= (:trace first-run) (edn/read-string printed)))
    (is (= (trace/canonical-value world-a)
           (trace/canonical-value world-b)))))

(deftest seeded-strategy-has-a-portable-golden-and-material-seed-effect
  (let [enabled [0 1 2]
        seeded (strategy/seeded 42)
        [first-id seeded] (strategy/choose seeded enabled)
        [second-id seeded] (strategy/choose seeded enabled)
        [third-id seeded] (strategy/choose seeded enabled)
        schedules
        (set
         (for [seed (range 1 9)]
           (let [[a s] (strategy/choose (strategy/seeded seed) enabled)
                 [b s] (strategy/choose s enabled)
                 [c _] (strategy/choose s enabled)]
             [a b c])))]
    (is (= [0 0 2] [first-id second-id third-id]))
    (is (= 551494037 (:state seeded)))
    (is (< 1 (count schedules)))))

(deftest exact-replay-reproduces-final-state
  (let [original (kernel/run (countdown-config 97))
        replayed (kernel/replay (countdown-config 999)
                                (:trace original))]
    (is (= :completed (:status replayed)))
    (is (= (:world original) (:world replayed)))
    (is (= (:tasks original) (:tasks replayed)))
    (is (= (:now original) (:now replayed)))
    (is (= (:steps original) (:steps replayed)))
    (is (= (:trace original) (:trace replayed)))))

(deftest replay-rejects-initial-world-or-task-state-drift
  (let [config
        {:tasks {0 (kernel/runnable :initial)}
         :world {:version 1}
         :step (fn [_ _]
                 (-> (kernel/step-complete :same)
                     (kernel/at-site :finish)))}
        original (kernel/run config)
        world-data
        (caught-data
         #(kernel/replay (assoc config :world {:version 2})
                         (:trace original)))
        task-data
        (caught-data
         #(kernel/replay
           (assoc-in config [:tasks 0] (kernel/runnable :different))
           (:trace original)))]
    (doseq [data [world-data task-data]]
      (is (= kernel/replay-diverged (:type data)))
      (is (= :event (:reason data)))
      (is (= 0 (:event-index data))))))

(deftest replay-rejects-result-drift-with-an-identical-choice-op-and-site
  (let [step-a (fn [_ _]
                 (-> (kernel/step-complete {:answer :a})
                     (kernel/at-site :finish)))
        step-b (fn [_ _]
                 (-> (kernel/step-complete {:answer :b})
                     (kernel/at-site :finish)))
        config {:tasks {0 (kernel/runnable :same)}
                :world {:same true}
                :step step-a}
        original (kernel/run config)
        data
        (caught-data
         #(kernel/replay (assoc config :step step-b)
                         (:trace original)))]
    (is (= kernel/replay-diverged (:type data)))
    (is (= :event (:reason data)))
    (is (= 2 (:event-index data)))
    (is (= :task/transition
           (first (:expected data))
           (first (:actual data))))
    (is (= (subvec (:expected data) 0 8)
           (subvec (:actual data) 0 8)))))

(deftest replay-rejects-enabled-set-drift
  (let [original (kernel/run (countdown-config 17))
        drifted (assoc-in (countdown-config 17)
                          [:tasks 3]
                          (kernel/runnable 1))
        data (caught-data
              #(kernel/replay drifted (:trace original)))]
    (is (= kernel/replay-diverged (:type data)))
    (is (= :enabled-set (:reason data)))
    (is (= [0 1 2] (:expected-enabled data)))
    (is (= [0 1 2 3] (:actual-enabled data)))))

(deftest replay-rejects-a-recorded-non-runnable-choice
  (let [original (kernel/run (countdown-config 21))
        events (:trace original)
        choice-index (event-index events :schedule/choose)
        first-choice (nth events choice-index)
        bad-choice (assoc first-choice 4 99)
        bad-trace (assoc events choice-index bad-choice)
        data (caught-data
              #(kernel/replay (countdown-config 21) bad-trace))]
    (is (= kernel/replay-diverged (:type data)))
    (is (= :chosen-not-enabled (:reason data)))
    (is (= 99 (:chosen data)))))

(deftest replay-rejects-non-schedule-event-drift
  (let [original (kernel/run (countdown-config 55))
        events (:trace original)
        transition-index (event-index events :task/transition)
        transition (nth events transition-index)
        bad-trace
        (assoc events transition-index
               (assoc transition 5 (trace/canonical-value :wrong-site)))
        data (caught-data
              #(kernel/replay (countdown-config 55) bad-trace))]
    (is (= kernel/replay-diverged (:type data)))
    (is (= :event (:reason data)))
    (is (= transition-index (:event-index data)))))

(deftest malformed-event-shapes-fail-before-choice-extraction
  (let [result (kernel/run (countdown-config 5))
        events (:trace result)
        choice-index (event-index events :schedule/choose)
        choice (nth events choice-index)
        cases
        [[:outer-list (apply list events) 0]
         [:list-event (assoc events choice-index (apply list choice))
          choice-index]
         [:truncated-event (assoc events choice-index (pop choice))
          choice-index]
         [:enabled-list
          (assoc events choice-index
                 (assoc choice 3 (apply list (nth choice 3))))
          choice-index]
         [:negative-task
          (assoc events choice-index (assoc choice 4 -1))
          choice-index]
         [:unknown-tag
          (assoc events choice-index (assoc choice 0 :unknown/event))
          choice-index]]]
    (doseq [[label bad-trace expected-index] cases]
      (testing (name label)
        (let [data
              (caught-data
               #(kernel/replay (countdown-config 5) bad-trace))]
          (is (= kernel/replay-diverged (:type data)))
          (is (= :malformed-trace (:reason data)))
          (is (= expected-index (:event-index data))))))))

(deftest replay-distinguishes-truncation-missing-and-unused-choices
  (let [result (kernel/run (countdown-config 19))
        events (:trace result)
        choice-indices
        (vec
         (keep-indexed (fn [index event]
                         (when (= :schedule/choose (first event))
                           index))
                       events))
        missing-index (last choice-indices)
        missing-trace
        (vec (concat (subvec events 0 missing-index)
                     (subvec events (inc missing-index))))
        missing-data
        (caught-data
         #(kernel/replay (countdown-config 19) missing-trace))
        unused-trace
        (conj events (trace/choose-event 999 0 [0] 0))
        unused-data
        (caught-data
         #(kernel/replay (countdown-config 19) unused-trace))
        truncated-trace (pop events)
        truncated-data
        (caught-data
         #(kernel/replay (countdown-config 19) truncated-trace))]
    (is (= :missing-choice (:reason missing-data)))
    (is (= :unused-choices (:reason unused-data)))
    (is (= :event (:reason truncated-data)))
    (is (= (count truncated-trace) (:event-index truncated-data)))))
