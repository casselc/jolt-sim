# RFC 0003: The type IR, importers and scaffolds

Status: accepted for design; implementation after RFC 0001 step 3 and RFC
0002's checker (phase 2, build order items 6 and 7). Design context:
[DESIGN.md](../DESIGN.md) "Schemas" and "Scaffolding".

## Purpose

Cells and graphs take their input and output types from existing contracts:
protobuf, JSON Schema, OpenAPI, AsyncAPI. One internal form holds all of
them, so the graph checker, the CEL checker, the Hegel generators and the
scaffolders read one thing.

## The IR

```ocaml
type ty =
  | Scalar of scalar * format list   (* int, uint, double, string, bytes, bool, timestamp, duration *)
  | Record of { fields : field list; rest : row }      (* row: Closed | Open of var *)
  | Variant of { cases : (string * ty) list; discriminator : string option }
  | List of ty * bounds | Map of scalar * ty * bounds
  | Optional of ty
  | Ref of ident                       (* a named type; recursion only through Ref *)
and field = { name : string; ty : ty; required : bool; doc : string option }
and format = Max_length of int | Min_length of int | Pattern of string | Uri | Email | Uuid | ...
and bounds = { min_items : int option; max_items : int option }
and ident = { doc : string; name : string; hash : string }   (* document, name, content hash *)
```

Every import produces three things:

1. **types** in this IR;
2. **CEL residue**: one predicate per constraint the types cannot express
   (a `oneOf` without a discriminator, `if`/`then`/`else`, `not`, a regex
   on a key), checked at the trust boundary;
3. **a lossiness report**: each construct that was approximated, with the
   source location, so nothing is lost silently.

**Identity** is (document, name, content hash). Two documents defining
`Order` never collide, and a changed definition is a different type, which
closes Cordis's key-collision gap (§6.6) for imported types.

**Size bounds flow to the cost estimator.** `maxLength`, `maxItems` and
`maxProperties` become `bounds`, which RFC 0001's estimator reads; a type
without them gets the configured ceiling, and the lossiness report says so.

## Importers

| Source | Maps to | Lossy where |
| --- | --- | --- |
| Protobuf (descriptor sets from `buf build`) | messages to closed records, `oneof` to variants with the field name as discriminator, enums to variants of unit cases, well-known types to scalars | `Any` (becomes `bytes` plus residue); `buf breaking` is the compatibility check |
| JSON Schema (2020-12) | objects to records (closed at trust boundaries), `enum` to variants, `$ref` to `Ref` | `allOf` (merged, residue for conflicts), `not`, `if`/`then`/`else`, undiscriminated `oneOf`/`anyOf` (looser type plus residue), `patternProperties` |
| OpenAPI 3.1 | schemas via the JSON Schema importer; each operation to an intent kind | callbacks and links are reported, not imported |
| AsyncAPI 3 | channels and send or receive operations to the graph's external ports | bindings beyond the message payload are reported |

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

- Importer golden files: source document to IR, residue and report.
- Generators: for every imported type, Hegel values generated from the IR
  must validate against the source schema (with an external validator for
  JSON Schema and protobuf's own parser), and the residue must accept them.
- Round trip where the format allows (protobuf descriptor to IR to
  descriptor).
