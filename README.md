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
  unchanged while gating ordinary future starts and, under ABI v2/v3,
  substituting registered FFI/native effects;
- capped lexicographic enumeration of exact top-level future schedules and a
  fresh-process supervisor that runs each plan with a deadline, canonical
  result transport, and bounded termination/reaping;
- an optional Hegel adapter that either selects from an ordered schedule domain
  or directly generates a count-based permutation, shrinks a failing choice,
  and exactly replays it in another fresh worker;
- a deterministic native-memory world with configurable ABI widths and byte
  order, exact bounds/lifetime failures, copy-safe byte buffers, owned C
  strings, and leak snapshots; and
- a deterministic SQLite handler model plus a real/sim parity fixture that
  executes unchanged `jdbc.core` application code.

The current runtime adapter supports three controller ABI versions discovered
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

ABI v3 is the v2 FFI descriptor plus two worker-ownership events,
`:exit` and `:abort`. Under v3 a task owns a worker from `:spawn` until exactly
one `:exit`/`:abort`; `:finish`/`:cancel` settle its future but do **not**
release that ownership, so a cancelled running worker remains owned until
`:exit`. After the body returns, cleanup waits a bounded interval
(`:drain-timeout-ms`, default 2000) for every lifecycle-owned hooked-future
worker to release ownership and every lifecycle callback to finish before any
restoration. Restoration is the reverse (FFI then future). **Safe restoration
is never claimed while any tracked v3 worker ownership or lifecycle callback
remains:** if a hooked-future body cannot drain, the controller is left
installed and the session is poisoned
(`:jolt.sim.runtime/session-poisoned` on the next run) rather than restored
unsafely. Late `:finish`/`:cancel`/`:exit`/`:abort` needed for drainage are
accepted after scope closure and still reach `:on-event`; new
`:spawn`/`:start` after closure fail closed, and the core's balancing
`:abort` for a rejected spawn is consumed without inventing a worker. Body
exceptions are rethrown unchanged only after the same drain and restoration
boundary succeeds.

The non-restoration case intentionally poisons its process, so its permanent
regression is isolated from the reusable test session:

```sh
/path/to/abi-v3/target/sim/jolt -M:runtime-poison-test
```

The adapter still does not discover future counts, choose high-utility
interleavings, advance virtual time, or inject faults. `schedule-plans` can
enumerate the first bounded lexicographic top-level permutations, but that is
not yet Hegel/swarm search or partial-order reduction. The kernel has no
stream, socket, HTTP, database, or OpenTelemetry integration. Its deterministic
traces describe kernel test runs, not arbitrary production execution.

### First coarse scripted scheduler (ABI v3 `:future-schedule`)

`run-controlled` accepts an optional `:future-schedule` under ABI v3: a
nonempty vector that is an exact permutation of `0..N-1`, shape-validated
before any controller is installed (so a malformed schedule fails closed on
any image, including an ordinary released one) and rejected with
`:jolt.sim.runtime/capability-unavailable` on v1/v2. It drives
`jolt.sim.future-schedule`, the first coarse deterministic scheduler over
**unchanged** ordinary futures, using only the existing ABI v3 lifecycle
events -- no new controller hook.

Ordinals are assigned, in arrival order, to accepted `:spawn` events whose
`:parent` is `0`. ABI v3 also uses parent zero for a future created by any
non-hooked raw thread; it does not expose enough identity to distinguish that
thread from the thunk's thread. This first slice therefore requires a
caller-enforced quiescent scope with exactly one parent-zero spawner. Nested
hooked futures fail closed; competing raw-thread spawners are an explicit
nonclaim. The schedule states the admission order for the accepted ordinals'
*bodies*: an immutable single-use gate is created per ordinal at `:spawn` and
its `:start` blocks on that gate; at most one ordinal's body is admitted at a
time, and the next is released only after the current ordinal's `:finish`.
`:exit` remains ABI v3 worker-ownership/drain evidence, never the advancement
point.

Any of the following aborts every undecided gate (so blocked `:start` calls
fail fast and can still reach `:exit` for drainage) and fails with an `ex-info`
tagged `:jolt.sim.runtime/schedule-error`: a nested spawn, a spawn
beyond the schedule's length, fewer spawns than the schedule declares, a
successfully registered spawn followed by pre-worker `:abort`, an out-of-order
terminal event, or an application cancellation, which is **unsupported** in
this first successful-schedule slice. The first schedule failure is retained
even when application code catches its propagated callback exception.

On success the result map gains `:schedule-events`, a deterministic logical
vector containing only alternating `[:admit ordinal]` and
`[:complete ordinal]` entries. It deliberately excludes racing raw
spawn/start/exit arrival order and global task ids; those remain available in
the unchanged compatibility `:events` field. Repeating one successful script
therefore produces byte-identical scheduler evidence even though raw ids and
worker exit order change.

An optional user `:on-event` still composes. Scheduler validation and start
admission run before the callback. A valid `:finish` reaches the user callback
before releasing the next body, so a callback failure aborts every undecided
gate instead of allowing or stranding later work. Such a user failure remains
a controller-callback error rather than being mislabeled as a schedule
violation.

This scheduler makes **no deadlock-recovery claim**: if the schedule's
admission order is incompatible with how the unchanged thunk's own thread
actually depends on its futures (for example, blocking on an already-spawned
future before the schedule ever lets the future it is waiting on run), the
blocked `:start`/`deref` simply never returns, the same as it would in
unchanged application code. That is a property of the requested order, not a
failure this slice detects or recovers from, and still requires
external/subprocess supervision -- exercised in isolation, not as an
in-process test that would hang the suite:

```sh
/path/to/abi-v3/target/sim/jolt -M:future-schedule-poison-test
```

### Fresh-process schedule exploration

`jolt.sim.process-explorer` now supplies the external supervision required by
that nonclaim. `defsim` vars are marked and retain their original no-argument
form; an additive one-map arity lets the worker merge in one exact
`:future-schedule` while leaving the scenario body unchanged. A project can
define a worker alias such as:

```clojure
{:aliases
 {:sim-worker
  {:main-opts ["-m" "jolt.sim.explore-worker"]}}}
```

The parent runs outside `run-controlled` and passes an explicit Jolt image,
working directory, scenario var, schedule vector, and deadline:

```clojure
(require '[jolt.sim.explore :as explore]
         '[jolt.sim.process-explorer :as process-explorer])

(process-explorer/explore
 {:worker-command ["/path/to/abi-v3/jolt" "-M:sim-worker"]
  :dir "/absolute/path/to/project"
  :scenario 'my.scenarios/checkout-race
  :schedules (explore/schedule-plans
              {:future-count 3 :max-schedules 6})
  :timeout-ms 5000})
```

The worker command must name the Jolt executable directly or use an exec-style
wrapper. The current Jolt process host reclaims that worker process, not an
arbitrary descendant tree left behind by a shell wrapper.

Each schedule gets a new operating-system process and a versioned EDN
request/result file pair. Only a namespaced, `defsim`-marked var is invoked.
Completed values and scenario failures cross the boundary through the
canonical trace value domain; resolution, protocol, and encoding failures are
reported as `:worker-error`. A child that misses its deadline is sent TERM,
then KILL after a bounded grace period, and must be observed reaped before the
supervisor returns `:timeout`. Failure to observe death is an infrastructure
exception and retains the run directory for diagnosis.

`:timeout` deliberately means only “the worker did not exit by its deadline.”
It is not a deadlock proof. The current plan space is still just serial
admission of a known number of top-level ordinary-future bodies; nested
futures, competing raw spawners, clocks, synchronization-boundary choices,
fault plans, workload/fault shrinking, and coverage-guided sampling remain
later work.

### Optional Hegel selection and shrinking

The `jolt.sim.hegel` namespace is an optional adapter. Activate a
`jolt-hegel` dependency before requiring it; the repository's
`:hegel-explore-test` alias pins the peeled v0.1.2 commit and demonstrates the
setup. A property can let Hegel select one ordered plan, run it in the
fresh-process supervisor, and shrink a failure:

```clojure
(require '[hegel.core :as h]
         '[jolt.sim.explore :as explore]
         '[jolt.sim.hegel :as sim-hegel])

(let [plans (explore/schedule-plans
             {:future-count 3 :max-schedules 6})]
  (h/run-test!
   {:test-cases 100
    :database ""
    :derandomize? true
    :name "checkout/future-schedules"
    :verbosity :quiet}
   (fn [_]
     (let [outcome
           (sim-hegel/require-completed!
            (sim-hegel/run-schedule! process-config plans))]
       (when-not (application-invariants-hold? outcome)
         (throw
          (ex-info
           "checkout invariant failed"
           {:hegel/origin "checkout/future-schedules"
            :schedule (:schedule outcome)})))))))
```

The adapter validates the ordered plan set before generation. Hegel owns the
choice and shrink spans; each generated or replayed case still gets a new
worker process. A non-completed process outcome can be promoted to a stable
property failure with `require-completed!`, while an application-specific
invariant supplies its own stable failure origin and evidence.

When the future count is known but materializing an explicit plan domain is
undesirable, the property body can generate a schedule directly:

```clojure
(sim-hegel/run-direct-schedule! process-config 8)
```

For `N > 1`, this makes exactly `N - 1` bounded Hegel integer draws as Lehmer
digits and decodes them into one exact permutation in `O(N²)` time. It never
enumerates the `N!` schedule space. `N = 1` uses the constant `[0]` generator
without an integer draw. All-zero digits decode to the identity permutation;
the pinned-engine tests demonstrate an always-failing identity shrink and a
specific real-process reduction from `[1 2 0]` to `[1 0 2]`.

These remain structural shrinking seams, not high-utility distributions.
For an explicit domain, `g/sampled-from` shrinks toward earlier entries, so
callers control the simplification order; a bounded lexicographic prefix is
deterministic but utility-neutral. The pinned Hegel API does not document a
distribution for its integer draws, so the direct generator makes no uniform
sampling claim. Hegel runs cases sequentially; stateful swarm rules,
coverage/resource-order scores, targeted sampling, workload and fault
generation, and partial-order reduction remain separate later work.

### FFI interception caveats (ABI v2/v3)

- **Effects are live in-memory evidence.** The `:effects` vector returned under
  v2/v3 records each exact validated descriptor in arrival order. Descriptor
  arguments are the live objects that crossed the interception boundary; they
  may include mutable values such as byte arrays, and are not snapshotted or
  canonicalized.
- **Native work before scope is not intercepted.** A library load or foreign
  function call completed before `run-controlled` installs its controllers has
  already reached the real OS. Defining a lazy `defcfn` before the scope is
  safe; invoking it inside the scope is intercepted before symbol resolution.
- **Raw threads remain outside lifecycle ownership.** Only ordinary
  `future-call` workers emit the current lifecycle events. A raw `Thread.`
  created inside the scope can outlive it undetected and regain uncontrolled OS
  access after restoration. ABI v3 closes the canceled-running-worker gap for
  hooked futures; it does not claim ownership of arbitrary host threads. ABI
  v1/v2 also lack v3's post-worker `:exit` acknowledgement.
- **A unified causal trace remains later work.** Lifecycle events and native
  effects are recorded in separate ordered logs; correlating a future's task id
  across both logs is the caller's responsibility today.

### Deterministic native memory

`jolt.sim.ffi-memory` supplies handlers for all 13 ABI v2/v3 native operations.
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

1. Drive the existing future controller adapter from scheduler choices --
   the first coarse slice (`:future-schedule`, a scripted top-level spawn
   order, single-concurrency admission) has landed; still open is running
   one unchanged Jolt namespace in normal and controlled modes through
   nested spawns, promises, clocks, and entropy.
2. Extend the landed memory and SQLite handler models with bounded error and
   cleanup plans, then add handler packs for codecs and socket operations on
   the same fail-closed FFI/native interception boundary.
3. Add operation effects for connect, accept, read, write, close, cancel, and
   deadlines, followed by deterministic network and storage fault models.
4. Extend the landed optional explicit-domain and direct-count Hegel schedule
   shrinkers into generation and shrinking for workloads,
   synchronization-boundary choices, native faults, and effect plans,
   accepting a shrink only when its trace replays exactly.
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
