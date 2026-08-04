(ns perturb.dbtxcorpus
  "The acceptance corpus for a SUM `:to` — one operation with three destinations
  chosen at run time, and the case split that eliminates it.

  Same contract as `perturb.corpus` and `perturb.httpcorpus`: real perturb source
  compiled by Jolt, checked from the IR the back end was handed, `expectations`
  below records the verdict the gate requires, and EVERY ACCEPT IS ALSO RUN.

  WHAT EACH ENTRY IS FOR. The feature is opt-in — an operation whose machine
  declares a single `:to` behaves exactly as it did before it existed — so every
  rejection here has to be attributable to the sum and to nothing else. The five
  program entries are therefore written as three PAIRS that differ by one line:

    begin-with-full-case-split   accept   two nested discriminators, three arms
    resolve-one-summand-only     reject   the same program, inner test deleted
    begin-then-close             accept   no case split, a `:from :any` destructor
    begin-then-commit            reject   the same program, an :open-only operation

  and two declaration entries — `probe!` and `wide-probe!` — which name no
  program at all and are rejected where the annotation is WRITTEN, one for
  under-covering the declared destinations and one for over-covering them.

  WHAT NONE OF THIS ESTABLISHES. Nothing here reads `perturb.dbtx`'s bodies: the
  transitions are axioms, the discriminator is an axiom, and an accept means the
  program is consistent with a declaration nobody checked. See
  `perturb.check/report-limits` items 1 and 14."
  (:require [perturb.dbtx :as db]
            [perturb.cap :as cap]))

;; ===========================================================================
;; ACCEPT
;; ===========================================================================

(defn begin-with-full-case-split
  "ACCEPT, AND IT RUNS. `begin` leaves the handle in [:idle :open :poisoned] and
  TWO NESTED DISCRIMINATORS take it apart:

    autocommit?  true  -> :idle              `unwind!` is legal there and nowhere else
                 false -> [:open :poisoned]
    poisoned?    true  -> :poisoned          `abort!` is legal there and nowhere else
                 false -> :open              `commit!` is legal there and nowhere else

  Each arm calls an operation that ONE summand admits, and every one of the three
  would be `state-unresolved` if the test above it were removed — which is
  exactly what `resolve-one-summand-only` below is.

  THE MIDDLE ARM WAS REWRITTEN, AND E26 FINDING 7 PREDICTED IT WOULD HAVE TO BE.
  As first written it read `(do (db/abort! t1) :poisoned)` and ended at the sum
  [:poisoned :closed], which was accepted at scope exit only because
  `perturb.dbtx` declared BOTH members terminal. E27 settled that `:poisoned` is
  not terminal — a poisoned transaction is still owed a close — and the moment
  `:terminal` shrank to [:closed] this arm became a `dangling` on
  perturb.dbtx/Tx@:poisoned. Finding 7 recorded that in advance rather than
  papering over it. The arm now DISPOSES OF the handle, which is what the
  corrected model requires, and the leak it used to be is a fixture of its own:
  `abort-and-never-close`.

  All three arms now end at :closed."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)]
    (if (db/autocommit? t1)
      (do (db/unwind! t1) :no-transaction)
      (if (db/poisoned? t1)
        (do (db/close! t1) :poisoned)
        (do (db/close! (db/commit! t1)) :committed)))))

(defn begin-then-close
  "ACCEPT, AND IT RUNS. No case split anywhere, and none is needed: `close!`
  declares `:from :any`, which admits every member of the sum, so there is
  nothing for a discriminator to resolve. This is the useful consequence of
  stating the rule as `every member must be admitted` rather than `a sum may not
  be used` — a destructor is total, and a total operation needs no case split."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)
        t2 (db/close! t1)]
    :done))

;; ===========================================================================
;; ACCEPT — cancellation (§4.6 piece 5)
;; ===========================================================================

(defn abort-then-close
  "ACCEPT, AND IT RUNS. The straight-line shape of the whole mechanism: a handle
  is acquired, the protocol is ABANDONED before it is finished, and the handle
  is then disposed of.

    connect  ->  :idle
    abort!   ->  :poisoned      the DECLARED CANCELLED state
    close!   ->  :closed        the only edge out of it, and terminal

  Nothing new in the checker made this accept: `abort!` is an ordinary edge and
  `close!` is the `:from :any` destructor E26 finding 7 already built. That is
  the point of piece 5 — it needs no new term. What is new is everything the
  three programs below are refused for."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/abort! t0)
        t2 (db/close! t1)]
    :abandoned))

(defn abort-the-open-transaction
  "ACCEPT, AND IT RUNS. The same, mid-protocol and after a case split, which is
  the shape a real abandonment has: the sum out of `begin` is resolved, and the
  arm that HAS an open transaction gives up on it instead of committing.

  `abort!` declares `:from [:idle :open]` — a collection on the `:from` side,
  which has meant `any of` since E5 and is untouched by the sum work. The
  :poisoned arm needs no `abort!`: `begin` already cancelled that handle, and
  the two directions meet at one state with one way out."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)]
    (if (db/autocommit? t1)
      (do (db/unwind! t1) :no-transaction)
      (if (db/poisoned? t1)
        (do (db/close! t1) :poisoned-by-begin)
        (do (db/close! (db/abort! t1)) :abandoned)))))

;; ===========================================================================
;; REJECT — the program side
;; ===========================================================================

(defn abort-then-commit
  "REJECT, `cancelled-use`. AFTER `abort!`, EVERY OPERATION EXCEPT THE DESTRUCTOR
  IS REFUSED. `commit!` is declared `:from :open` and the handle is `:poisoned`.

  The rejection needs no new rule — the typestate rule already refuses it — and
  it gets a new KIND anyway, because `typestate` here would be true and useless.
  It would say the handle is in :poisoned and the operation wants :open, as if
  the program had picked a bad moment. What happened is that the protocol was
  abandoned, and the diagnostic says so, prints the only operation the state
  admits, and says that reaching :poisoned discharged nothing."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/abort! t0)]
    (db/commit! t1)
    :committed))

(defn abort-and-never-close
  "REJECT, `dangling`. THE ENTIRE POINT OF THE MECHANISM, STATED NEGATIVELY.

  Reaching the cancelled state must NOT discharge the obligation. If it did,
  this program would type-check and the mechanism would be silent discard with
  extra steps — which is exactly what Fowler §1.3 rejects when it rules out
  affine types, and exactly the trap §4.6 piece 5 has to avoid to be worth
  anything.

  It is also the direct measurement of Task 1(b): under the OLD declaration,
  with `:terminal [:poisoned :closed]`, this program is ACCEPTED. Under E27's
  corrected reading it leaks, and the diagnostic says why in the words of the
  declaration rather than as a generic scope-exit complaint."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/abort! t0)]
    :abandoned))

(defn begin-then-commit
  "REJECT, `state-unresolved`. `begin-then-close` with `close!` replaced by
  `commit!`, which is declared `:from :open` and therefore admits exactly one of
  the three destinations. The diagnostic must say the capability is in a SUM
  state, list the three members, name the two that are not admitted, and print
  the discriminators that could have resolved them — a bare `typestate` verdict
  here would be true and useless, because the program is not wrong about a
  state, it is missing a case split."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)]
    (db/commit! t1)
    :committed))

(defn resolve-one-summand-only
  "REJECT, `state-unresolved`, and this is the entry that says the narrowing is
  not a rubber stamp. It is `begin-with-full-case-split` with the INNER test
  deleted: the `autocommit?` split resolves :idle in the then arm, and the else
  arm still holds [:open :poisoned] where `commit!` admits only :open.

  One diagnostic, not two: the then arm's `unwind!` is accepted, which is the
  narrowing working, and the else arm's `commit!` is refused, which is the sum
  surviving one test out of the two it needs."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)]
    (if (db/autocommit? t1)
      (do (db/unwind! t1) :no-transaction)
      (do (db/commit! t1) :committed))))

;; ===========================================================================
;; REJECT — the declaration side
;; ===========================================================================
;;
;; Two capabilities that name no code beyond the one-line operation each
;; declares, in the spirit of `perturb.httpcorpus/declaration-corpus`: the fault
;; is in the ANNOTATION, and it is visible without reading a body. They are here
;; rather than in that corpus because the gate that runs them checks a var, and a
;; var is what a reader would have written by hand and got wrong.

(def probe-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.dbtxcorpus/Probe
       :perturb.cap/doc        "A machine whose one operation has three destinations."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states   [:idle :a :b :c]
        :initial  :idle
        :terminal [:c]
        :transitions [{:op 'perturb.dbtxcorpus/probe! :from :idle :to :a}
                      {:op 'perturb.dbtxcorpus/probe! :from :idle :to :b}
                      {:op 'perturb.dbtxcorpus/probe! :from :idle :to :c}]}
       :perturb.cap/representation []
       :perturb.cap/locality :dropped-by-design})))

(defn probe!
  "REJECT, `annotation-inconsistent`. UNDER-COVERING: the machine declares three
  destinations for this edge and the annotation names one. This is the shape the
  old rule could not even see — it read a single `:state` out of `:produces` and
  compared it against whichever single `:to` the primitive table happened to
  hold, and with the table keyed `[capability operation]` by `assoc` that was
  whichever entry was declared last.

  Nothing calls this and its body is never checked: `probe!` is a declared
  transition, so it is an AXIOM. The rejection is entirely in the declaration."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Probe :state :idle :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Probe :state :a}]}}
  [p] p)

(cap/annotate-op! (var probe!) (:perturb.cap/op (meta (var probe!))))

(def wide-probe-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.dbtxcorpus/WideProbe
       :perturb.cap/doc        "The same, for the over-covering half of the rule."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states   [:idle :a :b :c :d]
        :initial  :idle
        :terminal [:c :d]
        :transitions [{:op 'perturb.dbtxcorpus/wide-probe! :from :idle :to :a}
                      {:op 'perturb.dbtxcorpus/wide-probe! :from :idle :to :b}
                      {:op 'perturb.dbtxcorpus/wide-probe! :from :idle :to :c}]}
       :perturb.cap/representation []
       :perturb.cap/locality :dropped-by-design})))

(defn wide-probe!
  "REJECT, `annotation-inconsistent`. OVER-COVERING: the annotation claims a
  destination `:d` the machine does not declare. Equality as a SET is the rule in
  both directions — an annotation that promises less than the machine can do
  leaves a caller unprepared, and one that promises more forces case splits on
  states that cannot happen and, worse, would let a `:from :d` operation be
  reached with no edge that ever gets there."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/WideProbe :state :idle :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/WideProbe :state [:a :b :c :d]}]}}
  [p] p)

(cap/annotate-op! (var wide-probe!) (:perturb.cap/op (meta (var wide-probe!))))

;; ===========================================================================
;; REJECT — TWO SUMMANDS, TWO REFINEMENTS. The fixture for the last table that
;; still had E26 finding 7's defect.
;; ===========================================================================
;;
;; `perturb.cap/refinements` was keyed [capability operation] with `assoc`. Two
;; edges of one group each carrying a `:perturb.cap/refine` collided and the one
;; declared LAST silently won. `perturb.check/report-limits` recorded that as
;; item 14(j) — "nothing declares such a pair today" — rather than fixing it.
;;
;; This declares such a pair, and it is arranged so that the defect is an ACCEPT
;; rather than a differently-worded rejection, which is the only version of the
;; fixture that is worth anything:
;;
;;   the REFUTED obligation is declared FIRST, so `assoc` dropped it;
;;   the VALID obligation is declared SECOND, so `assoc` kept it and the
;;     program discharged an obligation it does not owe and none that it does.
;;
;; Restore the `assoc` and this entry flips from reject to accept. That is the
;; negative control, and it is the same shape as the one E26 finding 7 used on
;; the primitive table.

(def meter-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.dbtxcorpus/Meter
       :perturb.cap/doc        "One operation, two destinations, a refinement on each."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states   [:fresh :lo :hi :closed]
        :initial  :fresh
        :terminal [:closed]
        :transitions
        [{:op 'perturb.dbtxcorpus/meter-open :from nil :to :fresh}
         ;; DECLARED FIRST. Under `assoc` this entry was overwritten by the one
         ;; below and its obligation was never discharged.
         {:op 'perturb.dbtxcorpus/meter-split! :from :fresh :to :lo
          :perturb.cap/refine
          '{:name     lo-arm-needs-one-octet-of-slack
            :requires (= budget (+ spent 1))}}
         {:op 'perturb.dbtxcorpus/meter-split! :from :fresh :to :hi
          :perturb.cap/refine
          '{:name     hi-arm-needs-the-budget-exactly
            :requires (= budget spent)}}
         {:op 'perturb.dbtxcorpus/meter-close! :from :any :to :closed}]}
       :perturb.cap/representation []
       :perturb.cap/locality :dropped-by-design})))

(defn meter-open
  "Mints the ghost state on the `:produces` entry, as `respond-begin` does."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.dbtxcorpus/Meter :state :fresh
                                :perturb.cap/refine '{:init {budget 4 spent 4}}}]}}
  []
  {:perturb.cap/capability 'perturb.dbtxcorpus/Meter
   :perturb.dbtxcorpus/state :fresh})

(defn meter-split!
  "THE SUM WITH A REFINEMENT ON EACH SUMMAND. Both edges share `:op` and `:from`
  and both carry a `:perturb.cap/refine`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Meter :state :fresh :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Meter :state [:lo :hi]}]}}
  [m] m)

(defn meter-close!
  "The totally-admitting destructor, so the sum needs no case split and the only
  thing this fixture can be rejected for is the refinement."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Meter :state :any :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Meter :state :closed}]}}
  [m] m)

(cap/annotate-op! (var meter-open)   (:perturb.cap/op (meta (var meter-open))))
(cap/annotate-op! (var meter-split!) (:perturb.cap/op (meta (var meter-split!))))
(cap/annotate-op! (var meter-close!) (:perturb.cap/op (meta (var meter-close!))))

(defn meter-split-refutes-one-summand
  "REJECT, `refinement`. `budget` and `spent` are both 4, so the SECOND
  summand's `(= budget spent)` is valid and the FIRST summand's
  `(= budget (+ spent 1))` is REFUTED — a decided counterexample, not a failure
  to prove.

  The call takes one of the two destinations and which one is not knowable until
  run time, so a correct program must satisfy BOTH. That is the totality rule
  `state-ok?` already applies on the typestate side, moved to the refinement
  tier. With the old table only one obligation existed and this program was
  accepted."
  []
  (let [m0 (meter-open)
        m1 (meter-split! m0)
        m2 (meter-close! m1)]
    :done))

;; ===========================================================================
;; REJECT — the CANCELLED-STATE declaration rule (§4.6 piece 5)
;; ===========================================================================
;;
;; THE DECLARATION MUST NOT BE ABLE TO LIE. Every other key in a declaration is
;; an axiom: `:from`/`:to` are believed and so is a discriminator, because they
;; are claims about what a BODY does and the body is not read. A cancelled state
;; is a claim about what is IMPOSSIBLE — that nothing but the destructor is
;; legal from it — and that claim is settled by the `:from`s written in the same
;; map. So it is checked, and these are the ways it can be false.
;;
;; These machines are NOT registered: they are data handed to
;; `perturb.check/check-cancellation-declarations!` by the gate, exactly as
;; `perturb.httpcorpus/declaration-corpus` hands it annotations. Registering
;; them would make the shipped declaration set unsound, which is the thing the
;; rule exists to prevent.

(defn- machine
  [nm ts cancelled]
  {:perturb.cap/name       nm
   :perturb.cap/uniqueness :unique
   :perturb.cap/linearity  :once
   :perturb.cap/contention :thread-confined
   :perturb.cap/typestate  ts
   :perturb.cap/cancelled  cancelled
   :perturb.cap/representation []
   :perturb.cap/locality :dropped-by-design})

(def cancellation-corpus
  "Six machines, one key each. `:expect` is the exact SET of diagnostic kinds.

  The first is WELL FORMED and expects nothing, because a rule that rejected
  every cancelled state would pass five of these six and be useless."
  [{:name 'only-the-destructor-leaves-it
    :declarations
    {'fixture/Good
     (machine 'fixture/Good
              {:states   [:idle :cancelled :closed]
               :initial  :idle
               :terminal [:closed]
               :transitions [{:op 'fixture/open     :from nil       :to :idle}
                             {:op 'fixture/give-up! :from :idle     :to :cancelled}
                             {:op 'fixture/close!   :from :any      :to :closed}]}
              :cancelled)}
    :expect []}

   {:name 'cancelled-state-has-a-non-destructor-edge
    :declarations
    {'fixture/Retryable
     (machine 'fixture/Retryable
              {:states   [:idle :cancelled :closed]
               :initial  :idle
               :terminal [:closed]
               :transitions [{:op 'fixture/open     :from nil       :to :idle}
                             {:op 'fixture/give-up! :from :idle     :to :cancelled}
                             ;; THE LIE. An ordinary operation out of the state
                             ;; that is supposed to admit only its destructor.
                             {:op 'fixture/retry!   :from :cancelled :to :idle}
                             {:op 'fixture/close!   :from :any      :to :closed}]}
              :cancelled)}
    :expect [:cancelled-state-unsound]}

   {:name 'cancelled-state-is-terminal
    :declarations
    {'fixture/Discarding
     (machine 'fixture/Discarding
              {:states   [:idle :cancelled :closed]
               :initial  :idle
               ;; reaching it would DISCHARGE the obligation: silent discard
               ;; with extra steps, which is what Fowler §1.3 rejects.
               :terminal [:cancelled :closed]
               :transitions [{:op 'fixture/open     :from nil       :to :idle}
                             {:op 'fixture/give-up! :from :idle     :to :cancelled}
                             {:op 'fixture/close!   :from :any      :to :closed}]}
              :cancelled)}
    :expect [:cancelled-state-unsound]}

   {:name 'nothing-reaches-the-cancelled-state
    :declarations
    {'fixture/Decorative
     (machine 'fixture/Decorative
              {:states   [:idle :cancelled :closed]
               :initial  :idle
               :terminal [:closed]
               :transitions [{:op 'fixture/open   :from nil  :to :idle}
                             {:op 'fixture/close! :from :any :to :closed}]}
              :cancelled)}
    :expect [:cancelled-state-unsound]}

   {:name 'nothing-leaves-the-cancelled-state
    :declarations
    {'fixture/Inescapable
     (machine 'fixture/Inescapable
              {:states   [:idle :cancelled :closed]
               :initial  :idle
               :terminal [:closed]
               ;; the destructor is `:from :idle`, so a cancelled capability can
               ;; never be disposed of and every program holding one leaks.
               :transitions [{:op 'fixture/open     :from nil   :to :idle}
                             {:op 'fixture/give-up! :from :idle :to :cancelled}
                             {:op 'fixture/close!   :from :idle :to :closed}]}
              :cancelled)}
    :expect [:cancelled-state-unsound]}

   {:name 'cancelled-state-is-not-a-state
    :declarations
    {'fixture/Absent
     (machine 'fixture/Absent
              {:states   [:idle :closed]
               :initial  :idle
               :terminal [:closed]
               :transitions [{:op 'fixture/open   :from nil  :to :idle}
                             {:op 'fixture/close! :from :any :to :closed}]}
              :zombie)}
    :expect [:cancelled-state-unsound]}])

;; ===========================================================================
;; what the checker must say — and, for every accept, that it RUNS
;; ===========================================================================

(def expectations
  "Same contract as the other two corpora. `:run` is the argument list the gate
  calls an accepted program with; the second argument is which of `begin`'s three
  destinations the run takes, so the two accepts between them execute the R0 arm
  (`:took-effect`, through commit! and close!) and the R3-R7 arm
  (`:unresolvable`, through the total destructor). Nothing runs the R1/R2 arm:
  `run-accepts` calls each accepted program ONCE, and that is the same limit
  `perturb.corpus/both-arms-close` has always had."
  [{:var 'perturb.dbtxcorpus/begin-with-full-case-split :expect :accept
    :run ["case-split" :took-effect]}
   {:var 'perturb.dbtxcorpus/begin-then-close :expect :accept
    :run ["destructor" :unresolvable]}
   {:var 'perturb.dbtxcorpus/abort-then-close :expect :accept
    :run ["abandon" :proven-clean]}
   {:var 'perturb.dbtxcorpus/abort-the-open-transaction :expect :accept
    :run ["abandon-open" :took-effect]}

   {:var 'perturb.dbtxcorpus/begin-then-commit :expect :reject
    :kind :state-unresolved}
   {:var 'perturb.dbtxcorpus/resolve-one-summand-only :expect :reject
    :kind :state-unresolved}
   {:var 'perturb.dbtxcorpus/probe! :expect :reject
    :kind :annotation-inconsistent}
   {:var 'perturb.dbtxcorpus/wide-probe! :expect :reject
    :kind :annotation-inconsistent}

   ;; §4.6 piece 5 — cancellation as a state.
   {:var 'perturb.dbtxcorpus/abort-then-commit :expect :reject
    :kind :cancelled-use}
   {:var 'perturb.dbtxcorpus/abort-and-never-close :expect :reject
    :kind :dangling}

   ;; the last table that still had E26 finding 7's defect.
   {:var 'perturb.dbtxcorpus/meter-split-refutes-one-summand :expect :reject
    :kind :refinement}])
