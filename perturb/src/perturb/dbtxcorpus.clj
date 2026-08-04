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

  The three arms end at :closed, at [:poisoned :closed] and at :closed. The
  middle one is a sum, and it is accepted at scope exit only because BOTH its
  members are terminal."
  [path outcome]
  (let [t0 (db/connect path outcome)
        t1 (db/begin t0)]
    (if (db/autocommit? t1)
      (do (db/unwind! t1) :no-transaction)
      (if (db/poisoned? t1)
        (do (db/abort! t1) :poisoned)
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
;; REJECT — the program side
;; ===========================================================================

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

   {:var 'perturb.dbtxcorpus/begin-then-commit :expect :reject
    :kind :state-unresolved}
   {:var 'perturb.dbtxcorpus/resolve-one-summand-only :expect :reject
    :kind :state-unresolved}
   {:var 'perturb.dbtxcorpus/probe! :expect :reject
    :kind :annotation-inconsistent}
   {:var 'perturb.dbtxcorpus/wide-probe! :expect :reject
    :kind :annotation-inconsistent}])
