# Ripple

Ripple is the offline jolt-sim document viewer. This optional dependency root
serves a small loopback-only web UI over the existing trace and Case/Outcome
reporters, a safe experiment-plan inspector, and the fresh-process replay
helper. It does not implement another
scheduler, controller, monitor, evidence schema, or worker protocol.

The browser keeps the selected EDN file locally until **Inspect** or **Replay
once** is pressed. Every request requires a startup capability token. The
upload UI requires an explicit document kind -- **Trace**, **Case/Outcome**,
or **Experiment plan** -- and the server never infers or guesses a schema from
the uploaded bytes.
Trace documents render through `jolt.sim.report/trace->html` and are never
replayable; experiment-plan documents render only their safe projection and
are also never replayable. Case/Outcome documents render through
`jolt.sim.report/case-outcome->html` and keep the existing replay path. Replay
uses only the scenario, input, and schedule restored from the validated
Case/Outcome document; worker command, working directory, deadlines,
environment, and artifact policy come from the trusted server configuration.
Scenarios must be explicitly allowlisted.

Experiment plans are inspection-only. The process-local map returned by
`jolt.sim.experiment/plan-data` still contains executable handler functions,
probes, projectors, monitors, presenters, and possibly sensitive pack
configuration. It must never be persisted or uploaded. First convert it to
the closed inert viewer document:

```clojure
(require '[jolt.sim.experiment :as experiment]
         '[jolt.sim.viewer.experiment :as viewer-experiment])

(spit "experiment-plan.edn"
      (viewer-experiment/canonical-edn
       (viewer-experiment/plan-data->document
        (experiment/plan-data compiled-plan))))
```

That projection retains only experiment/profile identity, node and port
capabilities, connection endpoints/pack/mode, handler pack ownership and
counts, check identities, runtime FFI mode, and monitor/presentation counts.
It cannot replay, step, perturb, resolve code, or reconstruct an executable
plan. [`examples/experiment-plan.edn`](examples/experiment-plan.edn) is an
inert representative fixture that can be opened in Ripple without compiling a
live plan.

Programmatic and REPL-driven startup may also include a trusted
`:presentation-registry` map. Ripple composes it after jolt-sim's built-in
event presenters, so application entries override library/default displays
without changing the trace or the simulator. This value contains functions
and therefore belongs in ordinary Clojure startup code, not the EDN config
file. Uploaded documents can only select already-registered event-tag,
transition-operation, or canonical transition-site entries; they cannot load
or name presenter code. Presenter implementations must nevertheless treat the
validated event passed to them as untrusted input: they must not evaluate it,
use it to select filesystem paths or native calls, or perform other
input-directed side effects. See the report README for the small presenter
shape.

The server admits one body-consuming inspection or replay request at a time,
even when several tabs share the capability. A competing request receives 429
before its body is read. Two bounded HTTP threads let jolt-http's parser keep
feeding that one request while its handler consumes a segmented body; this
does not permit a second replay process.

Copy [`example-config.edn`](example-config.edn), replace the absolute paths,
and start the viewer with the sim-enabled Jolt image:

```sh
cd viewer
JOLT_SIM_VIEWER_TOKEN='replace-with-at-least-32-random-characters' \
  /absolute/path/to/sim/jolt -M:viewer /absolute/path/to/viewer-config.edn
```

Port `0` selects an ephemeral loopback port and the startup message prints the
actual URL. A fixed port such as `8788` is more convenient for repeated use.
Press Ctrl+C in the foreground viewer process to stop the listener and handler
pool cleanly; the command-line entry point owns SIGINT and runs server shutdown
before exiting.

The Linux CI gate exercises that real signal path in a fresh process and
retains the config, stdout, and stderr for every run:

```sh
JOLT_SIM_BIN=/absolute/path/to/sim/jolt \
JOLT_SIM_PROJECT_DIR=/absolute/path/to/jolt-sim \
  test/cli-sigint-smoke.sh
```

The configuration is deliberately closed. Ambient replay keys are limited to
the public process-explorer settings; browser data cannot replace them. The
example retains completed worker directories as well as failures because the
viewer is an investigation tool. `:temp-dir` is the existing parent under
which the process explorer creates one isolated run directory; it is not an
output directory chosen by the uploaded document.

Current boundary: inspection is a real report render (trace documents through
the trace report, Case/Outcome documents through the Case/Outcome report, and
safe experiment-plan projections through specialized presentation kinds), and
replay delegates to one real fresh worker for Case/Outcome documents only.
Hosted CI drives the checked-in canonical outbox Case/Outcome through the live
viewer HTTP API, executes its unchanged HTTP/SQLite/TCP/bencode scenario in
that worker, and retains the complete worker directory plus an append-only
phase log. The UI does not yet compare two outcomes, evaluate post-hoc
invariants, or expose a general scenario catalog. Those are later viewer
slices over the same evidence and replay APIs.

### Replay activity panel

While a replay runs, the page polls the authenticated `GET
/api/replay-progress` endpoint and renders a small "Replay activity" panel
below the report. This is an ephemeral progress view over the *same* single
fresh-process replay started by **Replay once** -- it is not a second replay
implementation, a scheduler, or an evidence store.

The endpoint never accepts a filesystem path (or any other input) from the
browser. It reports the one server-owned active/most-recent replay by reading
only fixed basenames from the trusted run directory that
`jolt.sim.process-explorer` already creates for that replay: `worker-ready.edn`
and -- existence only, never parsed -- `result.edn` as milestones, and up to
65536 bytes each of `stdout.log`/`stderr.log`. Active responses expose the
last milestone as the boolean `result-observed?`, allowing a client or test to
distinguish a genuinely in-flight worker from a child that has already written
its result while the supervising POST is still unwinding. A file may be
missing or partially written at any moment; both are tolerated. The response is JSON
(`Content-Type: application/json`) with `Cache-Control: no-store`, exactly
like every other viewer response, and requires the same capability token and
loopback binding as `/api/render` and `/api/replay`.

The reported `status` is one of a small closed set: `idle` (no replay has
run), `starting` (accepted; the worker is not yet confirmed alive),
`worker-ready` (the worker's readiness marker exists), `running` (readiness
and/or output is observable), `completed`, or `failed`. The terminal snapshot
reuses the exact bounded stdout/stderr diagnostics process-explorer already
captured before its own artifact cleanup ran, so it renders correctly even
when `:retain-completed-artifacts?` is false and the run directory has since
been removed. The browser renders every dynamic string through `textContent`
only and polls at most one request at a time. Polling continues until the
authoritative replay POST settles, then performs one final snapshot fetch.
Non-final polls render only active statuses, so the previous replay's idle or
terminal snapshot cannot replace the new replay's local `starting` state even
if a fresh progress GET reaches the server before its replay POST. A generation
token discards older in-flight poll responses, and the file, capability,
inspect, and replay controls remain disabled for the complete request so a
second click cannot stop observation of the first replay.

The E2E gate uses an optional trusted observer in the ordinary outbox fixture.
That observer writes a retained checkpoint only after the real HTTP command
has committed to SQLite and the pending row has been reloaded and validated,
but before delivery attempt 1. The parent then samples the authenticated
endpoint, requires `result-observed?` to be false, writes a retained release
record, and requires the same worker's normal Case/Outcome completion. The
default fixture and scenario path supply no observer and remain unchanged; the
viewer does not reimplement HTTP, SQLite, TCP, bencode, or application logic.

### In-process session stepping adapter

`jolt.sim.session-view` is a UI-neutral, in-process adapter over one
`jolt.sim.session` Session (the cooperative REPL control capability). It is
not HTTP, a UI, a remote protocol, another scheduler, durable storage, or a
generic effect layer: every branch preview and transition delegates to the
Session, and the adapter owns only the bounded optimistic retry budget that
keeps one read coherent.

`read-frame` returns one coherent, closed frame: the snapshot, the isolated
branch previews, and the append-only journal tail from a validated integer
cursor, all at the same session revision. A concurrent REPL step cannot mix
revisions -- the read retries (bounded) until two consecutive snapshot reads
agree and fails closed with a typed `::coherence-failed` error otherwise. A
malformed or out-of-range cursor fails closed with `::invalid-cursor`.
The returned `:journal/:next-cursor` can be passed to the next read to receive
only entries appended afterward.

`step-frame!` synchronously applies one exact revision-scoped branch and
returns an explicit command-result envelope. A committed result always carries
its plain branch/revision acknowledgment, even if the post-commit frame cannot
be obtained; this prevents an ambiguous retry from executing a command twice.
A stale branch is never silently rebased just because the same action identity
remains enabled: shared state may have changed since the displayed preview.
Instead, the result is `:stale`, carries a refreshed frame, and requires the
human or agent to choose again explicitly.
