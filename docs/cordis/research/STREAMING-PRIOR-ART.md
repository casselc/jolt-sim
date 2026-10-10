# Prior art for batch and streaming topologies (for Cordis RFC 0005 Frames)

Legend: **[doc]** = stated in the cited documentation; **[inf]** = my inference.

## Recommendations for Cordis

These are mapped to the RFC 0005 "Frames" open questions. "Change" marks proposed edits to that section. All of this is inference from the sections below.

1. **Batch delimiting: a batch is a half-open position interval `[lo, hi)`, fixed and logged before the batch is processed.**
   - The interval is over source positions (row offset, or row-group index plus offset). It is not just "the k-th batch".
   - Rama fixes a microbatch's offset range at its first `explodeMicrobatch`. Spark writes `offsets/<id>` before running. Materialize's remap binds offsets durably.
   - Replay after a crash must re-derive *the same* batches, or the per-batch cost and exactly-once arguments fail.
   - **Change:** RFC 0005 says "a stream of batches each `list(T) <max B>`". Add "each batch is identified by its source interval, and the interval is durable before its turn runs."

2. **Commit: one atomic record per turn = {cell state', outbox intents, consumed cursor `hi`, revision id}.**
   - Kafka Streams commits output, changelog, and input offsets in one transaction. Rama's microbatch commits PStates and microbatch state together.
   - Resume means: load the last record, then read from `hi`. Exactly-once follows from "rollback to the last commit + deterministic replay". Cells are pure CEL, so Cordis gets determinism for free, which Rama cannot promise once `shufflePartition` or randomness is involved.
   - **Change:** state this in RFC 0005, and model it in `frames.qnt` as a single `commitTurn` action with a crash action that may strike between "turn computed" and "commit". Mutant: a commit that writes state but not the cursor.

3. **Exactly-once effects.** Key each intent's idempotency on `(stream id, batch lo, intent index)`, the batch analogue of RFC 0002's "append keyed by the scrutinee's revision".
   - A redelivered batch then re-emits identical intents, and the outbox dedups them.
   - This is Flink's two-phase-commit sink split into pre-commit (staged intent) and commit (receipt).
   - The `frames.qnt` property should be stated as *effects observed once*, not "turn ran once", because a turn may run twice before commit.

4. **Upgrading code mid-stream: cut over only at a commit boundary, and record the revision in the commit record.**
   - The model checks "batches ≤ k processed by r, > k by r′" (Temporal's patch marker; Rama's handover at checkpoint).
   - **Change:** RFC 0002 says admission's outcome "is that of a cold start of the new revision". That is **false** for a carried accumulator: a cold start would re-fold batches 0..k with the new code. RFC 0005 must either:
     - weaken the claim for frame consumers, to "equivalent to the old revision on `[0,k)` and the new one on `[k,∞)`"; or
     - offer a per-revision choice: **carry** (auto-upgrade), **drain** (finish this frame on the old fiber, then cut over — Temporal pinned; Dataflow drain), or **restart** (discard the accumulator and re-read from 0 — Kafka Streams' new `application.id`).
   - Recommend all three, with `restart` as the default whenever the accumulator's *type* changed.

5. **State migration.** Follow Rama's `PState.migrated(schema, id, fn)`: a migration has an id, is idempotent, and is applied on read until complete. Widening needs none.
   - Follow Flink: never evolve *key* types of keyed state.
   - Follow Dataflow: the element type of a **buffered, not yet consumed** stream is state too. A revision that changes a frame stream's `T` must migrate or refuse the buffered batches.
   - **Change:** extend `stateReadable` to stream buffers.

6. **Batch size B: make it a policy ceiling in `gates.edn`-style policy, derived from the budget, and let the runtime choose the actual size per input, ≤ B.**
   - Effective size: `B_eff = min(policy B, floor(turn budget / per-row cost estimate), row-group/page boundary)`.
   - Beam gives users no control over bundle size. Rama, Spark and Kafka expose a cap (`depot.microbatch.max.records`, `maxOffsetsPerTrigger`, `commit.interval.ms`).
   - Aligning to Parquet row groups / Vortex chunks avoids re-decoding, but a row group may exceed B, so split it. The interval in (1) makes splitting safe.
   - Require handlers to be correct for *any* partition of the input into batches. Add a Hegel property: fold over random batchings equals fold over one batch.
   - **Change:** answer open question 4 this way instead of "fixed per graph".

7. **Per-batch cost bounds.** The static estimate over `B` rows only holds if the accumulator is bounded or indexed. A handler that scans its accumulator is O(state), not O(B) (the differential/DBSP lesson).
   - **Change:** require the accumulator type to carry a size bound (`<max n>`), or have the cost estimator charge a per-turn state term.
   - Also charge migration work per turn, as Rama does (`...migration.max.paths.per.batch`).

8. **Backpressure vs refusal: refusal must be lossless and must say whether data was durably accepted.**
   - For a *source-fed* frame stream, a full buffer should stop the reader from advancing its cursor, which is backpressure by not reading (Kafka pull, Flink credits). Nothing is "refused" in a way that loses rows.
   - Keep RFC 0002's refuse-and-report for *route appends on feedback loops*, where refusal is the intended semantics.
   - Rama's `ACK`-append-throws-but-data-was-written shows the ambiguity to avoid. A refusal must be a definite "not appended".
   - **Change:** "a full stream refuses rather than drops" should be split into those two cases. The model should check that no row is lost *and* that the producer's cursor does not pass a refused batch.

9. **End of input: model it as the frontier closing (`hi = ∞` / watermark = +∞), committed like a batch, with a final turn that flushes the accumulator.**
   - The flush turn's output must also be committed exactly once, which is Flink's FLIP-147 lesson.
   - Its cost is bounded separately: an `on_end` handler with its own estimate.
   - **Change:** RFC 0005 says only that "the end marker follows every batch". Add "the end turn runs exactly once, after the last committed batch, and is itself a commit".
   - **Conflict:** RFC 0002 says "a new input empties the streams". It must say what happens to a half-consumed frame when a graph input changes. Recommend that the frame episode be abandoned and its accumulator discarded, and that `frames.qnt` check it.

10. **Two-phase aggregation: let a cell declare its accumulator as a *combiner*** (`zero`, `combine(a,b)`, associative; input type = state type). Only then may the runtime split a frame across parallel fibers and merge partials.
    - This is Rama's rule that two-phase aggregation applies only if every aggregator is a combiner, and Beam's `CombineFn.mergeAccumulators`.
    - Plain accumulator cells stay sequential. This is also what makes "batching is invisible" checkable: Hegel property `combine` associativity, plus `fold(xs++ys) = combine(fold xs, fold ys)`.

11. **Fan-in.** A cell with two frame inputs needs aligned markers (Flink barriers) or per-input cursors in its commit record. The RFC currently assumes one frame input per cell, so either say so or put per-input cursors in (2).

12. **Model scope for `frames.qnt`.** Beyond the five listed properties, add:
    - "a rejected revision leaves the old fibers running" (Dataflow's compatibility check);
    - "replay re-derives identical batch intervals";
    - "the end turn commits once".

    Mutants:
    - cursor committed separately from state;
    - batch re-split on replay;
    - cutover mid-batch;
    - end turn before the last commit;
    - refusal that advances the cursor.

## Comparison table

| System | Batch / commit unit | How delimited | Exactly-once scope | Upgrade with live state | In-flight data at upgrade | Backpressure | End of input | Two-phase agg |
|---|---|---|---|---|---|---|---|---|
| Rama stream | per-task event batch; checkpoint every N records/time | count / timer | at-least-once (retry modes); none with `retryNone` | module update, matched by name; `PState.migrated` lazy + on read | pause 2–30 s, resume from checkpoint [doc]; replays post-checkpoint [doc] | per-task cap; ACK append throws (data written) | n/a (unbounded depots) | no (batch blocks only) |
| Rama microbatch | microbatch ID; atomic per-task PState commit | offset range per partition fixed at first explode, ≤ `max.records` | PState updates exactly-once; depot appends from inside not yet | same | presumably retried under same ID by new instance [inf] | implicit (pull) | n/a | yes, iff all aggregators are combiners |
| Flink | checkpoint *n* | barriers injected into streams | state; sinks via 2PC | stop-with-savepoint, restart; uid-mapped; POJO/Avro lazy evolution; keys never | unaligned: saved in checkpoint; else drained to barrier | credit-based, lossless | `endInput` + MAX_WATERMARK; final checkpoint (FLIP-147) | combiners via pre-aggregation |
| Beam / Dataflow | bundle (runner-chosen) | runner; splittable at runtime | per-key state + shuffle dedup; sinks idempotent | replacement job, transform mapping, coder check | kept; finishes through old transforms | runner | watermark → +∞ | `CombineFn` lifting |
| Kafka Streams | transaction per commit interval | time (`commit.interval.ms`) | Kafka→Kafka only | rolling bounce; explicit names; no migration | aborted txn replays | pull; log is buffer | n/a | no (local aggregate per partition) |
| Timely / Differential | timestamp/epoch | frontier passes *t* | n/a (in-memory) | n/a | n/a | n/a | frontier empty | arrangements / incremental |
| Materialize | persist batch `[lower, upper)` | reclocked timestamps | ingestion + views (deterministic) | redeploy views | n/a | n/a | upper = ∞ | n/a |
| Feldera (DBSP) | circuit step | whatever is queued | checkpoint + per-step journal | modify + bootstrap [recalled] | n/a | connector buffers | n/a | incremental Z-sets |
| Spark SS | micro-batch id | offsets log before run | with replayable source + idempotent sink | restricted; stateful changes need new checkpoint | rerun same offsets | `maxOffsetsPerTrigger` | `AvailableNow` | partial agg |
| Temporal | workflow task | event history | workflow state (replay); activities at-least-once | patch markers; worker versioning (pinned / auto-upgrade); continue-as-new | pinned runs finish on old code | task queue | workflow completes | n/a |
| **Cordis (RFC 0005)** | turn per batch | `list(T) <max B>` (**add: durable interval**) | goal: exactly-once via outbox | admission: retire, then insert; carry or migrate | cutover between batches | "full stream refuses" (**split per rec. 8**) | end marker (**make it a committed turn**) | not specified (**add combiner cells**) |

## 1. Rama (Red Planet Labs)

Sources: [depots](https://redplanetlabs.com/docs/~/depots.html), [stream](https://redplanetlabs.com/docs/~/stream.html), [microbatch](https://redplanetlabs.com/docs/~/microbatch.html), [aggregators](https://redplanetlabs.com/docs/~/aggregators.html), [pstates](https://redplanetlabs.com/docs/~/pstates.html), [operating](https://redplanetlabs.com/docs/~/operating-rama.html), [Clojure test API](https://redplanetlabs.com/clojuredoc/com.rpl.rama.test.html), [demo gallery](https://github.com/redplanetlabs/rama-demo-gallery).

**Depots and partitioners.** A depot is a partitioned, replicated, append-only log declared in a module: `setup.declareDepot("*depot", Depot.hashBy(ExtractUserId.class))`, `Depot.random()`, `Depot.disallow()` (topology-only writes), custom `Depot.Partitioning`, and `global()` for a single partition. Order is guaranteed only within a partition; partitions proceed independently. Appends take an `AckLevel`: `NONE`, `APPEND_ACK` (appended + replicated), `ACK` (default: also waits for colocated *stream* topologies to finish, and returns their `ackReturn` values keyed by topology name). The docs warn that an exception from `append` does not prove the append failed. Retention is `depot.max.entries.per.partition`; trimming can be coordinated with consumers that have not read yet. Depots themselves can be migrated with `.migration(id, fn)`, and `Depot.TOMBSTONE` excises a record without changing offsets. **[doc]**

**Stream topologies** process each record within milliseconds. Each source declares a retry mode. `retryNone()` is at-most-once. The default individual mode retries only the failed record (best-effort order). `retryAllAfter()` retries the failed record and everything after it on that partition, which preserves order. Retries are at-least-once, so the docs point non-idempotent work (counters) to microbatch. A record counts as done when its whole event tree completes, within `topology.stream.timeout.seconds`. Writes are batched per task: if one event in a batch throws, the whole batch fails and its PState writes are discarded. Progress lives in an internal PState (`$$__streaming-state-<id>`) and is checkpointed by count or timer, so work done after the last checkpoint replays after a failover. **[doc]**

**Microbatch topologies** give exactly-once PState updates. The runner on task 0 issues microbatch IDs. On the first `explodeMicrobatch`, each ID fixes the offset range per depot partition, capped by `depot.microbatch.max.records`. A microbatch runs in three phases:
- **priming:** clear buffers and temporary PStates, and reset user PStates to the end of the previous ID;
- **processing**;
- **commit:** checkpoint PStates (hard links), replicate, then flip external visibility atomically per task.

On failure (exception, leader change, or `topology.microbatch.phase.timeout.seconds`), the batch reruns with the same ID, a higher version, the same records and the same starting state. The exactly-once argument is "deterministic batch boundary + rollback to the last commit + replay". The docs name the exceptions: `shufflePartition`, randomness, mirror reads, and depot appends from inside a microbatch are not yet exactly-once. **[doc]**

**PStates** are durable, partitioned, schema-typed indexes (`mapSchema`, `setSchema`, `listSchema`, `fixedKeysSchema`, `.subindexed()`). Only the owning topology writes to a PState. Reads can be point, path or reactive (`proxy`, which emits diffs). **[doc]**

**Batch blocks, aggregators, combiners.** `batchBlock` (microbatch and query topologies) splits code into pre-agg, agg and post-agg phases. Rama infers the phases from the code, which lets it reorder execution and support joins and temporary PStates. There are two aggregator kinds:
- **Accumulator** (`initVal`, `accumulate(curr, args...)`): runs sequentially.
- **Combiner** (`RamaCombinerAgg<T>`: `zeroVal()`, `combine(a,b)`, optional `isFlushRequired()`): associative, input type equals output type.

Two-phase aggregation applies only if *every* aggregator in the agg phase is a combiner. Each task then pre-aggregates into a hidden combiner buffer before the final partitioner, so only partials cross the network. Example: `.batchBlock(Block.explodeMicrobatch("*mb").out("*v").globalPartition().agg("$$p", Agg.sum("*v")))`. Other batch-only features are `captureNewValInto`, `limitAgg`, and temporary `$$` PStates. **[doc]**

**Ack and backpressure.** Backpressure is bounded by `topology.stream.max.executing.per.task`. When the cap is hit, the depot waits. An `ACK` append *throws even though the data was written*, and `retryNone` sources skip records, so overload becomes an error or a skip, not unbounded buffering. The docs advise sizing so normal load stays well under the cap. `depot.max.pending.streaming.per.partition` bounds the queue per partition. **[doc]**

**Module updates and in-flight data.** `rama deploy --action update`. Objects are matched by name, with no renames. A removed depot or PState must be listed exactly in `--objectsToDelete`, or deploy fails. The Conductor starts new workers beside the old ones, hands over reads and writes (`:updating-prepare-handover`), then destroys the old instance. During handover:
- depot appends are buffered client-side;
- queries retarget, and reactive queries resync;
- stream and microbatch processing pauses for 2–30 s;
- topologies resume from their last checkpoint; start-from options are not reapplied.

PState migration is `PState.migrated(newSchema, "migrationId", fn)`. Reads apply `fn` immediately, while the disk is rewritten lazily: a bounded slice of migration work runs before each streaming batch or microbatch commits (`topology.microbatch.migration.max.paths.per.second`). Reusing the same migration ID across updates continues the migration; a new ID restarts it, so `fn` must be idempotent. Widening a schema needs no migration. Rama rejects removal of the `migrated` wrapper until the migration is done. **[doc]**

The docs do not spell out what happens to a microbatch in flight at cutover. **[inf]** It is presumably abandoned and retried by the new instance under the same ID from the last commit. The priming and retry design makes that safe, and the test API's `pause-microbatch-topology!` waits for the running microbatch before pausing.

**Relevance to Cordis [inf].**
- Rama's microbatch is almost exactly the RFC 0005 frame model: a fixed batch boundary, rollback, replay, and an atomic commit of state together with progress.
- "Cutover only between batches" is Rama's handover-at-checkpoint.
- Migration on read, plus bounded migration work charged to each batch, is a model for "carry or migrate".
- The ACK-throws-but-written rule is a warning: a "refusal" must say whether the data was durably accepted.

## 2. Apache Flink

Sources: [checkpointing / stateful stream processing](https://nightlies.apache.org/flink/flink-docs-stable/docs/concepts/stateful-stream-processing/), [savepoints](https://nightlies.apache.org/flink/flink-docs-stable/docs/ops/state/savepoints/), [schema evolution](https://nightlies.apache.org/flink/flink-docs-stable/docs/dev/datastream/fault-tolerance/serialization/schema_evolution/), [CLI stop/drain](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/cli/), [end-to-end exactly-once blog (2018)](https://flink.apache.org/2018/02/28/an-overview-of-end-to-end-exactly-once-processing-in-apache-flink-with-apache-kafka-too/), Carbone et al., "Lightweight Asynchronous Snapshots for Distributed Dataflows" (arXiv:1506.08603).

**Checkpoint barriers.** The JobManager injects numbered barriers into every source stream. A barrier splits the stream into "records in checkpoint *n*" and "records after it": the batch boundary is a marker in the data, not a count. When an operator has received barrier *n* on every input, it snapshots its state asynchronously and forwards the barrier. Under *aligned* checkpoints, an input that delivered its barrier is blocked until all inputs catch up, which gives exactly-once state. With alignment off, delivery is at-least-once. *Unaligned* checkpoints let barriers overtake buffered records and save those in-flight buffers in the checkpoint, so a checkpoint under backpressure completes quickly but is larger. Recovery restores every operator to checkpoint *n* and rewinds sources (for example, Kafka offsets) to the positions stored with it. **[doc]**

**Exactly-once effects.** State is exactly-once internally. External effects use a two-phase-commit sink (`TwoPhaseCommitSinkFunction`, now Sink V2 `Committer`):
- On the barrier: pre-commit (flush and stage a transaction).
- On `notifyCheckpointComplete`: commit.
- On recovery: re-commit or abort staged transactions.

Commits must therefore be idempotent, because a commit can be retried after a crash. **[doc]**

This is the closest analogue to Cordis's outbox: **[inf]**
- staged intents are the "pre-commit";
- receipts are the commit acknowledgement.

**Savepoints and upgrades.** A savepoint is a manually triggered, portable checkpoint in canonical or native format. State binds to operators by `uid(String)`. Auto-generated IDs depend on graph shape, so a restructured job may fail to restore unless every stateful operator has an explicit uid. `--allowNonRestoredState` drops state that no operator in the new program claims. Since 1.15, intermediate savepoints commit no side effects and are not used for recovery. The upgrade path is `flink stop --savepointPath ...`, which atomically takes a savepoint and stops, then a restart of the new jar from that savepoint. `--drain` also emits `MAX_WATERMARK` first, firing all event-time timers and windows, and is meant for *permanent* shutdown, not upgrade. **[doc]**

The upgrade is therefore stop-the-world at a barrier. Flink has no in-place hot swap. **[doc / inf]**

**State schema evolution.** Only POJO and Avro types evolve; Kryo-serialised types and anything nested in them do not. Migration is lazy, per state, on first access after restore: read with the old serializer, write with the new one. POJO fields may be added (with defaults) or removed, but types and class names may not change. **Key schemas can never evolve**, because a changed key could merge previously distinct keys. Anything beyond these rules goes through the offline State Processor API. **[doc]**

**Backpressure and bounded input.** Network channels use credit-based flow control. A slow consumer stops granting credits and the upstream blocks, so nothing is dropped. Backpressure propagates to the sources. **[doc]**

In batch execution mode or on bounded sources, end of input is an explicit signal: `BoundedOneInput.endInput()`, plus a final `MAX_WATERMARK`. Operators flush their accumulators there. Since FLIP-147, a final checkpoint after end of input lets two-phase-commit sinks commit the last data. **[doc: FLIP-147, [wiki](https://cwiki.apache.org/confluence/display/FLINK/FLIP-147%3A+Support+Checkpoints+After+Tasks+Finished)]**

**Relevance [inf].**
- **Barrier-as-marker.** It handles fan-in correctly: a multi-input cell must align markers or record per-input positions. RFC 0005 assumes a single upstream.
- **The FLIP-147 bug class.** The end marker must itself be *committed*. If the final accumulator flush is not checkpointed, outputs after the last batch are lost or duplicated.
- **Stable identity.** Cordis's "carry if new type reads it" needs a stable cell identity like Flink's `uid`, not a position in the graph.
- **Key/identity types.** The rule that key types never evolve applies to any state that indexes by key.

## 3. Dataflow model / Apache Beam

Sources: Akidau et al., "The Dataflow Model" (VLDB 2015), [PDF](https://www.vldb.org/pvldb/vol8/p1792-Akidau.pdf); [Beam programming guide](https://beam.apache.org/documentation/programming-guide/); [Beam execution model (bundles)](https://beam.apache.org/documentation/runtime/model/); [Streaming 101](https://www.oreilly.com/radar/the-world-beyond-batch-streaming-101/) / [102](https://www.oreilly.com/radar/the-world-beyond-batch-streaming-102/); [Dataflow pipeline update](https://docs.cloud.google.com/dataflow/docs/guides/updating-a-pipeline).

**Bounded vs unbounded.** A `PCollection` is either bounded or unbounded, and the same transforms apply to both. The model's central claim is that batch is a special case of streaming. A bounded source is an unbounded one whose watermark jumps to +∞ when the input is exhausted. **[doc]** This suggests Cordis's end marker is better modelled as "the input watermark reaches ∞" than as a separate message kind. Its semantic effect (finalise every open aggregate) is the same. **[inf]**

**The four questions.** The model separates four concerns:
- **What** is computed: transforms and `CombineFn`.
- **Where in event time**: windows (fixed, sliding, session, global).
- **When in processing time** results are emitted: *triggers*. Options are watermark (`AfterWatermark.pastEndOfWindow()`), early/late firings, processing-time delays, element counts, and composites.
- **How refinements relate**: *accumulation mode*, either discarding, accumulating, or accumulating-and-retracting.

**[doc]**

**Watermarks.** A watermark is a monotone, possibly heuristic estimate that "no more data with event time < W". When the watermark passes the end of a window, an on-time pane fires. Data that arrives later is *late*: it is dropped after `allowedLateness`, or else produces late panes. Perfect watermarks exist only when the source knows its input is complete (for example, a finished file). **[doc]**

**CombineFn = two-phase aggregation.** The interface is `createAccumulator`, `addInput`, `mergeAccumulators`, `extractOutput`. `mergeAccumulators` lets the runner do combiner lifting: partial combine before the shuffle, then merge after it. That is the same pattern as Rama's combiners and MapReduce combiners. `Combine.perKey` lifts automatically. A plain `GroupByKey` followed by a `DoFn` does not. **[doc]**

**Bundles: the runner picks the batch size.** Elements are processed in *bundles* that the runner chooses, with `@StartBundle` and `@FinishBundle` hooks. A bundle is the unit of commit and retry. If any element fails, the *whole bundle* is retried, possibly on another worker, and its outputs are discarded. Side effects are therefore at-least-once unless they are idempotent. Beam deliberately gives users no control over bundle size. Dataflow streaming bundles are small and adaptive; batch bundles come from splitting the source. Splittable `DoFn`s and dynamic work rebalancing let the runner split a bundle *while it runs* (`trySplit` on a restriction tracker). **[doc]** The lesson for Cordis: batch size can belong to the runtime, as long as handlers are written to be correct for any partition of the input. The handler is a fold, and the commit boundary is opaque to it. **[inf]**

**Exactly-once on Dataflow.** Each shuffle deduplicates by record ID, and state and timers commit atomically with output per key/bundle. Sinks need idempotence or transactions. **[doc: Dataflow "exactly-once" docs, inferred detail]**

**Upgrading a running streaming job (Dataflow).** A *replacement job* keeps:
- intermediate state;
- buffered and in-flight records.

But records already in flight "still go through the old transforms" as they finish. A compatibility check maps old steps to new ones (`--transformNameMapping`, where `null` deletes a step) and requires the same coders on every `PCollection`. If the check fails, the *old job keeps running* and the replacement is rejected. `graph_validate_only` dry-runs the check. Changing windowing or triggers does not re-bucket data already buffered. Schema changes may add fields or make them nullable, but may not remove, rename, or retype. Changing the coder of buffered data is the classic way to break an update. The alternative is **drain**: stop reading, close all windows by advancing the watermark to ∞, flush, and start a fresh job. **[doc]**

**Relevance [inf].**
- **Admission.** Validate first, keep the old version running if validation fails, and only then cut over. This matches Cordis's admission step and should be explicit in the Quint model as a "rejected revision ⇒ old fibers continue" property.
- **Wire format.** The format of *buffered* frames must stay readable across a revision. The stream itself is state, so its schema needs carry-or-migrate rules just like cell state. RFC 0005 should say so.

## 4. Kafka Streams

Sources: [Kafka Streams architecture](https://kafka.apache.org/documentation/streams/architecture), [config `processing.guarantee`, `commit.interval.ms`](https://kafka.apache.org/documentation/streams/developer-guide/config-streams.html), [KIP-98 transactions](https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging), [KIP-447 EOS v2](https://cwiki.apache.org/confluence/display/KAFKA/KIP-447%3A+Producer+scalability+for+exactly+once+semantics), [Confluent: exactly-once in Kafka Streams](https://www.confluent.io/blog/enabling-exactly-once-kafka-streams/), [upgrade guide](https://kafka.apache.org/documentation/streams/upgrade-guide), [naming operators](https://kafka.apache.org/documentation/streams/developer-guide/dsl-topology-naming.html).

**Exactly-once via transactions.** Setting `processing.guarantee=exactly_once_v2` makes each task (one input partition group) run a *read-process-write* loop inside a Kafka transaction. Three kinds of write commit atomically, or not at all:
1. output records;
2. changelog writes for state stores;
3. the consumer's input offsets (`sendOffsetsToTransaction`).

Downstream consumers use `isolation.level=read_committed`, so they never see aborted output. A zombie instance is fenced by producer epoch (KIP-98) or, in v2, by consumer group generation (KIP-447). **[doc]**

**The batch is whatever accumulated between commits.** Its length is set by `commit.interval.ms`, which defaults to 100 ms under EOS and 30 s otherwise. The boundary is a *time* interval chosen by config, not a data property. A smaller interval lowers latency and raises transaction overhead. **[doc]** On failure, the transaction aborts, and the store is rebuilt to the last committed changelog offset and replayed from the committed input offsets. Like Rama's microbatch, this is "rollback + replay". **[doc / inf]** Exactly-once covers only Kafka-to-Kafka. External side effects in a processor are at-least-once. **[doc]**

**State stores.** Stores are local (RocksDB or in-memory) and each is backed by a compacted *changelog topic*, which is the durable source of truth. Standby replicas (`num.standby.replicas`) shadow the changelog for fast failover. Under EOS, a crash wipes the local store when its checkpoint file is absent and restores it from the changelog. **[doc]**

**Upgrades.**
- **Rolling bounce.** Instances restart one at a time with new code. Protocol or format changes use a two-bounce procedure: first set `upgrade.from=<old version>` and roll, then remove it and roll again. During a bounce, old and new code process different partitions *at the same time*. **[doc]**
- **Topology changes.** Internal topics and stores are named after auto-generated processor names (for example, `KSTREAM-AGGREGATE-STATE-STORE-0000000003`). Adding an operator upstream renumbers them, which silently orphans the state, so the docs say to name every stateful operator explicitly (`Named`, `Materialized.as`, `Grouped.as`). A topology that is not compatible requires a new `application.id` (fresh state) or the reset tool. There is no built-in state migration: value formats must be compatible at the serde level (for example, Avro/Protobuf with a schema registry), or you reprocess. **[doc / inf]**

**Backpressure.** Kafka Streams pulls from the log, so backpressure is implicit: the log *is* the buffer, bounded only by retention. The `buffered.records.per.partition` and `max.poll.records` settings limit memory. Producers to a full broker block (`max.block.ms`) and then throw. **[doc]**

**Relevance [inf].**
- **Commit unit.** One atomic commit of {output intents, state delta, input offset} is the essence of exactly-once. Cordis's turn commit should write the new cell state, the outbox intents, and the consumed batch cursor *in one transaction*. Model that in frames.qnt as a single action.
- **Cutover.** Rolling bounce means two code versions run at once on disjoint partitions. Cordis's "retire, then insert" is stricter and simpler; keep it.
- **Naming.** Auto-generated names silently orphan state. Cordis cells need stable, author-given identities.

## 5. Timely / Differential Dataflow, Materialize, Feldera (DBSP)

Sources:
- Murray et al., "Naiad: A Timely Dataflow System" (SOSP 2013), [PDF](https://sigops.org/s/conferences/sosp/2013/papers/p439-murray.pdf).
- McSherry et al., "Differential Dataflow" (CIDR 2013); [timely-dataflow book](https://timelydataflow.github.io/timely-dataflow/) and [differential-dataflow book](https://timelydataflow.github.io/differential-dataflow/).
- Materialize: [reclocking design doc](https://github.com/MaterializeInc/materialize/blob/main/doc/developer/design/20210714_reclocking.md) and [persist / formalism](https://github.com/MaterializeInc/materialize/blob/main/doc/developer/platform/formalism.md).
- Budiu et al., "DBSP: Automatic Incremental View Maintenance" (VLDB 2023), [arXiv:2203.16684](https://arxiv.org/abs/2203.16684).
- [Feldera fault tolerance](https://docs.feldera.com/pipelines/fault-tolerance).

**Timely: timestamps and frontiers delimit batches.** Every record carries a logical timestamp, which may be partially ordered, for example (epoch, loop-iteration). An operator holds *capabilities* to produce output at particular times. The system tracks pointstamps and gives each operator its input *frontier*: an antichain of times that may still arrive. "The frontier has passed *t*" is the *only* completion signal. It acts as a per-epoch end marker computed by the system, not sent by the producer. An operator may buffer data for time *t* and act once the frontier passes *t*: this is "notification". **[doc]** Batching is decoupled from correctness. Records of one epoch may arrive in any number of physical batches, and the operator only commits its output for *t* when the frontier passes. **[doc / inf]**

**Differential: collections as update streams.** A collection is a stream of `(data, time, diff)` triples with integer (or ring-valued) diffs. Operators are incremental: they maintain *arrangements*, which are indexed, shared, compacted traces, and emit only output diffs. Compaction advances times below a `since` frontier, so history before it collapses. **[doc]**

**Materialize.**
- **Reclocking.** External source offsets are bound to Materialize timestamps by a durable "remap" collection, a mapping from timestamp to source frontier. It is written *before* data is considered ingested, so a restart re-derives exactly the same batches for the same timestamps. That is how ingestion becomes exactly-once and deterministic. **[doc]**
- **Persist shards.** Each shard has an `upper` (writes are complete below it) and a `since` (reads are valid at or after it). Writers append with `compare_and_append(expected_upper, new_upper, updates)`. The CAS on `upper` fences a zombie writer and makes each batch an atomic, gap-free interval `[lower, upper)` of time. **[doc]**

This is the cleanest formulation of "batch delimiting and commit" in the survey: a batch is a half-open time interval, and commit is a CAS on the upper frontier. **[inf]**

**Feldera / DBSP.** DBSP models a query as a circuit over streams of Z-sets (multisets with integer weights). The incremental form of any query Q is `Q^Δ = D ∘ ↑Q ∘ I`, where `I` integrates changes and `D` differentiates them. The paper derives operator-by-operator incremental rewrites, including recursion via nested time. Execution is a sequence of *steps*. Each step consumes whatever input changes have been buffered, as one Z-set per input, and produces one output delta. The step is the batch, and the runtime chooses its size: everything queued, subject to connector limits. **[doc / inf]**

Feldera fault tolerance has two modes:
- **At-least-once:** periodic checkpoints of operator state plus connector positions, every 60 s by default.
- **Exactly-once:** adds an input/output journal *per step*. Restart loads the checkpoint, replays the journal, and suppresses output that was already sent.

Some sources (Delta Lake, Iceberg) can only checkpoint at certain input positions, so a checkpoint waits for one. **[doc]** Feldera also supports modifying a pipeline's SQL and "bootstrapping" new or changed views from retained state. **[recalled from Feldera docs, not verified in this pass]**

**Relevance [inf].**
- **Bounded per-batch cost.** Incremental (diff-based) operators bound per-batch cost by the *size of the change* rather than the state. That fits Cordis's static per-turn cost estimate only if state lookups are indexed (arrangements). A CEL handler that scans its accumulator costs O(state) per batch.
- **Delimit by position, not by count.** Batches should be delimited by an *interval of source positions* (`[lower, upper)`), committed by CAS on `upper`. The batch *count* is then irrelevant to correctness, and resume means "continue from committed upper".
- **End of input.** An end marker is `upper = ∞`, the frontier-closed state, not a sentinel record.
- **Durable boundaries.** Store the batch boundaries durably before processing (Materialize remap, Rama's per-ID offset range). Without that, replay after a crash may re-split the input differently.

## 6. Temporal

Sources: [Workflow versioning / patching (Go, TS, Java, Python SDK guides)](https://docs.temporal.io/develop/go/versioning); [Worker Versioning](https://docs.temporal.io/worker-versioning); [Continue-As-New](https://docs.temporal.io/workflow-execution/continue-as-new); [event history limits](https://docs.temporal.io/workflow-execution/limits).

**Model.** A workflow is deterministic code whose every effect (activity calls, timers, child workflows, signals received) is recorded in an append-only *event history*. On recovery or cache eviction, the worker *replays* the code against the history. Recorded results are returned instead of re-executing the effects. Activities are at-least-once; dedup comes from idempotency keys. The workflow *state* is never stored, only the history, so state is a function of code and history. **[doc]**

**Changing running code.** Because state is re-derived by replay, any code change that alters the *sequence of commands* for an existing history raises a non-determinism error. There are three remedies:
1. **Patching** (`workflow.GetVersion(ctx, "change-id", DefaultVersion, 1)` in Go; `patched("id")` / `deprecatePatch("id")` in TS and Python). The first time new code passes the patch point it records a marker in the history. Old executions replaying old histories take the old branch, and new ones take the new branch. The lifecycle has three steps: add the patch → wait until no open executions predate it → `deprecatePatch` → remove it. **[doc]**
2. **Worker Versioning** (Build IDs / deployment versions). Each execution is *pinned* to the worker version it started on, or marked *auto-upgrade* to move to the current version at its next workflow task. Old worker versions drain, and you retire them when their executions close. **[doc]**
3. **Continue-As-New.** Atomically finish the current run and start a new one with the same workflow ID, passing compact state as input. This truncates history, which is limited to 51,200 events / 50 MB, with warnings well below. It is also the natural place to pick up new code. **[doc]**

**Analogy to Cordis [inf].**
- **Two ways to carry state.** Temporal's "pinned vs auto-upgrade" is exactly the choice Cordis makes per revision:
  - finish the in-flight stream on the old cell (pinned / drain);
  - switch at the next turn boundary (auto-upgrade).

  Cordis currently only has the second: retire, then insert. Offering "drain the current frame on the old fiber, then cut over" covers accumulators whose meaning changes mid-stream. That is the case "state carried if the new type reads it" handles badly, because the type matches but the semantics don't.
- **Turn boundaries.** Temporal switches code only at a *workflow task* boundary, never mid-task. That matches "cutover only between batches", and Temporal treats it as a safety invariant.
- **Patch markers.** A patch marker is a *recorded decision point* in the log. The Cordis analogue is to write the revision ID into the stream's commit record at cutover. Replay after a crash then knows which code processed which batch, and frames.qnt can state "batches ≤ k were processed by rev r, > k by r′" as an invariant.
- **Continue-As-New.** It shows why the *journal* needs a bound, not just the stream buffer. A long-lived frame consumer's receipts and outbox grow without limit unless the commit record compacts them. Cordis's committed cursor plus current state is the compacted form.

## 7. Brief notes

**Spark Structured Streaming** ([guide](https://spark.apache.org/docs/latest/streaming/index.html)).
- **Default micro-batch mode.** Each trigger plans a batch from source offsets. The planned offset range is written to a *write-ahead offset log* (`offsets/<batchId>`) **before** the batch runs, and the `commits/<batchId>` entry after it. On restart, an incomplete batch is re-run with *the same offsets*, so batch boundaries are deterministic. State stores (HDFS-backed or RocksDB) are versioned per batch ID. End-to-end exactly-once requires replayable sources and idempotent sinks; `foreachBatch` exposes `batchId` for dedup. Batch size is controlled by source options such as `maxOffsetsPerTrigger` / `maxFilesPerTrigger`, plus `Trigger.AvailableNow` for "process everything up to now in rate-limited batches, then stop". **[doc]**
- **Upgrades.** The guide lists which query changes may restart from an existing checkpoint. Changing stateful operators or their schema is "not allowed", so a changed stateful query generally needs a fresh checkpoint. **[doc]**
- **Lesson.** The two-log (offsets / commits) pattern is exactly frames.qnt's "resume from the last committed batch". **[inf]**

**Arroyo / RisingWave.**
- **Arroyo** ([docs](https://doc.arroyo.dev/)) is a Rust, Flink-style engine with Chandy–Lamport barrier checkpoints and two-phase-commit sinks. It is built on Arrow `RecordBatch`es, so its operators process columnar batches, which is close to Cordis frames. **[doc / inf]**
- **RisingWave** ([docs](https://docs.risingwave.com/)) is a streaming database. It injects *barriers* at a fixed interval (`barrier_interval_ms`, ~1 s) and checkpoints every N barriers (`checkpoint_frequency`) to its Hummock LSM store on object storage. The barrier epoch is both the consistency unit and the visibility unit. Backpressure propagates via bounded exchange channels. **[doc]**
- **Lesson.** Columnar batch processing composes with barrier epochs; the epoch need not equal the physical batch. **[inf]**

**Hydro / Hydroflow (DFIR)** ([hydro.run](https://hydro.run/), Hellerstein et al.).
- **Ticks.** Execution proceeds in *ticks*. Within a tick, a dataflow runs to fixpoint over the inputs that arrived. Operators are typed by *lattice* semantics (monotone merges), which makes a computation's coordination-freeness checkable. Persistence across ticks is explicit (`persist()` and similar), which separates per-batch state from accumulated state. **[doc / inf]**
- **Lesson.** Let the type system distinguish "per-batch" from "carried" state, and require accumulators to be *lattice merges / combiners* when you want reordering, splitting or two-phase aggregation. **[inf]**

**Dagster assets** ([docs](https://docs.dagster.io/guides/build/assets/)).
- **Software-defined assets.** An asset is a function plus its upstream dependencies. Materialisations are recorded with metadata and a `code_version`. The `data_version` / staleness machinery marks an asset stale when its code version or an upstream data version changes. **Partitions** (time or static) make a large asset into independently materialisable batches, and *backfills* run them in bounded runs. **[doc]**
- **Lesson.** It maps directly onto Cordis's content-hashed assets. The code version belongs in the memo key, or a revision silently reuses outputs computed by old code. Partitioned assets are the batch-oriented counterpart of frame streams: a frame consumer over a partitioned source could commit per partition. **[inf]**
