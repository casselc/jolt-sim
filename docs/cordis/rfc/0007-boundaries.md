# RFC 0007: Boundaries, and the inference operation contract

Status: proposed, in its fourth revision. This is the normative text: it
states the contract as it now stands and keeps no superseded description.
The path to it is in "History". halo-flash's half is its
`docs/OPERATIONS.md` (branch `rfc/operation-api`, e2d5030 at the time of
writing), which this RFC adopts; where the two differ, this RFC says so.

## Purpose

Who decides whether an external operation happened, and what it cost, was
settled one header at a time (`Halo-Answer`, `Halo-Discarded-Tokens`,
`Halo-Admitted-Sends`): Cordis reconstructed an operation's history through a
connection-oriented interface whose server remembers for a bounded time.
This RFC names the owners, states what an inference operation is, and
orders the work. It is a contract for **inference only**; see "Scope".

## Scope

- **One executor, one contract.** The contract is written for inference
  against halo-flash and implemented **inside the existing per-agent
  outbox**. Nothing moves into yard, and no shared operation service is
  built. The contract is stated per operation type so that a second type
  (repository, tool or sandbox execution) can adopt it; when one does, it
  decides what to extract: a library, a ledger interface, or a service.
  Until then the outbox is the operation service, now named so.
- **Trusted policy stays out of agent-editable graphs.** A graph states what
  it may request (effects, types, bounds); the runner's configuration grants
  an intent's class and authority, and the outbox enforces cost policy. A
  graph cannot make itself authoritative by declaring a permissive class
  (`observe.qnt`'s `misclassify`).
- **Not adopted:** XTDB or general bitemporal workflow execution; decoupling
  the run store from workspace snapshots (see "Workspace").

## Owners

| Owner | Authoritative facts | Does not own |
| --- | --- | --- |
| **Cordis** (the workflow runtime) | Committed logical intents, workflow state, revision and workspace basis, which receipts it applied | Credentials, provider retry rules, physical resources |
| **The outbox** (per agent; the platform operation service for inference) | Inference execution attempts, reconciliation, authorization, reservations, settlement: the authoritative accounting | Workflow semantics, model internals |
| **halo-flash** | Execution evidence: what it accepted and ran within its advertised window, its result, measured usage | Workflow retries, the spending ledger, identity issuance |
| **Workspace manager** (yard) | Which workspace versions exist and which checkpoint references each | |

Halo-specific protocol details stay inside the outbox's adapter. What RFC
0006 decided stands: the outbox is outside every dataset a step snapshot
rolls back (3B, `outbox.qnt`'s `journalInSnapshot`); recovery is the run
store's checkpoint plus log with a replay audit (5C); unikernels and the
custom tender (8C) come last.

## Identity, binding and clock

- **An attempt is `(owner, operation id, attempt)`.** The owner is the
  authenticated principal; the operation id and attempt are issued by the
  outbox, unique per owner, and never reused. The attempt is the unit of
  execution and accounting. A new execution is a new attempt the outbox has
  authorized; halo never starts one.
- **Binding.** A submit binds the attempt to a digest of its immutable parts:
  `api`, `request`, `not_after` and `mode`. Any later message for the attempt
  naming another digest gets 409 `conflict` and changes nothing.
- **Incarnation.** 128 random bits chosen at process start, per process and
  not a durable epoch (halo persists nothing an operation depends on). **Every
  verb names the incarnation it is meant for.** Another incarnation answers
  `unknown` (status) or 409 (submit, cancel, ack) and acts on nothing, so an
  old query after a restart never gets the new process's `never_accepted`,
  and a delayed cancel or ack reaches only the attempt and incarnation it
  names.
- **Clock.** Every answer carries `Halo-Time`, milliseconds on halo's
  monotonic clock since the incarnation started. Deadlines are in that
  clock and only halo compares them, so no two hosts' clocks are compared. A
  restart is a new incarnation with a new clock. The outbox computes
  `not_after` from its latest `Halo-Time` plus its own monotonic time
  elapsed since that reading plus the window; an underestimate only shortens
  the window. Halo rejects a `not_after` more than `max_submit_age_s`
  (proposed 600 s) ahead.
- **Every verb carries the deadline.** Status and cancel carry `not_after`
  and the digest as submit does. This is what lets halo answer for an
  attempt whose submit never arrived: with no record, it compares its clock
  with the carried `not_after`. Without it halo has no deadline for that
  attempt and must guess one, and a guess earlier than the true deadline
  answers `closed` while a copy can still be accepted (`expiry.qnt`'s
  `guessDeadline`). A cancel records the carried `not_after` and digest in
  its tombstone, so a later submit naming another is a conflict.

## Verbs

All requests carry `Halo-Incarnation`, the attempt's `not_after` and digest,
and are authenticated. All answers carry `Halo-Incarnation` and `Halo-Time`.

| Verb | Request | Answers |
| --- | --- | --- |
| contract | `GET /v1/halo/contract` | incarnation, time, the guarantees per operation type |
| submit | `POST /v1/operations` `{operation_id, attempt, not_after, api, request, mode}` | 202 `accepted`; a duplicate gets the current status; 409 stale incarnation or `conflict`; 410 past `not_after`; 429 or 503 with Retry-After |
| status | `GET /v1/operations/{op}/{attempt}` | see "Status answers" |
| fetch | `GET /v1/operations/{op}/{attempt}/result` | the body and usage, while held |
| observe | `GET /v1/operations/{op}/{attempt}/stream?from=<event>` | SSE from an event offset while the body is held; 410 with the status afterwards. Leaving is an observer departing, never a cancel. Partial tokens are observations, never receipts |
| cancel | `POST /v1/operations/{op}/{attempt}/cancel` | `cancelled_before_accept` (the id is tombstoned: a later submit is a no-op); `stopping` (finishes at the next unit boundary as `done`, finish `cancelled`, with its actual usage); or the current terminal status |
| ack | `POST /v1/operations/{op}/{attempt}/ack` | the outbox has durably committed the result: halo releases the body and keeps the outcome until the tombstone is pruned |

**Disconnect never cancels.** There is no detach policy: an operation is
cancelled only by an explicit cancel, and observers never own execution.

### Status answers, and what the outbox does

| Status | Meaning | The outbox |
| --- | --- | --- |
| `running` | accepted, executing | waits |
| `done` | finished (finish reason, usage, `expires_at` in halo time); the body can be fetched | fetch, commit, ack (below) |
| `released` | acknowledged and released | nothing; it committed before acking |
| `expired` | finished, but the capture window passed without an ack: the body is gone, the outcome and usage are not | settle at that usage, no receipt; a new attempt if the operation still needs one; ack the outcome |
| `cancelled_before_accept` | cancelled before any copy was accepted | settle at 0 (terminal) |
| `closed` | never accepted, and `Halo-Time` is past the carried `not_after`: no copy can be accepted any more | settle at 0 (terminal) |
| `never_accepted` | not accepted *yet*: a delayed copy may still arrive | keep the reservation; resend as first sent, cancel, or wait for `closed` |
| `unknown` | another incarnation | settle at the worst case R; a new attempt to run again |

`forgotten` (evicted before capture) does not exist under the retention
promise; a server without the promise would answer it, settled at R.

### Rules the outbox keeps

1. **Commit before ack** (below).
2. **A refusal closes the copy, not the attempt.** A 429 or 503 shows that
   *this copy* was not accepted; another may still be. The reservation stays
   until a terminal answer: `done`, `expired`, `cancelled_before_accept`,
   `closed` or `unknown`.
3. **Settle `never_accepted` only through a terminal answer.** That is
   `cancelled_before_accept` (from the cancel, or a later status) or
   `closed`.
4. **Resend a submit exactly as first sent**: same identity, incarnation,
   deadline and digest. It is never re-aimed at a new incarnation; an unknown
   attempt is never sent again into another one.
5. **Repeat a cancel until it is answered.** It can be lost, its answer can
   be lost, and it can race acceptance and completion. A cancelled attempt
   that ran has a retained terminal result with its actual usage:
   cancellation is not proof that nothing was spent.

## The durable handoff

The outbox's capture is five steps, and the order is the protocol:

1. **Commit the intent and its reservation** before submitting.
2. **Fetch** the result (volatile).
3. **Commit** receipt, settlement and a pending workflow delivery, durably, as
   one record.
4. **Acknowledge** halo, which then releases the body.
5. **Deliver** the receipt to the workflow, which applies it once by the
   attempt, and confirms; the delivery stops being pending only on that
   durable confirmation.

The outbox may crash between any two steps; an acknowledgement or a delivery
may be lost or duplicated. A lost acknowledgement only keeps the body longer
(until its window ends and it becomes `expired`); it never loses the receipt.
`capture.qnt` states this: `resultNotLost`, `receiptNotLost`, `appliedOnce`,
and the accounting, with mutants `ackFirst`, `clearEarly` and `noKey`.

## Retention

- **The capture window is the promise.** Halo holds a body until it is
  acknowledged or its window (`capture_s`, advertised; `expires_at` on every
  `done`) ends, and never evicts under load. After the window the outcome
  (status and usage) is kept until it is acknowledged, then pruned with its
  tombstone. This is a bounded promise; "until captured" needs unbounded
  memory.
- **An accepted attempt always leaves a record until the outbox
  acknowledges it.** That is what makes `closed` sound: with no record past
  the window, the attempt was never accepted. Pruning an unacknowledged
  outcome turns "it ran" into "closed" (`dropOutcome`).
- **Space is reserved at acceptance**, for the most the result can need:
  `max_tokens` times the bytes-per-token bound, tool-call arguments and
  reasoning text, and the stream's event history. If reservations would
  exceed the budget, halo refuses with 503 and Retry-After. **Metadata is
  bounded too**: the tombstones and unacknowledged outcomes have a stated
  ceiling, and halo refuses admission when it is reached; it never erases
  history to make room.
- **Tombstones** keep an attempt id until its `not_after` has passed and, if
  it ran, its outcome has been acknowledged (about 50 bytes per id seen in a
  window).
- The stream answers 410 with the status once the body is gone.

## Usage and modes

- **Usage is `{prompt, cached, completion}`, disjoint.** `completion` counts
  every token the attempt decoded, delivered or not, a cancelled attempt
  included. There is no `discarded`: an attempt executes at most once.
  Token counts are the accounting unit; `engine_ms` is a measurement, not a
  cost.
- **`mode`** is `relaxed` (the default) or `exact`, and is part of the
  binding. Under `relaxed`, batch participation can change a greedy
  session's output (about 0.8% of token positions on halo's fixtures); under
  `exact` greedy output is identical on the same build and weights. The
  contract advertises it.

## Accounting

| Outcome | Settled at |
| --- | --- |
| `done`, captured | the actual usage; receipt applied |
| `expired` | the actual usage; no receipt |
| `closed`, `cancelled_before_accept` | 0 |
| `unknown` (another incarnation) | the worst case R; a new attempt |
| `forgotten` (a server without the promise) | R |

A submit lost before it arrives costs 0. When the executor completes work
and is lost before the result is captured, the outcome is unknown and no
ledger can make it known: the outbox preserves the uncertainty and settles
at R. Removing it would need a completion handoff, a separate design choice
and not needed until restarts prove frequent.

## History: decisions and evidence are append-only

The outbox's journal records what was known when a decision was made. This
borrows the discipline of casselc/observability (STPA requirement R-L6 and
its near miss 49, R-L3):

- **Facts are appended, never rewritten.** Each has a stable id, a custody
  time, a source (the halo answer, with its incarnation and `Halo-Time`, or
  the outbox's own decision), and the fact it **supersedes**, if any.
  Precedence is stated by the writer through `supersedes`, never inferred
  from arrival order: at-least-once retries reorder arrivals, and "latest
  wins" would let a retried older fact override a newer one.
- **Later evidence does not overwrite an earlier decision's basis.** The
  attempt's current state is a resolution of the chain; the chain is kept.
- **Any accounting adjustment is an explicit, authorized, exactly-once
  ledger event** that names what it adjusts, with a stable id so a retry
  cannot apply it twice. Cost is computed from price facts that carry
  valid time, and every cost result names its price facts' as-of.
- **Audits query a durable basis**: a store identity and a commit position.
  A basis that has expired (compacted past, or from another store) is
  refused, never answered with incomplete history.
- **Valid-time history is added selectively**, where pricing or lifecycle
  facts need retroactive correction, not as general bitemporal execution.
  **Historical authorization explains a past action; it never grants current
  authority.**

The current journal updates rows in place (`journal.ml`); moving it to this
shape is a build step, not a property it has.

## Workspace

The invariant: **a resumed workflow observes the workspace version on which
its retained state and receipts depend.** Rolling the run store and the
workspace back together is the implementation (what is built, and what
`rollback-fidelity.sh` checks); an explicit checkpoint-to-artifact reference
is another, right for branching, and waits until a replacement proves the
invariant including branching and garbage collection. It is revisited
separately. A model cache is disposable acceleration.

## The models

| Model | States | Mutants (each breaks one property) |
| --- | --- | --- |
| `operation.qnt` | Identity, a lossy network, incarnation fencing, tombstones, never-accepted abandoned only through a cancel, unknown settled at R | `noFence`, `noTomb`, `abandonUntombed`, `cacheNoTomb`, `resubmitSame` |
| `capture.qnt` | The durable handoff across crashes and lost or duplicated messages | `ackFirst`, `clearEarly`, `noKey` |
| `expiry.qnt` | The deadline, the capture window, the outcome kept until acknowledged, the monotonic clock, the verb carrying the deadline | `dropOutcome`, `closedEarly`, `guessDeadline`, `pruneEarly`, `lateAccept`, `clockBack` |

halo-flash's `spec/operation/` is the mirror from the executor's side (it
reports 10 mutants, all caught, and the design at 100,000 samples of 80
steps); I have not rerun it. The two scopes differ: ours is the outbox's
accounting and handoff, theirs the executor's identity, delivery, time and
retention. Their correspondence table is in `OPERATIONS.md` section 7. The
operation model is also proved by Apalache to 8 steps (6 in the gate).

## Order of work

1. **Consolidate** (this revision).
2. **halo-flash implements its API**: `Halo-Incarnation`, `Halo-Time` and the
   contract first; then operation keys in the scheduler (binding digests,
   incarnation and deadline checks, tombstones, reservation, the capture
   window, ack release); then the endpoints and the stream. Disconnect never
   cancels; metadata is bounded; `exact` and `relaxed` are advertised.
3. **The outbox's durable halo adapter**, in the order of "The durable
   handoff". The adapter is built against a stand-in server that speaks the
   verbs, as the header rules were; the header rules stay for ordinary
   clients and for a server run with `--operations off`, and retire for
   operations once the adapter moves.
4. **The journal's history**: append-only facts with `supersedes`, ledger
   events, and an audit basis.
5. **One end-to-end milestone** (below).
6. **Only then** ask whether a second executor calls for extraction.

### The milestone

Cordis, the outbox adapter and halo run together through: lost and delayed
submissions, cancellation races, outbox and executor crashes, lost
acknowledgements, duplicate receipt delivery, and expiry. It verifies the
accounting, that every receipt is preserved and applied once, the retention
and metadata bounds, and that audit views reproduce. The tested revisions of
all three are pinned, and failure evidence is retained.

## Open questions

1. Refusal cost: what reservation at acceptance costs callers who set
   `max_tokens` far above what they use, measured on halo's 8-agent pool.
2. How the outbox obtains its `Halo-Time` reading before its first submit
   (the contract call returns it).
3. The ceiling on retained metadata, and what a platform that stops
   acknowledging should see before halo refuses admission.
4. A completion handoff for inference, if restarts make `unknown` common.

## History

- Revision 1: the review of the architecture's division of responsibility
  (two agents) and the first model, `operation.qnt`: owners, an operation
  contract, unknown outcomes charged at R.
- Revision 2: halo-flash's proposal (58e60d3) and the network added to the
  model: incarnation fencing, tombstones, abandon only through a cancel.
- Revision 3: a third agent's review. `capture.qnt` and `expiry.qnt`; the
  finding that revision 1 of halo's proposal pruned a tombstone at
  `not_after` while the backstop that releases a result was much later, so a
  released result read as never accepted. halo's revision 2 (e2d5030) took all
  six requirements and answered that finding by keeping an expired outcome
  until it is acknowledged, which makes `closed` sound at no cost and which
  this RFC adopted in place of its own proposal (answer `expired`, settle R).
- Revision 4: the fourth review. One normative text; inference-only,
  implemented in the outbox, extraction deferred; the verbs carry the
  deadline (`guessDeadline`); metadata bounded; history append-only;
  temporal scope narrow; one milestone.
