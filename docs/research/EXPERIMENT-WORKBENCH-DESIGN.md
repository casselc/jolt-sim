# Perturb and Ripple experiment workbench

**Status:** pre-release design synthesis, 2026-08-05. This document defines a
first implementation target. It is not a compatibility promise and does not
authorize a new language, compiler, or runtime fork.

## Decision

Perturb and Ripple are first a test-harness, experimentation, hardening,
debugging, and introspection framework for ordinary programs.

- **Perturb** owns experiment declarations: topology, typed connections,
  regimes, workloads, search, claims, evidence, and fidelity profiles.
- **jolt-sim** owns controlled execution: schedules, virtual worlds, boundary
  providers, fault plans, fresh-process isolation, replay, traces, and
  deterministic monitors.
- **Ripple** owns human and agent interaction with those declarations and
  artifacts: run, observe, pause, choose a branch, perturb, replay, compare,
  navigate, and export.
- **Application and library code remains the subject under test.** A node is an
  ordinary service, handler, process, or entry point. The harness must not
  require rewriting it as a simulator-specific state machine.

Repeated use may later reveal a useful library, framework, compiler mode,
effect system, or language. That is an outcome to earn through real
experiments, not the current milestone.

## Why the connection is the primary control boundary

The useful part of a `core.async.flow`-like UX is a visible graph with explicit
topology, contracts, lifecycle, and flow control. The crucial adaptation is to
put simulation policy on the **connection**, not to replace each node with a
new cell language.

A connection states:

- which real endpoints it connects;
- the protocol and correlation contract;
- capacity, ordering, partitioning, timing, retry, and lifecycle semantics;
- which boundary adapter owns interception;
- which operations pass through, are recorded, are simulated, or are hybrid;
- which fault outcomes are legal and how their activation is selected;
- which observations and monitors depend on the connection;
- how evidence at one fidelity maps to another.

For example, a simulated TCP connection chooses when and how bytes arrive, but
the real production codec and server consume those bytes. A simulated SQLite
boundary chooses native-call results and persistence facts, but the real DB
adapter and application transaction code execute. A requested control mode
without an executable interception witness is a compilation error. An
explicitly observational connection may be displayed and recorded, but checks
requiring control report `:inconclusive`.

## Authoring and persisted forms

The source API may provide a convenient `defexperiment`, but the persisted and
uploaded form is closed, versioned, canonical data. Executable functions are
resolved only through an explicit immutable trusted registry supplied by the
caller.

```clojure
(defexperiment outbox-under-pressure
  {:nodes
   {:client
    {:pack :jolt/driver-v1
     :ports {:requests {:direction :out
                        :capabilities #{:http/client-v1}}}}
    :app
    {:pack :jolt/in-process-service-v1
     :config {:entry :example.outbox.app/run}
     :ports {:requests {:direction :in
                        :capabilities #{:http/server-v1}}
             :transactions {:direction :out
                            :capabilities #{:sqlite/client-v1}}
             :outbox {:direction :out
                      :capabilities #{:tcp/client-v1}}}}
    :sqlite
    {:pack :native/sqlite-v1
     :ports {:database {:direction :in
                        :capabilities #{:sqlite/server-v1}}}}
    :receiver
    {:pack :jolt/in-process-service-v1
     :config {:entry :example.outbox.receiver/run}
     :ports {:tcp {:direction :in
                   :capabilities #{:tcp/server-v1}}}}}

   :connections
   {:command
    {:from [:client :requests]
     :to   [:app :requests]
     :pack :http/request-response-v1
     :config {:contract :example.outbox/commands-v1}}

    :store
    {:from [:app :transactions]
     :to   [:sqlite :database]
     :pack :sqlite/transaction-v1
     ;; This vocabulary belongs to :sqlite/transaction-v1.
     :config {:contract :example.outbox/store-v1}}

    :delivery
    {:from [:app :outbox]
     :to   [:receiver :tcp]
     :pack :tcp/bencode-v1
     ;; This vocabulary belongs to :tcp/bencode-v1.
     :config {:contract :example.outbox/delivery-v1}}}

   :profiles
   {:hermetic
    {:fidelity :hermetic-real-code
     :connections
     {:command {:mode :simulate}
      :store {:mode :simulate
              :params {:faults #{:busy :begin-ambiguous :commit-error
                                  :crash-after-commit}}}
      :delivery {:mode :simulate
                 :params {:capacity [1 2 4 16]
                          :latency-ms [0 1 99 100 101]
                          :ordering #{:fifo :reorder}
                          :faults #{:partial-write :drop-ack :duplicate
                                    :half-close :reset}}}}}

    :native-recording
    {:fidelity :real-local
     :connections
     {:command {:mode :record}
      :store {:mode :record}
      :delivery {:mode :record}}}}

   :workload
   {:pack :hegel/stateful-v1
    :config {:rules [:submit :retry :cancel :crash :restart :advance-time]
             :shrink [:operations :payload :schedule :fault-plan :capacity]}}

   :checks
   [{:pack :example.outbox/command-atomic-v1}
    {:pack :elle/txn-v1
     :config {:when :concurrent-multi-command}}
    {:pack :example.outbox/mark-after-ack-v1}
    {:pack :example.outbox/eventually-terminal-v1
     :config {:under #{:receiver-recovers :finite-faults
                       :worker-fairness}}}]}

  [ctx generated-case]
  ;; Ordinary application entry point, not a simulator facsimile.
  (example.outbox.app/run! ctx generated-case))
```

### Vocabulary ownership and compilation

The workbench core deliberately owns only the envelope: experiments, nodes,
ports, connections, profiles, workloads, checks, artifacts, `:from`, `:to`,
`:pack`, `:config`, `:mode`, and `:params`. It does **not** define keywords such
as `:csrf-required`, `:drop-ack`, or `:begin-ambiguous`. Node, connection,
workload, and check packs each own their own nested configuration vocabulary.

A named, versioned **connection pack** owns the vocabulary within its
`:config`, its supported modes and mode `:params`, legal faults, semantic
projection, monitors, and presentation. For example,
`:http/form-session-v1`—not Perturb itself—would define whether and how a
CSRF requirement is expressed. A project or library publishes that reusable
pack as trusted code; an individual connection selects and configures it.

Conceptually, trusted library code constructs one descriptor:

```clojure
(workbench/connection-pack
 {:id :http/form-session-v1
  :config-schema HttpFormSessionConfig ; closed, inspectable Malli data
  :editor HttpFormSessionEditor        ; canonical safe UI projection
  :modes {:simulate {:params-schema HttpSimulationParams}
          :record   {:params-schema HttpRecordParams}
          :pass-through {:params-schema HttpNativeParams}}
  :faults HttpFaultCatalog
  :compile compile-http-connection
  :projector project-http-history
  :presenters http-ripple-presenters})
```

Those executable values never appear in uploaded EDN. The canonical manifest
contains only stable IDs and data. At startup, the caller explicitly composes
an immutable allowlisted registry:

```clojure
(def experiment-registry
  (workbench/registry
   jolt-sim/default-packs
   http/packs
   example.outbox/packs))

(workbench/compile experiment-registry outbox-under-pressure)
```

Duplicate `(pack-kind, pack-id)` entries fail; later registries do not silently
override earlier ones. No namespace scanning, var resolution from EDN, or
mutable process-global registration occurs. The selected pack IDs,
implementation revisions, schema digests, and complete registry digest
contribute to the run identity.

A developer discovers the vocabulary rather than guessing it:

```clojure
(workbench/describe :http/form-session-v1)
(workbench/template :http/form-session-v1)
(workbench/modes :http/form-session-v1)
(workbench/faults :http/form-session-v1)
```

The same descriptor drives REPL help, generated documentation, completion and
validation, and Ripple's schema-derived connection editor. The first release
accepts a closed, inspectable Malli subset rather than arbitrary predicates,
transforms, or registry references. A canonical editor projection carries
field documentation, defaults, examples, enumerations, secrecy/redaction
metadata, and conditional visibility. Authoritative validation remains on the
runner. Unknown pack IDs, unknown keys, incompatible endpoint capabilities,
and unsupported modes fail closed before execution.

Compilation proceeds visibly:

1. validate the core experiment envelope;
2. resolve each versioned pack from the trusted registry;
3. validate its `:config` and the selected profile's mode `:params`;
4. check endpoint capabilities;
5. ask the pack to emit a closed binding result: consumed endpoint
   capabilities, bound operation IDs, route/provider ownership, permitted
   native fallback, fault rules, projectors, history adapters, presenters, and
   a mandatory mechanism probe;
6. freeze the result as an immutable execution plan with registry digests.

For `:simulate` or `:hybrid`, failure to produce that binding is a compilation
error. A pack can instead declare a connection `:observational`; that explicit
mode permits display/recording but makes checks that require intervention
`:inconclusive`. It never silently pretends to simulate the boundary. The
mechanism probe records whether subject calls actually traversed the selected
binding; a green property with an inert probe is not evidence.

Check packs follow the same rule. They own their `:config`, checker binding,
required observations, verdict domain, and Ripple presentation. Arbitrary
functions are not loaded from experiment data.

## Core model

### Experiment

An experiment fixes the subject revision, topology, workload, checks, artifact
policy, and available fidelity profiles. Its identity includes a canonical
digest. Changing any executable registry entry produces a new registry digest.

### Node

A node is ordinary code plus lifecycle metadata:

- kind and instance cardinality;
- start, ready, health, reset/namespace, drain, stop, kill, and restart;
- process/in-process placement;
- working directory and environment allowlist;
- capability and boundary declarations;
- retained artifact policy.

Node lifecycle is controlled by the harness, but application transitions stay
in application code.

### Connection

A typed connection owns:

- source and destination ports;
- request, response, stream, or transaction schema;
- correlation keys, generations, attempts, and idempotency keys;
- ordering, partitioning, capacity, backpressure, and terminal outcomes;
- mode-specific boundary adapters;
- fault vocabulary and deterministic activation coordinates;
- semantic history projection and required observations;
- mandatory mechanism probes and presenters.

The first four modes are:

| Mode | Meaning |
| --- | --- |
| `:pass-through` | Execute the real boundary with minimal structural evidence. |
| `:record` | Execute the real boundary and capture a canonical, redacted logical interaction. |
| `:simulate` | Control timing, capacity, faults, and provider outcomes while still invoking real endpoint code. |
| `:hybrid` | Model an explicit operation/resource subset and route the rest through the guarded real boundary. |

A later `:shadow` mode may compare a non-authoritative model with a real result.
It must not alter the authoritative result.

### Regime

A regime is a named mode plus parameters and search strategy for one connection.
It may constrain latency, loss, duplication, reordering, capacity, admission,
timeouts, retries, recovery, or native-call outcomes. Regime changes are
append-only commands with stable IDs and revisions, applied only at declared
safe boundaries or to the next run.

A **BREAKpoint** is therefore a scope over one connection or subgraph, not a
source point. It changes the regime or search objective while ordinary
breakpoints only pause on a predicate.

### Workload and search

Hegel owns generation, targeting, shrinking, and seed replay. The experiment
adapter declares generators, stateful rules, pools, schedule domains, fault
plans, fidelity profiles, and target metrics; it must not build a second random
choice loop.

Useful targets include runnable-set width, context switches, resource
handoffs, queue occupancy, retry amplification, simultaneous deadlines,
oldest-obligation age, saturation duration, recovery time, and distinct
resource-order fingerprints.

Infrastructure failures—dependency resolution, worker bootstrap, protocol
framing, or missing native libraries—abort the property run and retain their
artifacts. They are not generated counterexamples and are not shrunk.

### Checks and outcomes

The harness supports distinct check classes:

- pure safety invariants;
- deterministic post-hoc trace/history monitors;
- terminal resource and lifecycle obligations;
- conditional or bounded liveness with named fairness/environment assumptions;
- metastability and recovery classification;
- semantic parity across fidelity profiles;
- bounded proof claim packs;
- Jepsen/Elle-style consistency histories;
- external checker adapters.

The decision domain is at least `:pass`, `:violation`, `:inconclusive`,
`:not-applicable`, and `:checker-error`. A timeout alone never proves a liveness
violation without the declared bound and assumptions. A green test that did not
exercise its intended mechanism is `:inconclusive` or `:inert`, not evidence.

## Fidelity and assurance are separate axes

The same topology, IDs, property predicates, and semantic projection should run
through a fidelity ladder:

1. pure model/cooperative kernel;
2. real in-process application and protocol logic over modeled boundaries;
3. hybrid interception of selected native boundaries;
4. real local OS, native libraries, files, sockets, and processes in record mode;
5. distributed or staging execution observed through normalized telemetry.

Moving upward changes the observation adapter and residual risks. It does not
automatically strengthen every claim. An exhaustive hermetic result can be
stronger for its finite scope than a sampled native run.

Assurance strength remains explicit: `:asserted`, `:sampled`,
`:bounded-complete`, `:monitored`, `:proved`, `:opaque`, or `:failed`, with
denominator, bounds, assumptions, source, basis, and residuals. Missing
observations produce `:inconclusive`; they never silently pass.

A promoted witness records:

```clojure
{:witness/id "..."
 :source-fidelity :hermetic-real-code
 :target-fidelity :real-local
 :preserved {:input "..." :fault-plan "..." :schedule-prefix "..."}
 :result :survived        ; or :not-reproduced / :inconclusive
 :projection-diff "..."}
```

`:not-reproduced` is not `:fixed`.

## Metastability experiments

Metastability requires feedback, not merely independent fault injection.
Connection regimes and workloads must be able to vary offered load, queue and
pool capacity, latency, timeout thresholds, retry/backoff, admission, consumer
service time, partial capacity loss, fault correlation, and recovery timing.

Experiments record orbits such as queue depth, retry debt, consumer lag,
runnable tasks, outstanding obligations, latency, and recovery half-life.
Finite runs may conclude “non-recovering within bound B under assumptions A,”
not global metastability.

The same case should graduate from an abstract log/queue model through real
application logic, hybrid native boundaries, a real local broker or database,
and staging telemetry. Ripple compares whether the semantic violation and the
feedback cycle survive each promotion.

## Jepsen and Elle integration

The harness records a canonical semantic operation history separate from the
closed kernel replay trace:

```clojure
{:index 17
 :process 3
 :type :invoke                 ; :invoke / :ok / :fail / :info
 :f :transfer
 :value {:from :a :to :b :amount 5}
 :connection :client-api
 :request-id "r-17"}
```

Nemesis/regime commands enter the same history as correlated `:info` operations.
An adapter exports that history to established Jepsen/Elle checkers out of
process and records checker name, version, model, options, exit status, result,
and anomaly/cycle witnesses.

Elle is enabled per check, not globally. It is useful for truthful concurrent
transaction/read-write/list-append histories. It is `:not-applicable` for a
single outbox operation, a terminal view, or broadcast set convergence. The
harness must not manufacture an Elle pass for an unsuitable subject.

Hegel may generate and shrink client operations, schedules, and nemesis plans;
every candidate shrink reruns the authoritative checker. Ripple renders an
anomaly cycle and navigates each dependency edge back to history indices,
connections, trace observations, fault commands, and source coordinates.

## Veriframe integration

Veriframe is complementary model-authoring and proof-checking machinery, not a
jolt-sim runtime dependency. It is already a Jolt application with Z3,
SWI-Prolog, Lean, and Octave engines. Its live HTTP API supports asynchronous
runs, a cursor journal, interventions at turn boundaries, and abort. Its
deterministic `veriframe.engine.smt/run-smt` path and cross-checked SMT templates
are the relevant machine interface.

Keep the experiment separate from a versioned claim pack:

```clojure
{:jolt.sim.claim-pack/version 1
 :id :outbox/terminal-boundary
 :claim "an expired operation never marks delivered"
 :scope {:rows 1 :operations 1 :steps 12}
 :assumptions [...]
 :nonclaims [...]
 :queries
 [{:id :corrected
   :engine :z3
   :encoding "docs/proofs/outbox-terminal-boundary-invariant.smt2"
   :expected :unsat}
  {:id :relative-timeout-control
   :engine :z3
   :encoding "docs/proofs/outbox-terminal-relative-timeout-control.smt2"
   :expected :sat
   :witness-type :outbox/terminal-plan-v1}]
 :decoder :outbox/terminal-witness-v1
 :monitors [:outbox/terminal-durability]}
```

Normalize a result to engine/version, encoding digest, expected and actual
polarity, status, concrete witness, and Veriframe run/artifact references.
Acceptance requires:

1. rerun the exact encoding locally with the recorded solver/version;
2. match the expected polarity;
3. validate a typed, fail-closed witness decoder;
4. replay the decoded case/regime in jolt-sim;
5. obtain the deterministic monitor decision;
6. demonstrate non-vacuity and control reachability.

An existential SAT result without concrete coordinates cannot become a replay.
Veriframe's model review and LLM explanation are useful metadata, never verdict
authority.

The first bridge uses the existing outbox terminal-boundary proof: the buggy
relative-timeout control is SAT, its typed witness becomes boundary/offset and
regime coordinates, Hegel shrinks/replays it, the corrected query is UNSAT, and
Ripple links proof, Case/Outcome, trace location, and monitor decision. Elle is
`:not-applicable` to that single-operation case.

## External tools in one workbench workflow

Every tool consumes or augments the same immutable experiment/evidence graph.
None becomes an alternate scheduler or hidden source of truth.

```text
author experiment + register trusted extensions
  -> validate topology, contracts, mechanisms, and budgets
  -> Hegel generate / target / shrink
  -> jolt-sim execute ordinary code in a fresh or cooperative run
  -> deterministic monitors classify trace and obligations
  -> Jepsen/Elle check applicable semantic histories
  -> Veriframe/proof engines check bounded claim packs and emit witnesses
  -> exact replay validates every accepted witness
  -> Ripple/REPL inspect, pause, choose branches, and promote fidelity
  -> OTel and native tools attach higher-fidelity evidence
  -> CI and static HTML retain/render the same artifact graph
```

Tool roles:

| Tool | Consumes | Produces | Ripple affordance |
| --- | --- | --- | --- |
| Hegel | experiment domains and adapters | seed, case, targets, minimal counterexample | campaign progress, shrink tree, replay |
| jolt-sim | compiled experiment plan | trace, Case/Outcome, route/fault evidence | branches, stepping, obligations, semantic timeline |
| Veriframe/Z3/Prolog/Lean | claim pack and bounded model | normalized verdict, controls, witness | proof-to-case navigation and rerun status |
| Jepsen/Elle | canonical operation history | validity, anomalies, dependency cycles | cycle graph and correlated events |
| OTel/jolt-otel | real/hybrid semantic observations | OTLP capture and normalization report | distributed timeline and fidelity comparison |
| gdb/sanitizers/strace | isolated native worker and case digest | core/stack/sanitizer/syscall manifest | last durable phase and raw diagnostics |
| REPL | trusted registry and artifacts | commands, queries, derived views | same command protocol as the GUI |
| CI/static report | exact revisions and artifact graph | retained bundle and scriptless HTML | reopen the same failed case |

The durable journal is authoritative. Live taps and UI streams may be lossy and
recover by cursor. Presentation must never backpressure the execution being
observed.

## Ripple affordances

The first useful workbench includes:

- experiment catalog with exact subject/registry revisions;
- topology graph with node lifecycle and connection regime state;
- edge metrics: capacity, queue depth, latency, attempts, faults, obligations;
- profile/regime matrix and same-case fidelity promotion;
- semantic timeline correlated to kernel/effect traces and artifacts;
- first/previous/next/last, bookmarks, predicates, and run-until;
- cooperative enabled-branch preview and revision-scoped step commands;
- ordinary-runtime exact replay in fresh workers without fake rewind;
- scoped breakpoints and connection/subgraph BREAKpoints;
- Hegel target and shrink progress;
- invariant, liveness, mechanism-probe, and residual-evidence panes;
- Jepsen/Elle anomaly graphs;
- Veriframe proof/control/witness links;
- OTel and native diagnostic attachments;
- `datafy`/`nav` and `tap>` views over the same bounded read model;
- trusted per-event, per-connection, and per-domain presentation registries;
- static HTML generated from the same view models.

Any command that changes execution is authenticated, append-only,
revision-scoped, acknowledged exactly once, and applied at a declared boundary.
Rewind creates a fresh session from initial configuration plus a retained path
prefix; it does not mutate a live ordinary process backward.

## POC-derived requirements

The design was probed against five different shapes:

| Subject | Requirements it forces | Domain extensions, not core |
| --- | --- | --- |
| Gossip Glomers broadcast | multi-instance nodes, link partitions, convergence, envelope history, topology healing | Maelstrom JSON-lines launcher, broadcast/read model |
| MQTT/Kafka ingest | partition ordering, offsets, rebalances, retry amplification, backpressure, broker fidelity | broker lifecycle, codec, delivery semantics, offset/transaction projection |
| Rama-like topology | repartition, checkpoint crash boundaries, derived state, pure fold oracle | depot/PState/query semantics |
| User manager/outbox | HTTP sessions, auth/CSRF, DB transactions, idempotency, background delivery | router/form/cookie packs and business invariants |
| bbf1/a1s-like interactive app | generation-stamped results, PTY/view-tree facts, cancellation, subprocess ownership, redaction | terminal, cloud, subprocess, and screen presenters |

The shared core is therefore limited to versioned experiments, trusted
registries, lifecycle, typed connections and modes, deterministic fault/search
coordinates, semantic histories, checks/evidence, fidelity comparison,
artifact provenance, and workbench navigation. Protocol and business semantics
remain reusable extension packs.

## Security and forensic requirements

- Redaction occurs before canonicalization, journaling, OTel export, stdout or
  stderr capture, crash staging, and Ripple frames. Viewer-only masking is too
  late.
- Record mode captures normalized logical operations, never ambient credentials,
  unrestricted environment, raw secret bodies, bearer/session tokens, or raw
  subprocess argument vectors.
- Replay misses fail closed; they never silently call the real boundary.
- Hybrid native fallback is operation-allowlisted and refuses arguments that
  alias model-owned resources.
- Native crash, hang, poison, and sanitizer runs execute in fresh supervised
  workers. Ripple's long-lived process never hosts them directly.
- The append-only journal records command intent and acknowledgement before a
  live action is considered committed. Crash recovery reports incomplete
  commands rather than guessing their outcome.
- Every retained artifact records subject, experiment, registry, tool, model,
  checker, and binary revisions plus digests and redaction policy.

## First implementation slice

Use the existing canonical outbox application. Do not start by generalizing
every protocol.

1. Add a closed experiment manifest and validator for its existing application,
   SQLite, HTTP, TCP, clock, and delivery topology.
2. Add a trusted registry that separately compiles the HTTP command, SQLite
   store, and TCP delivery connections to existing handler packs and native
   route guards.
3. Support `:record`, `:simulate`, and `:hybrid` coordinates per connection;
   keep `:pass-through` explicit.
4. Emit a separately versioned semantic connection history that references,
   rather than alters, the kernel trace, effect trace, and Case/Outcome.
5. Adapt two existing outbox invariants to that history and record mechanism
   coverage.
6. Let Hegel vary connection capacity, latency, retry, terminal action, fault
   activation, and schedule; retain seed and minimal case.
7. Add Ripple topology and edge detail views, regime selection for a new run,
   exact replay, and cooperative branch navigation where truthful.
8. Implement the outbox Veriframe claim-pack bridge described above.
9. Add a Jepsen-shaped operation history, but return `:not-applicable` for Elle
   until a concurrent multi-command experiment truthfully exposes transactions.
10. Promote one minimized case from hermetic to hybrid/native and compare the
    semantic projection.

Acceptance is a working human/agent workflow, not a schema-only milestone:

- run the real outbox code from the REPL and Ripple;
- observe the graph and active connection regimes;
- find or replay a fault case with Hegel;
- inspect the minimal failure and all retained evidence;
- apply a different regime to one connection;
- promote the same case to a higher fidelity;
- validate a bounded proof witness through deterministic replay;
- produce a static report from the same view model.

## Explicit non-goals for v0

- no Perturb language fork, compiler, new type system, effect rows, linear
  capability checker, or mandatory cell/effect application architecture;
- no simulator-owned rewrite of HTTP, SQLite, codecs, business logic, or UI;
- no universal graph runner that becomes the application's production router;
- no claim that Malli schemas prove semantics;
- no arbitrary code execution from uploaded EDN;
- no raw expression tracing as a prerequisite for useful simulation;
- no in-process native crash/hang campaign in Ripple;
- no reimplementation of Elle, ARIES, OTel, gdb, or sanitizers;
- no compatibility promise before the first real release.

## Source material retained

This synthesis preserves and corrects ideas from:

- `JOLT_SIM_RESEARCH_BACKLOG.md` and `jolt-sim-planning/README.md`, plus the P0
  through P5 adversarial reviews and focused research notes dated 2026-08-01;
- `jolt-sim-architecture-review-2026-08-01.md`;
- `JOLT-SIM-MAELSTROM-FLOW-IMPLEMENTATION-PLAN.md`;
- `JOLT-FORMALIZABLE-APPLICATION-CORE-RESEARCH-PLAN-2026-08-01.md`;
- [`docs/research/PERTURB-DESIGN.md`](https://github.com/casselc/jolt-sim/blob/claude/ocaml-effect-based-language-gsg316/docs/research/PERTURB-DESIGN.md),
  [`APPLICATION-SKETCHES-UNDER-PERTURB.md`](https://github.com/casselc/jolt-sim/blob/claude/ocaml-effect-based-language-gsg316/docs/research/APPLICATION-SKETCHES-UNDER-PERTURB.md),
  and [`PRACTICAL-ADOPTION-AND-SIM-LEARNINGS.md`](https://github.com/casselc/jolt-sim/blob/claude/ocaml-effect-based-language-gsg316/docs/research/PRACTICAL-ADOPTION-AND-SIM-LEARNINGS.md)
  at Perturb research commit `1464615f4946b18d40498d62920d2dc20e782867`;
- the current application-core charter handoff and its effect-family/runtime-seam
  requests on `casselc/jolt` commit `999d2504`;
- [Veriframe README](https://github.com/yogthos/veriframe/blob/40de2c1105bbf7fef84c44f19851042b9668c4ee/README.md),
  [SMT engine](https://github.com/yogthos/veriframe/blob/40de2c1105bbf7fef84c44f19851042b9668c4ee/src/veriframe/engine/smt.clj),
  and [journal](https://github.com/yogthos/veriframe/blob/40de2c1105bbf7fef84c44f19851042b9668c4ee/src/veriframe/store/journal.clj);
- [Jepsen](https://github.com/jepsen-io/jepsen),
  [Elle](https://github.com/jepsen-io/elle),
  [FlowStorm](https://github.com/flow-storm/flow-storm-debugger), and the
  previously reviewed deterministic-simulation systems.

The original Perturb research remains valuable requirements research. Its
language-first scope and mandatory `(state,event) -> [state,effects]` shape are
not inherited. The workbench must earn any later language design through real
application experiments.
