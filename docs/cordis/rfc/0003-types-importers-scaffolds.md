# RFC 0003: The type IR, importers and scaffolds

Status: revision 3 (the JSON Schema importer specified); revision 4
(the OpenAPI importer) proposed. **For protobuf,
superseded by [RFC 0004](0004-descriptor-native-types.md)**:
graphs use protobuf descriptors directly, and the translating importer
described below (implemented in `cordis/import`) remains as a report tool
showing what a schema would lose as graph-language records. The
AsyncAPI importer and the scaffolders remain as designed in revision 1. Design context: [DESIGN.md](../DESIGN.md)
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

**Revision 4** (proposed) specifies the OpenAPI importer ("The OpenAPI
importer", below): an operation becomes an effect whose request and
response types come from the JSON Schema importer, and whose retry class
comes from its method. It adds one thing the runtime did not have, a
third retry class, **keyed**, for a non-idempotent operation that accepts
an idempotency key; that class is stateful, so it is modelled
(`observe.qnt`) before it is built.

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
`$dynamicRef` (and 2019-09's `$recursiveRef`), a custom metaschema, and a
pattern with no RE2 counterpart (lookaround, backreferences, Unicode
property classes). `unevaluatedProperties` and `unevaluatedItems` are
read by the interpreter only ("The interpreter").

**Patterns stay RE2, and lookaround stays refused.** One regular
expression dialect holds everywhere a pattern is evaluated: CEL's
`matches` in cells, the compiled predicate, and the interpreter. Lookaround
(29 SchemaStore schemas) and backreferences could be read by the
interpreter alone with a backtracking engine, but that would give the
boundary a second dialect, with matching that can take exponential time
on a hostile string, and a pattern whose meaning differed between a
`check_T` cell and the codec. A schema that needs one is refused, naming
the pattern; its author can rewrite it in RE2 (most lookaround in the
corpus guards a URL or an e-mail address, which `format` describes), or
the harness can admit it as `dyn` with the pattern dropped and reported,
which is an explicit, reviewed choice and not this importer's default.

**A pattern is translated, where the two dialects spell one thing
differently.** CEL's `matches` matches by code point (`cel/lib/re2.ml`,
RFC 0001), so a class or a range may name characters beyond ASCII, as
ECMA-262's may. The translation (`Schema_pred.translate`) rewrites
`\uHHHH` (a surrogate pair as the one character it encodes) and `\xHH`
as `\x{...}`, `\cX` as its control character and `\0` as NUL; `.`
excludes all four of ECMA's line terminators, not only `\n`; `\s` and
`\S` are ECMA's white space (Unicode's spaces and the BOM), not RE2's
ASCII four, written out as ranges; a letter escaped for no reason is the
letter (ECMA's identity escape, which RE2 rejects); `(?<name>` is
`(?P<name>`; `[^]` is any character. It refuses a lone surrogate, a
legacy octal escape and the empty class `[]`, besides lookaround,
backreferences and Unicode property classes. The suite's
`optional/ecmascript-regex.json` and `optional/non-bmp-regex.json` are
vendored with the Test Suite, and all their tests agree but the four
groups that use `\p{...}`; a translation that gives `\s` RE2's meaning
fails five of them.

### The validation predicate

Every supported subschema compiles to a CEL predicate over the instance
as plain JSON: objects as `map(string, dyn)`, arrays as lists, numbers as
`double`, and `null`. Each keyword is a conjunct, guarded by the type it
applies to, so a predicate never errors:

| Keyword | Predicate (at `x`) |
| --- | --- |
| `type` | `type(x) == string`, …; `integer` is a finite double with `math.trunc(x) == x` |
| `minLength`, `maxLength` | `size(x)` (CEL counts code points, as JSON Schema does) |
| `pattern` | `x.matches(re)`, the pattern translated from ECMA-262 to RE2 (below): both search unanchored; ECMA-262 features RE2 lacks (lookaround, backreferences, Unicode property classes) are refused |
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
would not fit.

The interpreter also reads `unevaluatedProperties` and
`unevaluatedItems`, which depend on what other keywords evaluated and so
have no predicate. Each successful evaluation returns its annotations:
the properties `properties`, `patternProperties` and
`additionalProperties` evaluated, the item indices `prefixItems` and
`contains` evaluated, or every item (`items`). In-place applicators merge
their passing subschemas' annotations (`allOf`; every passing branch of
`anyOf`, not only the first; `oneOf`'s one; a passing `if` with `then`, or
`else`; `dependentSchemas`; `$ref`), a failed subschema and `not`
contribute none, and the `unevaluated*` keywords are evaluated after
their siblings. Annotations are collected only below a schema that has
one of the two keywords, so elsewhere `anyOf` still stops at its first
match. A schema with them imports with the location `dyn` and the
interpreter as its check (`Checked`), and the codec reads it exactly.

The interpreter and the compiled predicate must agree
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
    it reads (2020-12: 1,237; all drafts: 4,787; none disagree);
  - for every test whose schema imports, the types file checks and the
    codec reads the instance exactly when the suite calls it valid, but
    for the documented refusals above (2020-12: 1,209 agree and 14 are
    documented refusals; all drafts: 4,661 and 70; none disagree).
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
  990 schemas, 919 import and their types check; 71 are refused, by kind
  (29 a remote reference to a document not in the set, 29 a lookaround
  pattern, the rest a handful each). Of the
  instances, 1,893 agree with `jsonschema`, 220 valid ones are documented
  refusals, 37 `jsonschema` itself cannot validate, and none disagree.
  The listing is checked in, as for the suite.
- **Golden imports** of the corpus schemas (types, report, name map).
- **Mutants** of the predicate compiler, the importer and the codec: of
  27 tried, 25 are caught by the properties, the examples or the suite;
  one is equivalent (it skipped a residue the corpus types do not have)
  and one did not compile. The tests found a dozen importer and codec
  bugs before they were committed, each now covered.

## The OpenAPI importer

An OpenAPI document describes a service the agent calls. The importer
writes, for one document:

- a **types file**: the JSON Schema importer's types for every schema the
  operations use (`components.schemas` and inline ones), and per
  operation a request record and a response variant;
- an **effect per operation**, `effect <service>.<op>(<Op>Request) :
  <Op>Response;`, with a check cell for each as for any imported type;
- the **operation map**, beside the name map: per effect, the method, the
  path template, where each request field goes (path, query, header,
  cookie, body) and how it is serialised, the media type, the status code
  of each response case, the credential's scheme name, the retry class,
  and the servers;
- the **report**, as for JSON Schema, plus every operation skipped and why.

The outbox's `http` executor reads the operation map: it renders an intent
into a request, sends it only to a listed server, adds the credential it
holds for the scheme, and turns the response into the response variant.
The graph never sees a credential or a URL.

### Requests and responses

| OpenAPI | Graph language | Notes |
| --- | --- | --- |
| path, query, header and cookie parameters | fields of `<Op>Request`, named by the JSON Schema importer's rules; optional unless `required` (path parameters always are) | the name map records each field's location and wire name |
| `requestBody` with `application/json` | a field `body` of the schema's type (optional unless `required`) | |
| `requestBody` with `text/plain` or `application/octet-stream` | `body : string` or `body : bytes` | |
| any other media type | the operation is skipped, reported | form and multipart encodings come later |
| parameter `style`/`explode` | the defaults (`simple` for path and header, `form` with explode for query) and `deepObject` for query objects | any other style skips the operation, reported |
| responses | `<Op>Response`, a variant with a case per documented status (`S200(T)`, `S404(T)`; a case carries the JSON body's type, or nothing), and `Other(HttpOther)` for `default` and any status not documented: `{status : int; body : dyn}` | a status range (`2XX`) is a case `S2XX` |
| a header parameter named in `securitySchemes`, `Authorization`, or a cookie the scheme uses | not a field: the executor adds it | reported, so it is visible that the graph cannot set it |
| `servers` | the operation map's allowlist, with server variables at their defaults | variables are reported; the allowlist is what the gateway and `pf` rules are generated from |
| `operationId` | the effect's name (`<service>.<camelCase id>`) | without one, method and path (`get_pets_by_id`), reported |
| callbacks, links, webhooks | not imported | reported |

An HTTP error status is a **definite answer**: a `404` is a receipt whose
value is the `S404` case, and the cell decides what it means. Only a
failure to get a response at all (no connection, a timeout, a reset after
the request may have gone) is a lost receipt.

### OpenAPI 3.0 and 3.1

3.1's schemas are JSON Schema 2020-12 and go to the JSON Schema importer
as they are. 3.0's Schema Object is a dialect, and most published
documents are 3.0, so the importer rewrites a 3.0 schema to 2020-12 first,
the well-known way: `nullable: true` adds `null` to the type,
`exclusiveMinimum: true` with `minimum` becomes a numeric
`exclusiveMinimum` (likewise the maximum), `example` is dropped, and
`x-` extensions are kept as annotations. Swagger 2.0 is refused (convert
it with the usual tools first).

### Retry classes

The engine has two classes (RFC 0006): an **observational** intent is
sent again after a lost receipt, as a new attempt; an **effectful** one
goes Uncertain until a reconcile asks the world whether it took effect.
HTTP adds a third case, which the method and the document decide:

| Operation | Class | After a lost receipt |
| --- | --- | --- |
| `GET`, `HEAD`, `OPTIONS` | observational | a new attempt |
| `PUT`, `DELETE` | **keyed** (idempotent by method) | sent again, the same request |
| `POST`, `PATCH` with an `Idempotency-Key` header parameter (or the policy's name for one) | **keyed** | sent again with the same key, which the executor sets to the intent's key |
| any other `POST` or `PATCH` | effectful | Uncertain; reconciled by a read the policy names (an operation and the field to match), else it waits for a person |

A keyed intent is sent again with the same identity (an idempotency key
digesting the outbox's agent, the intent's key and its request, so no two
instances or requests share one; a new attempt number only for the
journal), and the server applies it at
most once, so at-most-once rests on the server keeping its promise. Each
send reserves its worst case, as an observational attempt does; a resend
the server deduplicates is charged nothing, and an abandoned send that
never answers is charged its worst case, so spend is never under-counted.
When the sends run out the intent is Uncertain, not failed, since it may
have taken effect, and it is reconciled by key. Policy grants the keyed
class per operation (a cell cannot claim it, as with the observational
class), because a server that ignores the key turns a resend into a
second effect. `observe.qnt` has the class: witnesses `keyedResent`,
`keyedDeduplicated` and `keyedUncertainW`, and the mutant `keyIgnored`
(the server does not deduplicate), which breaks `effectOnce`. The model
is checked with Apalache to depth 8 (about 40 s).

### Testing

- **Corpus**: the APIs.guru OpenAPI directory (several thousand public
  documents, mostly 3.0): the share imported, the share of operations
  skipped by reason, and every types file passing the checker.
- **Agreement with an OpenAPI implementation (Hegel)**: for each imported
  operation, request values generated from its request type, rendered by
  the executor's encoder, are accepted by `openapi-core`'s request
  validator against the document; response values generated from each
  case, rendered as an HTTP response, are accepted by its response
  validator and read back by the executor to the same value.
- **End to end**: a stub server for a small document, a graph calling it
  through `cordis-outbox` with crashes, as `e2e.sh`; a keyed operation
  against a server that deduplicates (one effect however often resent) and
  one that does not (the planted fault the model predicts).

### Progress

`cordis/import/lib/openapi.ml` is the importer and `openapi_http.ml` the
executor's pure half (rendering a request, reading a response);
`cimport openapi DOC` writes the types, the report, the name map and
`operations.json`. Choices the implementation settled:

- **Only what the operations reach.** The types file holds the component
  schemas the operations reach through references, not every one.
- **A refusal skips an operation, not the document.** When the JSON
  Schema importer refuses a schema (a pattern it cannot translate, say),
  the operations are halved until each refusal is pinned on the
  operations whose schemas reach it; those are skipped, reported, and the
  rest imported.
- **Check cells for requests only.** A `check_T` cell writes out every
  type it reaches, which for a large document is most of the document
  again per type; requests are what a cell builds and may check before
  sending, while a response is checked by the codec as the executor reads
  it. (For AWS Glue the graph checker went from 196 s to under a second,
  with this and with the checker no longer rebuilding its environment of
  named types for every expression.)
- **A response's unknown properties are dropped** (`Jsonschema_codec.read
  ~drop_unknown`), since servers add fields; a request is as strict as
  ever.
- **An empty list or object parameter is left out** (HTTP has no form for
  it that reads back as empty), and a required one is refused as not
  sendable, as is an empty path parameter.
- **A typed default response**: `Other` carries `{status; body : T}` when
  `default` has a JSON body, else `HttpOther`.
- **A line of nothing but a tab** is blanked and the YAML read again
  (libyaml refuses it; other readers take it as blank).

**The corpus** (`import/test/fetch_openapi_corpus.sh`, every sixth
OpenAPI 3 document of the APIs.guru directory at a pinned commit, the
listing checked in): all 329 documents (310 3.0, 19 3.1) import, their
types checking; of 13,747 operations, 13,330 are imported and 417
skipped: 357 for patterns the JSON Schema importer refuses (276 Unicode
property classes, 81 lookaround), 56 for a request body in a media type
not read (XML, octet streams, `*/*`), and 4 for parameter shapes. Before
the pattern translation and the form and multipart bodies, 686 were
skipped: 243 for request bodies, 155 for patterns that did not compile
(mostly `\uXXXX`, `\xHH` and `\_` escapes, and repetitions over RE2's
1000), 7 for non-ASCII patterns.

**Agreement with openapi-core** (`import/test/openapi_agreement.ml`,
Hegel, over the OAI petstore example and a document of every style the
importer reads): requests drawn from each operation's type are valid for
openapi-core exactly when the codec accepts them, and responses drawn
from each case are valid exactly when the codec accepts the payload, and
decode back to the same value. A status the document does not describe
at all is Other: openapi-core calls it invalid, the RFC a definite
answer. Planted encoder faults it catches: `deepObject` keys written
`name.k`, the idempotency header not set, list headers joined by `;`.

**The runtime.** The engine has the keyed class: a lost receipt sends
the same key again under a new attempt, and when the attempts run out
the intent is Uncertain and reconciled by key (the review loop's
properties run with `repo.read` keyed too, and an example loses every
send until a reconcile; a planted fault that fails the intent instead is
caught). The outbox journals a keyed intent per send and answers a
reconcile from whichever send is done. `cordis-outbox --api DOC` runs a
document's operations with the `http` executor: it renders each intent,
adds the credential for the operation's scheme from
`CORDIS_CREDENTIAL_<SCHEME>` (never from the graph), sends to the
document's server or `--api-base`, and reads any status as the response
variant; no response is a lost receipt. A keyed or observational
operation reconciles by sending again; an effectful one cannot be asked
without a read the policy names, so the outbox leaves it Uncertain.
`cordis-run --operations operations.json` takes each kind's class from
the import. `outbox/test/openapi.sh` runs a graph over an imported Notes
API against a stand-in server that requires the token and drops the
first create's answer after applying it: the create is sent twice and
applied once, with the runner crashed part way, and the graph reads the
note back; against a server that ignores the key the same run applies it
twice, as `keyIgnored` predicts.

**Form and multipart bodies.** A request body is read in the first of
JSON, `application/x-www-form-urlencoded`, `multipart/form-data` and
`text/*` the document offers. A form's or multipart body's schema must
be an object (a body that is not one is skipped, naming it), and its
properties are written as its Encoding Object says: a form field per
property by its style (form and exploded by default, so a list repeats
its key and an object spreads its keys; `deepObject` writes `k[p]=v`; a
JSON `contentType` writes the value as JSON), and a multipart part per
property, a list a part per item, `text/plain` for a primitive,
`application/octet-stream` for a binary string (or its
`contentMediaType`), `application/json` for anything else, or the
Encoding Object's `contentType`. The boundary is a digest of the parts,
so the same body is the same bytes, and occurs in none of them. An empty
list or object has no field or part of its own (it would read back as
absent), so it is not sendable. `openapi/bodies.yaml` joins the
agreement documents: a form and a multipart body offered both ways, a
form with `deepObject` and unexploded encodings, a multipart body with a
JSON part, and a form body that is not an object. The agreement test
also checks that openapi-core reads a form or multipart body back as the
one written (a field written in the wrong style reads back absent, which
validity alone does not see; a planted style fault is caught). An empty
list or object property that is not required is left out, as an empty
parameter is. openapi-core reads a multipart `text/plain` part as a
string whatever its schema (OpenAPI reads it by the schema), so the
agreement document gives multipart bodies no number or boolean property.

Still to come: the egress allowlist generated from `servers` (the
executor sends only to the base it is given).

## Importers to come

| Source | Maps to | Lossy where |
| --- | --- | --- |
| JSON Schema (2020-12) | specified above | |
| OpenAPI 3.0 and 3.1 | specified above | |
| AsyncAPI 3 | channels and send or receive operations to the graph's inputs and effects | bindings beyond the message payload are reported |
| XSD, CSV (with Table Schema), Arrow (for Parquet and Vortex) | see [RFC 0005](0005-xml-csv-frames.md) (proposed) | |


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
