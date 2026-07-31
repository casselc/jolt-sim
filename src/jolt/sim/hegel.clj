(ns jolt.sim.hegel
  "Optional adapter that connects an ordered domain of exact future schedules to
  Hegel's generator/shrinker and the unchanged jolt.sim.process-explorer
  supervisor.

  It performs no selection of its own. The schedule domain is validated
  synchronously, handed to hegel.generator as a sampled generator, and drawn
  once per property case, so selection and shrinking stay engine-owned. The
  drawn schedule then runs through jolt.sim.process-explorer/run-schedule
  exactly as an explicit :schedule would, and a non-:completed outcome fails
  fast with a bounded typed error.

  hegel.core and hegel.generator are optional consumer dependencies. This
  repository supplies them under :hegel-explore-test, while the ordinary
  compatibility suite does not load this namespace."
  (:require [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.process-explorer :as process-explorer]))

(defn- invalid-config [reason data]
  (ex-info
   (str "jolt.sim.hegel rejected a malformed schedule domain: " (name reason))
   (merge {:type ::invalid-config :reason reason} data)))

(defn- validate-schedule-domain!
  "Throws ::invalid-config unless `schedules` is a nonempty vector of unique,
  same-size exact future permutations. Returns `schedules` unchanged so the
  input order is preserved for the engine-owned shrinker."
  [schedules]
  (when-not (vector? schedules)
    (throw (invalid-config :schedules-not-a-vector {:value schedules})))
  (when-not (seq schedules)
    (throw (invalid-config :empty-schedules {:value schedules})))
  (doseq [schedule schedules]
    (when-not (future-schedule/valid-schedule? schedule)
      (throw (invalid-config :invalid-schedule {:value schedule}))))
  (when-not (= (count schedules) (count (set schedules)))
    (throw (invalid-config :duplicate-schedule {:value schedules})))
  (let [sizes (set (map count schedules))]
    (when-not (= 1 (count sizes))
      (throw (invalid-config :inconsistent-schedule-size
                             {:sizes (vec (sort sizes))}))))
  schedules)

(defn schedule-generator
  "Returns a Hegel generator that selects one schedule from `schedules`, an
  ordered nonempty vector of unique, same-size exact permutation schedules.

  The domain is validated synchronously (throwing :jolt.sim.hegel/invalid-config
  with a stable :reason) and then handed unchanged to g/sampled-from, so the
  input order is preserved and selection/shrinking remain engine-owned."
  [schedules]
  (validate-schedule-domain! schedules)
  (g/sampled-from schedules))

(def ^:private schedule-keys [:schedule :schedules])

(defn draw-schedule!
  "Draws one schedule from the ordered `schedules` domain inside an active
  Hegel property, labeling the choice site \"future-schedule\". Validates the
  domain synchronously before drawing. Must be called within h/run-test!."
  [schedules]
  (h/draw! (schedule-generator schedules) "future-schedule"))

(defn run-schedule!
  "Draws one schedule from the ordered `schedules` domain and runs it through
  the unchanged jolt.sim.process-explorer supervisor.

  `config` is a process-explorer run-schedule base config without :schedule; it
  must be a map and must not already carry :schedule or :schedules, since this
  adapter owns schedule selection. Must be called within h/run-test! so the
  single draw is engine-owned. Returns the supervisor outcome unchanged."
  [config schedules]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map {:value config})))
  (let [present (filter #(contains? config %) schedule-keys)]
    (when (seq present)
      (throw (invalid-config :schedule-already-present {:keys (vec present)}))))
  (let [schedule (draw-schedule! schedules)]
    (process-explorer/run-schedule (assoc config :schedule schedule))))

(def ^:private bounded-outcome-keys
  "Only these outcome fields may cross the require-completed! failure boundary."
  [:status :schedule :reason :exit :error])

(defn require-completed!
  "Returns `outcome` unchanged when it is a :completed supervisor outcome.
  Otherwise throws an ex-info tagged :jolt.sim.hegel/non-completed with
  :hegel/origin \"jolt.sim.hegel/require-completed\" and, when `outcome` is a
  map, only the bounded :status/:schedule/:reason/:exit/:error fields."
  [outcome]
  (if (= :completed (:status outcome))
    outcome
    (throw
     (ex-info
      "jolt.sim.hegel expected a :completed process-explorer outcome"
      (cond-> {:hegel/origin "jolt.sim.hegel/require-completed"
               :type ::non-completed}
        (map? outcome) (into (select-keys outcome bounded-outcome-keys)))))))
