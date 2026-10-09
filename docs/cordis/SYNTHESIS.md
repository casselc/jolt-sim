# Cordis Synthesis: Components, Effects, Coeffects

Oct 9, 2026 · @Chuck

Cordis supplies the missing half of what samizdat, jolt-sim, bbagent and the mycelium design already built: those projects have declared dependencies, effect signatures, commit-gated emissions and hot reload, but no inverses and no lifecycle that reacts to the context. Read through Cordis, a mycelium cell is a component, a manifest is a composition of components, and the mutation protocol is a revision of a running fiber.

## Where everything left off

Nothing has merged to any main branch since 2026-09-18, and the design-heavy work sits on unmerged branches in samizdat, samizdat-agent-bootstrap and jolt-sim. Newest first:

| Repo | Workstream | Frontier branch | Last commit | State | Decision pending |
| --- | --- | --- | --- | --- | --- |
| samizdat | Telemetry, embedded collector | `perf/embedded-collector-options-20261008` | 2026-10-08 | 1 commit, no PR; starts issue #44 | Open a PR or fold into #44 |
| samizdat-agent-bootstrap | Judgment eval, worker screen | `agent/onbox/jev-judgment-eval-v1` | 2026-09-26 | Eval-3 configured, not run; accounting hold reconciled | Run eval-3; main is still the 2-commit kit, PRs #1 and #2 open |
| samizdat | Context selection, run idempotency | `agent/onbox/context-selection-seam` | 2026-09-23 | 78 behind main; migration numbers clash (#46) | Rebase onto main's schema |
| samizdat | Main | `main` (PR #59) | 2026-09-18 | Telemetry and durable demo landed; PR #57 open | Merge #57 |
| samizdat | Closed-domain decisions (decide, ADR-002) | `canonical` → `agent/onbox/outcome-pilot-v1` → `agent/onbox/observability-v1` | 2026-09-05 to 09-12 | Parked; never PR'd; about 140 behind main | Issues #5, #11, #14, #19 gate wiring it in |
| stpa-workbench | Lifecycle STPA RFC | `claude/stpa-workbench-rfc-1zzv3b` | 2026-09-12 | Schema v0, fixture, diff and impact oracles; RFC still proposed | Review and merge |
| samizdat | Self-hosting evaluator (JS1, JS2) | `js2-converge-closure-34a21ab` | 2026-08-30 | M4 attempt 2 and JS2 PASS; frozen evidence, never merged | Port the 13-point contract, not the branch |
| bbagent | Execution SPI | `main` | 2026-08-28 | A3c landed; S0 branches superseded | None open |
| jolt-sim | Jolt 0.7.16 and FFI descriptor v9 | `codex/ffi-descriptors-v0716` (#130 on #129) | 2026-08-19 | Draft PRs; #128 partly superseded | Land #129 and #130; reconcile #128's Hegel work |
| agent-lisp-starter | REPL-versus-catalog experiment | `feat/first-scripted-slice` | 2026-08-12 | Isolation built; no real-model runs | Run milestone 1 |
| jolt-sim | Flow cells, command cells, workbench | `main` (PR #127) | 2026-08-11 | Flow compiler, commit-gated effects, retained workers, command cells landed | Build the long-lived process and link layer, or the native Ripple client |
| jolt-sim | Docs direction | #123 versus `agent/docs-human-story-v1` | 2026-08-11 | Two competing rewrites; #123 carries the Mycelium project vision | Pick one |
| agent-lisp-mycelium | Recursive cell harness | `main` | 2026-08-11 | Design only | None open |
| jolt-sim | Perturb language fork | `claude/ocaml-effect-based-language-gsg316` | 2026-08-04 | 246 commits, no merge base with main; non-authoritative by its own ROADMAP | Keep as research input |

Beads could not be read: `bd` is not installed in this environment and no `.beads/` directory is checked out, so GitHub issues stood in for the open-work list. jolt-sim main was squashed into one root commit on 2026-08-06, so about 110 older branches and 11 `backup/pr*` branches are already in main and can be deleted.

## Cordis in one page

Cordis turns effects and coeffects from compile-time annotations into runtime objects, so a component can be loaded, removed and reloaded while the system runs. Two guarantees: removing a component reverts everything it did (temporal composability), and a component runs only while what it depends on is present (spatial composability).

**Revertible effects (§3.1).** An effect function takes the context and returns the new context plus an inverse that reverts it *at that state*:

```latex
\mathfrak{E}_\Gamma := \Gamma \to \Gamma \times (\Gamma \to \Gamma), \qquad (\delta, g) = e(\gamma) \Rightarrow g(\delta) = \gamma
```

The runtime composes each returned inverse onto an accumulator, last in first out. A component's whole load is one effect iterator (a generator yielding state, inverse, continuation); unloading it is applying the accumulator.

**Reactive coeffects (§3.2).** The coeffect context is a typed partial map from keys to values. A component declares a specification d, the set of keys it needs. Every context change is classified against d as activating, deactivating or neutral. Providing a key (`set`) is itself a revertible effect whose inverse removes the key.

- *Isolation* resolves one key to different values for different components (realms).
- *Interception* attaches metadata to an access. It merges right-biased, so an enclosing context can narrow what a component may do with a dependency without touching either side.

**The context paradigm (§3.3).** One context carries state, accumulator and coeffects, and every interaction passes through it. A key carries a value type plus a set of operations, and those operations alone define equivalence at that key. Two states are equal when no sequence of operations can tell them apart. That is what lets a freed heap block or a fresh name count as reverted.

**Independence (§3.4).** Two components' effects interleave and revert in any order when their operations commute. The provider of a key carries the proof that the key's operations commute. A table of uniquely tagged entries (routes, listeners) commutes; an ordered middleware chain does not.

**Components and fibers (§4).** A component is a triple: specification d, provision p (keys it may install) and a witnessed effect iterator. A fiber is one instance of a component, with a lifecycle state:

- **Inactive**: contributes nothing.
- **Reloading**: running its iterator, accumulating inverses.
- **Active**: providing its keys.
- **Unloading**: decided to leave, not yet reverted.

The orchestrator only inserts and retires fibers; lifecycle steps happen by themselves when a fiber's *target view* (which provider each declared key resolves to) differs from its *committed view*. The guard: a provider may not run its inverse while any installed fiber still resolves a key to it.

**Confinement (§4.2.3).** An effect function may read and write only its own table and the keys it declared, and may create child fibers. It may not read lifecycle state or anything no key binds.

**Metatheory (§4.3).** All results quantify over every schedule:

- Preservation.
- Terminal recovery: a closed episode leaves the tables as if the fiber never ran.
- Ordering: a provider activates before and deactivates after its consumers.
- Progress: with acyclic dependencies and finite iterators, the system always quiesces.
- Confluence: the quiescent state depends only on the final configuration.

Confluence is what makes incremental config reconciliation and hot module replacement sound.

**Extensions and limits (§4.4, §6).**

- Asynchronous hosts are *inertial*: a running transition completes before the next begins.
- A failed iteration unwinds its partial accumulator and marks the fiber failed, without propagating to siblings.
- A configuration change is retire, deactivate, remove, reinsert.
- The *system boundary* splits every outside operation into an **acquisition** (install a handle; revertible) and an **emission** (push data out; not revertible). Emissions can only be withheld until commit or compensated.
- Dependencies are linked by key name only; typed and versioned linking is named as open.

## Vocabulary map

Most Cordis concepts already exist somewhere in the repos under another name; the gaps are inverses, provisions and a reactive lifecycle. Status: built = on a main branch, branch = unmerged, design = docs only.

| Cordis concept | samizdat | jolt-sim | bbagent, JS1, starter | mycelium design, bootstrap | Status |
| --- | --- | --- | --- | --- | --- |
| Context Γ | `ctx` (fixed `ctx-keys`) plus userspace store | Kernel state, an immutable value | bb4t SCI Context; the single Environment authority owner | Cell `ctx` | built |
| Effect with inverse | None per change. `userspace/revert!` re-appends a chosen version by hand | `install-controller!` returns a restore token, restored LIFO | JS1 commit-only state: failed eval rolls back to committed history | Registry rollback "changes future resolution only" | built only for controllers |
| Accumulator, recover | Mutation checkpoint: whole registry and files snapshot | Keep the old kernel value; session branch preview | Discarded VM overlay: total, coarse revert | None | coarse only |
| Effect signature | Cell `:pure` or `:effects [:net :db :fs :proc]`, enforced | Flow cell `:emits #{intent-kinds}`; perturb op tables | Catalog: every effect is observation or actuation | Cell `:effects` descriptors with reconciliation | built |
| Coeffect specification d | Cell `:requires` and `:input` schema, checked at compile | Flow cell `:requires #{resources}` and closed `:input` | ContextSpec `:requested-capabilities`, `:resource-bindings` | Cell `:resources`, `:authority` | built, static only |
| Provision p | Implicit: whatever a cell writes into `data` (`:output`) | Flow cell `:output` delta | None | None | missing |
| notify: activating, deactivating | Gate `:when` re-evaluated each boundary; decide revalidation `:fresh`, `:authority-changed`, `:stale-revision` | None | Context refused at creation if a resource is unbound | Commands fail stale on digest mismatch | point checks only |
| Committed view ω | DecisionDomain `:based-on {:graph/revision :state/version}` | Revision-scoped `step!` | Pinned orientation per EvaluatorBinding | Descriptor and registry digests on commands | branch |
| Interception metadata | Role `:tools`, `phases.edn :withholds`, aspect roles observer, recorder, guard | Connection regimes `:normal`, `:drop`; hermetic, observe, hybrid modes | Effective authority = request ∩ controller ∩ catalog ∩ runtime | Child authority attenuated from parent | built in parts |
| Isolation (realms) | Per-project userspace; per-branch REPL namespace | Pack registry modes | SmolVM guest per run | Hidden verifier as sibling subtree | built |
| Fiber, lifecycle | ebb task tree; cancelled is not an error | Retained worker statuses; §3.5 says flows have "no story at all" for lifecycle | TurnLease: revoke before interrupt | Spawn, admit, reserve, start, fail, reconcile, cancel | partial |
| Guard (provider outlives consumer) | None | None | None | "Parent cannot terminate while live child leases remain" | design |
| Confinement | `resolve-under-root`, `check-requires!` | Regions: `close!` reports a non-empty exit with a count | Exact-key argv; no host namespaces | `:children {:max-depth :max-count}` | built in parts |
| Commutativity witness | `:must-precede` and `:must-follow` invariants, as path constraints | Handler-pack merge with fail-closed collision attribution | None | None | missing |
| Observational equivalence | None | Observation adapters; `:inconclusive` when observations are missing | Replay compares receipts, not heaps | None | partial |
| Acquisition versus emission | Journal is truth; telemetry may drop | `:absorbs` and `:emits`; outbox COMMIT then delivery | Replay at the semantic operation boundary | "Preview performs no effect" | built |
| Withhold until commit | decide `:act` demoted to `:defer` unless revalidated | `flow_effect_session`: publish only after an exact branch commits | Intent and outcome receipts | Invariant 8: no duplicate external effect | built |
| Uncertain emission | `idempotency_unresolved`, 409 | `:uncertain`, accepts only `reconcile!` | Ambiguous REPL request fails recovery | "Uncertainty is reconciled, not blindly retried" | built, absent from Cordis |
| Config reconciliation, HMR | `cells/load-cells!` drops vanished cells; `propose-cell!` validates, soaks, saves | Regime changes as revisioned commands at safe boundaries | Rejected: application code is trusted and compiled | Promotion tiers: run-local, project, global | built |
| Failed inverse | Rollback restores the registry snapshot | Failed restore poisons the session | Failed rollback poisons the instance | None | built |

## The unified model

One sentence: **a cell is a Cordis component whose effects are sorted by where they land relative to the system boundary, whose coeffects are typed keys with session-typed operations, and whose every witness carries an evidence strength.** Each part below names what it absorbs from the existing work.

### 1. Cells are components; manifests compose them

A cell already declares most of a Cordis component: `:requires` and the `:input` schema are its specification d, and `:effects` is its effect signature. Two things are missing:

- **A provision p.** Cordis needs to know which keys a component may install, so it can enforce single-provider and compute who relies on whom. Today a cell's `:output` plays this role for the data map, but nothing names the context keys a cell provides.
- **An inverse for each stage.** A cell's effect function must be a generator of stages, each yielding its inverse. That is Cordis's effect iterator, and it is also the shape of a mycelium step with a journal note.

A manifest is then a component whose iterator instantiates its cells as child fibers. Nesting (`beam.edn` containing `loop.edn`) is Cordis's hierarchical composition: unloading the parent retires the children. The core/behaviour/policy split survives intact: the calculus (lifecycle rules, guard, accumulator) is mechanism in `src/`; specifications, provisions and inverses live with the cells in `resources/`.

### 2. Three classes of effect, sorted by the boundary

Cordis's boundary (§6.1) is the organising idea the projects were missing a name for. Every declared effect falls into exactly one class, and the class decides its discipline.

| Class | What it touches | Discipline | Existing instances |
| --- | --- | --- | --- |
| Internal | State the system alone owns and can restore: userspace rows, the cell registry, the tape, REPL definitions, kernel state | Revertible: return an inverse; accumulate; unload applies it | Controller restore token; tape truncate; immutable kernel value |
| Acquisition | A handle on something outside: a file descriptor, a worker, a connection, a region member | Revertible at the handle: the inverse closes or releases it; a region's must-be-empty exit is terminal recovery | Regions and `close!`; `retained_process` terminate; SmolVM delete |
| Emission | Data pushed through a handle: a write, an HTTP send, a published command, a model call | Not revertible. Withheld until commit, then published at most once; a lost outcome becomes **uncertain** and must be reconciled; compensation is an optional, coarser inverse | `flow_effect_session`; command cell session; outbox; JS1 receipts; decide `:act` versus `:defer` |

The projects built the third row carefully and Cordis builds the first two. Put together, the rule for a cell author is short: internal effects get inverses, acquisitions get release functions, emissions go through intent, commit and receipt. The soak in the mutation protocol already behaves this way, by stubbing non-pure cells instead of reverting them: it is a derived context with every emission intercepted.

### 3. Coeffects become reactive

Today dependencies are checked once: at compile (`check-requires!`, the schema chain), at context creation (bbagent), or at apply (decide revalidation). Cordis makes the check continuous: a dependency change re-targets every fiber that reads it.

- A DecisionDomain's `:based-on {:graph/revision :state/version}` is a **committed view**. Revalidation is the comparison of committed view with target view, taken at the one point where an emission is about to happen.
- Its outcomes map directly: `:fresh` is neutral; `:authority-changed` and `:stale-revision` are deactivating; demoting `:act` to `:defer` is L-Divert.
- Gates whose `:when` is "re-evaluated, not latched" are polled notifications; under Cordis they become subscriptions on the keys their forms read.

Keep revalidation at the emission point as well as reacting continuously: reactivity decides whether a fiber runs; revalidation decides whether one specific emission may leave.

### 4. Typing and sessions are coeffect operations

Cordis gives each key a value type and a set of operations, and lets those operations alone define equivalence. That slot is where the typing work fits:

- **Malli schemas are the value types.** Closed maps are required, not optional: issue #14 showed an open input schema lets `:decide/allow-unrevalidated?` through.
- **A session type is the protocol over a key's operations.** perturb's typestate (`dbtx` begin to open, idle or poisoned) and the projected `defsession` roles say which operation may follow which. Cordis's operations are untyped in order; adding the protocol makes "use after close" a refusal instead of a runtime error.
- **Linearity is the accumulator.** E21 concluded that silent discard is the defect and that capabilities need linearity plus an explicit cancel. In Cordis every inverse in the accumulator is a linear obligation discharged by exactly one L-Unload, and L-Divert is the explicit cancel.
- **Observation adapters are equivalence.** Progressive formalism's refuted §1.1 found that each consumer needs an observation adapter. That is Cordis's Definition 31: equivalence is relative to the operations an observer can run.

### 5. Every witness carries evidence

Cordis demands two proofs: each inverse reverts its effect (Definition 8), and each key's operations commute (Definition 46). It treats them as given. Progressive formalism says what a real system can supply instead: a value with strength (`:unknown` to `:proved`), scope, source and basis, combined by meet.

- An inverse checked by property-based round trips is `:sampled`; one for an immutable value is `:proved`.
- A commutativity witness found by exhaustive interleaving under jolt-sim is `:exhausted` over a declared domain.
- "Nobody looked" stays `:unknown` and never reads as independent; an empty check is `:inconclusive`, never a pass.

The orchestrator then reads the weakest witness on a fiber and decides policy from it: for example, a component with an `:unknown` inverse may load but may not be hot-replaced without a full restart. STPA's unsafe control-action pairs (UCCAs) are the counterexample corpus: each is an interleaving where independence fails, and makes a test oracle for commutativity witnesses.

### 6. The mutation protocol is a fiber revision

Cordis's §4.4 defines a configuration change as retire, deactivate, remove, reinsert, and its Algorithm 10 reloads transactionally with backup and restore. `propose-cell!` is already that algorithm:

| Mutation step | Cordis reading |
| --- | --- |
| Checkpoint | Back up the old module (Algorithm 10, line 2) |
| Reload | Insert the new fiber |
| Validate | Specification and provision checks before L-Begin |
| Soak | Activation in a derived context with emissions intercepted |
| Commit | Keep the new fiber; append the userspace version |
| Rollback | Restore the backup; reinstate the old fiber |

What Cordis adds is the part the protocol cannot do today: retiring the old fiber also *reverts its internal effects*, dependents deactivate before the provider leaves, and Theorem 80 says the result equals a cold start of the new configuration.

## The extended lifecycle

Add one state to Cordis's four: **Uncertain**, entered when a published emission's outcome is lost. It accepts only `reconcile!`, and it blocks the fiber from unloading, so a provider cannot vanish while the world may still hold an effect of its.

*(Diagram: fiber lifecycle · Cordis's four states plus Uncertain. The diagram was interactive in the source document and is not reproduced here.)*

The new state changes one premise: L-Unload's guard gains "no uncertain emission". Progress (Theorem 73) then needs reconciliation to terminate, which is a policy number, not a theorem.

## Where the existing work disagrees with Cordis

Six real disagreements; none is fatal, and in four of them the existing work fills a gap Cordis leaves open.

| Disagreement | Cordis says | The repos say | Resolution |
| --- | --- | --- | --- |
| Undo versus intent | Every effect carries an inverse; removal reverts | Emissions cannot be undone, so withhold them until commit; registry rollback "changes future resolution only" (mycelium spike) | Both, by boundary class (unified model §2). Cordis's own §6.1 concedes emissions are not revertible |
| Uncertain outcomes | A failed iteration raises; nothing is "maybe happened" | `:uncertain` is a first-class state that blocks the next command until `reconcile!` | Add it to the lifecycle as a blocker on L-Unload and L-Begin (next section). This is the most valuable addition the repos make to Cordis |
| Revert versus replay | Recover by applying composed inverses | Journals are truth; state is a replayed fold (`workbench.clj`, RFC-009, temporal ledger) | Compatible. Confluence makes the quiescent state a function of the configuration log, so replay of that log is a second recovery route. Use inverses for live unload, replay for crash recovery |
| Continuous versus point checks | Every context change is classified | Check once, at the emission (ADR-002 revalidation, "no approval silently propagated" in STPA) | Keep both. Reactivity governs whether a fiber runs; revalidation guards each emission |
| Pure cells relocate cost | Confinement to declared keys is a consequence of the stage form | E24: making cells pure "relocates obligations rather than removing them"; regions are where they go (E41) | Agreement, stated differently. Cordis's keys are the relocation target; a region is a key whose operations are the members' protocols |
| Single provider per key | One provider; versions and realms are extensions | Userspace keeps every version per project; promotion tiers run-local, project, global | Map versions to realms: a run-local promotion is a realm the run's fibers resolve; promotion moves the realm table, which Cordis treats as a revision |

Two cautions from the repos that Cordis does not raise:

- **Interception is voluntary.** E29 found the effect boundary is "sound against bypass to the host, and not against a layer above it choosing not to use the boundary". Cordis's confinement is a property of the stage form; in a Clojure host with `eval` and the dispatch predicates samizdat compiles with host eval, it is a convention until a checker or sandbox enforces it.
- **Rollback is itself a control action.** The STPA fixture lists rollback stopped before convergence, restore while writes are enabled, and automatic rollback after an incompatible migration as unsafe control actions. Reverting is not automatically safe; the guard and the ordering theorem cover dependents, not external writers.

## What to build, in order

Start with inverses on what samizdat already owns, because that needs no new runtime and makes every later step testable. Each step names its side of the standing rule: mechanism in `src/`, decision in `resources/`.

| # | Build | Where | Depends on | Cordis result it buys |
| --- | --- | --- | --- | --- |
| 1 | `userspace/save!` returns an inverse (re-append the prior version); a run accumulates them; `session/experiment!` holds the inverse so a `:worse` verdict can recover | `src/` mechanism; when to recover in an oversight cell | Nothing | Track and recover for internal effects (§3.1) |
| 2 | Extend `:effects` to name the boundary class: `:internal`, `:acquire`, `:emit`. `validate` rejects an `:emit` cell that does not go through an intent cell | Cell metadata in `resources/cells`; the check in `manifests/compile-definition` | 1 | The three-class discipline, enforced at compile |
| 3 | Port the commit-gated emission path from jolt-sim (`flow_effect_session`, command cell session) and JS1 receipts into samizdat as the one way an `:emit` leaves | `src/` | 2 | Withhold until commit; uncertain plus reconcile |
| 4 | Land the decide stack (`canonical`) after closing issue #14's schema; treat `:based-on` as the committed view | Mechanism `src/samizdat/decide.clj`; policy `gates.edn` | 3 | First reactive coeffect in production |
| 5 | Add `:provides` to cells and manifests; compile computes provider and precedence, rejects cycles and double provision | Manifest schema; check in `src/` | 1 | Provision p; single provider; cycles caught at load (§6.5) |
| 6 | The fiber runtime: Inactive, Reloading, Active, Unloading, plus Uncertain and Failed; target versus committed view; the L-Unload guard. Build it once as jolt-sim's long-lived process and link layer, the gap `MYCELIUM-FLOW-V1-GAPS.md` and §3.5 name, and test it under deterministic simulation | jolt-sim `src/jolt/sim/`, then samizdat `src/` over ebb tasks | 5 | The calculus, with progress and confluence checked by exploration |
| 7 | Rewrite `propose-cell!` as a fiber revision: retire, unload with inverses, reinsert, soak in a derived context | `src/samizdat/mutation.clj` | 6 | Hot replacement that reverts the old cell's effects |
| 8 | Attach evidence values to inverse and commutativity witnesses; orchestration policy reads the weakest | Evidence in perturb's `evidence.clj` shape; thresholds in `gates.edn` | 6 | Witnesses a running system can actually supply |
| 9 | Session-typed operations per key, starting with the REPL session and the SQLite transaction typestate | Declarations in `resources/`; monitor in `src/` | 6 | Order-correct use of a coeffect |

Steps 1 to 4 are local to samizdat and reuse code that already passed its gates on other branches. Step 6 is the one real piece of new design, and jolt-sim is the right place for it: its simulator can explore every interleaving of the nine rules over small configurations, which is how Theorems 73 and 80 become `:exhausted` evidence instead of a paper claim.

## Open questions

- **Who supplies inverses for agent-written cells?** If the supervisor writes a cell, it must also write the cell's inverses, and those are the least trustworthy part. Should a cell with no proved inverse be restricted to `:emit` through intents only?
- **What is a key in samizdat?** `ctx-keys` is fixed and compiled in. Reactive coeffects need keys that appear and disappear; which existing things (the REPL session, the git baseline, the provider adapter, live branches) become providable keys?
- **Does the beam fit single-provider?** Branches are many instances of one component providing the same keys. Cordis's answer is realms or a broker (§6.2); which one matches beam semantics?
- **How does uncertainty interact with confluence?** Cordis excludes failed fibers from confluence; an uncertain fiber may be stuck indefinitely. Does reconciliation need a deadline policy in `gates.edn`?
- **Which host?** Cordis needs closures, module eviction and interposed access (§6.4). Jolt and SCI both qualify; bbagent deliberately rejects runtime loading. Is the runtime-modifiable harness samizdat-on-Jolt only?
- **Docs direction in jolt-sim.** The Mycelium project vision lives only on #123. Picking #123 over `agent/docs-human-story-v1` decides where this synthesis's jolt-sim half is recorded.

## Sources

- Shi, Zhang, Cui, *A Programming Paradigm for Spatiotemporal Composability* (Cordis; arXiv 2608.25512v1, uploaded PDF). Definitions 8, 17, 21–22, 28–31, 46, 48–56; Theorems 70, 73, 80; §4.4, §5.2, §6.1–6.7.
- samizdat `main`: `AGENTS.md`; `docs/RFCS/RFC-001` to `RFC-014`; `src/samizdat/mutation.clj`; `src/samizdat/manifests.clj`; `src/samizdat/userspace.clj`; `src/samizdat/session.clj`; `src/samizdat/cancel.clj`; `resources/gates.edn`; `resources/manifests/loop.edn`.
- samizdat branches: `canonical` (`src/samizdat/decide.clj`, `resources/manifests/decide.edn`); `agent/onbox/outcome-pilot-v1` (`src/samizdat/store/lifecycle.clj`); `agent/onbox/context-selection-seam`; `js1-bounded-samizdat` (`docs/JS1_CONVERGENCE_PLAN.md`); `js2-converge-closure-34a21ab`; `codex/embed-aspects`. Issues #5, #11, #14, #19, #44, #46.
- samizdat-agent-bootstrap `agent/onbox/jev-judgment-eval-v1`: `MISSION.md`; `docs/adr/ADR-001-responsibility-split.md`; `docs/adr/ADR-002-decision-contracts.md`; `docs/CONSOLIDATION-PLAN.md`.
- jolt-sim `main`: `src/jolt/sim/flow.clj`; `flow_effect_session.clj`; `command_cell_session.clj`; `retained_process.clj`; `runtime.clj`; `docs/research/MYCELIUM-FLOW-V1-GAPS.md`; `docs/research/EXPERIMENT-WORKBENCH-DESIGN.md`; `docs/ROADMAP.md`. PR #123 (`docs/research/MYCELIUM-PROJECT-VISION.md`).
- jolt-sim `claude/ocaml-effect-based-language-gsg316`: `docs/research/PROGRESSIVE-FORMALISM-DESIGN.md` (§2, §3.2, §3.4–3.5, §5.5–5.7, §7.2); `PERTURB-DESIGN.md` (E21, E24, E29, E33, E41, E42); `PROGRESSIVE-ASSURANCE-ARCHITECTURE.md`; `STAGED-PROTOCOL-NOTE.md`; `perturb/src/perturb/{effect,region,evidence,dbtx}.clj`.
- bbagent `main`: `docs/A2_FINDINGS.md` §10; `docs/A3B_FINDINGS.md`; `docs/architecture/0003-execution-environment-edn-spi.md`; `src/bbagent/bb4t.clj`.
- agent-lisp-mycelium `MYCELIUM_RECURSIVE_HARNESS_SPIKE.md`; agent-lisp-starter `feat/first-scripted-slice`; stpa-workbench `claude/stpa-workbench-rfc-1zzv3b` (`docs/rfc/0001-lifecycle-stpa-workbench.md`, UCCA fixtures).
