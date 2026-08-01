(ns jolt.sim.fault
  "Transport-neutral deterministic fault-plan director.

  A director is plain immutable data built from a closed fault plan. Given an
  attempt, [[step]] computes one deterministic firing (or none) plus evidence
  that records every matching rule's activation separately from actual firing.

  The core is pure: no atoms, I/O, time, entropy, threads, or host identities.
  Match and outcome data are frozen through jolt.sim.trace canonical
  projections at construction time, so mutation of caller-owned byte arrays
  after a director is built cannot alter matching or later outcomes. Every
  returned outcome is restored fresh, so caller mutation of one firing's byte
  arrays cannot corrupt a later firing.

  The director state and evidence are accepted by
  jolt.sim.trace/canonical-value and compare deterministically."
  (:require [jolt.sim.trace :as trace]))

(def ^:private director-type :jolt.sim.fault/director)
(def ^:private type-key :jolt.sim.fault/type)

;; Public typed ex-info identifiers. ``reason`` is always a stable keyword in
;; the same ex-data map, so callers can fail closed on shape without depending
;; on message text.
(def invalid-plan :jolt.sim.fault/invalid-plan)
(def invalid-attempt :jolt.sim.fault/invalid-attempt)
(def invalid-director :jolt.sim.fault/invalid-director)

(def ^:private rule-keys #{:id :match :activation :outcome})
(def ^:private activation-keys #{:on-match :times})
(def ^:private required-activation-keys #{:on-match})
(def ^:private compiled-rule-keys
  #{:id :match :on-match :times :outcome :matches :firings})
(def ^:private director-keys #{type-key :rules :firings})

(defn- fail! [type reason data]
  (throw
   (ex-info (str "jolt.sim.fault " (name reason))
            (assoc data :type type :reason reason))))

(defn- positive-int? [value]
  (and (integer? value) (pos? value)))

(defn- nonnegative-int? [value]
  (and (integer? value) (not (neg? value))))

(defn- plain-map? [value]
  (and (map? value) (not (record? value))))

(defn- stable-key-vector [values]
  (vec (sort-by pr-str values)))

(defn- unknown-keys [allowed value]
  (stable-key-vector (remove #(contains? allowed %) (keys value))))

(defn- missing-keys [required value]
  (stable-key-vector (remove #(contains? value %) required)))

(defn- trace-stable? [value]
  (try
    (trace/canonical-value value)
    true
    (catch :default _
      false)))

(defn- freeze-matcher [matcher]
  ;; Validate and freeze the matcher through a canonical projection. Canonical
  ;; projections retain no mutable storage, so later mutation of caller-owned
  ;; byte arrays cannot alter matching. canonical-value rejects mutable map
  ;; keys and any value outside the trace domain, failing closed here.
  (let [canonical (trace/canonical-value matcher)]
    (into {}
          (map (fn [[canon-key canon-val]]
                 ;; Map keys cannot be byte arrays, so restored keys are
                 ;; immutable caller-safe values retained for direct lookup.
                 [(trace/restore-value canon-key) canon-val]))
          (nth canonical 1))))

(defn- validate-activation [activation id]
  (when-not (plain-map? activation)
    (fail! invalid-plan :activation-must-be-map {:rule-id id}))
  (let [unknown (unknown-keys activation-keys activation)
        missing (missing-keys required-activation-keys activation)]
    (when (seq unknown)
      (fail! invalid-plan :unknown-activation-keys
             {:rule-id id :unknown unknown}))
    (when (seq missing)
      (fail! invalid-plan :missing-activation-keys
             {:rule-id id :missing missing})))
  (let [on-match (:on-match activation)
        times (get activation :times 1)]
    (when-not (positive-int? on-match)
      (fail! invalid-plan :invalid-on-match {:rule-id id :on-match on-match}))
    (when-not (positive-int? times)
      (fail! invalid-plan :invalid-times {:rule-id id :times times}))
    {:on-match on-match :times times}))

(defn- build-rule [index rule]
  (when-not (plain-map? rule)
    (fail! invalid-plan :rule-must-be-map {:rule-index index}))
  (let [unknown (unknown-keys rule-keys rule)
        missing (missing-keys rule-keys rule)]
    (when (seq unknown)
      (fail! invalid-plan :unknown-rule-keys
             {:rule-index index :unknown unknown}))
    (when (seq missing)
      (fail! invalid-plan :missing-rule-keys
             {:rule-index index :missing missing})))
  (let [id (:id rule)
        matcher (:match rule)
        activation (:activation rule)
        outcome (:outcome rule)]
    (when-not (and (keyword? id) (namespace id))
      (fail! invalid-plan :invalid-id {:rule-index index :id id}))
    (when-not (plain-map? matcher)
      (fail! invalid-plan :match-must-be-map {:rule-id id}))
    (let [on (validate-activation activation id)
          frozen-match (freeze-matcher matcher)
          ;; canonical-value throws unsupported-value if the outcome is outside
          ;; the trace domain, rejecting before any director is returned.
          frozen-outcome (trace/canonical-value outcome)]
      {:id id
       :match frozen-match
       :on-match (:on-match on)
       :times (:times on)
       :outcome frozen-outcome
       :matches 0
       :firings 0})))

(defn director
  "Returns an immutable plain-data fault-plan director built from ``plan``.

  ``plan`` is a vector of closed rule maps. Each rule has exactly
  #{:id :match :activation :outcome}:

  * ``:id``        a namespaced keyword, unique across the plan;
  * ``:match``     a plain map (empty is catch-all);
  * ``:activation`` a closed map with positive-integer ``:on-match`` and
                   optional positive-integer ``:times`` (default 1, the total
                   successful firings, not additional firings);
  * ``:outcome``   opaque stable trace-domain data the director never
                   interprets.

  Match and outcome data are frozen through jolt.sim.trace canonical
  projections so caller mutation after construction cannot alter matching or
  later outcomes.

  Rejects non-vector plans and malformed rules with typed ex-info
  ({:type invalid-plan, :reason stable-keyword}). Values outside the trace
  canonical-value domain surface jolt.sim.trace's typed unsupported-value
  error before any director is returned."
  [plan]
  (when-not (vector? plan)
    (fail! invalid-plan :plan-must-be-vector {:plan-class (str (class plan))}))
  ;; Validate the complete caller document before inspecting its structure.
  ;; This rejects metadata and unstable unknown keys as trace-domain errors
  ;; instead of silently dropping them or returning nondeterministic evidence.
  (trace/canonical-value plan)
  (loop [index 0
         remaining plan
         seen-ids #{}
         built []]
    (if (seq remaining)
      (let [next-rule (build-rule index (first remaining))
            id (:id next-rule)]
        (when (contains? seen-ids id)
          (fail! invalid-plan :duplicate-id {:id id}))
        (recur (inc index)
               (next remaining)
               (conj seen-ids id)
               (conj built next-rule)))
      {type-key director-type
       :rules built
       :firings 0})))

(defn- validate-compiled-rule! [index rule]
  (when-not (plain-map? rule)
    (fail! invalid-director :rule-must-be-map {:rule-index index}))
  (let [unknown (unknown-keys compiled-rule-keys rule)
        missing (missing-keys compiled-rule-keys rule)]
    (when (seq unknown)
      (fail! invalid-director :unknown-rule-keys
             {:rule-index index :unknown unknown}))
    (when (seq missing)
      (fail! invalid-director :missing-rule-keys
             {:rule-index index :missing missing})))
  (let [{:keys [id match on-match times outcome matches firings]} rule]
    (when-not (and (keyword? id) (namespace id))
      (fail! invalid-director :invalid-rule-id
             {:rule-index index :rule-id id}))
    ;; A compiled matcher retains ordinary stable keys but only canonical-form
    ;; values. Validating the complete map also rejects mutable byte-array keys.
    (when-not (and (plain-map? match)
                   (every? trace/canonical-form? (vals match)))
      (fail! invalid-director :invalid-rule-match
             {:rule-index index :rule-id id}))
    (when-not (and (positive-int? on-match) (positive-int? times))
      (fail! invalid-director :invalid-rule-activation
             {:rule-index index :rule-id id
              :on-match on-match :times times}))
    (when-not (trace/canonical-form? outcome)
      (fail! invalid-director :invalid-rule-outcome
             {:rule-index index :rule-id id}))
    (when-not (and (nonnegative-int? matches)
                   (nonnegative-int? firings)
                   (<= firings times)
                   (<= firings matches)
                   (or (zero? firings) (>= matches on-match)))
      (fail! invalid-director :invalid-rule-counters
             {:rule-index index :rule-id id
              :matches matches :firings firings
              :on-match on-match :times times})))
  rule)

(defn- validate-director! [value]
  (when-not (plain-map? value)
    (fail! invalid-director :director-must-be-map
           {:director-class (str (class value))}))
  (when-not (trace-stable? value)
    (fail! invalid-director :director-outside-trace-domain {}))
  (let [unknown (unknown-keys director-keys value)
        missing (missing-keys director-keys value)]
    (when (seq unknown)
      (fail! invalid-director :unknown-director-keys {:unknown unknown}))
    (when (seq missing)
      (fail! invalid-director :missing-director-keys {:missing missing})))
  (when-not (= director-type (get value type-key))
    (fail! invalid-director :invalid-director-type
           {:provided (get value type-key)}))
  (when-not (vector? (:rules value))
    (fail! invalid-director :rules-must-be-vector
           {:rules-class (str (class (:rules value)))}))
  (when-not (nonnegative-int? (:firings value))
    (fail! invalid-director :invalid-firing-count
           {:firings (:firings value)}))
  (let [rules (mapv (fn [index rule]
                      (validate-compiled-rule! index rule))
                    (range (count (:rules value)))
                    (:rules value))
        ids (mapv :id rules)]
    (when-not (= (count ids) (count (set ids)))
      (fail! invalid-director :duplicate-rule-id {:rule-ids ids}))
    (let [sum (reduce + 0 (map :firings rules))]
      (when-not (= sum (:firings value))
        (fail! invalid-director :inconsistent-firing-count
               {:firings (:firings value) :rule-firings sum}))))
  value)

(defn validate-director
  "Validates and returns an existing plain-data fault director.

  Boundary frontends use this when accepting restored or caller-supplied
  director state without consuming an attempt. The validation is identical to
  [[step]]'s director validation: exact closed shapes, canonical frozen fields,
  unique rule ids, coherent counters, and the global firing invariant. Invalid
  values throw typed ``invalid-director`` ex-info."
  [value]
  (validate-director! value))

(defn- project-attempt [attempt]
  ;; Validates the whole attempt is stable trace-domain data and returns a map
  ;; from each natural key to its canonical projection, for shallow-subset
  ;; matching. Throws unsupported-value on any out-of-domain value.
  (let [canonical (trace/canonical-value attempt)]
    (into {}
          (map (fn [[canon-key canon-val]]
                 [(trace/restore-value canon-key) canon-val]))
          (nth canonical 1))))

(defn step
  "Advances ``director`` by one ``attempt``.

  Returns exactly {:director next-director :evidence evidence}. For every rule
  whose shallow-subset matcher matches the attempt, increments that rule's
  match count and records its per-rule match ordinal. A rule is activated when
  its updated match count is at least ``:on-match`` and its firing count is
  below ``:times``. The first activated rule in plan order fires; only its
  firing count (and the global firing count) increment. Exhausted rules never
  shadow later eligible rules, and an activated loser remains eligible on
  later matching attempts until it actually fires. This is deterministic from
  director state plus attempt.

  Evidence is exactly {:attempt-id :matches :firing}. ``:matches`` lists every
  matching rule in plan order with its activation and firing outcome;
  ``:firing`` is nil or the fired rule's ordinals plus a freshly restored
  outcome."
  [director attempt]
  (validate-director director)
  (when-not (plain-map? attempt)
    (fail! invalid-attempt :attempt-must-be-map
           {:attempt-class (str (class attempt))}))
  (when-not (contains? attempt :attempt-id)
    (fail! invalid-attempt :missing-attempt-id {}))
  (let [projected (project-attempt attempt)
        fresh-attempt-id (trace/restore-value
                          (trace/canonical-value (:attempt-id attempt)))
        rules (:rules director)
        per-rule (mapv (fn [rule]
                         (let [entries (:match rule)
                               matched?
                               (every? (fn [[key canon-val]]
                                         (and (contains? projected key)
                                              (= canon-val (get projected key))))
                                       entries)
                               new-matches
                               (if matched? (inc (:matches rule)) (:matches rule))
                               activated?
                               (and matched?
                                    (>= new-matches (:on-match rule))
                                    (< (:firings rule) (:times rule)))]
                           {:rule rule
                            :matched? matched?
                            :new-matches new-matches
                            :activated? activated?}))
                       rules)
        activated-index
        (loop [i 0 [current & more] per-rule]
          (cond
            (nil? current) nil
            (:activated? current) i
            :else (recur (inc i) more)))
        chosen (when activated-index (nth per-rule activated-index))
        global-firings (:firings director)
        new-global-firings (if chosen (inc global-firings) global-firings)
        next-rules
        (mapv (fn [i {:keys [rule] :as result}]
                (let [updated (assoc rule :matches (:new-matches result))]
                  (if (and chosen (= i activated-index))
                    (assoc updated :firings (inc (:firings rule)))
                    updated)))
              (range (count per-rule))
              per-rule)
        next-director (assoc director
                             :rules next-rules
                             :firings new-global-firings)
        matches
        (filterv some?
                 (mapv (fn [i result]
                         (when (:matched? result)
                           {:rule-id (get-in result [:rule :id])
                            :match-ordinal (:new-matches result)
                            :activated? (boolean (:activated? result))
                            :fired? (boolean (and chosen (= i activated-index)))}))
                       (range (count per-rule))
                       per-rule))
        firing
        (when chosen
          {:rule-id (get-in chosen [:rule :id])
           :match-ordinal (:new-matches chosen)
           :firing-ordinal new-global-firings
           :rule-firing-ordinal (inc (get-in chosen [:rule :firings]))
           :outcome (trace/restore-value (get-in chosen [:rule :outcome]))})
        evidence {:attempt-id fresh-attempt-id
                  :matches matches
                  :firing firing}]
    {:director next-director :evidence evidence}))
