#!/usr/bin/env python3
"""Probe 2: multiple capabilities, aliasing, and function boundaries.

mode_checker.py tracks ONE capability with no names. Real code holds several,
binds them, passes them to functions, and stores them in structures. Uniqueness
disciplines normally get hard at exactly these points, so this probe asks which
of them the E5 rule set already covers and which need machinery it lacks.

Environment: name -> Env (the per-capability mode from mode_checker), plus a
`moved` set recording names whose capability has been moved out. A moved name
is not merely stale -- using it is use-after-move.

Function signatures declare their effect on an argument:
    'consume'  -- takes ownership; the caller's name is moved out
    'borrow'   -- reads/writes for the call's duration; caller keeps ownership,
                  but the argument must be unique (no live lease) at the call
    'produce'  -- returns a fresh capability bound to the result name

Usage:  python3 multicap.py
"""

from dataclasses import replace

from mode_checker import Env, RULES, ModeError, check


class AliasError(ModeError):
    pass


class Store:
    def __init__(self):
        self.caps: dict[str, Env] = {}
        self.moved: set[str] = set()

    def live(self, name: str) -> Env:
        if name in self.moved:
            raise AliasError(f"{name}: use after move")
        if name not in self.caps:
            raise AliasError(f"{name}: unbound capability")
        return self.caps[name]

    def bind(self, name: str, env: Env) -> None:
        if name in self.caps and name not in self.moved:
            raise AliasError(f"{name}: rebinding would drop a live capability")
        self.caps[name] = env
        self.moved.discard(name)

    def move_out(self, name: str) -> Env:
        env = self.live(name)
        self.moved.add(name)
        return env


SIGS = {
    "encode_into":  "borrow",    # writes through the buffer, gives it back
    "publish":      "consume",   # takes ownership, caller loses it
    "fresh_buffer": "produce",
}


def run(prog, store: Store | None = None) -> Store:
    """prog is a list of instructions:
        ('new',  name)                fresh capability
        ('op',   name, opname)        a mode_checker operation on that capability
        ('alias', dst, src)           bind dst to src WITHOUT moving  (unsound)
        ('move', dst, src)            move src into dst
        ('call', fn, name)            call with declared signature
        ('call', fn, name, dst)       'produce' form, result bound to dst
    """
    store = store or Store()
    for ins in prog:
        kind = ins[0]
        if kind == "new":
            store.bind(ins[1], Env())
        elif kind == "op":
            _, name, opname = ins
            store.caps[name] = check([opname], store.live(name))
        elif kind == "alias":
            _, dst, src = ins
            # AFFINE BINDING. A plain alias duplicates a unique capability:
            # two names then move to different roles and reconstruct hako's
            # writer_result two-owner state. The E5 rule set cannot forbid it,
            # because that rule set has no names -- uniqueness has to be
            # enforced at the BINDING form, not only at operations. So there
            # is no non-moving bind for a capability.
            store.live(src)
            raise AliasError(
                f"alias {dst} = {src}: capabilities bind affinely; "
                f"a non-moving alias would duplicate a unique capability"
            )
        elif kind == "move":
            _, dst, src = ins
            store.bind(dst, store.move_out(src))
        elif kind == "call":
            fn, name = ins[1], ins[2]
            sig = SIGS[fn]
            if sig == "borrow":
                env = store.live(name)
                if not env.unique():
                    raise AliasError(
                        f"{fn}({name}): argument has a live lease; "
                        f"borrow requires unique")
            elif sig == "consume":
                store.move_out(name)
            elif sig == "produce":
                store.bind(ins[3], Env())
        else:
            raise ModeError(f"unknown instruction {kind}")
    return store


def accepts(prog) -> bool:
    try:
        run(prog)
        return True
    except ModeError:
        return False


CASES = [
    ("two-independent-capabilities", "accept", [
        ("new", "a"), ("new", "b"),
        ("op", "a", "detach_result"), ("op", "b", "return_pool")],
     "separate names carry separate modes; no interference"),

    ("move-then-use-source", "reject", [
        ("new", "a"), ("move", "b", "a"), ("op", "a", "detach_result")],
     "use after move on the source name"),

    ("move-then-use-target", "accept", [
        ("new", "a"), ("move", "b", "a"), ("op", "b", "detach_result")],
     "the target holds it after the move"),

    ("consume-then-use", "reject", [
        ("new", "a"), ("call", "publish", "a"), ("op", "a", "detach_result")],
     "a consuming call moves the capability out"),

    ("borrow-then-use", "accept", [
        ("new", "a"), ("call", "encode_into", "a"), ("op", "a", "detach_result")],
     "a borrowing call gives it back"),

    ("borrow-while-leased", "reject", [
        ("new", "a"), ("op", "a", "acquire_native"),
        ("call", "encode_into", "a")],
     "borrow requires unique; a native lease is live"),

    ("rebind-over-live", "reject", [
        ("new", "a"), ("new", "a")],
     "rebinding would silently drop a live capability"),

    # THE PROBE. Nothing in the E5 rule set forbids this.
    ("plain-alias-then-diverge", "reject", [
        ("new", "a"), ("alias", "b", "a"),
        ("op", "a", "detach_result"), ("op", "b", "return_pool")],
     "two names for one capability, moved to two different roles"),
]


def main() -> int:
    print("probe 2: multiple capabilities, aliasing, function boundaries\n")
    failures = 0
    for name, expected, prog, why in CASES:
        got = "accept" if accepts(prog) else "reject"
        ok = got == expected
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {name}: expected {expected}, got {got}")
        print(f"          {why}")
    print(f"\n{len(CASES) - failures}/{len(CASES)} as expected"
          + ("" if not failures else f"; {failures} FAILED"))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
