# RFC 0008: Coding-agent runs as operations (Cordis's half)

Status: **proposed** (revision 5). Nothing here is built. Yard's half is **casselc/yard
`design/AGENT-OPERATIONS.md`, revision 2 on yard main** (dddf7e5; its models are `spec/agentop/`). The two were
revised together after review, and revision 4 took yard's wording where the two had drifted (outcome and field
names, refusal reasons, the pinned valuation); revision 5 corrects three points a further review found (the
settlement of `never_accepted` and of refused copies, the claim that local spend is always exact, and the deadline
clock) and settles the pricing and wire names. Where the documents differ, "Relation to yard's draft" says which
rules.

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
type AgentBudget = { tokens: int, value: int, seconds: int }     // three independent limits; value in valuation micro-units
type AgentTask<T> = {
  agent: Agent, model: ModelRoute,                                // a permitted route, never a key
  workspace: Workspace, mode: ReadOnly,
  instructions: [str], output: Type<T>,
  budget: AgentBudget,                                            // at or below the operator's ceiling
}
type Dim = { confirmed: int, unresolved_max: int }
type Usage = { tokens: Dim, value: Dim, seconds: Dim }            // the only usage representation (see "Wire fields")
type AgentOutcome<T> = {
  result:    Completed{ value: T }
           | Incomplete{ reason: InsufficientInformation | InvalidOutput | BudgetExhausted | TimedOut
                               | Cancelled | WorkerLost | ExecutorError },
  usage:      Usage,
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
stopped or fenced, the broker has confirmed the scope revoked, and every unresolved call is either resolved or
charged at its reserved maximum (yard's section 6). **Nothing waits for exactness**: a call that cannot be resolved
stays charged, and the attempt finishes `bounded`.

### Wire fields and the charge

There is one representation of usage and one number settlement uses.

```
usage.tokens   = { confirmed, unresolved_max }    all token classes, counted by the broker: input, cached read,
                                                  cache write, output; each token is one token here
usage.value    = { confirmed, unresolved_max }    integer valuation micro-units: each class priced separately
                                                  under the pinned valuation (a cache write costs more than plain
                                                  input), rounded up; engine time is included only if the
                                                  valuation prices it, and the valuation says so
usage.seconds  = { confirmed, unresolved_max }    wall time

charge = usage.value.confirmed + usage.value.unresolved_max           // the adapter's only settlement figure
```

`spend.charge` of revision 3 does not exist; the adapter reads `usage.value`. `tokens` and `seconds` are reported for
the cell and the audit and do not move Cordis's budget. The three limits are enforced by yard, each on its own.

### Receipts and settlement

| What yard reports | Receipt to the cell | Settlement |
| --- | --- | --- |
| `completed` | a **Done** receipt, `Completed` | `charge` |
| `insufficient_information`, `invalid_output`, `budget_exhausted`, `timed_out`, `cancelled`, `worker_lost`, `executor_error` | a **Done** receipt, `Incomplete{reason}`, with the usage and the partial artifacts | `charge` |
| `accounting: bounded` (a provider call unanswered, **or a local call whose operation record Halo no longer holds**) | as above; the field is visible to the cell | the same `charge`: unanswered work at its reserved maximum, so the true cost is at most the charge |
| `execution: fenced` | as above, the field visible | the same: once the broker scope is revoked the worker can spend nothing more |
| `cancelled_before_accept`, `closed` | a **Refused** receipt (not executed) | 0, **released here** |
| a refused copy: `capacity`, or `not_permitted`, `invalid_task`, `unknown_valuation`, `limits_missing`, `input_unavailable` | `capacity`: **deferred**, the same submission sent again. The others: **a cancel first**, fencing the attempt, and the Refused receipt only after `cancelled_before_accept` | the reservation **holds** until that terminal answer |
| `never_accepted` | **deferred**: it means *not accepted yet*, and a delayed copy may still arrive | the reservation and the exact submission identity **hold** until `cancelled_before_accept`, `closed`, or another authoritative terminal outcome |
| `unknown` (a lost store at yard), or `running` marked `degraded` (a revocation yard cannot confirm) at the deadline | a lost receipt | the whole reservation (R) |

- **A refusal is reserved for a submission that was not executed, and only an authoritative terminal answer
  releases a reservation.** RFC 0007's rule is kept: a refusal closes the **copy**, not the attempt. `never_accepted`
  is not terminal, and neither is a refusal: until the attempt is fenced by an answered cancel
  (`cancelled_before_accept`) or closed by its deadline (`closed`), a delayed copy of the same submission may be
  accepted and run. Revision 4's table released `never_accepted` and every refusal at once; that contradicted
  RFC 0007 and is withdrawn. (The Halo adapter built today settles a 400 or 422 at once; it is brought in line in
  the envelope split, below.)
- Timeout, cancellation, budget exhaustion and invalid output incur cost, so they are results with usage, never
  refusals.
- The engine already charges "the world reports what the attempt spent, with no usable result" (`Lost_at`, at most
  the worst case; `observe.qnt`'s `EXPIRE_REPORTED`). A `Done` receipt with `Incomplete` is charged the same
  `charge`, which yard guarantees is at most the reservation.
- Uncertainty has several sources and the adapter assumes none away: unanswered calls make accounting **bounded**,
  an unreachable worker makes execution **fenced**, a restart of Halo can make a local call unresolvable (below),
  and only a lost store (or a scope yard cannot confirm revoked by the deadline) is `unknown`.

**The reservation R** is the task's `value` limit. A task with any of its three limits missing is refused
(`limits_missing`).

### Later certainty updates

Yard may later upgrade a `fenced` execution to `stopped`. The captured receipt and its journal fact are
**immutable**, and so is the acknowledged result. The upgrade arrives as an **execution-evidence correction**, an
append-only record yard keeps with the attempt's evidence bundle under its lease. The adapter polls it for attempts
whose execution was `fenced` until the correction arrives or the evidence lease ends; on receipt it appends a
journal fact (`why: execution-evidence`, superseding nothing in the receipt) and delivers it to the workflow as an
input event that cells may read. A correction never changes the charge: revocation had already bounded the spend.
(Yard's draft allows the upgrade; the endpoint that exposes the corrections is a request to yard, below.)

### Deadlines and the clock

The envelope's deadline rules are RFC 0007's: a deadline in the executor's clock, carried on every verb, with
tombstones that outlive it. Halo's clock restarts with its incarnation. **Yard's incarnation survives a restart of its
controller when its store survives**, so the clock cannot be inherited unchanged: a process clock that resets
beneath a constant incarnation would let a pruned tombstone accept again or make a deadline appear to move back.
Specifying a restart-safe deadline interpretation (a persistent logical clock, or a monotonic reading that is
stored and never goes back) is yard's, and the adapter relies only on the property RFC 0007 needs from any clock:
**no time reading is earlier than one already given, and no tombstone is pruned or accepted against before its
deadline in that same clock.** The adapter takes the clock from the contract, never assumes it resets with the
incarnation, and the stand-in and the milestone test **a controller restart near deadline expiry**, with a
delayed copy sent after it.

## Valuation is the operator's

Agents may choose permitted routes, smaller budgets and retry strategies. They **cannot change the prices used to
enforce their budgets.**

- Cordis's budget for agent runs is an integer in **valuation micro-units**. Yard converts tokens and route prices
  into valuation micro-units with the operator's price table, rounding up, once.
- The price table's id (the **valuation**) is in yard's contract. The outbox reads it and sends it with the submit;
  yard refuses an unknown one (`unknown_valuation`, not executed) and **pins** the named one to the attempt.
  Reservation, enforcement and settlement use the pinned table however long the task runs; a price change applies
  to later attempts only. The cell never names a valuation.
- Accordingly **no price lives in `gates.edn` or any file the agent can edit.** `gates.edn` holds a cell's budget (at
  or below the operator's ceiling) and retry counts.

**One definition of local pricing: a resource valuation.** The unit is a *valuation*, not a bill. For a provider
route a valuation micro-unit is one millionth of a dollar and the charge is real spending. For a local route it is
a nominal charge the operator sets for the resource used (per token class, or per engine second where Halo reports
it), so that a local review has a budget that binds and counts against the workflow's budget like any other work.
It is not financial spending, and the books keep the difference: the ledger records the route beside each charge, and
a report separates *priced* routes (real money) from *valued* routes (nominal). The alternative, leaving local
inference free and bounded only by yard's token and time limits with a separate aggregate resource budget in Cordis,
is rejected for the first milestone: it adds a second budget to the engine for no gain.

**Requirement:** every route an agent task may use has a nonzero price in the valuation. A route priced zero
reserves nothing: a local review would never touch Cordis's budget and only yard's token limit would bound it. Yard
refuses a task on a zero-priced route, and the outbox checks the price table in the contract and refuses such a
task **before it journals the submit**, as a second guard. Yard's draft says a local route is priced zero unless
the operator says otherwise; for agent runs the operator must say otherwise. The wire name of the dimension is
`micro_usd` in yard's draft; this RFC calls it `value`, and proposes yard rename it (`micro_value`) so that a nominal
charge is not labelled as dollars.

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
A cell's acceptance guards that examine **only the findings** need no lease, because the value is in the receipt. A
guard that checks a **transcript or supporting evidence** requires those artifacts to remain available: the cell
holds their leases until the guard has run, and an unavailable artifact makes the guard fail explicitly (acceptance
unavailable), never pass.

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
| `broker.qnt` | one attempt's accounting: concurrent calls, token and value limits independently, local and provider routes, lost answers, two broker crashes, local calls resolved from Halo's operation record | `checkConfirmedOnly`, `volatileReserve`, `dropUnanswered`, `valuationFromGraph`, `moneyOnly`, `claimExact` |
| `executor.qnt` | yard-ctl's recovery: delayed starts, partitions with VMs still running, delayed revocation, workers that write after they finish, every VM on CID 3 | `restartStartsAgain`, `bindByCid`, `unreachableIsStopped`, `endBeforeRevoke`, `captureFromUnstopped` |
| `apply.qnt` | candidates, leases, approvals, apply (later milestones) | `ackReleasesArtifacts`, `approvalByName`, `applyStaleBase`, and others |

They assume that a call's real cost never exceeds its reserved maximum (the input bound's correctness), that
**Halo keeps the records the broker resolves from**, and that yard's store and clock survive a restart unchanged.
The last two assumptions are not true of the system and the two models the review called most valuable come
from them. Mutants proposed in revision 3 map onto yard's (`startWithoutIncarnation` is `restartStartsAgain`,
`activateWithoutAttestation` is `bindByCid`, `substituteEmpty` is added).

**Most valuable next, before more features:**

| Where | Extension |
| --- | --- |
| Yard `broker.qnt` | **Halo restarts during reconciliation**: Halo is ephemeral, and a restart before the broker durably commits makes the local call's record unknown. The broker then retains the call's reserved maximum; the attempt is `bounded`, and no task waits for exactness. Mutants: `waitForExact`, `releaseOnUnknown` |
| Yard `executor.qnt` | **Deadline continuity across a controller restart**: a process clock that resets under a constant incarnation. Properties: no tombstone pruned early, no renewed acceptance after a restart; mutant `clockResets` |

**Cordis's side still has to model:**

| Where | Extension |
| --- | --- |
| `operation.qnt` (existing) | already forbids settling `never_accepted` except through a terminal answer (`abandonUntombed`); add the **refused-copy** case (a refusal closes the copy) and the cancel-then-refuse order, with a mutant `refuseEarly` |
| `observe.qnt` family | a world that reports a **bounded** charge with unanswered work inside it (`spendBounded`): the engine never charges below the reported charge, never above R, and charges R for `unknown`. `EXPIRE_REPORTED` models one known report only |
| `authority.qnt` (small) | a request against an operator ceiling, checked where it is accepted: mutant `ceilingInGraph`. Prices are yard's (`valuationFromGraph`) |
| the same | a route priced zero reserves nothing: mutant `freeRoute` (the value limit stops binding) |

## Order of work

Yard's milestones are AG0 to AG9 (its section 16). The Cordis side is AG3, and most of it can be built before yard
serves the envelope, against a stand-in.

1. **This RFC accepted with yard's draft** (AG0), and the open questions below answered.
2. **Cordis's models** (above), with gate rows, before the code they describe.
3. **The envelope split** (`Op_client`, `Halo_adapter`): a refactor tested by the existing suite, **plus the one
   behaviour change this revision requires**: a refused copy no longer releases the reservation at once. A capacity
   refusal is deferred and resent exactly; any other refusal is followed by a cancel, and the Refused receipt is
   delivered after `cancelled_before_accept` (and `never_accepted` holds). New `ops.sh` phases cover them.
4. **A stand-in for yard's agent API**, beside `stub_ops.py`: every outcome, `stopped` and `fenced` (with a later
   evidence correction), `exact` and `bounded` (including a local call whose record is gone), partial and
   unavailable artifacts, refusal reasons, an unknown valuation, a zero-priced route, a `degraded` operation, a
   `never_accepted` copy delivered late, and a **controller restart near deadline expiry** with a delayed copy. `Yard_adapter` against it, with an `ops.sh`-style phase for each row of the settlement
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

1. The **value** of the nominal local price (per token class, or per engine second), and its calibration against
   the provider routes: the operator's choice, needed before the first local review has a budget that binds.
2. The **lease length** the receipt's artifact references carry, and how long the execution-evidence correction is
   kept.
3. Whether the findings value has a size ceiling the receipt enforces (larger goes to an artifact).
4. Measurements Cordis contributes to the evidence manifest: recovery time, refusal rate, cost per review, the
   fraction of local reviews that finish `bounded`.
5. **Requests to yard:** the restart-safe deadline clock (above); an endpoint for execution-evidence corrections;
   renaming `micro_usd`; the Halo-restart world in `broker.qnt`.
6. Reconciling a `bounded` provider charge against the provider's later usage report (yard's open question 2). Not
   needed for the first milestone.

Halo keeps an operation's usage until it is acknowledged, **but only while it keeps its incarnation's state**: Halo
is ephemeral, and a restart before the broker has durably captured the usage makes that call's outcome unknown. A
local review therefore usually ends `exact` and sometimes `bounded` (revision 4 said it always ends exact; that is
withdrawn). Exactness is never a precondition for finishing.

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
- Revision 5: a further review. Settlement no longer releases `never_accepted` or a refused copy early (a refusal
  closes the copy; a cancel fences the attempt first); local accounting may be `bounded` because Halo is ephemeral,
  and nothing waits for exactness; the deadline clock across a controller restart is named as a requirement on yard
  with a test; local pricing is one definition, a resource valuation, with `value` replacing the dollar-named
  dimension; one usage representation and one charge (`usage.value`), with token classes and engine time stated;
  later certainty updates are an append-only execution-evidence correction; the artifact-lease wording is
  qualified; the models named most valuable next are Halo restart during reconciliation and deadline continuity.
