(ns jolt.sim.trace-index
  "Pure deterministic bounded navigation indexes over validated jolt-sim v1
  trace events.

  Every function here is a pure total function of already-validated events
  (or, for monitor-flagged targets, already-validated decision indices). It
  never consults the clock, randomness, mutable state, or untrusted code, and
  it never evaluates document data. Anchors are stable, script-free fragment
  targets so a static report embedded under a no-script content security
  policy -- such as Ripple's bare-sandbox iframe -- stays fully navigable.

  These indexes are deliberately bounded and semantic -- positions, tag, task,
  canonical site, virtual time, and terminal/failure positions -- rather than a
  general query language. Callers compose them with a renderer; this namespace
  emits only ordinary ordered data and performs no rendering or I/O."
  (:require [jolt.sim.trace :as trace]))

(def ^:private terminal-tags
  "Every event tag that ends a run. Mirrors the report's terminal set so the
  terminal index and the rendered outcome agree."
  #{:run/completed :run/failed :run/deadlock :run/step-limit})

(def event-anchor-prefix
  "Lowercase ASCII prefix shared by every event fragment id."
  "evt")

(defn event-anchor
  "Returns the stable fragment id for the event at `index` (for example
  `evt-3`). The id uses only lowercase ASCII letters, digits, and a hyphen so
  it is a valid HTML id and a fragment that needs no percent-encoding."
  [index]
  (str event-anchor-prefix "-" index))

(defn event-anchor-href
  "Returns the same-document fragment href (`#evt-3`) for the event at
  `index`."
  [index]
  (str "#" (event-anchor index)))

(def nav-anchor
  "Stable fragment id for the quick-navigation section of a report."
  "nav")
(def nav-anchor-href (str "#" nav-anchor))

(def tag-index-anchor
  "Stable fragment id for the by-tag index section of a report."
  "idx-tag")
(def task-index-anchor
  "Stable fragment id for the by-task index section of a report."
  "idx-task")
(def site-index-anchor
  "Stable fragment id for the by-site index section of a report."
  "idx-site")
(def time-index-anchor
  "Stable fragment id for the by-virtual-time index section of a report."
  "idx-time")

(defn- keyword-text [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn event-task
  "Returns the ordinary task id an event is about, or nil when the event has
  no single task. Mirrors the presentation-layer projection so the by-task
  index agrees with the rendered Task column."
  [event]
  (let [tag (first event)]
    (case tag
      :schedule/choose (nth event 4)
      (:task/transition :run/failed) (nth event 3)
      nil)))

(defn event-virtual-time
  "Returns the integer virtual time at which an event occurs, or nil for the
  initial event (which carries no clock coordinate). A `:time/advance` event
  is indexed at the time it begins advancing from."
  [event]
  (when-not (= :run/initial (first event))
    (nth event 2 nil)))

(defn- event-site-key
  "Returns a collision-free grouping key and readable projection for a
  task/transition site, or nil for any other event.

  The canonical trace value remains the identity. Grouping by only its
  restored, readable EDN would merge distinct values whose readable forms
  happen to collide, such as a byte array and an ordinary vector spelling its
  tagged representation."
  [event]
  (when (= :task/transition (first event))
    (let [canonical-site (nth event 5)]
      {:canonical canonical-site
       :canonical-edn (trace/canonical-edn canonical-site)
       :display-edn
       (trace/canonical-edn (trace/restore-value canonical-site))})))

(defn positions
  "Returns one navigation map per event position, in trace order.

  Each map carries the event's stable anchor and href plus first/prev/next/
  last anchors. `prev-*` and `next-*` are nil at the boundaries so a renderer
  can show disabled controls without inventing targets. `position` is the
  1-based ordinal and `total` is the event count."
  [event-count]
  (let [n event-count
        last-index (max 0 (dec n))]
    (mapv (fn [index]
            {:index index
             :anchor (event-anchor index)
             :href (event-anchor-href index)
             :first-index 0
             :first-href (event-anchor-href 0)
             :last-index last-index
             :last-href (event-anchor-href last-index)
             :prev-index (when (pos? index) (dec index))
             :prev-href (when (pos? index) (event-anchor-href (dec index)))
             :next-index (when (< index last-index) (inc index))
             :next-href (when (< index last-index) (event-anchor-href (inc index)))
             :position (inc index)
             :total n})
          (range n))))

(defn- trace-order-groups
  "Groups `[index event]` members by `(key-fn event)`, preserving trace order
  within each group, then orders groups by `order-fn` applied to the key.
  Members whose key is nil are dropped. Returns a vector of `[key members]`
  tuples where each member is `[index event]`."
  [events key-fn order-fn]
  (let [grouped
        (reduce
         (fn [acc [index event]]
           (let [k (key-fn event)]
             (if (nil? k)
               acc
               (update acc k (fn [v] (conj (or v []) [index event]))))))
         {}
         (map-indexed vector events))]
    (->> grouped
         (sort-by (fn [[k _]] (order-fn k)))
         (mapv (fn [[k members]] [k (vec members)])))))

(defn- link-row
  "One script-free navigable link toward an event."
  [index event]
  {:index index
   :tag (keyword-text (first event))
   :href (event-anchor-href index)})

(defn- group-row
  "Builds one index group row from a `[key members]` tuple."
  [key-text key-edn members]
  {:key-text key-text
   :key-edn key-edn
   :count (count members)
   :events (mapv (fn [[index event]] (link-row index event)) members)})

(defn tag-groups
  "Returns one row per event tag, ordered by tag text. Each row carries the
  tag's display text, its EDN label, its count, and trace-ordered links to
  every matching event."
  [events]
  (mapv (fn [[tag members]]
          (let [tag-text (keyword-text tag)]
            (group-row tag-text (str ":" tag-text) members)))
        (trace-order-groups events first keyword-text)))

(defn task-groups
  "Returns one row per task id that appears in a choice, transition, or
  failure event, ordered by task id. Events without a single task are absent."
  [events]
  (mapv (fn [[task-id members]]
          (let [text (str task-id)]
            (group-row text text members)))
        (trace-order-groups events event-task identity)))

(defn site-groups
  "Returns one row per canonical task/transition site, ordered by the site's
  canonical EDN. Only `:task/transition` events carry a site."
  [events]
  (mapv (fn [[site-key members]]
          (assoc (group-row (:display-edn site-key)
                            (:display-edn site-key)
                            members)
                 :canonical-key-edn (:canonical-edn site-key)))
        (trace-order-groups events event-site-key :canonical-edn)))

(defn time-groups
  "Returns one row per virtual time at which one or more events occur, ordered
  numerically. The initial event (no clock coordinate) is absent."
  [events]
  (mapv (fn [[time members]]
          (let [text (str time)]
            (group-row text text members)))
        (trace-order-groups events event-virtual-time identity)))

(defn- target-row
  [index event]
  {:index index
   :tag (keyword-text (first event))
   :href (event-anchor-href index)})

(defn terminal-targets
  "Trace-ordered links to every terminal event (completed, failed, deadlock,
  or step-limit)."
  [events]
  (mapv (fn [[index event]] (target-row index event))
        (filter (fn [[_ event]] (contains? terminal-tags (first event)))
                (map-indexed vector events))))

(defn failure-targets
  "Trace-ordered links to every `:run/failed` event."
  [events]
  (mapv (fn [[index event]] (target-row index event))
        (filter (fn [[_ event]] (= :run/failed (first event)))
                (map-indexed vector events))))

(defn monitor-targets
  "Trace-ordered, de-duplicated links for every non-nil validated monitor
  decision index. Duplicate indices collapse to one link so a monitor that
  re-flags the same position produces one navigation target."
  [indices]
  (mapv (fn [index] {:index index :href (event-anchor-href index)})
        (sort (distinct (filter some? indices)))))
