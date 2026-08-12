(ns jolt.sim.retained-process-test
  (:require [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]))

(defn- ex-data-of [thunk]
  (try
    (thunk)
    nil
    (catch :default error
      (ex-data error))))

(defn- private-var [symbol-name]
  (resolve (symbol "jolt.sim.retained-process" symbol-name)))

(defn- test-paths []
  (let [dir (str (fs/create-temp-dir
                  {:prefix "jolt-sim-retained-parent-test-"}))]
    {:dir dir
     :paths (@(private-var "create-paths!") dir)}))

(defn- test-state [dir paths]
  {:status :ready
   :instance-id "test-instance"
   :artifact-dir dir
   :paths paths
   :child :fake-child
   :pid 37
   :exit nil
   :next-sequence 0
   :uncertain-sequence nil
   :last-receipt nil
   :command-timeout-ms 100
   :kill-grace-ms 5})

(defn- test-handle [dir paths]
  (@(private-var "make-handle") (test-state dir paths)))

(defn- retain-artifacts! [& dirs]
  (doseq [dir dirs]
    (println "retained protocol test artifacts:" dir))
  (flush))

(defn- receipt-document [status sequence payload]
  (cond->
   {:jolt.sim.retained/protocol 1
    :jolt.sim.retained/instance-id "test-instance"
    :jolt.sim.retained/sequence sequence
    :jolt.sim.retained/status status}
    (= :completed status)
    (assoc :jolt.sim.retained/value (trace/canonical-value payload))

    (= :failed status)
    (assoc :jolt.sim.retained/error (trace/canonical-value payload))))

(defn- write-receipt! [paths status sequence payload]
  (let [path (str (fs/path (:receipts paths)
                           (format "receipt-%020d.edn" sequence)))]
    (spit path (trace/canonical-edn
                (receipt-document status sequence payload)))
    path))

(deftest start-validates-before-creating-artifacts
  (doseq [[config reason]
          [[nil :config-not-a-map]
           [{:worker-command []
             :adapter 'fixture/run!
             :dir "/tmp"}
            :invalid-worker-command]
           [{:worker-command ["jolt"]
             :adapter 'unqualified
             :dir "/tmp"}
            :invalid-adapter]
           [{:worker-command ["jolt"]
             :adapter 'fixture/run!
             :dir "/tmp"
             :command-timeout-ms 0}
            :invalid-timeout]]]
    (let [data (ex-data-of #(retained/start! config))]
      (is (= :jolt.sim.retained-process/invalid-config (:type data)))
      (is (= reason (:reason data))))))

(deftest start-launches-the-exact-closed-worker-boundary
  (let [process-var (resolve 'jolt.process/process)
        wait-var (private-var "wait-for-path-or-exit")
        pid-var (private-var "child-pid")
        alive-var (private-var "child-alive?")
        decode-var (private-var "decode-ready")
        captured (atom nil)
        temp-root (str (fs/create-temp-dir
                        {:prefix "jolt-sim-retained-start-root-"}))
        result
        (with-redefs-fn
          {process-var
           (fn [command options]
             (reset! captured {:command command :options options})
             :fake-child)
           wait-var (fn [_ path _]
                      ;; Arguments are evaluated before the mocked decoder;
                      ;; publish one complete placeholder so this fake wait
                      ;; cannot claim a nonexistent ready document is present.
                      (spit path "{}")
                      :present)
           pid-var (fn [_] 88)
           alive-var (fn [_] true)
           decode-var (fn [_ _] :ready)}
          #(let [handle
                 (retained/start!
                  {:worker-command ["sim-jolt" "-M:retained-worker"]
                   :adapter 'fixture/run!
                   :input {:seed 4}
                   :dir "/work"
                   :temp-dir temp-root
                   :startup-timeout-ms 25
                   :command-timeout-ms 50
                   :kill-grace-ms 5})]
             {:handle handle :snapshot (retained/snapshot handle)}))
        snap (:snapshot result)
        artifact-dir (get-in snap [:artifact :dir])
        command (:command @captured)
        options (:options @captured)]
    (try
      (is (= ["sim-jolt" "-M:retained-worker"] (subvec command 0 2)))
      (is (= 7 (count command)))
      (is (= [(get-in snap [:artifact :startup])
              (get-in snap [:artifact :ready])
              (get-in snap [:artifact :commands])
              (get-in snap [:artifact :receipts])
              (get-in snap [:artifact :terminal])]
             (subvec command 2)))
      (is (= "/work" (:dir options)))
      (is (= (get-in snap [:artifact :stdout]) (:out options)))
      (is (= (get-in snap [:artifact :stderr]) (:err options)))
      (is (= :ready (:status snap)))
      (is (= 88 (get-in snap [:child :pid])))
      (is (= #{:jolt.sim.retained/protocol
               :jolt.sim.retained/instance-id
               :jolt.sim.retained/adapter
               :jolt.sim.retained/input}
             (set (keys ((private-var "read-document")
                         (get-in snap [:artifact :startup]))))))
      (finally
        (retain-artifacts! artifact-dir temp-root)))))

(deftest startup-cleanup-failure-retains-child-ownership-coordinates
  (let [process-var (resolve 'jolt.process/process)
        wait-var (private-var "wait-for-path-or-exit")
        pid-var (private-var "child-pid")
        alive-var (private-var "child-alive?")
        terminate-var (private-var "terminate-and-reap-child!")
        temp-root (str (fs/create-temp-dir
                        {:prefix "jolt-sim-retained-cleanup-root-"}))
        data
        (with-redefs-fn
          {process-var (fn [_ _] :cleanup-child)
           wait-var (fn [_ _ _]
                      (throw (ex-info "startup observation failed" {})))
           pid-var (fn [_] 991)
           alive-var (fn [_] true)
           terminate-var (fn [_ _]
                           (throw (ex-info "child survived cleanup" {})))}
          #(ex-data-of
            (fn []
              (retained/start!
               {:worker-command ["sim-jolt"]
                :adapter 'fixture/run!
                :dir "/work"
                :temp-dir temp-root
                :startup-timeout-ms 25
                :command-timeout-ms 50
                :kill-grace-ms 5}))))]
    (is (= :jolt.sim.retained-process/transport-error (:type data)))
    (is (= :startup-cleanup-failed (:reason data)))
    (is (= 991 (:pid data)))
    (is (= :startup (:phase (:startup-error data))))
    (is (= :startup-cleanup (:phase (:cleanup-error data))))
    (is (fs/exists? (:artifact-dir data)))
    (retain-artifacts! (:artifact-dir data) temp-root)))

(deftest completed-and-application-failed-receipts-remain-distinct
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        wait-var (private-var "wait-for-path-or-exit")
        alive-var (private-var "child-alive?")
        statuses (atom [[:completed {:answer 42}]
                        [:failed {:type :application/rejected}]])]
    (try
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var
         (fn [_ _ _]
           (let [sequence (- 2 (count @statuses))
                 [status payload] (first @statuses)]
             (swap! statuses subvec 1)
             (write-receipt! paths status sequence payload)
             :present))}
        (fn []
          (is (= {:status :completed :sequence 0 :value {:answer 42}}
                 (retained/command! handle {:op :inspect})))
          (is (= {:status :failed
                  :sequence 1
                  :error {:type :application/rejected}}
                 (retained/command! handle {:op :deliver})))))
      (with-redefs-fn
        {alive-var (fn [_] true)}
        (fn []
          (let [snap (datafy/datafy handle)]
            (is (= :ready (:status snap)))
            (is (= 2 (:next-sequence snap)))
            (is (= {:status :failed :sequence 1} (:last-receipt snap)))
            (is (not (.contains (pr-str snap) ":application/rejected"))))))
      (finally
        (retain-artifacts! dir)))))

(deftest prepublication-failure-carries-no-reconcilable-coordinate
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        publish-var (private-var "publish-command!")
        alive-var (private-var "child-alive?")]
    (try
      (with-redefs-fn
        {alive-var (fn [_] true)
         publish-var (fn [_ _ _]
                       (throw (ex-info "publication refused" {})))}
        (fn []
          (let [failed (ex-data-of
                        #(retained/command! handle {:op :inspect} 5))]
            (is (= :publication-failed (:reason failed)))
            (is (= :failed (:status failed)))
            (is (= 0 (:sequence failed)))
            (is (nil? (:uncertain-sequence failed))))
          (let [nothing (ex-data-of #(retained/reconcile! handle 5))]
            (is (= :nothing-to-reconcile (:reason nothing)))
            (is (nil? (:uncertain-sequence nothing))))))
      (finally
        (retain-artifacts! dir)))))

(deftest post-publication-timeout-is-ambiguous-until-exact-reconciliation
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        wait-var (private-var "wait-for-path-or-exit")
        alive-var (private-var "child-alive?")]
    (try
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var (fn [_ _ _] :deadline)}
        (fn []
          (let [timed-out
                (ex-data-of
                 #(retained/command! handle {:op :submit} 7))]
            (is (= :jolt.sim.retained-process/transport-error
                   (:type timed-out)))
            (is (= :receipt-deadline (:reason timed-out)))
            (is (= :uncertain (:status timed-out)))
            (is (= 0 (:sequence timed-out)))
            (is (= 0 (:uncertain-sequence timed-out))))
          (let [rejected
                (ex-data-of
                 #(retained/command! handle {:op :inspect} 7))]
            (is (= :uncertain-command (:reason rejected)))
            (is (= 0 (:sequence rejected))))))
      (is (fs/exists?
           (str (fs/path (:commands paths)
                         "command-00000000000000000000.edn"))))
      (is (not (fs/exists?
                (str (fs/path (:commands paths)
                              "command-00000000000000000001.edn")))))
      (write-receipt! paths :completed 0 {:snapshot :stable})
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var (fn [_ _ _] :present)}
        (fn []
          (is (= {:status :completed
                  :sequence 0
                  :value {:snapshot :stable}}
                 (retained/reconcile! handle 7)))))
      (with-redefs-fn
        {alive-var (fn [_] true)}
        (fn []
          (let [snap (retained/snapshot handle)]
            (is (= :ready (:status snap)))
            (is (= 1 (:next-sequence snap)))
            (is (nil? (:uncertain-sequence snap))))))
      ;; A second capability can adopt the already-settled immutable receipt.
      ;; It must not disturb a newer uncertainty owned by another publisher.
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var (fn [_ _ _] :deadline)}
        (fn []
          (is (= :receipt-deadline
                 (:reason
                  (ex-data-of
                   #(retained/command! handle {:op :later} 7)))))))
      (is (= {:status :completed
              :sequence 0
              :value {:snapshot :stable}}
             (retained/reconcile-sequence! handle 0 7)))
      (with-redefs-fn
        {alive-var (fn [_] true)}
        (fn []
          (let [snap (retained/snapshot handle)]
            (is (= :uncertain (:status snap)))
            (is (= 1 (:uncertain-sequence snap)))
            (is (= 1 (:next-sequence snap))))))
      (write-receipt! paths :completed 1 {:later true})
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var (fn [_ _ _] :present)}
        (fn []
          (is (= {:status :completed :sequence 1 :value {:later true}}
                 (retained/reconcile-sequence! handle 1 7)))))
      (finally
        (retain-artifacts! dir)))))

(deftest child-exit-is-a-transport-failure-not-an-application-receipt
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        wait-var (private-var "wait-for-path-or-exit")
        alive-var (private-var "child-alive?")
        exit-var (private-var "reaped-exit")
        alive-observations (atom 0)]
    (try
      (with-redefs-fn
        {alive-var (fn [_]
                     ;; command! first proves the child commandable; after the
                     ;; publication wait reports exit, observe-exit sees dead.
                     (= 1 (swap! alive-observations inc)))
         exit-var (fn [_] 23)
         wait-var (fn [_ _ _] :exited)}
        (fn []
          (let [data (ex-data-of
                      #(retained/command! handle {:op :inspect} 10))]
            (is (= :jolt.sim.retained-process/transport-error (:type data)))
            (is (= :child-exited (:reason data)))
            (is (= 23 (:exit data)))
            (is (= 0 (:uncertain-sequence data)))
            (is (not= :failed (:status data))))
          (let [snap (retained/snapshot handle)]
            (is (= :exited (:status snap)))
            (is (= 23 (get-in snap [:child :exit])))
            (is (= 0 (:uncertain-sequence snap))))))
      (is (fs/exists?
           (str (fs/path (:commands paths)
                         "command-00000000000000000000.edn"))))
      (finally
        (retain-artifacts! dir)))))

(deftest exact-receipt-wins-reconciliation-after-child-exit
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        wait-var (private-var "wait-for-path-or-exit")
        alive-var (private-var "child-alive?")
        exit-var (private-var "reaped-exit")]
    (try
      (with-redefs-fn
        {alive-var (fn [_] true)
         wait-var (fn [_ _ _] :deadline)}
        (fn []
          (is (= :receipt-deadline
                 (:reason
                  (ex-data-of
                   #(retained/command! handle {:op :inspect} 5)))))))
      ;; Observe the exit before the exact receipt is inspected. This must not
      ;; erase the uncertain coordinate or prevent reconciliation.
      (with-redefs-fn
        {alive-var (fn [_] false)
         exit-var (fn [_] 0)}
        (fn []
          (let [snap (retained/snapshot handle)]
            (is (= :exited (:status snap)))
            (is (= 0 (:uncertain-sequence snap))))))
      (write-receipt! paths :completed 0 {:snapshot :committed})
      (with-redefs-fn
        {alive-var (fn [_] false)
         exit-var (fn [_] 0)
         wait-var (fn [_ _ _] :present)}
        (fn []
          (is (= {:status :completed
                  :sequence 0
                  :value {:snapshot :committed}}
                 (retained/reconcile! handle 5)))
          (let [snap (retained/snapshot handle)]
            (is (= :exited (:status snap)))
            (is (nil? (:uncertain-sequence snap)))
            (is (= 1 (:next-sequence snap))))
          (is (= :child-exited
                 (:reason
                  (ex-data-of
                   #(retained/command! handle {:op :inspect} 5)))))))
      (finally
        (retain-artifacts! dir)))))

(deftest terminate-owns-one-bounded-reap-and-retains-artifacts
  (let [{:keys [dir paths]} (test-paths)
        handle (test-handle dir paths)
        alive-var (private-var "child-alive?")
        terminate-var (private-var "terminate-and-reap-child!")
        calls (atom [])]
    (try
      (with-redefs-fn
        {alive-var (fn [_] true)
         terminate-var
         (fn [child grace-ms]
           (swap! calls conj [child grace-ms])
           143)}
        (fn []
          (let [first-snapshot (retained/terminate! handle)
                second-snapshot (retained/terminate! handle)]
            (is (= [[:fake-child 5]] @calls))
            (is (= :terminated (:status first-snapshot)))
            (is (= 143 (get-in first-snapshot [:child :exit])))
            (is (= first-snapshot second-snapshot))
            (is (= dir (get-in first-snapshot [:artifact :dir])))
            (is (fs/exists? dir)))))
      (finally
        (retain-artifacts! dir)))))
