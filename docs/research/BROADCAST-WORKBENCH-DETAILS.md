# Maelstrom Broadcast workbench implementation details

This is the detailed implementation and gate record for the Broadcast
workbench. Start with the short
[Broadcast workbench guide](../../examples/maelstrom-broadcast-workbench/README.md)
for the normal interactive flow.

This example attaches Ripple and its persistent Jolt REPL to one separately
running retained three-node Broadcast cluster. The child uses the unchanged
`jolt.maelstrom.broadcast` applications and `jolt.maelstrom.node` handlers over
the existing memory transport. The workbench adds no application, transport,
scheduler, retry, or fault behavior.

The default configuration starts with message `42` and both links connected.
The retained application also accepts the older `:partition-heal` startup
fixture for regression tests. The only accepted inputs are exact maps of this
shape:

```clojure
{:message <integer> :regime :healthy|:partition-heal}
```

## Launch

The parent image must support persistent evaluation. The child selected by
`JOLT_SIM_BIN` must run this checkout's
`:maelstrom-broadcast-retained-worker` alias. One image may fill both roles.
All Jolt invocations go through the pinned Chez 10.4.1 wrapper.

```sh
cd /home/chuck/ai-src/jolt-sim/examples/maelstrom-broadcast-workbench

JOLT_SIM_VIEWER_TOKEN='replace-with-at-least-32-random-characters' \
JOLT_SIM_BIN='/absolute/path/to/jolt-capable-of-running-the-worker' \
JOLT_SIM_PROJECT_DIR='/home/chuck/ai-src/jolt-sim' \
JOLT_SIM_RETAINED_ARTIFACT_DIR='/absolute/writable/forensic-root' \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  /absolute/path/to/eval-capable-jolt \
  -M:workbench config/ripple.edn
```

`JOLT_SIM_VIEWER_PORT` optionally overrides the configured port; `0` requests
an ephemeral loopback port. The token is never accepted from the EDN file. The
launcher rejects an empty file or any second top-level EDN form before it
allocates a retained artifact directory, child, EvalSession, or server.

Open the printed URL, enter the capability token, and use the existing
**Attached retained worker** panel. **Refresh worker** reads only the generic
supervisor coordinate. Send `{:op :inspect}` to receive the current bounded
application snapshot. Ripple renders that snapshot as a three-node topology;
ready nodes have a **Deliver next mailbox** action and each connection has an
enabled **Drop future deliveries** or **Restore deliveries** action.

These captures come from the no-mock Playwright acceptance against a real
retained Jolt worker:

![Ripple Broadcast topology](../../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-topology.png)

![Partitioned connection details and restore action](../../examples/maelstrom-broadcast-workbench/docs/ripple-broadcast-edge-actions.png)

## Interactive connection regime and retry

Start with `{:op :inspect}` and `{:op :bootstrap}`. Bootstrap moves the
application into its running phase and enqueues the seven official openers, but
does not deliver one. Then use the `n2--n3` edge's **Drop future deliveries**
action before stepping a mailbox. The equivalent raw command is
revision-scoped:

```clojure
{:op :set-connection-regime
 :connection ["n2" "n3"]
 :expected-revision 0
 :regime :drop}
```

Ripple submits the exact canonical command embedded by the application-owned
presenter. Reusing that rendered action after the revision changes is a
definite application failure and cannot mutate the connection state.

Inspect `:ready-mailboxes`, select exactly one node, and send:

```clojure
{:op :step :node-id "n1"}
```

Repeat with the first ready mailbox until none remain. With `n2`--`n3` dropped,
the snapshot then shows message `42` at `n1` and `n2`, an empty `n3`, one
dropped `n2`-to-`n3` envelope, and the unchanged Broadcast application's pending
retry identity.

Use the current `n2`--`n3` edge's **Restore deliveries** action. Restoring a
connection only changes future delivery policy; it does not replay the dropped
envelope. Continue explicitly:

```clojure
{:op :retry}  ; real retry-pending! reuses the application's identity
```

Step ready mailboxes until the cluster converges, then:

```clojure
{:op :read}
{:op :step :node-id "n3"}
{:op :inspect}
{:op :stop}
```

The read reply contains `[42]`. Stop publishes terminal evidence and exits the
child cleanly. **Terminate worker** remains the generic bounded forced-reap path
for an uncertain or unresponsive child; it is not normal application shutdown.

## Shared persistent REPL

The Jolt REPL panel and retained panel address the same serialized worker.
These UI-neutral helpers delegate only through `jolt.sim.retained-view`:

```clojure
(require '[maelstrom-broadcast-workbench.main :as wb] :reload)
(wb/frame)
(wb/inspect!)
(wb/bootstrap!)
(wb/step! "n2")
(-> (wb/inspect!) :receipt :value :connections)
(wb/set-connection-regime! ["n2" "n3"] 0 :drop)
(wb/set-connection-regime! ["n2" "n3"] 1 :normal)
(wb/retry!)
(wb/read!)
(wb/stop-worker!)
(wb/present (get-in (wb/inspect!) [:receipt :value]))
```

`present` is pure and returns the same bounded ordinary map/vector topology
model used by Ripple and static reports, including inert canonical action
descriptors. Static reports display those descriptors but never execute them.
The model is suitable for `datafy`, default `nav`, or an explicit `tap>`; it
never taps automatically. Broadcast snapshot and command semantics remain in
the example's immutable value registry, not in Ripple.

A command issued in this REPL consumes the next retained sequence; refreshing
the retained panel observes that exact new coordinate. The retained handle's
own lock serializes browser and REPL operations. EvalSession publishes each
completed evaluation envelope through `tap>` and retains bounded datafy/nav
history. Application command and receipt documents remain in the child artifact
directory printed at startup.

Ctrl+C stops Ripple admission, removes the shared REPL coordinate, attempts the
graceful application stop, and forcibly reaps only if the child remains alive.
Repeated launcher shutdown returns the same result. Artifact directories,
stdout, stderr, command documents, receipts, and terminal evidence are never
deleted by this workbench.

## Gates

The real loopback integration drives the full drop/stale-action/restore/retry/
read story, with one mailbox step sent through the EvalSession and every other
action sent through Ripple's retained HTTP service:

```sh
JOLT_SIM_BIN='/absolute/path/to/jolt-capable-of-running-the-worker' \
JOLT_SIM_PROJECT_DIR='/absolute/path/to/this/jolt-sim-checkout' \
  /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  /absolute/path/to/eval-capable-jolt -M:workbench-test
```

Its progress file defaults to
`$TMPDIR/jolt-sim-broadcast-workbench-<uuid>.edn` and may be pinned with
`JOLT_SIM_BROADCAST_WORKBENCH_PROGRESS_FILE`. It records the child artifact
directory before exercising the application. The primordial test thread owns
and publishes the workbench before starting the timed scenario. On an error or
watchdog deadline it records the stable child coordinate, stops admission,
attempts graceful cleanup, and uses the retained supervisor's TERM/forced-kill
and reap path rather than exiting while the child is merely owned by a future.

The dedicated Playwright acceptance installs no route mocks. It launches this
real workbench, controls the retained child from both panels, and always retains
trace, video, screenshots, and child artifacts. Its spec lives under
`viewer/test-browser-real`, outside the default mock-backed browser suite:

```sh
cd /home/chuck/ai-src/jolt-sim/viewer
JOLT_SIM_BIN='/absolute/path/to/jolt-capable-of-running-the-worker' \
JOLT_SIM_EVAL_BIN='/absolute/path/to/eval-capable-jolt' \
JOLT_SIM_CHEZ_WRAPPER='/home/chuck/ai-src/tools/jolt-with-chez-10.4.1' \
JOLT_SIM_PROJECT_DIR='/absolute/path/to/this/jolt-sim-checkout' \
  npx playwright test --config playwright.broadcast-retained.config.mjs
```

Outputs are under `target/ripple-playwright/broadcast-retained`.

## Boundaries

- This is the real Broadcast application over the existing deterministic memory
  transport, not the official Maelstrom JSON-lines process or real OS network.
- It provides operator-selected single-mailbox delivery, not automatic schedule
  exploration, adversarial scheduling, rewind, or a second retry engine.
- The topology and its action descriptors are a generic bounded presentation
  model. Broadcast-specific extraction and commands remain application-owned;
  Ripple contains no Broadcast vocabulary.
- The live lifecycle and retained adapter currently reside under the repository
  test root. This in-repository example uses the explicit root worker alias; it
  does not claim those fixture namespaces are a released library API.
- The attached persistent evaluator is the shared REPL in this slice. The
  launcher does not additionally start nREPL or a socket REPL.
- Retained process artifacts are crash-persistent files. EvalSession history and
  `tap>` observations are bounded in-process data, not a durable journal.
