(ns jolt.maelstrom.node
  "A transport-agnostic Maelstrom node boundary.

  A node owns exactly the mutable state a Maelstrom node needs: the one
  node_id and node_ids learned from init, and a source-local monotonically
  increasing msg_id counter for outbound messages. It answers init itself,
  dispatches every other body type to a registered handler, and builds every
  reply envelope (src, dest, body type, msg_id, in_reply_to) uniformly.

  A node knows nothing about JSON or about how envelopes arrive or leave --
  callers inject a send! function and feed envelopes to handle! however they
  like. The exact same registered handlers can run this way over real
  Maelstrom JSON-lines framing or an in-process simulated transport.

  Envelopes and bodies use Maelstrom's own wire field names (:src, :dest,
  :body, :type, :msg_id, :in_reply_to, :node_id, :node_ids) so no translation
  layer sits between this namespace and either transport.

  Malformed envelopes and duplicate or conflicting init both fail closed with
  a typed ex-info rather than being guessed at or silently ignored. A
  request body type with no registered handler gets the official
  not_supported error reply when the request carried a msg_id to correlate
  it to; a handler's own body-content validation (e.g. a required key) is
  each handler's responsibility.

  After init, workloads send their own requests with the public send!: it
  validates the destination and body, fills in the source-local msg_id, and
  hands the finished envelope to the same injected transport send!.

  Outbound requests remain pending until one response with the same
  :in_reply_to arrives from the exact destination. Unknown and wrong-source
  responses fail closed; a bounded same-source duplicate-response tombstone
  makes exact request retries idempotent without invoking observers twice.
  create-node takes an optional :on-reply observer, called at most once for
  each correlated request with (node envelope request-context); without one,
  correlated responses are simply consumed. The optional request context is
  registered before transport emission. retry! resends the exact pending
  envelope and never allocates a second msg_id.")

(def ^:private completed-response-window 1024)

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- valid-envelope?
  [envelope]
  (and (map? envelope)
       (string? (:src envelope))
       (string? (:dest envelope))
       (map? (:body envelope))
       (string? (:type (:body envelope)))
       (or (not (contains? (:body envelope) :msg_id))
           (and (integer? (:msg_id (:body envelope)))
                (not (neg? (:msg_id (:body envelope))))))
       (or (not (contains? (:body envelope) :in_reply_to))
           (and (integer? (:in_reply_to (:body envelope)))
                (not (neg? (:in_reply_to (:body envelope))))))))

(defn- valid-handlers?
  [handlers]
  (and (map? handlers)
       (not (contains? handlers "init"))
       (every? (fn [[type handler]]
                 (and (string? type) (fn? handler)))
               handlers)))

(defn create-node
  "Creates a new node boundary.

  handlers is a map from request body type string to a handler fn of (node
  request-envelope) -> reply body map. A handler's returned body must
  include :type; :msg_id and :in_reply_to are filled in by the node.

  send! is called with each outbound envelope (replies and requests).

  on-reply is optional: nil or a fn of
  (node response-envelope request-context), invoked at most once for a
  correlated pending request. Responses are never replied to, whether or not
  an observer is configured."
  [{:keys [handlers send! on-reply]}]
  (when-not (valid-handlers? handlers)
    (fail! ::invalid-config
           "handlers must map string message types to functions and must not replace init"
           {:handlers handlers}))
  (when-not (fn? send!)
    (fail! ::invalid-config "send! must be a function" {:send! send!}))
  (when-not (or (nil? on-reply) (fn? on-reply))
    (fail! ::invalid-config
           "on-reply must be nil or a function"
           {:on-reply on-reply}))
  {::handlers handlers
   ::send! send!
   ::on-reply on-reply
   ::state (atom {:node_id nil
                  :node_ids nil
                  :next-msg-id 1
                  :pending-requests {}
                  :completed-requests {}
                  :completed-order []})})

(defn node-id
  "This node's node_id, or nil before init."
  [node]
  (:node_id @(::state node)))

(defn node-ids
  "This node's node_ids, or nil before init."
  [node]
  (:node_ids @(::state node)))

(defn- next-msg-id!
  [node]
  (dec (:next-msg-id (swap! (::state node) update :next-msg-id inc))))

(defn- emit-envelope!
  "Hands one finished envelope to the injected transport send! and returns
  it. The sole place outbound envelopes leave the node."
  [node envelope]
  ((::send! node) envelope)
  envelope)

(defn- emit-request!
  [node envelope]
  (try
    (emit-envelope! node envelope)
    (catch :default error
      (throw
       (ex-info "outbound request transport send failed"
                {:type ::send-failed :request-envelope envelope}
                error)))))

(defn- send-reply!
  [node dest body request-msg-id]
  (when-not (and (map? body) (string? (:type body)))
    (fail! ::invalid-reply
           "reply body must be a map with a string :type"
           {:body body}))
  (when (or (contains? body :msg_id) (contains? body :in_reply_to))
    (fail! ::invalid-reply
           "reply handlers must not supply reserved correlation fields"
           {:body body}))
  (emit-envelope!
   node
   {:src (node-id node)
    :dest dest
    :body (cond-> (assoc body :msg_id (next-msg-id! node))
            (some? request-msg-id) (assoc :in_reply_to request-msg-id))}))

(defn send!
  "Sends one outbound request: validates dest and body, allocates the next
  source-local msg_id, hands the finished envelope to the injected transport
  send!, and returns the envelope.

  Requires an initialized node. dest must be a string; body must be a map
  with a string :type and must not carry the reserved correlation fields
  :msg_id or :in_reply_to. Delivery is the transport's concern; this node
  tracks no promises and imposes no timeouts. A later response to this
  request arrives through handle! as a body carrying :in_reply_to and is
  reported to the :on-reply observer, if any. The four-argument form stores
  opaque request-context in the correlation record before emission and gives
  it back only to that observer."
  ([node dest body]
   (send! node dest body nil))
  ([node dest body request-context]
   (when-not (node-id node)
     (fail! ::not-initialized
            "node cannot send requests before initialization"
            {:dest dest :body body}))
   (when-not (string? dest)
     (fail! ::invalid-request
            "request destination must be a string"
            {:dest dest}))
   (when-not (and (map? body) (string? (:type body)))
     (fail! ::invalid-request
            "request body must be a map with a string :type"
            {:body body}))
   (when (or (contains? body :msg_id) (contains? body :in_reply_to))
     (fail! ::invalid-request
            "request bodies must not supply reserved correlation fields"
            {:body body}))
   (let [message-id (next-msg-id! node)
         envelope {:src (node-id node)
                   :dest dest
                   :body (assoc body :msg_id message-id)}]
     ;; Register the envelope and application coordinate before handing the
     ;; envelope to the transport. A synchronous response can therefore retire
     ;; both layers of delivery state before send! returns.
     (swap! (::state node) assoc-in [:pending-requests message-id]
            {:envelope envelope :context request-context})
     (emit-request! node envelope))))

(defn retry!
  "Resends the exact envelope for one still-pending outbound request.

  No msg_id is allocated and no request is created. Negative and non-integer
  IDs fail closed. A recently completed request
  returns nil without resending, so retry racing its acknowledgment is
  idempotent. Older unknown IDs fail closed. Transport failures
  retain the request and carry the exact :request-envelope in typed ex-data so
  the caller can preserve the ambiguous attempt as forensic evidence."
  [node message-id]
  (when-not (and (integer? message-id) (not (neg? message-id)))
    (fail! ::invalid-request-id
           "retry request id must be a non-negative integer"
           {:msg_id message-id}))
  (let [current @(::state node)]
    (if-let [request (get-in current [:pending-requests message-id])]
      (emit-request! node (:envelope request))
      (if (contains? (:completed-requests current) message-id)
        nil
        (fail! ::unknown-request
               "retry request id is not pending or recently completed"
               {:msg_id message-id})))))

(defn- consume-response!
  [node envelope]
  (let [response-id (get-in envelope [:body :in_reply_to])
        state (::state node)]
    (loop []
      (let [current @state
            request (get-in current [:pending-requests response-id])
            completed-source (get-in current [:completed-requests response-id])]
        (when (and (nil? request) (nil? completed-source))
          (fail! ::unexpected-response
                 "response does not correlate to a pending or recently completed request"
                 {:reason :unknown-request
                  :in_reply_to response-id
                  :src (:src envelope)}))
        (let [expected-source (or (get-in request [:envelope :dest])
                                  completed-source)]
          (when-not (= (:src envelope) expected-source)
            (fail! ::unexpected-response
                   "response source does not match the pending request destination"
                   {:reason :wrong-source
                    :in_reply_to response-id
                   :expected-src expected-source
                   :src (:src envelope)})))
        (if (nil? request)
          ;; A same-source duplicate for an exact retry is idempotently
          ;; consumed and never reaches the observer again.
          nil
          (let [next-order (conj (:completed-order current) response-id)
                evicted-id (when (> (count next-order)
                                    completed-response-window)
                             (first next-order))
                kept-order (if (some? evicted-id)
                             (vec (subvec next-order 1))
                             next-order)
                next-state
                (cond-> (-> current
                            (update :pending-requests dissoc response-id)
                            (assoc-in [:completed-requests response-id]
                                      (get-in request [:envelope :dest]))
                            (assoc :completed-order kept-order))
                  (some? evicted-id)
                  (update :completed-requests dissoc evicted-id))]
            (if (compare-and-set! state current next-state)
              (when-let [observer (::on-reply node)]
                (observer node envelope (:context request)))
              (recur))))))))

(defn- handle-init!
  [node envelope]
  (let [body (:body envelope)
        node_id (:node_id body)
        node_ids (:node_ids body)]
    (when-not (contains? body :msg_id)
      (fail! ::invalid-init "init body must carry an integer msg_id" {:body body}))
    (when-not (string? node_id)
      (fail! ::invalid-init "init body node_id must be a string" {:body body}))
    (when-not (and (sequential? node_ids)
                   (seq node_ids)
                   (every? string? node_ids)
                   (= (count node_ids) (count (set node_ids)))
                   (some #{node_id} node_ids))
      (fail! ::invalid-init
             "init body node_ids must be a nonempty unique sequence containing node_id"
             {:body body}))
    (when-not (= (:dest envelope) node_id)
      (fail! ::invalid-init
             "init envelope destination must match body node_id"
             {:dest (:dest envelope) :node_id node_id}))
    (let [state (::state node)]
      (loop []
        (let [existing @state]
          (when (some? (:node_id existing))
            (fail! ::already-initialized
                   "node received a second init message"
                   {:existing {:node_id (:node_id existing)
                               :node_ids (:node_ids existing)}
                    :received {:node_id node_id :node_ids (vec node_ids)}}))
          (when-not (compare-and-set!
                     state existing
                     (assoc existing :node_id node_id :node_ids (vec node_ids)))
            (recur)))))
    (send-reply! node (:src envelope) {:type "init_ok"} (:msg_id body))))

(defn handle!
  "Processes one inbound envelope: answers init itself, dispatches other
  request body types to their registered handler, or -- when the request
  carried a msg_id -- replies with the official not_supported error (code
  10) for an unregistered type. Replies are delivered through the node's
  send!.

  A valid non-init body carrying :in_reply_to is admitted only when it
  correlates to one pending outbound request and comes from that request's
  destination. The pending request is consumed atomically before the
  :on-reply observer is invoked. A bounded same-source duplicate is consumed
  idempotently; unknown and wrong-source responses fail closed. Responses are
  never replied to.

  Returns nil."
  [node envelope]
  (when-not (valid-envelope? envelope)
    (fail! ::invalid-envelope
           "envelope must have string :src/:dest and a :body map with a string :type"
           {:envelope envelope}))
  (let [body (:body envelope)
        type (:type body)
        handler (get (::handlers node) type)]
    (cond
      (= "init" type)
      (handle-init! node envelope)

      (nil? (node-id node))
      (fail! ::not-initialized
             "node received a non-init message before initialization"
             {:envelope envelope})

      (not= (:dest envelope) (node-id node))
      (fail! ::wrong-destination
             "message destination does not match this node"
             {:dest (:dest envelope) :node_id (node-id node)})

      (contains? body :in_reply_to)
      (consume-response! node envelope)

      (some? handler)
      (send-reply! node (:src envelope) (handler node envelope) (:msg_id body))

      (some? (:msg_id body))
      ;; 10 is Maelstrom's official not_supported error code.
      (send-reply! node (:src envelope)
                    {:type "error" :code 10 :text (str "not supported: " type)}
                    (:msg_id body))

      :else nil))
  nil)
