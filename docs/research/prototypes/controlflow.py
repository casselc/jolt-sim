#!/usr/bin/env python3
"""Probe 1: does the capability-tier rule set survive branching control flow?

mode_checker.py checks straight-line operation sequences. Real programs branch
and loop, and that is where typestate normally stops being easy: two arms of an
`if` can leave a capability in different roles, and there is no single role to
carry forward.

This extends the checker to a small program form:

    ('op', name) | ('seq', [p...]) | ('if', p_then, p_else) | ('loop', p_body)

with the two standard judgements:

  IF   -- check both arms from the same environment; the join must AGREE.
          Disagreement is a static error: "the capability may or may not have
          been moved" is not a mode, and no sound successor typing exists.
  LOOP -- the body must be environment-preserving (env_in == env_out), the
          one-step fixpoint condition. A body that moves the capability
          cannot run twice.

Usage:  python3 controlflow.py
"""

from mode_checker import Env, RULES, ModeError, check


class JoinError(ModeError):
    pass


def check_prog(p, env: Env) -> Env:
    kind = p[0]
    if kind == "op":
        return check([p[1]], env)
    if kind == "seq":
        for sub in p[1]:
            env = check_prog(sub, env)
        return env
    if kind == "if":
        then_env = check_prog(p[1], env)
        else_env = check_prog(p[2], env)
        if then_env != else_env:
            raise JoinError(
                "if: arms disagree on capability state -- "
                f"then={then_env}, else={else_env}; no sound join"
            )
        return then_env
    if kind == "loop":
        out = check_prog(p[1], env)
        if out != env:
            raise JoinError(
                "loop: body is not environment-preserving -- "
                f"in={env}, out={out}; body cannot run twice"
            )
        return env
    raise ModeError(f"unknown program form {kind}")


def accepts(p, env: Env = Env()) -> bool:
    try:
        check_prog(p, env)
        return True
    except ModeError:
        return False


OP = lambda n: ("op", n)
SEQ = lambda *ps: ("seq", list(ps))
IF = lambda a, b: ("if", a, b)
LOOP = lambda b: ("loop", b)
NOP = SEQ()

LEASE = SEQ(OP("acquire_native"), OP("complete_native"), OP("release_native"))

CASES = [
    # (name, expected, program, start env, why)
    ("balanced-lease-in-both-arms", "accept",
     IF(LEASE, LEASE), Env(),
     "both arms return the capability unique@writer"),

    ("lease-in-one-arm-only", "accept",
     IF(LEASE, NOP), Env(),
     "a complete lease is env-neutral, so it joins with doing nothing"),

    ("move-in-one-arm-only", "reject",
     IF(OP("detach_result"), NOP), Env(),
     "then=result, else=writer -- 'maybe moved' is not a mode"),

    ("same-move-in-both-arms", "accept",
     IF(OP("detach_result"), OP("detach_result")), Env(),
     "both arms agree on result, so the join is exact"),

    ("different-moves-per-arm", "reject",
     IF(OP("detach_result"), OP("return_pool")), Env(),
     "result vs pool -- roles diverge"),

    ("dangling-borrow-in-one-arm", "reject",
     IF(OP("acquire_native"), NOP), Env(),
     "one arm leaves a live lease; the other does not"),

    ("loop-of-region-reads", "accept",
     SEQ(OP("move_to_region"), LOOP(OP("use_region"))), Env(),
     "use_region is env-preserving, so it iterates"),

    ("loop-that-moves", "reject",
     LOOP(OP("detach_result")), Env(),
     "writer->result is not env-preserving; a second iteration is illegal"),

    ("loop-of-balanced-leases", "accept",
     LOOP(LEASE), Env(),
     "acquire/complete/release returns to the entry environment"),

    ("loop-with-unreleased-lease", "reject",
     LOOP(SEQ(OP("acquire_native"), OP("complete_native"))), Env(),
     "the lease is still live at the back edge"),

    ("move-then-use-after-join", "accept",
     SEQ(IF(OP("move_to_region"), OP("move_to_region")), OP("use_region")), Env(),
     "an agreed join carries the role forward soundly"),

    ("use-after-divergent-join", "reject",
     SEQ(IF(OP("move_to_region"), NOP), OP("use_region")), Env(),
     "rejected at the join, before the use is even considered"),
]


def main() -> int:
    print("probe 1: branching control flow over the capability tier\n")
    failures = 0
    for name, expected, prog, env, why in CASES:
        got = "accept" if accepts(prog, env) else "reject"
        ok = got == expected
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {name}: {got}")
        print(f"          {why}")
    print(f"\n{len(CASES) - failures}/{len(CASES)} as expected"
          + ("" if not failures else f"; {failures} FAILED"))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
