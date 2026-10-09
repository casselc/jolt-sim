# RFC 0002: The graph language

Status: accepted for design; implementation follows RFC 0001 steps 1–3
(phase 2, build order item 5). Design context: [DESIGN.md](../DESIGN.md)
"The graph language". Models: `cordis/quint/admission.qnt`,
`cordis/quint/assets.qnt`.

## Purpose

Agents rewrite the harness's behaviour by editing graphs: which cells run,
what they read and emit, and how results loop back. The graph language makes
those edits checkable before they run. It replaces mycelium's schema chain,
`:requires`/`:effects` lists and dispatch-coverage checks with one type
system, and it compiles to the kernel's components, so a running graph is a
set of Cordis fibers.

## Surface

```
uses text = sha256:3f0c...;                       // a function library (RFC 0001)

type Diff    = { path : string; patch : string<max 65536> };
type Review  = { passed : bool; notes : string };
type Verdict = Pass(Review) | Fail(Review);

effect repo.read(string) : { text : string };
effect llm.complete({ prompt : list(string); max_tokens : int }) : { text : string };

cell critic(diff : Diff) : Verdict
  ! { repo.read, llm.complete }
  state { attempts : int = 0 }
= {
  use ctx  = repo.read(diff.path);
  use resp = llm.complete({'prompt': [ctx.text, diff.patch], 'max_tokens': 4000});
  let r    = text.parse_review(resp.text);
  r.passed ? Verdict.Pass(r) : Verdict.Fail(r)
    with state.attempts := state.attempts + 1;
}

graph review_loop {
  input  request : Diff;
  stream notes : string;
  asset  draft   = write(request, notes) ~fresh:on_change;
  asset  verdict = critic(draft);
  asset  shipped = gate(verdict);
  route verdict { Pass(_) -> shipped; Fail(r) -> notes <~ r.notes };
  invariant critic before gate;
}
```

Top-level forms: `type`, `effect`, `cell`, `graph`, `uses` (library imports
by hash), and later `signature` (module types such as `AUTHOR`, for
functor-parameterised graphs). A cell body is a sequence of `let` (pure
CEL), `use` (an effect: a wait), and a final expression, optionally followed
by `with` path updates to the cell's state.

### Concrete syntax

The graph language is a thin layer around CEL: every expression in it is a
CEL expression, parsed and checked by RFC 0001's implementation. The layer
is delimited so the CEL inside never has to be re-lexed: a CEL expression
runs to the next `;` (or, for the final expression, `with`) that is outside
brackets and string literals.

```
file     := item*
item     := 'uses' IDENT '=' HASH ';'
          | 'type' NAME '=' type ';'
          | 'type' NAME '=' case ('|' case)* ';'
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
          | 'stream' IDENT ':' type ';'
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
bound    := '<' 'max' INT '>'
```

`?name` in any CEL position is a typed hole: it type-checks as whatever the
position needs and makes its turn pending.

### Values in CEL

Records and variants exist only in the graph language's types; at runtime
they are CEL maps, and the CEL checker learns the declared types:

- a **record** value is a map with a string key per field. Records are
  built with CEL's message-literal syntax, `Review{passed: true, notes:
  ''}`, which the checker accepts for a declared record type (every field
  given, no others); field selection on a record is typed per field.
- a **variant** value is `{'tag': 'Pass', 'value': r}` (RFC 0001's
  `match`). `Verdict.Pass(r)` builds one, and a unit case is
  `Verdict.Skipped`.
- an **open record type** `{ path : string; .. }`, allowed only in a cell's
  parameters, accepts any record with at least those fields (width
  subtyping at the boundary). Pass-through rows (`.. 'r` in the result)
  need a record-spread operator CEL does not have, and are left for later.

### Cell bodies

The names in scope in a body are the parameters, `state` (a record of the
declared state fields), the `let` and `use` names before the expression,
and the imported library aliases. A `use x = k(e)` requires `k` in the
cell's effect row, `e` assignable to `k`'s request type, and gives `x`
`k`'s receipt type. The final expression must be assignable to the declared
result; each `with` update to the state field's type.

Each `use` ends a turn. Turn *i* is one CEL program over the inputs, the
state and the receipts of turns before it; it re-binds the earlier `let`s
(pure, so recomputing them is safe) and evaluates to the intent it emits,
or, for the last turn, to the result and the state updates. The cost of
every turn is estimated (RFC 0001) with size bounds from the parameter
types (`<max N>`, each level of a type bounded by its own) and a configured
default, and a turn over its budget is an admission error. The bounds are
promises the runtime enforces at the cell's boundary. A record value
always comes from a named record type's literal: a CEL map literal has
type `map(string, dyn)`, which is not a record, so an effect's request is a
named record (`Prompt{...}`).

### Graphs

- `input` declares an asset supplied from outside; `asset a = c(x, y)`
  applies cell `c` to assets or streams `x`, `y`, positionally. An
  argument's type must be assignable to the parameter's (with width
  subtyping for an open record); a type variable `'a` in a cell's signature
  is unified across its parameters and result, so a generic cell gets a
  concrete type per use.
- `stream s : T` is an append-only input stream; a parameter fed by `s` has
  type `list(T)`. Only routes append to streams, which is how results loop
  back without a dependency cycle: the asset graph, with streams removed,
  must be acyclic.
- `route a { ... }` matches the variant asset `a`. Its cases must be
  exhaustive over `a`'s type (or end in `_`). An action naming an asset
  guards it: that asset computes only when `a` has that case, and it must
  depend on `a`. An action `s <~ e` appends `e` (CEL over the case's
  binder, of the stream's element type) to stream `s`.
- `invariant c1 before c2` is checked statically: every asset computed by
  `c2` must depend, transitively, on one computed by `c1`. `always` and
  `sometimes` take a CEL predicate over the assets and become simulator
  assertions and Quint witnesses.
- A graph's effect row is the union of its cells' rows; it is what the
  Cedar baseline grants are generated from.

### Identity and edits

Each definition prints to a canonical text (fixed spacing, CEL printed from
its tree) and is identified by the SHA-256 of that text, so formatting and
comments never change a hash; a graph's hash covers the hashes of the
definitions it uses. (The canonical Preserves form of DESIGN.md
"Serialization" replaces this text once the codec exists; only the
hashing input changes.)

### Build stages

1. Syntax: AST, parser, canonical printer, hashes; the round-trip property.
2. Checking: records, variants and named types in the CEL checker; cells
   (turns, effects, state, holes, budgets); graphs (wiring, acyclicity,
   streams, routes, invariants, effect rows); a corpus of accepted and
   rejected programs.
3. Compilation to kernel components, replacement compatibility (what needs
   approval), and hash-guarded patches.

Signatures and functors, pass-through rows and holes inside larger CEL
expressions follow once these stand.

All three stages are built (`cordis/graph`). Compilation produces the
kernel's shape (keys, turns, emissions); since the kernel has no
conditionals, a route's guard and an asset's freshness are carried as
metadata for the turn runtime. Each compiled graph is run under the kernel
in random interleavings and must reach quiet with every asset provided,
only its cells' effect kinds emitted, and every `before` invariant the
checker accepted held, which tests the static check against runs.
Admission refuses a whole patch whose expected hash for any definition is
not the live one, and holds a checked revision that needs approval until
it is approved; a Hegel property replays `admission.qnt`'s scenario of two
agents patching from one snapshot.

## Types

- **Scalars:** `int`, `uint`, `double`, `string`, `bytes`, `bool`,
  `timestamp`, `duration`, and scalars with formats from RFC 0003
  (`string<uri>`, `string<max 256>`).
- **Records with rows:** `{ a : int; b : string; .. 'r }`. Closed by
  default; an open row `'r` lets a cell accept any record with at least
  those fields and pass the rest through.
- **Variants:** `Pass of Review.t | Fail of Review.t`. Routes over a variant
  must be exhaustive.
- **Lists, maps, optionals** (`t option`, consumed with `??` or `match`).
- **Arrows with effect rows:** `a ->{ E1, E2 | 'e } b`. The row lists the
  intent kinds a cell may emit; it is the cell's capability row, and Cedar
  baseline grants are generated from it.
- **Signatures and functors:** a graph may be parameterised by a module of
  a signature (`Author : AUTHOR`), so a scaffold can leave a slot an agent
  fills.

Inference is Hindley–Milner with Rémy-style row polymorphism for records
and the same machinery for effect rows (as Koka does), so annotations are
needed only at cell and graph boundaries. Every cell's signature is
declared; bodies are inferred and checked against it.

**Subtyping only at boundaries:** replacing a cell is allowed when its new
signature accepts at least the old input row and produces at least the old
output row, and its effect row is no larger. A change that narrows the
input, widens the effects or changes a variant needs approval (it is the
graph-level form of Cedar Analysis's "More Permissive").

## Bodies and turns

`use` splits a body into turns. Each turn is a CEL expression over the
cell's input, its state and the receipts of the turn's predecessors; it
evaluates to `(state', intents, next)`. The kernel runs each turn as one
iteration (an `Iter` with an `emit`), so:

- a `use` is a commit point: the turn's state update and its intent commit
  together, and the intent is released only then (outbox, RFC-level design
  in DESIGN.md "The outbox");
- a crash between turns resumes at the last committed turn; the receipt
  cache (the emission cell's memo) answers the `use` it was waiting on;
- `?todo` holes type-check and make the turn pending, so a partly written
  cell can be admitted and its finished turns soaked.

## Graphs

- **Assets** are the acyclic, memoised part: each asset is a cell applied
  to other assets. An asset is a Cordis component that requires its input
  assets' keys and provides its own.
- **Routes** carry results back in time, not in the dependency graph: `draft
  <~ r.notes` appends to `draft`'s input stream, which makes a new revision
  of `draft` and, through freshness, of everything downstream. A route
  never creates a dependency cycle.
- **Freshness** says when a memoised asset must recompute: `on_change` (any
  input hash changed), `always`, or `every <duration>` (time is an input).
  `assets.qnt` checks that memoisation plus freshness gives the same
  quiescent values as recomputing from scratch, and that an emission
  cell's memo never stands in for an emission that did not happen.
- **Invariants** (`critic before gate`, `always`, `sometimes`) become the
  graph's Quint witnesses and simulator assertions.

## Storage and edits

- Definitions are stored by the hash of their canonical Preserves form
  (DESIGN.md "Serialization"); a graph refers to cells and libraries by
  hash, so a definition can be reused and garbage-collected safely.
- **Edits are structural patches** against an expected hash: `replace node
  N in graph G@h with ...`. A patch whose base is not the live revision is
  refused, never merged silently. `admission.qnt` checks that concurrent
  agents cannot lose each other's updates, that only admitted revisions go
  live, and that a narrowing change never goes live unapproved.
- **Admission** runs the gates of DESIGN.md "Gates on change" in order:
  parse, type-check, CEL cost estimate within budget, Cedar Analysis of the
  derived policy, contract check against the previous revision, model
  check of the generated Quint module, soak. Then the old fibers retire and
  the new ones are inserted; by Cordis's confluence result the outcome is
  that of a cold start of the new revision.

## Compilation to the kernel

| Graph form | Kernel |
| --- | --- |
| asset `a = c x y` | component: requires `{x, y}`, provides `{a}`, iterations = the turns of `c` |
| `use` in a turn | the iteration's `emit` |
| `with state.p := e` | stages writing the cell's own state keys |
| route | an intent of kind `route.append` handled internally (an acquisition, not an emission) |
| graph revision | one O-Retire per removed fiber, one O-Insert per added one |

## Testing

- Parser round trip: print then parse gives the same tree (Hegel-generated
  trees).
- Type checker: a corpus of accepted and rejected programs with expected
  errors; principal types for the inference cases.
- Compilation: the components a graph compiles to, run in the simulator,
  must satisfy the graph's invariants; their traces conform to the
  generated Quint module.
