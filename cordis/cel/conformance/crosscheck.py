#!/usr/bin/env python3
"""Run the plain-value part of the conformance corpus through celgo and compare.

A test is "usable" when it has no proto object/enum values (bindings, expected
value or typed result), no type_env, is not check_only, has no container, and
its expression does not name a conformance proto type (e.g.
`.cel.expr.conformance.proto3.TestAllTypes{...}`), which would need the test
protos registered in the evaluator.

Usage:
    python -I crosscheck.py --celgo <path to celgo binary> [--profile default|conformance]
        [--simple <dir of converted JSON>] [--verbose] [--json <report.json>]

Matching rules (mirroring cel-spec's SimpleTest semantics and cel-go's runner):
  * value: CEL value equality; maps are order-agnostic, NaN matches NaN,
    doubles compare numerically (so -0 == 0), timestamps/durations compare as
    instants/lengths.  A celgo error never matches a value.
  * error: celgo must report an error at program/eval time (a parse or check
    failure is a mismatch, as in cel-go's runner).  Message equality is
    reported separately; it is not part of pass/fail.
  * unknown: celgo must return the same unknown ids.
  * any_value: celgo must return a value.
"""

import argparse
import base64
import datetime
import glob
import json
import math
import os
import re
import subprocess
import sys

PROTO_EXPR = re.compile(r"TestAllTypes|cel\s*\.\s*expr\s*\.\s*conformance|GlobalEnum|NestedEnum")

HERE = os.path.dirname(os.path.abspath(__file__))


def has_proto(v):
    if isinstance(v, dict):
        if "object" in v or "enum" in v:
            return True
        return any(has_proto(x) for x in v.values())
    if isinstance(v, list):
        return any(has_proto(x) for x in v)
    return False


def usable(t):
    return not (t["type_env"] or t["check_only"] or t["container"]
                or has_proto(t["bindings"]) or has_proto(t["expect"])
                or has_proto(t["typed_result"]) or PROTO_EXPR.search(t["expr"]))


# ---------------------------------------------------------------- equality

def dbl(x):
    if isinstance(x, str):
        return {"NaN": math.nan, "Infinity": math.inf,
                "-Infinity": -math.inf, "-0": -0.0}[x]
    return float(x)


def ts(s):
    m = re.fullmatch(r"(.*T\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)", s)
    if not m:
        raise ValueError("bad timestamp " + s)
    base = datetime.datetime.fromisoformat(m.group(1) + ("+00:00" if m.group(3) == "Z" else m.group(3)))
    frac = (m.group(2) or "").ljust(9, "0")
    secs = int((base - datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)).total_seconds())
    return secs * 10**9 + int(frac or 0)


def dur(s):
    m = re.fullmatch(r"(-?)(\d+)(?:\.(\d+))?s", s)
    if not m:
        raise ValueError("bad duration " + s)
    n = int(m.group(2)) * 10**9 + int((m.group(3) or "").ljust(9, "0") or 0)
    return -n if m.group(1) else n


def veq(a, b):
    """CEL value equality over the JSON encoding (structural, map-unordered)."""
    if not (isinstance(a, dict) and isinstance(b, dict) and len(a) == 1 and len(b) == 1):
        return a == b
    (ka, va), = a.items()
    (kb, vb), = b.items()
    if ka != kb:
        return False
    if ka == "double":
        x, y = dbl(va), dbl(vb)
        return (math.isnan(x) and math.isnan(y)) or x == y
    if ka == "int" or ka == "uint":
        return int(va) == int(vb)
    if ka == "bytes":
        return base64.b64decode(va) == base64.b64decode(vb)
    if ka == "list":
        return len(va) == len(vb) and all(veq(x, y) for x, y in zip(va, vb))
    if ka == "map":
        if len(va) != len(vb):
            return False
        unmatched = list(vb)
        for k, v in va:
            for i, (k2, v2) in enumerate(unmatched):
                if veq(k, k2):
                    if not veq(v, v2):
                        return False
                    del unmatched[i]
                    break
            else:
                return False
        return True
    if ka == "timestamp":
        return ts(va) == ts(vb)
    if ka == "duration":
        return dur(va) == dur(vb)
    return va == vb


# ---------------------------------------------------------------- driver

def run_celgo(celgo, profile, reqs):
    inp = "".join(json.dumps(r) + "\n" for r in reqs)
    p = subprocess.run([celgo, "-profile", profile], input=inp, text=True,
                       capture_output=True, check=True)
    lines = p.stdout.splitlines()
    if len(lines) != len(reqs):
        raise SystemExit("celgo returned %d lines for %d requests" % (len(lines), len(reqs)))
    return [json.loads(l) for l in lines]


def judge(expect, got):
    """-> (passed, note)"""
    if "error" in expect and len(expect) == 1:
        if "error" not in got:
            return False, "expected error"
        if got.get("stage") not in ("eval", "program"):
            return False, "expected eval error, got %s error" % got.get("stage")
        want = expect["error"]
        return True, ("" if not want or want in got["error"] else "message differs")
    if "error" in got:
        return False, "unexpected %s error" % got.get("stage")
    if expect == {"any_value": True}:
        return True, ""
    if "unknown" in expect and len(expect) == 1:
        return veq(expect, got["value"]), ""
    return veq(expect, got["value"]), ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--celgo", required=True)
    ap.add_argument("--profile", default="default")
    ap.add_argument("--simple", default=os.path.join(HERE, "simple"))
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--json", help="write a machine-readable report here")
    a = ap.parse_args()

    rows, fails, msgdiff = [], [], []
    for path in sorted(glob.glob(os.path.join(a.simple, "*.json"))):
        doc = json.load(open(path, encoding="utf-8"))
        tests = [(s["name"], t) for s in doc["sections"] for t in s["tests"]]
        use = [(s, t) for s, t in tests if usable(t)]
        reqs = [{"id": "%s/%s/%s" % (doc["name"], s, t["name"]), "expr": t["expr"],
                 "bindings": t["bindings"], "disable_macros": t["disable_macros"],
                 "disable_check": t["disable_check"]} for s, t in use]
        got = run_celgo(a.celgo, a.profile, reqs) if reqs else []
        ok = 0
        for (s, t), r, g in zip(use, reqs, got):
            passed, note = judge(t["expect"], g)
            if passed:
                ok += 1
                if note:
                    msgdiff.append((r["id"], t["expect"]["error"], g["error"]))
            else:
                fails.append((r["id"], t["expr"], t["expect"], g, note))
        rows.append((doc["name"], len(tests), len(use), ok))

    print("profile: %s" % a.profile)
    print("| file | total | usable | cel-go agrees |")
    print("|---|---:|---:|---:|")
    for r in rows:
        print("| %s | %d | %d | %d |" % r)
    tot = [sum(r[i] for r in rows) for i in (1, 2, 3)]
    print("| **total** | %d | %d | %d |" % tuple(tot))
    print("\n%d mismatches; %d passing error tests whose message differs"
          % (len(fails), len(msgdiff)))
    for fid, expr, exp, g, note in fails:
        gs = json.dumps(g.get("value")) if "value" in g else "error(%s): %s" % (
            g.get("stage"), g["error"].splitlines()[0])
        print("FAIL %s\n  expr:   %s\n  expect: %s\n  got:    %s%s"
              % (fid, expr, json.dumps(exp), gs, ("  [" + note + "]") if note else ""))
    if a.verbose:
        for fid, want, have in msgdiff:
            print("MSG  %s: want %r, got %r" % (fid, want, have.splitlines()[0]))
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"profile": a.profile, "rows": rows,
                       "fails": [{"id": x[0], "expr": x[1], "expect": x[2], "got": x[3],
                                  "note": x[4]} for x in fails]}, f, indent=1)


if __name__ == "__main__":
    main()
