# RFC 0007: Boundaries, and the operation contract

Status: proposed. Revision 3 answers a third agent's review of revision 2
and halo-flash's proposal: the durable handoff and time are modelled
(`capture.qnt`, `expiry.qnt`), and the wire contract gains requirements
(identity bound to the incarnation and owner on every verb, refusal is not
closure, cancellation races, retention reserved at admission, usage
semantics). Revision 2 adds halo-flash's half (its branch
`rfc/operation-api`, 58e60d3, `docs/OPERATIONS.md`, `spec/operation/`) and
the network to the model. Records the review of the architecture's division of
responsibility (two agents, against RFC 0006 and what was built through
round 5 of the halo-flash runs) and the one change it supports: an
**operation contract** between the platform and an inference executor. It
does not move any code. Each change below needs this RFC accepted first,
and the contract needs halo-flash's agreement.

## Purpose

Every question of who decides whether an external operation happened, and
what it cost, has so far been settled one header at a time
(`Halo-Answer`, `Halo-Discarded-Tokens`, `Halo-Admitted-Sends`): Cordis
reconstructs an operation's history through a connection-oriented
interface whose server remembers for a bounded time. Each header is
correct; together they are the symptom. This RFC names the owners, states
what an operation is, and orders the work.

## Owners

Three domains, which need not be three services, and a fourth that is
today part of yard:

| Owner | Authoritative facts | Does not own |
| --- | --- | --- |
| **Workflow runtime** (Cordis) | What the workflow committed, its revision and workspace basis, which receipts it applied | Credentials, provider retry rules, physical resource lifecycle |
| **Platform operation service** (yard's domain; today Cordis's outbox, per agent) | What it accepted and authorized, execution attempts, retained receipts, budget reservations and settlement | Workflow semantics, model internals |
| **Inference service** (halo-flash) | What inference executed within its advertised observation window, its result, measured usage | Workflow retries, the spending ledger, sandbox identity |
| **Workspace manager** (yard) | Which workspace versions exist, and which checkpoint references each | |

What stays as RFC 0006 decided: the outbox is outside every dataset a
step snapshot rolls back (3B, `outbox.qnt`'s `journalInSnapshot`);
recovery is the run store's checkpoint plus log, with a replay audit
(5C), not VM snapshots; the checker bounds what a graph may request and
the runner's configuration grants what it may do (an intent's class is
never the cell's choice, `observe.qnt`'s `misclassify`); unikernels and
the custom tender (8C) come last.

## The operation contract

An external operation is not an HTTP connection. Its parts:

- **Identity.** An operation id and an attempt number, issued by the
  platform. An attempt is the unit of execution and accounting; a new
  attempt is a new execution the platform has authorized.
- **Incarnation.** The executor names the incarnation it is (a
  fencing token that changes whenever it may have lost state). Every
  answer carries it. A query for an attempt the executor does not hold
  gets **unknown**, never a silent new submission: *never accepted* and
  *I no longer know* are different answers, and only the first lets the
  platform resubmit under the same identity.
- **Verbs.** Submit or attach (by operation id and attempt), status and
  result, explicit cancel, an observation stream. A disconnect is the
  observer leaving; whether execution continues is a policy named in the
  submit, not a socket behaviour. Partial tokens are observations, not
  receipts.
- **Retention is a promise or it is not.** "Ten minutes, unless capacity
  evicts sooner" describes a cache. The executor either holds a finished
  result until the platform has captured it (and refuses admission when
  it would have to break that), or it advertises that it does not, and
  the platform accounts for it.
- **Guarantees are declared by the operation type**, not implied by its
  name: external effects, deduplication scope and lifetime, whether it can
  be reconciled, cancellation semantics, how cost is reported. Inference
  is safe to repeat for external effects, but repeating it spends again and
  may answer differently; those are properties of the type.
- **Unknown outcomes.** If the executor completes work and is lost before
  the platform captures the result, the outcome is unknown, and no ledger
  can make it known: the platform preserves the uncertainty and settles at
  the attempt's worst case. Removing it needs additional durability or a
  completion handoff, a separate design choice. Accounting therefore
  reduces to "settle what the operation reports" only when an
  authoritative report exists; otherwise the reservation is the charge.

`quint/operation.qnt` states this (`operation_modes.qnt`, checked in
`check.sh`), over a network that loses, delays and duplicates a submit and
an executor that restarts, holds an attempt until it is acked, and (without
a promise) may evict a finished result, leaving a forgotten marker. The
platform's three answers to a query are *done / running*, *unknown or
forgotten* (settled at the worst case R; going on needs a new attempt),
and *never accepted*, which is safe to act on at no cost, by resending the
same identity or abandoning it, **given three rules**, each with a mutant:

| Rule | Broken by | Breaks |
| --- | --- | --- |
| Incarnation fencing: a submit names the incarnation it is sent to, and any other refuses it | `noFence` | `runsOnce`: a copy delayed past a restart reruns an attempt already settled as unknown |
| Tombstones: an accepted, acked or cancelled id is refused until its submit's `not_after` has passed | `noTomb`, `cacheNoTomb` (a tombstone evicted under load) | `runsOnce`: a duplicate reruns a captured attempt |
| Abandon only through a cancel that tombstones the id | `abandonUntombed` | `accounted`: the late copy runs after the attempt was settled at 0 |

and one prohibition: `resubmitSame`, sending an unknown attempt again as it
was (a submit is never re-aimed at a new incarnation), breaks `accounted`:
**an idempotency key is a starting point and not an operation contract**.
The design holds at 20,000 samples of 60 steps and is verified by
Apalache to 8 steps (6 in the gate; 8 takes fifteen minutes); the value of a promise is
measurable: `promised` (no restart, a promise) never overcharges and a lost
submit abandoned costs 0, where `cacheLike` (eviction under load) pays the
worst case for a result evicted before it was acked. halo-flash's model is
the mirror from the executor's side, and agrees on every instance (its
`cacheNoTomb` breaks `neverAcceptedTrue`, mine `runsOnce`: an invariant
about attempts still awaiting an answer, against one about every attempt).

## halo-flash's surface (revision 2)

Agreed, from `docs/OPERATIONS.md`; the implementation order is theirs:

- `Halo-Incarnation` (128 random bits, per process: a durable epoch would
  distinguish nothing, as halo persists nothing an operation depends on) on
  every answer, and `GET /v1/halo/contract` (retention, deduplication,
  cancellation, cost reporting, what repeating spends);
- `POST /v1/operations {operation_id, attempt, incarnation, not_after, api,
  request, on_detach}`: 202; the current status for a duplicate; 409 for a
  stale incarnation; 410 past `not_after`; 429 or 503 with Retry-After,
  both definitely not accepted;
- `GET /v1/operations/{op}/{attempt}`: running, done (usage with
  `discarded`, and the result), unknown, forgotten, never_accepted; a
  stream from an offset; `cancel`; `ack`;
- retention until ack (24 h backstop, advertised as part of the promise);
  halo refuses new submits (503) rather than release an unacked result;
- `on_detach` (continue or cancel) replaces keyed grace for operations.

Assumptions we state and do not model: the platform's and halo's clocks
agree to within a stated skew (today they share a host), which bounds how
long a tombstone must live; and cancel is a request the platform repeats
until answered. Open question 2 is answered (per process); question 3 is
the implementation's measurement (refusal and held bytes under the
8-agent pool; with ack-based release a refusal should mean the platform
stopped acking, a fault it can see).

On our side: record `Halo-Incarnation` and the contract as soon as halo
ships them (the outbox journals them beside `source`); the header rules
(`observe_report.qnt`, `observe_forget.qnt`, `Halo-Admitted-Sends`) retire
for an operation once the adapter moves to the verbs, and stay for
ordinary clients and for a server run with `--operations off`.

## The durable handoff (revision 3)

Capture is four steps, and the order is the protocol: **fetch** the result;
**commit** it durably in the platform (receipt, settlement and a pending
delivery, one record); **acknowledge** the executor, which then releases
it; **deliver** the receipt to the workflow, which applies it by its key and
confirms, and only then is the delivery no longer pending. A lost
acknowledgement only retains the result longer; it never loses the receipt.
`quint/capture.qnt` models the platform crashing between any two steps,
lost and duplicated acknowledgements and deliveries, and the executor
restarting, and states four properties: the executor never releases a
result the platform has not committed (`resultNotLost`); a committed
receipt is applied or still pending (`receiptNotLost`); the workflow
applies one (`appliedOnce`); and the accounting holds. Each mutant breaks
one: `ackFirst` (acknowledging on fetch, before the commit),
`clearEarly` (pending cleared on send, not on the workflow's
confirmation), `noKey` (the workflow applies every delivery).

## Time (revision 3)

The API's deadlines change what `operation.qnt` could assume. A submit
carries a deadline, `not_after`; the executor refuses one that arrives past
it (410) and so remembers an id only until then; and it releases an
unacknowledged result after a backstop. `quint/expiry.qnt` adds time, and
its findings are requirements:

- **The executor decides by its own clock; the platform only names the
  deadline.** Skew then changes how long the window is, never whether the
  rules hold. A clock that goes back does break them (`clockBack`): the
  executor needs a monotonic clock, or a tombstone horizon that does not
  depend on it.
- **Past the window, an id with no record is `expired`, not
  `never_accepted`.** After `not_after` the executor has pruned what it
  knew, so it cannot tell a submit it never saw from one it accepted, ran
  and released at the backstop (24 h, long after the tombstone's 10
  minutes). Answering `never_accepted` there (`absentAlways`) lets the
  platform abandon at no cost an attempt that ran. `expired` is settled at
  the worst case, as `forgotten` is. Abandoning at no cost therefore needs a
  query inside the window and a cancel that tombstones the id.
- **A tombstone dropped before its deadline** (`pruneEarly`), **a submit
  accepted past it** (`lateAccept`) and **a clock set back** (`clockBack`)
  each rerun an attempt.

The clock assumption is stated, not removed: platform and executor may
share a host today and not under yard; the design above needs only the
executor's own clock to be monotonic.

## The wire contract's requirements (revision 3)

Not modelled, and to be agreed with halo-flash before implementation:

1. **Every verb is bound to the identity and the incarnation.** Status,
   cancel and ack carry the expected incarnation as submit does (an old
   query after a restart answers `unknown`, never the new process's
   `never_accepted`; a delayed cancel or ack cannot touch another
   execution). Identity is authenticated owner, operation id, attempt and
   incarnation, bound to the submit's immutable content: reusing it with a
   different request, deadline or `on_detach` is a conflict (409).
2. **A refusal is not closure.** A 429 or 503 shows that *this copy* was not
   accepted; another copy of the same submit may still arrive. The platform
   keeps the reservation until an acknowledged cancel or an `expired` or
   `never_accepted` answer inside the rules above closes the attempt.
3. **Cancellation races.** A cancel can be lost, its answer can be lost, and
   it can race acceptance and completion; the platform repeats it until
   answered. A cancelled attempt that ran has a retained terminal result
   with its actual usage: cancellation is not proof that nothing was spent.
   `on_detach: cancel` is only for a single observer; platform operations
   cancel explicitly, and any detach-triggered cancel needs a stated
   ownership rule first.
4. **Retention is reserved at admission.** Admitting an operation reserves
   the space for its eventual result (output, reasoning, tool arguments and
   the stream buffer, each bounded), not only the results currently held;
   otherwise many running operations finishing together exceed the promise.
   The replayable stream states what a reader gets once its history has
   expired. The retention is a bounded capture window (backstop `max_hold_s`),
   and the answer after it is `forgotten`, with an explicit expiry returned
   on the result.
5. **Usage is defined.** `completion` and `discarded` are disjoint (a
   cancelled attempt's decoded work is `discarded` and not also
   `completion`), and token counts are not claimed to be the resource cost.
6. **The repeat guarantee is qualified.** "Identical text under greedy
   decoding on the same build" is true only of an execution mode without
   batch-dependent numerics; the contract advertises the mode, or says the
   text may differ.

## Streams and the ledger

Token streaming can stay outside the platform's data path, provided
access stays authorized and bound to the operation, partial tokens are
never committed as workflow receipts, and completion and usage reach the
ledger by a recoverable path (the platform pulls the result by id, rather
than being notified once).

## Workspace and run store

The invariant is: **a resumed workflow observes the workspace version on
which its retained state and receipts depend.** Rolling the run store and
the workspace back together is one implementation (what is built, and what
`rollback-fidelity.sh` checks); an explicit checkpoint-to-artifact
reference is another, and the right end state for branching and for
workflows whose progress should not require a whole-environment snapshot.
It is a change to the run store's design, not a deployment change, and
waits until a replacement proves the invariant including branching and
garbage collection. A model cache is disposable acceleration: losing it
changes latency, never workflow truth.

## What round 5 already is

The headers are the contract's first approximation over an interface
that has no verbs: `Halo-Answer` is the answer's source, `Discarded-Tokens`
the usage of work no answer carries, `Admitted-Sends` an incarnation
substitute (a count since the key was last forgotten). They stay until the
operation API exists; the runtime's rules for them
(`observe_report.qnt`, `observe_forget.qnt`) are the interim, and the
operation model replaces them rather than adding to them.

## Order of work

1. Specify identity, guarantees, retention and unknown outcomes (this
   RFC, `operation.qnt`).
2. Halo: status and result by operation id, explicit cancel, an
   incarnation on every answer, a retention promise or an advertised
   absence of one. Observation separate from execution.
3. A durable platform adapter around the existing Cordis/Halo protocol,
   which can be the per-agent outbox, now named as the platform's
   operation service.
4. Move execution policy and accounting ownership there: the limiter, the
   grant of an intent's class, credentials.
5. Retire Cordis's external-operation journal once equivalent recovery
   evidence exists in the platform's.
6. Revisit workspace coupling independently.

## Open questions

1. Where the platform's ledger lives for an agent that is itself
   suspended: in the agent's service jail (today's outbox) or beside
   yard's broker.
2. Whether the incarnation is a per-process token or a durable epoch the
   executor persists, which decides whether a restart is distinguishable
   from a lost disk.
3. What admission refusal under a retention promise costs a workflow in
   practice (a 429 where a cache would have evicted), measured on
   halo-flash's pool.
4. A completion handoff for inference, if unknown outcomes after crashes
   turn out to cost enough to pay for one.
