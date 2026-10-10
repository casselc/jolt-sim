#!/usr/bin/env python3
"""protobuf's own binary and JSON reading and writing, as a line-oriented
service for the codec's agreement property
(docs/cordis/rfc/0004-descriptor-native-types.md, "Testing").

    pbwire.py SET.binpb [SET.binpb ...]

Each input line is {"type": "pkg.Message", "binary": "<base64>", "json":
"<protobuf JSON>"}. Both are parsed (strictly: unknown JSON fields are
errors, required fields are checked); each output line is

    {"ok": true, "same": <the two parse to the same message>,
     "binary": "<base64 of what protobuf writes>", "json": "<what it prints>"}

or {"ok": false, "error": "..."}. Printing keeps proto field names and
prints fields with no presence; binary writing is deterministic.
"""
import base64
import json
import sys

from google.protobuf import descriptor_pb2, descriptor_pool, json_format, message_factory


def as_dict(m, pool):
    return json_format.MessageToDict(m, preserving_proto_field_name=True, descriptor_pool=pool)


def differences(a, b):
    """The fields set differently in two messages, for a failure's report."""
    fa = {f.name: v for f, v in a.ListFields()}
    fb = {f.name: v for f, v in b.ListFields()}
    return sorted(
        f"{name}: binary {fa.get(name)!r} json {fb.get(name)!r}"
        for name in set(fa) | set(fb)
        if fa.get(name) != fb.get(name))


def main(paths):
    pool = descriptor_pool.DescriptorPool()
    added = set()
    for path in paths:
        fds = descriptor_pb2.FileDescriptorSet()
        with open(path, "rb") as f:
            fds.ParseFromString(f.read())
        for fd in fds.file:
            if fd.name not in added:
                pool.Add(fd)
                added.add(fd.name)
    for line in sys.stdin:
        req = json.loads(line)
        try:
            cls = message_factory.GetMessageClass(pool.FindMessageTypeByName(req["type"]))
            from_binary = cls()
            from_binary.ParseFromString(base64.b64decode(req["binary"]))
            from_json = json_format.Parse(req["json"], cls(), ignore_unknown_fields=False, descriptor_pool=pool)
            resp = {
                "ok": True,
                # compared as protobuf JSON, which unpacks each Any: an Any's
                # bytes hold a map's entries in no fixed order
                "same": as_dict(from_binary, pool) == as_dict(from_json, pool),
                "diff": differences(from_binary, from_json),
                "binary": base64.b64encode(from_binary.SerializeToString(deterministic=True)).decode(),
                "json": json_format.MessageToJson(
                    from_binary, preserving_proto_field_name=True, always_print_fields_with_no_presence=True, indent=None,
                    descriptor_pool=pool),
            }
        except Exception as e:  # a rejection is an answer, not a crash
            resp = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main(sys.argv[1:])
