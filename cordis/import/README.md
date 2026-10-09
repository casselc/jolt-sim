# cordis/import: schema importers

Importers from existing contracts to graph-language types, as specified in
[RFC 0003 revision 2](../../docs/cordis/rfc/0003-types-importers-scaffolds.md).
Protobuf is done; JSON Schema, OpenAPI and AsyncAPI follow.

| Path | What it is |
| --- | --- |
| `lib/descriptor.ml` | A `FileDescriptorSet`, decoded from protobuf's wire format (the parts of descriptor.proto the importer reads) |
| `lib/protobuf.ml` | The protobuf importer: names, the type mapping, presence, oneofs, enums, well-known types, recursion cuts, residue, the report and the name map |
| `lib/codec.ml` | The boundary codec: protobuf JSON to and from runtime values |
| `bin/cimport.ml` | `cimport protobuf SET.binpb [--out DIR \| --print types\|report\|names]` |
| `test/protos/` | The corpus (`shop.proto`, proto3; `legacy.proto`, proto2) and its descriptor set; `build.sh` rebuilds it with `grpc_tools.protoc` |
| `test/golden/` | The corpus's import, checked in: types, report, name map |
| `test/codec_test.ml` | The codec by example |
| `test/agreement.ml`, `test/pbcheck.py` | Hegel: protobuf's own JSON parser and the residue agree, and values round-trip through protobuf |

```sh
dune exec import/bin/cimport.exe -- protobuf import/test/protos/corpus.binpb
dune test                                  # golden files and codec examples
dune build @import/test/agreement          # needs python3 with protobuf
```
