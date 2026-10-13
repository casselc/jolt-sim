# RFC 0008: Coding-agent runs as operations (Cordis's half)

Status: **proposed** (revision 2). Nothing here is built. Revision 2 aligns
this RFC with yard's draft, which already existed when revision 1 was written
and which revision 1 had not seen: **casselc/yard
`design/AGENT-OPERATIONS.md`** (main, 8431f9e; its model is `spec/agentop/`).
That document is yard's half and calls this RFC by number. Where the two
differ, "Relation to yard's draft" says which one rules.

## Purpose

Cordis workflows should coordinate, prompt and delegate to coding agents
(Claude Code, Codex, opencode and the like) the way they use a model today: a
cell emits an intent, an executor does the work, and a typed receipt comes
back with its cost. The motivating cell is a **review**: a read-only agent
examines a change and returns findings the workflow acts on; the second is a
**writer**, whose result is a branch a person approves before it is applied.

An agent run is not an inference call: it runs for minutes, uses tools, and
its cost is metered by someone other than the agent. RFC 0007 limited its
contract to inference "until a second executor calls for extraction". This is
that second executor, and the answer is the same as for the first: **the
operation contract is reused unchanged with a new `api`, and the outbox is not
extracted.** The executor is yard's, not the outbox's.

## Scope

In (Cordis's side):

- the effects `agent.run` and `workspace.apply`, their types, and how a cell
  reads their results;
- settlement: what a run costs the workflow's budget, and what each status
  does to it;
- the outbox adapter's changes (one adapter, two executors);
- where authority lives, and what an agent's graph may and may not change;
- the coordination patterns built from cells.

Out, and yard's: the VM, the broker and its metering, workspace versions and
their registry, approval cards and passkeys, the harness drivers, the model
routes, A2A edges between agents. Out and later: agents that drive Cordis (an
MCP or CLI surface over the HTTP API).

## Relation to yard's draft

| Subject | Rules |
| --- | --- |
| The wire (verbs, identity, incarnation, deadline, ack, retention, status answers) | RFC 0007; yard serves it for `api: "agent"` |
| The executor, sandbox, broker, metering, workspaces, approval cards | yard's draft |
| The Cordis types, settlement, adapter behaviour, cells, authority split | this RFC |
| Anything the draft says about what Cordis does (names, effect signatures) | proposals to this RFC; accepted below where marked |

Revision 1 of this RFC proposed things yard's draft decides otherwise, and
yard's decisions are adopted: no executor in the outbox (a direct-subprocess
stage is dropped; the Firecracker decision has no stopgap); cost is **metered
by the broker**, never taken from the CLI's own report; a run's result is a
workspace **version**, and applying one needs approval; an operation's
incarnation is yard-ctl's durable epoch, so `unknown` means a lost store, not a
restart.

## The effects

Cordis adopts yard's signatures (graph language, proposal accepted):

```
type Workspace   = { repo: str, version: str }        // a yard workspace version
type AgentBudget = { tokens: int, usd: float, minutes: int }
type AgentTask<T> = {
  agent: Agent, model: ModelRoute,                     // the route, never a key
  workspace: Workspace, mode: ReadOnly | ReadWrite,
  instructions: [str],
  output: Type<T>,
  budget: AgentBudget,
}
type AgentOutcome<T> = { value: T, result_version: Workspace | null, usage: Usage, transcript: Ref }

effect agent.run<T>(AgentTask<T>) : AgentOutcome<T>
effect workspace.apply(result: Workspace, expected_base: Workspace) : Workspace
```

`instructions` are rendered from `resources/prompts/` by the cell, as every
word a model reads is. `output` is a Cordis type; the codec that validates
every other receipt validates this one a second time on arrival (yard-ctl
validates first and retries once in the same VM). **A result that fails
validation, or a run that finished on `budget` or `timeout`, is a refused
receipt and never an approval.** The rule is the one already in force for
cut-off completions. A `Pass` is an input to the workflow's own guards, not a
decision: a cell may require every finding to cite a file and line that exist
in the version reviewed.

A review cell is as yard's draft shows it. Its type, `Review = { verdict,
findings }`, belongs to the graph, and the prose of its instructions to a
prompt file.

## Classes and settlement

`agent.run` is **observational**: the run happens in a VM, its writes land in
a new workspace version that never changes the one it read, and a repeat is a
new attempt in a new VM that spends again and may differ. That is RFC 0007's
repeat guarantee, stated by yard's contract ("external effects: none outside
the VM, except model calls through the broker, metered"). `workspace.apply` is
**effectful and keyed** by `(result, expected_base)`: a compare-and-set on the
target head, which RFC 0003's keyed model covers.

The adapter settles each status as RFC 0007's accounting table, with what
yard's draft adds:

| Status | The adapter | Settlement |
| --- | --- | --- |
| `running` | waits | the reservation holds |
| `done`, finish `stop` | commit receipt, ack | the **metered** usage |
| `done`, finish `budget`, `timeout`, `error` | commit, ack; the receipt is **refused** to the cell | the metered usage |
| `done`, finish `cancelled` | commit, ack | the metered usage |
| `failed` (yard's: a VM or node crash with its usage metered) | refused; ack | the metered usage, via `Lost_at` |
| `needs_input` (proposed) | refused with the question as data; a cell routes it and emits a new attempt with the answer | the metered usage |
| `expired`, `closed`, `cancelled_before_accept`, `released`, `unknown` | as RFC 0007 | as RFC 0007 |

`failed` and `needs_input` are additions to RFC 0007's status vocabulary and
need its next revision. Neither is new to the **accounting**: "the world
reports what an attempt spent, with no result" is `Lost_at`, which the engine
already charges (at most the worst case) and which `observe.qnt`'s
`EXPIRE_REPORTED` models. `unknown` remains the worst case, and with yard-ctl's
durable epoch it occurs only when yard-ctl's store was lost.

**The worst case R** is the task's budget converted to Cordis units. A run
whose budget lacks any of its three limits is refused at submit; the broker
enforces the token and spend limits, and the executor the minutes, so R is
real. Overshoot beyond a cap is the broker's to state in its contract, and R
includes it.

**Units.** Cordis keeps one integer budget. `AgentBudget` has three
dimensions. `gates.edn` owns the mapping from a task's budget to units (a
price per token class per route, a price per minute) and the adapter applies
it to the metered usage the same way, so reservation and settlement cannot use
different prices. A route yard prices in USD is converted by the same table.
The mapping is policy and is therefore data; it is not a constant in code.

## Authority

Two things are both true. The standing rule is that behaviour is data the
agent can rewrite at run time. And yard's draft: **authority is the runner's
configuration, never the graph's**, because an agent that can raise its own
budget, grant itself `ReadWrite` or enable automatic approval has no limit.
They meet in a division:

| Lives where | What | Who can change it |
| --- | --- | --- |
| Runner configuration (outside the graph and the mutation protocol) | which agents and model routes a cell may use, the **ceiling** of its budget, `ReadWrite`, whether `workspace.apply` may be approved automatically | the operator |
| `manifests/*.edn`, `cells/*.clj` | which of the permitted agents a state uses, for what, in what order; fan-out, quorum, escalation | the agent, behind admission |
| `gates.edn`, `agents.edn` | prices, the units mapping, per-cell budgets **at or below the ceiling**, retry counts | the agent, behind admission |
| `prompts/*.md` | every instruction word | the agent |

A cell may request less than its ceiling and never more, and the check is made
where the request is accepted (yard-ctl, from its own copy of the runner
configuration), not where it is composed. The mutation protocol cannot reach
the first row. This is `observe.qnt`'s `misclassify` rule applied to
authority: a cell, not policy, must not choose its own class.

## Applying a result

`workspace.apply` takes a result version and the base it expects. It succeeds
only if the target is still at that base, and **only with an approval bound to
`(result, base, diff digest)`**: a card for the user, until a Cordis approval
harness exists and has passed its evaluation set, after which the runner
configuration may enable automatic approval for a named cell. Until then every
apply raises a card (yard's decision 2).

From the workflow's side:

- Awaiting a card is a **long wait**. The operation stays `running` with its
  own deadline (the card's expiry); an expired card is a refusal, never a late
  apply. The outbox's patience (`wait_s`) and `Halo-Not-After`'s
  `max_submit_age_s` bound a *submit*, not an approval, so the operation's
  deadline for apply is a field of its own, to be set with yard.
- A `conflict` (the target moved) is a data result. A cell rebases by emitting
  a new `agent.run` against the new head, or asks a person.
- The approval harness is a Cordis workflow (critics, tests in their own agent
  tasks, deciders) and a graph like any other; its decisions are recorded
  facts. This RFC does not specify it.

## The adapter

`halo_op.ml` becomes an operation client taking an endpoint, an `api` and a
request builder; halo and yard are two configurations of it. What changes:

- the request body for `api: "agent"` is the `AgentTask` as JSON;
- the status vocabulary above, including `failed` and `needs_input`;
- the receipt carries `result_version` and `transcript`, and the journal keeps
  them in the fact beside the value;
- the settlement mapping from `gates.edn`;
- `contract` is yard's (`/v1/yard/contract`); the adapter reads the guarantees
  it needs (incarnation, `inference`-style mode is not relevant) and refuses a
  task whose cell is not permitted, before it is journaled.

Everything else is built: the journaled exact submit, the salted operation id,
commit-before-ack with the obligation in the same transaction, the owed-ack
recovery, the crash injection, `Lost_at`, conflict charged as unknown, and the
milestone script with its evidence manifest.

## Coordination patterns

Cells and manifests only; no new mechanism.

- **Review loop.** `review_loop.cg` with the critic's `llm.complete` replaced
  by `agent.run` in a read-only task.
- **Fan-out with quorum.** The same version to several reviewer agents
  (different agents, different models). A quorum cell takes their typed
  reviews; a disagreement emits a third reviewer or a card to a person. All
  are reserved against one budget.
- **Cross review.** The agent that produced a version is not among its
  reviewers; the manifest states the exclusion.
- **Writer.** A `ReadWrite` task returns a version; a reviewer fan-out looks at
  it; `workspace.apply` raises a card with the diff taken from the registry,
  not from the agent's description of it.

## Models

yard's `spec/agentop` covers what agents add on the executor's side:
`accounted`, `withinCap`, `runsOnce`, `noApplyWithoutApproval`, `noLostUpdate`,
`appliedOnce`, `noCallsAfterEnd`, with seven mutants. This RFC does not
duplicate it. What the Cordis side adds is small, and most of it is modelled:

| Rule | Where |
| --- | --- |
| A run that reports usage and no result settles at that usage, at most R | `observe.qnt` `EXPIRE_REPORTED` / `EXPIRE_UNDER` (built) |
| Commit before ack, the obligation with the answer | `capture.qnt`; `ops.sh` K, L |
| A refused receipt is never an approval | a cell guard, tested with the stub; not state |
| Authority cannot be widened by a cell | **to model**: `authority.qnt`, a request against a ceiling, with the mutant `ceilingInGraph` (the ceiling read from the graph) |
| The units mapping applied equally to reservation and settlement | **to model** with `authority.qnt`: mutant `priceMismatch` |
| An apply raises a card until automatic approval is configured and tested | yard's `autoApproveUnconfigured`; the Cordis side adds a witness only |

## Order of work

C0 is this RFC accepted, with yard's draft; the rest follows yard's milestones
AG0 to AG7 and can mostly be built against a stand-in before yard serves the
verbs.

1. **C0.** Accept; answer the open questions.
2. **C1. The adapter generalised and a stand-in.** `halo_op.ml` as an operation
   client; `stub_ops.py` extended (or a sibling) to serve `api: "agent"` with
   the new statuses, metering, `failed`, `needs_input`, a budget cap, a refused
   receipt; the existing `ops.sh` phases run against both APIs. Needs nothing
   from yard.
3. **C2. `authority.qnt`** and its gate rows; then the runner-configuration
   check in the adapter (refuse before journaling), with `agents.edn` and the
   `gates.edn` mapping.
4. **C3. The `agent.run` kind**: codec, manifest wiring, `manual.edn` entry,
   `resources/prompts/` review rules, and a `claude_review` cell and graph;
   the milestone script with a stub agent.
5. **C4. Against yard** at AG2 and AG3: `milestone.sh` with `YARD_URL`, with
   the evidence manifest, through yard-ctl restart and a killed VM.
6. **C5. `workspace.apply`** with cards, at AG4; the long wait.
7. **C6. The Cordis approval harness** and its evaluation set, at AG7.

## Open questions

1. **Units** (above): confirm one integer budget with a `gates.edn` mapping,
   or let Cordis budget in USD and tokens separately. A separate-dimension
   budget is a larger change to the engine and `budget.qnt`.
2. **The apply deadline**: where a card's expiry lives in the operation, and
   whether an operation may stay `running` for hours under a restart-prone
   outbox. (Its journaled exact submit and owed-ack recovery already survive
   outbox restarts; the wait itself needs a bound that is not `wait_s`.)
3. **`needs_input`** as yard proposes: a new attempt with the answer. Agreed
   here; the status needs a place in RFC 0007.
4. **`failed`**: whether yard reports it as its own status or as `done` with
   finish `error` (its own text uses both). The adapter treats both the same.
5. **Mode.** `exact` and `relaxed` are halo's inference modes; whether an agent
   task has an analogue (a harness version and flags pinned, so a replay is
   comparable) is yard's to say.
6. **Transcript retention** and what the receipt keeps: a reference only, with
   the transcript held by yard under the run's training-data class.

## History

- Revision 1: first draft, written without yard's. It proposed an executor in
  the outbox, profiles that map a CLI's own usage report, and Cordis applying
  patches.
- Revision 2: aligned with yard's `design/AGENT-OPERATIONS.md`. Adopts: yard
  as executor, broker metering, workspace versions and approved apply,
  authority in the runner's configuration. Replaces the outbox executor and
  `agent_run.qnt` with the adapter generalisation and a smaller `authority.qnt`.
