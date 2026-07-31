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
- a `run-controlled`/`defsim` adapter that preserves the application body
  unchanged while gating ordinary future starts and, under ABI v2,
  substituting registered FFI/native effects;
- a deterministic native-memory world with configurable ABI widths and byte
  order, exact bounds/lifetime failures, copy-safe byte buffers, owned C
  strings, and leak snapshots; and
- a deterministic SQLite handler model plus a real/sim parity fixture that
  executes unchanged `jdbc.core` application code.

The current runtime adapter supports two controller ABI versions discovered
dynamically from a sim image. ABI v1 is lifecycle-only: an optional `:on-event`
callback observes and gates ordinary Jolt future starts. ABI v2 additionally
installs an FFI controller on every controlled run, so every `jolt.ffi` native
effect inside the scope is intercepted before the operating system is reached.
An effect without a registered handler throws
`:jolt.sim.runtime/unhandled-native-effect`; handlers are configured via
`:ffi-handlers`, a map keyed by `[:native-operation operation]` or
`[:foreign-function c-symbol-string argument-types return-type blocking?]`,
where map membership (including a `nil` value) defines handled. Under v1 an
explicitly supplied `:ffi-handlers` key is rejected with
`:jolt.sim.runtime/capability-unavailable`, and the result map never carries
`:effects`.

The adapter still does not select an exhaustive schedule, reorder execution,
advance virtual time, or inject faults. The kernel has no stream, socket,
HTTP, database, or OpenTelemetry integration. Its deterministic traces
describe kernel test runs, not arbitrary production execution.

### FFI interception caveats (ABI v2)

- **Effects are live in-memory evidence.** The `:effects` vector returned under
  v2 records each exact validated descriptor in arrival order. Descriptor
  arguments are the live objects that crossed the interception boundary; they
  may include mutable values such as byte arrays, and are not snapshotted or
  canonicalized.
- **Native work before scope is not intercepted.** A library load or foreign
  function call completed before `run-controlled` installs its controllers has
  already reached the real OS. Defining a lazy `defcfn` before the scope is
  safe; invoking it inside the scope is intercepted before symbol resolution.
- **Already-started outliving threads can escape after restoration.** When the
  controllers are restored (FFI then future, exact tokens, both attempted on
  cleanup), a thread that started inside the scope but has not yet reached its
  terminal lifecycle event regains uncontrolled OS access. The adapter detects
  and fails such outliving tasks, but cannot prevent their late native calls
  from reaching the OS once restoration has completed.
- **A unified causal trace remains later work.** Lifecycle events and native
  effects are recorded in separate ordered logs; correlating a future's task id
  across both logs is the caller's responsibility today.

### Deterministic native memory

`jolt.sim.ffi-memory` supplies handlers for all 13 ABI v2 native operations.
It allocates aligned fake addresses backed only by immutable byte vectors, so
an intercepted pointer can never reach Chez or the operating system. The
default world is deterministic LP64 little-endian; `:pointer-size`,
`:long-size`, `:byte-order`, `:base-address`, `:alignment`, and the set of
available library names are explicit configuration.

The application body remains ordinary Jolt code:

```clojure
(require '[jolt.sim.ffi-memory :as memory]
         '[jolt.sim.runtime :as sim]
         '[my.library :as library])

(let [world (memory/world)
      run (sim/run-controlled
           {:ffi-handlers (memory/handlers world)}
           library/exercise-native-api)]
  {:result (:result run)
   :effects (:effects run)
   :leaks (memory/leaks world)})
```

`my.library` imports `jolt.ffi`, not `jolt.sim`. The test fixture
`jolt.sim.fixtures.ffi-roundtrip` is executed unchanged against both real
native memory and this world.

This is intentionally a memory substrate, not a model of every C library.
Foreign functions such as SQLite, sockets, and codecs still require
library-specific handler packs that allocate or inspect buffers in the same
world. Scalar `:float` and `:double` memory reads/writes fail with a distinct
typed error in this first slice (their `sizeof` values are available).
Simulated `loaded?` reports names successfully loaded in the world; it never
re-probes the host loader. Bounds errors, invalid frees, double frees, use after
free, unterminated strings, and leaked live allocations are explicit evidence
rather than native undefined behavior.

### Deterministic SQLite driver model

`jolt.sim.sqlite` implements the exact 22 foreign-function descriptors used by
the current `db.sqlite` driver over the same deterministic memory world. It is
data-driven rather than a SQL engine: each `sqlite3_prepare_v2` consumes the
next statement plan, verifies the exact SQL, and then verifies typed bound
parameters while serving declared rows, change counts, last-row ids, or a
declared SQLite error.

```clojure
(require '[jolt.sim.runtime :as sim]
         '[jolt.sim.sqlite :as sqlite]
         '[my.app :as app])

(let [world
      (sqlite/world
       [{:sql "PRAGMA foreign_keys=1;"
         :params {} :columns [] :rows []}
        {:sql "select payload from messages where id = ?"
         :params {1 {:type :integer :value 7}}
         :columns ["payload"]
         :rows [[{:type :blob :value (byte-array [0 127 128 255])}]]}])
      run
      (sim/run-controlled
       {:ffi-handlers (sqlite/handlers world)}
       #(app/read-message 7))]
  {:result (:result run)
   :effects (:effects run)
   :clean? (sqlite/clean? world)})
```

`my.app` and its database library import no simulator namespaces. The
integration fixture runs the same `jdbc.core` body against real SQLite and the
model, including nonempty and empty BLOB round trips, and checks the exact
foreign-call sequence and complete handle/borrowed-memory cleanup:

```sh
export JOLT_SIM_BIN=/path/to/an/ABI-v2-sim-enabled/jolt
"$JOLT_SIM_BIN" -M:sqlite-test
"$JOLT_SIM_BIN" -M:sqlite-sim-test
```

These workspace-local aliases expect the `db` checkout to be a sibling of the
canonical `jolt-sim` checkout. They deliberately do not fall back to an older
installed `db`. The first command proves real/sim result parity, but resolving
`db` as a normal dependency lets Jolt process its native-library metadata
before the controlled scope begins. The second command adds the `db` source
path without that metadata: it proves the fixture does not need SQLite
native-library startup or loading. It does not claim that every other startup
effect in the process is OS-independent. In both lanes, every application
SQLite call made inside `run-controlled` is intercepted and an unhandled
descriptor fails closed.

This first model intentionally describes sequential statement executions. It
does not parse SQL, simulate SQLite locking or durability, choose concurrent
schedules, or inject step/cleanup failures yet.

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
2. Extend the landed memory and SQLite handler models with bounded error and
   cleanup plans, then add handler packs for codecs and socket operations on
   the same fail-closed FFI/native interception boundary.
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
