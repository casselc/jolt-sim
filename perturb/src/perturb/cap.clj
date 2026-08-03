(ns perturb.cap
  "Capability annotations, as data. NOTHING HERE CHECKS ANYTHING.

  PERTURB-DESIGN §1.2 puts handles, cursors, buffers, leases, continuations and
  mutable cells in the capability tier with four axes — uniqueness, linearity,
  typestate (added by E5), contention (restored by E6) — and says borrows are
  exclusive and capabilities bind affinely.

  §1.3's checker is explicitly out of scope for this artifact, and the design
  record is blunt that nothing built so far is close to checking real code. So
  this namespace does exactly two things:

    1. It lets a capability and its typestate machine be DECLARED as data, and
       an operation's requires/ensures be attached to a var as metadata, in a
       shape a future checker could consume without reading this file. A single
       transition of that machine may also carry a §1.3 REFINEMENT — see
       `refinements` — which is the one place the two typed tiers meet.
    2. It records the transitions a run actually took, as an ordered ledger.

  It does NOT reject anything. `note!` will happily record a transition the
  declared machine forbids. That is deliberate: a runtime guard here would be a
  dynamic checker wearing a static checker's clothes, and the point of a
  hand-annotated artifact is that the annotations are separable from the code
  and can be handed to a checker later. If the ledger and the declaration
  disagree, that is a finding for the report, not an exception at runtime.

  WHAT THE ANNOTATIONS ARE WORTH. They are hand-written and unverified. They
  describe the code as its author intends it, and the only evidence that the
  code agrees is (a) reading it and (b) the ledger. Both are weak. Stated so the
  claim is not overread.")

;; --- declaring a capability -------------------------------------------------

(def axes
  "§1.2's four axes, named so a declaration missing one is visible."
  [:perturb.cap/uniqueness :perturb.cap/linearity
   :perturb.cap/typestate  :perturb.cap/contention])

(defn capability
  "Build a capability declaration. Validates only that all four axes are present
  — this is shape, not semantics; nothing checks that the code obeys it."
  [m]
  (let [missing (remove (fn [a] (contains? m a)) axes)]
    (when (seq missing)
      (throw (ex-info "perturb.cap: declaration is missing an axis"
                      {:perturb.cap/missing (vec missing)})))
    m))

(def registry
  "capability name -> declaration, and var symbol -> operation annotation."
  (atom {:perturb.cap/capabilities {} :perturb.cap/operations {}}))

(defn declare-capability!
  [decl]
  (swap! registry assoc-in [:perturb.cap/capabilities (:perturb.cap/name decl)] decl)
  decl)

(defn annotate-op!
  "Attach an operation annotation to a var. Recorded in the registry, and also
  written onto the var's own metadata where the host allows it, so a checker can
  find it either way. The `defn` forms in perturb.nrepl carry the same map as
  literal `^{:perturb.cap/op ...}` metadata, so the source is annotated even if
  the runtime write is unavailable."
  [v ann]
  (try (alter-meta! v assoc :perturb.cap/op ann) (catch :default _ nil))
  (swap! registry assoc-in
         [:perturb.cap/operations (symbol (str (:ns (meta v))) (str (:name (meta v))))]
         ann)
  v)

;; --- a refinement attached to a transition ----------------------------------
;;
;; PERTURB-DESIGN §1.2 lists four axes and E18 finding 3 is that a STATE cannot
;; carry a REFINEMENT: `:finished` says a body writer stopped, and `wrote exactly
;; N` is arithmetic over an integer that is not known until run time. §1.3
;; reserves that class for refinements. This is where the two tiers are joined,
;; and the join is one extra key on a transition map:
;;
;;   {:op 'ns/finish :from :open :to :finished
;;    :perturb.cap/refine {:name wrote-exactly-content-length
;;                         :requires (= written declared)}}
;;
;; and, on the transitions that MOVE the arithmetic rather than checking it:
;;
;;   {:op 'ns/write :from :open :to :open
;;    :perturb.cap/refine {:update {written (+ written (ocount (arg 1)))}}}
;;
;; WHY A KEY ON THE TRANSITION AND NOT A NEW AXIS. §1.2's axes are properties of
;; a capability; this is a property of one EDGE of one machine, and the two
;; obligations on `perturb.http/body-capability` are both written that way
;; already. It is also the smallest shape that survives the primitive table
;; being rekeyed by [capability operation]: the transition maps do not move.
;;
;; THE ONE PLACE IT IS NOT ON A TRANSITION, AND WHY. The ghost variables have to
;; be given their initial values by whatever MINTS the capability, and E18
;; finding 1(a) is that a capability minted by another machine's operation
;; cannot name that operation among its own `:transitions` — the primitive table
;; is keyed by operation, so the entry would collide. So `:init` rides on the
;; `:produces` entry of the minting operation instead:
;;
;;   :produces [{:cap 'ns/Body :state :open :at [1]
;;               :perturb.cap/refine {:init {declared (arg 4) written 0}}}]
;;
;; That asymmetry is a symptom of finding 1(a), not a design choice, and it
;; disappears the moment the table is rekeyed.
;;
;; NOTHING HERE CHECKS ANYTHING, exactly as above. `perturb.refine` decides the
;; formulas and `perturb.check` supplies the values; this namespace only says
;; where a refinement is written down.

(def refine-key :perturb.cap/refine)

(defn refinements
  "[capability operation] -> the transition's `:perturb.cap/refine` map, over
  every declaration in `decls`.

  Keyed by capability AND operation on purpose: `spec`'s primitive table is
  keyed by operation alone and therefore loses one of the two machines an
  operation like `body-finish!` advances (E18 finding 1a). A refinement that
  inherited that defect would be attached to the wrong machine."
  [decls]
  (reduce (fn [acc e]
            (let [cap-name (first e)
                  ts       (:perturb.cap/typestate (second e))]
              (reduce (fn [a t]
                        (if (contains? t refine-key)
                          (assoc a [cap-name (:op t)]
                                 (assoc (get t refine-key)
                                        :from (:from t) :to (:to t)))
                          a))
                      acc (:transitions ts))))
          {} decls))

;; --- the ledger -------------------------------------------------------------

(def ledger
  "Ordered record of capability events actually taken by a run. Observation, not
  enforcement. (INHERITED I10: a process-global atom is itself the shape §1.2
  says should be capability-tier.)"
  (atom []))

(defn reset-ledger! [] (reset! ledger []))

(defn note!
  "Record one capability event. Returns its argument unchanged so it can be
  threaded through an operation without altering control flow."
  [event]
  (swap! ledger conj event)
  event)

(defn transition!
  [cap-name cap-id from to site]
  (note! {:perturb.cap/capability cap-name
          :perturb.cap/id         cap-id
          :perturb.cap/from       from
          :perturb.cap/to         to
          :perturb.cap/site       site}))

;; --- what a checker would be handed -----------------------------------------

(defn checker-input
  "Everything a future checker would need from a run: the declarations, the
  per-operation annotations, and the observed transition sequence. Emitted as
  plain EDN-able data."
  []
  {:perturb.cap/declarations (:perturb.cap/capabilities @registry)
   :perturb.cap/operations   (:perturb.cap/operations @registry)
   :perturb.cap/ledger       @ledger})

(defn ledger-summary
  "A human reading of the ledger: per capability id, the state sequence and the
  number of times it reached a terminal state. Descriptive only — it computes no
  verdict and raises nothing."
  []
  (reduce (fn [acc e]
            (let [id (:perturb.cap/id e)]
              (update acc id (fn [seen] (conj (or seen [(:perturb.cap/from e)])
                                              (:perturb.cap/to e))))))
          {}
          @ledger))
