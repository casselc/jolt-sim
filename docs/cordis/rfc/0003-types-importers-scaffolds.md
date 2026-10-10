# RFC 0003: The type IR, importers and scaffolds

Status: revision 3 (the JSON Schema importer specified). **For protobuf,
superseded by [RFC 0004](0004-descriptor-native-types.md)**:
graphs use protobuf descriptors directly, and the translating importer
described below (implemented in `cordis/import`) remains as a report tool
showing what a schema would lose as graph-language records. The JSON
Schema, OpenAPI and AsyncAPI importers and the scaffolders remain as
designed in revision 1. Design context: [DESIGN.md](../DESIGN.md)
"Schemas" and "Scaffolding".

## Revision history

**Revision 1** (accepted for design) proposed a separate IR (records with
rows, variants with discriminators, `Ref` for recursion, formats, bounds)
that every importer targets.

**Revision 2** follows RFC 0002 revision 2, which made the graph language's
types concrete:

| Revision 1 | Revision 2 | Why |
| --- | --- | --- |
| A separate OCaml IR | The IR is the graph language's type form (RFC 0002 "Types"), plus what an importer records beside it: the name map, the residue and the report | One type form already checked, bounded and costed end to end; a second one would need its own checker |
| Residue as free-standing CEL predicates | Residue as one pure cell per type, `cell check_T(x : T) : bool`, in the emitted file | The graph checker checks the residue like any cell, and a graph can gate on it today |
| `Ref` for recursion | A recursive edge becomes `dyn`, reported | The graph language rejects recursive types; references come with a later revision of RFC 0002 |
| `Any` becomes `bytes` plus residue | `Any` becomes `dyn`, reported | At the boundary an `Any` is a JSON object with `@type`, not bytes |
| Identity (document, name, content hash) | Each emitted type's hash is its graph-language hash (RFC 0002 "Identity"); the report records the source document | The same hashing as every other definition |

**Revision 3** specifies the JSON Schema importer ("The JSON Schema
importer", below). It adds one idea revision 2 did not need: the
**validation predicate**, a compilation of any supported subschema to a
CEL predicate over the instance's plain JSON form, which is both the
residue of what the types cannot say and the codec's check at the
boundary. It is tested against the official JSON Schema Test Suite.

**Why no Quint model.** An importer is a pure function from a schema to a
file: no state, no concurrency, no partial failure. What can go wrong is a
translation that disagrees with the source schema, and that is tested
against the schema's own implementation (Testing, below), not modelled.
Re-importing a changed schema produces a revision that goes through RFC
0002's admission and compatibility gates, which `admission.qnt` already
covers.

## Purpose

Cells and graphs take their input and output types from existing contracts:
protobuf, JSON Schema, OpenAPI, AsyncAPI. Each importer writes graph-language
type definitions, so the graph checker, the CEL checker, the cost estimator,
the Hegel generators and the scaffolders all read one thing.

## What an import produces

1. **A types file** (`.cg`): one `type` definition per imported type, and
   one pure cell `check_T(x : T) : bool` per type that has residue. The file
   must pass the graph checker on its own, with no cost budget: a `check_`
   cell whose estimate exceeds the default budget is reported, not refused,
   since the budget belongs to the graph that uses it (nested repeated
   fields under protobuf's missing bounds make checks quadratic in the
   default ceiling, and the remedy is to bound them).
2. **Residue**: the constraints the types cannot express, as the CEL body of
   `check_T`; the runtime evaluates it at the trust boundary, and a graph
   may also gate on it as an asset.
3. **A report**: every construct that was approximated or dropped, with its
   source location (file and full name), so nothing is lost silently.
4. **A name map**: source name to type name, and per field the source's
   wire names, for the boundary codec.

**Size bounds flow to the cost estimator** where a source has them
(`maxLength`, `maxItems`, `maxProperties` in JSON Schema). A source without
them gets the configured default ceiling, and the report says so once.

## The protobuf importer

Input: a `FileDescriptorSet` (`buf build -o set.binpb`, or `protoc
--include_imports --descriptor_set_out`). Every message and enum in the set
is imported, except the well-known types, which map to graph-language types
directly.

### Names

- A type is named by its path inside its package, joined with `_`
  (`Outer.Inner` becomes `Outer_Inner`). When two packages yield the same
  name, both are prefixed with their package, dots as `_` (`acme_v1_Order`).
- Record fields keep the proto field names. The name map records each
  field's `json_name`; the codec reads either and writes the proto name,
  as protobuf's JSON parser does.

### Types

| Protobuf | Graph language | Residue or report |
| --- | --- | --- |
| `int64`, `sint64`, `sfixed64` | `int` | |
| `int32`, `sint32`, `sfixed32` | `int` | residue: the 32-bit range |
| `uint64`, `fixed64` | `uint` | |
| `uint32`, `fixed32` | `uint` | residue: at most `4294967295u` |
| `double` | `double` | |
| `float` | `double` | report: single precision is widened |
| `bool`, `string`, `bytes` | the same | |
| enum | a variant of unit cases, one per value name | report: an open (proto3) enum's unknown numbers are refused at the boundary; an alias keeps its first name |
| message | a closed record | |
| `repeated T` | `list(T)` | |
| `map<K, V>` | `map(K, V)`, `K` as above | residue: a 32-bit key's range |
| `oneof o { … }` | a field `o : option(M_o)`, `M_o` a variant with one case per member, named in CamelCase, carrying the member's type | |
| `google.protobuf.Timestamp`, `Duration` | `timestamp`, `duration` | |
| wrappers (`Int64Value`, …) | `option(` the scalar `)` | |
| `Struct`, `Value`, `ListValue` | `map(string, dyn)`, `dyn`, `list(dyn)` | |
| `Empty`, `FieldMask` | `{}`, `string` | |
| `Any` | `dyn` | report: the type URL is not checked |
| a reference that leads back to a message being defined | that reference becomes `dyn` (`repeated Category` stays a list, of `dyn`) | report: recursion is not representable yet, and the residue does not check beneath it |

**Presence.** A proto3 singular scalar without `optional` is always present
(absent means its default), so it is `T`. A field with explicit presence
(proto3 `optional`, proto2 `optional`, any singular message field) is
`option(T)`. A proto2 `required` field is `T`. Proto3's synthetic oneofs
(behind `optional`) are not variants.

### Residue

`check_M` is the conjunction of every constraint reachable from `M`'s fields
without crossing a recursive edge: a range on a 32-bit field, on each element
of a repeated one, on each key of a map; through an `option` only when it
has a value; through a nested message by inlining that message's
constraints (a cell cannot call another cell yet). A type with no
constraints gets no `check_` cell.

### The boundary codec

Values cross the boundary as protobuf JSON. The codec maps, both ways:
records to JSON objects by field name; absent `option` fields to omitted
members; a oneof's variant `{'tag': 'Text', 'value': v}` to the member
field `text: v`; an enum's case to its name (a number is read too);
`int64`/`uint64` to strings (numbers are read too); `bytes` to base64;
timestamps and durations to their RFC 3339 and `1.5s` forms; wrappers to
their value or omitted. Reading rejects what the types reject: unknown
fields, a second member of one oneof, an unknown enum name or number.

## The JSON Schema importer

Input: a JSON Schema document, draft 2020-12, or draft 4, 6, 7 or
2019-09 read as the 2020-12 keywords that mean the same
(`cordis/import/lib/schema_draft.ml`, walking schema positions only, so
`enum`, `const`, `default` and `examples` values are never rewritten):
`items` as an array is `prefixItems` and `additionalItems` is `items`;
`dependencies` is `dependentRequired` and `dependentSchemas`; a `$ref`'s
siblings are dropped before 2019-09, as those drafts say; draft 4's
boolean `exclusiveMinimum`/`exclusiveMaximum` become numbers and `id` is
`$id`. Any other `$schema` is refused. The root and every `$defs` entry become types; the
root is named by `title`, else by the file name. **Documents.** An import reads a set of documents: the schema, and any
others it names (`cimport jsonschema SCHEMA --with FILE|DIR`). Nothing is
fetched over the network. Each document has an absolute URI (its `$id`,
resolved against the URI it was given under), and when it is loaded
every `$ref` in it is rewritten to an absolute URI, resolved by RFC 3986
against the base its position has (the nearest enclosing `$id`), and
every resource (`$id`) and anchor (`$anchor`) is indexed by absolute URI.
After loading, resolving a reference is a lookup: a document, then a
JSON Pointer or an anchor in it. A reference to a document not in the set
is refused, naming it.

A schema whose validation the boundary could not check exactly is
**refused, not approximated**: a reference to a document not given,
`$dynamicRef`, `unevaluatedProperties`, `unevaluatedItems`, a custom
metaschema, and a pattern with no RE2 counterpart (lookaround,
backreferences, Unicode property classes).

### The validation predicate

Every supported subschema compiles to a CEL predicate over the instance
as plain JSON: objects as `map(string, dyn)`, arrays as lists, numbers as
`double`, and `null`. Each keyword is a conjunct, guarded by the type it
applies to, so a predicate never errors:

| Keyword | Predicate (at `x`) |
| --- | --- |
| `type` | `type(x) == string`, …; `integer` is a finite double with `math.trunc(x) == x` |
| `minLength`, `maxLength` | `size(x)` (CEL counts code points, as JSON Schema does) |
| `pattern` | `x.matches(re)`: both search unanchored; ECMA-262 features RE2 lacks (lookaround, backreferences) are refused |
| `minimum`, `maximum`, `exclusive…`, `multipleOf` | comparisons; `multipleOf m`: `math.trunc(x / m) == x / m` |
| `const`, `enum` | `==`, `in` (CEL's equality is JSON's: `1 == 1.0`, `false != 0`) |
| `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `min/maxProperties`, `dependentRequired`, `dependentSchemas` | `'p' in x`, `x.all(k, …)` |
| `items`, `prefixItems`, `contains`, `min/maxContains`, `min/maxItems`, `uniqueItems` | `x.all(i, v, …)`, `size(x.filter(v, …))`, `size(x.distinct()) == size(x)` |
| `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else` | `&&`, `\|\|`, `size([p1, …].filter(b, b)) == 1`, `!`, `c ? t : e` |
| `$ref` | the target's predicate, inlined; a recursive reference has no predicate (CEL has no recursion), so its schema is refused for validation and reported |
| `format`, `title`, `description`, `default`, `examples`, `deprecated`, `readOnly`, `writeOnly`, `$comment` | none: annotations (2020-12's default) |

### The interpreter

The same keyword semantics are also an OCaml interpreter
(`cordis/import/lib/schema_eval.ml`), which the codec uses at `dyn`
locations. Unlike the compiled predicate it follows a recursive `$ref`,
and it has no size limit, so a `dyn` location whose subschema recurses
or whose predicate would be too large is still checked exactly at the
boundary. A reference that returns to itself at the same place in the
instance (a loop that consumes nothing) is an error, not a hang. Inside
graphs, a `check_T` cell carries the compiled predicate with each
recursive edge cut to `true`, which the report states, or none if it
would not fit. The interpreter and the compiled predicate must agree
wherever the predicate compiles (Hegel, over generated schemas and
instances), and the interpreter is held to the official suite and to
Python's `jsonschema` like the predicate.

### Types

The types are the structural part of the schema, the predicate the rest:

| JSON Schema | Graph language | Residue |
| --- | --- | --- |
| `string` | `string`, `<max maxLength>` when given | the rest of its keywords |
| `integer`; `number` | `int` (a value beyond int64 is refused at the boundary); `double` | bounds, `multipleOf` |
| `boolean` | `bool` | |
| `array` with `items` | `list(T)`, `<max maxItems>` when given | `minItems`, `uniqueItems`, `contains` |
| `array` with `prefixItems` and `items: false` | a record `{ _0 : T0; _1 : T1; … }`, by position | |
| `object` with `properties` | a closed record; a property not in `required` is `option(T)` | the object's other keywords |
| `object` with only `additionalProperties` or one `patternProperties` | `map(string, T)`, `<max maxProperties>` when given | `propertyNames`, the pattern |
| `enum` of strings | a variant of unit cases | |
| `enum`, `const` otherwise | the values' common type | `in`, `==` |
| `[T, "null"]`, `anyOf`/`oneOf` of `T` and `null` | `option(T)` | |
| `oneOf`/`anyOf` of objects with a discriminating property (each branch's `const`, or OpenAPI's `discriminator`) | a variant, a case per branch named by the constant, carrying the branch's record without the discriminator | |
| `allOf` of objects | one record: properties and `required` merged | a property both define differently: report and `dyn` |
| `$ref` to `$defs/X` | `X` | |
| a recursive `$ref` | `dyn` | report; the codec reads the value as the referenced type, residue and all, so the boundary checks every level; `check_T` inside a graph stops at the edge |
| `true`, `{}`, and anything else (`not`, `if`, undiscriminated `oneOf`, mixed `type`) | `dyn` | the whole predicate |
| `false` | refused | |

**Where the boundary is stricter than the schema**, by design and
nowhere else (each reported per location, and each the only refusal the
tests allow beyond the schema's own):

- **Objects are closed.** A record refuses properties it does not name,
  even where the schema leaves the object open: at a trust boundary an
  unexpected property is refused, not carried. A schema that wants extra
  properties says so with `additionalProperties`.
- **An object schema without `type` is an object.** `{"properties": …}`
  with no `type` also admits non-objects; real schemas almost always mean
  an object, so the record requires one.
- **Integers fit int64.** A larger integer is refused.
- **A key appears once per object.** JSON leaves duplicate names
  undefined and parsers disagree on which one wins (Python's keeps the
  last), which is the material of parser-differential attacks; the codec
  refuses them.

**Null.** `[T, "null"]` and `anyOf`/`oneOf` of `T` and `null` are
`option(T)`; whether `null` itself satisfies the schema (a `const` or
`not` beside the `type` applies to it too) is decided at import, since
`null` is one value. A `oneOf` whose other branch also admits `null` is
`dyn`, as is a nullable `dyn`. At a `dyn` location `null` is a value, so
an optional `dyn` property distinguishes absent from `null`.

**A required property the schema does not describe** is a required field
of any value; if `additionalProperties` is `false` as well, nothing
satisfies the object, and its residue is `false`.

**References.** Every local `$ref` is a definition, keyed by the
reference and translated once (the root is `#`); a reference back into
one being translated is a recursive edge.

**Names.** A type is named from its `$defs` key in CamelCase; a property
whose name is not an identifier (`first-name`, `$id`) becomes one
(`first_name`, `_id`), and a CEL reserved word is selected with
backquotes, as for protobuf. The name map keeps every JSON name; a
collision is suffixed and reported. Variant cases are named from the
string constants the same way.

**Size bounds** come from `maxLength`, `maxItems` and `maxProperties`
where the schema gives them, so the cost estimator uses the schema's own
bounds; a missing bound gets the default ceiling, reported once.

### Residue and the codec

Each type has two residues. Its **local residue** checks its own
keywords and does not look into the named types it holds; the codec
checks every named value it reads against its own, so the boundary
checks everything in time linear in the document. Its **`check_T` cell**
writes out every reachable type's residue inline, since a cell cannot
call another; where definitions are widely shared that is exponential,
so it is generated under a budget and written only if it fits the graph
checker's bound (10,000 nodes, counted as the checker counts). A type
whose cell does not fit is reported: the boundary still checks it, but a
graph cannot gate on it.

`check_T` is the predicate's typed residue: the keywords the type does
not capture, over the typed value (a field of an `option` checked only
when present), with any `dyn` location checked by its whole predicate.
The codec maps JSON to and from typed values by the name map. Reading is
strict: it refuses what the type refuses (a missing required property, a
property the record does not name, a value of another JSON type, an
integer with a fraction or beyond int64, a string constant that is no
case) and then evaluates `check_T`; writing prints properties in schema
order and absent `option`s as omitted members.

### Testing

- **The official JSON Schema Test Suite** (drafts 4, 6, 7, 2019-09 and
  2020-12, vendored in `cordis/import/test/json-schema-test-suite`; the
  earlier drafts test the draft reading), each draft twice:
  - the predicate's verdict is the suite's for every test whose schema
    compiles (2020-12: 1,030 tests; all drafts: 4,366; none disagree);
  - the interpreter's verdict is the suite's for every test whose schema
    it reads (2020-12: 1,038; all drafts: 4,404; none disagree);
  - for every test whose schema imports, the types file checks and the
    codec reads the instance exactly when the suite calls it valid, but
    for the documented refusals above (2020-12: 1,010 agree and 14 are
    documented refusals; all drafts: 4,278 and 70; none disagree).
  The suite's remote documents are loaded under
  `http://localhost:1234/`, as the suite serves them.
  The schemas that do not compile or import are listed, by reason, in a
  checked-in golden file.
- **Agreement with Python's `jsonschema` (Hegel, `schema_agreement.ml`)**:
  - corpus schemas: values drawn from the imported types, with scalars
    from pools built around every number and string in the schema (both
    sides of each bound), are written by the codec; `jsonschema` accepts
    the JSON exactly when the codec's strict read does, and the read gives
    back the value. The same JSON with one node changed (`null`, a
    fraction, a string, a property dropped or added, an empty list) is
    checked the same way, allowing only the documented refusals;
  - generated schemas: one to three keywords drawn uniformly from every
    supported keyword, nested; the predicate's verdict is `jsonschema`'s;
  - generated schemas, imported: the types file checks, and the codec
    reads an instance (drawn, or written from the type and perturbed)
    only if `jsonschema` accepts it, and refuses a valid one only as
    documented;
  - examples of every rule of the importer's own, with the verdict
    `jsonschema`'s, not written by hand.
- **SchemaStore** (`schemastore.ml`, at a pinned commit; the alias
  `@import/test/schemastore`): every schema is imported in one shared
  document set with the draft metaschemas, and every test instance of an
  imported schema is read by the codec and validated by `jsonschema`. Of
  990 schemas, 893 import and their types check; 97 are refused, by kind
  (29 a remote reference to a document not in the set, 29 a lookaround
  pattern, 26 `unevaluatedProperties`, the rest a handful each). Of the
  instances, 1,886 agree with `jsonschema`, 220 valid ones are documented
  refusals, 37 `jsonschema` itself cannot validate, and none disagree.
  The listing is checked in, as for the suite.
- **Golden imports** of the corpus schemas (types, report, name map).
- **Mutants** of the predicate compiler, the importer and the codec: of
  27 tried, 25 are caught by the properties, the examples or the suite;
  one is equivalent (it skipped a residue the corpus types do not have)
  and one did not compile. The tests found a dozen importer and codec
  bugs before they were committed, each now covered.

## Importers to come

| Source | Maps to | Lossy where |
| --- | --- | --- |
| JSON Schema (2020-12) | specified above | |
| OpenAPI 3.1 | schemas via the JSON Schema importer; each operation to an effect (an intent kind) | callbacks and links are reported, not imported |
| AsyncAPI 3 | channels and send or receive operations to the graph's inputs and effects | bindings beyond the message payload are reported |
| XSD, CSV (with Table Schema), Arrow (for Parquet and Vortex) | see [RFC 0005](0005-xml-csv-frames.md) (proposed) | |

From OpenAPI, per operation: the method sets the retry rule (GET, PUT,
DELETE idempotent; POST only with an idempotency-key header), the security
scheme names the credential the outbox holds, and `servers` becomes the
egress allowlist that the `pf` rules and the gateway are generated from.

## Scaffolds

Generation-gap: a scaffold writes generated files that are regenerated
freely and stubs that are written once and then owned by the agent or a
person.

- **From OpenAPI, AsyncAPI or gRPC:** one router graph per service, one
  handler cell per operation with its typed signature and a `?todo` body,
  the intent kinds for its outbound calls, a Quint module with the routes as
  actions, and Cedar baseline grants from the effect rows.
- **From a CRD:** a reconcile cell (desired spec, observed status) to
  (status', intents), in Metacontroller's pure shape; finalizers become the
  component's Unloading guard; server-side-apply field ownership is the
  per-key commutativity witness.

## Testing

- **Golden files:** for a corpus of `.proto` files, the descriptor set's
  import (types file, report, name map) against a checked-in expectation.
- **The output checks:** every types file an import writes passes the
  graph checker.
- **Agreement with protobuf itself (Hegel):** for every imported message,
  values generated from its graph-language type, encoded by the codec, are
  accepted by protobuf's own JSON parser (Python `json_format`), and what
  protobuf prints back decodes to the same value; the residue accepts every
  such value; and a value protobuf rejects (a 32-bit field out of range) is
  rejected by the residue.
- **The codec by example:** what protobuf JSON may say that a generated
  value never does (`null` for absent, JSON names, enum numbers, `int64`
  as numbers), and what reading must refuse (unknown fields, two members of
  a oneof, unknown enum names or numbers, 32-bit values out of range, a
  missing proto2 `required` field).
- **Round trip** (later): descriptor to types to descriptor, where the
  format allows.

The agreement property draws 32-bit values at both sides of their bounds,
in a minority of cases, so most generated messages are entirely valid and
everything else about them is tested too. Mutants of the importer and
codec (a dropped or off-by-one range, a wrong field width, a misnamed oneof
member, a broken bytes encoding, ignored proto3 presence) are each caught
by it, the codec examples or the golden files.
