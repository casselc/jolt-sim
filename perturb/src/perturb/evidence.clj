(ns perturb.evidence
  "EVIDENCE-v1: a claim and its denominator, as one validated value.

  Step 1 of the convergence slice (`PROGRESSIVE-FORMALISM-DESIGN` §7.2b). It is
  deliberately shaped like `jolt.sim.case-outcome`: a **closed** key set, one
  accepted version, no compatibility layer, and a fail-closed validator that
  throws rather than coercing.

  WHY IT EXISTS. Every gate in this tree prints its verdict its own way, and
  three separate documents found the current lattice conflates STRENGTH with
  SCOPE. Here they are separate keys, and `:source` is a third axis that does
  not order — `#{:simulated}` and `#{:native}` are different facts, not ranked
  ones.

  THE TWO VACUITY RULES, AND THE SECOND IS THE ONE PEOPLE FORGET.

    A. `:checked 0` with any strength above `:unknown` is INVALID. A claim with
       an empty denominator is not a weak claim, it is not a claim.

    B. `:findings n` with n > 0 and `:checked 0` is INVALID. A checker that
       reports violations while counting zero units has a denominator that
       could quietly convert a FAILURE into a shrug. This is E40's
       `:vacuity-accounting`, and the external review's formulation — *reject
       nonzero strength with zero checked units* — permits exactly this case,
       which is why it is written out separately here.

  `:basis` IS MANDATORY AND IS CHECKED AT CONSTRUCTION. One sentence saying
  what ONE unit of `:checked` is. A count without a stated unit is not
  interpretable, and E39's `bounded-complete` \"within its stated corpus\" —
  which names no bound — is the claim this rule exists to reject.")

(def version
  "The only evidence document version this namespace accepts."
  1)

(def strengths
  "Ordered weakest-first. `:unknown` is bottom and is the only strength an
  empty denominator may carry."
  [:unknown :asserted :monitored :sampled :exhausted :proved])

(def ^:private strength-set (set strengths))

(def ^:private sources #{:symbolic :simulated :native})

(def ^:private required-keys
  #{:perturb.evidence/version :perturb.evidence/strength :perturb.evidence/scope
    :perturb.evidence/source  :perturb.evidence/basis    :perturb.evidence/units})

(def ^:private optional-keys
  #{:perturb.evidence/residual :perturb.evidence/findings :perturb.evidence/as-of})

(defn- invalid! [reason detail]
  (throw (ex-info "perturb.evidence: invalid evidence"
                  {:type :perturb.evidence/invalid
                   :perturb.evidence/reason reason
                   :perturb.evidence/detail detail})))

(defn- nat-int? [x] (and (integer? x) (not (neg? x))))

(defn validate!
  "Validates an evidence value and returns it unchanged, or throws fail-closed."
  [e]
  (when-not (map? e) (invalid! :not-a-map (str e)))
  (let [ks (set (keys e))]
    (when-not (every? (fn [k] (contains? ks k)) required-keys)
      (invalid! :missing-keys (vec (sort (remove (fn [k] (contains? ks k)) required-keys)))))
    (let [extra (remove (fn [k] (or (contains? required-keys k)
                                    (contains? optional-keys k)))
                        ks)]
      (when (seq extra) (invalid! :unknown-keys (vec (sort extra))))))
  (when-not (= version (:perturb.evidence/version e))
    (invalid! :unsupported-version (:perturb.evidence/version e)))
  (let [st (:perturb.evidence/strength e)]
    (when-not (contains? strength-set st) (invalid! :bad-strength st)))
  (let [sc (:perturb.evidence/scope e)]
    (when-not (and (map? sc)
                   (string? (:covered sc)) (seq (:covered sc))
                   (string? (:of sc))      (seq (:of sc)))
      (invalid! :bad-scope sc)))
  (let [src (:perturb.evidence/source e)]
    (when-not (and (coll? src) (seq src) (every? (fn [x] (contains? sources x)) src))
      (invalid! :bad-source src)))
  (let [b (:perturb.evidence/basis e)]
    (when-not (and (string? b) (seq b))
      (invalid! :missing-basis b))
    ;; ONE SENTENCE. A basis that needs paragraphs is a basis nobody has
    ;; reduced to a unit, and the count beside it cannot be read.
    (when (some (fn [c] (= c \newline)) b)
      (invalid! :basis-not-one-sentence b)))
  (let [u (:perturb.evidence/units e)]
    (when-not (and (map? u) (nat-int? (:checked u)) (nat-int? (:expected u)))
      (invalid! :bad-units u))
    (when (> (:checked u) (:expected u))
      (invalid! :checked-exceeds-expected u))
    ;; RULE A
    (when (and (zero? (:checked u))
               (not= :unknown (:perturb.evidence/strength e)))
      (invalid! :vacuous-strength
                {:strength (:perturb.evidence/strength e) :units u}))
    ;; RULE B
    (let [f (:perturb.evidence/findings e)]
      (when (and (some? f) (pos? f) (zero? (:checked u)))
        (invalid! :vacuity-accounting {:findings f :units u}))))
  e)

(defn evidence
  "Builds and validates an evidence value."
  [m] (validate! (assoc m :perturb.evidence/version version)))

(defn from-arm
  "Lift one `perturb.layer` arm — the shape E40 introduced — into evidence-v1.

  This is the only adapter in the slice, and it is here rather than in
  `perturb.layer` because the OWNERSHIP BOUNDARY puts evidence rendering on the
  simulator side of the seam and the checker's own verdict shape on the other.

  The mapping is total and states its own losses: an arm's `:state` becomes a
  strength (`:inconclusive` -> `:unknown`, which is the only strength a zero
  denominator may carry), `:exercised` becomes `:checked`, and the arm's
  violation count becomes `:findings`, which is what makes rule B reachable."
  [a scope-of]
  (let [ex   (:perturb.layer/exercised a)
        vs   (count (:perturb.layer/violations a))
        st   (:perturb.layer/state a)]
    (evidence
      {:perturb.evidence/strength (if (= :inconclusive st) :unknown :monitored)
       :perturb.evidence/scope    {:covered (str "this recorded trace, arm "
                                                 (:perturb.layer/arm a))
                                   :of      scope-of}
       :perturb.evidence/source   #{:simulated}
       :perturb.evidence/basis    (:perturb.layer/basis a)
       :perturb.evidence/units    {:checked ex :expected ex}
       :perturb.evidence/findings vs})))

(defn render
  "One line, byte-stable for a given value."
  [e]
  (let [u (:perturb.evidence/units e)]
    (str (name (:perturb.evidence/strength e))
         "  " (:checked u) "/" (:expected u)
         "  src " (pr-str (vec (sort (:perturb.evidence/source e))))
         (if (pos? (or (:perturb.evidence/findings e) 0))
           (str "  findings " (:perturb.evidence/findings e)) "")
         "  basis: " (:perturb.evidence/basis e))))
