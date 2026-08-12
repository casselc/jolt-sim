(ns maelstrom-broadcast-workbench.flow-retained
  "Project-local Mycelium-shaped command and receipt cells for Broadcast.

  The cells are pure. A command cell emits one inert
  :example.broadcast/command intent; jolt.sim.flow-effect-session publishes it
  only after an exact Session branch commits. The existing retained Broadcast
  adapter still owns the real application, transport, mailboxes, retry logic,
  connection regimes, and lifecycle.

  A receipt cell consumes a bounded projection of one definite application
  receipt as ordinary accumulated data. It performs no worker call and does
  not reinterpret Broadcast protocol behavior."
  (:require [jolt.fs :as fs]
            [jolt.sim.command-cell-session :as command-cell-session]
            [jolt.sim.flow :as flow]
            [jolt.sim.kernel :as kernel]))

(def effect-kind :example.broadcast/command)

(defn retained-config
  "Builds the existing Broadcast retained-worker launch contract."
  [jolt-bin project-dir input]
  (let [temp-dir (or (System/getenv "JOLT_SIM_RETAINED_ARTIFACT_DIR")
                     (or (System/getenv "TMPDIR") "/tmp"))]
    (fs/create-dirs temp-dir)
    {:worker-command [jolt-bin "-M:maelstrom-broadcast-retained-worker"]
     :adapter 'jolt.sim.fixtures.broadcast-retained/run!
     :input input
     :dir project-dir
     :temp-dir temp-dir
     :extra-env {"JOLT_AOT_CACHE" "0"}
     :startup-timeout-ms 60000
     :command-timeout-ms 60000
     :kill-grace-ms 1000}))

(def ^:private no-arg-command-schemas
  {:inspect
   [:map {:closed true} [:op [:enum :inspect]]]
   :bootstrap
   [:map {:closed true} [:op [:enum :bootstrap]]]
   :heal
   [:map {:closed true} [:op [:enum :heal]]]
   :retry
   [:map {:closed true} [:op [:enum :retry]]]
   :read
   [:map {:closed true} [:op [:enum :read]]]
   :stop
   [:map {:closed true} [:op [:enum :stop]]]})

(def ^:private step-command-schema
  [:map {:closed true}
   [:op [:enum :step]]
   [:node-id [:enum "n1" "n2" "n3"]]])

(def ^:private regime-command-schema
  [:map {:closed true}
   [:op [:enum :set-connection-regime]]
   [:connection
    [:enum ["n1" "n2"] ["n2" "n1"]
     ["n2" "n3"] ["n3" "n2"]]]
   [:expected-revision [:int {:min 0}]]
   [:regime [:enum :normal :drop]]])

(def empty-output-schema [:map {:closed true}])

(def command-specs
  (into {}
        (map (fn [[op input-schema]]
               [op {:handler :emit-command
                    :schema {:input input-schema
                             :output empty-output-schema}
                    :emits #{effect-kind}}]))
        (assoc no-arg-command-schemas
               :step step-command-schema
               :set-connection-regime regime-command-schema)))

(def command-handlers
  {:emit-command
   (fn [_ state command]
     {:state (inc (or state 0))
      :data {}
      :intents [{:kind effect-kind :payload command}]})})

(def ^:private command-keys
  {:inspect #{:op}
   :bootstrap #{:op}
   :step #{:op :node-id}
   :set-connection-regime
   #{:op :connection :expected-revision :regime}
   :heal #{:op}
   :retry #{:op}
   :read #{:op}
   :stop #{:op}})

(defn command-flow
  "Compiles one exact Broadcast command into a pure one-cell flow.

  Unknown operations and operation-specific shape errors fail before a worker
  is contacted. The emitted intent payload is the original canonical command."
  [command]
  (let [op (:op command)]
    (when-not (contains? command-specs op)
      (throw (ex-info "Broadcast flow command has an unknown operation"
                      {:type ::invalid-command :operation op})))
    (when-not (= (get command-keys op) (set (keys command)))
      (throw (ex-info "Broadcast flow command has the wrong keys"
                      {:type ::invalid-command
                       :operation op
                       :expected (get command-keys op)
                       :actual (set (keys command))})))
    (flow/compile-workflow
     {:cells {:command op}
      :edges []
      :start :command
      :input command
      :resources {}}
     command-specs
     command-handlers)))

(defn- sorted-node-values [snapshot field]
  (into (sorted-map)
        (map (fn [[node-id state]] [node-id (vec (get state field []))])
             (:nodes snapshot))))

(defn- mailbox-counts [snapshot]
  (into (sorted-map)
        (map (fn [[node-id mailbox]] [node-id (:count mailbox)])
             (:mailboxes snapshot))))

(defn- pending-counts [snapshot]
  (into (sorted-map)
        (map (fn [[node-id state]] [node-id (count (:pending state))])
             (:nodes snapshot))))

(defn- connection-values [snapshot]
  (mapv (fn [[nodes regime]] {:nodes nodes :regime regime})
        (sort-by (comp pr-str key) (:connections snapshot))))

(defn- read-messages [snapshot]
  (or (->> (get-in snapshot [:client-replies :tail])
           reverse
           (some (fn [reply]
                   (when (= "read_ok" (get-in reply [:body :type]))
                     (vec (get-in reply [:body :messages]))))))
      []))

(defn receipt-observation
  "Projects a completed retained value onto a closed workflow boundary."
  [value]
  (let [direct? (= :jolt.sim/maelstrom-broadcast-live (:kind value))
        snapshot (if direct? value (:snapshot value))
        operation (if direct? :inspect (:operation value))]
    {:operation operation
     :status (:status snapshot)
     :regime-revision (:regime-revision snapshot)
     :connections (connection-values snapshot)
     :ready-mailboxes (vec (:ready-mailboxes snapshot))
     :mailbox-counts (mailbox-counts snapshot)
     :messages (sorted-node-values snapshot :messages)
     :pending-counts (pending-counts snapshot)
     :dropped-total (get-in snapshot [:drops :dropped-total])
     :client-reply-count (get-in snapshot [:client-replies :count])
     :read-messages (read-messages snapshot)}))

(def receipt-observation-schema
  [:map {:closed true}
   [:operation
    [:enum :inspect :bootstrap :step :set-connection-regime
     :heal :retry :read :stop]]
   [:status [:enum :created :running :stopped]]
   [:regime-revision [:int {:min 0}]]
   [:connections
    [:vector
     [:map {:closed true}
      [:nodes [:vector {:min 2 :max 2} :string]]
      [:regime [:enum :normal :drop]]]]]
   [:ready-mailboxes [:vector :string]]
   [:mailbox-counts [:map-of :string [:int {:min 0}]]]
   [:messages [:map-of :string [:vector :int]]]
   [:pending-counts [:map-of :string [:int {:min 0}]]]
   [:dropped-total [:int {:min 0}]]
   [:client-reply-count [:int {:min 0}]]
   [:read-messages [:vector :int]]])

(def receipt-specs
  {:observe-receipt
   {:handler :observe-receipt
    :schema {:input receipt-observation-schema
             :output [:map {:closed true} [:observed :boolean]]}}})

(def receipt-handlers
  {:observe-receipt
   (fn [_ state _]
     {:state (inc (or state 0))
      :data {:observed true}})})

(defn receipt-flow
  "Compiles the pure continuation for one completed retained value."
  [value]
  (flow/compile-workflow
   {:cells {:observe :observe-receipt}
    :edges []
    :start :observe
    :input (receipt-observation value)
    :resources {}}
   receipt-specs
   receipt-handlers))

(defn observe
  "Runs the pure receipt continuation and returns its accumulated result."
  [value]
  (get-in (kernel/run (receipt-flow value)) [:tasks 0 :result]))

;; ---- generic CommandCellSession adapter -----------------------------------

(def ^:private command-cell-ids
  {:inspect :example.broadcast/inspect
   :bootstrap :example.broadcast/bootstrap
   :step :example.broadcast/step
   :set-connection-regime :example.broadcast/set-connection-regime
   :heal :example.broadcast/heal
   :retry :example.broadcast/retry
   :read :example.broadcast/read
   :stop :example.broadcast/stop})

(def command-cell-descriptors
  "Data-only catalog entries for the eight exact Broadcast commands."
  (into {}
        (map (fn [[operation cell-id]]
               [cell-id
                {:effect-kind effect-kind
                 :input-schema
                 (get-in command-specs [operation :schema :input])
                 :output-schema receipt-observation-schema
                 :projector :example.broadcast/receipt-observation
                 ;; Existing topology presenters consume the full tagged
                 ;; retained value, not this bounded receipt projection.
                 :suggested-kind nil}]))
        command-cell-ids))

(defn start-command-cell-session
  "Starts the generic one-active-cell owner over one borrowed Broadcast worker.

  Config is exact. `:worker` is the existing closed flow-effect worker service;
  `:workbench` is a caller-owned WorkbenchSession shared with Ripple or a REPL.
  This factory starts and terminates neither capability."
  [{:keys [worker workbench evidence-stream-id] :as config}]
  (when-not (and (map? config)
                 (= #{:worker :workbench :evidence-stream-id}
                    (set (keys config))))
    (throw (ex-info "Broadcast command-cell config has the wrong shape"
                    {:type ::invalid-command-cell-config})))
  (let [cell-ids (vals command-cell-ids)]
    (command-cell-session/start
     {:evidence-stream-id evidence-stream-id
      :descriptors command-cell-descriptors
      :trusted
      {:compilers (zipmap cell-ids (repeat command-flow))
       :workers (zipmap cell-ids (repeat worker))
       :projectors
       {:example.broadcast/receipt-observation receipt-observation}
       :workbench (command-cell-session/workbench-service workbench)}})))
