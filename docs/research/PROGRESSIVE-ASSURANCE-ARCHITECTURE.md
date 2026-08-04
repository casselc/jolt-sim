# Progressive assurance architecture for Perturb and jolt-sim

**Status:** proposed architecture and testing strategy; not an implementation
decision or a claim that any layer proves another.  This describes how Perturb
can expose its residual obligations deliberately, then use them to plan bounded
exploration, generative tests, controlled execution, hybrid parity runs, and
production-trace validation.

## Thesis

> Every assurance layer either discharges an obligation in its stated model or
> exports the residual obligation—with its state, assumptions, generators,
> observables, and negative controls—to the next layer.

This is **progressive assurance**, a local mnemonic for an
assume-guarantee/assurance-case style of decomposition, not “static first,
testing later.” A type error, solver refusal, unsupported dynamic shape, opaque
native call, or unbounded state space is useful information. It identifies the
testing or monitoring boundary and prevents a weaker result from being silently
reported as proof.

The evidence labels remain separate:

- `proved`: a mechanically checked theorem whose statement, semantics,
  implementation/correspondence link, and trusted assumptions are named;
- `bounded-complete`: a decidable property checked for every member of an
  explicitly finite domain, with harness evidence of expected cardinality,
  equality-confirmed uniqueness, and full consumption; it applies only to that
  fragment, not unbounded or host behavior;
- `sampled`: generated cases, even when shrunk and replayable;
- `monitored`: observed finite trace at an instrumented boundary;
- `simulated`: deterministic model behavior, not host behavior;
- `runtime`: real native calls on the stated target;
- `assumed` or `opaque`: premise not independently discharged; and
- `failed`: a refuted claim, planted negative control, or observed violation.

`simulated` and `runtime` refine the charter's evidence vocabulary for this
architecture; they are observation-source descriptors, not an ordering above
`monitored` evidence.

Deterministic replay reproduces one witness. It is neither exploration nor a
positive completeness verdict.

## The assurance layers

| Layer | Question answered | Useful theory | Export when not discharged |
| --- | --- | --- | --- |
| Value and operation model | What state, operation, result, and failure exist? | algebraic data types; labelled transition systems; state machines | pure step function, invariants, initial state, event vocabulary |
| Local static analysis | Which local ownership, state, and arithmetic facts hold? | abstract interpretation; refinements; affine permissions; typestate | unknown predicates, alias classes, dynamic identities, boundary assumptions |
| Resource/protocol account | Which lifecycle edges and terminal obligations are legal? | automata; typestate; session/resource protocols | legal edge relation, terminal accounting, resource identity and provenance |
| Bounded exploration | Does a finite abstraction contain a bad execution? | explicit-state search; reduction only after independence is specified | counterexample trace, visited/domain denominator, bound assumptions |
| Generative testing | Does a large input/history space reveal a defect? | stateful/model-based property testing; shrinking | minimal input/history, seed/generator structure, untested space |
| Controlled execution | Does unchanged code behave under modeled effects, faults, and schedules? | deterministic simulation; operational runner/effect ideas | deterministic choice/replay input kept distinct from live lifecycle and effect-route observations; controlled-world witness; model-vs-host boundary |
| Hybrid/real parity | Does declared behavior agree at the chosen native boundary? | differential testing; refinement/trace relation | normalized difference, target facts, native-only assumptions |
| Runtime validation | Does an observed trace obey objective declared facts? | runtime verification; finite-trace monitoring | violation/inconclusive evidence, provenance, unobserved obligations |
| Replay and corpus | Can evidence survive as a durable regression? | canonical kernel traces; explicit schedule witnesses; deterministic replay | minimized fixture, exact version/baseline/assumptions; native observations retained as history rather than replay choices |

No row subsumes the others. In particular, a runner-shaped boundary is control
structure; it does not prove a protocol correspondence. A monitor sees finite
events; it does not establish unbounded liveness. A bounded model checker is
complete only for its own identity relation, transition relation, and bounds.

## Contract as the common source

A useful contract is operational data, not merely a type signature:

```clojure
{:operation :close!
 :resource :connection
 :cases [{:pre :open   :result true  :post :closed
          :discharges #{:must-close}}
         {:pre :closed :result false :post :closed}]
 :observers #{:closed? :connection-info}
 :effects #{:socket/close}
 :faults #{:econnreset :timeout}
 :contention {:kind :atomic
              :linearization :close-transition
              :assumptions #{:atomic-primitive-correctly-observed}}}
```

One contract can project into distinct artifacts:

1. static pre/post and ownership checks where the facts are local;
2. a pure transition relation and terminal-accounting model;
3. trace-event shape and pure monitor;
4. operation, fault, alias, and schedule generators;
5. known-good and known-bad fixtures; and
6. a canonical replay schema.

The projection must state what it does **not** establish. A declared atomic
transition does not establish atomicity, winner selection, or a truthful result
discriminator. A Boolean returned by an external CAS is an assumption unless
the stated linearization evidence is independently observed at the boundary
under the declared schedule model.

## Residual-obligation routing

| Residual shape | Next layer | Required output |
| --- | --- | --- |
| finite state/value/history domain | bounded exploration | exact domain definition, cardinality/uniqueness check, property |
| large but generatable input/history domain | property-based test | generator, shrink relation, seed policy, oracle |
| dynamic resource identity or collection membership | modeled table/lease plus monitor | identity/generation policy, lifecycle trace, terminal probe |
| aliasing or shared atomic transition | controlled schedule/fault model and runtime observation | interleaving abstraction, winner/linearization evidence |
| opaque/native operation | hermetic model or hybrid/observe parity lane | descriptor, target assumptions, normalized oracle |
| no independent oracle | explicit assumption and a validation plan | owner, scope, expiry/revisit condition |

This is the key discipline: a residual does not become “handled” merely because
it has a test. The test must name the residual's domain and evidence strength.

## Three implementation strategies

### A. Model-first adapter

```text
contract → pure model → bounded exploration → implementation adapter → code
```

Build the pure operation model before the production component. Explore it,
then implement against the same operation vocabulary.

**Best for:** new parsers, codec kernels, protocol state machines, and pure data
structure kernels.

**Strength:** early counterexamples and a clear oracle.
**Risk:** implementation/model drift; awkward for existing APIs.

### B. Production-first shadow model

```text
unchanged production code → declared interception boundary → trace → pure model
```

Run unchanged application code while intercepting only declared calls that occur
after controller installation and within the controlled scope. Hermetic mode
substitutes registered handlers and blocks an *intercepted* descriptor without a
handler before OS access. Observe mode rejects handlers and proceeds every
intercepted call through its native branch. Hybrid mode substitutes explicitly
classified handler results, while a miss or handler-selected `proceed` routes
natively only after the modeled-resource provenance guard passes. Routing
evidence records the actual handler, native, or blocked route. Work completed
before installation and FFI performed by unmanaged raw threads/executor tasks
remain outside this boundary. At the native rung this is fail-closed; above it,
handler-layer completeness remains a convention unless an explicit composed
layer invariant is checked.

**Best for:** existing Jolt libraries and current jolt-sim handler packs.

**Strength:** small rewrite surface and real integration evidence.
**Risk:** trace completeness is only a claim about the instrumented boundary.

### C. Two-world refinement harness

```text
same scenario
  ├─ modeled world
  └─ :observe or :hybrid world
       ↓
 normalized result and trace relation
```

Execute unchanged application code in two worlds. Compare only contract-defined
facts: result normalization, legal edge trace, cleanup/terminal accounting, and
explicitly permitted target differences such as error code or timeout
granularity.

A passing comparison establishes only the stated scenario's normalized boundary
relation. It is not a refinement proof, protocol-correspondence proof, or
general host-behavior claim.

**Best for:** storage drivers, socket layers, protocol servers, codecs, and
native-library boundaries.

**Strength:** turns “simulation parity” into a checkable relation.
**Risk:** an unjustified equality relation can reject valid host variation or
hide material semantic differences.

## Recommended default

Use **B** as the `jolt-sim` default, add **C** for important native/library
boundaries, and reserve **A** for new pure kernels. Perturb may use the same
assurance artifacts, but its language-feature direction must not be reduced to
an interception adapter. The recommendation aligns with separate current
`jolt-sim` surfaces: `run-controlled` executes unchanged code with hermetic
handlers or observe/hybrid routing and returns live route evidence; the
cooperative kernel owns canonical trace replay; and process exploration reruns
an exact top-level future-admission schedule in a fresh worker. Runtime effect
evidence is not canonical replay input, and an exact schedule does not reproduce
arbitrary native execution or host interleavings.

Do not turn this into a requirement for a general effect system, delimited
control, global static checker, or whole-language region discipline. Explicit
operation data, a pure model, and a narrow interception boundary are sufficient
to begin.

Only scenario harnesses and optional simulation adapters or handler packs should
import `jolt.sim`; production application/library namespaces retain normal Jolt
APIs and production configuration. `run-controlled` owns hooked ordinary-future
workers, not raw threads or executor tasks, which must be joined before the body
returns. Drain/restoration failure leaves controllers installed and poisons the
session; hang, poison, native-crash, and signal cases require process-isolated
gates. A process explorer reaps only its directly launched child, so worker
commands must use an executable directly or an exec-style wrapper. A deadline
means timeout, not deadlock; failure to observe death is an infrastructure error
with retained artifacts. Controller drainage also does not prove model-world
cleanup: memory, storage, and network worlds need explicit terminal/leak probes.
Hybrid provenance is conservative numeric guarding, not typed provenance,
retirement, or general taint tracking.

## The box of pain

An adversarial test plan should be generated from residual obligations rather
than assembled as unrelated fuzz cases:

```clojure
{:schedule [:serial :preempt-after-open :preempt-before-close]
 :fault    [:none :econnreset :eintr :partial-write]
 :resource [:normal :double-close :stale-key :leaked-member]
 :input    [:empty :boundary :one-byte-chunks :oversize]
 :boundary [:modeled :native :forbidden-native]}
```

Small Cartesian products may be exhaustively enumerated only when the exact
tuple/history domain—not merely each axis—is finite, fully consumed, and checked
with equality-confirmed identity and no unexplained filtering or duplicates.
Larger dimensions use stateful generators and shrinkers. Each selected axis
records whether it is finite/exhausted, sampled, `simulated`, `runtime`,
`monitored`, or omitted. No green run may imply coverage of an omitted axis.

Examples of residual-to-test projection:

- unknown aliasing → generate alias/borrow patterns;
- trusted result discriminator → generate dishonest-result model controls;
- unmodeled native descriptor → hermetic-miss control and native parity lane;
- terminal obligation → leak/finalization mutant;
- order-sensitive protocol edge → reordered-trace and invalid-edge controls.

Every planted fault must actually fire. A negative control that cannot fail is
not evidence for the positive test.

## Proposed production trace discipline (not the current trace schema)

The following is a target event shape, not an event currently emitted by
`jolt-sim`. Current `jolt.sim.monitor` accepts version-1 cooperative-kernel
trace documents. `run-controlled` lifecycle and FFI route logs remain separate
live evidence, not canonical monitor input or replay choices.

An event should include stable provenance sufficient to explain one
contract-level fact. Replay of a scenario additionally requires a versioned
scenario record containing initial world, inputs, schedule/fault choices, target
baseline, and defined ordering/correlation rules:

```clojure
{:version 1
 :effect-id 42
 :site-id :app.transport/read
 :resource-id :conn/17
 :operation :recv
 :layer :tcp-model
 :route :handler                 ; :handler | :native | :blocked | :forward
 :request canonical-request
 :outcome [:ok canonical-result] ; or [:abort reason] / [:fault kind]
 :timestamp logical-time}
```

`:forward` is a target multi-rung/Perturb route, not one emitted by the current
`run-controlled` route log.

Avoid live host objects, raw exception identity, mutable buffers, PIDs, and
closure identity in canonical evidence. Keep process supervision facts—PID,
deadline, TERM/KILL path, reaping, diagnostic truncation—separate from the
canonical scenario result. A monitor must return at least `:pass`, `:violation`,
or `:inconclusive`; a missing required operation is normally inconclusive, not
success. Without the required causal/order correlation, a concurrency or
resource monitor must return `:inconclusive`, not infer ordering, ownership, or
cleanup completeness from an effect trace.

## Proposed REPL exploration and query surface

The Clojure-like REPL should be an assurance workbench, not merely a way to
invoke scenarios. This is a target surface, not a claim about current Jolt or
`jolt-sim` APIs. It should operate only over canonical scenario records, pure
models, declared contracts, compiler/IR facts when available, and explicitly
captured observations.

The split matters:

| REPL facility | Input | Result | Cannot establish |
| --- | --- | --- | --- |
| model step/explore | pure transition relation; finite initial states; complete successor enumerator; canonical state equality; termination condition | states, traces, counterexamples, completeness denominator, or `:inconclusive` for an unknown/cut-off successor | host execution, unbounded behavior, or completeness after incomplete expansion |
| scenario run/replay | scenario record, selected model handlers, schedule/fault choices | controlled-run outcome and evidence | arbitrary native replay or unmanaged-thread control |
| trace query | canonical trace/history plus declared event schema | selected events, joins, reachability, monitor explanation | missing causal facts or semantic truth outside the trace |
| IR query | normalized compiler/analysis facts | callers, callees, effect/resource sites, dependency/reachability slices | dynamic dispatch/effects not represented in the facts |
| bounded proof query | named pure semantics and bounded/SMT encoding | `sat` witness, `unsat` within stated encoding, or `unknown` | implementation-wide proof or an unbounded theorem |

An optional test-only namespace family could expose a small, data-oriented
surface:

```clojure
(sim/inspect scenario)                       ; canonical scenario summary
(sim/explore! model {:domain domain :check invariant})
(sim/run! scenario {:world world :faults plan})
(sim/replay! witness)
(sim/query trace '[:find ?e :where [?e :route :blocked]])
(sim/explain monitor-result)
(perturb.ir/callers facts 'my.ns/f)
(perturb.ir/reachability facts 'a 'b)
(perturb.bounded-check! model {:bound 8 :property safety})
```

The names above are illustrative. Every result is ordinary EDN containing its
subject revision, semantic/schema version, evidence label, assumptions, and
nonclaims. It also carries facility-specific evidence: exploration reports its
initial domain, enumerated states/transitions, identity relation, termination,
and completeness status; graph queries report a fact snapshot, extractor and
normalizer versions, resolution scope, and fact-universe completeness status;
SMT checks report encoding/query, solver/version/options, and semantic controls;
scenario runs report controller availability, route coverage, world, and
supervision status. A failure carries a canonical replay witness only when a
declared concretization/refinement map makes it admissible and a separately
recorded controlled run validates it.

### Query and proof semantics

The useful native analogue to Chiasmus is not one magical query engine. It is
three explicitly scoped fact stores:

1. **IR facts**: normalized definitions, binding identities, calls, effect
   descriptors, declared contracts, and static dependency edges. They support
   caller/callee, impact, reachability, and guard-coverage queries. Every query
   records compiler/source revision, extractor/normalizer version, resolution
   scope, and whether the fact universe is closed. Dynamic edges may be reported
   as unknown only where the extractor has a defined unknown-producing coverage
   boundary; otherwise absence is merely absence of a fact. Results are
   fact-query results, not source or behavioral truth.
2. **Execution facts**: canonical scenario inputs, model transitions, choices,
   normalized outcomes, and captured trace provenance. They support relational
   trace queries, explanation slices, and monitor debugging. A query over a
   live FFI route log must preserve its `runtime`/live-evidence status rather
   than silently converting it into canonical replay data.
3. **Proof facts**: a named pure transition system or VC semantics plus its
   finite enumeration or SMT encoding. The query asserts an explicit negated
   property/violation predicate and records solver/version/options/result.
   `sat` is an encoding witness, not an executable replay witness, until a
   declared concretization/refinement map and model-admissibility check succeed;
   only then may a controlled run validate it. `unsat` is only a bounded/encoded
   result and requires three controls against the same query: a known-buggy
   `sat` witness, a corrected `unsat` case, and a reachable-valid-boundary
   non-vacuity `sat` case. `unknown` remains an unresolved residual and should
   be routable into exploration or generators.

This supports an interactive loop:

```text
REPL query discovers a missing edge or opaque premise
  → declare/normalize the residual
  → choose finite exploration, generator, model handler, or monitor
  → obtain an encoding/model witness and validate or shrink it when admissible
  → replay only a concretized, separately validated witness against unchanged code
  → retain the witness, evidence label, and nonclaim as an artifact
```

This surface must never run a poison/hang/native-crash experiment in the REPL
process. Such a request creates a fresh-process job and returns supervision
evidence. Nor may an IR graph query be described as proof of behavior, or an SMT
`unsat` answer be described as proof without its exact model, bound, and
semantic-control checks.

## Proposed cellular event programming model

The assurance architecture can preserve ordinary application programming if the
special surface is confined to **cell boundaries**, not ordinary expressions.
If Perturb adopts eager evaluation with opt-in laziness, that simplifies when
ordinary expressions run; it does **not** make a returned closure, future,
promise, channel, lazy sequence, mutable object, or host value effect-free. The
initial cell evidence boundary therefore accepts only realized, canonicalizable
data. Deferred and opaque values need an explicit representation, forcing
policy, and evidence classification before crossing it. This constrains scenario
evidence, not ordinary language expressions.

This proposal borrows two ideas, without adopting either implementation:

- `core.async.flow` separates step logic from topology, execution,
  communication, lifecycle, monitoring, and error handling; its transform step
  returns new state plus selected output messages. The process manages its
  internal channel communication and lifecycle, but step functions may supply
  external port channels whose lifecycle remains their responsibility.
- [Mycelium](https://github.com/mycelium-clj/mycelium) demonstrates useful cell
  boundaries: explicit input/output schemas, resources passed at a boundary,
  and graph-owned routing. Its schema/graph validation is useful evidence of
  declared shape/path compatibility, not a proof of handler semantics.

### Cell contract

A cell is ordinary code with a declared evidence interface. It receives
boundary data and an explicit context; it returns boundary data and zero or more
declarative effect requests. Workflow-owned dispatch predicates select a
transition label from the returned data, then the graph routes that label. A
future design that permits a handler to select labels directly must call that
handler-controlled routing and audit it separately.

```clojure
(defcell :orders/validate
  {:input   [:map [:order-id :uuid] [:items [:vector item-schema]]]
   :output  {:ok  [:map [:validated-order order-schema]]
             :bad [:map [:errors [:vector error-schema]]]
   :effects #{}
   :purity  :pure}
  [ctx data]
  (if (valid-order? data)
    {:data {:validated-order (normalize data)}}
    {:data {:errors (explain data)}}))

(defcell :orders/request-save
  {:input   [:map [:validated-order order-schema]]
   :output  [:map [:pending-save :uuid]]
   :effects {:db/insert
             {:request [:map [:table :keyword] [:row order-schema]]
              :responses {:ok    [:map [:order-id :uuid]]
                          :error [:map [:reason :keyword]]}}}
   :purity  :effectful}
  [ctx data]
  {:data {:pending-save (get-in data [:validated-order :order-id])}
   :effects [{:operation :db/insert
              :request {:table :orders :row (:validated-order data)}}]})

(defworkflow :orders
  {:dispatch {:orders/validate
              [{:label :ok  :predicate :orders/has-validated-order}
               {:label :bad :predicate :orders/has-errors}]}
   :effects {:db/insert {:responses {:ok :orders/save-ok
                                     :error :orders/save-failed}}}})
```

The workflow declaration must name every dispatch predicate, which must be
declared deterministic and effect-confined for the model in question. The runner
requires exactly one declared label (or an explicit declared ambiguity/error
case) and rejects an undeclared label or data that fails that selected label's
output schema. A declaration is still a claim: audit/monitor coverage is needed
before treating a predicate as actually deterministic or effect-confined.

The code inside these forms is normal code. The declaration only imposes
boundary obligations:

| Contract portion | What it can check or drive | What it cannot establish alone |
| --- | --- | --- |
| Malli/spec-like input/output schema | data shape at cell entry/exit; generator domain; path-compatible graph composition | semantic truth of an arbitrary predicate or business invariant |
| workflow-owned transition labels | route existence, reachability/path constraints, per-transition output shape | semantic truth of a dispatch predicate |
| declared effects and response shapes | request/response validation, handler/model selection, fault generation | complete interception outside the declared/runtime-supported boundary |
| purity/resource declaration | a candidate classification for pure model, differential, bounded, or proof lanes | effect confinement or purity without an audit/monitor coverage boundary |
| graph topology and joins | finite graph/path checks | finite executions, real parallel behavior, fairness, or merge correctness beyond the model |

An effect request is data; it is not evidence that all effects performed by a
cell are requests. The graph runner assigns every accepted request a fresh effect
id; it validates the declared operation and request schema. A response carries
the originating effect id and is rejected unless that request is outstanding and
its response label/value validates. Retry, timeout, cancellation, and duplicate
response behavior are absent unless separately declared. An observed native
crossing, mutable-state dependency, nondeterministic read, or unsupported call
makes that execution `:opaque`; it must not be treated as pure, hermetic, or
model-complete.

This response-event routing is proposed future machinery, not a current
`jolt-sim` runtime feature. Current FFI handler packs synchronously substitute,
proceed, or block intercepted native descriptors and return live route evidence.
They do not emit canonical graph-routed response events. Until a cell/flow
adapter exists, unchanged ordinary code may continue to call a production API
whose underlying FFI is modeled by a handler pack; a future declarative executor
must define the correlation, response, and routing rules above.

This is re-frame-like only in the useful sense—labelled events and effects—not
in the sense of a global untyped application database or implicit dispatch. The
state/data map, transition label, effect descriptor, resource identity, and
response are explicit, versioned evidence.

### Flow composition and bounded cells

A flow declaration connects cells through named ports/labels in the style of
`core.async.flow`'s static step descriptors and connection map. It is a finite
graph artifact, not a finite execution model: cycles, queues, joins, retries,
hidden state, and environment choices can create unbounded histories. A
deployment adapter using channels, futures, threads, or a simulator is future
work; current `jolt-sim` does not control channel choices, joins, backpressure,
or arbitrary executor work. Each exploration/proof query must separately state
state identity, initial-state and queue bounds, successor relation,
termination/cut-off rule, environment choices, and fairness/progress
assumptions. Its declared cell boundaries make three candidate classes visible:

1. **Pure candidate cell:** declared no effects and a value interface. It can
   receive direct examples, differential tests, exhaustive finite input
   exploration, or a narrowly scoped proof query only after enforcement/audit
   establishes the declared coverage boundary.
2. **Modeled-effect candidate cell:** declared effects have deterministic handler
   models. A future cell adapter can run it with generated fault/response plans;
   today a lower FFI boundary may instead be modeled under unchanged ordinary
   code.
3. **Opaque/native cell:** effects cross a real boundary. It requires a
   hermetic-miss control, observe/hybrid evidence or a differential oracle, and
   explicit target assumptions.

Cells are therefore *bounded assurance units*, not automatically proven units.
The graph composes data-shape and declared-edge contracts; it does not compose
proofs of arbitrary handler bodies. A cell may be independently developed,
tested, mutated, and counterexample-shrunk. A future graph executor may replay a
concretized witness only after defining its canonical trace schema, choice
semantics, and mapping to ordinary code; current `jolt-sim` provides no
graph-level replay.

Resource-bearing values remain outside an accumulating unrestricted data-map
discipline. This proposal makes no ownership or authority-safety claim until it
defines an enforced capability boundary. Resource authority must be a graph-
issued, non-serializable token held outside input/output/effect data; handlers
must validate flow scope, lifecycle generation, and operation rights. Resource
ids and map fields are correlators only, never authority. Unknown, retired,
copied, or cross-flow tokens are rejected. Until that mechanism exists,
resource-bearing cells are `:opaque`.

### Datafy and nav as exploration, not formatting

`datafy` and `nav` may make already-captured evidence discoverable from a REPL;
they are not query execution. Neither protocol inherently guarantees purity or
boundedness. The proposed implementation must document and test that its
datafication/navigation paths transform only immutable, versioned, canonical
snapshots; never start scenarios, force deferred work, invoke FFI, run solvers,
launch processes, or dispatch through live-object protocols. Traversal has
declared depth, node, and byte limits and returns `{:status :truncated ...}` on
exhaustion. Capability tokens, mutable state, and resource handles are never
datafied.

```clojure
;; Proposed `perturb.repl` API; no current Jolt/jolt-sim facade exists.
(let [view (perturb.repl/datafy scenario)]
  {:runs  (perturb.repl/nav view :runs (:runs view))
   :trace (perturb.repl/nav view :trace (:trace view))
   :query (perturb.repl/nav view :query (:query view))})
;; `perturb.repl/nav` implements Navigable's (nav coll key value) contract.
;; :query is a descriptor; submission is separate and bounded.
```

Useful navigation edges include scenario → explored domains/sampled runs;
trace → event/layer/resource/contract edge; cell → schemas/effects/callers/
callees/known controls; proof query → encoding/solver/semantic controls; and
failure → shrunk witness plus its controlled replay result. Caller/callee,
proof-control, and solver results are either already captured facts or the
result of a separately submitted bounded query. Navigation over a live route log
retains that log's live-evidence status; it cannot manufacture a canonical replay
trace.

The `core.async.flow` guide's static `datafy` view and separate dynamic process
inspection are the right distinction: static flow/contract data and dynamic run
state must not be conflated. The current `jolt-sim` process explorer has no
live-process ping surface; it supervises an opaque child to completion/deadline
and retains bounded artifacts. A future supervisor telemetry protocol would need
bounded IPC/file snapshots, deadlines, reaping, redaction, capability rules, and
explicit lifecycle ownership; REPL navigation must never retain or query a live,
poisoned, or hung child directly.

## Practical first slice

1. Choose one operation boundary and write its contract, pure step function,
   independent oracle, and known-bad mutation.
2. Run the declared finite operation/value/history domain; record expected
   cardinality, equality-confirmed uniqueness, full consumption, the decidable
   property result for every member, and the exact bound. Only then call the
   result `bounded-complete` for that fragment.
3. Add a stateful generator for the nonfinite remainder; retain its seed and
   shrunk witness as `sampled` evidence.
4. Run unchanged code against a hermetic handler pack; require all declared
   routes and terminal probes to be observed.
5. Run the same scenario in `:observe` or `:hybrid` mode only where a normalized
   parity relation is written down; neither mode is hermetic.
6. Promote a repeatedly stable residual upward only when a narrower static rule
   or proof model can actually discharge it.

For a persistent value target, public extensional behavior, index arithmetic,
and physical sharing are three separate claims. For a connection, stable handle
identity, protocol state, and discharge obligation are three separate facts.

## Nonclaims

This architecture does not make monitoring proof, make sampling exhaustive,
make a modeled world evidence of host behavior, establish fairness/liveness,
hardware memory-order behavior, true OS-level parallelism, or an external
contract truthful. It does not promise a static solution for runtime-sized
collections or shared CAS arbitration. A replay on one target/version does not
establish fidelity across host targets, Jolt/Chez versions, or native-library
versions. Its value is that these limits become explicit inputs to simulation,
testing, trace validation, and future formalization rather than hidden gaps.
