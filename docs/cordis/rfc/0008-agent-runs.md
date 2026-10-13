# RFC 0008: Coding-agent runs as operations

Status: **proposed** (revision 1). Nothing here is built. It extends RFC 0007
(the operation contract) and RFC 0006 (the turn runtime) to a second kind of
executor, and it names the Quint model that has to exist before the first
line of code.

## Purpose

Cordis workflows should be able to coordinate, prompt and delegate to coding
agents (Claude Code, Codex, opencode and the like) the way they now use a
model: a cell emits an intent, an executor does the work, and a typed receipt
comes back with its cost. The motivating cell is a **review**: a read-only
agent examines a patch against the project's rules and returns findings that
the workflow can act on.

An agent run is not an inference call. It runs for minutes, uses tools,
spends the provider's money by its own meter, and produces an artifact.
RFC 0007 limited its contract to inference "until a second executor calls for
extraction". This is that second executor. This RFC takes the question and
answers it narrowly: **the operation contract is reused unchanged, a new
`api` is added, and the outbox is still not extracted.**

## Scope

In:

- the intent kind `agent.run`, its request and receipt types, and its class;
- caps, and what a run's worst case is;
- profiles, as policy in `resources/agents.edn`;
- isolation: workspace, tools, network, credentials;
- the executor: first a direct subprocess in the outbox, then operations
  served by yard;
- coordination patterns built from cells: review, fan-out with quorum, cross
  review, a writer whose patch Cordis applies.

Out:

- agents that drive Cordis (an MCP or CLI surface over the HTTP API we already
  serve); separate, later, and it changes none of this;
- interactive sessions, resuming an agent's own session, and streaming
  partial output into a decision (partial output is an observation, never a
  receipt, as in RFC 0007);
- a billing system. Cordis keeps budget units; a profile maps a CLI's report
  to them.

## What an agent run is

**An operation with `api: "agent"`.** Identity, binding, incarnation, clock,
deadline on every verb, commit before ack, retention until acknowledged,
status answers and the accounting table are RFC 0007's, unchanged. The
executor serving it (yard, or the outbox itself in the first stage) must keep
the same promises; the adapter that exists for halo (`halo_op.ml`) becomes an
operation client taking an endpoint and an `api`, and the request body is the
only thing that differs.

**Its class is observational, by construction.** RFC 0007's repeat guarantee
for inference is "external effects: none; spend: a new generation spends
again". An agent run can keep that promise only if it cannot touch anything
that outlives it. So:

1. It runs in a **disposable workspace**: a worktree or copy of a named
   revision (`workspace.rev`), read-only unless the profile says otherwise,
   discarded when the run ends.
2. Its effect is an **artifact in the receipt** (findings, a patch, a
   transcript reference), never a change to the real repository. Applying a
   patch is a separate intent, effectful and keyed, that **Cordis** emits
   from a cell, and which RFC 0003's keyed-retry model already covers.
3. A run that is repeated starts from the same revision and spends again.

A profile that cannot meet this (it needs the network, or a shared service)
is declared **effectful** in its profile and is then reconciled, not retried;
no such profile is proposed here.

## The intent

```
emit agent.run {
  profile:   "claude-reviewer"                  // names a row of agents.edn
  prompt:    render("review.md", {diff: draft.patch, rules: rules})
  workspace: {rev: draft.base}                  // read-only by the profile
  returns:   Review                             // a type of the graph
}

type Finding = { path: string, line: uint, severity: Severity, claim: string, evidence: string }
type Review  = { verdict: Verdict, findings: list<Finding> }
```

The **request** is the profile name, the rendered prompt, the workspace
reference, optional per-run narrowing of the profile's caps (never widening),
and the output type's schema. The **receipt** is the typed value; beside it,
the run's usage in the profile's units, a transcript artifact reference, and
the finish reason (`stop`, `turns`, `budget`, `timeout`, `cancelled`).

**The output is data.** The executor asks the CLI for structured output where
it has such a mode (Claude Code's JSON-schema output, Codex's output schema),
and the outbox validates the result against the graph's type with the same
codec every other receipt uses. A result that does not validate, or a run that
stopped on a cap before finishing, is a **refused receipt**, not an
approval: the rule already held for cut-off completions ("never a verdict").
A `Review` whose verdict is `Pass` is still only an input to the workflow's
own guards; for instance a cell may require every finding to cite evidence
that exists in the revision, and may treat zero findings from an agent that
read nothing as suspicious.

## Caps, and the worst case

RFC 0007's unknown outcome is settled at the worst case R. For inference, R
is `max_tokens`. For an agent run, R exists only if the harness enforces it,
so **a profile without all four caps is rejected at load**:

| Cap | Enforced by | Notes |
| --- | --- | --- |
| wall clock | the executor kills the process group at the deadline | carried as the operation's `not_after` |
| turns | the CLI's own limit, and the executor counts events | Claude Code `--max-turns`; the others by event count |
| spend | the CLI's own limit where it has one, else the executor stops the run at the reported spend | Claude Code `--max-budget-usd` |
| output size | the executor | artifacts are bounded |

R is the profile's spend cap converted to units. A run that cannot report its
spend is charged R when it ends, as an inference call that reports no usage
is today. The executor stops a run **at or just over** a cap, so the true
spend can exceed R by one unit of the CLI's own granularity; the profile
states that overshoot and R includes it. This is the number the Quint model
must be able to rely on (see "Models").

## Profiles are policy

`resources/agents.edn`, loaded at run time and editable by the agent through
the mutation protocol, with its figures in `gates.edn`:

```
{:claude-reviewer
 {:agent    :claude-code
  :argv     ["claude" "-p" "--output-format" "json" "--json-schema" :schema
             "--max-turns" :turns "--max-budget-usd" :spend
             "--permission-mode" "plan" "--allowedTools" "Read,Grep,Glob"]
  :workspace {:mode :ro}
  :network   [:provider]
  :caps      {:wall-s 600 :turns 12 :spend-usd 2.00 :output-kb 256}
  :units     {:per-usd 100}
  :report    {:spend [:total_cost_usd] :turns [:num_turns]}
  :credential :anthropic}}
```

A profile names the binary and argument template, the workspace mode, the
network the jail allows, the caps, how the CLI's report maps to units, and
the credential **name**. A credential's value is held by the executor, never
by the graph, a cell, or a prompt. Adding an agent is adding a row and a
report mapping; nothing in `src/` knows the name of a CLI.

`resources/manual.edn` gains the `agent.run` entry so the next run knows it
exists, and `resources/prompts/` holds the prose (review rules, severity
definitions).

## Isolation and trust

The reviewed content is untrusted: a patch can contain instructions aimed at
the reviewer. The defences are structural, not the model's good manners.

- **Read-only tools and workspace** for reviewers; a writer's workspace is a
  worktree it may change, and its patch is read out, not merged.
- **Network** limited to the provider's endpoint (or halo's, for a local
  model), through the egress proxy the outbox already uses for imported APIs.
- **Credentials** injected into the jail's environment by the executor, per
  run, scoped to the one profile, and absent from the transcript the receipt
  keeps (the executor redacts values it injected).
- **The receipt is data.** Nothing in it is executed or fed to another run's
  prompt without passing through a cell that decides.
- **Subscription authentication** (a person's logged-in CLI session) is not
  assumed. API keys per profile are the baseline; whether a deployment may use
  a subscription login for unattended runs is a policy decision for its owner.

## Executors

**Stage 1: the outbox runs the process directly.** `agent.run` is an executor
beside `llm.complete`, spawning the CLI in a temporary worktree with the
profile's argv, enforcing the caps, collecting the CLI's JSON, and returning
the receipt. The durable handoff is RFC 0007's, already built: the request is
journaled before the run; the result is committed with its obligation before
anything is released. A crash mid-run leaves the process orphaned and the
attempt unknown (charged R); the worktree is reaped at start from a directory
the journal names.

**Stage 2: yard serves it.** The same operation verbs against yard: submit,
status, fetch, cancel, ack, with incarnation and deadline. The run moves into
yard's jail, and the outbox no longer starts processes. Because yard is
ephemeral like halo, the unknown/closed/expired accounting is unchanged. The
adapter shared with halo is the reason stage 1 is worth building as an
operation client from the start, with a loopback server (like `stub_ops.py`)
in the tests.

**Halo's part** is inference only. An agent whose backend is a local model
points at halo's OpenAI-compatible endpoint from inside the jail; halo meters
that inference under its own principal and the profile's `:report` says which
meter counts toward the run's spend (the agent's own report, never both).

## Coordination patterns

All are cells and a manifest; none needs a new mechanism.

- **Review loop.** `review_loop.cg` with the critic's `llm.complete` replaced
  by `agent.run` under a reviewer profile. The writer stays as is, or becomes
  a writer run whose patch is applied by Cordis.
- **Fan-out with quorum.** The same patch to several reviewer profiles
  (different agents, different models). A quorum cell takes their typed
  reviews; disagreement emits a third reviewer or escalates to a person.
  Budgets are per graph, so the fan-out is bounded by the same reservation
  that bounds everything else.
- **Cross review.** The agent that wrote a patch is not among its reviewers;
  the manifest states the exclusion as data.
- **Writer.** A run in a writable worktree returns a patch; a keyed intent
  applies it to the real repository, and a cell verifies the result before it
  is accepted.

## Models

Written before code, with gate rows in `quint/check.sh` and mutants, per the
standing rule.

`agent_run.qnt`: a run with caps, a disposable workspace, an artifact and a
real spend that the executor may overshoot by a bounded amount.

- **Safety:** the repository changes only through an applied patch intent
  (`workspaceIsolated`); spend is within R plus the stated overshoot, and
  settled spend is never below the run's reported usage (`spendBounded`,
  `neverUndercharged`); a receipt that fails validation or stopped on a cap
  is never an approval (`noApprovalOnCap`); a run is journaled before it
  starts and its artifact is committed before it is released (RFC 0007's
  handoff, `capture.qnt` composed with it).
- **Mutants:** `writeThrough` (the workspace is the real repository),
  `noCap` (a profile without a spend cap), `approveOnCap`, `ackFirst` (reuse),
  `leakCredential` (a credential value reaches the transcript).
- **Witnesses:** a run stopped on its turn cap, a run killed with usage
  unknown, a refused receipt, a fan-out whose reviewers disagree.

The caps are the model's only trusted input; the executor's enforcement of
them is tested against a stub CLI that overspends, ignores signals and
exhausts turns.

## Order of work

1. **This RFC, accepted.** Questions below answered.
2. `agent_run.qnt` and its gate rows.
3. `agent.run` as a direct-subprocess executor in the outbox, as an operation
   client with a loopback server, tested against a stub CLI (caps, overspend,
   killed process, malformed output, orphan reaping), then one real
   Claude Code reviewer profile and a review cell.
4. `agents.edn` loading, profile validation (reject missing caps), the manual
   entry, and the fan-out/quorum example graph.
5. Stage 2 against yard, when yard serves the operation verbs; the milestone
   run of RFC 0007, with a real CLI, under faults.
6. Writer runs with Cordis applying the patch.

## Open questions

1. **What is yard's shape today?** This RFC assumes it can run a process in a
   jail and serve the operation verbs. If it does neither, stage 2 is a
   separate piece of work and stage 1 stands alone.
2. **The budget unit.** Units per USD as above, or tokens? A multi-vendor
   deployment cannot add tokens across models; USD cannot compare a local
   model with an API. The proposal is units with a per-profile mapping, and
   `gates.edn` owning the mapping.
3. **Workspace for large repositories.** A worktree per run is simple and
   isolated; a shallow copy or overlay may be needed for size.
4. **Writer authority.** May a writer ever run against the real repository
   (a deployment that wants an agent's in-place edits), or only through a
   worktree and an applied patch? This RFC says only the latter.
5. **Unattended authentication** (see "Isolation and trust").
6. **Output modes differ by CLI.** Where a CLI has no schema mode, the profile
   asks for JSON in the prompt and the outbox validates; the model treats a
   validation failure as a refusal either way.

## History

- Revision 1: first draft, after the question of delegating to coding agents
  from Cordis, yard and halo.
