# Cordis harness documents

- [DESIGN.md](DESIGN.md): the integrated design. Start here.
- [SYNTHESIS.md](SYNTHESIS.md): where the earlier repos left off, and how
  their concepts map onto Cordis.
- [OCAML-ON-BHYVE.md](OCAML-ON-BHYVE.md): the longer OCaml, bhyve, CEL, Cedar,
  scaffolding and OxCaml-on-FreeBSD exploration the design condenses.
- [research/PRESERVES.md](research/PRESERVES.md): Preserves in depth, with a
  hands-on canonical-encoding demo (`research/scripts/`).
- [rfc/](rfc/): phase 2 RFCs: 0001 CEL for cells, 0002 the graph language,
  0003 the type IR, importers and scaffolds, 0004 descriptor-native
  protobuf types, 0005 XML and XSD, CSV and typed data frames (proposed).
- [examples/](examples/): the review-loop graph, a cell, an intent frame,
  receipts and a Preserves Schema, in Preserves text.

The documents were exported from the shared design docs on 2026-10-09; their
interactive diagrams are described in place rather than reproduced. Phase 1
of the build order is in [`cordis/`](../../cordis/README.md).
