(ns jolt.sim.monitor-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.monitor :as monitor]
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
