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
source reading, and none from the examples a specification supplied. The record
so far: three performance hypotheses refuted by measurement (host-interop
emulation, allocation, dispatch — §1/E1, §8/E7), and two rule-set claims refuted
by probes built to attack them, both of which had passed their spot checks
(§6/E5, §7/E6). Accordingly: **no claim here should be trusted ahead of the
artifact that tests it**, and an acceptance criterion is derived from a
specification's semantics, never from its examples.

---

## 1. Findings

### E1 — Byte access through a deftype collection interface dominates codec cost

`monitored`, single non-isolated sample, unpinned toolchain (see Nonclaims).

Measured on the jolt-bencode nREPL benchmark frame (98 bytes, 10 strings):

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

**Root cause — superseded by §8/E7 and §9/E8; the original text is retained
below the correction, since it was the basis for the landed fix.**

The *dominant* cost is not on this path at all. §8 measures deftype dispatch at
~19% of `nth` on a Window and devirtualization at ~3%; §9 measures
`unchecked-byte` — one arithmetic primitive, defined in the `clojure.core`
overlay rather than as a host native — at ~209 ns/byte net, ~8x the `aget` it
wraps. Read E1's table as *where the time appeared to be*, and §8/§9 as where
it is.

What the original analysis got right, and what the landed fix addressed:
`host/chez/collections.ss` `jolt-nth` is a `cond` chain whose persistent-vector
fast path is position 2 and whose `deftype` path is position 5, reached via
`rec-coll-method` → `find-method-any-protocol`, which allocated a fresh
`hashtable-keys` vector and performed up to 2N string-keyed lookups per call for
an N-protocol type. That was real and worth removing — it is 40% of decode at
the pinned tuple (§5) — but it was the minority term, and the analysis below
claiming the residual is "two string-keyed lookups plus generic invoke" is
**wrong**; see §8.

**Two prior hypotheses are refuted by this measurement.** Host-interop `String`
emulation was hypothesized (in session) to dominate: it is 0.2% of decode.
`jolt-bytes/docs/PERFORMANCE.md` attributes the gap to allocation of "decoded
Clojure values and parser result maps": allocation is real but minor —
`window-octets` costs only ~12% more than the bare scan it wraps.

Partial fix landed as `jolt@048582c3`: flatten and memoize per-type protocol
method resolution, guarded by `jolt-proto-epoch` and per-type-table identity.
Result: 1336 → 1009 ns/byte; full decode 493 → 368 µs (25%). Gates: unit
1054/1054, devirt 12/12, pic 22/22, protoret 4/4, infer 36/36.

The residual gap to persistent-vector `nth` is two string-keyed lookups still on
the path (the `type-registry` tag hash and the method-name hash) plus generic
invoke. Closing it requires resolving collection methods to a descriptor-local
slot at registration time, which changes the `nongenerative` `jrdesc` record
layout — **not attempted**; a deliberate stop, not a completed fix.

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

### E3 — Every existing proof obligation is capability-tier

`assumed` (source inspection, complete across the repos read).

| source | proves | about |
| --- | --- | --- |
| jolt-bytes (Ansatz/CIC) | slice end preserved, slice contained, cursor reads | bounds geometry |
| jolt-hako (Z3) | builder growth prefix, bounds transactional commit, utf8 count capacity, preflight encode atomic | bounds, capacity, commit geometry |
| jolt-hako (Z3) | native lease completion, borrow generation, non-inheriting scratch | ownership, leases |
| jolt-hako (Prolog) | exclusive owner + lease lifecycle | ownership |
| jolt-bencode (Z3) | byte-string frame header, failure-consumes | framing/commit geometry |
| jolt-sim-planning P4 | capability held → consumed-by, source closure | linearity |

**No obligation is about application semantics.** Every one concerns bounds,
ownership, linearity, or commit geometry — the same tier E2's ownership systems
guard. This is the load-bearing finding for §2.2.

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

---

## 2. Decisions

### 2.1 Substrate — fork Jolt onto Chez

Keeps the reader, analyzer, IR, backend, deps, and build; keeps multi-shot
`call/cc`, the exact numeric tower, and self-hosting. OxCaml was evaluated: its
mode system (locality, uniqueness, linearity, portability, contention, yielding,
statefulness, visibility) is a static form of E2's hand-rolled systems, and its
unboxed types would address representation cost. It was not selected because
modes are a checker perturb writes over its own IR rather than a host feature it
must inherit, and because OxCaml supports no multi-shot handlers and has no
formalization of one-shot effects against its own extensions.

E1 is **not** evidence against this choice: `aget` at 54 ns/byte is adequate;
the measured gap is dispatch structure, not Chez codegen.

Required IR changes, from inspection of `jolt-core/jolt/ir.clj`:
`:local` carries a name, not binding identity — linearity checking needs
alpha-conversion or a `:binding-id`. The `:host`/`:host-static`/`:host-new`/
`:host-call` ops are untyped, un-effected escapes and should be replaced by a
single `:extern` carrying a declared effect row and signature. Note charter
rejected-alternative A1 ("annotate optimization IR: identity not durable through
passes") applies: the effect boundary's `site-id` must come from a durable
identity spine, not a pass-attached annotation.

### 2.2 Typing — two tiers

Ordinary values: static types with full inference, `Any` escape hatch. No proof
obligations, no modes; immutability makes the mode questions trivial.

Capabilities (handles, cursors, buffers, leases, continuations, mutable cells):
modes plus refinements. Axes kept: **uniqueness** (`unique`/`shared`),
**linearity** (`once`/`many`), **typestate** (role-indexed operation
legality), and **contention** (does an owner survive a thread fork). Borrows
are **exclusive** — a live lease blocks reads by the owner, not only moves —
and capabilities bind **affinely**: there is no non-moving alias.

*(Uniqueness and linearity alone were the original list; §6/E5 measured that
pair wrongly accepting 1051 of 6470 sequences and added typestate and
exclusive borrows, and §7/E6 added affine binding and restored contention.
The axes are listed here in corrected form; the evidence is in §6 and §7.)*

Axis dropped: **locality** — regions are the
specific feature that makes effects unsound (cf. arXiv 2607.15876, Yarrow:
non-local control breaks stack discipline; multi-shot handlers break
exit-at-most-once). Escape safety for loans, handler scope, and task containment
follows from uniqueness plus linearity alone, so dropping regions avoids the
unsolved interaction at no cost to the properties E2 needs.

E3 is the justification for the tier split: refinements confined to capabilities
cover 100% of the existing proof surface without dependent types over ordinary
values and without giving up inference.

### 2.3 Proof — capability-tier refinements, Ansatz retained

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

### 2.4 Effects — charter D4 retained, D3 deferred not foreclosed

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

### 2.5 v0 — port a measured slice, don't build a new one

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

Gate architecture follows `jolt-toolchains`: producer records its own claims;
an independent clean-consumer job revalidates after fresh extraction; the
verifier's limits are stated rather than implied.

---

## 3. Nonclaims

1. E1's absolute numbers are not evidence at any pinned target tuple. They were
   taken on a self-built Chez 10.4.1 (unpinned against
   `jolt-toolchains/config/toolchains.json`) and jolt at `380e59e`, not
   jolt-bencode's pinned `89fe46e8`; single sample per measurement; measured
   full decode 368–493 µs against a recorded 991 µs. Ratios are structural and
   source-corroborated; absolutes are not.
2. `jolt@048582c3` is not claimed to fix E1. It is a 25% partial; the structural
   fix is identified and deliberately not attempted.
3. Modes are **not** claimed to address the measured gap. On current evidence
   they address allocation, which is the minority term.
4. The jolt-bytes suite does not validate `048582c3`: it fails identically with
   and without the patch on this jolt commit (version mismatch, pre-existing).
   Neutrality is established; validation is not.
5. No completeness, partial-order-reduction, or Molly/LDFI claim is made or
   extended. P5 §4.2/§4.3 govern.
6. E2/E3 are source-inspection surveys over the repositories read
   (jolt, jolt-sim, jolt-sim-planning, jolt-bytes, jolt-hako, jolt-bencode,
   jolt-toolchains). `jolt-toolchain` (singular) was not readable.
7. The fork is a declared remint event. Portable artifacts survive — SMT
   families, the Prolog model, Ansatz proof closures, oracle decision tables,
   Hegel property designs. Artifacts pinned to a Jolt image do not: runtime
   gates, `verify-runtime-jolt`, evidence records naming Jolt commits, and the
   jolt-sim controller ABI work. This must be declared, never silently
   reinterpreted (charter F4).

## 4. Open questions

### Q1 — residual dispatch gap: likely no layout change (was: may require remint)

`jrdesc` is `(fields tag fkeys index (mutable ptable))`,
`(nongenerative chez-jrdesc-v3)`. A descriptor-local eq-keyed method cache
therefore **already exists**, with invalidation already wired: `ptable` is `#f`
until the first `register-protocol-method`, and a stale pre-redef descriptor has
it reset to `#f` so lookups fall back to the string registry. It is keyed by
`intern-pm-key proto method`.

The collection fallback cannot use it only because `rec-coll-method` knows the
method name but not the supplying protocol. Two resolutions:

- (a) add a method-name-keyed table → `chez-jrdesc-v4`, a remint;
- (b) store the any-protocol resolution in the **existing** ptable under a
  reserved pseudo-protocol key (`intern-pm-key` with a sentinel proto name that
  cannot collide) → no field added, no version bump.

Under (b) the path is `jrec-desc` (immutable field read) → `jrdesc-ptable`
(field read) → one eq-hashtable lookup on a **module-level precomputed interned
key**. No string hashing remains.

Open sub-questions: `intern-pm-key` must not hash per call, which is why keys
for the collection methods (`nth`, `count`, `assoc`, `cons`, `seq`) are minted
once at module scope; and host tags (`"String"`, `"Object"`) have no desc, so
the string-registry fallback must remain for them.

**Status:** downgraded from blocking. Attempted in 2.5 step 2.

### Q2 — refinements over HM: not a gradual-typing boundary (framing corrected)

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
corroboration of the §2.2 tier split.

Remaining risks: the `Any` escape hatch requires runtime checks where refined
meets unrefined; inference for higher-order and polymorphic-recursive code is
where liquid typing is least comfortable in practice.

**Status: both risks addressed in §16/E13.** `Any` resolves by confinement — a
capability is minted by an operation, never parsed from data, so it is never
`Any`. The higher-order risk splits: the sans-io driver types at a fixed
refinement, transducers over refined elements do not and need abstract
refinements of arity ≥ 2. See §16.

### Q3 — `unique` × multi-shot: under-specified, and not currently load-bearing

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

**Status:** not blocking. §2.4 defers D3; Q3 becomes load-bearing only if D3 is
taken up.

### Q4 — macro provenance: mechanism specified, reconstruction deferred

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

---

## 5. Measurement update — E1 re-measured at the pinned target tuple

**This section supersedes E1's numbers and Nonclaims 1, 2, and 4.**

Target tuple now satisfied on both halves. Chez built from
`e95a7efbafa2cf3bd5343ea542e6bc909a7ab2c4` with all five submodules matching
`jolt-toolchains/config/toolchains.json` exactly (lz4 `ebb370ca`, nanopass
`bb47b569`, stex `afa60756`, zlib `da607da7`, zuo `a288cbfe`); Jolt at
jolt-bencode's pinned `89fe46e8a826b60b69d264fab76c864881055830`.

**The setup reproduces the recorded baseline.** Full decode measures
985,225 ns against `jolt-bencode/docs/PERFORMANCE.md`'s recorded 991,008 ns —
0.6%. E1 is therefore promoted from `monitored` indication to a measurement at
the target tuple.

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

**Correctness validated, not merely neutral.** On the pinned image with the
patch applied: jolt-bytes `:status :verified`, 132,672 assertions (969 parents,
20,349 slices, 2,601 cursor reads, 4,845 compositions, 825 overlap cases);
jolt-bencode 13 tests, 109,209 assertions, 0 failures; Jolt unit gate 1127/1127.
The earlier jolt-bytes failure was purely version skew, as suspected.

**Jolt has already improved this path independently.** The same `nth` costs
4,326 ns/byte at `89fe46e8` and 1,336 at `380e59e` — a 3.2x gain from work
between those commits. The 25% figure recorded against `380e59e` understated
the patch because much of the win was already captured there; at the pin where
the recorded baseline lives, it is 40%.

**Superseded nonclaims.**

1. (was: absolutes are not evidence at any pinned tuple) — now measured at the
   tuple and reproducing the recorded baseline to 0.6%. Still a single
   non-isolated sample on one machine, and no cross-platform claim is made.
2. (was: `048582c3` is not claimed to fix E1) — the pair `048582c3`+`31cf9de0`
   is a 40% end-to-end reduction at the pin. It does **not** close the gap:
   `nth` on a deftype remains 2,061 ns/byte against 86 for a persistent vector,
   ~24x. Method resolution is no longer the dominant term; the remainder is the
   `jolt-nth` cond preamble and generic `jolt-invoke`, which needs call-site
   devirtualization for collection-interface methods in the backend — a
   compiler change, not attempted.
4. (was: jolt-bytes does not validate the patch) — it does, at the pin.

Nonclaim 3 stands unchanged and is reinforced: allocation was never the
dominant term, so modes remain unjustified by this measurement.

**Q1 status:** resolution (b) implemented — the flat table is cached in the
descriptor's own `ptable` under a reserved gensym key, no `chez-jrdesc-v3`
bump, no remint. A weak side table keyed by descriptor measured within noise
(836 vs 863 ns/byte at `380e59e`); the ptable form was kept for its structural
properties. Q1's remaining sub-question — whether closing the rest requires
backend devirtualization — is answered yes, and is now the open item.

---

## 6. E5 — the capability tier needs three disciplines, not two

**This section qualifies §2.2 and §2.3.**

`docs/research/prototypes/mode_checker.py` implements a rule set for the
capability tier; `equivalence.py` transcribes `jolt-hako/proofs/prolog/
ownership.pl` `step/3` and compares verdicts over **every** operation sequence
to a given depth, rather than the 9 traces `queries.json` spot-checks.

The successive results, each a genuine correction:

| rule set | unsound acceptances, depth 8 |
| --- | ---: |
| uniqueness + linearity (as §2.2 specifies) | **1051 / 6470** |
| + typestate (role-indexed operation legality) | 103 |
| + exclusive borrow (a lease blocks reads, not only moves) | **0** |

Final state: depth 8 — 6,470 sequences examined, 1,236 accepted by both, zero
disagreements in either direction. Depth 10 — 23,430 examined, 4,406 accepted
by both, zero disagreements. `queries.json`: 9/9 decided as recorded.

### What the two corrections mean

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

### Consequences for §2.2 / §2.3

§2.2's axis list — "Axes kept: uniqueness, linearity" — is **insufficient** as
stated. The capability tier requires uniqueness, linearity, and typestate, with
borrows exclusive by default.

§2.3's claim that capability-tier refinements "cover 100% of the existing proof
surface" survives but is now qualified: coverage requires the typestate axis,
which E3's survey did not name because the obligations it catalogued
(bounds, ownership, linearity, commit geometry) hide role sequencing inside
"ownership". Bounds and commit geometry remain refinement obligations; role
sequencing is a separate typing judgement.

### What this does and does not establish

Establishes: a syntactic type discipline decides, without state-space search,
exactly what `ownership.pl` decides by bounded reachability — over the whole
space to depth 10, not merely the recorded queries. Where the model answers
"no double ownership within 8 steps" by exhaustion, the environment holds one
owner field, so `writer_result` is unrepresentable; that specific query becomes
structural rather than bounded-complete.

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

---

## 7. E6 — three probes past the straight-line fragment

E5's rule set checks a straight-line operation sequence over one unnamed
capability. Three probes push past that. Prototypes: `controlflow.py`,
`multicap.py`.

### Probe 1 — branching and loops: the rule set survives

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
are a real usability risk for §2.2.

### Probe 2 — names and aliasing: E5's structural claim was too strong

`multicap.py` adds named capabilities, moves, and function signatures
(`consume`/`borrow`/`produce`). Seven cases passed immediately. The eighth
**failed**, and it matters:

```
new a; alias b = a; a.detach_result; b.return_pool     -- ACCEPTED (unsound)
```

Two names for one capability, moved to two different roles. That reconstructs
`writer_result` — the exact two-owner state hako's `double-owner-bug.pl`
injects, and the state §6 called *unrepresentable*.

**§6's structural claim holds only for the single-capability fragment.** `Env`
has one owner field, so one capability cannot have two owners; but two *names*
can hold one capability and diverge. Uniqueness must therefore be enforced at
the **binding form**, not only at operations — capabilities bind **affinely**,
with no non-moving alias. With that rule the probe is 8/8, and E5's equivalence
(depth 10, zero disagreements), probe 1 (12/12), and the recorded queries (9/9)
all still hold.

This is the second time a claim survived its spot checks and failed a probe
designed to attack it.

### Probe 3 — the SMT families: modes cover two of seven

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
consistent with §2.3's split, and confirming both halves are load-bearing rather
than one subsuming the other.

**The seventh contradicts §2.2.** `noninheriting-scratch-corrected.smt2`
declares `parent_thread`, `child_thread`, `inherited_owner`, `inherited_scratch`
and asserts an `alias_violation` — whether an owner-tagged scratch buffer is
inherited across a thread fork. That is a **contention/portability** obligation,
and §2.2 cut that axis on the grounds that it is "only needed if you want real
shared-memory parallelism" which explicit message passing would sidestep. It is
in the *current* proof surface regardless, so the axis is restored in §2.2.

### Net effect on the axis list

| §2.2 as first written | after §6 and §7 |
| --- | --- |
| uniqueness, linearity | uniqueness, linearity, typestate, contention |
| (borrow blocks moves) | borrows **exclusive** — block reads too |
| (no binding rule) | capabilities bind **affinely** |
| locality dropped | locality still dropped (Yarrow interaction stands) |

Every addition came from a probe designed to break the previous claim, and none
from the spot checks the specification supplied. That pattern is now the method
note in §6, restated: derive the acceptance criterion from the specification's
own semantics, not from its examples.

---

## 8. E7 — devirtualization is worth ~3%, not 24x; the cost is the method body

**This supersedes §5's closing attribution and retires the backend-devirtualization
work item.**

§5 attributed the residual gap to "the `jolt-nth` cond preamble and generic
`jolt-invoke`" and proposed call-site devirtualization for collection-interface
methods. Investigation found a real structural blocker for that proposal:
`passes/types.clj` attaches `:devirt-type` only when the callee resolves through
`env`'s `:protocol-methods` — user-defined protocol methods. `nth` is
`clojure.core/nth`, so it never gets `:proto`/`:method`, never devirtualizes,
and never receives even a PIC.

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
representation question of §2.2, not the call-site question.

**Retired:** backend devirtualization for collection-interface methods. It
would buy ~3% and requires changing how core collection fns are recognized in
the type pass.

**Redirected:** the open performance question is now whether deftype method
bodies can get unboxed fixnum arithmetic and direct array indexing. Not
investigated.

### Method note, third instance

This is the third hypothesis of mine that measurement refuted:

1. host-interop `String` emulation dominates → it is 0.2% (§1/E1);
2. allocation dominates, per `PERFORMANCE.md` → it is the minority term (§1/E1);
3. dispatch dominates, so devirtualization is the fix → dispatch is 19%,
   devirtualization worth 3% (this section).

Each was plausible from source reading and each was wrong. The pattern matches
§6's finding on the rule set: what survived was always what was tested against
an independent measurement or specification, never what was argued from
inspection. No performance claim in this document should be trusted ahead of a
number.

---

## 9. E8 — the dominant byte-path cost is `unchecked-byte`, a Clojure-level defn

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

### Cause

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

### Proposed fix (not attempted)

Implement `unchecked-byte`, `unchecked-short`, and `unchecked-char` as host
natives alongside the existing `unchecked-long`/`unchecked-int`. Small and
local. **Not attempted here** because it edits the `clojure.core` overlay, which
per the Jolt README requires `make remint` to iterate the bootstrap seed to a
byte fixpoint — too long to start and verify within the remaining session.

### Attribution chain

Pinned full bencode decode 985 µs → ~70–80% byte access → `nth` on Window
1145 ns/byte → 74% method body → 72% of the body is `signed-byte-at` → ~80% of
that is `unchecked-byte`. On those proportions `unchecked-byte` is a
double-digit percentage of total decode time, for one arithmetic primitive.

This is a **Jolt finding independent of perturb**, and it is more actionable
than anything the devirtualization line would have produced.

### Consequence for §2.1

§2.1 says "E1 is **not** evidence against this choice: `aget` at 54 ns/byte is
adequate; the measured gap is dispatch structure, not Chez codegen." E7 and E8
show the gap is neither dispatch nor Chez codegen — it is **which primitives
Jolt implements natively versus in its own core overlay**. The conclusion (Chez
is not the problem) survives, and is in fact strengthened: `aget` at 54 ns/byte
and a function call at 18 ns/byte are both fine. The stated *reason* was wrong
and is corrected here.

---

## 10. E9 — the fix landed; and this host's noise floor is ~10%

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

### The measurement finding, which matters more

End-to-end decode after the fix measured 437, then 395, 421, 432 µs on
repeated runs of the **same build** — a ~10% spread. So:

**No end-to-end improvement is claimed for E9.** The primitive-level gains
(3.7x on `unchecked-byte`, 2.1x on the standalone body) are far outside that
band; the aggregate is not resolvable from single samples on this host.

This retroactively qualifies earlier single-sample comparisons in this
document:

| comparison | delta | verdict at a ~10% floor |
| --- | ---: | --- |
| §5 pinned A/B, 985 → 596 µs | −40% | **survives** |
| §1/E1, 493 → 368 µs | −25% | probably survives; single samples |
| §5, descriptor-keyed commit 1009 → 863 ns/byte | −14% | **marginal** |
| §5, "full decode unchanged at ~370 µs" | ~0% | **within noise; not evidence** |

The `jolt@31cf9de0` commit message states 863 vs 836 as "within noise" for the
weak-table variant — correctly — but treats 1009 → 863 as real. At this floor
that too is marginal. The commit stands on its structural properties (no leak,
invalidation already wired), which were the stated reason for keeping it.

**Standing correction:** every ns/byte figure in this document is a single
non-isolated sample unless stated otherwise, on a host with a ~10% run-to-run
spread. Ratios of 2x and above are safe; anything under ~20% needs repeated
sampling on a quiet machine before it is evidence. The §5 pinned-tuple A/B
remains the only comparison here taken against a reproduced published baseline.

---

## 11. E10 — the pinned A/B with repeated sampling

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

### Correction to §5's reproduction claim

§5 stated the setup "reproduces the recorded baseline to within 0.6%" —
985,225 ns measured against `PERFORMANCE.md`'s 991,008. With 5 runs the baseline
median is **1,211,841 ns**, 22% above the recorded figure. The 0.6% agreement
was a single sample landing near the recorded value; it was luck, not
reproduction.

What survives: the setup is at the pinned tuple, the arms are identical apart
from the change under test, and the control row holds. What does not: any claim
that this host reproduces the published absolute. Cross-host absolutes should
not be compared at all from these measurements — only within-arm deltas taken
in the same session.

That makes E10 the second correction to arrive from repeated sampling alone
(E9's ~10% floor was the first), and both invalidated numbers that had been
stated with more confidence than a single sample can carry.

---

## 12. E11 — `aget` is generic dispatch, and a byte array costs 8 bytes per element

Both found with `jolt.perf` (`jolt@aa278165`, `73dc9aee`), statically or with a
deterministic counter. Neither needed a benchmark.

### `aget` is not an array read

```
(fn [b i] (aget b i))  →  (lambda (b i) (jolt-nth b i))
```

An array read lowers to the generic collection dispatch — the same `jolt-nth`
`cond` chain §1/E1 and §5 optimised. The 54 ns/byte that E1 recorded as the
"floor" and §2.1 cited as evidence that Chez codegen is adequate is therefore
not a primitive read at all; it is generic dispatch that happens to hit an
early `cond` arm.

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

### A byte array is eight bytes per element

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
jolt-sim depends on (§1/E2). **Not attempted.**

### Method note, fourth instance

E1's table named `aget` at 54 ns/byte as the floor and §2.1 leaned on it to
conclude "the measured gap is dispatch structure, not Chez codegen." Both
sentences were built on the assumption that `aget` is an array read. One line
of `optimized-scheme` output shows it is not, and one counter reading shows the
array is eight times larger than assumed. Neither fact is visible from timing,
which is why ten sections of timing did not surface them.

---

## 13. E12 — encapsulating the array backing, and two semantics carried forward

`jolt@57980315` routed all `jolt-array` backing access through `ja-*` helpers,
as the safe precondition for changing the byte backing. Delegated to a
subagent; the results correct this document twice.

### My survey was wrong in two ways

Recorded in §12 as "~35 direct uses across 10 files". Verified: **41 raw
operations across 7 files**, all under `host/chez/java/`. Three of the ten
files I named have no backing access at all — `natives-coll.ss`'s `jolt-array`
hits are `jolt-array-map`, an unrelated name collision with Clojure's
`array-map`; `records.ss` uses only `jolt-array?`/`jolt-array-kind`; `io.ss`
has none.

More seriously, **I framed the problem as reads and it is not**. Six
*construction* sites hand-build byte backings outside `na-byte-array` —
`ByteBuffer/allocate`, `/allocateDirect`, `.slice`, `jolt.ffi/read-array`,
`io/copy`'s output-stream shim, and `Files/readAllBytes` — using
`(make-vector n 0)` or `(list->vector (bytevector->u8-list bv))` directly.
Those would have broken under a representation change exactly as badly as the
reads, and nothing in a grep for read operations would have surfaced them.

### Two semantics that must be decided, not discovered

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

### Method note, fifth instance — now about delegation

The delegated agent verified the file list rather than trusting the brief, and
the brief was wrong. It also found a category of site the brief's framing
excluded by construction. The pattern from §6 and §8 extends: **a brief is a
specification, and its examples are not its semantics.** Delegation is not
exempt — an agent told what to change will change that; an agent told what
property must hold will find what the instructions missed. The instruction that
did the work here was "an honest inventory of the remaining hard cases is a
valuable deliverable, do not force-fit those", which licensed reporting over
compliance.

---

## 14. Decision — perturb's byte type is unsigned octets

Added to §2.2's capability tier. **perturb has no JVM signed-`byte[]`
compatibility obligation.** A byte is an octet, `0..255`. Codecs do not perform
sign folding to read a byte.

### This is charter-consistent, not a new departure

Charter §1.3 non-goal 1 already disclaims exactly this class: "The core does
not preserve any host's implementation accidents — JVM UTF-16 surrogate
splitting, **JVM primitive overflow behavior**, Chez-specific integer widths".
Java's signed `byte` is that accident — a language decision from 1995 with no
representational justification, since the storage is octets either way and
signedness is only a read-time interpretation.

### The cost was measured, not assumed

`jolt-bytes` documents the friction directly: *"Jolt stores byte-array slots as
unboxed octets while the JVM exposes `byte[]` slots as signed bytes.
`unchecked-byte` makes traversal identical."* So every byte read in the codec
path calls `unchecked-byte` **purely to reproduce a JVM convention**.

§9/E8 measured that call at **209 ns/byte** before nativisation — the single
dominant term in `Window/nth`'s body, ~8x the `aget` it wrapped. The JVM signed-
byte accident had a measurable, dominant cost on precisely the path this whole
performance line has been about, and it bought nothing except compatibility
with a host perturb does not target.

That is the cleanest instance in this document of a host accident charging rent.
E2 catalogued five hand-rolled ownership systems as the cost of a *missing*
language feature; this is the cost of an *inherited* one.

### Consequence for the two-tier design

The tier split (§2.2) already says ordinary values get no modes and
capabilities get modes plus refinements. Byte views are capability-tier, so
their representation is perturb's to choose: bytevector-backed, unsigned, no
conversion at the accessor.

This also settles a question §2.3 left open. Refinements on a byte view can
state `0 ≤ b ≤ 255` as a *type*, decidable in QF-LIA. Under signed semantics the
same property needs a case split on the sign fold — the same obligation, harder
to discharge, for no gain.

### Scope note

None of this applies to Jolt itself, which does have a compatibility target.
The concurrent Jolt work preserves `byte[]` semantics exactly and aims to make
that path fast too — a bytevector backing with read-time conversion, since
signedness is interpretation rather than storage. Two byte types in Jolt would
be a compatibility accommodation; perturb needs only one, and it is the fast one.

---

## 15. Decision — two layers: perturb's core, and `clojure.*` compatibility on top

**perturb's core/prelude** offers a surface comparable to `clojure.core` —
familiar and ergonomic to a Clojure developer, same names, same shapes — but
hews to **perturb semantics** wherever they differ.

**`clojure.*` namespaces** are a separate compatibility layer that maintains
Clojure semantics *on top of* perturb. They are opt-in, not the default.

### What this resolves

The charter is written as a "Clojure.next Application Core", which left it
ambiguous whether perturb inherits a semantics or defines one. This settles it:
**perturb defines, and compatibility is a library.** §14's unsigned-byte
decision is the first worked instance rather than a one-off exception — the
charter's own §1.3 non-goal 1 (no host-accident canonization) becomes the
default posture of the core, with `clojure.*` as the place accidents are
reproduced for those who need them.

### Where the rent lands, and why that is the right place

§14 measured the JVM signed-byte convention costing 209 ns/byte on the codec
path. Under this layering that cost moves into `clojure.*` and is paid only by
code that asks for Clojure semantics. Code on perturb's core pays nothing. The
cost becomes **opt-in and visible** rather than ambient and invisible, which is
the property E8 showed was missing — nobody chose to pay 209 ns/byte, it was
simply inherited.

### The rule this imposes on the core

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

### The hazard: same names, different semantics

Identical spelling with divergent behaviour is a real footgun — a Clojure
developer's muscle memory will be correct about shape and can be silently wrong
about behaviour. Two divergences are already known:

| name | Clojure | perturb |
| --- | --- | --- |
| byte access | signed, -128..127 | unsigned octet, 0..255 (§14) |
| `map`/`filter`/sequence ops | lazy, chunked | **eager**; laziness opt-in (charter §1.2 H4) |

Neither is discoverable from a call site. So this layering requires an
**enumerated divergence register** — every name whose semantics differ from
`clojure.core`, with the difference stated. The charter already specifies the
mechanism in §1.4: feature → classification → semantics location → support
level, with a Notes column naming exact unsupported variants. The register is
that matrix with a divergence column, and it is a release obligation, not
documentation hygiene: an unenumerated divergence is indistinguishable from a
bug.

### Consequence for the v0 ladder

§2.5's slice imports `jolt.bytes`/`jolt.bencode` semantics. Under this layering
the port targets **perturb's core**, not `clojure.*` — so the bencode decoder
reads octets directly and the `unchecked-byte` fold disappears from the path
rather than being reimplemented. The existing oracle corpora remain valid as
*value* tests (they pin decoded results, not byte representation), but any
corpus row asserting a signed byte is testing `clojure.*`, not perturb, and
must be reclassified rather than ported.

### 15.1 Sequencing — `clojure.*` is deferred, the rule is not

Exact Clojure compatibility is **not an initial priority**. The `clojure.*`
layer is a later stage; nothing in the v0 ladder (§2.5) implements or tests it.

This frees the core's design — no divergence has to prove itself against a
working compatibility layer before being taken. But it sharpens one thing and
weakens nothing:

**Deferring the layer removes the artifact that tests the no-foreclosure rule.**
§15 states that the core must not make Clojure semantics inexpressible, only
non-default. With `clojure.*` unimplemented, that rule has no executable check —
and this document's standing commitment is precisely that an untested rule is
the kind that gets refuted later. Every divergence taken between now and the
layer's existence is an unverified claim that compat remains implementable.

Mitigation, and it is cheap only at decision time: **when a divergence is
decided, record alongside it the one-line sketch of how `clojure.*` would
recover the Clojure behaviour.** For §14 that sketch is "sign-fold on read, since
storage is octets". Writing that when the decision is fresh costs a sentence;
reconstructing it years later, against a core built on unexamined assumptions,
is how compatibility layers turn out to be impossible.

So the divergence register (§15) is not documentation of a shipped layer — it is
the **only artifact carrying the compat design** until the layer exists, and it
should be maintained from the first divergence rather than started when
`clojure.*` does.

**Open question (Q5):** is `clojure.*` deferred or optional? "Not an initial
priority" implies later, and the no-foreclosure rule is kept on that reading. If
exact compatibility is ultimately *not wanted*, the rule can be dropped and the
core gains real freedom — laziness, equality, numeric tower, and error model all
have cheaper designs without it. Worth settling before the core's semantics are
far enough along that the answer stops being free.

### 15.2 Q5 resolved — the rule is a defeasible default

Neither "deferred" nor "optional". No-foreclosure is a **goal and a rule, held
by default, breakable later for a sufficiently interesting gain.**

That is a third posture and it changes the mitigation, because the risk is no
longer "compat silently becomes impossible" — it is **"compat gets broken by
drift rather than by decision."** A rule you would knowingly trade away is only
useful if you can see the price at the moment you would pay it.

So the divergence register (§15) needs a third column. Per divergence:

1. **What differs** from `clojure.core`.
2. **How `clojure.*` would recover it** — the compat sketch (§15.1).
3. **What breaking compat here would unlock** — the gain that might justify it.

Column 3 is what makes the rule defeasible on purpose. Without it a break is
indistinguishable from an oversight, and a series of individually small,
individually unexamined decisions is exactly how a compatibility layer turns out
to be impossible without anyone having chosen that.

Worked example, §14's bytes — the rule is *not* broken:

| | |
| --- | --- |
| differs | byte is an unsigned octet `0..255`, not signed `-128..127` |
| compat sketch | sign-fold on read; storage is octets either way |
| would unlock | nothing further — compat costs one fold on the `clojure.*` path only |

Since column 2 is cheap and column 3 is empty, there is no trade to make and the
rule holds for free. **That is the common case, and it is worth recording
precisely so the uncommon case stands out.**

Two asymmetries to carry:

- **Breaking is cheap at decision time and expensive to reverse.** A divergence
  that forecloses compat is a sentence of design today and a rewrite later, so
  the register should flag divergences that put compat *at risk*, not only those
  that break it.
- **Column 3 being empty is the signal to hold the rule.** If a divergence
  unlocks nothing beyond itself, keeping compat expressible is free and should
  not be traded away casually — the reason to break the rule has to be a named
  gain, not a general preference for freedom.

Candidates where column 3 is plausibly non-empty, and where this will matter
first: laziness (charter H4 already diverges), equality and hashing, the numeric
tower, and the error model. Each should get its register row when decided, not
retrospectively.

### 15.3 Divergence register — row 2: eager sequences

Charter §1.2 H4 already decides this and perturb adopts it unchanged: the core
is **eager/transducer-first**. Default sequence operations are eager and strict,
transducers are the composable primitive, `into`/`transduce`-style eager drivers
are the canonical consumers, and **laziness is opt-in** via explicit lazy
stream/generator constructors with defined realization, exception, cancellation,
and resource semantics.

| | |
| --- | --- |
| **differs** | `map`/`filter`/`take` etc. are eager and strict; Clojure's are lazy and chunked |
| **compat sketch** | `clojure.*` sequence ops are perturb's opt-in lazy-stream constructors — recoverable *modulo chunk-size observability*, which charter §1.2 already classifies as a realization detail rather than core semantics |
| **would unlock** | nothing beyond itself — laziness stays expressible, so no trade |

Column 3 empty again, so the rule holds for free — the same shape as §14's
bytes, and the second consecutive case where §15's constraint costs nothing.

### The charter satisfied the rule before the rule existed

H4 says laziness is **opt-in**, not **absent**. That distinction is exactly
§15's no-foreclosure rule: the core declines laziness as a default without
making it inexpressible. The charter arrived at the required shape independently,
which is mild evidence the rule is naturally satisfiable rather than a
constraint that will keep binding.

### Why eager-first is stronger for perturb than for Jolt

The charter argues H4 on ergonomics and predictability. perturb has a sharper
reason the charter did not need: **lazy realization is nondeterminism that does
not go through the oracle.**

§2.4 makes the whole simulation architecture rest on a single nondeterminism
source — every choice comes from one `choose`, the trace is the sequence of
answers, replay is feeding them back. A pervasively lazy sequence breaks that:
the point at which an element realizes is determined by *consumption*, not by
program order, so any effect inside a lazy computation fires at a time no
component chose and the oracle never saw. Realization timing becomes an
unmodelled scheduling decision.

That is not a performance argument, it is a correctness one for the determinism
claim. Pervasive laziness and §2.4's single-oracle model are incompatible;
eager-by-default with laziness as a declared, bounded construct is what makes
them coexist. An opt-in lazy stream is fine precisely because opting in is
visible — the realization points are where the program says they are.

This also constrains the opt-in form: a perturb lazy stream must have
realization semantics defined well enough that its realization points are
either deterministic or routed through the oracle. The charter already requires
"defined realization, exception, cancellation, and resource semantics" for the
lazy constructors; §2.4 is why that requirement is load-bearing rather than
tidy.

### 15.4 Divergence register — row 3: equality, and what hashing turned out not to be

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

| | |
| --- | --- |
| **differs** | Clojure's `=` is IEEE on NaN, so `(= ##NaN ##NaN)` is `false` and a NaN key can never be looked up in a map containing it |
| **compat sketch** | `clojure.core/=` is perturb's `==` on doubles, `=` elsewhere |
| **would unlock** | reflexivity, hence working collections and agreement with `compare` |

Column 3 is non-empty for the first time, but the gain is *correctness*, not
performance: `=` becomes an equivalence relation, which is what both hash-based
collections and `compare` already assume. The charter's own `compare` rule is
evidence the total reading is the one actually wanted.

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
versions or implementations"; §2.4 needs "identical within a build given the
same operation sequence", which a deterministic hash over a deterministic HAMT
already provides. This is the §15.3 argument again — varying iteration order is
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
   so every map lookup would carry a non-empty effect row and §2.2's
   no-obligations value tier collapses.
3. **It is the hottest path in the language**, and §9/E8 measured what one
   var-deref-plus-invoke costs when paid per element.

The useful residue is the constraint the failure reveals: equality and hash must
be carried by the value or its type, fixed at construction. Which is what §15's
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

---

## 16. Q2 partially resolved, and a sampling bias in E3

### Q2 — the `Any` risk closes by confinement

§2.2 gives ordinary values inference plus an `Any` escape hatch, and the
standard worry is that refined-meets-unrefined needs runtime checks or leaks
soundness. But refinements live only on the capability tier (§2.3), so the
dangerous case is not `Any` in the value tier — it is `Any` reaching a
*capability* position. Prohibit that: **a capability position never accepts
`Any`.** There is then no refined/unrefined boundary to be unsound at.

The cost looks nil, because capabilities are **minted by operations**
(`Net/connect`, `acquire-native`, `cursor`) rather than parsed from data, so
they arrive typed by construction. Deserialisation cannot produce one.

The higher-order half stays open and is under test (`prototypes/`, in progress):
whether a driver taking a refined step function as an argument types without
dependent function types or abstract refinements.

### E3's survey was biased by its sample

E3 concluded: "**No obligation is about application semantics.** Every one
concerns bounds, ownership, linearity, or commit geometry." That is the
load-bearing finding for §2.2's tier split, and it is **true of the sample and
not of the domain.**

The sample was `jolt-bytes`, `jolt-hako`, `jolt-bencode`, and P4 — byte buffers,
codecs, and a mailbox. Codec libraries *have* no application semantics; finding
none is close to tautological. E3 is evidence about what a codec needs. It is
**not** evidence about what a consensus protocol or a search tree needs, and it
was used as though it were.

This matters because four consecutive axes (bytes, sequences, hashing, `Any`)
resolved by appeal to tier confinement. Either the split is doing real work, or
the same move keeps succeeding because everything examined so far lives on one
side of it. Codecs are exactly the workload that would hide the difference.

### Three property classes, only one of which is covered

| class | example | mechanism | status |
| --- | --- | --- | --- |
| **resource safety** | bounds, ownership, leases, commit geometry | modes + QF-LIA refinements | designed (§2.2/§2.3), E3's whole sample |
| **structural / inductive** | tree balance, in-order traversal sorted, acyclicity | inductive predicates or dependent types | **not covered** — QF-LIA cannot quantify over subtrees |
| **temporal / behavioural** | election safety, log matching, eventual commit | trace monitors, refinement relations, liveness under fairness | partly covered |

Structural invariants are the clearest gap. `0 ≤ offset ≤ capacity` is QF-LIA;
"every path from the root has equal black height" is not, and no amount of
capability-tier refinement reaches it. That is Ansatz/CIC territory — which the
stack already has (§ the proof survey), but which §2.3 treated as a residual for
"obligations outside the type system" rather than as a first-class tier.

Temporal properties are partly covered: safety properties fold over a trace,
which `monitor.clj` already does. **Liveness does not** — it needs fairness
assumptions and either bounded response (P4's seed) or a temporal logic, and
P4's own approval explicitly is "not a SAT/UNSAT result". Refinement relations
— showing an implementation refines a spec, which is how consensus protocols
are actually proved — are gestured at by P3 and bounded by P5 §4.2 to
`explore_states` alone.

### What this does and does not change

**Does not change:** §2.4's machinery. Effects, the kernel, the single oracle,
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
integration and, for liveness, genuine design.

### Consequence for v0

§2.5's ladder is entirely codec-shaped, which is how the bias got in. It should
gain a **non-codec target** before the typing decisions harden — one that
exercises the kernel, faults, and a liveness property, none of which bencode
touches. P4's capacity-one mailbox is the cheapest candidate since it is already
fully specified, with a small leader election as the more honest one.

---

## 16. E13 — the driver types; transducers need abstract refinements

**This section qualifies §2.2 and §2.3, and closes the second half of Q2.**

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

### The driver types, against the step function as a parameter

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

### Three things the driver revealed that the design did not have

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
This is an obligation *outside* the type system, which §2.3 already reserves
for Ansatz; it is recorded so it is not later mistaken for something the
checker discharges.

**`Step` is not closed under retry-wrapping.** The natural combinator
`retrying : Step -> Step`, which hides `:need-more` by refilling, **does not
type**: the contract pins the result window to the *argument's* window, and
refilling replaces it. The retry loop therefore has to be the driver, which
threads the new window explicitly — as the real driver does. This is a design
consequence, not a defect, and it is the shape §2.5 step 5's session type must
respect.

### The transactional property is expressible — but only up to structure

`read-window` and `decode` return **the identical original Cursor** on a
non-`:ok` result (`jolt-bytes` gives `Cursor` an explicit `identical?` equality
contract; `jolt-bencode`'s docstring says "the identical original Cursor").

A refinement over field values can state that the returned cursor carries the
same window and the same position. It **cannot** state that it is the same
object: with a ghost identity field added, structural equality provably does
not imply identity. So the strongest available refinement is satisfied by an
implementation that returns a *copy*.

This does not break the property; it relocates it. Under §7/E6's affine
binding a `unique` Cursor is moved into `decode`, so no one is holding the
original to compare against, and field equality is the whole observable
content of "nothing was consumed". **Identity is a mode-tier notion and
structure is a refinement-tier one, and the transactional property needs
both** — the third independent instance (after §6/E5's typestate and §7/E6
probe 3's contention) of an obligation that looked like one tier's job and
required the other.

### Transducers do not type, and the machinery they need has a name

Charter §1.2 H4 (adopted in §15.3) makes transducers the composable primitive.
Composing refined steps was measured directly:

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
solving over a qualifier set. §2.2's phrase "without giving up inference" is
therefore too strong at the combinator boundary, though the leaves stay
QF-UFLIA and decidable.

**Arity is the sharp part.** A zero-copy element — the decoded value *is* a
sub-`Window` of the input buffer rather than a copy out of it — has a
refinement that mentions **the step function's own cursor binder**. An arity-1
predicate `p :: Window -> Bool` cannot see it. The requirement is
`p :: Cursor -> Window -> Bool`, an abstract refinement *parameterised by a
binder internal to another type in the signature*. That is the most exotic
thing this probe found, and it is not in §2.2 or §2.3 in any form.

### Does §2.2's confinement claim survive?

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
the confinement claim holds. §8/E7 and §12/E11 push toward the opposite: a
zero-copy decoder returning sub-`Window`s is the natural end of that line, and
it puts refined capabilities into the element stream, where the arity-2
abstract-refinement requirement bites.

So the tier split is not threatened, but its cheapness is contingent. The
honest statement for §2.2 is: *confinement holds while the capability tier
terminates at the driver; a zero-copy element type moves the boundary into the
transducer chain and costs abstract refinements of arity ≥ 2.* That trade
should be made deliberately, at the point the zero-copy decoder is decided,
not discovered afterwards.

### Method note, sixth instance

One expectation here was refuted by the artifact, in the small: a case
asserting that a fresh-window step and the same-window `Step` are incomparable
was written with the fresh step guaranteeing `position >= 1`, and the solver
showed `Step` really *is* a subtype of it. Making the fresh step do what a
de-framing stage actually does — hand back a cursor at position 0 — produced a
genuinely incomparable pair. Small, but the same shape as §6, §8 and §12: the
claim survived inspection and failed the check.

### Nonclaims

1. Checking, not inference. No claim that liquid inference would find these
   annotations, and the abstract refinements are instantiated by hand.
2. The solver is incomplete over the integers; "fails" means "not proved",
   and each negative was inspected by hand for that failure mode.
3. One driver shape and one transducer shape. `partition-by`, early
   termination via `reduced`, and stateful transducers whose state is itself a
   capability are untested.
4. Nothing here touches linearity or typestate; those stages are §6 and §7.
   The interaction of abstract refinements with affine binding is untested.
5. The Python model encodes objects field-wise into integers. Descriptor
   identity is deliberately not representable, which is the subject of one of
   the findings rather than an artifact of the encoding.

---

## 17. Revised v0 targets — dogfood the runtime, not a model

**Supersedes §16's closing recommendation** (P4's capacity-one mailbox, or a
small leader election). Both are models. The runtime already contains better
targets, and they are things perturb needs early regardless.

### The pairing

| target | property classes exercised | open question it tests |
| --- | --- | --- |
| **nREPL session + correlation** | resource safety, **temporal** | E5/E6 modes on a real resource; §16's temporal gap |
| **nREPL middleware** | higher-order composition | E13's abstract-refinement arity, on a real signature |
| **Persistent collections** | **structural / inductive** | §16's QF-LIA gap; the Ansatz tier |

Together these cover all three of §16's property classes. Neither is an abstract
example; both are load-bearing runtime.

### Why nREPL, concretely

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
- **Concurrency.** Concurrent requests and ordering exercise §2.4's kernel,
  which E4 established codecs do not touch at all.
- **Middleware.** `(fn [handler] (fn [request] ...))` is exactly E13's
  higher-order shape. If a handler's type carries the session and the `:reply`
  channel as refined capabilities, a middleware chain is the transducer problem
  including the arity-2 requirement E13 found unnamed in §2.2/§2.3. E13 tested
  it synthetically; this tests it against a signature that exists.
- **An already-asserted claim to discharge.** The docstring states `:reply` is
  "a thread-safe `(fn [response-map])`". That is an unverified concurrency claim
  in shipped code, which makes it a better target than one we invent.

Deferred within nREPL: **`interrupt`** — an in-flight eval racing an interrupt,
which must either land or report session-idle. The genuine race and the right
eventual target, but it depends on runtime lifecycle hooks that jolt-sim's
roadmap item 6 records as not yet existing.

### Why persistent collections

nREPL supplies **nothing structural** — no trees, no balance, no
ordering-preserved-under-traversal. §16's inductive gap would stay untested.

Jolt's own collections are the natural target: 32-way vector tries with tails,
and maps that promote from insertion-ordered small maps to HAMTs past a
threshold. Their invariants are exactly the ones QF-LIA cannot reach — node
arity, trie depth consistency, tail invariants, and *promotion preserving
lookup*. That last one is the interesting obligation, because it is a refinement
between two representations of the same abstract map, which is the same shape as
the refinement relations §16 noted consensus proofs need.

They are also maximally load-bearing: everything in the language sits on them.

### First slice

**The nREPL session type** — `unique` session with typestate, plus the per-`id`
trace grammar. Small, and specifiable in a way nREPL's own documentation is not:
there is no normative nREPL spec, so **writing the machine-checkable session
contract is the contribution**, not merely the test. That artifact does not
currently exist anywhere.

### Still not covered

**Consensus and distributed properties.** nREPL is single-node; persistent
collections are sequential. Multi-node safety, refinement against a spec, and
liveness under partition remain untested by this ladder, and a leader-election
target is still eventually required. This revision buys getting off codecs
without inventing a toy; it does not close the distributed gap.
