# Cordis Cells in OCaml on bhyve

Oct 9, 2026 · @Chuck

An OCaml rendering of the Cordis synthesis: a mycelium- and core.async.flow-shaped task graph whose cells run agent-written code in a small capability-only interpreter, hosted in bhyve guests on FreeBSD so the same revert discipline runs from a single cell up to a whole VM. The companion doc, Cordis Synthesis: Components, Effects, Coeffects, holds the vocabulary this one assumes.

## At a glance

*(Diagram: levels · host, jail, VM, runtime, cell, and the single path out. The diagram was interactive in the source document and is not reproduced here.)*

Five nested levels run the same Cordis lifecycle at different grains; each level's inverse is the fallback when the level inside it cannot revert, and nothing leaves the host except through the outbox.

## Why OCaml and OxCaml

OCaml fits because the runtime kernel is mostly pure decisions over variants, and OxCaml's modes can check at compile time the confinement that the Clojure projects check by convention. The trusted core is written in OCaml; the code the agent writes runs in an interpreter that core owns.

- **What OCaml buys.** Exhaustive matches over lifecycle states, GADT witnesses for port types, Eio's capability-passing IO, and a `Switch` whose release hooks already run LIFO, which is a Cordis accumulator for one lifetime.
- **What OxCaml adds.** `local` stops a cell from storing or returning a capability; `unique` makes handles single-owner; `once` makes an inverse callable at most once; `portable` and `stateless` stop a cell body closing over ambient mutable state. These are the static form of the confinement the Cordis calculus assumes.
- **Why perturb's rejection does not carry over.** perturb turned OxCaml down as the substrate of a *new language* (PERTURB-DESIGN §1.1): it lacks multi-shot handlers and perturb wanted its own checker over its own IR. Here OxCaml hosts the harness, not the language. Cordis's effect iterator is one-shot by design, and the cell language gets its own small checker anyway.
- **The catch.** OxCaml officially supports only Linux and Apple Silicon macOS. The kernel should still build on mainline OCaml 5.4 with modes as additive annotations; a FreeBSD port is feasible but is roughly 7 to 14 person-weeks (see OxCaml on FreeBSD).

## The cell language (superseded by CEL cells below)

Cells run in a purpose-built interpreter, not native code, because it is the only option that gives true unload, per-cell confinement and determinism at once. Native `Dynlink` cannot unload code, so every hot-replaced native cell would leak its old version forever; an interpreted cell's old code is just garbage.

Shape, borrowing from SCI, writ and lua-ml:

- **Surface.** EDN-style s-expressions, so agents write the same Lisp they write for samizdat, and manifests and cells share one reader.
- **Core.** A small lowered AST in the style of `writ.lower`: literal, ref, if, do, let, loop and recur, fn, case, invoke. Nothing else reaches the evaluator.
- **Environment = coeffects.** A cell's environment is built only from the keys it declared. No ambient `print`, clock, file or socket exists; each arrives as a capability record its provider installed. An undeclared name is an unbound symbol, so confinement is by construction, not by checking.
- **Effects are primitives that return inverses.** Each effectful primitive in the table returns its inverse alongside its result; the evaluator pushes the inverse onto the cell's accumulator. Primitives are tagged internal, acquisition or emission, and emission primitives only ever produce an intent.
- **Fuel.** A step and allocation allowance, counted deterministically (writ's `writ.work`). A run halts at the same instruction on every machine, which replaces the preemption OCaml cannot give (the Riot post-mortem) and makes replay exact.
- **Admission check.** Before a cell loads, a pass over its AST computes what it can reach, writ-style `calls` and `flow` contracts. A cell that names a primitive outside its declared keys is refused at load, not at first call.
- **Escape hatch.** Trusted extensions may be native OCaml modules linked at build time and never reloaded. Owi, a pure-OCaml Wasm interpreter with imports-only authority, is the fallback if a cell needs a second language.

## Cells in CEL

Use CEL for most cell bodies and every guard, route and policy, and Wasm components for the cells that need general computation. That retires the custom interpreter above: what remains to build is a CEL evaluator, which has a published spec and conformance suite, and the graph checker.

CEL (Common Expression Language) fits because its limits are the ones the design wanted anyway:

- **Pure and bounded.** No side effects, no unbounded loops, evaluation in linear time. A cell is the pure handler shape `(state, input) -> (state', output, intents)`, and effects exist only as intent values it returns.
- **Cost known before it runs.** CEL can estimate an expression's worst-case cost statically, and the estimate tightens when schemas bound `maxItems`, `maxLength` and `maxProperties`. Kubernetes rejects a rule estimated above 10 million cost units at admission. Admission does the same here, and fuel survives only as a runtime backstop.
- **The environment is the capability row.** An expression sees only the variables and functions declared for it, so confinement comes from the language.
- **Typed against schemas.** The checker types expressions against declared variable types, including protobuf messages natively, and Kubernetes types it against OpenAPI schemas.
- **Known to models.** It is used in Kubernetes admission policy, Envoy, IAM conditions and protovalidate.

The three tiers:

| Tier | Used for | Bound | Confinement |
| --- | --- | --- | --- |
| ML graph language | Wiring, routes, signatures, functors | Typechecked at admission | Capability and effect rows |
| CEL | Most cell bodies; guards, invariants, policies, schema rules | Static cost estimate at admission; runtime cost limit | Declared variables and functions only |
| Wasm component | Cells that need loops, recursion, heavy parsing or string work | Fuel | WIT imports only; every import goes through the trap |

A CEL cell, with the ML signature around it:

```ocaml
cell plan_review
  : { diff : Diff.t; state : { attempts : int } }
    -> { state : { attempts : int }; intents : Intent.t list }
  requires { llm : Llm.complete }            (* the one intent kind it may emit *)
= cel {|
    {
      "state":   {"attempts": state.attempts + 1},
      "intents": state.attempts >= 3 ? [] : [
        llm.complete({"prompt": "Review this patch:\n" + diff.patch,
                      "max_tokens": 4000})
      ]
    }
  |}
```

`llm.complete` is a host function that only builds an intent value; nothing leaves until the trap checks the frame and the outbox releases it. The receipt comes back as input to the next cell the graph routes to.

What CEL does not cover, and where it goes instead:

- **A multi-step decision inside one cell** (call, inspect, decide, call again). This becomes several cells wired by the graph, which is mycelium's own rule.
- **Reuse.** Core CEL has no user-defined functions. Use the `cel.bind` extension for local names, and the function libraries in Extending CEL below for sharing.
- **Parsing model output, regex extraction, templating.** These become host functions that return optional values, so a parse failure is a value the cell must handle. Each one is trusted code, so keep the list short and reviewed.
- **Loops, recursion, search, heavy string work.** Tier 3, a Wasm component on fuel.

Building it:

- **No OCaml implementation exists.** Write one against cel-spec and its conformance tests, and test it side by side against cel-go. The alternatives are binding cel-cpp or running cel-rust compiled to Wasm.
- **Fix one determinism hole.** Iteration order over a map is implementation-defined; ours sorts keys.
- **Regular expressions are RE2.** CEL's `matches` is specified as RE2, and OCaml's regex libraries differ at the edges. Use an RE2-compatible engine or restrict the supported syntax and test it.
- **Extensions to include:** strings, math, optional types and `cel.bind`. Leave out anything that reads the clock or randomness; both come in as inputs.

### Extending CEL

CEL can take `fold`, user-defined functions and a variant `match` without losing its static cost bound, provided every extension is either a macro that expands to CEL's existing comprehension, or a function the checker can see the whole body of. Nothing adds new evaluator behaviour, so the conformance suite still covers the core and the cost estimator still works.

The rule: **extend by expansion, never by semantics.**

| Extension | How it works | Cost bound |
| --- | --- | --- |
| `fold` | `xs.fold(acc, init, x, step)` expands to a CEL comprehension: iterate `x` over `xs`, start `acc` at `init`, replace `acc` with `step` each pass. CEL's comprehension node already has exactly these parts, so this is a macro, not a new construct | Bounded by `size(xs)` times the cost of `step`. Watch accumulator growth: `acc + acc` doubles each pass, so values carry size limits and the runtime cost limit stays on |
| `fold_while` | The same with CEL's loop condition, for early exit | Same |
| Two-variable comprehensions | Index and value over lists, key and value over maps; cel-go added these in October 2024 as an optional extension | Same as existing macros |
| Function libraries | `fn` definitions in versioned library files outside any cell. Each has a typed signature and a CEL body, and cells import them by name in their `requires`. Libraries are content-hashed and pinned like schema imports | The call graph must be acyclic, checked at admission. A function's cost is its body's cost, so the estimate composes |
| Pure cells as functions | A cell that returns no intents can be imported into another cell as a function | Same as a library function |
| Variant `match` | `r.match(Pass(x) -> …, Fail(x) -> …)` expands to tag tests and ternaries; our checker requires every variant of the type to be handled | No loops, so trivial |

A function library and its use:

```ocaml
library review_fns ~pin:"sha256:…" = {|
  fn severity(f: Finding) -> int =
    f.kind == "security" ? 3 : f.kind == "bug" ? 2 : 1

  fn worst(fs: list<Finding>) -> int =
    fs.fold(m, 0, f, severity(f) > m ? severity(f) : m)
|}

cell gate
  : { findings : Finding list } -> [ Block of int | Pass ]
  uses { review_fns }
= cel {| worst(findings) >= 3 ? Block(worst(findings)) : Pass |}
```

The line not to cross is recursion. A recursive function, or a loop whose length is not a value already in hand, gives up the static cost bound; that is the step DataWeave took with `fun` plus tail recursion. Anything that needs it belongs in a Wasm cell on fuel.

### Prior art: MuleSoft flows and DataWeave

MuleSoft is the closest commercial precedent for this whole layer: a graph of flows wired from connector operations, with a dedicated transformation language in the nodes. Nearly every piece maps across; the differences are where this design is stricter.

| MuleSoft | This design | Difference |
| --- | --- | --- |
| Flow, subflow | Graph, functor subgraph | Ours is typechecked as a whole before it loads |
| Transform Message, written in DataWeave | CEL cell | CEL keeps a static cost bound; DataWeave is Turing-complete |
| DataWeave modules: `.dwl` files of `fun`, `var` and `type`, imported by name | CEL function libraries | Ours are content-hashed and pinned, and may not recurse |
| DataWeave `reduce` | `fold` macro | Same idea, expanded to a CEL comprehension |
| Connector operation | Intent kind, generated from an OpenAPI operation | Ours never performs the call; it produces an intent the outbox releases |
| Generating flows from an API spec | OpenAPI and AsyncAPI import | Ours also derives retry rules, credentials and egress from the spec |
| Type metadata carried through a flow | Schema chain as row-type inference | Ours fails at admission rather than warning in the editor |
| Choice router | `route` with an exhaustive match | A new outcome is a type error until routed |
| Scatter-Gather | Join | Same |
| For Each, Batch | `fold` inside a cell, or a bounded fan-out process | Same |
| Retry scopes and redelivery | Retry only for idempotent intent kinds | Mule retries blindly; non-idempotent intents here must reconcile |
| Error handlers that continue or propagate | Routes on `Failed` and `Uncertain` receipts | Uncertainty is its own outcome, not an error |
| Transaction scope | Outbox commit point | Ours can roll back held emissions; Mule's reach stops at transactional resources |

What to copy:

- **Format readers and writers as host functions.** Much of DataWeave's power is parsing and writing JSON, XML and CSV by declared media type. Give CEL the same, returning optional values.
- **Modules as the reuse unit.** DataWeave keeps reusable logic in modules that hold only definitions. That is the shape for CEL libraries.
- **Specs as the starting point.** Starting a flow from an API spec is what keeps integrations honest; the importers do this.

What to avoid:

- **Turing-completeness in the transform language.** Once DataWeave had `fun` and recursion, a transform's cost could no longer be known before it ran.
- **Blind retries of non-idempotent operations.**

The MuleSoft feature names above are from general knowledge. The DataWeave points are checked against MuleSoft's own docs: [reduce](https://docs.mulesoft.com/dataweave/2.4/dw-core-functions-reduce), [custom modules](https://docs.mulesoft.com/dataweave/2.4/dataweave-create-module) and [functions](https://docs.mulesoft.com/dataweave/2.3/dw-functions). The CEL extension points are checked against [cel-go two-variable comprehensions](https://redirect.github.com/google/cel-go/pull/1034) and the [cel-java comprehension model](https://javadoc.io/static/dev.cel/compiler/0.9.0/dev/cel/expr/Expr.Comprehension.html).

## Four structural upgrades

Four borrowed ideas change the design rather than decorate it. Together they make agent edits safe by construction, make replay and soak cheap, and give emissions a real path back.

### 1. Content-addressed definitions (Unison, Dhall)

Every cell, function library, schema and graph is identified by the hash of its normalised syntax tree; names are a separate table pointing at hashes, stored in Irmin.

- **Pins stop being a feature.** An import *is* a hash.
- **Edits cannot break bystanders.** A revision writes new hashes and moves names. Code that does not reference the new hash cannot change.
- **Versions coexist.** Two versions of a library are two hashes, so there are no diamond conflicts.
- **Anti-weakening is a comparison** of the signatures at the old and new hash.
- **Cache and replay keys come free**, which is what the next upgrade needs.
- **Cordis's key collision goes away.** Component and key identity is the hash, not a name (§6.6).

### 2. Assets with incremental caching (Dagster; Salsa; Build Systems à la Carte)

A graph declares *assets*, the named things that should exist, and which cell materialises each. The runtime memoises each materialisation by (cell hash, input hashes).

- **Pure cells are trivially cacheable.** A CEL cell with the same hash and inputs returns the same output.
- **Emission cells are cached by intent hash.** In simulation that cache is the recording cassette; in production it is a receipt cache that makes replay idempotent.
- **Freshness is a reactive dependency.** An asset declares when it goes stale, on any upstream change or after an age, and a stale asset re-targets the fibers that read it.
- **Soaks get cheaper.** A mutation's soak recomputes only cells whose hash or inputs changed.

### 3. Transactional turns and pipelining (Spritely Goblins, E)

Each fiber step is a *turn*: it runs against a snapshot of its declared keys, then commits its writes and intents together, or not at all.

- **State needs no inverses.** A failed or diverted turn is discarded, so Cordis's per-step inverses are only needed for acquisitions and emissions, not for ordinary state.
- **Receipts can be pipelined.** An intent may refer to the future receipt of an earlier one, as in `billing.email(r1.id)` after `r1 = billing.create(…)`. The outbox resolves the chain in order behind one commit point, which cuts checkpoints per round trip.
- **Uncertainty propagates.** If `r1` comes back `Uncertain`, everything pipelined on it is held until reconcile.

### 4. Compensation per intent kind (BPMN compensation, sagas)

Each intent kind may declare a compensating intent. When a component whose emissions were already released is unloaded, the outbox issues their compensations last-in-first-out, each one an ordinary intent with its own key and receipt.

```ocaml
intent create_invoice = Billing.createInvoice
  idempotent_by (fun o -> o.order_id)
  compensate    (fun r -> Billing.voidInvoice { id = r.id })

intent send_email = Mail.send
  irreversible                       (* graph policy may require approval *)
```

- **Coarser than an inverse.** Compensation restores state only up to an equivalence the application chooses (§6.1), so it carries its own evidence grade, `:compensated`, and never reads as reverted.
- **Irreversible kinds are marked.** A graph policy can require human approval before any `irreversible` intent is released.

## Further prior art

Smaller borrowings, each with where it lands. These are from general knowledge, not checked this session, except CUE and Quint, which have their own sections.

| Prior art | Crib | Lands in |
| --- | --- | --- |
| Temporal, Restate | Validation of cells plus journal plus intents as durable execution. Restate's virtual objects: keyed state with one writer per key | Coeffect keys that need no commutativity proof because writes are serialised |
| CRDTs (Automerge and others) | Value types whose operations commute by construction | The standard library for shared keys; the Cordis commutativity witness comes from the type |
| Hazel | Typed holes: an incomplete program still has a type and a meaning | Agents submit graphs with holes; the checker reports each hole's type; the runtime marks them pending |
| Cedar | Authorisation policy typed against a schema and built for automated analysis | Capability policy: which cell may emit which intent kind, under what limits, with "does this change widen access?" answered before it ships |
| Koka, Unison | Effect rows written in the function arrow | The revised syntax |
| Gleam | `use` for callback-shaped code | Waiting on receipts, in the revised syntax |
| DataWeave `update`, lenses, JSONata paths | Deep updates by path | A `with` macro in CEL |
| re-frame | Effects and coeffects as data, under exactly those names | Naming and documentation; the cell shape is already this |
| Nix, Bazel | Hermetic, content-addressed build steps | Wasm cell builds: a component's hash covers its toolchain and inputs |
| Marimo, Observable | Reactive notebooks with dependencies found statically | The operator UI: a graph rendered as live cells that re-run when inputs change |
| CNCF Serverless Workflow | Workflows with expression languages and OpenAPI or AsyncAPI function definitions | An export format if graphs ever leave this system |
| P language (AWS) | State machines written to be model-checked | Same role as Quint below; pick one, and Quint already has tooling here |

### CNCF Serverless Workflow in detail

The spec, now renamed the Open Workflow Specification, is good prior art and a reasonable lossy export format, but not something to import graphs from. It covers the same ground as the graph language: OpenAPI and AsyncAPI calls, data flow between tasks, RFC 7807 errors, retries and lifecycle events. It has no Uncertain outcome, no compensation, and a Turing-complete expression language.

Status:

- **Version.** CNCF sandbox since 2020. DSL 1.0.0 shipped around January 2025; current examples use `dsl: '1.0.3'`. serverlessworkflow.io now redirects to open-workflow-specification.org.
- **Runtimes.** Quarkus Flow 1.0.0 (August 2026) is the first stable embedded engine on the 1.0 DSL, with preview LangChain4j agent and OpenTelemetry modules. Others listed: Synapse (.NET), Lemline, Apache EventMesh Workflow, Zigflow. Apache KIE SonataFlow is listed too, though it may still be on the older 0.8 DSL.
- **SDKs.** .NET, Go, Java, PHP, Python, Rust and TypeScript.

The DSL:

- **Document shape.** `document` (name, namespace, version), `input`, `output`, `schedule`, `timeout`, `use` and `do`.
- **`use`** declares reusable authentications (bearer, OAuth2, OIDC, secret-backed), function catalogs referenced as `name:version@catalog`, errors, extensions, functions, retries, secrets and timeouts.
- **Twelve task types.** `call` (http, grpc, openapi, asyncapi, or a catalog function), `do`, `for`, `fork` (with `compete` for a race), `emit` (a CloudEvent), `listen` (one, any or all, with correlation), `raise`, `run` (container, script, shell, workflow), `set`, `switch`, `try`/`catch`, `wait`.
- **Data flow.** `input.from`, then the task, then `output.as`, then `export.as` into a shared `$context`, each stage with an optional JSON Schema. Expressions are jq inside `${ }`.
- **Errors and retries.** Errors are RFC 7807 problem details with standard types: communication, validation, expression, authorization, timeout. `catch` filters by error fields and can retry with constant, linear or exponential backoff, attempt and duration limits, and jitter.
- **Lifecycle.** Phases pending, running, waiting, suspended, cancelled, faulted, completed, each announced as a CloudEvent.
- **Extensions.** `extend` runs tasks before or after any task type. These are hooks, not policy: an extension cannot deny an action.

How it maps:

| Serverless Workflow | This design | Fidelity |
| --- | --- | --- |
| `call: openapi` by `operationId` | Intent kind | Clean |
| `call: asyncapi`, `emit`, `listen` | AsyncAPI ports | Clean |
| `try`/`catch` with `errors.with`, `retry` | Routing on receipts; retry policy | Clean for Failed; Uncertain has no equivalent |
| `fork`, `compete: true` | Join, race join | Clean |
| `switch` | Exhaustive `route` | Theirs is not checked for exhaustiveness |
| `set`, `input.from`, `output.as`, `export.as` | A CEL cell with path updates | jq has no cost bound; export needs CEL translated to jq, or a declared expression language |
| `use.extensions` | Where interception hooks sit | Hooks cannot deny; Cedar stays outside |
| `use.authentications`, `secrets` | Capability bindings held by the outbox | Clean |
| `use.catalogs` `name:version@catalog` | Content-addressed libraries | Versioned by name, not by hash |
| Lifecycle phases and CloudEvents | Fiber observability | Close; no Uncertain or Unloading phase |
| `then:` jump to any task | Routes | Theirs allows unrestricted backward jumps |
| Nothing | Compensation, outbox commit points, Uncertain | Missing; 1.0 dropped 0.8's `compensatedBy` |

What to crib:

- **The four-stage data flow** with a schema at each stage.
- **RFC 7807 problem details** as the shape of `Failed` receipts.
- **The standard error types.**
- **Lifecycle phases published as CloudEvents.**
- **Function catalogs** as the distribution shape for intent kinds.

The review loop in DSL 1.0.3, as an export would produce it. The structure is checked against the spec's examples; the file has not been run through the schema or a runtime:

```yaml
document: { dsl: '1.0.3', namespace: harness, name: review-loop, version: '0.1.0' }
use:
  secrets: [ llmApiKey ]
  authentications:
    llm: { bearer: { use: llmApiKey } }
  retries:
    llmTransient:
      delay: { seconds: 2 }
      backoff: { exponential: {} }
      limit: { attempt: { count: 3 } }
do:
  - author:
      call: openapi
      with:
        document: { endpoint: https://llm.internal/openapi.json }
        operationId: createMessage
        parameters: { role: author, feedback: '${ $context.feedback // null }' }
        authentication: { use: llm }
      export:
        as: '${ $context + { draft: .content, round: (($context.round // 0) + 1) } }'
  - critique:
      try:
        - critic:
            call: openapi
            with:
              document: { endpoint: https://llm.internal/openapi.json }
              operationId: createMessage
              parameters: { role: critic, draft: '${ $context.draft }' }
              authentication: { use: llm }
            output: { as: '${ { verdict: .verdict, critique: .content } }' }
      catch:
        errors:
          with: { type: https://open-workflow-specification.org/spec/1.0.0/errors/communication }
        retry: llmTransient
  - gate:
      switch:
        - accepted:  { when: '${ .verdict == "accept" }', then: publish }
        - exhausted: { when: '${ $context.round >= 3 }', then: escalate }
        - revise:    { then: storeFeedback }
  - storeFeedback:
      set: { status: revising }
      export: { as: '${ $context + { feedback: .critique } }' }
      then: author
  - publish:
      emit:
        event:
          with: { source: https://harness.local/review-loop, type: harness.review.accepted.v1 }
      then: end
  - escalate:
      raise:
        error: { type: https://harness.local/errors/review-exhausted, status: 409, title: Review loop exhausted }
```

What the export loses:

- **Uncertain.** It becomes a custom error type, `harness.local/errors/uncertain`, plus a reconcile task no runtime understands.
- **Compensation and commit points.** These go into an extension namespace.
- **Cost bounds.** jq is Turing-complete.
- **Types.** Only JSON Schema at the boundaries survives.

Sources: [Open Workflow Specification](https://open-workflow-specification.org/), [CNCF project page](https://www.cncf.io/projects/open-workflow-specification/), [dsl.md](https://raw.githubusercontent.com/serverlessworkflow/specification/main/dsl.md), [rename discussion](https://github.com/serverlessworkflow/specification/issues/1127), [Quarkus Flow 1.0.0](https://quarkus.io/blog/quarkus-flow-1-0-0-released/).

## The task-graph DSL

A graph is EDN data with two layers: mycelium's per-invocation workflow (cells, dispatches, schema chain) inside core.async.flow's long-lived processes (ports, links, lifecycle). Every cell declares the Cordis triple directly, so the loader can compute providers, precedence and confinement before anything runs.

A cell:

```clojure
(defcell :review/critic
  {:requires  {:llm   [:cap/llm   {:max-tokens 4000}]   ; d, with interception metadata
               :repo  [:cap/repo  {:mode :read}]}
   :provides  #{}                                       ; p: keys it may install
   :in        {:diff   [:map {:closed true} [:path :string] [:patch :string]]}
   :out       {:review [:map {:closed true} [:verdict [:enum :pass :fail]] [:notes :string]]}
   :effects   {:llm/complete :emit}                     ; boundary class per primitive
   :fuel      200000}
  (fn [{:keys [llm repo]} {:keys [diff]}]
    (let [ctx  (repo/read repo (:path diff))
          resp (llm/complete llm {:prompt [ctx (:patch diff)]})]  ; returns an intent
      {:review (parse-review resp)})))
```

A graph:

```clojure
{:graph/id   :review-loop
 :processes  {:author  {:cell :code/author}
              :critic  {:cell :review/critic}
              :gate    {:cell :review/gate}}
 :links      [[:author :diff   :critic :diff   {:buffer 4 :regime :normal}]
              [:critic :review :gate   :review {:buffer 1}]]
 :dispatches {:gate [[:pass :done] [:fail :author]]}
 :invariants [[:must-precede :critic :gate]]
 :lifecycle  {:on-provider-loss :drain}       ; Unloading drains in-flight messages first
 :evidence   {:min-inverse :sampled}}         ; weakest inverse witness allowed to hot-replace
```

What each idea is taken from:

| Feature | Source | Cordis role |
| --- | --- | --- |
| Cells with closed input and output schemas, per-path schema chain, dispatches, joins, halt and resume | mycelium | Specification d and confinement checked at load |
| Processes, in and out ports, buffered links, start, pause, resume, stop, a monitor | core.async.flow | Fibers and their lifecycle |
| Per-link regimes `:normal`, `:drop`, `:reject` | jolt-sim connection regimes | Interception metadata on a link |
| `myc patch`: structural edits to the graph, batch-validated, written atomically, guarded by `--expect-hash` | upstream mycelium CLI | The only way an agent edits a graph: a revision, never a rewrite |
| Frame laws (`:changes`), machine tables with `never` and `before`, `calls` and `flow` contracts, anti-weakening baselines | writ | Admission checks; a mutation that weakens a contract needs approval |
| Pure decision core per runtime choice (`life/step`, `restart-plan`, `intensity`), shell forced to call through it | ensemble | The lifecycle calculus as total functions |
| Effect results with a declared delivery mode (`:before`, `:after` checkpoint), checkpoint written in the same CAS as outputs, CAS scoped by a query | grain | Emission discipline; declared reads become the commit guard |
| Read models versioned in their cache key; desired-state reconciliation by lease diff | grain | Reactive coeffects; config reconciliation |
| Typed LLM signatures whose calls are recorded as facts | grain's DSPy leaf | An LLM call is an emission with a schema'd intent and receipt |

### An ML-syntax graph language

An ML surface is worth it, because a small ML with row types and effect rows turns the checks that EDN graphs bolt on into ordinary type inference. The EDN version above stays useful as the serialised form, but the language the agent writes and the loader checks is this one.

What the types buy over EDN plus schemas:

| Check today | In the ML language |
| --- | --- |
| mycelium's schema chain: walk every path, accumulate output keys, intersect at joins | Row-polymorphic records. A cell has type `{ diff : diff; .. } -> { review : review; .. }`; composing cells infers the row, and a missing key is a type error at the edge that drops it |
| Join outputs must be disjoint unless merged | A "lacks" constraint on the row |
| Per-transition output schemas; dispatch coverage | A variant result; routes are a `match`, and exhaustiveness checking is the coverage check |
| `:requires` and interception metadata | A capability row in the cell's signature. The environment is built from it, and attenuation is a subtype |
| `:effects` and boundary classes | An effect row, Koka-style: `llm.emit`, `repo.read`. A cell whose body performs an effect it did not declare does not typecheck |
| Emission returns an intent, receipt comes later | `let!` suspends the cell until the intent commits and its receipt arrives; the type says it may be `Uncertain` |
| Fragments and parameterised subgraphs | Functors over a graph signature |
| writ's anti-weakening check | Subtyping on signatures: a revised cell may accept more and produce more, never less, without approval |

A sketch:

```ocaml
cell critic
  : { diff : diff; .. } -> [ Pass of review | Fail of review ]
  requires { llm : Llm.t with max_tokens = 4000; repo : Repo.t with mode = Read }
  effects  { llm.emit; repo.read }
= fun { diff; _ } ->
    let ctx = Repo.read repo diff.path in
    let! resp = Llm.complete llm [ ctx; diff.patch ] in   (* intent, then receipt *)
    match parse_review resp with
    | { verdict = Pass; _ } as r -> Pass r
    | r -> Fail r

graph review_loop (Author : AUTHOR) = struct
  process author = Author.cell
  process critic = critic
  process gate   = Review.gate

  link author.diff -> critic.diff  ~buffer:4 ~regime:Normal
  route critic with
  | Pass r -> gate.review r
  | Fail r -> author.feedback r          (* a third verdict is a compile error here *)

  invariant (must_precede critic gate)
  on_provider_loss Drain
end
```

How it fits the rest:

- **Our own checker, not the OCaml compiler.** The graph language is checked by Hindley–Milner with row and effect polymorphism, a few thousand lines of OCaml run at admission. The interpreter executes the checked tree, so there is no `Dynlink`, unload stays free, and the trusted base stays small.
- **Cell bodies are CEL or Wasm.** Most bodies are CEL expressions typed against the cell's signature (see Cells in CEL). A Wasm component can stand in for a body: its WIT imports are the capability row and its exports the output type.
- **Edits are typed patches.** `myc patch`-style operations on the syntax tree are re-checked before commit, and a patch that narrows a signature needs approval.
- **EDN stays the wire format.** It is still the storage, diff and journal format, and the serialised tree round-trips.

### Syntax, revised

Four changes make the language read like what it means: effects in the arrow, `use` for waiting on receipts, path updates, and assets named as nouns. The sketch above, rewritten:

```ocaml
(* Effects and capabilities in the arrow, Unison/Koka style *)
cell critic : { diff : Diff.t; state : { attempts : int } }
           ->{ Llm.complete, Repo.read } Verdict.t =
  let ctx = Repo.read diff.path
  use resp = Llm.complete { prompt = [ctx; diff.patch]; max_tokens = 4000 }
  let r = Review.parse resp ?? Review.unparseable
  (if r.verdict == Pass then Pass r else Fail r)
    with state.attempts := state.attempts + 1

(* Assets are nouns: what should exist, and what makes it *)
graph review_loop (Author : AUTHOR) = {
  asset draft   = Author.write
  asset verdict = critic draft      ~fresh:on_change
  asset shipped = gate verdict

  route verdict {
    Pass _ -> shipped
    Fail r -> draft <~ r.notes      (* cycles are routes back into an asset's inputs *)
  }
  invariant (critic before gate)
}
```

- **Effect rows replace `requires` and `effects`.** `->{ Llm.complete, Repo.read }` is the capability row and the effect row at once; the environment is built from it.
- **`use` splits a body into turns.** Written as sequential code, compiled into a chain of CEL turns cut at each `use`: the first turn returns the `Llm.complete` intent, the next resumes with its receipt. Each segment must be CEL-expressible on its own, and each `use` is a commit point. This is async/await compiled to a state machine, with the state machine made of cells.
- **`with` path updates** are a CEL macro: `x with a.b := v` rebuilds only that path.
- **`??` for optionals.** Parsers return optional values; `??` supplies the fallback, so a parse failure is handled where it happens.
- **Assets for the acyclic part, routes for loops.** Assets are memoised and freshness-driven (upgrade 2); a cycle is written as a route feeding an asset's input, which keeps the cached part of the graph acyclic.

### Schema import

Types can come from protobuf, JSON Schema, OpenAPI and AsyncAPI if every importer targets one internal type form and anything a type cannot say is kept as a CEL check. Kubernetes already types CEL against OpenAPI schemas, and protovalidate attaches CEL rules to protobuf messages, so both halves have precedent.

The internal form is the graph language's own: records with rows, variants, lists, maps, optionals, and scalars with formats. Each import produces three things:

1. **Types** the graph checker and the CEL checker both read.
2. **Residue**: the constraints no type expresses, compiled to CEL predicates and checked at the trap on every frame.
3. **A report** marking what was approximated, so a lossy import is visible in review and never silent.

| Source | Maps to | Lossy or extra |
| --- | --- | --- |
| Protobuf | Messages become records, `oneof` a variant, enums variants, `repeated` lists, `map` maps, `optional` optional. Timestamp, Duration and Struct are native CEL types | Close to lossless. `buf breaking` rules give a ready-made compatibility check |
| JSON Schema | `properties` become record fields, `required` decides optional, `enum` and discriminated `oneOf` become variants, `$ref` named types | A validation language, not a type system. `allOf`, `not`, `if`/`then`/`else`, `patternProperties` and undiscriminated `oneOf` become a looser type plus CEL residue. Objects are open by default; the importer closes them at trust boundaries |
| OpenAPI 3.1 | Its schemas import as JSON Schema 2020-12. Each operation becomes an **intent kind**: request body is the frame type, responses are receipt types | The HTTP method sets the retry rule (GET may retry, POST must reconcile). `securitySchemes` names the outbox credential. `servers` is the egress allowlist |
| AsyncAPI 3 | Message payloads import as their declared format. Channels and send/receive operations become the graph's **external ports** | Send operations are emission kinds; receive operations are inputs that enter as receipts |

In the graph language:

```ocaml
import Billing = openapi "specs/billing.yaml"  ~pin:"sha256:…"
import Events  = asyncapi "specs/events.yaml" ~pin:"sha256:…"
import Review  = proto "proto/review.proto"

cell invoice
  : { order : Review.Order; .. } -> { intents : Intent.t list }
  requires { billing : Billing.createInvoice }   (* generated from operationId *)
= cel {| {"intents": [billing.createInvoice({"amount": order.total})]} |}
```

Versioning closes the gap the Cordis paper leaves open in §6.6, where dependencies link by key name only:

- **Identity** is source document, type name and content hash, so two unrelated `Order` types never collide.
- **Compatibility** is structural subtyping on the internal form, plus `buf breaking` for protobuf. A provider may widen what it accepts and narrow what it returns; anything else is a breaking change.
- **Pins** make a schema upgrade an explicit graph revision, re-typechecked and subject to the anti-weakening rule.

Libraries: `ocaml-protoc` for protobuf, and `jsonschema` 0.1.0 on opam (drafts 4 through 2020-12) as a reference validator to test the residue against. Avro, GraphQL SDL and WIT can follow the same pattern once the internal form settles.

### Where CUE fits

Use CUE at the edges, not the core. It is the best open cross-format schema normaliser, and its order-independent merging suits deployment config. It should not be the schema IR, the residue language or the policy language, and it should never be linked into the OCaml process.

What CUE is (v0.17.1 stable, July 2026; v0.18 in alpha):

- **Types are values.** Types and data sit in one lattice, and unification (`&`) is the meet, so constraints from any file, in any order, combine to one answer. A concrete value can never be overridden, by design.
- **Definitions are closed.** v0.18 makes embedding a closed value close the enclosing struct, with a postfix `...` to reopen it.
- **`|` with `*` defaults** covers sum types, enums and defaults in one operator.
- **Not bounded.** There is no static cost estimate and no published termination guarantee. Disjunctions can blow up, and v0.18 adds an experimental `functions` feature that removes even CUE's practical totality.

Roles:

| Role | Use CUE? | Why |
| --- | --- | --- |
| Importer front end, offline | Yes | `encoding/jsonschema` reads drafts 4 through 2020-12 and OpenAPI 3.0 component schemas; there is a protobuf importer. Run it with `Strict` on, because without it unsupported keywords are dropped silently. Lower `cue def` output into our own IR; whatever strict mode rejects becomes CEL residue |
| Deployment config and overlays for graphs | Yes | Order-independent merging and closed definitions are exactly right for environments and defaults. Validate with `cue vet` against JSON Schema exported from our IR |
| Scheduler prior art | Study it | `tools/flow` infers task dependencies from references, writes results back with `Fill`, and starts a task once its inputs are concrete. That is our asset model with implicit edges; ours keeps edges and effects explicit |
| Canonical schema IR | No | Pre-1.0 and still changing; no row or effect types; no OCaml binding; content addressing is per module archive, not per definition |
| CEL residue | No | No cost bound, which is the one property residue needs |
| Capability policy | No | Unification is not authorisation; see Cedar |
| Linked library | No | libcue is a cgo build of the whole Go runtime, self-described as unstable; its signal handling and threads would share a process with OCaml 5 |
| AsyncAPI | No | CUE has no AsyncAPI support; that importer is ours |

How OCaml calls it:

1. **At pin time, a subprocess.** Shell out to a version-pinned `cue` binary (`cue import`, `cue def`, `cue export`) with JSON on stdin and stdout. Record the CUE version beside the content hash of what it produced.
2. **For validation at run time, a sidecar.** If live `cue vet` is ever needed, use a small Go program on the official API over a Unix socket.
3. **Hashes stay ours.** Our semantic hash over our own normal form is authoritative. If schemas are also published as CUE modules on an OCI registry, as Timoni does, the OCI digest is only transport integrity.

Before relying on it, test two things CUE's docs do not settle: whether protobuf editions and services import, and whether Kubernetes `x-kubernetes-validations` CEL rules survive import or are dropped.

The neighbours, for contrast:

- **Dhall** is total and hashes imports over their normal form, the closest match to content-addressed definitions.
- **KCL** is a CNCF sandbox project and much easier to embed, with C and Wasm SDKs.
- **Pkl** generates typed code for several languages.
- **Nickel** has contracts and gradual typing, but is Turing-complete.
- **Dagger** dropped its CUE SDK in December 2023 for lack of adoption among pipeline authors, not for a flaw in the semantics.

Sources: [CUE spec](https://cuelang.org/docs/reference/spec/), [The Logic of CUE](https://cuelang.org/docs/concept/the-logic-of-cue/), [v0.18.0-alpha.2 notes](https://github.com/cue-lang/cue/releases/tag/v0.18.0-alpha.2), [encoding/jsonschema](https://pkg.go.dev/cuelang.org/go/encoding/jsonschema), [CUE and OpenAPI](https://cuelang.org/docs/concept/how-cue-works-with-openapi/), [CUE and protobuf](https://cuelang.org/docs/concept/how-cue-works-with-protocol-buffers/), [modules](https://cuelang.org/docs/reference/modules/), [libcue](https://github.com/cue-lang/libcue), [tools/flow](https://pkg.go.dev/cuelang.org/go/tools/flow), [Dagger ends CUE support](https://dagger.io/blog/ending-cue-support), [Dhall safety guarantees](https://docs.dhall-lang.org/discussions/Safety-guarantees.html), [KCL](https://www.kcl-lang.io/docs/user_docs/getting-started/intro), [Timoni](https://timoni.sh/).

### Scaffolding services from specs

Starting from an OpenAPI, AsyncAPI or protobuf spec, the scaffolder generates a typed router and one flow per operation whose body is a typed hole. The generated parts are owned by the importer and regenerated freely. The holes are owned by whoever fills them and are never touched. That is MuleSoft APIkit's shape with two fixes: response types are sums, so the response side is exhaustive too, and content addressing makes re-scaffolding a type check instead of a merge.

What APIkit does today:

- **Flows.** A main flow (listener, router, error handler) and one backend flow per method, resource and content type, named like `get:\users\userbyid:api-config`. Each starts as a mock.
- **Validation.** The router validates requests against the spec; lenient by default, strict for query parameters and headers is a config flag.
- **Fixed error mapping.** `APIKIT:BAD_REQUEST` 400, `NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, `NOT_ACCEPTABLE` 406, `UNSUPPORTED_MEDIA_TYPE` 415, `NOT_IMPLEMENTED` 501 for an operation with no flow.
- **Add-only re-scaffolding.** Re-scaffolding adds flows for new operations and "Existing flows are not modified." What happens to flows for removed operations is not documented.
- **AsyncAPI support** (2.6) scaffolds a flow per publish operation, with message listeners, for Anypoint MQ, Kafka, Salesforce Platform Events and Solace.
- **Mocking** is driven by per-request headers that pick status, example, error rate and delay.

Other generators split the same way. oapi-codegen's strict server, ConnectRPC, Smithy server SDKs and TypeSpec generate an *interface* and leave the implementation to the user, the Generation Gap pattern. OpenAPI Generator instead writes one-shot stubs guarded by an ignore file. The interface approach is the one that survives regeneration.

What the scaffold generates, keyed by a stable operation key (`method path content-type`, `rpc`, or `channel action`):

| Generated | Contents |
| --- | --- |
| Router | One exhaustive `route` over operations; no match is `NotFound`, wrong method `MethodNotAllowed`, content negotiation `NotAcceptable` or `UnsupportedMediaType` |
| Flow per operation | Typed input record (path, query, headers, body); output a **sum over declared responses**, as oapi-codegen does with `FindPets200JSONResponse`; body a typed hole defaulting to 501 |
| AsyncAPI | Receive operations become inbound routes and flows; send operations become intent kinds released by the outbox |
| Protobuf | One flow per rpc; streaming rpcs get stream-typed holes |
| Validation residue | CEL guard cells for what types cannot say: patterns, formats, ranges, unique items; strictness is a policy setting, not code |
| Error table | Router and validation faults to the six statuses above; declared error responses to typed arms; unhandled failure to 500 or 503; an `Uncertain` receipt to 202 with a reconcile handle |
| Cedar stubs | One action per operation from the spec's `security` and scopes; default deny |
| Quint stub | An action per route; invariants "every request gets exactly one response" and "no publish before commit" |
| Mock mode and tests | Responses from the spec's examples with MS2-style selectors; a contract test per operation, a negative test per residue rule, a router exhaustiveness test |

A generated flow, before anyone fills it:

```ocaml
(* generated from petstore.yaml, operation get:/pets/{id} — do not edit *)
type get_pet_in  = { path : { id : int64 }; headers : { x_request_id : string option } }
type get_pet_out = Ok_200 of Pet.t | Not_found_404 of Problem.t | Error_default of Problem.t

(* owned by you: the hole the router calls *)
flow get_pet : get_pet_in ->{ Store.read } get_pet_out = ?todo
```

One rule beyond APIkit: **the response to a client is an emission.** If a flow's work emits intents, its reply is withheld until those intents commit, so a client is never told "done" about something the outbox later rolls back. A reply waiting on an `Uncertain` receipt becomes 202 with a handle the client can poll.

Re-scaffolding, with content addressing:

1. **Added operation.** A new route and a new hole; nothing else moves.
2. **Removed operation.** The route goes; the filled flow is kept and flagged orphaned, never silently deleted.
3. **Changed signature.** The filled hole no longer type-checks against the new input or output type, so the error lands at exactly that flow, with no text merge.
4. **Unchanged.** A filled hole with the same hash and types is reused as is.

Each re-import is classified with oasdiff's breaking rules, or `buf breaking` at `WIRE_JSON` or stricter, and goes through the normal validate-and-soak path.

Sources: [APIkit scaffolding](https://docs.mulesoft.com/apikit/latest/apikit-4-scaffolding-reference), [APIkit error handling](https://docs.mulesoft.com/apikit/latest/apikit-error-handling-reference), [validation](https://docs.mulesoft.com/apikit/latest/validate-4-task), [spec versions](https://docs.mulesoft.com/anypoint-code-builder/imp-manage-api-spec-versions), [APIkit for AsyncAPI](https://docs.mulesoft.com/apikit/latest/apikit-for-asyncapi), [mocking headers](https://docs.mulesoft.com/design-center/apid-behavioral-headers), [oapi-codegen](https://github.com/oapi-codegen/oapi-codegen), [Smithy handlers](https://smithy.io/2.0/languages/typescript/ts-ssdk/handlers.html), [ConnectRPC](https://connectrpc.com/docs/go/getting-started/), [Generation Gap](https://www.martinfowler.com/dslCatalog/generationGap.html), [buf breaking](https://buf.build/docs/breaking/rules/), [oasdiff](https://github.com/oasdiff/oasdiff).

### Scaffolding controllers from CRDs

A Kubernetes controller is the Cordis lifecycle with different names, so a CRD can scaffold one directly. The reconcile hole should take Metacontroller's pure shape, (parent, observed children) to (desired children, status), and the runtime does the rest. Writing it as a CEL cell is natural, because Kubernetes already validates CRDs with CEL.

The correspondence:

| Kubernetes | Cordis and this design |
| --- | --- |
| Reconcile, level-triggered and idempotent | Target view against committed view: the hole computes desired state, the runtime diffs it against observed state and emits apply intents. Re-running on the same observed state is a no-op |
| `RequeueAfter`, `resyncAfterSeconds` | A scheduled re-evaluation intent |
| `status.observedGeneration` matching `metadata.generation` | The committed view records the hash of the spec it was computed from; status is written only from observed state |
| Finalizer: delete becomes "set deletion timestamp" until cleanup removes the finalizer | The Unloading guard. Deletion moves the fiber to Unloading; the finalize hole shrinks desired children step by step; the guard releases only when the *observed* view confirms they are gone. An `Uncertain` cleanup keeps it held |
| Owner references and garbage collection; `Owns()` wakes the parent on child events | The fiber tree: retiring a parent retires its children in reverse dependency order; child changes re-target the parent |
| Server-side apply: each field manager owns a set of fields; disjoint owners never conflict | Commutativity per key. Writes by different managers to disjoint fields commute; `listType: map` and `mapType: granular` set the grain; `atomic` means one owner for the whole subtree |
| CRD `x-kubernetes-validations` in CEL, transition rules with `oldSelf`, a cost budget enforced when the CRD is written | CEL guard cells imported unchanged; transition rules become guards over old and new committed views, with `optionalOldSelf` as an option type |
| kubebuilder RBAC markers | Cedar actions per verb and resource, generated as stubs at the trap |

What the scaffold produces from a CRD:

- **The resource type,** from the CRD's OpenAPI v3 schema.
- **Validation,** with `x-kubernetes-validations` as CEL guards.
- **A reconcile flow and a finalize flow,** each a typed hole of Metacontroller's shape.
- **Child templates with dependencies inferred from references,** as KRO does. KRO's ResourceGraphDefinition writes `${deployment.metadata.name}` inside a child template, infers the dependency graph from those CEL references, creates in topological order, deletes in reverse, rejects cycles, and gates children with `readyWhen` and `includeWhen`. It is at v0.9.4, API `v1alpha1`, with a v0.10 release candidate.
- **Cedar stubs** from the RBAC needs.
- **A Quint stub** with invariants "no two managers own one field" and "retire waits for an empty child set".
- **Tests:** a table of observed-to-desired reconcile cases, a finalize-ordering test, a negative test per transition rule, and a two-manager conflict test.

The filled reconcile hole for a small web-app resource:

```ocaml
(* generated from webapps.example.com CRD *)
reconcile webapp
  : { parent : WebApp.t; observed : { deployment : Deployment.t option; service : Service.t option } }
    -> { desired : Child.t list; status : WebApp.status; resync_after : duration option }
= cel {|
    {"desired": [
       deployment(parent.spec.name, parent.spec.image, parent.spec.replicas),
       service(parent.spec.name, 80)],
     "status": {"ready": observed.deployment.?status.readyReplicas.orValue(0) == parent.spec.replicas,
                "observedGeneration": parent.metadata.generation},
     "resync_after": null}
  |}
```

The runtime applies `desired` by server-side apply with this controller as field manager, forcing conflicts on objects it owns. It deletes observed children missing from `desired`, as Metacontroller and Crossplane do, and routes the apply receipts through the outbox like any other emission. Crossplane's composition functions add one more pattern worth keeping: several cells in a pipeline accumulate desired state over the same observed snapshot, and a resource any step drops is deleted.

Sources: [kubebuilder book](https://book.kubebuilder.io), [CRD validation rules](https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/), [server-side apply](https://kubernetes.io/docs/reference/using-api/server-side-apply/), [Metacontroller CompositeController](https://metacontroller.github.io/metacontroller/api/compositecontroller.html), [DecoratorController](https://metacontroller.github.io/metacontroller/api/decoratorcontroller.html), [KRO ResourceGraphDefinition](https://kro.run/docs/concepts/rgd/overview), [Crossplane compositions](https://docs.crossplane.io/latest/composition/compositions/), [kopf handlers](https://docs.kopf.dev/en/stable/handlers/).

## The runtime kernel

The kernel is ensemble's shape in OCaml: every decision a total pure function over variants, and a thin Eio shell that can only change state through those functions. Signatures, mainline OCaml with OxCaml modes shown as comments where they would go:

```
(* Effects: a step returns its new state and its inverse (Cordis Def. 8, 17). *)
type boundary = Internal | Acquire | Emit

type 'ctx inverse = {
  undo    : 'ctx -> 'ctx;          (* @ once: callable at most once *)
  witness : Evidence.t;            (* :proved | :exhausted | :sampled | :unknown *)
}

type ('ctx, 'out) step =
  | Yield of 'ctx * 'ctx inverse * ('ctx, 'out) iter
  | Done  of 'ctx * 'ctx inverse * 'out
  | Raise of error                  (* partial accumulator unwinds via Divert *) 
and ('ctx, 'out) iter = 'ctx -> ('ctx, 'out) step

(* Emissions never run in a step; they become intents. *)
type intent = { id : Intent_id.t; kind : string; payload : Value.t; schema : Schema.t }
type receipt = Definite of Value.t | Failed of error | Uncertain of { published_seq : int }

(* Coeffects: typed keys with operations (Def. 19, 29). *)
type 'v key = { name : string; schema : 'v Schema.t; ops : 'v Op.set; commutes : Evidence.t }

(* Components and fibers (Def. 48, 49). *)
type component = {
  requires : Key_set.t;           (* d *)
  provides : Key_set.t;           (* p *)
  body     : (Ctx.t, unit) iter;  (* the interpreted cell, lowered *)
}

type view = Fiber_id.t Key_map.t   (* key -> provider *)

type life =
  | Inactive
  | Reloading of { rest : (Ctx.t, unit) iter; acc : Ctx.t inverse list; committed : view }
  | Active    of { acc : Ctx.t inverse list; committed : view }
  | Uncertain of { acc : Ctx.t inverse list; committed : view; pending : intent }
  | Unloading of { acc : Ctx.t inverse list; committed : view }
  | Failed    of error

(* The calculus: pure, total, exhaustively matched. *)
val target  : registry -> Fiber_id.t -> view option            (* None = should not run *)
val relied  : registry -> Fiber_id.t -> bool
val next    : registry -> Fiber_id.t -> rule option            (* Begin | Iter | Finish | Divert | Leave | Unload | Reconcile *)
val apply   : registry -> Fiber_id.t -> rule -> registry * Ctx.t inverse list
```

How the shell maps onto Eio and friends:

- **One `Eio.Switch` per activation.** Its LIFO `on_release` hooks run the accumulator on L-Unload; a parallel inverse log records which inverse belongs to which step, because a Switch alone cannot say.
- **Ownership with a leak check.** Every acquisition is registered with a Miou-style owner (`own`, `release`, `transfer`); a fiber that reaches Inactive still owning something raises `Resource_leaked`. That is the at-least-once half of "every inverse runs exactly once"; `once` supplies the at-most-once half.
- **Masked teardown.** Unloading runs under `Switch.run_protected`, so a cancellation cannot tear an inverse half-way, as ensemble masks `settle!`.
- **Reactivity.** Key observation follows Lwd's `prim ~acquire ~release`: a key gains an acquire when some fiber's committed view first names it and a release when the last one drops it.
- **Versioned userspace.** Graphs, cells and policy live in Irmin: a branch per candidate revision, merge on commit, reset the head on rollback. That replaces samizdat's append-only userspace table and makes `revert!` a real inverse.
- **Recording.** The registry's step log is the journal; replay re-folds it, and confluence (Theorem 80) says replaying the configuration log reaches the same quiescent state as the live run.

## Mode encodings

OxCaml checks six of the eight guarantees statically; at-least-once inverses and always-handled effects still need a runtime check in either compiler. Every mode below must stay an erasable annotation, so the mainline build is the same code with weaker checking.

| Guarantee | OxCaml encoding | Mainline OCaml fallback |
| --- | --- | --- |
| A cell cannot keep a capability past its invocation | Pass capabilities `@ local` | Abstract types plus a runtime revocation flag |
| A handle has one owner | `unique` handle, consumed on use | Phantom typestate plus a dynamic "already consumed" check |
| An inverse runs at most once | `once` closure | One-shot continuation semantics; raise on second call |
| An inverse runs at least once | Not expressible: a scope must return a `discharged` witness, plus a leak check | Miou-style ownership, `Resource_leaked` at scope exit |
| A cell body touches no ambient mutable state | `portable` and `stateless` on the body | Not expressible; rely on the interpreter environment |
| Per-component state across many invocations | Capsule brand `('a, 'k) Capsule.Data.t` | A private module per component |
| Typestate of a commit-gated emission: Pending, Committed, Uncertain, Reconciled | GADT-indexed handle that is also `unique`, so the old state is consumed | GADT-indexed handle; stale handles checked at run time |
| An effect is always handled | Reported `handled_effect` API using `yielding`; unverified | `Effect.Unhandled` at run time only |

The large caveat: modes stop at the interpreter boundary. They confine the kernel and the trusted native extensions; agent-written cells are confined only by how their environments are built. That makes the CEL evaluator, its host functions and the Wasm runtime the trusted computing base.

### OxCaml on FreeBSD

A native FreeBSD port is about 7 to 14 person-weeks, mostly build tooling, test triage and the Jane Street libraries, not compiler work. A source read of oxcaml/oxcaml at `49773974` (2026-10-09, `5.4.0+ox`) found that OxCaml kept nearly all of upstream OCaml 5's FreeBSD support:

- configure already maps amd64 and arm64 FreeBSD to a native backend;
- the runtime has FreeBSD paths for futexes (`_umtx_op`) and CPU affinity;
- the amd64 emitter treats FreeBSD as an ELF target;
- domain state lives in a register, so thread-local storage is not a concern.

Nothing was run on FreeBSD, so everything below is static analysis.

| Work | Effort | Detail |
| --- | --- | --- |
| Build tooling | Days | GNU make is assumed: two dune rules call bare `make`. Use dune's `%{make}` and opam's `%{make}%`, or put a `gmake` shim on PATH. Needs `gmake`, `bash`, `rsync`, `autoconf` |
| Release configure flags | Trivial | `--enable-stack-checks --enable-poll-insertion --enable-multidomain`, as the opam package uses; without multidomain you get one domain |
| Probe sections | Trivial | `[%probe]` hits `fatal_error` outside Linux in `asm_section.ml`; add FreeBSD to the ELF cases, and emit `.note.GNU-stack` there too |
| Stack-overflow handler | Small | `signals_nat.c` writes Linux's `REG_RIP`; FreeBSD needs `mc_rip`. Only compiled in the developer default build, not the release flags |
| `Sys.executable_name` | Small | Add `sysctl(KERN_PROC_PATHNAME)`; today it returns nothing on FreeBSD |
| JIT for the native toplevel and `eval` | Moderate | Allocate near the text segment with an mmap hint; arm64 FreeBSD has no `sbrk` |
| CPU feature probes | Verify | About 14 configure probes use `__builtin_cpu_supports`; check them in a bhyve guest, where CPUID masking can hide AVX-512 |
| Testsuite | 1 to 3 weeks | Never run on FreeBSD; expect gdb, DWARF and binutils-shaped failures |
| Libraries | 2 to 4 weeks | `parallel` pulls in `core_unix` through `async`; core\_unix needs C-stub fixes on FreeBSD. Use `eio_posix`, not `eio_linux`. `janestreet_cpuid` and `ocaml-probes` are marked Linux-only |
| arm64 | 1.5 to 3 weeks | Build, JIT, triage on native arm64 hardware; emulation is too slow for the testsuite |
| CI | 1 to 2 weeks | A nightly FreeBSD job on a self-hosted bhyve runner, plus an opam overlay for patched packages |

After that, expect half a day to a day a week to track OxCaml's fast-moving main and roughly monthly releases.

The cheaper stop-gaps:

1. **A Linux jail under FreeBSD's Linux emulation.** It covers what OxCaml needs: simple futexes, epoll, eventfd, and timerfd in the base kernel since 14.0. Mount linprocfs for `/proc/self/exe`. Worth trying first.
2. **A Linux bhyve guest.** This is a supported platform with no surprises, but the binaries it produces are Linux binaries.

Cross-compiling from Linux is not viable: the configure probes run test programs, and the bootstrap runs the compiler it just built.

Upstreaming is plausible for the small fixes. A comparable OpenBSD fix was merged in July 2025, and an open refactor keeps FreeBSD as a named target. FreeBSD is unlikely to become a platform Jane Street's CI protects, so plan on a small patch queue of your own. Structure the patches as "non-Linux POSIX" rather than FreeBSD-only, because illumos would need nearly the same set; upstream OCaml already lists OmniOS, an illumos distribution, as a Tier 2 target.

Sources: [OpenBSD fix #4210](https://github.com/oxcaml/oxcaml/pull/4210), [DragonFly draft #5282](https://github.com/oxcaml/oxcaml/pull/5282), [Target\_system refactor #6438](https://github.com/oxcaml/oxcaml/pull/6438), [get OxCaml](https://oxcaml.org/get-oxcaml/), [Eio](https://github.com/ocaml-multicore/eio/blob/main/README.md), [FreeBSD Linux emulation](https://docs.freebsd.org/en/books/handbook/linuxemu/), [Linux jails](https://wiki.freebsd.org/LinuxJails).

## Into the hypervisor

The Cordis calculus applies unchanged at every level from fiber to jail; what changes is the grain of the inverse. Each level's inverse is the backstop for the level inside it: if a fiber's composed inverse fails or ends uncertain, the VM rolls back to its pre-activation snapshot; if the VM is lost, the jail's ZFS clone is destroyed. Only emissions, which by definition crossed the host boundary, have no inverse anywhere.

This assumes stock FreeBSD 15 and bhyve; the details of your platform may change the jail and VM rows.

| Level | Component, fiber | Acquisition and its inverse | Confinement, interception | Where emissions cross |
| --- | --- | --- | --- | --- |
| Host, bare-metal FreeBSD | Not a component: the system boundary itself | None | Default-deny `pf` and `netgraph` on egress | The one outbox daemon: releases an intent only after its cell commits, records the receipt, holds uncertain ones for reconcile |
| Jail per agent or tenant | Supervisor component. Create is L-Begin; `jail -r` is L-Unload | VNET epair: destroy. ZFS dataset clone: `zfs destroy`. rctl rule: remove. devfs ruleset: drop | VNET plus `pf`; rctl budgets read from policy; `allow.vmm` lets bhyve run inside, as a non-root `vmm` group member | All egress goes to the host outbox; nothing leaves directly |
| bhyve VM per graph or experiment | Component. Reloading is `bhyvectl --suspend` then `bhyve -r` | zvol clone: rollback to `@pre` or destroy. Checkpoint file: delete. Tap or slirp: detach. VM: `bhyvectl --destroy` | Capsicum-sandboxed bhyve. The device list *is* the coeffect set: virtio-console or 9p for control (bhyve has no vsock), slirp for a fully mediated network | Anything written to console, 9p or net is an intent until the host side forwards it |
| Process or unikernel in the VM | The OCaml cell runtime. A MirageOS unikernel makes "granted devices = declared coeffects" literal | fd: close. 9p fid: clunk. Memory: covered by the VM snapshot | One domain, effect-based fibers; the interpreter is the innermost confinement | A hypercall or virtio write is a request to emit |
| Fiber, one cell activation | Cordis fiber, six states | Each primitive returns its inverse; `Switch` hooks replay them LIFO | Environment built from declared keys only; fuel | Primitives tagged `:emit` produce intents only |

Three platform facts shape the design:

- **One vCPU, one domain inside the deterministic boundary.** Antithesis pins one core and one vCPU per VM; ocaml-solo5 runs OCaml 5 on a single domain. Parallelism therefore comes from many VMs, which is why jails and VMs are components too, not just isolation.
- **The model runs outside.** GPU passthrough on bhyve needs out-of-tree patches and wired memory, which rules out snapshots. A local model is a host-side service, and every inference call is an emission: recorded on the way out, replayed from a cassette in simulation, reconciled if its outcome is lost.
- **Restored clones are not unique.** bhyve's VM generation counter is filled at start and its restore notification is `#if 0`. Until that is patched, every clone from one snapshot shares its RNG, TCP sequence seeds and wall clock; the guest runtime must reseed itself on restore, as Firecracker documents for its own clones.

## Deterministic exploration

Use two nested determinism boundaries with one contract between them: in-process simulation for cells (cheap, semantic, thousands of runs a second), and a deterministic hypervisor for everything underneath (runtime, GC, guest kernel, I/O timing). Cell code must run unchanged under both.

- **One SDK contract.** Give the in-process simulator Antithesis's vocabulary: `always`, `sometimes`, `reachable`, `unreachable`, `setup_complete`, and an oracle-chosen `random` that is used at once and never seeds a PRNG. Under a hypervisor the same calls go out over its hypercall or `sdk.jsonl`.
- **One entropy oracle.** Every scheduler choice, fuel tie-break and random draw goes through it. In-process it is the simulator's seed; inside a deterministic VM it calls the hypervisor, so the hypervisor's exploration tree contains the simulator's choices. Antithesis branches exactly at randomness draws, so the branch points line up.
- **Evidence grades follow the boundary.** A property shown only in-process is `:simulated`; one that also survived hypervisor faults (clock skips, partitions, node kills) is `:system`. Both feed the witness strengths from the synthesis doc.
- **The manifest is the test plan.** Antithesis's Test Composer roles line up with the graph: `first_` is acquisition and setup, `parallel_driver_` the cells, `eventually_` and `finally_` the reconciliation and inverse-completeness checks. "After unload, the zvol equals `@pre`" is an `eventually_` property the host can check by diffing the clone.
- **Soak in branched futures.** A mutation's soak becomes multiverse debugging: snapshot the VM, branch it N ways, run the revised graph in each with faults on, and commit only if every branch's inverses close cleanly.

The hard part is the hypervisor itself. Antithesis's bhyve fork is proprietary; the open analogue, bedrock (Rust, 2026), runs Linux guests on Intel VT-x with one vCPU and needs Ice Lake-SP or newer PMU features for precise interrupts. Three options, in rising cost:

1. **Rent Antithesis** for system-level runs; keep everything else on stock bhyve. Cheapest, and the SDK contract above makes it a drop-in.
2. **Use bedrock** as a sibling to bhyve on a Linux host or partition, for the deterministic lane only.
3. **Build a deterministic bhyve mode**: for arbitrary guests: one vCPU, virtual TSC and HPET, interrupt injection on instruction counts. That is the Antithesis path and a multi-person-year effort. For Mirage and Wasm guests only, the narrow version below needs none of it.

### A narrow deterministic hypervisor for Mirage and Wasm

Yes: if the only guests are Solo5 unikernels and Wasm cells, determinism can be a property of the guest interface instead of the CPU, which removes the part of Antithesis that costs person-years. Antithesis must make an arbitrary x86 guest repeatable, so it counts instructions and injects interrupts at exact points. A Solo5 guest is single-core, takes no interrupts and does all I/O through hypercalls, so the only places its future can diverge are those hypercalls. Control their results and the guest is deterministic between them.

What Solo5's hvt target looks like today (checked against `Solo5/solo5` master, 2026-10-09):

- **Eight hypercalls**: walltime, puts, poll, block write, block read, net write, net read, halt. `poll` with a deadline is the guest's only way to wait.
- **No interrupts.** The guest polls; the tender sets only `VM_CAP_HALT_EXIT` on FreeBSD vmm.
- **A small tender.** The FreeBSD tender is about 480 lines over `/dev/vmm` ioctls, with about 1,100 more for core, net and block. Forking it is a contained job.
- **Two leaks.** Monotonic time is read with `rdtsc` inside the guest (`bindings/hvt/tscclock.c`), and the stack-protector seed is taken from `rdtsc` at boot (`bindings/crt_init.h`).

Where nondeterminism enters, and how the narrow version closes each:

| Source | Antithesis has to | Narrow version does |
| --- | --- | --- |
| Preemption and interrupts | Inject at exact instruction counts, fighting PMU skid | Nothing: the guest is never interrupted. A host watchdog only kills runaways, which counts as a failed run |
| Time | Trap every TSC and HPET read | Patch the bindings to read a time page the tender writes only at hypercalls; time moves only when the simulator says |
| Entropy | Control RDRAND, seeds, devices | Seed from the boot info; mask RDRAND and RDSEED in vmm's CPUID emulation; mint random draws through the entropy oracle |
| CPUID and CPU model | Stabilise across hosts | CPUID always exits under VMX, so vmm's answer is the answer; pin it per run |
| I/O arrival order | Control every device | `poll` asks the simulator which handles are ready and what time it is; net and block backends are simulated |
| Multiple nodes | Run containers inside one single-vCPU VM | Run each node as its own VM, in parallel on separate cores; they meet only at hypercalls, merged in (virtual time, node, sequence) order |

The tender becomes a simulator client. Each VM runs freely until its next hypercall; the tender reports it to one simulation kernel, which decides the result from the seeded oracle and the fault plan, then resumes the guest. That kernel is the same one that schedules Wasm cells and in-process fibers.

Four more properties come almost free:

- **Static admission of guest images.** Disassemble the unikernel and refuse any `rdtsc`, `rdtscp`, `rdrand` or `rdseed` outside allowed sites. That catches the bindings above and any library that samples the cycle counter for entropy.
- **Cheap branching.** Branch points are hypercalls, which are C function calls, so the x86-64 ABI already lets the guest discard every vector register across them. A snapshot needs guest memory plus general registers, not full FPU state. Restoring means loading memory into a fresh VM and returning from the hypercall with the branch's chosen result.
- **Faults in the simulated world.** Partitions, drops, duplicates and delays on the simulated switch; torn or lost block writes; per-node clock skew; kill and restart a node from its disk image.
- **Antithesis vocabulary.** A ninth hypercall carries `always`, `sometimes` and `reachable` reports, so the same assertions work in-process, here, and under Antithesis itself.

Wasm cells need no hypervisor for determinism at all. Disable threads and relaxed SIMD, canonicalise NaNs, cap memory, and route every clock and random import through the oracle. Fuel then gives deterministic preemption, which the Mirage lane cannot. A bhyve VM or jail only adds isolation.

What this does not cover:

- Linux or BSD guests.
- More than one core inside a guest.
- Anything timing-sensitive, because compute takes no virtual time. A guest that spins on the clock without calling `poll` hangs; charge a fixed virtual tick per hypercall to break such loops deterministically.
- The model itself, which stays outside as recorded emissions.

Unverified: whether OCaml or any Mirage library changes MXCSR or the x87 control word, which the snapshot shortcut assumes stay at defaults.

### The trap as the enforcement point

The same trap that makes guests deterministic can enforce the emission discipline, so "the outbox is the only way out" becomes a property of the device model rather than of the cell's good behaviour. For a Solo5 guest a hypercall *is* the system call, since there is no kernel underneath it; for a Wasm cell a host import is. Both exit to code we own, so every write can be classified and converted on the way out without trusting the guest.

*(Diagram: the trap as enforcement · three device classes, one path out. The diagram was interactive in the source document and is not reproduced here.)*

Each exit is sorted by device class:

| Device | Class | What the trap does |
| --- | --- | --- |
| Block write | Internal | Lets it land on the run's ZFS or copy-on-write clone; rollback reverts it |
| Console | Observation | Forwards to telemetry; may drop, never blocks the guest |
| Intent channel: a framed, message-oriented hypercall or import | Emission | Validates the frame against the cell's schema and capability row, assigns an idempotency key from (run, node, sequence), and holds it |
| Raw network | Emission, compatibility mode | Terminated in the tender's userspace TCP stack and parsed at the application layer; refused unless a graph opts in |

What this buys:

1. **Emissions stay revertible until release.** While the tender holds a frame, rolling back means discarding it, so the acquisition/emission boundary moves outward from the guest to the outbox's release step. This is the output-commit rule from rollback-recovery, which Remus applied to whole VMs by buffering outbound packets until the checkpoint that produced them was safe.
2. **The guest cannot forge or bypass.** It has no other device, and the jail's pf blocks the host path, so a compromised cell can only produce frames the trap refuses. The idempotency key comes from the tender, not the guest.
3. **Secrets never enter the guest.** The guest names a capability; the outbox holds the API keys and makes the real call. Terminating TLS in the outbox is what makes the raw-network mode inspectable at all.
4. **Interception metadata is enforced in one place.** Rate limits, budgets and allowed intent kinds are checked at the trap, from the same capability row the type checker read.
5. **One binary for both modes.** In simulation the outbox is the simulator or a cassette; in production it calls out for real. The guest cannot tell, which is what makes a production trace replayable.

The cost is at the commit point. A request that needs a reply forces a release, and the strong form checkpoints the guest before releasing, as Remus does. That costs a memory snapshot per external round trip. The cheaper form releases on the guest's declared commit and journals the intent first; a crash between release and receipt then surfaces as `Uncertain` on restart and goes through reconcile. Choose per intent kind: checkpoint before payments and pushes, journal-only for model calls.

This reaches syscalls only where syscalls exit to us. A Linux guest's syscalls never leave its own kernel, so for a general-purpose guest the trap sits at the device: virtio-console or a vsock device, which bhyve does not yet have. That is another reason to keep the deterministic, enforced lane to Mirage and Wasm.

### Capability policy in Cedar

Cedar should make the decision at the trap, with CEL computing the numbers it needs. The question "may cell X emit intent kind Y against endpoint Z, given budget, rate, approvals and uncertainty" is exactly Cedar's request shape: principal, action, resource, context. Cedar can also answer, before an agent's policy edit ships, whether that edit widens access. AWS's Bedrock AgentCore Policy already uses it this way, putting a Cedar check on every agent-to-tool call.

What Cedar is (`cedar-policy` 4.13.0, September 2026; a CNCF sandbox project since October 2025):

- **Model.** `permit` and `forbid` policies scoped by principal, action and resource, refined with `when` and `unless`. The default is deny, and any matching `forbid` beats every `permit`. Entities have typed attributes and a parent hierarchy; actions can be grouped.
- **Guarantees.** Every policy terminates, evaluation is deterministic and roughly linear in policy size, and it does no I/O. The evaluator, authoriser and validator are modelled in Lean, and the Rust code is tested against those models.
- **Analysis.** `cedar-policy-symcc` compiles policies to SMT for cvc5 and checks equivalence, implication, "never errors" and "always denies", each with a counterexample. The Cedar Analysis CLI classifies a change as Equivalent, More Permissive, Less Permissive or Incomparable.
- **Partial evaluation (TPE, experimental).** Evaluate with some inputs unknown and get residual policies back.

How it fits the design:

| Step | Who does it |
| --- | --- |
| Count tokens used, rate in window, time from the oracle, uncertainty state | The trap, or a CEL cell, writing them into Cedar's `context` |
| Allow or deny the intent | Cedar, at the trap |
| Generate the baseline grant | One template link per (cell, API), produced from the cell's capability row at admission |
| Refuse an agent's policy edit that widens access | Cedar Analysis inside the mutation protocol's validate step; More Permissive without approval is refused |
| Make the trap cheap | At graph compile the cell and intent kind are known, so TPE leaves a residual over context only, cached under the graph hash |

A schema fragment and three policies (not yet run through the Cedar validator):

```cedar
namespace Harness {
  type Ctx = {
    intentHash: String, now: datetime, uncertainty: String,
    budget: { tokensRemaining: Long }, rate: { inWindow: Long },
    requestedTokens?: Long,
    approval?: { intentHash: String, at: datetime },
  };
  entity Graph = { hash: String };
  entity Fiber in [Graph] = { state: String };
  entity Cell in [Graph] = { fiber: Fiber, evidenceRank: Long };  // 2 = sampled
  entity Api;
  entity Endpoint in [Api] = { reversibility: String, ratePerMin: Long };
  action "intent.emit";
  action "llm.complete" in ["intent.emit"]
    appliesTo { principal: [Cell], resource: [Endpoint], context: Ctx };
  action "graph.hotReplace"
    appliesTo { principal: [Cell], resource: [Fiber], context: Ctx };
}
```

```cedar
// Irreversible intents need an approval bound to this exact intent, within 15 minutes.
forbid (principal, action in Harness::Action::"intent.emit", resource is Harness::Endpoint)
when { resource.reversibility == "irreversible" }
unless { context has approval
         && context.approval.intentHash == context.intentHash
         && context.now.durationSince(context.approval.at) < duration("15m") };

// LLM token budget: compare against headroom, never add, so overflow cannot disable it.
forbid (principal, action == Harness::Action::"llm.complete", resource)
unless { context has requestedTokens
         && context.requestedTokens <= context.budget.tokensRemaining };

// Hot replacement needs evidence of at least :sampled, an Active fiber of its own,
// and no Uncertain receipt outstanding.
permit (principal is Harness::Cell, action == Harness::Action::"graph.hotReplace",
        resource is Harness::Fiber)
when   { principal.evidenceRank >= 2 && resource.state == "Active" && principal.fiber == resource }
unless { context.uncertainty == "uncertain" };
```

Limits to design around:

- **Cedar decides; it does not count.** There is no aggregation, so budgets, rates and counts arrive precomputed in context.
- **Arithmetic is narrow.** `+`, `-` and `*` on integers only, no division. Overflow is a runtime error the validator does not catch, and an erroring policy is skipped, so an erroring `forbid` silently forbids nothing. Write limits as comparisons against headroom.
- **No ordering on strings or decimals.** Evidence grades become integer ranks.
- **Entities travel with the request.** The trap supplies the slice of entity data each decision needs.
- **Templates have two slots,** principal and resource.

From OCaml: no binding exists. The Rust crate already exposes JSON-string entry points (`cedar_policy::ffi`, with policy and schema pre-parsing), the same surface CedarJava and the npm Wasm build use. Options:

1. **Wrap it in a small Rust C library called through `ctypes`.** This is the simplest route.
2. **Compile it as a Wasm component run by the trap's own Wasm host.** This keeps the policy engine content-addressed and sandboxed like everything else.

Run symcc and Cedar Analysis out of process, in CI and at validate time; they need a cvc5 binary.

Why not the alternatives:

- **CEL** has no decision model and no analysis; it computes the context instead.
- **OPA/Rego** is too general for a hot path, and its termination is not guaranteed.
- **Zanzibar/OpenFGA** answers relationship questions (is this fiber part of this graph?), not limits on rates and budgets.

Sources: [Cedar changelog](https://raw.githubusercontent.com/cedar-policy/cedar/main/cedar-policy/CHANGELOG.md), [operators](https://docs.cedarpolicy.com/policies/syntax-operators.html), [templates](https://docs.cedarpolicy.com/policies/templates.html), [ffi module](https://docs.rs/cedar-policy/latest/cedar_policy/ffi/index.html), [symcc](https://docs.rs/cedar-policy-symcc/latest/cedar_policy_symcc/), [Cedar Analysis](https://aws.amazon.com/blogs/opensource/introducing-cedar-analysis-open-source-tools-for-verifying-authorization-policies), [Cedar joins CNCF](https://aws.amazon.com/blogs/opensource/cedar-joins-cncf-as-a-sandbox-project), [Cedar paper](https://arxiv.org/pdf/2403.04651), [How We Built Cedar](https://arxiv.org/pdf/2407.01688), [SymCert](https://www.amazon.science/publications/symcert-verifying-smt-based-policy-analyses).

## Verifying with Quint

`casselc/observability` already holds most of the verification machinery this design needs. Three things are already there:

- `ambiguousCall.qnt` models exactly the Uncertain-then-reconcile outbox.
- `retirement.qnt` models a lifecycle with zombies and adoption.
- `quintgo` checks recorded implementation traces against a Quint model.

What is missing is generating the model from a graph, and an OCaml-side recorder.

What the repo has (casselc/observability at `ca7ae8a`, 2026-10-02; Quint 0.32 pinned):

- **A modelling convention.** In `otel-chdb/model/TEMPLATE.md` each design choice is a `const`, and each configuration is an instance module: a design instance next to named mutants. Four kinds of check run against each model:
  - random simulation against invariants;
  - `w*` witnesses, checked as `not(w)` and expected to be VIOLATED, which proves the state is reachable;
  - scripted `*_test.qnt` runs;
  - bounded Apalache.
- **The external-call template.** `ambiguousCall.qnt` splits every call across a process boundary into `send`, `apply`, `applyAnswerLost`, `lose`, `timeout`, `retry` and `verify`. The caller's rule is a set of consts (`IDEMPOTENT`, `RESOLVE`, `SETTLE`). The invariant is `atMostOnce`. `verifyFirst` and `idempotentRetry` pass; `blindRetry` and `verifyEarly` are the named failures.
- **An outbox model.** `alertEvaluator.qnt` has an outbox ledger to a pager that "can take a send and lose the answer", with mutants `ackOnNoAnswer` and `blindWrite`.
- **quintgo.** A Go tool with two directions:
  - **Model drives code:** `quint run --mbt` traces drive Go code.
  - **Code checks against model:** code emits model steps, quintgo rebuilds the trace and replays it with `quint test`. Its verdicts are **disabled** (the model cannot take the observed step), **state** (projection mismatch) and **invariant**, with exit codes 0, 1 and 2.
  - Bindings are YAML.
  - Trace reconstruction orders steps per actor and reports gaps and overlapping incarnations.
- **CI verdict rules.** `ci/model-verdict.sh` fails on a design violation, an error, or an empty report. It warns on an unreached witness or a mutant that simulation missed. UNKNOWN is never a pass.

How the harness uses it:

| Harness piece | Reuse | Change needed |
| --- | --- | --- |
| Outbox, Uncertain, reconcile | `ambiguousCall.qnt` and its four-action template; `atMostOnce` as the commit-gate invariant; `blindRetry` and `verifyEarly` as permanent mutants | Instantiate per intent kind, with `IDEMPOTENT` read from the OpenAPI method |
| Lifecycle calculus | `retirement.qnt` as the closest structure | Write the six-state fiber model once by hand; the guard becomes an invariant, Uncertain blocking L-Unload a witness |
| Each graph | The design-plus-mutants instance pattern | **Generate** a Quint module per graph at admission: fibers, provides and requires as state, lifecycle actions guarded as the rules say. Nothing in the repo generates models yet |
| Runtime conformance | quintgo's model-level ITF format (`mbt::actionTaken`, `mbt::nondetPicks`, `obs::*`) and its step-log fields (`quint.actor`, `quint.seq`, `quint.outcome`) | The OCaml simulator writes that format per run; `quintgo validate` checks it with no Go code |
| Model-based testing | The edgepublish trick: every store write waits at a gate until the model releases it with `Ok`, `Fail` or `Ambiguous` | The entropy oracle *is* that gate: feed it `mbt::nondetPicks` |
| Hidden nondeterminism | modelcheck records the store double's side (`apply`, `lose`) as a second event source | The simulated net and block devices record their side, so an Uncertain outcome never replays as a plain failure |
| Assertions | Invariants are `always`; witnesses are `sometimes` | Same report rows and `model-verdict.sh` rules for both the simulator and Quint |

Evidence grades fall out directly:

- Apalache to depth N is `:exhausted` within that bound.
- `quint run` samples are `:sampled`.
- A conforming recorded trace is `:monitored`.
- Nothing is `:proved`.

The repo's `VERIFICATION.md`, with "Sees" and "Blind to" columns per technique, is the template for stating those grades honestly.

Known limits carried over:

- No liveness checking.
- Conformance is per actor and after the fact.
- Validation takes 4 to 8 seconds per trace on the TypeScript backend, and the Rust evaluator overflows its stack on long chains.
- Replaying ambiguous outcomes soundly needs an Apalache mode quintgo has not built.

## Property testing with Hegel

Hegel needs no new FFI: there is an official OCaml library, and it runs the same native engine jolt-hegel already uses. The work is wiring it in as the simulator's entropy source and deriving its generators from imported schemas. Quint checks the model; Hegel samples the real implementation against the same properties.

What exists:

- **The library.** `hegel` on opam (0.25.0 per its package page) runs libhegel, the Rust engine from `hegeldev/hegel-rust` behind a C interface, in-process.
- **The test style.** Tests are `let%hegel_test name tc = …` with values drawn inline by `draw tc (integers ~min_value:… ())`, so later draws can depend on earlier ones. Shrinking is built in, and a failure reports the minimal case with a replay line.
- **Framework support.** It works with Alcotest or any other test framework, and has generator and state-machine modules.
- **Shared engine.** jolt-hegel binds the same C interface (libhegel 0.36.3) from Jolt, Babashka and the JVM, so the OCaml and Clojure sides share one engine and its behaviour.

Gaps to close:

| Gap | What to do |
| --- | --- |
| No FreeBSD build of libhegel; published builds are Linux amd64/arm64 and Apple Silicon only | Build hegel-rust's C library on FreeBSD, point `HEGEL_LIBHEGEL_PATH` at it, set `HEGEL_LIBHEGEL_NO_DOWNLOAD=1`. Not yet verified to build |
| OxCaml compatibility unknown | Check the package's compiler constraints and C binding method; start on mainline OCaml |
| Replay from a stored case and nondeterminism detection | jolt-hegel's interface description lists `hegel_test_case_from_blob` and `hegel_test_case_is_nondeterministic`. If the OCaml package does not expose them, generate a small binding from jolt-hegel's language-neutral `abi.edn` |

Where it plugs in, in order of value:

1. **Hegel is the entropy oracle.** Every simulator choice is a Hegel draw: scheduling order, fault injection, the narrow tender's `poll` answers, and which receipts come back `Uncertain`. A failing multi-node run then shrinks to a minimal schedule and fault set. Under the hypervisor, libhegel stays in the host-side simulator; the guest's oracle hypercall becomes a draw, so nothing Hegel-related runs inside a unikernel. Keep all draws on the simulator's one scheduler domain.
2. **Generators come from schemas.** Derive generators from the internal type form, so OpenAPI, protobuf and JSON Schema types all get them. Size bounds such as `maxItems` become generator limits, and CEL residue becomes a filter. jolt-hegel's `hegel.malli` does the same from Malli schemas.
3. **Cordis witnesses get sampled evidence.** For each effect, "apply then inverse leaves the declared keys unchanged"; for each shared key, "both orders of two operations are equivalent". Passing runs earn `:sampled`, with the case count recorded.
4. **Graphs and scaffolds get stateful tests.** State-machine rules are the graph's inputs plus injected faults, checked against a reference model. Scaffolded services get "every request gets exactly one response"; scaffolded controllers get "a finalizer never releases while children are still observed". These are the same invariants as the Quint stubs.
5. **Replay and soak.** A failing case's replay key goes into the journal. Generated property suites join the soak step of the mutation protocol, content-hashed like everything else, so an agent cannot weaken them unnoticed.

Sources: [hegel.dev](https://hegel.dev/), [hegel on ocaml.org](https://ocaml.org/p/hegel/latest/README.html), jolt-hegel `README.md` and `docs/ABI.md` (chucklehead-dev/jolt-hegel at `237fbbf`).

## Build order

1. **Kernel as pure OCaml.** The lifecycle calculus (`target`, `relied`, `next`, `apply`) as total functions, with property tests and DSCheck. Port ensemble's `restart-plan` and `intensity`. No IO.
2. **The languages.** ML-syntax graph parser and row- and effect-typed checker. A CEL evaluator with the static cost estimator, tested against the cel-spec conformance suite and side by side against cel-go. Host functions only build intents, never perform them.
3. **Seeded Picos scheduler plus Eio mock.** The in-process simulator, with the Antithesis-shaped SDK and the single entropy oracle. It writes quintgo's ITF trace format, and a Quint model generated per graph checks every run. Every oracle choice is a Hegel draw, so failing runs shrink to minimal schedules.
4. **The DSL loader and schema import.** Graph compile, `:provides` and precedence, checked patches stored in Irmin. Importers for protobuf first, then JSON Schema, OpenAPI operations as intent kinds, and AsyncAPI channels as external ports, each emitting types, CEL residue and a lossiness report. Every definition is stored under the hash of its normalised syntax tree. CUE runs here, offline, as an import front end. The scaffolders for services and CRD controllers come next, since they reuse the importers.
5. **Outbox and intents.** Port jolt-sim's commit-gated session and JS1 receipts. LLM calls become the first emission kind, with cassettes for replay. Cedar decides at the trap, with partially evaluated residuals cached per graph hash, and Cedar Analysis refuses policy edits that widen access. Intent kinds may declare compensations.
6. **One VM per graph on stock bhyve.** Jail with VNET and default-deny pf, bhyve non-root under `allow.vmm`, zvol clone per activation, virtio-console control channel, slirp network, host-side outbox daemon. Patch bhyve's VM generation counter notification, or reseed in the guest on restore.
7. **Snapshot-backed soak.** Build bhyve with `BHYVE_SNAPSHOT`; soak a mutation by restoring branched copies of the VM and comparing their outcomes.
8. **OxCaml lane.** Build the kernel under OxCaml in a Linux guest or CI, with modes on, as a static check of confinement.
9. **Deterministic lane.** Fork the Solo5 hvt tender into a simulator client: time page, poll answered by the simulator, simulated net and block, image admission scan. Antithesis stays the option for non-Mirage guests.

## Risks and open questions

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| The CEL evaluator, its host functions and the Wasm runtime are the whole trust base for agent code | Modes stop at its boundary | Keep it small, fuzz it, and run it under the simulator |
| OxCaml does not build on FreeBSD | The static mode checks cannot run on the host | Mainline build on hosts; OxCaml in a Linux jail or guest now, a native port later |
| bhyve snapshots are off by default, amd64-only, unstable format | Snapshot-backed revert and soak depend on them | Rebuild with `BHYVE_SNAPSHOT`; treat ZFS clones as the durable revert and VM checkpoints as an optimisation |
| Clones share RNG and clock | Duplicated tokens, colliding idempotency keys | Patch bhyve's generation counter notify; reseed on restore; mint idempotency keys from the entropy oracle |
| No open deterministic bhyve | The system lane depends on a vendor or a large build | Restrict deterministic runs to Mirage and Wasm guests and use the narrow tender; Antithesis for anything else |
| 9p writes escape VM snapshots | Host-side files survive a VM rollback | Back every shared directory with its own ZFS clone, reverted with the VM |
| No vsock or balloon device in bhyve | Clumsier control channel; memory per VM is fixed | virtio-console for control; small unikernel guests |
| Inverses are untestable for some effects | Time passing, packets sent | The outbox is the only way out; audit with DTrace that nothing else leaves |

Open questions for you:

- **Your platform.** Does it already give one jail or one VM per agent, a host-side proxy, ZFS clones per run? The levels table assumes all three.
- **Unikernel or Linux guest?** MirageOS makes coeffects literal but is single-domain and its clock and RNG became link-time choices in 4.9; a small FreeBSD or Linux guest is easier to debug.
- **Clojure or OCaml for the cell surface?** The design keeps EDN so cells written for samizdat port over; an ML-syntax surface would let cells be typechecked by OCaml itself.

## Sources

- OxCaml: [modes](https://oxcaml.org/documentation/modes/intro/), [uniqueness](https://oxcaml.org/documentation/uniqueness/intro/), [stack allocation](https://oxcaml.org/documentation/stack-allocation/intro/), [kinds](https://oxcaml.org/documentation/kinds/intro/), [capsules](https://oxcaml.org/documentation/parallelism/capsules/), [get OxCaml](https://oxcaml.org/get-oxcaml/), [janestreet/parallel](https://github.com/janestreet/parallel), [Data Race Freedom à la Mode, POPL 2025](https://popl25.sigplan.org/details/POPL-2025-popl-research-papers/23/Data-Race-Freedom-la-Mode).
- OCaml effects and libraries: [manual 5.4, effects](https://ocaml.org/manual/5.4/effects.html), [typed effects status thread](https://discuss.ocaml.org/t/whats-the-status-of-typed-effects/18439), [Eio Switch](https://ocaml.org/p/eio/latest/doc/eio/Eio/Switch/index.html), [Miou Ownership](https://ocaml.org/p/miou/latest/doc/miou/Miou/Ownership/index.html), [Picos](https://github.com/ocaml-multicore/picos), [Lwd](https://raw.githubusercontent.com/let-def/lwd/master/lib/lwd/lwd.mli), [OCurrent](https://github.com/ocurrent/ocurrent), [Irmin](https://github.com/mirage/irmin), [Dynlink](https://ocaml.org/manual/5.4/api/Dynlink.html), [Owi](https://ocaml.org/p/owi), [lua-ml](https://github.com/lindig/lua-ml), [ocaml-mpst](https://arxiv.org/pdf/2005.06333), [Riot post-mortem](https://leostera.substack.com/p/riots-dead-long-live-riot).
- Hypervisors: [So you think you want to write a deterministic hypervisor?](https://antithesis.com/blog/deterministic_hypervisor/), [FreeBSD Foundation on Antithesis](https://freebsdfoundation.org/antithesis-pioneering-deterministic-hypervisors-with-freebsd-and-bhyve/), [Debugging in the Multiverse](https://www.antithesis.com/blog/multiverse_debugging/), [Antithesis assertions](https://antithesis.com/docs/properties_assertions/assertions/), [fault injection](https://antithesis.com/docs/environment/fault_injection/), [bedrock](https://github.com/dergoegge/bedrock), [bhyve(8), FreeBSD 15.0](https://man.freebsd.org/cgi/man.cgi?query=bhyve&sektion=8&manpath=FreeBSD+15.0-RELEASE), [Firecracker snapshots](https://github.com/firecracker-microvm/firecracker/blob/main/docs/snapshotting/snapshot-support.md), [rr](https://rr-project.org/), [Hermit](https://github.com/facebookexperimental/hermit), [MirageOS on OCaml 5](https://discuss.ocaml.org/t/mirageos-on-ocaml-5/15822).
- Reference repos read: [jlt-commons/writ](https://github.com/jlt-commons/writ), [jlt-commons/ensemble](https://github.com/jlt-commons/ensemble), [ObneyAI/grain](https://github.com/ObneyAI/grain), [mycelium-clj/mycelium](https://github.com/mycelium-clj/mycelium/blob/main/README.md). FreeBSD `main` source checks: `usr.sbin/bhyve/vmgenc.c`, `share/mk/src.opts.mk`, as of 2026-10-09.
