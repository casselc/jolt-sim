# jolt-sim

`jolt-sim` is an experimental deterministic simulation and concurrency-testing
toolkit for Jolt. The production runtime should expose only small control and
event hooks; schedule search, virtual time, fault injection, replay, monitoring,
and simulated worlds belong here.

> [!WARNING]
> This is public pre-release research code, not a supported release. APIs,
> controller ABIs, trace schemas, and model contracts may change or be replaced
> without compatibility shims or a deprecation period. Git history is the only
> compatibility archive until the first real release establishes a versioned
> public boundary.

## Development baseline and CI

Current development targets `casselc/jolt` commit
`56d0694af7b815b61d9ad73924b0df19d4ad324c`, based on upstream Jolt 0.5.12,
with Chez Scheme 10.4.1. The full suite requires the special Jolt simulation
image from that commit; an ordinary Jolt image can run only the controller-free
portion of the suite.

Public CI builds that exact simulation image. Linux x86_64/aarch64 and macOS
x86_64/arm64 run the main suite plus the controller-poison, pointer-loan,
scheduler-deadline, POSIX/HTTP integration, and fresh-process case gates.
Windows x86_64 runs the main, controller-poison, pointer-loan, and scheduler
deadline gates, but not the fresh-process supervisor: the current Jolt process
host uses a POSIX shell plus `waitpid`/`kill`. Hegel schedule generation and
shrinking run on Linux x86_64. The Windows ARM64 lane currently runs the
controller-free source suite because its Chez toolchain intentionally provides
a source runtime but no GNU-compatible kernel development pack for linking the
simulation image. That narrower lane is nonblocking until its first hosted runs
establish a stable baseline.

No CI lane carries historical prerelease controller implementations. When the
current Jolt fork changes, this repository advances its one pinned development
baseline and remints the affected contracts and tests in place.

## Current foundation

- a pure cooperative-model scheduler with virtual integer time;
- seeded and scripted task selection;
- immutable exactly-once operation completions with deterministic wakeups;
- byte-stable, versioned EDN traces and exact replay;
- a pure fold-based offline monitor API;
- a trace-grammar monitor for ordering, step, time, and terminal invariants;
- dynamic discovery of the sim-image controller ABI without making ordinary
  Jolt images depend on it; and
- a `run-controlled`/`defsim` adapter that preserves the application body
  unchanged while gating ordinary future starts, substituting registered
  FFI/native effects, observing real native execution, or mixing modeled
  boundaries with guarded native fallback;
- capped lexicographic enumeration of exact top-level future-admission plans
  and a fresh-process supervisor that runs each case with a deadline, canonical
  result transport, and bounded termination/reaping;
- an optional Hegel adapter that either selects from an ordered schedule domain
  or directly generates a count-based permutation, shrinks a failing choice,
  and exactly replays it in another fresh worker;
- a deterministic native-memory world with configurable ABI widths and byte
  order, exact bounds/lifetime failures, copy-safe byte buffers, owned C
  strings, and leak snapshots; and
- a deterministic SQLite handler model plus a real/sim parity fixture that
  executes unchanged `jdbc.core` application code; and
- a descriptor-driven POSIX IPv4 loopback handler model whose dual-use fixture
  executes unchanged public `jolt.net` code against either real sockets or a
  hermetic in-memory stream, including partial I/O, directional half-close,
  target-exact `poll(2)`, and the real public poller/self-pipe wake machinery.

These capabilities belong to two deliberately different execution tracks:

- **Cooperative-model execution** runs a finite transition system through the
  pure kernel. Its canonical state, enabled actions, transitions, virtual time,
  and bounds can support explicitly scoped reachability or completeness claims.
- **Ordinary-runtime controlled execution** runs unchanged Jolt code through
  the controller hooks and modeled/native effect seam. Its current scheduler
  serially admits a declared number of top-level future bodies. It supports
  controlled execution and exact case replay within the documented hook and
  escape bounds; it is not model checking of arbitrary Jolt programs.

Hegel supplies generated cases and shrinking around either suitable boundary.
It is not itself the scheduler or an exhaustive-state explorer.

The runtime adapter accepts one exact current controller contract, presently
prerelease ABI 5. All current controller vars are required. If every var is
absent, `available?` returns false for an ordinary released Jolt image. A
partial namespace, stale or future ABI number, or any descriptor that differs
from the literal current shape fails closed as
`:jolt.sim.runtime/abi-incompatible`.

The current lifecycle has `:spawn`, `:start`, `:finish`, `:cancel`, `:exit`, and
`:abort`. A task owns a worker from `:spawn` until exactly one `:exit`/`:abort`;
`:finish`/`:cancel` settle its future but do **not** release that ownership, so
a cancelled running worker remains owned until `:exit`. After the body returns,
cleanup waits a bounded interval (`:drain-timeout-ms`, default 2000) for every
hooked-future worker to release ownership and every lifecycle callback to
finish before restoration. Restoration is FFI then future. If the scope cannot
drain, the controllers remain installed and the session is poisoned rather
than restored unsafely. Late terminal/drain events are accepted after closure;
new `:spawn`/`:start` events fail closed. Body exceptions are rethrown unchanged
only after drainage and restoration succeed.

Every controlled run installs FFI interception. Exact nested descriptor version
4 requires Boolean `:capture-native-error?` on foreign calls and admits all 15
current native operations, including scoped
`:borrow-byte-array`/`:release-byte-array`. A foreign argument type is either a
primitive keyword or the recursive form
`[:by-value [:struct [[:field :type] ...]]]`; nested field types may themselves
be `[:struct ...]`. Handlers are configured via `:ffi-handlers`, keyed by
`[:native-operation operation]` or `[:foreign-function c-symbol-string
argument-types return-type blocking? capture-native-error?]`. A five-element
foreign-function key is an unambiguous configuration shorthand for final
`false`; supplying both spellings for the same binding is rejected. Map
membership, including a `nil` value, defines handled. A captured handler must
return `[native-result error-code]`.

The current contract also exposes a scoped, owner-thread, single-use native
`proceed` continuation. `run-controlled` selects routing with `:ffi-mode`:

- `:hermetic` is the default and preserves the prior behavior exactly. The
  established one-argument controller is installed, registered handlers
  substitute results, and an unhandled effect is blocked before OS access.
- `:observe` accepts no `:ffi-handlers` and proceeds every
  intercepted call through its exact native branch while recording the route.
- `:hybrid` lets a registered handler substitute its result, or explicitly
  request native routing for its exact call; a handler miss (or an explicit
  request) proceeds through the exact native branch unless model-owned
  resource provenance makes that fallback unsafe.

Every successful run returns the `:effects` vector plus a positionally
correlated `:effect-trace`. Each trace entry records the
exact descriptor, selected mode, and actual `:handler`, `:native`, or
`:blocked` route. A native exception raised by `proceed` keeps ordinary
application semantics: unchanged code may catch it, and it is not relabeled as
a controller failure. Handler, descriptor, and routing-policy failures remain
latched fail closed even when application code catches their immediate throw.

Controller numbering is explicitly prerelease-only. ABI 6, 7, and later
development bumps replace the current contract in place; historical contracts
remain available in Git but do not accumulate as supported runtime branches.
At the first real public release, the externally visible controller and nested
FFI ABI/schema numbering will be consolidated and reset to version 1,
establishing the first compatibility boundary.

Hybrid handler functions must classify results explicitly with
`jolt.sim.runtime/substitute`, `jolt.sim.runtime/modeled-resource`, or
`jolt.sim.runtime/proceed`; a literal `nil` handler-map value remains an
explicit nil substitution. `substitute` is
an assertion that the result is a non-resource scalar/value; known non-null
pointer results (`alloc`, `string->ptr`, pointer-typed `read`, and foreign
`:pointer`/`:void*` results) reject that classification. A handled
`borrow-byte-array` is stricter: it must return a positive `modeled-resource`,
because core requires a usable borrowed pointer and cannot accept a null
substitution.
Integer handles returned under
generic scalar types such as `:int`/`:uptr` cannot be identified from ABI types
alone, so their handler packs must use `modeled-resource`. The latter marks
a non-negative numeric pointer, descriptor, or handle as model-owned. Its
optional positive span covers a half-open interval; `alloc` and
`borrow-byte-array` infer their span from the intercepted arguments when it is
omitted. Before a hybrid miss can proceed, the adapter truncates numeric
arguments with the same toward-zero rule as core and rejects any result inside
a model-owned interval. The ledger is per run and
conservative for the whole scope (no early retirement yet), so extension packs
should allocate fake resources from disjoint high ranges. This guard prevents
a fake pointer/handle returned by one modeled call from reaching a later real
native call; it is not general taint tracking for arbitrary scalar values.

For example, this models allocation while allowing an unchanged library's
other FFI calls to reach the real host:

```clojure
(sim/run-controlled
 {:ffi-mode :hybrid
  :ffi-handlers
  {[:native-operation :alloc]
   (fn [_descriptor] (sim/modeled-resource 1042000000 4096))}}
 library/exercise-native-api)
```

A matching modeled `free`/read/write boundary should also be registered by a
real handler pack; otherwise use of that fake range on a handler miss is
reported as `:jolt.sim.runtime/modeled-resource-native-fallback` before native
execution.

A registered hybrid handler may also return `jolt.sim.runtime/proceed`
instead of a classified result, explicitly requesting native routing for that
exact call rather than an ordinary unhandled-descriptor miss. The same
modeled-resource provenance guard still runs first, so a selected proceed is
blocked exactly like a miss would be when the call's live arguments already
alias a registered resource; only when the guard passes does the real native
`proceed` continuation run. `proceed` is rejected outright outside `:hybrid`
routing. The resulting `:effect-trace` entry uses `:route :native` and still
carries the selecting handler's identity, distinguishing an explicit
selection from an ordinary miss.

`jolt.sim.runtime/with-additional-resources` composes with `substitute` or
`modeled-resource` to register zero or more further modeled resources
alongside a handler's primary classified result, each an exact `{:base
nonnegative-integer :span positive-integer}` map. This covers APIs such as
POSIX `pipe`, whose primary return is an ordinary status while separate
output pointers receive modeled descriptors:

```clojure
(fn [_descriptor]
  (sim/with-additional-resources
   (sim/substitute 0)
   [{:base 9000 :span 1} {:base 9100 :span 1}]))
```

Every addition is validated before any of them -- or the primary resource --
reaches the ledger; a malformed addition throws immediately and leaves the
ledger completely unchanged. This wrapper does not add resource retirement or
typed resource domains.

The non-restoration case intentionally poisons its process, so its permanent
regression is isolated from the reusable test session:

```sh
/path/to/current-sim/target/sim/jolt -M:runtime-poison-test
```

Descriptor-version 4 retains the scoped byte-array pointer loan. Its dedicated
custom-image gate runs one ordinary `jolt.ffi` fixture first against real native
memory and then unchanged against the deterministic memory handlers, including
nested native access and exception cleanup:

```sh
/path/to/current-sim/target/sim/jolt -M:ffi-pointer-loan-test
```

The adapter still does not discover future counts, choose high-utility
interleavings, advance virtual time, or inject faults. `schedule-plans` can
enumerate the first bounded lexicographic top-level admission permutations, but
that is not Hegel/swarm search, partial-order reduction, or explicit-state
exploration. SQLite and the first POSIX loopback stream, self-pipe, and
readiness wait now have deterministic boundary models; unchanged public
`jolt.net`, `jolt-tcp`, and `jolt-http` code runs over the loopback world in the
current Hello World fixture. Real/sim HTTP parity, virtual network faults, and
OpenTelemetry export remain open. The traces still do not describe arbitrary
production execution.

### First coarse scripted scheduler (`:future-schedule`)

`run-controlled` accepts an optional `:future-schedule`: a
nonempty vector that is an exact permutation of `0..N-1`, shape-validated
before any controller is installed (so a malformed schedule fails closed on
any image, including an ordinary released one). It drives
`jolt.sim.future-schedule`, the first coarse deterministic scheduler over
**unchanged** ordinary futures, using only the current lifecycle
events -- no new controller hook.

Ordinals are assigned, in arrival order, to accepted `:spawn` events whose
`:parent` is `0`. The current hook also uses parent zero for a future created by
any non-hooked raw thread; it does not expose enough identity to distinguish that
thread from the thunk's thread. This first slice therefore requires a
caller-enforced quiescent scope with exactly one parent-zero spawner. Nested
hooked futures fail closed; competing raw-thread spawners are an explicit
nonclaim. The schedule states the admission order for the accepted ordinals'
*bodies*: an immutable single-use gate is created per ordinal at `:spawn` and
its `:start` blocks on that gate; at most one ordinal's body is admitted at a
time, and the next is released only after the current ordinal's `:finish`.
`:exit` remains worker-ownership/drain evidence, never the advancement
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
/path/to/current-sim/target/sim/jolt -M:future-schedule-poison-test
```

### Fresh-process cases and bounded future-admission enumeration

`jolt.sim.process-explorer` now supplies the external supervision required by
that nonclaim. Despite the prerelease namespace name, this layer is a process
supervisor and case runner; it does not explore an application state graph.
`defsim` vars are marked and retain their original no-argument
form. The original declaration form accepts runtime overrides but no nonnil
scenario input. An optional one-symbol binding form exposes a canonical input
to both the declared configuration and the otherwise ordinary Jolt body:

```clojure
(sim/defsim checkout-race [case]
  {:ffi-handlers (handlers-for (:faults case))}
  (checkout/run! (:workload case)))
```

The generated var records whether it accepts input, so a worker rejects an
incompatible case before invoking the scenario; it never infers that contract
from application exception data. A project can define a worker alias such as:

```clojure
{:aliases
 {:sim-worker
  {:main-opts ["-m" "jolt.sim.explore-worker"]}}}
```

The parent runs outside `run-controlled`. `run-case` carries one optional
canonical `:input` and one optional exact future `:schedule` through a fresh
worker. Supplying both is the basic transport for generated
workload/fault/schedule cases; omitting the schedule installs no future
scheduler:

```clojure
(require '[jolt.sim.process-explorer :as process-explorer])

(process-explorer/run-case
 {:worker-command ["/path/to/current-sim/jolt" "-M:sim-worker"]
  :dir "/absolute/path/to/project"
  :scenario 'my.scenarios/checkout-race
  :input {:workload [[:checkout :order-7]]
          :faults [[:sqlite/busy 1]]}
  :schedule [1 0]
  :timeout-ms 5000})
```

`run-schedule` remains the exact-schedule compatibility wrapper, and `explore`
runs an ordered schedule domain. For example:

```clojure
(require '[jolt.sim.explore :as explore]
         '[jolt.sim.process-explorer :as process-explorer])

(process-explorer/explore
 {:worker-command ["/path/to/current-sim/jolt" "-M:sim-worker"]
  :dir "/absolute/path/to/project"
  :scenario 'my.scenarios/checkout-race
  :schedules (explore/schedule-plans
              {:future-count 3 :max-schedules 6})
  :timeout-ms 5000})
```

The worker command must name the Jolt executable directly or use an exec-style
wrapper. The current Jolt process host reclaims that worker process, not an
arbitrary descendant tree left behind by a shell wrapper.

Each case gets a new operating-system process and a protocol-v2 EDN
request/result file pair. There is no prerelease protocol-v1 compatibility.
Only a namespaced, `defsim`-marked var with an explicit input-contract marker
is invoked. Inputs, completed values, and scenario failures cross the boundary
through the canonical trace value domain; resolution, protocol, contract, and
encoding failures are reported as `:worker-error`. A child that misses its
deadline is sent TERM, then KILL after a bounded grace period, and must be
observed reaped before the supervisor returns `:timeout`. Failure to observe
death is an infrastructure exception and retains the run directory for
diagnosis.

`:timeout` deliberately means only “the worker did not exit by its deadline.”
It is not a deadlock proof. General case transport does not itself generate or
interpret workloads and faults: the current plan space is still just serial
admission of a known number of top-level ordinary-future bodies. Nested
futures, competing raw spawners, clocks, synchronization-boundary choices,
fault-plan semantics, workload/fault shrinking, and coverage-guided sampling
remain later work.

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

### FFI interception and routing caveats

- **Effects are live in-memory evidence.** The `:effects` vector records each
  exact validated descriptor in arrival order, and
  `:effect-trace` records its route in the same order. Descriptor arguments are
  the live objects that crossed the interception boundary; they may include
  mutable values such as byte arrays, and are not snapshotted or canonicalized.
- **Native work before scope is not intercepted.** A library load or foreign
  function call completed before `run-controlled` installs its controllers has
  already reached the real OS. Defining a lazy `defcfn` before the scope is
  safe; invoking it inside the scope is intercepted before symbol resolution.
- **Raw threads remain outside lifecycle ownership.** Only ordinary
  `future-call` workers emit the current lifecycle events. A raw `Thread.`
  created inside the scope can outlive it undetected and regain uncontrolled OS
  access after restoration. The current lifecycle closes the
  canceled-running-worker gap for hooked futures; it does not claim ownership
  of arbitrary host threads. A scenario that
  creates raw threads or executor tasks must join them before its body returns
  if they can perform FFI; otherwise neither safe restoration nor a complete
  route trace is claimed.
- **A unified causal trace remains later work.** Native descriptors and their
  route decisions are now one correlated log, but lifecycle events remain a
  separate ordered log; correlating a future's task id across both logs is the
  caller's responsibility today.

### Deterministic native memory

`jolt.sim.ffi-memory` supplies handlers for all 15 operations in the current
descriptor-version 4 FFI contract. Owned allocations use aligned fake addresses
backed by immutable byte vectors, so an intercepted pointer can never reach
Chez or the operating system. A staged
`:borrow-byte-array`/`:release-byte-array` pair
instead aliases a validated live Jolt byte-array window for exactly one
`with-byte-array-pointer` callback; modeled native and ordinary array
mutations remain mutually visible, and access after release fails closed. The
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
SQLite and the initial POSIX loopback stream provide library-specific handler
packs over the same heap; other native libraries and codecs still require
their own minimal boundary handlers. Scalar `:float` and `:double` memory
reads/writes fail with a distinct
typed error in this first slice (their `sizeof` values are available).
Simulated `loaded?` reports names successfully loaded in the world; it never
re-probes the host loader. Bounds errors, invalid frees, double frees, use after
free, unterminated strings, and leaked live allocations are explicit evidence
rather than native undefined behavior.

### Deterministic POSIX loopback and readiness

`jolt.sim.net.posix-loopback` models the exact foreign descriptors used by the
current POSIX `jolt.net` stack over the same native-memory world. The caller
supplies the live target descriptor, so Linux `nfds_t :size_t` and Darwin
`nfds_t :uint`, `pollfd` offsets, socket constants, and errno values are never
guessed from the host name.

The model owns synthetic IPv4 stream sockets, listener accept queues,
directional byte FIFOs, and self-pipe endpoints. `poll(2)` reports requested
read/write readiness plus unconditional error, hangup, and invalid-fd bits. A
blocking wait registers atomically with its readiness check, releases the world
lock while parked, and recomputes live state after every notification. This is
why an ordinary self-pipe write, connect, stream send, shutdown, or close can
wake it without deadlocking or returning readiness for an unrelated descriptor.

Two fixtures import `jolt.net`, not `jolt.sim`: one exercises stream I/O and
half-close, and the other drives `open-poller`, registration-token succession,
`await-ready`, the wake cursor, removal, and idempotent close. Their harness
installs the handler world underneath the unchanged code:

```sh
/path/to/current-sim/target/sim/jolt -M:posix-loopback-test
```

The wait currently uses real monotonic elapsed time, not the simulator's future
virtual clock. Socket and pipe FIFOs are unbounded, so full-buffer backpressure
and the self-pipe wake-coalescing `EAGAIN` branch still need a capacity/fault
seam before they can be claimed as adversarial coverage.

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
export JOLT_SIM_BIN=/path/to/a/sim-enabled/jolt
"$JOLT_SIM_BIN" -M:sqlite-test
"$JOLT_SIM_BIN" -M:sqlite-sim-test
```

These workspace-local aliases expect the `db` checkout to be a sibling of the
canonical `jolt-sim` checkout. They deliberately do not fall back to an older
installed `db`. Public CI does not currently run either alias, so this is
locally verified parity rather than public platform evidence. The first command
proves real/sim result parity, but resolving
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

### Composing handler packs

`jolt.sim.handler-pack` is a small public helper for assembling one
`:ffi-handlers` map from several named, validated packs. It does not touch the
runtime or any world; `compose` returns the plain canonical map that
`run-controlled` already consumes, so an application can add bespoke
interceptors alongside a world's handlers without hand-merging their keys.

```clojure
(require '[jolt.sim.handler-pack :as hp]
         '[jolt.sim.sqlite :as sqlite]
         '[jolt.sim.runtime :as sim]
         '[my.app :as app]
         '[my.codec.sim :as codec-sim])

(defn run-scenario [plans codec-model production-config]
  (let [sqlite-world (sqlite/world plans)
        ;; sqlite/handlers already merges the memory world's native-operation
        ;; handlers with its SQLite foreign-function handlers. A separate
        ;; library-provided simulation namespace supplies the codec boundary.
        handlers
        (hp/compose
         (hp/pack :jolt.sim/sqlite (sqlite/handlers sqlite-world))
         (codec-sim/handler-pack codec-model))]
    (sim/run-controlled {:ffi-handlers handlers}
                        #(app/run! production-config))))
```

`native-operation-key` and `foreign-function-key` build the canonical keys
directly; `foreign-function-key` defaults `capture?` to `false` and takes an
explicit fifth positional argument for a capture-enabled handler. `pack`
requires an explicit **namespaced** keyword id and a handler map; legacy
five-element foreign-function keys are canonicalized to the six-element form.
`compose` merges one or more packs and fails closed with a typed ex-info on a
duplicate pack id or on any canonical handler key registered by more than one
pack — even when the two registered values are identical — carrying the
colliding key and both pack ids so an accidental double-registration can never
silently overwrite a handler.

A library can ship a function such as the hypothetical
`my.codec.sim/handler-pack` in an optional simulation-only namespace or
artifact. Its production namespaces continue to depend only on their ordinary
Jolt APIs; only the scenario harness requires `jolt.sim.handler-pack` and the
library's simulation namespace. The application body still receives its normal
production configuration rather than a simulator world.

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

## Canonical example and extension boundary

The co-located `jolt.maelstrom` namespaces are the first canonical application
example and an extraction candidate for a future `jolt-maelstrom` package.
They are not part of the simulator kernel and must not depend on `jolt.sim`.
The node and handlers run over injected transports; `jolt-sim` owns only the
optional deterministic transport, fault, replay, and checker adapters used by
scenarios. JSON-lines framing remains a real process boundary.

Echo alone is application-core evidence, not distributed-safety, liveness, or
Maelstrom interoperability evidence. No additional workload should land until
the same Echo handler has passed both the real JSON-lines and in-process
transport seams and its minimum run/message/causal evidence can replay and be
checked offline.

## Roadmap

1. Drive the existing future controller adapter from scheduler choices --
   the first coarse slice (`:future-schedule`, a scripted top-level spawn
   order, single-concurrency admission) has landed; still open is running
   one unchanged Jolt namespace in normal and controlled modes through
   nested spawns, promises, clocks, and entropy.
2. Extend the landed memory, SQLite, and POSIX loopback/poll models with bounded
   error and cleanup plans, pipe/socket capacity and fault seams, and handler
   packs for codecs on the same fail-closed interception seam.
3. Drive unchanged public nonblocking connect/accept through readiness, then
   add operation effects for cancel and deadlines followed by deterministic
   network and storage fault models.
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
