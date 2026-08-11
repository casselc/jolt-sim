# Project-local Mycelium vision and decision register

**Status:** working vision, 2026-08-11. This document records hypotheses and
decision points. Except for the current-scope statements below, it is not an
accepted API, compatibility promise, implementation plan, or authorization to
build a new runtime.

## Purpose and current scope

The immediate question is whether Mycelium-shaped cells make one Jolt
application or library easier to build, test, simulate, delegate, inspect, and
debug. The Broadcast and HTTP/SQLite/TCP Outbox examples are the first evidence
sources.

The current direction is deliberately project-local:

- cells describe meaningful components or transformations within one
  application or library;
- links describe the boundaries between those components;
- Perturb declares workloads, fidelity, regimes, faults, and checks over that
  project graph;
- jolt-sim executes and records controlled choices and boundary effects; and
- Ripple and the REPL inspect and control the same UI-neutral process data.

Repository rebases, CI jobs, PRs, releases, and ecosystem-wide dependency
coordination are not application cells. An external engineering orchestrator
may eventually consume the same contract and evidence shapes, but it must not
determine Mycelium's application semantics or public API.

Agent delegation is relevant in a narrower sense: a project cell can be a
useful context boundary for asking a human or agent to implement, inspect, or
prove that component. That is development tooling around the cell contract,
not an assumption that an application dispatches agents at runtime.

## Existing evidence and boundaries

The current implementation is intentionally smaller than this vision:

- `jolt.sim.flow/compile-workflow` compiles one finite, acyclic,
  accumulating-data workflow to the immutable simulator kernel.
- Cell handlers are pure `(ctx state data) -> {:state ... :data delta}`
  functions. They may emit canonical intent data, but may not perform effects
  during Session branch preview.
- The compiler checks a closed top-level Malli subset, selected resources,
  reachability, acyclicity, and required top-level key availability. It does
  not prove handler semantics or general Malli subsumption.
- `jolt.sim.session` owns immutable previews, exact revision-scoped commits,
  journaling, `datafy`, `nav`, and `tap>` observations.
- The flow-effect and retained-worker layers separate committed intent from
  external publication and distinguish definite receipts from uncertainty.
- Broadcast demonstrates a long-lived retained application with interactive,
  revision-scoped connection regimes in Ripple.
- Outbox demonstrates ordinary HTTP, SQLite, TCP, bencode, retry, deadline,
  cancellation, and crash boundaries. Its current finite flow wrapper emits
  commands and consumes definite receipts; it is not yet a complete cell model
  of the live application.

The finite workflow must remain honest. A repeated-input service with lifecycle,
backpressure, retries, and concurrent activity is not one finite DAG invocation.
If a long-lived process/link layer emerges, finite workflows may be used within
its cells, but their semantics should not be mutated to make a server appear to
fit.

See also:

- [Minimal Mycelium-shaped workflow on Jolt: v1 boundaries](MYCELIUM-FLOW-V1-GAPS.md)
- [Perturb and Ripple experiment workbench](EXPERIMENT-WORKBENCH-DESIGN.md)
- [Ripple REPL and self-observation integration](RIPPLE-REPL-DEBUGGER-INTEGRATION.md)
- [`jolt.sim.flow`](../../src/jolt/sim/flow.clj)
- [Outbox finite-flow/retained bridge](../../examples/outbox-workbench/src/outbox_workbench/flow_retained.clj)

## Candidate project shapes

These sketches are probes, not mandated decompositions.

### Broadcast

```text
Broadcast project
├── client command admission
├── node n1
├── node n2
├── node n3
├── connection n1--n2
├── connection n2--n3
├── retry and convergence
└── observation and history checks
```

This shape tests repeated inputs, mailboxes, connection-scoped regimes,
partitions, retry, convergence, and live interactive control. A useful design
must continue to call the ordinary Broadcast/node implementation rather than
replace it with simulator-specific node logic.

### Outbox

```text
Outbox project
├── HTTP admission
│   ├── decode and validate request
│   └── submit command
├── durable transaction
│   ├── update application state
│   └── append outbox row
├── delivery
│   ├── select pending row
│   ├── TCP/bencode exchange
│   └── validate acknowledgement
├── recovery
│   ├── retry pending work
│   └── reconcile uncertain outcome
└── observation
    ├── invariants
    ├── trace projection
    └── application result
```

This shape tests resource ownership, transaction boundaries, exactly-once
state transitions without exactly-once delivery claims, retained uncertainty,
crash recovery, and the distinction between application failure and transport
ambiguity. A useful design must preserve the real HTTP/SQLite/TCP functions and
interpose only at declared boundaries.

## Working hypotheses

The following ideas should be tested against both applications before becoming
decisions:

1. A cell should co-locate an input schema, output schema, explicit state
   schema, invariants, resource requirements, effect declarations, handler
   identity, and documentation.
2. A nested flow should expose stable parent/child and link identities without
   implying that every child is a thread or process.
3. Connection policy should remain the primary place for fidelity and fault
   regimes; cell policy should cover local execution and lifecycle only.
4. Pure previews should emit typed effect intents. Only an exact committed
   transition may authorize an external effect.
5. Effect descriptors should declare request/result schemas, authority,
   idempotency, reconciliation, redaction, and artifact policy.
6. Resource descriptors should declare ownership, borrowing, lifecycle,
   concurrency, and exclusive leases rather than merely naming a value in
   `ctx`.
7. Malli should describe data boundaries; Hegel should derive values and
   operation sequences; project invariants should decide verdicts after each
   meaningful transition.
8. Missing required observations should produce `:inconclusive`, not a vacuous
   pass.
9. Ripple, static reports, Kindly adapters, `datafy`/`nav`, and `tap>` should
   consume one data-only project/process view model.
10. Authoring syntax such as `defcell`, `defflow`, `defeffect`, and
    `definvariant` should be earned by repeated descriptors, not introduced
    before the underlying data contracts stabilize.

## Decision register

Each decision should name the evidence that would resolve it. Until then the
listed default is a trial posture, not policy.

| Question | Trial posture | Evidence required before deciding |
| --- | --- | --- |
| Is a long-lived cell a distinct runtime concept? | Keep finite flows and long-lived process/link execution separate. | Both Broadcast and Outbox can be expressed without duplicating application logic; lifecycle and backpressure semantics remain explicit. |
| What does nesting mean? | Treat nesting as identity, ownership, navigation, and progress aggregation—not automatically scheduling. | Ripple/REPL can navigate a nested project and traces retain unambiguous stable paths through replay. |
| Must cell state have a schema? | Add it to experimental descriptors. | It catches a real Broadcast or Outbox defect and yields useful diagnostics without excessive ceremony. |
| How rich should schema compatibility be? | Preserve closed top-level checks while investigating Malli subsumption/refinements. | Branch/join examples demonstrate which compatibility questions cannot be answered by key availability. |
| Where do effects execute? | Pure transition emits an intent; retained/effect worker executes only after commit. | Preview never publishes, stale/replayed commits never republish, and uncertain outcomes reconcile without blind retry. |
| Are connection regimes dynamic? | Yes at serialized, revision-scoped application-safe boundaries. | Broadcast and Outbox each change one link independently, reject stale controls without mutation, and recover through ordinary application logic. |
| What is a resource? | Use explicit project-owned descriptors, but do not build a global provider framework. | SQLite ownership and a second non-database resource share a small contract for acquisition, borrowing, release, and evidence. |
| How are invariants authored? | Accept ordinary pure functions from a trusted project registry, named by canonical data. | The same invariant runs under direct tests, Hegel, replay, Ripple, and static reports with matching verdicts. |
| Can Malli generate Hegel data? | Prototype a bounded Malli-to-Hegel adapter independently. | Generated values satisfy the supported schema subset, failures shrink, and unsupported schema forms fail explicitly. |
| Should agents be runtime effects? | No for the application design. Treat delegation as optional development tooling over a cell contract. | A real maintenance task receives a smaller complete context and returns reviewable evidence without leaking orchestration concepts into runtime traces. |
| What should Ripple control? | Exact revision-scoped project actions, not arbitrary browser-selected functions. | UI and REPL share one command path; out-of-band advancement disables stale controls; no action is silently retried. |
| Should Mycelium syntax be added now? | No. Compose versioned EDN plus trusted registries first. | Two applications exhibit repeated, stable descriptor boilerplate that syntax would materially reduce. |
| What validates generality after two examples? | Choose a structurally different third project, such as an HTTP session application or MQTT-style ingest pipeline. | It reuses the contracts without forcing distributed-mailbox or outbox semantics into the core. |

## Candidate developer experience

This is illustrative vocabulary only:

```clojure
(defcell delivery
  {:input DeliveryRequest
   :state DeliveryState
   :output DeliveryOutcome
   :resources {:outbox-store {:ownership :borrowed}
               :receiver {:ownership :borrowed}}
   :effects {:tcp/send TcpSend}
   :invariants [mark-only-after-ack?]}
  [ctx state request]
  ...)
```

A project should be discoverable without reading implementation internals:

```clojure
(describe-cell project :delivery)
(children process [:outbox])
(actions process [:outbox :delivery])
(step! process branch)
(pause! process [:outbox :delivery])
(set-regime! process [:delivery :receiver] :worst-case)
(reconcile! process effect-coordinate)
```

Contract errors should identify the project, producer cell, consumer cell,
link, path, expected schema, actual bounded value, source declaration, and
retained evidence location. The same process value should support `datafy`,
`nav`, `tap>`, Ripple, and static reporting. None of these operations should
require a UI-specific callback or application-specific JavaScript.

## Likely adoption sequence

If the project-local model proves useful in both examples, the next likely
adopters are:

1. Ripple's generic nested project/process view;
2. Perturb experiment definitions and scoped connection regimes;
3. Session effect-intent and retained-receipt projection;
4. Hegel generators, stateful actions, shrinking, and non-vacuity coverage;
5. trace, static-report, Kindly, and later OTEL projections; and
6. REPL discovery, navigation, stepping, pausing, and reconciliation.

This ordering is provisional. It deliberately avoids changing the low-level
scheduler kernel, raw FFI, Jolt compiler, or unrelated application functions
until a concrete missing seam is demonstrated.

## Validation and exit criteria

The vision earns a concrete design only if the project-local trial can show:

- Broadcast and Outbox use ordinary application/library functions at every
  substantive boundary;
- one shared data model drives REPL navigation, Ripple, and a static report;
- schemas or invariants catch at least one non-vacuous defect or materially
  shorten its diagnosis;
- a scoped regime changes one connection without globally perturbing the
  application;
- previews remain effect-free and external publication remains exactly tied to
  one committed intent;
- definite failure, uncertainty, reconciliation, and retry remain distinct;
- Hegel generates and shrinks useful project operations rather than only
  schema-valid values;
- failed, timed-out, and crashed runs retain navigable forensic evidence; and
- authoring the model is smaller and clearer than bespoke fixture/controller
  code for the same behavior.

If those properties do not hold, retain the useful contracts and presentation
work but do not introduce a generalized Mycelium runtime or syntax.

