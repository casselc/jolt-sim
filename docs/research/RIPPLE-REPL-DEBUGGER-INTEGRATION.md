# Ripple REPL and self-observation integration

Status: accepted direction; first socket-free evaluation adapter implemented,
2026-08-07. This is not a claim that remote Session attachment, a socket/prepl
transport, tap lifecycle, interruption, or source debugging is implemented.

## Why this is a separate contract

Ripple needs both ordinary live development and deterministic experiment
control. They overlap in presentation, but they are not one protocol:

- evaluation reads forms and reports return values, output, errors, taps, and
  evaluation context;
- simulation control reads immutable state and enabled choices, then applies
  one exact revision-scoped command with an unambiguous receipt; and
- conventional debugging suspends ordinary execution and exposes source
  locations, stacks, scopes, and variables.

Collapsing these into browser callbacks or ad hoc nREPL evaluation would make
alternate UIs harder to build and make command outcomes ambiguous.

## Evaluation adapters

Jolt currently supplies a textual stdin REPL and a loopback nREPL server. It
does not currently supply Clojure's `clojure.core.server/prepl`, `io-prepl`, or
`remote-prepl` APIs.

The fork now has one socket-free Jolt evaluation engine shared by the textual
REPL and nREPL. `jolt.sim.eval-stream/evaluate!` adds the first program-facing
structured adapter over that same engine:

```text
Jolt evaluation engine
        |
        +-- textual REPL adapter
        +-- prepl-compatible structured-event adapter
        `-- nREPL operation adapter
```

The current prepl-compatible subset preserves this vocabulary:

- `:ret`: result or structured exception, namespace, elapsed time, and source
  form;
- `:out` and `:err`: evaluation output chunks; and
- `:tap`: reserved for a later observational lifecycle; it is not emitted yet.

Each call evaluates one exact form, captures stdout and stderr as post-eval
batches, preserves raw success values, returns structured exception data plus
Jolt's catch-site backtrace, and emits exactly one terminal `:ret`. History is
off by default; a REPL/session owner may explicitly request thread history only
after installing its dynamic history bindings. This is an in-process data API,
not Clojure's full `prepl`, `io-prepl`, or `remote-prepl` implementation.

This is a program-facing structured stream, not an editor protocol. nREPL keeps
its request IDs, asynchronous multi-response lifecycle, discoverable ops,
persistent sessions, interruption, and middleware. Both adapters should share
evaluation semantics rather than grow independent readers, history, error, or
printing implementations.

Authoritative upstream references:

- <https://clojure.github.io/clojure/branch-master/clojure.core-api.html#clojure.core.server/prepl>
- <https://clojure.org/reference/repl_and_main#launching-a-socket-server>
- <https://spec.nrepl.org/>

## Simulation control remains explicit

The UI-neutral `jolt.sim.session` and `jolt.sim.session-view` contracts remain
the authority for cooperative simulation:

- immutable snapshot and journal page;
- exact enabled branch identities and isolated successor previews;
- revision-scoped step commands;
- explicit committed, stale, rejected, and ambiguous outcomes; and
- `datafy`, `nav`, and `tap>` access to the same values used by Ripple.

An nREPL middleware may expose those operations and a prepl session may return
their values, but arbitrary evaluated code is not the command protocol. Remote
attachment needs a capability-protected adapter over the same closed frame and
receipt schemas; it must not serialize the Session implementation or accept a
browser-selected function.

## Evolving workbench values and presentation overlays

Ripple is one generic workbench, not a collection of application dashboards.
A live session may accumulate loaded documents, evaluation results, tap values,
session frames, receipts, topology projections, monitor verdicts, and retained
evidence. Each item keeps a stable source coordinate, canonical source value,
source digest, and provenance. Application examples may seed or exercise this
stream, but they do not define new browser architecture.

The first UI-neutral persistence slice implements this as a closed v1
workbench document with an authoritative append-only journal. It retains
immutable items, provenance, exact item overrides, and source-kind or schema-ID
domain rules. `canonical-edn` and strict `read-edn` provide a portable save and
restore boundary; crash-safe file replacement remains a separate storage
adapter rather than hidden browser behavior.

Presentation selection and presentation execution are separate contracts. A
persisted rule selects a namespaced output kind. Trusted project or library
code supplies an immutable registry keyed by that output kind. This lets an
item acquire a table, tree, topology, or custom view after production without
changing its domain tag. Unknown saved kinds fail closed and leave canonical
source EDN available; uploaded workspace EDN cannot install executable code.

One source value can have several valid presentations. The selection order is:

1. an exact user override for one source coordinate or subtree;
2. a persisted domain-to-kind rule;
3. a producer or library default kind;
4. automatic advice from registered schemas or predicates; and
5. raw canonical EDN.

An override changes presentation only. It never edits the source value, trace,
receipt, or proof evidence. Ripple retains the selected kind, renderer output,
and renderer failure separately so a misleading or broken renderer cannot hide
the original value.

Exact overrides are bound to an immutable source coordinate and a stable
source fingerprint. The current fingerprint is CRC32C plus canonical byte
length for indexing and stale UI checks, not a cryptographic identity; the
exact coordinate and retained canonical source remain authoritative. Overrides
become stale when the item changes. Persisted domain rules are intentionally broader and
match only closed data selectors such as source kind, event tag, a top-level
discriminator, schema ID, or item path. A trusted project may register richer
matching code under a stable ID; persisted workspace data refers to that ID and
fails closed when it is unavailable. Uploaded EDN never supplies executable
predicates or renderers.

Presentation changes form a revisioned, append-only workspace journal. A user
can undo a choice, keep it for the current session, or save it in a workspace
document. A future UI can consume the same item, registry, selection, and
journal contracts; this mechanism is not coupled to Ripple's HTML.

The intended interaction follows Portal's useful distinction between a value,
its applicable viewers, and the current viewer. It also retains the useful part
of notebook-style per-result kind selection without embedding a fixed kind list
or application vocabulary in the UI. Well-known Kindly-compatible kinds are a
shared vocabulary; libraries may add data-only presentation models and trusted
renderers through the programmatic registry.

Open decisions that require real usage evidence:

- whether exact subtree coordinates should use `datafy`/`nav` paths, durable
  item-local IDs, or both;
- which closed selector subset is sufficient for persisted domain rules;
- whether workspace journals remain local files or gain an optional retained
  process writer;
- which Kindly kinds are implemented natively versus delegated to an adapter;
  and
- whether interactive viewer choice belongs in the first item-stream slice or
  follows a read-only item history.

## Ripple in Ripple

"Ripple in Ripple" is a canonical end-to-end acceptance scenario, not a
recursive UI gimmick. One real outer Ripple/REPL observes and controls an inner
Ripple application whose ordinary Jolt code runs under simulation:

```text
outer Ripple / REPL (real, out of band)
        |
        | capability-protected frame and command protocol
        v
inner Ripple under simulation
        |
        +-- HTTP request and response boundaries
        +-- executor, timer, lock, and shutdown checkpoints
        +-- report and retained-journal filesystem boundaries
        `-- simulated, recorded, real, or adversarial providers
```

The observer must not run under the scheduler it controls. Pausing, crashing,
deadlocking, or branching the inner Ripple must leave the outer control plane
usable. Every event and command therefore needs an explicit observation level,
run/session identity, and correlation identity so nested traces cannot be
mistaken for one another.

The scenario is complete only when a human or agent can:

1. start an inner Ripple as ordinary Jolt application code through a declared
   experiment;
2. attach an outer Ripple or nREPL-driven client without sharing mutable
   implementation state;
3. inspect datafied state, semantic events, output, and retained evidence;
4. pause at a checkpoint, enumerate branches, apply one exact step, and observe
   its receipt;
5. change a scoped regime for an inner HTTP, filesystem, executor, or shutdown
   boundary and continue;
6. kill or fault the inner process while the outer observer stays responsive;
7. reopen the retained journal after the crash and reproduce the same diagnosis;
8. drive the browser boundary through the same experiment using Playwright or
   a future Jolt-native UI-testing adapter; and
9. render an equivalent static report from the same immutable view models.

## DAP boundary

DAP is a later compatibility adapter for conventional source breakpoints,
exception stops, pause/continue, thread and stack inspection, scopes, and
variables. It does not replace the branching Session protocol: DAP assumes a
chosen execution path and does not naturally model several valid successors,
virtual-time advances, fault choices, or regime changes.

A truthful DAP adapter depends on Jolt first exposing compiler source maps,
safe ordinary-runtime suspension/checkpoints, stack frames, bounded variable
projection, and resume semantics. Until those exist, adding DAP message shapes
would create a debugger UI without a debugger.

## Sequencing

This work follows, rather than blocks, the current ordinary-application path:

1. land retained activity, shared Session projections, Ripple paging, and
   browser acceptance;
2. finish the canonical HTTP/SQLite/TCP outbox experiments and human workflow;
3. **done:** add a small prepl-compatible evaluation stream over shared
   evaluation semantics;
4. add capability-protected remote Session attachment;
5. run the Ripple-in-Ripple acceptance scenario; and
6. evaluate DAP only after the required Jolt runtime/compiler facts exist.
