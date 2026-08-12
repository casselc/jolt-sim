# Implementation and verification reference

This page records the detailed boundaries behind the short project and
workbench guides. Paths and alias names refer to this checkout.

## Evidence documents

jolt-sim uses several document kinds for different jobs:

- A cooperative trace records one model run.
- A Case/Outcome records one ordinary-runtime case and its result.
- An experiment plan is a data-only inspection document. It cannot execute.
- An official Maelstrom run records evidence from the external checker lane.

Ripple requires the caller to select the document kind. Only a validated
Case/Outcome can use the exact replay endpoint. Trusted server configuration
owns the scenario allowlist, worker command, project directory, deadline,
environment, and artifact policy. Browser input cannot replace those values.

## Runtime control boundary

The cooperative model API owns a finite transition system. Its completeness
claim applies only when steps are deterministic and side-effect free, state is
value-semantic, and the configured state cap is not binding. Closed-over
mutation, native I/O, wall clocks, host entropy, and other undeclared effects
remain outside that proof.

The ordinary-runtime path runs application code through the Jolt simulation
controller and registered effect handlers. The controller can gate declared
future starts, clock operations, and FFI calls. A handler may model an effect,
delegate to guarded native execution, or reject the operation. Unregistered
effects are outside the claim.

The runtime adapter accepts one exact controller contract. An absent controller
means that only controller-free code can run. A partial namespace, wrong ABI,
or mismatched descriptor fails closed.

## Process and retained evidence

Fresh-process cases run with a deadline and a bounded termination and reap
path. Retained workers use append-only command and receipt files in an artifact
directory. A command sequence is exact and monotonic. A definite failure is
not transport uncertainty. An uncertain publish must be reconciled against the
recorded sequence before a caller considers resending.

Ripple's EvalSession history and `tap>` observations are bounded in-process
data. They are not a crash-safe journal. The child artifact directory is the
durable forensic boundary for retained commands, receipts, stdout, stderr, and
terminal evidence.

## Ripple security boundary

Ripple listens on loopback and requires a capability token. Loopback is not a
substitute for the token. When evaluation is enabled, the token grants code
execution in the Ripple process. Keep it private.

The browser may choose only operations and presentation kinds installed by the
trusted launcher. Presentation functions are trusted project code. They map a
domain value to bounded, data-only output and are not accepted from browser
requests or saved evidence.

Ripple serializes document-consuming work. This protects one replay worker
from competing operations; it is not multi-user isolation.

## Command Cell contract

A Command Cell catalog is installed by trusted project code when Ripple starts.
Each cell declares:

- a stable identifier and display name;
- a closed input schema;
- a pure prepare function;
- one or more exact revision-scoped branches;
- an effect kind and borrowed capability;
- a receipt projection and bounded evidence identifiers.

Catalog reads and preparation do not publish an application command. A branch
commit publishes once. A stale session epoch or revision fails before
publication. The current catalog is fixed for the life of the workbench.

The Outbox browser acceptance expects two cells and retains three updatable
evidence items for prepare, commit, and projected receipt. The Broadcast
acceptance expects eight cells and checks the same generic evidence shape.

## Workbench Item contract

A WorkbenchSession stores canonical source EDN, a monotonic revision, and an
optional registered presentation selection. A presentation override is tied to
an exact item revision. A stale override fails without changing the item.
Unknown or failed presentations preserve the source value for inspection.

Static reports may render inert action descriptors. They never execute those
actions. Ripple executes only an action that the trusted live presenter offers
for the current value and revision.

## Focused application gates

The example dependency roots define the exact aliases. Run them with an image
that has the capability described by the example and with the workspace Chez
10.4.1 wrapper.

### Outbox

From `examples/outbox-workbench`:

- `-M:eval-flow-test` runs the one-shot persistent evaluation path.
- `-M:live-lifecycle-test` checks the post-commit pause and explicit delivery.
- `-M:retained-workbench-test` checks the separate retained child and graceful
  shutdown.
- `-M:flow-contract-test` checks the project-owned flow contract.
- `-M:flow-retained-test` checks the retained effect bridge.
- `-M:flow-ripple-test` checks the common Ripple workbench integration.

The real browser acceptance is
[`viewer/test-browser-real/outbox-flow-real.spec.mjs`](../../viewer/test-browser-real/outbox-flow-real.spec.mjs).
It installs no route mocks. The Playwright configuration writes artifacts
under `viewer/target/ripple-playwright/outbox-flow`.

### Broadcast

From `examples/maelstrom-broadcast-workbench`:

- `-M:presentation-test` checks the data-only topology presenter.
- `-M:flow-contract-test` checks project-owned command and receipt contracts.
- `-M:workbench-test` runs the real loopback Ripple and persistent REPL story.
- `-M:flow-effect-test` runs partition, stale revision, restore, application
  retry, convergence, read, and clean stop through commit-gated cells.

The real browser acceptance is
[`viewer/test-browser-real/broadcast-retained-real.spec.mjs`](../../viewer/test-browser-real/broadcast-retained-real.spec.mjs).
It installs no route mocks and retains trace, video, screenshots, and child
artifacts under `viewer/target/ripple-playwright/broadcast-retained`.

## Current application boundaries

The Outbox workbench uses real loopback HTTP, SQLite, TCP, and bencode. It does
not pause an arbitrary native call or database transaction, search schedules,
rewind process state, or prove exactly-once delivery.

The Broadcast workbench uses the ordinary Broadcast handlers over the existing
deterministic in-memory transport. It is not the official Maelstrom JSON-lines
process, an OS network test, automatic schedule exploration, or a second retry
engine. The example exposes operator-selected single-mailbox delivery.

Both workbenches assume that an ordinary program may interfere by accident.
They are not a containment boundary for an adversarial program.

## Toolchain

Jolt builds, gates, and scripts for this project require Chez Scheme 10.4.1.
The maintainer workspace enforces that version through this workspace-local
path:

```sh
/home/chuck/ai-src/tools/jolt-with-chez-10.4.1 <command> [args...]
```

The wrapper preflight is the authoritative local compiler-selection check.
It is not a general installation path. See [prerequisites](../PREREQUISITES.md)
for the eval-capable and sim-enabled image distinction and for guidance in
another workspace.
