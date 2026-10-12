#!/usr/bin/env python3
"""lxml as an oracle for the schemaless XML read (docs/cordis/rfc/0005-xml-
csv-frames.md, revision 5): each input line is {"xml": text}; each output
line is {"ok": true, "node": E} with E the root element in the codec's form
(ns, name, attrs in document order, kids: Element, Text, Comment, Pi), or
{"ok": false, "error": why}."""
import json
import sys

from lxml import etree

parser = etree.XMLParser(resolve_entities=False, remove_blank_text=False, strip_cdata=True, huge_tree=False, no_network=True)


def split(tag):
    if tag.startswith("{"):
        ns, name = tag[1:].split("}", 1)
        return ns, name
    return "", tag


def element(e):
    ns, name = split(e.tag)
    attrs = []
    for k, v in e.attrib.items():
        ans, an = split(k)
        attrs.append({"ns": ans, "name": an, "value": v})
    kids = []

    def text(t):
        if t:
            if kids and kids[-1]["tag"] == "Text":
                kids[-1]["value"] += t
            else:
                kids.append({"tag": "Text", "value": t})

    text(e.text)
    for c in e:
        if isinstance(c, etree._Comment):
            kids.append({"tag": "Comment", "value": c.text or ""})
        elif isinstance(c, etree._ProcessingInstruction):
            kids.append({"tag": "Pi", "value": {"target": c.target, "data": c.text or ""}})
        elif isinstance(c, etree._Entity):
            raise ValueError("an entity reference")
        else:
            kids.append({"tag": "Element", "value": element(c)})
        text(c.tail)
    return {"ns": ns, "name": name, "attrs": attrs, "kids": kids}


for line in sys.stdin:
    q = json.loads(line)
    try:
        root = etree.fromstring(q["xml"].encode(), parser)
        out = {"ok": True, "node": element(root)}
    except Exception as e:  # not well-formed for it
        out = {"ok": False, "error": repr(e)}
    print(json.dumps(out), flush=True)
