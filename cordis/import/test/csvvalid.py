#!/usr/bin/env python3
"""frictionless as a line-oriented CSV validator for the CSV codec's
agreement test (docs/cordis/rfc/0005-xml-csv-frames.md, revision 3,
"CSV").

    csvvalid.py

Each input line is {"schema": TABLE_SCHEMA, "csv": text}; each output line
is {"valid": bool, "errors": [...], "rows": [...]}, the rows as frictionless
reads them (a missing cell left out, a number as a double)."""
import decimal
import json
import sys

from frictionless import Resource, Schema


def plain(v):
    if isinstance(v, decimal.Decimal):
        return float(v)
    return v


for line in sys.stdin:
    q = json.loads(line)
    try:
        schema = Schema.from_descriptor(q["schema"])
        report = Resource(q["csv"].encode(), format="csv", schema=schema).validate()
        errors = [e.type + ": " + e.message for t in report.tasks for e in t.errors]
        rows = []
        with Resource(q["csv"].encode(), format="csv", schema=schema) as res:
            for r in res.row_stream:
                rows.append({k: plain(v) for k, v in r.to_dict().items() if v is not None})
    except Exception as e:  # a table frictionless cannot read at all
        errors, rows = [repr(e)], []
    print(json.dumps({"valid": not errors, "errors": errors, "rows": rows}), flush=True)
