(ns jolt.sim.retained-view-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.retained-view :as retained-view]))

(defn- supervisor-snapshot
  ([] (supervisor-snapshot :ready))
  ([status]
   {:protocol 1
    :instance-id "retained-test-1"
    :status status
    :next-sequence 4
    :uncertain-sequence nil
    :last-receipt {:status :completed :sequence 3 :ignored :parent-only}
    :child {:pid 4242
            :alive? (= :ready status)
            :exit (when-not (= :ready status) 0)
            :opaque-child (Object.)}
    :artifact {:dir "/tmp/private-run"
               :stdout "/tmp/private-run/stdout.log"}
    :diagnostics {:stdout {:bytes 17
                           :truncated? false
                           :text "secret stdout"
                           :error nil}
                  :stderr {:bytes 70000
                           :truncated? true
                           :text "secret stderr"
                           :error {:message "/tmp/private-run leaked"}}}
    :future-supervisor-field (Object.)}))

(def expected-ready-frame
  {:jolt.sim.retained-view/type :frame
   :kind :jolt.sim.kind/retained-process-frame
   :protocol 1
   :instance-id "retained-test-1"
   :status :ready
   :next-sequence 4
   :uncertain-sequence nil
   :last-receipt {:status :completed :sequence 3}
   :worker {:alive? true :exit nil}
   :diagnostics {:stdout {:bytes 17
                          :truncated? false
                          :read-error? false}
                 :stderr {:bytes 70000
                          :truncated? true
                          :read-error? true}}})

(defn- caught [f]
  (try
    (f)
    nil
    (catch :default error error)))

(deftest read-frame-is-a-closed-redacted-projection
  (let [snapshot-calls (atom 0)
        handle (Object.)]
    (with-redefs [retained/snapshot
                  (fn [actual]
                    (is (identical? handle actual))
                    (swap! snapshot-calls inc)
                    (supervisor-snapshot))]
      (is (= expected-ready-frame (retained-view/read-frame handle)))
      (is (= 1 @snapshot-calls)))))

(deftest command-frame-preserves-completed-application-receipt
  (let [command-calls (atom [])
        snapshot-calls (atom 0)
        handle (Object.)
        command {:op :inspect}
        receipt {:status :completed
                 :sequence 4
                 :value {:rows [1 2] :empty nil}}]
    (with-redefs [retained/command!
                  (fn [actual value]
                    (swap! command-calls conj [actual value])
                    receipt)
                  retained/snapshot
                  (fn [actual]
                    (is (identical? handle actual))
                    (swap! snapshot-calls inc)
                    (supervisor-snapshot))]
      (let [result (retained-view/command-frame! handle command)]
        (is (= :completed (:status result)))
        (is (true? (:committed? result)))
        (is (= :command (:operation result)))
        (is (= receipt (:receipt result)))
        (is (= expected-ready-frame (:frame result)))
        (is (nil? (:frame-error result)))
        (is (= [[handle command]] @command-calls))
        (is (= 1 @snapshot-calls))))))

(deftest command-frame-preserves-failed-application-receipt
  (let [command-calls (atom 0)
        handle (Object.)
        receipt {:status :failed
                 :sequence 4
                 :error {:type :example/rejected
                         :reason :hostile-ack}}]
    (with-redefs [retained/command!
                  (fn [actual command]
                    (is (identical? handle actual))
                    (is (= {:op :deliver} command))
                    (swap! command-calls inc)
                    receipt)
                  retained/snapshot (fn [_] (supervisor-snapshot))]
      (let [result (retained-view/command-frame!
                    handle {:op :deliver})]
        (is (= :failed (:status result)))
        (is (true? (:committed? result)))
        (is (= receipt (:receipt result)))
        (is (= 1 @command-calls))))))

(deftest command-frame-may-observe-a-later-concurrent-repl-coordinate
  (let [handle (Object.)
        receipt {:status :completed :sequence 4 :value :accepted}
        later (-> (supervisor-snapshot)
                  (assoc :status :uncertain
                         :next-sequence 6
                         :uncertain-sequence 6
                         :last-receipt {:status :completed :sequence 5}))]
    (with-redefs [retained/command! (fn [_ _] receipt)
                  retained/snapshot (fn [_] later)]
      (let [result (retained-view/command-frame! handle {:op :inspect})]
        (is (= receipt (:receipt result)))
        (is (= 6 (get-in result [:frame :next-sequence])))
        (is (= 6 (get-in result [:frame :uncertain-sequence])))
        (is (= :uncertain (get-in result [:frame :status])))))))

(deftest post-receipt-frame-failure-is-not-command-ambiguity
  (let [command-calls (atom 0)
        snapshot-calls (atom 0)
        receipt {:status :completed :sequence 4 :value :accepted}
        error (ex-info "private path" {:type :example/read-failed
                                       :reason :snapshot-race
                                       :artifact-dir "/tmp/private-run"})]
    (with-redefs [retained/command!
                  (fn [_ _]
                    (swap! command-calls inc)
                    receipt)
                  retained/snapshot
                  (fn [_]
                    (swap! snapshot-calls inc)
                    (throw error))]
      (let [result (retained-view/command-frame! (Object.) :once)]
        (is (= :completed (:status result)))
        (is (true? (:committed? result)))
        (is (= receipt (:receipt result)))
        (is (nil? (:frame result)))
        (is (= {:type :example/read-failed
                :phase :post-receipt
                :reason :snapshot-race}
               (:frame-error result)))
        (is (= 1 @command-calls))
        (is (= 1 @snapshot-calls))))))

(deftest command-transport-error-propagates-without-frame-read
  (let [command-calls (atom 0)
        snapshot-calls (atom 0)
        error (ex-info "uncertain" {:type :jolt.sim.retained-process/transport-error
                                    :reason :receipt-deadline})
        caught-error
        (with-redefs [retained/command!
                      (fn [_ _]
                        (swap! command-calls inc)
                        (throw error))
                      retained/snapshot
                      (fn [_]
                        (swap! snapshot-calls inc)
                        (supervisor-snapshot))]
          (caught #(retained-view/command-frame! (Object.) :once)))]
    (is (identical? error caught-error))
    (is (= 1 @command-calls))
    (is (zero? @snapshot-calls))))

(deftest reconcile-and-terminate-delegate-exactly-once
  (let [reconcile-calls (atom 0)
        terminate-calls (atom 0)
        snapshot-calls (atom 0)
        handle (Object.)
        receipt {:status :completed :sequence 4 :value {:recovered true}}]
    (with-redefs [retained/reconcile!
                  (fn [actual]
                    (is (identical? handle actual))
                    (swap! reconcile-calls inc)
                    receipt)
                  retained/terminate!
                  (fn [actual]
                    (is (identical? handle actual))
                    (swap! terminate-calls inc)
                    (supervisor-snapshot :terminated))
                  retained/snapshot
                  (fn [actual]
                    (is (identical? handle actual))
                    (swap! snapshot-calls inc)
                    (supervisor-snapshot))]
      (let [reconciled (retained-view/reconcile-frame! handle)
            terminated (retained-view/terminate-frame! handle)]
        (is (= :reconcile (:operation reconciled)))
        (is (= receipt (:receipt reconciled)))
        (is (= :terminated (:status terminated)))
        (is (= {:alive? false :exit 0} (:worker terminated)))
        (is (= 1 @reconcile-calls))
        (is (= 1 @terminate-calls))
        ;; Reconciliation refreshes once. Termination projects the authoritative
        ;; snapshot returned by terminate! and must not perform another read.
        (is (= 1 @snapshot-calls))))))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.retained-view-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
