# RFC 0008: Coding-agent runs as operations (Cordis's half)

Status: **proposed** (revision 3). Nothing here is built. Yard's half is **casselc/yard
`design/AGENT-OPERATIONS.md`, revision 2** (branch `design/agent-operations-r2`; its model is `spec/agentop/`). The
two were revised together after review; where they differ, "Relation to yard's draft" says which rules.

## Purpose

Cordis workflows should coordinate, prompt and delegate to coding agents the way they use a model today: a cell
emits an intent, an executor does the work, and a typed outcome comes back with its cost.

The architecture is settled and unchanged: **Yard executes agents in Firecracker VMs; the per-agent outbox manages
durable operations; Cordis coordinates workflows; Halo supplies local inference.** The scope of this RFC is not a
general agent platform. It is **one reliable read-only review operation**: clear authority, recovery and accounting,
delivered and connected to Cordis before anything else (writers, approved apply, cloud routes, delegation).

RFC 0007 limited its contract to inference "until a second executor calls for extraction". This is that second
executor, and the answer is: **share the operation envelope, not the executor's semantics**, and do not extract
the outbox.

## Scope

In (Cordis's side):

- the effect `agent.run` for a read-only, noninteractive task, its types, and how a cell reads its outcome;
- settlement: what a run costs the workflow's budget, and what each outcome does to it;
- the outbox: the operation envelope, and the yard adapter beside the halo adapter;
- where authority and valuation live;
- acceptance: what Cordis checks that yard does not;
- the order of work.

Out, and yard's: the VM, the broker and its accounting, the executor's stages and fencing, artifact leases, the
trusted tree comparison, harness drivers and routes. Out and later (section "Deferred"): `ReadWrite` tasks,
approval and apply, interactive continuation, native delegation, cloud routes, other harnesses, agents that drive
Cordis.

## Relation to yard's draft

| Subject | Rules |
| --- | --- |
| The wire (verbs, identity, incarnation, deadline, ack, retention) | RFC 0007; yard serves it for `api: "agent"` |
| Terminal outcome, execution and spend certainty; the executor, broker, valuation, artifacts, workspaces | yard's draft |
| The Cordis types, settlement, acceptance, authority split, the adapter's structure | this RFC |

## The effect

Noninteractive; a task states its inputs and gets one outcome. Yard's draft has the same text:

```
type Workspace   = { repo: str, version: str }                    // immutable
type AgentBudget = { tokens: int, micro_usd: int, seconds: int }  // three independent limits
type AgentTask<T> = {
  agent: Agent, model: ModelRoute,                                // a permitted route, never a key
  workspace: Workspace, mode: ReadOnly,
  instructions: [str], output: Type<T>,
  budget: AgentBudget,                                            // at or below the operator's ceiling
}
type AgentOutcome<T> = {
  result:    Completed{ value: T }
           | Incomplete{ reason: Timeout | BudgetExhausted | Cancelled | InvalidOutput | WorkerFailed | Insufficient },
  usage:     Usage,
  spend:     { certainty: Exact | Bounded, charge: int },          // micro-dollars
  execution: Stopped | Unconfirmed,
  artifacts: [Artifact],                                           // transcript, findings, invalid output, partial work
}
effect agent.run<T>(AgentTask<T>) : AgentOutcome<T>
```

The valuation the outbox priced its reservation with is part of the submit, not of the graph: the cell never names
it. `Insufficient` is how a noninteractive agent says it cannot proceed without more information.

## Outcomes, refusals and settlement

**Status is separate from accounting certainty.** A terminal answer carries three facts (yard's draft, section 4):
the task's **outcome**, whether its **execution** was proved stopped, and whether its **spend** is exact or
bounded. A failed VM does not prove every model call's cost is known, and an unreachable worker is not necessarily
stopped. Cordis therefore does not collapse them into one "lost" or "done":

| What yard reports | Receipt to the cell | Settlement |
| --- | --- | --- |
| `completed`, spend exact | a **Done** receipt, `Completed` | `spend.charge` |
| `completed`, spend bounded | Done, `Completed` | `spend.charge` (confirmed plus the unanswered calls at their reserved maxima) |
| `invalid_output`, `insufficient`, `budget_exhausted`, `timeout`, `cancelled`, `worker_failed` | a **Done** receipt, `Incomplete{reason}`, with the usage and the partial artifacts | `spend.charge` |
| `execution: unconfirmed` | as above, with the field visible to the cell | the same: once yard has revoked the broker scope the worker can spend nothing more |
| `refused`, `closed`, `never_accepted`, `cancelled_before_accept` (not executed) | a **Refused** receipt | 0 |
| `unknown` (a lost store at yard), or still `running` at the deadline | a lost receipt | the whole reservation (R) |

- **A refusal is reserved for a submission that was not executed.** Timeout, cancellation, budget exhaustion and
  invalid output incur cost, so they are results with usage, never refusals. (Revision 2 of this RFC made a cut-off
  run a refused receipt; that is withdrawn.)
- The engine already charges "the world reports what the attempt spent, with no usable result" (`Lost_at`, at most
  the worst case; `observe.qnt`'s `EXPIRE_REPORTED`). A `Done` receipt with `Incomplete` is charged the same
  `charge`, which yard guarantees is at most the reservation.
- The earlier claim that uncertainty occurs only after yard's store is lost is wrong, and the adapter does not
  assume it: unanswered calls make spend **bounded**, an unreachable worker makes execution **unconfirmed**, and
  only a lost store (or an unrevokable scope at the deadline) is `unknown`.

**The reservation R** is the task's `micro_usd` limit. A task with any of its three limits missing is refused.

## Valuation is the operator's

Agents may choose permitted routes, smaller budgets and retry strategies. They **cannot change the prices used to
enforce their budgets.**

- Cordis's budget unit for agent runs is the **micro-dollar**, an integer. Yard converts tokens, engine time and
  route prices into micro-dollars with the operator's price table, once, rounding up.
- The price table's digest (the **valuation**) is in yard's contract. The outbox reads it and sends it with the
  submit; yard pins it to the attempt and refuses (`409 valuation_changed`, not executed) a submit made with a
  stale one. Reservation, enforcement and settlement use the pinned table.
- Accordingly **no price lives in `gates.edn` or any file the agent can edit.** The earlier proposal of a unit
  mapping in `gates.edn` is withdrawn. `gates.edn` holds a cell's budget (at or below the operator's ceiling) and
  retry counts, as before.

## Authority

| Lives where | What | Who can change it |
| --- | --- | --- |
| Operator configuration (outside the graph and the mutation protocol) | the **price table**, the permitted agents and routes per cell, the **ceilings** on each of the three limits, `ReadWrite`, automatic approval (later) | the operator |
| `manifests/*.edn`, `cells/*.clj` | which permitted agent a state uses and for what; fan-out, quorum, escalation, repair attempts | the agent, behind admission |
| `gates.edn`, `agents.edn` | per-cell budgets at or below the ceiling; retry strategy; the name of a prompt | the agent, behind admission |
| `prompts/*.md` | every instruction word | the agent |

A request may be smaller than its ceiling and never larger, and the check is made where it is accepted (yard-ctl,
from the operator's configuration), not where it is composed. The mutation protocol cannot reach the first row.
This is `observe.qnt`'s `misclassify` rule applied to authority and prices.

## Completion is not acceptance

- **Yard validates structure** (the output parses and matches the schema) and **preserves** invalid output as an
  artifact. It does no hidden retry.
- **Cordis evaluates acceptance**: the cell's guards decide whether findings cite files and lines that exist in the
  reviewed version, whether a review that read nothing can pass, whether the verdict agrees with the findings. A
  `Completed` value is an input to those guards, never a decision.
- **Repair is explicit.** A cell that wants a repair emits a new `agent.run` (the invalid output and the validation
  error in its instructions), under its own reservation, and the cell bounds the number in its manifest.

## Artifacts

The receipt carries the findings value itself (small, and what the workflow acts on) and **references** to larger
artifacts (transcript, invalid output, partial work), each `{id, digest, lease}`. Acknowledging the operation
releases the operation's record, not an artifact: artifacts have their own leases (yard's draft, section 8). The
journal keeps the references and digests with the answer, so an audit names what it examined. An artifact whose
lease ended answers an explicit `artifact_unavailable`; a consumer that needed it fails, and nothing substitutes.
The review's acceptance does not depend on any lease, because the value is in the receipt.

## The outbox: one envelope, two adapters

The shared part is the **envelope**; the interpretation is each executor's.

```
Op_client   (shared)    identity (owner, operation id salted with the store, attempt), the exact journaled
                        submission, polling, deadline carried on every verb, durable capture with the obligation
                        in the same transaction as the answer, acknowledgement and its recovery, crash points
Executor_adapter  (one per executor: Halo_adapter, Yard_adapter)
                        build_request : task -> body bytes           (what to submit)
                        interpret     : status view -> Running | Terminal of settlement | Retry | Refused
                        decode        : fetched body -> Executors.answer (value, cost, source)
                        artifacts     : which references to journal
                        charge        : how cost is computed from the terminal view
```

`Halo_adapter` is today's `halo_op.ml` after the split (a refactor with no behaviour change, proven by every
`ops.sh` phase and the milestone passing unchanged). `Yard_adapter` reads the three facts above, builds the
`AgentTask` body, decodes the findings and the artifact references, and charges `spend.charge`. Status
interpretation, charging, result decoding and artifact handling are **not** shared.

## Deferred, until written

Each needs its own state, budget and cancellation semantics, in yard's draft (section 12) and here:

- **`ReadWrite` tasks and candidate workspaces.**
- **Approval and apply.** Approval is a **durable record** bound to `(candidate digest, expected base, diff digest,
  expiry)`, produced by a person or an approval harness and arriving at a workflow as an input (as any outside event
  does); it is not an open operation. **Apply** is a **short compare-and-set operation** carrying the approval id,
  which yard validates for authority, expiry, digest and head. A workflow never holds an operation open waiting for
  a person.
- **Interactive continuation (`needs_input`)** and **native delegation (A2A child agents)**: until their state,
  budget and cancellation are specified, a task returns `Insufficient` and **Cordis coordinates top-level fan-out**.
- The Anthropic route, other harnesses, the approval harness.

## Coordination, first

Cells and manifests only. The first patterns, all top-level: a **review loop** with the critic's `llm.complete`
replaced by `agent.run`; **fan-out with quorum** over several reviewers; **cross review**, the author excluded by
the manifest. Reviewers run against the same immutable workspace.

## Models

**What exists is evidence under its assumptions.** `observe.qnt`'s `EXPIRE_REPORTED` models one attempt whose world
reports what it spent: a single known report, no uncertainty marker, no concurrency. Yard's `spec/agentop` assumes
atomic, known-cost calls, a reliable worker stop and instantaneous revocation (its section 15 lists them). Neither
covers the failure modes of this revision.

To model before the code (gate rows, mutants, witnesses):

| Where | Extension |
| --- | --- |
| Yard (`accounting.qnt`, `stages.qnt`) | concurrent reservations, lost usage answers, broker crashes, ambiguous worker start and stop, delayed revocation, stale-incarnation admission, artifact retention (yard's section 15) |
| Cordis `observe.qnt` family | a world that reports a **bounded** charge, with unanswered work inside it (`spendBounded`); the engine never charges below the reported charge and never above R; `unknown` still charges R |
| Cordis `authority.qnt` | a request against an operator ceiling; a price fixed per attempt: mutants `ceilingInGraph` (the ceiling read from the graph), `priceFromGraph`, `priceUnpinned` (the table changes under a running attempt) |

## Order of work

1. **This RFC and yard's draft accepted**, with the open questions below answered.
2. **Models** (above), with their gate rows, before the code they describe.
3. **The envelope split** (`Op_client`, `Halo_adapter`): a refactor, tested by the existing suite.
4. **A stand-in for yard's agent API**, beside `stub_ops.py`: outcomes, execution, bounded spend, partial artifacts,
   unavailable artifacts, a stale valuation. `Yard_adapter` against it, and `ops.sh`-style phases for each row of
   the settlement table.
5. **`authority.qnt`** and the operator-configuration check (refusing before the submit is journaled).
6. **The `agent.run` kind**: codec for `AgentOutcome`, manifest wiring, `manual.edn`, review prompts, and a
   `claude_review` cell and graph with acceptance guards.
7. **Y1a (yard alone) passes** its fault set; then **Y1b**: the milestone script with `YARD_URL`, through the same
   faults, with the evidence manifest pinning both revisions, the model and the build settings.
8. Only then the deferred list, one item at a time.

## Open questions

1. Confirm the micro-dollar as Cordis's unit for agent runs, and how a **local** route is valued (a nominal price
   per token or per engine second, the operator's).
2. The **lease length** the receipt's artifact references carry, and whether Cordis renews them for an audit.
3. Whether halo's operation records let the broker **resolve** an unanswered call exactly (yard's open question 3),
   which turns some `bounded` spend into `exact`.
4. Whether the findings value has a size ceiling the receipt enforces (larger goes to an artifact).
5. Measurements Cordis contributes to the evidence manifest: recovery time, refusal rate, cost per review.

## History

- Revision 1: first draft, written without yard's.
- Revision 2: aligned with yard's `design/AGENT-OPERATIONS.md` (executor in yard, broker metering, workspace
  versions and approved apply, authority in operator configuration).
- Revision 3: after review. Status separated from accounting certainty; refusal reserved for not-executed
  submissions, typed outcomes with usage; valuation operator-owned and pinned, three independent limits, integer
  micro-dollars; artifacts with leases; completion separate from acceptance, explicit repair; approval separated
  from apply (a durable record, then a short operation); interactive continuation and native delegation deferred;
  envelope shared, executor semantics not; models restated as evidence under assumptions with extensions named; one
  read-only review operation first.
