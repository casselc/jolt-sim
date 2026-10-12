# RFC 0006: The turn runtime

Status: revision 2, accepted (2026-10-10): the recommendation below is
the design; the options stay as the record of what was weighed. Phase 3 (DESIGN.md build
order items 8 and 10, with the trap side of 9). Design context:
[DESIGN.md](../DESIGN.md) "The runtime", "The outbox", "Where the outbox
runs", "Hosting"; [RFC 0002](0002-graph-language.md) "Routes and streams at
run time" and "Compilation to the kernel"; [RFC 0004](0004-descriptor-native-types.md)
"Revisions"; [RFC 0005](0005-xml-csv-frames.md) "Frames", rules 1 to 12.
Prior art: [research/STREAMING-PRIOR-ART.md](../research/STREAMING-PRIOR-ART.md),
and the sources at the end. Models it must honour: every module in
`cordis/quint/`, listed in "What is already fixed".

## Revision history

**Revision 1** set out the options for eight decisions, recommended a
combination, and listed the models to write first.

**Revision 2** takes in a review from the agent building the local
inference stack (casselc/halo-flash, casselc/yard). What changed, and
why:

| Revision 1 | Revision 2 | Why |
| --- | --- | --- |
| Run store: the framed log (2B) from stage 3, SQLite (2A) the fallback | **SQLite (2A) behind the `Store` signature at stage 3**; the framed log when the unikernel host (8C) is built | 2B's case rests on running on a Solo5 block device under the narrow tender, the furthest-out part of the plan. Snapshots, compaction and indexes before there is a unikernel to run them on is cost with no near-term return, and stage 1's conformance does not depend on the store |
| Every lost receipt makes the fiber Uncertain; only a reconcile moves it on | **Observational emissions**: an intent kind whose only outside effect is spend retries under a held reservation instead (Decision 3, "Observational emissions"); modelled in `observe.qnt` | Inference calls and most read-only tool calls cannot answer "did key K happen?", and repeating them changes nothing but spend. Under revision 1 the first LLM-heavy graphs would wait in Uncertain on reconciles no provider can answer |
| "The run store rolls back with the step snapshot" taken as exact in `handoff.qnt` | Kept in the model, and **checked natively** at the run-store stage | Yard's SR1 work (branch `sr1-service-jails`, STATE.md) found a ZFS dataset dirtied at mount with no application write: DTrace caught `zil_replay` to `zil_destroy` to `dsl_dataset_dirty`, 8 KiB in `written@snap`, while `zfs diff` was empty and content hashes identical. The models cannot see that class of behaviour, and a check must tell logical change from physical |
| Stages ordered by layer: core, values, store, outbox, cutover, frames, platform | **A vertical slice first**: the review loop end to end, with an LLM intent through a loopback outbox and a crash and recovery checked against the models, before cutover, frames, or more importers | The system's core promises (at-most-once emission, rollback, the trap, the outbox) live here, not in the importers. RFC 0005 stays unscheduled, and the OpenAPI and AsyncAPI importers wait for the slice |
| Open questions 1, 2, 3 and 9 open | Answered ("Open questions") | Values in a separate pure module; the canonical text as the record's hash input until Preserves is needed; `ctx.now` recorded in the commit record; no external durable-execution engine in the outbox |
| Hosting from the unikernel down (8C) | **8A on FreeBSD as a client of yard** first: its service-jail lifecycle (suspend, resume, refusal on drift, settled cleanup) is the outbox jail's shape | Use what runs now: jails and ZFS snapshots and clones; unikernels and the OxCaml port (7 to 14 person-weeks) follow. Yard's lifecycle is modelled (`spec/sr0/lifecycle.qnt`, with load-order, key and operator-removal models beside it) and its Go state machine replays the model's ITF traces, as `cordis/sim` does with `quintgo` |

## Purpose

Phases 1 and 2 built everything a running graph needs except the thing
that runs it. `cordis/kernel` decides lifecycle steps as pure functions;
`cordis/sim` schedules them under one entropy oracle; `cordis/graph`
checks a graph, estimates each turn's cost and compiles the graph to
kernel components (`graph/lib/compile.ml`); `cordis/cel` evaluates a turn
with a runtime cost limit (`Eval.run ?limit`). Ten Quint models state
what the running system must guarantee. None of it persists anything,
sends anything, or survives a crash.

The **turn runtime** executes admitted graph revisions. It:

- schedules the turns of cells, and the lifecycle steps around them;
- evaluates each turn's CEL with its inputs, state and receipts, within
  its cost limit and its parameters' size bounds;
- persists each turn as one atomic commit record (RFC 0005 rule 2);
- hands committed intents to the outbox, and feeds receipts back;
- recovers from a crash to the last commit, and replays deterministically;
- cuts over between revisions at commit boundaries (carry, drain,
  restart);
- runs routes, guards, streams with capacities, episodes and frames in
  batches, as the models say;
- enforces cost and budget at run time, beside the static estimates.

This RFC does not choose yet. For each decision it sets out two to four
genuinely different options, the prior art each follows, its cost, and how
it keeps the modelled invariants. It ends with a recommended combination,
a staged plan, open questions, and the Quint models to write **before**
any of it is implemented: the project's rule is that anything stateful or
concurrent is modelled first.

## What is already fixed

The options below differ in mechanism. They do not get to differ in these
obligations, each of which a model already checks.

| Model (`cordis/quint/`) | Property | What the runtime must do |
| --- | --- | --- |
| `lifecycle.qnt` | `guardHolds` | change lifecycle state only through `Kernel.apply` and `Kernel.orchestrate`; never unload a fiber some committed view names |
| | `atMostOnce` (mutant `blindRetry`) | answer a `Lost` receipt with `Reconcile`, never a resend; carry the episode in every released key (`ikey ^ "@" ^ episode`) |
| | `boundOnlyWhenRun` | unbind a fiber's keys when it leaves; recovery must not resurrect bindings of a fiber that is not installed |
| `outbox.qnt` | `atMostOnce` (mutants `journalInSnapshot`, `resendOnRestart`) | keep the outbox journal off every dataset a step snapshot rolls back; reconcile `Unknown` entries after an outbox restart |
| | `withinLimit` (`localLimits`) | shared limits are granted by the central service, not counted per runtime |
| | `onlyOutboxEgress` (`pfOpen`) | the runtime has no network path of its own |
| | `receiptsTrue` | a `Definite` receipt is recorded only for an effect that happened |
| `branches.qnt` | `onlyChosen`, `realOnlyToChosen` (`releaseEarly`, `noForkIds`) | the fork ID is part of every commit record and every intent record; only the chosen branch's intents are released |
| `gateway.qnt` | `atMostOnce`, `remoteViaOutbox` (`ungated`, `missingIsInternal`) | pass the calling emission's idempotency key in a hosted tool's request metadata; a missing annotation means emission |
| `caps.qnt` | `noWidening` (`uncoveredCaveats`, `unionCaveats`) | the runtime holds sturdy refs and may only attenuate them; it never mints authority |
| `admission.qnt` | `liveAdmitted`, `noLostUpdate`, `narrowingApproved` | run only revisions admission made live; a revision change is a recorded event, never a file swap |
| `assets.qnt` | `consistent`, `receiptBacked`, `atMostOnce` (`memoByCell`, `receiptsByCell`, `noMemo`) | memoise by (cell hash, input hashes); the emission memo is the receipt cache, keyed by the value sent; early cutoff |
| `revisions.qnt` | `ownRegistry`, `stateReadable`, `breakingApproved`, `singleWriter` (`globalRegistry`, `carryAlways`, `noApproval`, `insertFirst`) | each fiber evaluates with its own revision's registry; state crosses a revision only when the new type reads it; retire before insert |
| `routes.qnt` | `consistent`, `appendOnce`, `bounded`, `noStall` (`keepGuarded`, `appendEveryRun`, `noCap`, `keyByValue`) | withdraw a guarded asset when its guard stops holding; staleness is transitive; key an append by the scrutinee's computation (episode, computation), not its value revision; refuse an append to a full stream; a new input empties the streams |
| `frames.qnt` | `accConsistent`, `contiguous`, `cursorMatches`, `revsInOrder`, `accTyped`, `effectsOnce`, `endAfterAll`, `noRowLost`, `bufferBounded` | RFC 0005 rules 1 to 10: durable batch intervals, one atomic record per turn, intents keyed `(stream, generation, lo, index)`, cutover at a commit, a committed end turn, backpressure that loses no row |

Three further facts constrain every option:

- **The kernel is the control plane, not the data plane.** It models
  values as integers and has no conditionals (`compile.ml`'s header). The
  runtime therefore has two layers whatever else it chooses: the kernel
  deciding *which fiber may step*, and a value plane holding asset values,
  their revisions and freshness, stream contents, memo entries, guards and
  appends, and cell state, deciding *what a step computes*. The value
  plane's rules are the ones `assets.qnt`, `routes.qnt` and `frames.qnt`
  already state.
- **Cordis hosts are inertial** (Cordis §4.4, SYNTHESIS.md): a transition
  completes before the next begins. Any concurrency must preserve that for
  each fiber, and the global reads the calculus makes (`target`, `relied`
  read the whole registry) must see a consistent registry.
- **Turns are deterministic.** A turn is a CEL program over its inputs,
  state and earlier receipts (RFC 0002 "Cells"); RFC 0001 "Determinism"
  removes clocks, map-order and regex nondeterminism. What is not
  deterministic is the schedule, the arrival of inputs and receipts, and
  faults. The runtime's recovery argument rests on this split.

## The commit record

Every store option below persists the same logical record. It is fixed
here so the options can be compared on how they make it atomic, not on
what it holds. One record per kernel step that changes durable state;
for a turn (an `Iter` or `Finish`):

```
<commit
  {graph: #x"…" revision: #x"…" fork: 0       ; RFC 0002 hash; branches.qnt fork ID
   seq: 1041                                  ; position in this instance's log
   fiber: 7 episode: 3 turn: 1 life: reloading
   inputs: {draft: <rev 12 #x"…">}            ; the revisions and hashes it read
   receipts: [#x"5a1e…@3"]                    ; receipts consumed by this turn
   state: {attempts: 2}                       ; the cell's state after the turn
   assets: {verdict: <rev 13 #x"…">}          ; values bound, by hash (blobs apart)
   streams: {notes: <append #x"…" rev: 13>}   ; keyed appends (routes.qnt)
   frame: <batch {stream: s gen: 2 lo: 4 hi: 6}> ; RFC 0005 rules 1, 2, 5
   cutover: none                              ; or <carry r'> <drain r'> <restart r'>
   budget: {reserve: 4000 settle: []}         ; see Decision 4
   intents: [<intent llm.complete {key: #x"…@3" …}>]}>
```

Lifecycle-only steps (`Begin`, `Divert`, `Leave`, `Unload`), orchestration
inputs (`Insert`, `Retire`, `Receive`, `Reconcile`, `Restart`), graph
inputs and admissions are records of the same envelope with the fields
that apply. A graph input is durable before its first turn reads it, the
input analogue of RFC 0005 rule 1.

The record is the unit of atomicity: state, cursor, revision, intents,
episode and budget reservation land together or not at all
(`frames.qnt`'s `splitCommit` mutant is what happens otherwise).
**Release follows durability**: no intent leaves for the outbox before the
record holding it is durable. This is Remus's output commit, Cloudflare
Durable Objects' output gate, and DESIGN.md's "released only after its
turn commits".

## Decision 1: execution model and concurrency

How turns are scheduled and evaluated inside one runtime instance (one
agent's graphs, or one graph; see Decision 8).

### 1A. One deterministic event loop per instance, sans-IO core

A single thread owns the registry, the value plane and the log. The loop
takes the next event (a graph input, a receipt, an admission, a timer) or
the next enabled step, evaluates it, appends the record, and once the
record is durable releases its intents. All IO sits behind a small effect
seam (store, outbox channel, clock, picker); the core is a pure function
`step : core -> event -> core * command list`, the shape `Kernel` already
has.

- **Prior art:** Spritely Goblins and E's vats (a turn runs to completion
  on one event loop, transactionally); TigerBeetle and FoundationDB (a
  single-threaded deterministic core, the same code run by the simulator);
  Cloudflare Durable Objects (one thread per object, input and output
  gates); Redis; the "sans-IO" protocol pattern.
- **For:** inertial by construction; `target` and `relied` always see one
  registry; the simulator *is* the runtime with the oracle as picker and
  in-memory store and outbox, so `quintgo` conformance covers production
  code, not a twin; no locks. CEL turns are cost-bounded, so a turn cannot
  hog the loop beyond its estimate, and no preemption is needed. The core
  runs unchanged on a Unix process (Eio at the edge), under the simulator,
  and in a MirageOS unikernel, whose device layer is Lwt-based and which
  runs one domain anyway.
- **Against:** no parallelism inside an instance. A graph with many
  independent expensive assets waits for them in turn. Parallelism comes
  from many instances (DESIGN.md "Parallelism comes from many VMs").
- **Invariants:** every model's actions are already atomic steps of one
  global state, so the runtime's step is the model's step; refinement is
  direct.

### 1B. Eio fibers per cell, with a domain pool for evaluation

Each kernel fiber is an Eio fiber; independent assets evaluate in
parallel on `Eio.Domain_manager` (or a Moonpool pool); a mutex or a single
committer fiber serialises registry changes and log appends.

- **Prior art:** Eio's structured concurrency (one `Switch` per activation,
  as DESIGN.md "The runtime" sketches); Flink and Rama task parallelism.
- **For:** uses multicore inside one instance; Switches give LIFO release
  of acquisitions for free; matches the original sketch in DESIGN.md and
  OCAML-ON-BHYVE.md.
- **Against:** the registry becomes shared state: either one lock around
  every kernel call (which is 1A with extra threads) or a protocol that
  needs its own model. Schedules are nondeterministic in production, so
  determinism holds only in the simulator's Picos/Eio mock, and the code
  paths differ. Inside a unikernel there is one domain, so the parallelism
  is unavailable exactly where the deterministic tender runs.
- **Invariants:** `singleWriter` and `guardHolds` need the commit path
  serialised; parallel evaluation is safe only because CEL is pure.

### 1C. An actor per fiber, with mailboxes and supervision trees

Each fiber is an actor holding its own lifecycle state; providers and
consumers exchange messages (activate, deactivate, value changed); a
supervisor actor applies `Order.restart_plan` and `intensity`.

- **Prior art:** Erlang/OTP, Akka, Orleans' grains, Riot (OCaml 5 actors);
  ensemble's supervision, already ported in `kernel/order.ml`.
- **For:** crash containment per fiber; natural mapping of supervision;
  scales across hosts.
- **Against:** the Cordis calculus reads the whole registry (`relied`:
  does *any* installed fiber's committed view name this one?). In an actor
  system that is a distributed protocol with in-flight messages, so the
  guard (Theorem 70) becomes a new concurrent algorithm to model and
  prove, not a function call. Confluence across actors needs every
  message order considered.
- **Invariants:** `guardHolds` must be re-established by a message
  protocol (reference counting of consumers, as Cordis §6.2's broker
  suggests); a new model would be required before building it.

### 1D. An external durable-execution engine hosts the cells

Run each graph as a Temporal workflow, a Restate virtual object, or a
Golem Wasm component; the engine journals, retries and recovers; Cordis
supplies the cells and the policy.

- **Prior art:** Temporal (event history, deterministic replay, worker
  versioning), Restate (per-invocation journal, single writer per key),
  Golem (Wasm with an oplog), Flink or Rama for frames.
- **For:** years of operational hardening; tooling; durability not ours
  to get right.
- **Against:** no OCaml SDK in any of them; their versioning (patch
  markers, pinned versus auto-upgrade) and retry models differ from
  carry/drain/restart and from "reconcile, never blind retry" (activities
  are at-least-once); Uncertain is not first-class; the engine runs
  outside the deterministic boundary and outside the trap, so the
  simulator and Quint conformance no longer cover what runs; two sources
  of truth for revision state. Each is also a server to operate.
- **Invariants:** `atMostOnce` would rest on the engine's activity
  semantics plus our idempotency keys; `revsInOrder` and `singleWriter`
  on its versioning, which does not have carry with a typed accumulator.

### Comparison

| | 1A loop | 1B Eio + domains | 1C actors | 1D external engine |
| --- | --- | --- | --- | --- |
| Inertial, consistent registry | by construction | needs a serialised commit path | needs a protocol | engine's |
| Same code under the simulator | yes | partly (mock schedulers) | partly | no |
| Parallelism inside an instance | none (pure work may be offloaded) | yes | yes | yes |
| Runs in a single-domain unikernel | yes | degenerates to 1A | yes, one domain | no |
| New models needed | none beyond this RFC's | commit serialisation | guard protocol | engine semantics |

## Decision 2: the durable store

Where the commit records live, and how one record is made atomic. Two
stores are distinguished first, because their requirements differ:

- the **definitions store** holds graphs, cells, libraries, descriptor
  sets and policy by hash. It is written at admission, read at load,
  branched per candidate (DESIGN.md: Irmin; RFC 0002 "Deferred"). Its
  write rate is that of agent edits.
- the **run store** holds commit records: per-turn state, cursors,
  intents, receipts, memo entries. Its write rate is that of turns, and
  each write is on the critical path to releasing an emission.

A content-addressed store fits the first and is a poor fit for the second
(a Merkle commit per turn). The options below are for the run store; the
definitions store stays as DESIGN.md has it, and Dolt or Irmin appear only
as an option for it.

A further constraint comes from `outbox.qnt`: the outbox journal must not
be rolled back with a step snapshot (`journalInSnapshot`). The run store,
by contrast, *should* roll back with the VM, since rolling back a step is
the inverse of its internal effects. So the run store and the outbox
journal are two stores on two datasets, joined by a relay keyed by
deterministic idempotency keys (the transactional outbox pattern). After a
rollback the guest re-runs its turns, re-derives the same keys, and the
outbox journal answers from what it recorded (`outbox.qnt`'s
`rolledBackAfterSend` witness). This holds for every option below; the
relay is modelled in `handoff.qnt` (see "Models to write first").

### 2A. SQLite in WAL mode

One database file per instance. Tables for commit records, current state
per fiber, pending intents, receipts, memo, stream contents and cursors.
One SQLite transaction per record (or per group of records, see below),
`synchronous=FULL`.

- **Prior art:** SQLite's atomic commit and WAL; samizdat-agent-bootstrap's
  ADR-001 ("SQLite causal truth, OTel observation, chDB derived"); the
  transactional outbox pattern (an outbox table written in the business
  transaction); jolt-sim's own outbox example
  (`src/jolt/example/outbox/sqlite.clj`) and its SQLite simulation pack
  (`src/jolt/sim/sqlite.clj`).
- **For:** atomic multi-table commit for free; queryable by people and
  tools (pending intents, Uncertain fibers, budget per graph); mature
  bindings (`sqlite3-ocaml`); runs on Linux and FreeBSD; one writer is
  exactly what 1A has.
- **Against:** a C library and a POSIX VFS: it does not run on a Solo5
  block device without a custom VFS. `fsync` per turn costs latency
  (mitigated by group commit). State is stored as current rows, so the
  history of a fiber is a separate table we must keep.
- **Atomicity:** the SQLite transaction.

### 2B. An append-only framed log with snapshots

Each record is one frame in jolt-sim's crash-safe journal format
(`proofs/journal-wal/README.md`, `src/jolt/sim/journal.clj`): length,
sequence, previous-record CRC, payload, trailing CRC. The trailing CRC is
the commit point: a torn frame is not a record. Current state is an
in-memory fold of the log, checkpointed by periodic snapshots (themselves
records naming the sequence they cover); the log before the oldest
retained snapshot is compacted.

- **Prior art:** Kafka Streams' changelog topics; Rama's PState
  checkpoints; Feldera's per-step journal; LevelDB's log framing and
  SQLite's stop-at-first-invalid scan (the journal-wal README's own
  sources); Temporal's Continue-As-New as the compaction lesson.
- **For:** "one atomic record" (RFC 0005 rule 2) is literally one frame;
  one sequential write and one `fsync` per record or group; runs on a raw
  block device, so on Solo5 inside the narrow tender, where torn writes
  are a simulated fault; its framing and recovery scan are already
  bounded-proved (`proofs/journal-wal/`, SMT reference and buggy variants);
  the log is also the audit trail and the source of ITF traces. Payloads
  are Preserves binary, as DESIGN.md "Serialization" asks of journals.
- **Against:** we own a storage engine: snapshots, compaction, the index
  of pending intents and memo entries, rebuilt in memory at recovery.
  Ad-hoc queries need a tool (or a derived SQLite or chDB copy).
- **Atomicity:** the trailing CRC of one frame.

### 2C. LMDB

A memory-mapped copy-on-write B+tree. One write transaction per record;
readers get MVCC snapshots without blocking the writer.

- **Prior art:** LMDB (OpenLDAP); its OCaml binding `lmdb`.
- **For:** crash-safe without a recovery pass (the root page flip is the
  commit); readers (observability, a conformance projector) never block
  the loop; single writer matches 1A.
- **Against:** a fixed map size to manage; no SQL; a C library needing
  `mmap`, so no unikernel; history must again be kept explicitly; `fsync`
  per transaction unless `MDB_NOSYNC`, which gives up durability.
- **Atomicity:** the write transaction.

### 2D. A server database (Postgres)

Commit records and the outbox table in Postgres; the outbox relay reads
with `FOR UPDATE SKIP LOCKED` or logical decoding.

- **Prior art:** the transactional outbox as usually deployed; Debezium's
  outbox event router (CDC); Temporal's and Restate's own persistence
  options.
- **For:** standard operations, replication, point-in-time recovery;
  several runtime instances can share one database.
- **Against:** a server outside the VM and outside the snapshot, so a
  step rollback no longer reverts internal state (it would need
  per-branch schemas or savepoints, and `branches.qnt`'s fork IDs on every
  row); a network hop on every commit; outside the deterministic
  boundary, so the simulator would need its own model of Postgres.
- **Atomicity:** the database transaction.

### Group commit

With any option, records may be grouped into one `fsync`: a group is
durable all at once, and the intents of every record in it are released
after the group's `fsync`. Grouping changes latency, not meaning, because
each record stays atomic and release still follows durability. The
picker's batch size is policy, not code.

### Comparison

| | 2A SQLite | 2B framed log | 2C LMDB | 2D Postgres |
| --- | --- | --- | --- | --- |
| Rule 2 atomicity | transaction | one frame | transaction | transaction |
| Rolls back with a step snapshot | yes (on the VM's dataset) | yes | yes | no |
| Runs in a Solo5 unikernel | no (VFS needed) | yes (block device) | no | no (network) |
| Under the narrow tender's torn writes | no | yes | no | no |
| Queryable | SQL | needs a tool | key-value | SQL |
| Engine we own | none | log, snapshots, compaction | none | none |
| Already proved here | no | framing and recovery (`proofs/journal-wal`) | no | no |

## Decision 3: effect execution, outbox delivery and receipts

What happens to an intent after its record is durable, and how its
receipt comes back. Common to all options: an intent is checked against
its cell's effect row and schema, carries a deterministic key
(`(graph, revision, fiber, turn, episode)`, or `(stream, generation, lo,
index)` for a batch), names a capability rather than holding a
credential, and its receipt (`Definite`, `Failed`, `Lost`) is itself a
durable record before the fiber's next turn reads it. A receipt applied
twice (a redelivery) must be recognised by its key and applied once.

### 3A. In-process executors

Effect handlers (an HTTP client, the model client, a tool runner) run in
the runtime process, as Eio fibers on the loop's edge.

- **Prior art:** Temporal activities in the same worker; jolt-sim's
  `flow_effect_session` publishing through a borrowed worker service.
- **For:** simplest; lowest latency; ideal for the simulator, where the
  "executor" is the replay cassette and the oracle chooses each receipt.
- **Against:** credentials sit in the runtime; the runtime has network
  access, which breaks `onlyOutboxEgress` (`pfOpen`); the journal is the
  run store, so a step rollback forgets sent intents (`journalInSnapshot`).
  Acceptable only where there is no snapshot and no adversary: tests and
  development.

### 3B. An outbox process per agent, outside the snapshot (DESIGN.md's choice)

The runtime relays committed intents over a frame channel (virtio-console
on the platform, a Unix socket off it) to the agent's outbox jail. The
outbox journals each intent by key, asks the central limiter for a token,
checks the capability (`caps.qnt`) and the Cedar decision, sends, and
journals the receipt; MCP and A2A calls go through the gateway with the
calling key in the request metadata (`gateway.qnt`). Receipts come back
over the same channel and are deduplicated by key on both sides.

- **Prior art:** the transactional outbox with a relay; Flink's two-phase
  sinks (staged intent, then commit); `outbox.qnt`, `gateway.qnt`,
  `branches.qnt` as already checked.
- **For:** every placement property already modelled holds by
  construction; credentials and egress live in one jail per agent; branches
  share one outbox, which holds back all but the chosen branch.
- **Against:** a second process and a channel protocol to build and model
  (the relay); a round trip per emission; the outbox journal has its own
  store (SQLite fits there: it is in a POSIX jail and benefits from
  queries).

### 3C. A central outbox daemon for every agent

One daemon next to `pf` holds every agent's journal and credentials.

- **Prior art:** a shared egress proxy; DESIGN.md "Where the outbox runs",
  row 5.
- **For:** one exit point, shared connections, global deduplication.
- **Against:** already rejected in DESIGN.md: every credential in one
  place, a single point of failure, noisy neighbours, awkward per-agent
  restore. Listed for completeness.

### 3D. Split by effect class

Internal effects and acquisitions run in-process (or in child jails whose
clones roll back, DESIGN.md "Hosting"); only emissions go to the outbox
jail (3B). `route.append` is an acquisition handled internally (RFC 0002
"Compilation to the kernel").

- **For:** most agent work (builds, tests, file edits in a child jail)
  avoids the outbox round trip, as DESIGN.md intends; the outbox carries
  only what crosses `pf`.
- **Against:** classification must be right; `gateway.qnt` showed it is a
  hint, and the backstop (a hosted tool's own egress gated by the calling
  key) must be in place before relying on it.

Whichever is chosen, **retry rules come from the intent kind**: idempotent
kinds may resend under the same key; others are reconciled. A `Lost`
receipt puts the fiber in Uncertain, which the runtime answers only with a
reconcile read and a `Reconcile` orchestration (`lifecycle.qnt`'s
`blindRetry`). A reconcile deadline is policy, after which a person
decides (DESIGN.md "Risks"). Revision 2 adds a class that does not reconcile.

### Observational emissions (revision 2)

"Reconcile, never blind retry" assumes the far side can answer "did key K
happen?". A model completion or a read-only tool call cannot, and need
not: sending it again has no outside effect but spend. Such an intent kind
is **observational**:

- **A lost receipt does not make the fiber Uncertain.** The intent is sent
  again as a new attempt, under the same intent key and a new attempt
  number, each attempt under its own reservation of its worst-case cost
  (4E). Attempts per intent are bounded by policy; when they run out the
  fiber fails, since nothing is uncertain.
- **An abandoned attempt still costs.** Its reservation is held until its
  late answer settles it at its actual cost, or it expires and is charged
  at the worst case; it is never refunded (`refundLost`). Its answer is
  never applied: one answer per intent, the one the commit record names
  (`applyLate`). Replay uses the recorded answer, so the retry's different
  completion does not make the run nondeterministic.
- **The class is policy's, not the cell's.** It is a property of the
  intent kind, granted in policy (the gates and Cedar), never chosen by the
  cell that emits it (`misclassify`: an effectful kind treated as
  observational takes effect twice). A call is observational only if the
  provider keeps no state the next turn depends on: a stateless completion
  is; a completion against a provider-side session (a stored response, a
  conversation thread, a server that snapshots and resumes sessions, as
  halo-flash does) is not, unless its session is itself keyed by the
  intent. A tool call is observational only if its own egress is gated by
  the calling key (`gateway.qnt`'s backstop), since its annotations are a
  hint.

`observe.qnt` checks it: `effectOnce`, `receiptOnce`, `withinBudget`,
`accounted` (real spend never exceeds settled plus reserved) and
`obsNeverUncertain`, with four mutants (`misclassify`, `refundLost`,
`noReserve`, `applyLate`) and `oneClass`, revision 1's single rule, which
fails `obsNeverUncertain`. It is in the model gate, with Apalache to depth 8.

## Decision 4: scheduling, fairness, and budgets

### Scheduling within an instance

Which enabled step the loop takes next. Confluence (Cordis Theorem 80,
`:sampled` in `cordis/test`) says the quiescent state does not depend on
the order when shared operations commute, so the choice is about latency,
wasted work and fairness, not correctness. Options:

- **4A. FIFO over enabled steps**, ties broken by fiber ID. Simplest;
  deterministic. Can recompute an asset twice when two of its inputs
  change in turn (a glitch, then the fix).
- **4B. By height**: take the enabled step of lowest topological height
  first, so an asset recomputes once after all its stale inputs settle.
  Prior art: Jane Street Incremental's recompute heap ordered by height;
  Salsa's revision-based early cutoff; `assets.qnt`'s `cutoffSaved`
  witness shows what early cutoff saves. Receipts and inputs are events,
  and are taken before steps of greater height.
- **4C. Oracle-driven** (the simulator's picker), for testing only; it
  is how every other order is shown to reach the same rest.

### Fairness across graphs and tenants

When one instance hosts several graphs (Decision 8), the loop also picks
among graphs. Options: round robin by turn; **deficit round robin by
cost units** (Shreedhar and Varghese), where each graph earns a quantum of
CEL cost per round and a turn runs when its estimate fits the deficit;
or strict priority. Strict priority can starve; DRR bounds the wait of a
ready graph by the quanta of the others, which is checkable as an
invariant (a bounded-wait counter) since Quint does not check liveness.

**Progress.** `schedule.qnt` is written and gated (below), and the
scheduler is `Sched` in the engine library, sans-IO like the core: it holds
each graph's deficit and which graph it is on, and the host says which
graphs have work and what each one's next step costs (`Engine.step_cost`:
a turn its estimate's max, which admission bounded; any other step one
unit). A graph earns a quantum when the loop reaches it with work, runs
while its deficit covers its next step, keeps the deficit when it does
not, and keeps none when it has no work. A graph with work then waits at
most `ceil(max_cost / quantum) * (graphs - 1) * (quantum + max_cost - 1)`
picks (`Sched.bound`); with each graph's cost fixed, as in the model, the
wait is the model's `BOUND`. `engine/test/sched_test.ml` checks the bound
and the deficits' range over drawn work and costs, and runs two to four
review loops under one scheduler with the host's events (syncs, sends,
lost and duplicated receipts) interleaved at drawn points: each rests
where it would alone, within the bound. Planted faults it catches: a
scheduler that starts every pick from graph 0, and one that keeps an idle
graph's deficit.

### Cost and budgets at run time

The static estimate (RFC 0001 "Cost") bounds each turn at admission. At
run time there are three distinct quantities:

| Quantity | Bound | Enforced by |
| --- | --- | --- |
| A turn's CEL cost | its estimate's max, which admission checked against the budget | `Eval.run ~limit`: the limit is the estimate. Exceeding it is an estimator soundness bug: the turn is discarded, the fiber `Failed`, and the case reported (RFC 0001's Hegel property should have caught it) |
| Input sizes | the parameters' `<max N>` bounds the estimate assumed | checked at the cell's boundary before the turn runs; a violation is a typed refusal, never a truncation (RFC 0002 "Types") |
| Spend: model tokens, money, calls, wall time | per graph, per agent, per period, in policy | reservations in the commit record, settled by receipts (below) |

Options for spend:

- **4D. Static only.** Admission multiplies the estimates by an expected
  number of turns. Cheap; wrong for feedback loops, whose turn count is
  bounded only by stream capacities, and for model calls, whose cost is in
  the receipt.
- **4E. Reserve on commit, settle on receipt.** A turn that emits an
  intent reserves its worst case (`max_tokens`, a price table) in the same
  record as the intent; the receipt settles to the actual figure and
  releases the rest. A `Lost` receipt keeps the reservation until
  reconcile. A turn whose reservation does not fit is not run, and the
  fiber waits (or the graph is paused), which is a definite "not
  emitted". Prior art: cloud billing holds; Rama's migration work charged
  per batch (RFC 0005 rule 6).
- **4F. Central token buckets only.** The limiter of `outbox.qnt` grants
  each emission a token against shared limits. Already modelled for
  counts; it knows nothing of per-graph budgets or receipts' actual cost.

4E and 4F compose: the runtime reserves against its graph's budget at
commit; the outbox asks the limiter for the shared token at release. The
atomicity matters: a reservation written after the intent's record, or a
refund on `Lost` that later turns out to have taken effect, overspends.
`budget.qnt` (below) checks this.

## Decision 5: recovery and replay

What a restarted runtime reads, and what it re-executes.

### 5A. Snapshot plus commit log (state-based)

Recovery loads the latest snapshot, folds the records after it, and
resumes. A turn computed but not committed is simply re-run: CEL is pure,
so it yields the same record, and its intents carry the same keys, so the
outbox deduplicates any that the relay had already passed on.

- **Prior art:** Kafka Streams (changelog, offsets), Rama microbatches,
  Flink checkpoints, Spark's `offsets/` and `commits/` logs.
- **For:** recovery time bounded by the snapshot interval; does not need
  old code to be loadable, only old state to be readable
  (`stateReadable`).
- **Against:** state is trusted as written: a nondeterminism bug that
  produced a wrong record is not detected by recovery.

### 5B. Input journal with deterministic replay (event-sourced)

Persist only inputs: graph inputs, receipts, admissions, reconcile
outcomes, and the picker's choices. Recovery re-executes every turn from
the start (or a snapshot) against recorded inputs, with recorded receipts
answering every intent.

- **Prior art:** Temporal's event history, Restate's journal, Golem's
  oplog; jolt-sim's "replay with zero world calls" (JS1).
- **For:** smallest log; any state is reconstructible at any point;
  content-addressed definitions make "the exact code of revision r" always
  available, which is Temporal's hardest problem.
- **Against:** recovery time grows with history (Temporal's 51,200-event
  limit and Continue-As-New); every revision's code must stay loadable as
  long as its history; replay must reproduce the schedule (record the
  picker) or rely on confluence, which holds only at rest and only for
  commuting operations.

### 5C. Hybrid: records are truth, replay is an audit

Recover as 5A. Additionally, each record names the hashes of what its
turn read (inputs, state, receipts), so any record can be re-executed
offline and compared: a **replay audit** that reports nondeterminism the
way Temporal's non-determinism error does, without being on the recovery
path. The same records project to ITF traces (Decision 7).

### What survives a crash, and what does not

Independent of the option chosen:

- **Durable:** each fiber's life (including Uncertain and its pending
  intent), episode, committed view, state, asset revisions, stream
  contents and answered appends, frame cursors and generations, budget
  reservations. An Uncertain fiber is never recovered as anything else;
  recovering it as Inactive and re-running would emit again.
- **The episode** is incremented in the record of `Begin`, which is
  durable before the episode's first intent is released. An episode held
  only in memory lets a restart reuse keys (the bug the simulator found,
  `cordis/README.md` "What the simulator found"), or, after a crash,
  reuse them in the other direction.
- **Acquisitions split in two.** Process-scoped handles (file
  descriptors, connections, Eio Switches) die with the process; their
  inverses need not run. Durable acquisitions (a child jail, a ZFS clone,
  a lease held elsewhere) outlive it; they are recorded when acquired and
  swept at recovery by the level above (DESIGN.md "Hosting": each level's
  inverse is the fallback for the level inside it). The kernel's `acc`
  is rebuilt from the durable ones only.
- **In-flight intents** are found from the records: committed and not
  receipted. The relay re-offers them under their keys; the outbox
  answers each from its journal.

## Decision 6: cutover and admission at run time

Admission (RFC 0002 "Admission", `admission.qnt`) decides *whether* a
revision goes live; this decision is *how* the running instance moves to
it. The fixed points: retire before insert (`singleWriter`), cutover only
at a commit (`revsInOrder`), state carried only when readable
(`stateReadable`), and the per-cell state plan admission already returns
(`Carry` or `Migrate`, RFC 0004), extended by RFC 0005 rule 5's carry,
drain and restart for frame consumers.

### 6A. Stop the world at rest

Stop taking new inputs, run to quiet, wait for every receipt and every
reconcile, then retire every old fiber, apply the state plan, insert the
new ones. The cutover is one record.

- **Prior art:** Flink's stop-with-savepoint and restart; Kafka Streams'
  new `application.id` for incompatible topologies.
- **For:** simplest to reason about; the "cold start of the new revision"
  claim of RFC 0002 holds literally, except for carried accumulators.
- **Against:** an Uncertain fiber blocks the whole graph until reconciled;
  a long frame blocks it until drained; inputs queue meanwhile.

### 6B. Rolling cutover in dependency order, at commit boundaries

Per fiber, at its next commit boundary: consumers retire first and
providers last (Cordis's ordering theorem gives this order already, since
a provider cannot unload while relied on), then new providers are
inserted before new consumers activate. Each stateful cell follows its
plan: carry, migrate (reset until migration cells exist), or, for frame
consumers, carry, drain (the old fiber finishes this frame; the new one
takes the next) or restart (new generation). A fiber with an awaited
receipt or an Uncertain intent is not unloaded until it is answered
(`Kernel.next` already gives no rule to a `Reloading` fiber with
`awaiting = Some _`, and none to `Uncertain`), so a receipt is always
applied under the revision recorded in the intent's record.

- **Prior art:** Rama's handover at checkpoint; Temporal's worker
  versioning (pinned runs drain; auto-upgrade moves at the next task);
  Dataflow's replacement job with drain as the alternative.
- **For:** only the cells that changed move; unchanged cells (same hash)
  keep running, and their memos stay valid; cutover pause is per fiber.
- **Against:** a mixed state exists while the cutover proceeds, so the
  cutover itself must be durable: a `cutover-begin` record naming the
  plan and a `cutover-end` record, so that recovery mid-cutover completes
  it (or rolls it back) instead of resuming a mixture nobody admitted.

### 6C. Shadow then switch (blue/green with soak)

Run the candidate in a shadow instance that reads the live inputs (or a
branched snapshot of them), with every emission answered from the replay
cassette (DESIGN.md gate 6, soak). When the soak passes, switch by 6A or
6B. The shadow never writes the live run store and never releases an
intent.

- **Prior art:** Dataflow's `graph_validate_only` and "a rejected
  replacement leaves the old job running"; the mutation protocol's soak in
  samizdat; `branches.qnt` (only the chosen branch's emissions are
  released).
- **For:** gate 6 needs a shadow anyway; a candidate that fails leaves the
  live revision untouched.
- **Against:** twice the evaluation during soak; the shadow's inputs must
  be a faithful copy, and the shadow's fork ID must never be chosen for
  release.

These compose rather than compete: 6C is how the soak gate runs, 6B is
the default switch, and 6A is the fallback when the plan or policy asks
for it (a breaking change to a shared type, or an operator's choice).

## Decision 7: observability and conformance

The simulator already writes ITF traces that `quintgo validate` replays
against `lifecycle.qnt` (`cordis/sim/itf.ml`, `quint/check.sh`
"conformance"). The question is how much of that reaches the runtime.

- **7A. Offline conformance from the commit log.** The commit log is
  projected, per model, onto that model's variables (a refinement
  mapping: lifecycle states and `applied` for `lifecycle.qnt`; journal
  entries for `outbox.qnt`; cursor, log and observed keys for
  `frames.qnt`; asset revisions for `routes.qnt`) and replayed by
  `quintgo`. A passing run earns `:monitored`. Limits carried over from
  DESIGN.md: per actor, after the fact, 4 to 8 seconds per trace, so
  sampled.
- **7B. Online assertions.** The models' invariants, written once as
  OCaml predicates over the core's state, checked after every record with
  the Antithesis vocabulary (`Assertions.always`, `sometimes`), in the
  simulator and, cheaply, in production. A violation stops the instance
  before release (the record is durable; its intents are not released).
- **7C. Model-based testing.** The model chooses the next action
  (`quintgo`'s MBT mode, as edgepublish's write gate does), the runtime
  under the simulator executes it, and states are compared after each
  step. Finds divergences the runtime's own scheduler would not choose.
- **7D. Telemetry as derived observation.** OpenTelemetry spans per turn
  and per intent, derived from the records, never the source of truth
  (samizdat-agent-bootstrap ADR-001's split).

All four need the same thing from Decisions 1 and 2: a log whose records
are the model's steps. With 1A and 2B the log *is* the trace; with 2A it
is a table to export.

## Decision 8: deployment shape

- **8A. One process per agent, all its graphs.** One loop, one run store,
  DRR across graphs (Decision 4). Matches "a VM per agent"
  (DESIGN.md "Hosting"). A crash takes every graph of the agent down
  together, and recovery brings them back together.
- **8B. One process per graph instance.** Crash containment per graph; a
  store per graph; cutover by starting a new process beside the old (6C
  for free). Costs a process and a channel to the outbox per graph, and
  cross-graph assets need IPC.
- **8C. A MirageOS unikernel per agent VM**, the runtime inside, the
  outbox jail outside, intent and receipt frames over virtio-console
  (DESIGN.md "Hosting", OCAML-ON-BHYVE.md "Into the hypervisor"). The
  narrow deterministic tender then makes the whole runtime, not only the
  cells, deterministic under simulation: block writes (the run store),
  console frames (intents and receipts) and `poll` (time) are its only
  exits. Single domain; Lwt at the device layer; no POSIX, so 2A and 2C
  do not run there.
- **8D. A library linked into a host** (the simulator, a test, an
  embedding harness), with the store and outbox supplied by the host.
  This is how the simulator and the Hegel properties run it.

8A, 8C and 8D are the same core under three hosts if the core is sans-IO
(1A); 8B is 8A with one graph each.

## Recommendation

The combination below is a recommendation for review, not a decision.

| Decision | Recommended | Why |
| --- | --- | --- |
| 1 Execution | **1A**, one deterministic loop per instance with a sans-IO core; pure evaluation may be offloaded to a domain pool when one exists, but every commit happens on the loop | Inertial by construction; the simulator runs the production core; runs in a single-domain unikernel; needs no model beyond those proposed here. Combiner frames (RFC 0005 rule 11) are the one case that earns parallel evaluation, and their merge is a commit like any other |
| 2 Store | **2A, SQLite, behind the `Store` signature** for the run store until the unikernel host exists, then **2B**, the framed log; **SQLite for the outbox journal** in the outbox jail; definitions in files, as now | Rule 2's atomic record is one transaction; nothing to build before the slice runs; the commit-record table is the ITF source until the log is. 2B earns its engine when there is a Solo5 block device and a narrow tender to fault it (revision 2) |
| 3 Effects | **3B with 3D's split**: emissions through a per-agent outbox jail; internal effects and acquisitions in-process or in child jails; **3A only as the simulator's cassette** behind the same interface | Every placement model holds by construction; one interface, two implementations |
| 4 Scheduling and budgets | **4B** (height order) within a graph; **DRR by cost units** across graphs; runtime CEL limit equal to the estimate; **4E reservations** composed with **4F's** shared limiter | Fewer recomputes; bounded wait; spend that is atomic with the intent that incurs it |
| 5 Recovery | **5C**: snapshot plus log, with a replay audit off the recovery path | Bounded recovery time; nondeterminism is still caught |
| 6 Cutover | **6C** for soak, **6B** to switch, **6A** as fallback, with durable `cutover-begin`/`cutover-end` records | Only changed cells move; failed candidates leave the live revision alone; recovery never resumes an unadmitted mixture |
| 7 Conformance | **7A + 7B + 7C**, with 7D derived | The log is the trace; assertions stop release on a violation; MBT finds what the scheduler would not choose |
| 8 Deployment | **8D now, 8A next as a yard client on FreeBSD, 8C on the platform** | One core, three hosts, in the order the build can test them; 8A uses jails, ZFS snapshots and yard's service-jail lifecycle, which exist now (revision 2) |

The reasoning that ties these together: the strongest evidence this
project produces is "the code that runs is the code the simulator and the
models checked". Every recommended option keeps that true. 1A makes the
production scheduler one of the simulator's schedules; 2B, when the
unikernel host arrives, makes the store something the narrow tender can
fault; 3B makes the outbox the one the
models placed; 5C and 7A make every production run a candidate
`:monitored` trace. The options turned down each break that chain at one
link: 1B and 1C by nondeterministic interleavings in production, 1D and
2D by putting state outside the deterministic boundary.

Revision 1 made the framed log the default and named owning its engine
(snapshots, compaction, in-memory indexes) as the recommendation's main
cost. Revision 2 defers that cost until 8C makes it pay: SQLite runs the
slice, behind a `Store` signature narrow enough that the log can replace
it, and the conformance work does not change when it does.

## Staged build plan

Revision 2 builds a vertical slice before anything wide: one graph, end to
end, through every promise the design makes, before cutover, frames, or
further importers. RFC 0005 stays unscheduled, and the OpenAPI and AsyncAPI
importers wait for the slice.

0. **Models.** `observe.qnt` is written and gated; `engine.qnt` and
   `handoff.qnt` next (the slice needs both), then `budget.qnt`; `cutover`
   and `schedule` before stage 5. Implement nothing they do not cover.
1. **The slice** (8D, then 8A):
   - the sans-IO core over the kernel, with the value plane as a separate
     pure module (open question 1): `step : core -> event -> core *
     command list`, commands `Persist`, `Release`, `Read` (reconcile),
     `Sweep`; real CEL turns (`Eval.run ~limit`), asset values, memo,
     routes and capacities;
   - the run store on SQLite behind `Store`, one transaction per commit
     record, `ctx.now` in the record;
   - a loopback outbox (the cassette) with observational and effectful
     intent kinds;
   - the review loop (`graph/examples/review.cg`) running to rest, with
     its `llm.complete` intent observational and its `repo.read`
     reconciled;
   - crash injection at every commit, recovery, and conformance of the
     run's traces against `lifecycle.qnt`, `engine.qnt` and `observe.qnt`;
     the existing `cordis/sim` becomes a host of this core.
   Hegel: compiled graphs reach the same rest as a from-scratch
   evaluation, under random schedules and crashes.
   **Progress.** The core is `cordis/engine` (`Engine`): `ready`,
   `perform`, `handle`, and the commands `Persist`, `Release`, `Reconcile`.
   Its test (`engine/test/review_loop.ml`) runs the checked
   `review.cg` against a loopback host: a store synced when the host
   chooses, an outbox that journals effectful keys and answers again from
   the journal, lost and duplicated receipts, an input changed part way
   through. Hegel checks that any such run rests where a fault-free run of
   the final input does, that `noStall` holds, that effectful keys take
   effect once, and that no intent leaves before its record is durable;
   seven planted engine bugs are each caught. Running it found three
   faults, each now fixed and covered:
   - an append keyed by the scrutinee's value revision stops a loop whose
     critic repeats itself (RFC 0002 amended; `routes.qnt`'s `noStall`);
   - receipts matched by key alone let a duplicated loss notice from one
     attempt advance another: a receipt carries its attempt and applies
     only to the awaited one, as `observe.qnt` already said;
   - staleness was direct only, so after an input change a verdict about
     the old draft could append into the new episode; it is transitive, as
     in `routes.qnt`.
   Crash and recovery followed: `Store` (in memory, and SQLite with one
   transaction per sync), `Codec` (records as versioned JSON, every value
   exact), and `Engine.recover`, which rebuilds memory from the durable
   records, releases again every intent a durable record still awaits, and
   reconciles every Uncertain fiber. The property now crashes at drawn
   steps, dropping the engine, every unsynced record and every message not
   yet taken by the outbox; an example runs the loop on an SQLite file
   with two crashes. The host keeps an input until it is durable and sends
   it again after a crash, as any sender to the system must. Planted
   recovery faults are caught (no re-release, no reconcile, a lossy
   double); one is equivalent here: forgetting answered appends cannot
   duplicate a note in a loop, since an append makes its scrutinee stale,
   and needs a graph whose route appends to a stream its scrutinee does
   not read.

   Conformance closes the stage. `engine.qnt` and `observe.qnt` check the
   protocol over a small fixed world, which the review loop's
   recomputations do not map onto step for step, so `quint/runtime.qnt`
   states the same rules over the engine's own alphabet, with parameters:
   `persist`, `emit`, `retry`, `giveUp`, `doubt`, `refetch`, `resume`,
   `sync`, `release`, `ask`, `crash`, `outside`. Its guards are the output
   gate, receipts by key and attempt, retry only for observational kinds
   and doubt only for effectful ones, and recovery to the durable state;
   it has its own witnesses and mutants in the gate. The loopback host
   (`engine/test/loopback.ml`) projects every host step onto those actions
   (a step can be several: a sync and the releases it lets out, a crash and
   what recovery asks), with the receipt's own key and attempt, and
   `engine/test/conform.exe` writes seeded runs under faults, crashes and an
   input change as ITF. `quintgo validate` replays them: twelve runs of the
   engine conform; an engine without the output gate fails on every run,
   and one that matches receipts by key alone fails on the run that loses a
   receipt and then hears from the old attempt. The model gate runs twelve
   (`runs conformance-engine`).
   `lifecycle.qnt`'s kernel steps are not replayed: within one revision the
   engine does not step the kernel (its control plane, from stage 5).
2. **A real model behind the outbox.** The outbox process with its
   SQLite journal, the relay (`handoff.qnt`), reservations and the
   limiter (`budget.qnt`), and a completion endpoint (halo-flash's
   OpenAI-compatible server) as the first observational effect.

   **Progress.** `budget.qnt` is written and gated: a turn's record
   reserves its intent's worst case in the same write (`reserveAfterCommit`
   breaks `accounted` across a crash), a receipt settles once at its
   actual cost (`settleEveryDelivery`), an Uncertain intent holds its
   reservation (`refundOnLost`). The engine follows it: `create ?budget
   ?worst`, a turn ending in an intent is offered only while its
   reservation fits, the reservation rides in the turn's record, a receipt
   (which now carries its cost) settles it, an attempt abandoned for
   another is charged at its worst case. The review-loop host keeps the
   world's ledger and checks after every step that settled plus reserved
   stays within the budget and never below what the world charged; four
   planted budget faults are caught; a budget too small for the loop stops
   it within the budget, with nothing reserved. That check also found the
   outbox's rule for observational intents: after a crash the engine
   releases the awaited attempt again, so the outbox must journal each
   observational attempt by (key, attempt) and answer a repeat from the
   journal, or the world is charged twice for one reservation. The real
   outbox journals every key that way.

   The outbox is a process (`cordis/outbox`, `cordis-outbox`): one engine
   at a time connects over a Unix socket and speaks JSON lines (intent,
   reconcile; ack, receipt, lost, reconciled). It journals an intent in
   SQLite before running it (by key, or by key and attempt for an
   observational kind), acknowledges once journaled, runs it once, journals
   the receipt, and answers a repeat from the journal; a row cut off by a
   restart becomes unknown and is answered as lost. Executors:
   `llm.complete` against an OpenAI-compatible chat-completions endpoint
   (halo-flash's), costed by the tokens it reports, through a small
   HTTP/1.1 client; `repo.read` under a root, reconciled by reading again.
   `cordis-run` hosts the engine as a process: SQLite run store, the outbox
   socket, a receipt not in time taken as lost, recovery from the store at
   start, and two crash hooks for tests. `outbox/test/e2e.sh` runs the
   review loop that way against a stand-in completions server, crashing
   the runner over and over (after a few actions, and right after a
   receipt is applied but before it is synced) with one outbox serving
   every run and restarted once: the loop ships, the server answers exactly
   three completions, the journal holds three of each kind, and an engine
   that hangs up mid-answer does not take the outbox down. Planted faults
   it catches: an outbox that does not journal observational attempts
   (seven completions instead of three) and one that dies on a write to a
   dead engine. Building it found three faults, each fixed:
   - the runner read frames through a buffered channel and waited on the
     socket with `select`, which cannot see a frame already in the buffer,
     so after a restart it timed out holding a receipt, took it as lost, and
     paid for a second completion;
   - the outbox died of SIGPIPE when an engine crashed while it was
     answering;
   - and, the engine gone, it abandoned an intent it had journaled; it now
     runs it and journals the receipt for the engine's return.
   The central limiter followed (`cordis-limiter`, decision 4F), as
   `outbox.qnt` has it: a ledger of grants keyed by emission (agent and
   journal key) in SQLite, so an emission asked for again gets its grant
   back and never a second token, and a new one is granted only while the
   window's count is under the limit (one window forever is the model's
   LIMIT; fixed windows give a rate). An outbox with a limiter journals an
   intent as waiting, runs it once granted, and asks again for waiting
   tokens on every tick, across restarts; the engine is told the intent is
   deferred, a definite "not sent", which its runner does not take for a
   loss. `outbox/test/limiter.sh` shares one limiter between two agents
   whose loops need twelve emissions against a limit of ten (one held by a
   probe that asks for one emission three times): the first ships using
   exactly six grants through two crashes, the second is granted the three
   left and stops blocked by the limiter, and exactly nine emissions run.
   Planted faults it catches: a limiter that grants a repeated emission
   again, an outbox that runs without a grant, and limits counted per
   agent (`outbox.qnt`'s `localLimits`). The outbox's serve loop became
   select-based with its own line buffer, as the runner's had to.
   **The halo-flash run.** The review loop ran against halo-flash serving
   Qwen3.8-Flash-Next, in three phases with predictions written first.
   What held: across 17 runner kills and an outbox restart, 9 critic
   computations made exactly 9 completions (journal and server agree),
   the stream's cap brought a failing loop to rest, and the observational
   class and the stores' durability were as specified. What it found,
   and what was done:

   1. *Declared string bounds were not enforced at a route.* Notes of up
      to 748 characters entered `notes : string<max 500>`, whose bound
      the cost of `write` assumed. The checker now refuses an append whose
      value may break the stream's bounds (the estimator's shape of the
      value against the stream type's declared bounds, the case payload's
      own type included), and the engine holds values to their declared
      bounds where they cross: a cell's arguments as it starts, its result
      as it ends (either over: the computation fails, a typed refusal), and
      an appended value (refused as a full stream refuses, the reason in
      the record's `refused` fact). Nothing is truncated; a cell bounds its
      own output (`review.cg`'s critic now does).
   2. *A single-threaded outbox and the runner's timeout charged three
      worst cases for one request.* The runner took its own timeout for a
      lost receipt while the outbox, busy with the first call, had not
      read the later attempts. `quint/intake.qnt` states the fix: the
      outbox reads its intake whatever calls run (calls now run on
      threads, their ends journaled by the loop), and the runner sends an
      unacknowledged attempt again as the same attempt (nothing ran, so
      nothing is lost or charged) and waits on an acknowledged one, which
      only the outbox reports lost; mutants `timeoutAsLost` and `serial`.
   3. *The runner exited 0 with a failed computation.* Failures are
      checked before rest.
   4. *The outcome was not in the output.* The runner prints `outcome`
      (`rest`, `rest_unbound`, `failed`, `blocked`), `refused`, and the
      unbound assets; at rest with an asset a guard left unbound (not
      shipped) it exits 4.
   5. *`review.cg` could not converge with a real model.* Its prompt now
      instructs the critic, its notes are bounded where made, and its
      fold comments every line of a note.
   6. *A 120 s timeout cut long completions off.* The completion is
      streamed, so the client's timeout limits silence, not length
      (`--llm-idle`).
   7. *Every executor failure was a loss.* An HTTP 4xx other than 408 and
      429 is a definite refusal (a `Refused` receipt: the computation
      fails, its reservation released, nothing retried, the journal
      answering `refused` again); 429 and 503 are deferred with backoff
      (definitely not run); no response stays a loss.
   8. *`finish_reason` was dropped.* The receipt carries `finish`, and the
      critic approves only an answer that finished.
   9. *A response without usage cost nothing.* An unreported cost is
      charged the worst case.
   10. Noted, not changed: the limiter's fixed window allows bursts at its
       edges; Apalache checks `engine`, `budget` and `schedule` to depth 6
       only; rollback fidelity is still to be run on ZFS.

   A second run (phases A and B again) held: each shipped at the first
   computation with one completion and one journal row, the 69 s call
   waited on, not resent, and a crash between a receipt and its sync
   recovered without a second completion. It found that thinking spends
   `max_tokens` (3996 of 4000 to say LGTM), so a slightly longer think
   ends at `length`, which the critic rightly refuses: `Prompt` now says
   whether the model may think (`thinking`, sent as
   `chat_template_kwargs.enable_thinking`; the critic turns it off), and
   a `Completion` carries the reasoning tokens the server reports.

   halo-flash's scheduler keeps idempotency keys (its docs/SCHEDULER.md,
   section 7): a request whose `Idempotency-Key` has a finished answer
   replays it, one still running is adopted, and the caller's rule is to
   retry only with the same key. Every outbox call now carries one, so
   `llm.complete` can be run keyed (`cordis-run --keyed llm.complete`): an
   answer lost after it was generated is sent again and replayed, not
   generated again. The key is not the intent's key, which names a
   computation within one instance (`review_loop/verdict#1.1` in every run
   of the graph) and would let the server replay one run's answer into
   another: it is a digest of the outbox's `--agent`, the intent's key and
   its request, the same for every send of one intent. (The OpenAPI
   executor's keys had the same flaw, and take the same digest.) Under
   the observational class the key is harmless (a retry is replayed
   rather than regenerated); the keyed class relies on it, so it is for a
   server known to keep keys, and `--agent` should name the instance.
   `latency.sh` phase E: a server that keeps keys drops the first answer
   after generating it; the resend is replayed (four requests, three
   generations), and a key that varies by attempt, planted, generates
   four.

   A third run, keyed, on halo-flash's G3 build (its main does not keep
   keys, so a keyed class there would regenerate: keep `--observational`
   against main): the G3 build answered a resend of a stream dropped
   mid-answer with the whole answer from one generation, replayed a
   finished key, and adopted a call whose client had left; nine
   computations made nine generations through an outbox killed
   mid-decode. It found two things:

   - *A lost keyed send was still charged its worst case* (4096 for a
     105-token generation the resend replayed). `observe.qnt` now has a
     world that reports the one generation's usage on a replay, and a
     keyed intent's answer releases its other sends' reservations
     instead of charging them (witness `supersededReleased`; mutant
     `releaseObs`, the same release for observational attempts, each of
     which may have run, breaks `accounted`; `keyIgnored` now breaks it
     too, since the release rests on the key being kept). The engine
     holds a keyed intent's worst case once per send and releases them
     all when it is answered; the outbox marks the other sends' journal
     rows `superseded`.
   - *The review loop can ship code that does not compile*: the writer
     only folds notes in as comments, so whether a wrong patch ships
     rests on a sampled critic. The critic now asks for temperature 0
     and is told comment lines are notes, not fixes; the writer is
     documented as never changing the code, so a wrong patch should rest
     unshipped at the cap. A converging loop needs a writer that applies
     the notes, a completion of its own.

   A fourth run held both: a kill mid-decode now settled exactly what an
   unkilled run did (2493, the lost send `superseded` at cost 0), and a
   wrong patch rested unshipped at its cap through 17 crashes and an
   outbox restart. It found three more:

   - *A verdict on the last line was refused.* At temperature 0 the model
     reasoned first and ended with LGTM, which `startsWith` rejected, and
     the loop got through only because the notes changed its input. The
     critic now asks for the verdict on the first line and accepts LGTM
     alone on the first or the last non-empty line.
   - *"One key, one generation" holds only while the server holds the
     original*: running, adopted within its grace window (30 s on the G3
     build), or finished. A resend after the server stopped an unfinished
     original generates afresh, and the stopped part's tokens are in no
     reported usage, so settled can fall short of the server's work by up
     to one worst case per send so stopped. `observe.qnt` states the limit
     as a world, `graceExpiry`, in which the release breaks `accounted`.
     Closing it needs the server to say whether an answer was replayed,
     adopted or generated afresh, so the outbox can charge the lost sends
     of a fresh one; until then the release is a choice the RFC records.
   - *Retry-After was not used.* A 429 or 503 with Retry-After now waits
     that long before the intent is asked again (else the outbox backs off
     from 1 s, doubling to a minute).

   `latency.sh` gains phases F (a verdict on the last line approves at the
   first review) and G (a 429 with Retry-After: 2 is asked again 2 s
   later); a planted outbox that ignores Retry-After fails G.

   For the fifth: `graph/examples/review_fix.cg` is the loop with a
   writer that applies the notes (a completion asked for the whole revised
   patch; asked once with no notes too, since a cell's effects are not
   conditional), so a wrong patch can be fixed and the loop converge
   (`outbox/test/fix.sh`). `outbox/test/fanout.sh` runs N agents at once
   against one server, each with its own outbox, store and agent, and
   checks for every agent one answered completion per intent, no send
   left unknown, and settled equal to the journal's costs, counting the
   429s and 503s waited out; the end-to-end suite runs it with four agents
   against the stand-in, and it takes a real server's URL for a halo-flash
   run.

   The limit above is closed where the server says where an answer came
   from: halo-flash adds `Halo-Answer: replayed | adopted | generated`.
   The llm executor reads it, the outbox journals it with the receipt (a
   `source` column) and puts it on the receipt frame (`"answer"`), and the
   runner turns a generated answer into the engine's `Generated` receipt.
   For a keyed intent that receipt still releases every send's
   reservation, but settles the other sends at their worst case instead of
   nothing, and the outbox leaves them `unknown` rather than `superseded`.
   `observe.qnt` models it as `REPORTED` (`graceExpiryReported`):
   `accounted` holds again under grace expiry, with the witness
   `freshCharged`. `latency.sh` phase H runs a stand-in that keeps nothing
   of a dropped answer: the resend says `generated`, and the lost send is
   charged 4096.

   A fifth run (halo-flash main with G3, which keeps keys; `--keyed`)
   held: a right patch shipped at the first review, generated once;
   `review_fix.cg` fixed `let x = ` to `let x = 1` in four completions
   through nine crashes and an outbox restart, the server admitting
   exactly four; twelve agents at once against a pool of eight waited out
   sixteen 429s and settled every agent at its journal's costs; with the
   default 30 s grace a killed generation finished inside it and the
   resend was replayed. With a 1 s grace the resend was generated, and two
   things were left: the lost send was charged its worst case, 4096, where
   the server counted 25 tokens decoded before it stopped the send; and
   its journal row stayed `unknown`. And against a server that keeps no
   keys (`--scheduler off`) there is no `Halo-Answer` at all.

   So the rule is now the other way round: a keyed answer releases the
   other sends only when the world says it covers them (`replayed`,
   `adopted`); one generated afresh, or one the world says nothing of, is
   charged. A world that keeps no keys says nothing, so `accounted` now
   holds there too (`keyIgnored`; the old rule is the mutant
   `RELEASE_UNREPORTED`, which breaks it with that world and with grace
   expiry). halo-flash will report the tokens it discarded under a key on
   the fresh answer (`Halo-Discarded-Tokens`), and those, no more than the
   sends' worst cases, are the charge (`observe_report.qnt`'s
   `graceExpiryDiscarded`; reporting only the latest stopped send's breaks
   `accounted`, `underReport`). The outbox journals the count with the
   receipt and marks the lost sends `charged` rather than leaving them
   `unknown` (a send a replay covers is `superseded`, as before). `latency.sh` gains phases I (the stand-in reports 4
   discarded tokens: the lost send is charged 4) and J (a stand-in that
   keeps no keys and says nothing: the resend generates again and the lost
   send is charged 4096), and H's lost row is `charged`. The model's
   instances moved to `observe_modes.qnt` and `observe_report.qnt`, apart
   from the module, so that each file stays under Apalache's request size.

   The gate then found the release unsound under grace expiry even with
   `Halo-Answer`: a first send stopped past the grace window, a second
   generated afresh and its answer lost, and a third answered as a replay
   of the second: `replayed` releases both lost sends, and the first's
   stopped tokens are in no answer (`graceExpiryReported` breaks
   `accounted`, in 30,000 samples of 100 steps). The discarded count must
   therefore come on every answer under a key that has any, a replay or an
   adoption too, and be the key's total; the runtime charges it whatever
   the answer's source (`latency.sh` phase K: a forgotten first answer, a
   dropped second, a replay of it reporting 4: both lost sends charged 4).
   With the count on every answer, `accounted` holds (`graceExpiryDiscarded`,
   sampled at 100 steps and verified to 8); reporting only the latest
   stopped generation's breaks it (`underReport`).

   halo-flash 87cc596 sends the count so (the key's total, on every
   admitted keyed answer), and the 1 s-grace run confirmed it end to end:
   the resend `generated` with `Halo-Discarded-Tokens: 25`, the lost send
   `charged`, settled 2572 = journal 2547 + 25. Its answer to when a key is
   forgotten (after `replay_ttl_s`, 600 s, unused, or evicted past 4096
   keys) opens the next hole: the world then forgets the generations, the
   discarded count and the sends of that key, and a later send starts it
   afresh, so neither header covers a send admitted before it forgot: a
   later replay reporting 0 releases it (`observe_forget.qnt`,
   `forgetDiscarded` breaks `accounted`). A time rule on our side cannot
   close it, eviction being by load. What does (`forgetAttempts`, sampled
   at 100 steps and verified to 8, witnesses `exactCharged` and
   `forgotThenDone`): every answer also says how many sends the world has
   admitted under the key since it last forgot it, and each send of ours
   it does not count is charged its worst case on top of the discarded
   count, the total capped at the sends' worst cases. Asked of halo-flash;
   until it is sent, the runtime keeps the round-5 rule, with this hole.

   `outbox/test/latency.sh` replays the run's conditions against a
   stand-in: calls slower than the runner's timeout with long multi-line
   answers (one completion per computation, none charged as lost, notes
   within their bound, every line commented), answers cut off by length
   (never approved; rests unshipped, exit 4), no usage (charged the worst
   case) and a 400 (refused once, exit 2). The runner's old timeout rule,
   planted back, fails it with the run's own numbers (settled 12289, the
   critic failed).
3. **The run store, hardened.** Recovery under fault injection; the
   replay audit (5C); and a **native rollback-fidelity check**: on the
   host's own filesystem (ZFS on FreeBSD), snapshot the run store's
   dataset, write, roll back, and compare the store by its files' content
   hashes and by commit record, including across a remount. Compare
   logical content, not `written@snap`: yard's SR1 found mount-time
   intent-log housekeeping dirties a dataset physically (8 KiB) with no
   logical change, so a physical measure gives false positives, and it
   refuses resumes on it only because a workspace's drift is a different
   question. `handoff.qnt` assumes rollback is exact; this check is what
   makes the assumption evidence, not hope.

   **Progress.** The replay audit is `Engine.audit`: every turn the
   durable records show is evaluated again from what the earlier record
   held and must give what was recorded (the same request, the same result,
   a failure that fails again). The review-loop property audits every run,
   crashes included; an example reads one run's records against a graph
   whose critic asks for a different `max_tokens` and finds every
   completion request; `cordis-run --audit` refuses to resume records the
   graph did not write, since that is a cutover, admission's business, and
   the end-to-end test checks the refusal. `cordis-run --dump-records`
   prints the store's logical content. `cordis/ops/rollback-fidelity.sh`
   is the native check, to run as root on the FreeBSD host: it snapshots a
   fresh dataset holding the run store, runs on, rolls back and remounts,
   and compares the record dumps (not `written@`), then resumes from the
   rolled-back store and requires the loop to ship with exactly three
   completions, the outbox's journal (outside the dataset) answering what
   the rollback re-released. Its predictions are stated in its header. It
   has been dry-run here against a stand-in `zfs` (a dataset is a
   directory; it passes, and fails as it should when the stand-in's
   rollback does nothing); it has not yet run on ZFS.
4. **Hosting on yard.** 8A on FreeBSD as a yard client: the agent's VM or
   jail, and the outbox jail as a yard service jail (SR1: a service spec,
   suspend and resume by ZFS snapshot, refusal on workspace drift with an
   explicit force, liveness probes, per-project keys). The outbox
   journal's dataset must stay out of the agent's step snapshots
   (`journalInSnapshot`), which yard's layout of one dataset per workspace
   allows; its key-service and crypto-shred decisions (SR1's open
   decision 1) bear on where the journal's dataset sits. Caps and Cedar
   at the trap.
5. **Cutover.** Shadow instances with cassette receipts; rolling cutover
   with durable begin and end records; state plans (carry, migrate by
   reset); the soak gate wired into admission.

   **Progress.** `cutover.qnt` is written and gated (Apalache to depth 8,
   about 25 s): a candidate soaked in a shadow that emits nothing, then a
   durable begin, assets switched one at a time at commit boundaries,
   providers first, the old fiber retired before the new one, and a
   durable end; a crash between begin and end finishes the cutover. Six
   properties, four witnesses, five mutants. The engine follows it for
   candidates that keep the graph's wiring (a rewiring is refused, and
   goes through a restart): `Engine.admit` writes the begin record;
   `Switch a` is offered when the asset's fiber is idle and every asset it
   reads has switched; an unchanged cell (by its definition's hash) keeps
   its value and memo, a changed one carries its state (or resets it, as
   `Compat.state_plan` says) and recomputes; `Finish_cutover` writes the
   end record and makes the candidate live; recovery replays the
   cutover's facts given the revisions the host knows, and the replay
   audit follows each asset's revision, so every turn is checked against
   the code that ran it. `Engine.soak` runs a candidate alone in memory
   on the live inputs against a cassette and returns only a verdict.
   `engine/test/cutover_test.ml`: a soak and a cutover part way through
   the loop (every asset on the candidate, the critic's requests switching
   once and for good, the records auditing clean across revisions); a
   candidate whose critic divides by zero fails its soak and leaves the
   live instance untouched; a rewiring is refused; a crash between begin
   and end is recovered and finished; and a Hegel property, a cutover at
   any step with lost and duplicated receipts and crashes, resting where a
   fresh run of the candidate does, ordered after every step. Four planted
   cutover faults are caught (switching a busy fiber, a consumer first,
   a switch not recorded, a changed cell kept). The admitter, like any
   sender, keeps a candidate until its begin record is durable and admits
   it again after a crash that lost it.

   Admission across processes is `cordis-run --admit FILE`. The run store
   keeps every revision's text (a `revisions` table: the starting one, and
   each candidate, stored before the record that names it), so recovery
   takes its revisions from the store and a changed `--graph` changes
   nothing: new code enters only by admission. `--admit` checks the
   candidate, soaks it in a shadow answered from the run's own records
   (the receipt recorded for the same request, else the last for the
   kind), and on a soak that comes to rest admits it and cuts over; a
   failed soak or a rewiring is refused with nothing changed, and
   admitting a candidate already live or under way does nothing, so a
   lost admission is simply asked for again. A shadow answered that way
   exercises only the paths the records can answer: it shows a candidate
   runs to rest on answers like the live run's, not that those are the
   answers it would get. `outbox/test/cutover.sh` runs it through
   processes: a crash part way, a candidate that fails its soak and one
   that rewires (both refused, the store unchanged), a crash in the middle
   of a cutover, the admission asked again, and a restart that audits the
   records clean across both revisions; the journal shows the completions
   asked with the old request until the switch and the new one after.
6. **Wider.** Frames (RFC 0005 rules 1 to 12, once scheduled); the
   OpenAPI and AsyncAPI importers; 8C in a Solo5 unikernel with the run
   store moved to the framed log (2B) on a block device, under the
   narrow tender.

## Models to write first

Each in `cordis/quint/`, in the existing conventions (a `design`
instance, mutants as instances with one flag flipped, witnesses checked as
`not(w)` expecting `VIOLATED`), added to `check.sh` with an Apalache depth,
and given an ITF projection from the commit log so stage 1 onward can be
checked against it.

### `observe.qnt`: observational emissions (written, revision 2)

See Decision 3, "Observational emissions". In the gate.

### `engine.qnt`: the turn commit protocol (written)

Fibers run turns; each turn is one record carrying the fiber's whole
state, its intent and any receipt it applies; records become durable a
prefix at a time (group commit, and a crash after part of a group); the
outbox sends each key once and answers again from its journal; a lost
receipt makes the fiber Uncertain durably. A turn's input is
nondeterministic (it stands for `ctx.now` and arrival order), so a turn
re-run after a crash may commit a different intent under the same key:
that is what the output gate prevents. Because the outbox deduplicates by
key, the failure is not a second effect but an effect that no durable
record backs, and the properties say that.

| | |
| --- | --- |
| Properties | `releasedOnlyDurable`: every key sent is in a durable record; `effectMatchesCommit`: what was sent under a key is what the durable record under that key says; `commitAtomic`: a durably Awaiting fiber's intent is durable with it; `receiptOnce`: at most one durable record applies each key's receipt; `uncertainSurvives`: a durably Uncertain fiber leaves Uncertain only through a receipt or reconcile record |
| Mutants | `releaseEarly` (no output gate) → `releasedOnlyDurable` and `effectMatchesCommit`; `splitRecord` (state and intent in two records) → `commitAtomic`; `unkeyedReceipt` (a receipt matched to its fiber, not its key) → `receiptOnce`; `forgetUncertain` → `uncertainSurvives` |
| Witnesses | `crashBeforeRelease`, `groupCommitted`, `receiptRedelivered`, `recoveredUncertain`, `allDone` |

What writing it changed from the plan above:

- **`receiptNotJournaled` is an equivalent mutant.** Deduplicating
  receipts against a volatile set instead of the journal breaks nothing,
  because a receipt applies only to a fiber awaiting exactly its key, and
  the awaited key is durable state: the fiber's record is the dedup. The
  run store therefore needs no receipt table of its own, provided receipts
  are matched by key; `unkeyedReceipt` is the mutant that matters. The
  gate checks `dedupInMemory` as expected to hold.
- **Episodes are not in this model.** Every record carries the fiber's
  whole state, episode included, so by the output gate an episode is
  durable before any of its intents can be sent; `episodeInMemory` has
  nothing to break here. That is an argument, not a check: restarts and
  episodes stay in `lifecycle.qnt`, which found the episode must be in the
  key, and the slice's conformance traces cover both.
- **`atMostOnce` is the outbox's.** With the outbox sending each key once,
  a second effect needs a second key for one emission; that is
  `handoff.qnt`'s `keyFromClock`, not this model's.

Apalache checks the design to depth 7 (about 400 s); the gate runs depth
6 (about 75 s), and depth 8 did not finish in 30 minutes.

### `handoff.qnt`: the run store to the outbox journal (written)

The guest computes and commits one intent per turn in a run store that
rolls back with the step snapshot; a relay sends committed,
unacknowledged intents over a channel that loses and duplicates frames;
the outbox journals each key on a dataset the snapshot skips,
acknowledges, sends each key to the world once, and answers duplicates
and re-issued keys from the journal. The guest and the outbox each crash;
the agent snapshots and rolls back.

| | |
| --- | --- |
| Properties | `atMostOnce` (per turn); `onlyCommittedReleased`: every journaled key was durable in the run store when relayed; `noOrphan`: every key the guest holds acknowledged is journaled; `receiptToIssuer`: the guest applies a receipt only to an intent it has committed |
| Mutants | `keyFromClock` (a key with a time part that a rollback does not rewind) → `atMostOnce`; `relayUncommitted` → `onlyCommittedReleased`; `ackBeforeJournal` (and an outbox crash between) → `noOrphan`; `receiptAny` (a receipt applied before the rolled-back guest has re-issued its intent) → `receiptToIssuer` |
| Witnesses | `rolledBackReissued` (a re-issued key answered from the journal: the plan's `rollbackAnswered`), `duplicateFrameIgnored`, `resentAfterOutboxCrash`, `allAnswered` |

One rule the model made explicit: after a rollback, a receipt for an
intent the guest has not yet re-issued waits, and is applied when the
re-issued frame is answered from the journal; applying it at once
(`receiptAny`) gives the guest a receipt for a turn it has not taken.
Apalache checks the design to depth 8 (about 10 s).

The model takes "the run store rolls back exactly with the step snapshot"
as given. Stage 3's native rollback-fidelity check is the evidence for
that assumption on a real filesystem; the model cannot see a snapshot
dirtied by the filesystem itself.

### `budget.qnt`: reservations and settlement

Turns that emit intents with a worst-case cost, a per-graph budget, the
shared limiter of `outbox.qnt`, receipts carrying actual cost, `Lost`
receipts and reconcile.

| | |
| --- | --- |
| Properties | `withinBudget`: settled plus reserved never exceeds the budget; `conserved`: at rest, reserved equals the sum over open intents (no leaked reservation); `heldWhileUncertain`: an Uncertain intent's reservation is kept until reconcile; `settleOnce` |
| Mutants | `reserveAfterCommit` (the reservation is a second write; a crash between) → `withinBudget`; `refundOnLost` (a `Lost` receipt refunds; reconcile later finds it took effect) → `withinBudget`; `settleEveryDelivery` → `settleOnce` and `conserved` |
| Witnesses | `refusedForBudget` (a turn did not run for lack of budget), `settledBelowReservation`, `uncertainHeld` |

### `cutover.qnt`: moving a running instance between revisions

Composes `revisions.qnt`'s cutover with the lifecycle guard, in-flight
receipts, Uncertain, frame modes and the shadow soak; a crash may strike
mid-cutover.

| | |
| --- | --- |
| Properties | `singleWriter` (as `revisions.qnt`); `answeredByIssuer`: a receipt is applied under the revision recorded with its intent; `noShadowEmission`: no intent of a shadow (soak) instance is released; `rejectedLeavesLive`: a candidate that fails a gate leaves the live fibers and their state unchanged; `noUnadmittedMix`: after recovery, every running fiber belongs to the live revision, or a durable `cutover-begin` without its end names both; `ordered`: no new consumer activates before its new provider, no old provider unloads before its old consumers |
| Mutants | `retireAwaiting` (unload with a receipt outstanding) → `answeredByIssuer`; `shadowReleases` → `noShadowEmission`; `volatileCutover` (no begin/end records) → `noUnadmittedMix`; `insertConsumerFirst` → `ordered`; `insertFirst` (as `revisions.qnt`) → `singleWriter` |
| Witnesses | `cutoverWaitedOnUncertain`, `crashMidCutoverCompleted`, `soakRejected`, `unchangedCellKept` (a cell with the same hash kept running and its memo) |

### `schedule.qnt`: schedule independence and fairness

Two copies of one instance (three tasks, each adding its weight to one
shared key and emitting one intent) run under independent schedules from
the same inputs, in one state (a product construction, since Quint checks
invariants of one run). Three graphs share one loop under DRR: graph 0
never rests and its steps cost 3, the others' work comes and goes and
their steps cost 1, the quantum is 2, and the bound on a wait is 8.
Apalache checks the model to depth 6 in the gate (about 30 s; depth 8
ran past 20 minutes): the least depth at which both copies rest, so
`sameRest` is checked exhaustively there. A wait past the bound takes nine
picks, so `boundedWait` rests on simulation, where the `waitedToBound`
witness shows the bound is reached and tight.

| | |
| --- | --- |
| Properties | `sameRest`: when both copies are quiet, their tables and their sets of released keys are equal (Theorem 80 extended to emissions); `boundedWait`: a ready graph is passed over at most a bound of picks (a safety stand-in for fairness, since liveness is not checked) |
| Mutants | `positionalKey` (an intent key that includes the step number) → `sameRest`; `putShared` (a shared key with non-commuting `Put`, as `cordis/test`'s confluence control) → `sameRest`; `strictPriority` → `boundedWait` |
| Witnesses | `schedulesDiffered` (the copies took different orders and still agreed), `deficitCarried`, `bothAtRest`, `waitedToBound` (a graph waits exactly the bound: it is tight) |

### Extensions to existing models

- **`assets.qnt`**: add revisions. A memo entry keyed by cell hash
  survives a cutover only for an unchanged cell; mutant `memoByName`
  (keyed by asset name and inputs, not the cell hash) reuses an old
  revision's output → `consistent`. (Dagster's lesson that the code
  version belongs in the memo key.)
  **Done**: a cutover admits a revision in which one cell changes; an
  asset is also stale when its cell's hash is not the one it was computed
  with, so the changed cell recomputes and the others keep their values;
  witness `memoKeptAcrossCutover` (an unchanged cell hits an entry from
  before the cutover). The engine keeps no memo table yet: its counterpart
  is `Switch`, which clears a changed cell's basis so it recomputes, and
  its receipt cache is the outbox's journal, keyed by intent key, which
  carries the computation count (`graph/asset#computes.turn`) and so is
  never reused by a recompute under new code.
- **`routes.qnt`**: add a crash and recovery between `routeAppend` and the
  next recompute, with the answered-revisions set durable; mutant
  `answeredInMemory` → `appendOnce`. **Done**, with one finding: an append
  to `notes` cannot be replayed even with the answered keys lost, since it
  makes the scrutinee stale and its recompute is a new computation, so the
  window exists only for an append to a stream the scrutinee does not
  read. The model gained such a stream (a `log` appended on `Pass`), a
  crash that keeps what the records hold, the witness `crashedBetween`,
  and the mutant. Writing the engine test for it found that the engine
  keyed answered appends by computation alone, so an arm appending to two
  streams appended to one and stalled; the key now names the stream
  (record format version 2), and the checker refuses an arm that appends
  to one stream twice (RFC 0002, amended).
- **`lifecycle.qnt`**: add durable versus process-scoped acquisitions and
  the recovery sweep; properties `noLeakedDurable` (at rest after
  recovery, every durable acquisition belongs to an installed fiber) and
  `releasedOnce`; mutants `noSweep`, `sweepTwice`. (Could instead be a
  separate `recovery.qnt` if the lifecycle model grows too slow for
  Apalache at depth 8.)
  **Done, as `recovery.qnt`**: `lifecycle.qnt`'s variables are the
  simulator's conformance target, so the extension is a model of its own.
  A durable acquisition is recorded with a generation in the record that
  makes it, and released by generation (a fenced release frees the thing
  only if that generation still holds it), with the release recorded too;
  a fiber releases its durable acquisitions before it unloads; a crash
  drops the process-scoped ones and reinstalls some fibers; before
  anything runs, the sweep releases every recorded acquisition whose owner
  was not reinstalled. Properties `noLeakedDurable`, `releasedOnce` (no
  generation released twice, since a stale release would free what
  another fiber has since acquired) and `procOnlyWhenRun`; mutants
  `noSweep` and `sweepTwice` (the sweep does not record its release, so
  the next recovery, or the owner reinstalled later, releases again);
  witnesses `sweptOrphan`, `reacquiredAfterSweep`, `crashedHolding`,
  `recoveredOwner`. Apalache to depth 8 in about 20 s. The engine has no
  durable acquisitions yet; the first will be yard's worktrees and
  leases (stage 4), and they follow this model.

The generated Quint module per graph (RFC 0002 "Deferred") is gate 5 of
admission, not a precondition of the runtime; it follows stage 2.

## Open questions

1. **Answered (revision 2): a separate pure module.** The kernel stays
   the control plane, which keeps phase 1's evidence intact.
   **The value plane's home.** Extend `Kernel` with a value type (a
   functor over values, keeping the int instance for the existing models
   and tests), or keep the kernel as the control plane and put values in
   a separate pure module the engine composes? The second keeps phase 1's
   evidence intact; the first keeps one transition function.
2. **Answered (revision 2): the canonical text.** RFC 0002's canonical
   text and SHA-256 are the hash input, with a format version in the
   record, until the capability slot (caps work) needs Preserves.
   **Record format before Preserves.** The codecs do not exist yet
   (DESIGN.md "Open questions"). Use the canonical text and SHA-256 of RFC
   0002 inside the frame now and change only the payload codec later, with
   a format version in the segment header?
3. **Answered (revision 2): recorded.** `ctx.now` is read once per turn
   and written in the commit record, so replay reproduces it; freshness
   timers remain open.
   **Time.** `ctx.now` comes from the oracle in simulation. In production
   is it read once per turn and recorded in the record (so replay
   reproduces it), and who supplies freshness timers (`~fresh:every`)?
4. **Snapshot cadence and retention.** Policy numbers, but the audit (5C)
   and conformance (7A) want history the compactor wants to drop. Ship
   compacted segments to a derived store (chDB, as ADR-001 suggests)?
5. **Cross-graph assets.** Can one graph's asset be another's input inside
   one instance (one provider, `guardHolds` across graphs), or only
   through inputs from outside? 8B forbids the first.
6. **Uncertain and cutover.** A reconcile that never finishes blocks a
   rolling cutover of that fiber forever. Is the reconcile deadline in
   policy enough, or does an admitted revision need a "proceed without
   this fiber" mode that a person approves?
7. **Wasm cells and fuel.** Wasm cells bound work by fuel, not a CEL
   estimate. Do they reserve fuel against the same budget, with fuel
   priced in cost units?
8. **Pipelining.** DESIGN.md's "an intent may refer to an earlier one's
   future receipt" lets a chain share one commit point. The kernel
   awaits one intent per fiber; is pipelining a later extension of
   `engine.qnt`, or needed for the first LLM-heavy graphs?
9. **Answered (revision 2): no.** An external durable-execution engine
   would be a second source of truth for retry semantics; the outbox's
   executor is ours, with SQLite as its journal.
   **Outbox journal store.** SQLite in the outbox jail is recommended;
   should the outbox's own executor be an existing durable-execution
   engine (Restate or Temporal, 1D in a narrower role), since it sits
   outside the deterministic boundary anyway?
10. **Unikernel IO layer.** The edge on Mirage is Lwt; on Unix, Eio. Is a
    sans-IO core with two thin edges acceptable, or should the core speak
    Picos so one edge serves both?

## Sources

In this repository: `cordis/kernel/kernel.mli`, `cordis/kernel/order.mli`,
`cordis/sim/` (`sim.ml`, `oracle.mli`, `assertions.mli`, `itf.ml`),
`cordis/graph/lib/compile.ml` and `check.ml`, `cordis/cel/lib/eval.ml`
and `cost.ml`, `cordis/quint/*.qnt` and `check.sh`, `cordis/README.md`;
`proofs/journal-wal/README.md`; `src/jolt/sim/journal.clj`,
`flow_effect_session.clj`, `sqlite.clj`; `src/jolt/example/outbox.clj`.
Design documents: [DESIGN.md](../DESIGN.md),
[SYNTHESIS.md](../SYNTHESIS.md), [OCAML-ON-BHYVE.md](../OCAML-ON-BHYVE.md),
[STREAMING-PRIOR-ART.md](../research/STREAMING-PRIOR-ART.md) (Rama,
Flink, Beam/Dataflow, Kafka Streams, Timely/Differential, Materialize,
Feldera, Temporal, Spark, Dagster, with their sources).

Further prior art cited here:

- Shi, Zhang, Cui, *A Programming Paradigm for Spatiotemporal
  Composability* (Cordis), arXiv 2608.25512v1: §4.3 (ordering, progress,
  confluence), §4.4 (inertial hosts), §6.2 (brokers).
- Spritely Goblins, vats and transactional turns:
  <https://spritely.institute/goblins/>; E's event loop:
  <http://erights.org/elib/concurrency/event-loop.html>.
- Cloudflare, "Durable Objects: Easy, Fast, Correct — Choose three"
  (input and output gates):
  <https://blog.cloudflare.com/durable-objects-easy-fast-correct-choose-three/>.
- TigerBeetle, deterministic simulation (VOPR):
  <https://docs.tigerbeetle.com/>; FoundationDB, simulation and testing:
  <https://apple.github.io/foundationdb/testing.html>.
- Remus, high availability via asynchronous VM replication (output
  commit), NSDI 2008.
- Temporal, workflow versioning and event history:
  <https://docs.temporal.io/develop/go/versioning>; Restate:
  <https://docs.restate.dev/>; Golem: <https://learn.golem.cloud/>.
- Transactional outbox: <https://microservices.io/patterns/data/transactional-outbox.html>;
  Debezium outbox event router:
  <https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html>.
- SQLite atomic commit and WAL: <https://www.sqlite.org/atomiccommit.html>,
  <https://www.sqlite.org/wal.html>; LMDB: <http://www.lmdb.tech/doc/>;
  PostgreSQL `SKIP LOCKED`: <https://www.postgresql.org/docs/current/sql-select.html>.
- Eio: <https://github.com/ocaml-multicore/eio>; Picos:
  <https://github.com/ocaml-multicore/picos>; Moonpool:
  <https://github.com/c-cube/moonpool>; Riot: <https://github.com/riot-ml/riot>;
  MirageOS: <https://mirage.io/>; Solo5: <https://github.com/Solo5/solo5>.
- Jane Street Incremental (height-ordered recompute):
  <https://github.com/janestreet/incremental>; Salsa:
  <https://github.com/salsa-rs/salsa>.
- Shreedhar and Varghese, "Efficient Fair Queuing using Deficit Round
  Robin", SIGCOMM 1995.
- Erlang/OTP supervision principles:
  <https://www.erlang.org/doc/system/sup_princ.html>.
