# Cordis harness documents

- [DESIGN.md](DESIGN.md): the integrated design. Start here.
- [SYNTHESIS.md](SYNTHESIS.md): where the earlier repos left off, and how
  their concepts map onto Cordis.
- [OCAML-ON-BHYVE.md](OCAML-ON-BHYVE.md): the longer OCaml, bhyve, CEL, Cedar,
  scaffolding and OxCaml-on-FreeBSD exploration the design condenses.
- [research/PRESERVES.md](research/PRESERVES.md): Preserves in depth, with a
  hands-on canonical-encoding demo (`research/scripts/`). Preserves is
  deferred (DESIGN.md "Serialization").
- [rfc/](rfc/): 0001 CEL for cells, 0002 the graph language, 0003 the type
  IR, importers and scaffolds, 0004 descriptor-native protobuf types (phase
  2, built); 0005 XML and XSD, CSV and typed data frames (accepted through revision 5); 0006
  the turn runtime (phase 3, revision 2 accepted); 0007 boundaries and the
  operation contract (proposed).
- [research/STREAMING-PRIOR-ART.md](research/STREAMING-PRIOR-ART.md): batch
  and streaming systems (Rama, Flink, Beam, Kafka Streams, Materialize,
  Temporal and others), behind RFC 0005's frames and RFC 0006.
- [examples/](examples/): the review-loop graph, a cell, an intent frame,
  receipts and a Preserves Schema, in Preserves text.

The documents were exported from the shared design docs on 2026-10-09; their
interactive diagrams are described in place rather than reproduced. Phase 1
of the build order is in [`cordis/`](../../cordis/README.md).
