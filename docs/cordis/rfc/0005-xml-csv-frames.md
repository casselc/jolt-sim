# RFC 0005: XML and XSD, CSV, and typed data frames

Status: proposed. Revision 2 settles the frames design (below) and its
model. Revision 3 (proposed) settles CSV's first stage, typed by a
Table Schema, and records it as built ("CSV", "Revision 3"). Revision 4
(proposed) does the same for XSD and XML without recursion ("XML and
XSD", "Revision 4"). Records the design so that later work
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
| a recursive reference | the named type (stage 3, built): a complex type referred to inside itself; one required all the way round has no finite document and is refused | report |
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

### Revision 4: the second stage, as built

**An XSD is imported through the JSON Schema importer**, as a Table
Schema is (`import/lib/xsd.ml`): the document element's type, and every
named type it reaches, become `$defs` of a synthetic JSON Schema, so the
types file, residue, report, name map and strict codec are that
importer's. Beside it the import builds the *shape* the XML codec goes
by: which properties are attributes (with their defaults), the elements
in order with their namespace and occurrence, the choices, and each
simple value's lexical kind. As built, against the table above:

- a choice is a variant, one case per element, each case's payload
  `{value}` (in JSON, `{kind: element, value}`);
- a particle that may repeat is a `list`, always present: no elements
  is the empty list (an `option(list)` would have two values for it);
- an attribute with a default or a fixed value is always present once
  read (the default filled), so its field is not an `option`;
- `string`, `normalizedString`, `token` and `anyURI` are strings, read
  with their whitespace preserved, replaced or collapsed as XSD says;
  a value that would not survive that (a tab in a `normalizedString`)
  is refused by a pattern in the residue;
- the integer types are `int` with their widths as bounds, `float` and
  `double` are `double`, and `xs:decimal` is a `double`, reported as
  lossy (open question 1 stays open; this is its second choice);
- a pattern is XSD's own dialect, matched whole: it is rewritten as an
  ECMA-262 one (`^` and `$` are characters, `.` excludes only CR and LF,
  `\d` is any decimal digit, `\w` anything but punctuation, separators
  and others, `\s` XML's four white-space characters) and translated to
  RE2 as JSON Schema's patterns are.

Refused, naming what: recursion (stage 3), `include`, `import` and
`redefine`, groups and attribute groups, `xs:any` and `anyAttribute`,
mixed content, `nillable`, unions, element defaults, substitution groups
and abstract types, qualified attributes, the calendar types (open
question 2), the binary and name-like types, and patterns using XSD's
name classes (`\i`, `\c`), block escapes or class subtraction. `key`,
`keyref` and `unique` are reported, not checked.

**The parser is the harness's own** (`import/lib/xml_tree.ml`), not
Xmlm: Xmlm collapses every attribute value, which XML asks only of
non-CDATA attributes, so a string attribute could not hold two spaces
or a tab and a document would be read as another. It reads XML 1.0 with
namespaces strictly: no DTD (refused), only the five predefined
entities and character references, line ends and attribute values
normalised as the spec says (a reference stays what it names), every
character UTF-8 and allowed by XML 1.0, duplicate attributes refused,
and the size, depth and attribute count limited by policy.

**The codec** (`import/lib/xml_codec.ml`) reads by the shape: the
document element must be the schema's; an attribute or element the type
does not name, a sequence out of order, an element occurring more or
fewer times than it may, and text where only elements may be are
refused; values are read in their lexical space; then the row codec
reads the whole with its residue. Writing is canonical: the declaration,
the target namespace declared once at the root (the default namespace
when every element is qualified, else a `tns` prefix for the qualified
ones), elements in sequence order, attributes sorted, no indentation, a
CR (and in attributes a tab or LF) as a character reference, the
shortest decimal that reads back (without an exponent for
`xs:decimal`). A value XML 1.0 cannot hold is refused.

`cimport xsd SCHEMA.xsd [--root ELEMENT]` writes the types, the report
and a name map holding the shape.

**Testing.** `import/test/xml_test.ml`: documents read and refused for
each rule (order, cardinality, unknown elements and attributes, text,
namespaces, a DTD, an unknown entity, residue), canonical writing,
import refusals, XSD pattern cases, and a Hegel property that values
drawn from a schema of every structural feature, written, read back as
themselves when they satisfy their schema (a value outside it may read
back normalised, as XSD reads it). `xml_agreement.ml` against xmlschema
and lxml (`xmlvalid.py`, `dune build @import/test/xml-agreement`): the
document the codec writes is valid for xmlschema exactly when the codec
reads it, libxml2 agrees, and xmlschema decodes a valid one to the value
the codec read (compared in xmlschema's form: `@` attributes, `$` simple
content, a list for what may repeat, a repeating choice grouped by
element, an empty string or list as its null); 2,758 documents in a
3,000-case run, 593 valid. A reader that does not replace a
`normalizedString`'s whitespace is caught.

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

### Revision 3: the first stage, as built

**A Table Schema is imported through the JSON Schema importer.** Its
fields become the properties of a JSON Schema for the row, so the types
file, residue, report, name map and strict codec are that importer's, and
the agreement test reuses its value generators
(`import/lib/table_schema.ml`):

| Table Schema | Row schema |
| --- | --- |
| `string` (format `default`); `uuid` | `string`; `uuid` as an anchored pattern |
| `integer`, `number`, `boolean` | `integer`, `number`, `boolean`; `trueValues` and `falseValues` in the name map |
| `any` | `string`: a cell is text |
| `required` | required (a primary key's fields are too) |
| `enum`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `minLength`, `maxLength` | the same keywords |
| `pattern` | anchored, `^(?:p)$`: Table Schema's patterns match the whole cell |
| `missingValues` | a missing cell is an absent property, so a field not required is `option(T)`; a string is never `""` when `""` is a missing value (`minLength: 1`), and another missing value (`NA`) is refused when written, as reported |
| `unique`, `primaryKey` | checked across the table when it is read, and reported |

Refused, naming the field: the calendar types (open question 2), the
nested ones (`object`, `array`, `geopoint`, `geojson`), string formats
other than `default` and `uuid`, and a `decimalChar`, `groupChar` or
`bareNumber` other than the default. `foreignKeys` are reported, not
checked. The dialect reads `delimiter`, `quoteChar` and `header`, and
refuses an escape character, comment lines and `skipInitialSpace`.

**The codec** (`import/lib/csv_codec.ml`) reads RFC 4180 strictly: a field
is quoted from its first character or not at all, and a quote inside an
unquoted field, text after a closing quote, a bare CR and a blank line
are refused. Cells are read by their column's kind (an integer as
`[+-]digits`, a number as a finite decimal), the row by the JSON Schema
codec with its residue, then the header, every row's column count, and
unique and primary keys across the table; a refusal names the row (the
header is row 1) and the column. Writing quotes only where it must,
writes CRLF, the shortest decimal that reads back as the same double,
and a field's first true or false value.

`cimport tableschema SCHEMA.json [--name ROW] [--dialect DIALECT.json]`
writes the types file, the report and a name map that adds the dialect,
the columns and the missing values.

**Testing.** `import/test/csv_test.ml`: text cases, table cases with each
refusal, import refusals, and two Hegel properties: any grid of awkward
fields printed reads back as itself (a printer that does not double
quotes is caught), and rows drawn from the row type, written, read back as
themselves (a writer that ignores a field's own true values is caught).
`csv_agreement.ml` against frictionless 5 (`csvvalid.py`, `dune build
@import/test/csv-agreement`): for rows drawn from three Table Schemas, the
table the codec writes is valid for frictionless exactly when the codec
reads it, and frictionless reads the same rows; 1,710 tables in a
3,000-case run, 1,023 of them valid. A codec that does not check primary
keys is caught, shrunk to two rows sharing one.

**A row type the graph declares** (`Table_schema.of_record`, `cimport
csvrow TYPES.cg ROW`): a closed record whose fields are `string`, `int`,
`double` or `bool`, or options of them, is written as a Table Schema (a
field required unless it is an option, a string's `<max N>` its
`maxLength`) and imported as one, so the row type is the declared record
again; `uint`, `bytes`, `timestamp`, `duration` and `dyn` columns are
refused for now.

**Inference from a sample** (`Csv_codec.infer`, `cimport csvinfer
SAMPLE.csv`), a scaffold only: a Table Schema for review, each column the
narrowest kind every present cell fits (integer, number, boolean, else
string) and required when no cell is missing, with what was seen on
standard error. It never runs at run time.

This completes stage 1 of the build stages below.

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

Revision 2 of this section takes in a survey of how batch and streaming
systems delimit, commit, upgrade and end their batches (Rama, Flink,
Beam/Dataflow, Kafka Streams, Timely/Differential, Materialize, Feldera,
Temporal, Spark), kept at `docs/cordis/research/STREAMING-PRIOR-ART.md`.
Each rule below names the system it follows. The model,
`cordis/quint/frames.qnt`, checks them, and is in the model gate.

**What a frame means.** It means the rows it holds, in order.
- CEL evaluates row by row.
- Columnar execution, such as compiling a subset of CEL to Arrow compute
  kernels, is a later optimisation. It must give the same answers.

**What a frame input is.** It is not a `list(T)`. It is a stream of
**batches**, each a `list(T)` of at most `B` rows, and a cell that
consumes a frame runs one turn per batch, its state carrying the
accumulator between them. The rules:

1. **A batch is an interval of the source, logged before it runs.** Each
   batch is identified by its half-open interval `[lo, hi)` of row
   positions (a row group's index and offset, for Parquet), and the
   interval is durable before the batch's turn reads it. Replay after a
   crash then re-reads the same batches. (Rama fixes a microbatch's offset
   range at its first read; Spark writes `offsets/<id>` before running;
   Materialize's remap binds offsets durably.)
2. **A turn commits one atomic record**: the accumulator, the cursor `hi`,
   the revision that computed it, and the turn's intents. A turn computed
   but not committed is lost to a crash and re-run from the last commit;
   cells are pure CEL, so re-running gives the same record. Exactly-once
   is "roll back to the last commit and replay" (Kafka Streams' commit of
   output, changelog and offsets in one transaction; Rama's microbatch
   commit). The model's `splitCommit` mutant writes the cursor after the
   state and double-counts a batch.
3. **Effects are observed once, not turns run once.** A turn may run twice
   before it commits. Each intent is keyed by `(stream, generation, lo,
   index)`, the batch form of RFC 0002's "an append is keyed by the
   scrutinee's revision"; the outbox delivers at least once and the
   receiver's receipts make each key observed once (Flink's two-phase
   sinks: staged intent, then receipt). The generation changes on a
   restart (rule 5), so a re-read frame's intents are new ones.
4. **`B` is a ceiling, and the runtime chooses each batch.** `B` is
   policy, not code, and each batch has `min(B, budget / row cost, the
   rest of the row group)` rows: a large row group is split, which rule 1
   makes safe. The cost estimate is taken over `B`, so it bounds every
   batch. A handler must therefore give the same answer for **any**
   partition of the input into batches (Beam's bundles, which users do not
   size): a Hegel property folds random batchings of generated frames and
   compares them with one batch.
5. **A revision cuts over at a commit, by one of three modes**, recorded
   in the commit record so a replay knows which revision computed which
   batch (Temporal's patch markers):
   - **carry**: the next turn runs on the new revision with the carried
     accumulator. Allowed only when the new revision reads the
     accumulator's type (RFC 0004's `stateReadable`); a turn computed but
     not committed by the old revision is dropped, never committed under
     the new (`midBatch` mutant). The result is the old revision's fold
     over `[0, k)` continued by the new one's over `[k, ...)`.
   - **drain**: the frame finishes on the old revision; the next frame
     runs on the new (Temporal's pinned workflows; Dataflow's drain).
   - **restart**: the accumulator is dropped and the frame re-read from 0
     on the new revision, as a new generation (Kafka Streams' new
     `application.id`). The default when the accumulator's type changed.

   This **amends RFC 0002**, which says the outcome of admission is that
   of a cold start of the new revision. That holds for every cell but a
   frame consumer under carry, whose outcome is the mixed fold above;
   RFC 0002 points here.
6. **State that is buffered is state.** Batches planned but not yet
   consumed are typed by `T`; a revision that changes a frame's `T` must
   migrate or refuse them, under the same `stateReadable` rule as cell
   state (Dataflow refuses an update that changes a buffered coder).
   Migration follows Rama's `PState.migrated`: named, idempotent, applied
   on read, its work charged to turns.
7. **The accumulator is bounded.** A static estimate over `B` rows holds
   only if a turn's work does not grow with the accumulator. The
   accumulator's type must carry size bounds (`<max n>`), or the cost
   estimator charges a per-turn state term; scanning an unbounded
   accumulator is O(state), not O(B) (the differential/DBSP lesson).
8. **A full buffer stops the reader; it does not refuse rows.** A
   source-fed frame applies backpressure by not advancing the reader's
   position (Kafka's pull, Flink's credits), so no row is lost
   (`refuseAdvances` mutant). RFC 0002's refuse-and-report stays for route
   appends on feedback loops, where refusal is the meaning; a refusal is
   always a definite "not appended" (Rama's acknowledged append that
   throws but was written is the ambiguity to avoid).
9. **The end of input is a committed turn.** When the last batch has
   committed, the input is closed (Beam's watermark at +∞; Materialize's
   `upper = ∞`) and an end turn runs exactly once, flushing the
   accumulator, as its own commit with its own intents and its own cost
   estimate (`on_end`). An end turn that runs before the last commit
   (`endEarly` mutant) misses rows; one not committed loses or duplicates
   its output (Flink's FLIP-147).
10. **A new input abandons the frame.** RFC 0002 empties a graph's streams
    when an input changes; a frame half consumed at that moment is
    abandoned and its accumulator discarded (`keepAcc` mutant), and the
    next frame runs on any revision admitted by drain.
11. **Combiners, for parallelism.** A cell may declare its accumulator a
    combiner: `zero`, and an associative `combine(a, b)` over the state
    type. Only then may the runtime split a frame over several fibers and
    merge their partials (Rama's two-phase aggregation, which applies only
    when every aggregator is a combiner; Beam's `mergeAccumulators`).
    Other cells consume sequentially. Hegel checks `combine`'s
    associativity and `fold(xs ++ ys) == combine(fold(xs), fold(ys))`.
12. **One frame input per cell**, for now. A cell with two would need a
    cursor per input in its commit record, or aligned markers (Flink's
    barriers); that waits for a use.

**What a frame output is.** It is a stream of batches written to a file
(Parquet or Vortex) or to Arrow IPC. Writing settings are policy, not
code: row group size, compression, encodings. A written row group is an
intent, so it is committed and observed once by rules 2 and 3.

**The model.** `frames.qnt` is one frame of four rows, a ceiling of two
rows a batch, one planned batch buffered at most, three revisions (one
with a new meaning and one with a new accumulator type), crashes, at-least-
once delivery and a second input. It checks:

| Property | Rule |
| --- | --- |
| `accConsistent`: the accumulator is the fold of the committed batches, each under its recorded revision | 2, 5, 10 |
| `contiguous`, `cursorMatches`: the committed batches partition `[0, cursor)` | 1, 2 |
| `revsInOrder`: revisions change only between commits | 5 |
| `accTyped`: the accumulator is of the type the live revision reads | 5 |
| `effectsOnce`: each intent is observed once | 3 |
| `endAfterAll`: the end turn follows the last commit | 9 |
| `noRowLost`, `bufferBounded` | 8 |

Witnesses show a frame ending, a carried cutover, a drain, a restart, a
redelivery, a full buffer and an abandoned frame; seven mutants, each
named above, are each caught by the property beside it. Apalache checks
the design to depth 8.

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
4. ~~**The batch size `B`**~~: answered in revision 2: a policy ceiling,
   with the runtime choosing each batch below it ("Frames", rule 4).
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
   **Recursion built** (the node type waits): a complex type referred to
   inside itself is a `Ref` in the codec's shape, resolved through the
   import's named complex types, and a `$ref` in the JSON Schema, which
   the JSON Schema importer closes as a recursive type wherever its cycle
   is guarded (an optional or repeated element, or an optional choice
   around a required one); a recursive element required all the way
   round is refused (no document is finite), as is a type extending one
   being defined. Tests: `xsd/tree.xsd` (a node in itself optionally,
   repeated and through a choice; mutually through `Branch`, whose `left`
   is required), read cases ten levels deep and refusals four levels down
   (a label's length, a weight's range), the types checked by the graph
   checker, a round trip property (500 documents, 118 valid read back
   alike), and the xmlschema/lxml agreement with `tree.xsd` among its
   schemas.
4. The Arrow import and the batch semantics in the turn runtime, under
   `frames.qnt` (written: revision 2), with the batching-invariance and
   combiner properties in Hegel.
5. The Parquet and Vortex codecs.
