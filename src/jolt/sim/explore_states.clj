(ns jolt.sim.explore-states
  "Deterministic BFS over the cooperative kernel's explicit state space.

  Explores the finite transition system exposed by `jolt.sim.kernel`'s machine
  API with full enabled-action branching and canonical-state deduplication. It
  performs no partial-order or symmetry reduction: every enabled action of each
  admitted canonical state is considered, while alternate paths to the same
  budget-bearing state are deliberately collapsed. The explorer is pure
  Clojure with no host, ABI, controller, or runtime-scheduler dependency, so it
  runs unchanged on any image.

  The `:completed` status means the reachable graph was exhausted within the
  positive finite `:max-states` cap and the inherited `:max-steps` bound: every
  reachable projection was visited and no invariant fired. `:state-limit` means
  the cap was reached first (more distinct projections were reachable than the
  cap allowed). `:violation` means the invariant returned evidence for some
  projection and the explorer reports the first, shortest, stable witness with a
  replayable action path. Replaying the path is `(reduce kernel/machine-apply
  (kernel/machine sim) path)`; it is not a `kernel/replay` of an event trace.

  That bounded completeness claim requires deterministic, side-effect-free step
  and invariant functions over value-semantic state and a fixed transition
  relation. Machine branches isolate mutable byte-array leaves, but closed-over
  mutation, entropy, host clocks, I/O, or other effects invalidate the claim.
  This is cooperative-state reachability, not model checking of arbitrary Jolt
  code."
  (:require [jolt.sim.kernel :as kernel]
            [jolt.sim.trace :as trace]))

(defn- invalid-config
  "Builds the canonical malformed-config ex-info. Every rejection carries the
  :type, a stable :reason keyword, and the offending key/value evidence."
  [reason data]
  (ex-info
   (str "explore-states rejected a malformed configuration: " (name reason))
   (merge {:type ::invalid-config :reason reason} data)))

(defn- check-config
  "Validates the explorer configuration and returns the validated triple
  `[sim max-states invariant]. Throws ::invalid-config for any non-map,
  unknown key, missing key, non-positive-integer :max-states, malformed :sim,
  or non-function :invariant."
  [config]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map {:value config})))
  (let [allowed #{:sim :max-states :invariant}
        unknown (seq (sort-by pr-str (remove allowed (keys config))))]
    (when unknown
      (throw (invalid-config :unknown-key {:keys (vec unknown)})))
    (when-not (contains? config :sim)
      (throw (invalid-config :missing-key {:key :sim})))
    (when-not (map? (:sim config))
      (throw (invalid-config :sim-not-a-map {:value (:sim config)})))
    (when-not (contains? config :max-states)
      (throw (invalid-config :missing-key {:key :max-states})))
    (let [max-states (:max-states config)]
      (when-not (and (integer? max-states) (pos? max-states))
        (throw (invalid-config :not-a-positive-integer
                               {:key :max-states :value max-states}))))
    (let [invariant (:invariant config)
          has-invariant (contains? config :invariant)]
      (when (and has-invariant (some? invariant) (not (fn? invariant)))
        (throw (invalid-config :invariant-not-a-fn {:value invariant})))
      [(:sim config) (:max-states config) (when (fn? invariant) invariant)])))

(defn- invariant-evidence [invariant context]
  (when invariant
    (when-let [evidence (invariant context)]
      (trace/canonical-value evidence [:invariant-evidence]))))

(defn- count-terminal
  "Returns `terminals` with the count for `status` incremented, or unchanged
  when `status` is nil (a non-terminal projection)."
  [terminals status]
  (if status
    (assoc terminals status (inc (get terminals status 0)))
    terminals))

(defn- expand-node
  "Expands every enabled action of one dequeued `[machine path]` node.

  Walks `(machine-actions machine)` in the kernel's stable action order,
  enqueueing each newly discovered successor. Returns either `{:result m}` for
  a terminal outcome (invariant violation or state-limit) or
  `{:continue [queue visited terminals]}` carrying the updated accumulators for
  the outer loop's next node. Because `visited` already deduplicates
  projections, each distinct terminal projection is counted exactly once."
  [machine path invariant max-states queue visited terminals]
  (loop [actions (kernel/machine-actions machine)
         queue queue
         visited visited
         terminals terminals]
    (if (seq actions)
      (let [action (first actions)
            child (kernel/machine-apply machine action)
            child-projection (kernel/machine-projection child)]
        (if (contains? visited child-projection)
          (recur (rest actions) queue visited terminals)
          (if (>= (count visited) max-states)
            {:result {:status :state-limit
                      :visited (count visited)
                      :terminals terminals}}
            (let [child-status (kernel/machine-status child)
                  visited (assoc visited child-projection nil)
                  terminals (count-terminal terminals child-status)
                  context {:projection child-projection
                           :status child-status}]
              (if-let [evidence (invariant-evidence invariant context)]
                {:result
                 {:status :violation
                  :visited (count visited)
                  :terminals (assoc terminals :violation 1)
                  :witness {:path (conj path action)
                            :projection child-projection
                            :status child-status
                            :evidence evidence}}}
                (let [queue (conj queue [child (conj path action)])]
                  (recur (rest actions) queue visited terminals)))))))
      {:continue [queue visited terminals]})))

(defn- explore
  "Pure BFS loop. See `explore-states` for the configuration contract."
  [sim max-states invariant]
  (let [start (kernel/machine sim)
        start-status (kernel/machine-status start)
        start-projection (kernel/machine-projection start)]
    (if-let [evidence (invariant-evidence
                       invariant
                       {:projection start-projection
                        :status start-status})]
      {:status :violation
       :visited 1
       :terminals (count-terminal {:violation 1} start-status)
       :witness {:path []
                 :projection start-projection
                 :status start-status
                 :evidence evidence}}
      (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [start []])
             visited {start-projection nil}
             terminals (count-terminal {} start-status)]
        (if (empty? queue)
          {:status :completed
           :visited (count visited)
           :terminals terminals}
          (let [[machine path] (peek queue)
                outcome (expand-node machine path invariant max-states
                                     (pop queue) visited terminals)]
            (if (:result outcome)
              (:result outcome)
              (let [[queue visited terminals] (:continue outcome)]
                (recur queue visited terminals)))))))))

(defn explore-states
  "Deterministic BFS over the cooperative kernel state graph.

  Options (a map, exactly these keys):

    :sim         the kernel config map `kernel/machine` accepts (required).
    :max-states  positive integer cap on distinct projections visited (required).
    :invariant   optional fn from `{:keys [projection status]}` to violation
                 evidence (truthy, canonicalizable), or nil/absent.

  Returns a map with `:status` (`:completed`, `:state-limit`, or `:violation`),
  `:visited` (distinct projections visited), `:terminals` (map of terminal
  status keyword to distinct terminal-projection count; a `:violation` entry
  counts the one violating projection), and, on `:violation`, `:witness` with
  the first shortest `:path`, its canonical `:projection`, `:status`, and the
  invariant's canonicalized `:evidence`.

  The witness `:path` is a vector of machine actions (`[:run task-id]` or
  `[:advance wake-at]`) replayable as `(reduce kernel/machine-apply
  (kernel/machine sim) path)`. The explorer never reports the opaque machine;
  its canonical projection includes the consumed/maximum step budget required
  to make terminal status and enabled actions part of state."
  [config]
  (let [[sim max-states invariant] (check-config config)]
    (explore sim max-states invariant)))
