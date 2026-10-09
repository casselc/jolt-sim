# Cordis Harness: Integrated Design

Oct 9, 2026 · @Chuck

An agent harness that can rewrite itself while it runs, built on the Cordis formalism. Agents edit typed graphs of small pure cells; every effect is classified by where it lands; anything that leaves the system goes through one trap and one outbox, held until commit; and the whole thing runs deterministically, inside FreeBSD and bhyve, under simulation and model checking. This doc consolidates the two earlier ones and every decision since.

## At a glance

*(Diagram: the whole system · from specs and agent edits to the outbox. The diagram was interactive in the source document and is not reproduced here.)*

Specs and agent edits both pass the same change gates into a content-addressed store; fibers run cells as turns; every emission leaves through one trap and one outbox; and the simulator, Quint and Antithesis check all of it.

## Decisions

Every choice below was argued in the companion docs or in conversation. Each row states what was chosen, what was turned down, and the reason.

| Area | Decision | Rejected | Why |
| --- | --- | --- | --- |
| Formalism | Cordis components and fibers, plus an Uncertain lifecycle state | Ad hoc lifecycle per project | Cordis supplies the reactive lifecycle and inverses the projects lacked; Uncertain is the projects' one addition Cordis lacks |
| Effects | Three classes by the system boundary: internal (inverse), acquisition (release), emission (withhold until commit) | Inverses for everything | Cordis itself concedes emissions have no inverse; the projects had already built the emission discipline |
| State | Transactional turns: a failed turn is discarded | Per-step inverses for ordinary state | Turns make state revert for free; inverses remain only for acquisitions |
| Emissions | One outbox: intent, commit, release once, receipt Definite, Failed or Uncertain; reconcile, never blind retry; optional compensation per intent kind | Retry scopes as in MuleSoft | Retrying a non-idempotent call can duplicate it; compensation gives a coarse inverse after release |
| Enforcement | The hypervisor trap is the only way out of a guest; hypercalls and Wasm imports become intent frames | Trusting cells to emit intents | A compromised cell cannot bypass a device it does not have |
| Host language | OCaml 5.4 mainline; OxCaml modes as erasable annotations, checked in an OxCaml build | OxCaml only; Clojure core | Mainline builds everywhere; modes add static confinement where available |
| Cell bodies | CEL, extended only by macros and acyclic function libraries; Wasm components for the rest | A custom SCI-like interpreter; DataWeave-style recursion | CEL has a spec, a conformance suite and a static cost bound; recursion would lose the bound |
| Graph language | ML syntax: row types, effect rows in the arrow, variants with exhaustive routes, assets, `use`, typed holes | EDN graphs with Malli | Types do the schema chain, effect checking and dispatch coverage as inference |
| Identity | Every definition addressed by the hash of its normalised syntax tree; names point at hashes; stored in Irmin | Name-and-version pins | Edits cannot break bystanders; replay, cache and anti-weakening keys come free |
| Caching | Assets memoised by (cell hash, input hashes); freshness drives reactivity | Task graphs that always re-run | Soaks and replays recompute only what changed |
| Schemas | One internal type form; importers for protobuf, JSON Schema, OpenAPI, AsyncAPI; CEL residue for what types cannot say | CUE as the schema IR | CUE is pre-1.0, has no cost bound, no row or effect types, no OCaml binding |
| CUE | Offline import front end and deployment config only | Runtime use; linking libcue | Its strength is normalisation and merging; libcue embeds the Go runtime |
| Policy | Cedar decides at the trap; CEL computes the context; Cedar Analysis gates policy edits | CEL alone; OPA/Rego; OpenFGA | Cedar is analysable: it can answer "does this change widen access?" |
| Scaffolding | Generated router and types, hand-filled typed holes; re-scaffold is a type check | One-shot stubs; add-only merges | The generation-gap pattern survives regeneration |
| Determinism | In-process simulator with one entropy oracle; a narrow deterministic tender for Mirage and Wasm guests; Antithesis for anything else | Building a general deterministic bhyve | Cooperative guests need no instruction counting |
| Property testing | Hegel draws drive the oracle; generators come from schemas | QCheck-style separate generators | One shrinking engine across simulator, OCaml and Clojure |
| Model checking | Quint, reusing casselc/observability's conventions and quintgo | P language | The tooling already exists in your repo |
| Platform | Your FreeBSD platform as built: a VM and jail per agent, child jails per tool call, central pf, ZFS clones and snapshots per step; OxCaml in a Linux jail now, a native port later | Linux hosts | Your platform; the port is 7 to 14 person-weeks |
| Export | Open Workflow Specification (Serverless Workflow 1.0) as a lossy export | Importing from it | It lacks Uncertain, compensation and cost bounds |
| Guests | MirageOS unikernels on Solo5, plus Wasm cells | Small Linux or FreeBSD guests | Granted devices are the declared capabilities; hypercalls make the trap exact and the narrow tender possible |
| Serialization | EDN for authoring; the Preserves data model and canonical binary for hashing, trap frames and journals | Canonical EDN with deterministic CBOR; JSON, Transit, Protobuf as the store | Agents keep EDN; one canonical binary, annotations outside the hash, and a capability slot; we own small OCaml and Clojure codecs tested against the Preserves sample suite |

## The model

The system is Cordis (Shi, Zhang and Cui, *A Programming Paradigm for Spatiotemporal Composability*) with five additions. A cell is a Cordis component; a graph composes components; a mutation is a revision of a running fiber.

From Cordis:

- **Components** declare what they require (keys they read), what they provide (keys they may install), and an effect iterator whose steps each return an inverse.
- **Fibers** are running instances with a lifecycle. They activate when their target view (who provides each required key) is satisfied and deactivate when it changes. The orchestrator only inserts and retires; the rest happens by itself.
- **The guard:** a provider may not unload while any installed fiber still resolves a key to it.
- **Confinement:** a cell reads and writes only its declared keys.
- **Commutativity:** the provider of a shared key carries proof that its operations commute, so components interleave and revert in any order.
- **Metatheory:** removing a component leaves the state as if it never ran; providers activate before and outlive their consumers; with acyclic dependencies the system always quiesces; the quiescent state depends only on the final configuration. That last result is what makes hot replacement and incremental reconciliation sound.

What this design adds:

1. **Effect classes by the system boundary.** Internal effects get inverses; acquisitions get release functions; emissions are withheld until commit and released at most once. Cordis §6.1 draws this boundary but does not build on it.
2. **Uncertain.** An emission whose outcome is lost puts the fiber in Uncertain, which accepts only reconcile and blocks unloading. This comes from jolt-sim, JS1 and samizdat's idempotency fence.
3. **Turns.** A fiber step commits all its writes and intents or none, so ordinary state needs no inverses.
4. **Compensation.** An intent kind may declare a compensating intent, a coarser inverse used when an already-released emission must be undone.
5. **Evidence on every witness.** Cordis assumes its two proofs (inverses revert; operations commute). Here each carries a grade: `:proved`, `:exhausted`, `:sampled`, `:monitored`, `:compensated` or `:unknown`, and policy reads the weakest.

*(Diagram: fiber lifecycle · Cordis's four states plus Uncertain. The diagram was interactive in the source document and is not reproduced here.)*

The one premise that changes is the guard: a fiber may not unload while any emission of its own is Uncertain. Progress then depends on reconciliation finishing, which is a policy deadline rather than a theorem.

## The languages

Four languages, each doing one job: a typed graph language for wiring, CEL for most computation, an internal type form fed by importers, and Cedar for policy. Wasm components cover the cells CEL cannot express.

| Tier | Job | Bound | Confinement |
| --- | --- | --- | --- |
| Graph language (ML syntax) | Wiring, routes, signatures, functors, assets | Typechecked at admission | Capability and effect rows |
| CEL | Most cell bodies; guards, invariants, schema residue | Static cost estimate at admission; runtime limit | Only declared variables and functions exist |
| Wasm component | Loops, recursion, heavy parsing | Fuel | Only WIT imports, each one trapped |
| Cedar | Allow or deny at the trap | Terminates, no I/O | Schema-validated; analysable |

### The graph language

Row-typed records replace mycelium's schema chain, effect rows in the arrow replace `:requires` and `:effects`, and variants with exhaustive routes replace dispatch coverage checks. Our own checker (Hindley–Milner with row and effect polymorphism) runs at admission; nothing is compiled natively, so old definitions are simply garbage on reload.

```ocaml
cell critic : { diff : Diff.t; state : { attempts : int } }
           ->{ Llm.complete, Repo.read } Verdict.t =
  let ctx = Repo.read diff.path
  use resp = Llm.complete { prompt = [ctx; diff.patch]; max_tokens = 4000 }
  let r = Review.parse resp ?? Review.unparseable
  (if r.verdict == Pass then Pass r else Fail r)
    with state.attempts := state.attempts + 1

graph review_loop (Author : AUTHOR) = {
  asset draft   = Author.write
  asset verdict = critic draft      ~fresh:on_change
  asset shipped = gate verdict
  route verdict {
    Pass _ -> shipped
    Fail r -> draft <~ r.notes
  }
  invariant (critic before gate)
}
```

- **`use`** splits a body into a chain of CEL turns at each wait; each `use` is a commit point.
- **`with`** updates a path; **`??`** supplies a fallback for an optional value.
- **Assets** are the acyclic, memoised part of the graph; **routes** carry loops back into an asset's inputs.
- **Typed holes** (`?todo`) type-check and run as pending, so agents and scaffolds can leave work unfinished.
- **Edits** are structural patches on the syntax tree, guarded by the expected hash, as upstream mycelium's `myc patch` does. A patch that narrows a signature needs approval.

### Cells in CEL

CEL is pure, has no unbounded loops, evaluates in linear time and has a static worst-case cost estimate. A cell is a pure handler, (state, input) to (state', output, intents); host functions such as `llm.complete` only build intent values.

CEL is extended only by expansion, never by new semantics:

- **`fold` and `fold_while`** are macros over CEL's existing comprehension. Values carry size limits so an accumulator cannot blow up.
- **Two-variable comprehensions** (index and value, key and value).
- **Function libraries:** typed CEL functions in content-addressed files, imported with `uses`; the call graph must be acyclic.
- **Pure cells as functions:** a cell that emits nothing can be called like a library function.
- **A variant `match` macro,** checked for exhaustiveness.
- **Format readers and writers** (JSON, XML, CSV) and parsers as host functions returning optionals, kept few and reviewed.

Recursion is the line not crossed; that is where DataWeave lost its cost bound. No OCaml CEL implementation exists, so one is written against cel-spec's conformance suite and tested side by side with cel-go, with map iteration sorted for determinism.

### Schemas

One internal type form (records with rows, variants, lists, maps, optionals, scalars with formats). Each import produces types, CEL residue for constraints types cannot express, and a lossiness report.

- **Protobuf:** close to lossless; `buf breaking` is the compatibility check.
- **JSON Schema:** `allOf`, `not`, `if`/`then`/`else` and undiscriminated `oneOf` become a looser type plus residue; objects are closed at trust boundaries.
- **OpenAPI:** each operation becomes an intent kind. The method sets the retry rule, `securitySchemes` names the credential, `servers` is the egress allowlist.
- **AsyncAPI:** channels and send or receive operations become the graph's external ports.
- **CUE** runs offline as an import front end with `Strict` on, and for deployment config; it is not the IR.
- **Identity** is document, name and content hash, which also fixes Cordis's key-collision gap (§6.6).

### Policy in Cedar

The trap asks Cedar whether cell X (principal) may emit intent kind Y (action) against endpoint Z (resource), given budget, rate, approvals and uncertainty (context). CEL or the trap computes those numbers, because Cedar does not count.

```cedar
forbid (principal, action in Harness::Action::"intent.emit", resource is Harness::Endpoint)
when { resource.reversibility == "irreversible" }
unless { context has approval
         && context.approval.intentHash == context.intentHash
         && context.now.durationSince(context.approval.at) < duration("15m") };
```

- **Baseline grants** are template links generated from each cell's capability row.
- **Policy edits** by an agent go through Cedar Analysis; a More Permissive change without approval is refused.
- **Partial evaluation** at graph compile leaves a context-only residual per graph hash, so the trap check is cheap.
- **Two traps to avoid:** integer overflow makes a `forbid` error and silently stop forbidding, so limits are comparisons against headroom; and evidence grades become integer ranks, since Cedar cannot order strings.
- **From OCaml:** wrap the Rust crate's JSON entry points in a small C library, or run Cedar as a Wasm component.

### Serialization

Use EDN where agents and people read and write, and Preserves for everything that is hashed or crosses the trap. Its data model and canonical binary give content addresses, frames and journals in one format. The format has five jobs:

- text that agents and people read and write;
- a canonical byte form, because content addressing hashes it;
- tagged extension for hashes, intents and capabilities;
- compact frames across the trap;
- an append-only journal.

No single format is best at all five.

| Format | For | Against |
| --- | --- | --- |
| EDN | Native to samizdat, jolt and the agents' Clojure; keywords, symbols, sets; tagged literals (`#hash`, `#intent`) for extension; readable diffs | No canonical form in the spec (map and set order, number spellings); text only; thin OCaml support. bbagent's execution SPI already defines canonical EDN (sorted maps and sets) and hashes it as `sha256:` coordinates, so the fix exists |
| JSON, with RFC 8785 canonicalisation (JCS) | Every language and tool reads it; JCS gives a canonical form; matches OpenAPI and JSON Schema directly | No keywords, symbols, sets or tags, so they are encoded by convention; numbers are doubles; noisy for agents to edit |
| Deterministic CBOR (RFC 8949) | Binary, compact, a specified deterministic encoding, numbered tags for extension, streams well; a natural fit for trap frames and journals | Not readable without a tool; keywords and symbols need registered or private tags; editing goes through another format |
| Transit | EDN's data model carried over JSON or MessagePack; native in Clojure | No canonical form; mostly a Clojure-ecosystem format; caching rewrites make bytes depend on context |
| Canonical S-expressions (Rivest csexp) | A canonical byte form by design; a readable "advanced" form; natural in OCaml (Jane Street's sexp tooling) | No native maps, sets or numbers beyond atoms, so everything is convention; unfamiliar on the Clojure side |
| Preserves (Syndicate) | One data model with readable text and a canonical binary form; records, sets and an "embedded" value type designed for capabilities | Small ecosystem with one main maintainer; no OCaml, Clojure or Go library, so we write the codecs |
| Amazon Ion | Readable text and binary forms; annotations; Ion Hash defines hashing | Heavier; a small OCaml ecosystem; symbol tables complicate canonical bytes |
| Protobuf | Compact, typed, excellent tooling and `buf breaking` | Not canonical; schema-first, so poor for open, agent-written data; keep it as an import source, not the store |
| Bencode | Canonical by design (sorted keys); trivial to implement; already in your stack through nREPL and jolt-bencode | Only integers, byte strings, lists and dictionaries; no floats, keywords or tags |

The recommendation, in three layers:

1. **Authoring and review: EDN** (or the ML surface, which round-trips to it). Agents already write it.
2. **Hashing: the Preserves data model and canonical binary.** EDN converts by a fixed, tested mapping, and annotations carry provenance outside the hash.
3. **Trap frames and journals: Preserves binary as well,** so a value has the same bytes whether it is hashed, sent or journalled. Deterministic CBOR is no longer needed.

#### Why Preserves

Preserves comes from the Syndicate project. One data model has a text syntax and a binary syntax, and the canonical binary has three rules: no annotations, set elements sorted by their encoded bytes, and dictionary keys sorted the same way. The spec names it as the form to hash and sign.

- **Annotations sit outside the value.** A source location or provenance note on a definition does not change its hash.
- **Records give named types.** `<intent …>` and `<receipt …>` are typed values, not reader conventions.
- **Embedded values are a capability slot.** `#:…` holds one, and Preserves Schema types it with `#:Iface`.
- **Syndicate's sturdy refs are a model for attenuation.** They are macaroons: an HMAC chain over canonical bytes, with caveats that match or rewrite messages, so anyone holding one can narrow it offline. The caveat language is far weaker than CEL and has no expiry.

The costs:

- **No OCaml, Clojure or Go library exists.** A codec is about 2 weeks in OCaml and 2–3 in Clojure; Schema code generation is 2–3 weeks more. Clojure needs wrapper types for doubles (`-0.0`, NaN), byte strings, records and embeddeds.
- **One maintainer has written about 95% of it.** The spec is at 0.996 and the Rust and JS packages are release candidates. The binary format changed in 2023–24 and has been stable since 0.996, so the hashed envelope carries a format version.
- **Implementations drift from the spec.** Python treats `1` and `1.0` as duplicate keys. Our codecs are tested against the spec's `tests/samples.pr` (about 180 cases), not against another library.
- **Embeddeds in content-addressed definitions carry a stable capability ID,** never a per-connection wire ID.
- **Schema is weak at constraints:** no ranges, regexes or real optional fields. CEL keeps carrying constraints, and Schema and Path stay optional.
- **No keywords and no nil.** The EDN edge maps keyword to symbol and `#tag` to record.

#### Examples

The review loop as agents write it, in EDN:

```clojure
{:graph/name :review-loop
 :params  [{:name Author :sig AUTHOR}]
 :assets  {:draft   {:call [Author/write]}
           :verdict {:call [critic :draft] :fresh :on-change}
           :shipped {:call [gate :verdict]}}
 :routes  [{:on :verdict
            :cases [[[:Pass '_] [:to :shipped]]
                    [[:Fail 'r] [:feed :draft 'r.notes]]]}]
 :invariants [[:before critic gate]]}
```

The same graph in Preserves text, the form that is hashed (364 canonical bytes). The `@<source …>` annotation is left out of the hash:

```
@<source "review_loop.sz" 12>
<graph review_loop
  {params: [<param Author AUTHOR>]
   assets: {draft:   <asset <call Author.write []> {}>
            verdict: <asset <call critic [draft]> {fresh: on-change}>
            shipped: <asset <call gate [verdict]> {}>}
   routes: [<route verdict [<case <Pass _> <to shipped>>
                            <case <Fail r> <feed draft r.notes>>]>]
   invariants: [<before critic gate>]}>
```

An intent frame leaving the trap, and the three kinds of receipt:

```
@<provenance {fiber: review_loop/critic turn: 41}>
<intent llm.complete
  {key: #x"5a1e9c0d8b7f2e11" class: emission compensation: none
   args: {prompt: "..." max_tokens: 4000}}
  #:<cap 7>>

[<receipt #x"5a1e9c0d8b7f2e11" <definite {tokens: 1834 body: <blob #x"c0ffee">}>>
 <receipt #x"77aa" <failed timeout>>
 <receipt #x"91bc" <uncertain {sent: #t acked: #f}>>]
```

A Preserves Schema for intents:

```
version 1 .
embeddedType Cap.Ref .
Intent      = <intent @kind symbol @meta IntentMeta @cap #:any> .
IntentMeta  = {key: bytes class: IntentClass compensation: any args: any} .
IntentClass = =internal / =acquisition / =emission .
```

## Scaffolding

A spec or a CRD produces a working skeleton: generated, regenerable parts (router, types, validation, error table, policy and model stubs, tests) around hand-filled typed holes. This is MuleSoft APIkit's shape with the generation-gap discipline of oapi-codegen, ConnectRPC and Smithy, and content addressing in place of merging.

### Services from OpenAPI, AsyncAPI or protobuf

| Generated | Contents |
| --- | --- |
| Router | One exhaustive `route` over operations keyed by `method path content-type`, `rpc`, or `channel action`; mismatches map to 404, 405, 406, 415 as APIkit does |
| Flow per operation | Typed input; output a sum over declared responses; body a typed hole defaulting to 501 |
| AsyncAPI | Receive operations become inbound routes; send operations become intent kinds |
| Protobuf | One flow per rpc; streaming rpcs get stream-typed holes |
| Validation | CEL residue at the boundary; strictness is a policy setting |
| Error table | Validation and routing faults, declared error arms, 500 or 503 for unhandled failures, 202 plus a reconcile handle for Uncertain |
| Stubs and tests | Cedar actions per operation; a Quint model with "exactly one response per request"; contract tests from the spec's examples; mock mode with per-request status and example selection |

```ocaml
(* generated from petstore.yaml, get:/pets/{id} *)
type get_pet_out = Ok_200 of Pet.t | Not_found_404 of Problem.t | Error_default of Problem.t
flow get_pet : get_pet_in ->{ Store.read } get_pet_out = ?todo   (* yours *)
```

The reply to a client is itself an emission: if a flow's work emits intents, its reply waits until they commit.

Re-scaffolding is a type check, not a merge. Each case:

- **Added operation:** a new hole.
- **Removed operation:** its filled flow is kept and flagged as orphaned.
- **Changed signature:** a type error at exactly that flow.
- **Unchanged:** reused by hash.

oasdiff or `buf breaking` classifies every change before it goes through validate and soak.

### Controllers from CRDs

A Kubernetes controller is the Cordis lifecycle under other names, so a CRD scaffolds one directly.

| Kubernetes | Here |
| --- | --- |
| Level-triggered, idempotent reconcile | Target view (desired) against committed view (observed); the runtime diffs and emits apply intents |
| `observedGeneration` | The hash of the spec the status was computed from |
| Finalizers | The Unloading guard, released only when observed state shows the children gone; an Uncertain cleanup holds it |
| Owner references, garbage collection | The fiber tree, retired as a cascade in reverse dependency order |
| Server-side apply field managers | Commutativity per key; `listType: map` and `mapType: granular` set the grain |
| `x-kubernetes-validations` CEL, transition rules with `oldSelf` | Imported unchanged as CEL guards over old and new views |
| RBAC markers | Cedar action stubs |

The reconcile hole takes Metacontroller's pure shape: (parent, observed children) to (desired children, status, resync). It is a CEL cell. Child templates use KRO's convention of inferring dependencies from `${}` references: create in topological order, delete in reverse, reject cycles, gate children with `readyWhen` and `includeWhen`. Several cells can form a Crossplane-style pipeline that accumulates desired state over one observed snapshot, where anything dropped is deleted.

## The runtime

The kernel is ensemble's shape: every lifecycle decision is a total pure function over variants, and a thin Eio shell can change state only by calling them. Around it sit turns, assets, the outbox and the trap.

```ocaml
type life =
  | Inactive
  | Reloading of { rest : (Ctx.t, unit) iter; acc : Ctx.t inverse list; committed : view }
  | Active    of { acc : Ctx.t inverse list; committed : view }
  | Uncertain of { acc : Ctx.t inverse list; committed : view; pending : intent }
  | Unloading of { acc : Ctx.t inverse list; committed : view }
  | Failed    of error

val target : registry -> Fiber_id.t -> view option   (* None = should not run *)
val relied : registry -> Fiber_id.t -> bool
val next   : registry -> Fiber_id.t -> rule option
val apply  : registry -> Fiber_id.t -> rule -> registry * Ctx.t inverse list
```

- **Turns.** Each fiber step runs against a snapshot of its declared keys and commits writes and intents together or not at all, after Spritely Goblins.
- **Inverses for acquisitions.** One Eio `Switch` per activation runs release hooks last-in-first-out, beside a log recording which inverse belongs to which step. A Miou-style ownership check raises if a fiber reaches Inactive still holding something. OxCaml's `once` covers "at most once"; the leak check covers "at least once".
- **Masked teardown.** Unloading runs protected from cancellation, so an inverse is never torn halfway.
- **Assets.** Outputs are memoised by (cell hash, input hashes). The memo for emission cells is the replay cassette in simulation and an idempotent receipt cache in production. An asset's freshness rule re-targets the fibers that read it.
- **Reactivity.** A key gains an acquire hook when some fiber's committed view first names it and a release hook when the last one drops it, as Lwd does.
- **Versioned userspace.** Graphs, cells, libraries and policy live in Irmin under their hashes: a branch per candidate revision, a reset to roll back.

### The outbox

Every emission is an intent: validated against its schema and capability row, given an idempotency key from (run, node, sequence), held, released only after its turn commits, and answered by a receipt.

| Receipt | Meaning | What happens |
| --- | --- | --- |
| Definite | The effect happened, result known | Flows on as input |
| Failed | It did not happen; RFC 7807 problem details | Routed as a typed error |
| Uncertain | Published, outcome lost | Fiber enters Uncertain; only reconcile against that exact publication is allowed |

- **Pipelining.** An intent may refer to an earlier one's future receipt, so a chain shares one commit point; if the first is Uncertain, the rest wait.
- **Retry rules come from the intent kind.** Idempotent kinds (from the OpenAPI method or a declared key) may retry; others reconcile.
- **Compensation.** A kind may declare a compensating intent; unloading a component whose emissions were already released issues those compensations last-in-first-out, graded `:compensated`. Kinds marked `irreversible` need approval.
- **Credentials** live in the outbox; guests only name capabilities.
- **Commit cost.** The strong form checkpoints the guest before release; the cheap form journals the intent first and accepts that a crash between release and receipt surfaces as Uncertain. The choice is made per intent kind.

#### Where the outbox runs

Two rules decide it. The journal sits outside anything that is snapshotted, cloned or restored, or a rollback would forget receipts and send intents again. And when one snapshot is restored into several VMs, the branches must not each send the same emission.

| Option | For | Against |
| --- | --- | --- |
| In the guest | Shortest path | Rolled back with the guest; one copy per branch; credentials in untrusted code. Ruled out |
| In the tender | Intent frames are produced here; outside guest snapshots | Dies with the VM; credentials and retries bloat the trusted tender; one copy per branch |
| The agent's existing jail | Per agent; outlives VM restarts; shared by branches | Needs a dataset that step snapshots skip; credentials share a jail with the VM's host side; `pf` cannot single it out |
| An outbox child jail per agent | Credentials isolated; `pf` allows egress from outbox jails only; own dataset; a failure stays with one agent; shared by branches | N more jails; limits shared across agents need coordinating elsewhere; no shared provider connections |
| A central daemon next to `pf` | One exit point; global quotas and deduplication; shared connections; one audit log | Holds every agent's credentials; single point of failure; noisy neighbours; restoring one agent's state is awkward |
| The MCP/A2A gateway | Already on the tool-call path and knows the tool schemas | Covers MCP and A2A only, so raw network and provider calls need a second outbox |

The choice combines them:

- **A child jail per agent runs the outbox.** It holds the journal, on a dataset that step snapshots and clones skip, plus the agent's credentials, retries and reconcile.
- **A central service next to `pf` enforces shared limits only.** It holds no credentials.
- **`pf` lets traffic out of the system only from outbox jails,** so the trap and `pf` agree on the boundary.
- **The tender frames and validates intents,** nothing more.
- **MCP and A2A calls go from the outbox jail through the gateway,** keeping one exit point per agent.

**Branches.** Each VM restored from a shared snapshot gets a fork ID. The outbox records every branch's intents but releases them only for the branch chosen to continue; the others receive simulated receipts from the replay cassette. Identical branches draw identical idempotency keys from the oracle, so a duplicate is caught even if the hold-back fails.

### The trap

For Mirage and Wasm guests a hypercall or host import is the system call, so every exit can be classified without trusting the guest.

*(Diagram: the trap · three device classes, one path out. The diagram was interactive in the source document and is not reproduced here.)*

| Exit | Class | Trap action |
| --- | --- | --- |
| Block write | Internal | Lands on the run's ZFS clone; rollback reverts it |
| Console | Observation | Telemetry; may drop, never blocks |
| Intent frame | Emission | Schema and capability check, Cedar decision, idempotency key, hold |
| Raw network | Emission, opt-in | Terminated in the tender's own TCP stack and parsed; refused by default |

While the trap holds a frame, rollback simply discards it: an emission stays revertible until release. This is the output-commit rule Remus applied to whole VMs.

## Hosting

Your platform already provides most of what this section needs. Each agent gets a VM and a jail; tool calls run in child jails; all traffic passes one central `pf`; ZFS clones and snapshots are taken between steps; bhyve is patched so restored VMs get a new generation and reseed their randomness on resume; and snapshots are supported. Guests are unikernels.

The same lifecycle runs at each level, and each level's inverse is the fallback when the level inside it cannot revert:

| Level | Component | Inverse of what it acquires | Confinement |
| --- | --- | --- | --- |
| Host | The system boundary | None; emissions leave only through the outbox behind the central `pf` | Central `pf` sees every flow |
| Jail per agent | Supervisor for the agent | `jail -r`; destroy its clones | VNET, rctl, `allow.vmm` |
| VM per agent | The agent's unikernel runtime; its graphs run as fibers inside | Roll back to the snapshot taken before the step | Capsicum-sandboxed bhyve; the device list is the coeffect set |
| Child jail per tool call | One acquisition or emission being carried out | Destroy the child jail and roll its dataset clone back | Its own clone, its own `pf` rules |
| Fiber | One cell activation | Turn discard; release hooks LIFO | Declared keys only; CEL cost bound or fuel |

Child jails change how tool calls are classified. A tool call whose effects stay inside its child jail's clone (editing files, running a build, running tests) is **internal**: rolling the clone back reverts it, so it needs no outbox and no reconcile. Only a call that sends traffic past the central `pf` is an **emission**. That moves most agent work out of the expensive path and makes `pf` the precise statement of the system boundary: whatever it lets out is an emission, whatever it blocks is not.

Platform facts that still shape the design:

- **Parallelism comes from many VMs.** A unikernel runs one domain, and the deterministic lane runs one vCPU.
- **The model runs outside the deterministic boundary.** Every inference is an emission, recorded on the way out and replayed from a cassette in simulation.
- **Idempotency keys come from the entropy oracle,** not the guest's RNG, so they stay deterministic under replay even though resume now reseeds.
- **Shared directories need their own clones,** because host-side writes to a 9p share escape a VM snapshot.
- **No vsock in stock bhyve;** virtio-console carries control and intent frames unless your platform has added a better channel.

### A narrow deterministic tender

Antithesis makes arbitrary x86 guests deterministic by counting instructions and injecting interrupts at exact points, which is years of work. For Mirage and Wasm guests it is unnecessary. A Solo5 guest runs on one core, takes no interrupts, and reaches the world only through eight hypercalls (walltime, puts, poll, block read and write, net read and write, halt), with `poll` its only wait. Control the hypercall results and it is deterministic in between.

Fork the Solo5 hvt tender for FreeBSD (about 1,600 lines of C) into a simulator client:

- **Time** comes from a page the tender writes only at hypercalls; patch the guest bindings that read `rdtsc` and seed the stack protector from it.
- **`poll`** is answered by the simulator: which handles are ready and what time it is.
- **Net and block** become simulated devices with partitions, drops, torn writes and clock skew.
- **CPUID** always traps under VMX, so mask RDRAND and RDSEED there.
- **Images** are disassembled at admission and refused if they contain stray `rdtsc` or `rdrand`.
- **Nodes run in parallel** as separate VMs and meet only at hypercalls, merged in (virtual time, node, sequence) order.
- **Snapshots** are cheap: branch points are hypercalls, which are C calls, so guest memory and general registers suffice.

Wasm cells get the same determinism in-process: disable threads and relaxed SIMD, canonicalise NaNs, route clock and randomness through the oracle, and fuel gives deterministic preemption. Linux guests, multicore guests and timing-sensitive code stay with Antithesis.

### OxCaml on FreeBSD

The kernel builds on mainline OCaml everywhere; OxCaml adds static checks of confinement. A source read of OxCaml `5.4.0+ox` found that it kept nearly all of upstream's FreeBSD support. The port is about 7 to 14 person-weeks:

- **Build tooling** (GNU make in dune rules).
- **Small runtime patches:** `mc_rip` in the stack-overflow handler, `KERN_PROC_PATHNAME`, probe sections, the JIT allocator.
- **Test triage.**
- **Library fixes:** `core_unix` under `parallel`, and `eio_posix` instead of `eio_linux`.
- **arm64,** and CI on a bhyve runner.

Until then, run OxCaml in a Linux jail under FreeBSD's Linux emulation (mount linprocfs) or a Linux bhyve guest. Write the patches as "non-Linux POSIX" so illumos could follow: Oxide's racks run bhyve on illumos, and Jane Street is an Oxide investor and reportedly a customer, though customer workloads there run as Linux guests.

## Verification

Three layers check the same properties at different strengths: an in-process simulator driven by Hegel, Quint models generated per graph, and Antithesis for whole systems. Every result is recorded as an evidence grade, and nothing silently counts as a pass.

| Grade | Earned by |
| --- | --- |
| `:proved` | A proof; Cedar's Lean-verified analyses for policy; immutable-value inverses |
| `:exhausted` | Apalache to a stated depth; exhaustive interleavings over a declared domain |
| `:sampled` | Hegel or `quint run` samples, with the count recorded |
| `:monitored` | A recorded run that conforms to its Quint model |
| `:compensated` | Reverted only up to a compensation's equivalence |
| `:unknown` | Nobody looked. An empty check is `:inconclusive`, never a pass |

### The simulator and Hegel

One entropy oracle makes every choice the simulator faces: scheduling, faults, the tender's `poll` answers, which receipts come back Uncertain. Each choice is a Hegel draw, so a failing multi-node run shrinks to a minimal schedule and fault set. Under the hypervisor, the guest's oracle hypercall becomes a draw on the host side, so nothing Hegel-related runs in a guest.

- **No FFI needed.** The `hegel` opam package runs libhegel, the Rust engine behind a C interface, in-process; jolt-hegel binds the same engine for Clojure.
- **Gaps:**
  - A FreeBSD build of libhegel (none is published).
  - OxCaml compatibility.
  - Exposure of replay-from-blob and nondeterminism detection; failing that, generate a binding from jolt-hegel's `abi.edn`.
- **Generators come from schemas,** with size bounds as limits and CEL residue as filters.
- **Cordis witnesses get sampled checks:** apply-then-inverse leaves declared keys unchanged; two operations on a shared key commute.
- **The simulator speaks Antithesis's vocabulary** (`always`, `sometimes`, `reachable`, `unreachable`, `setup_complete`, an oracle-chosen random used once), so the same cell code runs under Antithesis unchanged.

### Quint

casselc/observability already holds the conventions and tools.

| Piece | Reuse |
| --- | --- |
| Outbox | `ambiguousCall.qnt`: send, apply, answer lost, lose, timeout, retry, verify; `atMostOnce`; `blindRetry` and `verifyEarly` kept as permanent mutants; `IDEMPOTENT` read from the intent kind |
| Lifecycle | Written once by hand, closest to `retirement.qnt`; the guard is an invariant, Uncertain blocking unload a witness |
| Each graph | A generated module with design and mutant instances, witnesses for `sometimes`, invariants for `always` |
| Conformance | The simulator writes quintgo's ITF trace format; `quintgo validate` checks it with no Go code |
| Model-based testing | The model's choices feed the oracle, as edgepublish's write gate does |
| Hidden nondeterminism | Simulated devices record their side (applied, lost), so Uncertain never replays as plain failure |
| CI | `model-verdict.sh` rules: fail on violation or empty report, warn on unreached witness or missed mutant |

Carried-over limits: no liveness checking, conformance per actor after the fact, 4 to 8 seconds per trace, and no sound replay of ambiguous outcomes yet.

### Gates on change

An agent's change (a graph patch, a cell, a library, a policy) goes live only after:

1. It type-checks.
2. Its CEL costs fit their budgets.
3. Cedar Analysis finds no unapproved widening.
4. Its contracts are not weakened (signature subtyping; writ-style baselines).
5. Its Quint model passes.
6. Its soak passes: the revision replays in branched simulator futures, or restored VM snapshots, with faults on; only changed assets recompute.

Then the old fiber is retired with its inverses, and the new one inserted. By Cordis's confluence result, this ends where a cold start of the new configuration would.

## What comes from where

Most of the design is already built somewhere in your repos or in prior art; the new pieces are the graph checker, the CEL evaluator, the fiber runtime, the narrow tender and the generators that tie them together. Repo state is as of 2026-10-09; the first companion doc has the full branch survey.

| Source | Contributes | Where it left off |
| --- | --- | --- |
| samizdat | Core, behaviour and policy split; mutation protocol (checkpoint, reload, validate, soak, rollback); per-project versioned userspace; ebb task trees; decide cells and ADR-002 revalidation; idempotency fence on run start | Main at 2026-09-18; decide stack parked on `canonical` since 2026-09-05; context-selection seam unmerged |
| JS1, JS2 (samizdat branches) | Commit-only state, intent and outcome receipts, replay with zero world calls, TurnLease, authority as an intersection | Self-hosting canaries passed; frozen branches, never merged |
| jolt-sim | Immutable kernel with exact replay, sessions with branch preview, flow compiler, commit-gated effects with Uncertain and reconcile, retained workers, command cells | Main at 2026-08-11 (#127); Jolt 0.7.16 drafts #129 and #130 open |
| perturb (jolt-sim research branch) | Evidence values and the meet, regions with must-be-empty exits, typestate, B6 layering, the Datalog query design | Non-authoritative research input |
| bbagent | Capability profiles, replay at the semantic operation boundary, observation versus actuation, SmolVM isolation, execution SPI | Main at 2026-08-28 |
| agent-lisp-mycelium | Recursive cell contract, authority attenuation, "no orphan work" | Design only |
| samizdat-agent-bootstrap | ADR-001 (SQLite causal truth, OTel observation, chDB derived), decision contracts, aspect roles | Frontier `jev-judgment-eval-v1`, 2026-09-26 |
| stpa-workbench | UCCAs as counterexamples to independence; rollback as a control action with its own hazards | RFC branch, 2026-09-12 |
| casselc/observability | Quint conventions, `ambiguousCall.qnt`, quintgo conformance and model-based testing, CI verdict rules | Main at 2026-10-02 |
| jolt-hegel | The libhegel interface description; Malli-derived generators | v0.5.0 |
| Cordis | Components, fibers, lifecycle, guard, confinement, commutativity, confluence | Paper |
| mycelium upstream | Schema chain, joins, halt and resume, `myc patch` checked edits, cell briefs | Library |
| writ, ensemble | Frame laws and anti-weakening; pure decision cores the shell must call through | Libraries |
| grain | Effect delivery modes, checkpoint in the same CAS as outputs, query-scoped CAS, versioned read models | Library |
| MuleSoft APIkit, DataWeave | Scaffolding shape, error table, `reduce`, modules | Prior art |
| Metacontroller, KRO, Crossplane | Pure reconcile shape, CEL-referenced child graphs, accumulating pipelines | Prior art |
| Unison, Dhall, Dagster, Salsa, Goblins, Hazel | Content addressing, assets, incremental recomputation, transactional turns, typed holes | Prior art |

## Build order

Four phases, each usable on its own: first the pure core and its checks, then languages and imports, then emissions and enforcement, then the platform. Nothing in a later phase is needed to test an earlier one, because the simulator arrives in phase 1.

**Phase 1: pure core and simulator**

1. **Kernel as pure OCaml.** The lifecycle calculus (`target`, `relied`, `next`, `apply`) with Uncertain, as total functions; ensemble's `restart-plan` and `intensity` ported. No IO.
2. **Hand-written Quint model of the lifecycle and outbox,** from `retirement.qnt` and `ambiguousCall.qnt`, checked in CI with the observability verdict rules.
3. **Simulator.** A seeded Picos scheduler plus Eio mock, one entropy oracle backed by Hegel draws, the Antithesis-shaped assertion API, and ITF traces that quintgo validates.

**Phase 1b: model the distributed parts before building them**

Plain Quint modules beside the lifecycle model, each with a design instance, mutants and witnesses, simulated and checked with Apalache to a bound. Implementation in phase 3 follows the models.

- 3a. **Outbox placement** (`cordis/quint/outbox.qnt`): per-agent outbox jails holding journals and credentials, the central limit service, `pf` egress, jail crashes, step-snapshot rollbacks and lost messages.
- 3b. **Snapshot branching:** one snapshot restored into several VMs with fork IDs; only the chosen branch's emissions are released.
- 3c. **The MCP/A2A gateway:** tool calls from outbox jails, intent kinds from tool annotations and `pf`, the stricter winning.
- 3d. **Capability attenuation,** if Syndicate-style sturdy refs are adopted: attenuation never widens.

**Phase 2: languages and imports**

4. **CEL evaluator** with the cost estimator, the macro extensions and function libraries, tested against cel-spec conformance and cel-go.
5. **Graph language checker:** row and effect types, routes, assets, `use`, typed holes, structural patches; definitions stored by hash in Irmin.
6. **Importers,** protobuf first, then JSON Schema, OpenAPI and AsyncAPI, with CUE offline as a front end; then generators for Hegel from the same type form.
7. **Scaffolders** for services and CRD controllers, reusing the importers; Quint and Cedar stubs generated alongside.

**Phase 3: emissions and enforcement**

8. **Outbox and intents,** ported from jolt-sim's commit-gated session and JS1 receipts; LLM calls first, with cassettes; compensation declarations.
9. **Cedar at the trap,** through a small Rust C wrapper or a Wasm component; partial-evaluation residuals cached per graph hash; Cedar Analysis in the change gates.
10. **Turns, assets and the change gates,** with the mutation protocol rewritten as a fiber revision.

**Phase 4: platform**

11. **Your platform as it stands:** a VM and jail per agent, child jails per tool call, central `pf`, ZFS clones per step. Add the host outbox and the trap's intent channel, and generate the internal-versus-emission split for tool calls from the `pf` rules.
12. **The narrow tender:** fork Solo5 hvt into a simulator client; image admission scan; Wasm host with fuel and deterministic settings.
13. **Snapshot-backed soak,** using the platform's existing snapshots, once one snapshot can be restored into several VMs.
14. **OxCaml:** a Linux-jail build now; the native FreeBSD port when the static mode checks earn the 7 to 14 weeks.
15. **Antithesis** for anything outside Mirage and Wasm.

## Risks and open questions

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| The CEL evaluator, its host functions and the Wasm runtime are the trust base for agent code | Modes and types stop at that boundary | Keep host functions few and reviewed; conformance and differential tests; fuzz under the simulator |
| No OCaml CEL implementation exists | The core language must be written | Build against the conformance suite; cel-rust in Wasm as a fallback |
| Inverses and compensations are written by agents | The least trustworthy code carries the revert guarantee | Evidence grades on every inverse; low-grade components cannot hot-replace; compensations can need approval |
| Uncertain can block unloading indefinitely | Progress depends on reconcile | A reconcile deadline in policy, after which a human decides |
| A tool call's child jail reaches past its clone | A call classified internal would escape revert | The central `pf` decides the class: anything it lets out is an emission and must hold an intent; audit with DTrace |
| 9p writes and the model service sit outside snapshots | Rollback misses them | Per-share clones; inference only as recorded emissions |
| Canonical EDN has two printers, OCaml and Clojure | A mismatch breaks content addressing silently | One written spec with test vectors both printers must pass in CI |
| Unikernels are single-domain and harder to debug | Parallelism and diagnosis | Parallelism from many VMs; console snapshots and the simulator for diagnosis |
| libhegel, Cedar and cvc5 have no FreeBSD builds published | Tooling gaps on the host | Build from source; run analyses in CI if needed |
| OxCaml and Picos move fast | Maintenance load | Modes as erasable annotations; mainline remains the build of record |

Open questions:

- **Restoring one snapshot into several VMs** is now a platform feature requirement. Until it lands, soak testing in branched futures runs in the simulator; the outbox's fork IDs are designed for it already.
- **Trusting tool annotations.** Intent kinds come first from the hosted MCP servers' tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) and A2A agent cards. `pf` rules decide, the stricter of the two wins, and disagreements are flagged. Open: whether a third-party server with missing annotations defaults to emission.
- **Preserves codecs.** Who writes and maintains the OCaml and Clojure codecs, and whether Preserves Schema code generation is worth its 2–3 weeks.
- **Which parked work lands first:** the decide stack on `canonical`, the JS1 contract, or jolt-sim's Jolt 0.7.16 drafts.
- **Clojure as the agents' language.** EDN keeps samizdat's cells and graphs round-tripping; the ML surface is for checking and authoring.

## Sources and companion docs

- **Companion docs:** [Cordis Synthesis: Components, Effects, Coeffects](SYNTHESIS.md) (repo and branch survey, vocabulary map) and [Cordis Cells in OCaml on bhyve](OCAML-ON-BHYVE.md) (full detail on every section here, with all research sources).
- **The paper:** Shi, Zhang, Cui, *A Programming Paradigm for Spatiotemporal Composability* (Cordis), arXiv 2608.25512v1.
- **Languages and policy:** [cel-spec](https://github.com/cel-expr/cel-spec), [Kubernetes CEL validation](https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definitions/), [Cedar](https://docs.cedarpolicy.com/policies/syntax-operators.html), [Cedar Analysis](https://aws.amazon.com/blogs/opensource/introducing-cedar-analysis-open-source-tools-for-verifying-authorization-policies), [CUE](https://cuelang.org/docs/reference/spec/), [DataWeave modules](https://docs.mulesoft.com/dataweave/2.4/dataweave-create-module).
- **Scaffolding:** [APIkit](https://docs.mulesoft.com/apikit/latest/apikit-4-scaffolding-reference), [oapi-codegen](https://github.com/oapi-codegen/oapi-codegen), [Metacontroller](https://metacontroller.github.io/metacontroller/api/compositecontroller.html), [KRO](https://kro.run/docs/concepts/rgd/overview), [server-side apply](https://kubernetes.io/docs/reference/using-api/server-side-apply/), [Open Workflow Specification](https://open-workflow-specification.org/).
- **Runtime and hosting:** [OxCaml](https://oxcaml.org/get-oxcaml/), [Eio](https://github.com/ocaml-multicore/eio), [Miou ownership](https://ocaml.org/p/miou/latest/doc/miou/Miou/Ownership/index.html), [Irmin](https://github.com/mirage/irmin), [Solo5](https://github.com/Solo5/solo5), [Antithesis deterministic hypervisor](https://antithesis.com/blog/deterministic_hypervisor/), [bhyve(8)](https://man.freebsd.org/cgi/man.cgi?query=bhyve&sektion=8&manpath=FreeBSD+15.0-RELEASE), [Oxide hypervisor](https://docs.oxide.computer/guides/architecture/os-hypervisor-storage).
- **Verification:** [hegel](https://ocaml.org/p/hegel/latest/README.html), [hegel.dev](https://hegel.dev/), casselc/observability (`ambiguousCall.qnt`, quintgo), chucklehead-dev/jolt-hegel.
