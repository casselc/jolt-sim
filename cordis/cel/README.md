# cordis/cel: CEL for cells

An OCaml implementation of the Common Expression Language, as specified in
[RFC 0001](../../docs/cordis/rfc/0001-cel.md). No OCaml CEL existed; this
one is checked against cel-spec's conformance suite and, expression by
expression, against cel-go.

| Path | What it is |
| --- | --- |
| `lib/value.ml` | Values; CEL's heterogeneous numeric equality and ordering; maps kept in CEL key order so iteration is deterministic |
| `lib/lexer.ml`, `lib/parser.ml` | The grammar, with the standard macros and the two-variable comprehension macros expanded to comprehensions at parse time |
| `lib/eval.ml` | The evaluator: error and short-circuit rules, the standard library, and the actual-cost counter |
| `conformance/` | cel-spec's `tests/simple` suite as JSON (Apache-2.0, see its README for the pinned SHA and how to regenerate) |
| `celgo/` | A JSON-lines front end to cel-go v0.30.0, the reference for the differential test |
| `test/conformance.ml` | Runs the suite: every test passes, fails or is skipped with its reason |
| `test/differential.ml` | Hegel draws expressions; each must give the same value (or both an error) and the same actual cost as cel-go |

## Status (RFC 0001 phasing)

| Step | State |
| --- | --- |
| 1. Parser, macros, evaluator, standard library without time types | done: 1043 conformance tests pass, 0 fail |
| 2. Actual cost | done for unchecked evaluation: equal to cel-go's on 5,000 generated expressions |
| 3. Type checker | next; it also enables cel-go's per-overload costs (string and regex operations by length, `in` by list size), which cel-go applies only to checked expressions |
| 4. Static estimator and the Hegel cost-soundness property | after 3 |
| 5. Timestamps and durations | 78 conformance tests waiting |
| 6. Harness extensions (`fold`, `match`, libraries) | two-variable comprehensions are in |

Skipped conformance tests, by reason: containers 231, protobuf messages,
enums and wrappers 361 (out of scope), extension libraries 548 (strings,
math, lists, network, bindings, encoders, `cel.block`), timestamps 83,
optional types 70, type checker 66.

## Running

```sh
dune build @cel/test/runtest                       # the conformance suite (also part of dune test)
(cd cel/celgo && go build -o /tmp/celgo .)
CELGO=/tmp/celgo dune build @cel/test/differential # 2000 cases by default (CEL_DIFF_CASES)
```

## Decisions and cel-go differences

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
