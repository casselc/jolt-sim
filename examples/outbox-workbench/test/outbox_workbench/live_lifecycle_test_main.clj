(ns outbox-workbench.live-lifecycle-test-main
  "Focused fresh-process gate for the app-owned live outbox lifecycle."
  (:require [clojure.datafy :as datafy]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.fixtures.outbox-delivery :as delivery]
            [jolt.sim.fixtures.outbox-json-delivery :as fixture]
            [jolt.sim.fixtures.outbox-json-delivery-live :as live]))

(def ^:private failures (atom 0))
(def ^:private watchdog-timeout-ms 180000)

(defn- check [label expected actual]
  (if (= expected actual)
    (println (str "ok   " label))
    (do
      (swap! failures inc)
      (println (str "FAIL " label
                    "\n  expected: " (pr-str expected)
                    "\n  actual:   " (pr-str actual))))))

(defn- caught-data [thunk]
  (try (thunk) nil (catch :default error (ex-data error))))

(defn- pending-status [lifecycle]
  (get-in (live/snapshot! lifecycle) [:store-state :outbox 0 :status]))

(defn- run-normal-scenario []
  (let [lifecycle (live/start! {:retained-evidence 8})]
    (try
      (let [initial (live/snapshot! lifecycle)]
        (check "start is open and empty" [:open :empty []]
               [(:status initial) (:phase initial)
                (get-in initial [:store-state :outbox])])
        (check "empty lifecycle has nothing to deliver" :empty
               (:status (live/deliver-next! lifecycle)))
        (check "start has no retained server errors" [0 0]
               [(get-in initial [:http-errors :count])
                (get-in initial [:receiver-errors :count])]))

      (let [fresh (live/submit-command! lifecycle fixture/default-command)
            pending (live/snapshot! lifecycle)
            committed-state (:store-state pending)]
        (check "real HTTP fresh command returns 201" 201 (:status fresh))
        (check "real HTTP client connection closes idempotently" [true false]
               (get-in fresh [:close-results :connection]))
        (check "fresh COMMIT is stably pending" [:pending :pending]
               [(:phase pending)
                (get-in committed-state [:outbox 0 :status])])
        (check "receiver has observed no delivery before explicit step" 0
               (get-in pending [:receiver-requests :count]))

        (let [replay (live/submit-command! lifecycle fixture/default-command)
              after-replay (live/snapshot! lifecycle)]
          (check "exact replay returns 200" 200 (:status replay))
          (check "exact replay performs no durable mutation" committed-state
                 (:store-state after-replay)))

        (let [conflict-command (assoc fixture/default-command :payload [1 2 3])
              conflict (live/submit-command! lifecycle conflict-command)
              after-conflict (live/snapshot! lifecycle)]
          (check "conflicting request-id reuse returns 409" 409
                 (:status conflict))
          (check "conflict performs no durable mutation" committed-state
                 (:store-state after-conflict)))

        (let [delivered (live/deliver-next! lifecycle)
              final (live/snapshot! lifecycle)
              datafied (datafy/datafy lifecycle)
              original (::datafy/obj (meta datafied))
              received (datafy/nav original :receiver-requests
                                   (:receiver-requests datafied))]
        (check "explicit delivery receives exact correlated ack"
                 (delivery/expected-ack (:message (first received)))
                 (:reply delivered))
          (check "ack gates the durable delivered mark" [:delivered :delivered]
                 [(:phase final)
                  (get-in final [:store-state :outbox 0 :status])])
          (check "real TCP client connection closes idempotently" [true false]
                 (get-in delivered [:close-results :connection]))
          (check "receiver request navigation returns the exact one message" 1
                 (count received))
          (check "datafy preserves the UI-neutral lifecycle kind"
                 :jolt.sim.kind/outbox-live-lifecycle
                 (:kind datafied))))
      (finally
        (check "stop is idempotent" [true false]
               [(live/stop! lifecycle) (live/stop! lifecycle)])))

    (check "stopped snapshot retains the final durable state"
           [:stopped :delivered]
           [(:status (live/snapshot! lifecycle))
            (pending-status lifecycle)])
    (check "post-stop submit rejects before I/O" :stopped
           (:reason (caught-data
                     #(live/submit-command! lifecycle fixture/default-command))))
    (check "post-stop deliver rejects before I/O" :stopped
           (:reason (caught-data #(live/deliver-next! lifecycle))))))

(defn- run-hostile-ack-scenario []
  (let [lifecycle
        (live/start!
         {:reply-for (fn [message]
                       {"type" "outbox_delivery_ok"
                        "outbox-id" (inc (get message "outbox-id"))
                        "attempt" (get message "attempt")})})]
    (try
      (live/submit-command! lifecycle fixture/default-command)
      (let [before (:store-state (live/snapshot! lifecycle))
            failure (caught-data #(live/deliver-next! lifecycle))
            after (:store-state (live/snapshot! lifecycle))]
        (check "hostile ack is rejected by exact correlation" :ack-mismatch
               (:reason failure))
        (check "hostile ack cannot mutate durable state" before after)
        (check "hostile ack leaves the row pending" :pending
               (get-in after [:outbox 0 :status])))
      (finally
        (live/stop! lifecycle)))))

(defn- sample-delivery-row [outbox-id]
  {:outbox-id outbox-id
   :request-id (str "direct-" outbox-id)
   :entity-id "direct-observer"
   :version outbox-id
   :payload [0 255]
   :status :pending})

(defn- run-observation-retention-scenario []
  (let [lifecycle (live/start! {:retained-evidence 2})]
    (try
      (let [port (get-in (live/snapshot! lifecycle)
                         [:endpoints :delivery :port])]
        (doseq [outbox-id [1 2]]
          (delivery/exchange-deliveries!
           "127.0.0.1" port
           [(delivery/delivery-message (sample-delivery-row outbox-id))]))
        (let [captured (datafy/datafy lifecycle)
              original (::datafy/obj (meta captured))
              old-token (:receiver-requests captured)]
          (delivery/exchange-deliveries!
           "127.0.0.1" port
           [(delivery/delivery-message (sample-delivery-row 3))])
          (let [current (datafy/datafy lifecycle)
                retained (datafy/nav original :receiver-requests
                                     (:receiver-requests current))]
            (check "async receiver coordinates are monotonic and never reused"
                   true
                   (apply < (map :sequence retained)))
            (check "receiver evidence respects its retention limit" 2
                   (count retained))
            (check "evicted receiver nav token fails stale, never ABA-matches"
                   :stale-navigation
                   (:reason
                    (caught-data
                     #(datafy/nav original :receiver-requests old-token)))))))
      (finally
        (live/stop! lifecycle)))))

(defn- envelope-ret [envelope]
  (some #(when (= :ret (:tag %)) %) (:events envelope)))

(defn- run-eval-session-scenario []
  (let [session (eval-session/start)
        evaluate #(eval-session/evaluate! session {:form %})]
    (try
      (evaluate
       "(require '[jolt.sim.fixtures.outbox-json-delivery-live :as live] '[jolt.sim.fixtures.outbox-json-delivery :as fixture])")
      (evaluate "(def live-app (live/start!))")
      (evaluate "(live/submit-command! live-app fixture/default-command)")
      (let [pending (evaluate
                     "[(get-in (live/snapshot! live-app) [:store-state :outbox 0 :status]) (get-in (live/snapshot! live-app) [:receiver-requests :count])]")]
        (check "persistent EvalSession observes post-COMMIT/pre-delivery"
               [:pending 0]
               (:val (envelope-ret pending))))
      (let [delivered (evaluate
                       "[(get (live/deliver-next! live-app) :status) (get-in (live/snapshot! live-app) [:store-state :outbox 0 :status])]")]
        (check "persistent EvalSession explicitly advances real delivery"
               [:delivered :delivered]
               (:val (envelope-ret delivered))))
      (check "persistent EvalSession stops its app-owned lifecycle" true
             (:val (envelope-ret (evaluate "(live/stop! live-app)"))))
      (finally
        (when-let [app-var (resolve 'user/live-app)]
          (try (live/stop! @app-var) (catch :default _ nil)))
        (eval-session/close! session)))))

(defn- progress-file []
  (or (System/getenv "JOLT_SIM_OUTBOX_LIVE_PROGRESS_FILE")
      (str (or (System/getenv "TMPDIR") "/tmp")
           "/jolt-sim-outbox-live-" (java.util.UUID/randomUUID) ".edn")))

(defn- append-progress! [path record]
  (spit path (str (pr-str record) "\n") :append true))

(defn- run-all []
  (run-normal-scenario)
  (run-hostile-ack-scenario)
  (run-observation-retention-scenario)
  (run-eval-session-scenario)
  {:failures @failures})

(defn -main [& _]
  (let [progress (progress-file)
        _ (append-progress! progress {:phase :start :status :running})
        worker (future (run-all))
        outcome (try
                  {:result (deref worker watchdog-timeout-ms ::timeout)}
                  (catch :default error {:error error}))]
    (cond
      (:error outcome)
      (do
        (append-progress! progress
                          {:phase :error :status :errored
                           :message (ex-message (:error outcome))
                           :data (ex-data (:error outcome))})
        (println (str "FAILURE: " (ex-message (:error outcome))))
        (println (str "progress: " progress))
        (flush)
        (System/exit 1))

      (= ::timeout (:result outcome))
      (do
        (append-progress! progress
                          {:phase :timeout :status :timed-out
                           :watchdog-timeout-ms watchdog-timeout-ms})
        (println (str "FAILURE: timed out; progress: " progress))
        (flush)
        (System/exit 1))

      (pos? @failures)
      (do
        (append-progress! progress
                          {:phase :finish :status :failed
                           :failures @failures})
        (println (str "FAILURE: " @failures " checks failed"))
        (println (str "progress: " progress))
        (flush)
        (System/exit 1))

      :else
      (do
        (append-progress! progress
                          {:phase :finish :status :passed :failures 0})
        (println "live-lifecycle test passed")
        (println (str "progress: " progress))
        (flush)
        (System/exit 0)))))
