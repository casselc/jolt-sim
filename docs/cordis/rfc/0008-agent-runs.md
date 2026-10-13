# RFC 0008: Coding-agent runs as operations (Cordis's half)

Status: **proposed** (revision 4). Nothing here is built. Yard's half is **casselc/yard
`design/AGENT-OPERATIONS.md`, revision 2 on yard main** (dddf7e5; its models are `spec/agentop/`). The two were
revised together after review, and revision 4 takes yard's wording where the two had drifted (outcome and field
names, refusal reasons, the pinned valuation); where they differ, "Relation to yard's draft" says which rules.

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
type Workspace   = { repo: str, commit: str }                     // a git commit; yard builds a read-only image of it
type AgentBudget = { tokens: int, micro_usd: int, seconds: int }  // three independent limits
type AgentTask<T> = {
  agent: Agent, model: ModelRoute,                                // a permitted route, never a key
  workspace: Workspace, mode: ReadOnly,
  instructions: [str], output: Type<T>,
  budget: AgentBudget,                                            // at or below the operator's ceiling
}
type Usage = { tokens: Dim, micro_usd: Dim, seconds: Dim }       // Dim = { confirmed: int, unresolved_max: int }
type AgentOutcome<T> = {
  result:    Completed{ value: T }
           | Incomplete{ reason: InsufficientInformation | InvalidOutput | BudgetExhausted | TimedOut
                               | Cancelled | WorkerLost | ExecutorError },
  usage:      Usage,                                              // the charge is confirmed + unresolved_max, per dimension
  accounting: Exact | Bounded,
  execution:  Stopped | Fenced,
  valuation:  Digest,                                             // the pinned price table
  artifacts:  [Artifact],                                         // transcript, raw output, evidence bundle
}
effect agent.run<T>(AgentTask<T>) : AgentOutcome<T>
```

The input is a **git commit**, not a bare snapshot: yard builds the immutable image from it, and a later apply needs a
git base to compare-and-set against.

The valuation id is part of the submit, **not of the graph**: the outbox reads it from yard's contract and sends it,
and the cell never names it. `InsufficientInformation` is how a noninteractive agent says it cannot proceed with what
it has; its questions are data and nothing resumes.

## Outcomes, refusals and settlement

**Status is separate from accounting certainty.** A terminal answer carries yard's three facts (its section 4): the
**outcome**, whether the **execution** was proved stopped (`stopped`) or only **fenced** (broker access revoked, the
worker not confirmed stopped, its result not captured), and whether the **accounting** is `exact` or `bounded`. A
failed VM does not prove every model call's cost is known, and an unreachable worker is not necessarily stopped.
Cordis therefore does not collapse them into one "lost" or "done". Yard reports `done` only after the worker is
stopped or fenced, the broker has confirmed the scope revoked, and the local route's unresolved calls are resolved.

| What yard reports | Receipt to the cell | Settlement |
| --- | --- | --- |
| `completed` | a **Done** receipt, `Completed` | the money charge (`confirmed + unresolved_max`) |
| `insufficient_information`, `invalid_output`, `budget_exhausted`, `timed_out`, `cancelled`, `worker_lost`, `executor_error` | a **Done** receipt, `Incomplete{reason}`, with the usage and the partial artifacts | the money charge |
| `accounting: bounded` | as above; the field is visible to the cell | the same charge: unanswered provider calls at their reserved maxima, so the true cost is at most the charge |
| `execution: fenced` | as above, the field visible | the same: once the broker scope is revoked the worker can spend nothing more |
| `capacity`, `not_permitted`, `invalid_task`, `unknown_valuation`, `limits_missing`, `input_unavailable`; `closed`, `never_accepted`, `cancelled_before_accept` (not executed) | a **Refused** receipt | 0 |
| `unknown` (a lost store at yard), or `running` marked `degraded` (a revocation yard cannot confirm) at the deadline | a lost receipt | the whole reservation (R) |

- **A refusal is reserved for a submission that was not executed.** Timeout, cancellation, budget exhaustion and
  invalid output incur cost, so they are results with usage, never refusals. (Revision 2 of this RFC made a cut-off
  run a refused receipt; that is withdrawn.)
- The engine already charges "the world reports what the attempt spent, with no usable result" (`Lost_at`, at most
  the worst case; `observe.qnt`'s `EXPIRE_REPORTED`). A `Done` receipt with `Incomplete` is charged the same money
  charge, which yard guarantees is at most the reservation.
- The earlier claim that uncertainty occurs only after yard's store is lost is wrong, and the adapter does not
  assume it: unanswered calls make spend **bounded**, an unreachable worker makes execution **fenced** rather than stopped, and
  only a lost store (or a scope yard cannot confirm revoked by the deadline) is `unknown`.

**The reservation R** is the task's `micro_usd` limit, in millionths of a dollar. A task with any of its three limits
missing is refused (`limits_missing`).

## Valuation is the operator's

Agents may choose permitted routes, smaller budgets and retry strategies. They **cannot change the prices used to
enforce their budgets.**

- Cordis's budget unit for agent runs is the **micro-dollar**, an integer. Yard converts tokens and route prices into
  micro-dollars with the operator's price table, rounding up.
- The price table's id (the **valuation**) is in yard's contract. The outbox reads it and sends it with the submit;
  yard refuses an unknown one (`unknown_valuation`, not executed) and **pins** the named one to the attempt.
  Reservation, enforcement and settlement use the pinned table however long the task runs; a price change applies
  to later attempts only. The cell never names a valuation.
- Accordingly **no price lives in `gates.edn` or any file the agent can edit.** `gates.edn` holds a cell's budget (at
  or below the operator's ceiling) and retry counts.
- **Requirement: every route an agent task may use has a nonzero price.** The reservation is the money limit, and
  the money limit counts only priced tokens. A local model priced at zero reserves nothing: a local review would never
  touch Cordis's budget, and only yard's token limit would bound it. The operator therefore sets a **nominal price for
  local inference** (per token class, or per engine second where halo reports it), and yard refuses a task on a route
  priced zero. The outbox checks the price table in the contract and refuses such a task **before it journals the
  submit**, as a second guard.

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
  budget and cancellation are specified, a task returns `InsufficientInformation` and **Cordis coordinates top-level fan-out**.
- The Anthropic route, other harnesses, the approval harness.

## Coordination, first

Cells and manifests only. The first patterns, all top-level: a **review loop** with the critic's `llm.complete`
replaced by `agent.run`; **fan-out with quorum** over several reviewers; **cross review**, the author excluded by
the manifest. Reviewers run against the same immutable workspace.

## Models

**What exists is evidence under its assumptions**, not proof. Yard's three models are on yard main
(`spec/agentop/`, `quint run`, never model-checked; the assumptions are in its section 15):

| Yard model | Covers | Mutants relevant here |
| --- | --- | --- |
| `broker.qnt` | one attempt's accounting: concurrent calls, token and money limits independently, local and provider routes, lost answers, two broker crashes, local calls resolved from Halo's operation record | `checkConfirmedOnly`, `volatileReserve`, `dropUnanswered`, `valuationFromGraph`, `moneyOnly`, `claimExact` |
| `executor.qnt` | yard-ctl's recovery: delayed starts, partitions with VMs still running, delayed revocation, workers that write after they finish, every VM on CID 3 | `restartStartsAgain`, `bindByCid`, `unreachableIsStopped`, `endBeforeRevoke`, `captureFromUnstopped` |
| `apply.qnt` | candidates, leases, approvals, apply (later milestones) | `ackReleasesArtifacts`, `approvalByName`, `applyStaleBase`, and others |

They assume that a call's real cost never exceeds its reserved maximum (the input bound's correctness), and that
yard's store survives. They supersede the models revision 3 listed as "to write" on yard's side; the mutants that
revision proposed map onto theirs (`startWithoutIncarnation` is `restartStartsAgain`, `activateWithoutAttestation` is
`bindByCid`, and `substituteEmpty` is now added).

**Cordis's side still has to model:**

| Where | Extension |
| --- | --- |
| `observe.qnt` family | a world that reports a **bounded** charge with unanswered work inside it (`spendBounded`): the engine never charges below the reported charge, never above R, and charges R for `unknown`. `EXPIRE_REPORTED` models one known report only |
| `authority.qnt` (small) | a request against an operator ceiling, checked where it is accepted: mutant `ceilingInGraph`. Prices are yard's (`valuationFromGraph`) and are not repeated here |
| the same | a route priced zero reserves nothing: mutant `freeRoute` (the money limit stops binding), witness for the requirement above |

## Order of work

Yard's milestones are AG0 to AG9 (its section 16). The Cordis side is AG3, and most of it can be built before yard
serves the envelope, against a stand-in.

1. **This RFC accepted with yard's draft** (AG0), and the open questions below answered.
2. **Cordis's models** (above), with gate rows, before the code they describe.
3. **The envelope split** (`Op_client`, `Halo_adapter`): a refactor with no behaviour change, tested by the existing
   suite.
4. **A stand-in for yard's agent API**, beside `stub_ops.py`: every outcome, `stopped` and `fenced`, `exact` and
   `bounded`, partial and unavailable artifacts, refusal reasons, an unknown valuation, a zero-priced route, a
   `degraded` operation. `Yard_adapter` against it, with an `ops.sh`-style phase for each row of the settlement
   table.
5. **`authority.qnt`** and the operator-configuration check (refuse before the submit is journaled).
6. **The `agent.run` kind**: codec for `AgentOutcome`, manifest wiring, `manual.edn`, review prompts, and a
   `claude_review` cell and graph with acceptance guards.
7. **AG3**: yard's AG1 and AG2 pass first (the review alone, then its fault probe); then the milestone script with
   `YARD_URL`, through the same faults, with the evidence manifest pinning both revisions, the model and the build
   settings.
8. Only then the deferred list, one item at a time, in yard's order (writers, approval and apply, the Anthropic
   route, other harnesses, the approval harness).

## Open questions

1. How a local route is priced, now a requirement (above): per token class or per engine second, and the value. The
   operator's choice; needed before the first local review has a budget that binds.
2. The **lease length** the receipt's artifact references carry, and whether Cordis renews them for an audit.
3. Whether the findings value has a size ceiling the receipt enforces (larger goes to an artifact).
4. Measurements Cordis contributes to the evidence manifest: recovery time, refusal rate, cost per review.
5. Reconciling a `bounded` provider charge against the provider's later usage report (yard's open question 2). Not
   needed for the first milestone, where the route is local and calls end `exact`.

Answered: halo keeps an operation's usage until it is acknowledged, even after it expires, so the broker can always
settle a lost local call exactly. Local review spend is therefore `exact`; `bounded` arises for provider routes.

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
- Revision 4: aligned with yard main's revision 2 after the yard agent's review. Outcome, execution and accounting
  names are yard's (`stopped | fenced`, `exact | bounded`, per-dimension `confirmed` and `unresolved_max`); refusal
  reasons are yard's; the valuation id is sent by the outbox and pinned (`unknown_valuation`, not a stale-digest
  conflict); the input is a git commit; a nonzero price for local routes is a requirement; yard's three models
  replace the list of models "to write"; the open question on resolving lost local calls is answered.
