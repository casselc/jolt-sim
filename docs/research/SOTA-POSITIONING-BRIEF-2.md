# Brief 2: the questions round 1 left open, and the ones it created

Round 1 (`SOTA-SURVEY-ROUND1.md`, recorded as E31) killed the broad novelty
story and did it well. **Read it first — this brief assumes it.** Do not re-ask
its questions; it answered them. In particular, treat as settled: the general
linear-handler gap is closed; handler composition and outward forwarding are
standard; external specification files and pluggable checking are mature;
typestate/protocol work over dynamic languages exists; session systems support
unbounded participant sets; and "no resumption" was our misclassification of
implicit tail resumption.

**Same rules as round 1.** Primary sources for technical claims. Say per item
whether it was read in full, skimmed, or known only from an abstract — round 1
did this and it was the most useful thing about it. Negative findings mean "not
found in this survey". Licences: for PACMPL the CC-BY grant lives in Crossref
metadata (`api.crossref.org`, `license` field), not always in the page
furniture; commit only what is redistributable, record the basis per item, and
convert to markdown alongside the PDF so it can be grepped.

**Output format.** Findings in `PERTURB-DESIGN.md` §3 style: confirmed / refuted
/ no answer found, ordered by **how much each changes what we would do next**.
End with the three places you think we are most likely still wrong.

---

## Priority 1 — the two absence findings round 1 refused to certify

Round 1 flagged both as needing "a publication-quality systematic search with
explicit inclusion criteria" before novelty is claimed. That search is this
brief's first job. **State your inclusion criteria and the databases and query
strings you used**, so the negative result is reproducible rather than
anecdotal.

1. **The four-way conjunction.** Is there any system combining: external
   protocol declarations; a static pass over an existing *dynamically typed*
   language's compiler IR; substructural typestate for resources; and
   coordinated fail-closed boundary monitoring derived from the same
   declarations? **Search adversarially — try to find one**, rather than
   confirming absence. Look beyond PL venues: security/sandboxing, RV, systems.
2. **Typed generational capability tables.** Round 1 found no peer-reviewed
   system where an ordinary generational index or slot map preserves *static*
   linear typestate per dynamically inserted occupant, and named the search
   expansions itself: **dependent maps, world-indexed heaps, ST-style
   encapsulation, capability machines, existential packages**. Also try:
   indexed/parameterised monads over a heap, `Data.Type.Map`-style typed
   heterogeneous maps, Idris/Agda resource-indexed state, ATS linear views,
   session-typed process registries, and the Rust `slotmap`/`generational-arena`
   ecosystem's type-level work.

---

## Priority 2 — questions E30 created, which round 1 never saw

These come from checking a real library (`jolt-tcp`) with a real test suite and
measuring *why* the checker rejected it. They are the highest-value new
questions because they are grounded in a measurement rather than a hunch.

3. **Absorbing terminal states.** Our `:linearity :once` conflates two
   obligations that a real resource separates: *close happens exactly once* (a
   discharge obligation) and *the binding may not be mentioned afterwards* (a
   dead-name permission). The library satisfies the first more strongly than
   linearity does — a compare-and-set close means exactly one close **at run
   time however many times the text says it**, with the boolean return acting as
   a **race arbiter** — while pure observers legal in the terminal state
   (`closed?`, `connection-info`) are rejected because the *name* is dead.

   What we think is missing is a terminal state that admits its own destructor
   and its observers as **self-loops**. Questions: does this exist under any
   name? How do linear/affine systems handle *observers on a consumed resource*
   — Rust's `Drop`/`ManuallyDrop`, `&`-borrows of a moved-from value, Vault's
   `free`/`tracked` keys, Plural/Plaid's `unique`/`immutable`/`pure`
   permissions, ATS views? Is "idempotent destructor" treated anywhere as a
   typed notion rather than a library convention? And is there a system where a
   **CAS among an unknown set of holders** is what discharges a linear
   obligation — which is what this library actually does?

4. **In-place typestate over a mutable handle.** Our `:consumes`/`:produces` is
   *value threading*; the resource is a **stable name over a mutable atom**, and
   this accounted for **12 of 23** substantive rejections. Typestate for objects
   with identity is the classic Vault/Fugue/Plural/Plaid setting, so the
   suspicion is that we picked the wrong formalism and have been paying for it.
   Questions: how do identity-based typestate systems handle transitions that
   mutate in place and return something unrelated (a byte count, a boolean)?
   What did they need — alias control, fractional permissions, unique/shared
   splitting? What is the measured annotation cost of that machinery, and is
   there a published comparison of value-threading against identity-based
   typestate for the same programs?

5. **State-dependent destinations.** Our declaration language requires the
   transition relation to be a function of the *operation alone*, never of
   `(operation, source)`. That is obviously wrong as automata theory, so the
   question is what typestate **declaration languages** actually do: how do
   Vault, Plural, Fugue, JATYC, Mungo/StMungo, Typestate-Oriented Programming
   and the Checker Framework's typestate work spell an operation whose
   destination depends on its source? We want the surface syntax, not the
   theory.

6. **Our rejection-cause taxonomy — how should it be published?** Round 1 says
   these measurements are original and no comparable taxonomy exists, and that
   they should carry denominators, corpus-selection rules and a mutually
   exclusive taxonomy. Find the closest methodological models — empirical
   studies of type-system/checker rejections, `unsafe`-Rust studies, gradual
   typing adoption studies, false-positive studies for static analysers — and
   report **what denominators and selection rules they publish**, so we can
   match the strongest of them. Venue guidance welcome.

---

## Priority 3 — verify the reframing round 1 recommended

Round 1's most useful move was to say the nearest formal family for our effect
boundary is **runners**, not handlers. That is a recommendation, not a result,
and it is load-bearing enough to check.

7. **Runners in Action (Ahman & Bauer, ESOP 2020), in full.** Is our boundary
   literally a runner? Specifically: what does the runner calculus *require*
   that we do not have — co-operations, a kernel/user distinction, the runner
   monad, finalisation blocks, signals versus exceptions? What does it give that
   we would want (finalisation is the obvious one, given our cancellation work)?
   And is there follow-on work with an implementation, especially anything with
   linear or stateful resources?
8. **Tang et al.'s deep-handler restriction.** Round 1 notes that Tang's
   deep-handler rules "restrict capture of linear values". We have just built a
   two-rung stack where a handler satisfies an operation by **performing the
   same operation** outward. Does control-flow linearity permit that, forbid it,
   or not address it? This is the one question whose answer could invalidate an
   experiment we have already run.
9. **The narrow B6 obligation, stated formally.** Round 1 could not find
   same-effect forwarding through protocol layers preserving typestate-linear
   obligations, and recommended we state the invariant and build a known-good
   and known-bad composition. **What formal vocabulary should that invariant be
   stated in?** Scoped effects, effect rows with forwarding, runners composed by
   a monad morphism, or something else? We want the right frame before we write
   the invariant, not after.

---

## Priority 4 — things round 1 answered in a way that opens new work

10. **Occurrence typing as the fix for our discriminator.** Round 1 identified
    Typed Racket's occurrence typing as the direct precedent and our declared
    predicate as "a trusted proposition". Question: **what would adopting it
    cost over an untyped IR?** Occurrence typing lives inside a full type
    system with propositions in the type of the predicate. We have no types —
    only capability declarations. Is there a stripped-down account of
    proposition-carrying predicates without a surrounding type system? And how
    does Typed Racket itself handle a *trusted* predicate at the typed/untyped
    boundary — is the contract system what makes it honest, and at what cost?
11. **Dynamic Region Ownership (PLDI 2025), in full.** Round 1 calls it the
    closest retrofit of ownership into a dynamically typed language. What is the
    enforcement mechanism, what does it cost at run time, what does it refuse,
    and **is a static variant conceivable or explicitly ruled out**? Compare
    directly against our four measured failure shapes.
12. **Verified incremental parsing.** Our sans-io trichotomy needs
    `:need-more` to return the **exact original cursor**, and round 1 found no
    formal account combining chunked input, `consumed | need-more | invalid`,
    protocol typestate and that rollback. Narrow the gap rather than restating
    it: do EverParse/LowParse, Vest or Narcissus support incremental or resumable
    input at all? What does the streaming-parser verification literature (not
    incremental *document* parsing) actually cover? Is exact-cursor rollback a
    special case of a known transactional/backtracking parser result?
13. **Reducing monitor false alarms.** Legunsen et al.'s 82.81% false-alarm rate
    for handwritten specifications is the strongest argument against our
    monitored arm. What is known about **reducing** it — specification
    context-sensitivity, per-callsite suppression, ranking and triage,
    provenance, or spec-mining quality? If the literature has no answer, that is
    a decision: it means the monitored arm should be scoped to obligations whose
    violations are unambiguous rather than to axioms generally.
14. **Yarrow in full.** `§1.2`'s decision to drop locality cites it, and E25
    corrected our reading of it once already. Round 1 reports it as a Rocq/Iris
    development covering regions, fibers, once/many effects and resources
    exchanged through operation protocols. **Read it and tell us whether our
    §1.2 citation is still accurate**, and what its "resources exchanged through
    operation protocols" would say about our layering experiment.

---

## What to bring back

- Findings in the format above, ordered by decision impact.
- For every "adopt X": the cost, the failure literature, and what X does **not**
  do. Round 1 was good about this; keep it.
- Redistributable papers with licence basis recorded, plus markdown conversions.
- **Reproducible search protocol for Priority 1** — criteria, sources, queries.
- The three places you think we are still most likely wrong.
