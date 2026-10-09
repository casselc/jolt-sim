# CEL conformance corpus (JSON)

The `simple/*.json` files are a mechanical conversion of the cel-spec
"simple" conformance tests, `tests/simple/testdata/*.textproto`, from
<https://github.com/google/cel-spec> at commit
**`40a3c9007a9305d1f2638d5f538a4e011732cace`** (2026-09-16). Each JSON file
records its source and that SHA in its `"source"` field.

The test data is Copyright Google LLC and licensed under the Apache License
2.0; cel-spec's license is copied alongside as `LICENSE-cel-spec`. The
converted files are a derivative of that data and carry the same license.

## Files

| path | what |
|---|---|
| `simple/<name>.json` | one file per cel-spec textproto |
| `convert.py` | the converter (textproto -> JSON) |
| `crosscheck.py` | runs the plain-value tests through `../celgo` and compares |
| `LICENSE-cel-spec` | cel-spec's Apache-2.0 license |

## Regenerating

The textprotos are parsed with protobuf's own text-format parser against
descriptors compiled from cel-spec's `.proto` files (`grpc_tools.protoc` ->
`_pb2` modules -> `text_format.Parse`); nothing is regex-parsed. Tested with
Python 3.13, protobuf 7.36.2, grpcio-tools 1.84.0.

```bash
SCRATCH=/tmp/celwork            # anywhere outside the repo
mkdir -p $SCRATCH/src
git clone https://github.com/google/cel-spec $SCRATCH/src/cel-spec
git -C $SCRATCH/src/cel-spec checkout 40a3c9007a9305d1f2638d5f538a4e011732cace
python3 -m venv $SCRATCH/venv
$SCRATCH/venv/bin/pip install protobuf grpcio-tools
cd cordis/cel/conformance
$SCRATCH/venv/bin/python -I convert.py --cel-spec $SCRATCH/src/cel-spec \
    --out simple --gen $SCRATCH/gen --stats
```

`--stats` prints the per-file table below. To move to a newer cel-spec,
check out a different commit; the SHA is read from the checkout.

## Schema

```
{ "source": "cel-spec tests/simple/testdata/<file>.textproto @ <sha>",
  "name": "<SimpleTestFile.name>",
  "sections": [ { "name": "...", "tests": [ {
      "name", "expr", "disable_macros", "disable_check",
      "container": "",                 // "" when unset
      "bindings": { "x": <value> },    // {} when none
      "type_env": [ {"ident": n, "type": "<type>"} |
                    {"function": n, "overloads": [{"id", "params": [<type>],
                      "result": <type>, "type_params": [..], "receiver": bool}]} ],
      "expect": <value> | {"error": msg} | {"unknown": [ids]} | {"any_value": true},
      "check_only": false,
      "typed_result": null | {"value": <value>|null, "type": "<type>"}
  } ] } ] }
```

Values (one key per object): `{"int": "<decimal>"}`, `{"uint": "<decimal>"}`,
`{"double": <number> | "NaN" | "Infinity" | "-Infinity" | "-0"}`,
`{"string": s}`, `{"bytes": "<base64>"}`, `{"bool": b}`, `{"null": null}`,
`{"list": [v...]}`, `{"map": [[k, v], ...]}` (source order),
`{"type": "<name>"}`, `{"enum": {"type": t, "value": n}}`,
`{"timestamp": "<RFC3339>"}`, `{"duration": "<e.g. 1.5s>"}` (protobuf JSON
forms: 0, 3, 6 or 9 fractional digits), and
`{"object": {"type_url": u, "json": <MessageToDict of the unpacked Any>}}`.
Any message whose descriptor is not compiled would be
`{"json": {"unsupported": true}}`; with all cel-spec protos compiled, none is.

Type strings are a short CEL-ish text form: `int`, `uint`, `double`, `bool`,
`string`, `bytes`, `dyn`, `null_type`, `wrapper(int)`, `list(T)`,
`map(K, V)`, `type(T)` (bare `type` when unparameterised),
`google.protobuf.Timestamp|Duration|Any`, message names, type params by name,
`function(A, B) -> R`, abstract types as `name(P, ...)`, and `*error*`.

### Conversion caveats

- **Unset matcher** means `{"bool": true}` (the spec's default).
- **`any_eval_errors` / `any_unknowns`** (one of several sets) keep only the
  FIRST set; `eval_error` keeps only the first error's message. Expected error
  messages are informal: cel-spec matchers (and cel-go's runner) only require
  that *an* error occurs, so a consumer should not require message equality.
- **`check_only` with no `typed_result.result`**, and `typed_result` with no
  result, give `expect: {"any_value": true}` and `typed_result.value: null`.
- `typed_result.value` duplicates `expect` when present.
- Bindings that are `ExprValue.error` / `.unknown` would encode as
  `{"error": ..}` / `{"unknown": [..]}`; none occur at this SHA.
- `type_env` ident decls with a constant value carry an extra `"value"` key
  (protobuf JSON of the `Constant`); a non-empty `locale` would add a
  `"locale"` key. Neither occurs at this SHA.
- `object.json` uses the canonical protobuf JSON mapping (lowerCamel field
  names, 64-bit ints as strings, extensions as `"[full.name]"` keys, wrapper and
  `Value`/`Struct` messages unwrapped to their JSON form).
- `unknowns.textproto` has no tests at this SHA (0 sections).

## Counts

`object` / `enum` = tests with a proto message or enum anywhere in bindings,
expectation or typed result. `proto_expr` = the expression names a
conformance proto type or enum (`TestAllTypes`, `cel.expr.conformance...`,
`GlobalEnum`, `NestedEnum`). `usable` = none of object, enum, type_env,
check_only, container, proto_expr: the plain-value tests an evaluator without
the conformance protos can run.

|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| basic | 5 | 43 | 0 | 0 | 0 | 0 | 4 | 0 | 0 | 39 |
| bindings_ext | 1 | 8 | 0 | 0 | 0 | 0 | 3 | 2 | 0 | 5 |
| block_ext | 1 | 37 | 11 | 0 | 0 | 0 | 14 | 1 | 1 | 22 |
| comparisons | 10 | 406 | 0 | 0 | 0 | 0 | 9 | 44 | 44 | 353 |
| conversions | 9 | 109 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 109 |
| dynamic | 19 | 226 | 103 | 0 | 0 | 0 | 18 | 153 | 153 | 53 |
| encoders_ext | 3 | 4 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 4 |
| enums | 4 | 85 | 21 | 21 | 0 | 0 | 7 | 82 | 76 | 0 |
| fields | 5 | 60 | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 48 |
| fp_math | 1 | 30 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 30 |
| integer_math | 2 | 64 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 64 |
| lists | 4 | 39 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 39 |
| lists_ext | 7 | 52 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 52 |
| logic | 4 | 30 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 30 |
| macros | 6 | 44 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 44 |
| macros2 | 5 | 46 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 46 |
| math_ext | 21 | 199 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 199 |
| namespace | 3 | 14 | 0 | 0 | 0 | 0 | 14 | 11 | 0 | 0 |
| network_ext | 4 | 69 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 69 |
| optionals | 1 | 70 | 0 | 0 | 0 | 0 | 0 | 11 | 11 | 59 |
| parse | 9 | 219 | 17 | 0 | 0 | 0 | 1 | 20 | 26 | 192 |
| plumbing | 4 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 5 |
| proto2 | 9 | 118 | 51 | 0 | 0 | 0 | 20 | 97 | 103 | 0 |
| proto2_ext | 2 | 18 | 18 | 0 | 0 | 0 | 18 | 0 | 18 | 0 |
| proto3 | 7 | 85 | 33 | 0 | 0 | 0 | 2 | 82 | 83 | 0 |
| string | 7 | 51 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 51 |
| string_ext | 15 | 216 | 0 | 0 | 0 | 0 | 7 | 0 | 3 | 206 |
| timestamps | 11 | 78 | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 77 |
| type_deduction | 12 | 66 | 4 | 0 | 34 | 66 | 23 | 7 | 7 | 22 |
| unknowns | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| wrappers | 16 | 36 | 0 | 0 | 0 | 0 | 0 | 36 | 36 | 0 |
| **total** | 207 | 2527 | 258 | 21 | 34 | 66 | 153 | 546 | 561 | 1818 |

## Cross-check against cel-go

`crosscheck.py` feeds every `usable` test to `../celgo` (cel-go v0.30.0) and
compares results. Matching: CEL value equality (maps unordered, NaN == NaN,
-0 == 0, timestamps/durations compared as instants/lengths); an expected
error must be an evaluation error (a parse/check failure is a mismatch, as in
cel-go's own runner), message not compared.

```bash
python3 -I crosscheck.py --celgo <path>/celgo                         # profile default
python3 -I crosscheck.py --celgo <path>/celgo --profile conformance   # + optionals, bindings, encoders, network
```

| file | total | usable | agree (default) | agree (conformance) |
|---|---:|---:|---:|---:|
| basic | 43 | 39 | 39 | 39 |
| bindings_ext | 8 | 5 | 0 | 5 |
| block_ext | 37 | 22 | 0 | 0 |
| comparisons | 406 | 353 | 353 | 353 |
| conversions | 109 | 109 | 109 | 109 |
| dynamic | 226 | 53 | 53 | 53 |
| encoders_ext | 4 | 4 | 0 | 4 |
| enums | 85 | 0 | 0 | 0 |
| fields | 60 | 48 | 45 | 45 |
| fp_math | 30 | 30 | 30 | 30 |
| integer_math | 64 | 64 | 64 | 64 |
| lists | 39 | 39 | 39 | 39 |
| lists_ext | 52 | 52 | 52 | 52 |
| logic | 30 | 30 | 30 | 30 |
| macros | 44 | 44 | 44 | 44 |
| macros2 | 46 | 46 | 46 | 46 |
| math_ext | 199 | 199 | 199 | 199 |
| namespace | 14 | 0 | 0 | 0 |
| network_ext | 69 | 69 | 0 | 59 |
| optionals | 70 | 59 | 0 | 58 |
| parse | 219 | 192 | 192 | 192 |
| plumbing | 5 | 5 | 5 | 5 |
| proto2 | 118 | 0 | 0 | 0 |
| proto2_ext | 18 | 0 | 0 | 0 |
| proto3 | 85 | 0 | 0 | 0 |
| string | 51 | 51 | 51 | 51 |
| string_ext | 216 | 206 | 204 | 204 |
| timestamps | 78 | 77 | 77 | 77 |
| type_deductions | 66 | 22 | 22 | 22 |
| unknowns | 0 | 0 | 0 | 0 |
| wrappers | 36 | 0 | 0 | 0 |
| **total** | 2527 | 1818 | 1654 | 1780 |

All 1818 usable tests were run. With the `conformance` profile cel-go
agrees on **1780**; the 38 mismatches are:

- **block_ext (22)**: `cel.block` / `cel.index` / `cel.iterVar` are macros
  that only cel-go's conformance *test harness* defines (`celBlockLib` in
  cel-go's `conformance_test.go`); they are not part of cel-go's public
  extensions, so celgo does not provide them. Not a cel-go evaluation
  difference.
- **Genuine cel-go differences, all in cel-go's own conformance skip list**
  (`conformance/BUILD.bazel` at v0.30.0):
  - `fields/qualified_identifier_resolution/map_key_float` — cel-go accepts
    double map keys (`{3.3:15.15, 1.0: 5}[1.0]` gives `5`); spec wants an error.
  - `fields/qualified_identifier_resolution/map_value_repeat_key` and
    `map_value_repeat_key_heterogeneous` — cel-go keeps the last/first
    duplicate key instead of failing.
  - `string_ext/value_errors/indexof_out_of_range`, `lastindexof_out_of_range`
    — cel-go returns `-1` for an out-of-range start offset; spec wants an
    error (cel-go notes "need a spec update").
  - `optionals/optionals/map_optional_select_has` —
    `has({'foo': optional.none()}.foo.bar)`: cel-go raises `no such key: bar`,
    spec wants `false`.
- **Genuine cel-go differences in network_ext (10)** (network_ext is not in
  cel-go's conformance run at all):
  - 8 invalid-literal tests (`ip('192.168.0.1.0')`, `cidr('192.168.0.0/')`,
    `isIP(cidr(...))`, zone / IPv4-mapped literals): cel-go validates
    constant `ip()`/`cidr()` arguments at CHECK time, so the error is a
    compile error rather than the evaluation error the spec expects.
  - `ipv4/ipv4_equals_ipv6`, `ipv4_not_equals_ipv6`: cel-go rejects the
    IPv4-mapped IPv6 literal `::ffff:c0a8:1` outright; the spec expects it to
    compare equal to `192.168.0.1`.

With the `default` profile (exactly the stdlib + Strings/Math/Lists/Sets/
TwoVarComprehensions) cel-go agrees on 1654; the 164 mismatches are the 5
fields/string_ext differences above, the 22 block_ext tests, and all 137
usable tests of bindings_ext (5), encoders_ext (4), network_ext (69) and
optionals (59), whose libraries that profile does not load.

Of the passing error tests, ~117 have an expected message that is not a
substring of cel-go's (e.g. spec `divide by zero` vs cel-go
`division by zero`; `--verbose` lists them). The spec treats messages as
informal.
