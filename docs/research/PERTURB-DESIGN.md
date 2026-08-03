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

‡ Two rows are the exception the sentence above does not cover: they arrived
from re-examining the argument (and, for 18, from the literature) rather than
from an artifact built to attack the claim. The commitment is stated of the
other sixteen, which came from a measurement, a probe, an independent model, or
a delegated verification. Rows 6–9 and 16 are cases where the claim had already
**passed its spot checks** and failed a probe designed to attack it — the
pattern the commitment exists to name.

**How to read this document.** §1 is the settled design, stated once in final
corrected form. §2 is the divergence register. §3 is the findings E1–E16, each
stated as currently believed rather than as first written. §4 is the open
questions. §5 is the v0 ladder. §6 is the nonclaims. Appendix A is the
correction history, Appendix B holds the superseded ladders in full, and
Appendix C maps the old chronological section numbers onto this structure —
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
measured (§4.6); the one measurement that exists is zero occurrences outside
the corpus in `perturb.nrepl` (E15).

**Known defect, now the top of this section's queue.** `:consumes` /
`:produces` name a capability and a state but not **where in the value** it
sits. E15 ran the rule set against real IR and found this is not an
expressiveness gap but a soundness hole: a primitive returning `[conn frames]`
must be modelled as returning the capability bare, so programs the checker
accepts crash when run, while the only real client perturb has cannot be
annotated at all. Positioned specs come before E13's abstract refinements.

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
at `d883385`):

| gate | what it decides | limits printed by |
| --- | --- | --- |
| `-M:selftest` | codec/octet self-tests, no socket | the run |
| `-M:check` | 17 corpus programs get their recorded capability verdicts; the real client is checked and reported, not gated | `report-limits`, 8 items (E15) |
| `-M:oracle` | perturb's bencode against `jolt.nrepl`'s over their shared profile | the run |
| `-M:demo` | one session var under a real socket and two in-memory handlers; sent octets identical | the transcript |
| `-M:noio` + `verify-noio.sh` | no syscall attributable to perturb in a scripted window, with a positive control | the verdict block (E16) |

The last two follow the rule literally: each states what its instrument *cannot*
see (`-M:noio` because `dlopen(NULL)` is invisible to strace; `-M:check` because
its accept set is only as good as the annotation language, E15).

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

E1–E16, each stated as currently believed. What each said first, and what
corrected it, is in Appendix A. E1–E13 are measurements and prototypes; E14 is
a source-and-history survey of the v0.5.17 branch lane and is `assumed`
throughout — it qualifies §1.4 and §2 row 3 without settling either.

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
- **The join-rule usability risk.** E6 probe 1's rejection of
  `if (c) { b = detach_result(b) }; use(b)` is a real usability risk for §1.2,
  and how often it fires on real programs is argued rather than measured. One
  data point now exists: on `perturb.nrepl` it fires zero times (E15).
- **~~`:local`~~ and `:extern`.** §1.1's two IR claims were inferences from
  source reading. **`:local` is now settled**: a checker walked real IR and the
  claim holds — names, no binding identity, no `:binding-id` key, no
  alpha-renaming (E15). `:extern` is still untested, and the standing pessimism
  applies to it: this session's record on inferences from source reading is
  poor. The `jolt-array` survey was wrong on scale *and* kind (E12), E3's
  central finding was sample-biased, three performance hypotheses died to
  measurement (E1, E7), and I11's `defcfn` premise was wrong (E16).
- **Positioned capability specs.** §1.2's `:consumes` / `:produces` name a
  capability and a state but not *where in the value* it is. E15 shows this is a
  soundness hole, not a convenience gap, and it now blocks annotating the only
  real client perturb has. It is the top of the §1.2 queue, ahead of E13's
  abstract refinements.
- **Machine primitives are axioms.** Nothing checks that a declared transition's
  body performs its transition (E15 blind spot 1). Unbroken since
  `mode_checker.py`; still unaddressed.
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
