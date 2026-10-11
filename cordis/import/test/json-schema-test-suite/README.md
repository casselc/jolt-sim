# JSON Schema Test Suite (drafts 4 to 2020-12)

The `tests/{draft2020-12,draft2019-09,draft7,draft6,draft4}/*.json` files
of the official
[JSON Schema Test Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite)
at commit `7de0e6a06031ede80028583dd45d0cd41105ae5b`, unmodified (MIT; see
`LICENSE`). Of the optional tests, only draft 2020-12's
`optional/ecmascript-regex.json` and `optional/non-bmp-regex.json` are
included: they cover the translation of ECMA-262 patterns into CEL's RE2.

`../schema_suite.ml` runs the validation predicate
(`../../lib/schema_pred.ml`) and the importer and codec against them; the
earlier drafts are read through `../../lib/schema_draft.ml`.
