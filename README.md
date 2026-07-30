# jolt-sim

`jolt-sim` is an experimental deterministic simulation and concurrency-testing
toolkit for Jolt. The production runtime should expose only small control and
event hooks; schedule search, virtual time, fault injection, replay, monitoring,
and simulated worlds belong here.

## Current foundation

- a pure cooperative scheduler with virtual integer time;
- seeded and scripted task selection;
- byte-stable, versioned EDN traces and exact replay;
- a pure fold-based offline monitor API; and
- a trace-grammar monitor for ordering, step, time, and terminal invariants.

The current kernel has no stream, socket, HTTP, database, runtime-thread, or
OpenTelemetry integration yet. Its traces describe deterministic test runs,
not arbitrary production execution.

## Roadmap

1. Add operation/completion effects for connect, accept, read, write, close,
   cancel, and deadlines, then deterministic network and storage fault models.
2. Add Hegel generation and shrinking for workloads, scheduler choices, and
   fault plans while accepting a shrink only when its trace replays exactly.
3. Build canonical example systems that run through the same application logic
   in real and simulated modes: a TCP protocol, an HTTP/API service, and a
   SQLite-backed application using the bytes, codec, FFI, net, HTTP, and DB
   libraries. Maintain an executed scenario-to-library coverage manifest.
4. Add test-only Jolt runtime hooks at synchronization boundaries so ordinary
   futures, promises, atoms, executors, locks, timers, and completion operations
   can be controlled and replayed.
5. Expand offline monitors from trace grammar into resource safety,
   application models, bounded liveness, and explicit proof-assumption checks.

Completion is not just a scheduler API. It requires end-to-end examples,
real/sim behavioral parity, reproducible Hegel counterexamples, concurrency and
fault stress, and offline monitors that exercise the ecosystem organically.

## Production OpenTelemetry follow-on

The versioned semantic event schema is also the intended bridge to production
observability. Once production runtime hooks exist, add a separate adapter that:

- represents task and application-operation lifetimes as OpenTelemetry spans;
- attaches synchronization, scheduler, completion, and fault facts as span
  events or log records;
- carries stable task, operation, resource, site, outcome, and semantic-version
  attributes rather than simulator-internal object identities;
- emits asynchronously through a bounded queue with sampling, redaction,
  explicit drop metrics, and no blocking on Jolt runtime or collector threads;
  and
- converts captured OTLP data back into the versioned offline document form so
  the same safety, model, and bounded-liveness monitors can run post hoc.

OpenTelemetry remains an adapter dependency, not a scheduler-kernel dependency.
The mapping must be versioned and tested against the native EDN trace so
production telemetry cannot silently change monitor meaning.
