# Structural-tier research findings and revised framing

**Status:** research synthesis and proposed framing, not a design decision or
implementation authority.  It answers the research half of
`STRUCTURAL-TIER-BRIEF.md` and tests a revised framing against three additional
read-only reviews.  Sources are labelled by the research pass's access level;
negative findings are scoped search results, never nonexistence claims.

## The correction in one sentence

> **Structural assurance is a claim-indexed program for replayable value
> semantics—not the complement of capabilities and not a blanket proof tier.**

The original brief correctly notices that a tuple-only capability domain cannot
express collection structure.  Its framing is nevertheless too broad in three
ways:

1. A source-level value is unrestricted only under a declared *observational
   interface*: retaining, sharing, comparing, copying, or dropping it must not
   duplicate authority, change an observable protocol, or violate resource
   safety.  Immutable allocation alone is insufficient.
2. Persistent collection API laws and representation facts differ.  Extensional
   `conj`/`pop`/old-version laws do not establish trie layout, physical sharing,
   cache correctness, or retention bounds.
3. Structural domains are not inherently finite.  Only a named finite value and
   operation-history domain that is fully enumerated earns `bounded-complete`.
   Sampling, shrinking, and deterministic replay do not.

This gives three claim classes, each with a different tool:

| Claim class | Smallest adequate technique | Strongest honest verdict |
| --- | --- | --- |
| Extensional API law | independent reference/model differential test; finite enumeration where possible | `bounded-complete` over declared domain; otherwise `sampled` |
| Local index/arithmetic safety | explicit preconditions plus a checked QF-LIA/bounded obligation | only `proved` if the semantics, VC generator, and implementation link are named; otherwise bounded or monitored |
| Representation invariant | selected small-kernel proof plus correspondence argument | `proved` only for that kernel and link |

Values with reachable mutation, resource ownership, finalization, affinity,
callbacks/continuations, deferred effects, clocks/entropy, or external authority
are **mixed/authority-bearing** values.  They need boundary-specific analysis;
they do not become structural merely because a payload is immutable.  In
particular, `StreamConn` is mixed, while an octet payload may be a value-semantic
target.

## Research findings

### 1. The unrestricted fragment is an interface property

**Confirmed.** Linear Haskell attaches multiplicity to binders/arrows, allowing
ordinary unrestricted values to pass through linear code; it does not split all
types permanently into linear and nonlinear copies.  Granule uses grades/modal
introduction to license reuse.  Alms distinguishes unlimited from affine
interfaces, and ATS separates ordinary types from linear views.  Rust supplies
the important qualification: `Copy` excludes `Drop`, and persistent-library
implementations can use reference counting/copy-on-write internally.

The useful classifier is therefore not “allocated” or “immutable,” but whether
duplication/discard at the declared interface is semantically harmless.  An
otherwise persistent collection carrying a resource element is mixed; a hidden
cache must remain observationally irrelevant or become a separate invariant.

- Bernardy et al., *Linear Haskell*, `skim`, [DOI](https://doi.org/10.1145/3158093),
  retained under CC-BY at
  [`papers/POPL20-linear-haskell.pdf`](papers/POPL20-linear-haskell.pdf) with
  [conversion](papers/markdown/POPL20-linear-haskell.md).
- Orchard, Liepelt, Eades, *Quantitative Program Reasoning with Graded Modal
  Types*, `skim`, retained
  [`papers/ICFP19-quantitative-program-reasoning-with-graded-modal-types.pdf`](papers/ICFP19-quantitative-program-reasoning-with-graded-modal-types.pdf).
- **No answer found:** no primary empirical study was located that ran a
  substructural/typestate checker on persistent immutable collections and
  concluded it was useless solely because nothing needed restriction.  This is
  bounded to the recorded source and terminology search.

### 2. Real structural verification exists, but not this vector/HAMT target

**Confirmed, bounded.** Coq/CFML work verifies sharing/copy-on-write behavior
for concrete snapshotable trees and a transient stack; `hs-to-coq` verifies
properties over translations of portions of unmodified Haskell `containers`.
Those results demonstrate techniques, not end-to-end correctness of a Jolt or
Clojure 32-way vector trie/HAMT.  No such direct proof target was located in the
survey.

Consequently, “persistent collections are solved” is not a premise for this
branch.  The credible sequence is extensional behavior first, representation
invariants only when an API-relevant defect cannot be exposed by the external
oracle, and then a deliberately small correspondence claim.

- Mehnert et al., *Formalized Verification of Snapshotable Trees*, `abstract`.
- Moine, Charguéraud, Pottier, *Specification and Verification of a Transient
  Stack*, `abstract/artifact metadata`.
- Breitner et al., *Ready, Set, Verify!*, `abstract/artifact README`; its bridge
  back to executable GHC was reported not working.  No redistribution licence
  was established for these copies in this pass.

### 3. Index safety is a local analysis project, not automatic proof

**Confirmed.** Flux reports selected benchmark costs (vector-bounds: 487 Rust
LOC, 29 specification LOC, 2.63 seconds across seven benchmarks; WaVe: 5,585
LOC, 318 specification LOC, 16 seconds), but no independently adjudicated
false-positive/false-reject rate.  Liquid Resource Types reports 12 resource
benchmarks and timings, not a Jolt-like index-safety corpus or rejection rate.
The Checker Framework Index Checker is the closest operational design baseline,
but the examined material did not supply annotation, time, or false-reject
denominators.

Use a small range analysis over explicit Jolt/Perturb IR sites (`0 <= i < n`,
known lengths, simple same-length facts) before importing a Rust/Horn ecosystem.
Use QF-LIA only where local propagation cannot decide the obligation.  The
current `perturb.refine` is not an SMT solver: its sound-but-incomplete syntactic
normalizer may refuse unknown results, so it does not by itself make a `proved`
claim.

- Lehmann et al., *Flux*, `substantial technical skim`, retained
  [`papers/PLDI23-flux-liquid-types-for-rust.pdf`](papers/PLDI23-flux-liquid-types-for-rust.pdf).
- Knoth et al., *Liquid Resource Types*, `substantial technical skim`, retained
  [`papers/ICFP20-liquid-resource-types.pdf`](papers/ICFP20-liquid-resource-types.pdf).
- Santino, *Checker Framework Index Checker*, `abstract/metadata and current
  manual`; version-of-record redistribution was not established.

### 4. The evidence rule is exact

**Confirmed.** A finite exhaustive run earns `bounded-complete` only when: the
domain is explicitly finite; the harness establishes complete enumeration
(including cardinality/uniqueness/full-consumption evidence); and the property
is decidable for each member.  Depth-bounded enumeration earns the verdict only
over that depth-bounded fragment.  Hegel/property-based generation, coverage
guidance, and shrinking remain `sampled`; replay establishes reproducibility of
one witness, not exploration.

`jolt-sim` has useful parts of this discipline—canonical logical values, exact
replay, finite plan generation, Hegel shrinking, tri-state monitors, and fresh
process containment—but not a generic finite-domain runner or first-class
exploration/evidence manifest.  Do not use a hash as state identity for
deduplication without canonical equality confirmation.

### 5. Hash conformance is differential testing, not hash-quality testing

**Confirmed.** The checkable Jolt/Clojure claim is per surface and per value
shape: compare `hasheq` and `.hashCode` independently against the JVM reference;
ordered and unordered collections have distinct composition rules.  SMHasher
measures hash quality, not agreement between implementations.

Use three explicit verdicts:

1. finite-domain JVM agreement—`bounded-complete` over the declared scalar or
   bounded-composite domain;
2. generated nested-value agreement—`sampled`, with shrink/replay; and
3. within-implementation equality/hash consistency—separate invariant, bounded
   or sampled according to its domain.

Mutants must flip a Murmur constant/rotation, omit a zero special case, or alter
the ordered combination, then shrink to a divergent value.  Agreement does not
prove cross-platform universality, algorithm equivalence, collision resistance,
iteration order, or concurrency/cache safety.

## Revised executable work queue

Every row starts with a target card containing subject revision, interface,
independent oracle, value profile, operation alphabet, finite bound where used,
negative control, assumptions, and separate evidence labels.

1. **Octet interface contract (first spike).**  Scope only constructor-originated
   octets and `{ocount, oref, osub, odrop, oconcat, decode-utf8}`.  Current
   `octets?` is a tag recognizer: it accepts a forged tagged map, while the
   constructors range-check inputs.  Either make construction opaque/harden the
   recognizer or expressly exclude forged maps.  Differentially check a pure
   sequence model; exhaust a small alphabet, maximum length, and operation
   history.  `StreamConn` and wire/native behavior are excluded.
2. **Persistent-vector extensional card.**  Against a pinned Clojure reference,
   exhaust bounded sequences of `conj`, `assoc`, `pop`, `nth`, and observations
   of all retained ancestor versions.  A wrong count, source mutation, or bad
   `pop` mutant must fail.  Call the result bounded-complete only for the exact
   alphabet/history bound.
3. **Local index slice.**  For one named raw `nth`/`subvec` path, state source
   preconditions and a safety property.  Controls include a buggy `i <= n`
   condition with `i = n`, a corrected guard, and a satisfiable valid-read
   non-vacuity witness.  Do not label the result proved until a named semantic
   model, VC generator, and implementation link exist.
4. **Hash conformance card.**  Record the JVM reference revision and canonical
   value profile; run finite scalar/bounded aggregate axes separately from
   Hegel-generated composite cases; pin both hash surfaces and target matrix.
5. **Representation-kernel decision.**  Add no induction because the tier has
   “structural” in its name.  First identify a required internal fact testing
   cannot expose (such as a sharing/space or trie invariant).  Only then select
   a small abstract node model, preservation proof, and correspondence boundary.

The semantic analyses remain separate from capability analysis, while sharing
IR capture, diagnostics, test gates, canonical witness transport, and corpus
infrastructure is desirable.  That rejects the false choice between extending
`check.clj` and building a disconnected second language tool.

## Evidence manifest sketch

Keep this test-side and additive; it does not alter production collection APIs.

```clojure
{:jolt.sim.evidence/version 1
 :id :collections/vector-laws-v1
 :claim {:property :persistent-vector-laws :verdict :bounded-complete}
 :subject {:revision "..." :jolt-baseline "..." :chez "10.4.1"}
 :oracle {:kind :differential-pure :implementation :jolt
          :reference :clojure :profile-version 1}
 :domain {:kind :finite-exhaustive :values {:elements [-1 0 1] :max-count 5}
          :operations [:conj :assoc :pop :nth] :max-history-length 6
          :expected-cases "..." :generated-cases "..." :duplicates 0}
 :replay {:input-schema 1 :history-schema 1 :exact? true}
 :results {:passed "..." :violations 0 :inconclusive 0}
 :assumptions #{:pure-oracle :declared-value-profile}
 :nonclaims #{:unbounded-inputs :physical-sharing :allocation-shape
              :cross-target-universality}}
```

For Hegel, use `:verdict :sampled` and replace `:domain` with engine revision,
seed policy, case count, generator/shrink structure, observed coverage, and
`:distribution :unspecified`.  Keep canonical scenario input/output/history
separate from PID, deadline, signals, reaping, and logs.

## Evidence boundary

No implementation or verification gate was run for this synthesis.  The revised
frame can support bounded extensional conformance, local arithmetic obligations,
and selectively proved representation kernels.  It does not establish general
algorithmic correctness, physical sharing, resource safety, temporal liveness,
or a universal structural type checker.
