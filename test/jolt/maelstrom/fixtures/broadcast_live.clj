(ns jolt.maelstrom.fixtures.broadcast-live
  "Interactive lifecycle around the unchanged Maelstrom Broadcast cluster.

  This fixture owns only orchestration and observation. `start!` delegates
  cluster construction to broadcast-scenario/build-world, which creates the
  real jolt.maelstrom.broadcast applications and jolt.maelstrom.node nodes over
  jolt.maelstrom.transport.memory. `step!` removes at most one envelope from
  exactly one selected node FIFO and hands it to node/handle!; it never drives a
  hidden fixpoint or substitutes Broadcast behavior.

  Snapshots are immutable and bounded: node application snapshots are small in
  this one-message scenario, each node mailbox exposes only count and head,
  client replies and transport history expose capped tails, and drop records
  retain the existing broadcast-scenario cap. The live handles themselves are
  process-local mutable test fixtures and are never returned as evidence."
  (:require [jolt.maelstrom.fixtures.broadcast-scenario :as scenario]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]))

(def ^:private live-marker ::live)
(def ^:private max-client-replies 32)
(def ^:private max-history-events 64)

(def ^:private regime-links
  {:healthy []
   :partition-heal [["n2" "n3"]]})

(defn- fail! [reason data]
  (throw
   (ex-info "interactive Broadcast lifecycle contract violation"
            (assoc data :type ::invalid-operation :reason reason))))

(defn- checked-input [input]
  (when-not (map? input)
    (fail! :input-not-a-map {:value-class (str (class input))}))
  (when-not (= #{:message :regime} (set (keys input)))
    (fail! :input-wrong-keys
           {:expected #{:message :regime}
            :actual (set (keys input))}))
  (when-not (integer? (:message input))
    (fail! :message-not-integer {:message (:message input)}))
  (when-not (contains? regime-links (:regime input))
    (fail! :unsupported-regime
           {:regime (:regime input)
            :supported (set (keys regime-links))}))
  input)

(defn- checked-live [live]
  (when-not (and (map? live)
                 (= live-marker (::marker live))
                 (map? (::world live))
                 (some? (::lifecycle live)))
    (fail! :invalid-live-handle {}))
  live)

(defn- require-phase! [live operation allowed]
  (let [phase (:phase @(::lifecycle (checked-live live)))]
    (when-not (contains? allowed phase)
      (fail! :invalid-phase
             {:operation operation :allowed allowed :actual phase})))
  live)

(defn- tail [values limit]
  (let [values (vec values)
        n (count values)]
    (if (> n limit)
      (subvec values (- n limit))
      values)))

(defn- application-snapshots [apps]
  (into (sorted-map)
        (map (fn [id]
               [id ((:snapshot (get apps id)))])
             scenario/cluster-ids)))

(defn- mailbox-summary [queues]
  (into (sorted-map)
        (map (fn [id]
               (let [messages (vec (get queues id []))]
                 [id {:count (count messages)
                      :head (first messages)}]))
             scenario/cluster-ids)))

(defn snapshot!
  "Returns one bounded immutable observation of the live cluster."
  [live]
  (let [live (checked-live live)
        {:keys [transport apps gate]} (::world live)
        lifecycle @(::lifecycle live)
        transport-snapshot (memory/snapshot transport)
        queues (:queues transport-snapshot)
        client-replies (vec (get queues scenario/client-id []))
        mailboxes (mailbox-summary queues)
        history (:history transport-snapshot)]
    {:kind :jolt.sim/maelstrom-broadcast-live
     :status (:phase lifecycle)
     :input (::input live)
     :topology scenario/line-topology
     :partition {:links (get regime-links (get-in live [::input :regime]))
                 :active? (:partition-active? lifecycle)}
     :nodes (application-snapshots apps)
     :ready-mailboxes
     (filterv #(pos? (get-in mailboxes [% :count])) scenario/cluster-ids)
     :mailboxes mailboxes
     :client-replies {:count (count client-replies)
                      :tail (tail client-replies max-client-replies)}
     :drops (scenario/drop-evidence gate)
     :transport {:history-count (count history)
                 :history-tail (tail history max-history-events)}
     :control (select-keys lifecycle
                           [:bootstrap-count :step-count :retry-count
                            :read-issued? :last-step :last-retry])}))

(defn start!
  "Creates a fresh stopped-at-start cluster for one exact input:

    {:message <integer> :regime :healthy|:partition-heal}

  No official envelope is enqueued until `bootstrap!`."
  [input]
  (let [{:keys [regime] :as input} (checked-input input)
        links (get regime-links regime)]
    {::marker live-marker
     ::input input
     ::world (scenario/build-world links)
     ::lifecycle (atom {:phase :created
                        :partition-active? (boolean (seq links))
                        :bootstrap-count 0
                        :step-count 0
                        :retry-count 0
                        :read-issued? false
                        :last-step nil
                        :last-retry nil})}))

(defn bootstrap!
  "Enqueues the real official init/topology/broadcast openers exactly once.
  It does not handle any of them."
  [live]
  (require-phase! live :bootstrap #{:created})
  (let [{:keys [transport]} (::world live)
        message (get-in live [::input :message])]
    (scenario/enqueue-official-openers! transport message)
    (swap! (::lifecycle live)
           assoc :phase :running :bootstrap-count 1)
    {:operation :bootstrap :enqueued 7}))

(defn step!
  "Attempts one delivery from exactly `node-id`'s FIFO.

  When an envelope exists, it is passed unchanged to the real node/handle!.
  When the selected FIFO is empty the step is an observed idle attempt. No
  other endpoint is read and no fixpoint loop runs."
  [live node-id]
  (require-phase! live :step #{:running})
  (when-not (contains? (set scenario/cluster-ids) node-id)
    (fail! :invalid-node-id
           {:node-id node-id :allowed (set scenario/cluster-ids)}))
  (let [{:keys [transport nodes]} (::world live)
        envelope (memory/take! transport node-id)
        sequence (inc (:step-count @(::lifecycle live)))
        result {:operation :step
                :sequence sequence
                :node-id node-id
                :delivered? (some? envelope)
                :envelope envelope}]
    (when envelope
      (node/handle! (get nodes node-id) envelope))
    (swap! (::lifecycle live)
           assoc :step-count sequence :last-step result)
    result))

(defn heal!
  "Clears the fixed n2--n3 partition. Dropped messages stay dropped."
  [live]
  (require-phase! live :heal #{:running})
  (let [lifecycle @(::lifecycle live)
        regime (get-in live [::input :regime])]
    (when-not (= :partition-heal regime)
      (fail! :healthy-regime-has-no-partition {:regime regime}))
    (when-not (:partition-active? lifecycle)
      (fail! :partition-not-active {}))
    (scenario/clear-partition! (get-in live [::world :gate]))
    (swap! (::lifecycle live) assoc :partition-active? false)
    {:operation :heal :links (get regime-links regime)}))

(defn retry!
  "Invokes each real Broadcast application's retry-pending! once, in stable
  cluster order. It enqueues retransmissions but does not deliver them."
  [live]
  (require-phase! live :retry #{:running})
  (let [{:keys [apps nodes]} (::world live)
        evidence
        (into (sorted-map)
              (map (fn [id]
                     [id ((:retry-pending! (get apps id)) (get nodes id))])
                   scenario/cluster-ids))
        sequence (inc (:retry-count @(::lifecycle live)))
        result {:operation :retry :sequence sequence :evidence evidence}]
    (swap! (::lifecycle live)
           assoc :retry-count sequence :last-retry result)
    result))

(defn read!
  "Enqueues the one official read request to n3 exactly once. It remains in
  n3's FIFO until a caller selects n3 with `step!`."
  [live]
  (require-phase! live :read #{:running})
  (when (:read-issued? @(::lifecycle live))
    (fail! :read-already-issued {}))
  (let [message-id
        (memory/enqueue! (get-in live [::world :transport])
                         {:src scenario/client-id :dest "n3"
                          :body {:type "read" :msg_id 30}})]
    (swap! (::lifecycle live) assoc :read-issued? true)
    {:operation :read :transport-message-id message-id}))

(defn stop!
  "Closes lifecycle ownership. The first caller owns the transition; later
  cleanup calls are harmless and return false."
  [live]
  (let [live (checked-live live)
        lifecycle (::lifecycle live)]
    (loop []
      (let [current @lifecycle]
        (if (= :stopped (:phase current))
          false
          (if (compare-and-set! lifecycle current (assoc current :phase :stopped))
            true
            (recur)))))))
