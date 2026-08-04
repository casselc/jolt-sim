# Perturb: design record

**Status:** DRAFT research record. No implementation is authorized by this
document. It records decisions taken in session, the evidence behind them, and
the open questions that must close before a v0 spec is written.

**Scope:** `perturb` is a fork of Jolt into a distinct language — a formal
Clojure-like whose deterministic simulation, effect discipline, and capability
safety are language features rather than an interception layer bolted on
underneath one. It is not a Jolt release, not a Jolt compatibility target, and
carries no JVM Clojure conformance obligation.

**Relationship to prior records.** `jolt/docs/research/APPLICATION-CORE-SEMANTIC-CHARTER.md`
predates the fork decision. Its §1.2 value set, §2 evaluation order, §2.4 error
model, and Appendix A normalization are written implementation-neutrally
("Clojure.next") and are inherited. Its §1.3 non-goals were written for a core
where "developers write ordinary Jolt" and nothing is a rewrite target; those
premises no longer hold, and each is re-decided below rather than assumed.

**Evidence labels** follow the charter §5 lattice: `proved | bounded-complete |
sampled | monitored | assumed | opaque | failed`. Nothing here is `proved`.

**Standing method commitment.** Every correction in this document came from
testing against an independent measurement or specification. None came from
source reading, and none from the examples a specification supplied.
Accordingly: **no claim here should be trusted ahead of the artifact that tests
it**, and an acceptance criterion is derived from a specification's semantics,
never from its examples.

The complete current tally of claims this document made and then refuted:

| # | claim made | refuted by | corrected version now in |
| --- | --- | --- | ---: |
| 1 | host-interop `String` emulation dominates codec cost | the `decode-utf8` decomposition — it is 0.2% | E1 |
| 2 | allocation dominates (per `jolt-bytes/docs/PERFORMANCE.md`) | `window-octets` costs only ~12% more than the bare scan it wraps | E1 |
| 3 | the residual after `048582c3` is "two string-keyed lookups plus generic invoke" | `measurements/profile3.clj` | E1, E7 |
| 4 | dispatch dominates, so backend devirtualization is the fix | `profile3.clj`: dispatch ≈219 ns/byte (≈19%), devirtualization ≈37 ns/byte (≈3%) | E7 |
| 5 | "the measured gap is dispatch structure, not Chez codegen" (§1.1's stated *reason*) | E7 and E8 — the gap is which primitives are host natives versus `clojure.core` overlay defns. The conclusion survives; the reason was wrong | §1.1, E8 |
| 6 | uniqueness + linearity are the capability tier's axes | `prototypes/equivalence.py` against `ownership.pl`: 1051 / 6470 unsound acceptances at depth 8 | §1.2, E5 |
| 7 | a borrow blocks moves | the same differential test: 103 sequences still wrongly accepted; borrows must be exclusive | §1.2, E5 |
| 8 | `writer_result` is structurally unrepresentable, so no binding rule is needed | `prototypes/multicap.py` probe 2 — two names, one capability, diverging roles | §1.2, E6 |
| 9 | contention is droppable ("only needed for real shared-memory parallelism") | `noninheriting-scratch-corrected.smt2` is a contention obligation already in the proof surface | §1.2, E6 |
| 10 | `aget` at 54 ns/byte is a primitive-read floor | `optimized-scheme` output: `(aget b i)` lowers to `(jolt-nth b i)`, generic dispatch | E11 |
| 11 | a byte array costs one byte per element | the allocation counter: 8 bytes/element, the same as a long array | E11 |
| 12 | the `jolt-array` backing survey: "~35 direct uses across 10 files", framed as reads | the delegated refactor: 41 raw operations across 7 files, plus six *construction* sites the framing excluded by design | E12 |
| 13 | single samples support the deltas stated | E9 — repeated runs of the same build spread ~10% on this host | E9, §6 |
| 14 | the pinned setup "reproduces the recorded baseline to within 0.6%" | E10 — 5-run baseline median 1,211,841 ns, 22% above the recorded figure; the 0.6% was luck | E10 |
| 15 | §1.2 gives ordinary values full inference "without giving up inference" | `prototypes/transducer.py` — abstract refinements are predicate variables; HM unification cannot solve them | §1.2, E13 |
| 16 | a fresh-window step and the same-window `Step` are incomparable | the solver: `Step` really is a subtype of the version as first written | E13 |
| 17 ‡ | E3: "no obligation is about application semantics" | re-examination of the sample — true of codecs, not of the domain | E3, §4.6 |
| 18 ‡ | Q2 is a gradual-typing boundary problem | the liquid-types literature — everything here is statically typed; an unrefined value is refined by `true` | Q2 |
| 19 | the capability corpus's accept set "would run" — and unpositioned `:consumes`/`:produces` is an expressiveness gap | running it: `open-request-close` and `uses-ping` both throw under the scripted handler. The gap is a **false accept**, not only a false reject | E15 |
| 20 | INHERITED I11: `defcfn` resolves its foreign symbol at def-evaluation | `backend_scheme.clj:589-617` — it lowers to a deferred, memoised, per-binding cell | E16 |
| 21 | positioned specs are the one thing §1.2 needs to check the real client | building them: the client checks, and the layer *underneath* it then failed — the missing concept is a module boundary, which §1.2 also does not have | E17 |
| 22 | E17's `perturb.nrepl` report shows what the checker found | a second protocol run through the same reporter: diagnostics raised **against axioms** were collected and silently dropped, hiding an `annotation-inconsistent` on `perturb.nrepl/open` since it was written | E18 (b) |
| 23 | `:perturb.cap/representation` measures the abstraction boundary | `perturb.http` drove the list to **zero** while unchecked concrete-map accesses went from 12 to 31 — counting operations counts the wrong thing | E18 (4) |
| 24 | E18: "nothing here is a false accept — it is a declaration language that cannot say what the code does" | true of (a), (b), (d); **false of (c)** — `borrow-and-return-listener` was ACCEPTED carrying an annotation no implementation can satisfy | E19 |
| 25 | E18: `accept` and `body-finish!` are the two operations that advance two machines | there are **three**. `respond-begin` is the third and it drew **no diagnostic at all** — it minted an undeclared capability silently | E19 |
| 26 | keying the primitive table by `[cap op]` and comparing per capability is the fix for the two-machine defect | insufficient: a terminal-`:to` rule was also needed, and two edges had to be **added** to declarations that had never named them | E19 |
| 27 ‡ | internal/external choice dissolves the join rule | the linear conditional rule types **both branches in the same context** — perturb's join rule *is* the linear rule; ⊕/& are additive | E20 |
| 28 ‡ | graded/quantitative types decide the Content-Length obligation without a solver | Granule requires Z3; grades are parameterized *over* a decision procedure; and grades count **uses of a binder**, not octets | E20 |
| 29 ‡ | sealing gives a principled account of "these bodies are axioms" | it **relocates** trust from a list to a scope; representation independence constrains clients, not implementations | E20 |
| 30 ‡ | E20: §1.4's no-resumption rule places perturb **outside** the linearity/handler tension | reading Tang et al.: the mismatch has two halves — continuations *discarded* and continuations *multi-invoked*. `abort!` is the discard half. perturb is outside one half of two | E21 |
| 31 ‡ | E20: exceptions require weakening linearity to **affinity** plus cancellation, and **"perturb has the affinity"** | the second clause is the error. Fowler et al. §1.3 rejects affine types by name (silent discard is the defect) and §1.4 is "**Linear** Types with Explicit Cancellation". Mostrous & Vasconcelos — recovered after E21 marked it unobtainable — *does* say "we relax the condition of linearity to that of **affinity**", so E20's vocabulary is theirs; but their affinity **is constituted by** the explicit `cancel` term. It is not a property perturb can hold half of, and E6's silent-discard binding is the version both papers reject | E21, refined by E25's recovery of the source |
| 32 ‡ | E20: "grades count uses of a binder, not octets", so no graded system can state the Content-Length obligation | Doré, *Dependent Multiplicities* (2507.08759) — multiplicities **can** depend on run-time values. The conclusion (row 28) survives; the reason does not. The barrier is undecidability and hand-written proof terms, not expressiveness | E21 |
| 33 | `report-limits` item 8: destructuring / `peek` / `last` / a computed index lose a capability **silently**, the likeliest false accept | probing all four: nothing is silent, every case draws two diagnostics. The real defect was a **false reject on idiomatic Clojure**, caused by an arity check | E22 |
| 34 | E15/E17/E18's blind-spot lists are the checker's limits | they omit **higher-order capability passing** entirely — `(f c)` has no annotation, which is `with-open` and every reactor callback | E23, E24 |
| 35 | a pure `(state, event) -> [state', effects]` shape collapses the capability cost into one component | it **relocates** the obligations rather than removing them: 2 of 4 wrong applications accepted, and the working app's `/wait` route breaks the declared machine and reaches the wire | E24 |
| 36 † | §1.2's **reason** for dropping locality, and D.8's escape from it | two defects in one sentence. (a) Yarrow's objection is to *stack-disciplined* memory regions; Milano's are a **domination-induced heap partition with no scope at all**, so it does not apply. (b) D.8's "D4's no-resumption may remove the reason" fails under row 30 — non-local control **is** the discard half, which D4 keeps. The conclusion may stand; neither the reason nor the escape does, and the region question is **harder** than D.8 states | E25 |
| 37 † | D.8: Flo's **bounded/unbounded** typing is the principled form of E23's `recv` defect | it is one level too high. Flo distinguishes a stream **value** carrying the terminator `⊗` from one that does not, and `fixed(c)` is a predicate on values; boundedness is the *type-level* prediction that `fixed` will eventually hold. E23's defect is the value-level collapse of `∅` into `fix(∅)` | E25 |

‡ Eight rows are the exception the sentence above does not cover: 17 and 18
arrived from re-examining the argument and from the literature; 27–29 came from
a **literature survey** (E20) rather than from an artifact built to attack the
claim; and 30–32 came from **reading E20's own sources** (E21). The commitment
is stated of the other twenty-seven, which came from a measurement, a probe, an
independent model, a delegated verification, or — for 24–26 — from building the
fix and finding the premise wrong. Rows 6–9 and 16 are cases where the claim had
already **passed its spot checks** and failed a probe designed to attack it,
which is the pattern the commitment exists to name. Rows 27–32 are the
refutations in this document that no artifact produced, and they are flagged
accordingly: nothing in E20 or E21 was executed. Note the shape of 30–32: E20
refuted four of my claims from abstracts, and reading the papers refuted three
of E20's — two of them in the direction of *more* work, not less.

† Rows 36–37 are the same case one step further out: E25 read the two papers
that *were* in hand and refuted a reason recorded in §1.2 and a hypothesis
recorded in Appendix D.8. Like 27–32, no artifact produced them. Rows 33–35, by
contrast, came from running code (E22, E23, E24) and are covered by the standing
commitment.

**How to read this document.** §1 is the settled design, stated once in final
corrected form. §2 is the divergence register. §3 is the findings E1–E25, each
stated as currently believed rather than as first written. §4 is the open
questions. §5 is the v0 ladder. §6 is the nonclaims. Appendix A is the
correction history, Appendix B holds the superseded ladders in full,
Appendix D is E20's bibliography — kept complete because E20 read no paper in it
in full text, and now marked with what E21 subsequently read (✔✔), what it could
not obtain (✗), and what remains second-hand — and Appendix C maps the old
chronological section numbers onto this structure —
quoted historical text below retains its original `§` references, and Appendix C
resolves them.

---

## 1. Decisions

The settled design. Every entry here is in final corrected form; what it was
first and what corrected it is in Appendix A.

### 1.1 Substrate — fork Jolt onto Chez

Keeps the reader, analyzer, IR, backend, deps, and build; keeps multi-shot
`call/cc`, the exact numeric tower, and self-hosting. OxCaml was evaluated: its
mode system (locality, uniqueness, linearity, portability, contention, yielding,
statefulness, visibility) is a static form of E2's hand-rolled systems, and its
unboxed types would address representation cost. It was not selected because
modes are a checker perturb writes over its own IR rather than a host feature it
must inherit, and because OxCaml supports no multi-shot handlers and has no
formalization of one-shot effects against its own extensions.

**Chez is not the problem, and the original reason for saying so was wrong.**
The first statement of this was: "E1 is **not** evidence against this choice:
`aget` at 54 ns/byte is adequate; the measured gap is dispatch structure, not
Chez codegen." E7 and E8 show the gap is neither dispatch nor Chez codegen — it
is **which primitives Jolt implements natively versus in its own core overlay**.
The conclusion survives and is strengthened: `aget` at 54 ns/byte and a function
call at 18 ns/byte are both fine. E11 then removed the premise underneath the
original sentence entirely: `aget` is not a primitive read, it is generic
dispatch that happens to hit an early `cond` arm, and a byte array is eight
bytes per element. Neither fact is visible from timing.

Required IR changes, from inspection of `jolt-core/jolt/ir.clj`:
`:local` carries a name, not binding identity — linearity checking needs
alpha-conversion or a `:binding-id`. The `:host`/`:host-static`/`:host-new`/
`:host-call` ops are untyped, un-effected escapes and should be replaced by a
single `:extern` carrying a declared effect row and signature. Note charter
rejected-alternative A1 ("annotate optimization IR: identity not durable through
passes") applies: the effect boundary's `site-id` must come from a durable
identity spine, not a pass-attached annotation.

Both IR claims are inferences from source reading and are **untested**; §4.6
records why that matters and what tests them.

### 1.2 Typing — two tiers, four axes

**Ordinary values:** static types with inference, `Any` escape hatch. No proof
obligations, no modes; immutability makes the mode questions trivial.

Inference is decidable and inferable **at the leaves**; it is not at the
combinator boundary. E13 measured the original phrasing ("full inference",
"without giving up inference") as too strong: abstract refinements — needed
wherever refined capabilities compose, e.g. transducers — introduce predicate
variables, which HM unification cannot solve. Inferring them is Horn-clause
constraint solving over a qualifier set. **Leaves stay decidable and inferable;
the combinator boundary does not.** An `Any` position never holds a capability,
which is what keeps the tiers from meeting (Q2, E13).

**Capabilities** (handles, cursors, buffers, leases, continuations, mutable
cells): modes plus refinements. Axes kept, in corrected form:

- **uniqueness** (`unique`/`shared`)
- **linearity** (`once`/`many`)
- **typestate** (role-indexed operation legality) — added by E5
- **contention** (does an owner survive a thread fork) — restored by E6

Borrows are **exclusive** — a live lease blocks reads by the owner, not only
moves — and capabilities bind **affinely**: there is no non-moving alias.
Exclusivity came from E5, affine binding from E6.

Axis dropped: **locality** — regions are the specific feature that makes effects
unsound (cf. arXiv 2607.15876, Yarrow: non-local control breaks stack
discipline; multi-shot handlers break exit-at-most-once). Escape safety for
loans, handler scope, and task containment follows from uniqueness plus
linearity alone, so dropping regions avoids the unsolved interaction at no cost
to the properties E2 needs. E6 re-checked this and locality stays dropped.

**E25 corrects the reason, and the decision is now genuinely open.** Yarrow's
objection is to *region-based memory management* — regions with a lexical scope
and a stack lifetime, which non-local control can skip. Milano, Turcotti & Myers
(PLDI 2022, Appendix D.8, read in full) use "region" for something else entirely:
a partition of the heap induced by domination, which "can grow and shrink
dynamically as threads exchange portions of the object graph" and has no scope
for control to escape. Their regions deliver exactly what E23 and E24 found
missing — a connection table is an arbitrary intra-region object graph — so the
sentence above rules out, by a reason that does not apply to it, the one
published system that answers §4.6's root cause. What genuinely does transfer is
**not** Yarrow's objection but E21's: a non-local exit that skips the discharge
of a focus leaves the domination exemption outstanding, which is the `abort!`
gap again. Note also that E20's suggested escape — that D4's no-resumption rule
removes the reason regions were dropped — does **not** hold: non-local control is
the *discard* half of the handler/linearity mismatch, and D4 removes only the
multi-shot half (tally row 30). Locality stays dropped as a decision, but the
recorded argument for it does not support the decision, and the prerequisite for
reopening it is the `abort!` cancellation work, not a new region theory
(tally row 36).

E3 is the justification for the tier split: refinements confined to capabilities
cover the existing proof surface without dependent types over ordinary values
and without giving up inference at the leaves. **That justification is qualified
twice.** E5: coverage requires the typestate axis, which E3's survey did not name
because the obligations it catalogued hide role sequencing inside "ownership".
E3's own sample bias (§4.6): the survey covers *resource safety* and says
nothing about *algorithmic correctness*.

**Confinement holds conditionally.** E13's honest statement, carried here
verbatim in force: *confinement holds while the capability tier terminates at
the driver; a zero-copy element type moves the boundary into the transducer
chain and costs abstract refinements of arity ≥ 2.* That trade should be made
deliberately, at the point the zero-copy decoder is decided, not discovered
afterwards.

**Known usability risk, unquantified.** E6 probe 1's join rule rejects
`if (c) { b = detach_result(b) }; use(b)` — a shape ordinary code writes freely.
Mitigations (explicit reconciliation, a sum state requiring a case-split,
per-variable flow-sensitivity) are unexplored and are a real usability risk for
this section. How often the rule actually fires on real programs is argued, not
measured (§4.6). Two data points now exist and they disagree: **zero** occurrences
in `perturb.nrepl` (E15), and it fires on the **first driver anyone would write**
for HTTP keep-alive — `if (keep-alive? req) c2 (close-conn! c2)` — where an
accepted rewrite exists but is a non-obvious idiom that must be known in advance
(E18). One of two protocols, not a frequency.

**~~Known defect~~ CLOSED — capability specs are positioned.** `:consumes` /
`:borrows` carry `:arg n`; `:produces` carries `:at [i]`. The abstract domain has
one composite, a tuple, and `first`/`second`/`nth`-with-a-constant are its
eliminators. This closed E15's false accept and made `perturb.nrepl`'s protocol
layer checkable without changing a line of its bodies (E17).

**The new top of this section's queue: a module boundary.** With the protocol
layer checkable, the implementation layer under it started failing — `state`,
`conn-id`, `conn`, `compact`, `read-frame` all touch the connection's concrete
map, and `(:perturb.nrepl/buf c)` cannot be given a capability signature. They
are axioms for the same reason a transition's body is, but that is a second
class §1.2 never named. The artifact names it by listing operations
(`:perturb.cap/representation`); what it wants to be is a scope — "inside the
Connection's implementation" — and §1.2 has no module concept (E17). Ahead of
E13's abstract refinements.

**And the list is gameable, measured.** `perturb.http` declares three
capabilities with an EMPTY `:representation` for each, by writing every
concrete-map access inside a declared transition instead of in a helper. The
unchecked surface grew — 31 accesses against `perturb.nrepl`'s 12 — while the
list went to zero. Counting operations counts the wrong thing; the metric that
matters is lines below the boundary, and only a module can define that line
(E18 finding 4).

**Two shapes the declaration language cannot express, both found by a second
protocol** (E18 findings 1 and 3), both ahead of E13's abstract refinements and
behind the module boundary:

- **an operation that advances two machines at once.** The primitive table is
  keyed by operation, so an operation belongs to at most one capability, and
  `accept` (mint a connection from a listener) and `body-finish!` (end a body,
  return the connection) cannot be declared. Both draw `annotation-inconsistent`
  and no way of writing them removes it. The same defect was already live on
  `perturb.nrepl/open` and had never been printed.
- **a state that carries a refinement.** A Content-Length body writer owes the
  wire exactly N octets. `:finished` is a state; `wrote exactly N` is QF-LIA over
  a run-time integer — §1.3's fragment, not this section's axis — and a program
  that declares 6 and writes 3 is ACCEPTED and RUNS. Relatedly, nothing in §1.2
  can relate two machines in time.

**Borrows do not close.** A `:borrows` parameter is the caller's; it is exempt
from the scope-exit leak rule. Added in E17, and found by the checker refusing
`perturb.nrepl/state`. E18 found that `:borrows` **and** `:produces` of the same
capability was a legal annotation that duplicated it; E19 **refuses it at the
annotation**, and the caller's spurious leak is what remains (E19 nonclaim 4).

**The declaration language now says what the code does (E19).** An operation is
an edge of as many machines as it moves — the primitive table is keyed
`[capability operation]`. A machine has **no pre-creation state**; a creating
edge declares `:from nil`. `:arg` is mandatory and the in-order fallback is
gone. And a **transition may carry a refinement**: `ResponseBody`'s
`:open -> :finished` edge carries `(= written declared)`, discharged against
ghost state, with three outcomes — proved, refuted with both numbers, and
**refused**. This is the first place §1.2's typestate axis and §1.3's arithmetic
meet, and E6 probe 3 predicted it from the other direction.

**What §1.2 still cannot say**, in the order E20's surveys rank it:
a module boundary (E17, E18 finding 4, and every survey's top item); a
refinement across a loop or function boundary, with no invariant syntax to
supply one (E19); and a cancellation obligation on the `abort!` path, which
Fowler et al. (POPL 2019) identify as the price of having exceptions at all in a
linear setting — E15 blind spot 4 is a soundness gap, not a coverage gap.

**E21 corrects what that price is, and it is not what E20 recorded.** E20 said
the recipe was affinity **plus** cancellation and that perturb "has the
affinity". Reading the paper: §1.3 rejects affine types *by name* — "Affine
types present two quandaries arising from endpoints being **silently
discarded**" — and §1.4 is titled "**Linear** Types with Explicit Cancellation",
warning explicitly against confusing the two. Linearity is kept; `cancel` is
added as a term that *uses* the endpoint. So this section's affine binding (E6)
is not half the fix, it is the shape the paper argues against, and both halves
of the obligation are outstanding. E21 claim 3 specifies the four pieces.
Relatedly, E21 narrows §1.4's defence: no-resumption puts perturb outside the
*multi-shot* half of the handler/linearity tension and inside the *discard*
half, which is this same gap seen from the effects side.

### 1.3 Proof — capability-tier refinements, Ansatz retained

Ansatz (`org.replikativ/ansatz`, a Lean 4 CIC kernel in Java with a Clojure
surface) currently proves a **pure model**, with an exhaustive bounded runtime
oracle bridging model to implementation — explicitly "not a compiler or an
executable extraction into Jolt". That bridge is load-bearing only because the
proof is about a separate artifact.

Capability-tier refinements collapse the bridge: if containment is a type on
`slice`, the implementation *is* the model and the oracle becomes a cross-check
rather than the connection. Session types over sans-io step functions are a
special case (protocol state), not a separate mechanism. Ansatz is retained for
obligations outside the type system.

Acceptance criterion for the checker, using artifacts it did not author: accept
the `*-corrected` models and reject `double-owner-bug.pl`,
`early-release-bug.pl`, `borrow-generation-check-omitted-buggy.smt2`,
`native-lease-early-release-buggy.smt2`; non-vacuity controls must still pass.

**Qualifications carried forward.** E5: the claim that capability-tier
refinements "cover 100% of the existing proof surface" survives but requires the
typestate axis — bounds and commit geometry remain refinement obligations; role
sequencing is a separate typing judgement. E6 probe 3: of `jolt-hako/proofs/smt`'s
seven families, two are mode/typestate obligations and four are arithmetic
refinements, confirming both halves are load-bearing rather than one subsuming
the other. §4.6: the covered class is *resource safety*; structural/inductive
and temporal/behavioural properties are a third tier that is open and undesigned.
E13: retry-soundness is an obligation *outside* the type system, which this
section already reserves for Ansatz; it is recorded so it is not later mistaken
for something the checker discharges.

A settled point that §1.5 supplies: refinements on a byte view can state
`0 ≤ b ≤ 255` as a *type*, decidable in QF-LIA. Under signed semantics the same
property needs a case split on the sign fold — the same obligation, harder to
discharge, for no gain.

### 1.4 Effects — charter D4 retained, D3 deferred not foreclosed

Effects substitute a validated result or abort; no continuations at that layer.
Control (blocking, scheduling, virtual time) stays in the explicit cooperative
kernel. Protocols are step functions over both.

This is retained on its merits, not inherited: P5 §4.2 places bounded
completeness solely with `explore_states.clj` BFS, under stated preconditions
including **value-semantic state** — the precondition continuations break. E4
shows the codec layer needs no continuations anyway.

**What D4 buys, restated after E21 read the paper it rests on.** E20 recorded
that Links combined linearity with effect handlers and carried a soundness bug
for years, and that §1.4's no-resumption rule "places perturb outside that
tension structurally". Half of that survives. Tang et al. (POPL 2024) name
**two** ways handlers break linearity — continuations *discarded* (for
exceptions) and continuations *invoked more than once* (for backtracking) — and
prove a safety property that is symmetric in them: "no linear value is
discarded or duplicated". D4 removes multi-shot, which is the hard half and a
real gain. It does **not** remove the discard half: `abort!` is exactly a
discarded continuation, and T-Handler's price for one is that its continuation
must capture **no linear resources at all**. That is the same gap §1.2 and §4.6
record on the `abort!` path, reached from the effects side. The recorded
consequence about D3 is unchanged — reopening it drags control-flow linearity in
with it, and that paper is still the price list.

D3 (delimited control) stays open for direct-style application code, at a lower
evidence tier — the two-track wall P5 already draws between bounded-complete
model exploration and heuristic runtime search is the right boundary. To keep it
cheap to add, the `perform` boundary must remain a real call site with durable
identity rather than being inlined at analysis time.

This section's single-nondeterminism-source model is load-bearing beyond
effects: it is why perturb's core is eager (§2, row 2) and why iteration order
is deterministic within a build (§2). Every choice comes from one `choose`, the
trace is the sequence of answers, replay is feeding them back.

### 1.5 Bytes — perturb's byte type is unsigned octets

Added to §1.2's capability tier. **perturb has no JVM signed-`byte[]`
compatibility obligation.** A byte is an octet, `0..255`. Codecs do not perform
sign folding to read a byte.

#### This is charter-consistent, not a new departure

Charter §1.3 non-goal 1 already disclaims exactly this class: "The core does
not preserve any host's implementation accidents — JVM UTF-16 surrogate
splitting, **JVM primitive overflow behavior**, Chez-specific integer widths".
Java's signed `byte` is that accident — a language decision from 1995 with no
representational justification, since the storage is octets either way and
signedness is only a read-time interpretation.

#### The cost was measured, not assumed

`jolt-bytes` documents the friction directly: *"Jolt stores byte-array slots as
unboxed octets while the JVM exposes `byte[]` slots as signed bytes.
`unchecked-byte` makes traversal identical."* So every byte read in the codec
path calls `unchecked-byte` **purely to reproduce a JVM convention**.

E8 measured that call at **209 ns/byte** before nativisation — the single
dominant term in `Window/nth`'s body, ~8x the `aget` it wrapped. The JVM signed-
byte accident had a measurable, dominant cost on precisely the path this whole
performance line has been about, and it bought nothing except compatibility
with a host perturb does not target.

That is the cleanest instance in this document of a host accident charging rent.
E2 catalogued five hand-rolled ownership systems as the cost of a *missing*
language feature; this is the cost of an *inherited* one.

A third independent instance arrived later (§5's corroborations):
`jolt-http/test/jolt/http/http_model.clj` handles bytes as vectors of unsigned
octets (0..255) throughout, "because jolt's byte-arrays read back signed" and
comparing the two representations directly reports spurious mismatches above
0x7f. Two libraries, two different workarounds, one inherited convention — code
that predates this decision corroborating it.

#### Consequence for the two-tier design

The tier split (§1.2) already says ordinary values get no modes and
capabilities get modes plus refinements. Byte views are capability-tier, so
their representation is perturb's to choose: bytevector-backed, unsigned, no
conversion at the accessor.

#### Scope note

None of this applies to Jolt itself, which does have a compatibility target.
The concurrent Jolt work preserves `byte[]` semantics exactly and aims to make
that path fast too — a bytevector backing with read-time conversion, since
signedness is interpretation rather than storage. Two byte types in Jolt would
be a compatibility accommodation; perturb needs only one, and it is the fast one.

### 1.6 Layering — perturb's core, and `clojure.*` compatibility on top

**perturb's core/prelude** offers a surface comparable to `clojure.core` —
familiar and ergonomic to a Clojure developer, same names, same shapes — but
hews to **perturb semantics** wherever they differ.

**`clojure.*` namespaces** are a separate compatibility layer that maintains
Clojure semantics *on top of* perturb. They are opt-in, not the default.

#### What this resolves

The charter is written as a "Clojure.next Application Core", which left it
ambiguous whether perturb inherits a semantics or defines one. This settles it:
**perturb defines, and compatibility is a library.** §1.5's unsigned-byte
decision is the first worked instance rather than a one-off exception — the
charter's own §1.3 non-goal 1 (no host-accident canonization) becomes the
default posture of the core, with `clojure.*` as the place accidents are
reproduced for those who need them.

#### Where the rent lands, and why that is the right place

§1.5 measured the JVM signed-byte convention costing 209 ns/byte on the codec
path. Under this layering that cost moves into `clojure.*` and is paid only by
code that asks for Clojure semantics. Code on perturb's core pays nothing. The
cost becomes **opt-in and visible** rather than ambient and invisible, which is
the property E8 showed was missing — nobody chose to pay 209 ns/byte, it was
simply inherited.

#### The rule this imposes on the core

**perturb's core must not foreclose Clojure semantics — only decline to adopt
them by default.** A compatibility layer is only implementable if the underlying
core is expressive enough to host the behaviour it does not itself choose.

Bytes satisfy this: storage is octets, signedness is a read-time interpretation,
so `clojure.core/aget` over a byte array can sign-fold and return -1 while
perturb's own byte view returns 255. Any core decision that made the Clojure
behaviour *inexpressible* rather than merely non-default would break the
layering, and is therefore out of bounds.

That is a checkable constraint on every future divergence, and it should be
applied at the point the divergence is decided.

**The rule is a defeasible default** (Q5, resolved). Neither "deferred" nor
"optional": no-foreclosure is a **goal and a rule, held by default, breakable
later for a sufficiently interesting gain.** The governing mechanism is the
divergence register's third column — see §2.

#### The hazard: same names, different semantics

Identical spelling with divergent behaviour is a real footgun — a Clojure
developer's muscle memory will be correct about shape and can be silently wrong
about behaviour. Neither known divergence is discoverable from a call site. So
this layering requires an **enumerated divergence register** — every name whose
semantics differ from `clojure.core`, with the difference stated. The charter
already specifies the mechanism in §1.4: feature → classification → semantics
location → support level, with a Notes column naming exact unsupported variants.
The register is that matrix with a divergence column, and it is a release
obligation, not documentation hygiene: an unenumerated divergence is
indistinguishable from a bug. The register is §2.

#### Sequencing — `clojure.*` is deferred, the rule is not

Exact Clojure compatibility is **not an initial priority**. The `clojure.*`
layer is a later stage; nothing in the v0 ladder (§5) implements or tests it.

This frees the core's design — no divergence has to prove itself against a
working compatibility layer before being taken. But it sharpens one thing and
weakens nothing:

**Deferring the layer removes the artifact that tests the no-foreclosure rule.**
The core must not make Clojure semantics inexpressible, only non-default. With
`clojure.*` unimplemented, that rule has no executable check — and this
document's standing commitment is precisely that an untested rule is the kind
that gets refuted later. Every divergence taken between now and the layer's
existence is an unverified claim that compat remains implementable.

Mitigation, and it is cheap only at decision time: **when a divergence is
decided, record alongside it the one-line sketch of how `clojure.*` would
recover the Clojure behaviour.** For §1.5 that sketch is "sign-fold on read, since
storage is octets". Writing that when the decision is fresh costs a sentence;
reconstructing it years later, against a core built on unexamined assumptions,
is how compatibility layers turn out to be impossible.

So the divergence register is not documentation of a shipped layer — it is
the **only artifact carrying the compat design** until the layer exists, and it
should be maintained from the first divergence rather than started when
`clojure.*` does.

#### Consequence for the v0 ladder

The measured slice imports `jolt.bytes`/`jolt.bencode` semantics. Under this
layering the port targets **perturb's core**, not `clojure.*` — so the bencode
decoder reads octets directly and the `unchecked-byte` fold disappears from the
path rather than being reimplemented. The existing oracle corpora remain valid
as *value* tests (they pin decoded results, not byte representation), but any
corpus row asserting a signed byte is testing `clojure.*`, not perturb, and
must be reclassified rather than ported.

### 1.7 Gate architecture

Gate architecture follows `jolt-toolchains`: producer records its own claims;
an independent clean-consumer job revalidates after fresh extraction; the
verifier's limits are stated rather than implied.

The artifact's gates, as they stand (`perturb/dev/run-demo.sh`, all exiting 0
at `1dd068b`):

| gate | what it decides | limits printed by |
| --- | --- | --- |
| `-M:selftest` | codec/octet self-tests, no socket | the run |
| `-M:refine` | the refinement decision procedure alone, 18 cases, with the ones it must REFUSE recorded as first-class expectations | the run (E19) |
| `-M:stream` | a port of `teensyp.stream` by an agent forbidden to read the rules; frames lines under one-octet-per-`recv`, all 91 chunk boundaries, and a real loopback socket | the run (E23) |
| `-M:streamcheck` | that port, checked against a declaration written from outside. **Not a gate** — no recorded expectations, because the point was to learn the verdicts | the run (E23) |
| `-M:evt` | an event-driven driver holding two live connections, a pure application, both under a scripted network and a real socket; prints the checker's verdicts on both. **Not a gate** | the run (E24) |
| `-M:check` | 10 declaration fixtures + 55 corpus programs across TWO corpora (nREPL 25, HTTP 30) get their recorded verdicts, AND every accepted one is executed under a scripted handler; `perturb.nrepl` and `perturb.http` are checked and reported, not gated | `report-limits`, 13 items (E15, E17, E18, E19) |
| `-M:oracle` | perturb's bencode against `jolt.nrepl`'s over their shared profile | the run |
| `-M:demo` | one session var under a real socket and two in-memory handlers; sent octets identical | the transcript |
| `-M:http` | one keep-alive driver under a scripted network (121 one-octet `recv`s) and a real loopback listener (1 `recv`, two pipelined requests); response octets identical; exhibits the unstatable Content-Length obligation | the run, and E18 nonclaims (E18) |
| `-M:noio` + `verify-noio.sh` | no syscall attributable to perturb in a scripted window, with a positive control | the verdict block (E16) |

The last two follow the rule literally: each states what its instrument *cannot*
see (`-M:noio` because `dlopen(NULL)` is invisible to strace; `-M:check` because
8 of `perturb.nrepl`'s 15 functions are axioms whose bodies it never reads).

Every `-M:check` stage is demonstrated able to fail, separately: flipping one
recorded verdict fails the verdict stage; making an *accepted* program throw
fails the run stage while the verdicts still pass — the E15 regression, caught
by the stage built for it; and flipping one declaration fixture fails the
declaration stage, which exists because two of E18's four defects had no
program-corpus artifact at all (E19).

**A gate obligation E20 adds and this table does not yet meet.** Both deployed
users of solver-backed verification name proof instability as their first-order
operational cost — F\* ships `--quake` to detect flaky proofs by re-running
queries, and the AWS Dafny team reported proof brittleness *blocking code
updates*. A gate that accepts the corpus today can reject it after a solver
bump with no source change. `-M:refine` decides its fragment by normalisation
and has no solver today, so nothing is flaky yet; the moment one is added, this
table needs a pinned version and a repeat-run check.

---

## 2. Divergence register

### The rule governing it

`clojure.*` compatibility is held by a **defeasible no-foreclosure rule**
(§1.6): the core must not make Clojure semantics inexpressible, only non-default,
and that rule may be broken later for a sufficiently interesting gain. Because
the rule is breakable on purpose, the risk is not "compat silently becomes
impossible" — it is **"compat gets broken by drift rather than by decision."** A
rule you would knowingly trade away is only useful if you can see the price at
the moment you would pay it.

So every divergence carries three columns:

1. **What differs** from `clojure.core`.
2. **How `clojure.*` would recover it** — the compat sketch.
3. **What breaking compat here would unlock** — the gain that might justify it.

Column 3 is what makes the rule defeasible on purpose. Without it a break is
indistinguishable from an oversight, and a series of individually small,
individually unexamined decisions is exactly how a compatibility layer turns out
to be impossible without anyone having chosen that.

Two asymmetries to carry:

- **Breaking is cheap at decision time and expensive to reverse.** A divergence
  that forecloses compat is a sentence of design today and a rewrite later, so
  the register should flag divergences that put compat *at risk*, not only those
  that break it.
- **Column 3 being empty is the signal to hold the rule.** If a divergence
  unlocks nothing beyond itself, keeping compat expressible is free and should
  not be traded away casually — the reason to break the rule has to be a named
  gain, not a general preference for freedom.

The register is a release obligation, not documentation hygiene: an unenumerated
divergence is indistinguishable from a bug.

### The register

| # | name | differs | compat sketch | would unlock |
| ---: | --- | --- | --- | --- |
| 1 | byte access (§1.5) | byte is an unsigned octet `0..255`, not signed `-128..127` | sign-fold on read; storage is octets either way | nothing further — compat costs one fold on the `clojure.*` path only |
| 2 | `map`/`filter`/`take` and sequence ops | `map`/`filter`/`take` etc. are eager and strict; Clojure's are lazy and chunked | `clojure.*` sequence ops are perturb's opt-in lazy-stream constructors — recoverable *modulo chunk-size observability*, which charter §1.2 already classifies as a realization detail rather than core semantics | nothing beyond itself — laziness stays expressible, so no trade |
| 3 | `=` on doubles | Clojure's `=` is IEEE on NaN, so `(= ##NaN ##NaN)` is `false` and a NaN key can never be looked up in a map containing it | `clojure.core/=` is perturb's `==` on doubles, `=` elsewhere | reflexivity, hence working collections and agreement with `compare` |

Rows 1 and 2 have column 3 empty, so the rule holds for free. **That is the
common case, and it is worth recording precisely so the uncommon case stands
out.** Row 3 is the first non-empty column 3, and the gain is *correctness*, not
performance.

### Row 1 — bytes

The decision is §1.5. Since column 2 is cheap and column 3 is empty, there is no
trade to make and the rule holds for free.

### Row 2 — eager sequences

Charter §1.2 H4 already decides this and perturb adopts it unchanged: the core
is **eager/transducer-first**. Default sequence operations are eager and strict,
transducers are the composable primitive, `into`/`transduce`-style eager drivers
are the canonical consumers, and **laziness is opt-in** via explicit lazy
stream/generator constructors with defined realization, exception, cancellation,
and resource semantics.

Column 3 empty again, so the rule holds for free — the same shape as row 1, and
the second consecutive case where the no-foreclosure constraint costs nothing.

**The charter satisfied the rule before the rule existed.** H4 says laziness is
**opt-in**, not **absent**. That distinction is exactly the no-foreclosure rule:
the core declines laziness as a default without making it inexpressible. The
charter arrived at the required shape independently, which is mild evidence the
rule is naturally satisfiable rather than a constraint that will keep binding.

**Why eager-first is stronger for perturb than for Jolt.** The charter argues H4
on ergonomics and predictability. perturb has a sharper reason the charter did
not need: **lazy realization is nondeterminism that does not go through the
oracle.**

§1.4 makes the whole simulation architecture rest on a single nondeterminism
source — every choice comes from one `choose`, the trace is the sequence of
answers, replay is feeding them back. A pervasively lazy sequence breaks that:
the point at which an element realizes is determined by *consumption*, not by
program order, so any effect inside a lazy computation fires at a time no
component chose and the oracle never saw. Realization timing becomes an
unmodelled scheduling decision.

That is not a performance argument, it is a correctness one for the determinism
claim. Pervasive laziness and §1.4's single-oracle model are incompatible;
eager-by-default with laziness as a declared, bounded construct is what makes
them coexist. An opt-in lazy stream is fine precisely because opting in is
visible — the realization points are where the program says they are.

This also constrains the opt-in form: a perturb lazy stream must have
realization semantics defined well enough that its realization points are
either deterministic or routed through the oracle. The charter already requires
"defined realization, exception, cancellation, and resource semantics" for the
lazy constructors; §1.4 is why that requirement is load-bearing rather than
tidy.

### Row 3 — equality, and what hashing turned out not to be

Four decisions, one deferral, two rejected alternatives.

#### Decision — `=` is total, `==` is IEEE

Charter §2.3 currently contradicts itself: `=` follows IEEE so `NaN ≠ NaN`, but
`compare` "NaN sorts topmost among doubles and compares equal to itself (so
total order is preserved)". Two notions of equality that disagree, in one
section.

perturb resolves it by splitting, using surface Clojure already has:

- **`=` is total** — an equivalence relation. `NaN = NaN`. `-0.0 = 0.0`
  (hashing canonicalizes, as the charter already requires).
- **`==` is IEEE/numeric** — `NaN ≠ NaN`, retaining float semantics for
  numeric code.

This is register row 3. Column 3 is non-empty for the first time, but the gain
is *correctness*, not performance: `=` becomes an equivalence relation, which is
what both hash-based collections and `compare` already assume. The charter's own
`compare` rule is evidence the total reading is the one actually wanted.

#### Decision — capability equality is identity, derived from the mode

A `unique` capability is equal only to itself. This is not declared per type; it
follows from the mode.

That removes a class of hand-written contract E2 catalogued. `jolt-bytes`
currently writes it by hand — `(equals [this other] (identical? this other))`
with the comment *"Jolt currently gives an otherwise-unadorned deftype
structural `=`. State the selected identity contract explicitly."* E12 found the
same question again as `ja-equal?` for cross-kind arrays. Both become
consequences of the mode rather than per-type declarations.

#### Decision — iteration order is deterministic within a build

The charter says hash-map/set iteration order is **unspecified**. perturb
strengthens this to **deterministic within a build, unstable across versions**.

`unspecified ≠ nondeterministic`. Clojure means "do not rely on it across
versions or implementations"; §1.4 needs "identical within a build given the
same operation sequence", which a deterministic hash over a deterministic HAMT
already provides. This is the row-2 argument again — varying iteration order is
nondeterminism that does not go through the oracle, and would make replay
inexact. Free to state, load-bearing for the determinism claim.

#### Deferred — cross-category numeric equality

`(= 1 1.0)`, `(= 1 1N)`. The charter deliberately punts (D7/C3: "no formal
numeric-`=` claim in v1") and perturb keeps that deferral. It is entangled with
the numeric tower, which is its own register row; deciding equality first means
deciding it twice.

#### Finding — the hash *algorithm* is not a divergence at all

I expected this to be the first real trade: perturb takes a 64-bit modern hash,
`clojure.*` needs Clojure's 32-bit Murmur3-compatible `hasheq`, and the two must
somehow coexist. Working backwards from what compat actually requires, that is
wrong.

**Hash values are not observable through any specified interface.** The only law
is hash-consistency, `(= a b) ⇒ (= (hash a) (hash b))`. What is observable is
`=` semantics (a real obligation, and *separate from the algorithm*) and
iteration order (already unspecified, now strengthened above). Hash values
themselves surface only through `clojure.core/hash` — and the charter already
rules that JVM-compatible hashing is "a `target-dependent` interop concern",
**"never canonical."**

So `clojure.*` can preserve Clojure's `=` exactly while using perturb's hash
underneath, provided it is consistent with that `=`. **The divergence to carry
is `=`, not `hash`** — and `=` is naturally type-carried, because a Clojure map
is a Clojure map. perturb picks the better hash once, for everything, and no
register row is needed.

This is the third consecutive axis where an expected trade evaporated on
inspection. Column 3 is empty more often than the framing suggested.

#### Rejected — equality/hash as a dynamically-scoped effect

Considered: make `=`/`hash` effect operations, so `clojure.*` installs a handler
rather than reimplementing. Rejected on three grounds, the first fatal:

1. **Hash-based structures become handler-relative.** A map's layout invariant
   is tied to the hash used to build it. Build under one handler, read under
   another, and lookup probes the wrong bucket — a silent wrong answer, not an
   error. The hash must be fixed at construction and travel with the structure;
   "varies by dynamic extent" is exactly what it cannot do.
2. **It destroys the purity tier.** `(get m k)` calls `=` and `hash` internally,
   so every map lookup would carry a non-empty effect row and §1.2's
   no-obligations value tier collapses.
3. **It is the hottest path in the language**, and E8 measured what one
   var-deref-plus-invoke costs when paid per element.

The useful residue is the constraint the failure reveals: equality and hash must
be carried by the value or its type, fixed at construction. Which is what §1.6's
layering already implies — `clojure.*` collections are their own types carrying
Clojure equality.

#### Rejected — infective build-time hash selection

Considered: if anything in the dependency graph imports `clojure.*`, the whole
image builds with the Clojure-compatible hash. It has two genuine advantages
over type-carried — complete cross-boundary interop with no dispatch question,
and zero runtime cost since the hash stays monomorphic per build.

Rejected because:

1. **It is Cargo's feature-unification failure mode.** A transitive dependency
   four levels down silently slows the whole application, invisibly from any
   call site.
2. **The trigger is a poor proxy.** "Imports `clojure.*`" does not mean "needs
   Clojure hash" — `clojure.string`/`set`/`walk` are reflexive imports whose
   behaviour does not depend on hashing. Nearly every real application would
   flip, making the fast path the unusual one.
3. **The finding above makes it moot.** There is no compat obligation on hash
   values, so there is nothing for the infection to protect.

**If it is ever revisited, declare rather than infer**: a build states its
equality profile explicitly and an incompatible import is a build error naming
the offending dependency. That converts a silent slowdown into a diagnosable
failure at no cost over the inferred form.

### Rows not yet written

Candidates where column 3 is plausibly non-empty, and where this will matter
first: laziness (row 2, now written — charter H4 already diverges), equality and
hashing (row 3, now written), **the numeric tower**, and **the error model**.
Each should get its register row when decided, not retrospectively.

---

## 3. Findings

E1–E24, each stated as currently believed. What each said first, and what
corrected it, is in Appendix A. E1–E13 are measurements and prototypes; E14 is
a source-and-history survey of the v0.5.17 branch lane and is `assumed`
throughout — it qualifies §1.4 and §2 row 3 without settling either. E15–E19 are
the running artifact: a checker that rejects real programs, and the four rounds
of failure and repair it went through. E22–E24 are the first measurements taken
against code **not written by someone who knew the rules** — a probe of a claim
nobody had tested, an unbiased port, and an architecture experiment whose
positive controls it failed. **E20 is different in kind from every
other finding here** — a literature survey, `assumed` throughout, in which
nothing was executed and no paper was read in full text. It refutes four claims
this document made and is itself the weakest evidence in it; Appendix D exists
so each of its citations can be checked against the source later.

### E1 — the codec byte path, and where its cost actually is

`monitored`, single non-isolated sample, unpinned toolchain for the first
tables below (see §6 nonclaims); the pinned re-measurement further down is at
the target tuple but is still a single non-isolated sample on one machine, and
no cross-platform claim is made. See §6 for the standing qualifications and E9
for this host's noise floor.

#### What is now believed — the final attribution chain

Pinned full bencode decode 985 µs → ~70–80% byte access → `nth` on Window
1145 ns/byte → 74% of that is the **method body**, not dispatch → 72% of the
body is `signed-byte-at` → ~80% of that is **`unchecked-byte`**, one arithmetic
primitive defined in the `clojure.core` overlay rather than as a host native,
measured at ~209 ns/byte net (E8). On those proportions `unchecked-byte` was a
double-digit percentage of total decode time.

Deftype dispatch is ~19% of `nth` on a Window and devirtualization is worth ~3%
(E7). Underneath all of it, `aget` is not an array read at all — it lowers to
the generic `jolt-nth` dispatch — and a byte array costs eight bytes per element
(E11).

Read the tables below as *where the time appeared to be*, and E7/E8/E11 as where
it is.

#### The original measurements, retained

Measured on the jolt-bencode nREPL benchmark frame (98 bytes, 10 strings), on a
self-built Chez 10.4.1 and jolt at `380e59e`:

| path | ns/byte |
| --- | ---: |
| `aget` on byte-array | 54 |
| arithmetic only, no access | 45 |
| `nth` on persistent vector | 81 |
| `reduce` over Window (IReduce) | 518 |
| `nth` on Window (deftype `Indexed`) | 1336 |

Decomposition of `decode-utf8` over a 22-byte string (45.5 µs):
`window-octets` 33 µs (72%), of which the bare `octet` scan is 29 µs (64%);
`String.`/`.getBytes` round-trip 1 µs (2%); remainder ~11.5 µs.

**Two hypotheses are refuted by this measurement.** Host-interop `String`
emulation was hypothesized (in session) to dominate: it is 0.2% of decode.
`jolt-bytes/docs/PERFORMANCE.md` attributes the gap to allocation of "decoded
Clojure values and parser result maps": allocation is real but minor —
`window-octets` costs only ~12% more than the bare scan it wraps.

#### What the original analysis got right, and the fix it produced

`host/chez/collections.ss` `jolt-nth` is a `cond` chain whose persistent-vector
fast path is position 2 and whose `deftype` path is position 5, reached via
`rec-coll-method` → `find-method-any-protocol`, which allocated a fresh
`hashtable-keys` vector and performed up to 2N string-keyed lookups per call for
an N-protocol type. That was real and worth removing — it is 40% of decode at
the pinned tuple — but it was the minority term, and the analysis that followed
it is **wrong**; see E7. The original text is retained here, since it was the
basis for the landed fix:

> The residual gap to persistent-vector `nth` is two string-keyed lookups still
> on the path (the `type-registry` tag hash and the method-name hash) plus
> generic invoke.

Partial fix landed as `jolt@048582c3`: flatten and memoize per-type protocol
method resolution, guarded by `jolt-proto-epoch` and per-type-table identity.
Result: 1336 → 1009 ns/byte; full decode 493 → 368 µs (25%). Gates: unit
1054/1054, devirt 12/12, pic 22/22, protoret 4/4, infer 36/36.

Closing the residual by resolving collection methods to a descriptor-local slot
at registration time would change the `nongenerative` `jrdesc` record layout —
**not attempted** in that form; a deliberate stop, not a completed fix. Q1
records what was implemented instead.

#### The pinned re-measurement

Target tuple satisfied on both halves. Chez built from
`e95a7efbafa2cf3bd5343ea542e6bc909a7ab2c4` with all five submodules matching
`jolt-toolchains/config/toolchains.json` exactly (lz4 `ebb370ca`, nanopass
`bb47b569`, stex `afa60756`, zlib `da607da7`, zuo `a288cbfe`); Jolt at
jolt-bencode's pinned `89fe46e8a826b60b69d264fab76c864881055830`.

A/B of `jolt@31cf9de0` (both perf commits) cherry-picked onto the pinned image:

| measurement | pinned baseline | + patch | change |
| --- | ---: | ---: | ---: |
| full bencode decode | 985,225 ns | 595,789 ns | **−40%** |
| `nth` on Window | 4,326 ns/byte | 2,061 | **−52%** |
| `decode-utf8`, 22-byte string | 98,389 ns | 56,931 | −42% |
| bare `octet` scan, 22 bytes | 80,886 ns | 37,863 | −53% |
| `reduce` over Window | 766 ns/byte | 640 | −16% |
| `nth` on persistent vector | 86 ns/byte | 86 | — |
| `aget` on byte-array | 95 ns/byte | 95 | — |

The single-sample full decode of 985,225 ns was **originally reported as
reproducing** `jolt-bencode/docs/PERFORMANCE.md`'s recorded 991,008 ns to 0.6%.
E10 refutes that: with 5 runs the baseline median is 1,211,841 ns, 22% above the
recorded figure. The 0.6% agreement was a single sample landing near the recorded
value; it was luck, not reproduction. **No claim that this host reproduces the
published absolute survives.** What survives: the setup is at the pinned tuple,
the arms are identical apart from the change under test, and E10's control row
holds.

**Correctness validated, not merely neutral.** On the pinned image with the
patch applied: jolt-bytes `:status :verified`, 132,672 assertions (969 parents,
20,349 slices, 2,601 cursor reads, 4,845 compositions, 825 overlap cases);
jolt-bencode 13 tests, 109,209 assertions, 0 failures; Jolt unit gate 1127/1127.
The earlier jolt-bytes failure was purely version skew, as suspected.

**Jolt had already improved this path independently.** The same `nth` costs
4,326 ns/byte at `89fe46e8` and 1,336 at `380e59e` — a 3.2x gain from work
between those commits. The 25% figure recorded against `380e59e` understated
the patch because much of the win was already captured there; at the pin where
the recorded baseline lives, it is 40%.

### E2 — Five independent hand-rolled ownership systems exist

`assumed` (source inspection, complete across the repos read).

| location | mechanism |
| --- | --- |
| `jolt-sim` | modeled-resource provenance ledger; self-described "not general taint tracking", "conservative for the whole scope (no early retirement yet)" |
| `jolt` core | `borrow-byte-array`/`release-byte-array` scoped descriptor loan |
| `jolt-bytes` | documented gap: "do not establish exclusive ownership or native-operation leases… callers retain responsibility" |
| `jolt-hako` | `proofs/prolog/ownership.pl` — exclusive `BaseOwner` + coexisting lease borrowers; canonical bug state is `owner_count(writer_result, 2)`; SMT families for borrow generation and lease release |
| `jolt-sim-planning` P4 | capability registry `cap[k] = :held \| [:consumed-by p]` with source closure — granted once, held, consumed exactly once, consumer recorded |

Each is a static property enforced dynamically or by convention, re-derived per
library. P4's is a linear capability type written out as a model.

### E3 — Every existing proof obligation is capability-tier — of this sample

`assumed` (source inspection, complete across the repos read). **Sample-biased;
see the qualification below, which is part of the finding.**

| source | proves | about |
| --- | --- | --- |
| jolt-bytes (Ansatz/CIC) | slice end preserved, slice contained, cursor reads | bounds geometry |
| jolt-hako (Z3) | builder growth prefix, bounds transactional commit, utf8 count capacity, preflight encode atomic | bounds, capacity, commit geometry |
| jolt-hako (Z3) | native lease completion, borrow generation, non-inheriting scratch | ownership, leases |
| jolt-hako (Prolog) | exclusive owner + lease lifecycle | ownership |
| jolt-bencode (Z3) | byte-string frame header, failure-consumes | framing/commit geometry |
| jolt-sim-planning P4 | capability held → consumed-by, source closure | linearity |

The original conclusion: "**No obligation is about application semantics.** Every
one concerns bounds, ownership, linearity, or commit geometry — the same tier
E2's ownership systems guard. This is the load-bearing finding for the tier
split."

**That is true of the sample and not of the domain.** The sample was
`jolt-bytes`, `jolt-hako`, `jolt-bencode`, and P4 — byte buffers, codecs, and a
mailbox. Codec libraries *have* no application semantics; finding none is close
to tautological. E3 is evidence about what a codec needs. It is **not** evidence
about what a consensus protocol or a search tree needs, and it was used as
though it were.

This matters because four consecutive axes (bytes, sequences, hashing, `Any`)
resolved by appeal to tier confinement. Either the split is doing real work, or
the same move keeps succeeding because everything examined so far lives on one
side of it. Codecs are exactly the workload that would hide the difference.

#### Three property classes, only one of which is covered

| class | example | mechanism | status |
| --- | --- | --- | --- |
| **resource safety** | bounds, ownership, leases, commit geometry | modes + QF-LIA refinements | designed (§1.2/§1.3), E3's whole sample |
| **structural / inductive** | tree balance, in-order traversal sorted, acyclicity | inductive predicates or dependent types | **not covered** — QF-LIA cannot quantify over subtrees |
| **temporal / behavioural** | election safety, log matching, eventual commit | trace monitors, refinement relations, liveness under fairness | partly covered |

Structural invariants are the clearest gap. `0 ≤ offset ≤ capacity` is QF-LIA;
"every path from the root has equal black height" is not, and no amount of
capability-tier refinement reaches it. That is Ansatz/CIC territory — which the
stack already has (E3's own proof survey), but which §1.3 treated as a residual
for "obligations outside the type system" rather than as a first-class tier.

Temporal properties are partly covered: safety properties fold over a trace,
which `monitor.clj` already does. **Liveness does not** — it needs fairness
assumptions and either bounded response (P4's seed) or a temporal logic, and
P4's own approval explicitly is "not a SAT/UNSAT result". Refinement relations
— showing an implementation refines a spec, which is how consensus protocols
are actually proved — are gestured at by P3 and bounded by P5 §4.2 to
`explore_states` alone.

#### What this does and does not change

**Does not change:** §1.4's machinery. Effects, the kernel, the single oracle,
and handler-level faults are, if anything, *under-exercised* by codecs — E4
showed the codec layer needs no scheduler and no continuations at all.
Consensus (message passing, partial failure, schedule search) and disk
structures (crash injection at every I/O boundary) are the workloads that
apparatus was built for. Those should fit better, not worse.

**Does change:** the claim that capability-tier typing covers the proof surface.
It covers *resource safety*. It says nothing about *algorithmic correctness*,
and consensus and data structures are mostly the latter. perturb needs a third
tier — inductive and temporal properties — and the components exist (Ansatz for
inductive, `explore_states`/monitors for temporal). The open work is
integration and, for liveness, genuine design (§4.6).

#### Consequence for v0

The original v0 ladder was entirely codec-shaped, which is how the bias got in.
It should gain a **non-codec target** before the typing decisions harden — one
that exercises the kernel, faults, and a liveness property, none of which
bencode touches. P4's capacity-one mailbox is the cheapest candidate since it is
already fully specified, with a small leader election as the more honest one.
That recommendation was taken up and then superseded twice on the choice of
target (Appendix A rows 26–27); the target chosen is now §5.

### E4 — The sans-io decoder shape is already validated

`bounded-complete` within its stated corpus (jolt-bytes/jolt-bencode oracles).

`jolt.bytes/read-window` and `jolt.bencode/decode` return a transactional
trichotomy — `:ok` with a new cursor, `:need-more` with the *exact original*
cursor, `:invalid` with reason/offset and the original cursor — with commit only
at the exact frame boundary. Backed by 132,672 assertions over 969 bounded
parents, 20,349 slices, 2,601 cursor reads, 4,845 two-read compositions, plus
Hegel state machines.

This is a pure step function: it never blocks and never performs I/O. The
protocol/codec layer therefore requires no continuations, empirically rather
than by design preference.

### E5 — the capability tier needs three disciplines, not two

**This qualifies §1.2 and §1.3.**

`docs/research/prototypes/mode_checker.py` implements a rule set for the
capability tier; `equivalence.py` transcribes `jolt-hako/proofs/prolog/
ownership.pl` `step/3` and compares verdicts over **every** operation sequence
to a given depth, rather than the 9 traces `queries.json` spot-checks.

The successive results, each a genuine correction:

| rule set | unsound acceptances, depth 8 |
| --- | ---: |
| uniqueness + linearity (as first specified) | **1051 / 6470** |
| + typestate (role-indexed operation legality) | 103 |
| + exclusive borrow (a lease blocks reads, not only moves) | **0** |

Final state: depth 8 — 6,470 sequences examined, 1,236 accepted by both, zero
disagreements in either direction. Depth 10 — 23,430 examined, 4,406 accepted
by both, zero disagreements. `queries.json`: 9/9 decided as recorded.

#### What the two corrections mean

**Typestate is a required third axis.** Every move in the model carries a
*source* precondition — `detach_result`, `move_to_region`, `return_pool`, and
`reset_writer` are legal only from `writer`; `checkout_pool` only from `pool`.
That is not a uniqueness property. The capability carries a role (writer =
mutable working state, result = published, pool = returned, region = arena-held,
none = consumed) and each operation is legal only from specific roles.
Uniqueness and linearity supply the exclusion and the consume-once discipline;
they say nothing about role sequencing. Dropping the source preconditions
wrongly admitted 16% of the space.

**A borrow must be exclusive, not merely move-blocking.** The model forbids
`use_region` while a native lease is live: the owner cannot *read* through a
live lease either. Enforcing the freeze only on ownership transfer left 103
sequences wrongly accepted.

#### Consequences for §1.2 / §1.3

The original axis list — "Axes kept: uniqueness, linearity" — is
**insufficient** as stated. The capability tier requires uniqueness, linearity,
and typestate, with borrows exclusive by default.

§1.3's claim that capability-tier refinements "cover 100% of the existing proof
surface" survives but is now qualified: coverage requires the typestate axis,
which E3's survey did not name because the obligations it catalogued
(bounds, ownership, linearity, commit geometry) hide role sequencing inside
"ownership". Bounds and commit geometry remain refinement obligations; role
sequencing is a separate typing judgement.

#### What this does and does not establish

Establishes: a syntactic type discipline decides, without state-space search,
exactly what `ownership.pl` decides by bounded reachability — over the whole
space to depth 10, not merely the recorded queries. Where the model answers
"no double ownership within 8 steps" by exhaustion, the environment holds one
owner field, so `writer_result` is unrepresentable; that specific query becomes
structural rather than bounded-complete. **E6 probe 2 bounds that last sentence
to the single-capability fragment.**

Does not establish: soundness beyond depth 10; that the rule set generalizes to
capabilities other than this one buffer; or that a real perturb checker over the
Jolt IR would infer these judgements rather than check them on a pre-supplied
operation sequence. The prototype checks a straight-line sequence, not a program
with branching, loops, or higher-order calls — where the reachability property
becomes genuinely harder. `equivalence.py` is a differential test against one
model, not a proof.

**Method note.** The 9 recorded queries passed at the *first* rule set — the one
carrying 1,051 unsound acceptances. Spot checks drawn from a specification are
not a substitute for differential testing against the specification's own
semantics, which is the discipline `bin/verify-*` already applies elsewhere in
these repositories.

### E6 — three probes past the straight-line fragment

E5's rule set checks a straight-line operation sequence over one unnamed
capability. Three probes push past that. Prototypes: `controlflow.py`,
`multicap.py`.

#### Probe 1 — branching and loops: the rule set survives

`controlflow.py` adds `if` and `loop` with the standard judgements: an `if`
checks both arms from the same environment and requires the join to **agree**;
a `loop` body must be **environment-preserving**. 12/12 cases as expected — a
complete lease cycle is environment-neutral so it joins with doing nothing and
iterates in a loop, while a move in one arm only, divergent moves per arm, a
dangling borrow in one arm, and a loop that moves or leaves a lease live are
all rejected.

Weaker evidence than E5: both the rules and the expectations are authored here,
so this is a consistency check, not a differential test against an independent
specification.

**Design consequence, not a defect:** the join rule rejects
`if (c) { b = detach_result(b) }; use(b)` — a shape ordinary code writes freely.
"May or may not have been moved" is not a mode, and no sound successor typing
exists, so rejection is correct but is exactly where typestate systems earn
their reputation for friction. Mitigations (explicit reconciliation, a sum state
requiring a case-split, per-variable flow-sensitivity) are unexplored here and
are a real usability risk for §1.2.

#### Probe 2 — names and aliasing: E5's structural claim was too strong

`multicap.py` adds named capabilities, moves, and function signatures
(`consume`/`borrow`/`produce`). Seven cases passed immediately. The eighth
**failed**, and it matters:

```
new a; alias b = a; a.detach_result; b.return_pool     -- ACCEPTED (unsound)
```

Two names for one capability, moved to two different roles. That reconstructs
`writer_result` — the exact two-owner state hako's `double-owner-bug.pl`
injects, and the state E5 called *unrepresentable*.

**E5's structural claim holds only for the single-capability fragment.** `Env`
has one owner field, so one capability cannot have two owners; but two *names*
can hold one capability and diverge. Uniqueness must therefore be enforced at
the **binding form**, not only at operations — capabilities bind **affinely**,
with no non-moving alias. With that rule the probe is 8/8, and E5's equivalence
(depth 10, zero disagreements), probe 1 (12/12), and the recorded queries (9/9)
all still hold.

This is the second time a claim survived its spot checks and failed a probe
designed to attack it.

#### Probe 3 — the SMT families: modes cover two of seven

Classifying `jolt-hako/proofs/smt`'s seven families against the rule set:

| family | decided by |
| --- | --- |
| borrowed-view generation/lifecycle gating | **modes/typestate** |
| native-loan release after completion | **modes/typestate** |
| owner-tagged non-inheriting scratch across a thread fork | **contention** — see below |
| subtraction-form bounds and transactional read commit | refinement (QF-LIA) |
| contiguous builder growth and complete prefix copying | refinement |
| preflight and all-or-nothing publication geometry | refinement + commit typestate |
| UTF-8 scalar count, capacity, transactional publication | refinement |

Two of seven are mode/typestate obligations; four are arithmetic refinements —
consistent with §1.3's split, and confirming both halves are load-bearing rather
than one subsuming the other.

**The seventh contradicted the original axis list.**
`noninheriting-scratch-corrected.smt2` declares `parent_thread`,
`child_thread`, `inherited_owner`, `inherited_scratch` and asserts an
`alias_violation` — whether an owner-tagged scratch buffer is inherited across a
thread fork. That is a **contention/portability** obligation, and the axis had
been cut on the grounds that it is "only needed if you want real shared-memory
parallelism" which explicit message passing would sidestep. It is in the
*current* proof surface regardless, so the axis is restored in §1.2.

#### Net effect on the axis list

| as first written | after E5 and E6 |
| --- | --- |
| uniqueness, linearity | uniqueness, linearity, typestate, contention |
| (borrow blocks moves) | borrows **exclusive** — block reads too |
| (no binding rule) | capabilities bind **affinely** |
| locality dropped | locality still dropped (Yarrow interaction stands) |

Every addition came from a probe designed to break the previous claim, and none
from the spot checks the specification supplied. That pattern is now the method
note in E5, restated: derive the acceptance criterion from the specification's
own semantics, not from its examples.

### E7 — devirtualization is worth ~3%, not 24x; the cost is the method body

**This supersedes E1's pinned-re-measurement closing attribution and retires the
backend-devirtualization work item.**

That attribution put the residual gap on "the `jolt-nth` cond preamble and
generic `jolt-invoke`" and proposed call-site devirtualization for
collection-interface methods. Investigation found a real structural blocker for
that proposal: `passes/types.clj` attaches `:devirt-type` only when the callee
resolves through `env`'s `:protocol-methods` — user-defined protocol methods.
`nth` is `clojure.core/nth`, so it never gets `:proto`/`:method`, never
devirtualizes, and never receives even a PIC.

That blocker is real but **irrelevant**, because the premise was wrong.

`measurements/profile3.clj` puts every path in one run on one deftype:

| path | ns/byte |
| --- | ---: |
| `nth` on a persistent vector | 81 |
| `pget`, a USER protocol method (devirt-eligible) | 263 |
| `nth`, a core builtin (never devirt-eligible) | 300 |
| `.b` dot-field form | 945 |
| `nth` on the real `jolt.bytes/Window` | 1145 |

Decomposition:

- **deftype dispatch overhead ≈ 219 ns/byte** (300 trivial-body vs 81 vector).
- **Window's own `nth` body ≈ 845 ns/byte** (1145 real vs 300 trivial) —
  **74% of the cost**.
- **Devirtualization is worth ≈ 37 ns/byte** (300 vs 263, the devirt-eligible
  protocol method on the same receiver) — about **3%** of 1145.

So the remaining gap is not a dispatch problem. It is `Window/nth`'s body:
bounds checks, offset arithmetic, and the backing-array access, at ~15x the
54 ns/byte a bare `aget` costs in a tight loop. That points at generic numeric
operations and array indexing inside deftype method bodies — the unboxed
representation question of §1.2, not the call-site question.

**Retired:** backend devirtualization for collection-interface methods. It
would buy ~3% and requires changing how core collection fns are recognized in
the type pass.

**Redirected:** the open performance question is now whether deftype method
bodies can get unboxed fixnum arithmetic and direct array indexing. Not
investigated.

#### Method note, third instance

This is the third hypothesis of mine that measurement refuted:

1. host-interop `String` emulation dominates → it is 0.2% (E1);
2. allocation dominates, per `PERFORMANCE.md` → it is the minority term (E1);
3. dispatch dominates, so devirtualization is the fix → dispatch is 19%,
   devirtualization worth 3% (this section).

Each was plausible from source reading and each was wrong. The pattern matches
E5's finding on the rule set: what survived was always what was tested against
an independent measurement or specification, never what was argued from
inspection. No performance claim in this document should be trusted ahead of a
number.

### E8 — the dominant byte-path cost is `unchecked-byte`, a Clojure-level defn

E7 put 74% of `Window/nth` in the method body and redirected to representation.
`measurements/profile4.clj` decomposes that body. Net of a 27 ns/byte loop floor:

| stage | ns/byte | net |
| --- | ---: | ---: |
| loop only | 27 | 0 |
| `aget`, inline | 54 | 27 |
| `aget` through a `defn-`, `^bytes` hinted | 72 | 45 |
| `aget` through a `defn-`, **hintless** | 72 | 45 |
| `(int index)` checked cast | 36 | 9 |
| generic `(+ offset index)` | 36 | 9 |
| `integer?` | 54 | 27 |
| `valid-index?` (three predicates, through a `defn-`) | 100 | 73 |
| `signed-byte-at` = `(unchecked-byte (aget b i))` | 290 | 263 |
| **`unchecked-byte`, on a CONSTANT** | **236** | **209** |

`unchecked-byte` alone — no array access at all — is **~8x the `aget` it wraps**
and ~80% of `signed-byte-at`. Function-call overhead is ~18 ns/byte, and the
`^bytes` hint is worth **nothing** (72 hinted, 72 hintless).

#### Cause

`jolt-core/clojure/core/22-coll.clj:295` defines it in Clojure, not as a host
native:

```clojure
(defn unchecked-byte [x]
  (let [b (bit-and (unchecked-long x) 0xff)] (if (< b 128) b (- b 256))))
```

Per call: a var deref and invoke, a call to `unchecked-long`, then generic
`bit-and`, `<`, and possibly `-` — five-ish numeric-tower operations and two
calls for what should be a mask and sign-extend. The comment directly above it
records that **`unchecked-long`/`unchecked-int` are host natives**
(`converters.ss`); the byte, short, and char variants were left at the Clojure
level. That looks like an oversight rather than a decision.

#### Fix

Implement `unchecked-byte`, `unchecked-short`, and `unchecked-char` as host
natives alongside the existing `unchecked-long`/`unchecked-int`. Small and
local. It was **not attempted in the session that found it** because it edits
the `clojure.core` overlay, which per the Jolt README requires `make remint` to
iterate the bootstrap seed to a byte fixpoint — too long to start and verify
within the remaining session. It landed subsequently — E9.

#### Attribution chain

Pinned full bencode decode 985 µs → ~70–80% byte access → `nth` on Window
1145 ns/byte → 74% method body → 72% of the body is `signed-byte-at` → ~80% of
that is `unchecked-byte`. On those proportions `unchecked-byte` is a
double-digit percentage of total decode time, for one arithmetic primitive.

This is a **Jolt finding independent of perturb**, and it is more actionable
than anything the devirtualization line would have produced.

#### Consequence for §1.1

The original justification read: "E1 is **not** evidence against this choice:
`aget` at 54 ns/byte is adequate; the measured gap is dispatch structure, not
Chez codegen." E7 and E8 show the gap is neither dispatch nor Chez codegen — it
is **which primitives Jolt implements natively versus in its own core overlay**.
The conclusion (Chez is not the problem) survives, and is in fact strengthened:
`aget` at 54 ns/byte and a function call at 18 ns/byte are both fine. The stated
*reason* was wrong and is corrected in §1.1.

### E9 — the fix landed; and this host's noise floor is ~10%

`jolt@584aecd4` nativises `unchecked-byte` and `unchecked-short` in
`converters.ss` in the same shape as the existing `unchecked-int`.
`unchecked-char` stays in the overlay (it returns a char, and is not on the
byte path). Seed re-minted, converged in 2 passes.

| measurement | before | after |
| --- | ---: | ---: |
| `unchecked-byte` alone | 236 ns/byte | **63** |
| `signed-byte-at` | 290 | **109** |
| `Window/nth` body, standalone | 418 | **200** |

Gates: unit 1054/1054, devirt 12/12, pic 22/22, protoret 4/4, infer 36/36,
narrow 10/10, contagion 20/20. Semantics spot-checked against JVM values:
`(unchecked-byte 200)` → -56, `(unchecked-byte -1)` → -1,
`(unchecked-short 40000)` → -25536, `(unchecked-char 65)` → `\A`.

#### The measurement finding, which matters more

End-to-end decode after the fix measured 437, then 395, 421, 432 µs on
repeated runs of the **same build** — a ~10% spread. So:

**No end-to-end improvement is claimed for E9.** The primitive-level gains
(3.7x on `unchecked-byte`, 2.1x on the standalone body) are far outside that
band; the aggregate is not resolvable from single samples on this host.

This retroactively qualifies earlier single-sample comparisons in this
document:

| comparison | delta | verdict at a ~10% floor |
| --- | ---: | --- |
| the pinned A/B (E1), 985 → 596 µs | −40% | **survives** |
| E1's unpinned pair, 493 → 368 µs | −25% | probably survives; single samples |
| §5's descriptor-keyed commit, 1009 → 863 ns/byte | −14% | **marginal** |
| §5, "full decode unchanged at ~370 µs" | ~0% | **within noise; not evidence** |

*(The last two rows name figures recorded in the session narrative of the
superseded §5 rather than in a table reproduced here; they are kept exactly as
written. The final row's phrase does not appear in the §5 text this document
retains, and the discrepancy is left as-is rather than repaired — see §4.6.)*

The `jolt@31cf9de0` commit message states 863 vs 836 as "within noise" for the
weak-table variant — correctly — but treats 1009 → 863 as real. At this floor
that too is marginal. The commit stands on its structural properties (no leak,
invalidation already wired), which were the stated reason for keeping it.

**Standing correction:** every ns/byte figure in this document is a single
non-isolated sample unless stated otherwise, on a host with a ~10% run-to-run
spread. Ratios of 2x and above are safe; anything under ~20% needs repeated
sampling on a quiet machine before it is evidence. The pinned-tuple A/B in E1
was, at the time it was written, the only comparison here taken against a
reproduced published baseline — and E10 then removed even that standing.

### E10 — the pinned A/B with repeated sampling

Arms: `jolt@89fe46e8` clean, versus the same commit plus all three perf changes
(`048582c3` memoize, `31cf9de0` descriptor-keyed, and the `unchecked-byte`/
`unchecked-short` nativisation applied as a **minimal** source edit rather than
a cherry-pick, because `22-coll.clj` and `converters.ss` had drifted between the
commits and taking whole files would have contaminated the comparison). Seed
re-minted in the pinned worktree, converged in 2 passes; semantics re-checked
there (`(unchecked-byte 200)` → -56, `(unchecked-short 40000)` → -25536).

5 runs per arm via `measurements/repeat-sample.py`, medians in ns:

| label | baseline | patched | change | spreads |
| --- | ---: | ---: | ---: | --- |
| full bencode decode | 1,211,841 | **692,135** | **−42.9%** | 4.9% / 10.5% |
| `decode-utf8`, 22 bytes | 126,284 | 64,915 | −48.6% | 17.9% / 8.0% |
| `window-octets`, 22 bytes | 106,109 | 51,425 | −51.5% | 6.7% / 7.3% |
| bare `octet` scan | 101,975 | 46,783 | −54.1% | 7.3% / 10.2% |
| direct UTF-8 validate | 102,555 | 46,106 | −55.0% | 38.7% / 6.2% |
| **`String`/`getBytes` round-trip** | **1,722** | **1,749** | **+1.6%** | 8.4% / 16.4% |

**The last row is the control.** The host-interop round-trip touches none of the
three changes, and it did not move — +1.6%, inside both spreads. Everything on
the byte path moved by roughly 2x, far outside them. That the one term expected
to be invariant *was* invariant is the strongest internal evidence here that the
comparison measures what it claims.

**Result: −43% end-to-end bencode decode at the pinned target tuple**, from the
session's three changes combined, with repeated sampling and a control.

#### Correction to the reproduction claim

The pinned re-measurement stated the setup "reproduces the recorded baseline to
within 0.6%" — 985,225 ns measured against `PERFORMANCE.md`'s 991,008. With 5
runs the baseline median is **1,211,841 ns**, 22% above the recorded figure. The
0.6% agreement was a single sample landing near the recorded value; it was luck,
not reproduction.

What survives: the setup is at the pinned tuple, the arms are identical apart
from the change under test, and the control row holds. What does not: any claim
that this host reproduces the published absolute. Cross-host absolutes should
not be compared at all from these measurements — only within-arm deltas taken
in the same session.

That makes E10 the second correction to arrive from repeated sampling alone
(E9's ~10% floor was the first), and both invalidated numbers that had been
stated with more confidence than a single sample can carry.

### E11 — `aget` is generic dispatch, and a byte array costs 8 bytes per element

Both found with `jolt.perf` (`jolt@aa278165`, `73dc9aee`), statically or with a
deterministic counter. Neither needed a benchmark.

#### `aget` is not an array read

```
(fn [b i] (aget b i))  →  (lambda (b i) (jolt-nth b i))
```

An array read lowers to the generic collection dispatch — the same `jolt-nth`
`cond` chain E1 optimised. The 54 ns/byte that E1 recorded as the "floor" and
§1.1 cited as evidence that Chez codegen is adequate is therefore not a
primitive read at all; it is generic dispatch that happens to hit an early
`cond` arm.

A typed fast path already exists as precedent: `jolt-flaget` is emitted for
`(aget ^doubles a i)` when `jolt.passes.numeric` proves the array kind, and it
"skips jolt-nth's case-lambda + jolt-array?/flvector? dispatch". The insertion
points for a `^bytes` analogue are exact — `passes/numeric.clj:170` (the
`:fl-aget` clause), `backend_scheme.clj:838` (emit), a native beside
`jolt-flaget` in `natives-array.ss`, and a gate mirroring `run-flarr.ss`.
**Not attempted:** it spans the analyzer's array-hint plumbing, the numeric
pass's kind lattice (which knows `:doubles`/`:floats`, not `:bytes`), the
backend, and a new gate — more than could be implemented and verified
responsibly in the remaining session.

#### A byte array is eight bytes per element

`natives-array.ss` builds a byte array as
`(make-jolt-array (list->vector (bytevector->u8-list a)) 'byte)` — a Scheme
**vector of fixnums**, not a bytevector. Measured with the allocation counter:

| allocation | bytes/element |
| --- | ---: |
| `(byte-array 1000)` | **8** |
| `(make-array Long/TYPE 1000)` | 8 |

A byte array and a long array cost the same. For the codec path this is an 8x
memory and cache-footprint penalty on precisely the data being scanned, and it
is invisible to every timing measurement in this document because it changes
the constant, not the shape.

Switching the backing to a Chez `bytevector` is the larger and more valuable
change, and also the riskier one: it touches `na-byte-array`, `ja-set!`, the
`aset` path, seq/reduce over arrays, and the FFI byte-array interop —
including the `borrow-byte-array`/`release-byte-array` loan contract that
jolt-sim depends on (E2). **Not attempted.**

#### Method note, fourth instance

E1's table named `aget` at 54 ns/byte as the floor and §1.1 leaned on it to
conclude "the measured gap is dispatch structure, not Chez codegen." Both
sentences were built on the assumption that `aget` is an array read. One line
of `optimized-scheme` output shows it is not, and one counter reading shows the
array is eight times larger than assumed. Neither fact is visible from timing,
which is why ten sections of timing did not surface them.

### E12 — encapsulating the array backing, and two semantics carried forward

`jolt@57980315` routed all `jolt-array` backing access through `ja-*` helpers,
as the safe precondition for changing the byte backing. Delegated to a
subagent; the results correct this document twice.

#### My survey was wrong in two ways

Recorded in §12 as "~35 direct uses across 10 files". Verified: **41 raw
operations across 7 files**, all under `host/chez/java/`. Three of the ten
files I named have no backing access at all — `natives-coll.ss`'s `jolt-array`
hits are `jolt-array-map`, an unrelated name collision with Clojure's
`array-map`; `records.ss` uses only `jolt-array?`/`jolt-array-kind`; `io.ss`
has none.

*(The "~35 direct uses across 10 files" figure was recorded in the session
narrative rather than in the §12 text this document retains; the sentence is
kept verbatim, and the dangling reference is noted in §4.6 rather than
repaired.)*

More seriously, **I framed the problem as reads and it is not**. Six
*construction* sites hand-build byte backings outside `na-byte-array` —
`ByteBuffer/allocate`, `/allocateDirect`, `.slice`, `jolt.ffi/read-array`,
`io/copy`'s output-stream shim, and `Files/readAllBytes` — using
`(make-vector n 0)` or `(list->vector (bytevector->u8-list bv))` directly.
Those would have broken under a representation change exactly as badly as the
reads, and nothing in a grep for read operations would have surfaced them.

#### Two semantics that must be decided, not discovered

Both were found by the refactor and are recorded here because each would
otherwise reappear later as a regression with no obvious cause:

1. **Cross-kind array equality.** `ja-equal?` is `equal?` today, so
   `(Arrays/equals (byte-array [1 2]) (int-array [1 2]))` is `true`. On a
   bytevector backing the naive form becomes `false`. The refactor extracted
   `ja-equal?` specifically so this has one home.
2. **Write range-checking.** `bytevector-u8-set!` *errors* on out-of-range
   values where `vector-set!` accepts them silently. Today only `na-aset-byte`
   masks; the generic `aset` / `jolt.host/ref-put!` path does not. The byte arm
   must mask or throw deliberately, consistently with `na-aset-byte`.

A third, higher-risk one is signed-versus-unsigned: Java `byte[]` slots are
signed, Chez `bytevector-u8-*` is unsigned, and Jolt's convention appears to be
storing octets and converting on read (`jolt-bytes`' `signed-byte-at` is
`(unchecked-byte (aget b i))`). That must be verified empirically rather than
assumed.

#### Method note, fifth instance — now about delegation

The delegated agent verified the file list rather than trusting the brief, and
the brief was wrong. It also found a category of site the brief's framing
excluded by construction. The pattern from E5 and E7 extends: **a brief is a
specification, and its examples are not its semantics.** Delegation is not
exempt — an agent told what to change will change that; an agent told what
property must hold will find what the instructions missed. The instruction that
did the work here was "an honest inventory of the remaining hard cases is a
valuable deliverable, do not force-fit those", which licensed reporting over
compliance.

### E13 — the driver types; transducers need abstract refinements

**This qualifies §1.2 and §1.3, and closes the second half of Q2.**

Q2 left two risks. The `Any` escape hatch resolves by confinement — a
capability is minted by an operation, never parsed from data, so it is never
`Any`. The other was open: *"inference for higher-order and polymorphic-
recursive code is where liquid typing is least comfortable in practice."*

Prototypes: `docs/research/prototypes/refinement.py` (refinements over
`jolt.bytes/Window`, `Cursor`, `read-window` and `jolt.bencode/decode`, with a
self-contained QF-LIA decision procedure), `higherorder.py` (the sans-io
driver), `transducer.py` (composition). 7/7, 12/12 and 16/16 cases as
expected, wired into `verify-capability-rules`.

**Method limit, stated up front.** Types are **hand-annotated and checked**.
Nothing is inferred, so this establishes that the discipline is *expressible*
and that the obligations are *dischargeable*; it says nothing about whether an
inference engine would find them. The solver is Gaussian elimination plus
Fourier-Motzkin over the rationals with DPLL splitting: sound for validity,
incomplete over the integers. No result below turns on integrality.

#### The driver types, against the step function as a parameter

The driver — feed bytes, retry on `:need-more`, emit on `:ok`, stop on
`:invalid`, with the step function as an argument and no knowledge that it is
bencode — **type-checks**, discharging every obligation from the step's
*declared* refinement. It needs exactly three things, all of them already
inside the stated mechanism:

1. **Dependent function types over value arguments** — `(c : Cursor) -> {r | φ(c, r)}`.
   The binder is what lets the result refinement name the input cursor. This
   is not an extension: liquid types have it, and the refinements themselves
   stay quantifier-free.
2. **Equality over an uninterpreted sort** for backing-array identity, i.e.
   QF-UFLIA rather than bare QF-LIA. Q2 already anticipated this ("normally
   QF-LIA with uninterpreted functions").
3. **Refinement subtyping with contravariant arguments**, so `decode` (which
   guarantees at least two bytes consumed) reaches the driver by subsumption
   without re-checking the body.

The check is doing real work, not passing vacuously: weakening the step's
contract to permit a foreign result window, or to drop the upper bound on the
result position, makes the driver's own `WF_CURSOR` obligation unprovable in
both cases.

**The driver is higher-order at a *fixed* refinement, and that is not an
accident.** A driver quantified over the step's refinement was tested
directly: an *unbounded* quantifier must hold at its weakest instance (`true`),
where the driver's obligation fails; a quantifier *bounded* by the trichotomy
contract collapses back to the fixed contract plus subsumption. So the driver
never needed refinement polymorphism, which is why it types.

#### Three things the driver revealed that the design did not have

**Termination needs the byte source refined too.** The obvious metric —
bytes left in the window — *increases* across `:need-more`, because refilling
grows the window. Termination requires a lexicographic metric whose first
component is a source budget, i.e. the driver's type must carry a *second*
refined capability. With an unrefined source neither component decreases and
the metric obligation fails. (For a live socket the loop genuinely does not
terminate, which is correct; the point is that the type cannot say so unless
the source is in the tier.)

**Retry-soundness is outside the fragment.** Retrying the step after a refill
is only meaningful if the refilled window agrees with the old one below
`position`. That is `forall i. 0 <= i < old.length => new[i] = old[i]` — array
content under a quantifier. It is not derivable from the position-and-length
arithmetic, and it becomes derivable exactly when one instance is assumed.
This is an obligation *outside* the type system, which §1.3 already reserves
for Ansatz; it is recorded so it is not later mistaken for something the
checker discharges.

**`Step` is not closed under retry-wrapping.** The natural combinator
`retrying : Step -> Step`, which hides `:need-more` by refilling, **does not
type**: the contract pins the result window to the *argument's* window, and
refilling replaces it. The retry loop therefore has to be the driver, which
threads the new window explicitly — as the real driver does. This is a design
consequence, not a defect, and it is the shape any session type over the decode
trichotomy must respect.

#### The transactional property is expressible — but only up to structure

`read-window` and `decode` return **the identical original Cursor** on a
non-`:ok` result (`jolt-bytes` gives `Cursor` an explicit `identical?` equality
contract; `jolt-bencode`'s docstring says "the identical original Cursor").

A refinement over field values can state that the returned cursor carries the
same window and the same position. It **cannot** state that it is the same
object: with a ghost identity field added, structural equality provably does
not imply identity. So the strongest available refinement is satisfied by an
implementation that returns a *copy*.

This does not break the property; it relocates it. Under E6's affine
binding a `unique` Cursor is moved into `decode`, so no one is holding the
original to compare against, and field equality is the whole observable
content of "nothing was consumed". **Identity is a mode-tier notion and
structure is a refinement-tier one, and the transactional property needs
both** — the third independent instance (after E5's typestate and E6
probe 3's contention) of an obligation that looked like one tier's job and
required the other.

#### Transducers do not type, and the machinery they need has a name

Charter §1.2 H4 (adopted as register row 2) makes transducers the composable
primitive. Composing refined steps was measured directly:

| shape | verdict |
| --- | --- |
| `mapStep` over an **ordinary-tier** element | types |
| `mapStep` at **one fixed** element refinement | types |
| the same monomorphic `mapStep` **reused at a second** refinement | **fails** |
| `mapStep` with an **arity-1 abstract refinement**, instantiated per use | types |
| **zero-copy** element with an arity-1 refinement variable | **fails** |
| zero-copy element with an **arity-2** refinement variable | types |
| zero-copy element consumed **first-order**, no combinator | types |
| refinement variable left uninstantiated | **fails** |

Reading the table:

**A monomorphic refined transducer loses the refinement.** Declared at one
element refinement and used at another, the declared codomain is not a subtype
of what the caller needs. That is the whole point of a combinator defeated.

**The fix is abstract refinements** — LiquidHaskell's
`forall <p :: a -> Bool>` (Vazou et al., *Abstract Refinement Types*, ESOP
2013). Predicate variables are not type variables: **HM unification has
nothing to solve them with**, and inferring them is Horn-clause constraint
solving over a qualifier set. §1.2's phrase "without giving up inference" is
therefore too strong at the combinator boundary, though the leaves stay
QF-UFLIA and decidable.

**Arity is the sharp part.** A zero-copy element — the decoded value *is* a
sub-`Window` of the input buffer rather than a copy out of it — has a
refinement that mentions **the step function's own cursor binder**. An arity-1
predicate `p :: Window -> Bool` cannot see it. The requirement is
`p :: Cursor -> Window -> Bool`, an abstract refinement *parameterised by a
binder internal to another type in the signature*. That is the most exotic
thing this probe found, and it is not in §1.2 or §1.3 in any form.

#### Does §1.2's confinement claim survive?

**Yes, conditionally — and the condition is one the performance line is
actively eroding.**

The claim is that refined things are mostly consumed by *first-order*
operations. Every first-order case here types, including a refined capability
threaded through an eager `into` accumulator and a zero-copy `Window` handed
straight to a first-order consumer. The driver, the one genuinely higher-order
consumer on the v0 path, types at a fixed refinement. Transducers over
ordinary-tier elements type, because there is no refinement to compose.

The condition is that **the codec copies out of the capability tier**.
`jolt-bencode`'s `decode-utf8` builds a `String`, so every element flowing into
a transducer chain today is ordinary-tier and unrefined — which is exactly why
the confinement claim holds. E7 and E11 push toward the opposite: a
zero-copy decoder returning sub-`Window`s is the natural end of that line, and
it puts refined capabilities into the element stream, where the arity-2
abstract-refinement requirement bites.

So the tier split is not threatened, but its cheapness is contingent. The
honest statement for §1.2 is: *confinement holds while the capability tier
terminates at the driver; a zero-copy element type moves the boundary into the
transducer chain and costs abstract refinements of arity ≥ 2.* That trade
should be made deliberately, at the point the zero-copy decoder is decided,
not discovered afterwards.

#### Method note, sixth instance

One expectation here was refuted by the artifact, in the small: a case
asserting that a fresh-window step and the same-window `Step` are incomparable
was written with the fresh step guaranteeing `position >= 1`, and the solver
showed `Step` really *is* a subtype of it. Making the fresh step do what a
de-framing stage actually does — hand back a cursor at position 0 — produced a
genuinely incomparable pair. Small, but the same shape as E5, E7 and E11: the
claim survived inspection and failed the check.

#### E13's own nonclaims

1. Checking, not inference. No claim that liquid inference would find these
   annotations, and the abstract refinements are instantiated by hand.
2. The solver is incomplete over the integers; "fails" means "not proved",
   and each negative was inspected by hand for that failure mode.
3. One driver shape and one transducer shape. `partition-by`, early
   termination via `reduced`, and stateful transducers whose state is itself a
   capability are untested.
4. Nothing here touches linearity or typestate; those stages are E5 and E6.
   The interaction of abstract refinements with affine binding is untested.
5. The Python model encodes objects field-wise into integers. Descriptor
   identity is deliberately not representable, which is the subject of one of
   the findings rather than an artifact of the encoding.

### E14 — the v0.5.17 branch lane: a controller seam exists, and the baseline pins hash values

**This qualifies §1.4 and §2 row 3, and dates non-goal 13.** Both were written
against `origin/codex/upstream-rebase-v0.5.17-candidate` as if that branch were
the whole of the v0.5.17 lane. It is not: seven unmerged branches carry work
that bears directly on them.

**Evidence label: `assumed`, throughout.** This is a source-and-history survey
of branches in `jolt` plus the adapter in `jolt-sim` that consumes them. Nothing
below was executed — no image built, no gate run, no hash computed, no schedule
replayed. By this document's standing method commitment that is the weak tier:
**this section can put a decision in doubt and cannot settle one.** Where a
branch's *name or commit message* asserts more than its *diff contains*, the
diff is what is recorded, and the gap is named.

#### The lane, as of 2026-08-03

Baseline `f06f77f0` (2026-08-01, "feat(ffi): expose scoped byte-array pointer
loans"). Every merge-base below was computed against it.

| branch | tip | dated | ahead / behind baseline | note |
| --- | --- | --- | --- | --- |
| `codex/v0517-threadsafe-hasheq-cache` | `c26215cf` | 08-02 | 3 / 0 | the only branch carrying the hasheq concurrency gate |
| `codex/v0517-sim-hasheq-replay` | `b9293295` | 08-02 | 7 / 0 | integration tip: controller + varargs + target descriptor + clock + hasheq fix |
| `claude/v0517-sim-controller` | `0f7e86f4` | 08-02 | 1 / 0 | the controller commit itself; contained in six other branches |
| `claude/v0517-target-descriptor` | `15ccca63` | 08-02 | 3 / 0 | ancestor of the integration tip |
| `codex/v0517-sim-clock-linearization` | `71f177a6` | 08-02 | 5 / 0 | ancestor of the integration tip |
| `claude/v0517-executor-admission` | `5b98f249` | 08-01 | 1 / — | **already absorbed**: `concurrency.ss` is byte-identical to the baseline's |
| `codex/v0513-sim-controller-atomic` | `645757c6` | 08-01 | — / 36 | self-described "archive … checkpoint" at v0.5.13; **superseded** |

Three corrections to the branch list I was given. There is no
`origin/codex/v0517-sim-controller`; the controller commit lives on
`claude/v0517-sim-controller` and is `0f7e86f4` on six others.
`claude/v0517-executor-admission` is not pending work — its content is in the
baseline already (cherry-picked, not merged: `--contains` still names only its
own branch). And the branch that matters most was not on the list:
`opencode/v0513-application-core-charter` carries both the charter itself and
`docs/research/APPLICATION-FLOW-RUNTIME-SEAMS-2026-08-01.md`, the companion
artifact non-goal 13 points at when it says seams are "requested".

#### Hashing — what the two branches actually contain

Both hash branches make **one** change to `host/chez/hasheq.ss`, and it is the
same change, committed twice (`7dc6208f` and `3af5622d`, identical content, tips
ten seconds apart). The two module-level weak caches become per-thread cells:

```scheme
-(define symbol-hasheq-cache (make-weak-eq-hashtable))
+(define symbol-hasheq-cache-slot (make-thread-parameter #f))
```

with an ownership check — `(if (and (pair? owned) (eqv? (car owned) thread-id))
…)` — because "Chez thread parameters are inherited, hence the explicit owner
id." No hash *function* changed. `compute-symbol-hasheq` and
`compute-string-hasheq` are untouched.

The reproduction is on `threadsafe-hasheq-cache` only: `f2ce7c52`,
"test(runtime): reproduce concurrent hasheq cache corruption", a
process-isolated 4-thread gate under lowered `collect-trip-bytes` that checks
"every production `jolt-hasheq` result … with its pure `compute-*` function",
classifying hangs and hard faults separately. It is wired as `make
hasheqconcurrency` and deliberately **not** in the CI aggregate ("Keep this
focused until old-control reliability and fixed cross-platform stability justify
registering it"). The integration branch `sim-hasheq-replay` carries the fix
**without** the gate.

**The branch named `sim-hasheq-replay` contains no hash replay.** Its seven
commits are the sim controller, variadic FFI boundaries, the target descriptor,
the monotonic clock, clock linearization, and the hasheq fix. The string
`replay` does not occur in its `host/chez/sim/` overlay or in any test it adds.
So the specific worry — that replay forces hash values to be reproducible across
runs — is **not confirmed by anything on the branch**. The name asserts an
intent the diff does not contain. That is a nonclaim, not a refutation: it says
nothing about what the branch is *for*.

#### But the baseline already pins hash values, which §2 row 3 did not know

Row 3 argues: *"**Hash values are not observable through any specified
interface.** The only law is hash-consistency, `(= a b) ⇒ (= (hash a) (hash
b))`."* Three facts from the baseline contradict that as a description of Jolt:

1. `host/chez/hasheq.ss` opens *"JVM-compatible hash engine for Jolt: Murmur3 +
   hasheq dispatch. Ports Murmur3.java, Util.hasheq/Util.hashCombine,
   Numbers.hasheq, Keyword.hasheq/Symbol.hasheq, APersistentMap.mapHasheq,
   APersistentVector.hasheq, APersistentSet.hasheq."* — 492 lines whose entire
   purpose is reproducing specific 32-bit values, down to a written soundness
   argument for the unsafe fixnum primitives.
2. The CHANGELOG specifies the values as observable and JVM-exact: *"record
   hashes are JVM-exact defrecord hasheq … vector/map/set hashes are
   value-identical to the JVM"*, and separately fixes *"A keyword's `.hashCode`
   is the Java hash, not its hasheq"* — i.e. **two** hash surfaces are specified
   independently, and disagreeing with the JVM on either was a reported bug.
3. The new gate's oracle is not the consistency law. It asserts, per call, that
   the memoized value equals the pure computation for that object — an equation
   on hash *values*.

So on the artifact row 3 was reasoning about, hash values are observable,
specified by reference to an external implementation, and gated. Row 3's
conclusion — *"perturb picks the better hash once, for everything, and no
register row is needed"* — may still be the right call for a fork that carries
no JVM conformance obligation, but the premise it rests on is false of Jolt, and
the argument has to be remade as *perturb chooses not to expose hash values*
rather than *nothing exposes them*. **Left open here.**

#### Does a thread-safe cache make hash identity load-bearing?

Yes, in a place row 3 did not look. Row 3 reasoned about the hash *algorithm*
and about `=`/`hash` as a dynamically-scoped effect. The bug these branches fix
is in neither: it is **memoization of a hash into a mutable table shared across
threads**. Concurrent insertion and adaptive resize of one weak table could
corrupt it, and the failure mode the gate is built to catch is a production
hasheq that disagrees with the pure function — i.e. hash-consistency itself
breaking, at runtime, for reasons having nothing to do with which hash was
chosen.

This corroborates row 3's own residue from the rejected effect-handler design —
*"equality and hash must be carried by the value or its type, fixed at
construction"* — and supplies a second, independent reason for it: a hash that
is *computed once and cached* is a piece of shared mutable state, and every such
cache needs an ownership discipline. Jolt's answer is one weak table per thread,
which trades memory and cross-thread cache misses for the absence of a mutator
race. perturb inherits the question the moment it memoizes a hash anywhere.

Residual, and unmeasured: per-thread caches make the *cost* of `hash` depend on
which thread first saw the object. That is a timing observable only, and I did
not measure it.

#### Iteration order — the one replay implementation deliberately does not use it

Row 3 strengthens iteration order to "deterministic within a build" and calls it
"free to state, load-bearing for the determinism claim". Neither half is
contradicted, but the second is **unconfirmed by the only replay engine in the
ecosystem**, which solves the problem a different way. `jolt-sim`'s
`src/jolt/sim/trace.clj` says of its canonical projection:

> The resulting EDN is a collision-free logical representation within this
> domain and **never relies on a runtime hash** or host-object printer.

and implements that by sorting: `canonical-map` sorts entries by `pr-str` of the
canonical key, `canonical-set` likewise, and `kernel.clj` keeps task ids in a
`sorted-map` and hands the scheduler `(sorted-ids …)`. Task *selection* is
`(nth enabled (mod next-state (count enabled)))` over that sorted vector, so
scheduling — the thing replay must reproduce exactly — is order-stable without
any assumption about hash iteration order at all.

Two consequences, both left open. (a) "Load-bearing for the determinism claim"
is not established: an existing deterministic replay engine pays for canonical
sorting instead, and a design that assumes iteration order can substitute for
sorting should say why it is cheaper than the sort it removes. (b) The
strengthening is still free, and is still worth having for a different reason
than the one row 3 gives — sorting is a per-observation cost, iteration-order
determinism is not.

One residual observable I noticed and did not test: `canonical-map` walks the
map with `reduce-kv` and throws on the *first* unsupported key it meets, so
*which* key an error names is iteration-order-dependent even though the accepted
output is not.

#### The controller seam — non-goal 13's factual claim survives, its posture does not

Non-goal 13 reads:

> **No reliance on a runtime lifecycle/controller seam.** None exists at the
> v0.5.17 baseline (P10: `sim/` overlay REMOVED upstream; the v0.5.13-era
> private future-lifecycle overlay cannot be cited). Runtime seams are
> *requested* from the v0.5.17 runtime lane (companion artifact, §8/§9) — never
> assumed.

The first half is still exactly right: `git ls-tree` on the baseline shows no
`host/chez/sim/` at all. The second half is out of date. The request has been
answered — not merged, but written, tested, and documented. `0f7e86f4`
("feat(sim): add atomic runtime control profile") adds an 867-line
`host/chez/sim/runtime.ss` that describes itself as

> the complete prerelease controller overlay. It owns lifecycle,
> monotonic-clock, typed foreign-call, and raw native-operation interception,
> unified behind ONE atomic install/restore pair

with `jolt.internal.sim/{capabilities, install-controller!, restore-controller!,
controller-errors, clear-controller-errors!, supervisor-mono-nanos}`, a
self-describing capability map at `:abi-version 6` / `:descriptor-version 6`,
strict-LIFO install tokens validated under one mutex, six future lifecycle
events (`:spawn :start :finish :cancel :exit :abort`), a sixteen-operation raw
native registry, and a bounded proof note (`docs/proofs/sim-worker-exit.md`) for
the worker-exit ordering it guarantees. The companion artifact's own request
list names "items 7–9 (sim image, lifecycle hooks, unified controller)"; all
three are on the branch.

Where the request is *not* met, precisely: open request **R6** asks for the
unified controller to carry "charter descriptor fields
(operation-id/resource-id/site-id)". The delivered descriptor carries `:kind
:task :symbol :argument-types :return-type :blocking? :capture-native-error?
:varargs-after :arguments`. No operation-id, resource-id, or site-id. And the
consumer is behind the producer: `jolt-sim/src/jolt/sim/runtime.clj` still
declares *"one exact current controller contract: ABI v5 … descriptor-version 4
FFI interception"* against a runtime now publishing 6 and 6. §6 nonclaim 7
already says the jolt-sim controller ABI work does not survive the fork; it can
now say so with version numbers.

#### Continuations — §1.4's rejection survives on the narrow reading, and its dispatch algebra does not

§1.4 states: *"Effects substitute a validated result or abort; no continuations
at that layer."* The delivered seam splits that sentence in half.

**No continuation capture: upheld, and enforced on purpose.** The FFI
interception path hands the controller a descriptor and a 0-arity `proceed`
thunk guarded by a token that is one-shot, owner-thread-pinned, LIFO-ordered,
and retired in `dynamic-wind`'s after thunk. The overlay's own comment:

> Every routing continuation is an owner-thread, one-shot token at the top of
> this thread's dynamic proceed stack. … Retiring in dynamic-wind's after thunk
> rejects escape and continuation re-entry.

with four separate errors for out-of-extent, wrong-thread, non-LIFO, and
second use. The seams request states the same rule as a requirement: *"**No
continuation** beyond the invocation-scoped native `proceed`"*, and lists "no
continuation capture" among the S5 non-goals. Whatever else is true, nobody in
this ecosystem is building multi-shot control at the runtime seam, and Q3's
`unique` × multi-shot worry is not made more urgent by anything here.

**"Substitute a validated result or abort" as an exhaustive algebra: refuted at
the seam.** The delivered dispatch has a third arm. The controller may run the
real operation, receive its value, and continue — that is what `proceed` is
for. `jolt-sim`'s adapter classifies handler results as
`:legacy/:substitute/:modeled-resource/:proceed`, and its own error text calls
the thunk a continuation:

> The routing controller received an invalid proceed continuation

So the two documents use "continuation" for different things and reach opposite
verdicts about the same object: §1.4 counts resumption as a continuation and
excludes it; the runtime lane counts only *capture* as a continuation and ships
one-shot resumption as ordinary handler vocabulary. **This is a real conflict of
terms with a real design question inside it — a handler that can invoke the
operation it intercepted and post-process the result is strictly more expressive
than substitute-or-abort, and §1.4 does not have a name for it. Named, not
resolved.**

**"Control … stays in the explicit cooperative kernel": corroborated,
independently.** The seam does not schedule. `jolt-sim/src/jolt/sim/
future_schedule.clj` — "the first coarse deterministic scheduler for unchanged
ordinary Jolt future code" — gets its determinism by *blocking a real worker
thread*: "an immutable single-use gate (a promise) is created the moment a
scheduled ordinal's `:spawn` is observed. The `:start` hook for that task blocks
on the gate." That is §1.4's model built by someone else: a cooperative kernel
holding control, an effect layer with no continuations, and ordering enforced by
parking threads. The runtime seam's contribution is that the kernel can now
reach *unchanged ordinary code*, which it previously could not.

**"The `perform` boundary must remain a real call site with durable identity
rather than being inlined at analysis time": corroborated, and priced.**
`jolt-core/jolt/backend_scheme.clj` gains a per-compilation-unit
`set-sim-instrument!` flag; when armed, every `defcfn` call site emits
`(let ((h (jolt-sim-current-ffi-hook))) (if h (jolt-ffi-invoke-sim-hook h
<descriptor built from this site's own types/arity/flags> (lambda ()
<native-body>)) <native-body>))`, and when not armed emits the bare native body
with "no simulator reference at all". So durable operation identity is
affordable — at the cost of a **separate image**. The same binary is not both
interceptable and uninstrumented. That is a datum for D3's cost, and it is the
opposite of the "keep it cheap to add" framing: it is cheap to add and it
bifurcates the build.

One further fact for the scheduler model: the clock controller is invoked
*inside* the domain mutex (`71f177a6`, "Obtain, validate, and publish now share
one domain-wide linearization point"), so under a controlled clock every
`mono-nanos` read across all threads serializes on one lock and is checked
non-decreasing. Virtual time is a global linearization point, not a per-task
value.

#### What `executor-admission` admits, and why it barely touches §1.4

`5b98f249` moves `java.util.concurrent` executor **task admission** under the
same mutex as the shutdown flag, so `execute`/`submit` after `shutdown` throw
`RejectedExecutionException` synchronously instead of appending work a worker
may never run, and `isShutdown`/`isTerminated` stop reading the flag with no
lock at all. Its own comment: *"Admission and shutdown linearize under the queue
mutex."*

Three things follow. It is **already in the baseline** — the branch's
`concurrency.ss` and the candidate's are byte-identical — so it changes nothing
about what §1.4 was written against. It is a JVM-fidelity fix, not a scheduler
design: the model it adds is a three-state executor lifecycle
(running → shutdown → terminated) with a synchronous rejection contract, which
is the kind of boundary a cooperative kernel has to model but not one it gets to
control. And the seams register is explicit that executors stay *outside* the
controllable surface — S5 records "raw executor tasks unowned", so the
lifecycle seam sees `future` and does not see executor tasks. A perturb
scheduler that assumes it can see every task would be assuming something the
runtime it is forking does not provide.

#### Verdict table

| claim | where | verdict |
| --- | --- | --- |
| `=` is total, `==` is IEEE | §2 row 3 | **untouched** — no branch bears on it |
| capability equality is identity, derived from the mode | §2 row 3 | **untouched** |
| hash values are not observable through any specified interface | §2 row 3 | **refuted as a statement about Jolt.** The perturb decision it supports is still available, and must be re-argued as a choice not to expose |
| the only law is `(= a b) ⇒ (= (hash a) (hash b))` | §2 row 3 | **refuted for the baseline** — the runtime's law is JVM value-identity, and the new gate asserts a per-object value equation |
| the hash algorithm is not a divergence at all; no register row needed | §2 row 3 | **doubtful.** Turns entirely on whether the `clojure.*` layer inherits Jolt's JVM-exactness obligation — a decision, not a finding |
| replay forces reproducible hash values | *the worry itself* | **not confirmed.** `sim-hasheq-replay` contains no hash replay; jolt-sim's replay explicitly does not rely on a runtime hash |
| iteration order deterministic within a build — free to state | §2 row 3 | **survives** |
| …and load-bearing for the determinism claim | §2 row 3 | **unconfirmed** — the existing replay engine buys the same property by canonical sorting |
| equality/hash cannot be a dynamically-scoped effect; hash must be fixed at construction | §2 row 3 | **corroborated**, with a second reason (shared mutable memoization) |
| none exists at the v0.5.17 baseline | non-goal 13 | **survives literally** — no `host/chez/sim/` on the candidate |
| runtime seams are requested, never assumed | non-goal 13, leaned on by §1.4 | **stale.** Answered on seven branches: sim image, lifecycle hooks, unified controller, ABI 6 |
| no continuations at that layer | §1.4 | **survives on the narrow reading** (no capture, one-shot, non-re-entrant) — and the runtime enforces it deliberately |
| effects substitute a validated result or abort | §1.4 | **refuted as exhaustive** at the runtime seam: substitute / abort / **proceed-once** |
| control stays in the explicit cooperative kernel | §1.4 | **corroborated** by an independent implementation |
| the `perform` boundary must remain a real call site with durable identity | §1.4 | **corroborated, and priced**: a compile-time profile flag and a second image |
| D3 (delimited control) deferred, not foreclosed | §1.4 | **survives as to the lane** — no branch implements delimited control. §7 has since reopened D3 on other grounds; see the note below |
| the jolt-sim controller ABI work does not survive the fork | §6 nonclaim 7 | **survives**, now with versions: producer ABI 6 / descriptor v6, consumer pinned to ABI 5 / descriptor v4 |

#### Note — §7 reopened D3 while this section was being written

§7 reopens D3 on the ground that §1.4's resolution leaned on jolt-sim's
constraints rather than perturb's, and cites this section's reconnaissance in
passing. The two arrived independently and do not conflict, but they push in
opposite directions and the difference is worth stating.

§7's argument is that perturb owns its compiler, its scheduler, and Chez's
multi-shot `call/cc`, so nothing external settles the question. This section's
evidence is narrower and cuts the other way on one point: the runtime lane, with
the same host and the same `call/cc` available, chose one-shot resumption and
then spent code forbidding everything above it — four distinct errors for
out-of-extent, wrong-thread, non-LIFO and second use, plus a `dynamic-wind`
retirement whose stated purpose is to reject "continuation re-entry". That is
not evidence that multi-shot is wrong for perturb. It is evidence that the
nearest system to perturb, holding the same capability, priced re-entry above
what it wanted to pay at a *native* boundary — and the reason given is
resource-safety, not search: "Consuming the token precedes the native call, so
an exception cannot make the same OS effect eligible to run twice." Whether that
reason survives at an effect layer that never touches the OS is exactly the
question D3 now has to answer, and this section does not answer it.

#### E14's own nonclaims

1. **Nothing here was executed.** No image was built, no gate run, no hash
   computed, no schedule replayed. Every row above is source inspection plus git
   metadata: `assumed`.
2. No claim about **intent**. Branch names and commit messages are recorded as
   claims about diffs, not as statements of plan. `sim-hasheq-replay` may well be
   heading somewhere its diff does not yet go, and `threadsafe-hasheq-cache`'s
   gate is explicitly staged out of CI.
3. No claim that any of this **lands**. Seven branches carry the overlay; none is
   merged into the v0.5.17 candidate, and the candidate is not behind any of
   them. A design record that treats the seam as present is making a bet.
4. No claim about the **correctness** of the hasheq fix, the clock
   linearization, or the worker-exit proof. The proof document states its own
   bound — it is a sufficiency claim for one forked worker, abstracting values,
   exceptions, identity and time, and it explicitly does *not* prove that
   `restore-controller!` waits for quiescence; that is the external supervisor's
   job.
5. I did not determine whether **hash values cross the controller ABI**. The
   descriptor projection validates key *sets* and an alist key *order* internal
   to the runtime; I found no path by which a hash value is compared across
   runs, and I did not exhaustively search for one.
6. `perturb` is a fork with no JVM conformance obligation. Every refutation above
   is a refutation of a claim **about Jolt** that a perturb decision was resting
   on. None of them decides the perturb question, and this section deliberately
   decides nothing.

---

### E15 — perturb rejects a program; and the accept set does not run

Until this point every capability claim in §1.2 was carried by a Python
prototype over a hand-written model. `perturb.check` is the first thing in this
record that reads **real Jolt IR from real perturb source and refuses**. It is a
gate: `jolt -M:check`, wired into `dev/run-demo.sh`. Independently re-run at
`d883385`; the whole `run-demo.sh` sequence — selftest, check, oracle, live
demo against a `jolt nrepl-server`, and the no-I/O verifier — exits 0.

#### How it gets the IR, and what that cost

`jolt.analyzer/analyze` takes a `chez-actx` record that no Jolt-level code can
construct, and `compile-eval.ss:12-20` `var-deref`s both `analyze` and
`run-passes` at host load, so neither var can be rebound from Jolt. The one var
`run-passes` still calls *through its cell* is `jolt.passes.numeric/annotate`;
`perturb.ir` `alter-var-root`s that one and captures 97 defs on the way past.
**Nothing in `/home/user/jolt` was modified** to make this work. The fragility
is priced as INHERITED I18: perturb is reading its own compiler through the one
seam Jolt happens to leave open, and Jolt owes it nothing.

#### The first rejection, verbatim

```
  use-after-move  perturb.nrepl/Connection
    capability    `c` : perturb.nrepl/Connection@:active, bound at perturb/src/perturb/corpus.clj:90:11
    consumed by   perturb.nrepl/close!  at perturb/src/perturb/corpus.clj:91:5
    used again at perturb/src/perturb/corpus.clj:92:5  (argument to perturb.nrepl/request)
    in            perturb.corpus/use-after-close
```

That program is INHERITED I16's example verbatim — the one recorded as
"compiles and runs on Jolt today". It no longer checks.

The corpus is 17 real perturb functions, never called, each with the verdict the
gate requires: 6 accept, 11 reject (`use-after-move` ×2, `typestate`,
`dangling` ×2, `join` ×2, `loop-not-preserving`, `untracked-consume`,
`escape`/`produces-mismatch`, `capture`). Flipping one expectation gives
`16/17 … CHECK FAILED`, exit 1, so the gate is known to be able to fail.

#### §4.6's `:local` item — closed, and the pessimism was warranted

§1.1 claimed from source reading that `:local` carries a name and not binding
identity; §4.6 recorded it as UNTESTED and said to assume it might be wrong.
The checker measured it on `perturb.corpus/shadowed-rebind`, which binds three
different `Connection` instances to one name:

```
     :let binding names     ["c" "c" "c"]   <- three separate bindings
     :local nodes naming c  2, every one of them exactly {:op :local, :name "c"}
     a :binding-id key?     false
```

**The claim holds.** The analyzer's lexical env is a *set* of names
(`analyzer.clj:84-86`), so a shadowing binding reuses the name outright. The
checker therefore mints its own binding id at each binding occurrence;
`perturb.corpus/shadowing-hides-a-leak` is the program a name-keyed checker
accepts and this one rejects, and it is in the corpus so the property is
regression-tested rather than asserted. `:extern` is still untested.

#### The real client is rejected — and that is the finding

`perturb.nrepl`, the working nREPL client from `0e36f37`, does **not** survive
its own rules: `clone-session`, `eval-code` and `session` draw 5 diagnostics at
`nrepl.clj:168, 179, 198, 199, 204`. One root cause. §1.2's `:consumes` /
`:produces` are **unpositioned**, so a function returning `[conn value]` — which
is how this client threads the connection through every operation — cannot be
annotated at all.

#### Correction to that finding, from running the corpus

The delegated report framed this as an expressiveness gap: the annotation
language is too weak to describe the real client, and `perturb.corpus/ping`
(returns the connection bare, **accepted**) versus `/ping-tuple` (same function
with the pair put back, identically annotated, **rejected**) isolates it. The
isolation is right. The framing is too kind, and I checked it by running the
accept set rather than reading it.

`perturb.nrepl/request` really returns `[conn' frames]`. Its unpositioned
`:produces` cannot say so, so the checker models the *whole result* of a call as
the successor capability. Every corpus ACCEPT is written to fit that model —
`(let [c1 (n/request c …)] (n/close! c1))` — and at runtime `close!` therefore
receives the pair. Run under the scripted handler, with no server and no socket:

```
open-request-close -> THREW: Exception in fx=?: #[keyword-v1 "perturb.cap" "state"] is not a fixnum
                             at perturb/src/perturb/corpus.clj:21
uses-ping          -> THREW: (the same)
```

So:

- **`ping` and `ping-tuple` are the same function.** Both return a pair. The
  checker accepts one and rejects the other because one builds the pair with a
  vector node in *this* body and the other inherits it from the callee. The
  distinction the checker draws is **syntactic**.
- The unpositioned annotation is not only a **false reject** on the real client.
  It is a **false accept** on the corpus: the six accepted programs are accepted
  under a model of `request` that contradicts `request`'s actual return shape,
  and they crash.
- `perturb.corpus`'s docstring said every entry "would run". For the accept set
  that was false. Corrected in the artifact, and printed by the gate as blind
  spot 8.

This does not make the checker worthless — all eleven rejections are rejections
of programs that are genuinely wrong, and the machinery under them (binding ids,
join, loop invariance, typestate, affinity) is doing real work. It relocates the
priority: **positioned capability specs are the first thing §1.2 needs**, ahead
of the abstract refinements E13 asked for, because they close a soundness hole
and not merely an expressiveness one.

#### E6's join rule, one data point

E6 probe 1's rejection of conditional-move code was recorded in §4.6 as a
usability risk whose frequency was "argued rather than measured". It fires
exactly where predicted (`perturb.corpus/conditional-close`) and **zero times
outside the corpus**: `perturb.nrepl` has one `if`, inside a `loop`. On this one
client the join rule is not the friction; the missing product rule is. That is
one program, not a measurement of how often.

#### What the checker cannot see — printed by the gate itself

1. A declared transition operation is an **axiom**. `open`, `request` and
   `close!` have unchecked bodies; nothing verifies that `close!` closes. Same
   posture as `mode_checker.py`'s `RULES` — the ported hole, still a hole.
2. `:consumes` / `:produces` unpositioned (above).
3. Closure bodies are walked for diagnostics but their state does not
   propagate; capture is rejected, not reasoned about.
4. No exception-path join for `try`.
5. Only `let` / `loop` carry capabilities. One in an atom, var, map or vector is
   rejected, never tracked.
6. Interprocedural flow is by **annotation only**. No inference.
7. Post-const-fold IR, and only for namespaces required after the tap installs.
8. The false-accept above.

#### Two judgements that are not ports

Stated because the standing commitment is that a rule set is only as good as
what it was differentially tested against, and these two were not:

- The Python prototypes have no non-local exit, so they never said what
  `recur` / `throw` do at a join. This checker treats those paths as unreachable
  (bottom), making join-with-bottom the identity. Without it, every ordinary
  `loop` fails the join rule at its own back edge. Sound and standard — but it
  is not `controlflow.py`'s, and `ownership.pl` never saw it.
- For a *derived* annotated operation the checker matches capability specs to
  parameters **in order**. That convention is the checker's, not §1.2's.

#### E15's own nonclaims

1. **No soundness claim.** Seventeen programs decided as recorded is a
   regression corpus, not a proof, and blind spots 1–8 are each a way a wrong
   program can pass.
2. Nothing here validates the **rules**; it validates that the rules E5/E6
   settled can be run against real IR. The differential validation against
   `ownership.pl` remains the only evidence the rules are right, and it did not
   cover bottom-at-join.
3. The corpus is written by the same author as the checker, against the same
   reading of §1.2. Its accept set was wrong for exactly that reason, and it
   took *running* it to find out.

---

### E16 — the load-time I/O leak: closed, and I11's premise was wrong

INHERITED I11 recorded that `perturb.posix` performs I/O at namespace load — a
`jolt.ffi/load-library` outside any handler — and that CLAIM 2 ("all I/O goes
through a declared effect") was therefore false at the edges. It is now closed,
at `88c8d1a`, and the closing found the premise misstated.

#### `defcfn` does not resolve at def-time

I11 assumed `defcfn` binds its foreign symbol when the `def` is evaluated. It
does not. `defcfn` expands to `(def name (jolt.ffi/__cfn …))`, and
`emit-ffi-fn` (`jolt-core/jolt/backend_scheme.clj:589-617`) lowers that to

```scheme
(let ((p #f))
  (lambda args ((or p (begin (set! p (foreign-procedure …)) p)) ...)))
```

— deferred to first call, memoised, one cell per binding. So the FFI
*declarations* were never the leak; only the explicit `load-library` call was.
Logged as INHERITED I17. SHAREABLE S6 is amended accordingly: the
`foreign-procedure` **emission** is semantics-neutral and shareable with Jolt,
the **binding time** is not — when a foreign symbol is bound decides whether a
`def` has an effect.

`ensure-native!` is now reached only from `perturb.posix/handler`, itself
reached only from `perturb.effect/perform`. No `:load-library` op was added to
`perturb.wire/socket`: a scripted handler has no library, so an op only one
handler can implement is not part of the interface.

#### The verification had to be built, because strace could not see it

A no-argument `jolt.ffi/load-library` is `dlopen(NULL)` — **no syscall at all**.
An strace-only verifier would have shown a clean window before the fix and after
it, and proved nothing. Coverage therefore comes from three instruments, all run
by `dev/verify-noio.sh` (`-M:noio`):

| instrument | reads |
| --- | --- |
| strace over a marked window | 6 syscalls, all `clock_gettime(CLOCK_PROCESS_CPUTIME_ID)` from Chez's collector; **0 attributable to perturb** |
| instrumented `load-library` counter | `{:library-loads 0, :calls 0, :by-op {}}` after a complete scripted session |
| absent-symbol canary | `(ffi/defcfn c-absent-canary "perturb_absent_symbol_canary_do_not_define" [] :int)` defines without error — laziness demonstrated, not argued |

Positive control (`-M:noio --touch-native`) shows `socket` / `connect` / `close`
in the same window, so the clean window is a measurement rather than a silent
instrument. Also found, and deliberately not relied on: libc symbols resolve on
this host without `load-library` at all, because Chez's foreign-entry table sees
process symbols. perturb keeps the call.

#### Leak 2 is left open, and is now exactly measured

The console output of a scripted run is **3 `write(2)` calls** in the marked
window. Left open on the stated ground that *an effect does not remove I/O, it
makes I/O substitutable*: nothing consumes perturb's console output, so a
console handler would have no second implementation to be checked against.
Recorded as INHERITED I12's exact size rather than as a fixed defect.

#### What did not close

**Namespace loading is still not an effect.** I11 is closed by *arrangement* —
the one load-time call was moved behind the handler — not by a design that makes
load-time effects impossible. Anything a future namespace does at load time is
outside the effect discipline again, and nothing in the artifact prevents it.

#### E16's own nonclaims

1. The window is one scripted session on one host. `assumed` beyond it.
2. "0 attributable syscalls" is attribution by instrument, not by proof: the
   counter counts the five syscall bindings perturb declares, and a sixth path
   would be invisible to it.
3. This closes a claim about **perturb's own artifact**. It says nothing about
   whether Jolt's namespace loading should be effect-mediated, which is Jolt's
   decision and not perturb's.

---

### E17 — positioned capability specs; and what became checkable then broke

E15 left one item at the top of §1.2's queue: `:consumes` / `:produces` name a
capability and a state but not **where in the value** it sits, which is both a
false reject on the real client and a false accept on the corpus. That is now
done, and doing it moved the frontier somewhere the design did not anticipate.

#### The change

Two keys on a capability spec entry:

| key | on | means |
| --- | --- | --- |
| `:arg n` | `:consumes`, `:borrows` | the capability is parameter `n`, and nowhere else |
| `:at [i]` | `:produces` | the capability is at position `i` of the returned tuple; absent means the result **is** the capability |

`perturb.nrepl/request` now says what it does:

```clojure
{:consumes [{:cap 'perturb.nrepl/Connection :state :active :arg 0}]
 :produces [{:cap 'perturb.nrepl/Connection :state :active :at [0]}]}
```

The checker's abstract domain gains exactly one composite — a **tuple** — because
that is the shape a path can name. `first`, `second`, and `nth` with a constant
index are its eliminators: they move nothing and consume nothing, and they are
how a capability gets back out of a positioned `:produces`. A capability entering
a map or a set is still an escape, because no annotation can say where it went.

#### What that bought

- **The real client checks.** `perturb.nrepl/clone-session`, `/eval-code` and
  `/session` — the three E15 recorded as unannotatable — now carry positioned
  signatures and are accepted. Nothing about their bodies changed.
- **The accept set runs.** Every accepted corpus program is now **executed**
  under the scripted handler as part of the gate, and 6/6 complete. E15's
  regression class is now caught by construction rather than by someone thinking
  to try it.
- **`ping` / `ping-tuple` collapsed.** E15 showed the checker split them on
  syntax. With `:at [0]` both are accepted, and `wrong-position` — the same
  function declaring position 0 and returning position 1 — is the rejection.
- **Two rules the old shape could not state.** `drops-the-connection` binds the
  pair and uses only position 1, leaking the connection at position 0 with no
  mention of a connection anywhere after the request. `loop-shape-drift` is
  `loop-of-requests` without the `first`: same capability, same state, different
  **shape** at the back edge. Neither was expressible before.

Corpus: 22 programs, 8 accept / 14 reject, all as recorded. Both gate stages are
demonstrated able to fail independently — flipping one verdict gives
`21/22 … CHECK FAILED`, and an accepted program made to throw gives
`22/22 decided as recorded` with `5/6 accepted programs ran to completion`,
exit 1. The second is the E15 regression exactly, and the gate catches it now.

#### One checker bug the client found

The first positioned run rejected `clone-session` for `use-after-move`: `(first
r)` moved position 0, and the very next line's `(second r)` was read as using
position 0 again. **Naming a value is not using every capability inside it.** A
consumed position now becomes a `:dead` leaf and the diagnostic is raised where a
dead leaf is actually used — consumed, passed to an unannotated callee, or
returned. Without positions this bug could not exist, and without the real
client it would not have been found.

#### And then the layer underneath started failing

This is the part that was not designed. With the protocol layer checkable, the
**implementation** layer under it began drawing diagnostics: `state`, `conn-id`,
`conn`, `compact` and `read-frame` all reach into the connection's concrete map,
and `(:perturb.nrepl/buf c)` is not a capability operation and cannot be given a
signature. A checker that checked those bodies could only refuse them.

They are axioms for the same reason a transition's body is an axiom — they work
below the level the modes describe — but that is a **second, distinct class**,
and §1.2 had no name for it. The artifact now carries one:

```clojure
:perturb.cap/representation
['perturb.nrepl/conn 'perturb.nrepl/compact 'perturb.nrepl/read-frame
 'perturb.nrepl/state 'perturb.nrepl/conn-id]
```

**Listing operations by name is a placeholder.** What this wants to be is a
module boundary — "inside the Connection's implementation" is a scope, not a
list — and §1.2 has no module concept. That is now the top of its queue, in the
place positioned specs just vacated.

A related rule fell out and is correct on its own: a **borrowed** parameter is
not the callee's to close, so it is exempt from the scope-exit leak rule. `state`
and `conn-id` are the artifact's first `:borrows`, and they were found by the
checker refusing them.

#### The number that matters is not the one that looks good

`perturb.nrepl` reports **0 rejected** — of **7 of 15** functions. The other 8
are axioms: 3 transitions and 5 representation operations, believed and not
checked. The gate prints it that way on purpose. The `read-frame` driver, E4's
whole sans-io contract, is inside that unchecked set.

#### E17's own nonclaims

1. **Paths are one level deep and tuples only.** A capability nested two deep, or
   in a map, is not tracked.
2. **Only three eliminators.** Destructuring, `peek`, `last`, or a computed index
   silently lose a capability to opaque. That is the most likely place a false
   accept hides today, and it is silent rather than diagnosed.
3. **The axiom set grew, and growing it is how the client was made to pass.**
   Every operation moved into `:representation` is a body that stopped being
   checked. That is defensible per operation and dangerous as a habit; the honest
   reading of "the real client checks" is "the 7 functions above the abstraction
   boundary check".
4. Unpositioned entries still fall back to matching specs to parameters in
   order — the checker's convention, not §1.2's, and still unremoved.

### E18 — a second protocol: what two capabilities, a cycle and an obligation do to the rules

E17 left a module boundary at the top of §1.2's queue and a rule set validated
against exactly one capability shape: `perturb.nrepl`'s Connection, one
capability per program, a typestate machine that is a straight line. This is the
same rule set met by a protocol whose shape differs on purpose.

`perturb.http` is HTTP/1.1, server side, sans-io, over the same
`perturb.wire/socket` effect, with **three** capabilities — `Listener`,
`ServerConn`, `ResponseBody` — a keep-alive **cycle** in `ServerConn`'s machine,
and a body writer whose terminal condition is an **obligation**. It runs under
both handlers exactly as `perturb.nrepl` does: `perturb.script/server-session`
delivers one octet per `recv`, and `perturb.posix` now answers `:listen` and
`:accept` on a real loopback socket. `perturb.httpcorpus` is 25 programs,
8 accept / 17 reject, all decided as recorded, every accept executed.

Gate as of this section: `-M:selftest`, `-M:check` (47 corpus programs across two
corpora, 11 accepts executed), `-M:oracle`, `-M:demo`, the new `-M:http`, and
`-M:noio`, all exiting 0.

#### Finding 1 — two capabilities at once: the flow rules hold, the DECLARATION language does not

Everything positional survived contact, on the first attempt and without a line
of `check.clj` changing:

- `accept` consumes a Listener at `:arg 0` and produces a Listener at `:at [0]`
  **and a ServerConn at `:at [1]`**. `produced-value` builds the two-capability
  tuple; `first`/`second` project each back out; `check-scope-exit` requires both
  to be disposed of. `accept-drops-the-listener` and `accept-drops-the-connection`
  are the two halves of that, and each is rejected for the one it drops.
- a derived operation whose **parameters** mention two different capabilities at
  `:arg 0` and `:arg 1`, and whose `:produces` names both at `[0]` and `[1]`,
  checks against its body (`serve-with-listener-held`). Returning them the other
  way round is `produces-mismatch` (`swapped-two-cap-produces`).
- a `loop` with **two capability bindings** preserves shape at each position
  independently (`loop-holding-both`); exchanging them at the back edge is
  rejected at both positions (`loop-holding-both-swapped`).
- `body-finish!` **consumes two capabilities of two different machines in one
  call**, at `:arg 0` and `:arg 1`. Swapping the arguments at a call site is
  caught (`finish-with-swapped-arguments`).

So `multicap.py`'s shape is not a problem for the flow analysis. Three things
broke, all of them in the *declaration* language, and all of them silently.

**(a) The primitive table is keyed by operation, so an operation belongs to at
most one capability.** `spec` builds `opsym -> {:cap :from :to}` by reducing over
every declaration's `:transitions`; a second declaration naming the same
operation **overwrites the first**. `accept` is a transition of `Listener`
(`:listening -> :listening`) and of `ServerConn` (`nil -> :reading`), and 10
declared transition entries collapse to 9 primitives. `check-annotation-
consistency!` then compares the annotation's *first* `:consumes`/`:produces`
entry against whichever transition won:

```
  --- perturb.http/accept
  annotation-inconsistent  perturb.http/ServerConn
    declared machine:  -> :reading
    operation annotation: :listening -> :listening
    the two data sources cap/checker-input emits disagree

  --- perturb.http/body-finish!
  annotation-inconsistent  perturb.http/ResponseBody
    declared machine: :open -> :finished
    operation annotation: :open -> :reading
    the two data sources cap/checker-input emits disagree
```

Both diagnostics are correct about the data and wrong about the program. There
is no way to write these annotations that removes them, because the declaration
language cannot say *"this operation is the `:open -> :finished` edge of
ResponseBody and the `:writing -> :reading` edge of ServerConn"*. §1.2's
typestate axis is **per capability**, and an operation that advances two machines
at once is outside it. This is not exotic: `accept` (mint a connection from a
listener) and `finish` (end a body, give the connection back) are the two most
ordinary operations a server has.

**(b) A second inconsistency was already live in `perturb.nrepl` and had never
been seen.** `perturb.nrepl/open` declares `:from :created` and annotates
`:consumes []`, i.e. `nil -> :active`; that raises `annotation-inconsistent` too.
It was invisible because `run-client` computed its bad set over the non-axioms
only, so a diagnostic raised *against an axiom* was collected and never printed.
The report stage now prints them. Nothing about the checker's verdicts changed —
this was a reporting hole, and it hid a real disagreement for two sections.

**Correction — this is not defect (a).** This section as first written called it
"the same defect". It has a different cause and a different fix. `Connection` is
the only capability declared in `perturb.nrepl`, so no transition table entry was
overwritten; the disagreement is that `open` **consumes nothing**, so the
consistency check derives `from = nil` and compares it against a declared
`:from :created`. What that exposes is that **`:created` is a fiction** — nothing
is ever in that state, because `open` is what brings the connection into
existence. Either a machine should have no pre-creation state, or a
producing-only operation needs its own consistency rule. (a) and (b) share only
the *suppression* that hid them both; fixing (a)'s `[cap op]` keying would leave
(b) exactly where it is.

**(c) `:borrows` plus `:produces` of the same capability duplicates it, and
nothing says so.** `borrow-and-return-listener` borrows a Listener at `:arg 0`
and also declares it at `:at [0]` — the natural way to write "I looked after
your listener, here it is back". Both halves are individually legal: E17's rule
exempts a borrowed parameter from the leak check, and the body really does yield
a Listener at position 0. The checker therefore holds **two** abstract
capabilities for **one** runtime listener, and the cost lands on the caller:
`uses-borrow-and-return` is `perturb.corpus`'s accepted composition shape with
one word changed, and it is rejected for leaking a listener the programmer
disposed of correctly. The diagnostic is sound given the annotation; the
annotation is the thing that is wrong, and there is no rule that can point at it.

**(d) The unpositioned fallback degrades from unprincipled to actively
misleading.** With one capability, matching specs to parameters in order is a
convention (E17 nonclaim 4). With two, `unpositioned-two-cap-helper` — parameters
`[c l]`, specs `[Listener ServerConn]` — binds `c` to the Listener spec and `l`
to the ServerConn spec and produces **five** diagnostics, of which the clearest
is:

```
  dangling  perturb.http/Listener
    capability    `c` : perturb.http/Listener@:listening
```

None of the five names the annotation. The fallback should be removed, not
documented.

#### Finding 2 — the cycle is fine; the shape the cycle wants to be written in is not

`ServerConn`'s machine is `:reading -> :responding -> :reading`, with
`respond!` as the back edge — the first operation in perturb to return a
capability to a state it has left. **The loop rule accepts it unchanged.** The
reason is structural and worth stating: `PRESERVE` compares the
`(path, capability, state)` shape at the back edge against the shape at loop
entry, and a *cycle* in the typestate machine is precisely the condition under
which a loop body can restore that shape. Nothing had to be added for cycles;
the rule was already the right one.

The negatives all fire:

| program | what it does | verdict |
| --- | --- | --- |
| `keepalive-recurs-mid-cycle` | recurs at `:responding`, a response still owed | `loop-not-preserving` |
| `keepalive-drops-the-connection` | exit arm returns without closing | `dangling` |
| `accept-loop-leaks-connections` | listener invariant holds, a ServerConn leaks per pass | `dangling` |
| `accept-loop-shuts-down-inside` | back edge re-enters at `Listener@:closed` | `loop-not-preserving` |
| `respond-without-reading` / `read-twice-without-responding` | one-response-per-request | `typestate` |

**But the driver has to be written in a specific shape to be accepted, and it is
not the obvious one.** An HTTP keep-alive loop decides *after* responding whether
the peer wants the connection kept. Written the way that reads best —

```clojure
c3 (if (keep-alive? req) c2 (close-conn! c2))
```

— it is E6 probe 1's `if (c) { b = detach_result(b) }; use(b)`, and it is
rejected:

```
  join  perturb.http/ServerConn
    capability    `c2` : perturb.http/ServerConn@:reading
    at the if at  perturb/src/perturb/httpcorpus.clj:419:28
    consumed in the else arm and not in the other
    "may or may not have been moved" is not a mode; no sound join exists
```

with three further diagnostics cascading from it. §4.6 records the frequency of
this rule as "argued rather than measured", with one data point: **zero**
occurrences in `perturb.nrepl` (E15). The second data point is that it fires on
the *first driver anyone would write* for the second protocol. The accepted
rewrite exists — put the close inside the branch and the `recur` in the other, so
one arm is bottom and the join is vacuous, which is what
`perturb.http/serve-connection` and `perturb.httpcorpus/keep-alive-loop` do — but
it is a non-obvious idiom that must be known in advance. The usability risk is no
longer hypothetical, and the sample is now 1 of 2 protocols rather than 0 of 1.

#### Finding 3 — the obligation: §1.2 can require termination and cannot require correctness

A Content-Length response body is a capability whose terminal condition is an
**obligation**: it owes the wire exactly N octets, where N was committed to in a
header already sent. `ResponseBody`'s machine is `:open -> :finished`.

What the typestate axis **can** say: `:finished` is terminal and `:open` is not,
so a body that is written to and abandoned leaks at scope exit
(`body-never-finished`, `dangling`), and a write after `body-finish!` is a use
after move (`write-after-finish`).

What it **cannot** say, and this is the finding: `perturb.httpcorpus/short-body-
still-type-checks` is `stream-a-body` with one `body-write` deleted. It declares
`Content-Length: 6` and writes 3. **`perturb.check` accepts it and the gate runs
it to completion**, because the capability reached `:finished`, which is all a
state can be asked for. `body-finish!` deliberately records the discrepancy in
the ledger instead of aborting — an abort would convert a static gap into a
dynamic check and hide it — and `-M:http` prints what went on the wire:

```
    HTTP/1.1 200 OK..content-length: 6..content-type: text/plain....abc
    ledger: perturb-body-7  declared Content-Length 6, wrote 3 -> VIOLATED
```

**What is missing, precisely.** Not a new axis. `wrote exactly N` is a
refinement over a run-time integer — `(= 0 (:remaining b))` at the `:finished`
transition — in QF-LIA, which is exactly the fragment §1.3 already reserves for
refinements and Q2 says liquid types can infer. The typestate axis is about
*which operations are legal from which state*; this is about *a numeric
invariant that must hold at a particular transition*. Both halves are
load-bearing, which is E6 probe 3's result arriving again from the other
direction. Concretely, §1.2 would need a state to be able to carry a refinement
(`ResponseBody@:finished` where `remaining = 0`), and no capability in perturb
carries one today.

A second obligation on the same capability is *worse* and is recorded rather
than solved: `body-finished-before-conn-reused` relates **two** machines in
time — the ServerConn may not go `:writing -> :reading` unless the ResponseBody
reached `:finished` first. The typestate axis is per capability and §1.2 has no
way to relate two of them. It is written on `body-capability` with
`:class :temporal` and nothing discharges it. Note that `close-conn!` is declared
legal from `:writing`, so a connection can be closed with a response half
written and no rule objects.

#### Finding 4 — the abstraction boundary: the list went to zero and the boundary did not move

E17 added `:perturb.cap/representation` — five operations of `perturb.nrepl` —
and put a module boundary at the top of §1.2's queue. The obvious test for a
second protocol is how long its list is.

**It is empty, for all three capabilities, and that is not progress.** Every
concrete-map access was written *inside* a declared transition, whose body is
already an axiom, instead of in a helper the list would have had to name. The
measurements:

| | `perturb.nrepl` (E17) | `perturb.http` (this section) |
| --- | --- | --- |
| declared transitions | 3 | 9 (10 entries, see finding 1a) |
| `:representation` operations | 5 | **0** |
| axioms / functions | 8 of 15 | 9 of 49 |
| concrete-map accesses, all unchecked | 12 | **31** |
| unchecked code lines / total | 72 / 143 = **50.3%** | 136 / 456 = **29.8%** |

The unchecked surface **grew** — 31 accesses against 12 — while the list went to
zero, and the whole of the improvement in the percentage is a large *pure*
parser (`parse-request` and its helpers, ~150 checked lines that take no
capability) diluting the denominator. `perturb.nrepl`'s driver `read-frame` is
18 unchecked lines; `perturb.http`'s `read-request` is 26 of the same, plus the
compaction that used to be a separate named `compact`.

Two things that cost, both structural:

- **Inlining made the boundary cheaper to declare and harder to move.**
  `compact` and `read-frame` were at least *named things* a future module system
  could have been asked to give signatures to. Folding them into a transition
  removes the names.
- **There are no `:borrows` observers in `perturb.http` at all.**
  `perturb.nrepl/state` and `/conn-id` exist because callers want to look at a
  connection without taking it, and they are two of the five list entries.
  Offering the same thing here means re-opening the list, so `perturb.httpdemo`
  reports from the *ledger* instead of from the capability — a weaker thing,
  and a distortion of the code caused by the annotation language.

**Conclusion, which is a sharper form of E17's:** counting operations counts the
wrong thing. The list is a proxy for a boundary and it can be driven to zero
without moving the boundary at all, by writing bigger functions. What §1.2 needs
is still a **module** — a scope inside which the concrete representation is
visible and outside which only the capability is — and the number that would
measure it is *lines below the boundary*, not *names in a list*. It stays at the
top of §1.2's queue, and this section is the argument that its absence is not
merely inelegant: it makes the one available metric gameable, and this artifact
gamed it without trying to.

#### Changes to `check.clj`, in full

Three, none of them a rule. Recorded because §1.7's posture is that a checker
change must be visible:

1. `run-corpus` takes the namespace, expectations var and banner instead of
   hard-coding `perturb.corpus`. Two corpora.
2. `run-accepts` takes the expectations var, and honours a `:handler` key naming
   a 0-arg var that builds the scripted handler. An HTTP server cannot be
   executed against a scripted nREPL peer.
3. `run-client` becomes `run-implementation`, parameterised by namespace, and
   **prints diagnostics raised against axioms**, which the previous version
   collected and discarded. This is what surfaced finding 1(b).

No judgement, no diagnostic kind, and no acceptance condition was altered. Every
`perturb.corpus` verdict is unchanged.

#### E18's own nonclaims

1. **Not a working HTTP server.** No chunked coding (refused explicitly rather
   than mis-parsed), no HTTP/1.0, no absolute-form targets, no trailers, no
   timeouts, no concurrency, no out-of-order pipelined responses. Each omission
   is a feature that would have added protocol code without adding a capability
   shape.
2. **The `:need-more` contract is tested, not proved.** `perturb.selftest`
   checks all 62 proper prefixes of one request and the two-request pipelining
   case; `-M:http` drives 121 one-octet `recv`s through it. That is E4's method
   at a fraction of E4's sample size, and `contract` in `perturb.http` is
   hand-written and undischarged like `perturb.bencode`'s.
3. **The contention axis is still untouched.** All four capabilities in perturb
   declare `:thread-confined`, there is no scheduler (INHERITED I20), and no rule
   here has ever seen a capability cross a task boundary.
4. **The real-socket half is single-threaded and depends on kernel behaviour**
   — a loopback `connect` completing into the accept queue, and both requests
   fitting in a socket buffer. It is an existence proof that the same driver runs
   on a real socket, not a demonstration that the driver is a server.
5. **The three new capabilities' annotations are hand-written and unverified**,
   like every other annotation in perturb. The nine transition bodies are
   axioms; nothing checks that `respond!` responds.

---

### E19 — the declaration language fixed, and a refinement on a transition

E18 left two items: four defects in the **declaration language**, and an
obligation the typestate axis could not state. Both were built concurrently —
one in the main tree, one in a git worktree — and merged by hand at `1dd068b`.
Independently re-run: `-M:refine`, `-M:check` (10/10 declaration fixtures, 25/25
+ 30/30 corpus verdicts, 14 accepted programs executed), `-M:selftest`,
`-M:oracle`, `-M:demo`, `-M:http`, `-M:noio`, all exiting 0.

#### The four declaration defects

**An operation now advances as many machines as it moves.** The primitive table
is keyed `[capability operation]` and stores the declared transition entry
itself. `check-annotation-consistency!` iterates an operation's declared edges
and compares **per capability**. Three diagnostics against axioms disappeared —
`perturb.nrepl/open`, `perturb.http/accept`, `perturb.http/body-finish!`.

Keying alone was **not sufficient**, contrary to the brief that commissioned it:
a third rule was needed (a terminal `:to` need not be produced, because the leak
rule exempts terminal states and *produced-and-dropped* is indistinguishable
from *not-produced* to every rule the checker has), and two edges had to be
**added to the declarations** — `ServerConn` declared no `body-finish!` edge and
`ResponseBody` declared no `respond-begin` edge, so per-capability comparison
alone would have found nothing to compare against. `perturb.http` goes from 10
declared entries read as 9, to **12 entries across 9 operations, none lost**.

**Correction to E18: there were three two-machine operations, not two.**
`perturb.http/respond-begin` is the third, and it is the dangerous one, because
it drew **no diagnostic at all**: the operation-keyed table held only its
ServerConn edge, which its annotation agreed with, so it **minted an undeclared
capability silently**. E18 finding 1(a) hid one spurious diagnostic *and* one
silent gap, and only the spurious one was visible.

**A machine has no pre-creation state.** `:created` is deleted from
`perturb.nrepl/Connection`; `open` declares `:from nil`. The argument recorded
with the fix: a state with no inhabitants and no outgoing edge is not a state,
it is a name for the machine's absence, and `nil` already denotes that in all
three of `perturb.http`'s capabilities, which were written later and never
needed one. Deleting the fiction requires no new rule; keeping it requires one.

**`:borrows` + `:produces` of the same capability is refused at the annotation**,
reported against the declaring var, and the body is then not checked — a refused
annotation is not a specification.

**Correction to E18: (c) was a false accept.** E18 recorded "nothing here is a
false accept — it is a declaration language that cannot say what the code does."
That is right for (a), (b) and (d) and wrong for (c):
`borrow-and-return-listener` was **accepted** while carrying an annotation no
implementation can satisfy. The fix moves it accept → reject, which is the
definition of removing a false accept.

**The unpositioned fallback is removed.** `:arg` is mandatory.
`unpositioned-two-cap-helper` goes from 5 diagnostics, none naming the
annotation, to 2, both naming it. Measured rather than assumed: the pre-fix
checker was run against the post-fix corpus, and (d) moved **no verdict** — only
a diagnostic kind.

**A new gate stage.** `declaration-corpus`: 10 hand-built machine/annotation
fixtures naming no code. It exists because (a) and (b) had **no program-corpus
artifact at all** — their only evidence was a diagnostic raised against an
axiom, which is exactly why they survived two sections. Demonstrated able to
fail independently.

#### A refinement on a transition

One extra key, `:perturb.cap/refine`, on a transition map:

```clojure
{:op 'perturb.http/body-write :from :open :to :open
 :perturb.cap/refine '{:update {written (+ written (ocount (arg 1)))}}}

{:op 'perturb.http/body-finish! :from :open :to :finished
 :perturb.cap/refine '{:name wrote-exactly-content-length
                       :requires (= written declared)
                       :logic QF-LIA}}
```

`short-body-still-type-checks` — declares `Content-Length: 6`, writes 3 — is now
**rejected**, with both numbers printed and the assignment history that produced
them. `over-long-body` is the same equality in the other direction (response
smuggling rather than truncation).

**It is not a solver, and the report says so unprompted.** A linear normaliser
plus a sign test; one third of the cases it decides are decided by plain
evaluation. The fragment, named honestly: *ground linear integer arithmetic with
uninterpreted non-negative constants, conjunctive, decided by normalisation.*
Complete on the variable-free case, sound but incomplete once an atom survives.
No case split, no Fourier–Motzkin, no hypotheses. Strictly weaker than QF-LIA
and strictly weaker than `prototypes/refinement.py`, which is retained as the
thing to port if the fragment must grow.

**Outside the fragment: refuse.** `refinement-undischarged` is a rejection, not
an acceptance. Four sources: a refinement crossing a loop boundary in either
direction, a refinement crossing a function boundary, a non-linear ghost term,
and a formula outside the fragment.

**The decidable class is wider than "constant lengths".**
`stream-a-runtime-length-body` declares `(o/ocount bo)` where `bo` is the
incoming request's body — an integer nobody knows until the request arrives —
writes `bo`, and **discharges**, because `bo` is one binding, so `ocount bo` is
one atom and `declared − written` normalises to zero. A run-time integer is not
the problem; an *unrelated* run-time integer is.

**The naive-implementation claim was verified rather than asserted.** With the
two `widen-caps` calls deleted, `body-written-in-a-loop` flips to accept; the
first draft of that program was wrong (the write sat inside the `recur` arm, so
a naive checker would have false-*rejected* it) and was rewritten so the naive
answer is genuinely `3 = 3`.

#### What the merge showed

The two fixes met and agreed: the refinement table was keyed
`[capability operation]` *in anticipation* of the rekeying, so it never
inherited (a)'s collision. In `http.clj` the union is strictly better than
either side — `ResponseBody`'s transitions carry **both** `respond-begin` as the
creating edge (expressible only after the rekeying) **and** the refinement keys.

**Each side's limits list falsified part of the other's.** The refinement work
recorded "an operation that advances two machines cannot be declared" and
"`:borrows`+`:produces` duplicates it"; the declaration work fixed both. The
declaration work recorded "a state cannot carry a refinement"; the refinement
work fixed that. Neither could have written the merged limits list.

#### E19's own nonclaims

1. **Declaring is not checking.** All nine `perturb.http` transitions remain
   axioms. Nothing establishes that `body-finish!` takes both edges it now
   declares, and `body-finished-before-conn-reused` is still data with nothing to
   discharge it. E18 finding 3 is **half** closed.
2. **`:update` is an annotation on an axiom.** Nothing checks that `body-write`
   writes the octets it claims. The discharge is relative to three declared
   lines exactly as the typestate check is relative to `:from`/`:to`.
3. **A refused annotation means an unchecked body** — a hole the fix introduced.
   It cannot hide a false accept (the refusal *is* a rejection), but fixing an
   annotation can surface diagnostics that were never suppressed, only never
   reached.
4. **The caller of a refused annotation is still analysed with it**, so
   `uses-borrow-and-return` is still rejected for a leak it did not commit.
   Suppressing that is a flow-rule change, so it was reported rather than done.
5. **No invariant syntax.** A programmer who knows a loop writes exactly N
   octets cannot say so; the program stays refused. The IR offers nowhere to
   attach an annotation to a `loop` form.
6. **One capability carries a refinement.** Whether any of it generalises is
   untested; a second would be the probe.
7. **Atoms are syntactic and per-binding.** Two inline occurrences of one
   expression are two atoms; only a shared binding is identified.

---

### E20 — the literature, and four claims of mine it refuted

Three parallel surveys were commissioned against the E15–E18 failures, each
asked for the case *against* the hypothesis it was testing. The framing under
test was mine: **that the annotation language is a shadow type system, and each
finding is a rediscovery of something a real type system does structurally.**
That framing is partly right and was wrong in four specific places.

**Method limitation, and it bounds everything here.** The egress policy blocked
every scholarly host — `dl.acm.org`, `arxiv.org`, `link.springer.com`,
`drops.dagstuhl.de`, `plv.mpi-sws.org`, `iris-project.org`, and most university
sites. Citations were verified for existence, venue, authors and year;
**no paper was read in full text.** Quantities are second-hand but consistent
across sources. Two of the three agents did reach primary *implementation*
artifacts (Granule's `Vec.gr` and `File.gr`, the Idris 2 manual, `h11`'s
`api.rst`, `rust-lang/rust#2178`, `cargo-geiger` issue #71), which is stronger
evidence than an abstract. Appendix D lists every reference so the sources can
be obtained later.

**Superseded in part by E21.** *No paper was read in full text **when E20 was
written***; that sentence bounded E20 and it no longer bounds this document.
Eighteen of the references below have since been fetched and read (E21), and
where E21's reading disagrees with E20's summary, **E21 governs** — three of
E20's own statements did not survive (tally rows 30–32), two of them in the
direction of more work rather than less. Everything in E20 not marked ✔✔ in
Appendix D is still second-hand and still carries the limitation as originally
stated.

#### The four refutations

| my claim | refuted by |
| --- | --- |
| internal/external choice dissolves the join rule | The linear conditional rule types **both branches in the same linear context**; under linearity each must consume it in full. **perturb's join rule *is* the linear-logic rule.** ⊕/& are the *additive* connectives, and "additive" is precisely the statement that alternatives use the same resources |
| graded/quantitative types decide the Content-Length obligation without a solver | Granule — the reference graded-modal language, by the authors named — **requires Z3**. Ghica & Smith's system is "parameterized by the decision procedure of the semiring equational theory". And Liquid Resource Types (the AARA authors) concluded value-dependent counting needs **refinements** |
| sealing gives a principled account of "these bodies are axioms" | It **relocates** trust from a list of names to a scope. Representation independence constrains what *clients* observe; it says nothing about whether the implementation maintains the invariant. A sealed `read-frame` that corrupts the buffer is perfectly well-sealed |
| MPST is the model for a listener plus many connections | MPST fixes the role set statically. The right model is **replicated binary sessions** — `!A` in linear logic — which is what Listener-plus-per-connection-ServerConn already is |

A fifth correction is structural rather than a claim: **grades count uses of a
binder; the obligation counts octets.** Those coincide only if `body-write`
writes exactly one octet. Any encoding making grades count octets is a linear
budget token split as `Permit[n] → Permit[k] ⊗ Permit[n−k]`, which is grade
arithmetic over variables, which is LIA, which is the solver. The intuition
"this is a counting problem" was right; "therefore grades" did not follow.

#### What the surveys converged on, independently

- **The module boundary is the top item, and it is not a type-theory problem.**
  All three said this. Every system surveyed inherits its host's module system.
- **E18 finding 1 was underpriced.** Two agents arrived separately at: make
  `body-finish!` *consume* the Body and *produce* the Conn, and the temporal
  ordering is enforced by the affine discipline already present, at zero solver
  cost — and the `close-conn!`-legal-from-`:writing` hole closes as a side
  effect. This is Vault (PLDI 2001), whose function guards are pre/post
  conditions over the **whole key set**. perturb's missing declaration form,
  from 2001.
- **Refinements at the transition, not inside a recursive protocol type.** Rast
  (CONCUR 2020) put arithmetic refinements inside recursive session types and
  got **undecidable type equality** despite Presburger being decidable. Putting
  the refinement on a transition and discharging by VC generation — which is
  what E19 did — avoids that.

#### The highest-value item nobody had on the queue

**`abort!` past live capabilities.** E15 blind spot 4 is a *soundness* gap, not
a coverage gap. Fowler et al. (POPL 2019): to have exceptions at all, linearity
must weaken to **affinity plus explicit cancellation**, so a capability
abandoned by an exception is cancelled rather than leaked. perturb has the
affinity (E6); it lacks the cancellation obligation on the abort path.

#### D4 is doing more work than it was credited with

Links combined linearity with effect handlers and **carried a soundness bug for
years**, because handlers can discard or multi-invoke continuations; the fix
required a second discipline, control-flow linearity (Tang et al., POPL 2024,
distinguished paper). §1.4's no-resumption rule places perturb outside that
tension structurally. **Recorded consequence: reopening D3 would drag
control-flow linearity in with it**, and that paper is the price list.

#### Three things the surveys establish that change no decision but should be known

**A dynamic join is a real option with a decade of production evidence.**
§1.2's "may or may not have been moved is not a mode; no sound join exists" is
true for a fully static, zero-runtime-state discipline and **false in general**:
Rust accepts the shape and resolves it with a runtime **drop flag** (RFC 320).
A `:maybe-moved` mode discharged by a compiler-inserted conditional close is the
only join mitigation in the survey with production evidence behind it. Recorded
as an option, not a decision.

**Independent corroboration of E18 finding 4, from a shipped tool.**
`cargo-geiger` has the same defect in mirror image — narrowing an `unsafe fn`
into a safe fn wrapping a small `unsafe {}` block *increases* its count, though
encapsulation strictly improved. Known since 2019, still shipped, and its own
docs disclaim it as "statistical input to auditing". Our list goes down when you
inline; theirs goes up when you narrow. Same flaw: a count over syntactic
markers tracks code shape, not boundary position.

**h11 independently invented finding 3b and does it dynamically.** It tracks
client and server machines simultaneously with state-triggered transitions
coupling them, because there was no static option. Its `MUST_CLOSE` state is
exactly the reification that dissolves perturb's keep-alive join — the decision
becomes a *state* rather than a branch — and it is adoptable today with no new
theory.

#### The case against the reframe, which is the strongest part

Of eleven recorded failures: **six are data-model or rule bugs fixable inside
the current architecture** (E19 fixed four of them), one is a module system, one
has no answer anywhere, one is the cancellation gap, and only two genuinely want
indices — which §1.3 already reserved.

And the sharp one: **adopting typestate-as-types would cost the runtime ledger.**
E15's false accept was found by *running* the accept set. If states exist only at
compile time, the only cross-check on the checker is the checker. No paper in
the survey addresses that trade, because none of them had a checker that was
wrong in a way execution could reveal.

Deployment reality: Plaid is dead (the group moved to Wyvern), Vault never
shipped, Fugue never shipped, Sing# shipped inside one research OS by its own
designers. Rust **removed** built-in typestate in 2012 — "the longest compiler
pass… I like the idea of typestate, but it's not pulling its weight." The only
mass deployment of typestate-as-types is the Rust embedded HAL *pattern*, which
needs macros to survive combinatorial type growth and ships a documented dynamic
escape hatch.

#### Two costs to budget for now

**Proof instability.** F\* ships `--quake` to detect flaky proofs by re-running
queries; the AWS Dafny team named proof brittleness as something that *blocked
code updates*. Concretely: a gate that accepts the corpus today can reject it
after a solver bump with no source change. §1.7 needs a pinned solver version
and a repeat-run check before the corpus grows.

**Trusted cores are small and frequently wrong.** Rudra scanned all 43,000
crates once and found 264 memory-safety bugs — **51.6% of everything RustSec had
recorded since 2016** — including in the standard library and the compiler. The
architecture localises faults correctly; it does not eliminate them. Do **not**
import that number: it is about memory safety, and perturb's axioms are protocol
claims, for which no base rate exists.

#### E20's own nonclaims

1. **No paper was read in full.** See the method limitation above.
2. **No survey validates a perturb decision.** Each says what the literature
   contains; the mapping onto perturb's failures is the agents' argument and
   mine, not a cited result.
3. **Where the research has no answer, it is recorded as such**: nobody has
   typed the sans-io trichotomy; nobody grades a capability by a wire-format
   byte budget; there is no empirical base rate for how often hand-written
   protocol axioms are wrong; there is no metric for trusted surface that
   survives adversarial refactoring; and "how often the join rule fires on real
   programs" is unmeasured in the literature — perturb's zero-in-nREPL,
   fires-on-the-first-HTTP-driver is better data than anything published.
4. **The four refutations are of my claims, not of the reframe.** Typestate-as-
   types would still eliminate E17 nonclaims 1–2 and E18 findings 1(a)–(c). The
   argument against it is cost, deployment evidence, and the ledger — not that
   it would fail to work.

**E20 nonclaim 1 is superseded.** Eighteen of E20's references have now been
read in full text (E21), plus two sources E20 did not have. Where E21 and E20
disagree, E21 governs.

---

### E21 — the papers, read; and the two places E20 read an abstract correctly and applied it wrongly

E20 was a literature survey conducted under an egress policy that blocked every
scholarly host, so **no paper in it was read in full text**. It nonetheless
refuted four claims of mine, and those refutations became load-bearing: two
§4.6 items and a defence of §1.4's D4 rest on them. This finding is the
verification pass. Network access was available; **19 documents were fetched and
read in full text** — eighteen of E20's references (Vasconcelos in both the SFM
2009 and I&C 2012 versions) plus two E20 did not have. All are marked ✔✔ in
Appendix D.

**Method, and what it does not establish.** Every verdict below carries a
quotation with a section, page or figure locator. Where a paper could not be
obtained, that is said rather than smoothed over. What this finding establishes
is *what the cited papers say*; the mapping onto perturb remains argument, and
E20 nonclaim 2 stands unchanged.

#### The verdict table

| # | E20's claim | verdict | what moves |
| --- | --- | --- | --- |
| 1 | the linear conditional rule types both branches in the same context; perturb's join rule *is* the linear rule; ⊕/& do not dissolve it | **CONFIRMED**, and more strongly than E20 put it | §4.6 join item gains a sharper citation and one published mitigation E20 did not have |
| 2 | handlers break linearity; Links carried the bug; control-flow linearity is the fix | **CONFIRMED** (first three parts) | — |
| 2′ | §1.4's no-resumption rule places perturb **outside** that tension | **OVERSTATED** — outside the *multi-shot* half only. `abort!` is the *discard* half, which the same paper names as the other half of the mismatch | §1.4, §4.6; new tally row 30 |
| 3 | exceptions require **affinity** plus explicit cancellation; perturb "has the affinity" | **REFUTED on the mechanism.** Fowler et al. §1.3 *rejects* affinity by name and §1.4 is titled "Linear Types **with** Explicit Cancellation". Linearity is kept; cancellation is an explicit term | §1.2, §4.6 rewritten; new tally row 31 |
| 4 | grades do not decide a value-dependent counting obligation without a solver | **CONFIRMED as a conclusion** | tally row 28 stands |
| 4′ | *because* "grades count uses of a binder, not octets" | **REFUTED as a reason.** Doré (2026) gives multiplicities that depend on run-time values. The obstacle is not expressiveness; it is that the resulting check is undecidable and pays in hand-written proof terms instead of SMT | new tally row 32 |
| 5 | sealing relocates trust; RustBelt discharges it per-library | **CONFIRMED**, verbatim | tally row 29 stands |
| 6 | sealing *creates* linearity (Alms) | **CONFIRMED**, verbatim, and the mechanism is directly transplantable | §4.6 module item gains a concrete design |
| 7 | Vault gives pre/post over the whole key set; E18 1(a) was a 2001 rediscovery | **CONFIRMED**, and Vault is **more general than E19** in three ways | §4.6 |
| 8 | refinements inside recursive session types cost decidability; E19's placement avoids it | **CONFIRMED**, and the cause is nameable | — |
| 9 | Rudra 264 / 51.6%; Astrauskas 92.3%; seL4 ~20:1 | **CONFIRMED with denominator corrections**, one of which matters | §6 |
| 10 | Flux is the nearest existing system | **CONFIRMED**, every conjunct | §4.6 gains a costed target |
| 11 | five negative claims | **four hold; one is qualified** | E20 nonclaim 3 |

#### Claim 1 — confirmed, and the algorithmic form is the exact shape of perturb's rule

Vasconcelos, *Fundamentals of Session Types*. The declarative rule (SFM 2009
notes Fig. 5, p. 7; I&C 2012 Fig. 5):

> Rule [T-If] for the conditional process splits the incoming context in two
> parts: one used to check the condition, the other to check **both branches**.
> The same context for the two branches is justified by the fact that only one
> of P or Q will be executed.

⊕/& do not dissolve it — the branch rule is the same rule (SFM §5, p. 14):

> To type check a branching process prefixed by x at type &{lᵢ : Tᵢ} we have to
> check each of the possible continuations Pᵢ at x: Tᵢ. **We use the exact same
> Γ₂ in all cases** for only one of the Pᵢ will be executed, **similarly to
> rule for the conditional process**.

And the algorithmic system (SFM Fig. 12, p. 22; I&C Fig. 12) is stronger than
E20 claimed, in perturb's favour. [A-If] is

```
    Γ₁ ⊢ v : q bool ; Γ₂      Γ₂ ⊢ P : Γ₃ ; L₃      Γ₂ ⊢ Q : Γ₃ ; L₃
    ───────────────────────────────────────────────────────────────
                Γ₁ ⊢ if v then P else Q : Γ₃ ; L₃
```

Both branches take the **same input context Γ₂ and must return the same output
context Γ₃ and the same used-set L₃**. That is not merely "the same context"; it
is an equality on the *residual*, which is what perturb's join rule computes and
what a consuming branch beside a non-consuming branch violates. E20 said
perturb's rule *is* the linear rule; the algorithmic rule shows it is the same
rule stated in the same style, output contexts and all. **Tally row 27 stands
and is strengthened.**

*Scope, because the brief asked:* this is a π-calculus of processes, not a
functional language with a join point, and the paper offers no discussion of
what to do when branches disagree — it simply does not type them. Two
independent corroborations were found that E20 did not have, and one supplies
the mitigation §4.6 was missing; both are under claim 11(d) and the
staged-protocol note below.

#### Claim 2 — confirmed, and then applied to a case the paper puts on the other side

Tang, Hillerström, Lindley, Morris, *Soundly Handling Linearity*, POPL 2024.
The first three parts are confirmed from the abstract and §1:

> Whereas conventional linear type systems bake in the assumption that
> continuations are invoked exactly once, effect handlers allow continuations
> to be **discarded (e.g. for exceptions)** or invoked more than once (e.g. for
> backtracking). This mismatch leads to soundness bugs in existing systems such
> as the programming language Links.

The Links bug is real and worked in full in §1 — the `outch` example, a channel
of session type `!Int.!String.End` against a multi-shot `Choose` and a
discarding `Fail`: "Running the program causes a segmentation fault when
printing the received values, as it erroneously attempts to concatenate a
string with an integer." §5: the implementation "fixes a **long-standing** type
soundness bug in Links". *Long-standing* is the paper's word and it states no
duration; E20's "for years" is a fair gloss but is not sourced.

**The fourth part does not survive.** E20 wrote that §1.4's rule — substitute a
validated result or abort, no resumption, no continuation capture — places
perturb *outside* that tension. The sentence quoted above lists **two** ways
handlers break linearity, and `abort!` is the first one. The paper's safety
property is symmetric:

> we formally prove that F°ₑff preserves the integrity of linear values in the
> sense that **no linear value is discarded or duplicated**.

And T-Handler (§3, p. 15) prices exactly this. The continuation rᵢ of an
operation ℓᵢ is given the value linearity Yᵢ of that operation, so that

> when Yᵢ = ◦, the continuation of ℓᵢ may use some linear resources. Making rᵢ
> linear guarantees that they are used exactly once. When Yᵢ = •, **the
> continuation of ℓᵢ must not use any linear resources** and rᵢ is unlimited.

An operation whose continuation is *discarded* cannot be given a linear
resumption — a linear rᵢ must be used exactly once, and a `Fail`-style clause
never binds it. It must therefore be control-flow-unlimited, and the rule then
requires that **its continuation capture no linear resources at all**. That is
the restriction that suffices, stated precisely; and it is the restriction
perturb violates every time `abort!` is thrown past a live capability.

So the correct statement is: **tail-resumptive-or-abort places perturb outside
the multi-shot half of the tension and squarely inside the discard half.** "No
resumption and no continuation capture" is a sound answer for the
substitute-a-result path and is *not* an answer for the abort path. The halves
are not equally hard — multi-shot is the hard one and perturb genuinely avoids
it — but E20's "outside that tension" is too strong, and it is too strong in
exactly the direction of claim 3.

**D4's defence is narrowed, not withdrawn.** Reopening D3 still drags
control-flow linearity in, and that paper remains the price list. What changes
is that D4 does not buy perturb out of the linearity/handler interaction; it
buys it out of half of one.

#### Claim 3 — the highest-value item, and E20 (and the brief) got its mechanism backwards

Fowler, Lindley, Morris, Decova, *Exceptional Asynchronous Session Types*, POPL
2019. E20 says: "to have exceptions at all, linearity must weaken to **affinity
plus explicit cancellation**", and "perturb has the affinity (E6); it lacks the
cancellation obligation". The brief restates this. **The paper says the opposite
about the first half, in a section written to say it.**

§1.3, titled *Affine Types*, is an argument against them:

> Affine types present two quandaries arising from endpoints being silently
> discarded. First, a developer receives no feedback if they accidentally forget
> to finish a protocol implementation. Second, if an exception is raised in an
> evaluation context that captures an open endpoint then the peer may be left
> waiting forever.

§1.4 is titled ***Linear* Types with Explicit Cancellation** and heads off the
confusion by name:

> (They characterise their sessions as affine, but it is important **not to
> confuse their system with affine type systems, as in §1.3, which allow
> variables to be discarded implicitly**.)

The recipe is linearity **retained**, with a `cancel` term that is itself a
*use*: T-Cancel (Fig. 4) is `Γ ⊢ M : S` ⟹ `Γ ⊢ cancel M : 1`. Discarding is not
permitted; discarding *explicitly* is. Three principles, §1.4:

> endpoints can be explicitly discarded; an exception is thrown if a
> communication cannot succeed because a peer endpoint has been cancelled; and
> endpoint cancellations are **propagated** when endpoints become inaccessible
> due to an exception being thrown.

**This inverts what perturb was told it already had.** E6's affine binding is
not half of the solution; it is §1.3's rejected option — silent discard is the
defect, not the foundation. perturb has the thing the paper argues against and
lacks both halves of the thing it argues for. The §4.6 item gets bigger, not
smaller.

**What discharges the obligation, and what it costs.** From §2.3, Fig. 6:

- `E-Cancel`: `F[cancel a] ⟶ F[()] ∥ ⨸a` — cancellation creates a **zapper
  thread** that owns the dead endpoint.
- `E-Zap`: cancelling an endpoint cancels every endpoint sitting in its buffer,
  repeatedly, until the buffer is empty. Cancellation is **transitive through
  data that holds capabilities**.
- `E-Raise`: "invokes the `otherwise` clause if an exception is raised, **while
  also cancelling all endpoints in the enclosing pure context**"; for an
  unhandled exception, `E-RaiseChild`/`E-RaiseMain` cancel **all free endpoints
  in the evaluation context**. This is the automatic half: the language, not the
  programmer, cancels what the non-local exit abandoned.
- `E-ReceiveZap` / `E-CloseZap`: an operation on an endpoint whose peer is
  cancelled **raises** rather than blocking. §2.2 says why this is not optional:
  "Naïvely implemented, cancellation violates progress: a thread could discard
  an endpoint, leaving a peer waiting forever."
- One asymmetry, a design cost rather than an oversight: there is deliberately
  **no** rule raising on a *send* to a cancelled peer, "since to do so would
  violate confluence" (§2.3). "Not raising exceptions on sends to dead peers is
  standard in languages such as Erlang."

**What perturb would have to add — concretely**, in dependency order:

1. **A `cancel` form per capability class, typed as a consuming use.** Not an
   exemption and not a checker special case: `cancel` must satisfy the same
   `:consumes` rule every other operation does, so that "cancelled" and "leaked"
   become different verdicts. This is small and has the best ratio of the four.
2. **A live-capability set at every `abort!` site.** `perturb.check` already
   computes what is live at a program point — that is what the scope-exit leak
   rule reads. The rule becomes: at an `abort!`, every capability live in the
   enclosing scope up to the nearest handler must be cancelled. Two ways to
   discharge it, and the choice is real: *require* the programmer to write the
   cancels (a new rejection, more diagnostics, in the E19 style), or *insert*
   them (a compiler obligation, which is what E-Raise does, and which is the
   same shape as the dynamic-join option below).
3. **Propagation into composites.** E-Zap's analogue: cancelling a capability
   must cancel the capabilities reachable from it. perturb's abstract domain has
   exactly one composite, a tuple (§1.2), so this is bounded today and will not
   stay bounded.
4. **Peer notification — only where there *is* a peer.** This is the part that
   does **not** transplant wholesale, and saying so is the point of item 4.
   EGV's endpoints always have a counterparty, and the `⨸`/raise machinery
   exists to stop that counterparty hanging. perturb's `ResponseBody` and
   `ServerConn` have a remote peer that is not a perturb value; a buffer or a
   cursor has no peer at all. What survives is the *obligation*, not the
   mechanism: a cancelled `ServerConn` must leave the wire consistent (close, or
   a truncated-body signal), and that is an axiom, not a typing rule.

Items 1–3 are checker work of the class E19 already did. Item 4 is a protocol
decision per capability. **The `abort!` item is the one that becomes work, and
it is now specified.**

#### Claim 4 — the conclusion holds; the reason given for it does not

Three parts confirmed:

- **Granule requires a solver.** §1: Granule "exploits an SMT solver to
  discharge theorems over the indices of graded modalities"; §5.1: "Predicates
  are compiled into the SMT-LIB format and passed to a compatible SMT solver",
  "(we use Z3)". Precisely: it requires *an SMT solver*, with Z3 the one used.
  E20's "requires Z3" is right in practice and a notch stronger than the paper.
- **Ghica & Smith.** Abstract: "for this abstract type system we provide both a
  general **type-inference procedure**, parameterized by the decision procedure
  of the semiring equational theory"; §4: "Provided this theory is decidable, a
  type inference algorithm automatically follows." E20 attributed the
  parameterisation to *the system*; the paper attributes it to the *inference
  procedure*. Directionally right, one notch stronger than the source. §3.3
  supplies a sharper fact E20 did not have: their own constraints are discharged
  by Z3, and "nonlinear systems of constraints over ℕ are generally
  undecidable".
- **Liquid Resource Types.** Abstract, and it is the cleanest statement of the
  trade in this bibliography: "automated techniques are restricted to relatively
  constrained families of resource bounds, while **more expressive proof
  techniques admitting value-dependent bounds rely on handwritten proofs**.
  Liquid resource types combine the best of these approaches, **using logical
  refinements** to automatically prove precise bounds." Value-dependent counting
  ⇒ refinements ⇒ SMT. **Tally row 28 stands.**

**But E20's fifth, structural correction is refuted.** E20 wrote: "grades count
uses of a binder; the obligation counts octets... Any encoding making grades
count octets is a linear budget token split as `Permit[n] → Permit[k] ⊗
Permit[n−k]`, which is grade arithmetic over variables, which is LIA, which is
the solver." The first clause is not a fact about graded systems; it is a fact
about the graded systems E20 could reach. arXiv 2507.08759 — which E20 could not
fetch — is Maximilian Doré, *Dependent Multiplicities in Dependent Linear Type
Theory*, and its subject is precisely the opposite:

> We present a novel dependent linear type theory in which the **multiplicity of
> some variable — i.e., the number of times the variable can be used in a
> program — can depend on other variables.**

So "this handle owes the wire exactly N more octets", N a run-time integer, *is*
expressible as a multiplicity, and it is decided **without SMT** — by conversion
in the host dependent type theory, implemented in Agda (§5). The price is stated
by the author in §2.4, and it is the price LRT names:

> The expressivity of our type system comes at a cost, however: since we can
> compute with supplies using our intuitionistic theory, **type-checking for ⊩
> is vastly undecidable.** ... Dal Lago and Gaboardi have dealt with this issue
> by parametrising their theory over the set [of] function symbols that are
> allowed in the computation of resource annotations, restricting to simpler
> theories when type-checking needs to be decidable. **We take a different
> approach** and allow the full power of DTT ... if we can establish that some
> terms are propositionally equal, we can use this witness in `splyConv` to
> convince the type-checker that we have used the specified resources.

That is: the programmer supplies a proof term. **The answer to the brief's
concrete question is therefore "yes, and it does not help":** a graded system
can express the Content-Length obligation over a run-time integer, and avoids
SMT only by making the check undecidable and handing the discharge back to the
programmer. The others were checked and cannot: Granule's grades are semiring
elements solved by SMT; Idris 2/QTT multiplicities are {0, 1, ω}; Linear Haskell
puts multiplicity on the arrow over the same finite set; BLL's index polynomials
and dℓPCF are complete only *relative to an oracle* — which is why Doré cites Dal
Lago & Gaboardi at exactly the point above.

**The conclusion perturb acted on is unchanged: there is no solver-free route to
this obligation.** What changes is the record's *reason* for believing it, which
had been a claim about what grades **are** and is now a claim about what the
decision problem **costs**. Recorded rather than silently applied. **New tally
row 32.**

#### Claims 5 and 6 — both confirmed verbatim, and 6 is a design

**Claim 5.** RustBelt, POPL 2018, abstract:

> Our proof is extensible in the sense that, **for each new Rust library that
> uses unsafe features, we can say what verification condition it must satisfy**
> in order for it to be deemed a safe extension to the language.

And §1.2: "the semantic interpretation of the interface yields a
**library-specific verification condition**... confined to libraries that
satisfy their verification conditions, the program is safe to execute." E20
rested a recommendation on one sentence about per-library verification
conditions; the sentence exists and says what E20 said. **Tally row 29 stands.**

*Scope, and §4.6 should carry it:* RustBelt's obligations are discharged in Coq
against a semantic model of the whole language. "Each entry names an obligation
instead of granting an exemption" describes an architecture whose price is a
machine-checked logical relation, not a lightweight annotation scheme. Naming
the obligation is the cheap part.

**Claim 6.** Alms, POPL 2011, abstract:

> A key feature of Alms is the ability to introduce abstract affine types via
> ML-style signature ascription. In Alms, **an interface can impose stiffer
> resource usage restrictions than the principal usage restrictions of its
> implementation.** This form of sealing allows the type system to naturally and
> directly express a variety of resource management protocols from
> special-purpose type systems.

Confirmed verbatim. **What makes it sound is stated in §2** and is
transplantable:

> The original array type α Array.array has kind U, as in unlimited, because it
> places no limits on duplication. We can use it to represent an abstract type
> of kind A, however, **because U is a subkind of A**, and Alms's kind
> subsumption rule allows assigning an abstract type a **greater** kind than
> that of its concrete representation.

The requirement is: a qualifier lattice with unlimited ≤ affine; sealing
permitted to move *up* it only; and a principal-kinding result (Theorem 3 —
"every typeable aλms function has a principal usage qualifier") so that "the
principal usage restrictions of its implementation" is well defined at all.

Two further details bear directly on `:perturb.cap/representation`. First, §2's
`CAP_ARRAY` separates the *reference* from the *capability* and ties them with
an existential "stamp" — `(α, β) array` unlimited, `β cap` affine — where "the
existential guarantees that the stamp on an array can only match the stamp on
the capability created by the same call to `new`". That is the shape §1.2 has
been reaching for by listing operation names. Second, Alms represents those
capabilities by `unit`, "which is adequate because these capabilities have no
run-time significance": **the capability is erased.** That matters to any §1.4
ledger argument that assumes capabilities must exist at run time to be
cross-checked.

#### Claim 7 — confirmed, and Vault is more general than E19 in three ways

DeLine & Fähndrich, PLDI 2001, §2:

> In Vault, a function's type has a pre- and postcondition, which respectively
> state **which keys must be in the held-key set to call the function and which
> keys are in the held-key set when the function returns.**

Effect clauses `[k@A→B]`, `[k@A→]` (consumed), `[→k@B]` (produced), `[new k@B]`
(fresh). Keys "can be neither duplicated nor lost". An operation moving two
machines is therefore one effect clause naming two keys — ordinary, in 2001,
exactly as E20 said. E18 finding 1(a) was a rediscovery. **Confirmed.**

E19 fixed it by keying `[capability operation]`. Vault's form is more general in
three ways, and the first is the one §4.6 has circled for three findings:

1. **Type guards attach keys to *data*, not only to operations.** §2: "a data
   object is guarded by zero or more keys; at a given program point, **all the
   object's keys must be in the held-key set in order for the program to access
   the data object** at that point." That is a typed statement of "you may touch
   the connection's concrete map only while holding the Connection's key" — the
   module-boundary problem, expressed *inside* the capability system rather than
   as a list of exempt operation names. perturb has no analogue, and
   `:perturb.cap/representation` is where it would go.
2. **Types may be parameterised by key sets** (§2: "a Vault type may be
   parameterized by a key set"), so a helper can be generic in which capability
   it requires. E19's `:arg` is mandatory and monomorphic; the
   `unpositioned-two-cap-helper` family is the shape this addresses.
3. **`[new k@S]` makes minting first-class.** E19 reached the same place by
   deleting `:created` and declaring `:from nil` — the right answer, arrived at
   independently — but Vault has it as an effect form rather than a sentinel.

All three are compile-time only: "Since guards and keys are purely compile-time
entities, the function will be compiled into a function taking an ordinary
parameter" — the same erasure point Alms makes.

#### Claim 8 — confirmed, and the cause names why E19 is safe

Das & Pfenning, CONCUR 2020, abstract:

> We show that, **despite the decidability of Presburger arithmetic, type
> equality and therefore also subtyping and type checking are now undecidable**,
> which stands in contrast to analogous dependent refinement type systems from
> functional languages. We also present a practical, but incomplete algorithm
> for type equality, which we have implemented [in Rast].

The cause, §4:

> We prove the undecidability of type equality by exhibiting a reduction from an
> undecidable problem about two counter machines. ... arithmetic constraints
> allow us to model branching zero-tests available in the machine. **This,
> coupled with recursion in the language of types, establishes undecidability.**

The undecidability needs *both* arithmetic refinements *and* recursive types,
and the reduction goes through on "a small fragment ... containing only type
definitions, internal choice (⊕) and assertions ?{φ}.A where φ just contains
constraints n = 0 and n > 0".

**E19's placement genuinely avoids it, for the stated reason.** E19 attaches
`:perturb.cap/refine` to a *transition* and discharges it by normalisation
against ghost state. There is no recursive refined type, so no type-equality
question is ever asked: the theorem's hypothesis is not met. That is a
structural answer, not luck. Two honesty notes. E19 also avoids it by being
enormously weaker — it refuses every loop (E19 nonclaim 5) — so "avoids the
undecidability" is not evidence the fragment is well chosen. And the hypothesis
*would* be met the moment a protocol type became recursive and indexed, which is
what any session-typed direction for §1.2 would do.

#### Claim 9 — numbers confirmed, denominators corrected, and one correction matters

- **Rudra**, SOSP 2021. Abstract: "RUDRA can scan the entire registry (43k
  packages) in 6.5 hours and identified 264 previously unknown memory safety
  bugs—leading to 76 CVEs and 112 RustSec advisories being filed, which
  **represent 51.6% of memory safety bugs reported to RustSec since 2016**."
  §6.1 confirms 264 bugs in 145 packages. **The denominator is not 43,000.**
  §6.1: of the 43k downloaded, "15.7% (7k) did not compile ... 4.6% (2k) did not
  produce any Rust code ... 1.8% (0.7k) did not have proper metadata, **leaving
  us with 77.9% (33k) packages as analysis targets**." E20's "scanned all 43,000
  crates" should read *downloaded 43k, analysed 33k*. This does not weaken the
  use E20 made of it; it slightly strengthens it, the yield having come from a
  smaller corpus.
- **Astrauskas et al.**, OOPSLA 2020, §5.2.1: "for **92.3% of all crates, the
  unsafe statement ratio is at most 10%**." Confirmed verbatim. **The
  denominator is the correction that matters:** "all crates" includes the 76.4%
  that "contain no unsafe features at all", so the statistic is dominated by
  crates with none. The authors' own next sentence is the one E20 did not carry:
  "with 21.3% of crates containing some unsafe statements and – out of those
  crates – **24.6% having an unsafe statement ratio of at least 20%, we cannot
  claim that developers use unsafe Rust sparingly**, i.e., they do not always
  follow the first principle of the Rust hypothesis." Appendix D's "only
  **partially** supported" was right; the 92.3% figure quoted alone points the
  other way and should not be used to argue that trusted cores are small.
- **seL4**, SOSP 2009. p. 1: "seL4 ... comprises **8,700 lines of C code** and
  600 lines of assembler." §5.2: "The overall size of the proof, including
  framework, libraries, and generated proofs ... is **200,000 lines of Isabelle
  script**." 200,000 / 8,700 ≈ **23:1**, not ~20:1. Immaterial to any argument
  here; corrected because it is quoted as a fact.

#### Claim 10 — Flux confirmed on every conjunct, and the gap to `perturb.refine` is now costed

Lehmann, Geller, Vazou, Jhala, PLDI 2023. The abstract, conjunct by conjunct:

> we design a novel refined type system for Rust that **indexes mutable
> locations, with pure (immutable) values that can appear in refinements**, and
> then **exploits Rust's ownership mechanisms** to abstract sub-structural
> reasoning about locations within Rust's polymorphic type constructors, **while
> supporting strong updates** ... we implement our type system in Flux, a
> plug-in to the Rust compiler that ... **efficiently synthesize[s] loop
> annotations—including complex quantified invariants describing the contents of
> containers—via liquid inference.**

Strong update = the refinement index of a location changes at an assignment
(§2.3: "Exclusive ownership provides local strong updates"), which is E20's
"typestate carrying a refinement" — a fair gloss, though Flux never uses the
word *typestate*. Inference is Horn-clause based (§4.3): "Flux uses the Liquid
Fixpoint horn constraint solver ... the unknown κ predicates are Horn variables
that may have multiple arguments, allowing liquid inference to track
dependencies between multiple program variables, **thereby enabling Flux to
automatically synthesize loop invariants**."

**What `perturb.refine` would need to reach the same class**, given E19's
fragment is "ground linear integer arithmetic with uninterpreted non-negative
constants, conjunctive, decided by normalisation" and refuses every loop:

1. **An SMT backend.** Flux's ergonomics rest on "decidable (quantifier free)
   validity queries over the predicates, thereby enabling Horn-clause based"
   inference (§1). No version of this keeps E19's normaliser.
   `prototypes/refinement.py` is already recorded as the thing to port; this is
   the finding that says porting it is necessary and not sufficient.
2. **A Horn-clause solver over refinement variables** — which is E13's abstract
   refinements arriving from the other direction, since Flux's κ variables *are*
   predicate variables. §1.2 already records that HM cannot solve them and that
   this is Horn-clause constraint solving; Flux is the existence proof that it
   is practical at this scale.
3. **Indexed locations**, so a capability's state and its refinement are one
   thing rather than E19's transition-plus-ghost-state pair.

That is a large, well-understood, entirely conventional pile of work with a
shipped system at the end of it. It is also the *opposite* direction from E19's
"it is not a solver, and the report says so unprompted". Both positions are
defensible; the record should not pretend they are compatible.

#### Claim 11 — four negative claims hold, one is qualified, and one search produced the missing mitigation

Searched with the hosts the brief named, plus arXiv, DBLP, Semantic Scholar and
GitHub.

- **(a) A typed sans-io trichotomy — NOT FOUND, unchanged.** Searched for typed
  incremental parsers returning ok / need-more-with-the-*exact original* cursor /
  invalid. What exists is the *pattern*, undocumented by a type system
  (`sans-io.readthedocs.io`, `webrtc-rs/sansio`), and formal work on the *split*
  rather than the trichotomy (Interaction Trees, `coq-http`, already Appendix
  D.7). EverParse validators return a consumed position or an error and are
  verified, but they validate a complete buffer; "need more" is not in their
  result type. **E20's negative claim stands.**
- **(b) An implementation of Qian, Kavvos, Birkedal — NOT FOUND, confirmed.**
  The paper (arXiv 2010.13926, read) introduces coexponentials and a
  session-typed functional language *translated into* them. Every occurrence of
  "implement" in it is an encoding **within** the calculus — Compare-and-Set, a
  Keynesian beauty-contest umpire — not a software artifact. **Stands.**
- **(c) A graded system enforcing a wire-format length obligation — NOT FOUND,
  stands**, and claim 4 now explains why: the obligation is value-dependent, and
  the one system that can express it pays in undecidability rather than in a
  solver. Granule's protocol examples grade *sessions*, not octets.
- **(d) A measurement of how often a join rule rejects real code — NOT FOUND for
  frequency; E20's stronger sentence does not survive.** E20 nonclaim 3 says
  "perturb's zero-in-nREPL, fires-on-the-first-HTTP-driver is better data than
  anything published". On *frequency* that stands. Two things were found that it
  should not have claimed superiority over:
  - **The problem is named and published, as the motivation for a system built
    to remove it.** Doré (2507.08759) §1, on the standard linear `if` rule:
    "Both elements of type A have to be constructed from the same resources,
    **which is a steep ask: different if-then-else branches in a program will
    generally do different things, and hence use different resources.**" That is
    §1.2's "known usability risk" in someone else's words, and dependent
    multiplicities let the branches differ. **This is a fourth mitigation and
    the only one that removes the rule rather than routing around it** — and it
    costs undecidable checking (claim 4), so it is an option to record, not to
    take.
  - **The usability cost is measured, just not the frequency.** *A Grounded
    Conceptual Model for Ownership Types in Rust* (arXiv 2309.04134, read), §4:
    participants could "predict the compiler's reason for rejection in 78% of
    cases", but "could only **fix** the program in **46%** of cases ... and
    could only create a counterexample in 31% of cases". §4.6's own sentence —
    the accepted rewrite "works and is not discoverable from the diagnostic" —
    is that documented failure mode, with a number on it.
- **(e) A trusted-surface metric surviving adversarial refactoring — NOT FOUND,
  stands.** Nothing beyond what E20 had: `cargo-geiger` #71's mirror defect, and
  the unsafety-isolation-graph and safety-tag work in Appendix D.6, which
  propose audit *units* rather than a scalar — i.e. they concede the scalar does
  not exist.

#### One thing found that belongs to the staged-protocol note

`docs/research/STAGED-PROTOCOL-NOTE.md` asks whether "type systems as macros"
carries *substructural* typing, calling it "the load-bearing question for a
Lisp-hosted capability tier". **It does, and the artifact exists.** The POPL
2017 Turnstile paper itself never mentions linearity — its example ladder is
STLC → System F → Fω → subtyping → dependent types — but the `macrotypes`
repository ships `turnstile-example/turnstile/examples/linear/`, containing
`lin.rkt`, `lin2.rkt`–`lin5.rkt`, `lin+cons.rkt`, `lin+tup.rkt`, `lin+var.rkt`,
**`lin+chan.rkt`** (linear channels) and `fabul.rkt`. `lin.rkt` defines the
linear arrow `-o` and a `turnstile/mode`-based linear scope discipline
(`linear-use-var!`, `linear-out-of-scope!`, `linear-merge-scopes!`,
`make-linear-branch-mode`, `branch-then`/`branch-else`).

And it raises, verbatim:

```racket
(define (fail/unbalanced-branches x)
  (raise-syntax-error #f "linear variable may be unused in certain branches" x))
```

A Lisp-hosted linear type checker implemented as macros has **perturb's join
rule and perturb's join diagnostic**. That is a third independent corroboration
of claim 1, an existence proof for the note's first question, and the closest
prior art to a perturb capability tier this document has found. The note's other
two questions — whether 3D is independently checked, and whether anyone has
*generated* linearity/typestate annotations from a protocol description — were
not resolved and remain open.

#### E21's own nonclaims

1. **Nineteen documents, not the whole bibliography** (twenty-one counting the
   two D.8 papers, which E25 read). Appendix D has roughly ninety entries. What was read is marked ✔✔; everything unmarked remains
   verified-for-existence only, and E20's method limitation continues to bound
   it. The selection was the brief's eleven claims, not a sample of the field.
2. **Reading a paper is not running one.** Nothing here was executed. E21 is a
   documentary finding and sits under the same flag as E20 in the tally: no
   artifact produced these corrections. The `abort!` work specified under claim
   3 is the artifact that would.
3. **The mapping onto perturb is still argument.** Confirming that Fowler et al.
   say X does not establish that X applies to a capability with no peer; claim 3
   item 4 says exactly where the transplant stops.
4. **Two papers could not be obtained**, and were marked ✗ in Appendix D:
   Parkinson & Bierman (POPL 2005) and Mostrous & Vasconcelos (2014).
   **Since corrected: Mostrous & Vasconcelos was recovered** from its CC-BY HAL
   deposit and is now in `docs/research/papers/`. It refines tally row 31 rather
   than overturning it — the word "affinity" is theirs, but their affinity is
   *constituted by* explicit cancellation, so "perturb has the affinity" is
   still wrong. Parkinson & Bierman remains unobtained; Unpaywall reports it is
   **not open access at all**, so claim 5's abstract-predicates half still rests
   on RustBelt, which was read, plus E20's second-hand reading.
5. **The brief that commissioned this contained a factual error, as it
   predicted.** It restated E20's "linearity must weaken to affinity **plus** an
   explicit cancellation obligation" as the thing to verify against Fowler et
   al., and offered "its capabilities already bind affinely (E6)" as though that
   were half the solution. Fowler §1.3 rejects affinity by name and §1.4 keeps
   linearity. The error was inherited from E20 rather than introduced by the
   brief — but it was carried forward unqueried in both, which is the failure
   mode a second source is supposed to catch.

---

> **E21 is reserved** for the verification of E20 against the actual papers,
> commissioned in `docs/research/E20-VERIFICATION-BRIEF.md` and running
> elsewhere. E22–E24 were recorded while it was outstanding.

---

### E22 — `report-limits` item 8 was wrong in both halves

Item 8 said: *"Destructuring, `peek`, `last`, or a computed index lose the
capability to OPAQUE — which is silent, not a diagnostic. This is the most
likely place for a FALSE ACCEPT to hide today."* Never tested. One probe program
per eliminator, with a correct-disposal and a drop variant of each.

**Nothing was silent.** Every case drew `no-signature` at the callee — the tuple
holding a live capability was passed to a function with no capability signature —
and `dangling` at scope exit. Five rejections, zero acceptances, four diagnostics
on the destructuring case alone. There was no false accept to find.

**The real defect was the opposite one, and worse in practice: a false reject on
idiomatic Clojure.** `(let [[c frames] r] …)` lowers to `(nth G__287 0 nil)` —
three arguments, the third a not-found default — and `projection-index` demanded
exactly two. Every destructuring bind of a capability was refused on arity, not
analysis. One-line fix; the not-found default is sound to ignore because an index
past the end of the abstract tuple already yields OPAQUE.

`perturb.corpus` gains `destructure-and-close` and `uses-destructuring-in-a-loop`
(both accept, both run) and the drop variant as a rejection.

**Why item 8 was wrong in this particular direction matters.** A self-authored
corpus cannot contain this bug, because the author knew destructuring would not
check and therefore never wrote it. The claim was not merely unverified — it was
*unfalsifiable from inside the corpus*.

---

### E23 — checking code perturb did not write

Every artifact checked through E19 was authored by someone who already knew the
rules. E15 nonclaim 3 says why that is dangerous and E22 shows it concretely.
This is the first artifact without that defect.

An agent ported `teensyp.stream` — a blocking line-oriented connection adapter
from `casselc/jolt-tcp` — into perturb, **forbidden to read** `cap.clj`,
`check.clj`, either corpus, `refine.clj`, the README, or this document. The
capability declaration was then written **from outside**, in `perturb.streamcap`,
leaving the port unmodified. The port runs: `-M:stream` exits 0, including
against a real loopback socket. Every rejection below is the checker refusing a
program that works. `-M:streamcheck` reproduces it.

**Round 1** — capability and lifecycle annotated: **7 of 8 rejected**, almost all
`untracked-borrow` / `untracked-consume` on ordinary helpers taking a connection.
Interprocedural flow is by annotation only, so an unannotated function's
parameters are opaque.

**Round 2** — four helper annotations added: **5 of 8 pass**, including
`line-client`, the whole client lifecycle. So the rule set's cost on real code is
**measurable, not fatal**: eight annotations for eight functions in a 250-line
namespace. That distinction is the reason for running two rounds.

#### Three survive, and one of them is new

**`with-conn` is `with-open`, and it cannot be expressed.** It takes `[c f]` and
calls `(f c)`. A higher-order function handing a capability to a function-valued
**parameter** has no signature to check against, so no annotation describes it;
its teardown is in a `finally`, which is `unsupported-construct` on top. This
appears in **none** of E15's blind spots, E17's or E18's nonclaims, or E20's
survey. E24 shows it is not merely an idiom: it is the shape of every reactor
callback.

**`read-lines` draws `join`** — the third data point for E6 probe 1, with the
finest cause yet. Not "conditional close": it **borrows** on the complete-reply
path and **consumes** on the truncated one. *"Borrows, except on the branch that
aborts"* is unsayable. §4.6's count is now zero on `perturb.nrepl`, fires on the
first HTTP keep-alive driver anyone would write, fires here.

**`serve-lines`** — `try`/`catch` again.

#### What the port cost, in the porter's own words

Recorded because it is independent evidence about perturb rather than about the
checker: `perturb.wire` has **no half-close**, so the very regression
`teensyp.stream`'s reactor arity exists to fix can be neither expressed nor
reproduced; `perturb.script` has no client-side handler taking arbitrary octets,
so the demo fabricates a posix transcript of a session that never happened;
`w/recv`'s empty view means both "end of stream" and "nothing queued", which the
original distinguished; `close!` is idempotent and unchecked in both directions,
so a double close and a missing close are equally silent; and `oconcat`/`osub`
make buffer compaction O(n) per chunk, quadratic in line length.

---

### E24 — the event-driven boundary: the guarantees moved, they did not survive

The hypothesis, from E23's fallout and from `echo-response` checking because it
is pure: *if every capability stays in a driver and application code is a pure
`(state, event) -> [state', effects]`, then user code checks clean with no
annotations and the whole cost of the capability discipline collapses into one
component.* Built as `perturb.evt` (driver, holding **two live connections at
once**), `perturb.evtapp` (pure application), `perturb.evtcheck`. `-M:evt` runs
both drivers under the scripted handler and over a real loopback socket, octets
identical. No rule was weakened; `check.clj` and `cap.clj` untouched; **zero
axioms and zero `:perturb.cap/representation` entries in either namespace.**

#### The hypothesis half-held, and the half that held is nearly vacuous

The pure application functions check clean with no annotations, as predicted. But
of four deliberately-wrong applications the checker **rejects two and accepts
two**:

| control | verdict |
| --- | --- |
| stashes the connection in app state | rejected — `escape`, `dangling` |
| returns the connection in an effect | rejected — `escape` at `ServerConn@:reading[1][0][1]`, followed through two nested vector literals |
| **two responses for one request** | **ACCEPTED** — and the run puts **139 octets, two complete responses, on the wire for one request** |
| **responds after closing** | **ACCEPTED** — the analogue of `write-after-move`, except the thing reused is an integer id |

And the two that *are* rejected have to **mint their own connection**, because an
unannotated function's parameters are opaque. There is no way to write "an
application that was handed a connection and misused it" — only "an application
that opened a socket." **The boundary is enforced by absence, not by a rule.**

#### The strongest result was not a control

`perturb.evtapp`'s `/wait` route defers a response by one round — the most
ordinary event-driven move there is, in the **working** application. Underneath,
it makes the driver read a second request from a connection that still owes a
response: `perturb.httpcorpus/read-twice-without-responding`, a recorded
`:typestate` rejection. Here it is accepted, it runs, and the only trace is that
the ledger stops joining up:

```
{:id "perturb-sconn-8", :was :responding, :claims :reading, :site :perturb.http/read-request}
{:id "perturb-sconn-8", :was :reading, :claims :responding, :site :perturb.http/respond}
```

**The capability discipline collapsed into one component and the protocol
obligations leaked into the other one, unguarded.** The pure-handler shape does
not remove typestate obligations; it relocates them somewhere nothing enforces
them — and where `perturb.http`'s own ledger cannot see them either, because
`respond!` records its `:from` as the literal `:responding`, so a violated edge
still reports the state it was supposed to start in. The contradiction is visible
only *between* consecutive entries.

#### What a connection table costs, measured

**A fixed-arity register file checks.** Two `ServerConn`s at `:arg 0`/`:arg 1`,
returned at `:at [0]`/`:at [1]`, carried through a loop at every back edge, live
alongside a `Listener`. **Two instances of the same capability alive at once had
never been checked before.** 47 lines, all clean.

**A map keyed by connection id — what a server actually needs — is rejected
function by function**, 38 lines across four functions (`no-signature`,
`untracked-consume` ×4, `dangling`). And the diagnostics say something worse than
"wrong state": once the capability enters the map the typestate axis is not
violated, it is **absent**. The checker does not know a connection is there.

Three isolation programs pin down the rules:

- **`accept-into-vector` draws the same diagnostics as the map version,
  character for character.** Choosing the composite the abstract domain models
  buys nothing: what it models is the vector **literal node**, not the vector.
  `conj` is an ordinary function and draws `no-signature`. The sharpest single
  fact in the experiment.
- **`table-grows-in-a-loop`** — `loop-not-preserving`. **A capability table
  cannot grow**; its arity must be a source constant, because the only way to add
  a slot is to write a wider literal.
- **`honour-close-effect`** — `join` ×2 plus `produces-mismatch`. **A value
  cannot select a capability.** Every effect is a decision made in data about a
  capability held elsewhere, so a checked driver structurally cannot honour
  `[:close id]`.

**Two clean verdicts that should be read as failures.** `serve-table-with-listener`
and `serve-table` check — *because every `ServerConn` became opaque inside
`accept-into-table` before control reached them*. A clean verdict on the function
that composes a driver is exactly what a hidden boundary looks like from above.

#### One root cause behind E23 and E24 both

A capability may only live in a `let`/`loop` binding **whose shape is known at
compile time**. Passing it to a function-valued parameter, storing it in a
collection, growing a collection of them, or selecting one with a runtime value
are all outside that, and an event-driven application needs all four.

#### E24's own nonclaims

1. **Nothing here says the architecture is wrong**, only that it does not
   preserve the guarantees it appears to. An instrumented driver (see
   `RUNTIME-OBLIGATION-BRIEF.md`) is a coherent answer; this measures the size of
   what would need instrumenting.
2. **No escape hatch was used, and one would have hidden everything.** A
   `ConnTable` capability with two `:perturb.cap/representation` entries makes all
   seven rejections vanish by making the bodies unread — the move
   `report-limits` item 1 calls gameable, producing a clean report about nothing.
3. **Single-threaded throughout.** Two connections held at once is not
   concurrency; the contention axis remains untested (I20).
4. The driver carries one hand-written runtime guard (`(nil? c)`) that makes
   `responds-after-closing` a no-op instead of a write to a closed socket. It is
   the only reason that accepted-wrong control does not reach the wire, and it is
   a use-after-close check implemented by hand because the static one stops at
   the map.

---

### E25 — the two papers in hand, read; and the reason §1.2 dropped regions does not apply to them

E20-VERIFICATION-BRIEF item 12. Both PDFs are committed under
`docs/research/papers/`, both CC-BY 4.0, and unlike the rest of Appendix D they
were readable without network. Both were read in full text. D.8 recorded two
mappings **as hypotheses**; both are substantially right and **both name the
wrong construct**, which changes what perturb would adopt.

#### Milano, Turcotti, Myers — *A Flexible Type System for Fearless Concurrency*, PLDI 2022

**(1) Yes, a region gives a connection table — and not for the reason D.8 gave.**
D.8 attributed it to tempered domination. The mechanism is simpler and stronger,
§1:

> intra-region references may freely link objects within the same region,
> **allowing programmers to easily form arbitrary object graphs**, while
> inter-region references are tracked by the type system and stored in
> appropriately annotated isolated fields.

A table of N connections held in **one** region needs no tracking at all —
intra-region references are unconstrained. That is the direct answer to
`accept-into-vector` and to the map version: the composite stops being something
the abstract domain must model, because the *region*, not the binding, is the
unit of ownership. **Tempered domination buys something else** — it relaxes
domination for *tracked* `iso` fields, which is what the doubly-linked-list
examples need, i.e. moving things *between* regions. The distinction matters
because it says what perturb would have to build first: a region discipline, of
which tempered domination is a later refinement, not the entry price.

**(2) Growth and reassignment: yes, explicitly, and with no annotation.** §4.4:

> This tracking context also allows iso fields to be **freely reassigned, even
> if doing so would create cycles** in the object graph. This is safe because
> tempered domination requires domination only on untracked iso fields; fields
> explicitly mentioned in H are exempt. ... T7 - Isolated-Field-Assignment ...
> **places no restrictions on 𝑒** beyond ensuring that it type-checks.

And §1: the guarantee holds "without requiring any annotations from the
programmer **except at function boundaries**". `table-grows-in-a-loop` was
rejected because "the only way to add a slot is to write a wider literal"; here
the loop body is ordinary intra-region mutation.

**(3) `Send`/`Receive` and the contention axis — the most transferable result in
the paper.** §4.4:

> empty tracking contexts prove that **every iso field within that region
> contains a dominating reference**, and thus is safe to transmit between
> threads via **T16 - Send (which requires an empty context)** and **T17 -
> Receive (which assumes one)**.

That is a precise statement of the question §1.2's contention axis asks and has
never tested (I20): *does an owner survive a thread fork?* The answer is **iff,
at the fork, nothing it transitively owns is focused** — no borrow, no
outstanding exemption. perturb's universal `:thread-confined` is the degenerate
instance where the answer is fixed at "no". The typing rules are the shape a real
answer would take, and they cost a notion of region perturb does not have.

**(4) `if disconnected` is a runtime test discharging a static property — and it
is weaker than `RUNTIME-OBLIGATION-BRIEF.md` needs, in an instructive way.**
The dynamic rules (Fig. 7, E15a/E15b) test
`tracked-set(x) ∩ tracked-set(y) = ∅` and branch on it. The typing rule T15
(Fig. 10) is where the work happens: in the **success** branch `x` and `y` are
retyped into **two distinct regions** `r_x` and `r_y`; in the **failure** branch
both remain in the single region `r`. A run-time check splits a region, statically.

Two qualifications the brief should carry:

- **Nothing is accepted that would otherwise be rejected.** Both branches are
  well-typed and both must be written; the dynamic test only selects which
  well-typed continuation runs, and it **fails conservatively** ("conservatively
  assuming that they remain connected if the counts do not match"). That is a
  *refinement of a static approximation with a total fallback*, which is
  materially weaker than the brief's second row — "accepted, carrying a residual
  obligation the runtime discharges". **The design lesson is the fallback**: a
  runtime test may license a stronger static conclusion only if the negative
  branch is itself typable and written. Applied to `RUNTIME-OBLIGATION-BRIEF`,
  that is an argument for the *third* row (instrument the axioms) and against the
  second (accept the refused programs) — the refused Content-Length program has
  no typable else-branch to fall into.
- **The cost is argued, not measured.** §5 says "we **propose** a two-step
  process", the check is a reference-count-plus-interleaved-traversal, and the
  worst case is explicit: "in the worst case, this check may involve traversing
  an entire region of arbitrary size." No benchmark is reported. Under this
  document's standing method commitment that is `assumed`, not `measured`, and
  D.8 should not be read as supplying a cost datum.
- Incidentally, T15's two premises share one conclusion context — both branches
  must produce the same `H′; Γ′`. That is **a fourth independent instance of the
  linear join rule** (E21 claim 1), in a system that is not linear.

**(5) The decisive question — does D4's no-resumption argument extend to regions?
The paper cannot answer it, and §1.2's stated reason is inapplicable anyway.**

The paper offers no evidence, because its core language has **no non-local
control of any kind**. The syntax (Fig. 6) is locations, variables, sequencing,
field read/assign, assignment, calls, binary ops, `new`, `declare`, `if`,
`while`, `send`/`recv`, `if disconnected`, and the `maybe` forms. No exceptions,
no handlers, no continuations. Whatever the interaction is, this system does not
exhibit it.

**But §1.2's reason for dropping locality does not apply to a region system of
this kind, and that is a correction rather than a quibble.** §1.2 says regions
are "the specific feature that makes effects unsound (cf. Yarrow: **non-local
control breaks stack discipline**; multi-shot handlers break exit-at-most-once)".
That objection is about *region-based memory management* in the Tofte–Talpin
sense, where a region has a **lexical scope and a stack lifetime** that a
non-local exit can skip. Milano's regions have no scope at all: they are a
partition of the heap induced by domination, and §1 states that they "can grow
and shrink dynamically as threads exchange portions of the object graph". There
is no region frame for control to escape. Two systems, one word. **New tally row
36:** §1.2's conclusion may still stand, but the reason recorded for it does not
transfer — the same shape as row 5.

**And D.8's escape hatch does not exist, by the mechanism E21 had just
corrected.** D.8's tension paragraph says "E20 found that D4's no-resumption rule
may remove the reason regions were dropped". Read that against tally row 30.
Yarrow's objection is that **non-local control** breaks stack discipline — and a
non-local exit *is* a discarded continuation, which is precisely the half of the
handler/linearity mismatch that D4 **does not** remove. D4 removes multi-shot
resumption; `abort!` is retained by design (§1.4). So the argument D.8 leans on
would not have licensed regions even if Yarrow's objection did apply. Two
independent defects in one sentence: the cited objection targets a different kind
of region, *and* the offered escape from it covers the wrong half. **The region
question is harder than D.8 states, not easier.**

**What *does* transfer is E21's problem, not Yarrow's — and it is the same
problem.** The tracking context H is flow-sensitive; function requirements are
"only checked at the beginning and end of each function body", and `Send`
requires emptiness. A non-local exit that skips the point where a focus is
discharged leaves `iso` fields tracked and the domination exemption outstanding.
That is the discarded-continuation failure of E21 rows 30 and 31 in a third
setting — linear contexts, tracking contexts and typestate all being
flow-sensitive state that a non-local exit can strand. **So adopting regions
would not introduce a new soundness problem; it would enlarge the one §4.6
already has open on the `abort!` path**, and it would enlarge it in the one
direction perturb has no mechanism for. That makes the region item and the
cancellation item **one item, and the cancellation item is the prerequisite** —
E21's claim 3 work (a `cancel` form, a live set at each `abort!`, propagation)
is what a region discipline would need in order to be sound in a language that
keeps exceptions. Sequencing recorded: **`abort!` first, regions after.**

#### Laddad, Cheung, Hellerstein, Milano — *Flo*, POPL 2025

**The mapping is right and D.8 named the wrong level.** Flo has three, and E23's
defect is at the bottom one, which D.8 did not mention:

1. **Values.** §6: `S<V> ≜ {[v₁,…,vₙ]} ∪ {[⊗, v₁,…,vₙ]}`, where "the terminator
   symbol ⊗ indicates the end of a stream", with the laws
   `[⊗,…] ++ x = [⊗,…]` (absorbing) and `c ++ ∅ = c`. **An empty stream and a
   terminated stream are different values.**
2. **A predicate.** §3.2: `fixed(c) ≜ ∀c′ ∈ C. c ++ c′ = c` — "identifies a
   collection value such that **no more data can be added to it**".
3. **A type.** §3.3: a boundedness flag, `Bounded | Unbounded`, capturing
   "whether a collection value will **eventually** become fixed, or if it may
   never become that".

E23 found that `perturb.wire`'s `recv` returns an empty octet view for both
end-of-stream and nothing-queued. That is a collapse of `∅` into `fix(∅)` at
**level 1**. The principled form of the fix is a sum on `recv`'s result and the
`⊗`-absorbing algebra — not the boundedness flag, which is a separate and later
discipline. **New tally row 37.**

**Boundedness is still worth having, for a different job**, stated in §1:

> Operators can only **block** on bounded streams, and must always make progress
> with respect to unbounded streams.

That is a licence-to-block discipline, and it maps onto perturb cleanly: a
**Content-Length body is Bounded**, a **keep-alive connection is Unbounded**.
Note where that lands — E19's `(= written declared)` refinement on
`ResponseBody`'s terminal edge is precisely a witness that the body stream
becomes fixed, so §1.3's arithmetic and Flo's boundedness flag are the same
obligation stated twice. That is a genuine unification and it was not visible
from either side alone.

**Nested graphs with cycles do cover the keep-alive shape**, and Flo's own
motivating example is the same shape: §2 introduces `nest` exactly because "if
input is unbounded, each inner argument to nest is bounded, and hence can be
passed into fold". An HTTP server is an **unbounded** stream of connections, each
carrying a **bounded** stream of request bodies — outer U, inner B, which is
`nest`'s signature. The composition law perturb would adopt for framing layers is
therefore not new machinery; it is that a layer is an operator whose input and
output boundedness flags compose, with `nest` the constructor that re-bounds an
unbounded stream.

#### E25's own nonclaims

1. **Nothing was executed and nothing was built.** This is a reading of two
   papers against E23/E24's measurements, in the E21 mould. Whether a region
   discipline is implementable over Jolt IR is untested, and §1.1's `:extern`
   pessimism applies with full force to a claim this large.
2. **The region result is about *expressiveness*, not about perturb's checker.**
   Milano's system admits a connection table because ownership is per-region;
   nothing here says perturb's `[capability operation]` machinery could be
   retrofitted onto regions, or what it would cost to try.
3. **No cost number was obtained for `if disconnected`.** See above: the paper
   proposes an implementation and argues its complexity. `RUNTIME-OBLIGATION-BRIEF`
   item 5's cost question is **not** answered by this paper.
4. **Flo is a semantics, not an implementation perturb can adopt.** It is
   parameterised over collections and operators; instantiating it for octet
   streams over a descriptor is the work, and the paper's instantiations are
   Flink, LVars and DBSP, none of which is a byte-oriented transport.
5. **The `nest` mapping is structural, not verified.** That HTTP keep-alive has
   the shape `nest` is built for is an observation about signatures. No Flo
   program was written and no property was checked.

## 4. Open questions

Q1–Q5 are §4.1–§4.5; §4.6 collects open items that never carried a Q number.

### Q1 — the residual byte-path gap: no layout change, and the target has moved twice

**Status: downgraded from blocking. The layout question is closed; the
performance question is open and has been re-aimed twice.**

#### The layout question — closed, resolution (b), no remint

`jrdesc` is `(fields tag fkeys index (mutable ptable))`,
`(nongenerative chez-jrdesc-v3)`. A descriptor-local eq-keyed method cache
therefore **already exists**, with invalidation already wired: `ptable` is `#f`
until the first `register-protocol-method`, and a stale pre-redef descriptor has
it reset to `#f` so lookups fall back to the string registry. It is keyed by
`intern-pm-key proto method`.

The collection fallback cannot use it only because `rec-coll-method` knows the
method name but not the supplying protocol. Two resolutions were identified:

- (a) add a method-name-keyed table → `chez-jrdesc-v4`, a remint;
- (b) store the any-protocol resolution in the **existing** ptable under a
  reserved pseudo-protocol key (`intern-pm-key` with a sentinel proto name that
  cannot collide) → no field added, no version bump.

Under (b) the path is `jrec-desc` (immutable field read) → `jrdesc-ptable`
(field read) → one eq-hashtable lookup on a **module-level precomputed interned
key**. No string hashing remains.

Sub-questions carried: `intern-pm-key` must not hash per call, which is why keys
for the collection methods (`nth`, `count`, `assoc`, `cons`, `seq`) are minted
once at module scope; and host tags (`"String"`, `"Object"`) have no desc, so
the string-registry fallback must remain for them.

**Resolution (b) was implemented** — the flat table is cached in the
descriptor's own `ptable` under a reserved gensym key, no `chez-jrdesc-v3`
bump, no remint. A weak side table keyed by descriptor measured within noise
(836 vs 863 ns/byte at `380e59e`); the ptable form was kept for its structural
properties. E9's noise floor later reclassifies the 1009 → 863 improvement as
marginal; the commit stands on the structural properties, which were the stated
reason for keeping it.

#### The performance question — re-aimed twice, currently on representation

At the time (b) landed, the remaining sub-question was stated as "whether
closing the rest requires backend devirtualization", **answered yes**. E7 then
refuted that: devirtualization is worth ~3%, the work item is **retired**, and
74% of the cost is the method body. E8 then located most of the body cost in
`unchecked-byte`, which E9 fixed at the primitive level.

What is open now, none of it attempted:

- whether deftype method bodies can get **unboxed fixnum arithmetic and direct
  array indexing** (E7's redirection — not investigated);
- a **`^bytes` typed fast path** for `aget`, analogous to `jolt-flaget`, whose
  insertion points E11 names exactly (`passes/numeric.clj:170`,
  `backend_scheme.clj:838`, a native beside `jolt-flaget` in
  `natives-array.ss`, a gate mirroring `run-flarr.ss`);
- switching the byte-array backing to a Chez **bytevector** (E11 — 8 bytes per
  element today), which touches `na-byte-array`, `ja-set!`, the `aset` path,
  seq/reduce over arrays, and the FFI byte-array interop including the
  `borrow-byte-array`/`release-byte-array` loan contract jolt-sim depends on;
- E12's three semantics that must be decided rather than discovered before that
  backing changes: cross-kind array equality, write range-checking, and
  signed-versus-unsigned — the last of which "must be verified empirically
  rather than assumed".

### Q2 — refinements over HM: not a gradual-typing boundary; both risks now closed

**Status: closed. Both risks addressed — `Any` by confinement, higher-order by
E13 — with the residue recorded rather than resolved.**

The original framing was wrong. Gradual typing's difficulties arise from mixing
typed and untyped code; here everything is statically typed and an unrefined
value is refined by `true` — a lattice with a top element, not a boundary.

The established mechanism is **liquid types**: HM inference plus refinements
inferred by predicate abstraction over a fixed qualifier set. Two properties
make the fit unusually close: liquid typing requires a decidable logic, normally
QF-LIA with uninterpreted functions, and every refinement in E3 is already
written in exactly that fragment in the existing `.smt2` models; and Z3 is
already a toolchain dependency via `bin/verify-models`.

The real difficulty is different: **content refinements over mutable state are
unsound under aliasing**, and `Window` is precisely an immutable descriptor over
mutable aliased storage. Uniqueness resolves this rather than complicating it —
permit content refinements only on `unique` capabilities, so no alias can
invalidate them. This is a synergy between the two axes, and independent
corroboration of the §1.2 tier split.

Two risks were left open: *"the `Any` escape hatch requires runtime checks where
refined meets unrefined; inference for higher-order and polymorphic-recursive
code is where liquid typing is least comfortable in practice."* Both are now
addressed — the higher-order half was recorded as "under test (`prototypes/`, in
progress)" and E13 is that test:

**`Any` closes by confinement.** §1.2 gives ordinary values inference plus an
`Any` escape hatch, and the standard worry is that refined-meets-unrefined needs
runtime checks or leaks soundness. But refinements live only on the capability
tier (§1.3), so the dangerous case is not `Any` in the value tier — it is `Any`
reaching a *capability* position. Prohibit that: **a capability position never
accepts `Any`.** There is then no refined/unrefined boundary to be unsound at.
The cost looks nil, because capabilities are **minted by operations**
(`Net/connect`, `acquire-native`, `cursor`) rather than parsed from data, so
they arrive typed by construction. Deserialisation cannot produce one.

**Higher-order splits.** The sans-io driver types at a fixed refinement;
transducers over refined elements do not and need abstract refinements of arity
≥ 2 (E13). Inference at the combinator boundary is Horn-clause constraint
solving, not HM unification. The residue is recorded in §1.2's conditional
confinement statement and in E13's nonclaims — in particular that E13 checks
hand-written annotations and infers nothing.

### Q3 — `unique` × multi-shot: under-specified, and not currently load-bearing

**Status: not blocking. §1.4 defers D3; Q3 becomes load-bearing only if D3 is
taken up.**

The stated rule ("a `many` continuation may not capture `unique` resources in
its frames") is wrong at two levels.

**Granularity.** A continuation captures the stack to its prompt, and a `unique`
value *reachable from* a captured frame — via a collection, closure environment,
or record field — is duplicated on second resume exactly as one held directly.
The property is reachability over the captured region, not frame membership, and
must stay sound through closures and calls into unknown code.

**Formulation.** This is the linearity-versus-control tension. Known-good shapes
are linear continuations (Filinski), which reduce to one-shot, or a
region/effect discipline preventing linear values crossing the prompt (Yarrow,
for regions). The formulation to try instead: **place the obligation on the
prompt, not the capture** — a `many` prompt requires its delimited body to be
`unique`-free. Checkable where the prompt is written, expressible as an
effect-row obligation, and composable with the rest of the checker.

**Satisfiability in the intended use:** search continuations range over the
kernel world, which P4 requires to be value-semantic and therefore free of
unique capabilities, so backtracking search sits inside the rule. Rejected is
application code holding a unique handle across a `choose` point — arguably
correct, since resuming twice would duplicate the handle, but it needs checking
against real scenarios: if it rejects too much, direct-style D3 code is unusable
exactly where it is wanted.

### Q4 — macro provenance: mechanism specified, reconstruction deferred

**Status: open. A deferred scope decision plus one unsettled research problem
(blame), with a proposed resolution not yet tested.**

Charter Appendix A.6 defines **expansion parent (single-step)**: each NF node
records the immediate expansion step that introduced it — the role path of the
macro call site in the pre-expansion form, or `nil` for a source-written node
(realization: `V17/jolt-core/jolt/analyzer.clj:961-973`,
`V17/host/chez/host-contract.ss:236-253`). But the full expansion-parent chain
is "reconstructible by following links and is **not** part of v1 (F1)."

Diagnostics need the chain, not the step: reporting against user-written code
means walking parents outward to a `nil` parent, which one link does not reach
through nested expansion. So this is a deferred scope decision, not a research
problem.

What remains hard after the chain exists is **blame**, which provenance alone
does not settle — Racket carries source location and lexical context through
expansion and Typed Racket still has this problem. Three cases the chain cannot
separate: the caller passed something ill-typed (report at the use site); the
macro generates ill-typed code from its own literals (the use site is a
misleading target); nested expansion where the useful frame is neither outermost
nor innermost.

Proposed resolution: **macros used in typed positions declare input and output
type schemas.** A use-site mismatch is then caught before expansion and reported
where the user wrote it, and a type error in the expanded body is attributable
to the macro, whose declared output schema it violated. This converts an
unattributable error into one of two attributable ones.

### Q5 — is `clojure.*` deferred or optional?

**Status: closed. Neither — the no-foreclosure rule is a defeasible default.**

The question as posed: "Not an initial priority" implies later, and the
no-foreclosure rule is kept on that reading. If exact compatibility is
ultimately *not wanted*, the rule can be dropped and the core gains real freedom
— laziness, equality, numeric tower, and error model all have cheaper designs
without it. Worth settling before the core's semantics are far enough along that
the answer stops being free.

The resolution is a third posture: no-foreclosure is a **goal and a rule, held
by default, breakable later for a sufficiently interesting gain.** That changes
the mitigation, because the risk is no longer "compat silently becomes
impossible" — it is **"compat gets broken by drift rather than by decision."**
The mechanism that makes the rule defeasible on purpose is the divergence
register's third column; see §1.6 and §2.

### 4.6 Open items not carrying a Q number

Recorded here so they are not lost between sections. None of these is decided.

- **The structural/inductive tier.** QF-LIA cannot reach tree invariants; how
  Ansatz obligations integrate with the two typed tiers is not designed (E3).
- **Liveness.** P4's bounded response is a seed, not a mechanism. It needs
  fairness assumptions and either bounded response or a temporal logic, and
  P4's own approval explicitly is "not a SAT/UNSAT result" (E3).
- **Agreement/consensus.** Multi-node safety, refinement against a spec, and
  liveness under partition remain untested by the current ladder. Still only a
  ladder entry (§5, step 5).
- **A capability may only live in a binding of statically-known shape.** The
  single root cause behind E23 and E24, measured from four directions: it cannot
  be passed to a **function-valued parameter** (`with-conn`, and every reactor
  callback), stored in a **collection** (map and vector are identical here — the
  abstract domain models the vector *literal node*, not the vector), held in a
  collection that **grows** (arity must be a source constant), or **selected by a
  runtime value** (`join`). An event-driven application needs all four, so this
  now sits above the module boundary in the queue. Whether the answer is a
  language feature (handle table, existentials, capability regions) or a
  permanently instrumented trusted core is **undecided** and is the live fork.

  **E25 read the candidate and the language-feature arm now has a concrete
  design.** Milano, Turcotti & Myers (PLDI 2022) admit all four shapes, and the
  mechanism is not the one D.8 guessed: **intra-region references are untracked
  altogether** — "intra-region references may freely link objects within the
  same region, allowing programmers to easily form arbitrary object graphs" —
  so a connection table is one region and needs no annotation. Growth and
  reassignment are explicit (T7 places "no restrictions on 𝑒"), including
  cycles. Tempered domination is a *later* refinement for moving objects between
  regions, not the entry price. Two things this costs, both now named rather
  than guessed: a region discipline is a second ownership notion beside §1.2's
  four axes, and its `Send`/`Receive` rules are the first precise statement
  anything has offered of the contention axis (I20) — an owner survives a fork
  **iff its region's tracking context is empty**. And one objection is now known
  to be the wrong objection: §1.2 ruled regions out citing Yarrow's stack
  discipline, which is about *memory* regions and does not apply here (tally row
  36). The real hazard is the `abort!` gap, which is already on this list, so
  the two items are one.
- **Higher-order capability passing has no notation at all.** Not in E15's blind
  spots, not in E17's or E18's nonclaims, not in E20's survey — found only by
  checking code perturb did not write (E23). `(f c)` cannot be annotated because
  the callee is a parameter.
- **The join-rule usability risk.** E6 probe 1's rejection of
  `if (c) { b = detach_result(b) }; use(b)` is a real usability risk for §1.2,
  and how often it fires on real programs is argued rather than measured. Two
  data points now exist and they disagree: zero times on `perturb.nrepl` (E15),
  on the first driver anyone would write for HTTP keep-alive (E18), on
  `read-lines` in code perturb did not write (E23, cause: borrows on one path and
  consumes on the other), and on `honour-close-effect` in an event-driven driver
  (E24, cause: a value selecting a capability). The
  accepted rewrite — close inside the branch, `recur` in the other, so one arm
  is bottom — works and is not discoverable from the diagnostic.
  **E20 corrects the framing and confirms the rule.** Session types do NOT
  dissolve this: the linear conditional rule types both branches in the same
  context, so perturb's join rule *is* the linear rule. What session types buy
  is that the accepted rewrite becomes the *only* writable idiom — a pedagogy
  fix. Three mitigations now have names: reify the decision as a **state**
  (h11's `MUST_CLOSE`, adoptable today with no new theory), a **sum state
  requiring a case-split** (§1.2's own unexplored option, which is the
  session-typed answer), or a **runtime drop flag** (Rust RFC 320). Also: E20
  found no published measurement of how often this fires, so perturb's two data
  points are better evidence than anything in the literature.
  **E21 confirms the rule from the source and qualifies the last sentence.**
  Vasconcelos's *algorithmic* rule [A-If] is sharper than E20 reported: both
  branches take the same input context **and must return the same output context
  and the same used-set**, which is an equality on the residual — perturb's join
  rule exactly. [T-Branch] is the same rule for &, "the exact same Γ₂ in all
  cases ... similarly to rule for the conditional process", so ⊕/& really do not
  dissolve it. Two things E20 did not have:
  - **A fourth mitigation, and the only one that removes the rule.** Doré,
    *Dependent Multiplicities in Dependent Linear Type Theory* (arXiv
    2507.08759), is built to dissolve exactly this, and names it: "Both elements
    of type A have to be constructed from the same resources, **which is a steep
    ask: different if-then-else branches in a program will generally do
    different things**". Value-dependent multiplicities let the branches differ.
    The cost is that type checking becomes "vastly undecidable" and discharge
    moves to hand-written proof terms (tally row 32). An option to record, not
    to take.
  - **The frequency is still unmeasured; the usability cost is not.** *A
    Grounded Conceptual Model for Ownership Types in Rust* (arXiv 2309.04134):
    participants predicted the compiler's reason for rejection in 78% of cases
    but could only **fix** the program in **46%**, and construct a
    counterexample in 31%. "The accepted rewrite is not discoverable from the
    diagnostic" is a documented failure mode with a number on it. perturb's two
    data points remain the better evidence *on frequency* and only on frequency.
  - And a third corroboration, in a Lisp: Turnstile's `linear/lin.rkt` raises
    `"linear variable may be unused in certain branches"` from a macro-hosted
    linear type checker (E21).
- **~~`:local`~~ and `:extern`.** §1.1's two IR claims were inferences from
  source reading. **`:local` is now settled**: a checker walked real IR and the
  claim holds — names, no binding identity, no `:binding-id` key, no
  alpha-renaming (E15). `:extern` is still untested, and the standing pessimism
  applies to it: this session's record on inferences from source reading is
  poor. The `jolt-array` survey was wrong on scale *and* kind (E12), E3's
  central finding was sample-biased, three performance hypotheses died to
  measurement (E1, E7), and I11's `defcfn` premise was wrong (E16).
- **~~Positioned capability specs~~ DONE** (E17). Replaced at the top of the
  §1.2 queue by:
- **A module boundary.** `:perturb.cap/representation` names the operations
  inside a capability's implementation by listing them. It wants to be a scope.
  §1.2 has no module concept, and every operation added to that list is a body
  that stops being checked (E17). **And the list is gameable:** `perturb.http`
  has an empty one for each of three capabilities and 31 unchecked concrete-map
  accesses against `perturb.nrepl`'s 5-entry list and 12 accesses, because the
  accesses were written inside transition bodies instead of helpers. Lines below
  the boundary is the metric; names in a list is not (E18).

  **E20: this is the top item in all three literature surveys, and it is not a
  type-theory problem** — every system surveyed inherits its host's module
  system, and perturb has no host to inherit one from (Clojure's `:private` is
  intent, not enforcement: `@#'ns/private-var` works). Four things the surveys
  add. (i) The answer is sealing — Mitchell & Plotkin 1988, elaborating to
  System Fω — but sealing **relocates** trust from a list to a scope rather than
  discharging it; RustBelt is the architecture that discharges it, where each
  entry names an *obligation* instead of granting an *exemption*. (ii) In a Lisp
  whose checker reads post-macroexpansion IR, "inside the implementation" can be
  an **owner namespace read off the IR** rather than declared, because a
  keyword's namespace is in the keyword; that is one of very few properties
  surviving macroexpansion without provenance, which argues for doing it before
  Q4. (iii) Sealing does not merely preserve linearity, it **creates** it — Alms
  (POPL 2011) lets an interface impose stiffer restrictions than its
  implementation, so the seal is what turns a map into a capability. (iv) The
  case against: per-type sealing handles **rights amplification** badly, and the
  three operations that advance two machines are exactly that shape, so ML-style
  sealing would push toward one coarser seal; Modula-3's partial revelation and
  Morris's sealer/unsealer pairs fit better. See Appendix D.5.
  **Independent corroboration of the gameability finding:** `cargo-geiger` has
  the same defect in mirror image and has shipped with it since 2019.

  **E21 read (i), (iii) and the Vault citation, and all three survive with a
  design attached.** Alms's abstract is verbatim what E20 reported — "an
  interface can impose stiffer resource usage restrictions than the principal
  usage restrictions of its implementation" — and §2 states what makes it sound:
  a qualifier lattice with **U a subkind of A**, sealing permitted to move *up*
  it only, plus a principal-kinding theorem so that "principal usage
  restrictions of its implementation" is well defined. §2's `CAP_ARRAY` is the
  shape this item wants: split the *reference* (unlimited) from the *capability*
  (affine) and tie them with an existential **stamp**, so "the stamp on an array
  can only match the stamp on the capability created by the same call to `new`".
  Two consequences worth carrying: the capability is represented by `unit` and
  **erased** — "these capabilities have no run-time significance" — which any
  ledger argument premised on run-time capabilities must reckon with; and
  RustBelt's per-library obligations are discharged in Coq against a semantic
  model of the whole language, so "names an obligation instead of granting an
  exemption" is an architecture whose price is a machine-checked logical
  relation, not a cheaper annotation. **Vault already has the missing
  construct**: its *type guards* attach keys to **data**, not only to
  operations — "at a given program point, all the object's keys must be in the
  held-key set in order for the program to access the data object" — which is
  "you may touch the connection's concrete map only while holding the
  Connection's key", stated inside the capability system rather than as a list of
  exempt names. It also parameterises types by key sets, which is the
  `unpositioned-two-cap-helper` shape. Both are compile-time only.
- **~~An operation that advances two machines cannot be declared~~ CLOSED**
  (E19). Keyed `[capability operation]`; three such operations, not two; and
  E18's "nothing here is a false accept" was wrong for the borrow+produce case.
- **~~A creating operation has no `:from`~~ DECIDED** (E19): a machine has no
  pre-creation state. `:created` is deleted and a creating edge declares
  `:from nil`, because a state with no inhabitants and no outgoing edge is a
  name for the machine's absence, which `nil` already denotes.
- **~~A state cannot carry a refinement~~ HALF CLOSED** (E19). A transition
  carries one; the short body is rejected. What remains: no refinement crosses a
  **loop** or **function** boundary and there is **no invariant syntax** to
  supply one, so a body written in a data-dependent loop is REFUSED, not
  decided. The cross-capability ordering obligation
  (`body-finished-before-conn-reused`) is untouched — and E20 says it is not a
  logic problem: make `body-finish!` *consume* the Body and *produce* the Conn,
  and the affine discipline enforces the order at zero solver cost (Vault, PLDI
  2001). E19 made that declarable; nothing has used it yet.
  **E21 costs the loop/function gap.** Flux (PLDI 2023) is confirmed on every
  conjunct E20 claimed for it, including inferred loop invariants — "Flux uses
  the Liquid Fixpoint horn constraint solver ... enabling Flux to automatically
  synthesize loop invariants". Reaching that class needs three things E19 does
  not have: an **SMT backend** (Flux rests on "decidable (quantifier free)
  validity queries"), a **Horn-clause solver over refinement variables** (Flux's
  κ variables are E13's abstract refinements arriving from the other direction),
  and **indexed locations** so state and refinement are one thing rather than a
  transition-plus-ghost-state pair. That is conventional, well-understood work
  with a shipped system at the end of it — and it is the opposite direction from
  E19's "it is not a solver, and the report says so unprompted". Both are
  defensible; they are not compatible, and the record should not imply they are.
  Separately, E21 confirms that E19's *placement* genuinely dodges Rast's
  undecidability rather than dodging it by luck: Das & Pfenning's reduction needs
  arithmetic refinements **and recursion in the language of types**, and E19 has
  no recursive refined type for a type-equality question to be asked about.
- **The `abort!` path has no cancellation obligation. Now specified, and bigger
  than E20 priced it (E21).** E15 blind spot 4 is a soundness gap, not a
  coverage gap: a non-local exit past a live capability leaks it. E20 recorded
  the recipe as "weaken linearity to affinity **plus** explicit cancellation —
  perturb has the affinity". **That is backwards.** Fowler et al. §1.3 rejects
  affine types by name, because they permit *silent* discard: "a developer
  receives no feedback if they accidentally forget to finish a protocol
  implementation", and "if an exception is raised in an evaluation context that
  captures an open endpoint then the peer may be left waiting forever". §1.4 is
  titled "**Linear** Types with Explicit Cancellation" and warns against
  confusing the two. Linearity is *kept*; `cancel` is a term that **uses** the
  endpoint (T-Cancel: `Γ ⊢ M : S` ⟹ `Γ ⊢ cancel M : 1`). E6's affine binding is
  the rejected option, so perturb has neither half. Four pieces, in dependency
  order (E21 claim 3):
  1. a `cancel` form per capability class, typed as a consuming use, so that
     *cancelled* and *leaked* become different verdicts — small, best ratio;
  2. a live-capability set at every `abort!` site, discharged either by
     requiring the programmer to write the cancels or by inserting them (the
     latter is what EGV's `E-Raise` does: it cancels "all endpoints in the
     enclosing pure context");
  3. propagation into composites — EGV's `E-Zap` cancels transitively through
     buffered values; perturb's abstract domain has one composite today (a
     tuple) and will not keep it;
  4. peer notification **only where a peer exists** — this is where the
     transplant stops. EGV endpoints always have a counterparty and the
     `⨸`/raise machinery exists to stop it hanging; a perturb buffer or cursor
     has none. What survives for `ServerConn` is the *obligation* to leave the
     wire consistent, which is an axiom, not a typing rule.

  Still the highest-value item the queue did not contain (Appendix D.3), and now
  the one item on this list with a written specification.
- **A dynamic join is an unexplored option.** §1.2's "no sound join exists" is
  true for a fully static, zero-runtime-state discipline and false in general —
  Rust carries a runtime **drop flag** (RFC 320). A `:maybe-moved` mode
  discharged by a compiler-inserted conditional close is the only join
  mitigation in E20's survey with production evidence. Not a decision; an option
  that was not on the list.
- **`:borrows` + `:produces` of the same capability duplicates it.** A legal,
  natural annotation ("here is your listener back") mints a second abstract
  capability for one runtime object; the false leak is reported at the caller
  and nothing points at the annotation (E18).
- **The unpositioned fallback should be removed, not documented.** With one
  capability, matching specs to parameters in order is unprincipled. With two it
  binds the wrong parameter to the wrong capability and produces five
  diagnostics, none of which names the annotation (E18; E17 nonclaim 4).
- **The contention axis has never been exercised.** All four capabilities in
  perturb declare `:thread-confined`, there is no scheduler perturb can drive
  (INHERITED I20), and no rule has seen a capability cross a task boundary.
  Everything above step 1 of §5's ladder needs this (E18).
- **Axioms — now two classes, and the set grew.** Nothing checks that a declared
  transition's body performs its transition, and nothing checks the five
  representation operations either — including `read-frame`, which is E4's whole
  sans-io driver. 8 of `perturb.nrepl`'s 15 functions. Unbroken since
  `mode_checker.py` for the first class; the second was added by E17 to make the
  client pass, which is defensible per operation and dangerous as a habit.
- **Tuple eliminators are a closed set of three.** `first`, `second`, `nth` with
  a constant. Destructuring or a computed index silently loses a capability to
  opaque — no diagnostic. Most likely place a false accept hides today (E17).
- **Namespace loading is not an effect.** I11 is closed by arrangement, not by
  design (E16). Nothing prevents the next namespace from doing I/O at load time.
- **The performance items under Q1** — unboxed deftype method bodies, a
  `^bytes` fast path, bytevector backing, and E12's three semantics.
- **Two dangling internal references, left unrepaired.** E9's noise-floor table
  cites `§5, "full decode unchanged at ~370 µs"`, and E12 cites a survey figure
  "recorded in §12" — neither phrase appears in the §5 or §12 text this document
  retains; both were session-narrative figures. The rows are preserved verbatim
  rather than corrected, because correcting them would mean deciding what the
  original measurement was.
- **Two sections shared the number 16** in the chronological record (the E3
  sampling-bias section and the E13 section), so bare `§16` references in
  historical text are ambiguous. Appendix C resolves each occurrence by content.
  No claim was changed to disambiguate them.

---

## 5. v0 ladder — jolt-tcp and jolt-http

`casselc/jolt-tcp` (teensyp-compatible TCP server over `jolt.net`'s readiness
reactor) and `casselc/jolt-http` (Capra-style HTTP/1.1 over it) are intended
perturb stdlib. Both already have implementations *and* independent oracles,
which makes them better targets than the two earlier ladders (Appendix B).

### The ladder

1. **jolt-tcp connection lifecycle** — typestate and the serial-per-connection
   guarantee, already stated in the README so the contract exists to check.
2. **jolt-http keep-alive/pipelining** — ordering, exactly-once, no-leftover,
   against the existing RFC-derived oracle.
3. **SSE** — liveness, resumption, reconnect faults.
4. **Persistent collections** — structural/inductive; nothing above touches it.
5. **Leader election** — agreement; still required, still open.

This replaces the earlier nREPL-first ordering. nREPL remains a good middleware
target for E13's abstract-refinement question, but jolt-tcp/jolt-http reach the
temporal class sooner and with oracles already built.

Gate architecture for all of it is §1.7.

### What they cover

| property | where | class |
| --- | --- | --- |
| per-connection serial order — "accept first, close last, reads sequential", writes ordered | jolt-tcp README contract | resource safety + **temporal** |
| connection lifecycle, `stop-server`/`with-open` | jolt-tcp | typestate on a real resource |
| **keep-alive and pipelining** — N requests in, N responses out, in order, exactly once, nothing left over | jolt-http + its RFC-9112 oracle | **temporal**, strongest available |
| chunked and streaming bodies | jolt-http | framing with termination |
| async handlers | jolt-http | concurrency and ordering |
| buffer bounds — "position non-negative", "unread bytes moved intact", "src/dest advanced by copied" | jolt-tcp property tests | resource safety, E3 shape |

**Pipelining is the strongest temporal target available.** Duplication,
splitting, reordering and leftover bytes are all expressible against an oracle
that already exists, and async handlers are where ordering can actually break.
Codecs have no analogue.

### SSE fits on top, not beside

jolt-http already supports streaming bodies, so SSE is an extension rather than
new infrastructure. It adds what nothing else here has: **liveness under fault**
(every published event eventually delivered, given eventual connectivity) and
**`Last-Event-ID` resumption**, whose properties — contiguous prefix, order
preserved across reconnect, at-least-once — are Raft's log matching minus
multi-node agreement. The honest stepping stone toward the consensus gap.

Scope note: SSE over HTTP/1.1 means chunked framing underneath event framing.
Scope the verification target to SSE framing plus connection lifecycle over an
already-parsed response; chunked is jolt-http's problem, not the slice's.

### Two corroborations these targets supplied

**Corroboration 1 — the method commitment, arrived at independently.**
`jolt-http/test/jolt/http/http_model.clj` is an independent HTTP/1.1 response
reader used as the oracle for every generative property, and its docstring says:

> "deliberately written from RFC 9112 rather than derived from
> `jolt.http.protocol` — **a property whose expected value is computed by the
> code under test proves nothing**."

That is this document's standing method commitment, reached independently. It
goes further, on exactly the ground E5 covered: the regex helpers it replaces
"cannot express the questions that matter most: how many responses are in this
byte stream, in what order, and is there anything left over? A duplicated or
split response is invisible to a regex … and duplication/splitting is exactly
what a framing bug produces."

Spot checks versus a specification's semantics, again.

**Corroboration 2 — a third instance of the signed-byte tax.** The same
docstring: "Bytes are handled as vectors of unsigned octets (0..255)
throughout, **because jolt's byte-arrays read back signed** and comparing the
two representations directly reports spurious mismatches above 0x7f."

So the JVM signed-byte accident (§1.5) has three independent instances:
`jolt-bytes` pays it per read through `signed-byte-at` (E8 measured that at
209 ns/byte); `jolt-http`'s *test model* pays it by converting representations
to make comparison work at all. Two libraries, two different workarounds, one
inherited convention. §1.5's decision is corroborated by code that predates it.

### Where this stands, and the next step

**perturb has no code.** What exists is this record, the Python prototypes
modelling rule sets (`prototypes/`, gated by `verify-capability-rules`), and
seven commits of Jolt improvements that came out of measuring rather than
designing.

That is a defensible position — the record has corrected itself repeatedly
against evidence, which is cheaper than building the wrong thing — but the
largest untested claim is now **"any of this can be built"**, and this document's
own standing commitment says untested claims are the ones that turn out wrong.

Settled enough to build on: the substrate (§1.1), the two tiers and their four
axes (§1.2, as corrected by E5/E6/E13), the capability-tier proof approach
(§1.3), D4 effects with D3 deferred (§1.4), unsigned bytes (§1.5), the
core/`clojure.*` layering with a defeasible no-foreclosure rule (§1.6), and this
ladder.

Open and undesigned: the **structural/inductive tier**, **liveness**, **Q3**
(`unique` × multi-shot, not load-bearing while D3 is deferred), **Q4** (macro
blame), and **agreement/consensus** — all in §4.

**The next step: run the capability checker over Jolt's IR.**

Every prototype so far models a rule set in Python; none has touched a real
program. §1.1 makes two specific claims from reading `jolt-core/jolt/ir.clj`,
both untested:

1. `:local` carries a name, not binding identity, so linearity checking needs
   alpha-conversion or a `:binding-id`.
2. `:host`/`:host-static`/`:host-new`/`:host-call` should collapse into one
   `:extern` carrying a declared effect row and signature.

Those are inferences from source reading. This session's record on inferences
from source reading is poor: the `jolt-array` survey was wrong on scale *and*
kind (E12), E3's central finding was sample-biased, and three performance
hypotheses died to measurement (E1, E7). Assume at least one of the two is wrong
until a checker walks real IR.

It is also **step zero of ladder step 1**: jolt-tcp's connection typestate
cannot be checked without a checker that reads real code. And it answers E6's
open usability question empirically — how often the join rule actually fires on
real programs — which is the largest unquantified risk to §1.2 and is currently
argued rather than measured.

Corpus: Jolt's own stdlib. Real, Clojure-shaped, already exists.

---

## 6. Nonclaims

Consolidated and current. Where an earlier nonclaim was superseded, the
superseded text is kept in Appendix A rather than here.

### Measurement

1. **Every ns/byte figure in this document is a single non-isolated sample
   unless stated otherwise**, on a host with a ~10% run-to-run spread (E9).
   Ratios of 2x and above are safe; anything under ~20% needs repeated sampling
   on a quiet machine before it is evidence.
2. **No cross-host absolute is claimed.** This host does not reproduce
   `jolt-bencode/docs/PERFORMANCE.md`'s recorded 991,008 ns — the 5-run baseline
   median is 1,211,841 ns, 22% above it (E10). Cross-host absolutes should not
   be compared at all from these measurements; only within-arm deltas taken in
   the same session. The earlier "reproduces to 0.6%" claim was a single sample
   landing near the recorded value. Even at the tuple these remain single
   non-isolated samples on one machine, and **no cross-platform claim is made**.
3. **No end-to-end improvement is claimed for E9.** The primitive-level gains
   (3.7x on `unchecked-byte`, 2.1x on the standalone body) are far outside the
   noise band; the aggregate is not resolvable from single samples on this host.
4. The single-sample E1 measurements were taken on a self-built Chez 10.4.1
   (unpinned against `jolt-toolchains/config/toolchains.json`) and jolt at
   `380e59e`, not jolt-bencode's pinned `89fe46e8`; one sample per measurement;
   measured full decode 368–493 µs against a recorded 991 µs. Ratios there are
   structural and source-corroborated; absolutes are not.
5. What the pinned A/B does establish: the setup is at the pinned tuple, the
   arms are identical apart from the change under test, and the `String`/
   `getBytes` control row did not move (+1.6%, inside both spreads). That is the
   strongest internal evidence here that the comparison measures what it claims
   — it is not a claim about any other machine.
6. **Modes are not claimed to address the measured gap.** On the evidence here
   they address allocation, which is the minority term. This nonclaim stands
   unchanged through every re-measurement and is reinforced by E9: allocation
   was never the dominant term, so modes remain unjustified by this measurement.

### Scope of the surveys and prototypes

7. E2/E3 are source-inspection surveys over the repositories read
   (jolt, jolt-sim, jolt-sim-planning, jolt-bytes, jolt-hako, jolt-bencode,
   jolt-toolchains). `jolt-toolchain` (singular) was not readable.
8. **E3 is biased by its sample.** It is evidence about what a codec needs, not
   about what a consensus protocol or a search tree needs. It covers *resource
   safety* only.
9. E5 does not establish soundness beyond depth 10; nor that the rule set
   generalizes to capabilities other than this one buffer; nor that a real
   perturb checker over the Jolt IR would *infer* these judgements rather than
   check them on a pre-supplied operation sequence. `equivalence.py` is a
   differential test against one model, not a proof.
10. E6 probe 1 is weaker evidence than E5: both the rules and the expectations
    are authored here, so it is a consistency check, not a differential test
    against an independent specification.
11. E13 is **checking, not inference**. No claim that liquid inference would
    find these annotations; the abstract refinements are instantiated by hand.
    Its solver is incomplete over the integers, so "fails" means "not proved",
    and each negative was inspected by hand for that failure mode. One driver
    shape and one transducer shape: `partition-by`, early termination via
    `reduced`, and stateful transducers whose state is itself a capability are
    untested. Nothing in E13 touches linearity or typestate, and the interaction
    of abstract refinements with affine binding is untested. Its Python model
    encodes objects field-wise into integers, so descriptor identity is
    deliberately not representable — which is the subject of one of the findings
    rather than an artifact of the encoding.

### Verification and simulation

12. No completeness, partial-order-reduction, or Molly/LDFI claim is made or
    extended. P5 §4.2/§4.3 govern.
13. Nothing here is `proved` on the charter §5 lattice.

### Fork and remint

14. The fork is a declared remint event. Portable artifacts survive — SMT
    families, the Prolog model, Ansatz proof closures, oracle decision tables,
    Hegel property designs. Artifacts pinned to a Jolt image do not: runtime
    gates, `verify-runtime-jolt`, evidence records naming Jolt commits, and the
    jolt-sim controller ABI work. This must be declared, never silently
    reinterpreted (charter F4).

### Not attempted

15. Explicitly identified and deliberately not attempted, each a stop rather
    than a completed fix: descriptor-local method slots in the original
    layout-changing form (E1); backend devirtualization for collection-interface
    methods, **retired** at ~3% (E7); unboxed fixnum arithmetic and direct array
    indexing in deftype method bodies, not investigated (E7); a `^bytes` typed
    `aget` fast path (E11); switching the byte-array backing to a Chez
    bytevector (E11).

---

## Appendix A — correction history

The audit trail. Every row is a claim this document made and then corrected.
Superseded verdicts are kept deliberately; this project's house style retains
rejected alternatives and superseded verdicts rather than deleting them (see
`jolt/docs/research/DECISION-MEMO-2026-08-01.md` and the P-series reviews in
`jolt-sim-planning`).

### A.1 Superseded claims

| # | claimed | where it was claimed | refuted by | corrected version now in |
| ---: | --- | --- | --- | --- |
| 1 | host-interop `String` emulation dominates codec cost | session hypothesis, recorded in §1/E1 | E1's own `decode-utf8` decomposition — 0.2% of decode | E1 |
| 2 | allocation of "decoded Clojure values and parser result maps" dominates | `jolt-bytes/docs/PERFORMANCE.md` | E1 — `window-octets` costs ~12% more than the bare scan | E1; §6 nonclaim 6 |
| 3 | the residual after `048582c3` is "two string-keyed lookups plus generic invoke" | §1/E1 | E7, `measurements/profile3.clj` | E1, E7 |
| 4 | the residual is the `jolt-nth` cond preamble and generic `jolt-invoke`; backend devirtualization is the fix | §5 | E7 — dispatch ≈219 ns/byte (19%), devirt ≈37 ns/byte (3%) | E7; item **retired** |
| 5 | "the measured gap is dispatch structure, not Chez codegen" | §2.1 | E7, E8 — it is native-vs-overlay primitives; E11 — `aget` is not even an array read | §1.1; E8; E11 |
| 6 | Q1's remaining sub-question is answered "yes, backend devirtualization" | §5 | E7 | Q1 |
| 7 | Q1 "residual dispatch gap: may require remint" — retitled "likely no layout change" | §4/Q1 as first titled | inspection of `jrdesc` — resolution (b) needs no field and no version bump; implemented | Q1 |
| 8 | Nonclaim 1: E1's absolutes are not evidence at any pinned tuple | §3 | the pinned re-measurement in §5 promoted E1 to the tuple — then E10 removed the reproduction claim | §6 nonclaims 1, 2, 4 |
| 9 | Nonclaim 2: `048582c3` is not claimed to fix E1 — it is a 25% partial and the structural fix is identified and deliberately not attempted | §3 | the pair `048582c3`+`31cf9de0` is a 40% end-to-end reduction at the pin; all three changes are −42.9% with repeated sampling. It still does **not** close the gap: `nth` on a deftype remains 2,061 ns/byte against 86 for a persistent vector, ~24x | E1; E10 |
| 10 | Nonclaim 4: the jolt-bytes suite does not validate `048582c3` — it fails identically with and without the patch (version mismatch, pre-existing); neutrality established, validation not | §3 | it validates at the pin: `:status :verified`, 132,672 assertions. The earlier failure was purely version skew | E1 |
| 11 | the pinned setup "reproduces the recorded baseline" to 0.6% (985,225 vs 991,008 ns) | §5 | E10 — 5-run baseline median 1,211,841 ns, 22% above the recorded figure; the agreement was luck | E10; §6 nonclaim 2 |
| 12 | single-sample deltas are evidence | throughout §1–§8 | E9 — 437/395/421/432 µs on the same build, ~10% spread | E9; §6 nonclaim 1 |
| 13 | the capability tier's axes are uniqueness + linearity | §2.2 | E5 — 1051 / 6470 unsound acceptances at depth 8 | §1.2; E5 |
| 14 | a borrow blocks moves | §2.2 | E5 — 103 sequences still wrongly accepted; borrows must block reads | §1.2; E5 |
| 15 | `writer_result` is structurally unrepresentable, so no binding rule is needed | §6/E5 | E6 probe 2 — `new a; alias b = a; a.detach_result; b.return_pool` accepted | §1.2; E6 |
| 16 | contention is droppable ("only needed for real shared-memory parallelism") | §2.2 | E6 probe 3 — `noninheriting-scratch-corrected.smt2` is a contention obligation already in the proof surface | §1.2; E6 |
| 17 | capability-tier refinements "cover 100% of the existing proof surface" | §2.3 | E5 — coverage requires typestate; E3's own sample bias — coverage is of *resource safety* | §1.3; E3; E5 |
| 18 | E3: "No obligation is about application semantics" | §1/E3 | re-examination of the sample: codecs have no application semantics, so finding none is close to tautological | E3; §4.6 |
| 19 | Q2 is a gradual-typing boundary problem | §4/Q2 as first framed | the liquid-types literature — everything is statically typed; an unrefined value is refined by `true` | Q2 |
| 20 | ordinary values get full inference, "without giving up inference" | §2.2 | E13 — abstract refinements are predicate variables; HM unification cannot solve them | §1.2; E13 |
| 21 | `aget` at 54 ns/byte is a primitive-read floor | §1/E1, leaned on by §2.1 | E11 — `(aget b i)` lowers to `(jolt-nth b i)` | E11; §1.1 |
| 22 | a byte array is one byte per element (implicit) | throughout the performance line | E11 — 8 bytes/element, same as a long array | E11 |
| 23 | the `jolt-array` backing survey: "~35 direct uses across 10 files", framed as reads | session narrative, cited in §13/E12 | the delegated refactor — 41 raw operations across 7 files, plus six construction sites | E12 |
| 24 | a fresh-window step and the same-window `Step` are incomparable | E13's own draft case | the solver — `Step` really is a subtype of it as first written | E13 |
| 25 | Q5: `clojure.*` is either deferred or optional | §15.1 | §15.2 — it is neither; the rule is a defeasible default | Q5; §1.6; §2 |
| 26 | v0 should gain a non-codec target: P4's capacity-one mailbox, or a small leader election | §16 (the E3 sampling-bias section) | §17 — both are models; the runtime has better targets | §5; Appendix B.2 |
| 27 | v0 targets are nREPL-first, then persistent collections | §17 | §18 — jolt-tcp/jolt-http have implementations *and* independent oracles | §5; Appendix B.2 |
| 28 | the original v0 ladder: re-measure at the pin, close the dispatch gap, mode checker, `unique` Cursor, session type then nREPL | §2.5 | steps 1–2 executed and then retired/redirected by E7/E8/E11; the ladder as a whole revised by §17 then §18 | §5; Appendix B.1 |
| 29 | "hash values are not observable through any specified interface"; the only law is hash-consistency | §15.4, now §2 row 3 | E14 — `host/chez/hasheq.ss` is a JVM-exact Murmur3 port; the CHANGELOG specifies vector/map/set hashes as "value-identical to the JVM"; `.hashCode` and `hasheq` are two separately-specified surfaces; the new concurrency gate's oracle is a per-object value equation | E14. **Left open**: the perturb decision may stand, but as a choice not to expose, not as a finding that nothing exposes |
| 30 | "Effects substitute a validated result or abort" as an exhaustive dispatch algebra | §2.4, now §1.4 | E14 — the delivered v0.5.17 sim controller dispatches substitute / abort / **proceed-once**, handing the handler a one-shot native `proceed` thunk | E14. **Left open** — including whether one-shot resumption counts as a continuation, which §1.4 and the seams request answer differently |
| 31 | charter non-goal 13's "runtime seams are *requested* … never assumed", relied on by §1.4 | §2.4, now §1.4 | E14 — seven unmerged branches carry a complete lifecycle/FFI/clock controller overlay at ABI 6, plus tests and a bounded proof note. The companion artifact's items 7–9 are all present | E14. The factual half ("none exists at the v0.5.17 baseline") is **unchanged and still true** |

### A.2 The method notes, in order

Each records a claim that survived inspection and failed a check. They are kept
as a numbered series because the numbering is itself the record.

| instance | where | what it recorded |
| ---: | --- | --- |
| first and second | E1 | host-interop emulation and allocation, both refuted by the `decode-utf8` decomposition |
| E5's method note | E5 | the 9 recorded queries passed at the rule set carrying 1,051 unsound acceptances |
| E6's restatement | E6 | every axis addition came from a probe designed to break the previous claim, none from the supplied spot checks |
| third | E7 | dispatch dominates → dispatch is 19%, devirtualization worth 3% |
| fourth | E11 | `aget` is an array read → it is generic dispatch; and the array is 8x larger than assumed. Neither is visible from timing, "which is why ten sections of timing did not surface them" |
| fifth | E12 | delegation is not exempt: **a brief is a specification, and its examples are not its semantics** |
| sixth | E13 | the fresh-window/`Step` incomparability case, refuted by the solver in the small |

---

## Appendix B — superseded ladders, retained in full

Kept because §5's ladder carries forward three of their targets (persistent
collections, leader election, and nREPL as a middleware target) and the
reasoning for each lives here.

### B.1 The original v0 ladder — "port a measured slice, don't build a new one"

Ordered:

1. **Re-measure at a pinned target tuple.** Re-run the profiles under
   `jolt-toolchains/setup-chez` at the recipe's Chez commit and jolt-bencode's
   pinned `89fe46e8`, to promote E1 from indication to evidence.
2. **Close the residual dispatch gap** (descriptor-local method slots), or
   establish that byte views must be primitive rather than user `deftype`.
3. **Mode checker against E3's existing controls.**
4. **`unique` Cursor, mutated in place** — satisfy jolt-bytes and jolt-bencode
   oracle corpora byte-identically, pass both Hegel suites, state an explicit
   target against `docs/PERFORMANCE.md`.
5. **Session type over the decode trichotomy**, then nREPL.

Steps 1–2 must precede 3–4: with dispatch dominant, the mode system's
performance case is untestable, and on current evidence modes address at most
the ~20–30% of decode that is allocation.

**Disposition.** Step 1 was executed (E1's pinned re-measurement, then E10).
Step 2 was attempted as Q1 resolution (b), then re-aimed twice: E7 retired
backend devirtualization, E8/E9 landed the `unchecked-byte` nativisation, and
E11 opened the representation questions that are now under Q1. Steps 3–5 were
not started; the ladder they belong to was revised by B.2 and then by §5. The
gate-architecture paragraph that closed this section is a live decision and is
now §1.7.

### B.2 The nREPL-first ladder — "dogfood the runtime, not a model"

Superseded the recommendation of P4's capacity-one mailbox or a small leader
election. Both are models. The runtime already contains better targets, and they
are things perturb needs early regardless.

#### The pairing

| target | property classes exercised | open question it tests |
| --- | --- | --- |
| **nREPL session + correlation** | resource safety, **temporal** | E5/E6 modes on a real resource; the temporal gap |
| **nREPL middleware** | higher-order composition | E13's abstract-refinement arity, on a real signature |
| **Persistent collections** | **structural / inductive** | the QF-LIA gap; the Ansatz tier |

Together these cover all three property classes. Neither is an abstract
example; both are load-bearing runtime.

#### Why nREPL, concretely

Read against `jolt-core/jolt/nrepl.clj`:

- **Session lifecycle.** `clone` creates, `close` destroys. A `unique`
  capability with typestate — created → active → closed, close-once,
  use-after-close an error. E5's typestate and E6's affine binding applied to a
  runtime resource rather than `ownership.pl`'s model.
- **Request/response correlation.** Per message `id`: exactly one terminal
  `status ["done"]`, optionally preceded by streamed `out`/`err`, and **nothing
  after done**. A trace-grammar property, which `monitor.clj` already monitors.
  "Every request eventually gets a done" is bounded response — P4's shape
  against real code.
- **Concurrency.** Concurrent requests and ordering exercise §1.4's kernel,
  which E4 established codecs do not touch at all.
- **Middleware.** `(fn [handler] (fn [request] ...))` is exactly E13's
  higher-order shape. If a handler's type carries the session and the `:reply`
  channel as refined capabilities, a middleware chain is the transducer problem
  including the arity-2 requirement E13 found unnamed in §1.2/§1.3. E13 tested
  it synthetically; this tests it against a signature that exists.
- **An already-asserted claim to discharge.** The docstring states `:reply` is
  "a thread-safe `(fn [response-map])`". That is an unverified concurrency claim
  in shipped code, which makes it a better target than one we invent.

Deferred within nREPL: **`interrupt`** — an in-flight eval racing an interrupt,
which must either land or report session-idle. The genuine race and the right
eventual target, but it depends on runtime lifecycle hooks that jolt-sim's
roadmap item 6 records as not yet existing.

#### Why persistent collections

nREPL supplies **nothing structural** — no trees, no balance, no
ordering-preserved-under-traversal. The inductive gap would stay untested.

Jolt's own collections are the natural target: 32-way vector tries with tails,
and maps that promote from insertion-ordered small maps to HAMTs past a
threshold. Their invariants are exactly the ones QF-LIA cannot reach — node
arity, trie depth consistency, tail invariants, and *promotion preserving
lookup*. That last one is the interesting obligation, because it is a refinement
between two representations of the same abstract map, which is the same shape as
the refinement relations E3 noted consensus proofs need.

They are also maximally load-bearing: everything in the language sits on them.

#### First slice

**The nREPL session type** — `unique` session with typestate, plus the per-`id`
trace grammar. Small, and specifiable in a way nREPL's own documentation is not:
there is no normative nREPL spec, so **writing the machine-checkable session
contract is the contribution**, not merely the test. That artifact does not
currently exist anywhere.

#### Still not covered

**Consensus and distributed properties.** nREPL is single-node; persistent
collections are sequential. Multi-node safety, refinement against a spec, and
liveness under partition remain untested by this ladder, and a leader-election
target is still eventually required. This revision buys getting off codecs
without inventing a toy; it does not close the distributed gap.

**Disposition.** Superseded by §5 for ordering. Persistent collections survive
as §5 step 4, leader election as step 5, and nREPL survives as a middleware
target for E13's abstract-refinement question.

---

## Appendix C — concordance

Historical text quoted above retains its original `§` references. This table
resolves them against the current structure.

| old | title | now in |
| --- | --- | --- |
| §1 / E1 | Byte access through a deftype collection interface dominates codec cost | E1 (final form); Appendix A.1 rows 1–3 |
| §1 / E2 | Five independent hand-rolled ownership systems exist | E2 |
| §1 / E3 | Every existing proof obligation is capability-tier | E3 (with the sample-bias qualification folded in) |
| §1 / E4 | The sans-io decoder shape is already validated | E4 |
| §2.1 | Substrate — fork Jolt onto Chez | §1.1 |
| §2.2 | Typing — two tiers | §1.2 |
| §2.3 | Proof — capability-tier refinements, Ansatz retained | §1.3 |
| §2.4 | Effects — charter D4 retained, D3 deferred | §1.4 |
| §2.5 | v0 — port a measured slice | Appendix B.1; gate architecture → §1.7 |
| §3 | Nonclaims | §6 (current); superseded items → Appendix A.1 rows 8–10 |
| §4 / Q1–Q4 | Open questions | Q1–Q4 in §4 |
| §5 | Measurement update — E1 re-measured at the pinned target tuple | E1 ("The pinned re-measurement"); Q1; Appendix A.1 rows 4, 6, 11 |
| §6 / E5 | the capability tier needs three disciplines, not two | E5 |
| §7 / E6 | three probes past the straight-line fragment | E6 |
| §8 / E7 | devirtualization is worth ~3%, not 24x | E7 |
| §9 / E8 | the dominant byte-path cost is `unchecked-byte` | E8 |
| §10 / E9 | the fix landed; noise floor ~10% | E9; §6 nonclaims 1, 3 |
| §11 / E10 | the pinned A/B with repeated sampling | E10; §6 nonclaims 2, 5 |
| §12 / E11 | `aget` is generic dispatch; byte array is 8 bytes/element | E11 |
| §13 / E12 | encapsulating the array backing | E12 |
| §14 | Decision — perturb's byte type is unsigned octets | §1.5; register row 1 |
| §15 | Decision — two layers: core and `clojure.*` | §1.6; §2 |
| §15.1 | Sequencing — `clojure.*` deferred, the rule is not | §1.6 ("Sequencing"); Q5 |
| §15.2 | Q5 resolved — the rule is a defeasible default | Q5; §2 ("The rule governing it") |
| §15.3 | Divergence register — row 2: eager sequences | §2 row 2 |
| §15.4 | Divergence register — row 3: equality | §2 row 3 |
| §16 (first) | Q2 partially resolved, and a sampling bias in E3 | Q2; E3; §4.6 |
| §16 (second) / E13 | the driver types; transducers need abstract refinements | E13 |
| §17 | Revised v0 targets — dogfood the runtime | Appendix B.2 |
| §18 | jolt-tcp and jolt-http | §5 |
| §19 | State, and the next step | §5 ("Where this stands, and the next step") |

Two sections shared the number 16 in the chronological record. Bare `§16`
references in historical text are resolved by content: references to the
sampling bias, the three property classes, or the codec-shaped ladder mean the
first; `§16/E13` means the second.

---

## Appendix D — E20's bibliography

**Why this appendix exists.** The E20 surveys ran under an egress policy that
blocked every scholarly host: `dl.acm.org`, `arxiv.org`, `link.springer.com`,
`drops.dagstuhl.de`, `plv.mpi-sws.org`, `iris-project.org`, `cs.cmu.edu`,
`microsoft.com`, and most university sites all returned 403 at the CONNECT
layer. Reachable: `github.com`, `raw.githubusercontent.com`, and search-engine
extractions. **Every reference below was verified for existence, venue, authors
and year; none was read in full text**, except the items marked ✔, which were
fetched directly, and the two in **D.8**, which were supplied as PDFs and are
committed under `docs/research/papers/`. This list is kept complete so the sources can be obtained when
access allows, and so any claim resting on one can be re-checked against the
paper rather than against a summary of it.

**E21 update — the marks now mean three different things.**

| mark | meaning |
| --- | --- |
| ✔✔ | **read in full text** during E21 or E25, from the URL given. Any claim resting on it has been checked against the paper |
| ✔ | fetched during E20 — an implementation artifact, repo, manual or issue, not a paper |
| ✗ | **attempted and not obtained** during E21; the reason is given |
| (none) | still verified for existence, venue, authors and year only. E20's method limitation applies unchanged |

Two entries were attempted and failed at first: **Parkinson & Bierman** (D.5)
and **Mostrous & Vasconcelos 2014** (cited by Fowler et al. as the origin of
explicit cancellation, and not previously listed here — added to D.3).
**Mostrous & Vasconcelos has since been recovered** from its CC-BY HAL deposit
and is marked ✔✔. **Parkinson & Bierman is not open access at all** (Unpaywall
`is_oa=false`, no author or institutional copy located) and remains ✗ and unread.
ACM was a dead end for automated fetching exactly as E20 recorded; everything obtained came
from arXiv, LIPIcs, author pages, institutional repositories, `web.archive.org`
(for `di.fc.ul.pt`, which redirect-loops), or GitHub.

**Twelve of the papers read are now committed to the repository**, under
`docs/research/papers/`, joining the two in D.8 — fourteen files in all. The
rule applied, and the per-paper evidence, is in
`docs/research/papers/README.md`: a paper is
redistributed **only** where there is an explicit licence grant. **Fourteen are
now committed**, and the selection rule was itself corrected once: the first pass
read the licence line printed on the PDF, which wrongly excluded five PACMPL
papers whose CC-BY grant lives in the publisher's **Crossref metadata** rather
than in the page furniture — Fowler et al., RustBelt, Astrauskas et al., Liquid
Resource Types and Qian et al. are all `creativecommons.org/licenses/by/4.0`
per `api.crossref.org`, and are included. ACM's
`© Copyright held by the owner/author(s)` line remains a copyright statement and
not a grant; papers whose Crossref record points at ACM's copyright policy —
**Rudra, Alms, Vault, seL4, Turnstile** — are **not** included. Ghica & Smith's
repository copy states "All rights reserved" explicitly, and Vasconcelos's I&C
paper is open at Elsevier with no licence. Das & Pfenning is committed in its
**LIPIcs** form rather than the arXiv one, because only the former is CC-BY.
`docs/research/papers/README.md` carries the per-paper evidence, and
`papers/markdown/` holds a greppable Markdown conversion of each one — lossy on
mathematics and figures, flagged as a modified derivative work as CC-BY
requires, and **not** the thing to quote from. Every quotation in E21 and E25
was taken from the PDF.

### D.1 Typestate

| ref | where |
| --- | --- |
| Strom & Yemini, *Typestate: A Programming Language Concept for Enhancing Software Reliability*, IEEE TSE 12(1), 1986 | original; NIL, IBM, no external deployment |
| ✔✔ DeLine & Fähndrich, *Enforcing High-Level Protocols in Low-Level Software*, PLDI 2001 | [MSR PDF](https://www.microsoft.com/en-us/research/wp-content/uploads/2001/05/pldi01.pdf) — **Vault**; tracked keys; pre/post over the whole key set. **E21: confirmed, and more general than E19 in three ways** — type guards attach keys to *data*; types may be parameterised by key sets; `[new k@S]` makes minting first-class |
| DeLine & Fähndrich, *Adoption and Focus: Practical Linear Types for Imperative Programming*, PLDI 2002 | Vault's aliasing story |
| DeLine & Fähndrich, *Typestates for Objects*, ECOOP 2004 | [10.1007/978-3-540-24851-4_21](https://link.springer.com/chapter/10.1007/978-3-540-24851-4_21) — Fugue |
| Fähndrich et al., *Language support for fast and reliable message-based communication in Singularity OS*, EuroSys 2006 | Sing#; channel contracts; `C.Imp`/`C.Exp` |
| Aldrich, Sunshine, Saini, Sparks, *Typestate-Oriented Programming*, Onward! 2009 | Plaid |
| Garcia, Tanter, Wolff, Aldrich, *Foundations of Typestate-Oriented Programming*, TOPLAS 36(4), 2014 | Plaid's metatheory |
| Bierhoff & Aldrich, *Modular Typestate Checking of Aliased Objects*, OOPSLA 2007 | [10.1145/1297105.1297050](https://dl.acm.org/doi/10.1145/1297105.1297050) — access permissions; the implementation side is the harder side |
| ✔ rust-lang/rust#2178 — removal of built-in typestate, July 2012 | [github.com/rust-lang/rust/issues/2178](https://github.com/rust-lang/rust/issues/2178) — "not pulling its weight" |
| Saffrich, Nishida, Thiemann, *Law and Order for Typestate with Borrowing*, OOPSLA 2024 | [arXiv:2408.14031](https://arxiv.org/abs/2408.14031) |
| Jia, Liu, He, Deng, Bao, Rompf, *Typestate via Revocable Capabilities*, OOPSLA 2025 | [arXiv:2510.08889](https://arxiv.org/abs/2510.08889) — closest to perturb's vocabulary |
| Beckman, Kim, Aldrich, *An Empirical Study of Object Protocols in the Wild*, ECOOP 2011 | protocol prevalence (~7.2% of types), not violation frequency |
| Naeem & Lhoták, *Typestate-like analysis of multiple interacting objects*, OOPSLA 2008 | [10.1145/1449764.1449792](https://dl.acm.org/doi/10.1145/1449764.1449792) |
| Mota, Giunti, Ravara, *On Using VeriFast, VerCors, Plural, and KeY to Check Object Usage*, ECOOP 2023 | [arXiv:2209.05136](https://arxiv.org/abs/2209.05136) — the one comparative effort study |

### D.2 Session types

| ref | where |
| --- | --- |
| Honda, Vasconcelos, Kubo, *Language Primitives and Type Discipline for Structured Communication-Based Programming*, ESOP 1998 | delegation |
| ✔✔ Vasconcelos, *Fundamentals of Session Types*, Information and Computation 217, 2012; and the SFM 2009 lecture notes | both read via `web.archive.org` (`di.fc.ul.pt` redirect-loops). **the conditional rule: both branches typed in the same context** — the refutation of E20's claim 1. **E21: confirmed and strengthened** — algorithmic [A-If] additionally requires both branches to return the **same output context and used-set**; [T-Branch] is the same rule for `&` |
| Caires & Pfenning, *Session Types as Intuitionistic Linear Propositions*, CONCUR 2010 | `!A` as replicated server |
| Thiemann & Vasconcelos, *Context-Free Session Types*, ICFP 2016 | FreeST; lifts `PRESERVE` beyond regular |
| Thiemann, *Intrinsically Typed Sessions with Callbacks*, 2023 | [arXiv:2303.01278](https://arxiv.org/abs/2303.01278) — driver/handler split in miniature |
| Hu & Yoshida, *Hybrid Session Verification through Endpoint API Generation*, FASE 2016 | HTTP/SMTP; **linearity checked dynamically** |
| Neykova, Yoshida, Hu, *Practical Interruptible Conversations*, RV 2013 / FMSD | the OOI deployment — **runtime monitoring, not static typing** |
| Jespersen, Munksgaard, Larsen, *Session Types for Rust*, WGP 2015 | the affine/linear gap |
| Cutner, Yoshida, Vassor, *Deadlock-Free Asynchronous Message Reordering in Rust with MPST*, PPoPP 2022 | Rumpsteak |
| Chen, Balzer, Toninho, Ferrite | [arXiv:2009.13619](https://arxiv.org/abs/2009.13619), [arXiv:2205.06921](https://arxiv.org/abs/2205.06921) |
| Scalas & Yoshida, lchannels, ECOOP 2016; Scalas, Yoshida, Benussi, Effpi, PLDI 2019 | Scala |
| Deniélou & Yoshida, *Parameterised Multiparty Session Types*, FoSSaCS 2011 / LMCS 8(4) 2012 | [arXiv:1208.6483](https://arxiv.org/abs/1208.6483) |
| ✔✔ Qian, Kavvos, Birkedal, *Client-Server Sessions in Linear Logic*, ICFP 2021 | [10.1145/3473567](https://doi.org/10.1145/3473567) · **CC-BY, in repo:** `papers/ICFP21-client-server-sessions-in-linear-logic.pdf` — right theory for listener+N, **no implementation found**. **E21: negative claim confirmed** — every "implement" in the paper is an encoding *within* the calculus (CAS, a beauty-contest umpire), not software |
| Balzer & Pfenning, *Manifest Sharing with Session Types*, ICFP 2017; *Manifest Deadlock-Freedom for Shared Session Types*, ESOP 2019 | trouble with a statically undetermined number of shared sessions — i.e. a server |
| ✔✔ Das & Pfenning, *Session Types with Arithmetic Refinements*, CONCUR 2020 | [10.4230/LIPIcs.CONCUR.2020.13](https://doi.org/10.4230/LIPIcs.CONCUR.2020.13) · **CC-BY, in repo:** `papers/CONCUR20-session-types-with-arithmetic-refinements.pdf` (the LIPIcs version; the arXiv posting is not redistributable) — **refinements inside recursive session types ⇒ undecidable type equality**. **E21: confirmed**; cause is a reduction from two-counter-machine halting, needing arithmetic constraints **and recursion in the language of types** — which E19's transition-level placement never introduces |
| Das & Pfenning, Rast, FSCD 2020 | the implementation of the above |
| Saffrich & Thiemann, *Polymorphic Typestate for Session Types*, PPDP 2023 | escaping CPS needs higher-order polymorphism + existentials |
| Kirkeby et al., *Session Types for the Transport Layer: Towards an Implementation of TCP*, 2024 | [arXiv:2404.05478](https://arxiv.org/abs/2404.05478) — "differences in assumptions between session type theory and how transport protocols are implemented" |
| Yoshida et al., *Programming Language Implementations with Multiparty Session Types*, 2024 | survey |

### D.3 Effects, handlers, and the linearity tension

| ref | where |
| --- | --- |
| Orchard & Yoshida, *Effects as Sessions, Sessions as Effects*, POPL 2016 | the two-way embedding — "the protocol **is** the effect signature" |
| ✔✔ Tang, Hillerström, Lindley, Morris, *Soundly Handling Linearity*, POPL 2024 (distinguished paper) | [arXiv:2307.09383](https://arxiv.org/abs/2307.09383) · **CC-BY, in repo:** `papers/POPL24-soundly-handling-linearity.pdf` — Links' soundness bug (the paper says "**long-standing**"; it states no duration); control-flow linearity; the price list for reopening D3. **E21: confirmed, and E20's use of it overstated** — the mismatch has two halves, *discarded* and *multi-invoked*, and `abort!` is the first (tally row 30) |
| ✔✔ Fowler, Lindley, Morris, Decova, *Exceptional Asynchronous Session Types: Session Types without Tiers*, POPL 2019 | [slindley/papers/zap.pdf](https://homepages.inf.ed.ac.uk/slindley/papers/zap.pdf) · **CC-BY, in repo:** `papers/POPL19-exceptional-asynchronous-session-types.pdf` — **~~affinity~~ LINEARITY + explicit cancellation**, still the highest-value unadopted item. **E21: E20 had the mechanism backwards** — §1.3 rejects affine types by name; §1.4 is "*Linear* Types with Explicit Cancellation" (tally row 31) |
| ✔✔ Mostrous & Vasconcelos, *Affine Sessions*, COORDINATION 2014 | [10.1007/978-3-662-43376-8_8](https://doi.org/10.1007/978-3-662-43376-8_8) · **CC-BY via [hal-01290071](https://inria.hal.science/hal-01290071), in repo:** `papers/COORDINATION14-affine-sessions.pdf` — the origin of explicit cancellation. **Recovered after E21 marked it ✗**, and it refines row 31: the paper *does* say "We relax the condition of linearity to that of **affinity**, by which channels exhibit **at most** the behaviour prescribed by their types" — so E20's vocabulary came from here. What does not follow is that perturb "has the affinity" |
| Brady, *Programming and Reasoning with Algebraic Effects and Dependent Types*, ICFP 2013; *Resource-Dependent Algebraic Effects*, TFP 2014; *State Machines All The Way Down*, ML 2017 | Idris `Control.ST`; closest existing design |
| Brachthäuser, Schuster, Ostermann, *Effects as Capabilities*, OOPSLA 2020; *Effects, Capabilities, and Boxes*, OOPSLA 2022 | Effekt — **vocabulary warning**: its "capability" is handler evidence, not a linear resource |
| Schuster et al., *From Capabilities to Regions*, OOPSLA 2023 | |
| Yarrow, effects × region-based memory management | arXiv 2607.15876 — already cited in §1.2 |

### D.4 Graded, quantitative, and refinement types

| ref | where |
| --- | --- |
| ✔✔ Orchard, Liepelt, Eades, *Quantitative Program Reasoning with Graded Modal Types*, ICFP 2019 | [Kent PDF](https://www.cs.kent.ac.uk/people/staff/dao7/publ/granule-icfp19.pdf) · **CC-BY, in repo:** `papers/ICFP19-quantitative-program-reasoning-with-graded-modal-types.pdf` — Granule. **E21: confirmed** — "exploits an SMT solver to discharge theorems over the indices of graded modalities"; predicates go to SMT-LIB and "a compatible SMT solver (we use Z3)". Requires *an* SMT solver; Z3 is the one used |
| ✔ Granule repo, install docs, `StdLib/Vec.gr`, `StdLib/File.gr`, `examples/intro.gr.md` | [github.com/granule-project/granule](https://github.com/granule-project/granule) — **requires Z3**; `File.gr`'s write loop has an un-indexed handle; `last`/`init` commented out of `Vec.gr` |
| ✔✔ Ghica & Smith, *Bounded Linear Types in a Resource Semiring*, ESOP 2014 | [Birmingham PDF](https://pure-oai.bham.ac.uk/ws/portalfiles/portal/23697858/esop14.pdf) — the quoted phrase is exact but describes the **type-inference procedure**, not "the system" as E20 had it. §3.3 adds: their constraints go to Z3, and "nonlinear systems of constraints over ℕ are generally undecidable" |
| Girard, Scedrov, Scott, *Bounded Linear Logic*, TCS 97(1), 1992 | index polynomials |
| Dal Lago & Gaboardi, *Linear Dependent Types and Relative Completeness*, LMCS 8(4), 2012 | [arXiv:1104.0193](https://arxiv.org/abs/1104.0193) — completeness *relative to an oracle* |
| Atkey, *Syntax and Semantics of Quantitative Type Theory*; Brady, *Idris 2: QTT in Practice*, ECOOP 2021 | LIPIcs 194:9 |
| ✔ Idris 2 multiplicities manual | [docs/source/tutorial/multiplicities.rst](https://github.com/idris-lang/Idris2/blob/main/docs/source/tutorial/multiplicities.rst) — grades are {0,1,ω}; no counting |
| Bernardy, Boespflug, Newton, Peyton Jones, Spiwack, *Linear Haskell*, POPL 2018 | [arXiv:1710.09756](https://arxiv.org/pdf/1710.09756) — linearity on the **arrow**; evidence perturb's annotation shape scales |
| ✔ GHC LinearTypes user's guide | multiplicity polymorphism "incomplete and experimental" |
| Petricek, Orchard, Mycroft, *Coeffects*, ICFP 2014 | framework, not a mechanism |
| Marshall, Vollmer, Orchard, *Linearity and Uniqueness: An Entente Cordiale*, ESOP 2022 | the citation to use **if** grading is reconsidered for §1.2's axis list |
| Marshall & Orchard, *Replicate, Reuse, Repeat*, 2022 | [arXiv:2203.12875](https://arxiv.org/abs/2203.12875) — session types as grades |
| ✔✔ Knoth, Wang, Reynolds, Polikarpova, Hoffmann, *Liquid Resource Types*, ICFP 2020 | [10.1145/3408988](https://doi.org/10.1145/3408988) · **CC-BY, in repo:** `papers/ICFP20-liquid-resource-types.pdf` (the published version; the arXiv posting is not redistributable) — **confirmed verbatim**: "more expressive proof techniques admitting value-dependent bounds rely on handwritten proofs. Liquid resource types combine the best of these approaches, using logical refinements..." Value-dependent counting ⇒ refinements ⇒ SMT |
| ✔✔ Lehmann, Geller, Vazou, Jhala, *Flux: Liquid Types for Rust*, PLDI 2023 | [flux-pldi23.pdf](https://ranjitjhala.github.io/static/flux-pldi23.pdf) · **CC-BY, in repo:** `papers/PLDI23-flux-liquid-types-for-rust.pdf` — **nearest system to perturb's shape. E21: every conjunct confirmed** — indexed mutable locations, ownership-based substructural reasoning, strong updates, and loop invariants synthesised by the Liquid Fixpoint Horn solver. Reaching this class costs SMT + a Horn solver + indexed locations |
| Sammler, Lepigre, Krebbers, Memarian, Dreyer, Garg, *RefinedC*, PLDI 2021 | [10.1145/3453483.3454036](https://dl.acm.org/doi/10.1145/3453483.3454036) — ownership + refinement **without SMT**, via Lithium |
| Vazou et al., abstract refinements, ESOP 2013; *LiquidHaskell in the real world* | already cited in §1.3/Q2 |
| Vazou, Tanter, Van Horn, *Gradual Liquid Type Inference*, OOPSLA 2018 | inference is global; failure gives obscure messages |
| Toman et al., ConSORT, ESOP 2020 | ownership refinement types, CHC-based |
| Xi, Dependent ML, JFP 2007; ATS | index refinement over a restricted domain; heavy annotation burden |
| ✔✔ Doré, *Dependent Multiplicities in Dependent Linear Type Theory*, 2026 | [arXiv:2507.08759](https://arxiv.org/abs/2507.08759) · **CC-BY, in repo:** `papers/dependent-multiplicities-in-dependent-linear-type-theory.pdf` — E20 could not fetch this and it **refutes E20's fifth correction**: multiplicities *can* depend on run-time values, so the Content-Length obligation is expressible. Decided by conversion in Agda, not SMT — but "type-checking for ⊩ is **vastly undecidable**" and discharge falls back on programmer-supplied propositional-equality witnesses. Also states the linear-`if` problem as motivation: "a steep ask" (tally row 32) |

### D.5 Abstraction boundaries, TCBs, and their measurement

| ref | where |
| --- | --- |
| Mitchell & Plotkin, *Abstract Types Have Existential Type*, TOPLAS 10(3), 1988 | [10.1145/44501.45065](https://dl.acm.org/doi/10.1145/44501.45065) |
| Rossberg, Russo, Dreyer, *F-ing Modules*, JFP 24(5), 2014; Rossberg, *1ML*, JFP 2018 | sealing elaborates to System Fω |
| Cardelli, Donahue, Jordan et al., *Modula-3 Report*, SRC-RR-52 | **partial revelation** — graded disclosure, the one classical answer to rights amplification |
| Morris, *Protection in Programming Languages*, CACM 16(1), 1973 | [10.1145/361932.361937](https://dl.acm.org/doi/pdf/10.1145/361932.361937) — sealer/unsealer pairs; **fits two-machine operations better than ML modules** |
| Matthews & Ahmed, *Parametric Polymorphism through Run-Time Sealing*, ESOP 2008 | [10.1007/978-3-540-78739-6_2](https://link.springer.com/chapter/10.1007/978-3-540-78739-6_2) |
| ✗ Parkinson & Bierman, *Separation logic and abstraction*, POPL 2005 | [10.1145/1040305.1040326](https://dl.acm.org/doi/10.1145/1040305.1040326) — **abstract predicates**. **Not obtained, and now known why**: Unpaywall reports `is_oa=false` — it is not open access anywhere, and no author or institutional copy was located. E20's claim 5 therefore rests on RustBelt, which *was* read and is in `papers/`, plus second-hand reading of this. The only reference E21 attempted and still has not read |
| ✔✔ Jung, Jourdan, Krebbers, Dreyer, *RustBelt*, POPL 2018 | [plv.mpi-sws.org PDF](https://plv.mpi-sws.org/rustbelt/popl18/paper.pdf) · **CC-BY, in repo:** `papers/POPL18-rustbelt-securing-the-foundations-of-rust.pdf` — **confirmed verbatim**: "for each new Rust library that uses unsafe features, we can say what verification condition it must satisfy". §1.2: "library-specific verification condition". *Scope:* discharged in Coq against a semantic model of the whole language |
| *RustHornBelt*, PLDI 2022; *RefinedRust*, PLDI 2024 | [10.1145/3519939.3523704](https://dl.acm.org/doi/10.1145/3519939.3523704) |
| Jung, Dang, Kang, Dreyer, *Stacked Borrows*, POPL 2020; Tree Borrows, PLDI 2025 | [plv.mpi-sws.org/rustbelt/stacked-borrows](https://plv.mpi-sws.org/rustbelt/stacked-borrows/) |
| Jung et al., *Miri*, POPL 2026 | [10.1145/3776690](https://dl.acm.org/doi/abs/10.1145/3776690) — **a dynamic checker for the trusted core**; cheap and perturb has no analogue |
| Swasey, Garg, Dreyer, *Robust and Compositional Verification of Object Capability Patterns*, OOPSLA 2017 | [10.1145/3133913](https://dl.acm.org/doi/10.1145/3133913) |
| ✔✔ Tov & Pucella, *Practical Affine Types* (Alms), POPL 2011 | [author PDF](https://users.cs.northwestern.edu/~jesse/pubs/alms/tovpucella-alms.pdf) — **sealing CREATES the linearity, confirmed verbatim**. Soundness needs: U a subkind of A, sealing moving *up* the lattice only, and principal kinding (Thm 3). §2's `CAP_ARRAY` splits reference from capability with an existential stamp, and capabilities are `unit` — **erased** |
| Tov & Pucella, *Stateful Contracts for Affine Types*, ESOP 2010 | [10.1007/978-3-642-11957-6_29](https://dl.acm.org/doi/10.1007/978-3-642-11957-6_29) — the paper for a `clojure.*` boundary (§1.6) |
| Morris, *The Best of Both Worlds* (Quill), ICFP 2016 | [arXiv:1612.06633](https://arxiv.org/abs/1612.06633) — linearity qualifiers solvable by qualified-type inference; a smaller hammer than E13's |
| ✔✔ Klein et al., *seL4*, SOSP 2009 | [SOSP PDF](https://www.sigops.org/s/conferences/sosp/2009/papers/klein-sosp09.pdf) — 8,700 C lines (+600 assembler), 200,000 Isabelle. **E21: the ratio is ≈23:1, not 20:1** |
| Protzenko et al., *Verified Low-Level Programming Embedded in F\**, ICFP 2017 | they **publish the TCB list** |
| Paulson, *the de Bruijn criterion vs the LCF architecture* | [lawrencecpaulson.github.io/2022/01/05/LCF.html](https://lawrencecpaulson.github.io/2022/01/05/LCF.html) |

### D.6 Empirical evidence on trusted cores

| ref | where |
| --- | --- |
| ✔✔ Astrauskas, Matheja, Poli, Müller, Summers, *How Do Programmers Use Unsafe Rust?*, OOPSLA 2020 | [ETH PDF](https://pm.inf.ethz.ch/publications/AstrauskasMathejaMuellerPoliSummers20.pdf) · **CC-BY, in repo:** `papers/OOPSLA20-how-do-programmers-use-unsafe-rust.pdf` — **92.3% ≤10% confirmed verbatim, but the denominator is "all crates", 76.4% of which contain no unsafe at all.** The authors' own conclusion: of the 21.3% that do, 24.6% exceed 20%, so "**we cannot claim that developers use unsafe Rust sparingly**". Do not quote 92.3% to argue trusted cores are small |
| ✔✔ Bae, Kim, Askar, Lim, Kim, *Rudra*, SOSP 2021 | [author PDF](https://taesoo.kim/pubs/2021/bae:rudra.pdf) — **264 bugs in 145 packages = 51.6% of memory-safety bugs reported to RustSec since 2016; 112 advisories, 76 CVEs. Confirmed.** Denominator correction: 43k *downloaded*, **33k analysed** (7k did not compile, 2k no Rust code, 0.7k bad metadata) |
| ✔✔ *A Grounded Conceptual Model for Ownership Types in Rust*, 2023 | [arXiv:2309.04134](https://arxiv.org/abs/2309.04134) · **CC-BY, in repo:** `papers/grounded-conceptual-model-for-ownership-types-in-rust.pdf` — not in E20. Participants predicted the borrow checker's reason for rejection in **78%** of cases but could only **fix** the program in **46%**, and build a counterexample in **31%**. The measured usability cost of a substructural rejection (§4.6's join item) |
| Qin, Chen, Yu, Song, Zhang, PLDI 2020 | [10.1145/3385412.3386036](https://dl.acm.org/doi/10.1145/3385412.3386036) — all memory-safety bugs involved unsafe; "interior unsafe" |
| Xu, Chen, Wang, Suo, Cheng, TOSEM 2021 | [arXiv:2003.03296](https://arxiv.org/abs/2003.03296) — all Rust memory-safety CVEs to 2020 |
| Evans, Campbell, Soffa, *Is Rust Used Safely by Software Developers?*, ICSE 2020 | only 27% of crates are transitively unsafe-free — per-crate ratios understate exposure |
| *A Mixed-Methods Study on the Implications of Unsafe Rust*, TOSEM 2025 | [arXiv:2404.02230](https://arxiv.org/abs/2404.02230) — "little official guidance" on encapsulating unsafe |
| Rao, Yang, Xu, *Characterizing Unsafe Code Encapsulation in Real-World Rust Systems*, 2024 | [arXiv:2406.07936](https://arxiv.org/abs/2406.07936) — **unsafety isolation graphs; audit units, not scalars** |
| *Annotating and Auditing the Safety Properties of Unsafe Rust*, 2025 | [arXiv:2504.21312](https://arxiv.org/abs/2504.21312) — safety tags; **96.1% of public unsafe APIs**; most transplantable idea |
| ✔ `cargo-geiger` and issue #71 | [github.com/geiger-rs/cargo-geiger](https://github.com/geiger-rs/cargo-geiger) · [issue #71](https://github.com/geiger-rs/cargo-geiger/issues/71) — **gameable in mirror image to E18 finding 4** |
| ✔ The Rustonomicon, *Working with Unsafe* | "the only bullet-proof way to limit the scope of unsafe code is at the module boundary with privacy" |
| ✔ Rust RFC 320, non-zeroing dynamic drop | [rust-lang.github.io/rfcs/0320-nonzeroing-dynamic-drop.html](https://rust-lang.github.io/rfcs/0320-nonzeroing-dynamic-drop.html) — **the dynamic join** |

### D.7 Deployments, and the Lisp precedents

| ref | where |
| --- | --- |
| Ramananandro et al., *EverParse*, USENIX Security 2019; EverParse3D | [project-everest.github.io/everparse](https://project-everest.github.io/everparse/) — **every packet in Hyper-V**, ~100 formats, non-malleability |
| *Project Everest: Perspectives from Developing Industrial-Grade High-Assurance Software*, TOPLAS 48(2), 2026 | [10.1145/3805702](https://dl.acm.org/doi/10.1145/3805702) |
| *Formally Verified Cloud-Scale Authorization*, ICSE 2025 | Dafny + Z3, deployed 2024; **proof brittleness blocked code updates** |
| Koh, Li, Li, Xia, Beringer, Honoré, Mansky, Pierce, Zdancewic, *From C to Interaction Trees*, CPP 2019 | [arXiv:1811.11911](https://arxiv.org/abs/1811.11911) — **the sans-io split, formally**; found RFC violations in Apache and nginx |
| Zhang, Honoré, Koh et al., *Verifying an HTTP Key-Value Server with Interaction Trees and VST*, ITP 2021 | [github.com/liyishuai/coq-http](https://github.com/liyishuai/coq-http) |
| ✔ `h11` `docs/source/api.rst` | **two coupled state machines, state-triggered transitions, `MUST_CLOSE`** — finding 3b, invented independently and done dynamically |
| Findler & Felleisen, *Contracts for Higher-Order Functions*, ICFP 2002 | [10.1145/581478.581484](https://dl.acm.org/doi/10.1145/581478.581484) |
| ✔ Racket Guide §7.1, *Contracts and Boundaries* | "**modules as units of blame**" — the two-sided obligation, in a Lisp |
| Dimoulas, Findler, Flanagan, Felleisen, *Correct Blame for Contracts*, POPL 2011 | [10.1145/1926385.1926410](https://dl.acm.org/doi/10.1145/1926385.1926410) — E18 1(c) is a blame failure in this sense |
| Dimoulas, Tobin-Hochstadt, Felleisen, *Complete Monitors for Behavioral Contracts*, ESOP 2012 | E18 (b) is an incomplete-monitoring failure |
| ✔ Racket Reference §17, *Unsafe Operations* | `protect-out` + code inspectors — a Lisp with an access-controlled trusted core |
| Flatt & Felleisen, *Units: Cool Modules for HOT Languages*, PLDI 1998 | |
| Tobin-Hochstadt & Felleisen, *The Design and Implementation of Typed Scheme*, POPL 2008 | |
| Bonnaire-Sergeant, Davies, Tobin-Hochstadt, *Practical Optional Types for Clojure*, ESOP 2016 | [arXiv:1812.03571](https://arxiv.org/abs/1812.03571) — the transplant exists |
| Takikawa, Feltey, Greenman, New, Vitek, Felleisen, *Is Sound Gradual Typing Dead?*, POPL 2016 | [10.1145/2837614.2837630](https://dl.acm.org/doi/10.1145/2837614.2837630) — **keep the seal static** |
| ✔✔ Chang, Knauth, Greenman, *Type Systems as Macros*, POPL 2017 | [Northeastern PDF](https://www.ccs.neu.edu/home/stchang/pubs/ckg-popl2017.pdf) — Turnstile. The **paper** never mentions linearity (its ladder is STLC → System F → Fω → subtyping → dependent) |
| ✔ `stchang/macrotypes`, `turnstile-example/turnstile/examples/linear/` | `lin.rkt`, `lin2`–`lin5`, `lin+cons`, `lin+tup`, `lin+var`, **`lin+chan`**, `fabul` — **a substructural type checker written as Racket macros**, with the linear arrow `-o` and a mode-based linear scope discipline. Raises `"linear variable may be unused in certain branches"`: perturb's join rule and perturb's diagnostic, in a Lisp. Answers the staged-protocol note's first question |
| Culpepper, Tobin-Hochstadt, Flatt, *Advanced Macrology and the Implementation of Typed Scheme*, Scheme Workshop 2007 | blame across macroexpansion is open (Q4) |
| ✔ Clojure reference: *Vars and the Global Environment* | `:private` is intent; `@#'ns/private-var` works — **perturb cannot get sealing from the host** |

---

### D.8 Full text actually in hand — the only two

Everything in D.1–D.7 was verified for existence, venue, authors and year and
**not read**. These two are different: they were supplied as PDFs, both are
**CC-BY 4.0**, and both are committed under `docs/research/papers/`. They are
also the two that bear most directly on the live fork in §4.6 — whether a
dynamically-sized capability collection becomes a language feature or stays in
an instrumented trusted core.

| ref | where | why it is here |
| --- | --- | --- |
| ✔✔ Milano, Turcotti, Myers, *A Flexible Type System for Fearless Concurrency*, PLDI 2022 | [10.1145/3519939.3523443](https://doi.org/10.1145/3519939.3523443) · `papers/PLDI22-flexible-type-system-for-fearless-concurrency.pdf` | **A candidate answer to E23/E24's root cause.** Its typing rules use *regions*, *isolated fields*, *tracked vs untracked*, **tempered domination**, `if disconnected`, and `Send`/`Receive` gated on an **empty tracking context**. |
| ✔✔ Laddad, Cheung, Hellerstein, Milano, *Flo: A Semantic Foundation for Progressive Stream Processing*, POPL 2025 | [10.1145/3704845](https://doi.org/10.1145/3704845) · `papers/flo-semantic-foundation-progressive-stream-processing.pdf` | **A candidate semantic foundation for the layered-event architecture.** Two properties — *streaming progress* and *eager execution* — a type system distinguishing **bounded** streams (operators may block on termination) from **unbounded**, dataflow composition, and **nested graphs with cycles**. Models Flink, LVars and DBSP. |

**✔✔ Both have since been read in full text — see E25.** The hypotheses below are
kept verbatim as written, because both were **substantially right and both named
the wrong construct**, and that is exactly the failure mode this document keeps a
tally of. Read E25 before acting on anything below it. In short: the connection
table comes from *intra-region references being untracked*, not from tempered
domination; and E23's `recv` defect is at Flo's **value** level (the terminator
`⊗`, and the `fixed` predicate), not at its bounded/unbounded **type** level
(tally rows 36–37). The paragraph below on Yarrow is the one that changed a
decision: the objection it cites is to stack-disciplined *memory* regions and
does not apply to these regions at all — **and** the escape it offers from that
objection ("D4's no-resumption may remove the reason regions were dropped") is
unavailable under tally row 30, because non-local control is the *discard* half
of the handler/linearity mismatch and D4 removes only the multi-shot half. The
region question is harder than the paragraph below states.

**What each would answer, stated as a hypothesis rather than a result — neither
has been read end to end, only the abstract of Flo and the typing-rule section
of the PLDI paper, via a crude PDF extraction.**

*Fearless Concurrency → the §4.6 root cause.* Our wall is that a capability may
live only in a binding of statically-known shape. A **region** holds an arbitrary
object graph and is owned as a unit, which is what a connection table is and what
a fixed-arity register file is not. **Tempered domination** relaxes global
domination for tracked isolated fields, permitting cycles and free field
reassignment — the property `table-grows-in-a-loop` failed to have.
`Send`/`Receive` on an empty tracking context is a discipline for moving a whole
region between threads, which is the contention axis nothing has tested (I20).
And `if disconnected` is a **runtime test establishing a static property**, which
is the hybrid posture `RUNTIME-OBLIGATION-BRIEF.md` argues for, already
load-bearing in a published system. The paper is pitched as *more flexible than
Rust*; perturb's problem is that it refuses code that runs.

**The tension this creates, and it is the important part.** §1.2 dropped
**locality — regions — by design**, citing Yarrow: regions are the specific
feature that makes effects unsound, because non-local control breaks stack
discipline and multi-shot handlers break exit-at-most-once. This is a region
system that delivers what §4.6 says is missing. E20 found that D4's
no-resumption rule may remove the reason regions were dropped — that was an
argument; this is a concrete instance of what the argument would license, and it
should be settled by reading rather than by inference.

*Flo → the layered-event architecture.* Its **bounded vs unbounded** distinction
is the principled form of a defect E23 found independently: `perturb.wire`'s
`recv` returns an empty octet view for **both** "end of stream" and "nothing
queued", a distinction `teensyp.stream` had and perturb cannot express. "Bounded
streams allow operators to block on termination" is exactly the property that
makes that distinction matter. Nested graphs with cycles is the keep-alive loop
and the layer stack; determinism is what perturb and jolt-sim both exist for.

---
---

## 7. Scoping correction — jolt-sim is input, not authority

**This qualifies the header's "Relationship to prior records" and every place
below that treats a jolt, jolt-sim, charter, or planning-packet decision as
inherited.**

perturb is **not bound by jolt-sim's decisions any more than by Clojure's**.
Both are prior art by the same author lineage, and both are sources of good
ideas and hard-won evidence. Neither is a specification perturb must satisfy.

### The distinction that matters

| status | examples | how perturb should use it |
| --- | --- | --- |
| **evidence** — observations about what real systems need | E2's five hand-rolled ownership systems; E3's proof survey; every measured cost (E1, E7–E11); jolt-hako's bug models; jolt-http's RFC-9112 oracle | load-bearing, cite freely |
| **convention** — house style worth adopting | the §5 evidence lattice; the corrected/buggy/non-vacuity control triple; producer/consumer gate separation | adopt because it is good, not because it is inherited |
| **specification** — decisions made under *jolt-sim's* constraints | charter D4; the charter's formal-core value set, evaluation order and error model; P4's bounded-response design; P5's completeness boundaries | **re-derive on perturb's terms or discard** |

The third row is where the record has been sloppy. Several decisions below were
written as "retains charter decision X" when the honest statement is either "we
independently reached X" or "we deferred to X and should not have".

### What must be re-derived

**§1.4's effects decision is the important one.** It retains D4 —
substitute-or-abort handlers, no continuations at that layer — and defers D3
(delimited control). The record claims this was "retained on its merits", and
part of it was: E4 established empirically that the codec layer needs no
continuations. But the rest leaned on two things that are jolt-sim's
constraints, not perturb's:

- **P5 §4.2's bounded-completeness precondition** ("value-semantic state") was
  used to argue continuations would break replay. That is a property of
  `explore_states.clj`'s search design, not a law.
- **Charter non-goal 13** ("no runtime lifecycle/controller seam exists") was
  used to argue the seam must be requested rather than assumed — and §3/E14's
  reconnaissance of the v0517 controller branches may already have overtaken
  it independently.

So **D3 is reopened on perturb's terms.** perturb controls its own compiler,
its own scheduler, and Chez's multi-shot `call/cc`; whether delimited control
belongs at the effect layer is a question perturb must answer from its own
goals, not one settled by deferring to a plan written for a different system.
The trade named in §1.4 (small TCB and stateful search versus direct-style
blocking code) stands as a *trade*; the resolution does not.

**The charter's formal-core semantics** — §1.2's value set, §2's evaluation
order, §2.4's error model, Appendix A's normalization — are recorded in the
header as "inherited". They are **available**, and they are good, but perturb
adopts each because it examined it, or not at all. Nothing has examined them
yet. That is now an open item.

### What survives unchanged

Decisions this record derived from evidence rather than deference are
unaffected:

- **unsigned bytes** (§1.5) — derived from E8's 209 ns/byte measurement and
  corroborated three times independently (§3/E1, E12, and jolt-http's test
  model). The charter's non-goal 1 was cited as agreement, not authority.
- **eager sequences** (§2 row 2) — the charter decided it, but the record
  supplied a perturb-specific argument the charter did not need: lazy
  realization is nondeterminism that does not pass through the oracle.
- **the two tiers and four axes** (§1.2) — derived from E5/E6/E13's probes.
- **capability-tier refinements** (§1.3) — derived from E3's survey, with E3's
  own sampling bias already recorded against it.

### Nonclaim

This does not assert any specific charter or planning decision is *wrong*. It
asserts they were adopted without the scrutiny perturb's own decisions received,
and that the difference was not visible in the record. Re-derivation may well
reach the same answers — D4 in particular may survive on E4's evidence alone.
The point is that it has to be asked.

---

## 8. Scoping correction 2 — Jolt is upstream, and what that costs

**Supersedes §7's framing, which treated Jolt and jolt-sim as one lineage.**

- **`jolt-lang/jolt` is not this project.** It is upstream. The working copy here
  is a fork kept rebased on it; changes sometimes influence upstream but do not
  belong to it.
- **`jolt-sim` is this project.**
- **Jolt will always be Clojure compatible.** That is settled upstream policy,
  not an open question.

### What this does to §1.1

§1.1 chose to fork Jolt, listing what that "keeps": the reader, analyzer, IR,
backend, deps, and build. That accounting is **incomplete in one direction**.
Those are free *once*. Staying current with an upstream that is actively moving
— this session alone found 203 commits of drift, a changed byte sign
convention, a new `System/arraycopy`, most of the `ja-*` seam, and a reworked
`bit-and` lowering — is a **permanent, recurring cost**.

And it is a recurring cost paid to track a project **committed to semantics
perturb explicitly rejects**. Every upstream change reinforcing Clojure/JVM
compatibility is churn perturb must absorb and then partly undo. §14's unsigned
bytes is not a one-off: upstream commit `e54ddb97` ("byte arrays hold signed
bytes, like the JVM's") moved *toward* the convention perturb declines, after
the record was written.

That does not overturn §1.1 — Chez, multi-shot `call/cc`, the numeric tower and
a working self-hosted compiler are real assets. But the decision was taken
against an understated cost, and the honest restatement is: **forking Jolt buys
a compiler and signs up for an indefinite rebase burden against a moving target
with divergent goals.**

### The response: draw the shared layer at semantics-neutrality

If perturb and Jolt share **lower compiler internals**, the divergence surface
— and therefore the rebase burden — shrinks to the part that actually differs.

| plausibly shared (semantics-neutral) | not shared (semantics-bearing) |
| --- | --- |
| Chez host runtime: collections, HAMT/vector-trie, string handling | `clojure.core` overlay |
| bytevector/array storage, FFI plumbing, the pointer-loan machinery | byte **accessors** (signed vs unsigned) |
| loader, deps resolution, AOT cache, image build, bootstrap seed | analyzer special forms, macro expansion |
| IR pass infrastructure (`map-ir-children`, tree walks) | effect rows, modes, binding identity |
| the toolchain (already separate — `jolt-toolchains` is the precedent) | numeric tower where it diverges |

**The byte work is the worked example, and it lands exactly on this line.** The
agent's finding was that bytevector *storage* is correct for both — it is
strictly better for Jolt too, since the FFI loan's bounce buffer exists only to
work around the vector backing — while `bytevector-s8-*` versus `-u8-*` is the
whole of the semantic difference. **Storage is shareable; the accessor is where
divergence lives.** That is a one-line boundary, and it suggests the split is
real rather than aspirational.

### The coordination problem, stated plainly

Extracting a shared host layer means restructuring **someone else's project**,
which requires upstream to want it. That is a cost this project does not
control and should not assume.

The alternative — perturb vendoring or depending on Jolt's host at a pinned
commit without asking upstream to restructure — needs no buy-in but drifts,
which is the same burden relabelled.

**Open (Q6):** which of those, and if extraction, what is the smallest layer
worth proposing upstream. The byte-backing change is a good first probe: it
benefits Jolt on its own terms (removes a bounce buffer, 7.95x less memory),
is gated, and needs no perturb-specific concept to justify it.

### Reframing this session's Jolt work

The six rebased commits on `claude/v0517-perf-rebase` are **upstream
contribution material, not perturb infrastructure**. Each stands on Jolt's own
terms — measured wins, gates green, no perturb concept involved. Two others
were dropped precisely because upstream had already done them, better. That is
the correct relationship, and it is evidence the semantics-neutral layer is
genuinely shared in practice already, even without a formal boundary.
