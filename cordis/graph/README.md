# cordis/graph: the graph language

The language agents use to change what the harness does: cells (CEL bodies
with effects and state) and the graphs that wire them, specified in
[RFC 0002](../../docs/cordis/rfc/0002-graph-language.md). Every expression
in it is CEL, parsed and checked by [`../cel`](../cel).

| Path | What it is |
| --- | --- |
| `lib/syntax.ml` | The tree: types, effects, cells (lets, uses, final expression, state updates), graphs (inputs, streams, assets, routes, invariants) |
| `lib/parse.ml` | The parser; each CEL expression is taken whole up to its terminator and handed to the CEL parser |
| `lib/celtext.ml` | A CEL expression's canonical text: its tokens with fixed spacing |
| `lib/print.ml` | The canonical printer and each definition's hash (over its text and the hashes of what it names) |
| `lib/check.ml` | The checker: types, cells (statements, turns, effects, state, holes, per-turn cost against a budget), graphs (wiring with per-use type variables and width subtyping, acyclicity, routes, streams, invariants, effect rows) |
| `lib/lower.ml` | Record literals and variant constructors into the CEL maps that represent them at runtime |
| `bin/cgraph.ml` | `cgraph fmt FILE`, `cgraph hash FILE`, `cgraph check FILE` |
| `examples/review.cg` | The review loop of RFC 0002 |
| `test/props.ml` | Hegel: print/parse round trip, formatting invariance (spacing, comments, CEL spelling), hash sensitivity |
| `test/corpus/` | Programs the checker must accept (`ok_*`) or reject with a given error (`bad_*`), one rule each |
| `test/soundness.ml` | Hegel: for inputs of the declared types, every turn costs at most its estimate and results have their declared types, over the corpus and over generated cells |

## Status

Stages 1 (syntax, printing, identity) and 2 (checking) of RFC 0002's build
stages are done; stage 3 (compilation to the kernel, replacement
compatibility, hash-guarded patches) is next.

Size bounds (`<max N>`) in a cell's parameter types are what its turns'
cost estimates assume; the runtime must enforce them at the cell's
boundary (phase 3, with the rest of the turn runtime). Until then a bound
is a promise the checker relies on.
