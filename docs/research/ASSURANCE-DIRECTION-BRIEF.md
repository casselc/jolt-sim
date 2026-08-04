# Brief: the assurance-workbench direction, and what would decide it

**Status: a reading and a work queue, not a decision.** Written after reading
`PROGRESSIVE-ASSURANCE-ARCHITECTURE.md`, `CELLULAR-UI-APPLICATION-SKETCHES.md`
and `TEMPORAL-LEDGER-AND-STREAM-SYSTEM-SKETCHES.md` in full, and **before** the
three independent reviews of them return. Amend on their arrival; where this
brief and a review disagree, the review was written against the same documents
with fresh eyes and should be believed first.

---

## What the three documents are

**One proposal in three parts.** PROGRESSIVE-ASSURANCE is the method,
CELLULAR-UI is the application shape, TEMPORAL-LEDGER is the data substrate. The
through-line is that perturb stops being a *checker* and becomes an *assurance
workbench*: ordinary code carrying declared boundaries, with evidence flowing
static → bounded → generated → controlled → runtime, and every layer either
discharging an obligation or exporting it.

**This is a direction change, and the documents half-say so.**
PROGRESSIVE-ASSURANCE recommends strategy **B** (production-first shadow over
unchanged code) as the default — which is `jolt-sim`'s `run-controlled`, not
anything perturb has — and then adds that perturb's "language-feature direction
must not be reduced to an interception adapter". Its *Practical first slice* is
therefore neighbour-lane work. That should be stated plainly before anyone
schedules it, because it is the part that would actually get built.

---

## Adopt regardless of which direction wins

These three items do not depend on becoming a workbench.

### 1. Residual routing — the best idea in the three documents, and half-built four times

> Every assurance layer either discharges an obligation in its stated model or
> **exports the residual** — with its state, assumptions, generators,
> observables, and negative controls — to the next layer.

perturb already returns "I will not say" as a first-class result in **four
independently built places**: `perturb.share`'s `:refused` (E38 — refused is
*ignorance*, never an accept), `perturb.refine`'s `unknown`, arm C's `unknown`,
and B6's `:inconclusive`. **Nothing collects them and nothing routes them
anywhere.** The document names the pattern; the mechanisms exist; the cost is
close to zero.

The discipline that makes it more than bookkeeping is stated in the source and
should be kept verbatim: *a residual does not become "handled" merely because it
has a test — the test must name the residual's domain and evidence strength.*

### 2. `simulated` and `runtime` are an observation *source*, not a lattice position

The charter's lattice is one ordered scale. Tally row 58 already records that it
conflates **strength** with **scope**. These documents add a third, orthogonal
axis: **source** — modelled behaviour versus real native calls on a stated
target. `simulated` is not above or below `monitored`; it says where the
observation came from.

### 3. Evidence as data — this is now five documents

Adoption audit §B2, the structural reframe's manifest sketch, and all three of
these. Every result carries subject revision, schema version, evidence label,
assumptions and nonclaims as **machine-readable data**. It should stop being a
proposal and become a decision.

---

## The question that actually decides the direction

**Is perturb a checker of existing code, or the assurance layer of a new
application framework?** The charter says the former. These documents describe
the latter, and are honest that the two share artifacts rather than goals.

Nothing in the record decides it, and it should not be decided by drift.

---

## The argument for cells that the documents do not make

The cellular model runs straight into what E38 measured. Cells return effect
requests as data, the graph routes labels, and authority is a runner-issued
non-serializable token — and until that mechanism exists the documents concede
that resource-bearing cells are `:opaque`. In perturb's terms every such cell is
`mixed` with an **undeclared consumer contract**: exactly the 3-of-7 `refused`
case, where §4.6's higher-order gap is the sole blocker.

So the cell boundary does **not** dissolve the higher-order problem. It
*relocates* it. And that relocation is the strongest argument for the whole
direction:

> **You pay the higher-order notation cost once, at one runner, instead of at
> every callee.**

Three undeclared `thrown-by` thunks become one declared graph runner. Nobody in
the three documents makes this argument, and it is the one that would justify
the direction if it holds. **It is also testable** — see item 2 below.

---

## Work queue

Ordered by what each would decide, not by size.

### 1. Does perturb's one `bounded-complete` verdict survive these documents' own definition?

All three define it strictly: a decidable property checked for **every** member
of an explicitly finite domain, with harness evidence of **expected cardinality,
equality-confirmed uniqueness, and full consumption**. E4's decode-trichotomy
verdict is perturb's only claim at that level, and it predates the definition.

Check it. If it survives, the strict definition is affordable and should be
adopted. **If it does not, perturb holds zero verdicts above `sampled`**, and
that is a finding about our own record that changes how every future claim is
worded. Cheapest decisive item on the list.

### 2. Test the relocation argument on the code we already have

Take E38's three `refused` captures. Declare a **single** consumer contract for
`thrown-by`-shaped higher-order retention — the minimal notation §4.6 says does
not exist — and see whether one declaration decides all three.

If it does, the cell argument holds in miniature and the direction has evidence
behind it. If one declaration cannot cover three call sites of the *same*
function, the relocation argument is weaker than it looks and the direction
should be discounted accordingly. Either result is worth more than another
survey.

### 3. Route the residuals

Collect the four existing residual kinds into one ledger with the fields the
document specifies: domain, assumptions, generators, observables, negative
controls, evidence strength **and scope and source**. Report what fraction of
perturb's current refusals have a named next layer. The honest expected answer
is *most have none* — which is the point of measuring it.

### 4. Only then, the framework question

If items 1–3 land well, the cellular direction deserves a real prototype, and
the sequencing question is which application goes first.

**Name the E3 risk before choosing.** `bbf1` is nominated first for
deterministic inputs and independent oracles — the correct criterion, and the
same one §5 used for jolt-tcp. But it is pure reduction over canonical events,
and its listed domains (load, stale-result, frame-tag, seek-plan) are shape and
ordering. `a1s` is the one carrying genuine application semantics — authority,
confirmation, staleness, operator intent — and it is scheduled second.
Concluding something general from `bbf1` alone would repeat Appendix A row 18
exactly.

---

## Constraints on whatever gets built

- **The workbench sits outside its own discipline.** `datafy`/`nav` over
  evidence is higher-order and dynamically selected — the E24 shapes perturb
  cannot express. It is test-only, so this is not fatal, but the verification
  surface would be the unverified part, and that should be written down rather
  than discovered later.
- **Strategy B is not available to perturb.** Its boundary is voluntary: E29
  finding 4 and E35 both record that call-over-call completes with correct
  octets and nothing refuses it. An interception default assumes a boundary
  perturb does not have.
- **A declaration is still a claim.** The documents say this well and repeatedly
  — a declared deterministic dispatch predicate, a declared effect-confined
  cell, a declared atomic transition. Each needs the audit or monitor coverage
  that makes it evidence. This is the same rule as E34's nonclaim 3 and it
  should not be restated as though it were new.
- **Do not let a green run imply an omitted axis.** Stated in the source; it is
  the box-of-pain discipline and it is right.
