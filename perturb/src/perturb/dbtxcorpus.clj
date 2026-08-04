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

  AND SINCE E33, THE THREE-NOTION MODEL. `Sock` is a second machine in this file
  and a different subject: a stable handle with an IDEMPOTENT compare-and-set
  destructor, an ABSORBING terminal state, observers legal after close, and a
  destination that depends on its SOURCE. It is `teensyp.client/Connection`'s
  shape in miniature, so the four things E30 measured `:linearity :once` failing
  at are gated here on perturb's own source rather than only reported against a
  library. `machine-corpus` is the declaration-level half: two well-formed
  controls and four machines that contradict their own claims.

  WHAT NONE OF THIS ESTABLISHES. Nothing here reads `perturb.dbtx`'s bodies: the
  transitions are axioms, the discriminator is an axiom, and an accept means the
  program is consistent with a declaration nobody checked. See
  `perturb.check/report-limits` items 1, 14 and 16-18."
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
;; THE THREE NOTIONS, SEPARATED — a handle, its state, and its obligation
;; ===========================================================================
;;
;; PERTURB-DESIGN E33's conceptual correction, and E30's measurement of what
;; conflating them costs, as PROGRAMS rather than as fixtures. `Sock` is a
;; miniature of `teensyp.client/Connection` — the jolt-tcp client connection
;; §5 step 1a checks — reduced to the four shapes E30 says `:linearity :once`
;; cannot express, and written in perturb's own source so the gate can both
;; CHECK it and RUN it:
;;
;;   1. AN IDEMPOTENT DESTRUCTOR AT A TERMINAL STATE. `sock-close!` is legal
;;      from `:closed` again and returns a RACE WITNESS, not a second close.
;;      Two result labels, `:won` and `:lost`, sharing one destination and one
;;      obligation delta — which is why this is not a sum and needs no case
;;      split (E33: "the boolean is a race witness selecting the one logical
;;      transition", confirmed by both surveys).
;;   2. OBSERVERS LEGAL IN THE TERMINAL STATE. `sock-closed?` and `sock-info`
;;      borrow at `:state :any` and are legal after the destructor, because the
;;      NAME is alive — that being a different axis from the STATE and from the
;;      OBLIGATION. Under `:linearity :once` they drew `use-after-move`, and
;;      E30 records that no `:state` value could rescue them.
;;   3. A DESTINATION THAT DEPENDS ON ITS SOURCE. `sock-shutdown!` has four
;;      `:from`s with four correspondingly different `:to`s — exactly
;;      `teensyp.client/shutdown-write!`'s shape, which E30 finding 3 measured
;;      as 6 `annotation-inconsistent` diagnostics because one annotation had to
;;      be compared against every group. Its annotation now OMITS `:state` and
;;      the machine answers, so there is nothing left to disagree with.
;;   4. IN-PLACE TYPESTATE. Every transition here is `:consumes … :arg 0` plus
;;      `:produces … :arg 0`: the handle is a stable name and changes state
;;      where it stands. That key used to be accepted and silently ignored
;;      (tally row 50).
;;
;; AND THE OBLIGATION IS STILL LOAD-BEARING, which is the half that has to keep
;; working: `sock-never-closed` and `sock-observed-but-never-closed` are
;; `dangling`, because an absorbing terminal state discharges the debt only when
;; the destructor is actually reached. Observing a handle is not disposing of it.

(def sock-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.dbtxcorpus/Sock
       :perturb.cap/doc
       "A stable handle with an idempotent destructor and an absorbing terminal
        state — teensyp.client/Connection's shape, in miniature."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states   [:open :read-done :half :half-done :closed]
        :initial  :open
        :terminal [:closed]
        :transitions
        [{:op 'perturb.dbtxcorpus/sock-connect :from nil :to :open}

         ;; reading to EOF: one source, one destination, no label needed.
         {:op 'perturb.dbtxcorpus/sock-drain! :from :open :to :read-done}

         ;; THE SOURCE-DEPENDENT DESTINATION (E30 finding 3). Four edges, four
         ;; sources, four destinations, and two result labels — `:first` for the
         ;; call that performed the half-close and `:again` for one that found it
         ;; already done. The labels do not partition the destination here; the
         ;; SOURCE does, and that is the case the operation-keyed relation could
         ;; not state.
         {:op 'perturb.dbtxcorpus/sock-shutdown! :from :open      :result :first
          :to :half}
         {:op 'perturb.dbtxcorpus/sock-shutdown! :from :read-done :result :first
          :to :half-done}
         {:op 'perturb.dbtxcorpus/sock-shutdown! :from :half      :result :again
          :to :half}
         {:op 'perturb.dbtxcorpus/sock-shutdown! :from :half-done :result :again
          :to :half-done}

         ;; THE ABSORBING TERMINAL STATE. `:won` is the caller that began the
         ;; close; `:lost` is one that found another had. Both land in `:closed`
         ;; and both discharge, so the two labels COLLAPSE and the second call is
         ;; not a sum, not a second close, and not a use-after-move.
         {:op 'perturb.dbtxcorpus/sock-close! :from [:open :read-done :half :half-done]
          :result :won  :to :closed}
         {:op 'perturb.dbtxcorpus/sock-close! :from :closed
          :result :lost :to :closed}]}
       :perturb.cap/representation
       ['perturb.dbtxcorpus/sock 'perturb.dbtxcorpus/sock-state]
       :perturb.cap/locality :dropped-by-design})))

(def ^:private S 'perturb.dbtxcorpus/Sock)

(defn sock
  "The concrete handle. Inside the representation."
  [state] {:perturb.cap/capability S :perturb.dbtxcorpus/state state})

(defn sock-state
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtxcorpus/Sock :state :any :arg 0}]}}
  [s] (:perturb.dbtxcorpus/state s))

(defn sock-connect
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.dbtxcorpus/Sock :state :open}]}}
  [] (sock :open))

(defn sock-drain!
  "A transition whose annotation writes NEITHER state: both are read off the
  machine. One source, one destination, so this is the degenerate case of the
  same mechanism `sock-shutdown!` needs four edges for."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]}}
  [s] (sock :read-done))

(defn sock-shutdown!
  "IDEMPOTENT, AND NOT AT A TERMINAL STATE — which is the shape that was always
  expressible (E30's own corroboration: `shutdown-write!` is a self-loop on
  `:write-shut` and perfectly declarable). What was NOT expressible is the
  source-dependent destination beside it, and that is what omitting `:state`
  buys."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]}}
  [s] (sock (if (= :read-done (sock-state s)) :half-done :half)))

(defn sock-close!
  "THE COMPARE-AND-SET DESTRUCTOR. Returns the handle unchanged in the concrete
  world — the real one returns a BOOLEAN, and the annotation no longer has to
  lie about that, because `:produces … :arg 0` says the capability stays where
  it is instead of claiming it is the return value."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]
                    :produces [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]}}
  [s] (sock :closed))

(defn sock-closed?
  "A pure OBSERVER, legal in every state INCLUDING the terminal one. It borrows,
  so it neither moves the machine nor touches the obligation — and it must not,
  which is E33's side condition on the absorbing state."
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtxcorpus/Sock :state :any :arg 0}]}}
  [s] (= :closed (sock-state s)))

(defn sock-send!
  "The guard that IS statically checkable: illegal once the write side is shut,
  and illegal after close. `:borrows` with a state set, costing nothing."
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtxcorpus/Sock
                               :state [:open :read-done] :arg 0}]}}
  [s _] :sent)

(cap/annotate-op! (var sock-state)    (:perturb.cap/op (meta (var sock-state))))
(cap/annotate-op! (var sock-connect)  (:perturb.cap/op (meta (var sock-connect))))
(cap/annotate-op! (var sock-drain!)   (:perturb.cap/op (meta (var sock-drain!))))
(cap/annotate-op! (var sock-shutdown!) (:perturb.cap/op (meta (var sock-shutdown!))))
(cap/annotate-op! (var sock-close!)   (:perturb.cap/op (meta (var sock-close!))))
(cap/annotate-op! (var sock-closed?)  (:perturb.cap/op (meta (var sock-closed?))))
(cap/annotate-op! (var sock-send!)    (:perturb.cap/op (meta (var sock-send!))))

;; --- ACCEPT ----------------------------------------------------------------

(defn sock-close-twice-then-observe
  "ACCEPT, AND IT RUNS. THE ACID TEST, in perturb's own source.

  This is `teensyp.client-test/public-client-loopback-contract`'s `finally`
  block, which E30 measured as FOUR `use-after-move` diagnostics against a
  program the library's own suite runs against a real loopback socket:

    (is (true?  (client/close! connection)))
    (is (false? (client/close! connection)))
    (is (client/closed? connection))
    (is (= :closed (:state (client/connection-info connection))))

  Every one of those four was refused, and E30's decisive point is that
  `closed?` and `connection-info` are declared legal in EVERY state and were
  refused anyway — because `use-after-move` is about the NAME being dead, not
  about the state. Separating the name from the state from the obligation is the
  whole of what makes this accept."
  []
  (let [s (sock-connect)]
    (sock-close! s)
    (sock-close! s)
    (sock-closed? s)
    (sock-state s)
    :done))

(defn sock-shutdown-twice-then-close
  "ACCEPT, AND IT RUNS. Idempotence AWAY from the terminal state — the half that
  was always expressible — followed by the destructor. `:open -> :half` then
  `:half -> :half`, two different result labels, one annotation."
  []
  (let [s (sock-connect)]
    (sock-send! s "before")
    (sock-shutdown! s)
    (sock-shutdown! s)
    (sock-close! s)
    :done))

(defn sock-drain-then-shutdown-then-close
  "ACCEPT, AND IT RUNS. THE OTHER SOURCE. `sock-shutdown!` here runs from
  `:read-done` and lands in `:half-done`, not in `:half` — the same operation,
  a different source, a different destination. Under the old rule the single
  annotation had to name ONE `:from`/`:to` pair and therefore had to disagree
  with three of this operation's four groups (E30 finding 3, 6 diagnostics)."
  []
  (let [s (sock-connect)]
    (sock-drain! s)
    (sock-shutdown! s)
    (sock-close! s)
    :done))

;; --- REJECT ----------------------------------------------------------------

(defn sock-never-closed
  "REJECT, `dangling`. THE GENUINE LEAK, AND THE REASON THE OTHER HALF OF THIS
  WORK CANNOT BE A WEAKENING. An absorbing terminal state discharges the
  obligation only when the destructor is REACHED. A handle that never reaches it
  is owed one, whatever else was done to it."
  []
  (let [s (sock-connect)]
    (sock-send! s "hello")
    :done))

(defn sock-observed-but-never-closed
  "REJECT, `dangling`. OBSERVING IS NOT DISPOSING. `sock-closed?` is legal in
  every state and moves nothing, so a program that asks a handle whether it is
  closed and then drops it still owes a close. This is E33's side condition seen
  from the program side: an observer must not re-acquire the resource, and it
  must not discharge it either."
  []
  (let [s (sock-connect)]
    (sock-closed? s)
    (sock-state s)
    :done))

(defn sock-send-after-close
  "REJECT, `typestate`. AN ABSORBING TERMINAL STATE ADMITS ITS DESTRUCTOR AND ITS
  OBSERVERS AND NOTHING ELSE. `sock-send!` borrows at `[:open :read-done]`, so
  after the close it is refused — and it is refused for the RIGHT REASON, with
  the state named, rather than as a use-after-move about a name that is
  demonstrably still usable two lines later."
  []
  (let [s (sock-connect)]
    (sock-close! s)
    (sock-send! s "too late")
    :done))

(defn sock-peek
  "REJECT, `annotation-underived-state`. `:state` MAY ONLY BE OMITTED WHERE THERE
  IS A MACHINE TO READ IT OFF. This function is not a declared transition of
  `Sock`, so `[Sock sock-peek]` has no edges and there is no destination and no
  admissible source to derive. Omitting `:state` here is not `any state`, it is a
  question with no answer, and it is refused where it is written."
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtxcorpus/Sock :arg 0}]}}
  [s] (sock-state s))

(cap/annotate-op! (var sock-peek) (:perturb.cap/op (meta (var sock-peek))))

;; ===========================================================================
;; REJECT — the two E33 MACHINE rules
;; ===========================================================================
;;
;; PERTURB-DESIGN E33 separates three things `:linearity :once` treated as one:
;; the stable handle, its protocol state, and the obligation to discharge the
;; resource. Two of the rules that separation makes possible are claims about a
;; MACHINE and are therefore checkable without reading a body, exactly as the
;; cancelled-state rule is:
;;
;;   (a) THE ABSORBING TERMINAL STATE'S SIDE CONDITION. A terminal state may now
;;       admit its own destructor and its observers as self-loops — obligation
;;       discharged, NAME STILL ALIVE. E33 attaches one condition, and it is the
;;       one the first survey omitted: an observer self-loop MUST NOT RE-ACQUIRE
;;       the discharged resource. An edge out of a discharged state whose
;;       obligation delta is not `:discharge` does exactly that.
;;   (b) SOURCE COLLECTIONS AS SUGAR. `:from [:a :b]` is one edge per member and
;;       is legal only where every member shares a destination and a delta. A
;;       collection that overlaps a differently-sourced edge of the same
;;       operation hides a source-dependent destination behind a shorthand.
;;
;; Two WELL-FORMED controls first, because a rule that rejected every absorbing
;; state or every collection would pass the four negatives and be worthless. The
;; first control is the shape the whole of E30's rung is about: a terminal state
;; with its destructor as a self-loop and an observer beside it.

(defn- plain-machine
  [nm ts]
  {:perturb.cap/name       nm
   :perturb.cap/uniqueness :unique
   :perturb.cap/linearity  :once
   :perturb.cap/contention :thread-confined
   :perturb.cap/typestate  ts
   :perturb.cap/representation []
   :perturb.cap/locality :dropped-by-design})

(def machine-corpus
  "Six machines. `:expect` is the exact SET of diagnostic kinds."
  [{:name 'absorbing-terminal-done-right
    :declarations
    {'fixture/Absorbing
     (plain-machine 'fixture/Absorbing
                    {:states   [:open :closed]
                     :initial  :open
                     :terminal [:closed]
                     :transitions
                     ;; The shape E30 finding 1 named and could not express: the
                     ;; destructor is legal from the terminal state AGAIN, with
                     ;; two result labels that share one destination, so the
                     ;; boolean is a RACE WITNESS and not a second close.
                     [{:op 'fixture/connect :from nil    :to :open}
                      {:op 'fixture/close!  :from :open   :result :won  :to :closed}
                      {:op 'fixture/close!  :from :closed :result :lost :to :closed}]})}
    :expect []}

   {:name 'source-collection-that-is-really-sugar
    :declarations
    {'fixture/Sugar
     (plain-machine 'fixture/Sugar
                    {:states   [:a :b :closed]
                     :initial  :a
                     :terminal [:closed]
                     ;; every member of the collection shares a destination and a
                     ;; delta, which is what makes it sugar and makes it legal.
                     :transitions [{:op 'fixture/open   :from nil     :to :a}
                                   {:op 'fixture/step   :from :a      :to :b}
                                   {:op 'fixture/close! :from [:a :b] :to :closed}]})}
    :expect []}

   {:name 'observer-self-loop-reacquires-the-resource
    :declarations
    {'fixture/Reopening
     (plain-machine 'fixture/Reopening
                    {:states   [:open :closed]
                     :initial  :open
                     :terminal [:closed]
                     :transitions
                     [{:op 'fixture/connect :from nil     :to :open}
                      {:op 'fixture/close!  :from :open   :to :closed}
                      ;; THE LIE E33's side condition names. An edge out of a
                      ;; DISCHARGED state that lands somewhere non-terminal puts
                      ;; the resource back in debt, and nobody owes it.
                      {:op 'fixture/reopen! :from :closed :to :open}]})}
    :expect [:absorbing-state-unsound]}

   {:name 'terminal-self-loop-that-retains-the-obligation
    :declarations
    {'fixture/Retaining
     (plain-machine 'fixture/Retaining
                    {:states   [:open :closed]
                     :initial  :open
                     :terminal [:closed]
                     :transitions
                     [{:op 'fixture/connect :from nil     :to :open}
                      {:op 'fixture/close!  :from :open   :to :closed}
                      ;; The destination IS terminal, so derivation would have
                      ;; said `:discharge`; the declaration overrides it and says
                      ;; the obligation is retained. That is a self-loop on a
                      ;; discharged state that leaves it owing, which is the same
                      ;; fault stated through the obligation axis instead of the
                      ;; state axis — and catching it is the evidence that the
                      ;; two axes really are separate.
                      {:op 'fixture/peek    :from :closed :to :closed
                       :obligation :retain}]})}
    :expect [:absorbing-state-unsound]}

   {:name 'collection-hides-a-source-dependent-destination
    :declarations
    {'fixture/Hiding
     (plain-machine 'fixture/Hiding
                    {:states   [:a :b :closed]
                     :initial  :a
                     :terminal [:closed]
                     :transitions
                     [{:op 'fixture/open  :from nil     :to :a}
                      ;; `:from [:a :b] :to :closed` overlaps `:from :b :to :a`:
                      ;; state :b has two destinations under one operation and the
                      ;; shorthand is what conceals it.
                      {:op 'fixture/step! :from [:a :b] :to :closed}
                      {:op 'fixture/step! :from :b      :to :a}]})}
    :expect [:edge-source-overlap]}

   {:name 'any-overlaps-a-named-source-with-another-destination
    :declarations
    {'fixture/Wildcard
     (plain-machine 'fixture/Wildcard
                    {:states   [:a :b :closed]
                     :initial  :a
                     :terminal [:closed]
                     :transitions
                     [{:op 'fixture/open  :from nil  :to :a}
                      {:op 'fixture/wipe! :from :any :to :closed}
                      {:op 'fixture/wipe! :from :a   :to :b}]})}
    :expect [:edge-source-overlap]}])

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
    :kind :refinement}

   ;; E33 — the three notions separated. THE ACID TEST AND ITS CONTROLS.
   {:var 'perturb.dbtxcorpus/sock-close-twice-then-observe :expect :accept :run []}
   {:var 'perturb.dbtxcorpus/sock-shutdown-twice-then-close :expect :accept :run []}
   {:var 'perturb.dbtxcorpus/sock-drain-then-shutdown-then-close :expect :accept :run []}
   {:var 'perturb.dbtxcorpus/sock-never-closed :expect :reject :kind :dangling}
   {:var 'perturb.dbtxcorpus/sock-observed-but-never-closed :expect :reject
    :kind :dangling}
   {:var 'perturb.dbtxcorpus/sock-send-after-close :expect :reject :kind :typestate}
   {:var 'perturb.dbtxcorpus/sock-peek :expect :reject
    :kind :annotation-underived-state}])
