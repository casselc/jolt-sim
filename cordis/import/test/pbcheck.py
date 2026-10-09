#!/usr/bin/env python3
"""protobuf's own JSON parser, as a line-oriented service for the importer's
agreement property (docs/cordis/rfc/0003-types-importers-scaffolds.md,
"Testing").

    pbcheck.py SET.binpb

Each input line is {"type": "pkg.Message", "json": "<protobuf JSON>"}; each
output line is {"ok": true, "json": "<what protobuf prints back>"} or
{"ok": false, "error": "..."}. Parsing is strict (unknown fields are
errors); printing keeps proto field names and prints fields with no
presence, so nothing is left to defaults.
"""
import json
import sys

from google.protobuf import descriptor_pb2, descriptor_pool, json_format, message_factory


def main(path):
    fds = descriptor_pb2.FileDescriptorSet()
    with open(path, "rb") as f:
        fds.ParseFromString(f.read())
    pool = descriptor_pool.DescriptorPool()
    for fd in fds.file:
        pool.Add(fd)
    for line in sys.stdin:
        req = json.loads(line)
        try:
            cls = message_factory.GetMessageClass(pool.FindMessageTypeByName(req["type"]))
            msg = json_format.Parse(req["json"], cls(), ignore_unknown_fields=False)
            out = json_format.MessageToJson(
                msg, preserving_proto_field_name=True, always_print_fields_with_no_presence=True, indent=None)
            resp = {"ok": True, "json": out}
        except Exception as e:  # a rejection is an answer, not a crash
            resp = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main(sys.argv[1])
