# Brief: the structural/inductive tier, which has never been built

**Status: a diagnosis and a work queue, not a decision.** Nothing in
`PERTURB-DESIGN.md` authorises the design choices below. It is written because
the charter names **two** tiers and only one exists, and because the absence of
the second is the reason every measurement this project has taken lands on
protocols.

---

## The diagnosis

Three facts that are individually recorded and have never been put together.

**1. The abstract domain has exactly one composite.** `check.clj`'s domain models
a **tuple** and nothing else. A map, a set, or a vector that grows is an *escape*
— the checker cannot follow a value into one. §4.6's root cause ("a capability
may only live in a binding of statically known shape") is therefore not a fact
about protocols at all. **It is a statement about the domain**, and every
protocol failure measured in E23, E24, E29 and E30 routes back through it.

**2. Only the capability tier has tooling.** §1.2's four axes, `check.clj`, the
gates, the corpora, the ledger and the effect boundary are all capability-tier
machinery. The structural/inductive tier has been "open and undesigned" since §5
was first written, and ladder step 4 (persistent collections) has never been
attempted. Work goes where the tools are; the tools are all on one side.

**3. Every axis §1.2 has is a restriction.** Uniqueness, linearity, typestate,
contention. The majority case in Clojure is a persistent value that is **freely
duplicable, and that is the point**. perturb has no account of it beyond §1.6's
core/`clojure.*` layering rule, which says where unchecked code lives rather than
why duplicable values need no discipline. **We have a vocabulary for constrained
things and no story for the unconstrained majority.**

A fourth observation follows from the evidence lattice. Every capability verdict
this project holds is `sampled`, `monitored` or `assumed`. Nothing is `proved` or
`bounded-complete`, because protocol obligations are open-ended without a
semantic model perturb does not have. **Structural properties are finite in the
way the neighbouring lanes' domains are finite** — which is why they are where
verdicts at the strong end of the lattice are actually attainable.

---

## Measured leads that have gone unused

Each of these is already in the record with a number attached, and none has been
followed up.

- **E10:** `nth` on a deftype costs ~2,061 ns/byte against ~86 for a persistent
  vector — roughly **24×** — and the gap survived three rounds of fixes.
- **E11:** a byte array is **8 bytes per element**, the same as a long array, and
  `(aget b i)` lowers to `(jolt-nth b i)`. It is not a primitive array read.
  §1.1's whole performance line had assumed otherwise.
- **E13:** abstract refinements over a window are predicate variables that HM
  unification cannot solve — the one place refinements have been taken seriously,
  and it was about a *data structure*, not a protocol.
- **E14:** `host/chez/hasheq.ss` is a JVM-exact Murmur3 port, the CHANGELOG
  specifies vector/map/set hashes as "value-identical to the JVM", and
  `.hashCode` and `hasheq` are two separately-specified surfaces. An observable
  data-structure property, with an oracle, and a cross-platform risk. It sits in
  the divergence register with nothing built on it.
- **E3:** the sample that produced "no obligation is about application semantics"
  was codecs — which have no application semantics — so the finding was close to
  tautological. The structural tier is where a *non*-tautological version of that
  question can be asked.

---

## The build half, in order

### 1. Byte-window representation — and it is already on someone's critical path

Round 2 of the SOTA survey asked for a parser benchmark before changing the
sans-io contract: one-byte chunk delivery, maximum legal frame size,
repeated-prefix CPU time, **retained-buffer high-water mark**, and
error-offset/replay behaviour. **Every one of those is a byte-sequence
representation measurement, not a parser measurement.**

The `:need-more` rule — return the **exact original cursor** — is a claim about
what a window is and what slicing costs. E11 says the underlying array is 8
bytes per element and indexed through a generic path. Nobody has measured what
that costs a real parse, or what a window actually retains.

Produce: a benchmark with a disabled-instrument control, reporting ns/octet and
retained bytes at the high-water mark, under adversarial chunking. That is the
input the parser decision needs, and it is a structural-tier artifact.

### 2. Index safety as the first `proved` verdict

`count` arithmetic, `subvec` bounds, index safety on every array access — these
are decidable and SMT-friendly, unlike anything in the capability tier. Flux
(PLDI 2023) and Liquid Resource Types (ICFP 2020) are both in `papers/`, and the
refinement machinery built for the Content-Length obligation (`:init` /
`:update` / `:requires`, ghost state, `refinement-undischarged`) already exists.

If this works it produces the **first verdict at the strong end of the lattice**
this project has ever had. If it does not, the reason is worth more than another
protocol finding.

### 3. Ladder step 4 — persistent collections

The one target with **no temporal content whatsoever**. Jolt has the
implementation; Clojure is the oracle; the properties are inductive: `conj`
increments count, `pop` decrements, a persistent update does not mutate its
source, and the trie invariants (branching factor, tail offset) hold after every
operation.

It is also the honest test of whether `check.clj` is a *protocol* tool or a
*language* tool. If the structural tier needs a separate checker, better to find
out on a target with an oracle than on a transport.

### 4. A metric that is not gameable

E18 finding 4 established that operation-counting is: `perturb.http` drove
`:perturb.cap/representation` to zero while unchecked concrete-map accesses went
12 → 31. The structural analogue is better behaved — **index-safety obligations
discharged versus assumed, counted per array access**. Inlining a field read does
not launder it, and the denominator is mechanically countable.

Name the refactoring that games it before adopting it. E20 records that no metric
in the literature survives adversarial refactoring, and the one shipped tool
(`cargo-geiger`) has been known-broken since 2019.

### 5. `hasheq` conformance

An observable property with a real oracle (the JVM), a real specification (the
CHANGELOG's "value-identical"), two separately-specified surfaces, and a real
cross-platform risk. The neighbouring lanes have the exploration machinery.
Nothing has been built on it since E14 recorded it.

---

## The research half

For an agent with real internet access. Same rules as the SOTA briefs: primary
sources; per-item full/skim/abstract labelling; Crossref `license` on the
**version of record** for redistribution; negative findings mean "not found under
the stated protocol".

1. **Why is the unconstrained majority safe?** Substructural systems all describe
   restrictions. Is there a principled account of the *complement* — why freely
   duplicable persistent values need no discipline, and exactly what makes a
   value belong to that class? Look at how Linear Haskell, Granule, Alms, ATS and
   Rust draw the boundary between the tracked and untracked fragments, and what
   they say about the untracked side.
2. **Verified persistent data structures.** What is the state of the art for
   verifying structural sharing, trie invariants and persistence itself? Where
   has it been done over a *real* implementation rather than a model?
3. **Refinement types for index safety, at our scale.** Flux and LRT are in
   hand. What does index-safety refinement cost in annotation burden and solver
   time on real code, and what is the published false-reject rate? The
   Checker Framework's Index Checker is the obvious industrial comparison — what
   does it report?
4. **Property-based testing as an oracle for inductive properties.** Shrinking,
   coverage-guided generation, and the relationship between a property suite and
   a `bounded-complete` claim. jolt-sim's Hegel explorer is next door; what does
   the literature say about when exhaustive exploration of a finite domain earns
   a stronger verdict than sampling?
5. **Hash conformance across a language port.** Is there prior work on verifying
   that a reimplementation's hash function agrees with a reference across a value
   domain, and on what "value-identical" can be made to mean as a checkable
   property?
6. **The negative to look for.** Has anyone applied a substructural or typestate
   checker to persistent immutable collections and found it *useless* — because
   there is nothing to restrict? If so, that decides whether the structural tier
   should share machinery with the capability tier or be a separate analysis
   entirely. **This is the question most likely to change what we build.**

---

## Constraints on whatever gets written

- **The structural tier may not need capabilities at all.** If persistent
  collections have no obligations to track, the honest finding is that
  `check.clj` is a protocol tool and the structural tier is a different
  analysis. Do not force one mechanism onto both because one exists.
- **Every claim executed.** The standing rule: a gate that has never failed is
  not evidence, and each new rule needs a negative control that fires.
- **Do not repeat E3's sampling error.** Codecs have no application semantics, so
  finding none in them proved little. Choose the corpus before running the tool,
  and state the selection rule.
- **Say where each verdict sits on the lattice.** The point of this tier is that
  `proved` and `bounded-complete` are attainable here; that is exactly why an
  overstated verdict would do more damage than usual.
