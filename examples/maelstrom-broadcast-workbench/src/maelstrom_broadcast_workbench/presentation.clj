(ns maelstrom-broadcast-workbench.presentation
  "Application-owned value presentation for the retained Broadcast example.

  This namespace knows the Broadcast snapshot schema. jolt-sim's value
  registry, Ripple, and the static report deliberately do not."
  (:require [jolt.sim.presentation :as presentation]))

(def snapshot-kind :jolt.sim/maelstrom-broadcast-live)
(def command-result-kind
  :maelstrom-broadcast-workbench/command-result)

(defn- require-kind! [expected value]
  (when-not (and (map? value) (= expected (:kind value)))
    (throw (ex-info "Broadcast presentation received the wrong value kind"
                    {:expected expected :actual (:kind value)})))
  value)

(defn- ordered-link [[left right]]
  (if (pos? (compare left right)) [right left] [left right]))

(defn- links [topology]
  (->> topology
       (mapcat (fn [[from tos]] (map #(ordered-link [from %]) tos)))
       distinct
       sort
       vec))

(defn- selected-link? [selected [left right]]
  (contains? (set (map ordered-link selected)) [left right]))

(defn- drops-on-link [records [left right]]
  (count (filter (fn [{:keys [src dest]}]
                   (= [left right] (ordered-link [src dest])))
                 records)))

(defn- edge-status [snapshot link]
  (let [selected? (selected-link? (get-in snapshot [:partition :links]) link)]
    (cond
      (and selected? (get-in snapshot [:partition :active?]))
      :jolt.sim.status/partitioned

      selected?
      :jolt.sim.status/healed

      :else
      :jolt.sim.status/connected)))

(defn- node-status [snapshot node-id]
  (cond
    (= :stopped (:status snapshot))
    :jolt.sim.status/stopped

    (pos? (get-in snapshot [:mailboxes node-id :count] 0))
    :jolt.sim.status/ready

    :else
    :jolt.sim.status/idle))

(defn snapshot-projection
  "Projects one exact Broadcast snapshot into the generic bounded topology VM."
  [value]
  (let [snapshot (require-kind! snapshot-kind value)
        topology (:topology snapshot)
        node-ids (vec (sort (keys topology)))
        drop-records (get-in snapshot [:drops :records] [])]
    {:summary (str "Broadcast cluster " (name (:status snapshot)))
     :fields [{:label "Control" :value (:control snapshot)}
              {:label "Drops" :value (:drops snapshot)}
              {:label "Input" :value (:input snapshot)}
              {:label "Replies" :value (:client-replies snapshot)}
              {:label "Status" :value (:status snapshot)}
              {:label "Transport" :value (:transport snapshot)}]
     :graph
     {:directed? false
      :nodes
      (mapv (fn [node-id]
              {:id node-id
               :label node-id
               :status (node-status snapshot node-id)
               :fields
               [{:label "Mailbox count"
                 :value (get-in snapshot [:mailboxes node-id :count] 0)}
                {:label "Mailbox head"
                 :value (get-in snapshot [:mailboxes node-id :head])}
                {:label "Messages"
                 :value (count (get-in snapshot [:nodes node-id :messages] []))}
                {:label "Pending"
                 :value (count (get-in snapshot [:nodes node-id :pending] []))}]})
            node-ids)
      :edges
      (mapv (fn [[left right :as link]]
              {:id (str left "--" right)
               :from left
               :to right
               :label (str left " - " right)
               :status (edge-status snapshot link)
               :fields
               [{:label "Active partition"
                 :value (and (selected-link?
                              (get-in snapshot [:partition :links]) link)
                             (get-in snapshot [:partition :active?]))}
                {:label "Dropped envelopes"
                 :value (drops-on-link drop-records link)}]})
            (links topology))}}))

(defn command-result-projection
  "Presents only the explicit snapshot slot in our exact command-result kind."
  [value]
  (require-kind! command-result-kind value)
  (let [snapshot (:snapshot value)
        projection (snapshot-projection snapshot)]
    (assoc projection :summary
           (str "Broadcast " (name (:operation value)) " result; "
                (:summary projection)))))

(def value-registry
  (presentation/value-registry
   {snapshot-kind
    {:kind :jolt.sim.kind/topology
     :present snapshot-projection}
    command-result-kind
    {:kind :jolt.sim.kind/topology
     :present command-result-projection}}))

(defn present
  "Pure REPL/tap-friendly presentation of one immutable application value."
  [value]
  (presentation/present-value value-registry value))
