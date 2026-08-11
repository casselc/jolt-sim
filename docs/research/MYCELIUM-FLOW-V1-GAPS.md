# Minimal Mycelium-shaped workflow on Jolt: v1 boundaries

The source reference for this slice is `mycelium-clj/mycelium` `main` at
`df45132fb5f70f46e6082ffe2b703efef4ce1a44` (2026-07-17). Its workflow model is
a finite accumulating-data DAG per invocation: cell specs declare handlers,
input/output schemas, and required resources; workflow aliases select cell
specs and parameters; `:start`, `:pipeline`, edges, and predicate dispatches
determine routing.

`jolt.sim.flow/compile-workflow` implements the smallest recognizable subset on
the existing immutable `jolt.sim.kernel` machine. It supports a canonical cell
spec registry, aliases using a keyword or `{:id ... :params ...}`, selected
resources, one `:start` plus `:input`, closed inspectable Malli input/output
schemas, explicit finite FIFO edges, deterministic fan-out, stable task and
message IDs, and fixed normal/drop/reject edge regimes. `jolt.sim.session` owns
all stepping, branch preview, journaling, datafication, navigation, and taps.
The manifest and spec registry remain separate canonical data artifacts; a
Session is the dynamic execution/inspection capability. Ordinary cell code has
the shape `(handler ctx state data) -> {:state next-state :data output-delta}`.
Resources and alias parameters arrive through `ctx`, never through the
accumulating data map. The output delta is validated and merged into the input;
the resulting accumulated map is what downstream cells receive.
This v1 defines no reserved `:mycelium/*` engine keys, so it preserves and
merges the entire input rather than stripping an engine-owned key subset.

Handlers in this first compiler are trusted **and pure**. Session branch
preview evaluates candidate transitions, so a handler that performs I/O or
mutates closed-over state would duplicate or leak effects across previews.
Effectful boundaries stay in the retained-worker lane until a later flow layer
has an explicit effect-intent/commit protocol. This is a contract of the v1
surface, not a claim that arbitrary upstream Mycelium handlers are pure.

Compilation fails closed on unknown keys in declared manifest/spec/cell/edge/
entry records, missing cells/resources, invalid edge targets or capacities,
unreachable cells, malformed entry data, and any acyclic path that cannot
supply a downstream cell's required top-level input keys. This declared-key
coverage proves neither handler semantics nor arbitrary Malli subsumption.
Later experiment reporting must call missing required observations
`:inconclusive`, not infer a pass from topology or schema validation.

The optional `:entries` vector is a simulator-only finite batch seam. It runs a
bounded input batch through one execution and intentionally shares each cell's
explicit state across those inputs, so FIFO, blocking, and capacity behavior
can be discriminated without pretending that a workflow is a server or that
the entries are independent workflow invocations.

The following upstream features are deliberately not implemented yet:

- `:pipeline` expansion and `:end`;
- source-to-target and transition-map edge shorthand;
- labeled predicate `:dispatches`;
- joins and schema/path propagation across branches;
- retry, timeout, resilience, or asynchronous handler semantics.

More importantly, the existing HTTP/SQLite/TCP outbox and Maelstrom examples
are long-lived services with repeated inputs, mailbox backpressure, component
lifecycle, and concurrent sessions. A finite Mycelium invocation is not that
service. Redoing those examples needs a distinct core.async.flow-style
process/link layer whose long-lived cells may invoke these compiled finite
workflows. That layer can substitute real, simulated, or hybrid boundary
providers while preserving the original application functions. It must not be
implemented by mutating Mycelium's finite-workflow semantics or by rebranding
one workflow invocation as the server.

That later layer, not this workflow compiler, owns process lifecycle operations
such as start, pause, drain, resume, and stop.
