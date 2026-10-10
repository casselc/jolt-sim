# RFC 0004: Descriptor-native types

Status: accepted for implementation (phase 2, build order item 6). Stages
1 to 4 are built; stage 5 remains.
Amends RFC 0002 (types, `uses`, identity, admission) and RFC 0003 (the
protobuf importer). Model: `cordis/quint/revisions.qnt`.

## Decision

Graphs work from protobuf descriptors directly. A graph attaches a
`FileDescriptorSet` by hash, and its cells use the message types as they
are: values are protobuf messages under CEL's own proto semantics, which
RFC 0001's implementation already supports and passes cel-spec's
conformance suite for. Nothing is translated, so nothing is approximated:
presence, oneofs, enums, wrappers, well-known types and recursion all keep
protobuf's meaning, and a 32-bit field is out of range because CEL refuses
to build it, not because a residue says so.

RFC 0003's translating importer stays, as a report tool (`cimport`): it
shows what a schema would lose as graph-language records, and it is the
pattern for JSON Schema, OpenAPI and AsyncAPI, which have no native runtime
form. It is no longer the protobuf path.

## Attaching descriptors

```
uses descriptors sha256:<hex>;      // the SHA-256 of a FileDescriptorSet's bytes
```

The bytes come from the same content store as libraries, by hash; a set
that does not hash to its name is refused. A graph file may attach several
sets. The well-known types (`google.protobuf.*`) are always available.

## The registry

A revision's **registry** is built once, from all the sets it attaches,
and is never changed afterwards. A full name defined by two sets must be
defined identically (the same descriptor), or the revision is refused.

Every check, cost estimate and evaluation of a revision runs with that
revision's registry bound, so a candidate revision's descriptors are never
seen by the live one (`revisions.qnt`: `ownRegistry`; the `globalRegistry`
mutant, where loading a candidate's sets changes what the live revision
reads, breaks it). The registry today in RFC 0001's implementation is
global; it becomes a value, bound for the dynamic extent of each entry
point.

## Types and values

- **Names.** A graph type that names a message or enum uses its full
  protobuf name: `cell price(o : acme.shop.v1.Order) : int`. CEL bodies
  build messages with message literals by full name
  (`acme.shop.v1.Money{units: 3}`) and read fields by name; `has()` tests
  presence.
- **Messages are nominal**: a message type fits only itself (or `dyn`).
  Width subtyping applies to graph-language records, not to messages.
- **Enums** are `int`, as in CEL by default (cel-go's legacy mode); strong
  enums are a registry option.
- **Oneofs** are fields: at most one member is set, and `has()` tells
  which. A route matches graph-language variants only; routing on a oneof
  or an enum is deferred.
- **Well-known types** are what CEL makes of them: `Timestamp` is
  `timestamp`, a wrapper is nullable, `Struct` is `map(string, dyn)`.
- **Recursion** is native: a message may contain itself.

## Cost

A message's size shape comes from its descriptor, field by field: repeated
and map fields, strings and bytes are bounded by the configured default
ceiling (protobuf has no size bounds; `protovalidate` constraints can
supply them later), singular fields by their own types.

## The boundary codec

Values cross the boundary in protobuf binary or JSON, both ways, read and
written from the registry (`cordis/cel/lib/proto_codec.ml`). A message's
value is the one CEL gives it: its set fields, the well-known types in
their CEL forms.

**Reading is strict**, as a trust boundary needs. Refused: an unknown
field (by number or by name; extensions are not read), two members of one
oneof, an enum number or name the enum does not declare (also for proto3's
open enums, which protobuf would keep), a 32-bit value out of range
(including a binary varint beyond 32 bits, which protobuf would truncate),
a missing proto2 `required` field, a string that is not UTF-8, a timestamp
or duration out of range, a duration in any form but protobuf's
(`-1.5s`), a fraction of more than nine digits, a duplicate JSON key, a
`null` element, a float that rounds to infinity, and anything malformed.
Where protobuf has a rule rather than an error it is kept: a singular
field's last value wins and a message's occurrences merge; packed and
unpacked repeated scalars are both read; `null` for a field is absent,
except a `Value` (null) or a `NullValue`.

**Writing is canonical**: fields in number order, map entries in key
order (in binary and JSON); proto3 repeated scalars packed, proto2's not;
groups framed by start and end tags; JSON with proto field names, 64-bit
integers as strings, `NaN` and `Infinity` as strings, and every field with
no presence printed. An `Any`'s content is written under the type its CEL
value has (a message under its own; an `int` as an `Int64Value`, a `uint`
as a `UInt64Value`, a `double` as a `DoubleValue`, a map as a `Struct`, a
list as a `ListValue`, `null` as a `Value`), since CEL keeps no more than
that. A `Value` holding `NaN` or an infinity, and a `FieldMask` path with
no lowerCamel form, have no JSON and are refused when written.

**Presence of `-0.0`.** A proto3 field with no presence is set when it
differs from its default by bit pattern, as protobuf decides it; CEL's
numeric equality would call `-0.0` the default and drop it. RFC 0001's
implementation now follows protobuf here.

## Identity

A definition that names a protobuf type depends on the `uses descriptors`
items that define it, so its hash covers theirs (RFC 0002 "Identity"): a
changed descriptor set changes the hash of every cell and graph that uses
its types, which invalidates their memos. A dotted path in a CEL body counts as a name
unless it starts at a variable in scope (a parameter, `state`, a `let` or
`use`, a comprehension variable), so a cell that only selects fields of
its own values does not depend on the sets.

## Revisions

What `revisions.qnt` checked, before implementation:

- **Each revision reads its own registry** (`ownRegistry`), including
  while a candidate is pending (witness `evaluatedBesideCandidate`).
- **State crosses a revision only when the new type reads it**
  (`stateReadable`). A cell's state is a message written under some
  version of its type. When a candidate attaches a new version:
  - a **compatible** change (the new descriptor reads every message the
    old one wrote: fields only added; no field's number, type or
    cardinality changed) carries the state over (witness
    `carriedCompatible`);
  - a **breaking** change migrates it: today, by resetting the state to
    the new type's initial value; later, by a migration cell the patch
    supplies (witness `migratedBreaking`). Carrying it over unread
    (`carryAlways`) breaks the property.
- **A breaking change needs approval** (`breakingApproved`; the
  `noApproval` mutant breaks it), extending RFC 0002's compatibility rules.
- **Cutover retires before it inserts** (`singleWriter`): no fiber of the
  old revision writes the state once one of the new revision has started,
  as the kernel's O-Retire then O-Insert already does (`insertFirst`
  breaks it).

**Compatibility of descriptor changes**, as admission classifies them:

| Change | Classification |
| --- | --- |
| a field added (not `required`) | compatible |
| an enum value added | compatible |
| a proto2 `required` field relaxed | compatible |
| a field removed, or its number, type or cardinality changed | breaking |
| a field renamed, or its JSON name changed (its JSON form changes) | breaking |
| a field given or deprived of presence, moved into or out of a oneof, made `required`, or given a new default | breaking |
| a `required` field added | breaking |
| an enum value removed, renamed or renumbered | breaking |
| the file's syntax changed | breaking |
| any of these in a type a field holds, at any depth | as the change |
| a message or enum removed while a definition names it | refused by the checker |

The rule is conservative: compatible means the new descriptor reads
every message the old one wrote, under the codec's strict reading, in
binary and JSON, to the same value; some changes called breaking (`int32`
to `int64`) also read every old message, but none called compatible fails
to (`cel/lib/proto_compat.ml`).

**As built.** `Compat` compares the two revisions' registries over the
protobuf types each definition names (in its signature, its state, or its
body, as a message literal or an enum constant) and every type those
reach. A breaking change makes the definition need approval with the
change as the reason (`acme.shop.v1.Order: field note (6) removed`),
though its signature reads the same. Admission returns a **state plan**
with the new revision: per stateful cell, `Carry`, or `Migrate` with the
reasons; `cgraph admit` prints it. Migration is the reset to the initial
value until migration cells exist.

## Testing

- **Conformance**: cel-spec's suite still passes once the registry is a
  value.
- **Isolation**: two registries defining the same full name differently
  are checked and evaluated side by side, each seeing only its own (Hegel).
- **Checking**: corpus programs over message types, accepted and rejected.
- **Soundness**: for messages generated from the descriptors, every turn
  costs at most its estimate and results have their declared types (Hegel).
- **Agreement with protobuf**: generated messages (`cel/test/proto_gen.ml`,
  shared with the graph soundness property) written by the codec, in
  binary and JSON, read back to themselves; protobuf (Python, through
  `cel/test/pbwire.py`) reads both, strictly, to the same message; and
  what protobuf writes back, in binary and JSON, the codec reads to the
  message it started from (Hegel, over cel-spec's `TestAllTypes` in proto2
  and proto3 and the importer's corpus). Python's protobuf (7.36) cannot
  read the `null` it prints for a `NullValue` element of a repeated or map
  field, so the agreement property does not generate those; the codec's
  examples cover them.
- **The codec by example**: what protobuf's binary and JSON may say that
  the codec never writes, and each refusal above.
- **Compatibility**: examples of each row of the table above; and
  (Hegel) for any sequence of the changes, when nothing breaking is found,
  every message the old descriptors write (binary and JSON) the new ones
  read to itself (`cel/test/proto_compat_test.ml`).
- **Revisions** (Hegel, `graph/test/revisions_test.ml`): a patch swapping
  the attached set for a changed one, admitted as `revisions.qnt` says: a
  breaking change goes live only approved (its `noApproval` mutant, as a
  code mutant, fails it); state is carried only when old state reads under
  the new descriptors (`carryAlways`), and migrated only across a breaking
  change; the live registry is untouched; the hash of a cell naming the
  type changes and of one naming none does not. Examples over the shop
  corpus's real sets.
- **Model**: `revisions.qnt` in the model gate, with its mutants and
  witnesses.

## Build stages

1. The registry as a value, loaded from a binary `FileDescriptorSet`, bound
   per check and evaluation; conformance and isolation tests.
2. The graph language: `uses descriptors`, message and enum types in
   signatures, the checker and the cost shapes; corpus and soundness.
3. The codec (binary and JSON) and generators from descriptors; the
   agreement property.
4. Admission: descriptor compatibility, the state carry-or-migrate rule,
   and hashes covering the descriptor sets.
5. `cimport` repositioned as the report tool.
