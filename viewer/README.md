# jolt-sim retained-case viewer

This optional dependency root serves a small loopback-only web UI over the
existing Case/Outcome reporter and fresh-process replay helper. It does not
implement another scheduler, controller, monitor, evidence schema, or worker
protocol.

The browser keeps the selected EDN file locally until **Inspect** or **Replay
once** is pressed. Every request requires a startup capability token. Replay
uses only the scenario, input, and schedule restored from the validated
document; worker command, working directory, deadlines, environment, and
artifact policy come from the trusted server configuration. Scenarios must be
explicitly allowlisted.

The server uses one handler thread, so at most one replay can run at a time
even when several tabs share the capability. Inspection requests wait behind
an active replay rather than creating additional local process pressure.

Copy [`example-config.edn`](example-config.edn), replace the absolute paths,
and start the viewer with the sim-enabled Jolt image:

```sh
cd viewer
JOLT_SIM_VIEWER_TOKEN='replace-with-at-least-32-random-characters' \
  /absolute/path/to/sim/jolt -M:viewer /absolute/path/to/viewer-config.edn
```

Port `0` selects an ephemeral loopback port and the startup message prints the
actual URL. A fixed port such as `8788` is more convenient for repeated use.

The configuration is deliberately closed. Ambient replay keys are limited to
the public process-explorer settings; browser data cannot replace them. The
example retains completed worker directories as well as failures because the
viewer is an investigation tool. `:temp-dir` is the existing parent under
which the process explorer creates one isolated run directory; it is not an
output directory chosen by the uploaded document.

Current boundary: inspection is a real report render, and replay delegates to
one real fresh worker. Hosted CI drives the checked-in canonical outbox
Case/Outcome through the live viewer HTTP API, executes its unchanged
HTTP/SQLite/TCP/bencode scenario in that worker, and retains the complete
worker directory plus an append-only phase log. The UI does not yet stream a
running trace, compare two outcomes, evaluate post-hoc invariants, or expose a
general scenario catalog. Those are later viewer slices over the same evidence
and replay APIs.
