# RFC 0005: XML and XSD, CSV, and typed data frames

Status: proposed; not scheduled. Records the design so that later work
starts from it. Extends RFC 0003 (translating importers) and follows the
pattern RFC 0004 set for protobuf. Each part needs a revision of this RFC,
accepted, before it is built, and the frames part needs a Quint model
first (see "Frames").

## Purpose

Cells and graphs should take their types from, and exchange values in,
three more families of formats:

- **XML**, typed by **XSD**: import a schema, and read and write documents.
- **CSV**, typed by a declared record type or a **Frictionless Table
  Schema**: read and write rows.
- **Typed data frames**: **Apache Arrow** as the type and in-memory model,
  **Parquet** and **Vortex** as file formats over it.

## Two routes, and which each format takes

The harness already has two ways to bring an external type system in:

| Route | When | What it gives | Example |
| --- | --- | --- | --- |
| **Native** (RFC 0004) | The format has a runtime value model that CEL already gives meaning to | Values keep the format's own semantics; nothing is translated, so nothing is approximated | protobuf |
| **Translating** (RFC 0003) | CEL has no native form for the format's values | A types file (graph-language types), residue (one `check_T` cell per type), a lossiness report, and a name map for the boundary codec | JSON Schema, OpenAPI, AsyncAPI |

None of the three families has a native form in CEL, so **all three are
translating**. Each consists of:

1. **a type source**, imported into a types file, residue, a report and a
   name map. For XSD this is the schema. For CSV it is a declared record
   type, or a Table Schema. For frames it is the Arrow schema, which
   Parquet and Vortex files carry or map to.
2. **a boundary codec**, from the name map. It reads strictly and writes
   canonically, as RFC 0004's codec does.

The work these share with what exists:

- The checker, the cost estimator and the generators read graph-language
  types.
- The test pattern carries over: golden imports, codec examples, an
  agreement property against the format's reference implementation, and
  mutants.

## XML and XSD

### Import: XSD to types

| XSD | Graph language | Residue or report |
| --- | --- | --- |
| `complexType` with `sequence` | a closed record; the name map records element order, and which fields are attributes | |
| `all` | a closed record; any order on reading | |
| `choice` | a variant, one case per particle | |
| `minOccurs="0" maxOccurs="1"` | `option(T)` | |
| `maxOccurs="n"` (finite) | `list(T) <max n>` | residue: at least `minOccurs`. The bound is the schema's own, so the cost estimate uses it, which protobuf could not give |
| `maxOccurs="unbounded"` | `list(T)` | the default size ceiling, reported once |
| attributes | record fields (`use="optional"` gives `option(T)`); `default` and `fixed` go in the name map | residue: a `fixed` value |
| `simpleContent` extension | a record with `value` beside the attributes | |
| simple type restricted by `enumeration` | a variant of unit cases | |
| facets: `pattern`, `length`, `minLength`, `maxLength`, `min/maxInclusive`, `min/maxExclusive`, `totalDigits`, `fractionDigits` | the base type | residue (XSD's regex dialect translated to RE2, or reported where it has no translation) |
| `xs:int`, `xs:short`, `xs:byte`, `xs:unsigned*`, `xs:long` | `int` or `uint` | residue: the width |
| `xs:integer`, `xs:decimal` | open question (below) | |
| `xs:dateTime` with a zone; `xs:duration` | `timestamp`; `duration` where it has no months or years | report: a duration with months, and a `dateTime` without a zone, have no CEL form and become `string` plus residue |
| `xs:date`, `xs:time`, `gYear`… | `string` | residue: the lexical form |
| `xs:list` | `list(T)` | |
| `xs:union` | a variant over the member types | report: the discriminator is the lexical form, tried in order |
| derivation by extension | the derived record: the base fields plus its own | |
| `xsi:type`, substitution groups | a variant over the derived types | |
| `key`, `keyref`, `unique` | the types alone | residue: CEL over the lists the selectors reach |
| `xs:any`, `anyAttribute`, mixed content | `dyn`, or the generic node type once it exists | report |
| a recursive reference | `dyn` until RFC 0002 adds references, as for protobuf | report |
| `redefine`, `override`, assertions (1.1) | — | refused or reported |

**Namespaces.** A type is named by its target namespace's prefix and its
own name. The name map keeps the namespace URIs, and qualified and
unqualified forms follow `elementFormDefault`.

**Schemaless XML** has no type to translate. The proposal is a library
type for an XML node: element (name, namespace, attributes, children),
text, comment and processing instruction. It is recursive, so it waits for
RFC 0002's references. Until then a schemaless read gives `dyn`.

### The codec

- **Reading is strict.** Refused:
  - unknown elements and attributes;
  - sequence order broken;
  - cardinality broken;
  - a lexical form outside the type.
- **The parser accepts no DTD**, so there are no external entities and no
  entity expansion. This defends against XXE and billion-laughs attacks.
- **Document size, depth and attribute count are limited.** The limits are
  policy, not code.
- **Parser:** Xmlm (pure OCaml, no DTD processing beyond the predefined
  entities) fits.
- **Writing is canonical:**
  - elements in sequence order;
  - attributes sorted;
  - namespaces declared once at the root;
  - UTF-8.
- **XML canonicalization and signatures (C14N, XML-DSig) are out of
  scope.** Values the harness hashes are hashed in Preserves (DESIGN.md
  "Serialization"), whatever format they arrived in.

### Testing

The oracles are Python `xmlschema` and lxml/libxml2:

- A document the codec writes from a generated value is valid against
  the schema exactly when the residue accepts the value.
- What the oracle reads, the codec reads to the same value.
- Golden imports cover a corpus of schemas, including a slice of the W3C
  XSD test suite.

## CSV

CSV has no types, so the type always comes from outside, in one of three
ways:

1. **A graph-language record type** declared by the graph. Every column is
   a scalar field, or an `option` of one.
2. **A Frictionless Table Schema**, imported like JSON Schema:
   - field types become graph-language scalars;
   - `required`, `unique`, `minimum`/`maximum`, `pattern` and `enum`
     become residue, and `enum` becomes a variant;
   - `missingValues` go in the name map.
3. **Inference from a sample**, as a scaffold only. It writes a types file
   and a report for review. It never happens at run time.

**The dialect** goes in the name map: delimiter, quote, escape, header
row, null tokens and line terminator, as in Frictionless CSV Dialect or
CSVW. The encoding is UTF-8; anything else is refused unless the dialect
declares it.

**Nested fields** (a list or a record in a column) have no CSV form. The
checker refuses them unless the dialect names an encoding for the cell,
such as JSON-in-cell, which is reported.

**The codec** maps rows to records:
- Reading checks the header against the field names, the column count of
  every row, and each cell against its type. A refusal names the row and
  column.
- Writing follows RFC 4180: CRLF line endings, quoting only where needed.

**The oracles** are Python's `csv` module and `frictionless validate`:
- what the codec writes, they read to the same rows;
- a row is valid exactly when the residue accepts it.

## Typed data frames: Arrow, Parquet, Vortex

### Import: Arrow schema to types

| Arrow | Graph language | Residue or report |
| --- | --- | --- |
| `struct` | a closed record | |
| a nullable field | `option(T)` | |
| `list`, `large_list` | `list(T)` | |
| `fixed_size_list<n>` | `list(T) <max n>` | residue: exactly `n` |
| `map` | `map(K, V)` | |
| dense or sparse `union` | a variant | |
| `dictionary` | its value type (the encoding is not part of the type) | |
| `int8` … `int64`, `uint8` … `uint64` | `int`, `uint` | residue: the width, as for protobuf's 32-bit types |
| `float16`, `float32` | `double` | report: precision is widened |
| `utf8`, `large_utf8`; `binary`, `fixed_size_binary` | `string`; `bytes` | residue: a fixed size |
| `timestamp(unit, tz)` | `timestamp` | report: the unit; a naive timestamp (no zone) is reported |
| `date32/64`, `time32/64`, `interval` | open question (below) | |
| `decimal128/256` | open question (below) | |
| extension types | the storage type | report |

### Frames

**This is the only genuinely new mechanism in this RFC.** A frame is a
sequence of rows of a record type `T`, stored by column, and possibly far
larger than any single turn may cost.

- **What a frame means.** It means the rows it holds, in order.
  - CEL evaluates row by row.
  - Columnar execution, such as compiling a subset of CEL to Arrow compute
    kernels, is a later optimisation. It must give the same answers.
- **What a frame input is.** It is not a `list(T)`. It is a **stream of
  batches**, each a `list(T) <max B>`, plus an end marker.
  - A cell that consumes a frame runs one turn per batch.
  - The turn's cost estimate is taken over `B` rows.
  - Its state carries the accumulator between batches.
  - So the cost bound is per batch, and a frame's total is the number of
    batches times the estimate. This reuses RFC 0002's turns, state and
    streams with capacity.
- **What a frame output is.** It is a stream of batches written to a file
  (Parquet or Vortex) or to Arrow IPC.
  - Writing settings are policy, not code: row group size, compression,
    encodings.

**Model first.** Batching makes the runtime stateful in new ways, so
`quint/frames.qnt` is written before this part is built. It checks:

- every batch is processed exactly once;
- the end marker follows every batch;
- a revision cutover happens only between batches, and the accumulator
  crosses it under RFC 0004's `stateReadable` rule;
- a crash resumes from the last committed batch;
- a full stream refuses rather than drops.

It would have mutants and witnesses, as the other models do.

### Libraries

Data crosses into OCaml through the **Arrow C Data Interface**, a small
stable ABI, so the harness binds that and not a whole library.

- **Parquet:** arrow-rs, behind a small C shim, the pattern DESIGN.md uses
  for Cedar; or the existing OCaml bindings to Arrow C++ if their weight
  is acceptable.
- **Vortex:** Rust only, so it goes through the same shim.

### Testing

The oracles are pyarrow and Vortex's Python package. For frames generated
from the imported type:
- what the codec writes, they read to the same rows;
- what they write, the codec reads to the same rows.

## Open questions

1. **Decimals.** CEL has no decimal type, and `xs:decimal`, `xs:integer`
   and Arrow's decimals need one. Three choices:
   - `string` plus residue: exact, but no arithmetic;
   - `double`: lossy, so it would be reported;
   - a decimal extension to RFC 0001: exact, but it adds semantics to CEL,
     which DESIGN.md has so far avoided.
2. **Calendar types** without a CEL form (dates, times, intervals,
   durations with months): `string` plus residue, or a small library of
   functions over a record form.
3. **Schemaless XML:** a generic node type (which waits for references),
   or refuse.
4. **The batch size `B`:** fixed per graph, a policy number, or chosen per
   input from the file's row groups.
5. **Which first?** Each costs more than the one before: CSV (smallest),
   then XSD/XML, then frames. Frames also wait for the turn runtime
   (phase 3).

## Build stages (when scheduled)

1. CSV:
   - declared record types, then Table Schema;
   - the codec;
   - the agreement property.
2. XSD import and the XML codec, without recursion.
3. Recursion in XSD, and the XML node type, once RFC 0002 has references.
4. `frames.qnt`, then the Arrow import and the batch semantics in the turn
   runtime.
5. The Parquet and Vortex codecs.
