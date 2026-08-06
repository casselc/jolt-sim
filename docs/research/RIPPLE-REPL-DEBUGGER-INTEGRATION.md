# Ripple REPL and self-observation integration

Status: accepted direction and future acceptance boundary, 2026-08-06. This is
not a claim that ordinary Jolt execution, remote Session attachment, prepl, or
source debugging is implemented.

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

The intended direction is one Jolt evaluation engine with adapters:

```text
Jolt evaluation engine
        |
        +-- textual REPL adapter
        +-- prepl-compatible structured-event adapter
        `-- nREPL operation adapter
```

The prepl-compatible stream should preserve Clojure's useful event vocabulary:

- `:ret`: result or structured exception, namespace, elapsed time, and source
  form;
- `:out` and `:err`: evaluation output chunks; and
- `:tap`: observational values that may arrive between evaluations.

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
3. add a small prepl-compatible evaluation stream over shared evaluation
   semantics;
4. add capability-protected remote Session attachment;
5. run the Ripple-in-Ripple acceptance scenario; and
6. evaluate DAP only after the required Jolt runtime/compiler facts exist.
