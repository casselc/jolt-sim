# RFC 0003: The type IR, importers and scaffolds

Status: revision 2. The protobuf importer is specified here for
implementation (phase 2, build order item 6); the JSON Schema, OpenAPI and
AsyncAPI importers and the scaffolders remain as designed in revision 1.
Design context: [DESIGN.md](../DESIGN.md) "Schemas" and "Scaffolding".

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
   must pass `cgraph check` on its own.
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
| a field whose type leads back to a message being defined | `dyn` | report: recursion is not representable yet |

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

## Importers to come

| Source | Maps to | Lossy where |
| --- | --- | --- |
| JSON Schema (2020-12) | objects to records (closed at trust boundaries), `enum` to variants, `$ref` to a named type | `allOf` (merged, residue for conflicts), `not`, `if`/`then`/`else`, undiscriminated `oneOf`/`anyOf` (looser type plus residue), `patternProperties`; recursion as for protobuf |
| OpenAPI 3.1 | schemas via the JSON Schema importer; each operation to an effect (an intent kind) | callbacks and links are reported, not imported |
| AsyncAPI 3 | channels and send or receive operations to the graph's inputs and effects | bindings beyond the message payload are reported |

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
- **Round trip** (later): descriptor to types to descriptor, where the
  format allows.
