(ns jolt.sim.monitor-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.monitor :as monitor]
            [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- countdown-config []
  {:tasks {0 (kernel/runnable 3)}
   :world {:order []}
   :step (fn [{:keys [task world]} remaining]
           (let [next-world (update world :order conj task)]
             (if (> remaining 1)
               (-> (kernel/step-yield (dec remaining))
                   (kernel/with-world next-world)
                   (kernel/at-site :countdown))
               (-> (kernel/step-complete :done)
                   (kernel/with-world next-world)
                   (kernel/at-site :finish)))))})

(defn- sample-events []
  (:trace (kernel/run (countdown-config))))

(deftest document-rejects-unsupported-version
  (let [events (sample-events)
        data (caught-data
              #(monitor/read-edn
                (pr-str {:jolt.sim.trace/version 2
                         :jolt.sim.trace/events events})))]
    (is (= ::monitor/invalid-document (:type data)))
    (is (= :unsupported-version (:reason data)))))

(deftest document-rejects-wrong-keys
  (let [events (sample-events)
        data (caught-data
              #(monitor/read-edn
                (pr-str {:jolt.sim.trace/version monitor/trace-version
                         :jolt.sim.trace/events events
                         :extra :nope})))]
    (is (= ::monitor/invalid-document (:type data)))
    (is (= :wrong-keys (:reason data)))))

(deftest document-rejects-trailing-edn
  (let [printed (pr-str (monitor/document (sample-events)))
        data (caught-data #(monitor/read-edn (str printed " :trailing")))]
    (is (= ::monitor/invalid-document (:type data)))
    (is (= :trailing-edn (:reason data)))))

(deftest malformed-events-are-rejected-before-monitor-callbacks
  (let [events (sample-events)
        bad-events (assoc events 0 (assoc (first events) 0 :unknown/event))
        bad-doc {:jolt.sim.trace/version monitor/trace-version
                 :jolt.sim.trace/events bad-events}
        called? (volatile! false)
        spec {:id :never-called
              :initial nil
              :step (fn [state _ _]
                      (vreset! called? true)
                      {:state state})
              :finish (fn [_] {:status :pass})}
        data (caught-data #(monitor/run-monitor spec bad-doc))]
    (is (= kernel/replay-diverged (:type data)))
    (is (false? @called?))))

(deftest monitor-validates-id-and-initial-state-before-callbacks
  (let [doc (monitor/document (sample-events))
        called? (volatile! false)
        base {:id :valid
              :initial nil
              :step (fn [state _ _]
                      (vreset! called? true)
                      {:state state})
              :finish (fn [_] {:status :pass})}
        bad-id (caught-data
                #(monitor/run-monitor (assoc base :id (fn [] nil)) doc))
        bad-initial (caught-data
                     #(monitor/run-monitor
                       (assoc base :initial (fn [] nil)) doc))]
    (is (= trace/unsupported-value (:type bad-id)))
    (is (= trace/unsupported-value (:type bad-initial)))
    (is (false? @called?))))

(deftest early-violation-reports-first-decision-index
  (let [doc (monitor/document (sample-events))
        violation-index 1
        spec {:id :first-transition-must-yield
              :initial nil
              :step (fn [state index _]
                      (if (= index violation-index)
                        {:status :violation :detail {:index index}}
                        {:state state}))
              :finish (fn [_] {:status :pass})}
        result (monitor/run-monitor spec doc)]
    (is (= :first-transition-must-yield (:id result)))
    (is (= :violation (:status result)))
    (is (= violation-index (:index result)))
    (is (= {:index violation-index} (:detail result)))))

(deftest monitor-passes-at-finish-when-no-violation-found
  (let [doc (monitor/document (sample-events))
        spec {:id :always-fine
              :initial 0
              :step (fn [state _ _] {:state (inc state)})
              :finish (fn [state] {:status :pass :detail {:steps state}})}
        result (monitor/run-monitor spec doc)]
    (is (= :pass (:status result)))
    (is (nil? (:index result)))
    (is (= (count (:jolt.sim.trace/events doc))
           (get-in result [:detail :steps])))))

(deftest monitor-is-inconclusive-when-trace-never-exercises-its-assumption
  (let [doc (monitor/document (sample-events))
        spec {:id :assumes-a-deadlock-never-happens
              :initial false
              :step (fn [state _ event]
                      {:state (or state (= :run/deadlock (first event)))})
              :finish (fn [saw-deadlock?]
                        (if saw-deadlock?
                          {:status :violation}
                          {:status :inconclusive
                           :detail {:assumption :no-deadlock-observed}}))}
        result (monitor/run-monitor spec doc)]
    (is (= :inconclusive (:status result)))
    (is (nil? (:index result)))
    (is (= {:assumption :no-deadlock-observed} (:detail result)))))

(deftest document-canonical-edn-round-trips
  (let [doc (monitor/document (sample-events))
        printed (trace/canonical-edn doc)
        read-back (monitor/read-edn printed)]
    (is (= doc read-back))
    (is (= printed (trace/canonical-edn read-back)))))

(deftest validated-byte-detail-has-a-canonical-serialization
  (let [doc (monitor/document (sample-events))
        result
        (monitor/run-monitor
         {:id :bytes
          :initial nil
          :step (fn [_ _ _]
                  {:status :violation
                   :detail (byte-array [0 255 128])})
          :finish (fn [_] {:status :pass})}
         doc)
        printed (trace/canonical-edn result)]
    (is (= :violation (:status result)))
    (is (string/includes? printed ":jolt.sim.value/bytes"))
    (is (string/includes? printed "[0 255 128]"))))

;; jolt.sim.monitor/check-trace-grammar

(defn- run-events [config]
  (:trace (kernel/run config)))

(defn- initial-completed-events []
  (run-events
   {:tasks {0 (kernel/completed :done)}
    :step (fn [_ _] (kernel/step-complete :unreachable))}))

(defn- initial-failed-events []
  (run-events
   {:tasks {0 (kernel/failed (ex-info "already failed" {}))}
    :step (fn [_ _] (kernel/step-complete :unreachable))}))

(defn- initial-deadlock-events []
  (run-events
   {:tasks {0 (kernel/blocked :waiting)}
    :step (fn [_ _] (kernel/step-complete :unreachable))}))

(defn- same-tick-sleep-events []
  (run-events
   {:tasks {0 (kernel/runnable :sleep)}
    :step (fn [{:keys [now]} state]
            (if (= :sleep state)
              (kernel/step-sleep :done now)
              (kernel/step-complete :ok)))
    :strategy (strategy/scripted [0 0])}))

(defn- real-time-advance-events []
  (run-events
   {:tasks {0 (kernel/runnable :sleep)}
    :now 10
    :step (fn [_ state]
            (if (= :sleep state)
              (kernel/step-sleep :done 15)
              (kernel/step-complete :ok)))
    :strategy (strategy/scripted [0 0])}))

(defn- thrown-task-events []
  (run-events
   {:tasks {0 (kernel/runnable :start)}
    :step (fn [_ _] (throw (ex-info "boom" {:code 7})))}))

(defn- two-task-events []
  (run-events
   {:tasks {0 (kernel/runnable :a)
            1 (kernel/runnable :b)}
    :step (fn [_ state] (kernel/step-complete state))
    :strategy (strategy/scripted [0 1])}))

(defn- grammar-result [events]
  (monitor/check-trace-grammar (monitor/document events)))

(deftest grammar-passes-valid-traces
  (testing "normal completion"
    (let [result (grammar-result (sample-events))]
      (is (= :pass (:status result)))
      (is (nil? (:index result)))))
  (testing "initial completion (zero-step)"
    (is (= :pass (:status (grammar-result (initial-completed-events))))))
  (testing "initial failure (zero-step)"
    (is (= :pass (:status (grammar-result (initial-failed-events))))))
  (testing "initial deadlock (zero-step)"
    (is (= :pass (:status (grammar-result (initial-deadlock-events))))))
  (testing "same-tick sleep"
    (is (= :pass (:status (grammar-result (same-tick-sleep-events))))))
  (testing "real time advance with a learned non-zero initial time"
    (is (= :pass (:status (grammar-result (real-time-advance-events))))))
  (testing "a thrown task"
    (is (= :pass (:status (grammar-result (thrown-task-events)))))))

(deftest grammar-catches-duplicate-terminal
  (let [events (sample-events)
        mutated (conj events (last events))
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (= (count events) (:index result)))
    (is (= :event-after-terminal (get-in result [:detail :reason])))))

(deftest grammar-catches-orphan-transition
  (let [events (sample-events)
        mutated (vec (concat (subvec events 0 1) (subvec events 2)))
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (= 1 (:index result)))
    (is (= :orphan-transition (get-in result [:detail :reason])))))

(deftest grammar-catches-choice-transition-task-mismatch
  (let [events (two-task-events)
        mutated (update events 4 assoc 3 0)
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (= 4 (:index result)))
    (is (= :choice-transition-mismatch (get-in result [:detail :reason])))))

(deftest grammar-catches-missing-transition
  (let [events (two-task-events)
        mutated (vec (concat (subvec events 0 2) (subvec events 3)))
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (= 2 (:index result)))
    (is (= :missing-transition (get-in result [:detail :reason])))))

(deftest grammar-catches-time-reversal
  (let [events (real-time-advance-events)
        time-advance-index
        (first (keep-indexed
                (fn [index event]
                  (when (= :time/advance (first event)) index))
                events))
        mutated (update events time-advance-index assoc 3 9)
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (= time-advance-index (:index result)))
    (is (= :time-not-advancing (get-in result [:detail :reason])))))

(deftest grammar-catches-missing-terminal
  (let [events (sample-events)
        mutated (vec (butlast events))
        result (grammar-result mutated)]
    (is (= :violation (:status result)))
    (is (nil? (:index result)))
    (is (= :missing-terminal (get-in result [:detail :reason])))))
