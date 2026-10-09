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

Top-level forms: `type`, `cell`, `graph`, `signature` (module types such as
`AUTHOR`), `uses` (library imports by hash). A cell body is a sequence of
`let` (pure CEL), `use` (an effect: a wait), and a final expression,
optionally followed by `with` path updates to the cell's state.

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
