#!/usr/bin/env python3
"""Python's jsonschema validator, as a line-oriented service for the JSON
Schema importer's tests (docs/cordis/rfc/0003-types-importers-
scaffolds.md, "The JSON Schema importer", "Testing").

    jsvalid.py

Each input line is {"schema": <schema>, "instance": <instance>}; each output
line is {"valid": bool} or {"error": "..."} when the schema itself is not
valid 2020-12. Formats are annotations, as the draft's default says.
Validators are cached by schema text.
"""
import json
import sys

from jsonschema import Draft202012Validator

_cache = {}


def validator(schema):
    key = json.dumps(schema, sort_keys=True)
    v = _cache.get(key)
    if v is None:
        Draft202012Validator.check_schema(schema)
        v = _cache[key] = Draft202012Validator(schema)
    return v


def main():
    for line in sys.stdin:
        req = json.loads(line)
        try:
            resp = {"valid": validator(req["schema"]).is_valid(req["instance"])}
        except Exception as e:  # an invalid schema is an answer, not a crash
            resp = {"error": f"{type(e).__name__}: {e}"}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
