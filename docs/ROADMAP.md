# jolt-sim implementation roadmap

Updated: 2026-08-03

Status: live execution roadmap for pre-release development

Current platform baseline: Jolt 0.5.17, simulation controller ABI 6, FFI
descriptor version 6, clock descriptor version 1, and Chez Scheme 10.4.1.

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
| Jolt 0.5.17 / ABI 6 platform and Windows native-path contract | Landed on the active stack | `9b5cd6b`; draft PR `casselc/jolt-sim#21` |
| Pure durable-outbox application transition | Landed | `5d2c61e`; draft PR `casselc/jolt-sim#22` |
| Real SQLite adapter using ordinary `jdbc.core` | Landed | `2deb01d`, aggregate registration `5663ca4`; draft PR `casselc/jolt-sim#23` |
| SQLite table-row model and unchanged-adapter parity | Landed on the active stack | Row model `1639507` / draft PR `casselc/jolt-sim#25`; parity integration `47fc0e5`, review corrections `7772c48` / draft PR `casselc/jolt-sim#26` |
| Framed TCP/bencode example and hosted lanes | Landed as an input to the canonical app | `6e87657`, `386b9d5`, diagnostic follow-up `51f7018` |
| Whole HTTP -> SQLite -> TCP outbox application | Not yet landed | Phase 3 below |

Phase 1 is green on Linux x86-64 using the pinned simulator image
`jolt v0.5.17-13-g3af5622d`
(`sha256:e84f8d764a8f44f8458d05f4a4e98f02676d903c4207782fd5c1e50ba1f3e7ab`):

- table-row model: 60 tests / 1111 assertions;
- unchanged-adapter real/hermetic/hybrid parity: 1 test / 47 assertions;
- ordinary durable SQLite outbox: 13 tests / 94 assertions;
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

Fix the current forensic and replay blockers before adding more protocols:

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

The simulation layer may provide boundary handlers and models. It must not
replace the HTTP, DB, TCP, codec, or application implementation with a second
simulator-specific implementation.

End-to-end examples should cover at least a simple web form/API call, a raw TCP
protocol client/server exchange, exact binary payloads including empty and
embedded-zero values, durable restart/retry, and one injected native failure.

### Phase 4 — Hegel workload, fault, schedule, and replay search

Once the complete app runs in all three modes:

- define one canonical Case/Outcome schema for workload, provider choices,
  capacities, faults, schedules, and replay coordinates;
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
| [Research backlog](https://github.com/casselc/jolt-sim-planning/blob/main/RESEARCH_BACKLOG.md) | Evidence discipline and findings retained; baseline facts require v0.5.17 / ABI 6 refresh |
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

## Existing branches awaiting disposition

These commits are useful inputs, not accepted dependencies of the active
stack. Revalidate them against Jolt 0.5.17, controller ABI 6, and the current
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
