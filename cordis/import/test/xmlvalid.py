#!/usr/bin/env python3
"""xmlschema and lxml as a line-oriented XML validator for the XML codec's
agreement test (docs/cordis/rfc/0005-xml-csv-frames.md, revision 4,
"Testing").

    xmlvalid.py

Each input line is {"xsd": path, "xml": text}; each output line is
{"valid": bool, "lxml": bool, "errors": [...]}: xmlschema's verdict, lxml's
(libxml2's) verdict, and xmlschema's reasons."""
import json
import sys

import xmlschema
from lxml import etree

schemas = {}

for line in sys.stdin:
    q = json.loads(line)
    path = q["xsd"]
    if path not in schemas:
        schemas[path] = (xmlschema.XMLSchema(path), etree.XMLSchema(etree.parse(path)))
    xs, lx = schemas[path]
    text = q["xml"]
    try:
        errors = [str(e.reason or e) for e in xs.iter_errors(text)]
    except Exception as e:  # not even well-formed for it
        errors = [repr(e)]
    try:
        lvalid = lx.validate(etree.fromstring(text.encode()))
    except Exception:
        lvalid = False
    print(json.dumps({"valid": not errors, "lxml": bool(lvalid), "errors": errors}), flush=True)
