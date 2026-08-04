# Temporal ledger, materialized view, and stream-system sketches

**Status:** architecture exercise, not a storage-engine plan, compatibility
claim, or implementation decision. This asks what a Jolt/Perturb system could
learn from Datomic, XTDB, and Rama while preserving the progressive-assurance
discipline. It begins with a single-node deterministic semantic kernel and makes
every distributed, durable, or external-effect property a later separate claim.

## 1. What to borrow—and what not to borrow

| Reference | Useful concept | Do not inherit by name or implication |
| --- | --- | --- |
| Datomic | immutable facts, transaction-ordered history, immutable database basis, query over historical values | ACID/distribution, transactor behavior, storage/index implementation, or global durability guarantees |
| XTDB | named query basis plus separate system and valid/effective time | bitemporality, SQL/XTQL, object-store/leader architecture, cross-database semantics, or snapshot guarantees |
| Rama | durable input log, independently shaped derived state, partition ownership, explicit stream retry/ack levels | replication, ISR/replog behavior, cross-partition transactions, globally atomic observation, or exactly-once external publication |

The direct design rule is:

> Accepted immutable input is the source of truth; indexes, materialized views,
> caches, and UI projections are explicitly derived observations with a named
> basis/frontier.

This is compatible with both a database-like interface and a stream-processing
interface. It does not require copying any product's query language, storage
layout, or distributed protocol.

Primary project sources consulted for this sketch include
[Datomic's data model](https://docs.datomic.com/cloud/whatis/data-model.html),
[XTDB's database architecture](https://docs.xtdb.com/about/dbs-in-xtdb.html),
[transactions/basis](https://docs.xtdb.com/about/txs-in-xtdb.html),
[time model](https://docs.xtdb.com/about/time-in-xtdb.html), and Rama's
[programming model](https://redplanetlabs.com/programming-model),
[depots](https://redplanetlabs.com/docs/~/depots.html),
[PStates](https://redplanetlabs.com/docs/~/pstates.html),
and [ACID documentation](https://redplanetlabs.com/docs/~/acid.html). They
support reference concepts only; no behavior is claimed for Jolt/Perturb.

## 2. The minimal semantic kernel

### 2.1 Commands, decisions, and a ledger

Start with an append-only ledger of **accepted complete transactions**, not a
general database:

```clojure
{:tx-id      17                 ; single-node logical order
 :command-id :client/op-42      ; stable idempotency/correlation identity
 :facts      [{:entity :order/7 :attribute :order/status
               :value :paid :op :assert}]
 :basis      16                 ; evaluated-before prefix
 :meta       {:origin :api}}    ; canonical, non-authority metadata
```

`submit` is serialized at the ledger. Given tail `L` of length `n`, it computes
`before = fold(L)`, constructs `t` with `:basis n` and `:tx-id (inc n)`, and
accepts iff `validate(before,t)` and `validate-after(before,t)` hold; its sole
accepting transition is `L → conj(L,t)`, otherwise `L → L`. `fold` applies the
entire `:facts` vector as one transition. The same serialized transition first
consults the command-id mapping: a repeated `:command-id` returns its recorded
decision (and transaction when accepted) rather than appending again. No query
or materializer may observe an individual fact of `t`.

Result states must distinguish:

```text
invoked → accepted | rejected | unknown
accepted → visible-at-basis
```

In this single-process model, `accepted` means appended to the in-memory ledger
of this execution only; it is not a durability, replication, or external-effect
acknowledgement. Deduplication is a persistent-in-model mapping from
`command-id` to canonical command digest, outcome, and, when accepted, `tx-id`.
A retry with the same id and digest returns that recorded outcome and cannot
append another transaction; the same id with a different digest is a definite
rejection. A rejection proves no append for that invocation. Lost request/reply,
timeout, or crash after submission yields `unknown`, including when the
transaction may already be accepted. An `accepted` response is emitted only
after the ledger transition. `accepted`, `materialized`, and externally
completed remain different facts.

### 2.2 Basis and time

Every query has an explicit observation basis:

```clojure
{:basis {:ledger-prefix 17}
 :query  query-data}
```

“Latest” is a convenience API that first captures a basis and returns it with
the result. It must not sample a moving prefix repeatedly during evaluation.

Ledger prefix is a logical serialization coordinate, not wall-clock or system
time. A future temporal model must name separately: (1) an immutable
snapshot/basis coordinate; (2) the source and comparison rule for system time;
and (3) valid time. A repeatable temporal query captures all three once. If
system time is represented by transaction order, it is explicitly a logical
order rather than a timestamp. Valid/effective time is optional and uses
declared half-open intervals. A temporal fact declares a version key and valid
interval `[valid-from, valid-to)`; endpoints belong to one total order extended
with `+∞`, require `from < to`, and contain `t` exactly when `from ≤ t < to`.

At basis `b`, the reference fold considers exactly `ledger[0:b]`; a version’s
system interval begins at its accepting transaction and ends at the next
accepted transaction that supersedes its declared version key, or `+∞`. A
temporal query returns only versions whose derived system interval and declared
valid interval both contain captured values. The domain must state whether
overlapping valid versions are rejected, returned as a set, or resolved by a
declared total rule.

### 2.3 Derived state is not authority

```text
R(c)    = fold(accepted-ledger[0:c])
V(c)    = project(R(c))
index   = sort/project(reference value)
```

A materialized view’s required relation is `view = V(cursor)`, not equality to
an unrelated reducer. Its externally observable checkpoint is an atomic pair
`(view,cursor)`: a crash exposes either the previous published pair or the next
valid pair; torn private state is rebuilt or validated before publication.
Queries requiring basis `b` may use the view only when `cursor ≥ b`; otherwise
their declared policy is wait, `:unknown`, or `:inconclusive`. Recovery rebuilds
from the ledger or advances from that cursor; it does not invent source order.

External side effects are outside this identity. A materializer may be
exactly-once with respect to its own deterministic state only under its stated
checkpoint model. It must not claim exactly-once email, HTTP, depot publication,
or other external effect without an atomic/fenced sink or an explicit
at-least-once contract.

## 3. Stream and partition layer

Only after the single-node ledger is useful should the system add a stream
model:

```text
event id → partition(key, epoch) → ordered partition prefix → derived view
```

The partition key and epoch are semantic inputs. Same-key order exists only
when the key maps to one partition for the relevant epoch. Cross-partition order
does not exist without a sequencer or transaction coordinator. Repartitioning
is a versioned migration with explicit old/new frontier semantics, not a hash
function replacement.

Rama-inspired materialized views should be declared as independently shaped
indexes over named input prefixes. They can be rebuilt and compared to a pure
projection `V(cursor)`. A retry model must say which unit retries—event, batch,
or transaction—and whether a sink is idempotent. “Exactly once” is never
inferred merely from a replayed deterministic projection. In particular, a
view’s atomic `(view,cursor)` checkpoint is a model requirement; a real storage
implementation needs a target-specific atomic-publish/recovery mechanism before
claiming that relation after crashes.

## 4. Assurance model

### 4.1 Single-node first

The first implementation target is a single-node deterministic ledger,
snapshot/basis query, one materialized index, and explicit `unknown` result
handling. It makes no networking, fsync, replication, or distributed-read
claim.

| Kernel | Pure reference property | Required negative control |
| --- | --- | --- |
| transaction | rejected command leaves ledger unchanged; visible state is a whole transaction prefix | partial publication mutant reaches an invalid state |
| temporal query | result satisfies captured basis/version and valid containment | selector ignoring the captured version/basis returns a later version |
| index | indexed query equals reference scan at same basis | stale/duplicate/wrong-key index update |
| materialization | `view = V(cursor)` for the accepted prefix | crash between state/cursor write; skip/duplicate input |
| partition | partition offsets are ordered and each event has exactly one declared owner | duplicated/omitted route or out-of-order offset |

A bounded model check defines the precise claim, state variables, initial
states, transition relation, finite domains/bounds, environment assumptions,
fairness/progress assumptions where liveness is claimed, and omitted behavior.
It asserts the negated property as an explicit violation predicate. Its record
includes encoding, solver/version/options, and exact results for the same query:
buggy control **SAT**, corrected control **UNSAT**, and reachable valid-boundary
non-vacuity control **SAT**. Each SAT witness becomes a deterministic replay
test or scheduler constraint; each UNSAT result is labeled only `bounded proof`
under the recorded bounds and assumptions. A solver/tool failure is
`unverified`, not UNSAT evidence.

The current `jolt.sim.hegel` adapter generates and shrinks only top-level future
admission schedules. It does **not** yet generate command, duplicate, failure,
or delivery histories for this ledger model. Such a state-machine generator,
shrinker, replay format, and model runner are future work, and would yield only
`sampled` evidence even when a witness replays exactly. A finite exploration
earns `bounded-complete` only when the exact finite
initial-state/history/transition/schedule/environment domain is fully consumed
with equality-confirmed identity, expected cardinality, uniqueness,
termination, and no unexplained cut-offs.

### 4.2 Distributed world later

The first distributed model adds logical nodes, per-origin prefixes/frontiers,
causal delivery, partition/heal, crash/restart, and a stated convergence
predicate. A query exposes its frontier; an insufficient frontier is wait or
unknown, never fabricated “current” data.

Any eventual-materialization claim requires explicit fairness assumptions:
enabled work is fairly scheduled; storage responds; crash/retry ceases; and
messages between live members eventually arrive. A finite trace monitor cannot
establish these assumptions and must return `:inconclusive` when they are
unexercised.

No Maelstrom/Jepsen adapter or canonical semantic-history exporter exists in
this repository today; current FFI route/lifecycle traces are not substitute
operation histories. Only after the distributed model exists should an external
adapter be considered. It must call the same command handler used by the
simulated world and emit a versioned canonical history schema containing at
least invocation, completion, `command-id`, digest, result (`accepted`,
`rejected`, or `unknown`), transaction id when known, node, and observed
frontier/basis. A checker-specific translation is derived from that schema and
must preserve incomplete operations. Passing a workload/checker validates only
that workload, history model, checker, target, and fault run—not general
linearizability, availability, liveness, or durability.

## 5. Proposed REPL and development experience

A future data-system REPL could be an evidence workbench; none of the following
illustrative APIs exists today:

```clojure
(ledger/submit! node command)
(ledger/query node {:basis 17 :query q})
(ledger/rebuild ledger-prefix)
(view/status node :orders/by-customer)
(sim/explore! ledger-model {:bound 8 :property whole-tx-prefix?})
(sim/query history '[:find ?event :where [?event :kind :unknown]])
(perturb.bounded-check! temporal-model {:bound 4 :property snapshot-safe?})
```

Current `jolt-sim` provides cooperative-kernel execution and exact trace replay,
capped enumeration and fresh-process execution of top-level future-admission
schedules, and offline trace monitors. It does not provide a ledger model,
generic finite-state exploration, a trace-query API, or a bounded solver facade;
`schedule-plans` does not establish bounded completeness.

A future facade would expose only captured, redacted ledger/view/history
snapshots and bounded-query descriptors. Live submission would be an effect and
return a correlated result. Authority tokens, credentials, storage handles, and
live mutable index objects would never be datafied. A model-check or solver
request would record its bounds, controls, and evidence status; it would never
run unbounded exploration in the REPL process.

## 6. Application fit

`bbf1` can use an immutable canonical timing-event ledger with derived replay
state, marker indexes, and telemetry projections. Its archive timeline is a
candidate source input, not evidence that the current application already has a
transaction log, durable ledger, or canonical execution replay. The immediate
value is rebuilding/comparing projections at a named cursor and exploring
bounded duplicate/stale/frame-order histories.

`a1s` can use an audit-oriented operation ledger: operator intent, target
origin, confirmation, request attempt, normalized response, and visible refresh
are separately recorded events. It must not put credentials or live clients in
the ledger. The ledger records operator evidence and modeled workflow state; it
does not substitute for IAM, AWS service semantics, or a durable cloud audit
system.

## 7. Development sequence

1. Specify canonical command, accepted transaction, basis, `unknown`, and pure
   projection semantics; write known-good/known-bad examples.
2. Build a single-node in-memory deterministic ledger and reference fold.
3. Add one materialized view with cursor/status and differential rebuild checks.
4. Add bounded duplicate/reject/crash-before-checkpoint worlds. Later, add a
   dedicated ledger state-machine generator, shrinker, and replay format; retain
   shrunk counterexamples. (The current Hegel adapter does not generate these
   histories.)
5. Add optional valid time only for a concrete application requirement, with
   boundary controls at `from`, `to - 1`, and `to`.
6. Add a logical partition model before any network transport; define
   repartitioning and cross-partition nonclaims.
7. Add real storage/network/process lanes only with target probes, fault
   harnesses, and a separately stated consistency/durability contract.

## Nonclaims

This sketch does not implement or promise Datomic ACID, XTDB bitemporal SQL,
Rama replication/PStates, global ordering, distributed transactions,
linearizability, exactly-once external effects, power-loss durability, safe
repartitioning, or availability under partitions. It makes a smaller claim:
accepted immutable input, named observation basis, and derived state can be
modeled, explored, tested, monitored, and eventually probed in stages without
pretending that a local event log is a distributed database.
