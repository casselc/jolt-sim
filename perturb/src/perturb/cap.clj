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
       shape a future checker could consume without reading this file.
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
