(ns jolt.maelstrom.broadcast
  "The official Maelstrom Broadcast workload as a transport-neutral
  application slice.

  All application state -- this node's identity as learned from its first
  handled request, the neighbor list installed by topology, and the set of
  observed broadcast messages -- lives in one atom captured by a closure
  created here; jolt.maelstrom.node internals are untouched. The constructor
  returns the :handlers map jolt.maelstrom.node/create-node expects plus a
  zero-arg :snapshot fn over that closure state.

  Handlers implement the official topology / broadcast / read message types.
  A broadcast value is stored once and creates one stable delivery obligation
  per sorted neighbor except the request's source. Peer broadcasts carry
  node-allocated msg_ids; duplicate input and explicit retry-pending! calls
  resend only outstanding obligations with the same IDs. Correlated
  broadcast_ok responses retire them without reply ping-pong. Every malformed
  body fails closed with a typed ex-info rather than being guessed at.

  The official Broadcast message is an integer. Keeping that wire contract
  here is important: the same handlers must run through Maelstrom's real JSON
  process boundary, not merely accept richer values in the in-memory test
  transport. read and snapshot return messages in numeric order so retained
  evidence is deterministic even though Maelstrom does not require an order."
  (:require [jolt.maelstrom.node :as node]))

(def body-key-map
  "Finite workload vocabulary for the real JSON-lines decoder. Unknown wire
  keys remain strings; these existing keywords are the only additional keys
  Broadcast consumes."
  {"topology" :topology
   "message" :message
   "messages" :messages})

(def ^:private allocating-request ::allocating-request)

(defn- fail!
  [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn- require-msg-id!
  [body type-name]
  (when-not (contains? body :msg_id)
    (fail! ::missing-msg-id
           (str type-name " request body has no msg_id")
           {:body body})))

(defn- note-node-id!
  "Records the node's identity in the application state the first time any
  handler runs. Handlers learn the node only when invoked; init is answered
  by the node itself before then."
  [state node]
  (when (nil? (:node_id @state))
    (swap! state assoc :node_id (node/node-id node))))

(defn- observe-message!
  "Adds one message and its required neighbor deliveries atomically. Returns
  true exactly for the first observation; loses no updates under concurrent
  handlers."
  [state message source]
  (loop []
    (let [current @state]
      (if (contains? (:messages current) message)
        false
        (let [destinations (remove #{source} (:neighbors current))
              next-state
              (reduce (fn [value neighbor]
                        (assoc-in value [:pending [message neighbor]] nil))
                      (update current :messages conj message)
                      destinations)]
          (if (compare-and-set! state current next-state)
            true
            (recur)))))))

(defn- claim-new-request!
  [state pair]
  (loop []
    (let [current @state
          pending (:pending current)]
      (cond
        (not (contains? pending pair)) false
        (some? (get pending pair)) false
        (compare-and-set! state current
                          (assoc-in current [:pending pair]
                                    allocating-request)) true
        :else (recur)))))

(defn- remember-request-id!
  [state pair message-id]
  (swap! state
         (fn [current]
           (if (= allocating-request (get-in current [:pending pair]))
             (assoc-in current [:pending pair] message-id)
             current))))

(defn- pending-pairs
  [state-value message]
  (->> (:pending state-value)
       keys
       (filterv #(= message (first %)))
       (sort-by second)
       vec))

(defn- all-pending-pairs
  [state-value]
  (vec (sort-by (fn [[message neighbor]] [message neighbor])
                (keys (:pending state-value)))))

(defn- request-id-from-error
  [error]
  (get-in (ex-data error) [:request-envelope :body :msg_id]))

(defn- attempt-pair!
  "Attempts or retries one pending delivery. Returns nil on success/skip or
  the transport error on failure; correlation state remains available for a
  later retry in every case."
  [state node pair]
  (let [[message neighbor] pair
        existing (get-in @state [:pending pair])]
    (cond
      (integer? existing)
      (try
        (node/retry! node existing)
        nil
        (catch :default error error))

      (nil? existing)
      (when (claim-new-request! state pair)
        (try
          (let [envelope (node/send! node neighbor
                                     {:type "broadcast" :message message}
                                     pair)]
            (remember-request-id! state pair (get-in envelope [:body :msg_id]))
            nil)
          (catch :default error
            (let [message-id (request-id-from-error error)]
              (remember-request-id! state pair
                                    (if (integer? message-id) message-id nil)))
            error)))

      :else nil)))

(defn- attempt-pairs!
  "Attempts every supplied pair even when an earlier transport call fails,
  then rethrows the first failure. This avoids starving a fanout suffix while
  retaining the original transport cause."
  [state node pairs]
  (let [first-error
        (reduce (fn [failure pair]
                  (let [error (attempt-pair! state node pair)]
                    (or failure error)))
                nil
                pairs)]
    (when first-error (throw first-error))))

(defn- acknowledge!
  [state pair]
  ;; The node registered this opaque pair before transport emission and only
  ;; returns it after exact ID/source correlation. It therefore works even for
  ;; a synchronous response that arrives before node/send! returns.
  (swap! state update :pending dissoc pair))

(defn- reject-reply!
  [state envelope pair]
  ;; The correlated request has reached a terminal response in the node, so
  ;; retrying its old msg_id would be a no-op. Preserve the application-level
  ;; delivery obligation as unallocated: an explicit later retry must create a
  ;; fresh request and correlation ID rather than silently treating rejection
  ;; as delivery.
  (swap! state
         (fn [current]
           (if (contains? (:pending current) pair)
             (assoc-in current [:pending pair] nil)
             current)))
  (let [body (:body envelope)
        type (:type body)]
    (if (= "error" type)
      (fail! ::delivery-rejected
             "a peer rejected the broadcast delivery"
             {:pair pair :response-body body})
      (fail! ::unexpected-reply
             "a broadcast delivery received a non-broadcast_ok response"
             {:pair pair :response-body body}))))

(defn- handle-reply!
  [state envelope pair]
  (if (= "broadcast_ok" (get-in envelope [:body :type]))
    (acknowledge! state pair)
    (reject-reply! state envelope pair)))

(defn- sorted-messages
  "The observed integer messages as a deterministically sorted vector."
  [state-value]
  (vec (sort (:messages state-value))))

(defn- pending-evidence
  [state-value]
  (mapv (fn [[message neighbor :as pair]]
          (let [request-id (get-in state-value [:pending pair])]
            {:message message
             :neighbor neighbor
             :msg_id (when (integer? request-id) request-id)
             :status (if (integer? request-id) :awaiting-reply :allocating)}))
        (all-pending-pairs state-value)))

(defn- install-topology!
  [state node-id canonical-topology body]
  (loop []
    (let [current @state]
      (if (:topology-installed? current)
        (when-not (= canonical-topology (:topology current))
          (fail! ::conflicting-topology
                 "a later topology must be identical to the installed topology"
                 {:node_id node-id :body body}))
        (if (compare-and-set!
             state current
             (assoc current
                    :node_id node-id
                    :neighbors (get canonical-topology node-id)
                    :topology canonical-topology
                    :topology-installed? true))
          nil
          (recur))))))

(defn- handle-topology
  [state node envelope]
  (let [body (:body envelope)]
    (require-msg-id! body "topology")
    (let [topology (:topology body)]
      (when-not (map? topology)
        (fail! ::invalid-topology
               "topology must be a map"
               {:body body}))
      (let [node-id (node/node-id node)
            known-node-ids (set (node/node-ids node))]
        (when-not (= known-node-ids (set (keys topology)))
          (fail! ::invalid-topology
                 "topology keys must exactly match the initialized node ids"
                 {:node_id node-id :node_ids (node/node-ids node) :body body}))
        (when-not
          (every? (fn [[id neighbors]]
                    (and (string? id)
                         (sequential? neighbors)
                         (every? string? neighbors)
                         (= (count neighbors) (count (set neighbors)))
                         (every? known-node-ids neighbors)
                         (not-any? #{id} neighbors)))
                  topology)
          (fail! ::invalid-topology
                 "topology neighbors must be unique known node ids other than the node itself"
                 {:node_id node-id :node_ids (node/node-ids node) :body body}))
        (let [canonical-topology
              (into {} (map (fn [[id neighbors]]
                              [id (vec (sort neighbors))])
                            topology))]
          (install-topology! state node-id canonical-topology body)))
      {:type "topology_ok"})))

(defn- handle-broadcast
  [state node envelope]
  (let [body (:body envelope)]
    (require-msg-id! body "broadcast")
    (note-node-id! state node)
    (when-not (:topology-installed? @state)
      (fail! ::topology-not-installed
             "broadcast cannot run before topology is installed"
             {:body body}))
    (when-not (contains? body :message)
      (fail! ::missing-message
             "broadcast request body has no message key"
             {:body body}))
    (let [message (:message body)]
      (when-not (integer? message)
        (fail! ::unsupported-message
               "broadcast message must be an integer"
               {:message message}))
      ;; Local deduplication and remote delivery are separate facts. The first
      ;; observation creates stable pending pairs; every later duplicate may
      ;; retry those same msg_ids until correlated broadcast_ok replies remove
      ;; them.
      (observe-message! state message (:src envelope))
      (attempt-pairs! state node (pending-pairs @state message))
      {:type "broadcast_ok"})))

(defn- handle-read
  [state node envelope]
  (require-msg-id! (:body envelope) "read")
  (note-node-id! state node)
  {:type "read_ok" :messages (sorted-messages @state)})

(defn create-broadcast
  "Creates one Broadcast workload application slice. Returns a map with:

    :handlers  the request-type -> handler map for
               jolt.maelstrom.node/create-node
    :on-reply  the correlated-response observer to pass to create-node
    :retry-pending! a one-arg fn of node that retries every outstanding pair
    :body-key-map the finite JSON-lines workload vocabulary
    :snapshot  a zero-arg fn returning immutable data of the form
               {:node_id <string or nil until the first handled request>
                :neighbors <sorted vector of neighbor node ids>
                :messages <sorted vector of observed integer messages>
                :pending <sorted vector of delivery evidence>}

  The state atom stays captured in the closure and is never exposed; the
  snapshot shares only persistent, immutable values."
  []
  (let [state (atom {:node_id nil
                     :neighbors []
                     :topology nil
                     :topology-installed? false
                     :messages #{}
                     :pending {}})]
    {:handlers {"topology" (fn [node envelope] (handle-topology state node envelope))
                "broadcast" (fn [node envelope] (handle-broadcast state node envelope))
                "read" (fn [node envelope] (handle-read state node envelope))}
     :on-reply (fn [_node envelope pair]
                 (handle-reply! state envelope pair))
     :retry-pending! (fn [node]
                       (attempt-pairs! state node (all-pending-pairs @state))
                       (pending-evidence @state))
     :body-key-map body-key-map
     :snapshot (fn []
                 (let [current @state]
                   {:node_id (:node_id current)
                    :neighbors (vec (:neighbors current))
                    :messages (sorted-messages current)
                    :pending (pending-evidence current)}))}))
