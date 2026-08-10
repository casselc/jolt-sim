# jolt-sim implementation roadmap

Updated: 2026-08-06

Status: live execution roadmap for pre-release development

Current platform baseline: Jolt 0.6.1, simulation controller ABI 6, FFI
descriptor version 8, clock descriptor version 1, and Chez Scheme 10.4.1.

This is the current implementation order and acceptance boundary for
`jolt-sim`. It does not replace the research packet or its adversarial reviews.
Those documents remain design inputs and audit history; live source, current
upstream Jolt behavior, and executed evidence take precedence when they drift.

## Product goal

`jolt-sim` should run ordinary Jolt application and library code under a
controlled environment, not require a second application written against a
simulator-only API.

The same application body should be runnable in three modes:

1. **real** — ordinary native libraries and operating-system resources;
2. **hermetic** — deterministic handler/model implementations; and
3. **hybrid** — an explicit mixture of modeled, observed, and guarded real
   native effects.

`defsim` is the intended scenario boundary: it declares inputs, providers,
faults, schedules, virtual services, and search policy around a vanilla Jolt
body. Libraries keep their production APIs. Library authors should be able to
add a boundary model, canonical projection, observations, and invariants in an
optional simulation namespace without modifying simulator internals.

The canonical whole-ecosystem application is a durable outbox flow:

```text
HTTP command
  -> pure application transition
  -> SQLite transaction and durable outbox row
  -> TCP/bencode delivery worker
  -> delivery outcome, retry/cancellation, and offline checks
```

It is deliberately one application, not a collection of unrelated demos. Its
scenarios should organically exercise Jolt core plus bytes, codecs, FFI, DB,
networking, TCP, HTTP, clocks, process isolation, Hegel, replay, and monitoring.

## Evidence and execution rules

- Label claims as source inspection, runtime evidence, simulated behavior,
  bounded proof, or heuristic search.
- A worker deadline means only that the worker missed its deadline. It is not
  a deadlock, livelock, quiescence, or liveness proof.
- Retain exact replay inputs and discriminating buggy controls.
- Preserve failed, timed-out, crashed, and incomplete worker artifacts before
  cleanup. Successful cases may be compacted or removed by declared policy.
- Do not infer causality from callback, log, or collection order. Emit an edge
  only when the producing boundary establishes it.
- Treat state-limit exhaustion as inconclusive.
- Run one local build or memory-heavy gate at a time. Prefer existing pinned
  images and focused gates before aggregate CI.
- Land bounded, independently reviewed slices on public branches with draft
  PRs and restart-safe evidence. Pre-release contracts may be reminted when a
  better design is found; compatibility is not a goal until the first release.
- Runtime/compiler work must unblock application behavior, correctness, or a
  required control seam. Building compilers is not itself a milestone.

## Current checkpoint

| Capability | State | Accepted evidence |
| --- | --- | --- |
| Jolt 0.6.4 / ABI 6 / FFI descriptor 8 simulation image | Aggregate branch pins the thread-safe runtime cache and native-Windows selected-shell fixes; focused compiler, exact retained-crash replay, JSON outbox, and generated outbox gates are green; hosted jolt-sim revalidation pending | Integration `79c926aa`; merged `casselc/jolt#38` and `#39`; root-cause cache fix uses owner-tagged per-thread weak hasheq caches; Windows shell fix selects and quotes `JOLT_SH` for cold dependency preparation; core hasheq concurrency gate passed with 8 workers x 25,000 operations and 367 automatic collections, values `70/70`, isolated unit `1236/1236`; native Windows selected-shell and cold dependency job plus full Linux Jolt gate passed; jolt-sim JSON outbox `74/74`; exact PR #67 crash request completed without a core; outbox Hegel `17 tests / 79 assertions`; prior integration evidence: variadic FFI `76542302`, reminted seed `cf240c7b`, exact target facts `9c4c75ef`, Windows absolute-root fix `c2a8285b`; unchanged consumers: POSIX `95/95`, HTTP `33/33`, TCP/bencode `101/101`, SQLite `34/34`, webhook simulation `149/149` |
| Jolt 0.6.1 / ABI 6 dedicated simulation image | Historical hosted-green baseline retained | Selected child `5c885d14` / draft PR `casselc/jolt#34`; image `c2b520b1` / draft PR `casselc/jolt#35`; hosted runs `31007539524`, `31007560952` |
| Jolt 0.5.20 / ABI 6 simulation core | Historical green baseline retained | `9fc64f93`; hosted run `30857980580`; draft PR `casselc/jolt#26` |
| Jolt 0.5.17 hosted platform and Windows native-path evidence | Historical baseline retained | `9b5cd6b`; draft PR `casselc/jolt-sim#21` |
| Pure durable-outbox application transition | Landed | `5d2c61e`; draft PR `casselc/jolt-sim#22` |
| Real SQLite adapter using ordinary `jdbc.core` | Landed | `2deb01d`, aggregate registration `5663ca4`; draft PR `casselc/jolt-sim#23` |
| SQLite table-row model and unchanged-adapter parity | Landed on the active stack | Row model `1639507` / draft PR `casselc/jolt-sim#25`; parity integration `47fc0e5`, review corrections `7772c48` / draft PR `casselc/jolt-sim#26` |
| Framed TCP/bencode example and hosted lanes | Landed as an input to the canonical app | `6e87657`, `386b9d5`, diagnostic follow-up `51f7018` |
| Whole HTTP -> SQLite -> TCP outbox application | Real/hermetic base and generated workload/capacity/poll-fault slice are hosted and green | Base draft PR `casselc/jolt-sim#28`; Hegel draft PR `#29`; hosted run `30861666592`; Hegel `2 tests / 5 assertions` over 2 boundaries plus 15 generated cases; aggregate `513 / 4,042`; Phase 3 below |
| Scoped reset and ordinary retry | The unchanged DB/TCP application now closes the failed connection, reloads the pending row, and retries after one receiver-port-scoped read reset | `2d41266`; draft PR `casselc/jolt-sim#33`; exact retry boundaries plus 15 generated fresh-process cases; Phase 3 below |
| Whole-case evidence and static reports | Versioned Case/Outcome, parent-owned monitor verdicts, stable failure/success bundles, and deterministic HTML are integrated and retained by bounded CI post-processing | Schema `f44be7a` / PR `#35`; harness `e0aa464` / PR `#36`; reporter `69e9abb` / PR `#37`; aggregate retention `8c7b270` / PR `#38`; exact aggregate outbox gate `9 tests / 48 assertions`; real retained report `41,200 bytes` |
| Ripple trusted Run-new example | Server-owned presets run the ordinary compiled cancel-before-ack outbox application, the unchanged `jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip` defsim scenario, the unchanged `jolt.maelstrom.fixtures.broadcast-scenario/broadcast-partition-heal` defsim scenario with its exact healthy and partition/heal inputs, and the unchanged routed-JSON outbox scenario under exact-replay and conflicting-replay regimes, all under the truthful `:hermetic` profile; the browser receives only safe catalog metadata and inert plan projections, while topology, progress, retained activity, and outcome reuse the shared viewer models | Current JSON idempotency integration slice; canonical plans `viewer/examples/outbox-cancel-before-ack-plan.edn`, `viewer/examples/maelstrom-echo-plan.edn`, `viewer/examples/maelstrom-broadcast-plan.edn`, and `viewer/examples/outbox-json-idempotency-plan.edn`; screenshot `viewer/docs/ripple-run-new-outbox.png`; local gates on compatible image `jolt v0.6.4-20-g3bbf0673`: focused viewer `119 / 1,166`; real-process E2E `7 / 269`; Playwright `14 / 14`; full outbox Hegel family `17 / 79`; full source suite `814 / 8,282` using exact public `jolt-core` source overlay `d040d502`; counts below are the last hosted run before the Echo preset slice: focused viewer `90 / 882`; real-process E2E `3 / 49`; Playwright `7 / 7`; full source suite `743 / 7,676` |
| Ack-gated durable delivery marking | Integrated on the current stack: ordinary and retry application paths validate the exact ack before one guarded `pending` → `delivered` transaction and final reload | Adapter API `f6f7538` / PR `#39`; guarded model plus hostile-ack, close-error, and concurrent-marker controls in the current slice; unit `557 / 4,677`; SQLite parity `1 / 47`; sim-only `3 / 51`; real/hermetic `3 / 52`; fresh-process Hegel `9 / 48` |

Phase 1 was last run on Linux x86-64 using the prior simulator image
`jolt v0.5.17-13-g3af5622d`
(`sha256:e84f8d764a8f44f8458d05f4a4e98f02676d903c4207782fd5c1e50ba1f3e7ab`):

- table-row model: 60 tests / 1111 assertions;
- unchanged-adapter real/hermetic/hybrid parity: 1 test / 47 assertions;
- ordinary transactional SQLite outbox: 13 tests / 94 assertions;
- existing real SQLite integration: 1 test / 19 assertions; and
- dependency-light aggregate: 507 tests / 4008 assertions.

Every gate ran serially with isolated writable HOME/cache/temp state and a
retained transcript under the workspace-root-relative directory
`artifacts/jolt-sim-outbox-sqlite-parity-integration/`. The initial parity run
is also retained: it exposed a stale five-versus-six mutation assertion before
the reviewed correction. The older adapter-only aggregate remains preserved at
`artifacts/jolt-sim-outbox-sqlite-adapter/final-20260803/full-test-direct.log`.

[Hosted run 30831219495](https://github.com/casselc/jolt-sim/actions/runs/30831219495)
previously exposed one finite TCP/bencode timeout for stream capacity 1, pipe
capacity 4, frontend-wide poll-EINTR ordinal 8, and mixed Unicode. It remains
timeout/flakiness evidence, not a liveness classification.

## Ordered implementation phases

### Phase 1 — unchanged SQLite adapter parity

Status: complete on the active pre-release stack. The requirements below are
the accepted Phase 1 boundary; later SQL/model breadth must be justified by a
real application scenario.

The accepted surface is the smallest table-row behavior required by the
already-landed adapter:

- explicit `:insert-row`, `:update-row`, and ordered `:scan-rows` statement
  effects, with no SQL parser;
- committed/staged visibility through the existing transaction overlay;
- deterministic typed ordering and immutable first-step scan snapshots;
- dynamic constraint and affected-row behavior;
- address-free mutation and source-provenance evidence; and
- exact real, hermetic, and hybrid execution of one unchanged adapter scenario.

Acceptance evidence includes focused validation, transaction, typed-value,
snapshot, and step/finalize/close race tests; exact semantic parity; complete
plan consumption; clean worlds; and a serial aggregate gate. Secondary
unique-index modeling and general SQL semantics are explicitly outside this
phase.

### Phase 2 — failure preservation and stable replay coordinates

Status: the first ordinary-runtime evidence spine is implemented in draft PRs
`casselc/jolt-sim#35` through `#38`. A strict Case/Outcome document owns the
case, terminal outcome, replay schedule, and ordered parent-owned monitor
decisions. The outbox Hegel harness retains original request/result/stdout/
stderr trees for parent-observed non-successes, exports a never-overwritten
stable bundle only after the child is quiescent, and preserves one successful
retry boundary witness. CI renders complete documents under bounded time and
then uploads raw, partial, and rendered trees from an `always()` step.

The stacked report-v2 candidate adds a human/agent inspection layer without a
second runtime: every result section remains visible, canonical outbox cases
gain an evidence-only journey, and the validated Case coordinate can be handed
to `jolt.sim.repl/replay-document!` for one fresh supervisor run. Runtime
configuration stays separate and cannot replace retained scenario, input, or
schedule. Case mode is provenance until the ordinary worker protocol exposes
an enforceable mode coordinate.

This closes the immediate parent-observed artifact-loss boundary. It does not
provide crash-atomic record writes, machine-crash durability, or the Phase 5
append-only journal; catastrophic runner cancellation can still prevent a CI
artifact upload.

The accepted phase boundary remains:

- retain request, result, stdout, stderr, controller/fault evidence, exit
  status, and environment/capability metadata for failed and timed-out workers;
- land the minimal versioned Case/Outcome envelope needed to carry those
  inputs, coordinates, classifications, and retained artifact references;
- publish the retained artifact directory in the returned outcome;
- make cleanup policy explicit and test success cleanup separately from
  failure retention;
- replace the frontend-wide poll attempt ordinal with a stable coordinate
  scoped to the relevant poller/operation and local attempt; and
- reproduce the observed EOF-mid-frame case as a permanent replay regression,
  while preserving timeout as a neutral deadline outcome.

This is the immediate bridge to the later append-only journal. It should not
grow into the full journal or telemetry design in this phase.

### Phase 3 — canonical whole-ecosystem outbox application

Build outward from the same pure core and SQLite adapter:

- HTTP request decoding and command admission;
- stable request, transaction, outbox, delivery, and attempt identities shared
  by real, hermetic, and hybrid observations;
- byte-exact request and response payloads through the codec/bytes layers;
- atomic durable enqueue in SQLite;
- a TCP/bencode delivery worker reading pending outbox rows;
- delivery marking, retry, cancellation, deadline, close, and crash boundaries;
- explicit real, hermetic, and hybrid providers; and
- Linux, macOS, and Windows capability classification.

The first implementation slice was intentionally narrower than Phase 3's final
boundary: one ordinary bencoded HTTP POST commits one SQLite outbox row, reloads
that pending row, sends it through the existing framed TCP/bencode stack, and
receives an outbox-id/attempt acknowledgement. It records stable request,
transaction, outbox, delivery, and attempt identities and runs the same body in
real and hermetic modes. The acknowledgement does not mark the row delivered;
retry, marking, cancellation, schedule/admission search, and POSIX hybrid
classification were later feature slices rather than hidden claims of that
initial witness. Subsequent slices added ack-gated marking, retry, clean
reopen, post-COMMIT process recovery, and cancel-before-ack without replacing
the ordinary application.
The first slice used one in-memory SQLite connection and therefore established
post-COMMIT reload only. Later stacked slices now add clean file-backed
close/reopen continuity and a separate real process-exit/recovery witness.

The routed-JSON expansion reuses that same application rather than adding a
simulator-side controller. A production Reitit/data.json handler accepts one
fresh command over a real or hermetic jolt-http server; the existing durable
reload must corroborate its response projection before the existing
TCP/bencode worker may deliver and mark the row. The wrapper adds no SQLite
observer query, so both command encodings consume the same 24-statement
application transcript. The two-request exact-replay/conflict workload now
runs both as a trusted Ripple preset with retained viewer evidence and as a
bounded Phase 4 Hegel lane with two exact boundaries plus six generated cases.

Ripple now exposes trusted **Run new** presets: the compiled
cancel-before-ack application, the ordinary Maelstrom Echo defsim
scenario (`jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip`) with one
exact nested Unicode input and no schedule, the Outbox first-poll regime lab
with its ten application-owned regimes, the Maelstrom Broadcast
partition/heal defsim scenario
(`jolt.maelstrom.fixtures.broadcast-scenario/broadcast-partition-heal`) with
its exact healthy and partition/heal inputs as the two trusted regimes, and
the routed-JSON idempotency lab over the existing marked
`jolt.sim.fixtures.outbox-json-delivery-scenarios/exercise-replay-or-conflict`
scenario with its exact-replay and conflict modes as the two trusted regimes
(`viewer/examples/outbox-json-idempotency-plan.edn`). The
server owns the allowlisted scenario, canonical input, schedule, `:hermetic`
profile, worker configuration, and
artifact policy; the browser receives only the preset identity/display fields
and the validated inert experiment-plan projection. The UI renders the
four-node outbox topology, the two-endpoint Echo topology
(`viewer/examples/maelstrom-echo-plan.edn`), or the four-node ten-edge
Broadcast line topology (`viewer/examples/maelstrom-broadcast-plan.edn`) and
then consumes the existing progress, retained semantic
activity, and terminal outcome models. Each is one interactive fresh-process
witness, not the two-worker real/hermetic parity proof. The next bounded
viewer execution slices are parameterized-but-validated inputs and a second
truthful executable profile.

Ripple's longer-term live-development boundary is recorded in
[`research/RIPPLE-REPL-DEBUGGER-INTEGRATION.md`](research/RIPPLE-REPL-DEBUGGER-INTEGRATION.md):
one shared Jolt evaluation engine adapted to the textual REPL, a
prepl-compatible structured stream, and nREPL; a separate revision-scoped
Session control protocol; and a canonical out-of-band Ripple-in-Ripple e2e
scenario. This follows the ordinary HTTP/SQLite/TCP application workflow and
must not displace it with debugger protocol scaffolding. The socket-free
`jolt.sim.eval-stream/evaluate!` subset now emits data-oriented `:out`, `:err`,
and terminal `:ret` events over the shared Jolt evaluation engine. It does not
yet provide a socket/prepl transport, tap lifecycle, remote Session attachment,
browser live evaluation, or Ripple-in-Ripple debugging.

The next stacked slice, draft PR `casselc/jolt-sim#29`, keeps that application
body unchanged and runs every case in a fresh sim-enabled process. Hegel owns
and shrinks the command payload octets, stream capacity, pipe capacity, and one
captured poll-interruption ordinal. Two explicit payload boundaries plus 15
generated cases require every value of each declared finite capacity/fault
axis to appear, but do not enumerate the axes' Cartesian product. Payloads are
sampled at the recorded seed. Every executed case asserts exact application
results and fixture-result SQLite statement evidence, handler-only routing,
and clean worlds; the scripted statement transcript is not a general SQLite
conformance claim. Completed worker artifacts remain available until the
parent semantic verdict and are removed only after all assertions pass.

The next feature slice, draft PR `casselc/jolt-sim#33`, injects one captured
receiver-port-scoped read reset after the receiver reaches its acknowledgement
boundary. The unchanged application accepts retry only for the typed read/
connection-reset outcome, requires first-attempt cleanup, closes the first TCP
connection, reloads the still-pending row through the same open SQLite
connection, and observes a correlated second acknowledgement. This is retry
evidence, not close/reopen persistence, delivery marking, exactly-once
delivery, or real-kernel reset parity.

Draft PRs `casselc/jolt-sim#35` through `#38` wrap those ordinary fresh-process
cases in the Case/Outcome evidence spine without changing the application or
library bodies. On 2026-08-04 the exact v0.5.20 simulation image completed both
the ordinary-delivery and retry campaigns serially: each ran two explicit
boundaries plus 15 generated cases; the combined runner reported 9 tests / 48
assertions with zero failures/errors. The configured transient run root was
empty after passing cleanup, one retry boundary bundle was retained, and the
exact CI report command rendered its document to a 41,200-byte self-contained
HTML file.

Hosted workflow run `30893724229` then completed every registered lane for
draft PR `#38`: Linux x86-64 and ARM64, macOS x86-64 and ARM64, Windows x86-64,
and the Windows ARM64 source suite. The Linux x86-64 lane also completed the
Hegel campaign, bounded Case/Outcome rendering, and unconditional artifact
preservation path.

The next API slice, draft PR `casselc/jolt-sim#39`, adds a pure idempotent
pending-to-delivered transition and a durable SQLite `mark-delivered!` adapter
operation. The adapter runs the transition inside the required outer
transaction boundary, uses an exact guarded status update, requires an
affected-row count of one, and rolls back after either a post-mutation failure
or a count mismatch. With the exact v0.5.20 simulation image, the pure gate
reported 16 tests / 181 assertions, the real-SQLite gate 22 / 187, the aggregate
suite 554 / 4,579, SQLite parity 1 / 47, and the unchanged delivery witness
1 / 27, all green. At that API checkpoint the whole-application fixture did not
yet call the marking operation.

The current integration slice closes that application boundary without a
simulator-specific application path. Both the ordinary and scoped-reset retry
flows keep their ordinary HTTP, JDBC/SQLite, TCP, bytes, and bencode bodies;
they validate the exact correlated acknowledgement before calling
`mark-delivered!`, then require the returned delivered state to equal a final
reload. The SQLite model separates physical row identity from an optional
typed equality `:where` guard, with SQL NULL nonmatching semantics, and applies
the guard decision plus mutation in the same world CAS. Exact negative controls
prove that a hostile acknowledgement never claims the mark transaction, two
racing markers produce one applied transition plus one guard miss, and a
reported SQLite close failure cannot replace the primary acknowledgement
failure. The statement scripts now consume 24 plans for ordinary delivery and
27 for retry. On the exact v0.5.20 image, the final serial gates reported unit
`557 / 4,677`, SQLite parity `1 / 47`, sim-only delivery `3 / 51`, real/hermetic
delivery `3 / 52`, and fresh-process Hegel `9 / 48`, all green. Delivery remains
at least once: a crash after remote acknowledgement but before durable marking
may redeliver.

Draft PRs `casselc/jolt-sim#41` through `#43` add an immutable modeled SQLite
file image, run the unchanged application across two sequential connections,
and exercise that clean reopen path in fresh Hegel workers. The focused real
and hermetic gate proves pending-to-delivered continuity across clean reopen;
the modeled campaign does not claim pager/WAL or power-loss behavior.

The next real-native slice exits a producer worker with status 86 after the
ordinary HTTP command has returned across SQLite COMMIT and written a closed
checkpoint, then starts a fresh recovery worker with only the retained database
path. Recovery reloads the pending row before any TCP send, validates the
correlated bencode acknowledgement, marks the row delivered, and closes. The
parent snapshots the quiescent post-producer database, checkpoint, and every
present SQLite sidecar before recovery can mutate them. It retains that raw
pending-state image, the final delivered database, both worker trees, parent
outcomes, progress records, and a final manifest even on success. This is
deliberate process-exit/file-survival evidence, not SIGKILL, machine-crash,
fsync, power-loss, torn-write, append-only-journal, or exactly-once evidence.

The simulation layer may provide boundary handlers and models. It must not
replace the HTTP, DB, TCP, codec, or application implementation with a second
simulator-specific implementation.

End-to-end examples should cover at least a simple web form/API call, a raw TCP
protocol client/server exchange, exact binary payloads including empty and
embedded-zero values, durable restart/retry, and one injected native failure.

### Phase 4 — Hegel workload, fault, schedule, and replay search

Status: workload/capacity/poll-fault search is implemented in draft PR
`casselc/jolt-sim#29`; semantic FFI admission-order search is stacked in PRs
`#30` and `#31`; scoped-reset retry is exercised in PR `#33`; the canonical
Case/Outcome/reporter spine is stacked in PRs `#35` through `#38`; and
ack-gated durable marking and clean reopen paths now run through generated
campaigns. Cancellation-before-ack is executable, and the current stacked
terminal candidate generates and shrinks one closed absolute-deadline or
cancellation action across post-COMMIT, pre-ack, and pre-mark boundaries while
fixing axes already covered elsewhere. The current lanes do not yet vary crash
actions, broader future admission, or one- and two-byte HTTP fragmentation;
the real process-exit witness is one fixed boundary rather than a generated
crash axis. Those remain bounded slices around the same ordinary application,
not alternate simulator implementations.

As the complete app gains those modes:

- extend the canonical Case/Outcome schema only when new workload, provider,
  fault, schedule, or replay coordinates require it;
- generate and shrink application workloads, not only scheduler integers;
- sample schedule, fault, capacity, deadline, cancellation, and crash channels;
- accept a shrink only when the complete trace replays and the failure remains;
- retain exact minimal cases as ordinary regression fixtures;
- add rank-spread as one comparison strategy; and
- add a small corpus/feature-guided selector using resource handoffs,
  runnable-set width, preemptions, queue occupancy, timer collisions, and
  close/cancel/complete overlap.

Coverage guidance is heuristic. Exhaustive claims remain limited to a stated
finite model and nonbinding bound.

### Phase 5 — causal evidence, offline monitors, and crash-safe journal

Drive the evidence design from the canonical application:

- versioned run, task, operation, resource, transaction, request, outbox,
  delivery, cancellation, and fault identities;
- producer-established causal edges and canonical real/model projections;
- offline safety, ownership, transaction/outbox consistency, and
  liveness-with-explicit-assumptions monitors;
- post-hoc comparison against bounded proof obligations and replay models;
- a crash-safe append-only journal with a proved record/commit/recovery model,
  checksums, truncation recovery, and forensic retention; and
- a later OpenTelemetry adapter that maps the same semantic events to
  spans/events without making OTel a kernel dependency.

Use proven WAL/journaling ideas and prove the small durability kernel before
implementation. Do not substitute an atom or agent for durable ordering.

### Phase 6 — extension surface for libraries and applications

Stabilize a small, versioned author-facing contract for new boundaries:

- boundary descriptor and capability declaration;
- real, hermetic, observed, and hybrid handler/provider registration;
- canonical value projection and redaction;
- semantic observations and optional causal edges;
- invariants/monitor hooks;
- fault families and replay coordinates; and
- scenario configuration through `defsim`.

Effect families remain open and application-extensible. The compiler/runtime
reserves a small core set only where interception or safety requires it.
Library production namespaces should not depend on `jolt-sim`.

### Phase 7 — broader ordinary-runtime control

Expand disabled-by-default Jolt hooks only when a real scenario needs them:

- nested thread/future/promise and executor admission;
- atoms and synchronization boundaries;
- locks, conditions, park/unpark, and core.async;
- virtual monotonic time, timers, entropy, and completion/cancellation; and
- raw FFI/native calls with explicit modeled, observed, hybrid, or forbidden
  policy.

A special compiler/debugger mode may eventually provide FlowStorm-like tracing,
inspection, injection, and richer source coordinates. It is a possible home for
long-lived experimental Jolt features, not a prerequisite for the application
milestones above.

### Phase 8 — release readiness

Before calling `jolt-sim` complete or making a first public release:

- run the canonical app and focused controls across the supported Linux,
  macOS, and Windows architecture matrix;
- publish a scenario-to-library and platform capability manifest;
- document real/hermetic/hybrid limits and native escape policy;
- prove same-seed/same-input trace determinism where claimed;
- retain replayable counterexamples and forensic artifacts in CI;
- document the library-author extension path; and
- collapse the current pre-release ABI/descriptor history to a coherent v1
  unless an external compatibility obligation exists by then.

## Research packet disposition

The original packet is preserved in
[`casselc/jolt-sim-planning`](https://github.com/casselc/jolt-sim-planning).
Its package names remain useful cross-references, but they no longer dictate
the critical path.

| Original material | Current disposition |
| --- | --- |
| [Research backlog](https://github.com/casselc/jolt-sim-planning/blob/main/RESEARCH_BACKLOG.md) | Evidence discipline and findings retained; baseline facts require v0.5.20 / ABI 6 refresh |
| [P0 evidence and contract clarifications](https://github.com/casselc/jolt-sim-planning/blob/main/P0-evidence-and-contract-clarifications.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P0-adversarial-review.md) | Fold small wording and contract tests into adjacent slices; not a feature blocker |
| [P1 rank-spread schedules](https://github.com/casselc/jolt-sim-planning/blob/main/P1-rank-spread-schedules.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P1-adversarial-review.md) | Retain as an optional Phase 4 search baseline; reuse existing permutation arithmetic |
| [P2 fault frontend state and bounds](https://github.com/casselc/jolt-sim-planning/blob/main/P2-fault-frontend-state-and-bounds.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P2-adversarial-review.md) | Defer the large opacity/checkpoint design until real corruption or load evidence requires it; implement only observed blockers |
| [P3 causal observation envelope](https://github.com/casselc/jolt-sim-planning/blob/main/P3-causal-observation-envelope.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P3-adversarial-review.md) | Refresh in Phase 5 around ABI 6 and real application producers; keep producer-established edges and make the producer surface extensible |
| [P4 quiescence and bounded response](https://github.com/casselc/jolt-sim-planning/blob/main/P4-quiescence-and-bounded-response.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P4-adversarial-review.md) | Derive a small reusable obligation kernel from the real outbox flow; do not prioritize the bespoke toy model |
| [P5 guided search and causal faults](https://github.com/casselc/jolt-sim-planning/blob/main/P5-guided-search-and-causal-faults.md) and [review](https://github.com/casselc/jolt-sim-planning/blob/main/P5-adversarial-review.md) | Highest-value research package after Phase 3; implement the smallest schedule/case corpus against real examples before causal-selector breadth |

The following workspace research artifacts also remain part of the decision
record and should be archived into the planning repository without rewriting
their historical baselines:

- `JOLT_SIM_RESEARCH_BACKLOG.md`
  (`sha256:361c701145d592314c99dfc405575390bdfe0c93171e93e607ed7f743368ac3c`);
- `JOLT-SIM-MAELSTROM-FLOW-IMPLEMENTATION-PLAN.md`
  (`sha256:23b66b2c0f084360bc5115c32de637f11a53c2c7d687f98cc10d5f143523786c`);
- `jolt-sim-architecture-review-2026-08-01.md`
  (`sha256:6a98b4d0a4494cc1d14b8bf5599e3f7182f34b4819bc2dfc1e6f82cc5d40288f`);
- `JOLT-FORMALIZABLE-APPLICATION-CORE-RESEARCH-PLAN-2026-08-01.md`
  (`sha256:da2ac051ea090cb826e079da7f04feadf95540527dff626d0cdd9808e337166f`);
  and
- the accepted Application Core charter, decision memo, P1–P14 reports, and
  runtime-seam requests on
  [`casselc/jolt` draft PR #12](https://github.com/casselc/jolt/pull/12).

The PR #12 source documents are
`docs/research/APPLICATION-CORE-SEMANTIC-CHARTER.md`,
`docs/research/DECISION-MEMO-2026-08-01.md`,
the `P1` through `P14` artifacts under `docs/research/reports/`,
`docs/research/APPLICATION-FLOW-RUNTIME-SEAMS-2026-08-01.md`, and
`docs/research/APPLICATION-CORE-HANDOFF.md`. The four hash-pinned workspace
files above should be copied byte-for-byte into `jolt-sim-planning` before
their originals are moved or edited.

Those materials inform this roadmap as follows: converge on one evidence spine
and Case/Outcome contract; keep Maelstrom and the outbox as ordinary
application examples; preserve open effect families; connect proof witnesses
to executable schedules and Hegel shrinking; and request compiler/runtime
seams only from demonstrated application needs.

The later `claude/ocaml-effect-based-language-gsg316` research branch remains
a non-authoritative cross-lane input. Its useful feedback is incorporated here
as per-axis evidence labels, parent-verdict artifact retention, and the rule
that a pure director permits O(1) live transitions plus O(N) coherence checks
at construction/snapshot boundaries. Its proposed replacement of the exact
SQLite result script with typestate is deferred: a lifecycle machine may
complement the fixture, but cannot replace the query/result behavior consumed
by unchanged application code.

## Existing branches awaiting disposition

These commits are useful inputs, not accepted dependencies of the active
stack. Revalidate them against Jolt 0.6.1, controller ABI 6, and the current
application before cherry-picking or reminting them.

The former parity candidate `deepseek/outbox-sqlite-parity` at `a09d3de` has
been integrated after the row-model contract as `47fc0e5`, corrected through
independent review in `7772c48`, and is therefore no longer awaiting
disposition.

| Candidate | Branch or commit | Required disposition |
| --- | --- | --- |
| Journal recovery proof control | `codex/journal-wal-proof` at `9d04db6` | Preserve as proof/test input for Phase 5; refresh against the final journal record and recovery model |
| Interrupted-write termination control | `codex/journal-wal-proof` at `90c805c` | Preserve as a bounded control; do not infer OS durability from it |
| Framed recovery codec | `codex/journal-codec` at `bf8eb4f` | Reuse only after the Phase 5 format and checksum contract is accepted |
| Synchronous framed writer | `codex/journal-writer` at `2adae1e` | Rebase or remint after the durability model; it is not yet the accepted journal |
| HTTP/SQLite evidence prototype | `claude/http-sqlite-evidence-v1` at `ac58958` | Mine producer identities and monitors in Phase 5 after the canonical app establishes its event schema |
| Maelstrom Echo evidence prototype | `glm/echo-case-evidence-v1` at `9fa0970` | Keep as a focused example; converge it on the canonical Case/Outcome and evidence contracts rather than a parallel schema |

## Explicit non-goals on the near-term critical path

- maintaining compatibility with Jolt 0.4.x, 0.5.11, 0.5.12, or other
  pre-release simulator contracts;
- recreating historical fork behavior when newer upstream behavior is sound;
- broad compiler, self-build, selected-Chez, or AOT-cache machinery that does
  not unblock a feature or correctness gate;
- a general SQL parser or a second DB implementation inside the simulator;
- exhaustive arbitrary-thread interleaving or weak-memory claims;
- full SCI compatibility or a proof-aware compiler before the executable app
  and evidence spine exist;
- production OpenTelemetry export in the first simulator release; or
- elaborate provider/capability hardening without an observed correctness or
  extension-boundary need.

## Definition of done

`jolt-sim` is not complete because it exposes a scheduler API or runs a bespoke
cooperative model. A release candidate must demonstrate:

- ordinary Jolt ecosystem code running in real, hermetic, and hybrid modes;
- the canonical HTTP/SQLite/TCP outbox app plus smaller focused examples;
- exact replay and useful Hegel shrinking across workloads, schedules, faults,
  capacities, deadlines, cancellation, and crash boundaries;
- retained forensic evidence for every non-success outcome;
- offline safety/consistency monitors and bounded-liveness checks with explicit
  assumptions;
- a minimal extension path for third-party libraries and applications; and
- an executed cross-platform coverage manifest.
