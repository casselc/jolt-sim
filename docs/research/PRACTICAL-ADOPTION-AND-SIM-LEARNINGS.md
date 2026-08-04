# Practical Perturb adoption and jolt-sim learnings

**Status:** read-only design audit, not implementation authority.  Four
independent audits examined the branch at
`c7818cb56f5c971267ec80e0aade20335cf4f245`: two asked how Perturb can become
useful without a new PL result, and two looked for small, one-way improvements
to `jolt-sim`.  This record distinguishes source observations from proposals.
None of the proposals below has been executed; their current evidence is
`assumed` until its named controls run.

This supplements, rather than replaces, `SOTA-SURVEY-ROUND2.md`.  The important
filter is practical: choose a bounded boundary, declare its evidence level, and
do not try to make dynamic sharing, ordinary collections, or handler layers
statically proven in the first iteration.

## A. Practical Perturb path

### A1. Repair the declaration interpreter before enlarging the language

**Source facts.** `tcpcap.clj` already contains an intentional but unsupported
in-place `:produces … :arg 0` encoding; `check.clj`'s annotation-consistency
path is the relevant consumer.  Its current model cannot preserve the pairing
between an operation's source and destination states.  This is an interpreter
defect/limitation before it is a research problem.

**Proposal.** Keep value threading for exclusive values, but add one explicit
receiver-update transition for a stable mutable identity.  Make the transition
relation finite and keyed by at least:

```text
(capability, operation, source-state, result-label) ->
  destination-state + obligation delta
```

Represent `close!` with an `:open`/`true` transition that discharges the
obligation and a `:closed`/`false` self-loop.  Permit declared pure observers
in terminal states.  A result label is preferred to a generic predicate;
external Boolean/predicate honesty remains an explicit assumption or needs a
runtime cross-check.

**Known technique applied.** Identity-indexed object typestate and
result-labelled finite state machines; this is not a new alias/permission
system.

**First controls.** Accept first close, repeated close, `closed?`, and
`connection-info`; reject `send-all!` after close; reject a mismatched
source/result edge; retain a missing-close leak as a negative control.

**Nonclaims.** The static checker does not choose the winner of a concurrent
CAS and does not prove an external Boolean truthful.  For `:contention :atomic`,
winner evidence is monitored at the boundary.

### A2. Make one explicit table boundary, not arbitrary maps sound

**Source facts.** The event-server table path (`evt.clj`'s
`accept-into-table` through `serve-table-with-listener`) is rejected precisely
where ordinary maps store and retrieve runtime-selected connections.  The
fixed-register alternative is the currently checkable shape.

**Proposal.** Add one `ConnTable`/owned-table abstraction instead of analyzing
`assoc` and `get` globally.  The table capability is statically owned; an opaque
generational key gives stale-key detection; lookup yields a scoped lease; the
member's state and outstanding terminal obligation are monitored.  Define a
pure table transition/replay function so the table history is re-derivable.

**Known technique applied.** Generational arena/slot-map stale-reference
protection plus an owned container with dynamic member monitoring.

**First controls.** A finite scripted multi-connection run must finish only
after every member becomes terminal.  Reuse of a removed slot with its old
generation and table finalization with an open member must report key/member
provenance.

**Nonclaims.** This is neither generic collection typestate nor static proof of
runtime-selected member protocols.  Generation wrap must be retired or stated
as a bounded guarantee.  No scheduler, fairness, or peer-progress conclusion
follows.

### A3. Make runner-inspired layers ordinary explicit data

**Source facts.** `perturb.effect` has a dynamic operation vocabulary and
implicit tail resumption; it has no captured continuation.  The Jolt native
`proceed` seam is separately scoped, owner-thread-bound, LIFO, and one-shot.

**Proposal.** Do not add first-class effects, compiler lowering, effect rows,
or delimited control.  Give a Perturb handler instance an explicit identity,
named outer instance, and `finalize!`; make forwarding a distinct operation
which targets that outer instance rather than rebinding the same name.  Record
one canonical event per attempted operation, including abort, malformed reply,
and route.  Run finalization on success, abort, and exception; it may report
transfer/discharge but may not retry native `proceed`.

**Known technique applied.** The engineering portion of runner composition:
explicit outer interpretation and finalization, without claiming the runner
calculus or a proof of protocol simulation.

**First controls.** Self-forward must fail; lower abort laundering must fail
unless an explicit recovery edge is recorded; wrong-thread, second-use, and
finalizer use of `proceed` must fail; a forged/reordered projected trace must
fail replay/coherence.

**Nonclaims.** This does not supply static effect safety, complete native-I/O
interception, or liveness.  The native `proceed` and a layer `forward` are
different mechanisms and must remain different trace routes.

### A4. Keep parsing and monitoring deliberately small

Extract a pure bounded line/frame step from `StreamConn`; the transport driver
owns receive, buffer append, and transcript recording.  Retain exact logical
rollback/retained buffering in v0 and measure one-byte chunking, maximum frame,
repeated-prefix CPU, and retained-buffer high-water mark before introducing
committed-prefix resumptions.  Add a pure finite-trace monitor fold with
`:pass`, `:violation`, and `:inconclusive`, initially for objective facts:
route completeness, operation/result shape, finalization, close ordering, and
parser terminal consistency.  `evtapp` remains application logic, not evidence
that integer ids are capabilities.

**Do not do yet:** generic predicate refinement, SMT expansion, pervasive
regions, a typed protocol elaborator, or resumable parser continuations.

## B. Scoped jolt-sim improvements

These are optional improvements to `jolt-sim`, not a request to import Perturb's
checker or change Jolt application APIs.

### B1. Reject ignored simulation data

Audit and remint exact schemas for kernel configuration, task status records,
transition maps, and strategy state.  In particular, malformed manually
constructed seeded strategy state should fail before state mutation rather than
acquiring accidental deterministic behavior.  This transfers the narrow lesson
from Perturb's accepted-but-ignored declaration field, not its language design.

**Controls:** missing/extra/operation-irrelevant keys, malformed record and
metadata, noninteger or impossible strategy state/index, plus known-good
constructors.

### B2. State exploration strength as data

Add an additive exploration manifest beside (not inside) `schedule-plans`, for
example:

```clojure
{:method :lexicographic-prefix
 :future-count 3 :space-size 6 :generated 6
 :coverage :bounded-complete
 :assumptions #{:top-level :single-parent-zero-spawner :serial-admission}}
```

Hegel cases must report `:sampled`, generator/shrink shape, and no claim of
uniformity or exhaustive schedule coverage.  This makes the existing bounds and
assumptions machine-readable without changing the plan vector API.

**Controls:** complete `N=3` domain, incomplete prefix, oversized limit,
sampled direct generation, malformed/mismatched manifest, and byte-identical
repetition.

### B3. Separate canonical scenario result from supervision evidence

In the process explorer, retain a canonical replayable `:scenario-result`
separate from live `:supervision` facts (PID, deadline, signal/reap path,
diagnostics, artifact retention).  A timeout means only missed deadline, never
deadlock.  This avoids PID/log timing leaking into replay assertions while
keeping infrastructure failures diagnosable.

**Controls:** same scenario has equal canonical results across fresh workers
despite different PIDs; timeouts record signal/reaping evidence; cleanup/result
failures cannot become application failures; poison cases stay process-isolated.

### B4. Add a reusable pure edge-machine monitor, alongside exact replay

`monitor.clj` can gain an opt-in constructor that projects selected trace events
into declared edges, terminal obligations, and required observed operations.
It must return `:inconclusive` for missing required evidence and include monitor
id/version, event index, site, edge, and bounded prefix.  It complements
byte-exact replay: a machine can accept legal reorderings that an intentionally
pinned transcript rejects.

**Controls:** legal reorder pass; illegal edge, use-after-terminal, and leaked
resource violate; absent required operation is inconclusive; every monitor ships
with a violating trace.  This is `monitored`, never a replacement for replay or
a liveness result.

### B5. Improve modeled-boundary provenance before introducing layering

`handler_pack/compose` currently returns a plain handler map after collision
checking; runtime traces carry handler keys and routes but not pack ownership.
Add an *additive* bundle form that preserves canonical key → pack id ownership,
leaving `compose` source-compatible.  Let opted-in runs record stable effect and
pack ids.  Model-internal calls from SQLite/POSIX into memory must remain marked
as model internals rather than fictitious extra intercepted FFI crossings.

Then, separately, permit optional terminal probes after lifecycle drainage and
before FFI restoration.  A probe reports, rather than auto-closes, owned memory,
SQLite DB/statement/blob state, POSIX endpoints/waiters, and any model leaks.
A failed opted-in probe is a typed model-terminal failure that application code
cannot catch into success.

**Controls:** ownership survives handler-key canonicalization; clean and leaked
worlds; failed probe latches failure; model-internal crossing does not inflate
the public effect trace.  Evidence is `monitored` per executed run.

### B6. Do not conflate modeled origin with liveness

The current resource ledger is intentionally conservative about fake values
reaching native fallback.  If it grows terminal accounting, preserve origin
even after retirement: a retired fake pointer/descriptor must still be blocked
from native fallback.  Add lifecycle status only for terminal reports, with no
reuse/generation feature until a demonstrated need exists.

**Controls:** acquire/retire, double retire, overlapping registration, live
terminal failure, and retired fake value still blocked from native routing.

## Non-transfers and correction

Do not transfer Perturb's capability annotations, static checker, generic
regions, effect calculus, or an assumption that its scheduler research proves
contention exploration.  Do not replace `jolt-sim`'s fail-closed FFI seam with a
convention-based dynamic-var gate, and do not replace exact replay with a
monitor.

One existing suggestion in `FEEDBACK-TO-NEIGHBOURING-LANES.md` requires a fresh
source check before it is acted on: it names a SQLite `:tx-effect` seam at lines
704–717, but the current `sqlite.clj` location contains cleanup/snapshot
material rather than that seam.  Treat the suggestion as stale until the exact
target revision is identified; this document makes no implementation claim from
it.

## Execution order and evidence boundary

For Perturb: A1 → A2 → A3 → A4.  For jolt-sim: B1/B2/B3/B4 can be independent;
B5 must precede any explicit inter-pack forwarding, and B6 only follows if
terminal accounting needs lifecycle state.  No code was edited and no suite was
run for this audit.  Each item becomes at most `monitored` or `sampled` after
its controls; it does not establish whole-program safety, scheduler
completeness, native-platform behavior, or a research novelty claim.
