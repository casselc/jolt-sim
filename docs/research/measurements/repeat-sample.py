#!/usr/bin/env python3
"""Run a benchmark command N times and report median and spread per label.

E9 found this host has a ~10% run-to-run spread, which made several
single-sample comparisons in PERTURB-DESIGN.md unresolvable. That floor was
discovered by accident. This exists so no measurement in this line is a single
sample by default.

Parses the `{:label :X ... :nanos-per-op N ...}` lines the profile scripts
print, aggregates per label, and reports median with min/max and the spread as
a percentage of the median. A comparison smaller than the spread is not a
result.

Usage:
    repeat-sample.py N -- <command...>
    repeat-sample.py 7 -- bin/jnc -A:bench -m profile
"""

import re
import statistics
import subprocess
import sys

LINE = re.compile(r":label\s+:?([A-Za-z0-9?!*+._-]+).*?:nanos-per-op\s+(\d+)")


def main() -> int:
    if "--" not in sys.argv:
        print(__doc__)
        return 2
    split = sys.argv.index("--")
    n = int(sys.argv[1])
    cmd = sys.argv[split + 1:]

    samples: dict[str, list[int]] = {}
    order: list[str] = []
    for run in range(n):
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            sys.stderr.write(f"run {run + 1} failed:\n{proc.stderr[-2000:]}\n")
            return 1
        for label, ns in LINE.findall(proc.stdout):
            if label not in samples:
                samples[label] = []
                order.append(label)
            samples[label].append(int(ns))
        print(f"  run {run + 1}/{n} done", file=sys.stderr)

    width = max((len(l) for l in order), default=10)
    print(f"\n{n} runs of: {' '.join(cmd)}\n")
    print(f"{'label'.ljust(width)}  {'median':>12} {'min':>12} {'max':>12}  spread")
    for label in order:
        xs = sorted(samples[label])
        med = statistics.median(xs)
        lo, hi = xs[0], xs[-1]
        spread = (hi - lo) / med * 100 if med else 0.0
        print(f"{label.ljust(width)}  {med:>12,.0f} {lo:>12,} {hi:>12,}  {spread:5.1f}%")
    print("\nA difference smaller than a label's spread is not a result.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
