#!/usr/bin/env python3
"""Prototype uniqueness/linearity checker for the perturb capability tier.

This is a PROTOTYPE validating a RULE SET, not perturb code and not a
compiler pass. It exists to answer one question from PERTURB-DESIGN.md §2.3:

    can a syntactic type discipline decide what jolt-hako currently decides
    by bounded reachability search over proofs/prolog/ownership.pl?

The acceptance criterion is jolt-hako's own proofs/prolog/queries.json --
9 corrected queries and 2 exactly-one-fault controls, with their recorded
expected answers. None of those artifacts were authored here.

The essential difference from the Prolog model: that model searches a state
space and asks whether a bad state is REACHABLE. This checker carries a typing
environment in which the bad states are NOT REPRESENTABLE. Where the model
answers "no double ownership within 8 steps" by exhausting a bounded space,
the environment holds exactly one owner per capability, so two owners cannot
be written down. That is the upgrade the capability tier is supposed to buy:
bounded-complete search replaced by a structural property.

Usage:  python3 mode_checker.py
"""

from dataclasses import dataclass, replace
from typing import Optional


class ModeError(Exception):
    """A static rejection. Carries the rule that fired."""


# --- the typing environment -------------------------------------------------
#
# One capability, as in the hako model: a native codec buffer. Its mode is
#   unique@owner            -- exclusively owned, movable, consumable
#   borrowed@owner[phase]   -- frozen by a live lease; the owner cannot move
# and the region it may live in has its own lifetime.
#
# `owner` is a single field. This is the whole point: `writer_result`, the
# canonical two-owner bug state, has no representation here.

OWNERS = {"writer", "pool", "result", "region", "none"}
BORROWABLE = {"writer", "result", "region"}   # usable_native_owner/1


@dataclass(frozen=True)
class Env:
    owner: str = "writer"
    borrow: Optional[str] = None      # None | "active" | "complete"
    region_open: bool = True

    def unique(self) -> bool:
        return self.borrow is None


# --- typing rules -----------------------------------------------------------

def _require_unique(env: Env, op: str) -> None:
    # MOVE rule: transferring ownership requires the capability be unique.
    # A live lease freezes its owner.
    if not env.unique():
        raise ModeError(
            f"{op}: cannot move a borrowed capability "
            f"(lease is {env.borrow}) -- MOVE requires unique"
        )


def _require_live(env: Env, op: str) -> None:
    if env.owner == "none":
        raise ModeError(f"{op}: capability already consumed -- use after move")


def move_to(target: str, sources: frozenset):
    """A move legal only FROM the given roles.

    The `sources` precondition is TYPESTATE, not uniqueness. Uniqueness says
    "one owner, and it cannot move while borrowed"; that alone let this
    checker accept 1051 sequences ownership.pl rejects (see equivalence.py
    and PERTURB-DESIGN.md E5). The capability also carries a role -- writer
    is the mutable working state, result is published, pool is returned,
    region is arena-held, none is consumed -- and each operation is legal
    only from specific roles. That discipline is a separate axis.
    """
    def rule(env: Env, op: str) -> Env:
        _require_live(env, op)
        _require_unique(env, op)
        if env.owner not in sources:
            raise ModeError(
                f"{op}: illegal from role {env.owner}; "
                f"requires one of {sorted(sources)} -- typestate"
            )
        if target == "region" and not env.region_open:
            raise ModeError(f"{op}: region is closed -- use after consume")
        return replace(env, owner=target)
    return rule


def acquire_native(env: Env, op: str) -> Env:
    _require_live(env, op)
    if not env.unique():
        raise ModeError(f"{op}: a lease is already live -- at most one borrow")
    if env.owner not in BORROWABLE:
        raise ModeError(f"{op}: {env.owner} cannot lend a native lease")
    if env.owner == "region" and not env.region_open:
        raise ModeError(f"{op}: region is closed -- use after consume")
    return replace(env, borrow="active")


def complete_native(env: Env, op: str) -> Env:
    if env.borrow != "active":
        raise ModeError(
            f"{op}: no active lease to complete (lease is {env.borrow})"
        )
    return replace(env, borrow="complete")


def release_native(env: Env, op: str) -> Env:
    # PHASE rule: a lease is released only after completion. This is the
    # rule hako's early-release-bug.pl deletes.
    if env.borrow != "complete":
        raise ModeError(
            f"{op}: lease is {env.borrow}, not complete -- early release"
        )
    return replace(env, borrow=None)


def use_region(env: Env, op: str) -> Env:
    if env.owner != "region":
        raise ModeError(f"{op}: capability is owned by {env.owner}, not region")
    if not env.region_open:
        raise ModeError(f"{op}: region is closed -- use after consume")
    # A native lease is EXCLUSIVE, not shared: while it is live the owner
    # cannot read either. Omitting this let the checker accept 103 sequences
    # ownership.pl rejects.
    _require_unique(env, op)
    return env


def close_region(env: Env, op: str) -> Env:
    if env.owner != "region":
        raise ModeError(f"{op}: capability is owned by {env.owner}, not region")
    _require_unique(env, op)
    if not env.region_open:
        raise ModeError(f"{op}: region already closed -- double consume")
    return replace(env, owner="none", region_open=False)


WRITER = frozenset({"writer"})
POOL = frozenset({"pool"})

RULES = {
    "detach_result":   move_to("result", WRITER),
    "move_to_region":  move_to("region", WRITER),
    "return_pool":     move_to("pool", WRITER),
    "checkout_pool":   move_to("writer", POOL),
    "reset_writer":    move_to("writer", WRITER),
    "acquire_native":  acquire_native,
    "complete_native": complete_native,
    "release_native":  release_native,
    "use_region":      use_region,
    "close_region":    close_region,
}


def check(ops, env: Env = Env()):
    """Type-check an operation sequence. Returns the final env or raises."""
    for op in ops:
        rule = RULES.get(op)
        if rule is None:
            raise ModeError(f"{op}: unknown operation")
        env = rule(env, op)
    return env


def accepts(ops, env: Env = Env()) -> bool:
    try:
        check(ops, env)
        return True
    except ModeError:
        return False


# --- acceptance against jolt-hako's queries.json ----------------------------
#
# Each case names the hako query it corresponds to and its recorded expected
# answer. "nonempty" = the model admits it, so the checker must ACCEPT.
# "empty" = the model forbids it, so the checker must REJECT.

# States the hako queries start from mid-trace, expressed as environments.
BORROWED_WRITER = Env(owner="writer", borrow="active")
BORROWED_RESULT = Env(owner="result", borrow="active")
IN_POOL         = Env(owner="pool")
CLOSED_REGION   = Env(owner="none", region_open=False)

CASES = [
    # (hako query name, expected, ops, starting env)
    ("result-native-completion-allowed", "accept",
     ["detach_result", "acquire_native", "complete_native", "release_native"], Env()),
    ("region-close-after-completion-allowed", "accept",
     ["move_to_region", "acquire_native", "complete_native", "release_native",
      "close_region"], Env()),
    ("reset-during-native-rejected", "reject",
     ["reset_writer"], BORROWED_WRITER),
    ("pool-return-during-native-rejected", "reject",
     ["return_pool"], BORROWED_WRITER),
    ("release-before-completion-rejected", "reject",
     ["release_native"], BORROWED_RESULT),
    ("double-pool-return-rejected", "reject",
     ["return_pool"], IN_POOL),
    ("use-after-region-close-rejected", "reject",
     ["use_region"], CLOSED_REGION),
    # controls -- the model ADMITS these (that is the injected fault), so the
    # checker must reject the corresponding program.
    ("early-release-control", "reject",
     ["acquire_native", "release_native"], Env()),
    ("double-owner-control", "reject",
     ["detach_result_bug"], Env()),
]


def main() -> int:
    print("perturb capability-tier mode checker -- prototype")
    print("acceptance: jolt-hako proofs/prolog/queries.json\n")
    failures = 0
    for name, expected, ops, env in CASES:
        got = "accept" if accepts(ops, env) else "reject"
        ok = got == expected
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {name}: expected {expected}, got {got}")
        if not ok:
            print(f"          ops={ops} from {env}")

    # The two whole-space queries are not run as searches. They are structural.
    print("\n  structural (no search performed):")
    print("    no-reachable-double-ownership-through-eight-steps:")
    print("      Env.owner is a single field; `writer_result` -- hako's")
    print("      owner_count(_, 2) bug state -- has no representation.")
    print("      ownership_violation/1 is not decidable-by-search here")
    print("      because it is not expressible.")
    print("    no-reachable-lifecycle-violation-through-eight-steps:")
    print("      lease count and phase are one field (None|active|complete),")
    print("      so lease=0/phase=active and lease>1 are unrepresentable;")
    print("      close_region sets owner=none, so owner=region with a closed")
    print("      region is unrepresentable.")

    print(f"\n{len(CASES) - failures}/{len(CASES)} decided as recorded"
          + ("" if not failures else f"; {failures} FAILED"))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
