(ns jolt.maelstrom.transport.memory
  "A thin, deterministic, in-memory Maelstrom transport slice.

  This is a transport only. It routes envelopes between named endpoints over
  per-endpoint FIFOs and records a monotonic, ordered history of enqueue and
  delivery events. Each successful enqueue is also tagged with a stable
  positive transport message-id -- the enqueue event's own ordinal -- that is
  retained unchanged on the matching delivery event, so every history event
  carries both its per-event :ordinal and its enqueued message's :message-id.
  It owns no message semantics: init, Echo, reply building, and body
  validation all stay in jolt.maelstrom.node and jolt.maelstrom.echo, whose
  handle! is driven unchanged over this transport.

  All mutable state lives in one atom captured by an opaque operation closure;
  callers cannot dereference or reset it. Every enqueue and delivery is one
  compare-and-set! on it, so a run is deterministic given call order and loses
  no updates under concurrent producers or consumers. The
  transport validates only the routing shape an envelope needs -- it must be a
  map with a string :dest -- so a body the node would reject still reaches node
  validation instead of being filtered here.")

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(def ^:private empty-queue clojure.lang.PersistentQueue/EMPTY)

(defn- enqueue-into!
  "Appends envelope to its :dest FIFO under one compare-and-set! on world and
  records an enqueue event carrying the assigned stable ordinal. That ordinal
  is also the envelope's transport message-id, retained unchanged on the
  matching delivery event. Returns the assigned ordinal. Validates only the
  routing shape (a map with a string :dest)."
  [world envelope]
  (when-not (and (map? envelope) (string? (:dest envelope)))
    (fail! ::invalid-envelope
           "envelope must be a map with a string :dest to route"
           {:envelope envelope}))
  (let [dest (:dest envelope)]
    (loop [current @world]
      (let [ordinal (:next-ordinal current)
            queues (:queues current)
            event {:ordinal ordinal :op :enqueue
                   :endpoint dest :envelope envelope
                   :message-id ordinal}
            next-state (-> current
                           (update :queues assoc dest
                                   (conj (get queues dest empty-queue)
                                         {:message-id ordinal :envelope envelope}))
                           (assoc :next-ordinal (inc ordinal))
                           (update :history conj event))]
        (if (compare-and-set! world current next-state)
          ordinal
          (recur @world))))))

(defn- take-from!
  "Atomically pops one envelope from endpoint's FIFO under one
  compare-and-set! on world, records a delivery event that retains the
  envelope's assigned message-id, and returns the bare envelope. Returns nil
  (and records nothing) when the FIFO is empty."
  [world endpoint]
  (loop [current @world]
    (let [queues (:queues current)
          q (get queues endpoint empty-queue)]
      (if (empty? q)
        nil
        (let [{:keys [message-id envelope]} (peek q)
              remaining (pop q)
              ordinal (:next-ordinal current)
              event {:ordinal ordinal :op :deliver
                     :endpoint endpoint :envelope envelope
                     :message-id message-id}
              next-queues (if (empty? remaining)
                            (dissoc queues endpoint)
                            (assoc queues endpoint remaining))
              next-state (-> current
                             (assoc :queues next-queues)
                             (assoc :next-ordinal (inc ordinal))
                             (update :history conj event))]
          (if (compare-and-set! world current next-state)
            envelope
            (recur @world)))))))

(defn create-transport
  "Creates a fresh in-memory transport. All routing state -- per-endpoint FIFOs
  and the monotonic event history -- lives in one atom captured by an opaque
  operation closure. The atom is not returned. Use send-fn to obtain the
  one-argument function intended for jolt.maelstrom.node/create-node :send!."
  []
  (let [world (atom {:queues (sorted-map)
                     :history []
                     :next-ordinal 1})]
    (fn transport-operation
      ([operation]
       (case operation
         ::snapshot
         (let [state @world]
           {:queues (into (sorted-map)
                          (map (fn [[endpoint queue]]
                                 [endpoint (mapv :envelope queue)])
                               (:queues state)))
            :history (vec (:history state))})
         (fail! ::invalid-operation
                "unknown zero-argument transport operation"
                {:operation operation})))
      ([operation argument]
       (case operation
         ::enqueue (enqueue-into! world argument)
         ::take (take-from! world argument)
         (fail! ::invalid-operation
                "unknown one-argument transport operation"
                {:operation operation}))))))

(defn- require-transport!
  [transport]
  (when-not (fn? transport)
    (fail! ::invalid-transport
           "transport must be a value returned by create-transport"
           {:transport transport}))
  transport)

(defn send-fn
  "Returns the one-argument send function carried by this transport. Pass it to
  jolt.maelstrom.node/create-node as :send!; the node calls it with each reply
  envelope, and it delivers that envelope into its :dest FIFO as an enqueue."
  [transport]
  (require-transport! transport)
  (fn [envelope] (transport ::enqueue envelope)))

(defn enqueue!
  "Appends envelope to its :dest FIFO, tags it with a stable positive monotonic
  transport message-id (the enqueue event's ordinal), and records an enqueue
  event carrying that message-id and the event ordinal. Returns the assigned
  event ordinal, which is also the message-id. Validates only the routing
  shape (a map with a string :dest); body content is passed through untouched."
  [transport envelope]
  ((require-transport! transport) ::enqueue envelope))

(defn take!
  "Atomically pops one envelope from endpoint's FIFO in FIFO order, records a
  delivery event, and returns the envelope. Returns nil when the FIFO is empty.
  The compare-and-set! loop loses no updates under concurrent consumers."
  [transport endpoint]
  (when-not (string? endpoint)
    (fail! ::invalid-endpoint
           "endpoint must be a string"
           {:endpoint endpoint}))
  ((require-transport! transport) ::take endpoint))

(defn drain!
  "Pops every envelope from endpoint's FIFO in FIFO order, recording one
  delivery event per envelope, and returns them as a vector (empty if the FIFO
  was empty). Each pop reuses take!'s atomic delivery."
  [transport endpoint]
  (loop [acc []]
    (if-let [envelope (take! transport endpoint)]
      (recur (conj acc envelope))
      acc)))

(defn snapshot
  "Returns a persistent structural snapshot containing a sorted map of
  endpoint -> FIFO vector and the ordered event history. The transport's atom
  and operation closures are never surfaced, and later queue/history updates do
  not change the returned containers.

  Envelope leaves are retained as supplied. This transport accepts only the
  routing shape it needs and deliberately does not pretend to validate or
  canonicalize workload payloads; a simulation evidence adapter must restrict
  or encode them into its declared trace domain before persistence/replay."
  [transport]
  ((require-transport! transport) ::snapshot))
