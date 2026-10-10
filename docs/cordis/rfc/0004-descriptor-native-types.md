# RFC 0004: Descriptor-native types

Status: accepted for implementation (phase 2, build order item 6). Stages
1 and 2 are built.
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
written from the registry: strict reading (unknown fields, two members of
one oneof, unknown enum names and out-of-range 32-bit values are refused),
canonical writing (proto field names; `int64` as strings; all fields with
no presence printed).

## Identity

A definition that names a protobuf type depends on the `uses descriptors`
items that define it, so its hash covers theirs (RFC 0002 "Identity"): a
changed descriptor set changes the hash of every cell and graph that uses
its types, which invalidates their memos.

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
| a field added | compatible |
| an enum value added | compatible |
| a field removed, or its number, type or cardinality changed | breaking |
| a field renamed (its JSON form changes) | breaking |
| an enum value removed or renumbered | breaking |
| a message or enum removed while a definition names it | refused by the checker |

## Testing

- **Conformance**: cel-spec's suite still passes once the registry is a
  value.
- **Isolation**: two registries defining the same full name differently
  are checked and evaluated side by side, each seeing only its own (Hegel).
- **Checking**: corpus programs over message types, accepted and rejected.
- **Soundness**: for messages generated from the descriptors, every turn
  costs at most its estimate and results have their declared types (Hegel).
- **Agreement with protobuf**: generated messages written by the codec, in
  binary and JSON, are read by protobuf's own parser, and what it writes
  back reads to the same message (Hegel).
- **Compatibility**: examples of each row of the table above.
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
