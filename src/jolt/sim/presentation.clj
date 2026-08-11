(ns jolt.sim.presentation
  "Pure, explicit presentation dispatch for validated jolt-sim trace events.

  Registries are ordinary immutable maps supplied by trusted application or
  library code. They are never read from a trace document and presentation
  dispatch never resolves vars, loads namespaces, evaluates data, or emits
  HTML. Later registries win, so callers can compose defaults, libraries, and
  application overrides visibly at one call site.

  Every presenter returns a small data-only projection. Static reports,
  Ripple, REPL/tap consumers, and future native frontends can therefore share
  event semantics without sharing a rendering toolkit.

  A separate activity path projects the closed `jolt.sim.activity` v1 events
  (`[namespaced-keyword nil nil map]`) recovered from the opt-in worker
  lifecycle journal. Activity presentation dispatches only by exact event tag
  through its own registry: it never applies the trace presenter's positional,
  site, or operation dispatch, even to an event tagged `:task/transition`."
  (:require [jolt.sim.trace :as trace]))

(def invalid-registry ::invalid-registry)
(def invalid-presentation ::invalid-presentation)
(def invalid-value-registry ::invalid-value-registry)
(def invalid-kind-registry ::invalid-kind-registry)
(def invalid-value-presentation ::invalid-value-presentation)

(def ^:private transition-ops
  #{:yield :block :sleep :complete :fail})

(def ^:private entry-keys #{:kind :present})
(def ^:private projection-keys #{:summary :fields})
(def ^:private field-keys #{:label :value})

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- keyword-text [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- registry-key? [key]
  (or
   (keyword? key)
   (and (vector? key)
        (= 3 (count key))
        (= :task/transition (nth key 0))
        (case (nth key 1)
          :site (trace/canonical-form? (nth key 2))
          :op (contains? transition-ops (nth key 2))
          false))))

(defn- registry-error! [reason detail]
  (throw
   (ex-info
    "Invalid jolt-sim presentation registry"
    {:type invalid-registry :reason reason :detail detail})))

(defn- validate-entry! [key entry]
  (when-not (registry-key? key)
    (registry-error! :invalid-key key))
  (when-not (and (map? entry) (= entry-keys (set (keys entry))))
    (registry-error! :invalid-entry-shape key))
  (when-not (namespaced-keyword? (:kind entry))
    (registry-error! :invalid-kind {:key key :kind (:kind entry)}))
  (when-not (fn? (:present entry))
    (registry-error! :invalid-presenter key))
  entry)

(defn validate-registry!
  "Returns `value` when it is an exact presentation registry, else throws.

  Ordinary keyword keys dispatch by event tag. Task transitions additionally
  accept `[:task/transition :op op]` and the value returned by `site-key`.
  Site keys use the trace's canonical representation, so ordinary keyword and
  structured sites are stable dispatch identities while mutable byte arrays
  can never become identity-based map keys."
  [value]
  (when-not (map? value)
    (registry-error! :not-a-map (str (class value))))
  (doseq [[key entry] value]
    (validate-entry! key entry))
  value)

(defn registry
  "Composes zero or more presentation registries; later entries win.

  A typical call is `(registry default-registry library-registry
  application-registry)`. Nil values are ignored so optional integrations do
  not need a special empty-map branch."
  [& registries]
  (reduce
   (fn [result value]
     (if (nil? value)
       result
       (merge result (validate-registry! value))))
   {}
   registries))

(defn site-key
  "Returns the stable registry key for one ordinary task-transition site.

  Callers should use this helper rather than constructing the canonical site
  representation themselves."
  [site]
  [:task/transition :site
   (trace/canonical-value site [:presentation-registry :site])])

(defn- field [label value]
  {:label label :value value})

(defn- restored [canonical]
  (trace/restore-value canonical))

(defn- initial-presentation [_]
  {:summary "Initial simulation state" :fields []})

(defn- choice-presentation [event]
  (let [enabled (nth event 3)
        chosen (nth event 4)]
    {:summary (str "Task " chosen " chosen from " (count enabled)
                   " runnable task" (when-not (= 1 (count enabled)) "s"))
     :fields [(field "Enabled tasks" enabled)
              (field "Chosen task" chosen)]}))

(defn- transition-presentation [event]
  (let [task (nth event 3)
        op (nth event 4)
        site (restored (nth event 5))
        wake (nth event 6)
        wake-at (nth event 7)]
    {:summary (str "Task " task " " (name op)
                   (when (some? site)
                     (str " at " (trace/canonical-edn site))))
     :fields (cond-> [(field "Operation" op)
                      (field "Site" site)]
               (seq wake) (conj (field "Wakes" wake))
               (some? wake-at) (conj (field "Wake time" wake-at)))}))

(defn- time-presentation [event]
  (let [from (nth event 2)
        to (nth event 3)
        awakened (nth event 4)]
    {:summary (str "Virtual time advanced from " from " to " to)
     :fields [(field "Awakened tasks" awakened)]}))

(defn- completed-presentation [_]
  {:summary "Run completed" :fields []})

(defn- failed-presentation [event]
  (let [task (nth event 3)
        error (restored (nth event 4))]
    {:summary (str "Task " task " failed")
     :fields [(field "Error" error)]}))

(defn- deadlock-presentation [event]
  (let [blocked (nth event 3)]
    {:summary (str "Run deadlocked with " (count blocked)
                   " blocked task" (when-not (= 1 (count blocked)) "s"))
     :fields [(field "Blocked tasks" blocked)]}))

(defn- step-limit-presentation [_]
  {:summary "Run reached its step limit" :fields []})

(def default-registry
  "Built-in presentations for every event in trace document version 1."
  {:run/initial
   {:kind :jolt.sim.kind/run-initial :present initial-presentation}
   :schedule/choose
   {:kind :jolt.sim.kind/schedule-choice :present choice-presentation}
   :task/transition
   {:kind :jolt.sim.kind/task-transition :present transition-presentation}
   :time/advance
   {:kind :jolt.sim.kind/time-advance :present time-presentation}
   :run/completed
   {:kind :jolt.sim.kind/run-completed :present completed-presentation}
   :run/failed
   {:kind :jolt.sim.kind/run-failed :present failed-presentation}
   :run/deadlock
   {:kind :jolt.sim.kind/run-deadlock :present deadlock-presentation}
   :run/step-limit
   {:kind :jolt.sim.kind/run-step-limit :present step-limit-presentation}})

(defn- event-step [tag event]
  (when-not (= :run/initial tag) (nth event 1)))

(defn- event-time [tag event]
  (case tag
    :time/advance (str (nth event 2) " -> " (nth event 3))
    :run/initial nil
    (nth event 2 nil)))

(defn- event-task [tag event]
  (case tag
    :schedule/choose (nth event 4)
    (:task/transition :run/failed) (nth event 3)
    nil))

(defn dispatch-keys
  "Returns the ordered registry lookup keys for one event.

  Task transitions prefer their canonical site, then their operation, then
  the ordinary event tag. Other events dispatch only by tag."
  [event]
  (let [tag (first event)]
    (if (= :task/transition tag)
      (let [canonical-site (nth event 5)
            op (nth event 4)]
        [[:task/transition :site canonical-site]
         [:task/transition :op op]
         :task/transition])
      [tag])))

(defn- selected-entry [registry event]
  (some (fn [key]
          (when-let [entry (get registry key)] [key entry]))
        (dispatch-keys event)))

(defn- validate-projection! [dispatch-key projection]
  (when-not (and (map? projection)
                 (= projection-keys (set (keys projection))))
    (throw
     (ex-info "Presentation function returned the wrong shape"
              {:type invalid-presentation
               :reason :invalid-projection-shape
               :dispatch-key dispatch-key})))
  (when-not (string? (:summary projection))
    (throw
     (ex-info "Presentation summary is not a string"
              {:type invalid-presentation
               :reason :invalid-summary
               :dispatch-key dispatch-key})))
  (when-not (and (vector? (:fields projection))
                 (every? #(and (map? %)
                               (= field-keys (set (keys %)))
                               (string? (:label %))
                               (contains? % :value))
                         (:fields projection)))
    (throw
     (ex-info "Presentation fields have the wrong shape"
              {:type invalid-presentation
               :reason :invalid-fields
               :dispatch-key dispatch-key})))
  (trace/canonical-value projection [:presentation dispatch-key])
  projection)

(defn- raw-projection [event]
  {:summary (str "Raw event " (keyword-text (first event))) :fields []})

(defn- present-event* [registry index event]
  (when-not (and (vector? event) (keyword? (first event)))
    (throw
     (ex-info "Cannot present a malformed event"
              {:type invalid-presentation :reason :invalid-event :index index})))
  (let [tag (first event)
        [dispatch-key entry] (selected-entry registry event)
        kind (if entry (:kind entry) :jolt.sim.kind/raw-event)
        projection
        (try
          ((if entry (:present entry) raw-projection) event)
          (catch :default error
            (throw
             (ex-info "Presentation function failed"
                      {:type invalid-presentation
                       :reason :presenter-threw
                       :dispatch-key dispatch-key}
                      error))))
        projection (validate-projection! dispatch-key projection)
        projection
        (update projection :fields
                (fn [fields]
                  (mapv #(assoc % :value-edn
                                (trace/canonical-edn (:value %)))
                        fields)))]
    (merge
     {:index index
      :tag (keyword-text tag)
      :kind kind
      :kind-name (keyword-text kind)
      :dispatch-key dispatch-key
      :dispatch-key-edn (trace/canonical-edn dispatch-key)
      :step (event-step tag event)
      :time (event-time tag event)
      :task (event-task tag event)
      :edn (trace/canonical-edn event)
      :has-fields (pos? (count (:fields projection)))}
     projection)))

(defn present-event
  "Projects one already-validated trace event to a data-only UI row.

  `registry` is trusted executable configuration. The event only chooses
  among functions already present in that map. A missing entry produces a raw
  data presentation, while malformed registries or presenter results fail
  closed. The complete canonical event EDN is always retained."
  [registry index event]
  (validate-registry! registry)
  (present-event* registry index event))

(defn event-presenter
  "Validates `registry` once and returns an incremental event projector.

  Use this for live Ripple/session tails so registry validation is independent
  of event count. The returned function accepts `[index event]`. Trusted
  custom presenters are contractually data-only and deterministic; functions
  that consult time, randomness, or mutable state can intentionally violate
  reproducible presentation and must not be used for forensic artifacts."
  [registry]
  (validate-registry! registry)
  (fn [index event] (present-event* registry index event)))

(defn events->rows
  "Projects validated events in trace order through one explicit registry."
  [registry events]
  (let [present (event-presenter registry)]
    (mapv (fn [index event] (present index event))
        (range) events)))

;; ---- arbitrary canonical value presentation ---------------------------------

(def ^:private value-entry-keys #{:kind :present})
(def ^:private kind-entry-keys #{:present})
(def ^:private value-projection-keys #{:summary :fields})
(def ^:private topology-projection-keys #{:summary :fields :graph})
(def ^:private value-field-keys #{:label :value})
(def ^:private graph-keys #{:directed? :nodes :edges})
(def ^:private graph-node-keys #{:id :label :status :fields})
(def ^:private graph-edge-keys
  #{:id :from :to :label :status :fields})
;; A node or edge may additionally declare an `:actions` vector of inert
;; command descriptors. Each action is exactly
;; `{:id string :label string :command canonical-value :enabled? boolean}`:
;; trusted presenters state what an attached application can be asked to do,
;; but the descriptor is data only. Presentation, server serialization, and
;; generic renderers never evaluate `:command`; it can only be echoed back,
;; unchanged, through an explicit user-initiated command channel.
(def ^:private graph-action-keys #{:id :label :command :enabled?})
(def ^:private graph-node-action-keys (conj graph-node-keys :actions))
(def ^:private graph-edge-action-keys (conj graph-edge-keys :actions))
(def ^:private maximum-value-summary-length 512)
(def ^:private maximum-value-label-length 128)
(def ^:private maximum-value-kind-length 128)
(def ^:private maximum-graph-id-length 128)
(def ^:private maximum-value-fields 64)
(def ^:private maximum-graph-nodes 256)
(def ^:private maximum-graph-edges 1024)
(def ^:private maximum-graph-actions 16)
;; Commands are inert data echoed through bounded command envelopes, so their
;; canonical EDN representation is itself bounded.
(def ^:private maximum-action-command-edn-length 4096)
(def ^:private maximum-value-presentation-bytes (* 256 1024))

(defn- value-registry-error! [reason detail]
  (throw (ex-info "Invalid jolt-sim value presentation registry"
                  {:type invalid-value-registry
                   :reason reason
                   :detail detail})))

(defn- value-presentation-error! [reason detail]
  (throw (ex-info "Invalid jolt-sim value presentation"
                  {:type invalid-value-presentation
                   :reason reason
                   :detail detail})))

(defn- kind-registry-error! [reason detail]
  (throw (ex-info "Invalid jolt-sim presentation-kind registry"
                  {:type invalid-kind-registry
                   :reason reason
                   :detail detail})))

(defn validate-kind-registry!
  "Validates an immutable trusted renderer registry keyed by output kind.

  Each exact entry is `{:present fn}`. Unlike a value registry, this registry
  does not advise from a source kind or choose an output kind. A caller has
  already selected the key, for example through a persisted workbench rule.
  No registry is loaded from EDN and no var is resolved by name."
  [value]
  (when-not (map? value)
    (kind-registry-error! :not-a-map (str (class value))))
  (doseq [[kind entry] value]
    (when-not (and (namespaced-keyword? kind)
                   (<= (count (keyword-text kind))
                       maximum-value-kind-length))
      (kind-registry-error! :invalid-kind kind))
    (when-not (and (map? entry)
                   (= kind-entry-keys (set (keys entry))))
      (kind-registry-error! :invalid-entry-shape kind))
    (when-not (fn? (:present entry))
      (kind-registry-error! :invalid-presenter kind)))
  value)

(defn kind-registry
  "Composes explicit presentation-kind registries; later entries win."
  [& registries]
  (reduce (fn [result candidate]
            (if (nil? candidate)
              result
              (merge result (validate-kind-registry! candidate))))
          {}
          registries))

(defn available-kinds
  "Returns the stable sorted kinds a trusted registry can render.

  Raw canonical EDN is always available even though it needs no executable
  registry entry."
  [registry]
  (validate-kind-registry! registry)
  (->> (conj (set (keys registry)) :jolt.sim.kind/raw-value)
       sort
       vec))

(defn validate-value-registry!
  "Validates an immutable trusted-code registry keyed by exact value kind.

  Keys and output kinds are namespaced keywords. Entries are exact
  `{:kind output-kind :present fn}` maps. This function never installs global
  state or resolves executable data."
  [value]
  (when-not (map? value)
    (value-registry-error! :not-a-map (str (class value))))
  (doseq [[source-kind entry] value]
    (when-not (and (namespaced-keyword? source-kind)
                   (<= (count (keyword-text source-kind))
                       maximum-value-kind-length))
      (value-registry-error! :invalid-source-kind source-kind))
    (when-not (and (map? entry)
                   (= value-entry-keys (set (keys entry))))
      (value-registry-error! :invalid-entry-shape source-kind))
    (when-not (and (namespaced-keyword? (:kind entry))
                   (<= (count (keyword-text (:kind entry)))
                       maximum-value-kind-length))
      (value-registry-error! :invalid-output-kind source-kind))
    (when-not (fn? (:present entry))
      (value-registry-error! :invalid-presenter source-kind)))
  value)

(defn value-registry
  "Composes explicit value registries; later exact-kind entries win."
  [& registries]
  (reduce (fn [result candidate]
            (if (nil? candidate)
              result
              (merge result (validate-value-registry! candidate))))
          {}
          registries))

(defn- bounded-string? [value maximum]
  (and (string? value) (<= (count value) maximum)))

(defn- exact-plain-map? [value expected-keys]
  (and (map? value)
       (nil? (meta value))
       (= expected-keys (set (keys value)))))

(defn- canonical-field [path field]
  (when-not (exact-plain-map? field value-field-keys)
    (value-presentation-error! :invalid-field-shape path))
  (when-not (bounded-string? (:label field) maximum-value-label-length)
    (value-presentation-error! :invalid-field-label path))
  {:label (:label field)
   ;; Store the canonical representation itself. This snapshots byte arrays and
   ;; excludes functions, handles, metadata, and other mutable/opaque leaves.
   :value (trace/canonical-value (:value field) (conj path :value))})

(defn- canonical-fields [path fields]
  (when-not (and (vector? fields)
                 (nil? (meta fields))
                 (<= (count fields) maximum-value-fields))
    (value-presentation-error! :invalid-fields path))
  (let [result (mapv #(canonical-field path %) fields)
        labels (mapv :label result)]
    (when-not (= labels (vec (sort labels)))
      (value-presentation-error! :unstable-field-order path))
    (when-not (= (count labels) (count (set labels)))
      (value-presentation-error! :duplicate-field-label path))
    result))

(defn- valid-status? [value]
  (or (nil? value)
      (and (namespaced-keyword? value)
           (<= (count (keyword-text value)) maximum-value-kind-length))))

(defn- validate-graph-id! [path value]
  (when-not (and (bounded-string? value maximum-graph-id-length)
                 (pos? (count value)))
    (value-presentation-error! :invalid-graph-id path))
  value)

(defn- canonical-action
  "Validates one inert action descriptor and stores its command in canonical
  form. The command is never evaluated here; canonicalization only snapshots
  mutable leaves and excludes functions, handles, metadata, and other opaque
  values, exactly like field values."
  [path action]
  (when-not (exact-plain-map? action graph-action-keys)
    (value-presentation-error! :invalid-action-shape path))
  (when-not (and (bounded-string? (:id action) maximum-graph-id-length)
                 (pos? (count (:id action))))
    (value-presentation-error! :invalid-action-id path))
  (when-not (and (bounded-string? (:label action) maximum-value-label-length)
                 (pos? (count (:label action))))
    (value-presentation-error! :invalid-action-label path))
  (when-not (boolean? (:enabled? action))
    (value-presentation-error! :invalid-action-enabled-flag path))
  (let [command (trace/canonical-value (:command action) (conj path :command))
        ;; Bound the exact tagged representation sent on the viewer wire, not
        ;; the usually smaller ordinary EDN obtained by restoring it.
        edn (trace/canonical-edn command)]
    (when (> (count edn) maximum-action-command-edn-length)
      (value-presentation-error! :invalid-action-command path))
    {:id (:id action)
     :label (:label action)
     :command command
     :enabled? (:enabled? action)}))

(defn- canonical-actions
  "Validates one entity's optional action vector. Action IDs are sorted and
  unique within the entity, so the canonical presentation is deterministic."
  [path actions]
  (when-not (and (vector? actions)
                 (nil? (meta actions))
                 (<= (count actions) maximum-graph-actions))
    (value-presentation-error! :invalid-actions path))
  (let [result (mapv (fn [ordinal action]
                       (canonical-action (conj path ordinal) action))
                     (range)
                     actions)
        ids (mapv :id result)]
    (when-not (= ids (vec (sort ids)))
      (value-presentation-error! :unstable-action-order path))
    (when-not (= (count ids) (count (set ids)))
      (value-presentation-error! :duplicate-action-id path))
    result))

(defn- canonical-node [node]
  (when-not (or (exact-plain-map? node graph-node-keys)
                (exact-plain-map? node graph-node-action-keys))
    (value-presentation-error! :invalid-node-shape nil))
  (validate-graph-id! [:graph :node :id] (:id node))
  (when-not (bounded-string? (:label node) maximum-value-label-length)
    (value-presentation-error! :invalid-node-label (:id node)))
  (when-not (valid-status? (:status node))
    (value-presentation-error! :invalid-node-status (:id node)))
  {:id (:id node)
   :label (:label node)
   :status (:status node)
   :fields (canonical-fields [:graph :node (:id node) :fields]
                             (:fields node))
   ;; The canonical shape is uniform: an entity that declares no actions
   ;; presents an explicitly empty vector.
   :actions (if (contains? node :actions)
              (canonical-actions [:graph :node (:id node) :actions]
                                 (:actions node))
              [])})

(defn- canonical-edge [edge]
  (when-not (or (exact-plain-map? edge graph-edge-keys)
                (exact-plain-map? edge graph-edge-action-keys))
    (value-presentation-error! :invalid-edge-shape nil))
  (doseq [key [:id :from :to]]
    (validate-graph-id! [:graph :edge key] (get edge key)))
  (when-not (bounded-string? (:label edge) maximum-value-label-length)
    (value-presentation-error! :invalid-edge-label (:id edge)))
  (when-not (valid-status? (:status edge))
    (value-presentation-error! :invalid-edge-status (:id edge)))
  {:id (:id edge)
   :from (:from edge)
   :to (:to edge)
   :label (:label edge)
   :status (:status edge)
   :fields (canonical-fields [:graph :edge (:id edge) :fields]
                             (:fields edge))
   :actions (if (contains? edge :actions)
              (canonical-actions [:graph :edge (:id edge) :actions]
                                 (:actions edge))
              [])})

(defn- canonical-graph [graph]
  (when-not (exact-plain-map? graph graph-keys)
    (value-presentation-error! :invalid-graph-shape nil))
  (when-not (boolean? (:directed? graph))
    (value-presentation-error! :invalid-directed-flag nil))
  (when-not (and (vector? (:nodes graph))
                 (nil? (meta (:nodes graph)))
                 (<= (count (:nodes graph)) maximum-graph-nodes))
    (value-presentation-error! :invalid-nodes nil))
  (when-not (and (vector? (:edges graph))
                 (nil? (meta (:edges graph)))
                 (<= (count (:edges graph)) maximum-graph-edges))
    (value-presentation-error! :invalid-edges nil))
  (let [nodes (mapv canonical-node (:nodes graph))
        edges (mapv canonical-edge (:edges graph))
        node-ids (mapv :id nodes)
        edge-ids (mapv :id edges)
        node-set (set node-ids)]
    (when-not (= node-ids (vec (sort node-ids)))
      (value-presentation-error! :unstable-node-order node-ids))
    (when-not (= edge-ids (vec (sort edge-ids)))
      (value-presentation-error! :unstable-edge-order edge-ids))
    (when-not (= (count node-ids) (count node-set))
      (value-presentation-error! :duplicate-node-id node-ids))
    (when-not (= (count edge-ids) (count (set edge-ids)))
      (value-presentation-error! :duplicate-edge-id edge-ids))
    (doseq [{:keys [id from to]} edges]
      (when-not (and (contains? node-set from) (contains? node-set to))
        (value-presentation-error! :dangling-edge id))
      (when (and (not (:directed? graph)) (pos? (compare from to)))
        (value-presentation-error! :unstable-undirected-edge id)))
    {:directed? (:directed? graph) :nodes nodes :edges edges}))

(defn- source-kind [value]
  (let [candidate (when (map? value) (:kind value))]
    (when (namespaced-keyword? candidate) candidate)))

(defn- raw-value-projection [kind]
  {:summary (if kind
              (str "Raw value " (keyword-text kind))
              "Raw canonical value")
   :fields []})

(defn- present-canonical-as* [source-canonical output-kind presenter]
  (let [source (trace/restore-value source-canonical)
        source-edn (trace/canonical-edn source)
        source-kind (source-kind source)
        projection
        (try
          (presenter source)
          (catch :default error
            (throw (ex-info "Value presentation function failed"
                            {:type invalid-value-presentation
                             :reason :presenter-threw
                             :source-kind source-kind}
                            error))))
        keys (when (map? projection) (set (keys projection)))
        topology? (= topology-projection-keys keys)]
    (when-not (or (= value-projection-keys keys) topology?)
      (value-presentation-error! :invalid-projection-shape keys))
    (when-not (bounded-string? (:summary projection)
                               maximum-value-summary-length)
      (value-presentation-error! :invalid-summary source-kind))
    (let [result
          (cond-> {:version 1
                   :kind output-kind
                   :source-kind source-kind
                   :summary (:summary projection)
                   :fields (canonical-fields [:value-presentation :fields]
                                             (:fields projection))
                   :source-edn source-edn}
            topology? (assoc :graph (canonical-graph (:graph projection))))
          bytes (count (.getBytes (trace/canonical-edn result) "UTF-8"))]
      (when (> bytes maximum-value-presentation-bytes)
        (value-presentation-error! :presentation-too-large
                                   {:bytes bytes
                                    :maximum maximum-value-presentation-bytes}))
      result)))

(defn- present-value-as* [value output-kind presenter]
  (present-canonical-as*
   (trace/canonical-value value [:value-presentation :source])
   output-kind
   presenter))

(defn- present-value* [registry value]
  (let [source-canonical
        (trace/canonical-value value [:value-presentation :source])
        kind (source-kind (trace/restore-value source-canonical))
        entry (get registry kind)]
    (present-canonical-as*
     source-canonical
     (if entry (:kind entry) :jolt.sim.kind/raw-value)
     (if entry (:present entry) (fn [_] (raw-value-projection kind))))))

(defn value-presenter
  "Validates one registry and returns a pure projector for arbitrary values."
  [registry]
  (validate-value-registry! registry)
  (fn [value] (present-value* registry value)))

(defn present-value
  "Snapshots and presents one canonicalizable value by exact top-level kind.

  Unknown or kindless values receive the bounded raw view. Presenter failures
  and malformed/overbound outputs fail closed without modifying the source."
  [registry value]
  ((value-presenter registry) value))

(defn present-as-kind
  "Presents one arbitrary value through an explicitly selected output kind.

  `registry` is trusted executable configuration keyed by presentation kind.
  The raw canonical kind is built in. Every other kind must have an exact
  registry entry; unknown persisted kinds fail closed while callers retain the
  immutable source value for raw fallback or later registry installation."
  [registry kind value]
  (validate-kind-registry! registry)
  (when-not (namespaced-keyword? kind)
    (kind-registry-error! :invalid-kind kind))
  (if (= :jolt.sim.kind/raw-value kind)
    (present-value-as*
     value kind (fn [source] (raw-value-projection (source-kind source))))
    (if-let [entry (get registry kind)]
      (present-value-as* value kind (:present entry))
      (kind-registry-error! :unknown-kind kind))))

;; ---- activity event presentation ---------------------------------------------

(defn- activity-event-shape?
  "The exact closed `jolt.sim.activity` v1 event shape: a four-element vector
  with a namespaced keyword tag, both reserved positions nil, and a plain map
  payload. Anything else is rejected before dispatch."
  [event]
  (and (vector? event)
       (= 4 (count event))
       (namespaced-keyword? (nth event 0))
       (nil? (nth event 1))
       (nil? (nth event 2))
       (map? (nth event 3))))

(defn- validate-activity-entry! [key entry]
  (when-not (namespaced-keyword? key)
    (registry-error! :invalid-activity-key key))
  (when-not (and (map? entry) (= entry-keys (set (keys entry))))
    (registry-error! :invalid-entry-shape key))
  (when-not (namespaced-keyword? (:kind entry))
    (registry-error! :invalid-kind {:key key :kind (:kind entry)}))
  (when-not (fn? (:present entry))
    (registry-error! :invalid-presenter key))
  entry)

(defn validate-activity-registry!
  "Returns `value` when it is an exact activity presentation registry, else
  throws.

  Activity events dispatch only by their exact event tag, so registry keys
  are namespaced keywords and nothing else. The trace registry's structured
  `[:task/transition :site ...]` and `[:task/transition :op ...]` keys are
  rejected here rather than silently never matching."
  [value]
  (when-not (map? value)
    (registry-error! :not-a-map (str (class value))))
  (doseq [[key entry] value]
    (validate-activity-entry! key entry))
  value)

(defn activity-registry
  "Composes zero or more activity presentation registries; later entries win.

  A typical call is `(activity-registry default-activity-registry
  library-registry application-registry)`. Nil values are ignored so optional
  integrations do not need a special empty-map branch."
  [& registries]
  (reduce
   (fn [result value]
     (if (nil? value)
       result
       (merge result (validate-activity-registry! value))))
   {}
   registries))

(defn- scenario-lifecycle-presentation [verb]
  (fn [event]
    (let [scenario (:scenario (nth event 3))]
      {:summary (str "Scenario " scenario " " verb)
       :fields [(field "Scenario" scenario)]})))

(def default-activity-registry
  "Built-in presentations for the scenario lifecycle events emitted under the
  opt-in `jolt.sim.activity` worker journal. Activity tags outside this small
  set fall back to the same raw data presentation as unknown trace tags."
  {:jolt.sim.explore/scenario-started
   {:kind :jolt.sim.kind/scenario-started
    :present (scenario-lifecycle-presentation "started")}
   :jolt.sim.explore/scenario-completed
   {:kind :jolt.sim.kind/scenario-completed
    :present (scenario-lifecycle-presentation "completed")}
   :jolt.sim.explore/scenario-failed
   {:kind :jolt.sim.kind/scenario-failed
    :present (scenario-lifecycle-presentation "failed")}})

(defn- present-activity-event* [registry index event]
  (when-not (activity-event-shape? event)
    (throw
     (ex-info "Cannot present a malformed activity event"
              {:type invalid-presentation
               :reason :invalid-activity-event
               :index index})))
  (let [tag (nth event 0)
        entry (get registry tag)
        kind (if entry (:kind entry) :jolt.sim.kind/raw-event)
        projection
        (try
          ((if entry (:present entry) raw-projection) event)
          (catch :default error
            (throw
             (ex-info "Presentation function failed"
                      {:type invalid-presentation
                       :reason :presenter-threw
                       :dispatch-key tag}
                      error))))
        projection (validate-projection! tag projection)
        projection
        (update projection :fields
                (fn [fields]
                  (mapv #(assoc % :value-edn
                                (trace/canonical-edn (:value %)))
                        fields)))]
    (merge
     {:index index
      :tag (keyword-text tag)
      :kind kind
      :kind-name (keyword-text kind)
      :dispatch-key tag
      :dispatch-key-edn (trace/canonical-edn tag)
      :step nil
      :time nil
      :task nil
      :edn (trace/canonical-edn event)
      :has-fields (pos? (count (:fields projection)))}
     projection)))

(defn activity-event-presenter
  "Validates `registry` once and returns an incremental activity-event
  projector accepting `[index event]`.

  Activity events are the closed `jolt.sim.activity` v1 shape: exactly
  `[namespaced-keyword nil nil map]`. Dispatch is by exact event tag only;
  this projector never applies the trace presenter's positional, site, or
  operation dispatch, even to an event tagged `:task/transition`. A missing
  entry produces the raw data presentation, while malformed events,
  registries, or presenter results fail closed. Rows carry the same closed
  key set as trace rows with `:step`, `:time`, and `:task` always nil and the
  complete canonical event EDN retained."
  [registry]
  (validate-activity-registry! registry)
  (fn [index event] (present-activity-event* registry index event)))
