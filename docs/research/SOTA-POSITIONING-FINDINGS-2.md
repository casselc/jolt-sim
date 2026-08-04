# Perturb state-of-the-art positioning: round 2

**Status:** research report; not a language decision or implementation
authority.  This answers the supplied `SOTA-POSITIONING-BRIEF-2.md` against
`b06dddc9df2bb7da678b126c15ab22257d8d30f5` on 2026-08-04.  Four independent,
read-only research assignments covered the brief's partitions.  Results were
reconciled against cited primary-source metadata where available.  `full`,
`skim`, and `abstract` describe the reported source access; an absence result
means only “not found under the stated protocol”.

## Executive findings, ordered by what changes next

1. **CONFIRMED — runners are the right analogy, but the current boundary is
   not literally a runner.**  Ahman and Bauer's runner calculus supplies
   hidden runner state, a user/kernel distinction, tail/one-shot continuation
   constraints, recoverable exceptions versus signals, and finalisation.  Its
   reported exactly-once finalisation result is relevant to cancellation, but
   the current controller/descriptor seam supplies none of those constructs.
   Do not add first-class runners merely to validate B6.  First add correlated
   forwarding records, lower-rung attribution, terminal-latch propagation, and
   replayable traces to the existing seam.

   - *Runners in Action*, `skim` (full PDF retained),
     [DOI](https://doi.org/10.1007/978-3-030-44914-8_2).
   - **Nonclaim:** runner finalisation does not prove the Jolt adapter's
     restoration, cancellation, or protocol-resource behaviour.

2. **CONFIRMED — same-effect outward forwarding is compatible with Tang et
   al.'s deep-handler account, but it does not establish Perturb's protocol
   invariant.**  The innermost handler is selected; a same-label operation
   performed by its clause is consequently available to an outer handler.  A
   control-flow-linear outer resumption must be used once.  However, deep
   handlers cannot capture linear variables, and the calculus does not cover
   dynamic `via`, external declarations, mutable handles, or fail-closed
   attribution.  The present handler map must therefore not be described as
   enforcing that semantics merely because `via` can simulate it.

   - Tang et al., *Soundly Handling Linearity*, `full`; existing local copy:
     [`papers/soundly-handling-linearity.pdf`](papers/soundly-handling-linearity.pdf).
   - **Proposed (not literature-proved) B6 frame:** a forwarding refinement
     over a labelled transition system.  For each declared request a layer
     either takes a declared local transition or emits exactly one correlated
     `forward` to its immediate lower layer.  Projection of the composed trace
     onto each capability must be a legal protocol trace; a lower refusal may
     not become upper success except through an explicit error-mapping edge.
     The existing call-over-call and abort-laundering examples are negative
     controls for this executable invariant, not counterexamples to Tang.

3. **CONFIRMED — split terminal discharge from dead-name permission.**  The
   close CAS can establish that precisely one runtime invocation won; it does
   not statically discharge obligations held by an unknown set of textual
   aliases.  Concurrent typestate has state-preserving observer transitions,
   but this pass found no typed account of an idempotent terminal destructor
   whose successful CAS discharges arbitrary aliases.  Future declarations
   should separately represent terminalisation, arbitration winner, and
   permitted post-terminal observation.  An observer self-loop must not
   re-acquire the discharged resource.

   - Gerbo and Padovani, *Concurrent Typestate-Oriented Programming in Java*,
     `full` for the relevant sections, [DOI](https://doi.org/10.4204/EPTCS.291.3).
   - **No answer found:** typed CAS-based discharge among an unknown holder
     set.  This is not a novelty claim.

4. **CONFIRMED — identity-based typestate is an alternative model, not a
   small patch to value threading.**  Obsidian demonstrates typestate, linear
   assets, and permission-based alias control, but its usability work reports
   substantial challenges.  No source located here gives per-annotation costs
   or a same-program comparison against value threading.  Retain stable
   runtime identity for diagnostics/ledgers while treating any in-place,
   identity-mutating capability type as a separate design requiring an alias
   policy and a corpus measurement.

   - Coblenz et al., *Obsidian*, `abstract`,
     [DOI](https://doi.org/10.1145/3417516); Coblenz et al., *Can Advanced Type
     Systems Be Usable?*, `abstract`, [DOI](https://doi.org/10.1145/3428200).
   - **Nonclaim:** the branch's E30 measurement (recorded in
     `PERTURB-DESIGN.md`, tally row 49), not either abstract, reports the 12/23
     rejection shape. It is not shown to disappear under permissions, nor is
     an annotation-cost estimate established.

5. **CONFIRMED — destinations must retain their source-state pairing.**
   Mungo-style declarations spell source-dependent transitions by declaring
   methods under each source state, and distinguish finite result-labelled
   choice from ordinary state transitions.  Perturb's surface can already
   spell repeated `:from`/`:to` edges, but a `[capability operation]` key loses
   that pairing.  Preserve finite edges keyed at least by capability,
    operation, and source; represent result choice separately.  Do not add an
    arbitrary destination-expression language or treat a declared discriminator
    as evidence it is honest.

   - Trindade, Mota, and Ravara, *Typestates to Automata and back: a tool*,
     `full` for the declaration syntax, [DOI](https://doi.org/10.4204/EPTCS.324.4).

6. **CONFIRMED, bounded — the Priority-1 searches found no qualifying
   four-way system or typed generational capability table.**  The four-way
   inclusion criterion required all of: external protocol declarations; a
   static pass over an existing dynamic host's IR/AST; substructural resource
   typestate; and a fail-closed monitor mechanically derived from the same
   declarations.  The table criterion required dynamically inserted,
   runtime-selected generational occupants whose *individual* typestate remains
   static.  Closest near misses were Scribble/Python monitoring, Dynamic Region
   Ownership, region ownership, and ordinary generational maps.  None met the
   conjunction in the surveyed corpus.

   - Sources queried: Crossref, OpenAlex full-text index, arXiv, official
     publisher/DOI pages, and official crate documentation.  DBLP API attempts
     returned HTTP 500 and were excluded.
   - Query families: `"dynamic language" AND (protocol OR "session type") AND
     ("static analysis" OR "compiler IR" OR AST) AND (linear OR affine OR
     substructural OR typestate) AND (monitor OR contract OR "runtime
     verification")`; `"external protocol declaration" AND "dynamic language"
     AND monitor`; `"generational index" AND (linear OR affine OR typestate OR
     session)`; `("slot map" OR slotmap OR "generational arena") AND typestate`;
     and the dependent-map/world-indexed-heap/existential/indexed-monad family
     from the brief.  Search date: 2026-08-04.
   - **Nonclaim:** this is evidence of absence only within the explicit search
     bounds; it establishes neither novelty nor impossibility.

7. **CONFIRMED — narrow the monitored arm rather than assume harmless
   alarms.**  Ahead-of-time feasibility analysis can suppress impossible
   runtime-monitor reports, but it does not supply a production base rate for
   effect-boundary monitors.  Generate monitor transitions from the declared
   relation where possible; classify missing facts as `inconclusive`; retain
   coverage and a violating positive control alongside alarm counts.

   - Bodden, Lam, and Hendren, *Finding Programming Errors Earlier by
     Evaluating Runtime Monitors Ahead-of-Time*, `abstract/metadata`,
     [DOI](https://doi.org/10.1145/1453101.1453109).  Its reported 13-spec,
     75%-average reduction is not a production protocol-alarm rate.

8. **CONFIRMED — preserve `NeedMore(original-cursor)` as an explicit parser
   contract.**  The surveyed verified-parser work supports parser/format
   correspondence but did not establish the combined chunked-input theorem:
   exact original-cursor rollback on insufficient input, a distinct invalid
   result, and composition through a streaming driver.  Keep this as a direct
   executable obligation rather than claiming it follows from verified parsing.

   - Swamy et al., *Hardening Attack Surfaces with Formally Proven Binary
     Format Parsers* (EverParse), `abstract`,
     [DOI](https://doi.org/10.1145/3519939.3523708); Cai et al., *Vest*,
     `abstract`, [USENIX Security 2025 PDF](https://www.usenix.org/system/files/usenixsecurity25-cai-yi.pdf).

9. **QUALIFIED — Yarrow corrects the existing citation, not the region
   decision.**  It addresses lexically scoped, stack-disciplined regions under
   one- and multi-shot effects.  It does not imply that domination-induced
   heap regions are unsound, and it supplies neither Perturb declarations nor
   boundary monitoring.  Its useful lesson is to make layer ownership and
   terminal outcome explicit protocol state.

   - Mathiasen, Timany, and Birkedal, *Yarrow*, `skim` (full PDF retained),
     [arXiv:2607.15876](https://arxiv.org/abs/2607.15876).

## Measurement-publication protocol

For the rejection taxonomy, freeze corpus inclusion/exclusion before running
the checker; publish corpus provenance; classify each eligible program once by
primary cause (with secondary labels retained); and use an independent oracle
to separate intended rejections, unsupported safe idioms, declaration defects,
checker defects, and unresolved cases.  Report every denominator: eligible
programs, programs reaching the construct, and all rejections.  Benchmarks such
as If-T provide paired expected outcomes, not representative-program frequency.

## Redistributable-paper register

The following newly retained artifacts are licensed for redistribution and have
an adjacent, page-delimited text conversion.  The conversion was generated with
`pypdf 6.14.2`; equations and layout require the PDF.

| Work | local artifacts | redistribution basis | source |
| --- | --- | --- | --- |
| Ahman & Bauer, *Runners in Action* (2020) | [`runners-in-action.pdf`](papers/runners-in-action.pdf), [`runners-in-action.md`](papers/runners-in-action.md) | CC-BY-4.0 in Crossref metadata for DOI 10.1007/978-3-030-44914-8_2 | [Crossref](https://api.crossref.org/works/10.1007/978-3-030-44914-8_2); retained PDF is the corresponding arXiv author version, [1910.11629](https://arxiv.org/abs/1910.11629) |
| Mathiasen, Timany & Birkedal, *Yarrow* (2026) | [`yarrow-effects-regions.pdf`](papers/yarrow-effects-regions.pdf), [`yarrow-effects-regions.md`](papers/yarrow-effects-regions.md) | CC-BY-4.0 displayed on the arXiv record | [arXiv:2607.15876](https://arxiv.org/abs/2607.15876) |

Other works are linked above but not copied here when this pass lacked both an
authoritative redistributable source *and* a successfully retrievable original
PDF.  A public abstract, publisher access, or an arXiv nonexclusive-distribution
grant alone was not treated as permission to commit a copy.

## Three places we are most likely still wrong

1. The four-way/table absence result may be overturned by work outside the
   queried indexes, especially capability-machine or dependently typed systems
   described with different terminology.
2. “Forwarding” may be too narrow: a useful layer can interpret locally rather
   than forward, so B6 must state exactly which operation classes require
   correlation.
3. Finalisation/cancellation may require a semantics stronger than a terminal
   trace latch; no result above proves the current adapter can safely supply it.

## Evidence boundary

This report neither proves the Perturb design sound nor authorizes a checker,
runtime, or Jolt change.  It does not measure adoption cost, false-rejection
frequency beyond the existing corpus, monitor overhead, or parser performance.
The proposed B6 invariant is a testable design hypothesis, not a theorem.
