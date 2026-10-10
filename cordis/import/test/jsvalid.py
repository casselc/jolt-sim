#!/usr/bin/env python3
"""Python's jsonschema validator, as a line-oriented service for the JSON
Schema importer's tests (docs/cordis/rfc/0003-types-importers-
scaffolds.md, "The JSON Schema importer", "Testing").

    jsvalid.py [DOCUMENT_OR_DIR ...]

The documents (JSON files, or directories of them) are what references
may name, registered under their $id and, for SchemaStore's convention,
https://json.schemastore.org/<file name>; nothing is fetched. Each input line is {"schema": <schema>, "instance": <instance>}; each output
line is {"valid": bool} or {"error": "..."} when the schema itself is not
valid. The draft is the schema's $schema (2020-12 without one). Formats
are annotations, as the drafts' default says. Validators are cached by
schema text.
"""
import json
import sys

import os

from jsonschema import Draft202012Validator, validators
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

_cache = {}
_registry = Registry()


def register(paths):
    global _registry
    resources = []
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.endswith(".json")]
        else:
            files.append(p)
    for f in files:
        try:
            with open(f) as h:
                doc = json.load(h)
        except Exception:
            continue
        if not isinstance(doc, (dict, bool)):
            continue
        resource = Resource.from_contents(doc, default_specification=DRAFT202012)
        uris = {"https://json.schemastore.org/" + os.path.basename(f)}
        if isinstance(doc, dict) and isinstance(doc.get("$id"), str):
            uris.add(doc["$id"].rstrip("#"))
        resources += [(u, resource) for u in uris]
    _registry = Registry().with_resources(resources)


def validator(schema):
    key = json.dumps(schema, sort_keys=True)
    v = _cache.get(key)
    if v is None:
        cls = validators.validator_for(schema, default=Draft202012Validator)
        cls.check_schema(schema)
        v = _cache[key] = cls(schema, registry=_registry)
    return v


def main(paths):
    register(paths)
    for line in sys.stdin:
        req = json.loads(line)
        try:
            resp = {"valid": validator(req["schema"]).is_valid(req["instance"])}
        except Exception as e:  # an invalid schema is an answer, not a crash
            resp = {"error": f"{type(e).__name__}: {e}"}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main(sys.argv[1:])
