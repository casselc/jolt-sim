# Brief: accept some things as unprovable, and instrument them instead

**Status: a proposition, not a decision.** Nothing in `PERTURB-DESIGN.md`
authorises this. It is written down because the alternative — continuing to
treat every gap as something to be closed statically — is not obviously right,
and because perturb already has most of the machinery this would need.

---

## The proposition

perturb's static checker has three outcomes, and only one of them currently
leads anywhere:

| verdict | today | under this proposition |
| --- | --- | --- |
| **decided** | accept / reject | unchanged |
| **refused** — `refinement-undischarged` (E19) | rejected; the program cannot be written | accepted, carrying a **residual obligation** the runtime discharges |
| **axiom** — 9 of `perturb.http`'s 12 transitions, 5 representation ops in `perturb.nrepl` | believed, checked by nothing | emits a **conformance check** against observed behaviour |

The third row is the one that matters. "Nothing checks that `respond!`
responds" (E18 nonclaim 5) is unprovable statically without a semantic model
perturb does not have and is not going to build soon. It is trivially
*observable*: the operation declares what I/O it performs, the handler records
what I/O actually happened, and the two can be compared.

The second row unblocks real code. E19 refuses **every** body written in a
data-dependent loop, with no invariant syntax to rescue it. Content-Length's
real semantics is a runtime obligation anyway.

## Why perturb is unusually well placed, and the part that is not obvious

Two things already exist:

- **The ledger is a complete record of capability transitions.**
  `perturb.cap/transition!` records every edge. It checks nothing, deliberately
  — `note!` "would happily record a transition the declared machine forbids",
  so that the gap stayed static and visible rather than being papered over by a
  dynamic check.
- **The effect layer is a mediation point for I/O that has been *measured*
  complete.** That is E16: zero syscalls attributable to perturb outside the
  handler, with a positive control (`-M:noio --touch-native`) proving the
  instrument was live rather than blind.

**The second is the load-bearing one and it is easy to miss.** A runtime monitor
at an effect boundary is only sound if nothing can bypass that boundary. E16 is
the measurement that says nothing does. Without it, monitor coverage would be an
assumption; with it, it is a bounded claim. Any write-up of this work should say
so, because it is the strongest thing perturb has here and it was established
for an unrelated reason.

> **E26 update: this paragraph now understates its own foundation.** The premise
> is no longer a sampled measurement on one host. The boundary **fails closed** —
> an effect or native crossing with no handler in dynamic extent is refused
> before any I/O — and the refusal is **latched** before the throw, so a caller
> that catches it cannot make the run report success
> (`perturb.effect/native!`, `latch!`, `report`; `perturb.posix/gate!`). Monitor
> soundness is therefore conditioned on an invariant *checked on every run that
> executes*, rather than bounded by one measurement.
>
> The brief's own constraint below — "a monitor that never fires is not
> evidence" — is satisfied here by two things the build half should copy
> verbatim: `-M:noio --unhandled-native`, a deliberately unhandled crossing that
> must trip the latch, and an **anti-vacuity required-symbol set**, so a run that
> performs no effect at all reports `all-handled? false` instead of passing by
> doing nothing.
>
> Two limits carry forward. perturb gates at its own wrapper rather than at the
> runtime's FFI descriptor layer, so the residual is "a binding added without a
> gate" rather than "nothing can bypass". And the run state is thread-local: a
> thread outliving the run and latching afterwards is charged to nobody. Both are
> in E26's write-up.
>
> **E29 adds a third limit, and it is the one that bounds this brief.** The
> invariant is a per-run property **of the native rung**. Above it the boundary is
> a *convention*, and the layering experiment measured two ways it fails: a
> handler that catches `:handler-abort` from the rung below and answers `[:ok
> empty]` leaves the run reporting `all-handled? true` with nothing latched
> (`latching-aborts` excludes `:handler-abort` because it is "catchable by the
> caller" — sound when the caller is application code, unsound when the caller is
> another handler); and the same layer composed by *calling* rather than
> performing drops 170 crossings from the trace with no instrument distinguishing
> the runs. So a monitor placed at an effect boundary is sound against *bypass to
> the host*, and not against *a layer above it choosing not to use the boundary,
> or rewriting what a layer below refused*. Any obligation this brief proposes to
> monitor at a layered boundary needs that stated.

A third exists in embryo: `perturb.posix/handler` records a transcript and
`perturb.script/replay-handler` replays it. That is the out-of-process half —
a trace checked offline, or replayed to re-derive the ledger, without the
originating process.

---

## The research half

Some of this is in Appendix D already; some is not. For each, the question is
not "does this exist" but **"what does it cost, what does it catch, and what
does the failure literature say"**.

1. **Does this posture have a name and a theory?** Runtime verification /
   monitor-oriented programming (Havelund, Roșu; JavaMOP). Specifically: is
   there a discipline for **deriving monitors from the same specification the
   static checker uses**, so the two cannot drift? That drift is exactly the
   class of defect E18 found three times (annotation vs machine).
2. **Miri.** E20 recorded it as "the trusted core got a dynamic checker… a very
   cheap thing perturb could have and does not" (POPL 2026, D.5). What does it
   actually check, how is it derived from the Stacked/Tree Borrows model, and
   what is the measured cost? It is the closest analogue to what is proposed
   here.
3. **Contracts and blame, in a Lisp.** Racket's `contract-out` makes **modules
   units of blame** — the module owes the positive positions, importers owe the
   negative ones (D.7). Two questions: is that the right shape for "an axiom
   owes its declared behaviour", and does *complete monitoring* (Dimoulas,
   Tobin-Hochstadt, Felleisen, ESOP 2012) give a usable criterion for **which
   boundaries must be instrumented** for blame to be correct? Note E18 finding
   1(c) was a blame failure in exactly this sense, and E18 (b) an
   incomplete-monitoring failure — the vocabulary already fits perturb's bugs.
4. **Affine resources cannot be guarded by an ordinary contract.** Tov &
   Pucella, *Stateful Contracts for Affine Types*, ESOP 2010 (D.5): affinity is
   a use-count property, so the wrapper must carry state. If a capability ever
   reaches unchecked `clojure.*` code (§1.6), this is the design. What does the
   stateful wrapper cost, and what does it need from the host?
5. **The cost ceiling.** Takikawa et al., *Is Sound Gradual Typing Dead?*, POPL
   2016 (D.7) measured boundary-contract overhead across all 2ⁿ configurations
   and concluded it was fatal. **Does that result transfer** to monitors placed
   at *syscall* boundaries, where the check is dwarfed by the I/O it sits
   beside? This is the single most important cost question and the answer is
   not obvious from the paper.
6. **Session types at runtime.** E20 found the one frequently-cited industrial
   MPST "deployment" (Ocean Observatories, RV 2013) is **runtime monitoring,
   not static typing**, and that Scribble's endpoint-API generation checks
   ordering statically but **linearity dynamically**, because Java has no
   linear types. Both are precedents for exactly this split. What did they
   report as the practical cost and the practical catch rate?
7. **Negative result to look for.** Is there published evidence on how often
   runtime monitors of this kind *actually fire* in production, versus being
   dead weight? E20 could not find a base rate for how often hand-written
   protocol axioms are wrong. If that literature exists, it decides whether this
   is worth building.

---

## The build half

**The cheapest first move**, which needs no new theory and reuses the ledger:

1. `cap/transition!` validates the edge against the declared machine and
   **records a violation** rather than throwing. Keeps E15's posture that the
   ledger observes.
2. A **terminal-edge obligation check**: `body-finish!` with
   `written ≠ declared` is caught where E19 refuses it statically. This is the
   pairing that makes the discipline real — refused statically, checked
   dynamically.
3. A gate stage that runs a **known-violating** program and asserts the
   violation **is detected**. Without this the monitor is unfalsifiable; see the
   constraint below.

Then, and only if the first move holds:

4. **Effect-layer conformance.** Each capability operation declares the I/O it
   performs; the handler records what it did; cross-check. This is what attacks
   "nothing checks that `respond!` responds" directly.
5. **Offline trace checking**, reusing the transcript/replay pair.

## Constraints that must be stated in whatever gets written

- **Detection is not prevention.** A Content-Length obligation caught at runtime
  means the malformed response is already on the wire. E19 *deliberately* chose
  to record rather than abort, so that the static gap stayed visible. Reversing
  that for some obligations is a **decision to record**, not an implementation
  detail — say which obligations abort and why.
- **A monitor that never fires is not evidence.** Every monitor needs a positive
  control, exactly as `-M:noio` has one. This is the discipline E16 established
  and it is the reason its clean window is a measurement rather than a shrug.
- **The ledger is a process-global atom.** INHERITED I10 already flags that this
  is itself the shape §1.2 says should be capability-tier. Under real
  concurrency it is wrong, and contention is the one axis nothing has ever
  tested (I20). A monitor built on it inherits both.
- **Instrumenting an axiom does not make it checked**, and the write-up must not
  let it read that way. It makes a violation *detectable on the executions that
  occur*. That is `monitored` on the charter's evidence lattice, not `proved`,
  and it is strictly weaker than what the static checker gives for the rows it
  decides.

## The metric this buys

E18 finding 4 showed operation-counting is gameable: `perturb.http` drove
`:perturb.cap/representation` to zero while unchecked concrete-map accesses went
12 → 31. **Monitor coverage is not gameable in that direction** — how many
axioms have a monitor, and how many trace events each monitor actually saw in
the last run. Inlining a field read into a transition body does not create trace
events, so it cannot launder anything. An axiom with no monitor and an axiom
whose monitor never fired are both countable, and both are holes.

Whether that metric survives an adversarial refactoring is itself an open
question — E20 records that no metric in the literature does, and that the one
shipped tool (`cargo-geiger`) has been known-broken since 2019. **Name the
refactoring that games this one before adopting it.**

---

## What to produce

- A finding in `PERTURB-DESIGN.md` §3 recording what the research half
  establishes, in the usual form: what is confirmed, what is refuted, what has
  no answer. If the cost question (item 5) says this is unaffordable at effect
  boundaries, that is a complete and useful result and the build half should not
  happen.
- If the build half proceeds: the first move, its gate stage, its positive
  control, and a coverage number for the axiom set.
- Either way, an honest statement of where this sits on the evidence lattice.
  The charter's word for it is `monitored`, and nothing here should be described
  more strongly than that.
