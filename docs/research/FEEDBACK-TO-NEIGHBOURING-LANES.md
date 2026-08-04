# Feedback to the neighbouring lanes, from perturb

**Status: offered, not requested.** Four agents read the integration stack
(`jolt-sim` #24, #26–#32), `casselc/db` #1–#3, `casselc/jolt-net` #2,
`casselc/jolt-tcp` #2 and `casselc/jolt`'s application-core charter branch,
read-only, looking for what refutes a claim in `PERTURB-DESIGN.md`. Four things
did, and they are recorded as E26 there. This note is the other direction: what
perturb has established that looks useful to those lanes.

Nothing here is a review. None of it was executed against your test suites, and
every item names how to falsify it.

---

## 1. Transcript equality is pinning more than you want it to

`outbox_sqlite_plans.clj` pins SQLite conformance as a FIFO transcript of 15
statement plans, asserted as `{:plan-index 15 :plan-count 15 :open-dbs 0
:active-stmts 0}`.

That assertion rejects **legal reorderings along with illegal ones**, and it has
to be re-pinned whenever the payload or the adapter changes. The property you
actually want — statements occur in an order the engine permits, every opened
handle is closed, no statement outlives its connection — is a property of
*edges*, not of a sequence.

perturb states exactly that shape as a typestate machine (`perturb/src/perturb/http.clj:386-442`
is a worked two-machine example with a cycle and a terminal obligation):

```clojure
{:states [:idle :open :pending-cleanup :poisoned :closed]
 :initial :idle
 :terminal [:poisoned :closed]
 :transitions [{:op 'begin :from :idle :to :open} …]}
```

Evidence then generalises across the input domain instead of across one pinned
trace.

**Falsifiable swap, cheap to run:** replace the 15-plan transcript with a
declared machine and check that the same bug class is still caught. If it isn't,
the transcript is carrying something the machine cannot say, and perturb would
like to know what — that is a finding for us, not just for you.

## 2. Name what a green run proves, per axis

A green Hegel run over the outbox is three different strengths at once:

| axis | strength |
| --- | --- |
| stream capacity {8,16,32}, pipe capacity {1,2,4}, poll-EINTR ordinal {nil,1,2,4,8} | **bounded-complete** — the test already asserts exact domain coverage |
| payload octets (≤32) | **sampled**, at one seed |
| the classification invariant behind the semantic selectors | **assumed** — an argued property of application code order, with a loud-failure detector |

perturb's charter uses a fixed lattice for this — `proved | bounded-complete |
sampled | monitored | assumed | opaque | failed` — for one reason: a large
assertion count reads as `proved` to anyone who did not write the test. "535
tests / 4,323 assertions" is true and says nothing about which of the three rows
above it covers. Publishing the verdict per axis costs a line and stops the
strongest number in the report from standing in for the weakest claim.

## 3. You already own the mechanism db needs for its unexercised branches

`casselc/db` records honestly that outcome **R3** of `verified-sqlite-begin!`
(rollback claims success but `sqlite3_get_autocommit` still reports `ac = 0`) "is
defensive coverage, not exercised by an executed test". R4, R5 and R7 are in the
same position. These are the *poison* branches — the ones that matter.

`jolt-sim`'s SQLite model already has the lever: `:tx-effect :when :always`
(`src/jolt/sim/sqlite.clj:704-717`) applies the physical transition **even when
the step reports its error**, and `:never` withholds it. That is an uncertain
`BEGIN` on demand — precisely the state db cannot reach with a real engine.

**Suggested cross-repo move:** drive db's recovery contract from the simulated
adapter and assert each of R1–R7 is reached. perturb's version of this rule is
that a monitor which has never fired is not evidence, and every monitor needs a
positive control; db has five well-formed branches with no control. This is the
cheapest positive control available to either repo, and it needs no new
machinery on either side.

## 4. The scoped recv-reset injection is not explored yet

PR #32's resource-scoped `ECONNRESET` rule is unit-tested and touches no Hegel
lane, so it is `monitored`, not explored. Drawing the fault as an input axis —
as the EINTR ordinal already is — would move it to `bounded-complete` over a
small domain for roughly the cost of the axis.

Worth saying separately: **`:peer-port` scoping is a good idea and perturb is
adopting the distinction.** perturb cannot let a runtime value *select a
capability*, but scoping an *obligation* by a runtime key is strictly weaker and
apparently sufficient. `socket-facts`' framing — "the admission linearization
point… evidence, not a lifecycle lease" — is the sentence that makes it work,
and it is worth keeping in the code as a comment.

## 5. The `:init` defect has a general shape, and it is worth a review rule

`casselc/db`'s `:cleanup-errors` is a ghost variable in all but name: cleared at
attempt start, appended on failure, and consulted by the poison exception as
`(:error (first errors))` — i.e. a precondition on a terminal edge. That
precondition was **false** until review caught a missing clear, so a stale error
from a *previous* attempt could be reported as the cause of this poisoning.

perturb's refinement syntax has an `:init` key for exactly this and only this
reason (E19), and the defect it prevents is the one that happened here in
shipped code, found by review rather than by a test.

**Generalisable rule, no perturb required:** any accumulator consulted by an
error path needs an explicit clear at attempt start, and the test for it is
*two* attempts where the first fails differently from the second. That test does
not exist in either repo.

## 6. `replay-history!`'s purity requirement deserves to be stated as a rule

This is the one that refuted us, so treat it as praise rather than advice.
`replay-history!` validates a history's internal coherence by re-deriving it
from a **pure** transition function, without validating protocol legality and
without discharging any static obligation. perturb's design record had recorded
that posture as impossible — a ledger either observes or becomes a dynamic
checker in disguise — and it is now tally row 38.

Two things worth writing down where a reader will find them:

- **The purity of the director is the load-bearing property**, not an
  implementation detail. It is what makes coherence checkable at all.
- **The cost boundary you found is reusable and non-obvious:** O(1) per call,
  O(N) at construction and snapshot boundaries, because full replay per call
  "would make N calls quadratic and can consume an application's real monotonic
  deadline" (`posix_fault.clj:279-285`). That is the first concrete answer
  anyone in either lane has to "what does a coherence monitor cost", and it is
  currently buried in a docstring.

## 7. jolt-tcp on Windows — a hypothesis, offered as a lead only

Unconfirmed; CI logs were not reachable from the session that produced it. The
clock migration is **exonerated with evidence**: `monotonic-clock-facts!`
(including a not-millisecond-truncated discriminator) is invoked by all four
Windows gates, and `jolt-net` #2 is green on both Windows arches at the same pin.

What changed is scope — both jolt-tcp Windows jobs went from *negative* gates
(assert `:unsupported-target` throws) to *positive* ones, and x86-64 now starts a
full reactor via `public-client-loopback-contract`.

The seam: `teensyp.server/interest` (`server.clj:883-888`) returns `#{}` whenever
`READ-MASK` is set, which includes `WORKING` — so **a live `WSAPOLLFD` entry with
`events == 0` is a steady state for every connection while its handler runs**.
`readiness.clj:93-101` asserts for both targets that error/hangup "are never
requested… Winsock additionally rejects them as inputs", which relies on POSIX
reporting `POLLHUP`/`POLLERR` regardless of `events`. jolt-net's own
`PLATFORM-COVERAGE.md` flags the divergence: a peer FIN with no pending data
reports `POLLHUP` **alone** on Winsock.

jolt-net's passing W4 gate never registers an empty interest set and never uses
the 3-arity `await-ready` cursor form — both of which the reactor uses
(`server.clj:989`, `:1166`).

**Concrete suggestion:** add those two cases to jolt-net's Windows gates. If the
hypothesis is right, the failure reproduces one layer down, where it is far
cheaper to diagnose than in a reactor loop. If it does not reproduce, the
hypothesis is dead and that is also worth knowing.

ARM64 has no hypothesis. That lane never starts a server, so the above cannot
explain it; the only remaining candidate noticed was the jolt-hegel bump, given
that `PLATFORM-COVERAGE.md` says Windows ARM64 has no property-test coverage and
has never resolved that dependency, while the `:windows-arm64-preview` alias
requires it with `JOLT_HEGEL_REQUIRED=1`.

## 8. Two lanes are solving the same problem and neither cites the other

`casselc/jolt`'s `APPLICATION-CORE-SEMANTIC-CHARTER.md` and perturb converge on
one problem and diverge on the answer:

- The charter makes an effect descriptor **data** — `{family, operation,
  canonical-args, operation-id, resource-id, site-id, assumptions}` — with
  handlers dynamically scoped strict-LIFO and "no continuations exist at this
  layer". It has no linearity, uniqueness or typestate. Because `resource-id` is
  a runtime identity, **a table of N live sockets is ordinary**, landing at
  `monitored`.
- perturb rejects that table statically and cannot express a server at all
  (§4.6), but can say a program *cannot* violate a protocol, which `monitored`
  cannot.

Both treat handlers as substitutable with no continuations — perturb's §1.4 "D4,
D3 deferred" is the charter's "no continuations at this layer", reached
independently. Both use `jolt.host/mono-nanos`. Neither document references the
other.

perturb's §4.6 records the choice between a language feature and a permanently
instrumented trusted core as **the live fork, undecided**. The charter is the
instrumented arm, built out; `jolt.sim`'s `clean?` is its obligation-check half
in miniature, over a genuinely dynamic collection, costing one function. Whoever
is deciding sequencing across these repos should know both arms already exist,
because that is the cheapest information in this note.

## What perturb is *not* offering

- **A scheduler.** Hegel explores permutations of future-body admission order
  with at most one body running at a time — sequentialisation, not contention.
  perturb has no scheduler either, and its contention axis has never been
  exercised (I20). Neither lane can currently make a claim about interleaving.
- **A checker you can run on this code.** perturb's checker rejects
  `teensyp.server` outright: a growing connection table, runtime-value selection,
  collection storage and a function-valued handler are all four shapes it cannot
  express. That is a limit of the checker, not a defect in the server.
- **Replay determinism from substitutable handlers.** The loopback runs on real
  threads, locks and monotonic timeouts — "virtual time is not modeled here" —
  which is why capacity counters can only be asserted nonnegative. perturb has
  no answer here and had assumed one.
