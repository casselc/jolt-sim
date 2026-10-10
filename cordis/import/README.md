# cordis/import: schema importers

Importers from existing contracts to graph-language types, as specified in
[RFC 0003 revision 2](../../docs/cordis/rfc/0003-types-importers-scaffolds.md).
JSON Schema, OpenAPI and AsyncAPI follow; XSD, CSV and Arrow are proposed
in [RFC 0005](../../docs/cordis/rfc/0005-xml-csv-frames.md).

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

```sh
dune exec import/bin/cimport.exe -- protobuf import/test/protos/corpus.binpb
dune test                                  # golden files and codec examples
dune build @import/test/agreement          # needs python3 with protobuf
```
