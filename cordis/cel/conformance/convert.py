#!/usr/bin/env python3
"""Convert cel-spec's tests/simple/testdata/*.textproto into JSON.

The textprotos are parsed with the real protobuf text-format parser against
descriptors compiled from cel-spec's own .proto files (no regexes).

Usage:
    python -I convert.py --cel-spec <cel-spec checkout> --out <dir> \
        [--gen <dir for generated _pb2 modules>] [--stats]

Requires: protobuf, grpcio-tools (for grpc_tools.protoc).
See README.md for the exact regeneration commands.
"""

import argparse
import base64
import glob
import importlib
import json
import math
import os
import subprocess
import sys
import re
import tempfile

# Expressions that name the conformance test messages/enums directly (without
# a container) need those proto types registered in the evaluator.
PROTO_EXPR = re.compile(r"TestAllTypes|cel\s*\.\s*expr\s*\.\s*conformance|GlobalEnum|NestedEnum")


# --------------------------------------------------------------------------
# proto compilation / loading

def compile_protos(cel_spec, gen):
    """Compile every .proto under <cel-spec>/proto into python modules in gen."""
    import grpc_tools  # noqa: F401  (fail early with a clear error)
    from grpc_tools import protoc
    proto_root = os.path.join(cel_spec, "proto")
    well_known = os.path.join(os.path.dirname(grpc_tools.__file__), "_proto")
    files = sorted(
        os.path.relpath(p, proto_root)
        for p in glob.glob(os.path.join(proto_root, "**", "*.proto"), recursive=True)
    )
    os.makedirs(gen, exist_ok=True)
    args = ["protoc", "-I" + proto_root, "-I" + well_known, "--python_out=" + gen] + files
    rc = protoc.main(args)
    if rc != 0:
        raise SystemExit("protoc failed (%d)" % rc)
    return files


def load_modules(gen, proto_files):
    """Import every generated module so all messages/extensions register in the
    default descriptor pool (needed for Any expansion and extensions)."""
    sys.path.insert(0, gen)
    mods = {}
    for f in proto_files:
        modname = f[:-len(".proto")].replace("/", ".") + "_pb2"
        mods[f] = importlib.import_module(modname)
    return mods


# --------------------------------------------------------------------------
# descriptors

TYPE_NAMES = {
    1: "double", 2: "float", 3: "int64", 4: "uint64", 5: "int32", 6: "fixed64",
    7: "fixed32", 8: "bool", 9: "string", 10: "group", 11: "message", 12: "bytes",
    13: "uint32", 14: "enum", 15: "sfixed32", 16: "sfixed64", 17: "sint32", 18: "sint64",
}


def field_json(fd):
    """One field (or extension) in the descriptor export."""
    j = {
        "name": fd.name,
        "json_name": fd.json_name,
        "number": fd.number,
        "type": TYPE_NAMES[fd.type],
        "repeated": fd.is_repeated,
        "type_name": (fd.message_type.full_name if fd.message_type is not None
                      else fd.enum_type.full_name if fd.enum_type is not None else None),
        "oneof": fd.containing_oneof.name if fd.containing_oneof is not None else None,
        "presence": bool(fd.has_presence),
        "default": None,
        "packed": bool(getattr(fd, "is_packed", False)),
    }
    if fd.has_default_value and not fd.is_repeated:
        v = fd.default_value
        if fd.type == fd.TYPE_ENUM:
            v = fd.enum_type.values_by_number[v].number if isinstance(v, int) else v
        if isinstance(v, bytes):
            j["default"] = {"bytes": base64.b64encode(v).decode()}
        elif isinstance(v, float):
            j["default"] = {"double": enc_double(v)}
        else:
            j["default"] = v
    mt = fd.message_type
    if mt is not None and mt.GetOptions().map_entry:
        j["map"] = {"key": field_json(mt.fields_by_name["key"]),
                    "value": field_json(mt.fields_by_name["value"])}
    if fd.is_extension:
        j["extendee"] = fd.containing_type.full_name
        j["full_name"] = fd.full_name
    return j


def export_descriptors(mods, path):
    """Every message, enum and extension of the compiled protos, as JSON, for
    the OCaml implementation's dynamic messages."""
    out = {"messages": {}, "enums": {}, "extensions": []}

    def message(d, syntax):
        out["messages"][d.full_name] = {
            "syntax": syntax,
            "map_entry": d.GetOptions().map_entry,
            "fields": [field_json(f) for f in d.fields],
        }
        for e in d.enum_types:
            out["enums"][e.full_name] = {"values": [[v.name, v.number] for v in e.values]}
        for n in d.nested_types:
            message(n, syntax)
        for x in d.extensions:
            out["extensions"].append(field_json(x))

    seen = set()
    from google.protobuf import (any_pb2, duration_pb2, empty_pb2, field_mask_pb2,
                                 struct_pb2, timestamp_pb2, wrappers_pb2)
    files = [m.DESCRIPTOR for m in mods.values()] + [
        m.DESCRIPTOR for m in (any_pb2, duration_pb2, empty_pb2, field_mask_pb2,
                               struct_pb2, timestamp_pb2, wrappers_pb2)]
    for fd in files:
        if fd.name in seen:
            continue
        seen.add(fd.name)
        syntax = getattr(fd, "syntax", None) or ("proto3" if "proto3" in fd.name else "proto2")
        for d in fd.message_types_by_name.values():
            message(d, syntax)
        for e in fd.enum_types_by_name.values():
            out["enums"][e.full_name] = {"values": [[v.name, v.number] for v in e.values]}
        for x in fd.extensions_by_name.values():
            out["extensions"].append(field_json(x))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")


# --------------------------------------------------------------------------
# encoders

def which(msg):
    """Name of the set field of a message's (first) oneof, or None."""
    oneofs = msg.DESCRIPTOR.oneofs
    if not oneofs:
        return None
    return msg.WhichOneof(oneofs[0].name)


def enc_double(d):
    if math.isnan(d):
        return "NaN"
    if math.isinf(d):
        return "Infinity" if d > 0 else "-Infinity"
    if d == 0.0 and math.copysign(1.0, d) < 0:
        return "-0"
    return d


class Encoder:
    def __init__(self):
        from google.protobuf import symbol_database
        self.db = symbol_database.Default()
        self.flags = set()  # features seen while encoding the current test

    def value(self, v):
        """cel.expr.Value -> JSON value encoding."""
        k = which(v)
        if k is None:
            # An unset Value. Not expected in the corpus; flag it loudly.
            self.flags.add("unset_value")
            return {"unset": True}
        if k == "null_value":
            return {"null": None}
        if k == "bool_value":
            return {"bool": v.bool_value}
        if k == "int64_value":
            return {"int": str(v.int64_value)}
        if k == "uint64_value":
            return {"uint": str(v.uint64_value)}
        if k == "double_value":
            return {"double": enc_double(v.double_value)}
        if k == "string_value":
            return {"string": v.string_value}
        if k == "bytes_value":
            return {"bytes": base64.b64encode(v.bytes_value).decode("ascii")}
        if k == "enum_value":
            self.flags.add("enum")
            return {"enum": {"type": v.enum_value.type, "value": v.enum_value.value}}
        if k == "object_value":
            return self.any(v.object_value)
        if k == "map_value":
            return {"map": [[self.value(e.key), self.value(e.value)]
                            for e in v.map_value.entries]}
        if k == "list_value":
            return {"list": [self.value(x) for x in v.list_value.values]}
        if k == "type_value":
            return {"type": v.type_value}
        raise ValueError("unknown Value kind " + k)

    def any(self, a):
        from google.protobuf import json_format
        type_url = a.type_url
        full_name = type_url.split("/")[-1]
        if full_name == "google.protobuf.Timestamp":
            from google.protobuf import timestamp_pb2
            t = timestamp_pb2.Timestamp()
            a.Unpack(t)
            return {"timestamp": t.ToJsonString()}
        if full_name == "google.protobuf.Duration":
            from google.protobuf import duration_pb2
            d = duration_pb2.Duration()
            a.Unpack(d)
            return {"duration": d.ToJsonString()}
        self.flags.add("object")
        try:
            cls = self.db.GetSymbol(full_name)
        except KeyError:
            return {"object": {"type_url": type_url, "json": {"unsupported": True}}}
        m = cls()
        if not a.Unpack(m):
            return {"object": {"type_url": type_url, "json": {"unsupported": True}}}
        return {"object": {"type_url": type_url,
                           "json": json_format.MessageToDict(m)}}

    def expr_value(self, ev):
        """cel.expr.ExprValue (bindings) -> value encoding, or error/unknown."""
        k = which(ev)
        if k == "value":
            return self.value(ev.value)
        if k == "error":
            self.flags.add("binding_error")
            return {"error": first_error(ev.error)}
        if k == "unknown":
            self.flags.add("binding_unknown")
            return {"unknown": list(ev.unknown.exprs)}
        raise ValueError("unset ExprValue")


def first_error(error_set):
    return error_set.errors[0].message if error_set.errors else ""


PRIM = {1: "bool", 2: "int", 3: "uint", 4: "double", 5: "string", 6: "bytes"}
WKT = {1: "google.protobuf.Any", 2: "google.protobuf.Timestamp",
       3: "google.protobuf.Duration"}


def type_str(t):
    """cel.expr.Type -> short CEL-ish text form."""
    k = which(t)
    if k is None:
        return "<unset>"
    if k == "dyn":
        return "dyn"
    if k == "null":
        return "null_type"
    if k == "primitive":
        return PRIM.get(t.primitive, "<primitive %d>" % t.primitive)
    if k == "wrapper":
        return "wrapper(%s)" % PRIM.get(t.wrapper, "<primitive %d>" % t.wrapper)
    if k == "well_known":
        return WKT.get(t.well_known, "<well_known %d>" % t.well_known)
    if k == "list_type":
        return "list(%s)" % type_str(t.list_type.elem_type)
    if k == "map_type":
        return "map(%s, %s)" % (type_str(t.map_type.key_type),
                                type_str(t.map_type.value_type))
    if k == "function":
        return "function(%s) -> %s" % (
            ", ".join(type_str(a) for a in t.function.arg_types),
            type_str(t.function.result_type))
    if k == "message_type":
        return t.message_type
    if k == "type_param":
        return t.type_param
    if k == "type":
        inner = which(t.type)
        return "type" if inner is None else "type(%s)" % type_str(t.type)
    if k == "error":
        return "*error*"
    if k == "abstract_type":
        ps = t.abstract_type.parameter_types
        if not ps:
            return t.abstract_type.name
        return "%s(%s)" % (t.abstract_type.name, ", ".join(type_str(p) for p in ps))
    return "<%s>" % k


def decl(d):
    k = which(d)
    if k == "ident":
        out = {"ident": d.name, "type": type_str(d.ident.type)}
        if d.ident.HasField("value"):
            from google.protobuf import json_format
            out["value"] = json_format.MessageToDict(d.ident.value)
        return out
    if k == "function":
        return {"function": d.name,
                "overloads": [{"id": o.overload_id,
                               "params": [type_str(p) for p in o.params],
                               "result": type_str(o.result_type),
                               "type_params": list(o.type_params),
                               "receiver": o.is_instance_function}
                              for o in d.function.overloads]}
    return {"unknown_decl": d.name}


# --------------------------------------------------------------------------

def convert_test(t, enc):
    enc.flags = set()
    out = {
        "name": t.name,
        "expr": t.expr,
        "disable_macros": t.disable_macros,
        "disable_check": t.disable_check,
        "container": t.container,
        "bindings": {k: enc.expr_value(t.bindings[k]) for k in sorted(t.bindings)},
        "type_env": [decl(d) for d in t.type_env],
    }
    m = t.WhichOneof("result_matcher")
    typed = None
    if m is None:
        expect = {"bool": True}  # spec: unspecified result means `true`
    elif m == "value":
        expect = enc.value(t.value)
    elif m == "typed_result":
        tr = t.typed_result
        if tr.HasField("result"):
            expect = enc.value(tr.result)
            typed = {"value": expect, "type": type_str(tr.deduced_type)}
        else:
            expect = {"any_value": True}
            typed = {"value": None, "type": type_str(tr.deduced_type)}
    elif m == "eval_error":
        expect = {"error": first_error(t.eval_error)}
    elif m == "any_eval_errors":
        sets = t.any_eval_errors.errors
        expect = {"error": first_error(sets[0]) if sets else ""}
    elif m == "unknown":
        expect = {"unknown": list(t.unknown.exprs)}
    elif m == "any_unknowns":
        us = t.any_unknowns.unknowns
        expect = {"unknown": list(us[0].exprs) if us else []}
    else:
        raise ValueError("unknown result matcher " + m)
    if t.check_only and m is None:
        # check_only with no matcher: there is nothing to evaluate against.
        expect = {"any_value": True}
    out["expect"] = expect
    out["check_only"] = t.check_only
    out["typed_result"] = typed
    if t.locale:
        out["locale"] = t.locale  # extra key; currently unused by the corpus
    return out, set(enc.flags), m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cel-spec", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--gen", default=None,
                    help="directory for generated _pb2 modules (default: temp)")
    ap.add_argument("--stats", action="store_true",
                    help="print a markdown table of per-file counts")
    a = ap.parse_args()

    cel_spec = os.path.abspath(a.cel_spec)
    sha = subprocess.check_output(["git", "-C", cel_spec, "rev-parse", "HEAD"],
                                  text=True).strip()
    gen = a.gen or tempfile.mkdtemp(prefix="celpb_")
    proto_files = compile_protos(cel_spec, gen)
    mods = load_modules(gen, proto_files)
    simple_pb2 = mods["cel/expr/conformance/test/simple.proto"]
    export_descriptors(mods, os.path.join(os.path.dirname(os.path.abspath(a.out)), "descriptors.json"))

    from google.protobuf import text_format
    enc = Encoder()
    os.makedirs(a.out, exist_ok=True)
    rows = []
    srcs = sorted(glob.glob(os.path.join(cel_spec, "tests", "simple", "testdata",
                                         "*.textproto")))
    for path in srcs:
        base = os.path.basename(path)[:-len(".textproto")]
        msg = simple_pb2.SimpleTestFile()
        with open(path, encoding="utf-8") as f:
            text_format.Parse(f.read(), msg)
        st = dict(sections=0, tests=0, object=0, enum=0, check_only=0,
                  typed_result=0, type_env=0, container=0, proto_expr=0, usable=0)
        doc = {
            "source": "cel-spec tests/simple/testdata/%s.textproto @ %s" % (base, sha),
            "name": msg.name or base,
            "sections": [],
        }
        for s in msg.section:
            st["sections"] += 1
            tests = []
            for t in s.test:
                jt, flags, _ = convert_test(t, enc)
                tests.append(jt)
                st["tests"] += 1
                st["object"] += "object" in flags
                st["enum"] += "enum" in flags
                st["check_only"] += t.check_only
                st["typed_result"] += jt["typed_result"] is not None
                st["type_env"] += bool(jt["type_env"])
                st["container"] += bool(jt["container"])
                pexpr = bool(PROTO_EXPR.search(t.expr))
                st["proto_expr"] += pexpr
                st["usable"] += not (flags & {"object", "enum"} or jt["type_env"]
                                     or t.check_only or jt["container"] or pexpr)
            doc["sections"].append({"name": s.name, "tests": tests})
        with open(os.path.join(a.out, base + ".json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, indent=1, ensure_ascii=False)
            f.write("\n")
        rows.append((base, st))

    if a.stats:
        cols = ["sections", "tests", "object", "enum", "check_only",
                "typed_result", "type_env", "container", "proto_expr", "usable"]
        print("| file | " + " | ".join(cols) + " |")
        print("|---|" + "---:|" * len(cols))
        tot = {c: 0 for c in cols}
        for base, st in rows:
            print("| %s | " % base + " | ".join(str(st[c]) for c in cols) + " |")
            for c in cols:
                tot[c] += st[c]
        print("| **total** | " + " | ".join(str(tot[c]) for c in cols) + " |")
    print("converted %d files from cel-spec @ %s" % (len(rows), sha), file=sys.stderr)


if __name__ == "__main__":
    main()
