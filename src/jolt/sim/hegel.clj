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

  A second, direct path builds an exact permutation of 0..N-1 from Hegel-owned
  bounded integer (Lehmer) digits over the remaining ordinals: no explicit
  domain is supplied and the N! plan space is never enumerated or materialized.
  All-zero digits decode to the identity permutation, and a regression against
  the pinned engine demonstrates that an always-failing property minimizes to
  it. This is direct structural generation only -- the pinned Hegel API does
  not document a distribution for integer draws -- and is not high-utility,
  PCT, or coverage-guided search.

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

(defn- validate-future-count!
  "Throws ::invalid-config unless `future-count` is a positive integer. Returns
  `future-count` unchanged. Synchronous and property-independent, so a bad
  count fails before any draw."
  [future-count]
  (when-not (and (integer? future-count) (pos? future-count))
    (throw (invalid-config :not-a-positive-integer
                           {:key :future-count :value future-count})))
  future-count)

(defn- decode-lehmer-digits
  "Pure decoder: turns a vector of (dec n) Lehmer digits into the exact
  permutation of 0..(dec n) they encode. Digit i selects and removes the
  (digit i)-th remaining ordinal. The all-zero digit vector decodes to the
  identity permutation. O(n^2) decoding; it never enumerates the N! plan space."
  [digits]
  (let [n (inc (count digits))]
    (loop [remaining (vec (range n))
           digits (seq digits)
           result []]
      (if-let [digit (first digits)]
        (let [chosen (remaining digit)]
          (recur (into (subvec remaining 0 digit)
                       (subvec remaining (inc digit)))
                 (next digits)
                 (conj result chosen)))
        (conj result (first remaining))))))

(defn- permutation-generator
  "Returns a Hegel generator over exact permutations of the ordinals 0..(dec n).

  For n > 1 it composes (dec n) fixed bounded-integer Lehmer-digit generators
  (max indices n-1 down to 1) with the documented variadic g/tuple, then g/fmaps
  `decode-lehmer-digits` over the drawn tuple. That is exactly (dec n) integer
  draws and one pure decode -- never an enumeration of the N! plan space, and
  never a filtered or assumed digit. All-zero digits decode to the identity
  permutation. For n = 1 it returns (g/just [0]) with no integer draw and no
  composition span."
  [n]
  (if (= 1 n)
    (g/just [0])
    (let [digit-generators
          (mapv (fn [max-index] (g/integer 0 max-index))
                (range (dec n) 0 -1))]
      (g/fmap decode-lehmer-digits (apply g/tuple digit-generators)))))

(defn schedule-generator
  "Returns a Hegel generator that selects one schedule from `schedules`, an
  ordered nonempty vector of unique, same-size exact permutation schedules.

  The domain is validated synchronously (throwing :jolt.sim.hegel/invalid-config
  with a stable :reason) and then handed unchanged to g/sampled-from, so the
  input order is preserved and selection/shrinking remain engine-owned."
  [schedules]
  (validate-schedule-domain! schedules)
  (g/sampled-from schedules))

(defn direct-schedule-generator
  "Returns a Hegel generator that produces an exact permutation of the integers
  0..(dec future-count), built directly from Hegel-owned bounded integer
  (Lehmer) digits over the remaining ordinals.

  `future-count` must be a positive integer; anything else is rejected
  synchronously with :jolt.sim.hegel/invalid-config and a stable
  :not-a-positive-integer reason carrying the offending :future-count evidence.

  No explicit schedule domain is supplied and the N! permutation space is never
  enumerated or materialized: for future-count > 1 the generator makes exactly
  (dec future-count) bounded integer draws via g/tuple plus one pure decode;
  for future-count = 1 it returns the sole permutation [0] via g/just with no
  integer draw. All-zero digits decode to the identity permutation, and an
  always-failing regression against the pinned engine minimizes to it. This is
  direct structural generation -- the pinned Hegel API does not document a
  distribution for integer draws -- and is not high-utility/PCT/coverage-guided
  search."
  [future-count]
  (validate-future-count! future-count)
  (permutation-generator future-count))

(def ^:private schedule-keys [:schedule :schedules])

(defn draw-schedule!
  "Draws one schedule from the ordered `schedules` domain inside an active
  Hegel property, labeling the choice site \"future-schedule\". Validates the
  domain synchronously before drawing. Must be called within h/run-test!."
  [schedules]
  (h/draw! (schedule-generator schedules) "future-schedule"))

(defn draw-direct-schedule!
  "Draws one directly-generated permutation for the positive `future-count`
  inside an active Hegel property, labeling the choice site \"future-schedule\".
  Validates `future-count` synchronously before drawing. Must be called within
  h/run-test!."
  [future-count]
  (h/draw! (direct-schedule-generator future-count) "future-schedule"))

(defn- validate-base-config!
  "Throws ::invalid-config unless `config` is a map carrying neither :schedule
  nor :schedules, since this adapter owns schedule selection. Returns `config`
  unchanged. Synchronous and property-independent, so a malformed base config
  fails before any draw. Shared by run-schedule! and run-direct-schedule!."
  [config]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map {:value config})))
  (let [present (filter #(contains? config %) schedule-keys)]
    (when (seq present)
      (throw (invalid-config :schedule-already-present {:keys (vec present)}))))
  config)

(defn run-schedule!
  "Draws one schedule from the ordered `schedules` domain and runs it through
  the unchanged jolt.sim.process-explorer supervisor.

  `config` is a process-explorer run-schedule base config without :schedule; it
  must be a map and must not already carry :schedule or :schedules, since this
  adapter owns schedule selection. Must be called within h/run-test! so the
  single draw is engine-owned. Returns the supervisor outcome unchanged."
  [config schedules]
  (validate-base-config! config)
  (let [schedule (draw-schedule! schedules)]
    (process-explorer/run-schedule (assoc config :schedule schedule))))

(defn run-direct-schedule!
  "Draws one directly-generated permutation for the positive `future-count` and
  runs it through the unchanged jolt.sim.process-explorer supervisor.

  `config` is a process-explorer run-schedule base config without :schedule; it
  must be a map and must not already carry :schedule or :schedules. Reuses the
  same base-config validation boundary as run-schedule!, so a bad config or a
  non-positive/non-integer future-count fails synchronously before any draw.
  Must be called within h/run-test! so the single draw is engine-owned. Returns
  the supervisor outcome unchanged."
  [config future-count]
  (validate-base-config! config)
  (let [schedule (draw-direct-schedule! future-count)]
    (process-explorer/run-schedule (assoc config :schedule schedule))))

(def ^:private bounded-outcome-keys
  "Only these outcome fields may cross the require-completed! failure boundary."
  [:status :schedule :reason :exit :error :artifact-dir])

(defn- non-completed-data [outcome]
  (cond-> {}
    (map? outcome) (into (select-keys outcome bounded-outcome-keys))))

(defn require-completed!
  "Returns `outcome` unchanged when it is a :completed supervisor outcome.

  A :worker-error is infrastructure, not a generated application witness. It
  throws :jolt.sim.hegel/infrastructure-error with :hegel/usage-error? true so
  the pinned Hegel engine aborts immediately instead of shrinking or repeating
  the same broken worker bootstrap. Other non-completed outcomes remain
  shrinkable property failures tagged :jolt.sim.hegel/non-completed.

  Both errors carry only the bounded
  :status/:schedule/:reason/:exit/:error/:artifact-dir fields from a map
  outcome."
  [outcome]
  (case (:status outcome)
    :completed outcome
    :worker-error
    (throw
     (ex-info
      "jolt.sim.hegel exploration worker failed before producing a usable case"
      (merge {:hegel/usage-error? true
              :type ::infrastructure-error}
             (non-completed-data outcome))))
    (throw
     (ex-info
      "jolt.sim.hegel expected a :completed process-explorer outcome"
      (merge {:hegel/origin "jolt.sim.hegel/require-completed"
              :type ::non-completed}
             (non-completed-data outcome))))))
