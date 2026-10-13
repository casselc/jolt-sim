# RFC 0007: Boundaries, and the inference operation contract

Status: **accepted** (revision 4, 2026-10-12). This is the normative text: it
states the contract as it now stands and keeps no superseded description.
The path to it is in "History". halo-flash's half is its
`docs/OPERATIONS.md` (branch `rfc/operation-api`; revision 3, 7b5536e, aligns
with this text: every verb carries `Halo-Not-After` and `Halo-Digest`, and
records are bounded with a `max_records` ceiling, a 503 with reason
`records`, and the contract reporting records in use), which this RFC
adopts; where the two differ, this RFC says so.

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

## The wire as served

halo-flash's stages 2 and 3 (operation records, then the verbs; main `c4a39ff`)
are on its main, and `outbox/test/stub_ops.py` follows them (read from
`src/server/operation_service.cpp` and `operations.cpp`, not only from
`OPERATIONS.md`). What the adapter must do, because of it:

- **The digest is the SHA-256 of the submit's body bytes.** The outbox
  journals the exact bytes it sent and resends them byte for byte; the
  digest is then the attempt's identity for every other verb
  (`Halo-Digest`, with `Halo-Incarnation` and `Halo-Not-After`). A submit may
  carry `Halo-Digest` and then it must match (400 otherwise).
- **The owner is `X-Halo-Principal`.** Two owners' identical operation and
  attempt are two attempts.
- **A mode the server does not run is 422 `mode_unavailable`:** the outbox
  reads `inference.mode` from the contract before it submits.
- **Answers:** 202 accepted; 200 a duplicate's view; 409 `stale_incarnation`
  (the body names the current one) or `conflict`; 410 `late`; 400 for a
  malformed submit or a `not_after` too far ahead; 503 `records` or
  `retention`, and a scheduler 429 or 503, all with Retry-After and all
  meaning *this copy was not accepted*. A status for another incarnation is
  200 `unknown`. `result` on an attempt not done is 409 with its status; the
  stream is 410 once the body is gone, replays from `?from=N`, and ends with
  an `event: status` frame.
- **A view** is `{operation_id, attempt, status, digest}` with
  `cancel_requested` while running, `finish` and `usage` once finished, and
  `expires_at` while `done`; usage is `{prompt_tokens, cached_tokens,
  completion_tokens, engine_ms}`, `engine_ms` 0 for now.
- **A cancel of a running attempt answers `running` with
  `cancel_requested: true`;** it finishes `done` with finish `cancelled`.
  (`OPERATIONS.md` calls the answer `stopping`; the server has no such status.)
  A cancel with no record and a carried `not_after` already past answers
  `closed` and records nothing: there is no copy left to fence.
- **`operations` in the contract is null only on a server run with
  `--operations 0`;** the top-level fields are always there.

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

### The adapter as built

`llm.complete` through `--ops` is a journaled submit, then status polling, fetch, journal, and
only then the acknowledgement. The operation id is the intent's idempotency key salted with the journal's store identity (never reused: a fresh journal's run is other operations, not a rebinding of ones halo still holds under another deadline, which halo would answer 409 `conflict`; found by the run against real halo); the attempt is
the adapter's own count, so a call after a settled attempt is a new one and a call after an open
one resumes it. The adapter keeps one row per attempt beside the journal (`ops`: incarnation,
`not_after`, the exact submit bytes, the digest, and whether it is settled, committed, acked); the
bytes are what every send of the attempt carries. A call that fails in transit is repeated within
its patience (`wait_s`), since a repeat of the attempt's one submit is harmless. The
acknowledgement is the answer's `committed` hook, which the outbox runs only after `Journal.finish`
returns, and what halo is owed (`owes`, `<op>/<attempt>`) is written to the journal's `obligations`
table in the same transaction as the answer: a crash between the commit and the acknowledgement
cannot strand it, and at start the adapter pays every obligation still open. An `expired` outcome
follows the same order (reviewer's finding on e6d08a4): the adapter raises `Expired` carrying the
usage, the obligation and how to pay it; the outbox journals the usage and the obligation, and only
then pays. `CORDIS_OUTBOX_CRASH=after-journal|after-expired-journal` ends the outbox exactly between
the two for `ops.sh` phases K and L, which also check that halo still holds the outcome after the
crash and that the next outbox acknowledges it. `e2e` phase `ops.sh` runs the review loop through
lost and duplicated submits, a lost acknowledgement, a halo restart and the recovery, against
`stub_ops.py`.

The engine confirms application (step 5): after its run store syncs, `cordis-run` sends
`{"op":"applied","key","attempt"}` for each receipt fed since the last sync, and the journal
appends an `applied` fact (`Journal.applied`; `Journal.pending` lists answers not yet
confirmed). A Done row still answers the same receipt when its intent is released again, since a
rollback of the run store rewinds the engine and not the journal: the confirmation ends the
delivery's being pending, it does not retire the row. A confirmation lost to a crash leaves the
delivery pending until the engine, restarted, applies the receipt again. The outbox does not yet
push pending deliveries to a reconnecting engine; the engine asks by releasing its intents.
An `expired` status (halo df4b6e8 carries the attempt's actual usage on it) is settled at that
usage, not the worst case: the adapter raises `Executors.Expired`, the journal row is unknown with
source `expired` and the cost, the `lost` frame carries `cost`, and the engine's `Lost_at` charges
it (at most the worst case) and tries again as for any lost attempt; `ops.sh` phase H runs it, and
`observe_report.qnt`'s `expireReported` models it (`accounted` holds; `expireUnder`, a world that reports
one token too few, breaks it). The
A 409 `conflict` (the identity already bound to another request in that incarnation, which may have run) is charged as unknown, not refused, and reported loudly; `ops.sh` phase J forces one (halo's suggestion). The
adapter never sends a cancel yet: when to give up on a running attempt is policy, and belongs in a
gate and a cell, not in `src/`.

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
- **Each fact carries the writer's revision**, and the state is the
  unsuperseded fact of the highest revision. A supersedes link alone cannot
  order facts across a gap (`journal.qnt` found it): with fact 1 not yet
  arrived, fact 2 (superseding 1) and a late fact 0 both look current among
  the facts present, and the one appended last would win.
- **Later evidence does not overwrite an earlier decision's basis.** The
  attempt's current state is a resolution of the chain; the chain is kept.
- **Any accounting adjustment is an explicit, authorized, exactly-once
  ledger event** that names what it adjusts, with a stable id so a retry
  cannot apply it twice. Cost is computed from price facts that carry
  valid time, and every cost result names its price facts' as-of.
- **Audits query a durable basis**: a store identity and a commit position.
  A basis that has expired (at or below the history horizon, after which a
  checkpoint stands for the compacted facts) or is from another store (a
  restore reuses positions for other facts) is refused, never answered with
  incomplete or different history. What a basis shows is the resolved value
  at its position, which a checkpoint preserves; the list of facts above the
  horizon may legitimately shrink as compaction proceeds.
- **Valid-time history is added selectively**, where pricing or lifecycle
  facts need retroactive correction, not as general bitemporal execution.
  **Historical authorization explains a past action; it never grants current
  authority.**

The journal is built to this shape (`outbox/journal.ml`, tested in
`outbox/test/journal_test.ml`, each model mutant mirrored by a unit case that
fails when the code is mutated). `intents` is written once; `facts` carries a
stable id (`<jkey>@<rev>`), the writer's revision, a custody position,
`supersedes` and a reason; `heads` and the `journal` view resolve (the
unsuperseded fact of the highest revision), so readers see the row they
always did; `ledger` events apply once by id; `basis`, `view_at` and `compact`
implement the audit basis. A journal from before facts migrates in place: each
row becomes an intent and a first fact marked `migrated`. A restore from a
backup must call `new_store_id`.

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
| `journal.qnt` | The journal as appended facts: revisions and supersedes, exactly-once ledger events, the audit basis | `latestWins`, `adjustAgain`, `answerExpired`, `ignoreStore` |
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
   contract first (**stage 1 is on halo's main**, `b196468`, merged as
   `6fab168`: a 32-hex-digit incarnation and `Halo-Time` in milliseconds on
   every answer, errors and connection-limit refusals included, and `GET
   /v1/halo/contract` naming the incarnation, its clock and the inference
   mode, with `operations` null until the verbs are served; the outbox
   records the world on every answer from it, in the journal and on the
   receipt frame); then operation keys in the scheduler (binding digests,
   incarnation and deadline checks, tombstones, reservation, the capture
   window, ack release); then the endpoints and the stream. Disconnect never
   cancels; metadata is bounded; `exact` and `relaxed` are advertised.
3. **The outbox's durable halo adapter** (built, `outbox/halo_op.ml`, run by
   `cordis-outbox --ops URL`; see "The adapter as built"), in the order of "The durable
   handoff". The adapter is built against a stand-in server that speaks the
   verbs, as the header rules were; the header rules stay for ordinary
   clients and for a server run with `--operations off`, and retire for
   operations once the adapter moves.
4. **The journal's history** (done): append-only facts with `supersedes`,
   ledger events, and an audit basis.
5. **One end-to-end milestone** (below).
6. **Only then** ask whether a second executor calls for extraction.

### The milestone

Cordis, the outbox adapter and halo run together through: lost and delayed
submissions, cancellation races, outbox and executor crashes, lost
acknowledgements, duplicate receipt delivery, and expiry. It verifies the
accounting, that every receipt is preserved and applied once, the retention
and metadata bounds, and that audit views reproduce. The tested revisions of
all three are pinned, and failure evidence is retained.

`outbox/test/milestone.sh` is that run against the stand-in (pinned to halo-flash
`c4a39ff`'s wire): eight engine crashes, an outbox restart and a server restart,
through a lost submit answer, a late copy, a duplicate, a lost acknowledgement and
a lost submit. It checks one done attempt per critic computation, no
acknowledgement owed or outcome held, and a clean audit; on failure it copies its
working directory to `$MILESTONE_EVIDENCE`. It found that an acknowledgement owed
to an incarnation that has since restarted (409 `stale_incarnation`) was retried
and owed for ever; the body went with the incarnation, so it is now counted done.
Not yet run against halo itself (needs its build and `--operations`), nor does it
cover expiry or cancel: those are the selftest's and `expiry.qnt`'s so far.

## Open questions

1. Refusal cost: what reservation at acceptance costs callers who set
   `max_tokens` far above what they use, measured on halo's 8-agent pool.
2. A completion handoff for inference, if restarts make `unknown` common.

Answered by halo's revision 3: the outbox gets its first `Halo-Time` from the
contract call (`time_ms`); the ceiling on retained records is `max_records`,
and a platform that stops acknowledging sees records in use, unacknowledged
outcomes and the oldest one's age in the contract and `/metrics`, and a 503
naming `records`; a refused cancel leaves the outbox waiting for `closed`.

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
- Revision 4 (accepted): the fourth review. One normative text; inference-only,
  implemented in the outbox, extraction deferred; the verbs carry the
  deadline (`guessDeadline`); metadata bounded; history append-only;
  temporal scope narrow; one milestone.
