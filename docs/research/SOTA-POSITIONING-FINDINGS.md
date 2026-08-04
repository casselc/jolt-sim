# Perturb state-of-the-art positioning: research findings

**Status:** research report, not a language decision and not implementation
authority. It answers the questions in the supplied
`SOTA-POSITIONING-BRIEF.md` against the branch at
`274f49fc354dd556cfa87210c92e09f96449ad0e`.

**Method.** Four independent, read-only research passes covered (A/C) dynamic
language and pluggable-system precedents, (B/E) linear handlers and
discriminated typestate, (D/G) collections/sessions/streams, and (F) runtime
monitoring. This report retains only conclusions whose cited source or primary
metadata was subsequently checked. `full` means a local PDF or open full text
was read; `abstract` means only the abstract/metadata was available; `docs`
means project or official documentation. Absence of a result is not proof that
no such work exists.

## Executive findings, ordered by decision impact

1. **REFUTED — linear effect handlers are not an unfilled theoretical gap.**
   Tang et al.'s *Soundly Handling Linearity* gives a calculus with linear
   types, effect types, and handlers, and introduces *control-flow linearity*
   so handlers cannot discard or duplicate a continuation that captures a
   linear value. It also adapts Links to repair a real soundness bug. It does
   **not** prove Perturb's checker, external declarations, refined typestate,
   native resources, or `abort!` cleanup. The correct conclusion is that the
   Fowler-2019 gap is **partly closed**: continuation/resource integrity has a
   direct theory; cancellation protocol obligations remain separate.

   - Source: Tang, Hillerström, Lindley, Morris 2024, `full`, §1–§5;
     [`soundly-handling-linearity.pdf`](papers/soundly-handling-linearity.pdf),
     [conversion](papers/soundly-handling-linearity.md),
     [DOI](https://doi.org/10.1145/3632896).
   - **Claim weakened:** “we are standing in an open gap” is too broad. A new
     contribution must be the Perturb-specific combination, not linearity plus
     handlers alone.

2. **CONFIRMED, with a terminology correction — no-resumption is a sound
   simplification only if it really excludes continuation capture.** A handler
   which only substitutes or aborts has no handler continuation to duplicate or
   discard. However, the existing `proceed` seam is described in the branch as
   scoped and single-use. That is a **one-shot resumption / linear
   continuation**, not literally no continuation. It is a sensible smaller
   design point, but it must retain its scope, owner-thread, and single-use
   guards. If D3 adds general handlers, control-flow linearity becomes a
   necessary design input.

   - Source: Tang et al. 2024, `full`, §1–§3.
   - **Claim weakened:** “substitute or abort” is exhaustive if a reachable
     `proceed` exists.

3. **CONFIRMED — exceptions over linear sessions need an explicit
   cancellation story.** Fowler et al. formally integrate asynchronous session
   types and exception handling, with a Links implementation. This is direct
   precedent for treating an abort path as more than ordinary affine discard.
   It does not prescribe Perturb's exact `abort!` transition; Perturb must name
   which capabilities are cancelled, what peer-visible action follows, and
   what happens when cancellation itself fails.

   - Source: Fowler, Lindley, Morris, Decova 2019, `full`, introduction and
     exception/cancellation development;
     [`exceptional-asynchronous-session-types.pdf`](papers/exceptional-asynchronous-session-types.pdf),
     [conversion](papers/exceptional-asynchronous-session-types.md),
     [DOI](https://doi.org/10.1145/3290341).
   - **Claim retained:** control-flow linearity does not itself supply the
     protocol transition or peer notification required by cancellation.

4. **CONFIRMED — declared discriminators do not establish their own honesty.**
   The ordinary static forms are a tagged sum, GADT-indexed state, a dependent
   sum/existential package, or a refinement proved at a branch. A declared
   predicate at an `if` only relocates trust unless the checker verifies the
   body that produces the tag/state, or the representation is sealed and a
   runtime monitor guards the opaque boundary. Session choice constrains the
   alternatives but cannot prove that unchecked code chooses the semantically
   correct label.

   - Evidence: formal-systems research pass, `full` for Tang et al. and
     branch design/code evidence; no single paper was found that proves the
     exact Perturb declaration-plus-discriminator mechanism.
   - **Claim killed:** the current declaration alone narrows a sum destination
     soundly. It is an axiom until one of the mechanisms above is added.

5. **CONFIRMED, but bounded — regions answer graph-as-a-unit ownership, not a
   dynamic connection table.** Milano, Turcotti, and Myers allow arbitrary
   intra-region graphs and track isolated inter-region references. Regions can
   therefore represent ownership of a graph as a unit, and `if disconnected`
   is a runtime test which selects a statically typed branch. The paper does
   not establish independent lookup, removal, or transfer of a single member
   of a growable `ConnId → session` table, and does not cover algebraic
   handlers, exceptions, or multi-shot control. Its virtual-transformation
   search has a stated worst-case exponential cost.

   - Source: Milano, Turcotti, Myers 2022, `full`, §§2.2, 4–7;
     [`PLDI22-flexible-type-system-for-fearless-concurrency.pdf`](papers/PLDI22-flexible-type-system-for-fearless-concurrency.pdf),
     [conversion](papers/PLDI22-flexible-type-system-for-fearless-concurrency.md),
     [DOI](https://doi.org/10.1145/3519939.3523443).
   - **Claim weakened:** “collection-held capabilities are foreclosed” is too
     strong. Reopening regions would be a distinct, effects-aware language
     design, not a small extension to the present checker.

6. **CONFIRMED — dynamically many clients have formal session accounts, but
   not the whole connection-table problem.** Coexponentials model a server
   accepting requests from arbitrarily many clients. The available source does
   not prove safe runtime-indexed storage, selection, and removal of linear
   endpoints in an application map. A practical alternative is an owned table
   with branded generational handles and monitored transitions; that is a
   runtime engineering discipline, not a static proof of arbitrary collection
   use.

   - Source: Qian, Kavvos, Birkedal, *Client-Server Sessions in Linear Logic*,
     `abstract`, [arXiv:2010.13926](https://arxiv.org/abs/2010.13926).
   - **No local PDF:** arXiv labels the item nonexclusive-distribution rather
     than CC-BY; this repository does not redistribute it.

7. **REFUTED — Flo boundedness is not EOF-vs-empty.** Flo distinguishes a
   stream that may eventually become fixed from one that may not. It gives
   useful composition obligations—streaming progress and eager execution—and
   supports nested graphs with cycles. It does **not** distinguish a receive
   that currently has no bytes from a receive that has reached EOF. A framing
   source must retain an explicit `Pending | Chunk(bytes) | End`-like fact
   before Flo-style semantics can apply.

   - Source: Laddad, Cheung, Hellerstein, Milano 2025, `full`, §§3–5;
     [`flo-semantic-foundation-progressive-stream-processing.pdf`](papers/flo-semantic-foundation-progressive-stream-processing.pdf),
     [conversion](papers/flo-semantic-foundation-progressive-stream-processing.md),
     [DOI](https://doi.org/10.1145/3704845).
   - **Claim killed:** bounded/unbounded alone supplies the missing
     `recv`-outcome distinction.

8. **CONFIRMED, with a sharp limit — runtime verification can derive monitors
   from a specification, but cannot make a hand-written checker and monitor
   agree by itself.** JavaMOP-style monitor synthesis and Scribble-style API
   generation establish the method. A single source specification prevents
   spec-to-generated-artifact drift only when both artifacts are generated or
   share a checked elaborator. It does not verify hand-written transition
   bodies. An effect-boundary monitor detects violations on observed executions;
   it is neither prevention nor a proof, and it cannot observe a semantic
   obligation that leaves no monitored trace fact.

   - Evidence: runtime-verification research pass, `abstract/docs` where the
     original hosts were inaccessible; the conclusion is deliberately limited
     to the established generation discipline.
   - **Claim weakened:** “derive both and they cannot drift” requires the
     checker to be derived too; a generated monitor alone is insufficient.

9. **NO ANSWER — monitor cost at syscall/effect boundaries and production
   firing rates.** *Is Sound Gradual Typing Dead?* measures boundary-contract
   overhead in CPU-hot paths, not monitors adjacent to host I/O. It therefore
   neither proves nor refutes the hypothesis that an O(1) check is negligible
   beside a syscall. No credible published production base rate for protocol
   monitor firings or hand-written protocol-axiom errors was found. Perturb
   must measure its own event boundary with a disabled baseline, a positive
   control, and frequency-sensitive workloads.

   - **Claim retained as a hypothesis only:** monitor cost is I/O-dwarfed.
   - **Nonclaim:** an E16-style no-bypass experiment remains bounded to its
     instrumented bindings and test runs; it is not global effect-boundary
     completeness.

10. **NO ANSWER — adoption, annotation burden, and rejection-cause data do
    not calibrate this design.** No peer-reviewed measured burden or
    rewrite-frequency result was found for core.typed or clojure.spec. Nor was
    a published rejection-cause distribution found for the exact combination of
    a dynamic-language compiler IR, external typestate declarations, and a
    capability-only checker. This supports novelty, not feasibility or
    usability.

## Direct transport precedent

**CONFIRMED, narrow.** Cavoj et al. implement a synchronous subset of TCP in
Rust with session-type tokens and test it against the Linux TCP stack. It is
useful evidence that transport-layer session descriptions can be made concrete.
It explicitly leaves timeout modelling outside the type system and does not
solve asynchronous reordering, a dynamic connection table, or Perturb's effect
and capability interactions.

- Source: Cavoj, Nikitin, Perkins, Dardha 2024, `full`, §§4.4–4.7 and §5;
  [`session-types-transport-layer.pdf`](papers/session-types-transport-layer.pdf),
  [conversion](papers/session-types-transport-layer.md),
  [DOI](https://doi.org/10.4204/EPTCS.401.3).

## What this changes in the design queue

1. Make D4's boundary explicit: either genuinely no continuation, or an
   explicitly specified one-shot `proceed`; do not call the latter no-resumption.
2. Treat `abort!` cancellation as a first-class capability transition with
   peer-visible semantics and failure handling before claiming linear safety
   across non-local exit.
3. Do not add regions as a checker patch. First specify their interaction with
   handlers/continuations and decide whether graph-as-a-unit ownership is worth
   the new heap and checker semantics.
4. For the near term, use a trusted owned connection-table boundary with
   generation-checked handles and monitors. Label it `monitored`, not statically
   guaranteed.
5. Split transport-source status (`pending`, bytes, end) before adopting Flo
   laws for framing; state cursor/prefix invariants separately.
6. Build the smallest monitor experiment only after defining positive controls,
   an observed-obligation coverage metric, and a benchmark whose effect rate is
   reported. It cannot be justified from a transferred gradual-typing number.

## Redistributable paper register

The following artifacts are stored under `docs/research/papers/`. Each `.md`
is a machine text conversion of the adjacent PDF, preserving PDF page
boundaries; equations and layout require consultation of the PDF.

| Paper | Local artifacts | redistribution basis | source basis |
| --- | --- | --- | --- |
| Tang et al., 2024 | `soundly-handling-linearity.{pdf,md}` | CC-BY-4.0 | Crossref `license` for DOI 10.1145/3632896; arXiv author version also labels CC-BY-4.0 |
| Fowler et al., 2019 | `exceptional-asynchronous-session-types.{pdf,md}` | CC-BY-4.0 | Crossref `license` for DOI 10.1145/3290341; PDF is the author-hosted extended version cited in that record |
| Milano et al., 2022 | `PLDI22-flexible-type-system-for-fearless-concurrency.{pdf,md}` | CC-BY-4.0 | Crossref `license` for DOI 10.1145/3519939.3523443 |
| Laddad et al., 2025 | `flo-semantic-foundation-progressive-stream-processing.{pdf,md}` | CC-BY-4.0 | Crossref `license` for DOI 10.1145/3704845 |
| Cavoj et al., 2024 | `session-types-transport-layer.{pdf,md}` | CC-BY-4.0 | arXiv version 2404.05478 explicitly labels CC-BY-4.0 |

No paper is added merely because an abstract is publicly readable. In
particular, the Qian–Kavvos–Birkedal arXiv item and the gradual-typing and
stateful-contract references are linked above or in the source briefs but are
not redistributed here because this pass did not establish a permissive
redistribution license for their specific PDFs.

## Evidence boundary

This report does not prove Perturb sound, useful, or competitive. It does not
measure annotation burden, false accepts/rejects, monitor overhead, monitor
firing frequency, real network behaviour, or cross-platform support. Its
strongest statements are source-backed positioning corrections; any subsequent
implementation claim needs its own executable evidence.
