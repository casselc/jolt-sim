# Perturb state-of-the-art survey — Round 2

**Survey date:** 2026-08-04  
**Target:** `casselc/jolt-sim`, branch `claude/ocaml-effect-based-language-gsg316`  
**Prompt:** [`SOTA-POSITIONING-BRIEF-2.md`](https://github.com/casselc/jolt-sim/blob/claude/ocaml-effect-based-language-gsg316/docs/research/SOTA-POSITIONING-BRIEF-2.md)  
**Method:** three independent tracks examined (1) perturb as an effect-handler/runner system, (2) perturb as a protocol-capability checker and fail-closed boundary, and (3) the state of the art purely for pragmatic adoption. Primary sources were preferred. “Not found” means not found under the recorded search protocol; it is not proof of absence.

## Executive result

Round 2 changes the recommended implementation more than the novelty story.

The best available design is not to force one mechanism to solve every resource problem. Perturb should combine:

1. **Identity-indexed protocol state** for stable mutable handles.
2. **Static permission/obligation tokens** where ownership really is exclusive and statically knowable.
3. **Runtime arbitration and monitoring** for shared concurrent transitions such as compare-and-set close.
4. **A generational resource table or owned region** for runtime-sized resource collections.
5. **Runner-style outward interpretation and finalization** for handler stacks.
6. **Generated static and runtime projections from the same external declaration**, while treating monitored implementation conformance as monitored—not proved.

No exact four-way predecessor was found combining external protocol declarations, a static pass over a dynamically typed language’s existing IR, substructural typestate, and fail-closed monitoring derived from the same declarations. That remains a plausible systems contribution. It should not be the reason to adopt the architecture: the architecture is recommended because its components match perturb’s measured failures.

The most important conceptual correction is:

> A stable shared handle, its current protocol state, and the obligation to discharge the underlying resource are three different things.

Perturb’s current `:linearity :once` treats them as one. E30’s failures follow directly.

## Decisions first

| Decision | Recommendation | Confidence | Why |
|---|---|---:|---|
| State attachment | Attach protocol state to resource identity; track permissions separately | High | Matches Vault/Plural/Fugue/Mungo and E30’s stable mutable atoms |
| Terminal resources | Permit terminal observers and explicit terminal self-loops | High | Dead-name prohibition is not resource discharge |
| Shared CAS close | Model as monotonic shared state plus one-shot discharge right; monitor the winner | High | Static linear ownership cannot predict an unknown runtime winner |
| Transition declarations | Make destination depend on `(operation, source, result-label)` | High | Established typestate syntax; current operation-only function is unnecessarily weak |
| Runtime-sized collections | Use a generational table or explicit region with runtime member-state checks | Medium-high | Practical SOTA; no usable fully static generational typestate table was found |
| Handler layers | Use explicit outer effect instances and runner-style forwarding/finalization | High | Runners already formalize same-operation outward forwarding |
| Linear-handler safety | Require outward operation to be control-flow-linear and resume exactly once | High | Tang permits this pattern; it does not prove protocol preservation |
| Layer invariant | State as protocol forward simulation plus resource conservation | Medium-high | Separates behavioral refinement from ownership transfer |
| Discriminators | Prefer tagged results/state witnesses; otherwise mark predicate refinement trusted | High | Occurrence typing does not make an external proposition honest |
| Parser `need-more` | Keep exact rollback for v0; benchmark adversarial chunking, then add resumable committed-prefix state only if needed | Medium-high | Verified systems do not supply the full interface; rollback is simpler but can become quadratic |
| Runtime monitors | Monitor objective, independently observable obligations first | High | False alarms mainly expose weak or context-insensitive specifications |
| Dynamic Region Ownership | Adapt only at explicit container/handler boundaries initially | High | Full write-barrier design is costly and has no published performance result |

## Lens 1 — perturb as an effect-handler and runner system

### Finding 1: the two-rung same-operation stack is established runner structure

**Confirmed.** Ahman and Bauer’s [*Runners in Action*](https://doi.org/10.1007/978-3-030-44914-8_2) does more than resemble perturb. Its interposed-runner example handles an operation, performs accounting or checking, invokes the same operation outward, and then returns. Runner composition gives this structure a formal account and connects it to finalization.

This is the closest direct precedent found for perturb’s layered boundary.

**What runners require that perturb does not yet have:**

- explicit user operations and kernel co-operations;
- a user/kernel distinction;
- typed operation sets;
- explicit kernel state;
- a distinction between recoverable exceptions and fatal signals;
- mandatory finalization clauses and a runner semantics for them.

Therefore perturb is **runner-shaped** or **runner-inspired**, not literally an implementation of the runner calculus.

**Adopt:** explicit finalization and explicit outer-operation selection.  
**Do not adopt automatically:** the full runner monad or kernel/user calculus unless formalization needs them.  
**Contribution opportunity:** a runner-inspired boundary integrated with external substructural protocols over dynamic IR.

### Finding 2: Tang does not invalidate the layering experiment

**Confirmed.** [Tang et al., *Soundly Handling Linearity*](https://doi.org/10.1145/3632896) prohibits a deep handler from freely capturing linear values because deep reinstallation can duplicate or discard their use. It does not prohibit an operation clause from receiving and using a linear resumption.

The two-rung pattern is admissible when:

1. the outward operation is control-flow-linear;
2. the captured resumption is resumed exactly once;
3. no linear resource is retained by a repeatedly installed deep handler; and
4. failure/cancellation separately discharges the resources that normal resumption would return.

It is rejected when the outward operation is control-flow-unrestricted and might resume zero or multiple times.

**No answer from Tang:** preservation of typestate across the inner and outer protocols. Control-flow linearity protects continuation/resource integrity, not semantic correspondence between protocol layers.

**Decision:** continue the experiment. Add a negative test whose outer operation is multi-shot/unrestricted and require rejection.

### Finding 3: state the B6 invariant as simulation plus conservation

No single vocabulary covers both halves cleanly.

Use:

- **operation-protocol forward simulation or trace refinement** for behavioral correspondence;
- **resource conservation** for linear capabilities and discharge obligations;
- **runner composition / handler forwarding** for control structure; and
- **separation-logic operation protocols** when a proof account of ownership transfer is needed.

For inner machine `I`, outer machine `O`, abstraction relation `R`, and adapter `A`, the core obligation should say:

1. if `R(i,o)` and the inner layer accepts operation `p`, `A` emits a finite outer trace accepted from `o`;
2. the resulting states `i'` and `o'` again satisfy `R`;
3. every linear capability entering the adapter is returned, transferred outward, or discharged exactly once;
4. abort/finalization establishes the declared terminal obligation; and
5. the adapter cannot intercept its own forwarded operation accidentally.

The fifth condition requires explicit effect instances, labels, or an equivalent outer-runner reference.

### Finding 4: Yarrow supplies proof vocabulary, not an implementation recipe

The July 2026 [Yarrow preprint](https://arxiv.org/html/2607.15876v1) provides an Iris/Rocq logic for fibers, regions, once/many effects, and resources exchanged between operations and handlers through protocols. These protocols state what resource propositions cross the perform/resume boundary.

This is useful for perturb’s layering invariant: an adapter protocol can say which resource is handed outward and which must return on resumption. Yarrow does not provide an executable typestate checker, a declaration syntax, or an analysis of same-effect protocol adapters.

**Decision:** borrow its operation-protocol vocabulary for a formal note; do not make Iris/Rocq a prerequisite for the next implementation experiment.

## Lens 2 — perturb as a protocol-capability checker and boundary interpreter

### Finding 5: E30’s close pattern is not ordinary linear consumption

The strongest formal account is:

- a **stable shared handle** usable by several holders;
- a **monotonic shared fact**, `Open → Closed`;
- a **one-shot discharge right** consumed by the successful CAS;
- persistent knowledge that `Closed` holds afterward; and
- observer permission that survives closure.

Concurrent separation logics, especially Iris-style authoritative/one-shot ghost state and logically atomic specifications, can model the CAS as the atomic event that transfers or consumes the one-shot right. [Iron](https://iris-project.org/iron/) is directly relevant to obligations such as closing handles in the presence of dynamically allocated threads. Fractional permissions alone are insufficient: they describe many readers and a writer, but do not make an unknown reader the unique runtime winner without an invariant/atomic update.

**Refuted:** close should make the stable handle name unusable.  
**Refuted:** an idempotent close means the underlying resource is closed repeatedly.  
**Confirmed:** the boolean result is a race witness selecting the one logical transition.

Recommended protocol:

```clojure
{:op close!
 :cases [{:from :open   :result true  :to :closed :discharges :must-close}
         {:from :closed :result false :to :closed}]
 :contention :atomic}
```

`closed?` and `connection-info` are ordinary borrows/self-loops in `:closed`.

### Finding 6: identity-indexed typestate is established and fits E30

Vault, Fugue, Plural/Plaid, Mungo/StMungo, and related object typestate systems attach state or permission facts to a stable object identity. A method can mutate the receiver in place and return a Boolean, count, or unrelated value; the analysis updates the receiver’s state fact rather than requiring a replacement receiver value.

The systems pay for this with some combination of:

- alias control or uniqueness;
- tracked keys/capabilities indexed by object identity;
- fractional or access permissions;
- pre/post permission specifications;
- state refinement linked to a result label; and
- restricted sharing or runtime checks.

There is no evidence that value threading is universally wrong. It is still a simple and effective presentation for exclusive resources. It is the wrong only representation for perturb because real handles keep identity while state changes internally.

**Decision:** preserve SSA/value-threading internally if convenient, but expose identity-indexed protocol state and permission tokens in the declaration/checker model.

### Finding 7: state-dependent destinations already have practical syntax

The operation-only transition function should be replaced. Established systems permit source- and result-dependent poststates.

Representative forms include:

- Vault: transitions keyed by source and destination, conventionally `K@s1 -> s2`;
- Plural: method pre/post permissions and refinements;
- Fugue: plug-in postconditions computed from receiver/current state/arguments/results;
- Mungo/StMungo: result-labelled continuations, for example:

```text
Status open(): <OK: Open, ERROR: end>
BooleanEnum hasNext(): <TRUE: Read, FALSE: Close>
```

**Decision:** make the primitive declaration relation approximately:

```text
(capability, operation, source-state, argument-shape, result-label)
    -> destination-state + obligation delta
```

Source collections can remain syntactic sugar only when every source has the same destination and obligation delta.

### Finding 8: no fully static generational typestate table was found

The search found several neighboring mechanisms but no exact practical system in which an unrestricted generational key into a runtime-sized table statically preserves a distinct linear typestate for each dynamically inserted occupant.

- Rust `slotmap`/generational arenas detect stale keys and prevent cross-table mistakes with nominal key types, but their keys are copyable and do not carry occupant typestate.
- Dependent heterogeneous maps relate a key type to a value type, but generally require the key/type universe to be represented statically; they do not solve arbitrary runtime identities plus evolving linear states.
- Indexed/parameterized monads can index computations by a type-level resource environment, but insertion/removal changes that environment at the type level and quickly requires existential packaging or dependent proofs.
- ST/world-indexed heaps provide encapsulation and freshness, not convenient escaped generational handles with per-entry protocols.
- Dynamic session systems support unbounded participants, but structure them through a session calculus or generated APIs rather than an ordinary host map of heterogeneous endpoint states.

**Practical design:**

1. statically own the table/region capability;
2. use copyable generational keys solely for stale-reference safety;
3. have lookup yield a scoped borrow, lease, or existential dynamic capability;
4. monitor each occupant’s state at runtime;
5. require table/region finalization to prove or monitor that all required occupants reached terminal states.

This is a principled hybrid, not a failed static solution.

The closest reviewed candidates make the boundary concrete:

| Candidate | What it supplies | What it does not supply |
|---|---|---|
| Rust [`slotmap`](https://docs.rs/slotmap/latest/slotmap/) | runtime growth, opaque nominal keys, generation/stale-key checks | keys are `Copy`; no per-entry static protocol |
| [`typed-generational-arena`](https://docs.rs/typed-generational-arena/latest/typed_generational_arena/) | typed index families and configurable generation storage | no linear permission or evolving occupant typestate |
| Haskell [`dependent-map`](https://hackage.haskell.org/package/dependent-map) | key type determines value type | runtime numeric identities erase the distinct static state unless existentially packaged |
| [`justified-containers`](https://hackage.haskell.org/package/justified-containers/docs/Data-Map-Justified.html) | membership proof tied to an immutable map world | mutation creates a new world and invalidates old proofs; not an ergonomic mutable registry |
| Idris `ST` | fresh resource identities and result-dependent resource environments | unbounded ordinary tables require existential worlds and substantial proof plumbing |
| Parameterized monads | principled pre/post heap or resource indices | every allocation changes the world; loops/collections become type-level engineering |
| Capability MPST / Ferrite | freely shareable reference separated from a linear use/acquire capability | session-specific; no slot generations or ordinary host table |
| Iris authoritative ghost map | can prove a dynamic registry, generations, and linear fragments | proof logic, not a user-facing language/table implementation |

Generation width is itself a runtime parameter: finite generation counters can wrap, after which an ancient key may eventually alias a reused slot. Perturb must either choose a practically nonwrapping width, retire exhausted slots, or document the bounded guarantee.

### Finding 9: the rejection taxonomy is publishable if reported as an empirical map

The correct methodology is a systematic mapping/measurement study, not a universal claim about type systems.

For perturb’s checker corpus, report:

- corpus discovery and inclusion/exclusion rules;
- exact commit hashes and tool configuration;
- LOC, namespaces/modules, operations, declared protocols, and checked boundaries;
- all diagnostics before deduplication;
- the unit of analysis: diagnostic, source site, operation, or rejected program;
- a mutually exclusive primary-cause code plus optional secondary causes;
- false accept, false reject, unsupported construct, inconsistent declaration, and checker defect separately;
- annotation density and partial-coverage escape hatches;
- two independent coders for a sample, disagreement resolution, and inter-rater agreement;
- a non-vacuity control and known-good/known-bad cases.

Use PRISMA-S for search reporting, Kitchenham/Charters and Petersen-style mapping-study guidance, and Wohlin-style backward/forward snowballing. The most natural venues are empirical software engineering or a PL experience/evaluation track, depending on whether the artifact or the language mechanism is central.

## Lens 3 — pragmatic SOTA for achieving perturb’s goals

### Finding 10: Dynamic Region Ownership is an escape hatch, not a replacement checker

[Dynamic Region Ownership for Concurrency Safety](https://doi.org/10.1145/3729313) assigns each object to an owner/region and dynamically checks region topology during stores, variable bindings, and argument passing. Cross-region access uses borrows and externally unique bridges; shared mutation is mediated by cowns.

It is much more invasive than adding a table abstraction:

- enforcement needs a write barrier across ordinary object interactions;
- moving a subgraph is proportional to graph size plus destination depth;
- local reference-count updates traverse ancestors;
- freezing is linear;
- recoverable errors may require two passes;
- region-tree topology can constrain parallelism;
- borrows require scoping/invalidation machinery;
- reflection and mutable module/type state need restriction, freezing, mirrors, or cowns;
- noncompliant modules may be serialized behind a global cown;
- cown cycles need management.

The paper supplies no end-to-end performance measurement; the authors explicitly leave performance goals open and describe a full CPython implementation as substantial future engineering. A gradual/static companion is conceivable future work, not a delivered result.

**Adopt now:** an explicit owned region/container for handler state or connection tables, with boundary-local runtime checks.  
**Defer:** whole-language region ownership and pervasive write barriers.  
**Measure:** whether one region/table removes the E30 collection failures without laundering member protocol violations.

### Finding 11: minimal occurrence refinement is possible, but honesty still needs evidence

Perturb does not need all of Typed Racket to propagate a discriminator fact. A small abstract interpretation can associate a predicate with two capability-state propositions and refine the environment along control-flow edges.

What it cannot obtain for free is truth of the proposition. A typed/untyped boundary contract can check that a predicate accepts the expected argument and returns a Boolean; it generally cannot establish that `true` means the hidden resource is in state `S`.

Preference order:

1. operation returns a genuine tagged result whose constructor determines the state;
2. operation returns a state witness produced by trusted boundary code;
3. generated monitor cross-checks the result against an independent state observation;
4. externally declared predicate is accepted as an explicit axiom.

**Decision:** implement result-labelled transitions first. Add generic predicate refiners only if a real library requires them.

### Finding 12: exact-cursor rollback is not the only—or usual—streaming design

Verified parsers such as EverParse/LowParse, Vest, and Narcissus primarily prove parsing of available finite buffers, memory safety, and encoder/decoder correspondence. They do not directly deliver perturb’s full chunked sans-I/O interface with protocol state and exact rollback.

Practical incremental parsers commonly return a suspended continuation. Attoparsec’s `Partial` resumes from the suspension point when given another chunk. Arbitrary backtracking can retain all prior input, producing memory proportional to total supplied input. This makes mandatory rollback to the original cursor a real cost, not merely a clean semantic property.

The pragmatic v0 decision is nevertheless to **keep exact rollback**. It is already a clear sans-I/O contract, aligns with value semantics, and may be entirely adequate for bounded protocol frames. Before changing it, measure:

- one-byte chunk delivery;
- the maximum legal frame size;
- repeated-prefix CPU time;
- retained-buffer high-water mark; and
- error-offset and replay behavior.

If repeated reparse becomes quadratic or buffer retention becomes material, move to:

```text
Done(value, committed-cursor, remainder)
NeedMore(resume, committed-cursor, minimum-hint?)
Invalid(error, error-cursor)
```

The parser may commit a prefix once no alternative can need it. Deterministic replay records the original chunks/transcript; it does not require the active parser cursor to roll back to the beginning of the attempted frame. Commitment should initially be permitted only at explicit frame/subframe boundaries, with an observational-equivalence test against the rollback implementation.

**Contribution opportunity:** a verified or model-checked correspondence among chunked resumable parsing, protocol typestate, and transcript replay. Exact full rollback is not necessary unless a particular combinator promises non-consuming failure.

### Finding 13: reduce monitor false alarms by reducing monitor ambition

The false-alarm result from Legunsen et al. is primarily a specification-quality warning. Follow-on tooling such as evolution-aware monitoring reduces repeated work and duplicate violations, but does not make an underspecified API property semantically correct.

For perturb, divide monitors into:

**High-confidence enforcement**

- declared edge exists;
- Content-Length equals emitted byte count;
- every resource in a closing region reached a terminal state;
- an unhandled native operation latched the run;
- a supposedly single-use token was consumed at most once;
- an adapter emitted only an allowed outer trace.

**Lower-confidence semantic conformance**

- an arbitrary function “really responded” in the intended application sense;
- an external predicate truthfully characterizes hidden state;
- a third-party function’s declared side effects are complete.

Build the first group. Treat the second as test-time auditing or sampled telemetry until it has an independent oracle. Attach provenance—declaration, operation, call site, trace, and state history—to every alert. Support contextual suppression only as a recorded scoped exception, never silent global filtering.

Follow-on runtime-verification work reduces human and runtime cost without repairing the underlying specification:

- evolution-aware RV reduced monitoring work by selecting properties affected by a change and suppressed already-seen violation messages;
- [eMOP](https://doi.org/10.1007/978-3-031-44267-4_20) reports up to 8.4× faster runs and 31.3× fewer displayed violations across 676 versions of 21 projects, without missing new violations in its evaluation;
- RVprio uses learned ranking to move likely true violations upward, but needs labelled data and still leaves some real bugs below early triage cutoffs.

For perturb, use stable violation fingerprints containing the specification version, operation, call site/path, adapter layer, transition, and relevant trace prefix. Show new/regressed fingerprints by default while retaining the complete record. Suppressions need an owner, reason, scope, and expiry. Defer learned ranking until a labelled corpus actually exists.

### Finding 14: use Yarrow narrowly

Yarrow **refutes a blanket claim that regions are the feature that makes handlers unsound**. It gives a sound operational semantics and Rocq/Iris logic for lexically scoped region-based memory management with one- and multi-shot handlers. The real rule is more precise: non-well-bracketed control needs explicit capture/revocation accounting, and a multi-shot resumption cannot retain reclaimed local references.

For one-shot effects, Yarrow can temporarily transfer the stack/region resources captured by the continuation and restore them on resumption. For multi-shot effects, old local references are not retained for reuse. Its operation protocols relate the operation argument and fiber-domain configuration to the resources required and returned by the continuation.

For perturb:

- lexical stack regions remain dangerous around escaping or multi-shot control;
- D4’s ordinary successful path is tail-resumptive, and the proceed seam is one-shot;
- a heap region or explicit connection table is a different abstraction and remains viable;
- operation protocols are useful for stating what ownership crosses the adapter boundary.

The current §1.2 citation should distinguish lexical allocation regions from domination/ownership regions and should not present Yarrow as a blanket argument against locality. It should say that locality plus non-well-bracketed control requires multiplicity-aware capture and revocation discipline—machinery perturb does not presently have.

## Reproducible absence-search protocol

### Operational inclusion criteria

An exact four-way match must satisfy all four:

1. **External protocol declarations:** behavioral/resource protocols can be supplied separately from the implementation, including for unaware third-party code.
2. **Static pass over an existing dynamically typed language IR:** analysis consumes the language/compiler’s real IR or AST without changing the whole source language into a statically typed replacement.
3. **Substructural typestate:** the static analysis tracks at least affine/linear ownership or use obligations together with protocol state transitions.
4. **Coordinated fail-closed monitoring from the same declarations:** runtime enforcement is generated/projected from the same protocol source and an unmediated governed boundary operation causes failure rather than silently bypassing enforcement.

Near neighbors are retained and scored 0–4 rather than discarded.

For a typed generational capability table, an exact match must provide runtime insertion/removal, stale-key protection, and a static guarantee that each occupant follows its own evolving linear typestate through ordinary key lookup.

### Sources searched

- ACM Digital Library / PACMPL and SIGPLAN program indexes
- DBLP
- arXiv
- Dagstuhl/LIPIcs
- Springer/ETAPS pages
- IEEE and USENIX search surfaces
- Google Scholar-style web search and author publication pages
- backward and forward references from Typed Clojure, SPY/Scribble, Checker Framework, JavaMOP, StaRVOOrS, Runners, Tang, Yarrow, Vault/Plural/Fugue/Mungo, Dynamic Region Ownership, Iron/Iris, and DMst/GoScr
- ecosystem documentation for Rust slotmap/generational arenas and Haskell dependent maps/indexed monads

### Query families

Queries were run with spelling and hyphenation variants. Representative exact strings:

```text
"external protocol" typestate "dynamic language" static runtime monitor
"protocol declarations" compiler IR linear typestate monitoring
Scribble static dynamic runtime monitor linearity typestate
"pluggable type system" typestate external annotations runtime checking
"fail-closed" static analysis runtime monitor protocol
"generational index" linear typestate
"slot map" linear type protocol state
dependent map dynamic allocation linear resources
world-indexed heap typestate dynamic key
indexed monad heap resource typestate
session typed registry dynamic participants handles
ATS linear view dynamic table handle
Idris Agda resource indexed state dynamic allocation
CAS one-shot obligation close shared resource separation logic
idempotent destructor typestate terminal state observer
```

### Screening result

No exact match was found in either search. The closest four-way neighbors split predictably:

| System/family | External declarations | Static dynamic-language pass | Substructural typestate | Same-source fail-closed monitor | Main miss |
|---|---:|---:|---:|---:|---|
| [Pupo et al., RASP→SAST](https://doi.org/10.22152/programming-journal.org/2022/6/1) | Yes | Yes, JavaScript subset | No | Yes | strongest security neighbor; no affine/linear resource protocol |
| [JAMScript/policy weaving](https://doi.org/10.1145/2635868.2635907) | Yes | Yes, JavaScript | No | Yes | stateful policies suppress violating statements, but no substructural typestate |
| Sorbet/RBI | Yes | Yes, Ruby | No | Partial | value signatures rather than resource protocols; no shared I/O monitor |
| Typed Clojure | Partial | Yes | No | No | no resource typestate/runtime arm |
| SPY/Scribble Python | Yes | No/limited | Runtime protocol | Yes, runtime | not static substructural IR checking |
| Scribble Java/FuSe | Yes | Static/generated API | Split static/dynamic | Runtime linearity | not existing dynamic-language IR |
| Gradual Typestate / TSOP | No external artifact | New calculus/language | Yes | Inserted checks | not an existing dynamic host or boundary monitor |
| Clara / StaRVOOrS | Yes | Java/static | No | Yes | one-spec residual/runtime monitoring without substructural resource state |
| Mungo/StMungo | Yes | Java/static | Yes | No | no coordinated runtime boundary monitor; not a dynamic host |
| Checker Framework | Yes | No | Checker-dependent | No | static host and no coordinated monitor |
| JavaMOP/RV-Monitor | Yes | No | Runtime only | Yes | no static substructural pass |
| StaRVOOrS | Yes | Static verification | No linear typestate | Yes | different property/enforcement model |
| Dynamic Region Ownership | No external protocols | No static pass | Dynamic ownership | Dynamic failure | dynamic-only and pervasive runtime model |
| Perturb | Yes | Yes | Yes | Proposed/partial | integration and evidence incomplete |

For generational tables, Rust slotmaps are the nearest practical stale-key mechanism; indexed/dependent heaps are the nearest static theories; dynamic session registries are the nearest unbounded-protocol mechanism. None combines all three in a practical ordinary table API.

This is a reproducible **scoped absence audit**, not yet a publication-grade PRISMA study: the survey did not retain database-export hit counts and dual-review every title/abstract. That stronger process is unnecessary for the architecture decision. It would be required before making “first system” a paper claim.

## Recommended implementation sequence

### Phase 1 — repair the declaration and checker model

1. Key state facts by binding identity **and** resource identity.
2. Replace single `:from`/`:to` annotations with source/result-labelled transition cases.
3. Add terminal observer and idempotent terminal-transition self-loops.
4. Separate `:must-discharge` from dead-name/affine use policy.
5. Reject accepted-but-ignored declaration keys such as the current ineffective `:arg` form.

### Phase 2 — model the real close protocol

1. Declare open/closed CAS cases and the boolean race witness.
2. Statically check exclusive paths.
3. Runtime-monitor the shared CAS winner and terminal obligation.
4. Preserve post-close observers.
5. Add known-good races, double textual close, lost-close, and dishonest-return controls.

### Phase 3 — add one runtime-sized resource abstraction

1. Implement an explicit generational capability table or owned region.
2. Make the table itself a static capability.
3. Return scoped leases/existential dynamic handles from lookup.
4. Monitor member typestate and generation at runtime.
5. Require table finalization to account for every outstanding discharge obligation.

### Phase 4 — make handler layering explicit

1. Give each boundary rung an effect instance/outer-runner reference.
2. Make forwarding syntactically distinct from local handling.
3. Add finalization to each rung.
4. Check the forward-simulation/resource-conservation invariant on known-good and known-bad adapters.
5. Test control-flow-linear versus multi-shot outward operations.

### Phase 5 — narrow the monitored arm

1. Generate monitors only for objective transition, byte-count, terminality, and boundary-completeness obligations first.
2. Include declaration and trace provenance in every violation.
3. Keep semantic-axiom conformance in tests/audits unless an independent oracle exists.
4. Measure alert precision, not only how often monitors fire.

### Phase 6 — benchmark rollback, then adopt resumable parsing only if warranted

1. Benchmark exact rollback under adversarial chunking and maximum legal frames.
2. Keep it if CPU and retention remain bounded acceptably.
3. Otherwise represent `NeedMore` with suspended parser state.
4. Permit committed-prefix release where backtracking cannot cross the commitment point.
5. Keep original chunks in the deterministic transcript.
6. Specify which combinators promise non-consuming failure and test cursor preservation only there.

## Natural contribution opportunities

These are worth pursuing only if they fall out of the implementation:

1. **Empirical:** a reproducible taxonomy of why a substructural typestate checker rejects real Clojure libraries, with false accepts and checker defects reported alongside false rejects.
2. **Systems:** one external protocol source projected into static dynamic-IR checking, member-level runtime monitoring, and fail-closed boundary enforcement.
3. **Formal:** same-effect protocol adapters characterized by forward simulation plus linear-resource conservation, instantiated by runner-style layers.
4. **Hybrid resource representation:** static ownership of a runtime-sized generational table with monitored per-occupant typestate and terminal accounting.
5. **Parsing/replay:** correspondence between committed-prefix incremental parsing and deterministic transcript replay.

None requires claiming a breakthrough before the artifact exists.

## The three places perturb is still most likely wrong

1. **Trying to make every capability itself linear.** Shared identity, observer access, state knowledge, and discharge rights need different structural rules.
2. **Demanding static proof inside runtime-shaped collections.** A table-level static capability plus member-level monitoring may be the correct boundary, not a temporary compromise.
3. **Treating implementation mediation as one uniform effect-handler problem.** Runner finalization, control-flow linearity, protocol simulation, and native fail-closed interception solve distinct obligations and should remain visibly separate.

## Evidence and limitations

- Negative findings are reproducible search results, not proofs of nonexistence.
- The exact-match criteria are intentionally strict; several systems solve three quarters of perturb’s conjunction.
- Dynamic Region Ownership has an implementation and formal model but no published end-to-end performance result.
- Concurrent separation logic explains the CAS obligation but is not directly an executable typing discipline for perturb.
- Tang establishes continuation/resource integrity, not typestate preservation between layered protocols.
- Practical incremental parsers establish a better engineering shape; a full verified account of perturb’s parser-plus-transcript protocol remains open.

## Read-depth ledger

### Full text or full relevant technical development

- Andrej Bauer and Matija Pretnar, [*Runners in Action*](https://arxiv.org/abs/1910.11629), ESOP 2020.
- Wenhao Tang et al., [*Soundly Handling Linearity*](https://arxiv.org/abs/2307.09383), POPL 2024, including proof/implementation appendices.
- Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal, [*Yarrow*](https://arxiv.org/abs/2607.15876), 2026 preprint.
- Fridtjof Stoldt et al., [*Dynamic Region Ownership for Concurrency Safety*](https://www.microsoft.com/en-us/research/wp-content/uploads/2025/04/pyrona_camera_ready.pdf), PLDI 2025.
- Fähndrich and DeLine, *Adoption and Focus: Practical Linear Types for Imperative Programming*, relevant Vault syntax and capability sections.
- Official technical documentation for Rust `slotmap`, `typed-generational-arena`, Haskell `dependent-map`, justified containers, Idris `ST`, JaTyC, Mungo, Typed Racket occurrence typing/boundaries, and Attoparsec incremental input.

### Full relevant sections or substantial technical skim

- EverParse/LowParse, Vest, and Narcissus parser papers.
- Ferrite shared sessions and Iris authoritative-map/atomic-operation documentation.
- Plural, Fugue, Papaya, dynamic multirole sessions, DMst/GoScr, capability-based MPST, and indexed/parameterized monad work.
- Pupo et al.’s RASP→SAST derivation, JAMScript/policy weaving, Clara, StaRVOOrS, SPY/Scribble, and hybrid endpoint verification.

### Abstract/metadata-level claims only

- Several methodological and follow-on runtime-verification papers where only their reported method/result was used: Kitchenham/Charters, Petersen, Wohlin, PRISMA-S, evolution-aware RV, eMOP, and RVprio.
- ATS/Agda dependent-linear session papers used only to establish component-level proximity, not an implementation claim.

## Selected bibliography

- Bauer and Pretnar. [*Runners in Action*](https://doi.org/10.1007/978-3-030-44914-8_2). ESOP 2020.
- Tang, Hillerström, Lindley, and Morris. [*Soundly Handling Linearity*](https://doi.org/10.1145/3632896). POPL 2024.
- Mathiasen, Timany, and Birkedal. [*Yarrow: Reconciling Effect Handlers and Region-Based Memory Management*](https://arxiv.org/abs/2607.15876). 2026 preprint.
- Stoldt et al. [*Dynamic Region Ownership for Concurrency Safety*](https://doi.org/10.1145/3729313). PLDI 2025.
- Fähndrich and DeLine. [*Adoption and Focus*](https://doi.org/10.1145/512529.512532). PLDI 2002.
- Bierhoff and Aldrich. [*Modular Typestate Checking of Aliased Objects*](https://doi.org/10.1145/1297027.1297050). OOPSLA 2007.
- Kouzapas et al. [Mungo/StMungo](https://doi.org/10.1016/j.scico.2017.10.006). Science of Computer Programming.
- Mota et al. [*A Java Typestate Checker Supporting Inheritance*](https://doi.org/10.1016/j.scico.2022.102844).
- Bizjak, Gratzer, Krebbers, and Birkedal. [*Iron: Managing Obligations in Higher-Order Concurrent Separation Logic*](https://iris-project.org/iron/). POPL 2019.
- Jung et al. [*Higher-Order Ghost State*](https://doi.org/10.1145/3022670.2951943). ICFP 2016.
- Voinea, Dardha, and Gay. [*Resource Sharing via Capability-Based Multiparty Session Types*](https://doi.org/10.1007/978-3-030-34968-4_24). iFM 2019.
- Castro-Perez and Yoshida. [*Dynamic Multiparty Session Types*](https://doi.org/10.4230/LIPIcs.ECOOP.2023.6). ECOOP 2023.
- Pupo et al. [*Deriving Static Security Testing from Runtime Security Protection*](https://doi.org/10.22152/programming-journal.org/2022/6/1).
- Jamrozik et al. [JAMScript/policy weaving](https://doi.org/10.1145/2635868.2635907). FSE 2014.
- Legunsen et al. [*How Good Are the Specs?*](https://doi.org/10.1145/2970276.2970356). ASE 2016.
- Delignat-Lavaud et al. [EverParse](https://www.usenix.org/conference/usenixsecurity19/presentation/delignat-lavaud). USENIX Security 2019.
- Cai et al. [Vest](https://www.usenix.org/conference/usenixsecurity25/presentation/cai-yi). USENIX Security 2025.
- Delaware et al. [Narcissus](https://doi.org/10.1145/3341686). ICFP 2019.
