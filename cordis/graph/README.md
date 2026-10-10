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
| `lib/compile.ml` | Compilation to kernel components, and a run of them under the kernel's rules with what it must show |
| `lib/compat.ml` | Replacement compatibility: what changed between revisions, and what needs approval |
| `lib/patch.ml` | Hash-guarded patches (`replace NAME@HASH`, `add`, `remove NAME@HASH`) and admission |
| `bin/cgraph.ml` | `cgraph fmt`, `hash`, `check`, `run FILE`; `cgraph diff OLD NEW`; `cgraph admit LIVE PATCH [--approved]`; `--store DIR` names the directory whose files `uses` finds by hash |
| `examples/review.cg` | The review loop of RFC 0002 |
| `test/props.ml` | Hegel: print/parse round trip, formatting invariance (spacing, comments, CEL spelling), hash sensitivity |
| `test/corpus/` | Programs the checker must accept (`ok_*`) or reject with a given error (`bad_*`), one rule each; `libs/` is their content store (a library text, descriptor sets), named in a program as `HASH(file)` |
| `test/soundness.ml` | Hegel: for inputs of the declared types, every turn costs at most its estimate and results have their declared types, over the corpus and over generated cells |
| `test/stage3.ml` | Hegel: compiled graphs run to quiet under any interleaving with assets provided, effect rows kept and statically accepted invariants held (random graphs with random invariants); admission as `admission.qnt` states it; compatibility examples |

## Status

RFC 0002's three build stages are done: syntax, printing and identity;
checking; compilation to the kernel, replacement compatibility and
hash-guarded patches. Next: signatures and functors, pass-through rows,
holes inside larger CEL expressions, the generated Quint module per graph,
and storage in a content-addressed store (Irmin) in place of a file.

Size bounds (`<max N>`) in a cell's parameter types are what its turns'
cost estimates assume; the runtime must enforce them at the cell's
boundary (phase 3, with the rest of the turn runtime). Where a bound can be
checked statically it is: every stream declares its capacity (`stream
notes : string up to 8;`), and a stream may feed only a parameter whose
bound covers it.

What routes and streams do at run time (guards that compute or withdraw,
appends keyed by the scrutinee's revision, full streams refusing, a new
input emptying the streams) is specified in RFC 0002 revision 2 and checked
in `quint/routes.qnt`, ahead of the turn runtime that will implement it.

**Protobuf types** ([RFC 0004](../../docs/cordis/rfc/0004-descriptor-native-types.md),
stage 2): a file attaches descriptor sets with `uses descriptors
sha256:<hex>;`, and its signatures name messages and enums by full name
(`o : acme.shop.v1.Order`). The file is checked with a registry built from
just those sets, and values are protobuf messages under CEL's proto
semantics: messages are nominal, enums are `int`, oneofs are fields, the
well-known types are what CEL makes of them, and recursion is native. A
message's cost shape comes from its descriptor field by field, repeated
fields, maps, strings and bytes bounded by the default ceiling. The
soundness property covers the corpus's protobuf cells with messages
generated from the descriptors. Next: the boundary codec over the
registry (stage 3) and descriptor compatibility in admission (stage 4).
