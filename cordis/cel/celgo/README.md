# celgo — cel-go reference evaluator

A small JSON-lines front end to [cel-go](https://github.com/google/cel-go),
used as an oracle for the OCaml CEL implementation and to cross-check the
conformance corpus in `../conformance`.

## Pinned versions

| | |
|---|---|
| `github.com/google/cel-go` | **v0.30.0** (latest release tag when pinned, 2026-10) |
| `cel.dev/expr` | v0.25.1 (indirect) |
| `google.golang.org/protobuf` | v1.36.10 |
| Go toolchain used | go1.24.7 (`go` directive in `go.mod`: 1.24.7) |

Extensions loaded (all available in v0.30.0, each at its latest version):
`ext.Strings()`, `ext.Math()`, `ext.Lists()`, `ext.Sets()`,
`ext.TwoVarComprehensions()`. `cel.HomogeneousAggregateLiterals` is left off
(the cel-go default). With `-profile conformance` it also loads
`cel.OptionalTypes()`, `ext.Bindings()`, `ext.Encoders()`, `ext.Network()`,
`cel.EnableIdentifierEscapeSyntax()` and
`cel.EnableErrorOnBadPresenceTest(true)`, which is closer to cel-go's own
conformance runner. No proto message types beyond cel-go's built-in
well-known types are registered.

## Build

```bash
cd cordis/cel/celgo
go build -o "$WORK/bin/celgo" .   # WORK: any scratch directory
```

(any output path works; `go mod download` first if offline caches are cold).

## Run

```bash
echo '{"expr": "x + 1", "bindings": {"x": {"int": "41"}}}' | celgo
# {"value":{"int":"42"},"cost":1,"estimate":{"min":1,"max":1}}
celgo -profile conformance < requests.jsonl > responses.jsonl
```

### Request (one JSON object per line)

```
{"expr": "...",
 "bindings": {"name": <value>},   // optional
 "disable_macros": false,          // optional: parse with no macros
 "disable_check": false,           // optional: evaluate the unchecked AST
 "id": <any>}                      // optional: echoed back
```

Values use the corpus encoding (`../conformance/README.md`): `int`, `uint`,
`double` (incl. `"NaN"`, `"Infinity"`, `"-Infinity"`, `"-0"`), `string`,
`bytes` (base64), `bool`, `null`, `list`, `map` (`[[k, v], ...]`), `type`,
`timestamp`, `duration`. `object` and `enum` are not accepted as bindings.

Every binding is declared `cel.Variable(name, cel.DynType)`.

### Response (one JSON object per line, same order)

```
{"value": <value>, "cost": n, "estimate": {"min": n, "max": n}}
{"error": "...", "stage": "input|parse|check|program|eval|output", "cost": n, "estimate": {...}}
```

- `cost` is cel-go's actual evaluation cost (`cel.CostTracking`), present
  whenever evaluation ran (also on evaluation errors).
- `estimate` is `env.EstimateCost` over the checked AST with no size hints, so
  anything depending on a `dyn` binding is wide (up to 2^64-1). It is present
  whenever the expression type-checks, including under `disable_check`.
  cel-go's estimate is not always a lower bound of the actual cost (e.g.
  `t == int` with `t` bound: min 3, actual 2).
- A check failure is reported as an error with `stage: "check"` unless
  `disable_check` is set.
- Map entries are sorted by the JSON encoding of their key.
- Output-only encodings: `{"optional": <value>|null}` for optional values,
  `{"unknown": [ids]}`, and `{"object": {"type_url", "json"}}` (protojson) for
  any remaining proto message.
- Timestamps/durations use protobuf JSON form (0/3/6/9 fractional digits).
