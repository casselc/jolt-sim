#!/usr/bin/env python3
"""Bounded equivalence: mode_checker.py vs jolt-hako's ownership.pl.

queries.json spot-checks 9 traces. The model itself reasons over
reachable_within(I, State, 8) -- the whole space to depth 8. Agreeing on 9
traces is therefore much weaker than agreeing with the model, so this
enumerates EVERY operation sequence up to depth 8 from the initial state and
compares the two verdicts.

`prolog_step` is a direct transcription of proofs/prolog/ownership.pl step/3.
It is the reference; mode_checker.py's rule set is what is under test.

A disagreement in either direction is a finding:
  model accepts, checker rejects  -> the rule set is too strict (false reject)
  model rejects, checker accepts  -> the rule set is UNSOUND (missed a bug)

Usage:  python3 equivalence.py [depth]
"""

import sys
from itertools import product

from mode_checker import Env, RULES, ModeError, check

# --- reference: a direct transcription of ownership.pl ----------------------
# state(BaseOwner, LeaseCount, NativePhase, RegionPhase)

USABLE_NATIVE_OWNER = {"writer", "result", "region"}
OPS = ["detach_result", "move_to_region", "return_pool", "checkout_pool",
       "reset_writer", "acquire_native", "complete_native", "release_native",
       "use_region", "close_region"]

PL_INITIAL = ("writer", 0, "idle", "open")


def prolog_step(op, s):
    """Return the successor state, or None where the Prolog clause has no match."""
    owner, lease, phase, region = s
    if op == "detach_result":
        if (owner, lease, phase) == ("writer", 0, "idle"):
            return ("result", 0, "idle", region)
    elif op == "move_to_region":
        if (owner, lease, phase, region) == ("writer", 0, "idle", "open"):
            return ("region", 0, "idle", "open")
    elif op == "return_pool":
        if (owner, lease, phase) == ("writer", 0, "idle"):
            return ("pool", 0, "idle", region)
    elif op == "checkout_pool":
        if (owner, lease, phase) == ("pool", 0, "idle"):
            return ("writer", 0, "idle", region)
    elif op == "reset_writer":
        if (owner, lease, phase) == ("writer", 0, "idle"):
            return ("writer", 0, "idle", region)
    elif op == "acquire_native":
        if lease == 0 and phase == "idle" and owner in USABLE_NATIVE_OWNER:
            if not (owner == "region" and region == "closed"):
                return (owner, 1, "active", region)
    elif op == "complete_native":
        if lease == 1 and phase == "active":
            return (owner, 1, "complete", region)
    elif op == "release_native":
        if lease == 1 and phase == "complete":
            return (owner, 0, "idle", region)
    elif op == "use_region":
        if (owner, lease, phase, region) == ("region", 0, "idle", "open"):
            return ("region", 0, "idle", "open")
    elif op == "close_region":
        if (owner, lease, phase, region) == ("region", 0, "idle", "open"):
            return ("none", 0, "idle", "closed")
    return None


def prolog_accepts(ops):
    s = PL_INITIAL
    for op in ops:
        s = prolog_step(op, s)
        if s is None:
            return False
    return True


def checker_accepts(ops):
    try:
        check(ops, Env())
        return True
    except ModeError:
        return False


def main() -> int:
    depth = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    # Only ops both sides know. mode_checker has exactly these.
    assert set(OPS) == set(RULES), set(OPS) ^ set(RULES)

    total = 0
    too_strict = []
    unsound = []
    accepted_by_both = 0

    # Enumerate over the reachable prefix tree rather than all 10^8 sequences:
    # once a prefix is rejected by BOTH, every extension is rejected by both,
    # so it cannot hide a disagreement. Prefixes rejected by only one side are
    # recorded as disagreements at that point.
    frontier = [()]
    for _ in range(depth):
        nxt = []
        for prefix in frontier:
            for op in OPS:
                ops = prefix + (op,)
                total += 1
                m = prolog_accepts(ops)
                c = checker_accepts(ops)
                if m and c:
                    accepted_by_both += 1
                    nxt.append(ops)
                elif m and not c:
                    too_strict.append(ops)
                elif c and not m:
                    unsound.append(ops)
        frontier = nxt

    print(f"bounded equivalence vs ownership.pl, depth {depth}")
    print(f"  sequences examined:        {total}")
    print(f"  accepted by both:          {accepted_by_both}")
    print(f"  model accepts, checker no: {len(too_strict)}  (too strict)")
    print(f"  checker accepts, model no: {len(unsound)}  (UNSOUND)")
    for label, xs in (("too strict", too_strict), ("unsound", unsound)):
        for ops in xs[:5]:
            print(f"    [{label}] {list(ops)}")
        if len(xs) > 5:
            print(f"    ... and {len(xs) - 5} more")
    ok = not too_strict and not unsound
    print("\n" + ("AGREES with the model on every sequence to this depth."
                  if ok else "DISAGREEMENT -- see above."))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
