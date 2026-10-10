# RFC 0002: The graph language

Status: revision 2, implemented (phase 2, build order item 5) in
`cordis/graph`. Design context: [DESIGN.md](../DESIGN.md) "The graph
language". Models: `cordis/quint/admission.qnt` (concurrent edits),
`cordis/quint/assets.qnt` (memoisation and freshness),
`cordis/quint/routes.qnt` (guards, streams and feedback loops).

## Revision history

**Revision 1** (accepted for design) sketched an ML-flavoured language:
cell bodies in ML syntax, Hindley–Milner inference with Rémy row
polymorphism, routes that append "to an asset's input stream", identity by
canonical Preserves, storage in Irmin.

**Revision 2** records the language as built, and the run-time semantics of
routes and streams that `routes.qnt` checked before they are implemented.
What changed, and why:

| Revision 1 | Revision 2 | Why |
| --- | --- | --- |
| Bodies in ML syntax (`Review.parse resp ?? x`, `if … then … else`) | Every expression is CEL (RFC 0001); the graph language is a thin layer of declarations around it | One expression language, one checker, one cost model; the harness's CEL is already conformance-tested and cost-bounded |
| Anonymous records `{ prompt = …; … }` | Records are named types, built with CEL's message-literal syntax (`Prompt{prompt: …}`); a CEL map literal is not a record | CEL has no record literal; message literals give construction a checked shape |
| HM with Rémy rows; pass-through rows `.. 'r` | Declared signatures; type variables instantiated per use and unified at the wiring; width subtyping for open records in parameters only | Every cell is annotated, so inference is needed only at the wiring; pass-through needs a record-spread operator CEL lacks (deferred) |
| `draft <~ r.notes` appends to an asset | Explicit, typed streams with a capacity: `stream notes : string up to 8;` | A stream must be named and typed to be checked; `routes.qnt` shows a feedback loop needs a capacity to come to rest |
| Routes undefined at run time | A guard computes or withdraws its asset; an append is keyed by the scrutinee's computation (amended, below); a full stream refuses; a new input empties the streams | Checked in `routes.qnt` (consistent, appendOnce, bounded, noStall) |
| Identity by canonical Preserves; Irmin store | Identity by SHA-256 of canonical text (and of the definitions named); definitions in files | The Preserves codec and Irmin are later work; only the hashing input will change |
| Admission gates listed | Admission built for parse, check, cost budget and compatibility; hash-guarded patches | Cedar Analysis, the Quint gate per graph and soak follow with phase 3 |

## Purpose

Agents rewrite the harness's behaviour by editing graphs: which cells run,
what they read and emit, and how results loop back. The graph language makes
those edits checkable before they run. It replaces mycelium's schema chain,
`:requires`/`:effects` lists and dispatch-coverage checks with one type
system, and it compiles to the kernel's components, so a running graph is a
set of Cordis fibers.

## An example

`cordis/graph/examples/review.cg`, abridged:

```
uses text = sha256:3f0c...;                       // a function library (RFC 0001)

type Diff    = { path : string; patch : string<max 65536> };
type Review  = { passed : bool; notes : string };
type Verdict = Pass(Review) | Fail(Review);
type Prompt  = { prompt : list(string); max_tokens : int };
type Text    = { text : string<max 65536> };

effect repo.read(string) : Text;
effect llm.complete(Prompt) : Text;

cell write(request : Diff, notes : list(string<max 500>)<max 8>) : Diff = {
  Diff{path: request.path, patch: notes.fold(p, request.patch, n, p + '\n// ' + n)};
}

cell critic(diff : Diff) : Verdict
  ! { repo.read, llm.complete }
  state { attempts : int = 0; }
= {
  use ctx  = repo.read(diff.path);
  use resp = llm.complete(Prompt{prompt: [ctx.text, diff.patch], max_tokens: 4000});
  let r    = Review{passed: resp.text.startsWith('LGTM'), notes: resp.text};
  r.passed ? Verdict.Pass(r) : Verdict.Fail(r)
    with state.attempts := state.attempts + 1;
}

graph review_loop {
  input  request : Diff;
  stream notes   : string<max 500> up to 8;
  asset  draft   = write(request, notes) ~fresh:on_change;
  asset  verdict = critic(draft);
  asset  shipped = gate(verdict);
  route verdict { Pass(_) -> shipped; Fail(r) -> notes <~ r.notes };
  invariant critic before gate;
}
```

## Concrete syntax

The graph language is a thin layer around CEL: every expression in it is a
CEL expression, parsed and checked by RFC 0001's implementation. A CEL
expression runs to the next `;` (or, for a cell's final expression,
`with`; inside a route, `,` or `}`) outside brackets, string literals and
comments, so the CEL inside is never re-lexed by the outer grammar.

```
file     := item*
item     := 'uses' IDENT '=' HASH ';'
          | 'type' NAME '=' type ';'
          | 'type' NAME '=' '|'? case ('|' case)* ';'
          | 'effect' DOTTED '(' type ')' ':' type ';'
          | 'cell' NAME '(' params ')' ':' type effects? state? '=' '{' body '}'
          | 'graph' NAME '{' gitem* '}'
case     := NAME | NAME '(' type ')'
params   := (IDENT ':' type) (',' IDENT ':' type)* |
effects  := '!' '{' (DOTTED (',' DOTTED)*)? '}'
state    := 'state' '{' (IDENT ':' type '=' CEL ';')* '}'
body     := (stmt ';')* CEL ('with' update (',' update)*)? ';'
stmt     := 'let' IDENT '=' CEL | 'use' IDENT '=' DOTTED '(' CEL ')'
update   := 'state' '.' IDENT ':=' CEL
gitem    := 'input' IDENT ':' type ';'
          | 'stream' IDENT ':' type 'up' 'to' INT ';'
          | 'asset' IDENT '=' NAME '(' (IDENT (',' IDENT)*)? ')' fresh? ';'
          | 'route' IDENT '{' rcase (';' rcase)* '}' ';'?
          | 'invariant' NAME 'before' NAME ';'
          | 'invariant' ('always' | 'sometimes') CEL ';'
fresh    := '~fresh:on_change' | '~fresh:always' | '~fresh:every' DURATION
rcase    := pattern '->' action (',' action)*
pattern  := NAME | NAME '(' (IDENT | '_') ')' | '_'
action   := IDENT | IDENT '<~' CEL | 'none'
type     := scalar bound? | 'list' '(' type ')' bound? | 'map' '(' type ',' type ')' bound?
          | 'option' '(' type ')' | '{' (IDENT ':' type ';')* ('..')? '}'
          | NAME | "'" IDENT | 'dyn'
scalar   := 'int' | 'uint' | 'double' | 'string' | 'bytes' | 'bool' | 'timestamp' | 'duration'
bound    := '<' 'max' INT '>'
```

`?name` in any CEL position is a typed hole: it type-checks as whatever the
position needs and makes its turn pending. A leading `|` makes a variant
(`type E = | Only;`): without it a lone unit case reads as a type name.

## Types

- **Scalars**, with an optional size bound on `string` and `bytes`
  (`string<max 256>`); `dyn`.
- **Lists and maps**, with an optional bound on their size; map keys are
  `int`, `uint`, `string` or `bool`. **Optionals**: `option(T)`.
- **Records**: `{ a : int; b : string }`, closed. An **open** record `{ a :
  int; .. }` is allowed only as a cell parameter, and accepts any record
  with at least those fields (width subtyping at the boundary).
- **Variants**: `Pass(Review) | Fail(Review) | Skipped`, by name.
- **Type variables** `'a` in a cell's signature: opaque in the body
  (parametricity: `x + 1` on an `'a` is rejected), instantiated afresh at
  each asset that uses the cell and unified with its arguments.
- **Named types** resolve to their definitions; a type that refers to
  itself is rejected (recursion goes through a reference, RFC 0003).

Every cell's signature is declared and its body is checked against it, so
inference is needed only where assets are wired.

**Bounds are cost inputs.** A turn's cost is estimated from its parameters'
types, each level bounded by its own `<max N>` or the configured default.
The runtime enforces a parameter's bounds at the cell's boundary; where a
bound can be checked statically (a stream's capacity against the parameter
it feeds), the checker does.

## Values in CEL

Records and variants exist only in the graph language's types; at run time
they are CEL maps, and the CEL checker knows the declared types:

- a **record** value is a map with a string key per field, built with
  `Review{passed: true, notes: ''}` (every field given, no others); field
  selection is typed per field;
- a **variant** value is `{'tag': 'Pass', 'value': r}`, built with
  `Verdict.Pass(r)` (or `Verdict.Skipped` for a unit case) and taken apart
  with RFC 0001's `match`;
- after checking, these forms are lowered to plain map literals, so the
  evaluator and the estimator see ordinary CEL.

## Cells

The names in scope in a body are its parameters, `state` (a record of the
declared state fields), the `let` and `use` names before the expression,
and the imported library aliases; nothing else exists (confinement). A
`use x = k(e)` requires `k` in the cell's effect row and `e` of `k`'s
request type, and gives `x` the receipt type. The final expression must
have the declared result type, and each `with` update its state field's.

**Turns.** Each `use` ends a turn. Turn *i* is one CEL program over the
inputs, the state and the receipts of the turns before it; it re-binds the
earlier `let`s (pure, so recomputing them is safe) and evaluates to the
intent it emits, or, for the last turn, to the result and the state
updates. Each turn's cost is estimated, and a turn over the budget is an
admission error. A turn with a hole is pending: it is admitted but does not
run.

## Graphs

- `input x : T` is supplied from outside. `asset a = c(x, y)` applies cell
  `c` to inputs, streams and assets, positionally; each argument must fit
  its parameter.
- `stream s : T up to N` is an append-only stream of at most `N` values; a
  parameter fed by `s` receives `list(T)<max N>`, and must not declare a
  smaller bound. Only routes append to streams, which is how results loop
  back: the asset graph with streams removed must be acyclic.
- `route a { … }` matches the variant asset `a`; its cases must cover
  `a`'s type (or end in `_`). An action naming an asset **guards** it: the
  asset computes only when `a` has that case, and must depend on `a`. An
  action `s <~ e` **appends** `e` (CEL over the case's binder, of the
  stream's element type) to `s`.
- `invariant c1 before c2` is checked statically: every asset computed by
  `c2` must depend, transitively, on one computed by `c1`. `always` and
  `sometimes` take a boolean CEL predicate over the assets, for the
  simulator and the per-graph Quint module.
- A graph's **effect row** is the union of its cells' rows; Cedar baseline
  grants are generated from it.

## Routes and streams at run time

Checked in `routes.qnt` (a review loop in miniature: a draft from a request
and the notes so far, a critic, a guarded `shipped`, and a `Fail` route
that appends to the notes), in Quint simulation and with Apalache to 14
steps, before the turn runtime implements it:

- **A guard computes or withdraws.** When the scrutinee has the guarding
  case, the guarded asset computes from it; when the scrutinee changes to
  another case, the asset is **withdrawn** (its key unbound, so whatever
  reads it stops). At rest, every asset equals a from-scratch evaluation of
  the graph for the current inputs and streams (`consistent`). Keeping the
  old value instead (`keepGuarded`) breaks this: a `shipped` from an
  earlier `Pass` would outlive the `Fail` that replaced it.
- **An append is keyed by the scrutinee's computation:** the episode, and
  which computation of the scrutinee in it. The route's `route.append`
  intent carries that key, so re-running the route (a restart, a
  redelivery) appends nothing new (`appendOnce`); appending on every run
  (`appendEveryRun`) duplicates notes. Revision 2 first keyed it by the
  scrutinee's *revision*, which moves only when the value changes. Running
  the review loop on the engine (RFC 0006, stage 1) showed why that is
  wrong: a critic that gives the same verdict for a new draft (an LLM
  repeating itself) keeps the revision, so the route treats it as answered
  and the loop stops early. `routes.qnt` now has such a critic, the
  property `noStall` (a failing loop rests only at its cap), and the
  mutant `keyByValue`, which breaks it. The episode is in the key because a
  new request can give a draft equal to an earlier one, which recomputes
  nothing downstream yet still needs an answer.
- **A full stream refuses.** A stream on a feedback loop has a capacity; an
  append to a full stream is refused (and reported), so a loop whose critic
  never passes still comes to rest, at the cap (`bounded`, witness
  `capReachedAtRest`). Without a capacity (`noCap`) such a loop never
  stops. The checker therefore requires a capacity on every stream.
- **A new input starts a new episode.** When a graph input changes, its
  streams are emptied: the notes belong to the request they were written
  for.

The witnesses show each behaviour is reachable: a loop that appended and
then passed and shipped, a loop at rest at its cap, a withdrawal, a refusal.

## Identity and edits

Each definition prints to a canonical text: CEL as its tokens with fixed
spacing, the rest with fixed layout. A definition's hash is the SHA-256 of
that text and of the sorted `(name, hash)` pairs of the definitions it
names, so formatting and comments never change a hash, and changing a type
changes the hash of every cell and graph that uses it. (The canonical
Preserves form of DESIGN.md "Serialization" replaces the text once that
codec exists.) A revision's hash covers every definition's.

**Edits are patches against expected hashes:**

```
replace critic@sha256:<hex>
cell critic(diff : Diff) : Verdict ... = { ... }

add
type Note = { text : string };

remove gate@sha256:<hex>
```

If any expected hash is not the live definition's, the whole patch is
refused, never merged (`admission.qnt`: no lost update).

## Admission

A patch that applies is admitted through these gates, in order:

1. **parse**;
2. **check** (types, confinement, effects, wiring, routes, invariants);
3. **cost**: every turn within its budget;
4. **compatibility** against the live revision: a cell may be replaced
   without approval when its new signature accepts at least the old input,
   produces at least the old output, and has no larger effect row, with no
   state field dropped or retyped. A change that narrows an input, widens
   the effects, changes a variant's cases or an effect's types, or imports
   a different library **needs approval**, with the reasons listed (the
   graph-level form of Cedar Analysis's "More Permissive");
5. still to come with phase 3: Cedar Analysis of the derived policy, the
   model check of the generated Quint module, and soak.

A revision that passes goes live; one that needs approval waits until it
is approved; anything else is refused. Then the old fibers retire and the
new ones are inserted; by Cordis's confluence result the outcome is that of
a cold start of the new revision. The one exception is a cell consuming a
frame in batches whose accumulator is carried across the cutover: its
outcome is the old revision's fold up to the cutover, continued by the
new one's (RFC 0005, "Frames", rule 5, which also offers drain and
restart).

## Compilation to the kernel

| Graph form | Kernel |
| --- | --- |
| `input x`, `stream s` | a component providing `x` (`s`) from outside |
| asset `a = c(x, y)` | a component requiring `{x, y}`, providing `a` and `a`'s state keys |
| a turn of `c` | an iteration; a turn ending in `use k` emits an intent of kind `k` |
| the last turn | binds `a` and writes the state keys |
| `s <~ e` in a route on `a` | an iteration of `a`'s component emitting `route.append`, an acquisition handled internally |
| graph revision | one O-Retire per removed fiber, one O-Insert per added one |

The kernel models values as integers and has no conditionals, so what
compiles is the shape: the keys a fiber needs and provides, its turns and
what they may emit. A route's guard, an append's key and an asset's
freshness are carried as metadata for the turn runtime, which implements
them as "Routes and streams at run time" states.

## Testing

- **Syntax:** print then parse is the identity, printing is idempotent,
  and a program written with random spacing, comments and string spelling
  parses to the same program with the same hashes (Hegel).
- **Checking:** a corpus of accepted and rejected programs, one rule each,
  with the expected error.
- **Soundness:** for inputs drawn from the declared types, bounds included,
  every turn costs at most its estimate and every result has its declared
  type, over the corpus and over generated cells (Hegel).
- **Compilation:** the components a graph compiles to, run under the kernel
  in random interleavings, reach quiet with every asset provided, only
  their cells' effect kinds emitted, and every accepted `before` held; over
  random graphs with random invariants, which tests the static check
  against runs (Hegel).
- **Admission:** `admission.qnt`'s scenario replayed: two agents patching
  from one snapshot lose nothing, live revisions check, and nothing that
  needs approval goes live unapproved (Hegel); compatibility examples.
- **Models:** `admission.qnt`, `assets.qnt` and `routes.qnt` in the model
  gate (`quint/check.sh`), each with its mutants and witnesses.

## Deferred

- **Signatures and functors** (`graph review_loop (Author : AUTHOR)`), so a
  scaffold can leave a slot an agent fills.
- **Pass-through rows** (`.. 'r` in a result), which need a record-spread
  operator in CEL.
- **Holes inside larger expressions** (`x + ?todo`); today a hole is a whole
  expression.
- **The generated Quint module per graph** and its admission gate, and
  `always`/`sometimes` as simulator assertions.
- **The turn runtime** (phase 3): bounds enforced at cell boundaries,
  guards, keyed appends, capacities and episodes as above, freshness.
- **Storage** in a content-addressed store (Irmin), and the Preserves
  canonical form.

## Scaffolds

Generation-gap: a scaffold writes generated files that are regenerated
freely and stubs that are written once and then owned by the agent or a
person.

- **From OpenAPI, AsyncAPI or gRPC:** one router graph per service, one
  handler cell per operation with its typed signature and a `?todo` body,
  the intent kinds for its outbound calls, a Quint module with the routes as
  actions, and Cedar baseline grants from the effect rows.
- **From a CRD:** a reconcile cell (desired spec, observed status) to
  (status', intents), in Metacontroller's pure shape; finalizers become the
  component's Unloading guard; server-side-apply field ownership is the
  per-key commutativity witness.
