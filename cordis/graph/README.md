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
| `bin/cgraph.ml` | `cgraph fmt FILE`, `cgraph hash FILE` |
| `examples/review.cg` | The review loop of RFC 0002 |
| `test/props.ml` | Hegel: print/parse round trip, formatting invariance (spacing, comments, CEL spelling), hash sensitivity |

## Status

Stage 1 of RFC 0002's build stages (syntax, printing, identity) is done.
Stage 2 (checking) and stage 3 (compilation, compatibility, patches) are
next.
