# Markdown conversions — what these are, and what they are not

Machine-generated Markdown for each PDF in the parent directory. They exist so
the corpus is **greppable and diffable**: `grep -r "tempered domination" .`
across fourteen papers is the thing a finding gets written from.

## Read this before quoting anything

**These are lossy.** They are produced by a heuristic converter, not by a
typesetting-aware tool:

- **Mathematics is destroyed.** Inference rules, subscripts, superscripts and
  operators flatten into run-together text. `Y𝑖= •` may appear as `𝑌𝑖= •`, and
  a typing rule's premises and conclusion lose their horizontal bar entirely.
- **Figures, tables and code listings** lose their structure and become
  paragraphs in whatever order the extractor found them.
- **Two-column reading order is reconstructed**, not read off the file. It is
  right nearly always and wrong at page boundaries and around floats.
- **Footnotes, running heads and author blocks** are interleaved with body text.
- Ligatures and end-of-line hyphenation are repaired, so `veriﬁed` becomes
  `verified` and `undecid-\nable` becomes `undecidable`. That is a *modification*
  of the source text, and it is why searching for a hyphenated form may fail.

**The PDF is the citable artifact.** Every quotation in `PERTURB-DESIGN.md`
findings E21 and E25 was taken from the PDF and carries a section or page
locator. Use these files to *locate* a passage, then read and quote the PDF.
`<!-- page N -->` anchors correspond to PDF pages so the round trip is short.

## Licence, and the obligation this directory creates

Every source paper is CC-BY 4.0 (or LIPIcs CC-BY) — see `../README.md` for the
per-paper grant and its evidence. CC-BY permits derivative works, which is what
a format conversion is, **provided the modification is indicated**. That
obligation is discharged in three places, and all three should stay:

1. the header block at the top of every generated `.md` file, which states that
   the file is a modified, machine-generated conversion and names the source;
2. this README;
3. `convert.py`, committed alongside, which *is* the precise statement of what
   was changed.

Attribution for each paper is in its own header block and in `../README.md`.

## Regenerating

```
python3 docs/research/papers/markdown/convert_all.py
```

Requires PyMuPDF (`import fitz`) and nothing else — no network, no pip install.
It is deterministic: re-running over unchanged PDFs reproduces byte-identical
output, so a diff here means a PDF changed or the converter did.

`convert.py` is the single-file converter (`convert.py <pdf> <title> <citation>
<source> <licence> <dest>`); `convert_all.py` holds the per-paper metadata table
and drives it. To add a paper: put the PDF in the parent directory **only if its
licence permits redistribution** (see `../README.md` for the test — the
authority is the publisher's Crossref metadata, not the line printed on the
PDF), then add a row to `convert_all.py`.

## Known conversion artifacts, by paper

| paper | artifact |
| --- | --- |
| `COORDINATION14-affine-sessions.md` | the first page is the HAL deposit cover sheet, not the paper; the paper starts at `<!-- page 2 -->` |
| `CONCUR20-session-types-with-arithmetic-refinements.md` | LIPIcs headers produce many short spurious headings |
| `grounded-conceptual-model-for-ownership-types-in-rust.md` | a study paper with many short subsections; heading detection over-fires |
| all PACMPL papers | the article number (e.g. `28`, `66`, `136`) appears as a stray line near the top of page 1 |
| `POPL24-soundly-handling-linearity.md` | the appendix is dense metatheory and converts worst of the set |
