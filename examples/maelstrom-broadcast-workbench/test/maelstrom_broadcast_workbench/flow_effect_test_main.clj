(ns maelstrom-broadcast-workbench.flow-effect-test-main
  "Fresh-process proof that project-local cells control the real Broadcast app.

  Every application operation is emitted by a pure flow cell and published by
  flow-effect-session only after an exact branch commit. The unchanged retained
  adapter still invokes the real Broadcast handlers, connection gate, memory
  transport, retry-pending!, and lifecycle."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]
            [maelstrom-broadcast-workbench.flow-retained :as flow-retained]))

(def ^:private failures (atom 0))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required") {:name name})))
    value))

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_BROADCAST_FLOW_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-broadcast-flow-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (try
    (spit path (str (pr-str record) "\n") :append true)
    (catch :default error
      (println "progress write failed:" (ex-message error))
      (flush))))

(defn- check [label expected actual]
  (if (= expected actual)
    (println "ok  " label)
    (do
      (swap! failures inc)
      (println "FAIL" label
               "\n expected:" (pr-str expected)
               "\n actual:  " (pr-str actual)))))

(defn- child-alive? [handle]
  (boolean (get-in (retained/snapshot handle) [:child :alive?])))

(defn- wait-for-exit [handle timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [snapshot (retained/snapshot handle)]
      (if (or (not (get-in snapshot [:child :alive?]))
              (>= (System/nanoTime) deadline))
        snapshot
        (do (Thread/sleep 10)
            (recur (retained/snapshot handle)))))))

(defn- commit-command! [service handle command]
  (let [before (retained/snapshot handle)
        before-sequence (:next-sequence before)
        bridge (effect-session/attach!
                {:sim (flow-retained/command-flow command)
                 :worker service
                 :effect-kind flow-retained/effect-kind})
        previews (effect-session/branches bridge)
        after-preview (retained/snapshot handle)
        branch (:branch (first previews))]
    (check (str "one branch for " (:op command)) 1 (count previews))
    (check (str "preview publishes nothing for " (:op command))
           before-sequence (:next-sequence after-preview))
    (let [result (effect-session/step! bridge branch)
          after (retained/snapshot handle)
          record (get-in result [:delivery :effects :records 0])]
      (check (str "exact commit accepted for " (:op command))
             true (:committed? result))
      (check (str "one retained sequence consumed for " (:op command))
             (inc before-sequence) (:next-sequence after))
      (check (str "effect settles for " (:op command))
             :settled (:state record))
      (:receipt record))))

(defn- completed-value! [label receipt]
  (check (str label " receipt completed") :completed (:status receipt))
  (:value receipt))

(defn- app-snapshot [value]
  (if (= :jolt.sim/maelstrom-broadcast-live (:kind value))
    value
    (:snapshot value)))

(defn- node-messages [snapshot]
  (into (sorted-map)
        (map (fn [[node-id state]] [node-id (:messages state)])
             (:nodes snapshot))))

(defn- drain! [service handle value]
  (loop [snapshot (app-snapshot value)
         steps 0]
    (when (> steps 100)
      (throw (ex-info "Broadcast flow drain exceeded its exact step bound"
                      {:steps steps})))
    (if-let [node-id (first (:ready-mailboxes snapshot))]
      (let [receipt (commit-command! service handle
                                     {:op :step :node-id node-id})
            value (completed-value! "step" receipt)]
        (recur (app-snapshot value) (inc steps)))
      {:snapshot snapshot :steps steps})))

(defn- run-scenario! [progress]
  (let [handle
        (retained/start!
         (flow-retained/retained-config
          (required-environment "JOLT_SIM_BIN")
          (required-environment "JOLT_SIM_PROJECT_DIR")
          {:message 42 :regime :healthy}))
        service (effect-session/retained-worker-service handle)
        artifact-dir (get-in (retained/snapshot handle) [:artifact :dir])]
    (println "Broadcast flow artifacts:" artifact-dir)
    (append-progress! progress {:phase :worker-ready
                                :artifact-dir artifact-dir})
    (try
      (let [bootstrap
            (completed-value! "bootstrap"
                              (commit-command! service handle {:op :bootstrap}))
            bootstrapped (app-snapshot bootstrap)]
        (check "bootstrap enqueues the seven official openers"
               {"n1" 3 "n2" 2 "n3" 2}
               (into (sorted-map)
                     (map (fn [[id mailbox]] [id (:count mailbox)])
                          (:mailboxes bootstrapped))))

        (let [drop-command {:op :set-connection-regime
                            :connection ["n2" "n3"]
                            :expected-revision 0
                            :regime :drop}
              dropped
              (completed-value! "drop n2--n3"
                                (commit-command! service handle drop-command))
              partitioned (:snapshot (drain! service handle dropped))]
          (check "only the chosen connection is dropped"
                 {["n1" "n2"] :normal ["n2" "n3"] :drop}
                 (:connections partitioned))
          (check "ordinary Broadcast reaches n1 and n2 but not n3"
                 {"n1" [42] "n2" [42] "n3" []}
                 (node-messages partitioned))
          (check "the real connection gate records one dropped envelope"
                 1 (get-in partitioned [:drops :dropped-total]))

          (let [stale (commit-command!
                       service handle
                       {:op :set-connection-regime
                        :connection ["n1" "n2"]
                        :expected-revision 0
                        :regime :drop})]
            (check "stale regime command is a definite application failure"
                   :failed (:status stale))
            (check "stale failure preserves its exact reason"
                   :jolt.maelstrom.fixtures.broadcast-scenario/stale-regime-revision
                   (get-in stale [:error :type])))

          (let [inspected
                (completed-value! "inspect after stale failure"
                                  (commit-command! service handle {:op :inspect}))]
            (check "stale regime command leaves application state unchanged"
                   partitioned inspected))

          (let [restored
                (completed-value!
                 "restore n2--n3"
                 (commit-command!
                  service handle
                  {:op :set-connection-regime
                   :connection ["n2" "n3"]
                   :expected-revision 1
                   :regime :normal}))
                no-replay (drain! service handle restored)]
            (check "restore replays no dropped envelope"
                   0 (:steps no-replay))
            (check "n3 is still missing the message before retry"
                   [] (get-in no-replay [:snapshot :nodes "n3" :messages])))

          (let [retried
                (completed-value! "retry"
                                  (commit-command! service handle {:op :retry}))
                converged (:snapshot (drain! service handle retried))]
            (check "real retry converges all ordinary Broadcast nodes"
                   {"n1" [42] "n2" [42] "n3" [42]}
                   (node-messages converged))
            (check "receipt continuation observes the closed application facts"
                   true (:observed (flow-retained/observe retried)))

            (let [read-enqueued
                  (completed-value! "read"
                                    (commit-command! service handle {:op :read}))
                  read-complete (:snapshot (drain! service handle read-enqueued))]
              (check "read reply returns the converged application value"
                     [42]
                     (:read-messages (flow-retained/observe read-complete)))))))

      (let [stop-receipt (commit-command! service handle {:op :stop})
            _ (completed-value! "stop" stop-receipt)
            exited (wait-for-exit handle 3000)
            terminal-path (get-in exited [:artifact :terminal])
            terminal (when (fs/exists? terminal-path)
                       (edn/read-string (slurp terminal-path)))]
        (check "retained child exits zero" 0 (get-in exited [:child :exit]))
        (check "retained child is reaped" false (get-in exited [:child :alive?]))
        (check "terminal evidence is completed"
               :completed (:jolt.sim.retained/status terminal)))
      (finally
        (when (child-alive? handle)
          (retained/terminate! handle))))))

(defn -main [& _]
  (let [progress (progress-file)]
    (println "Broadcast flow progress:" progress)
    (append-progress! progress {:phase :start :status :running})
    (try
      (run-scenario! progress)
      (if (zero? @failures)
        (do
          (append-progress! progress {:phase :complete :status :passed})
          (println "Broadcast flow/effect scenario passed")
          (System/exit 0))
        (do
          (append-progress! progress {:phase :complete :status :failed
                                      :failures @failures})
          (println "Broadcast flow/effect scenario failed:" @failures)
          (System/exit 1)))
      (catch :default error
        (append-progress! progress
                          {:phase :error :status :errored
                           :error (trace/normalize-error error)})
        (println "Broadcast flow/effect scenario errored:"
                 (pr-str (trace/normalize-error error)))
        (println "progress:" progress)
        (flush)
        (System/exit 1)))))
