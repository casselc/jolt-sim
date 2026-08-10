(ns jolt.sim.fixtures.outbox-json-delivery-live
  "App-owned live lifecycle for the unchanged canonical JSON outbox flow.

  One opaque handle owns one real in-memory SQLite connection, one persistent
  real jolt-http listener using the production routed JSON command handler,
  and one persistent real framed TCP/bencode receiver. Commands cross the
  real HTTP boundary but delivery is explicit: after a 201 COMMIT, snapshot!
  exposes the stable durable :pending row until deliver-next! sends it, checks
  the exact acknowledgement, and only then marks it delivered.

  This namespace supplies lifecycle and inspection only. It reuses the
  production facade/adapter plus the existing canonical request, framing,
  exchange, and acknowledgement components; it does not implement a second
  application, protocol, database, or controller ABI."
  (:require [clojure.core.protocols :as protocols]
            [jdbc.core :as jdbc]
            [jolt.example.outbox.http-json :as json-facade]
            [jolt.example.outbox.sqlite :as store]
            [jolt.http.server :as http]
            [jolt.sim.fixtures.http-sqlite :as http-fixture]
            [jolt.sim.fixtures.outbox-delivery :as delivery]
            [jolt.sim.fixtures.outbox-json-delivery :as json-fixture]
            [teensyp.server :as tcp]))

(def ^:private default-retained-evidence 32)
(def ^:private maximum-retained-evidence 1024)
(def ^:private maximum-error-message-chars 512)
(def ^:private maximum-error-class-chars 160)
(def ^:private maximum-error-data-entries 12)
(def ^:private maximum-error-key-chars 128)
(def ^:private maximum-error-value-chars 256)

(def ^:private snapshot-operation (Object.))
(def ^:private submit-operation (Object.))
(def ^:private deliver-operation (Object.))
(def ^:private stop-operation (Object.))
(def ^:private navigate-operation (Object.))

(defn- fail! [reason detail]
  (throw
   (ex-info "live outbox lifecycle rejected an operation"
            (merge {:type :jolt.sim.fixtures.outbox-json-delivery-live/rejected
                    :reason reason}
                   detail))))

(defn- safe-render [value]
  (try (pr-str value) (catch :default _ "#<unprintable>")))

(defn- bounded-text [value limit]
  (let [text (str (or value ""))
        length (count text)
        truncated? (> length limit)]
    {:text (if truncated? (subs text 0 limit) text)
     :original-chars length
     :truncated? truncated?}))

(defn- stable-error [error]
  (let [class (bounded-text (str (class error)) maximum-error-class-chars)
        message
        (bounded-text
         (or (ex-message error)
             (try (jolt.host/condition-message error)
                  (catch :default _ nil)))
         maximum-error-message-chars)
        data (ex-data error)
        entries (if (map? data)
                  (->> data
                       (sort-by (fn [[key _]] (safe-render key)))
                       (take maximum-error-data-entries)
                       (mapv
                        (fn [[key value]]
                          {:key (bounded-text (safe-render key)
                                              maximum-error-key-chars)
                           :value (bounded-text (safe-render value)
                                                maximum-error-value-chars)})))
                  [])
        entry-count (if (map? data) (count data) 0)]
    {:class (:text class)
     :class-original-chars (:original-chars class)
     :class-truncated? (:truncated? class)
     :message (:text message)
     :message-original-chars (:original-chars message)
     :message-truncated? (:truncated? message)
     :data {:entry-count entry-count
            :retained-count (count entries)
            :entries-truncated? (> entry-count (count entries))
            :entries entries}}))

(defn- trim-to [items limit]
  (let [items (vec items)
        overflow (- (count items) limit)]
    (if (pos? overflow) (subvec items overflow) items)))

(defn- append-bounded! [target limit value]
  (swap! target #(trim-to (conj % value) limit))
  nil)

(defn- phase-for [store-state]
  (let [rows (:outbox store-state)]
    (cond
      (empty? rows) :empty
      (some #(= :pending (:status %)) rows) :pending
      :else :delivered)))

(defn- evidence-token [items]
  (if (empty? items)
    {:from nil :through nil :count 0}
    {:from (:sequence (first items))
     :through (:sequence (peek items))
     :count (count items)}))

(defn- valid-token? [token]
  (and (map? token)
       (= #{:from :through :count} (set (keys token)))
       (integer? (:count token))
       (not (neg? (:count token)))
       (if (zero? (:count token))
         (and (nil? (:from token)) (nil? (:through token)))
         (and (integer? (:from token))
              (integer? (:through token))
              (<= 0 (:from token) (:through token))))))

(defn- captured-prefix [items token]
  (when-not (valid-token? token)
    (fail! :invalid-navigation {:value token}))
  (if (zero? (:count token))
    []
    (let [captured (->> items
                        (filter #(<= (:from token)
                                     (:sequence %)
                                     (:through token)))
                        vec)]
      (when-not (and (= (:count token) (count captured))
                     (= (:from token) (:sequence (first captured)))
                     (= (:through token) (:sequence (peek captured))))
        (fail! :stale-navigation
               {:from (:from token) :through (:through token)}))
      captured)))

(defn- ensure-open! [state operation]
  (when-not (= :open (:status @state))
    (fail! :stopped {:operation operation :status (:status @state)})))

(defn- submission-summary [sequence command cycle wire]
  {:sequence sequence
   :kind :jolt.sim.kind/outbox-submission
   :status (get-in cycle [:parsed :status])
   :request-id (:request-id command)
   :entity-id (:entity-id command)
   :payload-octets (count (:payload command))
   :response wire
   :close-results (:close-results cycle)})

(defn- failed-submission-summary [sequence command error]
  {:sequence sequence
   :kind :jolt.sim.kind/outbox-submission
   :status :failed
   :request-id (:request-id command)
   :entity-id (:entity-id command)
   :payload-octets (count (:payload command))
   :error (stable-error error)})

(defn- retain-submission! [state store-state evidence retained-evidence]
  (let [sequence (:sequence evidence)]
    (swap! state
           (fn [before]
             (-> before
                 (assoc :store-state store-state
                        :phase (phase-for store-state)
                        :revision (inc (:revision before))
                        :next-sequence (inc sequence))
                 (update :submissions
                         #(trim-to (conj % evidence) retained-evidence))))))
  evidence)

(defn- delivery-summary [sequence row exchange status error]
  (cond->
   {:sequence sequence
    :kind :jolt.sim.kind/outbox-delivery-attempt
    :status status
    :outbox-id (:outbox-id row)
    :request-id (:request-id row)
    :payload-octets (count (:payload row))}
    exchange
    (assoc :sent-bytes (:sent-bytes exchange)
           :received-bytes (:received-bytes exchange)
           :reply (first (:replies exchange))
           :close-results (:close-results exchange))
    error (assoc :error (stable-error error))))

(defn- retain-delivery! [state store-state evidence retained-evidence]
  (let [sequence (:sequence evidence)]
    (swap! state
           (fn [before]
             (-> before
                 (assoc :store-state store-state
                        :phase (phase-for store-state)
                        :revision (inc (:revision before))
                        :next-sequence (inc sequence))
                 (update :deliveries
                         #(trim-to (conj % evidence) retained-evidence))))))
  evidence)

(defn- validate-delivered-commit! [pending-row mark-step]
  (let [outbox-id (:outbox-id pending-row)
        committed-state (:state mark-step)
        target-rows (filterv #(= outbox-id (:outbox-id %))
                             (:outbox committed-state))
        pending-targets (filterv #(and (= outbox-id (:outbox-id %))
                                       (= :pending (:status %)))
                                 (:outbox committed-state))]
    (when-not (and (true? (:changed? mark-step))
                   (= 1 (count target-rows))
                   (= (:row mark-step) (first target-rows))
                   (= :delivered (:status (first target-rows)))
                   (empty? pending-targets))
      (fail! :invalid-delivered-commit
             {:outbox-id outbox-id
              :changed? (:changed? mark-step)
              :target-count (count target-rows)
              :pending-target-count (count pending-targets)
              :row (:row mark-step)}))
    committed-state))

(defn- snapshot-state [state receiver-requests http-errors receiver-errors]
  (let [{:keys [status phase revision store-state submissions deliveries
                http-port delivery-port retained-evidence]} @state]
    {:kind :jolt.sim.kind/outbox-live-lifecycle
     :status status
     :phase phase
     :revision revision
     :endpoints {:http {:host "127.0.0.1" :port http-port}
                 :delivery {:host "127.0.0.1" :port delivery-port}}
     :store-state store-state
     :retained-evidence retained-evidence
     :submissions (evidence-token submissions)
     :deliveries (evidence-token deliveries)
     :receiver-requests (evidence-token @receiver-requests)
     :http-errors (evidence-token @http-errors)
     :receiver-errors (evidence-token @receiver-errors)}))

(defn snapshot!
  "Returns a cheap immutable lifecycle snapshot. The complete current durable
  state is present at :store-state; bounded observations are navigation
  tokens that clojure.datafy/nav expands from the opaque handle."
  [lifecycle]
  (lifecycle snapshot-operation nil))

(defn submit-command!
  "Submits one canonical command through the lifecycle's real JSON HTTP
  listener. Client I/O never holds the SQLite owner lock; the server handler
  acquires it for the production transaction. Returns bounded HTTP evidence
  after the post-response durable reload has completed."
  [lifecycle command]
  (lifecycle submit-operation command))

(defn deliver-next!
  "Delivers the first durable pending row through the real framed TCP receiver.
  The SQLite owner lock spans pending reload, exchange, exact ack validation,
  mark-delivered!, and final reload. A hostile ack throws before any mark and
  leaves the durable row pending. Returns :empty when no pending row exists."
  [lifecycle]
  (lifecycle deliver-operation nil))

(defn stop!
  "Stops HTTP first, then the delivery receiver, then closes SQLite. Returns
  true for the owner call and false thereafter. The final immutable state and
  bounded evidence remain datafiable; submit/deliver reject after stopping."
  [lifecycle]
  (lifecycle stop-operation nil))

(defn- start-config [config]
  (when-not (and (map? config)
                 (every? #{:reply-for :retained-evidence} (keys config)))
    (fail! :invalid-config {:value-class (str (class config))}))
  (let [reply-for (get config :reply-for delivery/expected-ack)
        retained (get config :retained-evidence default-retained-evidence)]
    (when-not (fn? reply-for)
      (fail! :invalid-reply-function {:value-class (str (class reply-for))}))
    (when-not (and (integer? retained)
                   (<= 1 retained maximum-retained-evidence))
      (fail! :invalid-retention
             {:value retained :maximum maximum-retained-evidence}))
    {:reply-for reply-for :retained-evidence retained}))

(defn- open-store!
  []
  (let [conn (jdbc/connection "sqlite::memory:")]
    (try
      (store/init-schema! conn)
      {:connection conn :state (store/load-state conn)}
      (catch :default error
        (try ((:close conn)) (catch :default _ nil))
        (throw error)))))

(defn- start-servers!
  [http-handler receiver-handler http-error-logger receiver-error-logger
   close-store!]
  (let [receiver* (atom nil)]
    (try
      (let [receiver (tcp/run-server
                      :port 0
                      :reuse-address? true
                      :handler receiver-handler
                      :error-logger receiver-error-logger)
            _ (reset! receiver* receiver)
            http-server (http/run-server http-handler
                                         :port 0
                                         :reuse-address? true
                                         :error-logger http-error-logger)]
        {:http http-server :receiver receiver})
      (catch :default primary
        (let [cleanup-errors (atom [])]
          (when-let [receiver @receiver*]
            (try (tcp/stop-server receiver)
                 (catch :default error
                   (swap! cleanup-errors conj
                          {:component :delivery-server
                           :error (stable-error error)}))))
          (try (close-store!)
               (catch :default error
                 (swap! cleanup-errors conj
                        {:component :sqlite :error (stable-error error)})))
          (if (seq @cleanup-errors)
            (throw
             (ex-info (or (ex-message primary) (str primary))
                      (assoc (or (ex-data primary) {})
                             :outbox-live/start-cleanup-errors @cleanup-errors)
                      primary))
            (throw primary)))))))

(defn start!
  "Starts the live canonical outbox application and returns an opaque
  datafy/nav lifecycle capability. Optional config is a closed map containing
  :reply-for and/or :retained-evidence (1..1024)."
  ([] (start! {}))
  ([config]
   (let [{:keys [reply-for retained-evidence]} (start-config config)
         sqlite-lock (Object.)
         submit-lock (Object.)
         status-lock (Object.)
         opened (open-store!)
         conn (:connection opened)
         initial-store (:state opened)
         receiver-requests (atom [])
         http-errors (atom [])
         receiver-errors (atom [])
         observation-sequence (atom -1)
         next-observation-sequence! #(swap! observation-sequence inc)
         state (atom {:status :open
                      :phase (phase-for initial-store)
                      :revision 0
                      :next-sequence 0
                      :store-state initial-store
                      :submissions []
                      :deliveries []
                      :http-port nil
                      :delivery-port nil
                      :retained-evidence retained-evidence})
         record-received!
         #(append-bounded! receiver-requests retained-evidence
                           ;; Async observations use a never-reused coordinate
                           ;; independent of application-operation sequences.
                           {:sequence (next-observation-sequence!)
                            :kind :jolt.sim.kind/outbox-receiver-request
                            :message %})
         production-handler (json-facade/command-handler conn)
         resources
         (start-servers!
          (fn [request]
            (locking sqlite-lock
              (if (= :open (:status @state))
                (production-handler request)
                {:status 503
                 :headers {"Content-Type" "application/json"}
                 :body "{\"error\":{\"type\":\"stopped\"}}"})))
          (delivery/delivery-receiver-handler record-received! reply-for)
          #(append-bounded! http-errors retained-evidence
                            {:sequence (next-observation-sequence!)
                             :error (stable-error %)})
          #(append-bounded! receiver-errors retained-evidence
                            {:sequence (next-observation-sequence!)
                             :error (stable-error %)})
          #((:close conn)))
         http-server (:http resources)
         receiver (:receiver resources)
         cleanup-pending
         (atom #{:http-server :delivery-server :sqlite})]
     (swap! state assoc
            :http-port (:port http-server)
            :delivery-port (:port receiver))
     (reify
       clojure.lang.IFn
       (invoke [_ operation argument]
         (cond
           (identical? operation snapshot-operation)
           (locking sqlite-lock
             ;; Direct clients may use the exposed real HTTP endpoint without
             ;; going through submit-command!. Reload while SQLite is still
             ;; owned so this observation always follows the latest COMMIT.
             (when (contains? @cleanup-pending :sqlite)
               (let [store-state (store/load-state conn)]
                 (swap! state assoc :store-state store-state
                        :phase (phase-for store-state))))
             (snapshot-state state receiver-requests
                             http-errors receiver-errors))

           (identical? operation navigate-operation)
           (let [[key token] argument]
             (case key
               :submissions (captured-prefix (:submissions @state) token)
               :deliveries (captured-prefix (:deliveries @state) token)
               :receiver-requests
               (captured-prefix @receiver-requests token)
               :http-errors
               (captured-prefix @http-errors token)
               :receiver-errors
               (captured-prefix @receiver-errors token)
               token))

           (identical? operation submit-operation)
           (locking submit-lock
             ;; Admission is checked under the SQLite lock, then released so
             ;; the real server handler can own that lock while it commits.
             (locking sqlite-lock (ensure-open! state :submit-command))
             (let [outcome
                   (try
                     (let [cycle
                           (http-fixture/request-running-server!
                            "127.0.0.1" (:port http-server)
                            (fn [host port]
                              ((:request-bytes-for
                                json-fixture/json-http-seam)
                               argument host port))
                            http-errors)
                           parsed (:parsed cycle)
                           wire ((:decode-response
                                  json-fixture/json-http-seam)
                                 (:body parsed))]
                       {:cycle cycle :wire wire})
                     (catch :default error {:error error}))]
               (locking sqlite-lock
                 ;; Even a response/transport failure may follow a successful
                 ;; durable COMMIT. Reconcile the real store before returning
                 ;; or rethrowing so snapshot! never remains falsely empty.
                 (let [store-state (store/load-state conn)
                       sequence (:next-sequence @state)]
                   (if-let [error (:error outcome)]
                     (do
                       (retain-submission!
                        state store-state
                        (failed-submission-summary sequence argument error)
                        retained-evidence)
                       (throw error))
                     (retain-submission!
                      state store-state
                      (submission-summary sequence argument
                                          (:cycle outcome) (:wire outcome))
                      retained-evidence))))))

           (identical? operation deliver-operation)
           (locking sqlite-lock
             (ensure-open! state :deliver-next)
             (let [before-store (store/load-state conn)
                   row (first (filter #(= :pending (:status %))
                                      (:outbox before-store)))]
               (if-not row
                 {:status :empty :store-state before-store}
                 (let [message (delivery/delivery-message row)
                       sequence (:next-sequence @state)
                       exchange-outcome
                       (try
                         (let [exchange
                               (delivery/exchange-deliveries!
                                "127.0.0.1" (:port receiver) [message])]
                           (delivery/validate-acknowledgements!
                            [message] (:replies exchange))
                           {:exchange exchange})
                         (catch :default error {:error error}))]
                   (if-let [error (:error exchange-outcome)]
                     (let [unchanged (store/load-state conn)
                           evidence
                           (delivery-summary sequence row nil :failed error)]
                       (retain-delivery! state unchanged evidence retained-evidence)
                       (throw error))
                     (let [exchange (:exchange exchange-outcome)
                           mark-step (store/mark-delivered! conn (:outbox-id row))
                           committed-state
                           (validate-delivered-commit! row mark-step)
                           evidence
                           (delivery-summary sequence row exchange :delivered nil)]
                       ;; mark-delivered! returns only after COMMIT. Record that
                       ;; truth before the corroborating reload, so a later
                       ;; observation failure cannot relabel it :failed.
                       (retain-delivery! state committed-state evidence
                                         retained-evidence)
                       (let [final-store (store/load-state conn)]
                         (when-not (= committed-state final-store)
                           (fail! :mark-state-mismatch
                                  {:outbox-id (:outbox-id row)
                                   :committed-state committed-state
                                   :loaded-state final-store}))
                         (assoc evidence
                                :marking (dissoc mark-step :state)
                                :store-state final-store))))))))

           (identical? operation stop-operation)
           (let [owner?
                 (locking status-lock
                   (if (contains? #{:open :stop-failed} (:status @state))
                     (do (swap! state assoc :status :stopping) true)
                     false))]
             (if-not owner?
               false
               (let [errors (atom [])
                     attempt! (fn [component thunk]
                                (when (contains? @cleanup-pending component)
                                  (try
                                    (thunk)
                                    (swap! cleanup-pending disj component)
                                    (catch :default error
                                      (swap! errors conj
                                             {:component component
                                              :error (stable-error error)})))))]
                 ;; Claim stopping without waiting behind blocking I/O. Server
                 ;; teardown is what wakes any admitted submit/delivery; only
                 ;; after both listeners stop do we wait for their owners and
                 ;; close SQLite. Failed components remain retryable.
                 (attempt! :http-server #(http/stop-server http-server))
                 (attempt! :delivery-server #(tcp/stop-server receiver))
                 (when-not (or (contains? @cleanup-pending :http-server)
                               (contains? @cleanup-pending :delivery-server))
                   (locking submit-lock
                     (locking sqlite-lock
                       (when (contains? @cleanup-pending :sqlite)
                         (try
                           (let [final-store (store/load-state conn)]
                             (swap! state assoc
                                    :store-state final-store
                                    :phase (phase-for final-store)))
                           (catch :default error
                             (swap! errors conj
                                    {:component :store-snapshot
                                     :error (stable-error error)})))
                         (attempt! :sqlite #((:close conn)))))))
                 (swap! state assoc
                        :status (if (empty? @cleanup-pending)
                                  :stopped
                                  :stop-failed)
                        :revision (inc (:revision @state)))
                 (if (seq @errors)
                   (fail! :cleanup-failed
                          {:cleanup-errors @errors
                           :cleanup-pending @cleanup-pending})
                   true))))

           :else
           (fail! :invalid-operation {})))

       protocols/Datafiable
       (datafy [this]
         (snapshot! this))

       protocols/Navigable
       (nav [this key value]
         (case key
           :submissions (this navigate-operation [key value])
           :deliveries (this navigate-operation [key value])
           :receiver-requests (this navigate-operation [key value])
           :http-errors (this navigate-operation [key value])
           :receiver-errors (this navigate-operation [key value])
           value))))))
