(ns outbox-workbench.flow-retained-test-main
  "Focused fresh-process gate for one bounded real outbox flow/effect vertical.

  Starts one retained canonical outbox worker with the exact config/image
  contract proven by retained_workbench_test_main, then drives the real
  HTTP -> SQLite -> TCP/bencode lifecycle through a jolt.sim.flow-effect-session
  bridge over a minimal example-local command flow.

  Proven in one process:
    - branch preview does not advance the retained next sequence or affect the
      application (no command is published by a preview);
    - an exact :submit flow commit publishes once and yields real HTTP 201 plus
      a durable :pending row before delivery and zero receiver requests;
    - that definite receipt is fed into the ordinary receipt-continuation flow
      and observed there;
    - an exact :deliver flow commit publishes once and yields the real
      TCP/bencode ack-gated :delivered row and one receiver request;
    - that receipt is likewise consumed by the continuation flow;
    - a duplicate/stale branch cannot republish;
    - graceful {:op :stop} publishes completed terminal evidence, the child
      exits 0 and is reaped; forced termination is only a bounded finally
      fallback.

  The retained artifact directory is preserved and printed. Progress is
  appended to JOLT_SIM_PROGRESS_FILE when available and is never erased on
  failure. The process ends with System/exit because jolt-http and the fixture
  load core.async, whose non-daemon threads keep the process alive."
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]
            [outbox-workbench.flow-retained :as flow-retained]))

(def ^:private watchdog-timeout-ms 180000)
(def ^:private failures (atom 0))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required") {:name name})))
    value))

;; ---- retained progress breadcrumbs -----------------------------------------

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-flow-retained-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  ;; Best-effort append-only breadcrumbs for a single test process. This is
  ;; not a crash-safe journal/WAL contract and is never erased on failure.
  (spit path (str (pr-str record) "\n") :append true))

;; ---- checks -----------------------------------------------------------------

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

(defn- wait-for-child-exit [handle timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop [snapshot (retained/snapshot handle)]
      (if (or (not (get-in snapshot [:child :alive?]))
              (>= (System/nanoTime) deadline))
        snapshot
        (do
          (Thread/sleep 10)
          (recur (retained/snapshot handle)))))))

(defn- run-scenario []
  (let [handle (retained/start!
                (flow-retained/retained-config
                 (required-environment "JOLT_SIM_BIN")
                 (required-environment "JOLT_SIM_PROJECT_DIR")))
        service (effect-session/retained-worker-service handle)]
    (println "flow-retained artifacts:"
             (get-in (retained/snapshot handle) [:artifact :dir]))
    (flush)
    (try
      ;; ---- submit flow ------------------------------------------------------
      (let [bridge (effect-session/attach!
                    {:sim (flow-retained/command-flow
                           {:op :submit :command outbox/default-command})
                     :worker service
                     :effect-kind :example.outbox/command})
            previews (effect-session/branches bridge)
            branch (get-in previews [0 :branch])]
        (check "submit flow exposes exactly one branch"
               1 (count previews))
        (check "preview does not advance retained next sequence"
               0 (get-in (retained/snapshot handle) [:next-sequence]))
        (check "preview publishes no command (last receipt stays nil)"
               nil (get-in (retained/snapshot handle) [:last-receipt]))
        (let [result (effect-session/step! bridge branch)]
          (check "submit flow commit is authoritative"
                 true (:committed? result))
          (check "submit flow publishes once and settles ready"
                 :ready (get-in result [:delivery :status]))
          (let [record (get-in result [:delivery :effects :records 0])
                receipt (:receipt record)
                value (:value receipt)]
            (check "submit receipt is a settled completed record"
                   :settled (:state record))
            (check "submit receipt status is completed"
                   :completed (:status record))
            (check "submit yields real HTTP 201"
                   201 (get-in value [:result :status]))
            (check "submit durable row is pending before delivery"
                   :pending (get-in value [:snapshot :store-state :outbox 0 :status]))
            (check "submit has zero receiver requests"
                   0 (get-in value [:snapshot :receiver-requests :count]))
            (check "submit receipt is observed by the continuation flow"
                   {:operation :submit
                    :row-status :pending
                    :receiver-requests 0
                    :http-status 201
                    :observed true}
                   (flow-retained/observe value))))
        (check "retained next sequence advanced exactly once"
               1 (get-in (retained/snapshot handle) [:next-sequence]))
        (let [stale-type
              (try
                (effect-session/step! bridge branch)
                :no-throw
                (catch :default error (:type (ex-data error))))]
          (check "duplicate/stale branch cannot republish"
                 :jolt.sim.session/stale-branch stale-type))
        (check "no duplicate command was published"
               1 (get-in (retained/snapshot handle) [:next-sequence])))

      ;; ---- deliver flow -----------------------------------------------------
      (let [bridge (effect-session/attach!
                    {:sim (flow-retained/command-flow {:op :deliver})
                     :worker service
                     :effect-kind :example.outbox/command})
            branch (get-in (effect-session/branches bridge) [0 :branch])
            result (effect-session/step! bridge branch)]
        (check "deliver flow commit is authoritative"
               true (:committed? result))
        (check "deliver flow publishes once and settles ready"
               :ready (get-in result [:delivery :status]))
        (let [record (get-in result [:delivery :effects :records 0])
              value (:value (:receipt record))]
          (check "deliver yields ack-gated delivered row"
                 :delivered (get-in value [:snapshot :store-state :outbox 0 :status]))
          (check "deliver has exactly one receiver request"
                 1 (get-in value [:snapshot :receiver-requests :count]))
          (check "deliver receipt is observed by the continuation flow"
                 {:operation :deliver
                  :row-status :delivered
                  :receiver-requests 1
                  :observed true}
                 (flow-retained/observe value))))

      ;; ---- graceful stop ----------------------------------------------------
      (let [stop (retained/command! handle {:op :stop})
            after (wait-for-child-exit handle 3000)
            terminal-path (get-in after [:artifact :terminal])
            terminal (when (fs/exists? terminal-path)
                       (edn/read-string (slurp terminal-path)))]
        (check "graceful stop command completed"
               :completed (:status stop))
        (check "stop published completed terminal evidence"
               :completed (:jolt.sim.retained/status terminal))
        (check "retained child exited 0"
               0 (get-in after [:child :exit]))
        (check "retained child is reaped"
               false (get-in after [:child :alive?])))

      {:artifact-dir (get-in (retained/snapshot handle) [:artifact :dir])}
      (finally
        ;; Forced termination is only a bounded finally fallback: the graceful
        ;; :stop path above is the primary shutdown transition.
        (when (get-in (retained/snapshot handle) [:child :alive?])
          (retained/terminate! handle))))))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "flow-retained progress:" progress)
    (flush)
    (let [task (future (run-scenario))
          outcome (try
                    {:result (deref task watchdog-timeout-ms ::timeout)}
                    (catch :default error {:error error}))]
      (cond
        (:error outcome)
        (do
          (append-progress! progress
                            {:phase :error :status :errored
                             :error (trace/normalize-error (:error outcome))})
          (println "FAILURE:"
                   (pr-str (trace/normalize-error (:error outcome))))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (= ::timeout (:result outcome))
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :timeout-ms watchdog-timeout-ms})
          (println "FAILURE: flow-retained test timed out")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (pos? @failures)
        (do
          (append-progress! progress
                            {:phase :finish :status :failed
                             :failures @failures
                             :result (:result outcome)})
          (println "FAILURE:" @failures "checks failed")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        :else
        (do
          (append-progress! progress
                            {:phase :finish :status :passed
                             :result (:result outcome)})
          (println "PASS: flow-retained vertical")
          (println "progress:" progress)
          (flush)
          (System/exit 0))))))
