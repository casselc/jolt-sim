(ns jolt.sim.fixtures.outbox-delivery
  "Ordinary Jolt whole-application fixture for one committed outbox delivery.

  The same function runs against real native resources or underneath jolt-sim
  handlers. This namespace itself has no simulator dependency: it uses public
  jolt-http, jdbc.core, teensyp TCP/client, jolt.bytes, and jolt.bencode APIs,
  plus the existing ordinary HTTP and framed-TCP fixture seams.

   The HTTP command phase is a closed seam: request construction, handler
   construction, the expected status, and response decoding are the only four
   things a lane may replace. exercise-outbox-delivery-via runs the unchanged
   whole-application flow against any validated seam; exercise-outbox-delivery
   is that function with the bencode seam below. The routed JSON facade lane in
   jolt.sim.fixtures.outbox-json-delivery reuses the same unchanged receiver,
   SQLite connection lifecycle, post-COMMIT reload, delivery, acknowledgement
   validation, ack-gated durable marking, final reload, evidence, and cleanup
   -- extending this one canonical application rather than modeling a second
   one.

   One HTTP POST carries a bencoded canonical command whose payload is an octet
   vector. The request handler applies the existing SQLite outbox transition and
   returns after COMMIT. Once the HTTP server is quiescent, the outer application
   reloads the pending row through the same still-open in-memory connection,
   gives the materialized message to an ordinary framed TCP/bencode delivery
   function, and validates the receiver's exact correlated outbox-id/attempt
   ack. Only after that validation succeeds does it call
   jolt.example.outbox.sqlite/mark-delivered! for the one outbox id, still on
   the open connection, and reload once more: the returned mark state must equal
   the final reload, the target row must be :delivered, and zero :pending rows
   may remain. The connection therefore stays open through delivery,
   acknowledgement validation, durable marking, and final reload. This proves
   post-COMMIT visibility and ack-gated durable marking, not close/reopen or
   crash durability: a crash after the remote acknowledgement but before durable
   marking can still redeliver, so exactly-once is not claimed.

   exercise-outbox-delivery-retry is the two-attempt at-least-once companion
   witness. The HTTP phase is identical, but the SQLite connection stays open
   across delivery: the application reloads the committed row, makes one
   ordinary delivery attempt, catches an ordinary transport failure, closes
   and cleans the first connection, reloads through the same still-open
   connection to prove the committed state unchanged, and redelivers the same
   row with an incremented attempt over a fresh ordinary connection. The
   receiver therefore records exactly attempts [1 2] -- duplicate delivery is
   the witnessed semantics, not an exactly-once claim. Attempt 1 never marks:
   only the validated attempt-2 acknowledgement authorizes mark-delivered!,
   followed by the same final reload checks as the one-attempt flow."
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
(def ^:private ack-withheld-type ::ack-withheld)
;; Ordinary host-backed integration crosses two servers, SQLite transactions,
;; and multiple socket exchanges under one shared budget. Keep enough wall
;; clock headroom for loaded hosted runners; exact expiry semantics use an
;; explicitly supplied 5-second virtual budget in the terminal campaign.
(def ^:private default-operation-budget-nanos 30000000000)

(defn- fail! [reason detail]
  (throw
   (ex-info
    (str "outbox-delivery fixture: " (name reason))
    {:type :jolt.sim.fixtures.outbox-delivery/invalid-flow
     :reason reason
     :detail detail})))

(defn new-operation-context
  "Creates the ordinary application's shared operation context. Its absolute
   monotonic deadline is safe to pass unchanged through every participating
   library call and is intentionally independent of jolt-sim. An optional
   boundary observer receives each stable application phase immediately before
   its clock sample. Observability, tracing, and test harnesses may use that
   semantic seam without replacing HTTP, SQLite, TCP, or application code.
   Under a simulation-enabled compiler, the ordinary jolt.host clock call is
   handled by the installed clock controller."
  ([]
   (new-operation-context default-operation-budget-nanos))
  ([budget-nanos]
   (new-operation-context budget-nanos nil))
  ([budget-nanos on-boundary]
   (when-not (or (nil? on-boundary) (fn? on-boundary))
     (fail! :invalid-boundary-observer {:value on-boundary}))
   (cond-> {:deadline-nanos (+ (host/mono-nanos) budget-nanos)}
     on-boundary (assoc :on-boundary on-boundary))))

(defn- check-operation-deadline! [operation-context phase]
  (when-let [on-boundary (:on-boundary operation-context)]
    (when-not (fn? on-boundary)
      (fail! :invalid-boundary-observer {:value on-boundary :phase phase}))
    (on-boundary phase))
  (when-let [deadline-nanos (:deadline-nanos operation-context)]
    (let [observed-nanos (host/mono-nanos)]
      (when (<= deadline-nanos observed-nanos)
        (fail! :operation-deadline-exceeded
               {:phase phase
                :deadline-nanos deadline-nanos
                :observed-nanos observed-nanos}))))
  operation-context)

(defn- connect-options [operation-context]
  (if-let [deadline-nanos (:deadline-nanos operation-context)]
    {:deadline-nanos deadline-nanos}
    {:connect-timeout-ms 5000}))

(defn- stable-error-summary [error]
  {:class (str (class error))
   :message (or (ex-message error)
                (try (host/condition-message error)
                     (catch :default _ nil)))
   :data (when-let [data (ex-data error)]
           (into {}
                 (map (fn [[k v]] [(str k) (pr-str v)]))
                 data))})

(defn- cancellation-error-summary [error]
  (let [data (ex-data error)
        err (:err data)
        operation (:teensyp.client/op data)
        kind (:teensyp.client/kind data)
        summary (stable-error-summary error)]
    (when-not (= [::client/closed :receive :closed]
                 [err operation kind])
      (fail! :unexpected-cancellation-error {:error summary}))
    (assoc summary
           :err err
           :operation operation
           :kind kind)))

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
  ([phase error]
   (phase-error phase error nil))
  ([phase error command-evidence]
   (ex-info
    (or (ex-message error) (str error))
    (cond-> (assoc (or (ex-data error) {})
                   :outbox-delivery/phase phase)
      command-evidence
      (assoc :outbox-delivery/command-evidence command-evidence))
    error)))

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

(defn- with-sqlite-connection
  "Opens one ordinary JDBC connection, runs `thunk`, and closes the connection
   without allowing a close failure to replace the application's primary
   error. Connection acquisition remains outside the captured body: if open
   itself fails, no owner exists and no close is attempted. Once acquired, the
   connection is closed exactly once through its documented map `:close`
   function. A body failure remains primary while close diagnostics are
   attached by throw-with-cleanup!; a close-only failure still fails the case."
  [spec thunk]
  (let [conn (jdbc/connection spec)
        body (try
               {:value (thunk conn)}
               (catch :default error
                 {:error error}))
        cleanup-errors
        (vec
         (keep identity
               [(cleanup-attempt :sqlite-connection-close
                                 #((:close conn)))]))]
    (throw-with-cleanup! (:error body) cleanup-errors)
    (:value body)))

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

(defn semantic-identities
  "The canonical bounded identity projection for one committed command result:
   request/transaction/outbox/delivery/attempt identities. Shared unchanged by
   the bencode and JSON HTTP command-phase seams."
  [result]
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

(defn expected-ack
  "The exact correlated acknowledgement one delivery message must receive:
   the same outbox-id and the same attempt. This is the default receiver
   reply-for for every whole-application lane; a test may instead supply a
   hostile reply to prove that validation failure cannot authorize marking."
  [message]
  {"type" "outbox_delivery_ok"
   "outbox-id" (get message "outbox-id")
   "attempt" (get message "attempt")})

(defn- receiver-reply [received reply-for]
  (fn [message]
    (when-not (and (map? message)
                   (= delivery-wire-keys (set (keys message)))
                   (= "outbox_delivery" (get message "type")))
      (fail! :invalid-delivery-wire {:value message}))
    (swap! received conj message)
    (reply-for message)))

(defn- exchange-deliveries!
  ([host port messages]
   (exchange-deliveries! host port messages nil))
  ([host port messages operation-context]
   (let [connection* (atom nil)
         body
         (try
           {:value
            (let [connection (client/connect
                              host port (connect-options operation-context))
                  _ (reset! connection* connection)
                  exchange (framed/exchange! connection messages
                                             operation-context)
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
     (:value body))))

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

(defn- validate-acks! [messages replies]
  (let [expected (mapv expected-ack messages)]
    (when-not (= expected replies)
      (fail! :ack-mismatch
             {:expected expected :actual replies}))
    replies))

(defn- deliver-messages!
  ([host port messages]
   (deliver-messages! host port messages nil))
  ([host port messages operation-context]
   (check-operation-deadline! operation-context :before-delivery)
   (let [exchange (exchange-deliveries! host port messages operation-context)]
     (validate-acks! messages (:replies exchange))
     (check-operation-deadline! operation-context :after-ack)
     exchange)))

(defn- mark-delivered-and-reload!
  "Durably marks the one acknowledged outbox row delivered through the
   ordinary adapter, then reloads through the same still-open connection.
   Fails closed unless the returned mark state equals the reload, the target
   row is :delivered, and zero :pending rows remain; the witnessed transition
   must also report :changed? true because the row was reloaded :pending
   before the ack. Returns {:marking {:row delivered-row :changed? true}
   :state final-state}, where :marking is exactly the mark step without its
   duplicate :state. This records the ack-gated durable marking only; a crash
   between the remote acknowledgement and this commit can still redeliver."
  ([conn outbox-id]
   (mark-delivered-and-reload! conn outbox-id nil))
  ([conn outbox-id operation-context]
   (check-operation-deadline! operation-context :before-mark-delivered)
   (let [mark-step (store/mark-delivered! conn outbox-id)
         ;; SQLite calls are blocking and cannot be cancelled mid-operation.
         ;; Once mark-delivered! returns across COMMIT, that durable outcome
         ;; wins even if the deadline elapsed while SQLite was running.
         final-state (store/load-state conn)
         delivered-row (:row mark-step)
         _ (when-not (true? (:changed? mark-step))
             (fail! :mark-unchanged
                    {:outbox-id outbox-id :row delivered-row}))
         _ (when-not (= (:state mark-step) final-state)
             (fail! :mark-state-mismatch
                    {:mark-state (:state mark-step)
                     :loaded-state final-state}))
         target-rows (filterv #(= outbox-id (:outbox-id %))
                              (:outbox final-state))
         _ (when-not (and (= 1 (count target-rows))
                          (= delivered-row (first target-rows))
                          (= :delivered (:status (first target-rows))))
             (fail! :marked-row-not-delivered
                    {:outbox-id outbox-id
                     :row delivered-row
                     :loaded-rows target-rows}))
         pending-rows (filterv #(= :pending (:status %))
                               (:outbox final-state))
         _ (when-not (zero? (count pending-rows))
             (fail! :pending-rows-remain
                    {:count (count pending-rows)}))]
     {:marking (dissoc mark-step :state)
      :state final-state})))

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

(defn- withhold-ack-reply
  "Builds the ordinary framed-handler reply function that decodes and records
   each delivery message, signals `decoded-promise` once attempt 1 has been
   decoded/recorded, and then withholds its acknowledgement until `ack-gate`
   is delivered -- the causal handshake that lets a separate ordinary future
   close the client after decode and before an ack is sent. Once released, the
   callback records the withheld outcome and throws a private sentinel instead
   of returning a reply, so the generic framed handler never races a native
   write against the already-closed peer."
  [received ack-outcomes decoded-promise ack-gate]
  (fn [message]
    (when-not (and (map? message)
                   (= delivery-wire-keys (set (keys message)))
                   (= "outbox_delivery" (get message "type")))
      (fail! :invalid-delivery-wire {:value message}))
    (swap! received conj message)
    (deliver decoded-promise :decoded)
    (when (= ::ack-timeout (deref ack-gate 10000 ::ack-timeout))
      (fail! :ack-release-timeout {}))
    (swap! ack-outcomes conj {:status :withheld})
    (throw
     (ex-info "outbox receiver: acknowledgement withheld after cancellation"
              {:type ack-withheld-type}))))

(defn- cancel-before-ack-delivery!
  "One ordinary framed delivery attempt whose receiver withholds its
   acknowledgement at a causal handshake. The receiver delivers
   `decoded-promise` after it decodes and records attempt 1, then derefs
   `ack-gate` to withhold its ack until released. A separate ordinary Jolt
   future waits for `decoded-promise`, then calls public
   teensyp.client/close! on the delivery connection -- the one cancellation
   owner -- and delivers `ack-gate` to release the receiver; future-cancel is
   never used and the helper always completes normally (it stores any failure
   as a bounded outcome rather than throwing). The blocked client receive in
   this thread must wake with the canonical teensyp closed/receive
   cancellation outcome.

   After the exchange wakes, a second ordinary close! from this thread proves
   idempotent ownership (the first close owned cancellation, the second does
   not). Returns

     {:status :cancelled
      :exchange-error <bounded stable summary of the cancellation error>
      :close-result   <first close result -- true, owns cancellation>
      :cleanup-close  <second close result -- false, idempotent>
      :helper         <bounded helper outcome map>}

   or throws a typed violation when the cancellation is not exercised.
   Primary/cleanup error semantics mirror attempt-delivery!: the exchange
   cancellation is the expected primary path, while any connection or helper
   cleanup failure attaches bounded diagnostics and still fails the case."
  [host port messages decoded-promise ack-gate]
  (let [connection* (atom nil)
        helper* (atom nil)
        helper-error* (atom nil)
        helper-outcome* (atom nil)
        body
        (try
          {:value
           (let [connection (client/connect host port
                                            {:connect-timeout-ms 5000})
                 _ (reset! connection* connection)
                 helper
                 (future
                   (try
                     (let [decoded (deref decoded-promise 10000 ::decode-timeout)]
                       (if (= ::decode-timeout decoded)
                         (do (deliver ack-gate true)
                             (reset! helper-outcome* {:status :decode-timeout})
                             :decode-timeout)
                         (let [first-close (client/close! connection)]
                           (deliver ack-gate true)
                           (reset! helper-outcome*
                                   {:status :closed
                                    :close-result first-close})
                           :closed)))
                     (catch :default error
                       ;; Release the receiver even on a helper failure so it
                       ;; never blocks past this future's lifetime; store the
                       ;; bounded failure rather than throwing so the helper
                       ;; completes normally without future-cancel.
                       (deliver ack-gate true)
                       (reset! helper-error* error)
                       (reset! helper-outcome*
                               {:status :helper-error
                                :error (stable-error-summary error)})
                       :helper-error)))
                 _ (reset! helper* helper)
                 exchange-outcome
                 (try
                   {:delivered (framed/exchange! connection messages)}
                   (catch :default error
                     {:exchange-error error}))
                 _ (when (:delivered exchange-outcome)
                     ;; The ack was not withheld/cancelled: release the
                     ;; receiver and fail closed rather than treat an ack'd
                     ;; exchange as the cancellation witness.
                     (deliver ack-gate true)
                     (fail! :cancel-before-ack-not-exercised
                            {:exchange (:delivered exchange-outcome)
                             :helper @helper-outcome*}))
                 exchange-error (:exchange-error exchange-outcome)
                 cancel-error (cancellation-error-summary exchange-error)
                 ;; Release the receiver if the helper has not already (the
                 ;; helper delivers ack-gate right after close!; this is the
                 ;; idempotent no-op on the happy path and the release on any
                 ;; path where the helper deferred it).
                 _ (deliver ack-gate true)
                 helper-status (deref helper 10000 ::join-timeout)
                 _ (when (= ::join-timeout helper-status)
                     (fail! :helper-join-timeout
                            {:exchange-error
                             (stable-error-summary exchange-error)
                             :helper @helper-outcome*}))
                 helper-outcome @helper-outcome*
                 _ (when-let [helper-error @helper-error*]
                     (throw helper-error))
                 cancel-close (:close-result helper-outcome)
                 _ (when-not (true? cancel-close)
                     (fail! :cancel-close-not-owned
                            {:helper helper-outcome}))
                 cleanup-close (client/close! connection)
                 _ (when-not (false? cleanup-close)
                     (fail! :cleanup-close-not-idempotent
                            {:cleanup-close cleanup-close}))]
             {:status :cancelled
              :exchange-error cancel-error
              :close-result cancel-close
              :cleanup-close cleanup-close
              :helper helper-outcome})}
          (catch :default error
            {:error error}))
        cleanup-errors
        (vec
         (keep identity
               [(cleanup-attempt :release-ack-gate #(deliver ack-gate true))
                (cleanup-attempt :release-decoded
                                 #(deliver decoded-promise :cleanup))
                (when-let [helper @helper*]
                  (cleanup-attempt :helper-join
                                   #(let [status
                                          (deref helper 10000 ::join-timeout)]
                                      (when (= ::join-timeout status)
                                        (fail! :helper-cleanup-timeout {}))
                                      status)))
                (when-let [connection @connection*]
                  (cleanup-attempt :delivery-connection-close
                                   #(client/close! connection)))]))]
    (throw-with-cleanup! (:error body) cleanup-errors)
    (:value body)))

(defn- command-handler
  ([conn command-evidence]
   (command-handler conn command-evidence nil))
  ([conn command-evidence operation-context]
   (fn [request]
     (when-not (= [:post "/commands"]
                  [(:request-method request) (:uri request)])
       (fail! :unsupported-http-route
              {:request-method (:request-method request)
               :uri (:uri request)}))
     (check-operation-deadline! operation-context :before-command)
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
       ;; Publish the bounded durable evidence before observing the clock
       ;; again. If the deadline elapsed inside non-cancellable SQLite, the
       ;; caller can distinguish "committed, response withheld" from an
       ;; unstarted or uncertain command and must not begin delivery.
       (reset! command-evidence evidence)
       (check-operation-deadline! operation-context :after-command-commit)
       {:status 200
        :headers {"Content-Type" bencode-content-type}
        :body (bencode/encode (command-response-wire result))}))))

;; ---- HTTP command-phase seam ------------------------------------------------
;;
;; The whole-application flow below is fixed except for its HTTP command phase.
;; A closed seam map replaces exactly four things; everything else -- the
;; receiver, the SQLite connection lifecycle, the post-COMMIT pending reload,
;; delivery, acknowledgement validation, ack-gated durable marking, the final
;; reload, evidence, and cleanup -- is shared unchanged by every lane.

(def ^:private http-seam-keys
  #{:handler-for :request-bytes-for :expected-status :decode-response})

(defn- check-http-seam!
  "Fail-closed validation of one closed HTTP command-phase seam map before any
   receiver, connection, or server exists. :handler-for is
   (fn [conn command command-evidence* operation-context] -> synchronous
   jolt-http handler); :request-bytes-for is (fn [command host port] -> raw
   request byte array); :expected-status is the exact HTTP status the command
   phase must produce; :decode-response is (fn [^bytes response-body] ->
   decoded wire value). Returns the validated seam unchanged."
  [http-seam]
  (when-not (and (map? http-seam)
                 (= http-seam-keys (set (keys http-seam))))
    (fail! :invalid-http-seam {:value http-seam}))
  (when-not (fn? (:handler-for http-seam))
    (fail! :invalid-http-seam-handler {:value (:handler-for http-seam)}))
  (when-not (fn? (:request-bytes-for http-seam))
    (fail! :invalid-http-seam-request-bytes
           {:value (:request-bytes-for http-seam)}))
  (when-not (integer? (:expected-status http-seam))
    (fail! :invalid-http-seam-expected-status
           {:value (:expected-status http-seam)}))
  (when-not (fn? (:decode-response http-seam))
    (fail! :invalid-http-seam-decode-response
           {:value (:decode-response http-seam)}))
  http-seam)

(defn- check-command-evidence!
  "Validates the HTTP seam's bounded evidence against the ordinary durable
   post-COMMIT reload.  A seam may project a production handler's response,
   but it may not invent a command, result, emission, or identity that storage
   does not corroborate.  :committed-state is optional because some public
   handlers do not expose their internal transition step; when present (the
   original bencode lane), it must equal the reload as an additional witness."
  [evidence pending-state]
  (when-not (and (map? evidence)
                 (every? #(contains? evidence %) [:identities :command]))
    (fail! :invalid-command-evidence {:value evidence}))
  (let [{:keys [value result emitted] :as command-step} (:command evidence)
        request-id (:request-id result)
        outbox-id (:outbox-id result)
        durable-request (get-in pending-state [:request-log request-id])
        durable-row (first (filter #(= outbox-id (:outbox-id %))
                                   (:outbox pending-state)))
        expected-row {:outbox-id outbox-id
                      :request-id request-id
                      :entity-id (:entity-id result)
                      :version (:version result)
                      :payload (:payload value)
                      :status :pending}
        expected-state
        {:entities {(:entity-id result)
                    {:version (:version result)
                     :payload (:payload value)}}
         :request-log {request-id {:command value :result result}}
         :next-outbox-id (inc outbox-id)
         :outbox [expected-row]}]
    (when-not (and (map? command-step)
                   (= #{:value :result :emitted} (set (keys command-step)))
                   (map? value)
                   (= command-keys (set (keys value)))
                   (map? result)
                   (= #{:status :request-id :entity-id :version :outbox-id}
                      (set (keys result)))
                   (= :committed (:status result))
                   (= (:request-id value) request-id)
                   (= (:entity-id value) (:entity-id result))
                   (vector? emitted))
      (fail! :invalid-command-evidence {:value evidence}))
    (when-not (= (semantic-identities result) (:identities evidence))
      (fail! :command-identity-mismatch
             {:evidence (:identities evidence)
              :durable (semantic-identities result)}))
    (when-not (= {:command value :result result} durable-request)
      (fail! :command-result-mismatch
             {:evidence {:command value :result result}
              :durable durable-request}))
    (when-not (= expected-row durable-row)
      (fail! :command-outbox-mismatch
             {:evidence expected-row :durable durable-row}))
    ;; This flow initializes an empty schema and applies exactly one fresh
    ;; command, so its production transition must report exactly one emission;
    ;; an empty or duplicated projection must not pass by set membership.
    (when-not (= [expected-row] emitted)
      (fail! :command-emission-mismatch
             {:evidence emitted :durable (:outbox pending-state)}))
    ;; The shared flow initializes an empty schema and authorizes delivery for
    ;; exactly one accepted command. Exact state equality rejects an extra
    ;; request, entity, outbox row, or next-id advance even if a callback tried
    ;; to hide it behind otherwise valid evidence for the first command.
    (when-not (= expected-state pending-state)
      (fail! :unexpected-command-phase-state
             {:expected expected-state :durable pending-state}))
    (when (contains? evidence :committed-state)
      (when-not (= (:committed-state evidence) pending-state)
        (fail! :committed-state-mismatch
               {:step-state (:committed-state evidence)
                :loaded-state pending-state}))))
  evidence)

(def ^:private bencode-http-seam
  "The original HTTP command phase: POST /commands carrying one bencoded
   canonical command, the instrumented bencode command-handler, an exact 200,
   and the exact bencode response decode."
  {:handler-for
   (fn [conn _command command-evidence* operation-context]
     (command-handler conn command-evidence* operation-context))
   :request-bytes-for post-request-bytes
   :expected-status 200
   :decode-response
   (fn [^bytes body] (decode-bencode-exact :http-response body))})

(defn- check-command-phase-outcome!
  "Fail-closed validation for the bounded command-phase callback used by the
   shared delivery application. The callback must return the accepted command
   evidence and an outer result projection containing :http. A multi-request
   phase may also return its final durable :expected-pending-state. The shared
   flow still performs its own canonical load and requires exact equality, so
   a callback cannot bypass storage corroboration."
  [outcome]
  (when-not (and (map? outcome)
                 (contains? outcome :command-evidence)
                 (contains? outcome :result)
                 (every? #{:command-evidence :expected-pending-state :result}
                         (keys outcome))
                 (map? (:result outcome))
                 (contains? (:result outcome) :http)
                 (not (contains? (:result outcome) :application))
                 (not (contains? (:result outcome) :receiver)))
    (fail! :invalid-command-phase-outcome {:value outcome}))
  outcome)

(defn- run-single-http-command-phase
  "Runs the historical one-request HTTP seam and returns the closed command
   phase outcome consumed by exercise-outbox-delivery-with-command-phase."
  [http-seam command conn operation-context]
  (let [command-evidence* (atom nil)
        http-outcome
        (try
          {:value
           (http-fixture/run-request-cycle
            ((:handler-for http-seam)
             conn command command-evidence* operation-context)
            (fn [host port]
              ((:request-bytes-for http-seam) command host port))
            operation-context)}
          (catch :default error
            {:error error}))
        command-evidence @command-evidence*]
    (when-let [error (:error http-outcome)]
      (throw (phase-error :http error command-evidence)))
    (let [http-cycle (:value http-outcome)
          parsed (:parsed http-cycle)]
      (when-not (= (:expected-status http-seam) (:status parsed))
        (fail! :http-command-failed
               {:status (:status parsed)
                :expected-status (:expected-status http-seam)
                :command-evidence command-evidence
                :server-errors (:server-errors http-cycle)}))
      (when-not command-evidence
        (fail! :missing-application-evidence {}))
      {:command-evidence command-evidence
       :result
       {:http {:status (:status parsed)
               :content-type (get (:headers parsed) "content-type")
               :content-length (get (:headers parsed) "content-length")
               :response ((:decode-response http-seam) (:body parsed))
               :server-errors (:server-errors http-cycle)
               :close-results (:close-results http-cycle)}}})))

(defn exercise-outbox-delivery-with-command-phase
  "Runs the shared ordinary SQLite -> pending reload -> framed TCP/bencode
   delivery -> ack-gated durable marking application around `command-phase`.

   command-phase is called exactly once as (fn [conn operation-context]) while
   the schema-initialized SQLite connection is open. It owns one or more real
   jolt-http request cycles and returns the closed shape validated by
   check-command-phase-outcome!. Its :command-evidence names the one accepted
   command whose pending row may authorize delivery. Its :result is merged
   into the returned top-level evidence and must contain :http. A phase that
   already loaded its final durable state may supply
   :expected-pending-state; this function still performs the historical
   explicit post-COMMIT reload and compares it exactly.

   This is a narrow fixture-composition seam, not an alternate application:
   schema setup, connection lifetime, receiver, pending-state corroboration,
   TCP delivery, acknowledgement validation, marking, final reload, and
   cleanup remain here and are identical for every command phase."
  ([command-phase]
   (exercise-outbox-delivery-with-command-phase command-phase expected-ack nil))
  ([command-phase reply-for]
   (exercise-outbox-delivery-with-command-phase command-phase reply-for nil))
  ([command-phase reply-for supplied-operation-context]
   (when-not (fn? command-phase)
     (fail! :invalid-command-phase {:value command-phase}))
   (when-not (fn? reply-for)
     (fail! :invalid-reply-function {:value reply-for}))
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler
                    (receiver-reply received reply-for))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         body
         (try
           {:value
            (with-sqlite-connection
             "sqlite::memory:"
             (fn [conn]
               (store/init-schema! conn)
               (let [operation-context
                     (or supplied-operation-context (new-operation-context))
                     _ (check-operation-deadline! operation-context
                                                  :before-http)
                     phase-outcome
                     (try
                       {:value (check-command-phase-outcome!
                                (command-phase conn operation-context))}
                       (catch :default error
                         {:error error}))
                     command-evidence
                     (get-in phase-outcome [:value :command-evidence])
                     _ (when-let [error (:error phase-outcome)]
                         (if (contains? (or (ex-data error) {})
                                        :outbox-delivery/phase)
                           (throw error)
                           (throw (phase-error :http error command-evidence))))
                     phase (:value phase-outcome)
                     _ (check-operation-deadline! operation-context
                                                  :before-pending-reload)
                     pending (load-pending-delivery conn)
                     _ (check-operation-deadline! operation-context
                                                  :after-pending-reload)
                     pending-state (:state pending)
                     _ (when (and (contains? phase :expected-pending-state)
                                  (not= (:expected-pending-state phase)
                                        pending-state))
                         (fail! :command-phase-pending-state-mismatch
                                {:phase (:expected-pending-state phase)
                                 :loaded pending-state}))
                     _ (check-command-evidence! command-evidence pending-state)
                     delivery (deliver-messages! "127.0.0.1" (:port receiver)
                                                 (:messages pending)
                                                 operation-context)
                     outbox-id (:outbox-id (first (:outbox pending-state)))
                     marked (mark-delivered-and-reload!
                             conn outbox-id operation-context)
                     app (-> command-evidence
                             (dissoc :committed-state)
                             (assoc :pending-state pending-state
                                    :store-state (:state marked)
                                    :marking (:marking marked)
                                    :delivery delivery))]
                 (assoc (:result phase) :application app))))}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :receiver-stop #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))

(defn exercise-outbox-delivery-via
  "Runs one ordinary HTTP -> committed SQLite outbox -> framed TCP/bencode
  delivery/ack -> ack-gated durable delivery-marking flow with its HTTP
  command phase carried by `http-seam` (see check-http-seam! for the closed
  contract). The same in-memory SQLite connection stays open through the
  post-COMMIT reload, downstream TCP delivery, acknowledgement validation,
  mark-delivered!, and the final reload; this is still not a close/reopen or
  crash-durability witness. Returns canonical immutable evidence with no
  native handles, pointers, byte arrays, mutable values, controller objects,
  or ephemeral ports. The optional command arity lets generated hermetic plans
  exercise payload variants; the real/sim parity witness still uses
  default-command. The reply-for argument is a bounded negative-control seam:
  it receives the validated delivery message and returns the receiver reply.
  Ordinary callers use the exact correlated acknowledgement; tests may return
  a hostile reply to prove that validation failure cannot authorize marking.
  The four-argument form accepts an ordinary operation context. Otherwise the
  application creates one after schema initialization and carries its single
  absolute deadline through the outer HTTP, reload, delivery,
  acknowledgement, and pre-mark boundaries. A handler seam receives that
  context and owns any finer command-transaction checks: the original bencode
  seam checks immediately before the command and after COMMIT, while a seam
  that ignores it makes no such internal-boundary claim. Once
  mark-delivered! returns across COMMIT, that durable delivered outcome wins
  even if the deadline elapsed inside SQLite. The bencode payload is an octet
  vector; SQLite stores the same semantics as a BLOB, but this witness does
  not claim bencode binary-string wire parity."
  ([http-seam command]
   (exercise-outbox-delivery-via http-seam command expected-ack))
  ([http-seam command reply-for]
   (exercise-outbox-delivery-via http-seam command reply-for nil))
  ([http-seam command reply-for supplied-operation-context]
   (check-http-seam! http-seam)
   (exercise-outbox-delivery-with-command-phase
    (fn [conn operation-context]
      (run-single-http-command-phase
       http-seam command conn operation-context))
    reply-for
    supplied-operation-context)))

(defn exercise-outbox-delivery
  "Runs the ordinary bencode HTTP command phase of
   exercise-outbox-delivery-via: one bencoded HTTP command -> committed SQLite
   outbox -> framed TCP/bencode delivery/ack -> ack-gated durable
   delivery-marking flow. This is exercise-outbox-delivery-via with
   bencode-http-seam and nothing else; every behavior, witness boundary, and
   nonclaim documented there applies unchanged."
  ([]
   (exercise-outbox-delivery default-command))
  ([command]
   (exercise-outbox-delivery command expected-ack))
  ([command reply-for]
   (exercise-outbox-delivery command reply-for nil))
  ([command reply-for supplied-operation-context]
   (exercise-outbox-delivery-via
    bencode-http-seam command reply-for supplied-operation-context)))

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
   fails typed and bounded when it does not deliver the correlated ack.
   Attempt 1 never marks; only the validated attempt-2 acknowledgement calls
   mark-delivered! on the same still-open connection, after which the final
   reload must equal the returned mark state with the row :delivered and zero
   :pending rows. This is still not an exactly-once claim: a crash after the
   remote acknowledgement but before durable marking can still redeliver.

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

   The optional second argument is a trusted test/embedding observer invoked
   synchronously after the command transaction has committed and the pending
   row has been reloaded and validated, but before delivery attempt 1. The
   ordinary one-argument path supplies nil and is unchanged. The observer may
   block to expose that organic application boundary to an external harness;
   its exception remains the primary application failure and normal fixture
   cleanup still runs.

   Returns the same canonical immutable evidence shape as
   exercise-outbox-delivery, with :application carrying :retry instead of
   :delivery:

     {:attempt-1 {:error <bounded summary> :cleanup-errors [...]}
      :state-unchanged-after-failure? true
      :delivery <attempt-2 exchange, the non-retry :delivery shape>}"
  ([]
   (exercise-outbox-delivery-retry default-command nil))
  ([command]
   (exercise-outbox-delivery-retry command nil))
  ([command activity-observer]
   (when-not (or (nil? activity-observer) (fn? activity-observer))
     (fail! :invalid-activity-observer
            {:value-class (str (class activity-observer))}))
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler
                    (receiver-reply received expected-ack))
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
            (with-sqlite-connection
             "sqlite::memory:"
             (fn [conn]
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
                    ;; The explicit post-COMMIT/pre-delivery reload. The
                    ;; emitted command row remains :pending evidence on this
                    ;; side of the delivery boundary.
                    pending (load-pending-delivery conn)
                    pending-state (:state pending)
                    _ (when-not (= (:committed-state command-evidence)
                                   pending-state)
                        (fail! :committed-state-mismatch
                               {:step-state
                                (:committed-state command-evidence)
                                :loaded-state pending-state}))
                    ;; A trusted, optional observation seam at a real
                    ;; application boundary: the HTTP command response has
                    ;; completed, COMMIT has succeeded, and an exact reload
                    ;; has proved the durable row pending. No simulator type,
                    ;; schedule, or controller enters the ordinary fixture.
                    _ (when activity-observer
                        (activity-observer
                         {:phase :pending-delivery
                          :request-id (:request-id command)
                          :outbox-id
                          (:outbox-id (first (:outbox pending-state)))
                          :status (:status (first (:outbox pending-state)))
                          :attempt 1}))
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
                    ;; connection must observe the exact committed state:
                    ;; attempt 1 failed before any marking, so the row is
                    ;; still :pending here.
                    reloaded (load-pending-delivery conn 2)
                    _ (when-not (= pending-state (:state reloaded))
                        (fail! :post-failure-state-changed
                               {:committed-state pending-state
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
                    _ (validate-acks! (:messages reloaded)
                                      (:replies delivery))
                    ;; Only after the exact correlated attempt-2 ack: durable
                    ;; marking for the one outbox id through the same
                    ;; still-open connection, then the final reload checks.
                    outbox-id (:outbox-id (first (:outbox pending-state)))
                    marked (mark-delivered-and-reload! conn outbox-id)
                    app (-> command-evidence
                            (dissoc :committed-state)
                            (assoc :pending-state pending-state
                                   :store-state (:state marked)
                                   :marking (:marking marked)
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
                        :close-results (:close-results http-cycle)}})))}
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

(defn exercise-outbox-delivery-cancel-before-ack
  "Runs the ordinary HTTP -> SQLite outbox -> framed TCP path, but cancels the
   client after the receiver decodes attempt 1 and before it acknowledges. The
   blocked exchange must report canonical closed/receive cancellation; the
   same still-open SQLite connection must then reload the exact committed
   pending state. No mark-delivered! call or delivered claim exists on this
   path.

   A normal future owns the first close and an ordinary promise handshake
   withholds the ack. Cleanup releases both handshakes, joins the future,
   closes idempotently, and quiesces the receiver. Returned evidence is
   canonical immutable data under :application, :http, and :receiver; it
   contains no futures, promises, atoms, native handles, or ephemeral ports.

   This is unchanged ordinary application/library code with no simulator
   dependency. It proves at-least-once retryability after cancellation, not
   exactly-once delivery or every possible ack/close race."
  ([]
   (exercise-outbox-delivery-cancel-before-ack default-command))
  ([command]
   (let [receiver-errors (atom [])
         received (atom [])
         ack-outcomes (atom [])
         ;; Causal handshake shared between the receiver reply and the
         ;; closing helper future. Both are ordinary Clojure promises; neither
         ;; appears in the returned evidence.
         decoded-promise (promise)
         ack-gate (promise)
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler
                    (withhold-ack-reply received ack-outcomes
                                        decoded-promise ack-gate))
          :error-logger
          #(when-not (= ack-withheld-type (:type (ex-data %)))
             (swap! receiver-errors conj (stable-error-summary %))))
         command-evidence* (atom nil)
         body
         (try
           {:value
            ;; The SQLite connection deliberately stays OPEN across the
            ;; delivery attempt and post-cancellation reload: the pre-delivery
            ;; reload and the post-cancellation reload both flow through the
            ;; same ordinary connection, proving the committed state without
            ;; any close/reopen or crash-durability claim.
            (with-sqlite-connection
             "sqlite::memory:"
             (fn [conn]
               (store/init-schema! conn)
               (let [http-cycle
                     (http-fixture/run-request-cycle
                      (command-handler conn command-evidence*)
                      (fn [host port]
                        (post-request-bytes command host port)))
                     command-evidence @command-evidence*
                     _ (when-not command-evidence
                         (fail! :missing-application-evidence {}))
                     ;; The explicit post-COMMIT/pre-delivery reload. The
                     ;; emitted command row remains :pending evidence on this
                     ;; side of the delivery boundary.
                     pending (load-pending-delivery conn)
                     pending-state (:state pending)
                     _ (when-not (= (:committed-state command-evidence)
                                    pending-state)
                         (fail! :committed-state-mismatch
                                {:step-state
                                 (:committed-state command-evidence)
                                 :loaded-state pending-state}))
                     ;; Delivery attempt: connect, the closing helper future
                     ;; calls teensyp.client/close! after the receiver decodes
                     ;; attempt 1 and before its withheld ack, and the blocked
                     ;; client exchange wakes with the canonical closed/receive
                     ;; cancellation outcome.
                     cancel (cancel-before-ack-delivery!
                             "127.0.0.1" (:port receiver)
                             (:messages pending) decoded-promise ack-gate)
                     _ (when-not (= :cancelled (:status cancel))
                         (fail! :cancel-before-ack-not-exercised
                                {:cancel (dissoc cancel :status)}))
                     ;; The post-cancellation reload through the same
                     ;; still-open connection must observe the exact committed
                     ;; state: no ack was validated, so no marking ran and the
                     ;; row is still :pending here.
                     reloaded (load-pending-delivery conn)
                     _ (when-not (= pending-state (:state reloaded))
                         (fail! :post-cancel-state-changed
                                {:committed-state pending-state
                                 :loaded-state (:state reloaded)}))
                     app (-> command-evidence
                             (dissoc :committed-state)
                             (assoc :pending-state pending-state
                                    :store-state (:state reloaded)
                                    :cancel (dissoc cancel :status)))
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
                         :close-results (:close-results http-cycle)}})))}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :release-ack-gate #(deliver ack-gate true))
                 (cleanup-attempt :release-decoded
                                  #(deliver decoded-promise :cleanup))
                 (cleanup-attempt :receiver-stop
                                  #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the receiver before its final error snapshot.
     (assoc (:value body)
            :receiver {:requests @received
                       :ack-outcomes @ack-outcomes
                       :server-errors @receiver-errors}))))

(defn exercise-outbox-delivery-reopen
  "Runs the ordinary HTTP -> committed SQLite outbox flow on one connection,
   then closes that connection, reopens the same ordinary JDBC SQLite spec on
   a fresh connection, and performs the post-COMMIT reload, framed TCP/bencode
   delivery/ack, ack-gated durable marking, and final reload entirely on the
   reopened connection. This witnesses that the committed pending row survives
   a clean, sequential, single-owner close and reopen of an ordinary
   file-backed SQLite database: the reload on the reopened connection must
   equal the first connection's committed state before any delivery begins.

   Unlike the in-memory exercise-outbox-delivery and -retry witnesses, this
   function takes an ordinary JDBC SQLite spec (a file-backed 'sqlite:' spec,
   not the ':memory:' sentinel) because the committed image must outlive one
   connection closing. The receiver is started outermost. Connection 0
   initializes the schema and runs the existing HTTP command through COMMIT
   and HTTP-server quiescence, then closes; only after that close succeeds --
   with-sqlite-connection closes fail-closed and returns only after a
   successful close -- does connection 1 open the same spec, reload the
   pending row, deliver it over the existing TCP/bencode delivery path with
   exact correlated-ack validation, run the guarded mark-delivered!, perform
   the final reload, and close.

   This namespace has no simulator dependency: it uses public jolt-http,
   jdbc.core, teensyp, jolt.bytes, and jolt.bencode, plus the existing
   ordinary HTTP and framed-TCP fixture seams. It does not claim crash
   durability, power-loss safety, multi-connection concurrency, file locking,
   or exactly-once delivery: a crash after the remote acknowledgement but
   before durable marking can still redeliver. The close/reopen here is a
   clean, sequential, single-owner transition -- two opens of one filename,
   never two live owners at once.

   Returns the same canonical immutable evidence shape as
   exercise-outbox-delivery ({:application :http :receiver}) with no native
   handles, pointers, byte arrays, mutable values, controller objects,
   ephemeral ports, or file/image identities. The spec string and any native
   file path or simulator image identity are deliberately not part of the
   application evidence so real and hermetic lanes over different spec strings
   still compare equal."
  ([spec]
   (exercise-outbox-delivery-reopen spec default-command))
  ([spec command]
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler
                    (receiver-reply received expected-ack))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         command-evidence* (atom nil)
         body
         (try
           {:value
            (let [;; Connection 0: schema + HTTP command through COMMIT, then
                  ;; close. with-sqlite-connection closes the connection
                  ;; (failing closed on any close error) before its value
                  ;; returns, so the reopen below begins only after a
                  ;; successful close. No reload, delivery, or marking runs
                  ;; on this connection.
                  first-cycle
                  (with-sqlite-connection
                   spec
                   (fn [conn]
                     (store/init-schema! conn)
                     (let [http-cycle
                           (http-fixture/run-request-cycle
                            (command-handler conn command-evidence*)
                            (fn [host port]
                              (post-request-bytes command host port)))
                           command-evidence @command-evidence*
                           _ (when-not command-evidence
                               (fail! :missing-application-evidence {}))]
                       {:command-evidence command-evidence
                        :http-cycle http-cycle})))
                  command-evidence (:command-evidence first-cycle)
                  http-cycle (:http-cycle first-cycle)
                  ;; Connection 1: reopen the same spec. The committed
                  ;; pending row must be visible here exactly as connection 0
                  ;; left it, proving the clean close/reopen preserved the
                  ;; durable image before any delivery is attempted.
                  reopened
                  (with-sqlite-connection
                   spec
                   (fn [conn]
                     (let [pending (load-pending-delivery conn)
                           pending-state (:state pending)
                           _ (when-not (= (:committed-state command-evidence)
                                          pending-state)
                               (fail! :committed-state-mismatch
                                      {:step-state
                                       (:committed-state command-evidence)
                                       :loaded-state pending-state}))
                           delivery (deliver-messages! "127.0.0.1"
                                                       (:port receiver)
                                                       (:messages pending))
                           outbox-id (:outbox-id (first (:outbox pending-state)))
                           marked (mark-delivered-and-reload! conn outbox-id)
                           app (-> command-evidence
                                   (dissoc :committed-state)
                                   (assoc :pending-state pending-state
                                          :store-state (:state marked)
                                          :marking (:marking marked)
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
                               :close-results (:close-results http-cycle)}})))]
              reopened)}
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

(defn exercise-outbox-commit-before-close
  "Runs the ordinary bencoded HTTP command through the existing
  command-handler/post-request path on one ordinary JDBC SQLite
  connection to `spec`, requires the command evidence to exist (the
  handler returned after COMMIT and the HTTP server quiesced), builds a
  closed immutable evidence value carrying the ordinary application
  command semantics and the decoded HTTP response, and then invokes
  `after-commit!` exactly once INSIDE the connection callback -- after
  COMMIT and HTTP-server quiescence but BEFORE with-sqlite-connection
  gets a chance to close the connection.

  The intended producer callback writes a checkpoint and then calls
  System/exit, so the OS kills the process while this ordinary
  connection is still live: this is the post-COMMIT crash seam, not a
  close/reopen, retry, or exactly-once witness.

  If `after-commit!` returns, the same evidence is returned and the
  connection closes normally. If `after-commit!` throws, the throw is
  preserved as the primary error through with-sqlite-connection's
  existing cleanup logic.

  Returns a closed immutable evidence value with no DB spec/path,
  handle, pointer, byte array, atom, server object, port,
  simulator/controller value, or function:

    {:application {:identities ...
                   :command {:value ... :result ... :emitted ...}
                   :committed-state ...}
     :http {:status ... :content-type ... :content-length ...
            :response <decoded bencode> :server-errors ...
            :close-results ...}}

  This namespace has no simulator dependency."
  ([spec after-commit!]
   (exercise-outbox-commit-before-close spec default-command after-commit!))
  ([spec command after-commit!]
   (when-not (fn? after-commit!)
     (fail! :invalid-after-commit-callback {:value after-commit!}))
   (let [command-evidence* (atom nil)
         body
         (try
           {:value
            (with-sqlite-connection
             spec
             (fn [conn]
               (store/init-schema! conn)
               (let [http-cycle
                     (http-fixture/run-request-cycle
                      (command-handler conn command-evidence*)
                      (fn [host port]
                        (post-request-bytes command host port)))
                     command-evidence @command-evidence*
                     _ (when-not command-evidence
                         (fail! :missing-application-evidence {}))
                     parsed (:parsed http-cycle)
                     response
                     (decode-bencode-exact :http-response (:body parsed))
                     evidence
                     {:application command-evidence
                      :http {:status (:status parsed)
                             :content-type
                             (get (:headers parsed) "content-type")
                             :content-length
                             (get (:headers parsed) "content-length")
                             :response response
                             :server-errors (:server-errors http-cycle)
                             :close-results (:close-results http-cycle)}}]
                 ;; after-commit! runs INSIDE the connection callback,
                 ;; after COMMIT and HTTP-server quiescence but BEFORE
                 ;; with-sqlite-connection closes the connection. Its
                 ;; return value is discarded; if it throws, the throw
                 ;; becomes the primary error through the existing
                 ;; cleanup logic. The intended callback calls
                 ;; System/exit, so the OS terminates the process here
                 ;; while the ordinary connection is still open.
                 (after-commit! evidence)
                 evidence)))}
           (catch :default error
             {:error error}))]
     (throw-with-cleanup! (:error body) [])
     (:value body))))

(defn exercise-outbox-recovery
  "Starts the existing ordinary framed TCP/bencode receiver using the same
  exact correlated ack logic and cleanup behavior, opens a fresh ordinary
  JDBC SQLite connection to `spec`, reloads the pending committed outbox
  state BEFORE any TCP delivery send, delivers the materialized pending
  row through the existing ordinary deliver-messages! path, validates the
  correlated ack, marks exactly that outbox row delivered using the
  existing mark-delivered-and-reload!, and closes.

  This is the recovery companion to
  exercise-outbox-commit-before-close: it assumes the schema and one
  committed pending row already exist on `spec` (a file-backed 'sqlite:'
  spec whose image survived a producer process exit). It performs no
  schema initialization and no HTTP phase. It is still not an
  exactly-once claim: a crash after the remote acknowledgement but before
  durable marking can still redeliver.

  Returns a closed immutable evidence shape with no path/spec/native or
  mutable values:

    {:application {:pending-state ... :store-state ... :marking ...
                   :delivery ...}
     :receiver {:requests ... :server-errors ...}}

  Reuses existing private helpers and primary/cleanup exception behavior.
  This namespace has no simulator dependency."
  ([spec]
   (let [receiver-errors (atom [])
         received (atom [])
         receiver
         (tcp/run-server
          :port 0
          :reuse-address? true
          :handler (framed/framed-handler
                    (receiver-reply received expected-ack))
          :error-logger
          #(swap! receiver-errors conj (stable-error-summary %)))
         body
         (try
           {:value
            (with-sqlite-connection
             spec
             (fn [conn]
               ;; Reload the committed pending state BEFORE any delivery.
               ;; The schema and committed row must already exist on spec
               ;; (left by a prior producer phase); no init-schema! runs.
               (let [pending (load-pending-delivery conn)
                     pending-state (:state pending)
                     delivery (deliver-messages! "127.0.0.1"
                                                 (:port receiver)
                                                 (:messages pending))
                     outbox-id (:outbox-id (first (:outbox pending-state)))
                     marked (mark-delivered-and-reload! conn outbox-id)]
                 {:application {:pending-state pending-state
                                :store-state (:state marked)
                                :marking (:marking marked)
                                :delivery delivery}})))}
           (catch :default error
             {:error error}))
         cleanup-errors
         (vec
          (keep identity
                [(cleanup-attempt :receiver-stop
                                  #(tcp/stop-server receiver))]))]
     (throw-with-cleanup! (:error body) cleanup-errors)
     ;; stop-server quiesces the receiver before its final error snapshot.
     (assoc (:value body)
            :receiver {:requests @received
                       :server-errors @receiver-errors}))))
