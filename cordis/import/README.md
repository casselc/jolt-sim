# cordis/import: schema importers

Importers from existing contracts to graph-language types, as specified in
[RFC 0003](../../docs/cordis/rfc/0003-types-importers-scaffolds.md)
(protobuf, JSON Schema, OpenAPI; AsyncAPI follows) and
[RFC 0005](../../docs/cordis/rfc/0005-xml-csv-frames.md) (CSV typed by a
Table Schema, XSD and XML; Arrow follows). Every translating importer goes
through the JSON Schema importer, so each has a types file, residue
(`check_T` cells), a lossiness report, a name map and a strict codec.

**For protobuf this is a report tool.** Graphs use protobuf types
directly ([RFC 0004](../../docs/cordis/rfc/0004-descriptor-native-types.md)):
a graph attaches a descriptor set with `uses descriptors sha256:<hex>;`
(`cimport protobuf SET --print attach` prints the line), its cells take
messages and enums by full name, and values cross the boundary through
`../cel/lib/proto_codec.ml`. What `cimport protobuf` writes shows what the
schema would lose as graph-language records (the report), and it is the
pattern the translating importers follow for formats with no native form
in CEL.

| Path | What it is |
| --- | --- |
| `lib/descriptor.ml` | A `FileDescriptorSet`, decoded from protobuf's wire format (the parts of descriptor.proto the importer reads) |
| `lib/protobuf.ml` | The protobuf importer: names, the type mapping, presence, oneofs, enums, well-known types, recursion cuts, residue, the report and the name map |
| `lib/codec.ml` | The translated types' codec: protobuf JSON to and from their record values (graphs on native types use `../cel/lib/proto_codec.ml`) |
| `bin/cimport.ml` | `cimport protobuf SET.binpb [--out DIR \| --print types\|report\|names\|attach]` |
| `test/protos/` | The corpus (`shop.proto`, proto3; `legacy.proto`, proto2) and its descriptor set; `build.sh` rebuilds it with `grpc_tools.protoc` |
| `test/golden/` | The corpus's import, checked in: types, report, name map |
| `test/codec_test.ml` | The codec by example |
| `test/agreement.ml`, `test/pbcheck.py` | Hegel: protobuf's own JSON parser and the residue agree, and values round-trip through protobuf |

| `lib/jsonschema.ml`, `schema_pred.ml`, `schema_eval.ml`, `jsonschema_codec.ml` | JSON Schema drafts 4 to 2020-12: types, the compiled CEL predicate (patterns translated from ECMA-262 to RE2), an interpreter, and the strict codec |
| `lib/openapi.ml`, `openapi_http.ml` | OpenAPI 3.0 and 3.1: an effect per operation, request records and response variants, parameters by style, JSON, form, multipart and text bodies, the egress allowlist; the wire encoding |
| `lib/table_schema.ml`, `csv_codec.ml` | CSV typed by a Frictionless Table Schema, or by a record the graph declares; RFC 4180 read strictly, written canonically; inference from a sample as a scaffold |
| `lib/xsd.ml`, `xml_tree.ml`, `xml_codec.ml` | XSD without recursion, through the JSON Schema importer with a shape for the codec; a strict XML 1.0 parser (no DTD); a strict, canonical codec |
| `test/schema_suite.ml`, `schemastore.ml`, `openapi_corpus.ml` | The JSON Schema Test Suite (and its ECMAScript-regex files), SchemaStore against jsonschema, and an APIs.guru corpus, each a golden listing |
| `test/openapi_agreement.ml`, `csv_agreement.ml`, `xml_agreement.ml` | Hegel against openapi-core, frictionless, and xmlschema with lxml |
| `test/pattern_test.ml`, `egress_test.ml`, `csv_test.ml`, `xml_test.ml` | Pattern translation, the allowlist, and the CSV and XML codecs by example and by property |

```sh
dune exec import/bin/cimport.exe -- protobuf import/test/protos/corpus.binpb
dune test                                  # golden files and codec examples
dune build @import/test/agreement          # needs python3 with protobuf
dune build @import/test/openapi-agreement  # needs python3 with openapi-core
dune build @import/test/csv-agreement      # needs python3 with frictionless
dune build @import/test/xml-agreement      # needs python3 with xmlschema and lxml
SCHEMASTORE=DIR dune build @import/test/schemastore
OPENAPI_CORPUS=DIR dune build @import/test/openapi_corpus
```
