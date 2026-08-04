#!/usr/bin/env python3
"""PDF -> Markdown for two-column academic papers, using PyMuPDF only.

Deliberately conservative: it recovers reading order, headings and paragraphs.
Math, figures and tables degrade to plain text. Page anchors are preserved so a
quotation can be traced back to a page in the PDF.
"""
import re, sys, unicodedata, statistics
import fitz

LIG = {"ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi",
       "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st",
       "’": "'", "‘": "'", "“": '"', "”": '"',
       "–": "-", "—": "--", " ": " ", "﻿": "",
       "Ł": "L", "ł": "l"}


CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def clean(t):
    for k, v in LIG.items():
        t = t.replace(k, v)
    # Stray control bytes from broken font encodings make the file grep as
    # binary, which defeats the point of having a text conversion at all.
    t = CTRL.sub("", t)
    return t


def page_columns(page, blocks):
    """Detect a two-column layout from the x-midpoints of text blocks."""
    w = page.rect.width
    mids = [(b[0] + b[2]) / 2 for b in blocks if b[2] - b[0] < w * 0.62]
    if len(mids) < 6:
        return 1
    left = [m for m in mids if m < w / 2]
    right = [m for m in mids if m >= w / 2]
    if len(left) >= 3 and len(right) >= 3:
        return 2
    return 1


def ordered_blocks(page):
    raw = [b for b in page.get_text("blocks") if b[6] == 0 and b[4].strip()]
    if not raw:
        return []
    ncol = page_columns(page, raw)
    if ncol == 1:
        return sorted(raw, key=lambda b: (round(b[1], 1), b[0]))
    mid = page.rect.width / 2
    left = sorted([b for b in raw if (b[0] + b[2]) / 2 < mid],
                  key=lambda b: (round(b[1], 1), b[0]))
    right = sorted([b for b in raw if (b[0] + b[2]) / 2 >= mid],
                   key=lambda b: (round(b[1], 1), b[0]))
    return left + right


def body_size(doc):
    sizes = []
    for p in doc[: min(8, len(doc))]:
        for blk in p.get_text("dict")["blocks"]:
            for ln in blk.get("lines", []):
                for sp in ln.get("spans", []):
                    if sp["text"].strip():
                        sizes.append(round(sp["size"], 1))
    return statistics.median(sizes) if sizes else 10.0


def span_sizes(page):
    """Map (block index approximated by y) -> max font size, for headings."""
    out = []
    for blk in page.get_text("dict")["blocks"]:
        if "lines" not in blk:
            continue
        mx, bold = 0.0, False
        txt = []
        for ln in blk["lines"]:
            for sp in ln["spans"]:
                if sp["text"].strip():
                    mx = max(mx, sp["size"])
                    if "Bold" in sp.get("font", "") or "bold" in sp.get("font", ""):
                        bold = True
                    txt.append(sp["text"])
        if txt:
            out.append((blk["bbox"], mx, bold))
    return out


def dehyphenate(text):
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)
    text = re.sub(r"(\w)-\s*\n\s*(\w)", r"\1\2", text)
    return text


NUMHEAD = re.compile(r"^\s*(\d+(\.\d+)*)\s+([A-Z][A-Za-z].{0,70})$")


def convert(pdf_path, title, cite, source, licence):
    doc = fitz.open(pdf_path)
    bs = body_size(doc)
    out = [f"# {title}", "",
           "> **Machine-generated Markdown conversion — this is a MODIFIED version.**",
           f"> Converted from `{pdf_path.split('/')[-1]}` with PyMuPDF. Layout, mathematics,",
           "> figures and tables are lossy; **quote from the PDF, not from this file.**",
           "> Page anchors below correspond to PDF pages.",
           "",
           f"- **Citation:** {cite}",
           f"- **Licence:** {licence}",
           f"- **Source:** {source}",
           "",
           "---", ""]

    for pno, page in enumerate(doc, 1):
        out.append(f"\n<!-- page {pno} -->\n")
        sizes = span_sizes(page)

        def size_for(b):
            best, bold = bs, False
            for bbox, mx, bd in sizes:
                if abs(bbox[1] - b[1]) < 2 and abs(bbox[0] - b[0]) < 2:
                    return mx, bd
                if (bbox[0] >= b[0] - 2 and bbox[2] <= b[2] + 2
                        and bbox[1] >= b[1] - 2 and bbox[3] <= b[3] + 2):
                    best, bold = max(best, mx), bold or bd
            return best, bold

        for b in ordered_blocks(page):
            raw = clean(dehyphenate(b[4])).strip()
            if not raw:
                continue
            lines = [l.strip() for l in raw.split("\n") if l.strip()]
            if not lines:
                continue
            sz, bold = size_for(b)
            first = lines[0]

            # Drop running heads / bare page & article numbers.
            if re.fullmatch(r"[\d:.\s]+", first) and len(lines) == 1:
                continue

            # "1.3\nAffine Types" — number and title on separate lines.
            if re.fullmatch(r"\d+(\.\d+)*\.?", first) and len(lines) >= 2 \
                    and re.match(r"^[A-Z]", lines[1]) and len(lines[1]) < 90:
                depth = min(6, 1 + len(first.rstrip(".").split(".")))
                out.append(f"{'#' * max(2, depth)} {first} {lines[1]}")
                out.append("")
                lines = lines[2:]
                if not lines:
                    continue
                first = lines[0]

            # A heading may share a block with the paragraph that follows it.
            m = NUMHEAD.match(first) or re.match(
                r"^\s*(\d+(\.\d+)*)\s+([A-Z][A-Z ]{2,60})$", first)
            head, rest = None, lines
            if m:
                head = first
                rest = lines[1:]
            else:
                is_big = sz >= bs * 1.15
                if len(lines) <= 2 and (is_big or (bold and len(first) < 80)) \
                        and len(first) < 90:
                    head, rest = first, lines[1:]
                # "1 INTRODUCTION Body text ..." collapsed onto one line
                elif is_big or bold:
                    sp = re.match(
                        r"^\s*(\d+(?:\.\d+)*\s+[A-Z][A-Za-z’'\- ]{2,60}?)\s+"
                        r"(?=[A-Z][a-z])(.*)$", first)
                    if sp:
                        head = sp.group(1).strip()
                        rest = [sp.group(2)] + lines[1:]

            if head is not None:
                hm = NUMHEAD.match(head) or re.match(r"^\s*(\d+(\.\d+)*)\s", head)
                depth = min(6, 1 + len(hm.group(1).split("."))) if hm else 2
                if not hm and sz >= bs * 1.6:
                    depth = 1
                out.append(f"{'#' * max(2, depth)} {head}")
                out.append("")
                if not rest:
                    continue
                lines = rest
                first = lines[0]

            para = " ".join(lines)
            para = re.sub(r"\s+", " ", para).strip()
            out.append(para)
            out.append("")

    md = "\n".join(out)
    md = re.sub(r"\n{4,}", "\n\n\n", md)
    return md


if __name__ == "__main__":
    pdf, title, cite, source, licence, dest = sys.argv[1:7]
    open(dest, "w").write(convert(pdf, title, cite, source, licence))
    print(f"wrote {dest} ({len(open(dest).read())} chars)")
