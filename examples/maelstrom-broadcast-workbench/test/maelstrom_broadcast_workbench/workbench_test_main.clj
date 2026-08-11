(ns maelstrom-broadcast-workbench.workbench-test-main
  "Real loopback acceptance for shared Ripple/REPL control of Broadcast.

  Every application action crosses the retained worker protocol. One step is
  deliberately submitted through Ripple's persistent EvalSession; the
  surrounding retained-panel HTTP calls prove it advanced the same worker and
  command sequence. All child artifacts and progress breadcrumbs are retained."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]
            [maelstrom-broadcast-workbench.main :as workbench]
            [teensyp.client :as client]))

(def ^:private token "broadcast-workbench-test-token-00000001")
(def ^:private watchdog-timeout-ms 240000)
(def ^:private request-timeout-ms 60000)
(def ^:private cleanup-timeout-ms 70000)
(def ^:private failures (atom 0))
(def ^:private progress-error-limit 32)
(defonce ^:private progress-write-errors* (atom []))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required") {:name name})))
    value))

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_BROADCAST_WORKBENCH_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-broadcast-workbench-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  ;; These are best-effort append-only process breadcrumbs, not a WAL claim.
  (spit path (str (pr-str record) "\n") :append true))

(defn- bounded-error [error]
  (let [message (str (or (ex-message error) error))]
    {:class (str (class error))
     :message (if (> (count message) 1024)
                (str (subs message 0 1024) "...")
                message)
     :type (:type (ex-data error))
     :reason (:reason (ex-data error))}))

(defn- emit-progress-with! [writer path record]
  ;; Progress persistence is strictly observational. It may fail while the
  ;; process still owns a live child, so neither the write, in-memory capture,
  ;; nor stderr fallback is allowed to control cleanup.
  (try
    (writer path record)
    {:written? true}
    (catch :default error
      (let [diagnostic {:phase (:phase record)
                        :path path
                        :error (bounded-error error)}]
        (try
          (swap! progress-write-errors*
                 (fn [entries]
                   (->> (conj entries diagnostic)
                        (take-last progress-error-limit)
                        vec)))
          (catch :default _ nil))
        (try
          (binding [*out* *err*]
            (println "Broadcast workbench progress write failed:"
                     (pr-str diagnostic))
            (flush))
          (catch :default _ nil))
        {:written? false :error diagnostic}))))

(defn- emit-progress! [path record]
  (emit-progress-with! append-progress! path record))

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

;; ---- minimal real-loopback HTTP client -------------------------------------

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        destination (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (let [length (alength chunk)]
          (dotimes [index length]
            (aset destination (+ offset index) (aget chunk index)))
          (recur (rest remaining) (+ offset length)))
        destination))))

(defn- find-header-terminator [^bytes raw]
  (let [length (alength raw)]
    (loop [index 0]
      (cond
        (> (+ index 4) length) nil
        (and (= 13 (bit-and 0xff (aget raw index)))
             (= 10 (bit-and 0xff (aget raw (+ index 1))))
             (= 13 (bit-and 0xff (aget raw (+ index 2))))
             (= 10 (bit-and 0xff (aget raw (+ index 3))))) index
        :else (recur (inc index))))))

(defn- read-until-eof! [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (if-let [length (client/receive-into!
                       connection scratch 0 (alength scratch)
                       {:timeout-ms request-timeout-ms})]
        (recur (conj chunks
                     (java.util.Arrays/copyOfRange scratch 0 length)))
        (concat-byte-arrays chunks)))))

(defn- request-json! [port method path body]
  (let [payload (when body (json/write-str body))
        request-text
        (str method " " path " HTTP/1.1\r\n"
             "Host: 127.0.0.1:" port "\r\n"
             "Accept: application/json\r\n"
             "X-Jolt-Sim-Capability: " token "\r\n"
             (when payload
               (str "Content-Type: application/json\r\n"
                    "Content-Length: "
                    (alength (.getBytes payload "UTF-8")) "\r\n"))
             "Connection: close\r\n\r\n"
             (or payload ""))
        connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms request-timeout-ms})]
    (try
      (client/send-all! connection (.getBytes request-text "UTF-8")
                        {:timeout-ms request-timeout-ms})
      (let [raw (read-until-eof! connection)
            terminator (find-header-terminator raw)]
        (when-not terminator
          (throw (ex-info "Ripple response has no header terminator"
                          {:path path :raw-length (alength raw)})))
        (let [head-bytes (java.util.Arrays/copyOfRange raw 0 terminator)
              body-start (+ terminator 4)
              body-bytes (java.util.Arrays/copyOfRange
                          raw body-start (alength raw))
              head (String. ^bytes head-bytes "UTF-8")
              status-line (first (string/split head #"\r\n"))
              [_ status] (re-matches #"HTTP/1\.1 (\d{3}) .*" status-line)]
          (when-not status
            (throw (ex-info "Ripple response has no valid status line"
                            {:path path :status-line status-line})))
          {:status (parse-long status)
           :wire (json/read-str (String. ^bytes body-bytes "UTF-8"))}))
      (finally
        (client/close! connection)))))

(defn- retained-command-http!
  [port label request-body expected-status sequences]
  (let [response (request-json! port "POST" "/api/retained-command"
                                request-body)
        receipt-text (get-in response [:wire "receiptEdn"])
        receipt (when receipt-text (edn/read-string receipt-text))]
    (check (str label " is definite") 200
           (:status response))
    (check (str label " has the expected application outcome") expected-status
           (:status receipt))
    (when receipt
      (swap! sequences conj (:sequence receipt)))
    {:response response :receipt receipt}))

(defn- command-http! [port command sequences]
  (retained-command-http!
   port (str "HTTP command " (:op command))
   {"version" 1 "commandEdn" (pr-str command)}
   :completed sequences))

(defn- canonical-command-http!
  ([port label action sequences]
   (canonical-command-http! port label action :completed sequences))
  ([port label action expected-status sequences]
   (retained-command-http!
    port label
    {"version" 1
     "commandCanonicalEdn" (get action "commandCanonicalEdn")}
    expected-status sequences)))

(defn- topology-action [response entity-key entity-id action-id]
  (let [entities (get-in response [:wire "presentation" "graph" entity-key])
        entity (some #(when (= entity-id (get % "id")) %) entities)
        action (some #(when (= action-id (get % "id")) %)
                     (get entity "actions"))]
    (when-not action
      (throw (ex-info "Ripple presentation omitted the requested topology action"
                      {:entity-key entity-key
                       :entity-id entity-id
                       :action-id action-id})))
    action))

(defn- application-snapshot [receipt]
  (if (contains? (:value receipt) :snapshot)
    (get-in receipt [:value :snapshot])
    (:value receipt)))

(defn- drain-ready! [port initial sequences]
  (loop [snapshot initial steps 0 last-response nil]
    (when (> steps 100)
      (throw (ex-info "Broadcast workbench drain exceeded its step bound"
                      {:steps steps})))
    (if-let [node-id (first (:ready-mailboxes snapshot))]
      (let [{:keys [receipt response]}
            (command-http! port {:op :step :node-id node-id} sequences)]
        (check "selected mailbox step delivered one envelope" true
               (get-in receipt [:value :result :delivered?]))
        (recur (application-snapshot receipt) (inc steps) response))
      {:snapshot snapshot :steps steps :response last-response})))

(defn- node-messages [snapshot]
  (into {} (map (fn [[id state]] [id (:messages state)]) (:nodes snapshot))))

(defn- envelope-ret [envelope]
  (some #(when (= :ret (:tag %)) %) (:events envelope)))

(defn- wait-for-exit [worker]
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop [snapshot (retained/snapshot worker)]
      (if (or (not (get-in snapshot [:child :alive?]))
              (>= (System/nanoTime) deadline))
        snapshot
        (do (Thread/sleep 10)
            (recur (retained/snapshot worker)))))))

(defn- config-error-reason [text]
  (try
    (workbench/parse-config-edn text "<test-config>")
    :no-throw
    (catch :default error (:reason (ex-data error)))))

(defn- run-scenario [progress instance]
  (let [worker (:worker instance)
        port (get-in instance [:server :port])
        sequences (atom [])
        artifact-dir (get-in (retained/snapshot worker) [:artifact :dir])]
    (println "Broadcast workbench artifacts:" artifact-dir)
    (emit-progress! progress {:phase :worker-started
                                :status :running
                                :artifact-dir artifact-dir
                                :port port})
    (flush)
    (check "Ripple bound an ephemeral loopback port" true (pos? port))
      (let [frame (request-json! port "GET" "/api/retained-frame" nil)]
        (check "retained frame endpoint is live" 200 (:status frame))
        (check "retained worker starts ready" "ready"
               (get-in frame [:wire "coordinate" "status"]))
        (check "browser frame redacts artifact paths" false
               (string/includes? (str (get-in frame [:wire "frameEdn"]))
                                 artifact-dir)))

      (let [{initial :receipt initial-response :response}
            (command-http! port {:op :inspect} sequences)
            snapshot (:value initial)]
        (check "initial application waits at created boundary" :created
               (:status snapshot))
        (check "initial application has no ready mailbox" []
               (:ready-mailboxes snapshot))
        (check "interactive example starts with both connections normal"
               {["n1" "n2"] :normal ["n2" "n3"] :normal}
               (:connections snapshot))
        (check "connection actions are disabled before bootstrap" false
               (get (topology-action initial-response "edges" "n2--n3" "drop")
                    "enabled")))

      (let [{boot :receipt boot-response :response}
            (command-http! port {:op :bootstrap} sequences)
            snapshot (application-snapshot boot)
            drop-action (topology-action boot-response "edges" "n2--n3" "drop")]
        (check "bootstrap enqueues all seven official openers" 7
               (get-in boot [:value :result :enqueued]))
        (check "bootstrap performs no hidden delivery"
               {"n1" 3 "n2" 2 "n3" 2}
               (into {} (map (fn [[id mailbox]] [id (:count mailbox)])
                             (:mailboxes snapshot))))
        (let [dropped (canonical-command-http!
                       port "rendered n2--n3 drop action" drop-action sequences)
              after-drop (application-snapshot (:receipt dropped))
              stale (canonical-command-http!
                     port "stale rendered drop action" drop-action :failed sequences)
              inspected (:receipt (command-http! port {:op :inspect} sequences))]
          (check "rendered action changes only n2--n3"
                 {["n1" "n2"] :normal ["n2" "n3"] :drop}
                 (:connections after-drop))
          (check "accepted rendered action consumes one application revision" 1
                 (:regime-revision after-drop))
          (check "stale action reports application failure without terminating worker"
                 :failed (get-in stale [:receipt :status]))
          (check "stale rendered action cannot mutate connection state"
                 (select-keys after-drop [:connections :regime-revision])
                 (select-keys (:value inspected)
                              [:connections :regime-revision]))))

      ;; Use Ripple's persistent evaluator for one command. The evaluated helper
      ;; delegates through retained-view to the exact handle attached to the
      ;; retained panel; it does not construct a second Broadcast application.
      (let [evaluation
            (request-json!
             port "POST" "/api/eval"
             {"version" 1
              "form"
              (str "(do (require '[maelstrom-broadcast-workbench.main :as wb] :reload) "
                   "(wb/step! \"n2\"))")})
            envelope (last (eval-session/recent (:session instance)))
            result (:val (envelope-ret envelope))
            snapshot (get-in result [:receipt :value :snapshot])
            after (request-json! port "GET" "/api/retained-frame" nil)]
        (check "EvalSession command has a definite HTTP receipt" 200
               (:status evaluation))
        (check "EvalSession stepped the selected real mailbox" "n2"
               (get-in result [:receipt :value :result :node-id]))
        (check "EvalSession used the next shared retained command sequence" 5
               (get-in result [:receipt :sequence]))
        (swap! sequences conj (get-in result [:receipt :sequence]))
        (check "retained panel observes the shared next sequence" "6"
               (get-in after [:wire "coordinate" "nextSequence"]))
        (check "one n2 step consumed exactly one mailbox head" 1
               (get-in snapshot [:mailboxes "n2" :count]))

        (let [{partitioned :snapshot partition-steps :steps
               partition-response :response}
              (drain-ready! port snapshot sequences)
              pending (get-in partitioned [:nodes "n2" :pending 0])
              restore-action
              (topology-action partition-response "edges" "n2--n3" "restore")]
          (check "explicit steps reached the partition boundary" true
                 (pos? partition-steps))
          (check "partition leaves n3 without the broadcast"
                 {"n1" [42] "n2" [42] "n3" []}
                 (node-messages partitioned))
          (check "partition records one dropped n2-to-n3 envelope" 1
                 (get-in partitioned [:drops :dropped-total]))

          (let [restored (:receipt
                          (canonical-command-http!
                           port "rendered n2--n3 restore action"
                           restore-action sequences))
                healed-snapshot (application-snapshot restored)
                after-heal (drain-ready! port healed-snapshot sequences)]
            (check "restoring a connection alone manufactures no delivery" 0
                   (:steps after-heal))
            (check "n3 remains empty after restore alone" []
                   (get-in after-heal [:snapshot :nodes "n3" :messages])))

          (let [retried (:receipt (command-http! port {:op :retry} sequences))
                retry-snapshot (application-snapshot retried)
                converged (drain-ready! port retry-snapshot sequences)]
            (check "retry reuses the retained application message identity"
                   pending
                   (get-in retried [:value :result :evidence "n2" 0]))
            (check "retry converges all real Broadcast applications"
                   {"n1" [42] "n2" [42] "n3" [42]}
                   (node-messages (:snapshot converged)))))

        (let [read-receipt (:receipt (command-http! port {:op :read} sequences))
              read-snapshot (application-snapshot read-receipt)]
          (check "read request waits in n3 mailbox" ["n3"]
                 (:ready-mailboxes read-snapshot))
          (let [stepped (:receipt
                         (command-http! port {:op :step :node-id "n3"}
                                        sequences))
                reply (last (get-in (application-snapshot stepped)
                                    [:client-replies :tail]))]
            (check "read returns the converged value" [42]
                   (get-in reply [:body :messages])))))

      (let [stopped (:receipt (command-http! port {:op :stop} sequences))
            terminal-snapshot (get-in stopped [:value :snapshot])
            exited (wait-for-exit worker)]
        (check "terminal command owns graceful stop" true
               (get-in stopped [:value :owner?]))
        (check "application snapshot is stopped" :stopped
               (:status terminal-snapshot))
        (check "retained child exits zero" 0 (get-in exited [:child :exit]))
        (check "retained child is reaped" false
               (get-in exited [:child :alive?]))
        (let [terminal-path (get-in exited [:artifact :terminal])
              terminal-exists? (and terminal-path (fs/exists? terminal-path))
              terminal (when terminal-exists?
                         (edn/read-string (slurp terminal-path)))]
          (check "terminal evidence remains on disk" true
                 (boolean terminal-exists?))
          (check "terminal evidence is completed" :completed
                 (:jolt.sim.retained/status terminal))))

      (check "all retained command sequences are contiguous"
             (vec (range (count @sequences)))
             @sequences)
      (let [first-shutdown (workbench/shutdown! instance)
            second-shutdown (workbench/shutdown! instance)]
        (check "launcher shutdown is idempotent" first-shutdown second-shutdown)
        (check "shutdown preserves artifact directory" artifact-dir
               (get-in first-shutdown [:child :snapshot :artifact :dir])))
      {:artifact-dir artifact-dir
       :port port
       :command-count (count @sequences)}))

(defn- coordinate-call-outcome [snapshot!]
  (try
    {:coordinate (snapshot!)}
    (catch :default error
      {:coordinate-error (trace/normalize-error error)})))

(defn- coordinate-outcome [worker]
  (coordinate-call-outcome #(retained/snapshot worker)))

(defn- reaped-coordinate? [outcome]
  ;; Absence, nil, and a failed coordinate read are not liveness evidence.
  ;; Only the supervisor's exact false observation permits process exit.
  (and (map? (:coordinate outcome))
       (false? (get-in outcome [:coordinate :child :alive?]))))

(defn- force-reap-attempt-with [terminate! snapshot!]
  ;; Kept as one injectable seam so a focused future unit can make terminate!
  ;; throw ::retained/worker-survived-kill or a :termination-failed transport
  ;; error and assert that the fresh coordinate still controls the decision.
  (let [termination
        (try
          {:result (terminate!)}
          (catch :default error
            {:error (trace/normalize-error error)}))
        coordinate (coordinate-call-outcome snapshot!)]
    {:termination termination
     :coordinate coordinate
     :decision (if (reaped-coordinate? coordinate) :reaped :retry)}))

(defn- force-reap-attempt [worker]
  (force-reap-attempt-with #(retained/terminate! worker)
                           #(retained/snapshot worker)))

(defn- pace-reap-with! [sleep!]
  (try
    (sleep!)
    {:paced? true}
    (catch :default error
      ;; An interrupt is a request to stop ordinary work, not permission to
      ;; abandon a possibly live owned child.
      (try (Thread/yield) (catch :default _ nil))
      {:paced? false :error (bounded-error error)})))

(defn- force-reap-until-proven! [progress worker initial-coordinate cause]
  ;; This loop intentionally has no "give up and System/exit" branch. Every
  ;; terminate operation is internally TERM -> bounded grace -> forced kill ->
  ;; reap. A published retained command ahead of it is also deadline-bounded.
  ;; If either layer is defective, the primordial process remains the owner and
  ;; CI's outer deadline preserves the append-only evidence rather than this
  ;; process voluntarily orphaning the child.
  (loop [attempt 1]
    (let [observation (force-reap-attempt worker)]
      (emit-progress! progress
                        {:phase :force-reap-attempt
                         :status (if (= :reaped (:decision observation))
                                   :completed :retrying)
                         :cause cause
                         :attempt attempt
                         :initial-coordinate initial-coordinate
                         :termination (:termination observation)
                         :fresh-coordinate (:coordinate observation)})
      (if (= :reaped (:decision observation))
        observation
        (do
          ;; Keep retry diagnostics useful and bounded in volume even if a
          ;; mocked/broken terminate path returns immediately forever.
          (let [pacing (pace-reap-with! #(Thread/sleep 1000))]
            (when-not (:paced? pacing)
              (emit-progress! progress
                              {:phase :force-reap-pacing-error
                               :status :retrying
                               :cause cause
                               :attempt attempt
                               :error (:error pacing)})))
          (recur (inc attempt)))))))

(defn- cleanup-owned! [progress instance initial-coordinate cause]
  ;; The primordial test thread owns the published workbench. The scenario
  ;; future is only a client. Therefore watchdog/error handling can never leave
  ;; the sole child lifecycle trapped inside the timed task.
  (emit-progress! progress {:phase :cleanup-start
                              :status :running
                              :cause cause
                              :coordinate initial-coordinate})
  (let [worker (:worker instance)
        graceful (future (workbench/shutdown! instance))
        graceful-outcome
        (try
          {:result (deref graceful cleanup-timeout-ms ::timeout)}
          (catch :default error {:error error}))
        after-graceful (coordinate-outcome worker)
        _ (emit-progress! progress
                            {:phase :graceful-cleanup-observed
                             :status (cond
                                       (reaped-coordinate? after-graceful)
                                       :completed

                                       (:error graceful-outcome) :errored
                                       (= ::timeout (:result graceful-outcome))
                                       :timed-out
                                       :else :child-still-live)
                             :cause cause
                             :timeout-ms cleanup-timeout-ms
                             :graceful graceful-outcome
                             :fresh-coordinate after-graceful})
        forced (when-not (reaped-coordinate? after-graceful)
                 (force-reap-until-proven! progress worker
                                           initial-coordinate cause))
        final-coordinate (if forced (:coordinate forced) after-graceful)
        result {:mode (if forced :forced :graceful)
                :graceful graceful-outcome
                :forced forced
                :final-coordinate (:coordinate final-coordinate)}]
    ;; Both branches above reach here only with exact `:alive? false` evidence.
    (when-not (reaped-coordinate? final-coordinate)
      (throw (ex-info "Broadcast workbench cleanup lost its reap proof"
                      {:type ::missing-reap-proof
                       :cause cause
                       :initial-coordinate initial-coordinate
                       :cleanup result})))
    (emit-progress! progress {:phase :cleanup-finish
                                :status :completed
                                :cause cause
                                :cleanup result})
    result))

(defn- run-owned! [progress instance initial* reaped?*]
  ;; start! has already transferred the real child to this primordial scope.
  ;; Establish the guard before the first snapshot, port read, journal append,
  ;; future, or other post-start operation.
  (try
    (let [worker (:worker instance)
          initial-outcome (coordinate-outcome worker)
          _ (reset! initial* initial-outcome)
          _ (when-not (map? (:coordinate initial-outcome))
              (throw (ex-info "initial retained coordinate is unavailable"
                              {:type ::initial-coordinate-unavailable
                               :coordinate initial-outcome})))
          initial-coordinate (:coordinate initial-outcome)
          _ (emit-progress! progress
                            {:phase :owner-published
                             :status :running
                             :coordinate initial-coordinate
                             :port (get-in instance [:server :port])})
          task (future (run-scenario progress instance))
          outcome (try
                    {:result (deref task watchdog-timeout-ms ::timeout)}
                    (catch :default error {:error error}))
          cause (cond
                  (:error outcome) :error
                  (= ::timeout (:result outcome)) :timeout
                  (pos? @failures) :failed-checks
                  :else :success)
          _ (when (= :timeout cause) (future-cancel task))
          cleanup
          (try
            {:result (cleanup-owned! progress instance
                                     initial-coordinate cause)}
            (catch :default error
              ;; Recovery happens before even the non-throwing diagnostic.
              (let [recovery
                    (force-reap-until-proven! progress worker
                                              initial-coordinate
                                              :cleanup-error)]
                (emit-progress! progress
                                {:phase :cleanup-error-recovery
                                 :status :completed
                                 :coordinate initial-coordinate
                                 :recovery recovery
                                 :error (bounded-error error)})
                {:error error :recovery recovery})))
          proof (coordinate-outcome worker)]
        (when-not (reaped-coordinate? proof)
          (throw (ex-info "post-cleanup retained coordinate lacks reap proof"
                          {:type ::post-cleanup-reap-proof-missing
                           :coordinate proof
                           :cleanup cleanup})))
        (reset! reaped?* true)
        {:outcome (assoc outcome :cleanup cleanup)
         :cleanup cleanup
         :reap-proof (:coordinate proof)})
      (catch :default error
        ;; This includes failure of the very first post-start snapshot. The
        ;; owner must recover before attempting a diagnostic or returning.
        (let [recovery
              (force-reap-until-proven! progress (:worker instance) @initial*
                                        :owner-scope-error)]
          (reset! reaped?* true)
          (emit-progress! progress
                          {:phase :owner-scope-error
                           :status :reaped
                           :initial-coordinate @initial*
                           :recovery recovery
                           :error (bounded-error error)})
          {:owner-error error
           :recovery recovery
           :reap-proof (get-in recovery [:coordinate :coordinate])}))
      (finally
        ;; No exception path may cross this scope with live/unknown ownership.
        ;; The retry loop itself never returns without exact `:alive? false`.
        (when-not @reaped?*
          (force-reap-until-proven! progress (:worker instance) @initial*
                                    :owner-finally)
          (reset! reaped?* true)))))

(defn -main [& _]
  (let [progress (progress-file)]
    (emit-progress! progress {:phase :start :status :running})
    (println "Broadcast workbench progress:" progress)
    (flush)
    ;; Strict reader controls execute before start! can allocate an artifact
    ;; directory or child. They discriminate empty/trailing-form acceptance.
    (check "config reader accepts one EDN form"
           {:viewer {} :input {}}
           (workbench/parse-config-edn "{:viewer {} :input {}}" "<test-config>"))
    (check "config reader rejects an empty document"
           :empty-document (config-error-reason "  \n"))
    (check "config reader rejects a trailing form"
           :trailing-document (config-error-reason "{} {}"))
    (let [survived
          (force-reap-attempt-with
           #(throw (ex-info "simulated child survived kill"
                            {:type :jolt.sim.retained-process/worker-survived-kill}))
           (constantly {:child {:alive? true}}))
          reaped
          (force-reap-attempt-with
           #(throw (ex-info "simulated termination failed"
                            {:type :jolt.sim.retained-process/transport-error
                             :reason :termination-failed}))
           (constantly {:child {:alive? false}}))
          progress-failure
          (emit-progress-with!
           (fn [_ _]
             (throw (ex-info "simulated progress failure"
                             {:type ::simulated-progress-failure})))
           "<test-progress>" {:phase :simulated-live-child})
          interrupted-pacing
          (pace-reap-with!
           #(throw (ex-info "simulated interruption"
                            {:type ::simulated-interruption})))
          initial-snapshot-failure
          (coordinate-call-outcome
           #(throw (ex-info "simulated initial snapshot failure"
                            {:type ::simulated-initial-snapshot-failure})))]
      (check "terminate failure plus live coordinate requires retry"
             :retry (:decision survived))
      (check "terminate failure reason is retained in the attempt"
             :jolt.sim.retained-process/worker-survived-kill
             (get-in survived [:termination :error :data :type]))
      (check "exact reaped coordinate controls despite terminate exception"
             :reaped (:decision reaped))
      (check "progress write failure is observational while child is live"
             [false :retry]
             [(:written? progress-failure) (:decision survived)])
      (check "reap pacing interruption is contained"
             false (:paced? interrupted-pacing))
      (check "initial snapshot exception becomes an ownership outcome"
             ::simulated-initial-snapshot-failure
             (get-in initial-snapshot-failure
                     [:coordinate-error :data :type]))
      ;; The expected seam failure must not pollute a later real diagnostic.
      (reset! progress-write-errors* []))
    (let [initial* (atom {:coordinate-error
                          {:reason :initial-coordinate-not-observed}})
          reaped?* (atom false)
          instance
          (workbench/start!
           {:viewer-config {:port 0 :capability-token token}
            :retained-config
            (workbench/retained-config
             (required-environment "JOLT_SIM_BIN")
             (required-environment "JOLT_SIM_PROJECT_DIR")
             {:message 42 :regime :healthy})})
          owned (run-owned! progress instance initial* reaped?*)
          outcome (:outcome owned)
          cleanup (:cleanup owned)]
      (cond
        (:owner-error owned)
        (do
          (emit-progress! progress
                          {:phase :owner-error :status :errored
                           :recovery (:recovery owned)
                           :reap-proof (:reap-proof owned)
                           :error (bounded-error (:owner-error owned))})
          (println "FAILURE: Broadcast workbench owner scope failed:"
                   (pr-str (bounded-error (:owner-error owned))))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (:error cleanup)
        (do
          (emit-progress! progress
                            {:phase :cleanup-error :status :errored
                             :reap-proof (:reap-proof owned)
                             :recovery (:recovery cleanup)
                             :error (bounded-error (:error cleanup))})
          (println "FAILURE: owner cleanup failed:"
                   (pr-str (trace/normalize-error (:error cleanup))))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (:error outcome)
        (do
          (emit-progress! progress
                            {:phase :error :status :errored
                             :error (trace/normalize-error (:error outcome))})
          (println "FAILURE:" (pr-str
                                (trace/normalize-error (:error outcome))))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (= ::timeout (:result outcome))
        (do
          (emit-progress! progress
                            {:phase :timeout :status :timed-out
                             :timeout-ms watchdog-timeout-ms})
          (println "FAILURE: Broadcast workbench timed out")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (pos? @failures)
        (do
          (emit-progress! progress
                            {:phase :finish :status :failed
                             :failures @failures
                             :result (:result outcome)})
          (println "FAILURE:" @failures "Broadcast workbench checks failed")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        :else
        (do
          (emit-progress! progress
                            {:phase :finish :status :passed
                             :result (:result outcome)})
          (println "PASS: real Broadcast Ripple/REPL workbench")
          (println "progress:" progress)
          (flush)
          (System/exit 0))))))
