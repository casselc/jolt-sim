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
