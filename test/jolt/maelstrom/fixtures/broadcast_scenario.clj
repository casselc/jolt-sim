(ns jolt.maelstrom.fixtures.broadcast-scenario
  "One input-capable defsim scenario driving the unchanged
  jolt.maelstrom.broadcast application slice (create-broadcast :handlers,
  :on-reply, and :retry-pending!) on unchanged jolt.maelstrom.node instances
  over the existing jolt.maelstrom.transport.memory in-memory transport, in
  the fixed three-node line topology n1 -- n2 -- n3.

  The scenario owns only orchestration. It validates its exact input, builds
  the cluster through the real constructors, enqueues the official init /
  topology / broadcast / read client envelopes, drives every node endpoint
  through the unchanged node/handle! in sorted bounded round-robin order, and
  returns immutable observations: per-phase node snapshots, drop evidence,
  the real per-app retry-pending! evidence, the drained client replies, and
  the memory transport snapshot. Broadcast semantics, reply correlation, and
  retry identity live in the application and node namespaces; routing and
  history semantics live in the memory transport. The scenario never calls
  the history checker, never manufactures a Case/Outcome, and issues no
  verdict of its own -- judging the observations is the test's job.

  A tiny scenario-local link gate wraps each node's transport send fn. While
  active, it silently drops every node-to-node envelope whose {src dest} pair
  matches a selected undirected link and records bounded immutable drop
  evidence (a capped vector of {:src :dest :body} records plus an uncapped
  total count). Client-bound and client-originated envelopes are never
  gated. Clearing the partition flips one atom flag; nothing is replayed,
  reordered, or rewritten, so a retained application retry obligation is the
  only path back to delivery.

  jolt.sim.activity/emit! records small bounded semantic milestones. Healthy
  runs emit cluster-ready, post-retry-state-observed, and read-observed. Partitioned
  runs additionally emit link-partitioned, delivery-dropped, link-healed, and
  delivery-retried; a healthy run never claims those events occurred.
  Emission is a no-op without an observer; any observer lifecycle belongs to
  an external worker, never to this fixture."
  (:require [jolt.maelstrom.broadcast :as broadcast]
            [jolt.maelstrom.node :as node]
            [jolt.maelstrom.transport.memory :as memory]
            [jolt.sim.activity :as activity]
            [jolt.sim.runtime :as rt]))

(def cluster-ids
  "The fixed three cluster node ids, in sorted order."
  ["n1" "n2" "n3"])

(def line-topology
  "The fixed official topology body value for the n1 -- n2 -- n3 line."
  {"n1" ["n2"] "n2" ["n1" "n3"] "n3" ["n2"]})

(def client-id
  "The one official client endpoint id."
  "c1")

(def ^:private node-id-set (set cluster-ids))

(def ^:private max-drop-records 16)
(def ^:private max-passes 100)

;; ---- input validation --------------------------------------------------------

(defn- fail! [type message data]
  (throw (ex-info message (assoc data :type type))))

(defn validate-input!
  "Fail-closed validation of the one accepted scenario input shape:

    {:message <integer> :partition-links <vector>}

  where :partition-links is exactly [] (a healthy run) or the canonical
  undirected edge [[\"n2\" \"n3\"]] (this first partition slice). Every other
  value is rejected with a typed ::invalid-input ex-info before any cluster
  state exists. Returns nil on acceptance."
  [input]
  (when-not (map? input)
    (fail! ::invalid-input "scenario input must be a map" {:reason :not-a-map}))
  (when-not (= #{:message :partition-links} (set (keys input)))
    (fail! ::invalid-input
           "scenario input must carry exactly :message and :partition-links"
           {:reason :keys}))
  (when-not (integer? (:message input))
    (fail! ::invalid-input "scenario :message must be an integer"
           {:reason :message-not-integer}))
  (when-not (vector? (:partition-links input))
    (fail! ::invalid-input "scenario :partition-links must be a vector"
           {:reason :links-not-a-vector}))
  (when-not (contains? #{[] [["n2" "n3"]]} (:partition-links input))
    (fail! ::invalid-input
           "scenario :partition-links accepts only [] or [[\"n2\" \"n3\"]]"
           {:reason :unsupported-links}))
  nil)

;; ---- scenario-local link gate ------------------------------------------------

(defn- node-to-node?
  [envelope]
  (and (contains? node-id-set (:src envelope))
       (contains? node-id-set (:dest envelope))))

(defn- selected-link?
  "True when [src dest] matches one of the selected undirected links in
  either direction."
  [links src dest]
  (boolean (some (fn [[a b]]
                   (or (and (= a src) (= b dest))
                       (and (= a dest) (= b src))))
                 links)))

(defn- record-drop!
  "Records bounded immutable drop evidence: each dropped envelope contributes
  one {:src :dest :body} record until the record cap, while the total count
  always advances."
  [gate envelope]
  (swap! gate
         (fn [current]
           (-> current
               (update :dropped-total inc)
               (update :records
                       (fn [records]
                         (if (< (count records) max-drop-records)
                           (conj records {:src (:src envelope)
                                          :dest (:dest envelope)
                                          :body (:body envelope)})
                           records))))))
  nil)

(defn- gated-send
  "Wraps one node's transport send fn with the scenario-local link gate. A
  node-to-node envelope over a selected undirected link is dropped (with
  recorded evidence) while the gate is active; everything else -- including
  every client-bound envelope -- passes through to the real transport
  unchanged."
  [send! gate]
  (fn [envelope]
    (let [{:keys [active? links]} @gate]
      (if (and active?
               (node-to-node? envelope)
               (selected-link? links (:src envelope) (:dest envelope)))
        (record-drop! gate envelope)
        (send! envelope)))))

(defn drop-evidence
  "Returns the gate's immutable drop evidence as
  {:records [{:src :dest :body} ...] :dropped-total n}. Never exposes the
  gate atom itself."
  [gate]
  (let [current @gate]
    {:records (:records current)
     :dropped-total (:dropped-total current)}))

(defn clear-partition!
  "Heals every gated link. Envelopes already dropped stay dropped; later
  sends pass through. Returns nil."
  [gate]
  (swap! gate assoc :active? false)
  nil)

;; ---- cluster construction and driving ----------------------------------------

(defn build-world
  "Builds the three-node line-topology Broadcast cluster over one fresh
  memory transport: one real broadcast/create-broadcast application slice and
  one real node/create-node per cluster id, with each node's transport send
  fn wrapped by the scenario-local link gate selecting partition-links.
  Returns {:transport :nodes :apps :gate}; the gate stays behind
  drop-evidence and clear-partition!."
  [partition-links]
  (let [transport (memory/create-transport)
        gate (atom {:active? (boolean (seq partition-links))
                    :links partition-links
                    :records []
                    :dropped-total 0})
        apps (into {} (map (fn [id] [id (broadcast/create-broadcast)])
                           cluster-ids))
        nodes (into {}
                    (map (fn [id]
                           (let [app (get apps id)]
                             [id (node/create-node
                                  {:handlers (:handlers app)
                                   :send! (gated-send (memory/send-fn transport)
                                                      gate)
                                   :on-reply (:on-reply app)})])))
                    cluster-ids)]
    {:transport transport :nodes nodes :apps apps :gate gate}))

(defn enqueue-official-openers!
  "Enqueues the official client request envelopes that open the scenario:
  init (msg_ids 1-3) and topology (msg_ids 10-12) for each node in cluster
  order, then one broadcast of message to n1 (msg_id 20)."
  [transport message]
  (doseq [[i id] (map-indexed vector cluster-ids)]
    (memory/enqueue! transport
                     {:src client-id :dest id
                      :body {:type "init" :msg_id (inc i)
                             :node_id id :node_ids cluster-ids}}))
  (doseq [[i id] (map-indexed vector cluster-ids)]
    (memory/enqueue! transport
                     {:src client-id :dest id
                      :body {:type "topology" :msg_id (+ 10 i)
                             :topology line-topology}}))
  (memory/enqueue! transport
                   {:src client-id :dest "n1"
                    :body {:type "broadcast" :msg_id 20 :message message}}))

(defn drive-to-fixpoint!
  "Deterministic sorted bounded round-robin: in sorted node-id order, take!
  one envelope per node endpoint and feed it to the unchanged node/handle!,
  repeating until a full pass delivers nothing. Returns the number of
  delivering passes. Throws rather than looping forever when no fixpoint is
  reached within the pass bound."
  [transport nodes]
  (loop [passes 0]
    (when (> passes max-passes)
      (fail! ::no-fixpoint "no fixpoint within the pass bound"
             {:passes passes}))
    (let [delivered (reduce (fn [n id]
                              (if-let [envelope (memory/take! transport id)]
                                (do (node/handle! (get nodes id) envelope)
                                    (inc n))
                                n))
                            0
                            (sort (keys nodes)))]
      (if (zero? delivered)
        passes
        (recur (inc passes))))))

;; ---- observations ------------------------------------------------------------

(defn- node-snapshots
  "One real application :snapshot per cluster id, in cluster order."
  [apps]
  (into {} (map (fn [id] [id ((:snapshot (get apps id)))]) cluster-ids)))

(defn- message-counts
  [snapshots]
  (into {} (map (fn [[id snapshot]] [id (count (:messages snapshot))])
                snapshots)))

(defn- pending-counts
  [retry-evidence]
  (into {} (map (fn [[id evidence]] [id (count evidence)]) retry-evidence)))

(defn- snapshot-pending-counts
  [snapshots]
  (into {} (map (fn [[id snapshot]] [id (count (:pending snapshot))])
                snapshots)))

(defn- emit!
  [tag data]
  (activity/emit! [tag nil nil data]))

;; ---- the scenario ------------------------------------------------------------

(rt/defsim broadcast-partition-heal [input]
  {}
  (validate-input! input)
  (let [{:keys [message partition-links]} input
        {:keys [transport nodes apps gate]} (build-world partition-links)]
    (emit! :jolt.maelstrom.broadcast/cluster-ready
           {:node-ids cluster-ids
            :partition-links partition-links})
    (when (seq partition-links)
      (emit! :jolt.maelstrom.broadcast/link-partitioned
             {:links partition-links}))
    (enqueue-official-openers! transport message)
    (drive-to-fixpoint! transport nodes)
    (let [pre-heal (node-snapshots apps)
          pre-heal-drops (drop-evidence gate)]
      (when (seq partition-links)
        (let [dropped (first (:records pre-heal-drops))]
          (emit! :jolt.maelstrom.broadcast/delivery-dropped
                 {:link [(:src dropped) (:dest dropped)]
                  :msg_id (get-in dropped [:body :msg_id])
                  :dropped-total (:dropped-total pre-heal-drops)
                  :message-counts (message-counts pre-heal)})))
      (clear-partition! gate)
      (when (seq partition-links)
        (emit! :jolt.maelstrom.broadcast/link-healed
               {:links partition-links}))
      ;; The real application retry-pending! drives every outstanding
      ;; delivery obligation in sorted node order; each app's returned
      ;; evidence is retained verbatim.
      (let [retry-evidence
            (into {} (map (fn [id]
                            [id ((:retry-pending! (get apps id))
                                 (get nodes id))])
                          cluster-ids))]
        (when (seq partition-links)
          (emit! :jolt.maelstrom.broadcast/delivery-retried
                 {:pending-counts (pending-counts retry-evidence)
                  :msg_ids (into {}
                                 (map (fn [[id evidence]]
                                        [id (mapv :msg_id evidence)])
                                      retry-evidence))}))
        (drive-to-fixpoint! transport nodes)
        (let [final-snapshots (node-snapshots apps)]
          (emit! :jolt.maelstrom.broadcast/post-retry-state-observed
                 {:message-counts (message-counts final-snapshots)
                  :pending-counts
                  (snapshot-pending-counts final-snapshots)}))
        ;; The official client read against n3 after the retry fixpoint.
        (memory/enqueue! transport
                         {:src client-id :dest "n3"
                          :body {:type "read" :msg_id 30}})
        (drive-to-fixpoint! transport nodes)
        (let [client-replies (memory/drain! transport client-id)
              read-reply (last client-replies)
              observations
              {:input input
               :topology line-topology
               :phases {:pre-heal pre-heal
                        :final (node-snapshots apps)}
               :retry retry-evidence
               :drops pre-heal-drops
               :client-replies client-replies
               :transport (memory/snapshot transport)}]
          (emit! :jolt.maelstrom.broadcast/read-observed
                 {:node-id "n3"
                  :in_reply_to (get-in read-reply [:body :in_reply_to])
                  :message-count (count (get-in read-reply [:body :messages]))})
          observations)))))
