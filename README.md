# jolt-sim

`jolt-sim` is an experimental deterministic simulation and concurrency-testing
toolkit for Jolt. The production runtime should expose only small control and
event hooks; schedule search, virtual time, fault injection, replay, monitoring,
and simulated worlds belong here.

## Current foundation

- a pure cooperative scheduler with virtual integer time;
- seeded and scripted task selection;
- immutable exactly-once operation completions with deterministic wakeups;
- byte-stable, versioned EDN traces and exact replay;
- a pure fold-based offline monitor API;
- a trace-grammar monitor for ordering, step, time, and terminal invariants;
- dynamic discovery of the sim-image controller ABI without making ordinary
  Jolt images depend on it; and
- a `run-controlled`/`defsim` adapter that observes and can gate ordinary Jolt
  future starts while preserving the application body unchanged.

The current runtime adapter is deliberately narrower than the intended
scenario form below. Its v1 configuration accepts only an optional `:on-event`
callback and rejects unimplemented options rather than silently pretending
that seed, time, fault, or effect control exists. It observes future lifecycle
events and detects futures that outlive a controlled scope, but it does not yet
select an exhaustive schedule. The kernel still has no stream, socket, HTTP,
database, native-effect, or OpenTelemetry integration. Its deterministic
traces describe kernel test runs, not arbitrary production execution.

## Execution model

The cooperative step API is the scheduler kernel and a low-level model-testing
tool. It is not the intended way to author simulated applications.

The public direction is a `defsim` scenario form whose configuration declares
the controlled world while its body runs ordinary Jolt code:

```clojure
(defsim checkout-under-partition
  {:seed 42
   :clock {:start 0}
   :effects {:net (sim-net/world topology)
             :db (sim-db/sqlite)
             :entropy (sim/random)}
   :faults [[:partition :api :payments]]
   :search {:preemptions 3}}
  (let [system (app/start! production-config)]
    (client/create-order! system order)
    (is (= :pending (orders/status system (:id order))))))
```

`app/start!` and the application, protocol, codec, HTTP, and database
namespaces must be the same code used outside simulation. The completed
scenario form will install the controlled scheduler, clock, entropy, effect
implementations, trace capture, and cleanup around that body.

Core Jolt therefore needs disabled-by-default internal hooks for ordinary
threads and synchronization, time, entropy, and FFI calls. Ecosystem libraries
should retain their normal public APIs while native effects are intercepted
underneath them. In simulation mode, a raw native call without a registered
handler is an uncontrolled effect and must fail closed rather than reaching
the real operating system.

## Roadmap

1. Drive the existing future controller adapter from scheduler choices, then
   run one unchanged Jolt namespace in normal and controlled modes through
   ordinary futures, promises, clocks, and entropy.
2. Intercept `jolt.ffi` binding calls before native symbol resolution, fail
   closed on unregistered native effects, and add deterministic simulated
   memory ownership.
3. Add operation effects for connect, accept, read, write, close, cancel, and
   deadlines, followed by deterministic network and storage fault models.
4. Add Hegel generation and shrinking for workloads, scheduler choices, native
   faults, and effect plans while accepting a shrink only when its trace
   replays exactly.
5. Build canonical example systems that run through the same application logic
   in real and simulated modes: a TCP protocol, an HTTP/API service, and a
   SQLite-backed application using the bytes, codec, FFI, net, HTTP, and DB
   libraries. Maintain an executed scenario-to-library coverage manifest.
6. Expand test-only runtime hooks across atoms, executors, locks, conditions,
   timers, core.async, and completion operations.
7. Expand offline monitors from trace grammar into resource safety,
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
