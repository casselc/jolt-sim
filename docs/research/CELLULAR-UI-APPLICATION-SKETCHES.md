# Cellular UI architecture: bbf1 and a1s sketches

**Status:** design sketches, not migration plans or implementation authority.
They apply the proposed cellular/event and progressive-assurance architecture to
`bbf1` and `a1s` while deliberately ignoring their present UI, language, and
library choices. The point is to identify a principled application shape for the
proposed system, not to prescribe a rewrite of either current repository.

The common principle is:

> Application code remains ordinary eager code. Contracts exist at state,
> routing, effect, authority, and rendering boundaries so a model, simulator,
> test generator, monitor, or REPL can reason about the same application.

## 1. Framework shape

### 1.1 One event loop, explicit boundaries

```text
input / effect response
        ↓
workflow-owned dispatch
        ↓
ordinary cell transition
        ↓
new immutable state + declarative effect requests
        ↓
pure view tree
        ↓
TUI, GUI, or headless renderer
```

The root application owns route, modal/overlay stack, focus, UI session state,
and outstanding effect identities. It does **not** give arbitrary callbacks
ambient authority to mutate global application state.

A cell has a bounded contract:

```clojure
(defcell :resource/accept-page
  {:input   page-message-schema
   :output  {:accepted resource-state-schema
             :stale    resource-state-schema}
   :effects {}
   :purity  :candidate-pure}
  [ctx state message]
  ;; Ordinary code: return state/data and optional declarative requests.
  ...)
```

The proposed workflow declaration names dispatch predicates asserted to be
deterministic and effect-confined for a stated model, plus output schemas for
every selected label. A future runner may enforce exactly one declared label,
reject undeclared labels, and validate selected-label output shape. Those checks
establish declared routing and shape only; predicate determinism, effect
confinement, and handler semantics require an audited or monitored coverage
boundary.

### 1.2 Effects and authority

Effect requests are data, correlated by a runner-issued effect id:

```clojure
{:effect-id 42
 :operation :cloud/list-resources
 :request {:type "..." :page-token "..."}}
```

The runner binds that id to a non-serializable, runner-owned capability and
supplies it only inside an enforced handler boundary. The response must carry
the effect id, validate against the operation response contract, and arrive while
the request is outstanding. Retries, cancellation, timeouts, duplicate
responses, and late responses have no implied meaning; they must be declared as
state-machine cases.

An ARN, archive URL, row id, resource id, or scope name is **correlation data**,
not authority. Capability state remains outside cell input, output, state, trace,
export, and effect-request data; handler admission validates issuer/flow scope,
lifecycle generation, target origin, and operation. Until that boundary exists
and rejects unknown, retired, copied, and cross-flow capabilities,
resource-bearing cells are opaque rather than safe by declaration.

### 1.3 Views are pure projections

```clojure
(defview resource-list-view [state ui]
  [:screen
   [:header {:title (:title ui)}]
   [:table {:rows (project-rows state ui)
            :cursor (:cursor ui)
            :selection (:selection ui)}]
   [:overlay (overlay-view state ui)]
   [:footer (help-view state ui)]])
```

A proposed framework could define TUI, GUI, and headless renderers over a
retained view-tree contract. Terminal cell layout/ANSI output, native GUI
widgets/accessibility, and headless scene snapshots would be backend concerns. A
view-purity rule can make projection tests meaningful only after enforcement or
an audit/monitor establishes that rendering neither performs effects nor forces
opaque/deferred work. Native platform behavior remains runtime/observed evidence,
not a property of the abstract tree.

### 1.4 REPL evidence graph

A future `perturb.repl` surface must expose only captured, immutable, versioned
evidence; its `datafy`/`nav` implementation must be tested to avoid forcing
deferred work, invoking effects/FFI, running solvers, launching processes,
dispatching through live objects, or exposing capability tokens. Traversal must
enforce declared depth, node, and byte limits and report truncation. It is not
query execution:

```clojure
(let [view (perturb.repl/datafy run)]
  {:trace (perturb.repl/nav view :trace (:trace view))
   :cells (perturb.repl/nav view :cells (:cells view))
   :query (perturb.repl/nav view :query (:query view))})
```

Navigation returns redacted snapshots, references, or bounded-query descriptors.
It cannot force lazy work, invoke effects, run a solver, launch a process, or
expose capability tokens. Scenario → run → trace → event → contract/cell is the
primary path; cells expose schemas, declared effects, captured callers/callees,
controls, and witnesses. A live route log remains live evidence rather than
becoming a canonical replay trace.

## 2. bbf1 in this system

### 2.1 Application shape

`bbf1` is an archive-driven replay application with pure event reduction; it
does not currently retain an event log or canonical execution-replay trace. A
target-system version could have this shape:

```text
archive/live bytes
  → decode and normalization cells
  → canonical timing events
  → timeline/replay cells
  → immutable race model + UI state
  → board/panel view tree
  → terminal or GUI renderer
```

Candidate cells:

| Cell | Input | Output | Effect boundary |
| --- | --- | --- | --- |
| session load | selected session/configuration | pending, replay-ready, or error state | archive/HTTP fetch |
| feed normalize | raw payload or already canonical event | typed canonical event | none once bytes arrive |
| timeline build | event collection | immutable indexed timeline | none |
| frame advance | timing frame plus replay state | next reduced model, stale/no-op, rearm request | current real-time command scheduler; virtual-clock control requires a new adapter |
| seek plan | target plus immutable timeline/checkpoints | plan or refusal | none |
| seek chunk | plan/work state | progress or completed model | continuation scheduling |
| live batch | ordered ingress plus logical time | due timing frame or retained queue | future live transport |
| projections | reduced model plus UI selection | driver, telemetry, marker, panel data | none |
| UI shell | key/resize/input event plus UI state | route/overlay/focus transition | terminal input/output |

The core reducers, seek planner, timeline indexes, projections, and cache policy
remain normal code. They do not need conversion into a universal graph language.
They are candidate pure boundaries: direct examples, differential tests, property
generation, or local bounded models require an audit/enforcement boundary for
purity and canonicalized inputs.

### 2.2 Effects and simulations

A future replay world may supply archive, clock, continuation, UUID/tag, and
input adapters. Today canonical timing-frame data is shared by pure reducers and
the production replay transition, but archive HTTP/parsing, real-time scheduling,
UUID generation, and Charm input execution are not recorded or injectable as one
controlled application run.

Useful contracts include exact-set session load completion, stale-generation
drop, frame tag validity, seek's no-partial-visible-state rule, ordered live
queue drain, and replay teardown. Current rendering is a pure-ish ANSI/string
projection with memo/cache replacement data followed by terminal fitting;
terminal behavior requires separate VHS/Bombadil observations. A backend-neutral
headless scene tree is future work and cannot establish GUI or terminal behavior.

### 2.3 Development story

1. Establish canonical timing event and replay-state schemas without replacing
   reducers.
2. Add a headless scenario runner around existing load/frame/seek functions.
3. Make the virtual clock, input script, archive source, and continuation order
   explicit scenario choices.
4. Audit candidate-pure inputs, then enumerate only declared finite load,
   stale-result, frame-tag, and seek-plan domains. A bounded-complete claim also
   requires finite initial states/histories, state identity, successor relation,
   queue/schedule and environment choices, termination/cut-off rule, expected
   cardinality, equality-confirmed uniqueness, full consumption, and stated
   fairness/progress assumptions. Otherwise property-test mixed timelines against
   the one-event/full-prefix oracle as sampled evidence.
5. Add a pure trace monitor for load/seek/teardown obligations and retain shrunk
   failures as replay fixtures.
6. Treat terminal rendering and eventual live transport as observed/hybrid lanes,
   never as consequences of the pure replay proof/model.

`bbf1` is the first application because its archive domain offers deterministic
inputs and independent semantic oracles. A successful run is
`bounded-complete` only when it satisfies the complete finite-domain,
state-identity, transition, schedule/environment, termination, cardinality,
uniqueness, and full-consumption conditions in step 4. Otherwise it is
`sampled` or `simulated` evidence with recorded assumptions.

## 3. a1s in this system

### 3.1 Application shape

`a1s` becomes an authority-bearing operator workflow application:

```text
operator intent
  → UI/domain event
  → route and state transition
  → declared cloud/editor/process request
  → correlated response event
  → state, notification, and view update
```

Candidate cells:

| Cell | Input | Output | Effect boundary |
| --- | --- | --- | --- |
| scope activation | selected credential/scope/type | active target or challenge | credential/client resolution |
| browse projection | raw resource pages and view settings | rows, columns, selection | none |
| stale acceptance | stamped result and current target | accepted/drop result | none |
| detail/page load | scope/type/ref request | page/detail result | cloud read |
| edit/confirm plan | desired state and current state | diff/confirmation/refusal | editor/filesystem, cloud read |
| mutation lifecycle | confirmed plan and target | started, waiting, terminal result | cloud write and polling |
| stream/watch | scope/window/cursor | event page/badge/cursor state | cloud logs/events/metrics |
| service action | typed action and resource ref | domain response or handoff state | service-specific cloud/process action |
| UI shell | key/resize/text input | route/overlay/focus transition | terminal input/output |

The current central update ordering remains workflow-owned routing. Stale
discriminators—generation, scope/type, origin reference, pane generation, editor
path, or external session id—become first-class response correlation rules, not
incidental fields on ad hoc callbacks.

### 3.2 Authority and operator safety

The proposed authority capability is runner/flow-issued, non-serializable state
held outside state, exports, traces, screenshots, and effect data. Handler
admission rejects unknown, retired, copied, and cross-flow capabilities and
validates issuer/flow scope, lifecycle generation, target origin, and requested
operation. Displayed account/region and resource identifiers remain correlators
only.

Operator controls are separate facts:

- read-only mode, confirmation, and accent are guardrails, not IAM proof;
- IAM/service policy and cloud semantics are opaque runtime premises;
- secret reveal has redaction/non-persistence obligations;
- editor, terminal-session, and external tool handoff are opaque/process-isolated
  boundaries;
- SQS receive, cloud writes, publish, invoke, scan, redrive, and session start
  have individually declared effect semantics rather than a generic “read” or
  “write” label.

### 3.3 Development story

1. Begin with pure stale-result acceptance and browse projection: finite message
   histories, explicit generation identity, known stale-mutation control.
2. Add contracts to one mutation lifecycle: confirmation → initial request →
   waiting → terminal result → refresh/reopen. Keep the production command loop.
3. Extend the existing HTTP-level test world into a controlled cloud-handler
   world that records request/response correlation, target origin, allowed
   operation, fault plan, and oracle result. Its current integration evidence is
   limited to mock call logs and final-model identifier-set agreement with the
   generator oracle.
4. Add generated permutations of valid async result delivery and require the
   same final model where order-independence is declared.
5. Add redacted route evidence and a narrow disposable-resource parity suite for
   one consequential service flow at a time.
6. Leave editor, plugin, session handoff, real cloud timing, and credential
   behavior explicitly runtime/opaque unless their own process/target tests
   establish a narrower claim.

## 4. Shared assurance story

| Stage | bbf1 | a1s |
| --- | --- | --- |
| Pure kernel | event reduction, seek plans, projections | stale guards, selection, scope and mutation plans |
| Controlled world | archive/clock/input/continuation model | HTTP cloud test world and fault plans |
| Bounded exploration | finite replay/load/seek histories | finite result/confirmation/stale histories |
| Sampled testing | generated timelines and scripted UI input | generated resources, messages, and delivery permutations |
| Runtime evidence | terminal rendering, later live transport | live cloud, editor/process/session behavior |
| Corpus | shrunk timing/event witnesses | redacted request/result and test-world witnesses |

The framework should emerge from these two applications in that order: first the
deterministic replay kernel and a future headless view-tree contract; then
authority-aware effect contracts and controlled cloud worlds. It should not begin
as a general UI DSL, actor runtime, effect system, or proof language.

## Nonclaims

These sketches do not say that current bbf1 or a1s code can run unchanged on
Perturb, that schemas prove business behavior, that cells confine effects by
declaration, that a finite UI graph yields finite execution, or that simulated
cloud/terminal behavior proves host behavior. They identify boundaries where a
future implementation can add evidence incrementally while retaining ordinary
application code.
