(ns jolt.sim.kernel-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [jolt.sim.kernel :as kernel]
            [jolt.sim.strategy :as strategy]
            [jolt.sim.trace :as trace]))

(defn- choice-events [result]
  (filter #(= :schedule/choose (first %)) (:trace result)))

(defn- time-events [result]
  (filter #(= :time/advance (first %)) (:trace result)))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(deftest scheduler-selects-only-stably-sorted-runnable-tasks
  (let [config
        {:tasks {3 (kernel/runnable :done)
                 0 (kernel/blocked :waiting)
                 2 (kernel/sleeping :done 5)
                 1 (kernel/runnable :done)}
         :step (fn [_ _] (kernel/step-complete :ok))
         :strategy (strategy/scripted [1 3 2])}
        result (kernel/run config)
        choices (vec (choice-events result))]
    (is (= :deadlock (:status result)))
    (is (= [[:schedule/choose 0 0 [1 3] 1]
            [:schedule/choose 1 0 [3] 3]
            [:schedule/choose 2 5 [2] 2]]
           choices))
    (is (every? (fn [event]
                  (some #(= (nth event 4) %) (nth event 3)))
                choices))
    (is (= :blocked (get-in result [:tasks 0 :status])))))

(deftest virtual-time-advances-only-after-runnable-work-and-to-earliest-timers
  (let [config
        {:tasks {0 (kernel/runnable :again)
                 3 (kernel/sleeping :done 8)
                 2 (kernel/sleeping :done 5)
                 1 (kernel/sleeping :done 5)}
         :step (fn [_ state]
                 (if (= :again state)
                   (kernel/step-yield :done)
                   (kernel/step-complete :ok)))
         :strategy (strategy/scripted [0 0 2 1 3])}
        result (kernel/run config)
        advances (mapv #(subvec % 0 5) (time-events result))]
    (is (= :completed (:status result)))
    (is (= 8 (:now result)))
    (is (= [[:time/advance 2 0 5 [1 2]]
            [:time/advance 4 5 8 [3]]]
           advances))
    (is (= [1 2]
           (nth (nth (vec (choice-events result)) 2) 3)))))

(deftest blocked-work-without-a-future-event-is-an-explicit-deadlock
  (let [result
        (kernel/run
         {:tasks {1 (kernel/blocked :waiting)
                  0 (kernel/blocked :waiting)}
          :step (fn [_ _] (kernel/step-complete :unreachable))})
        terminal (last (:trace result))]
    (is (= :deadlock (:status result)))
    (is (= 0 (:steps result)))
    (is (= [:run/deadlock 0 0 [0 1]]
           (subvec terminal 0 4)))))

(deftest a-task-transition-can-wake-a-blocked-task
  (let [result
        (kernel/run
         {:tasks {0 (kernel/blocked :ready)
                  1 (kernel/runnable :send)}
          :step (fn [{:keys [task]} state]
                  (if (= task 1)
                    (-> (kernel/step-complete :sent)
                        (kernel/waking [0])
                        (kernel/at-site :signal))
                    (kernel/step-complete state)))
          :strategy (strategy/scripted [1 0])})
        transition (nth (:trace result) 2)]
    (is (= :completed (:status result)))
    (is (= :sent (get-in result [:tasks 1 :result])))
    (is (= :ready (get-in result [:tasks 0 :result])))
    (is (= [:task/transition
            0 0 1 :complete (trace/canonical-value :signal) [0] nil]
           (subvec transition 0 8)))))

(deftest step-limit-bounds-a-runnable-loop
  (let [result
        (kernel/run
         {:tasks {0 (kernel/runnable 0)}
          :step (fn [_ state] (kernel/step-yield (inc state)))
          :max-steps 3})]
    (is (= :step-limit (:status result)))
    (is (= 3 (:steps result)))
    (is (= 3 (get-in result [:tasks 0 :state])))
    (is (= [:run/step-limit 3 0]
           (subvec (last (:trace result)) 0 3)))))

(deftest zero-step-bound-is-terminal-without-running-user-code
  (let [called? (volatile! false)
        result
        (kernel/run
         {:tasks {0 (kernel/runnable :ready)}
          :step (fn [_ _]
                  (vreset! called? true)
                  (kernel/step-complete :wrong))
          :max-steps 0})]
    (is (= :step-limit (:status result)))
    (is (false? @called?))
    (is (= 0 (:steps result)))
    (is (= [:run/initial :run/step-limit]
           (mapv first (:trace result))))))

(deftest a-same-tick-sleep-wakes-without-advancing-time
  (let [result
        (kernel/run
         {:tasks {0 (kernel/runnable :sleep)}
          :step (fn [{:keys [now]} state]
                  (if (= :sleep state)
                    (kernel/step-sleep :done now)
                    (kernel/step-complete :ok)))
          :strategy (strategy/scripted [0 0])})]
    (is (= :completed (:status result)))
    (is (= 0 (:now result)))
    (is (empty? (time-events result)))
    (is (= [0 0] (mapv #(nth % 4) (choice-events result))))))

(deftest virtual-time-and-seeds-require-integer-ticks
  (let [bad-now
        (caught-data
         #(kernel/run
           {:tasks {}
            :now 0.5
            :step (fn [_ _] (kernel/step-complete nil))}))
        bad-wake
        (caught-data
         #(kernel/run
           {:tasks {0 (kernel/runnable nil)}
            :step (fn [_ _] (kernel/step-sleep nil 1.5))}))
        bad-seed
        (caught-data #(strategy/seeded 4.2))]
    (is (= :jolt.sim.kernel/invalid-config (:type bad-now)))
    (is (= :jolt.sim.kernel/invalid-transition (:type bad-wake)))
    (is (= :jolt.sim.strategy/invalid-seed (:type bad-seed)))))

(deftest task-identities-are-non-negative-integers
  (doseq [bad-id [:worker -1 "0"]]
    (let [data
          (caught-data
           #(kernel/run
             {:tasks {bad-id (kernel/runnable nil)}
              :step (fn [_ _] (kernel/step-complete nil))}))]
      (is (= :jolt.sim.kernel/invalid-config (:type data)))))
  (doseq [choices [[:worker] [-1] "not-a-choice-sequence"]]
    (let [data (caught-data #(strategy/scripted choices))]
      (is (= :jolt.sim.strategy/invalid-script (:type data))))))

(deftest invalid-wakes-fail-closed
  (doseq [[wake expected-detail]
          [[0 :non-sequential]
           [[-1] :negative]
           [[1 1] :duplicate]
           [[2] :unknown]
           [[0] :not-blocked]]]
    (let [tasks (if (or (= wake [1 1]) (= wake [2]))
                  {0 (kernel/runnable nil)
                   1 (kernel/blocked nil)}
                  {0 (kernel/runnable nil)})
          data
          (caught-data
           #(kernel/run
             {:tasks tasks
              :step (fn [_ _]
                      (-> (kernel/step-complete nil)
                          (kernel/waking wake)))
              :strategy (strategy/scripted [0])}))]
      (testing (name expected-detail)
        (is (= :jolt.sim.kernel/invalid-transition (:type data)))))))

(deftest a-blocking-task-cannot-wake-itself
  (let [data
        (caught-data
         #(kernel/run
           {:tasks {0 (kernel/runnable :start)}
            :step (fn [_ _]
                    (-> (kernel/step-block :waiting)
                        (kernel/waking [0])))
            :max-steps 5}))]
    (is (= :jolt.sim.kernel/invalid-transition (:type data)))
    (is (= 0 (:task data)))
    (is (= [0] (:wake data)))))

(deftest thrown-task-errors-become-readable-failed-task-state
  (let [result
        (kernel/run
         {:tasks {0 (kernel/runnable :start)}
          :step (fn [_ _] (throw (ex-info "boom" {:code 7})))})
        error (get-in result [:tasks 0 :error])]
    (is (= :failed (:status result)))
    (is (= :failed (get-in result [:tasks 0 :status])))
    (is (= :jolt.sim/exception (:kind error)))
    (is (= "boom" (:message error)))
    (is (= {:code 7} (:data error)))
    (is (= :run/failed (first (last (:trace result)))))
    (is (not (string/includes?
              (trace/canonical-edn (:trace result))
              "#error")))))

(deftest explicit-step-fail-normalizes-nested-raw-exceptions
  (let [result
        (kernel/run
         {:tasks {0 (kernel/runnable nil)}
          :step
          (fn [_ _]
            (kernel/step-fail
             {:outer (ex-info "outer" {:inner (ex-info "inner" {:n 1})})}))})
        error (get-in result [:tasks 0 :error])]
    (is (= :failed (:status result)))
    (is (= :jolt.sim/exception (get-in error [:outer :kind])))
    (is (= :jolt.sim/exception
           (get-in error [:outer :data :inner :kind])))
    (is (not (string/includes?
              (trace/canonical-edn (:trace result))
              "#error")))))

(deftest initially-failed-task-normalizes-a-raw-exception
  (let [result
        (kernel/run
         {:tasks {0 (kernel/failed
                     (ex-info "already failed" {:phase :initial}))}
          :step (fn [_ _] (kernel/step-complete :unreachable))})
        error (get-in result [:tasks 0 :error])]
    (is (= :failed (:status result)))
    (is (= :jolt.sim/exception (:kind error)))
    (is (= "already failed" (:message error)))
    (is (= {:phase :initial} (:data error)))
    (is (not (string/includes?
              (trace/canonical-edn (:trace result))
              "#error")))))

(deftest stable-value-domain-rejects-host-objects-but-accepts-byte-arrays
  (let [bad-data
        (caught-data
         #(kernel/run
           {:tasks {0 (kernel/runnable nil)}
            :world {:callback (fn [] :no)}
            :step (fn [_ _] (kernel/step-complete nil))}))
        bytes (byte-array [0 255 128])
        result
        (kernel/run
         {:tasks {0 (kernel/runnable bytes)}
          :world {:payload bytes}
          :step (fn [_ state] (kernel/step-complete state))})
        printed (trace/canonical-edn (:trace result))]
    (is (= trace/unsupported-value (:type bad-data)))
    (is (= :completed (:status result)))
    (is (string/includes? printed ":jolt.sim.value/bytes"))
    (is (string/includes? printed "[0 255 128]"))))

(defn- racy-increment-step [{:keys [world]} state]
  (case (:pc state)
    :read
    (kernel/step-yield {:pc :write
                        :seen (:counter world)})

    :write
    (-> (kernel/step-complete :done)
        (kernel/with-world
         (assoc world :counter (inc (:seen state)))))))

(defn- atomic-increment-step [{:keys [world]} _]
  (-> (kernel/step-complete :done)
      (kernel/with-world
       (update world :counter inc))))

(deftest scripted-witness-exposes-lost-update-and-atomic-step-is-the-control
  (let [racy
        (kernel/run
         {:tasks {0 (kernel/runnable {:pc :read})
                  1 (kernel/runnable {:pc :read})}
          :world {:counter 0}
          :step racy-increment-step
          :strategy (strategy/scripted [0 1 0 1])})
        atomic
        (kernel/run
         {:tasks {0 (kernel/runnable nil)
                  1 (kernel/runnable nil)}
          :world {:counter 0}
          :step atomic-increment-step
          :strategy (strategy/scripted [0 1])})]
    (testing "the read/read/write/write witness loses one increment"
      (is (= :completed (:status racy)))
      (is (= 1 (get-in racy [:world :counter])))
      (is (= [0 1 0 1]
             (mapv #(nth % 4) (choice-events racy)))))
    (testing "one atomic transition per increment preserves both"
      (is (= :completed (:status atomic)))
      (is (= 2 (get-in atomic [:world :counter]))))))

(deftest scripted-schedules-fail-on-exhaustion
  (let [data
        (caught-data
         #(kernel/run
           {:tasks {0 (kernel/runnable 0)}
            :step (fn [_ state]
                    (if (zero? state)
                      (kernel/step-yield 1)
                      (kernel/step-complete :done)))
            :strategy (strategy/scripted [0])}))]
    (is (= :jolt.sim.strategy/script-exhausted (:type data)))))
