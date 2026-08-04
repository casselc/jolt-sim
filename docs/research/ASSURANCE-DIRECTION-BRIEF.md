# Brief: the assurance-workbench direction, and what would decide it

**Status: a reading and a work queue, not a decision.** Revision 2, rewritten
after three independent reviews of `PROGRESSIVE-ASSURANCE-ARCHITECTURE.md`,
`CELLULAR-UI-APPLICATION-SKETCHES.md` and
`TEMPORAL-LEDGER-AND-STREAM-SYSTEM-SKETCHES.md` returned. **Revision 1's central
argument was refuted and is struck below; one of its factual claims was simply
false and is corrected.** Both are kept visible rather than deleted, per house
style.

---

## What the three documents are

**One proposal in three parts**, and the parts overlap each other more than any
of them overlaps the record. PROGRESSIVE-ASSURANCE is the method; the other two
restate it in a domain. The temporal-ledger review found §4.1 and §5 to be
near-verbatim restatements of its sibling — the same illustrative APIs
(`perturb.bounded-check!`, `sim/query`), the same SAT/UNSAT/non-vacuity triple,
the same "REPL as assurance workbench" sentence. The UI review found that its
document cites **zero** findings from the corpus: it does not contradict the
measurements by asserting their negation, it never intersects them.

**There is not one measurement across all three.** PROGRESSIVE-ASSURANCE
contains 23 numerals in 587 lines and every one is a schema version, a list
index, or an illustrative literal. Against a record whose standing commitment is
that no claim should be trusted ahead of the artifact that tests it, that is the
primary finding about all three.

Credit where due: the author read the code. The `jolt-sim` descriptions are
source-accurate — the process-explorer reaping claim is verbatim
`process_explorer.clj:328`, and the `run-controlled` hermetic/observe/hybrid
description matches `runtime.clj:1345-1404`.

---

## STRUCK — revision 1's argument for the cellular direction

Revision 1 claimed the cell boundary's relocation of the higher-order cost —
*"you pay the notation cost once, at one runner, instead of at every callee"* —
was the unmade argument for the direction.

**It was made, built, and measured, and it failed.** §1.1's pipeline is E24's
hypothesis verbatim, and tally row 35 is the result: a pure
`(state, event) -> [state', effects]` shape **relocates** the obligations rather
than removing them — 2 of 4 wrong applications accepted, and the working app's
`/wait` route broke the declared machine and reached the wire. You do not pay the
cost once; you move it somewhere the checker cannot see at all.

Worse, the relocation target is itself the refused shape. §1.2's containment
rule — *"capability state remains outside cell input, output, state, trace,
export, and effect-request data"* — **excludes the one shape perturb can check**
(`:arg 0` in, `:at [i]` out, threaded through `let`), and what it routes
authority into is E24's `accept-into-table` character for character: a map keyed
by a runtime id, that grows, dispatched by runtime value (`[:close id]` is
literally E24's refused control), behind a function-valued parameter. **All four
E24 shapes, in one paragraph, in the mechanism the architecture rests on.**

---

## CORRECTED — revision 1's factual error about B6

Revision 1 claimed perturb returns "I will not say" in **four** places:
`perturb.share`'s `:refused`, `perturb.refine`'s `unknown`, arm C's `unknown`,
and B6's `:inconclusive`.

**The fourth does not exist.** `inconclusive` appears nowhere in `layer.clj`;
`perturb.layer` is binary and returns `{:violations … :advisories … :summary …}`.
Verified by reading. And the consequence is worse than a miscount:
`check-error-mapping` folds over refusals *inside a request's extent*, so **on a
trace with zero refusals it emits nothing and reads as a pass on B6.3.** E35's
"known-good passes every clause" is, for at least that clause, a **vacuous pass
by construction**.

This inverts the dependency revision 1 assumed. You cannot collect a residual an
artifact does not emit, so giving `perturb.layer` a third outcome is a
**precondition** for residual routing, not a parallel task.

---

## The weakest load-bearing assumption in all three documents

The thesis is a dichotomy:

> Every assurance layer either discharges an obligation in its stated model or
> exports the residual obligation to the next layer.

**There is a third case, and it is where this project's most valuable findings
came from: a layer silently believes it discharged an obligation it had no name
for.**

- **E18 finding 3** — `short-body-still-type-checks` declares `Content-Length: 6`
  and writes 3. The checker **accepts it and the gate runs it to completion**,
  because the capability reached `:finished`, which is all a state can be asked
  for. The static layer exported nothing, because it did not know it had a
  residual.
- **E38 part 2** — `cross!`'s comment said a lying handler is stopped at the
  boundary. It was false as written; ten forgeries crossed, two silently.
- **E35 finding 1** — the negative control meant to exercise the residual was
  dead code on its fixture.

Residual routing presupposes the residual is **named**. The box of pain is
generated *from* the residual list, so it inherits that list's blind spots. None
of the three documents has a mechanism for finding an unnamed residual, and none
acknowledges that this is the class the record was built by refuting.

---

## Adopt regardless of direction

### 1. The source axis — the one genuinely new idea, and it costs nothing

`simulated` and `runtime` are an **observation source**, orthogonal to strength
and scope: modelled behaviour versus real native calls on a stated target.
Confirmed absent from the record by grep. The document's own qualification is
right — they are not an ordering above `monitored`.

But note the regression the same document commits: its box-of-pain line records
each axis as *"finite/exhausted, sampled, `simulated`, `runtime`, `monitored`, or
omitted"* — scope, strength, source and position mixed in one flat list. Adding a
third axis to a scale already known to conflate two is a regression unless the
split lands first. **Strength × scope × source, or nothing.**

### 2. Tri-state monitoring, applied to our own instrument

*A missing required operation is normally inconclusive, not success.* `jolt.sim.monitor`
already has this. `perturb.layer` does not — see the correction above. This is the
document's single most valuable concrete contribution precisely because it
exposes a defect in shipped code.

### 3. Decide one evidence schema — this is now the fifth sketch of it

Adoption audit §B2, the structural reframe's manifest, and all three of these.
The reviews found they duplicate field-for-field without merging, and that B2 is
**strictly more usable** than the newer ones because it names an assumption set
(`#{:top-level :single-parent-zero-spawner :serial-admission}`), a denominator
pair (`:space-size 6 :generated 6`), and six controls. The newer sketches list no
controls at all. **The correct action is a decision on one schema, not a sixth.**

---

## What each document contradicts

**PROGRESSIVE-ASSURANCE.** Strategy C's headline — "turns simulation parity into
a checkable relation" — is refuted by E35's executed control: call-over-call
produces 16 B6 violations while emitting **identical octets**, and drops 170
crossings from the trace. C compares the trace, which is the thing that silently
vanished. Its `:opaque` label for resource-bearing cells **inverts E38's
dominance order** — a resource-bearing cell is *known* authority-bearing, so
`:mixed`, a decision; collapsing it into `:opaque` is the exact error
`share.clj:59-64` was built to prevent, and routing it into "no independent
oracle → explicit assumption" launders known authority into a documented
assumption. Its first-slice step 4 ("require all declared routes to be observed")
reintroduces an author-chosen denominator of the shape E18 finding 4 measured as
gameable, without naming the refactoring that games it.

**CELLULAR-UI.** Covered under STRUCK. Additionally: **bbf1's nominated oracle is
not independent.** §2.3 names the "one-event/full-prefix oracle" — the reducer
folded incrementally against itself — which is exactly what
`jolt-http/test/jolt/http/http_model.clj` rules out in its own docstring. And
neither bbf1 nor a1s exists on disk, so half of §5's target criterion is unmet on
inspection.

**TEMPORAL-LEDGER.** Its central evaluation model is **perturb's refill
arithmetic with a different name**. `submit` computes `before = fold(L)` on every
submit and every query folds `ledger[0:b]` — but E36 measured that Jolt's
`subvec` is a **copying loop**, so `ledger[0:b]` is an O(b) copy before the fold
runs. That is the pattern E36 measured at 1220× and 4.2 GB of garbage for one
4 KB request, and nothing in 290 lines qualifies it. It also **breaks purity
exactly where it adds new machinery**: the command-id dedup table is a side
structure, so accept/reject is a function of `(L, t, dedup-table)` rather than
`(fold(L), t)` — row 38's entry price, and `cap/transition!`'s existing defect
(I10). And two of its three new ideas are unreachable under I20: serialization is
not a property without concurrent submitters, and `unknown` needs a caller
separated by something that can lose a message. **The crash arm is the
exception** — `process_explorer.clj` exists, so a crash control is reachable
without threads. Neither the document nor its own §4.1 draws that asymmetry.

---

## Unbuildable on what exists

- **No solver, anywhere.** `perturb.bounded-check!`, solver/version/options
  fields, `unsat` results. `perturb.refine` decides a ground linear fragment with
  no case split, no Fourier–Motzkin, no simplex, no way to combine two
  hypotheses, atoms compared syntactically. Tally row 57.
- **No IR fact store,** and its required completeness field is permanently false.
  `perturb/src/perturb/ir.clj` is 93 lines of `alter-var-root` tap; its own
  docstring says a namespace loaded before `install!` is never re-analyzed, so
  the universe is open **by construction** and load-order dependent.
- **`:contention {:kind :atomic :linearization …}` has no consumer** — I20, and
  E33's CAS-among-an-unknown-alias-set question is untouched.
- **The contract format is weaker than what already ships.** Its `:close!`
  example is `tcpcap.clj:488-489` with `:obligation` deltas and the
  absorbing-terminal rule — except it has **no argument positions**, and
  `report-limits` 2 records that the positional fallback was *removed* because
  E18 finding 1(d) measured it producing five diagnostics none of which named the
  annotation.
- **"Complete successor enumerator" as an input, "completeness denominator" as a
  result.** Nothing can check that an enumerator is complete. This ships an
  unchecked declaration straight into a completeness verdict — `report-limits`
  14(f)'s failure mode, in the architecture designed to prevent it.

---

## Work queue

1. ~~**Give `perturb.layer` a third outcome, and measure the vacuity.**~~
   **DONE — E40.** Built and measured: eight arms each carry a one-sentence
   `basis` denominator, `check` returns a **total** map over all six clauses,
   and `exercised 0` while holding violations is itself a violation
   (`:vacuity-accounting`). **Nine clause verdicts changed, all `pass →
   inconclusive`, no violation added or removed, no run-level verdict flipped.**
   This brief predicted one vacuous clause on one fixture; **two more were
   unpredicted** — C/B6.5 and D/B6.2, each a consequence of the defect its own
   fixture demonstrates. Item 3 is unblocked: `:inconclusive` is the fourth
   residual kind and an artifact now emits it. Residual left open: the `basis`
   denominators are hand-drawn, and nothing derives or cross-checks them.
2. **Does E4's `bounded-complete` verdict survive these documents' own
   definition?** Expected cardinality, equality-confirmed uniqueness, full
   consumption, decidable per member. E4 predates the definition and is perturb's
   only claim at that level. If it fails, **perturb holds zero verdicts above
   `sampled`** — a finding about our own record that changes how every future
   claim is worded. Cheapest decisive item.
3. **Route the residuals** — three existing kinds plus B6's new fourth, carrying
   strength, scope **and** source. Report what fraction of refusals have a named
   next layer; the honest expected answer is *most have none*.
4. **The discriminator experiment**, which is the one cheap new thing in the UI
   sketches. a1s's stale-acceptance shape is a second corpus case for
   `:perturb.cap/discriminator` — E33's "correctly implemented, wrongly
   prioritised" — and it comes with a **falsifiable prediction**: it will fail
   `report-limits` 14(g), because generation comparison is written as a bound
   boolean, and 14(i), because correlation and acceptance are split across two
   cells. Decidable outcome, no framework required.
5. **`view = V(cursor)` on `perturb.layer`'s credit fold.** E35 finding 3 *is*
   the bug this discipline prevents — the credit fold had to follow reply order
   rather than request order, "the kind of bug that makes a checker look correct
   while measuring the wrong thing," found by hand. Rebuild **at a boundary, not
   per event** (row 38's cost line, which the sketch omits); drop the crash arm
   for v0. Earns `monitored`.

Not on the queue: the framework itself. Items 1–3 are worth doing whichever
direction wins; 4 and 5 are cheap experiments that would inform it.

> **Superseded in scope, not in content.** `PROGRESSIVE-FORMALISM-DESIGN.md` is
> the framework this line said was not on the queue. The tension is deliberate
> and left visible: that document is a *design*, still unbuilt, and its own §11
> first slice is chosen precisely so the framework is not attempted before the
> merge it depends on is proven. Everything this brief says about the three
> reviewed documents stands unchanged. If a UI
target is wanted later, the honest one is **a single a1s mutation lifecycle
written as a threaded client session** — confirm → request → poll → terminal,
responses scripted, checked against an oracle that states the expected terminal
state independently. That is ladder step 1a with a long-running, operator-gated
protocol on top, and it tests something never tested. It is not a framework.
