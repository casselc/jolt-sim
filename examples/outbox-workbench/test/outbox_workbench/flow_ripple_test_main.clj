(ns outbox-workbench.flow-ripple-test-main
  "Fresh-process smoke for the interactive real outbox flow/Ripple vertical.

  The test starts the ordinary retained outbox application, chooses submit
  through Ripple's real loopback HTTP session API, stops only Ripple, then
  chooses deliver directly through the same opaque bridge. It proves that the
  UI and REPL share one revision/effect ledger and that UI shutdown does not
  own either the bridge or worker. The launcher finally closes admission and
  gracefully stops/reaps the worker."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [jolt.fs :as fs]
            [jolt.sim.fixtures.outbox-delivery :as outbox]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.trace :as trace]
            [outbox-workbench.flow-retained :as flow-retained]
            [outbox-workbench.flow-ripple-main :as flow-ripple]
            [teensyp.client :as client]))

(def ^:private token "outbox-flow-ripple-test-token-00000001")
(def ^:private watchdog-timeout-ms 180000)
(def ^:private request-timeout-ms 120000)
(def ^:private failures (atom 0))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (seq value)
      (throw (ex-info (str name " is required") {:name name})))
    value))

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-flow-ripple-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn- task-error [error]
  ;; Jolt futures wrap task failures once. Do not recursively traverse causes:
  ;; a retained host exception may expose an opaque native cause sentinel.
  (let [cause (ex-cause error)]
    (if (and cause (not (identical? cause error))) cause error)))

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

(defn- copy-of-length ^bytes [^bytes src n]
  (let [dest (byte-array n)]
    (System/arraycopy src 0 dest 0 n)
    dest))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        dest (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 dest offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        dest))))

(defn- find-header-terminator [^bytes raw]
  (let [n (alength raw)]
    (loop [i 0]
      (if (> (+ i 4) n)
        nil
        (if (and (= 13 (bit-and 0xff (aget raw i)))
                 (= 10 (bit-and 0xff (aget raw (+ i 1))))
                 (= 13 (bit-and 0xff (aget raw (+ i 2))))
                 (= 10 (bit-and 0xff (aget raw (+ i 3)))))
          i
          (recur (inc i)))))))

(defn- read-until-eof! [connection]
  (let [scratch (byte-array 4096)]
    (loop [chunks []]
      (if-let [length (client/receive-into!
                       connection scratch 0 (alength scratch)
                       {:timeout-ms request-timeout-ms})]
        (recur (conj chunks (copy-of-length scratch length)))
        (concat-byte-arrays chunks)))))

(defn- request-json! [port method path body cursor]
  (let [payload (when body (json/write-str body))
        request-text
        (str method " " path " HTTP/1.1\r\n"
             "Host: 127.0.0.1:" port "\r\n"
             "Accept: application/json\r\n"
             "X-Jolt-Sim-Capability: " token "\r\n"
             (when cursor
               (str "X-Jolt-Sim-Journal-Cursor: " cursor "\r\n"))
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
        (let [head (String. ^bytes (copy-of-length raw terminator) "UTF-8")
              start (+ terminator 4)
              body-bytes (java.util.Arrays/copyOfRange raw start (alength raw))
              status-line (first (string/split head #"\r\n"))
              [_ status] (re-matches #"HTTP/1\.1 (\d{3}) .*" status-line)]
          {:status (parse-long status)
           :wire (json/read-str (String. ^bytes body-bytes "UTF-8"))}))
      (finally
        (client/close! connection)))))

(defn- settled-value [bridge ordinal]
  (get-in (effect-session/snapshot bridge)
          [:effects :records ordinal :receipt :value]))

(defn- run-scenario [progress]
  (let [workbench
        (flow-ripple/start!
         {:viewer-config {:port 0 :capability-token token}
          :retained-config
          (flow-retained/retained-config
           (required-environment "JOLT_SIM_BIN")
           (required-environment "JOLT_SIM_PROJECT_DIR"))
          :submit-command {:op :submit :command outbox/default-command}})
        bridge (:bridge workbench)
        worker (:worker workbench)
        stopped? (atom false)]
    (println "flow Ripple artifacts:"
             (get-in (retained/snapshot worker) [:artifact :dir]))
    (append-progress!
     progress
     {:phase :worker-started
      :status :running
      :artifact-dir (get-in (retained/snapshot worker) [:artifact :dir])})
    (flush)
    (try
      (let [preview (effect-session/branches bridge)]
        (check "submit preview exposes one branch" 1 (count preview))
        (check "preview leaves worker sequence untouched"
               0 (get-in (retained/snapshot worker) [:next-sequence]))
        (check "preview publishes no application receipt"
               nil (get-in (retained/snapshot worker) [:last-receipt])))

      (let [port (get-in workbench [:server :port])
            frame (request-json! port "GET" "/api/session-frame" nil "0")
            choice (get-in frame [:wire "choices" 0])
            step (request-json!
                  port "POST" "/api/session-step"
                  {"version" 1
                   "cursor" (get-in frame [:wire "nextCursor"])
                   "branch" (select-keys choice
                                         ["revision" "kind" "value"])}
                  nil)
            submit (settled-value bridge 0)]
        (check "Ripple exposes the flow/effect v2 frame" 2
               (get-in frame [:wire "version"]))
        (check "Ripple exposes exactly one submit choice" 1
               (count (get-in frame [:wire "choices"])))
        (check "Ripple HTTP choice commits once" [200 true "committed"]
               [(:status step)
                (get-in step [:wire "committed"])
                (get-in step [:wire "outcome"])])
        (check "submit yields real HTTP 201" 201
               (get-in submit [:result :status]))
        (check "submit leaves one durable pending row" :pending
               (get-in submit [:snapshot :store-state :outbox 0 :status]))
        (check "submit has no receiver delivery" 0
               (get-in submit [:snapshot :receiver-requests :count]))

        (let [items (request-json! port "GET" "/api/workbench-frame" nil nil)
              item (get-in items [:wire "items" 0])
              source-before (get item "sourceEdn")
              change
              (request-json!
               port "POST" "/api/workbench-item-kind"
               {"version" 1
                "itemId" (get item "itemId")
                "sourceRevision" (get item "sourceRevision")
                "sourceFingerprint" (get item "sourceFingerprint")
                "kind" "example.outbox/effect-result"}
               nil)
              refreshed
              (request-json! port "GET" "/api/workbench-frame" nil nil)]
          (check "definite flow result becomes a generic workbench item"
                 [200 "simulation-step" "0"]
                 [(:status items) (get item "itemId")
                  (get item "sourceRevision")])
          (check "app renderer is offered as inert kind data"
                 true
                 (boolean (some #{"example.outbox/effect-result"}
                                (get-in items [:wire "availableKinds"]))))
          (check "source-bound kind override commits definitively"
                 [200 "committed"]
                 [(:status change) (get-in change [:wire "status"])])
          (check "custom app presentation renders through generic frame"
                 "Outbox flow result"
                 (get-in refreshed
                         [:wire "items" 0 "presentation" "summary"]))
          (check "presentation overlay preserves immutable source EDN"
                 source-before
                 (get-in refreshed [:wire "items" 0 "sourceEdn"]))))

      ;; Stopping the UI server is deliberately not application shutdown.
      (flow-ripple/stop-ripple! workbench)
      (check "stopping Ripple leaves worker alive" true
             (get-in (retained/snapshot worker) [:child :alive?]))
      (let [branch (get-in (effect-session/branches bridge) [0 :branch])
            result (effect-session/step! bridge branch)
            deliver (settled-value bridge 1)]
        (check "same bridge exposes the deliver revision" 1 (:revision branch))
        (check "direct REPL bridge step commits" true (:committed? result))
        (check "shared bridge ledger has two commits" 2
               (get-in (effect-session/snapshot bridge) [:commits :count]))
        (check "deliver yields the real ack-gated row" :delivered
               (get-in deliver [:snapshot :store-state :outbox 0 :status]))
        (check "deliver reaches receiver exactly once" 1
               (get-in deliver [:snapshot :receiver-requests :count])))

      (let [shutdown (flow-ripple/shutdown! workbench)
            _ (reset! stopped? true)
            child (:child shutdown)
            terminal-path (get-in child [:snapshot :artifact :terminal])
            terminal (when (fs/exists? terminal-path)
                       (edn/read-string (slurp terminal-path)))]
        (check "shutdown closes bridge admission" true
               (:closed? (effect-session/snapshot bridge)))
        (check "graceful worker stop completed" :completed
               (get-in child [:receipt :status]))
        (check "worker published completed terminal evidence" :completed
               (:jolt.sim.retained/status terminal))
        (check "retained child exits zero" 0
               (get-in child [:snapshot :child :exit]))
        (check "retained child is reaped" false
               (get-in child [:snapshot :child :alive?])))
      {:artifact-dir (get-in (retained/snapshot worker) [:artifact :dir])}
      (finally
        (when-not @stopped?
          (flow-ripple/shutdown! workbench))))))

(defn -main [& _]
  (let [progress (progress-file)]
    (append-progress! progress {:phase :start :status :running})
    (println "flow Ripple progress:" progress)
    (flush)
    (let [task (future (run-scenario progress))
          outcome (try
                    {:result (deref task watchdog-timeout-ms ::timeout)}
                    (catch :default error
                      {:error (task-error error)}))]
      (cond
        (:error outcome)
        (do
          (append-progress! progress
                            {:phase :error :status :errored
                             :error (trace/normalize-error (:error outcome))})
          (println "FAILURE:" (pr-str
                                (trace/normalize-error (:error outcome))))
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (= ::timeout (:result outcome))
        (do
          (append-progress! progress
                            {:phase :timeout :status :timed-out
                             :timeout-ms watchdog-timeout-ms})
          (println "FAILURE: flow Ripple smoke timed out")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        (pos? @failures)
        (do
          (append-progress! progress
                            {:phase :finish :status :failed
                             :failures @failures :result (:result outcome)})
          (println "FAILURE:" @failures "checks failed")
          (println "progress:" progress)
          (flush)
          (System/exit 1))

        :else
        (do
          (append-progress! progress
                            {:phase :finish :status :passed
                             :result (:result outcome)})
          (println "PASS: flow Ripple real outbox vertical")
          (println "progress:" progress)
          (flush)
          (System/exit 0))))))
