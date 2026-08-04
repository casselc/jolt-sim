# Perturb state-of-the-art positioning survey

**Survey date:** 2026-08-04  
**Target:** `casselc/jolt-sim`, branch `claude/ocaml-effect-based-language-gsg316`  
**Primary brief:** [`SOTA-POSITIONING-BRIEF.md`](https://github.com/casselc/jolt-sim/blob/claude/ocaml-effect-based-language-gsg316/docs/research/SOTA-POSITIONING-BRIEF.md)  
**Method:** primary sources were preferred for technical claims. “Full text” means the paper, dissertation, specification, or official documentation was inspected beyond its abstract. Absence claims are reported only as “not found in this survey.”

## Executive finding

The broad novelty story does **not** survive the survey. The literature has already closed the general gap between effect handlers and linear resources; handler composition and forwarding are standard; abortive and tail-resumptive handlers are established categories; external specification files and pluggable checking are mature; runtime protocol monitoring over dynamically typed programs exists; and session systems can support unbounded, dynamically changing participant sets.

The narrower conjunction remains plausibly distinctive:

> **A static substructural typestate checker over a dynamically typed language’s existing compiler IR, driven by external protocol declarations and paired with fail-closed runtime boundary enforcement derived from the same declarations.**

No source found in this survey combines all of those properties. Each property separately has close prior art, however, so this is a systems-integration and evidence claim—not yet a claim of a new type-theoretic mechanism.

The survey also changes the control-effects description. A handler that supplies a validated result and allows the caller to continue is not well described as “no resumption.” It is an **implicit tail resumption** even if no continuation value is exposed. Only its failure path is abortive. The most useful control axis is therefore:

> abortive (zero-shot) → implicit tail-resumptive → explicit affine/one-shot → multi-shot

On the present description, perturb occupies two points: implicit tail-resumptive on success and abortive on failure.

## Decision-changing results

### 1. The general linear-handler gap is closed

**Confirmed.** [Tang et al., *Soundly Handling Linearity* (POPL 2024)](https://doi.org/10.1145/3632896) introduces control-flow linearity, proves that linear values are neither discarded nor duplicated, repairs a long-standing Links soundness bug involving session types and handlers, and supplies an inference-oriented calculus. [Brachthäuser and Leijen, *Qualified Effect Types*](https://www.microsoft.com/en-us/research/publication/qualified-effect-types/) separately classify control flow as linear, affine, abortive, or unrestricted. [van Rooij and Krebbers, *Affect* (POPL 2025)](https://doi.org/10.1145/3704841) uses affine types to track continuations even through mutable references and nesting. All three were read in full.

**Refuted.** Fowler et al.’s 2019 future-work statement can no longer support the claim that formalizing linear effect handlers is an open gap.

**Still unanswered.** The survey found no direct formal account of the precise combination perturb is experimenting with: a handler satisfies an operation by performing the same operation to an outer layer while threading typestate-linear resources across external protocol declarations over dynamic-language IR.

**Claim killed:** “We may be standing in the still-open gap of linear effect handlers.”  
**Claim preserved, narrowed:** “The exact external-specification and dynamic-host integration may be unstudied.”

### 2. “No resumption” is the wrong classification

**Confirmed.** The handler literature distinguishes abortive handling from tail resumption. [*Lexical Effect Handlers, Directly*](https://doi.org/10.1145/3689770), [*Generalized Evidence Passing*](https://doi.org/10.1145/3473576), and [*Effect Handlers, Evidently*](https://doi.org/10.1145/3408981) treat tail-resumptive operations as a special, efficiently compilable case. A supplied result followed by continued caller execution is observationally tail-resumptive even if the continuation is implicit.

**Refuted.** “Substitute a validated result or abort, with no resumption” conflates two control cases. Successful substitution continues the delimited computation; failure discards it.

**Cost/benefit.** Avoiding first-class continuations simplifies resource reasoning and permits direct compilation. It also excludes handler implementations needing captured resumptions, such as generators, schedulers, backtracking, and some state interpretations.

**Claim killed:** “No-resumption handling is an unexplored design point.”

### 3. Handler layering is prior art; perturb’s exact layering obligation is not settled

**Confirmed.** Open algebraic handlers routinely forward unhandled operations outward. Scoped-effect calculi formalize explicit forwarding and handler composition; see [Bosman et al., *A Calculus for Scoped Effects & Handlers*](https://arxiv.org/abs/2304.09697). [*First-Class Names for Effect Handlers*](https://doi.org/10.1145/3563289) includes handlers implemented in terms of other operations and scoped resources.

**Refuted.** “Events over HTTP over TCP” is not novel merely because it uses nested handlers.

**No direct answer found.** None of the reviewed work isolates same-effect forwarding through several protocol layers while preserving perturb-style typestate-linear obligations. Tang’s open effect rows help, but its deep-handler rules restrict capture of linear values.

**Claim killed:** novelty of generic handler-over-handler composition.  
**Claim preserved, narrowed:** same-effect, protocol-layer, linear-resource composition remains an experimental obligation.

### 4. The nearest conceptual family may be runners and boundary interpreters

**Confirmed.** [Ahman and Bauer, *Runners in Action*](https://doi.org/10.1007/978-3-030-44914-8_2) models top-level external resources, modular virtual machines, finalization, and linear resource use. Capability-passing implementations of handlers likewise make permission to perform operations explicit; see [Schuster et al., *Compiling Effect Handlers in Capability-Passing Style*](https://doi.org/10.1145/3408975).

**Implication.** Perturb’s D4 boundary is more credibly positioned as a typed, capability-mediated operation interceptor or runner with protocol checking than as a novel algebraic-handler calculus.

**Claim weakened:** “resource-safe boundary interpretation is uncharted.”

## Question-by-question findings

### A1. core.typed / Typed Clojure

**Confirmed.** Annotation burden was severe. Bonnaire-Sergeant’s [dissertation](https://thesis.ambrosebs.com/) calls top-level annotation burden for porting untyped code “prohibitively high,” identifies brittle macro pre-expansion, and reports substantial local-function annotation needs. Full dissertation chapters covering design and evaluation were read.

The strongest production datum is partial coverage, not abandonment:

| Corpus | Size | Checked definitions | Unchecked definitions |
|---|---:|---:|---:|
| CircleCI | 19,000 LOC, 87 namespaces | 407 / 1,834 (22%) | 1,427 (78%) |
| feeds2imap | 825 LOC, 11 namespaces | 52 / 93 (56%) | 41 (44%) |

The dissertation’s automatic-annotation experiment also required substantial generated annotations and manual edits across five projects. Reported causes included casts and instantiations, polymorphic and local annotations, checker workarounds, over-precise inference, varargs, occurrence annotations, and heterogeneous-vector widening.

**Refuted.** The brief’s assertion that core.typed was “largely abandoned” is not established by the primary evidence. The [official repository](https://github.com/clojure/core.typed) and [current site](https://typedclojure.org/) remain active enough to advertise a stable release. That does not prove broad adoption; it makes “abandoned” an unsupported premise.

**No answer found.** No metric states how often a particular rule forced an idiomatic Clojure rewrite. No comparable distribution for perturb’s join rule exists.

**Claim killed:** “existing evidence may make E6 measurement unnecessary.” It does not.  
**Claim strengthened:** external declarations do not, by themselves, make annotation or adaptation cost tolerable.

### A2. clojure.spec

**Confirmed.** The [official guide](https://clojure.org/guides/spec) establishes separately declared specifications, runtime instrumentation, and generative checking. Stock instrumentation checks arguments; third-party tools such as [Orchestra](https://github.com/jeaye/orchestra) exist because return and relational function specifications are not covered by ordinary instrumentation.

**No answer found.** This survey found no primary corpus study of what Clojure practitioners annotate, how often instrumentation remains active in production, production overhead, or protocol-violation catch rates.

**Claim affected:** declare-from-outside plus runtime instrumentation is established ecosystem precedent, but neither its cost nor its production value can be inferred from the design’s existence.

### A3. Pluggable type systems and the Checker Framework

**Confirmed.** The [Checker Framework manual](https://checkerframework.org/manual/) supports external stub files and partial checking. Published evaluations report millions of lines checked and low average annotation density for mature checkers—about 2.6 annotations/kLOC overall and 20/kLOC for Nullness in the cited evaluation family; see the [Checker Framework papers](https://checkerframework.org/papers/) and [Dietl et al., ICSE 2011](https://doi.org/10.1145/1985793.1985819).

**Important cost tradeoff.** [NullAway](https://arxiv.org/abs/1907.02127) obtained roughly 1.15× build overhead, versus 2.8–5.1× for compared tools, by choosing intentionally unsound defaults. In its remaining production null failures, unchecked libraries dominated; suppressions and reflection/post-check mutation were other major causes.

**Refuted.** A checker over an existing language plus external signatures is not novel.

**No answer found.** Neither the framework literature nor the reviewed typestate checker [JATYC](https://inria.hal.science/hal-03387832v1/document) supplied a measured false-positive rate for real-world typestate checking.

**Claim killed:** generic novelty of external checker specifications.  
**Design warning:** adoption may require partial coverage or deliberate unsoundness; perturb should measure both explicitly rather than silently inheriting them.

### A4. Erlang/OTP links and monitors

**Confirmed.** The [Erlang process semantics](https://www.erlang.org/doc/system/ref_man_processes.html) provides a mature operational precedent. Links are bidirectional and propagate exit signals; monitors are independent, unidirectional observations producing `DOWN` messages. Signals are asynchronous, remote failure adds `noconnection`, and unlinking does not erase every race with already queued signals. Fowler’s [Erlang multiparty-session monitor](https://arxiv.org/abs/1608.03321) supplies a closer formal/runtime bridge.

**Refuted.** Peer notification and transitive failure propagation are not new semantics.

**Not equivalent.** OTP does not provide linear cancellation ownership or perturb’s proposed static protocol integration.

**Claim killed:** novelty of cancellation propagation.  
**Claim preserved:** static capability obligations integrated with that propagation remain distinct.

### B5. Linear and affine effect handlers

**Verdict:** answered; see Decision-changing result 1.

The strongest remaining theoretical edge is not “linear handlers.” It is the interaction among external specifications, host-IR imprecision, runtime-shaped capability storage, and a restricted boundary interpreter.

Fresh supporting evidence: [*Yarrow: Reconciling Effect Handlers and Region-Based Memory Management*](https://arxiv.org/html/2607.15876v1) (July 2026 preprint) gives a Rocq/Iris logic for regions, fibers, once/many effects, and resources exchanged through operation protocols. It has no prototype runtime or type system yet, but it further narrows any claim that handlers plus resources are untreated.

### B6. Handler-over-handler layering

**Verdict:** generic composition confirmed; exact linear typestate layering not found. See Decision-changing result 3.

### B7. No-resumption handlers

**Verdict:** reclassify as implicit tail resumption on success and abortive handling on failure. See Decision-changing result 2.

### C8. Typestate over a dynamically typed language

**Broad claim refuted.** [SPY](https://arxiv.org/abs/1312.2704) applies externally declared Scribble protocols to dynamically typed Python participants and checks traces at runtime. Typed Clojure statically checks Clojure analyzer output. [Dynamic Region Ownership for Concurrency Safety (PLDI 2025)](https://doi.org/10.1145/3729313) retrofits region ownership into a dynamically typed language, although enforcement is dynamic rather than perturb’s external static typestate pass.

**Narrow claim remains unanswered.** No reviewed system combined all of:

- external protocol declarations;
- a static pass over an existing dynamically typed language’s compiler IR;
- substructural typestate for resources; and
- coordinated fail-closed boundary monitoring.

**Claim killed:** “typestate or resource protocols in dynamic languages are absent.”  
**Defensible replacement:** the conjunction above was not found.

### C9. Measured rejection causes

**No comparable answer found.** Typed Clojure reports porting and annotation difficulties, but not a taxonomy of rejected protocol programs. Ownership papers motivate their designs with pervasive annotations and unnatural rewrites, but none of the reviewed evaluations identifies “capability stored in a runtime-shaped collection” as its dominant rejection cause.

**Claim affected:** perturb’s three measurements remain original and decision-relevant. They should be published with denominators, corpus-selection rules, and a mutually exclusive rejection taxonomy. The literature does not make E6 unnecessary.

### D10. Regions since Milano, Turcotti, and Myers

**Confirmed active.** Gallifrey’s domination-based regions remain an active line, while Verona publications continue developing reference capabilities and flexible memory management; see the [Verona publication index](https://microsoft.github.io/verona/publications.html). PLDI 2025’s dynamic region ownership is an especially relevant retrofit.

**Corrected positioning.** Region-as-unit-of-ownership is neither abandoned nor merely a stack-disciplined historical technique. It is a live way to admit object graphs while tracking authority at a coarser boundary.

**No answer found.** Evidence remains prototype/evaluation-level. This survey found no mature industrial deployment study quantifying adoption cost.

**Claim killed:** novelty of regions as the missing collection abstraction.  
**Action:** evaluate a region/arena/existential-container alternative directly against perturb’s measured four-shape failures.

### D11. Shared heaps and generational handles

**Partly answered.** Regions and reference capabilities type shared object graphs by moving ownership to a region/root authority. Industry slot maps and generational indices provide dynamic lookup and stale-handle detection, but usually move occupant lifetime and protocol state to runtime.

**No typed account found.** This survey did not locate a peer-reviewed system in which an ordinary generational index preserves static linear typestate for each dynamically inserted occupant.

**Claim affected:** this remains a plausible research seam, but it is an absence-of-evidence result. Expand any publication search to dependent maps, world-indexed heaps, ST-style encapsulation, capability machines, and existential packages before claiming novelty.

### D12. Dynamically many sessions

**Broad question answered.** [Dynamic Multiparty Session Types (ECOOP 2023)](https://doi.org/10.4230/LIPIcs.ECOOP.2023.6) supports unbounded fresh participants and dynamically changing topologies, proves deadlock freedom and liveness, and generates Go. Its artifact includes dynamic delegation, recursive DNS, and parallel Min-Max examples. Earlier [dynamic multirole session types](https://www.doc.ic.ac.uk/~yoshida/paper/main.pdf) permit participants to join and leave role classes dynamically.

**Refuted.** Session typing does not inherently require a statically bounded population of sessions.

**Residual distinction.** These systems structure dynamic participants through their own calculi and generated APIs. They do not necessarily type an ordinary host-language map whose values contain existentially different live endpoint states.

**Claim killed:** “session systems cannot express an unbounded server.”  
**Claim preserved, narrowed:** perturb cannot currently express the ordinary host-language collection representation it wants.

### E13. Sum destinations and discriminator predicates

**Confirmed standard obligation.** The direct dynamic-Lisp precedent is [Typed Racket occurrence typing](https://docs.racket-lang.org/ts-guide/occurrence-typing.html): predicates carry logical propositions that refine union members along branches. If the proposition is supplied by an external declaration, it is a trusted boundary exactly like perturb’s discriminator.

Sounder alternatives are materially different:

- return a genuine tagged sum and eliminate it by constructor matching, making the representation the witness;
- return an existential/dependent pair or GADT containing both the outcome and a state witness;
- verify the predicate and transition relation using refinements, SMT, or proof terms.

[Flux](https://doi.org/10.1145/3591283) demonstrates ownership plus liquid refinements and strong updates, but only within solver-supported logic and Rust’s aliasing discipline. It does not make arbitrary host predicates truthful. Re-running the same discriminator dynamically also cannot establish honesty without independent state evidence.

**Refuted.** An unchecked declared predicate does not discharge uncertainty. It relocates the proof obligation into an axiom.

**Claim killed:** novelty of “declared discriminator predicate at `if`.”  
**Axis correction:** this point belongs under trusted/assumed unless a structural witness or proof connects the predicate to the transition.

### E14. Name for nondeterministic target refined by a test

**Answered.** At the type level this is **occurrence typing**, **flow-sensitive refinement**, or union/sum elimination. At the transition-system level, execution maintains a set or belief state of possible targets and an observation filters that set—the usual powerset/subset-construction view of a partially observed transition system.

Session external branching (`&`) is analogous only when a received label is itself the honest discriminant. GADT refinement is analogous only when constructors carry unforgeable evidence. An arbitrary declared predicate is neither.

**Claim corrected:** perturb reinvented occurrence refinement with a trusted proposition; the transition itself need not be called nondeterministic if the returned value deterministically identifies its branch.

### F15. Deriving static and runtime enforcement from one specification

**Confirmed mature discipline.** JavaMOP/RV-Monitor generates instrumentation and monitors from one property. Scribble projects a global protocol into endpoint APIs and supplements Java’s static order checking with generated runtime linearity checks; see [Hu and Yoshida](https://www.doc.ic.ac.uk/~rhu/scribble/fase16.pdf). [StaRVOOrS](https://starvoors.github.io/files/papers/starvoorsfm2015.pdf) combines static and runtime verification from one ppDATE formalism.

**Refuted.** “Cannot drift” is too strong. Single-source generation prevents two manually maintained semantic specifications, but generator defects, event-extraction mismatch, stale generated artifacts, interception gaps, and errors in the common specification remain. Stronger assurance needs a synthesis-correctness argument plus build/version coupling.

**Claim killed:** novelty of deriving static and runtime arms from one source.  
**Claim preserved:** perturb’s particular fail-closed integration remains a meaningful system contribution if boundary coverage and generator coherence are tested.

### F16. Cost of monitoring at I/O boundaries

**Refuted blanket amortization.** Syscall-policy monitors are not identical to perturb’s monitor, but they directly disprove the intuition that proximity to I/O makes checks automatically negligible:

- [SysXCHG](https://cs.brown.edu/people/vpk/papers/sysxchg.ccs23.pdf) reports roughly 0–2.74% overhead for one seccomp-BPF configuration and 0–1.71% for its filter on the evaluated macrobenchmarks.
- [Draco](https://microarch.org/micro53/papers/738300a042.pdf) reports about 25% on a repeated `getppid` microbenchmark with seccomp, around 20% on ARM for simple checks, cites a prior 45% sandbox figure, and reports roughly 1.14× macro / 1.25× micro means before hardware caching.
- [SFP](https://arxiv.org/abs/2301.02915) reports a low average but materially higher worst-case overhead.
- The [Linux runtime-verification documentation](https://www.kernel.org/doc/html/v6.5/trace/rv/runtime-verification.html) explicitly makes feasibility depend on event frequency and monitor throughput.

O(1) transition lookup is the correct architecture, not evidence of a negligible constant. Blocking network or disk latency can hide checks; cached reads, loopback, short socket operations, high packet rates, tracing, synchronization, and allocation may not.

**Decision:** benchmark ns/operation and end-to-end workloads. Include scripted/in-memory handlers, loopback, cached I/O, short writes, batching, concurrency, and transcript-disabled controls.

### F17. Production firing rates and axiom error base rates

**Production base rate unanswered.** No defensible general firing rate was found for perturb-like typestate or syscall monitors in production.

The nearest large empirical result is warning rather than reassurance. [Legunsen et al.](https://doi.org/10.1145/2970276.2970356) ran JavaMOP with 182 handwritten and 17 mined API properties across 200 open-source projects, more than 18,000 manual tests, and 2.1 million generated tests. The study found real accepted bugs—95 reported, 74 fixed—but inspected violations had very high false-alarm rates: 82.81% for handwritten specifications and 97.89% for mined ones. Only 11/182 handwritten and 3/17 mined specifications led to discovered bugs. Average overhead was below 4.3×, still far above a “nearly free” presumption.

This was testing, not production, and a false alarm is not identical to a dishonest protocol axiom. It nevertheless shows that specification/context error can dominate monitor alerts.

**Claim weakened:** monitors may be useful, but raw violation count is not defect count and positive controls prove non-vacuity rather than practical yield. An alert-validation and triage design is part of the feature, not an operational afterthought.

### G18. Flo and octet-level framing

**Refuted equivalence.** [Flo](https://doi.org/10.1145/3704845) defines boundedness as a guarantee that an evolving collection eventually becomes fixed, permitting operators to block on termination. `nest` concerns streams of streams whose inner streams satisfy fixedness restrictions. The paper contains no byte, packet, or framing instantiation.

A length prefix states an expected cardinality and segment boundary; it does not guarantee that the peer will deliver those bytes or terminate. Conversely, a Flo-bounded stream need not expose a length. They become related only after adding a transport premise—exactly *n* bytes eventually arrive, or EOF/timeout converts non-arrival into failure—and a decoder that consumes exactly *n*.

Verified binary-parser systems such as [EverParse](https://www.normalesup.org/~ramanana/research/everparse/pldi2022/paper.pdf), [Vest](https://www.usenix.org/conference/usenixsecurity25/presentation/cai-yi), and [Narcissus](https://adam.chlipala.net/papers/NarcissusICFP19/NarcissusICFP19.pdf) are closer to the framing obligation but are not Flo instantiations.

**Claim killed:** a length-prefixed body and Flo boundedness are “the same obligation stated twice.”

### G19. Typed sans-I/O parsing

**Partly confirmed; exact combination unanswered.** Formal typed parsing is rich: Narcissus proves encoder/decoder correctness and state relations; EverParse/LowParse and Vest generate verified binary parsers; dependent regular grammars and typed protocol-format work cover data-dependent fields and contextual state. Practical [sans-I/O design](https://sans-io.readthedocs.io/how-to-sans-io.html) explicitly separates synchronous byte/state processing from transport I/O.

No primary formal account was found that simultaneously provides:

- chunked transport input;
- `consumed | need-more | invalid`;
- explicit protocol typestate; and
- restoration of the exact original cursor on `need-more`.

Many verified parsers operate on a finite supplied buffer and return failure/option; much “incremental parsing” literature concerns document edits rather than streaming chunks.

**Claim preserved, narrow:** exact transactional rollback for typed streaming sans-I/O parsing remains an evidence gap in this survey. Do not broaden that into “typed sans-I/O has no prior art.”

## Positioning in both directions

### Direction 1: perturb as an effect-handler language

This positioning is weak unless perturb grows first-class resumptions or makes a formal contribution to same-effect layered interpretation with linear typestate.

What is already occupied:

- sound combination of handlers and linear resources;
- linear/affine/abortive control-flow classifications;
- one-shot and multi-shot continuation tracking;
- generic effect forwarding and handler composition;
- capability-passing implementations;
- runners for external resources and finalization.

What may remain:

- same-effect layering with typestate-linear resources;
- a formal account tied to external declarations over imprecise dynamic IR;
- proof that the fail-closed boundary and static checker enforce coherent projections of one specification.

**Recommendation:** do not lead with “effect handlers.” Describe D4 as the implementation substrate, and claim theoretical novelty only after formalizing an obligation the existing systems do not cover.

### Direction 2: perturb as a protocol-capability checker and boundary interpreter

This is the stronger positioning.

Nearest neighbors:

| Perturb property | Nearest prior art | Remaining difference |
|---|---|---|
| Static checking of dynamic Clojure code | Typed Clojure | No substructural typestate or coordinated boundary monitor |
| External signatures over host code | Checker Framework stubs | Primarily static-language qualifier ecosystem |
| External protocols for dynamic programs | SPY/Scribble | Runtime monitoring rather than static IR linearity |
| Dynamic-language ownership retrofit | Dynamic Region Ownership | Dynamic enforcement and region authority rather than binding typestate |
| Resource-safe boundary interpretation | Runners, capability-passing handlers | Not external dynamic-host protocol declarations |
| Static/runtime protocol split | Scribble/FuSe/MPST monitors | Different host and enforcement boundary |
| Fail-closed interception | Sandbox/syscall mediation traditions | Perturb-specific integration with the declaration/checker ledger |

The defensible claim is therefore integrative:

> Perturb investigates whether externally supplied capability protocols can support useful static substructural checking over a Clojure-like compiler IR while using the same protocol source to monitor obligations that static analysis refuses or must assume, with boundary bypass converted into a latched run failure.

That statement is falsifiable. It does not claim the component techniques are new.

## The three places perturb was most likely wrong

1. **The control-effect classification.** “No resumption” hides an implicit tail resumption on success. This misclassification sent the search toward the wrong novelty claim.
2. **The breadth of the linear-handler gap.** POPL 2024 and 2025 substantially close it. The remaining problem is the exact integration and resource representation, not the general theory.
3. **The claim that binding-known shape is the only plausible root abstraction.** Dynamic session systems, regions, existential participant structures, and dynamic region ownership show multiple ways to move the indexing or ownership boundary. Perturb measured a real failure of its representation, but not a unique law of typestate checking.

A fourth deserves explicit mention: the brief too readily equated “shipped” with “adopted” and “no longer prominent” with “abandoned” for Typed Clojure. The primary evidence supports high burden and partial coverage, not abandonment.

## Recommended next work

1. **Rewrite the positioning axes.** Treat them as independent dimensions rather than monotone scales. Replace the control axis with abortive / implicit tail-resumptive / explicit one-shot / multi-shot.
2. **Rename D4 precisely.** Suggested term: “tail-resumptive result-substitution boundary with abortive failure,” or in plainer systems language, “typed operation interception with fail-closed failure.”
3. **Keep E6 and expand it.** Measure join-rule incidence, coverage, annotations/kLOC, unchecked escapes, false accepts, false rejects, and rejection causes on real programs. Typed Clojure does not answer this.
4. **Prototype one runtime-indexed ownership abstraction.** Compare an existential capability table or region/arena against the existing binding-shaped checker on the four known failures.
5. **Test layered same-effect interpretation directly.** State the invariant that an outer HTTP/TCP/descriptor layer must preserve, then build both a known-good and a known-bad composition. This is the narrow B6 gap the survey did not close.
6. **Generate static and runtime projections from one declaration.** Check generator coherence mechanically, while continuing to label implementation conformance as monitored rather than proved.
7. **Benchmark monitoring where it can hurt.** Include loopback, in-memory scripted handlers, small writes, batching, concurrency, and transcript-disabled controls—not only real network latency.
8. **Do not claim novelty from absence yet.** The two strongest absence findings—typed generational capability tables and the exact four-way conjunction—need a publication-quality systematic search and explicit inclusion criteria.

## Evidence-quality summary

- **Full text inspected:** Tang et al.; Qualified Effect Types; Affect; Yarrow; Typed Clojure dissertation; SPY; DMst/GoScr; core effect-compilation papers cited above; relevant official language/tool documentation.
- **Official/manual evidence:** Typed Clojure project state, clojure.spec behavior, Checker Framework stubs, Erlang links and monitors.
- **Abstract or publication metadata used cautiously:** some adoption and follow-on publication claims where full text was not necessary or not accessible.
- **Negative findings:** always mean “not found in this survey,” never proof of nonexistence.

## Core bibliography

- Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris. [*Soundly Handling Linearity*](https://doi.org/10.1145/3632896). POPL 2024.
- Jonathan Immanuel Brachthäuser and Daan Leijen. [*Qualified Effect Types—Taming Control-Flow through Linear Effect Handlers*](https://www.microsoft.com/en-us/research/publication/qualified-effect-types/). Technical report.
- Orpheas van Rooij and Robbert Krebbers. [*Affect: An Affine Type and Effect System*](https://doi.org/10.1145/3704841). POPL 2025.
- Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal. [*Yarrow: Reconciling Effect Handlers and Region-Based Memory Management*](https://arxiv.org/html/2607.15876v1). 2026 preprint.
- Danel Ahman and Andrej Bauer. [*Runners in Action*](https://doi.org/10.1007/978-3-030-44914-8_2). ESOP 2020.
- Ambrose Bonnaire-Sergeant. [*Typed Clojure in Theory and Practice*](https://thesis.ambrosebs.com/). Dissertation.
- Werner Dietl et al. [*Building and Using Pluggable Type-Checkers*](https://doi.org/10.1145/1985793.1985819). ICSE 2011.
- Subarno Banerjee et al. [*NullAway*](https://arxiv.org/abs/1907.02127). 2019.
- Raymond Hu et al. [*Session Types in Python: SPY*](https://arxiv.org/abs/1312.2704). 2013.
- [*Dynamic Multiparty Session Types*](https://doi.org/10.4230/LIPIcs.ECOOP.2023.6). ECOOP 2023.
- Mae Milano, Rose Bohrer Turcotte, and Andrew C. Myers. [*A Flexible Type System for Fearless Concurrency*](https://doi.org/10.1145/3519939.3523443). PLDI 2022.
- [*Dynamic Region Ownership for Concurrency Safety*](https://doi.org/10.1145/3729313). PLDI 2025.
