(ns jolt.sim.strategy
  "Deterministic task-selection strategies for the cooperative simulator.")

;; Park-Miller's minimal-standard generator.  Keeping the state below 2^31
;; avoids depending on host overflow behavior while still giving replayable,
;; portable choices.
(def ^:private modulus 2147483647)
(def ^:private multiplier 48271)

(defn- task-id? [value]
  (and (integer? value) (not (neg? value))))

(defn seeded
  "Returns a deterministic strategy initialized from `seed`."
  [seed]
  (when-not (integer? seed)
    (throw (ex-info "Deterministic seed must be an integer"
                    {:type ::invalid-seed
                     :seed seed})))
  (let [state (mod seed modulus)]
    {:type :seeded
     :state (if (zero? state) 1 state)}))

(defn scripted
  "Returns a finite strategy that chooses non-negative task IDs in order.

  Scripted strategies are useful for permanent regressions derived from a
  known schedule witness.  Exhaustion and selecting a non-runnable task are
  errors rather than implicit fallbacks."
  [choices]
  (when-not (sequential? choices)
    (throw (ex-info "Scripted choices must be a finite sequential collection"
                    {:type ::invalid-script})))
  (let [choices (vec choices)]
    (when-not (every? task-id? choices)
      (throw (ex-info "Scripted task IDs must be non-negative integers"
                      {:type ::invalid-script})))
    {:type :scripted
     :choices choices
     :index 0}))

(defn- choose-seeded [strategy enabled]
  (let [next-state (mod (* multiplier (:state strategy)) modulus)
        chosen (nth enabled (mod next-state (count enabled)))]
    [chosen (assoc strategy :state next-state)]))

(defn- choose-scripted [strategy enabled]
  (let [index (:index strategy)
        choices (:choices strategy)]
    (when-not (and (vector? choices)
                   (every? task-id? choices)
                   (and (integer? index) (not (neg? index))))
      (throw (ex-info "Malformed scripted strategy"
                      {:type ::invalid-script})))
    (when (>= index (count choices))
      (throw (ex-info "Scripted schedule exhausted"
                      {:type ::script-exhausted
                       :index index
                       :enabled enabled})))
    (let [chosen (nth choices index)]
      (when-not (some #(= chosen %) enabled)
        (throw (ex-info "Scripted schedule selected a non-runnable task"
                        {:type ::non-runnable-choice
                         :index index
                         :enabled enabled
                         :chosen chosen})))
      [chosen (assoc strategy :index (inc index))])))

(defn choose
  "Selects one ID from the already-sorted, non-empty `enabled` vector.

  Returns `[chosen next-strategy]`; the input strategy is never mutated."
  [strategy enabled]
  (when-not (seq enabled)
    (throw (ex-info "Cannot select from an empty runnable set"
                    {:type ::empty-enabled-set})))
  (when-not (and (vector? enabled)
                 (every? task-id? enabled)
                 (= enabled (vec (sort enabled)))
                 (= (count enabled) (count (set enabled))))
    (throw (ex-info "Enabled tasks must be a sorted unique task-ID vector"
                    {:type ::invalid-enabled-set})))
  (case (:type strategy)
    :seeded (choose-seeded strategy enabled)
    :scripted (choose-scripted strategy enabled)
    (throw (ex-info "Unknown scheduling strategy"
                    {:type ::unknown-strategy
                     :strategy strategy}))))
