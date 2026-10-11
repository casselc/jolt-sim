# cordis: phase 1

Phase 1 of the harness in [docs/cordis/DESIGN.md](../docs/cordis/DESIGN.md)
(build order, "Phase 1: pure core and simulator"): the Cordis lifecycle
calculus as pure OCaml, a hand-written Quint model of it, and a deterministic
simulator that checks one against the other.

| Directory | What it is | Design item |
| --- | --- | --- |
| `kernel/` | `Kernel`: the calculus (`target`, `relied`, `next`, `apply`, `orchestrate`) with Uncertain, turns, compensation, failure and episodes, as total functions with no IO. `Order`: ensemble's `restart-plan` and `intensity`, ported. | 1 |
| `quint/` | `lifecycle.qnt`: the calculus and the outbox's answers, with a `design` instance, two mutants (`noGuard`, `blindRetry`) and five witnesses. `check.sh` runs the model gate; `model-verdict.sh` holds casselc/observability's fail and warn rules. | 2 |
| `quint/outbox.qnt` | The outbox placement: per-agent outbox jails with journals and credentials, a central limit service, `pf` egress, crashes, restarts, step-snapshot rollbacks and lost messages. Four mutants, five witnesses. | 3a |
| `quint/branches.qnt` | Snapshot branching: one snapshot restored into several VMs with fork IDs; the outbox holds every branch's intents until one is chosen. Three mutants, three witnesses. | 3b |
| `quint/gateway.qnt` | Tool calls through the MCP/A2A gateway: remote and hosted tools, annotations that can lie or be missing, rollback replay. Two mutants, two witnesses. | 3c |
| `quint/caps.qnt` | Sturdy-ref attenuation: caveats appended by holders, a MAC chain the gatekeeper checks. Two mutants, two witnesses. | 3d |
| `sim/` | The simulator: one entropy `Oracle` makes every choice; `Assertions` speaks the Antithesis vocabulary; `Itf` writes runs as model-level ITF traces for `quintgo validate`. | 3 |
| `hegel/` | The oracle over a Hegel test case, so failing runs shrink. | 3 |
| `bin/` | `cordis-sim`: run a campaign, print its verdict, write ITF traces. | 3 |
| `cel/` | CEL for cells (RFC 0001): parser, macros, evaluator, actual cost; cel-spec conformance and a differential test against cel-go. See `cel/README.md`. | phase 2, 4 |
| `graph/` | The graph language (RFC 0002 rev 2): parser, printer, hashes, checker, cost budgets, compatibility, hash-guarded patches, compilation to the kernel; `cgraph`. Descriptor-native protobuf types across revisions (RFC 0004). | phase 2, 5 |
| `import/` | Importers (RFC 0003): protobuf, and JSON Schema drafts 4 to 2020-12 with a compiled CEL predicate, an interpreter and a strict codec; `cimport`. Checked against the official JSON Schema Test Suite, Python's `jsonschema` (Hegel) and SchemaStore. | phase 2, 6 |
| `quint/admission.qnt`, `assets.qnt`, `routes.qnt`, `revisions.qnt` | Phase 2's models: concurrent edits, memoisation and freshness, guarded assets and feedback loops, descriptor-native revisions (RFCs 0002 and 0004). | phase 2 |
| `import/lib/openapi.ml` | The OpenAPI importer (RFC 0003 revision 4): an operation to an effect, its request record and response variant from the JSON Schema importer, and `openapi_http.ml`, the wire encoding. `import/test/openapi_agreement.ml` checks it against openapi-core; the APIs.guru sample (`fetch_openapi_corpus.sh`) imports 13,061 of 13,747 operations. | phase 3 |
| `import/lib/table_schema.ml`, `csv_codec.ml` | CSV typed by a Frictionless Table Schema (RFC 0005 revision 3): the row imported through the JSON Schema importer, and a strict RFC 4180 codec; `cimport tableschema`. Checked against frictionless (`@import/test/csv-agreement`). | phase 2 |
| `import/lib/xsd.ml`, `xml_tree.ml`, `xml_codec.ml` | XSD and XML without recursion (RFC 0005 revision 4): the schema imported through the JSON Schema importer with a shape for the codec, a strict XML 1.0 parser of the harness's own (no DTD), and a strict, canonical codec; `cimport xsd`. Checked against xmlschema and lxml (`@import/test/xml-agreement`). | phase 2 |
| `engine/` | The turn runtime's sans-IO core (RFC 0006 rev 2, stage 1): recomputation as `routes.qnt` states it, one record per step, the output gate, receipts matched by key and attempt, observational retries and effectful reconciles. `engine/test/review_loop.ml` runs `review.cg` on a loopback host under Hegel. | phase 3 |
| `outbox/` | The outbox process (`cordis-outbox`): a SQLite journal outside the snapshots, every intent journaled before it runs and run once, executors for `llm.complete` (an OpenAI-compatible endpoint) and `repo.read`; with `cordis-run` (`engine/bin`), the engine as a process. `outbox/test/e2e.sh` runs the review loop through crashes against a stand-in server; `cordis-limiter` is the central limiter, keyed by emission, and `limiter.sh` shares it between two agents; `--api DOC` runs an imported OpenAPI document's operations, and `openapi.sh` runs a graph over one; `latency.sh` replays a real model's habits, `fix.sh` runs `review_fix.cg` (a writer that applies the notes), and `fanout.sh` runs N agents at once against one server (`dune build @outbox/test/e2e`). | phase 3 |
| `ops/rollback-fidelity.sh` | The run store's native rollback-fidelity check, for the FreeBSD host (root, a ZFS pool): snapshot, run on, roll back, remount, compare the logical records; then resume and require the outbox to answer what the rollback re-released (RFC 0006 stage 3). Not yet run on ZFS. | phase 3 |
| `quint/cutover.qnt` | Moving a running instance to an admitted revision: shadow soak, durable begin and end, assets switched at commit boundaries, providers first (RFC 0006 decision 6). The engine's `admit`, `Switch`, `soak` follow it; `engine/test/cutover_test.ml` tests them. | phase 3 |
| `quint/budget.qnt` | Reservations written with the turn that needs them, settlement once at the actual cost, reservations held while Uncertain, across crashes (RFC 0006). The engine's budgets follow it. | phase 3 |
| `quint/schedule.qnt` | Schedule independence (two copies, independent orders, the same rest and the same released intents) and deficit round robin across graphs with a bounded wait (RFC 0006 decision 4). The engine's `Sched` follows it; `engine/test/sched_test.ml` tests it over several review loops. | phase 3 |
| `quint/recovery.qnt` | Durable versus process-scoped acquisitions: durable ones recorded and released by generation, and the recovery sweep releasing those whose owner was not reinstalled, once (RFC 0006). The engine has none yet; yard's leases will follow it. | phase 3 |
| `quint/intake.qnt` | The runner, the outbox's intake and its calls: intake is never held back by a running call, and only an attempt the outbox accepted is charged as lost (RFC 0006, from the halo-flash run). The outbox runs calls on threads; `outbox/test/latency.sh` checks it with slow calls. | phase 3 |
| `quint/runtime.qnt` | The engine's protocol over its own alphabet, with parameters: the target engine runs are replayed in (`engine/test/conform.exe` writes them, `quintgo validate` replays them; `check.sh` runs twelve). | phase 3 |
| `quint/engine.qnt`, `handoff.qnt` | The turn commit protocol (one record per turn, group commit, the output gate, crashes) and the relay from the run store to the outbox journal across step-snapshot rollbacks (RFC 0006). | phase 3 |
| `quint/frames.qnt`, `observe.qnt` | Frames consumed in batches (RFC 0005 rev 2); observational emissions, which retry under reservations instead of reconciling (RFC 0006 rev 2). Written before their implementations. | phase 3 |
| `test/` | Alcotest suites: kernel and `Order` units, simulator campaigns, confluence, replay; Hegel properties with a negative control. | |

## Running it

```sh
opam switch create cordis 5.4.0
opam install dune yojson alcotest hegel
npm i -g @informalsystems/quint           # 0.33
# quintgo, from casselc/observability:
(cd observability/quintgo && go build -o "$HOME/bin/quintgo" ./cmd/quintgo)

dune build && dune test                    # 13 Alcotest suites (~300 cases) plus the conformance
                                           # and golden checks; seconds from a clean build
dune exec -- cordis-sim --runs 500         # a campaign over the chain scenario
dune exec -- cordis-sim --scenario random --fibers 6 --runs 1000
dune exec -- cordis-sim --mutant blind-retry   # exits 1: at most once is violated
QUINTGO=$HOME/bin/quintgo quint/check.sh   # the model gate, about fifteen minutes
```

`quint verify` (Apalache) needs Java 17+. `quint run` here uses `--backend typescript` (`QUINT_BACKEND` overrides it):
the Rust backend is fetched from GitHub on first use, which some sandboxes
refuse.

## What is checked, and how strongly

Every result is graded as in the design's evidence table; nothing that did
not run counts as a pass.

| Property | Simulator (`always`) | Quint | Grade |
| --- | --- | --- | --- |
| The guard: an installed fiber's view names only installed providers (Cordis Thm 70) | every state | `guardHolds`; mutant `noGuard` breaks it | `:sampled`; `:exhausted` to 8 steps |
| At most once: no emission takes effect twice | every state; mutant `blind-retry` breaks it, Hegel finds it | `atMostOnce`; mutant `blindRetry` breaks it | `:sampled`; `:exhausted` to 8 steps (model) |
| Terminal recovery: a fiber not installed holds no bindings; an unconsumed binding holds its provided value (Cordis Thm 68) | every state | `boundOnlyWhenRun` | `:sampled`; `:exhausted` to 8 steps (model) |
| Progress: every run quiesces, and a run with nothing left to do is quiet (Thm 73) | end of every run | not modelled (no liveness) | `:sampled` |
| Confluence: every schedule quiesces to the same tables when shared operations commute (Thm 80) | 60 schedules of a fan-in; with `Put` instead of `Add`, schedules disagree, as they should | not modelled | `:sampled` |
| Outbox: at most once across jail crashes and snapshot rollbacks; the shared limit holds; only outbox jails reach the service; a Done receipt is true | — | `outbox.qnt` `safety`; mutants `journalInSnapshot`, `localLimits`, `pfOpen`, `resendOnRestart` each break theirs | `:sampled`; `:exhausted` to 8 steps |
| Branches: only the chosen branch's emissions take effect, once; only it gets real receipts; every intent is answered | — | `branches.qnt` `safety`; mutants `releaseEarly`, `releaseEarlyNoDedup`, `noForkIds` | `:sampled`; `:exhausted` to 8 steps |
| Gateway: no call has an outside effect twice; remote tools leave only through the outbox | — | `gateway.qnt` `safety`; mutants `ungated`, `missingIsInternal` | `:sampled`; `:exhausted` to 8 steps |
| Capabilities: attenuation never widens | — | `caps.qnt` `safety`; mutants `uncoveredCaveats`, `unionCaveats` | `:sampled`; `:exhausted` to 8 steps |
| Frames: each batch committed once with its cursor and revision; cutover only at commits; the accumulator typed by the live revision; intents observed once; the end turn after the last batch; no row lost to a full buffer | — | `frames.qnt` `safety`; mutants `splitCommit`, `midBatch`, `carryAny`, `noDedup`, `endEarly`, `refuseAdvances`, `keepAcc` | `:sampled`; `:exhausted` to 8 steps |
| Observational emissions: an effectful intent takes effect once; one answer applied per intent; spend within budget and never under-counted; an observational intent never Uncertain | — | `observe.qnt` `safety` (instances in `observe_modes.qnt`, `observe_report.qnt`); mutants `misclassify`, `refundLost`, `noReserve`, `applyLate`, `oneClass` (the single-class rule), `keyIgnored`, `releaseObs`, and for what a keyed answer reports, `releaseUnreported` and `underReport` | `:sampled`; `:exhausted` to 8 steps |
| Conformance: a simulator run is a run of the model | — | `quintgo validate` replays ITF traces; a blind-retry run claiming to be the design is rejected | `:monitored` |

## What the simulator found

Restarting a fiber after a failed receipt re-ran its iterations and re-sent
their intents under the same idempotency key, so a restart read as a
duplicate emission. Each activation is now an episode (Cordis's own term):
the fiber's episode counter is part of every released intent's key, and the
model counts emissions per episode.

## What the phase 1b models found

- **Gateway:** classification by MCP annotation is a hint, not a guarantee.
  A hosted MCP server annotated `openWorldHint: false` that does reach out
  is classed internal, replayed on rollback, and acts twice. The fix the
  model checks: a hosted server's own egress also goes through an outbox,
  keyed by the calling emission's idempotency key, which the gateway passes
  in the MCP request's metadata. With honest annotations and "missing means
  emission", effects are single even without it (`honestUngated`).
- **Branches:** releasing before a branch is chosen breaks `onlyChosen`,
  but with keys drawn from the oracle and deduplicated, identical branches
  still cannot double an effect (`releaseEarly` keeps `atMostOnce`).
  Without fork IDs in the outbox's records, a real receipt reaches every
  branch that issued the same key.
- **Outbox:** each of the placement's four choices (journal off the
  snapshotted dataset, a central limit service, `pf` egress from outbox
  jails only, reconcile on restart) is load-bearing: dropping any one
  breaks a property.

## Deviations from the design sketch

- `Reloading` carries `awaiting`, and `Uncertain` carries `rest`: an
  iteration's intent is in flight between `Iter` and its receipt, and the
  iterations left must survive a reconcile.
- `Finish` is its own step after the last `Iter` rather than landing with
  it, so `Divert` may also fall after the last iteration.
- Instantiation (Cordis Definition 52: a component inserting a child) is not
  built yet; the `parent` field and `Remove`'s check are in place for it.
- The simulator is a scheduler over the pure kernel, not yet a seeded Picos
  scheduler with an Eio mock: no effect handlers run cell code yet, because
  there are no cells until phase 2.
- Supervision restarts one fiber at a time (`One_for_one`); `restart_plan`
  for the other strategies is ported and tested but not wired in.
