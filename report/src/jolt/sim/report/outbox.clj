(ns jolt.sim.report.outbox
  "Pure presentation projection for the canonical outbox scenario family.

  This namespace does not run application code, monitors, or simulator logic.
  It recognizes only the four scenario symbols owned by the current canonical
  outbox Case/Outcome adapter and selects evidence already present in a
  completed result. Missing optional evidence remains missing; no causal fact
  or success verdict is inferred from another field."
  (:require [jolt.sim.trace :as trace]))

(def ^:private scenario-kinds
  {'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities
   :ordinary

   'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset
   :retry

   'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-cancel-before-ack-with-capacities
   :cancellation

   'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-terminal-boundary
   :terminal})

(def ^:private kind-titles
  {:ordinary "Ordinary close/reopen delivery"
   :retry "Receive-reset retry"
   :cancellation "Cancel before acknowledgement"
   :terminal "Deadline or cancellation boundary"})

(def ^:private journey-paths
  {:ordinary
   [["Recorded command" [:application :command]]
    ["Recorded pending state" [:application :pending-state]]
    ["Recorded delivery exchange" [:application :delivery]]
    ["Recorded durable marking" [:application :marking]]
    ["Recorded final store state" [:application :store-state]]
    ["Recorded close/reopen persistence" [:persistence]]]

   :retry
   [["Recorded command" [:application :command]]
    ["Recorded pending state" [:application :pending-state]]
    ["Recorded first attempt" [:application :retry :attempt-1]]
    ["Recorded retry delivery" [:application :retry :delivery]]
    ["Recorded durable marking" [:application :marking]]
    ["Recorded final store state" [:application :store-state]]]

   :cancellation
   [["Recorded command" [:application :command]]
    ["Recorded pending state" [:application :pending-state]]
    ["Recorded cancellation exchange" [:application :cancel]]
    ["Recorded final store state" [:application :store-state]]]

   :terminal
   [["Recorded terminal action" [:terminal]]
    ["Recorded semantic boundaries" [:boundary-log]]
    ["Recorded virtual clock" [:clock]]
    ["Recorded application evidence" [:application]]
    ["Recorded durable outbox state" [:sqlite :outbox]]
    ["Recorded cleanup evidence" [:cleanup]]]})

(defn- path-value
  "Returns [present? value], preserving a present nil at any selected path."
  [value path]
  (loop [current value
         remaining path]
    (if-let [k (first remaining)]
      (if (and (map? current) (contains? current k))
        (recur (get current k) (next remaining))
        [false nil])
      [true current])))

(defn project
  "Returns a deterministic, evidence-only journey projection or nil.

  `scenario` is the restored Case scenario symbol and `result` is the restored
  completed result. The projection is available only for the exact canonical
  outbox scenario family and map results. Each step names its literal source
  path and carries canonical EDN of the value recorded there."
  [scenario result]
  (when-let [kind (get scenario-kinds scenario)]
    (when (map? result)
      (let [steps
            (into []
                  (keep
                   (fn [[label path]]
                     (let [[present? value] (path-value result path)]
                       (when present?
                         {:label label
                          :path-edn (trace/canonical-edn path)
                          :evidence-edn (trace/canonical-edn value)}))))
                  (get journey-paths kind))]
        {:kind kind
         :kind-name (name kind)
         :title (get kind-titles kind)
         :steps steps
         :has-steps (pos? (count steps))}))))
