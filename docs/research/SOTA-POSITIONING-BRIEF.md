# Brief: where perturb sits in the design space, and what to pull next

**For an agent with real internet access.** The session that wrote this has an
outbound proxy that refuses arXiv at CONNECT and gets Cloudflare 403s from the
ACM, so everything below is what could be established from papers already in
hand plus reading neighbouring code. Treat every claim in it as a hypothesis to
check, not a premise.

**Licence rule, learned the hard way.** A previous pass excluded five CC-BY
papers by reading the licence line printed on the PDF. For PACMPL (POPL / ICFP /
OOPSLA / PLDI *Proceedings of the ACM*) the grant lives in the publisher's
**Crossref metadata**, not always in the page furniture: query
`api.crossref.org` for the DOI and check `license` for
`creativecommons.org/licenses/by/4.0`. Prefer author or institution copies of the
version of record. Commit only what is redistributable, and record the licence
basis per item.

**Output format.** Findings in the style of `docs/research/PERTURB-DESIGN.md` §3:
for each question, what is **confirmed**, what is **refuted**, what has **no
answer in the literature**, and — this matters most — **which claim of ours it
kills**. A finding that refutes something we believe is worth ten that agree with
us. State plainly when a paper was read in full versus skimmed versus known only
from an abstract; a previous pass conflated these and three of its conclusions
were wrong as a result.

---

## What perturb is, in one paragraph

A capability/effect checker over the IR of **Jolt**, a Clojure compiler on Chez
Scheme. Capabilities are declared with four axes — uniqueness, linearity
(`:once`), typestate (an explicit state machine with transitions), contention —
and operations are annotated `:consumes` / `:borrows` / `:produces` with argument
and result positions. A static checker rejects use-after-move and out-of-protocol
sequences. Effects have substitutable handlers under "D4": a handler substitutes
a validated result or aborts, **with no resumption**. Declarations are written
*from outside* the code they describe, including for third-party libraries that
know nothing about them. A ledger records every capability transition and
validates nothing. The effect boundary fails closed and latches, so a caught
exception cannot make a run report success.

Recent state: sum destination states with a discriminated case split; a
`MUST_CLOSE`-style cancellation-as-a-state; a measured refutation of the claim
that four host-interop IR ops should collapse into one `:extern`.

---

## The seven axes, and where we think we sit

Check the positioning as much as the questions. If we have mislocated ourselves
on an axis, say so.

1. **Substructural strength.** unrestricted → affine → linear → linear + explicit
   cancellation → graded/quantitative. We are at *linear*, with an `abort!` path
   that has neither affinity nor a cancellation term.
2. **What protocol state attaches to.** value/binding → object → channel/session
   → region → object graph. We attach to a **binding of statically known shape**,
   which we believe is the narrowest live choice and is the single root cause of
   every expressiveness failure we have measured.
3. **Where uncertainty is discharged.** static proof → refinement + SMT → sum
   type + case split → runtime interrogation with a total fallback → monitored →
   assumed. We span four.
4. **Control effects.** none → no-resumption handlers → one-shot → multi-shot →
   delimited control. We chose no-resumption and deferred the rest.
5. **Boundary / TCB posture.** sealed abstraction → module boundary with blame →
   hand-written axiom list → verified TCB → dynamically-checked TCB → fail-closed
   interception.
6. **Contention.** thread-confined → borrow → region/domination → session
   channels → actor. We declare thread-confined and have **never tested it**.
7. **Host integration.** closed language → gradual boundary → checker over an
   existing compiler's IR with external declarations. We are at the far end, and
   we think this is underexplored.

---

## Questions, in priority order

### A. The prior art in our own ecosystem, which we have never cited

This is the highest-value section. We have been reading PLDI and skipping the
thing that already answered our usability question in our own host language.

1. **core.typed / Typed Clojure.** It was built, shipped, used and largely
   abandoned. Find the retrospective material — Ambrose Bonnaire-Sergeant's
   dissertation and papers, any post-mortem writing, issue-tracker evidence.
   What we need: **measured annotation burden**, checking cost, and the stated
   reasons for abandonment. Is there a number for how often a given typing rule
   forced a rewrite of idiomatic Clojure? We have an open question (E6) about how
   often our join rule fires on real programs, argued and never measured; this
   is the closest existing answer and may make measuring it unnecessary — or may
   tell us the answer is fatal.
2. **clojure.spec.** Its stance is declare-from-outside plus runtime
   instrumentation, which is exactly one arm of an open fork of ours. What is the
   practitioner evidence on what people actually annotate, where instrumentation
   is left on, and what it costs? Any measured data beats opinion here.
3. **Pluggable type systems generally** — the Java Checker Framework is the
   longest-running example of "a checker over an existing language with
   annotations written outside the code". What does its literature report about
   annotation burden, false-positive rates, and adoption? Is there a *measured*
   false-positive rate for any typestate checker on real code?
4. **Erlang/OTP links and monitors.** Cancellation propagation with peer
   notification has a thirty-year industrial answer. What does the OTP literature
   say about the semantics of link/monitor propagation, and is there any formal
   account that lines up with Fowler's zapper threads?

### B. Linear effect handlers — an acknowledged gap, and possibly ours to fill

Fowler et al. (POPL 2019, in hand) translate exception handling into effect
handlers and then say plainly: *"A formalisation of linear effect handlers for
session typing is outside the scope of this paper and left as future work."*
Tang et al. (POPL 2024, in hand) address control-flow linearity for effect
handlers.

5. **Has that gap been closed since?** Search 2019→now for linear/affine effect
   handlers, substructural effect systems, effect handlers with linear
   resources — Lindley, Hillerström, Fowler, Tang, Leijen, Brachthäuser,
   Schuster, Xie, Pretnar, and the Effekt / Koka / Links / Frank / OCaml 5
   communities. **We need to know whether we are standing in an open gap or in a
   crater someone already filled.** This is the single most decision-relevant
   question in the brief.
6. Specifically: is there an account of a handler that **performs the effect it
   satisfies** (handler-over-handler layering) in the presence of linear
   resources? That is the composition we need for "events over HTTP over TCP over
   a descriptor", and we are testing it experimentally right now with no theory
   to check the result against.
7. What is known about **no-resumption** handlers specifically? Most of the
   literature assumes at least one-shot resumption. Is the no-resumption
   restriction studied as a design point in its own right, and what does it buy
   or cost?

### C. Typestate over a dynamic language

8. Is there **any** prior system doing typestate or linear-resource checking over
   a dynamically-typed language's compiler IR, with specifications written
   outside the code? Vault and SAL did it for C; Plural/Plaid for Java. For
   Python, Ruby, JavaScript, Lua, or a Lisp — what exists, and what happened to
   it?
9. **What is the measured failure mode?** When a typestate discipline is applied
   to code not written for it, published data on *why* it rejects would be
   directly comparable with ours. We have three independent measurements
   converging on one cause (a capability may live only in a binding of statically
   known shape). Has anyone else reported a dominant cause, and is it the same
   one?

### D. The collection problem, which is our root cause

10. **Milano, Turcotti & Myers (PLDI 2022)** is in hand and admits all four shapes
    we cannot express, via regions whose intra-region references are untracked.
    What has happened since? Follow-on work, implementations, critiques, adoption
    in Verona or elsewhere. Is the "region as unit of ownership" line still live,
    and what has been learned about its costs in practice?
11. What is the current state of **ownership for heap structures with sharing** —
    Verona, Rust's arena and handle-table idioms, capability tables, generational
    indices? The `handle table with generational index` pattern is the informal
    industry answer to exactly our problem; is there any *typed* account of it?
12. **Session types with dynamically many sessions.** Our failure is a server
    holding N connections. How do session-typed systems handle an unbounded,
    runtime-indexed collection of sessions, and what do they give up?

### E. Sum destinations, refinements, and discriminators

13. We added a transition whose destination is one of several, chosen at run time,
    eliminated by a **declared discriminator predicate** at an `if`. Nothing
    checks the discriminator is honest — a lying one narrows to the wrong state
    and buys a false accept. This is a new class of axiom and we are unhappy
    about it. **What is the standard answer?** Candidates we know of: refinement
    types with SMT (Flux PLDI 2023 and Liquid Resource Types ICFP 2020, both in
    hand), typestate with predicates, GADT-style refinement, focusing/external
    choice in session types. Which of these actually discharges the obligation
    rather than relocating it, and at what cost?
14. Is there a name in the literature for what we built — a transition relation
    that is non-deterministic in its target, resolved by a runtime test that
    refines a static approximation? We suspect there is and that we reinvented it
    badly.

### F. The monitored arm, and its cost

15. `RUNTIME-OBLIGATION-BRIEF.md` in this repo proposes deriving runtime monitors
    from the same specification the static checker uses. **Is there a discipline
    for deriving monitors from a spec so the two cannot drift?** Runtime
    verification / monitor-oriented programming (Havelund, Roșu, JavaMOP) is the
    obvious place; Scribble's endpoint-API generation checks ordering statically
    and **linearity dynamically** because Java has no linear types, which is the
    same split.
16. **The cost question, still unanswered.** Takikawa et al. (POPL 2016) found
    boundary-contract overhead fatal for sound gradual typing. Does that transfer
    to monitors at *syscall* boundaries, where the check sits beside real I/O? We
    have one datum from neighbouring code — a coherence replay is O(1) per call
    and O(N) at snapshot boundaries, because full replay per call "would make N
    calls quadratic". Is there published measurement for monitors at I/O
    boundaries specifically?
17. **The negative result we most want.** Is there published evidence on how often
    runtime monitors of this kind actually *fire* in production, versus being dead
    weight? And is there a base rate anywhere for how often hand-written protocol
    axioms turn out to be wrong? If that literature exists it decides whether the
    monitored arm is worth building.

### G. Streams and framing

18. **Flo** (in hand) gives a semantics for progressive stream processing with a
    bounded/unbounded distinction, and `nest` re-bounds an unbounded stream —
    which maps exactly onto an unbounded stream of connections each carrying a
    bounded stream of request bodies. Its instantiations are Flink, LVars and
    DBSP, none byte-oriented. **Has anyone instantiated a stream semantics of this
    kind for octet-level transport framing?** We believe a length-prefixed body
    obligation and Flo's boundedness are the same obligation stated twice.
19. Is there a formal account of the **sans-io** pattern — a protocol parser as a
    pure function of bytes and state, returning "consumed / need more / invalid",
    with the *exact original cursor* returned on need-more? It is folklore in
    Python (h11, hyper-h2) and Rust; we want to know if anyone has typed it.

---

## What to bring back

- Answers in the finding format above, ordered by **how much they change what we
  would do next**, not by topic.
- For every "you should adopt X": the *cost*, the *failure literature*, and what
  X does **not** do. We have been burned by adopting a mechanism whose escape
  clause covered the wrong half of the problem.
- Papers whose licence permits redistribution, committed with the licence basis
  recorded per item. A markdown conversion alongside each PDF so it can be
  grepped.
- **Explicitly: the three places we are most likely to be wrong**, chosen by you
  from what you read. Prior passes that only confirmed things turned out to have
  been reading abstracts.
