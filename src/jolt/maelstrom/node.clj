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
  each handler's responsibility.")

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

  send! is called with each outbound reply envelope."
  [{:keys [handlers send!]}]
  (when-not (valid-handlers? handlers)
    (fail! ::invalid-config
           "handlers must map string message types to functions and must not replace init"
           {:handlers handlers}))
  (when-not (fn? send!)
    (fail! ::invalid-config "send! must be a function" {:send! send!}))
  {::handlers handlers
   ::send! send!
   ::state (atom {:node_id nil :node_ids nil :next-msg-id 1})})

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
  (let [envelope {:src (node-id node)
                  :dest dest
                  :body (cond-> (assoc body :msg_id (next-msg-id! node))
                          (some? request-msg-id) (assoc :in_reply_to request-msg-id))}]
    ((::send! node) envelope)
    envelope))

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
  "Processes one inbound envelope: answers init itself, dispatches other body
  types to their registered handler, or -- when the request carried a
  msg_id -- replies with the official not_supported error (code 10) for an
  unregistered type. Replies are delivered through the node's send!.
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

      (some? handler)
      (send-reply! node (:src envelope) (handler node envelope) (:msg_id body))

      (some? (:msg_id body))
      ;; 10 is Maelstrom's official not_supported error code.
      (send-reply! node (:src envelope)
                    {:type "error" :code 10 :text (str "not supported: " type)}
                    (:msg_id body))

      :else nil))
  nil)
