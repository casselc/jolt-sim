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

;; --- a sum :to, and the discriminator that eliminates it ---------------------
;;
;; PERTURB-DESIGN E26 finding 7: `casselc/db`'s `verified-sqlite-begin!` has ONE
;; operation with THREE destinations chosen at run time, and perturb could not
;; declare it. Two things say it now, and NEITHER IS A NEW CONCEPT HERE:
;;
;;   1. SEVERAL `:transitions` ENTRIES SHARING AN :op AND A :from.
;;
;;        {:op 'perturb.dbtx/begin :from :idle :to :open}      ; R0
;;        {:op 'perturb.dbtx/begin :from :idle :to :idle}      ; R1, R2
;;        {:op 'perturb.dbtx/begin :from :idle :to :poisoned}  ; R3-R7
;;
;;      Nothing here ever rejected that — `:transitions` is a vector and this
;;      namespace validates only that the four axes are present. What rejected it
;;      was downstream: `perturb.check`'s primitive table was keyed [capability
;;      operation] with `assoc`, so the second entry overwrote the first. The
;;      declaration language did not need extending; the checker's index did.
;;
;;   2. AN OPTIONAL CAPABILITY KEY, `:perturb.cap/discriminator`:
;;
;;        [{:op 'perturb.dbtx/autocommit? :arg 0
;;          :true :idle :false [:open :poisoned]}]
;;
;;      "this predicate, applied to that argument, narrows a sum to the `:true`
;;      states in the then arm and the `:false` states in the else arm." It is a
;;      property of the CAPABILITY rather than of one edge — a single predicate
;;      partitions the whole state set and is not attached to the operation that
;;      produced the sum — which is why it sits beside `:perturb.cap/typestate`
;;      and not on a transition the way `:perturb.cap/refine` does.
;;
;; NOTHING HERE CHECKS ANYTHING, as above. In particular a discriminator is a
;; CLAIM about a predicate's return value and `note!` will never contradict it:
;; the ledger records the one destination a run actually took, which is evidence
;; about the run and not about the declaration.

(def discriminator-key :perturb.cap/discriminator)

;; --- cancellation as a STATE, not a term ------------------------------------
;;
;; PERTURB-DESIGN §4.6 piece 5, from E26 finding 8 and scoped by E27. On
;; `abort!` a capability enters a state whose ONLY legal outgoing edge is the
;; destructor — `casselc/db`'s `:poisoned`, h11's `MUST_CLOSE`. It needs no new
;; term and no live-capability set enumerated at abort sites, because the sum
;; `:to` machinery already gives the destructor's shape: `:from :any` admits
;; every summand, so it needs no case split.
;;
;;   :perturb.cap/cancelled :poisoned
;;
;; ONE KEYWORD, NAMING ONE OF THE CAPABILITY'S OWN DECLARED STATES.
;;
;; WHY THE CAPABILITY DECLARES IT RATHER THAN IT BEING IMPLICIT FOR ALL. Three
;; reasons, and the first is the one that decides it:
;;
;;   1. E27's SUFFICIENCY CONDITION IS PER CAPABILITY. Fowler §1.4 names two
;;      quandaries of silent discard — a developer gets no feedback for an
;;      unfinished protocol, and a peer may be left waiting forever. A state
;;      discharges the first and cannot touch the second, because a state is a
;;      fact about the HOLDER. So the mechanism is sufficient exactly when no
;;      peer's progress depends on being told: true of a database transaction,
;;      FALSE of `perturb.http/ServerConn`, whose counterparty is a client on an
;;      open socket. That is a judgement about the resource, and a judgement has
;;      to be written down by whoever makes it. An implicit cancelled state
;;      would silently hand `ServerConn` a cancellation it is not entitled to.
;;   2. An implicit state would give every machine a state it did not name, and
;;      would need a destructor SYNTHESISED for machines that have none — a
;;      machine with no totally-admitting operation would acquire an inescapable
;;      state, which `perturb.check/report-limits` item 14(e) already names as
;;      the way to make a capability unusable, with no warning.
;;   3. It is the same posture as `:perturb.cap/discriminator` and as the sum
;;      itself: OPT-IN, so a machine that does not mention it behaves exactly as
;;      it did before this existed.
;;
;; NOTHING HERE CHECKS ANYTHING, as everywhere else in this namespace. But this
;; key is the one declaration in perturb that a checker MUST NOT simply believe:
;; a capability that declared a cancelled state with an ordinary outgoing edge
;; would be claiming a discipline it does not have, and every program holding it
;; would be accepted on a false premise. `perturb.check`'s
;; `check-cancellation-declarations!` refuses that shape where it is WRITTEN.

(def cancelled-key :perturb.cap/cancelled)

;; --- THREE NOTIONS, THREE KEYS (E33's conceptual correction) ----------------
;;
;; PERTURB-DESIGN E33 states the whole finding in one sentence:
;;
;;   > A stable shared handle, its current protocol state, and the obligation to
;;   > discharge the underlying resource are THREE DIFFERENT THINGS.
;;
;; `:linearity :once` treated them as one, and E30's failures follow directly:
;; the double `close!`, the rejected `closed?`/`connection-info`, and the
;; 12-of-23 in-place bucket are one conflation seen three ways.
;;
;; They are now three separate things to write down, and each means exactly one
;; thing ON ITS OWN:
;;
;;   1. THE PROTOCOL STATE — `:to` on a transition.
;;      ALONE IT SAYS: which operations are legal next, and nothing else. It says
;;      nothing about whether the resource is still owed a destructor and nothing
;;      about whether the binding may be mentioned again. Unchanged in meaning
;;      from the day it was written; what changed is that it no longer implies
;;      the other two.
;;
;;   2. THE OBLIGATION — `:obligation` on a transition, one of
;;      `:acquire` / `:retain` / `:discharge`.
;;      ALONE IT SAYS: whether, after this edge, the resource still owes its
;;      destructor. `:acquire` starts owing, `:retain` (the default) leaves it as
;;      it was, `:discharge` ends it. NOT DECLARED IS NOT THE SAME AS `:retain`:
;;      an edge that declares nothing is DERIVED exactly as the checker has
;;      always derived it — discharged iff every member of `:to` is terminal —
;;      which is what makes every declaration written before this key existed
;;      behave identically. The key exists for the two shapes derivation cannot
;;      reach: a destructor that hands back a REUSABLE handle (terminal-state
;;      arithmetic says it still owes; the resource says it does not), and an
;;      operation that re-acquires (`:acquire`), which the absorbing-state rule
;;      then refuses where it must be refused.
;;
;;   3. THE NAME — the SHAPE OF THE ANNOTATION, not a key on the machine.
;;      ALONE IT SAYS: may the caller's binding be mentioned after this call.
;;      Three shapes and only three:
;;
;;        :borrows [{:cap C … :arg 0}]                 the name lives, no edge
;;        :consumes [{… :arg 0}] :produces [{… :at 0}]  the name DIES and the
;;                                                      capability reappears in
;;                                                      the RESULT (value
;;                                                      threading — unchanged)
;;        :consumes [{… :arg 0}] :produces [{… :arg 0}] the name LIVES and
;;                                                      changes state WHERE IT
;;                                                      STANDS (in place)
;;
;;      The third is E30 finding 2 and §4.6's in-place item. Its syntax already
;;      existed and was ACCEPTED AND SILENTLY IGNORED (tally row 50); it is
;;      implemented now, and `:arg` together with `:at` on one entry is refused
;;      rather than resolved.
;;
;; WHY THE THIRD IS NOT A KEY ON THE MACHINE. A machine is a property of the
;; RESOURCE; whether a particular call site's binding survives is a property of
;; the CALLING CONVENTION of one operation. `close!` moving the handle in one
;; API and mutating it in place in another is the same machine either way. That
;; is exactly the distinction `:linearity :once` collapsed.

(def obligation-key :obligation)

(def obligation-values
  "The three obligation deltas an edge may declare. `nil` means DERIVE, and
  derivation reproduces the pre-E33 behaviour byte for byte."
  #{:acquire :retain :discharge})

;; --- OBLIGATION TRANSFER TO AND FROM A HOLDER (E41) -------------------------
;;
;; `:consumes` plus no `:produces` means consumed and gone. `:consumes` plus a
;; `:produces` result means consumed and returned. Neither says that a capability
;; remains live while held inside another capability. E41's region experiment
;; needs that third case in both directions:
;;
;;   :absorbs [{:cap Member :state :open :arg 2 :holder-arg 0}]
;;   :emits   [{:cap Member :state :open :at [1] :holder-arg 0}]
;;
;; `:holder-arg` names the argument containing the tracked holder. The holder
;; must itself be consumed and produced by the same operation. An absorbed
;; member is consumed from `:arg` but is not gone; an emitted member comes from
;; the holder and lands at its result position. In both cases the member's
;; machine edge preserves the state written on the transfer entry. The notation
;; transfers the obligation; it does not expose or statically enumerate the
;; holder's run-time membership.

(def transfer-keys
  "The two E41 operation-annotation keys that transfer a capability obligation."
  [:absorbs :emits])

;; --- RESULT-LABELLED TRANSITIONS (E33: the established syntax) ---------------
;;
;; Both round-2 surveys independently name the same primitive relation, and it
;; is NOT the operation-keyed one perturb had:
;;
;;   (capability, operation, source-state, result-label)
;;       -> destination-state + obligation delta
;;
;; Mungo/StMungo spells it directly — `Status open(): <OK: Open, ERROR: end>`,
;; `BooleanEnum hasNext(): <TRUE: Read, FALSE: Close>` — Vault keys transitions
;; by source AND destination, and Fugue computes postconditions from receiver,
;; state, arguments and results. A transition may therefore carry a `:result`:
;;
;;   {:op 'lib/close! :from [:open :half-closed] :result :won  :to :closed}
;;   {:op 'lib/close! :from :closed              :result :lost :to :closed}
;;
;; WHAT THE LABEL IS FOR, AND WHAT IT IS NOT FOR. It is not a value the checker
;; can observe: there is no result domain here and a label is never READ off a
;; call. What it does is make the relation a FUNCTION of `(operation, source,
;; label)` where before it had to be a function of the operation alone, and that
;; buys three things:
;;
;;   - a destination that DEPENDS ON THE SOURCE is now sayable, because the
;;     annotation stops repeating the machine (see `:state` omission below).
;;     E30 finding 3's `from-to-pairing` defect — 6 diagnostics, an operation
;;     with distinct `:from`s and correspondingly distinct `:to`s failing all
;;     groups but one — is that gap;
;;   - two labels from ONE source that share a destination and an obligation
;;     delta are NOT a sum. That is `close!` exactly: `:won` and `:lost` both
;;     land in `:closed` and both discharge, so the boolean is a RACE WITNESS
;;     and not a second close (E33, confirmed);
;;   - two labels from one source that DIFFER are a sum, and the sum `:to` plus
;;     `:perturb.cap/discriminator` machinery is the FALLBACK for it. Correctly
;;     implemented, wrongly prioritised — E33's words, and `report-limits` 14(f)
;;     was right to be uneasy.
;;
;; SOURCE COLLECTIONS ARE SUGAR, AND ONLY WHERE THEY ARE SUGAR. `:from [:a :b]`
;; expands to two edges sharing a destination and an obligation delta. If the
;; same operation ALSO declares an edge from `:a` with a different destination,
;; the collection is not sugar any more — it is hiding a source-dependent
;; destination behind a shorthand. `perturb.check/check-edge-overlap!` refuses
;; that where it is written.

(def result-key :result)

(defn refinements
  "[capability operation] -> a VECTOR of that operation's refinement-carrying
  transitions, each entry being the transition's `:perturb.cap/refine` map with
  the edge's `:from` and `:to` added, over every declaration in `decls`.

  Keyed by capability AND operation on purpose: `spec`'s primitive table is
  keyed by operation alone and therefore loses one of the two machines an
  operation like `body-finish!` advances (E18 finding 1a). A refinement that
  inherited that defect would be attached to the wrong machine.

  A VECTOR AND NOT ONE ENTRY — THE DEFECT THE PRIMITIVE TABLE SHED, SHED HERE
  TOO. Since E26 finding 7 a group of transitions may share a [capability
  operation] key with different `:to`s. This table was built with `assoc`, so
  two summands each carrying a `:perturb.cap/refine` would collide and the one
  declared LAST would silently win — the other's obligation would never be
  discharged and the program would be accepted on it. That was recorded as
  `report-limits` item 14(j) rather than fixed, on the grounds that nothing
  declared such a pair; `perturb.dbtxcorpus/Meter` now does, and it is a
  REJECTION that the old table turned into an accept.

  The consumers do the rest: `perturb.check` discharges EVERY applicable
  summand's `:requires` (the call may take any of them, so all must hold) and
  widens a ghost variable to unknown when the applicable summands disagree
  about its `:update`."
  [decls]
  (reduce (fn [acc e]
            (let [cap-name (first e)
                  ts       (:perturb.cap/typestate (second e))]
              (reduce (fn [a t]
                        (if (contains? t refine-key)
                          (update a [cap-name (:op t)]
                                  (fn [v]
                                    (conj (or v [])
                                          (assoc (get t refine-key)
                                                 :from (:from t) :to (:to t)))))
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
