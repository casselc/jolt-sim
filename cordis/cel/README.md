# cordis/cel: CEL for cells

An OCaml implementation of the Common Expression Language, as specified in
[RFC 0001](../../docs/cordis/rfc/0001-cel.md). No OCaml CEL existed; this
one is checked against cel-spec's conformance suite and, expression by
expression, against cel-go.

| Path | What it is |
| --- | --- |
| `lib/value.ml` | Values; CEL's heterogeneous numeric equality and ordering; maps kept in CEL key order so iteration is deterministic |
| `lib/lexer.ml`, `lib/parser.ml` | The grammar, with the standard macros, the two-variable comprehension macros, optional syntax and message literals |
| `lib/eval.ml` | The evaluator: error and short-circuit rules, containers, the standard library, and the actual-cost counter |
| `lib/checker.ml` | The type checker: inference with type variables, assignability and join, standard-library signatures |
| `lib/ctime.ml` | Timestamps and durations, RFC 3339 and Go-style parsing, calendar accessors in any IANA zone |
| `lib/proto.ml` | Protobuf messages from descriptors: construction, presence, defaults, well-known types, protobuf JSON and binary decoding |
| `lib/registry.ml`, `lib/extensions.ml` | Extension functions, macros and checker signatures, registered by library |
| `lib/ext_*.ml` | Libraries: strings, math, lists, sets, network, encoders, optionals, `cel.bind`/`cel.block`, proto extensions |
| `conformance/` | cel-spec's `tests/simple` suite and the descriptors of its protos as JSON (Apache-2.0; see its README) |
| `celgo/` | A JSON-lines front end to cel-go v0.30.0, the reference for the differential test |
| `test/conformance.ml` | Runs the suite; `--check-all` also type-checks every test that evaluates to a value |
| `test/differential.ml` | Hegel draws expressions; each must give the same value (or both an error) and the same actual cost as cel-go |

## Status

**Every cel-spec conformance test runs, and all 2527 pass.** Nothing is
skipped. The differential test agrees with cel-go on value and cost for
thousands of generated expressions.

| RFC 0001 step | State |
| --- | --- |
| 1. Parser, macros, evaluator, standard library | done |
| 2. Actual cost | done for unchecked evaluation: equal to cel-go's |
| 3. Type checker | done: all type-deduction tests pass, and every core test that evaluates checks to a type that admits its value; extension-library signatures in progress |
| 4. Static estimator and the Hegel cost-soundness property | next |
| 5. Timestamps and durations | done |
| 6. Harness extensions | two-variable comprehensions, `cel.bind`, `cel.block` in; `fold`, `match`, libraries next |

Beyond the RFC's first scope, protobuf messages are supported (they were
out of scope): they are what the protobuf importer of RFC 0003 will feed
cells, so the runtime half is done.

## Running

```sh
dune build @cel/test/runtest                       # the conformance suite (also part of dune test)
(cd cel/celgo && go build -o /tmp/celgo .)
CELGO=/tmp/celgo dune build @cel/test/differential # 2000 cases by default (CEL_DIFF_CASES)
```

## Decisions and cel-go differences

- **Enums are ints by default** (cel-go's legacy mode). Strong enums,
  typed values with `pkg.Enum(n)` and `pkg.Enum('NAME')` constructors, are
  an option (`Proto.strong_enums`); the conformance runner turns it on for
  the `strong_*` sections, so both halves of the enum tests run.
- **Time zones come from the host's zoneinfo** (`$ZONEINFO`, default
  `/usr/share/zoneinfo`), including backward-compatible names through
  `tzdata.zi` links. A cell that names a zone therefore depends on the
  host's tz data; the harness should pin it (or vendor it) per release.
- **NaN sorts first** in `sort` and `sortBy`, so sorting is total and
  deterministic; cel-go leaves NaN where its algorithm happens to put it.
- **Where cel-go and the conformance suite disagree, the suite wins**:
  `indexOf`/`lastIndexOf` with an offset past the end are errors, format
  errors use the suite's wording, and an IPv4-mapped IPv6 address written
  in hex is accepted as the IPv4 address (the dotted form is refused).

- **Map iteration is sorted** (RFC 0001, "Determinism"). cel-go iterates Go
  maps in random order, so a comprehension over a map can build lists in a
  different order there, and a short-circuiting one can cost a different
  amount. The differential test therefore only uses order-independent
  macros over maps and does not compare their costs.
- **Duplicate map keys are an error**, as cel-spec says; cel-go accepts them
  (it is on cel-go's own conformance skip list).
- **cel-go rejects some expressions while planning**, such as an index by
  the constant `null`, before the evaluation that the spec says would let
  `||` absorb the error. This implementation evaluates them.
- **Pairs of `!` or `-` cancel** (`!!x` is `x`), as in cel-go's parser;
  cel-spec's tests do not cover it, and the differential test found it.
- **Cost rules taken from cel-go** (`interpreter/runtimecost.go`): an
  identifier and each qualifier 1, a list 10, a map 30, a call 1 unless
  overload-specific, `&&` `||` `?:` nothing, equality
  `ceil(min(size l, size r) / 10)`; a strict call stops at its first error
  argument, and is charged only if every argument was evaluated.
