(ns jolt.sim.fixtures.outbox-delivery
  "Ordinary Jolt whole-application fixture for one committed outbox delivery.

  The same function runs against real native resources or underneath jolt-sim
  handlers. This namespace itself has no simulator dependency: it uses public
  jolt-http, jdbc.core, teensyp TCP/client, jolt.bytes, and jolt.bencode APIs,
  plus the existing ordinary HTTP and framed-TCP fixture seams.

   One HTTP POST carries a bencoded canonical command whose payload is an octet
   vector. The request handler applies the existing SQLite outbox transition and
   returns after COMMIT. Once the HTTP server is quiescent, the outer application
   reloads the pending row, closes SQLite, and gives the materialized message to
   an ordinary framed TCP/bencode delivery function. The receiver returns an
   outbox-id/attempt ack. This first witness deliberately does not mark the row
   delivered: the reloaded store state remains :pending after the acknowledged
   attempt. It proves post-COMMIT visibility, not close/reopen or crash
   durability.

   exercise-outbox-delivery-retry is the two-attempt at-least-once companion
   witness. The HTTP phase is identical, but the SQLite connection stays open
   across delivery: the application reloads the committed row, makes one
   ordinary delivery attempt, catches an ordinary transport failure, closes
   and cleans the first connection, reloads through the same still-open
   connection to prove the committed state unchanged, and redelivers the same
   row with an incremented attempt over a fresh ordinary connection. The
   receiver therefore records exactly attempts [1 2] -- duplicate delivery is
   the witnessed semantics, not an exactly-once claim."
  (:require [jdbc.core :as jdbc]
            [jolt.bencode :as bencode]
            [jolt.bytes :as bytes]
            [jolt.example.outbox.sqlite :as store]
            [jolt.host :as host]
            [jolt.http.body :as http-body]
            [jolt.sim.fixtures.http-sqlite :as http-fixture]
            [jolt.sim.fixtures.tcp-bencode :as framed]
            [teensyp.client :as client]
            [teensyp.server :as tcp]))

(def default-command
  {:request-id "req-1"
   :entity-id "entity-a"
   :payload [0 127 128 255]})

(def ^:private command-keys
  #{:request-id :entity-id :payload})

(def ^:private command-wire-keys
  #{"request-id" "entity-id" "payload"})

(def ^:private delivery-wire-keys
  #{"type" "outbox-id" "request-id" "entity-id" "version" "payload"
    "attempt"})

(def ^:private bencode-content-type "application/x-bencode")

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox-delivery fixture: " (name reason))
    {:type :jolt.sim.fixtures.outbox-delivery/invalid-flow
     :reason reason
     :detail detail})))

(defn- stable-error-summary [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (host/condition-message error)
                     (catch :default _ nil)))
   :data (when-let [data (ex-data error)]
           (into {}
                 (map (fn [[k v]] [(str k) (pr-str v)]))
                 data))})

(defn- cleanup-attempt [operation thunk]
  (try
    (thunk)
    nil
    (catch :default error
      {:operation operation :error error})))

(defn- phase-error
  "Wraps an ordinary transport/storage error with the bounded keyword of the
   retry phase it escaped from, preserving the original message, ex-data, and
   cause chain. Failure-site identity is part of the witness contract: an
   infrastructure or transport failure must name its phase rather than
   masquerade as an untracked semantic counterexample."
  [phase error]
  (ex-info
   (or (ex-message error) (str error))
   (assoc (or (ex-data error) {})
          :outbox-delivery/phase phase)
   error))

(defn- cleanup-summaries [cleanup-errors]
  (mapv (fn [{:keys [operation error]}]
          (assoc (stable-error-summary error) :operation operation))
        cleanup-errors))

(defn- throw-with-cleanup! [primary cleanup-errors]
  (if primary
    (if (seq cleanup-errors)
      (throw
       (ex-info
        (or (ex-message primary) (str primary))
        (assoc (or (ex-data primary) {})
               :outbox-delivery/primary-error (stable-error-summary primary)
               :outbox-delivery/cleanup-errors
               (cleanup-summaries cleanup-errors))
        primary))
      (throw primary))
    (when (seq cleanup-errors)
      (let [first-error (:error (first cleanup-errors))]
        (throw
         (ex-info
          "outbox-delivery fixture cleanup failed"
          {:type :jolt.sim.fixtures.outbox-delivery/cleanup-failure
           :outbox-delivery/cleanup-errors
           (cleanup-summaries cleanup-errors)}
          first-error))))))

(defn- concat-byte-arrays ^bytes [chunks]
  (let [total (reduce + 0 (map alength chunks))
        out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [^bytes chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 out offset (alength chunk))
          (recur (rest remaining) (+ offset (alength chunk))))
        out))))

(defn- decode-bencode-exact [context ^bytes raw]
  (let [decoded (bencode/decode-bytes raw)]
    (when-not (= :ok (:status decoded))
      (fail! :invalid-bencode
             {:context context
              :raw-length (alength raw)
              :raw-bytes (vec raw)
              :status (:status decoded)
              :reason (:reason decoded)}))
    (let [position (bytes/cursor-position (:cursor decoded))]
      (when-not (= (alength raw) position)
        (fail! :trailing-bencode
               {:context context
                :length (alength raw)
                :position position})))
    (:value decoded)))

(defn- command->wire [command]
  (when-not (and (map? command)
                 (= command-keys (set (keys command))))
    (fail! :invalid-command {:value command}))
  {"request-id" (:request-id command)
   "entity-id" (:entity-id command)
   "payload" (:payload command)})

(defn- wire->command [value]
  (when-not (and (map? value)
                 (= command-wire-keys (set (keys value))))
    (fail! :invalid-command-wire {:value value}))
  {:request-id (get value "request-id")
   :entity-id (get value "entity-id")
   :payload (get value "payload")})

(defn- post-request-bytes [command host port]
  (let [body (bencode/encode (command->wire command))
        head (.getBytes
              (str "POST /commands HTTP/1.1\r\n"
                   "Host: " host ":" port "\r\n"
                   "Content-Type: " bencode-content-type "\r\n"
                   "Content-Length: " (alength body) "\r\n"
                   "Connection: close\r\n"
                   "\r\n")
              "UTF-8")]
    (concat-byte-arrays [head body])))

(defn- command-response-wire [result]
  {"type" "command_ok"
   "status" (name (:status result))
   "request-id" (:request-id result)
   "entity-id" (:entity-id result)
   "version" (:version result)
   "outbox-id" (:outbox-id result)})

(defn- semantic-identities [result]
  (let [request-id (:request-id result)
        outbox-id (:outbox-id result)]
    {:request-id request-id
     :transaction-id [:outbox/command request-id]
     :outbox-id outbox-id
     :delivery-id [:outbox/delivery outbox-id]
     :attempt-id [:outbox/delivery-attempt outbox-id 1]}))

(defn- row->delivery-wire
  ([row]
   (row->delivery-wire row 1))
  ([row attempt]
   {"type" "outbox_delivery"
    "outbox-id" (:outbox-id row)
    "request-id" (:request-id row)
    "entity-id" (:entity-id row)
    "version" (:version row)
    "payload" (:payload row)
    "attempt" attempt}))

(defn- expected-ack [message]
  {"type" "outbox_delivery_ok"
   "outbox-id" (get message "outbox-id")
   "attempt" (get message "attempt")})

(defn- receiver-reply [received]
  (fn [message]
    (when-not (and (map? message)
                   (= delivery-wire-keys (set (keys message)))
                   (= "outbox_delivery" (get message "type")))
      (fail! :invalid-delivery-wire {:value message}))
    (swap! received conj message)
    (expected-ack message)))

(defn- exchange-deliveries! [host port messages]
  (let [connection* (atom nil)
        body
        (try
          {:value
           (let [connection (client/connect host port
                                            {:connect-timeout-ms 5000})
                 _ (reset! connection* connection)
                 exchange (framed/exchange! connection messages)
                 first-close (client/close! connection)
                 second-close (client/close! connection)]
             (assoc exchange
                    :close-results
                    {:connection [first-close second-close]}))}
          (catch :default error
            {:error error}))
        cleanup-errors
        (vec
         (keep identity
               [(when-let [connection @connection*]
                  (cleanup-attempt :delivery-connection-close
                                   #(client/close! connection)))]))]
    (throw-with-cleanup! (:error body) cleanup-errors)
    (:value body)))

(defn- load-pending-delivery
  ;; apply-command! has already returned across COMMIT. Reload through the
  ;; ordinary adapter so the worker consumes the committed row, not :emitted.
  ;; The attempt arity renumbers only the wire "attempt" field for the retry
  ;; witness; the reloaded row semantics are identical on every call.
  ([conn]
   (load-pending-delivery conn 1))
  ([conn attempt]
   (let [state (store/load-state conn)
         rows (filterv #(= :pending (:status %)) (:outbox state))
         _ (when-not (= 1 (count rows))
             (fail! :unexpected-pending-count {:count (count rows)}))
         messages (mapv #(row->delivery-wire % attempt) rows)]
     {:state state
      :messages messages})))

(defn- deliver-messages! [host port messages]
  (let [exchange (exchange-deliveries! host port messages)
        expected (mapv expected-ack messages)]
    (when-not (= expected (:replies exchange))
      (fail! :ack-mismatch
             {:expected expected :actual (:replies exchange)}))
    exchange))

(defn- attempt-delivery!
  "One ordinary delivery attempt over one fresh ordinary TCP connection:
   connect, framed pipelined exchange, idempotent close -- the same ordinary
   client path as exchange-deliveries!. Unlike deliver-messages! this
   function is the retry witness's catching boundary: an ordinary transport
   failure (for example a captured ECONNRESET on the ack read) is caught by
   ordinary application code, the connection is still closed/cleaned, and a
   bounded stable failure summary is returned instead of throwing. Returns
   {:status :delivered :exchange ...} or
   {:status :failed :error <bounded summary> :cleanup-errors []}.
   A failed attempt is eligible for retry only after its connection cleanup
   succeeds. Any cleanup failure throws before the caller can reload or open a
   second connection, preserving the transport error as primary and attaching
   bounded cleanup evidence. Success-path cleanup failures likewise throw."
  [host port messages]
  (let [connection* (atom nil)
        body
        (try
          {:value
           (let [connection (client/connect host port
                                            {:connect-timeout-ms 5000})
                 _ (reset! connection* connection)
                 exchange (framed/exchange! connection messages)
                 first-close (client/close! connection)
                 second-close (client/close! connection)]
             (assoc exchange
                    :close-results
                    {:connection [first-close second-close]}))}
          (catch :default error
            {:error error}))
        cleanup-errors
        (vec
         (keep identity
               [(when-let [connection @connection*]
                  (cleanup-attempt :delivery-connection-close
                                   #(client/close! connection)))]))]
    (if-let [error (:error body)]
      (if (seq cleanup-errors)
        ;; Ownership is uncertain: do not authorize reload/retry merely
        ;; because the primary error is an expected reset. The shared helper
        ;; keeps that reset primary and carries bounded cleanup diagnostics.
        (throw-with-cleanup! error cleanup-errors)
        {:status :failed
         :error (stable-error-summary error)
         :cleanup-errors []})
      (do
        (throw-with-cleanup! nil cleanup-errors)
        {:status :delivered :exchange (:value body)}))))

(defn- command-handler [conn command-evidence]
  (fn [request]
    (when-not (= [:post "/commands"]
                 [(:request-method request) (:uri request)])
      (fail! :unsupported-http-route
             {:request-method (:request-method request)
              :uri (:uri request)}))
    (let [command (->> (http-body/body-bytes (:body request))
                       (decode-bencode-exact :http-command)
                       wire->command)
          step (store/apply-command! conn command)
          result (:result step)
          evidence
          {:identities (semantic-identities result)
           :command {:value command
                     :result result
                     :emitted (:emitted step)}
           ;; Retained only until the ordinary post-COMMIT reload is compared.
           ;; The final public evidence uses :store-state instead.
           :committed-state (:state step)}]
      (reset! command-evidence evidence)
      {:status 200
       :headers {"Content-Type" bencode-content-type}
       :body (bencode/encode (command-response-wire result))})))

(defn exercise-outbox-delivery
  "Runs one ordinary HTTP -> committed SQLite outbox -> framed TCP/bencode
  delivery/ack flow. Returns canonical immutable evidence with no native
  handles, pointers, byte arrays, mutable values, controller objects, or
  ephemeral ports. The optional command arity lets generated hermetic plans
  exercise payload variants; the real/sim parity witness still uses
  default-command.
  The bencode payload is an octet vector; SQLite stores the same semantics as a
  BLOB, but this witness does not claim bencode binary-string wire parity."
  ([]
   (exercise-outbox-delivery default-command))
  ([command]
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler (receiver-reply received))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         command-evidence* (atom nil)
         body
         (try
           {:value
            (let [{:keys [http-cycle command-evidence store-state messages]}
                  (with-open [conn (jdbc/connection "sqlite::memory:")]
                    (store/init-schema! conn)
                    (let [http-cycle
                          (http-fixture/run-request-cycle
                           (command-handler conn command-evidence*)
                           (fn [host port]
                             (post-request-bytes command host port)))
                          command-evidence @command-evidence*
                          _ (when-not command-evidence
                              (fail! :missing-application-evidence {}))
                          pending (load-pending-delivery conn)
                          store-state (:state pending)
                          _ (when-not (= (:committed-state command-evidence)
                                         store-state)
                              (fail! :committed-state-mismatch
                                     {:step-state
                                      (:committed-state command-evidence)
                                      :loaded-state store-state}))]
                      {:http-cycle http-cycle
                       :command-evidence command-evidence
                       :store-state store-state
                       :messages (:messages pending)}))
                  ;; The HTTP request has succeeded, its server is quiescent,
                  ;; and SQLite is closed before downstream delivery begins.
                  delivery (deliver-messages! "127.0.0.1" (:port receiver)
                                               messages)
                  app (-> command-evidence
                          (dissoc :committed-state)
                          (assoc :store-state store-state
                                 :delivery delivery))
                  parsed (:parsed http-cycle)
                  response
                  (decode-bencode-exact :http-response (:body parsed))]
              {:application app
               :http {:status (:status parsed)
                      :content-type
                      (get (:headers parsed) "content-type")
                      :content-length
                      (get (:headers parsed) "content-length")
                      :response response
                      :server-errors (:server-errors http-cycle)
                      :close-results (:close-results http-cycle)}})}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :receiver-stop #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the receiver before its final error snapshot.
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))

(defn exercise-outbox-delivery-retry
  "Runs the ordinary HTTP -> committed SQLite outbox flow exactly as
   exercise-outbox-delivery, then proves one bounded at-least-once delivery
   retry: the SQLite connection stays open across delivery, delivery attempt
   1 runs the ordinary framed TCP/bencode path, an ordinary transport failure
   on that attempt is caught by ordinary application code, the first
   connection is closed/cleaned, the same still-open connection is reloaded
   to prove the committed outbox/application state unchanged, and delivery
   attempt 2 carries the same reloaded row with \"attempt\" 2 over a fresh
   ordinary connection to a correlated ack.

   This witness requires the retry path: attempt 1 must fail with a caught
   read-side connection reset for the two-attempt semantics to be exercised
   (under the simulation lane's scoped recv ECONNRESET rule it always does).
   An unexpectedly delivered attempt 1 is a typed witness violation, and any
   other attempt-1 failure -- connect error, send-side error, or deadline --
   is a typed :retry-attempt-1-anomaly, an infrastructure anomaly rather
   than a retry counterexample: the second attempt runs only when the
   caught failure is the reset this witness exists for. Attempt 2 likewise
   fails typed and bounded when it does not deliver the correlated ack. No
   row is ever marked delivered: the reloaded store state remains :pending
   throughout.

   The receiver's :server-errors reflect the world the flow runs against:
   under the simulation lane's bounded receive FIFOs (every supported
   capacity is smaller than the constant 59-octet ack frame) the attempt-1
   ack cannot be completely written before the client resets/closes without
   reading it, so one pending or subsequent ack write surfaces ordinary EPIPE.
   The admission gate proves only that receiver processing reached the
   ack-send boundary before the reset boundary; it does not order the two
   native-handler executions or identify which write fails. A real kernel's
   larger buffers may show no receiver error. No real-kernel ECONNRESET or
   buffering parity is claimed.

   Returns the same canonical immutable evidence shape as
   exercise-outbox-delivery, with :application carrying :retry instead of
   :delivery:

     {:attempt-1 {:error <bounded summary> :cleanup-errors [...]}
      :state-unchanged-after-failure? true
      :delivery <attempt-2 exchange, the non-retry :delivery shape>}"
  ([]
   (exercise-outbox-delivery-retry default-command))
  ([command]
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler (receiver-reply received))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         command-evidence* (atom nil)
         body
         (try
           {:value
            ;; The SQLite connection deliberately stays OPEN across both
            ;; delivery attempts: the post-failure reload flows through the
            ;; same ordinary connection, proving the committed state without
            ;; any close/reopen or crash-durability claim.
            (with-open [conn (jdbc/connection "sqlite::memory:")]
              (store/init-schema! conn)
              (let [http-cycle
                    (try
                      (http-fixture/run-request-cycle
                       (command-handler conn command-evidence*)
                       (fn [host port]
                         (post-request-bytes command host port)))
                      (catch :default error
                        (throw (phase-error :http error))))
                    command-evidence @command-evidence*
                    _ (when-not command-evidence
                        (fail! :missing-application-evidence {}))
                    pending (load-pending-delivery conn)
                    store-state (:state pending)
                    _ (when-not (= (:committed-state command-evidence)
                                   store-state)
                        (fail! :committed-state-mismatch
                               {:step-state
                                (:committed-state command-evidence)
                                :loaded-state store-state}))
                    ;; Delivery attempt 1: ordinary connect/exchange/close.
                    ;; Ordinary application code catches the transport
                    ;; failure and cleans the first connection.
                    attempt-1 (attempt-delivery! "127.0.0.1" (:port receiver)
                                                 (:messages pending))
                    _ (when-not (= :failed (:status attempt-1))
                        (fail! :retry-attempt-not-exercised
                               {:attempt-1 attempt-1}))
                    ;; Ordinary retry classification: the retry path exists
                    ;; for a caught read-side connection reset (the injected
                    ;; ECONNRESET under the simulation lane, or a real reset
                    ;; against native resources). Any other attempt-1
                    ;; failure -- a connect failure, a send-side error, or a
                    ;; deadline, all infrastructure anomalies for this
                    ;; witness rather than retry counterexamples -- fails
                    ;; closed with a typed, bounded anomaly instead of
                    ;; proceeding to a second attempt whose fault-scoped
                    ;; evidence would no longer mean retry-after-reset.
                    _ (when-not (and (= ":read"
                                        (get-in attempt-1
                                                [:error :data ":jolt.net/op"]))
                                     (= ":connection-reset"
                                        (get-in attempt-1
                                                [:error :data
                                                 ":jolt.net/kind"])))
                        (fail! :retry-attempt-1-anomaly
                               {:attempt-1 attempt-1}))
                    ;; The post-failure reload through the same still-open
                    ;; connection must observe the exact committed state.
                    reloaded (load-pending-delivery conn 2)
                    _ (when-not (= store-state (:state reloaded))
                        (fail! :post-failure-state-changed
                               {:committed-state store-state
                                :loaded-state (:state reloaded)}))
                    ;; Delivery attempt 2: a fresh ordinary connection, the
                    ;; same reloaded row, an incremented attempt, and the
                    ;; ordinary correlated-ack check. The catching boundary
                    ;; keeps any second-attempt transport failure a typed,
                    ;; bounded witness failure rather than a raw escape.
                    attempt-2 (attempt-delivery! "127.0.0.1" (:port receiver)
                                                 (:messages reloaded))
                    _ (when-not (= :delivered (:status attempt-2))
                        (fail! :retry-attempt-2-failed
                               {:attempt-2 (dissoc attempt-2 :status)}))
                    delivery (:exchange attempt-2)
                    _ (when-not (= (mapv expected-ack (:messages reloaded))
                                   (:replies delivery))
                        (fail! :ack-mismatch
                               {:expected (mapv expected-ack
                                                (:messages reloaded))
                                :actual (:replies delivery)}))
                    app (-> command-evidence
                            (dissoc :committed-state)
                            (assoc :store-state store-state
                                   :retry
                                   {:attempt-1 (dissoc attempt-1 :status)
                                    :state-unchanged-after-failure? true
                                    :delivery delivery}))
                    parsed (:parsed http-cycle)
                    response
                    (decode-bencode-exact :http-response (:body parsed))]
                {:application app
                 :http {:status (:status parsed)
                        :content-type
                        (get (:headers parsed) "content-type")
                        :content-length
                        (get (:headers parsed) "content-length")
                        :response response
                        :server-errors (:server-errors http-cycle)
                        :close-results (:close-results http-cycle)}}))}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :receiver-stop #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the receiver before its final error snapshot.
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))
