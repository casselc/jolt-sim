#!/usr/bin/env python3
"""Probe 3, part 1: capability-tier REFINEMENTS over the sans-io decoder.

PERTURB-DESIGN.md Q2 records the mechanism for §2.2's capability tier as
liquid types -- HM inference plus refinements over QF-LIA -- and names two
remaining risks. One (`Any`) resolves by confinement: capabilities are minted
by operations, never parsed from data, so a capability is never `Any`. The
other is unresolved:

    "inference for higher-order and polymorphic-recursive code is where
     liquid typing is least comfortable in practice"

This file writes the refinements the real artifacts need. `higherorder.py`
attacks the driver; `transducer.py` attacks composition. What is modelled here
is `jolt.bytes/Window`, `jolt.bytes/Cursor`, `jolt.bytes/read-window` and
`jolt.bencode/decode`, taken from the sources, not from a summary.

WHAT THIS IS AND IS NOT
-----------------------
Types are HAND-ANNOTATED and CHECKED. Nothing is inferred. The question under
test is whether the discipline is EXPRESSIBLE and whether the obligations a
higher-order consumer generates are DISCHARGEABLE in the stated logic -- not
whether an inference engine would find them. Where an obligation needs
machinery beyond the stated logic, that is the result, and it is reported as
such rather than assumed away.

The solver is a self-contained decision procedure for the conjunctive fragment
(Gaussian elimination on equalities, then Fourier-Motzkin on the inequalities)
with DPLL-style splitting on disjunction. It is SOUND FOR VALIDITY and
INCOMPLETE: FM runs over the rationals, so a Q-infeasible conjunct is certainly
Z-infeasible and "valid" is never claimed wrongly, while a Q-feasible but
Z-infeasible conjunct is reported as a counterexample it is not. Every negative
result below was inspected by hand for that failure mode; none of them turns on
integrality.

THE ENCODING, AND ITS ONE DELIBERATE LIMIT
------------------------------------------
Objects are encoded field-wise into integer variables: a Window is
`w.backing`, `w.offset`, `w.length`, `w.capacity`; a Cursor is `c.window.*`
and `c.position`. `w.backing` is an integer standing for the identity of the
backing array (an Ackermannization of an uninterpreted sort), and `w.capacity`
is `capacity(w.backing)` with its congruence instance asserted wherever two
windows are stated equal.

So EQUALITY OF DESCRIPTORS IS STRUCTURAL. `same_cursor(a, b)` says the two
descriptors carry the same window and the same position. It does NOT say they
are the same object. jolt-bytes deliberately gives Cursor identity semantics
(`(equals [this other] (identical? this other))`) and jolt-bencode's docstring
says a failing decode returns "the identical original Cursor". That identity
claim is strictly stronger than anything a refinement over field values can
state -- see `CASES` entry `identity-is-not-a-refinement`, which is a real
solver query with a real negative answer.

Usage:  python3 refinement.py
"""

from __future__ import annotations

from dataclasses import dataclass
from fractions import Fraction
from typing import Callable, Iterable, Optional

# ---------------------------------------------------------------------------
# 1. Linear expressions
# ---------------------------------------------------------------------------


class Lin:
    """A linear combination over integer-sorted variables."""

    __slots__ = ("c", "k")

    def __init__(self, coeffs: Optional[dict] = None, const=0):
        self.c = {}
        for name, coeff in (coeffs or {}).items():
            coeff = Fraction(coeff)
            if coeff:
                self.c[name] = coeff
        self.k = Fraction(const)

    def __add__(self, other):
        other = _lin(other)
        c = dict(self.c)
        for name, coeff in other.c.items():
            c[name] = c.get(name, Fraction(0)) + coeff
        return Lin(c, self.k + other.k)

    __radd__ = __add__

    def __neg__(self):
        return Lin({n: -v for n, v in self.c.items()}, -self.k)

    def __sub__(self, other):
        return self + (-_lin(other))

    def __rsub__(self, other):
        return _lin(other) + (-self)

    def __mul__(self, scalar):
        scalar = Fraction(scalar)
        return Lin({n: v * scalar for n, v in self.c.items()}, self.k * scalar)

    __rmul__ = __mul__

    def is_const(self) -> bool:
        return not self.c

    def key(self):
        """Normalized signature, for deduplication."""
        if not self.c:
            return ((), self.k)
        pivot = abs(self.c[min(self.c)])
        scale = Fraction(1) / pivot
        return (
            tuple(sorted((n, v * scale) for n, v in self.c.items())),
            self.k * scale,
        )

    def __repr__(self):
        parts = [f"{v}*{n}" for n, v in sorted(self.c.items())]
        if self.k or not parts:
            parts.append(str(self.k))
        return " + ".join(parts)


def _lin(x) -> Lin:
    if isinstance(x, Lin):
        return x
    if isinstance(x, str):
        return Lin({x: 1})
    return Lin({}, x)


def V(name: str) -> Lin:
    return Lin({name: 1})


# ---------------------------------------------------------------------------
# 2. Formulas
# ---------------------------------------------------------------------------


class Formula:
    pass


class Ge(Formula):
    """e >= 0"""

    __slots__ = ("e",)

    def __init__(self, e):
        self.e = _lin(e)

    def __repr__(self):
        return f"({self.e} >= 0)"


class Eq(Formula):
    """e == 0"""

    __slots__ = ("e",)

    def __init__(self, e):
        self.e = _lin(e)

    def __repr__(self):
        return f"({self.e} == 0)"


class And(Formula):
    __slots__ = ("fs",)

    def __init__(self, fs: Iterable[Formula]):
        self.fs = tuple(fs)

    def __repr__(self):
        return "(" + " /\\ ".join(map(repr, self.fs)) + ")" if self.fs else "true"


class Or(Formula):
    __slots__ = ("fs",)

    def __init__(self, fs: Iterable[Formula]):
        self.fs = tuple(fs)

    def __repr__(self):
        return "(" + " \\/ ".join(map(repr, self.fs)) + ")" if self.fs else "false"


class Not(Formula):
    __slots__ = ("f",)

    def __init__(self, f):
        self.f = f

    def __repr__(self):
        return f"~{self.f}"


class Imp(Formula):
    __slots__ = ("a", "b")

    def __init__(self, a, b):
        self.a, self.b = a, b

    def __repr__(self):
        return f"({self.a} => {self.b})"


TRUE = And(())
FALSE = Or(())


def ge(a, b) -> Formula:
    return Ge(_lin(a) - _lin(b))


def gt(a, b) -> Formula:
    # integers: a > b  <=>  a - b - 1 >= 0
    return Ge(_lin(a) - _lin(b) - 1)


def le(a, b) -> Formula:
    return ge(b, a)


def lt(a, b) -> Formula:
    return gt(b, a)


def eq(a, b) -> Formula:
    return Eq(_lin(a) - _lin(b))


# ---------------------------------------------------------------------------
# 3. Decision procedure
# ---------------------------------------------------------------------------


class SolverLimit(Exception):
    """Fourier-Motzkin blew past the cap. Validity is NOT claimed."""


def _neg_literal(f: Formula) -> Formula:
    if isinstance(f, Ge):  # ~(e >= 0)  <=>  e <= -1  <=>  -e - 1 >= 0
        return Ge(-f.e - 1)
    if isinstance(f, Eq):
        return Or([Ge(f.e - 1), Ge(-f.e - 1)])
    raise TypeError(f)


def nnf(f: Formula, pos: bool = True) -> Formula:
    if isinstance(f, (Ge, Eq)):
        return f if pos else _neg_literal(f)
    if isinstance(f, And):
        sub = [nnf(x, pos) for x in f.fs]
        return And(sub) if pos else Or(sub)
    if isinstance(f, Or):
        sub = [nnf(x, pos) for x in f.fs]
        return Or(sub) if pos else And(sub)
    if isinstance(f, Not):
        return nnf(f.f, not pos)
    if isinstance(f, Imp):
        return nnf(Or([Not(f.a), f.b]), pos)
    raise TypeError(f)


def _subst(e: Lin, subs: dict) -> Lin:
    if not subs:
        return e
    out = Lin({}, e.k)
    for name, coeff in e.c.items():
        if name in subs:
            out = out + subs[name] * coeff
        else:
            out = out + Lin({name: coeff})
    return out


def _fourier_motzkin(ineqs, cap: int = 4000) -> bool:
    """True if the system {e >= 0} is feasible over Q. Sound for infeasibility
    over Z, which is the only direction validity depends on."""
    cur = list(ineqs)
    while True:
        keep, seen = [], set()
        for e in cur:
            if e.is_const():
                if e.k < 0:
                    return False
                continue
            k = e.key()
            if k not in seen:
                seen.add(k)
                keep.append(e)
        cur = keep
        names = set()
        for e in cur:
            names |= set(e.c)
        if not names:
            return True
        best, best_cost = None, None
        for v in sorted(names):
            lo = sum(1 for e in cur if e.c.get(v, 0) > 0)
            hi = sum(1 for e in cur if e.c.get(v, 0) < 0)
            cost = lo * hi
            if best_cost is None or cost < best_cost:
                best, best_cost = v, cost
        v = best
        lower = [e for e in cur if e.c.get(v, 0) > 0]
        upper = [e for e in cur if e.c.get(v, 0) < 0]
        rest = [e for e in cur if v not in e.c]
        nxt = list(rest)
        for l in lower:
            a = l.c[v]
            for u in upper:
                b = -u.c[v]
                nxt.append(l * b + u * a)
        if len(nxt) > cap:
            raise SolverLimit(f"Fourier-Motzkin exceeded {cap} constraints")
        cur = nxt


def _feasible(lits) -> bool:
    eqs = [x.e for x in lits if isinstance(x, Eq)]
    ges = [x.e for x in lits if isinstance(x, Ge)]
    subs: dict = {}
    for raw in eqs:
        e = _subst(raw, subs)
        if e.is_const():
            if e.k != 0:
                return False
            continue
        v = min(e.c)
        a = e.c[v]
        rest = Lin({n: c for n, c in e.c.items() if n != v}, e.k) * (Fraction(-1) / a)
        subs = {n: _subst(val, {v: rest}) for n, val in subs.items()}
        subs[v] = rest
    return _fourier_motzkin([_subst(e, subs) for e in ges])


def sat(f: Formula) -> bool:
    """f must be in NNF."""

    def go(work, lits):
        work = list(work)
        while work:
            g = work.pop()
            if isinstance(g, And):
                work.extend(g.fs)
            elif isinstance(g, Or):
                if not g.fs:
                    return False
                for alt in g.fs:
                    if go(work + [alt], lits):
                        return True
                return False
            else:
                lits = lits + [g]
                if not _feasible(lits):
                    return False
        return _feasible(lits)

    return go([f], [])


def valid(f: Formula) -> bool:
    """True only when f is genuinely valid. Never claims validity on a
    SolverLimit -- that propagates."""
    return not sat(nnf(Not(f)))


# ---------------------------------------------------------------------------
# 4. The refinements the artifacts need
# ---------------------------------------------------------------------------
#
# jolt.bytes:
#   Window { backing, offset, length | 0 <= offset, 0 <= length,
#                                      offset + length <= capacity(backing) }
#   Cursor { window, position        | 0 <= position <= window.length }
#
# The Window bound is the one `require-window-bounds!` enforces dynamically
# (0 <= start <= end <= capacity), rewritten in the deftype's own
# (offset, length) form. The Cursor bound is `cursor`'s
# (<= 0 position) / (<= position (count value)).


def WF_WINDOW(w: str) -> Formula:
    return And([
        ge(f"{w}.offset", 0),
        ge(f"{w}.length", 0),
        le(V(f"{w}.offset") + V(f"{w}.length"), f"{w}.capacity"),
    ])


def WF_CURSOR(c: str) -> Formula:
    return And([
        WF_WINDOW(f"{c}.window"),
        ge(f"{c}.position", 0),
        le(f"{c}.position", f"{c}.window.length"),
    ])


def SAME_BACKING(a: str, b: str) -> Formula:
    # backing identity, plus the congruence instance capacity(x) = capacity(y).
    return And([eq(f"{a}.backing", f"{b}.backing"),
                eq(f"{a}.capacity", f"{b}.capacity")])


def SAME_WINDOW(a: str, b: str) -> Formula:
    return And([SAME_BACKING(a, b),
                eq(f"{a}.offset", f"{b}.offset"),
                eq(f"{a}.length", f"{b}.length")])


def SAME_CURSOR(a: str, b: str) -> Formula:
    return And([SAME_WINDOW(f"{a}.window", f"{b}.window"),
                eq(f"{a}.position", f"{b}.position")])


def CONTAINED(child: str, parent: str) -> Formula:
    """The jolt-bytes slice-containment obligation, as a refinement."""
    return And([
        SAME_BACKING(child, parent),
        ge(f"{child}.offset", f"{parent}.offset"),
        le(V(f"{child}.offset") + V(f"{child}.length"),
           V(f"{parent}.offset") + V(f"{parent}.length")),
    ])


# --- status tags -----------------------------------------------------------

OK, NEED_MORE, INVALID = 0, 1, 2
INSUFFICIENT = 1          # read-window's second status
SOME, NONE = 0, 1         # the driver's refill result


def STATUS(r: str, tag: int) -> Formula:
    return eq(f"{r}.status", tag)


# --- jolt.bytes/read-window ------------------------------------------------


def READ_WINDOW_CONTRACT(c: str, n, r: str, buggy: bool = False) -> Formula:
    """(read-window cursor size) -> {:status :ok :window child :cursor next}
                                  | {:status :insufficient-input
                                     :window nil :cursor original}

    `buggy` reproduces the acceptance test the source explicitly warns against
    -- comparing size to the window LENGTH rather than to the REMAINING bytes
    ("Acceptance compares `size` to remaining before adding it to the
    position, so a fixed-width runtime cannot turn an oversized request into a
    false success")."""
    n = _lin(n)
    accept = (le(n, f"{c}.window.length") if buggy
              else le(n, V(f"{c}.window.length") - V(f"{c}.position")))
    return And([
        Or([STATUS(r, OK), STATUS(r, INSUFFICIENT)]),
        Imp(STATUS(r, OK), And([
            accept,
            SAME_BACKING(f"{r}.window", f"{c}.window"),
            eq(f"{r}.window.offset", V(f"{c}.window.offset") + V(f"{c}.position")),
            eq(f"{r}.window.length", n),
            SAME_WINDOW(f"{r}.cursor.window", f"{c}.window"),
            eq(f"{r}.cursor.position", V(f"{c}.position") + n),
        ])),
        Imp(STATUS(r, INSUFFICIENT), SAME_CURSOR(f"{r}.cursor", c)),
    ])


# --- the step-function contract (jolt.bencode/decode) ----------------------


def STEP_CONTRACT(c: str, r: str,
                  progress: int = 1,
                  preserve_window: bool = True,
                  bound_result: bool = True,
                  preserve_on_need: bool = True) -> Formula:
    """(decode cursor) -> {:status :ok :value v :cursor next}
                        | {:status :need-more          :cursor original}
                        | {:status :invalid :reason r :offset o :cursor original}

    The knobs exist so `higherorder.py` can weaken exactly one clause at a
    time and see which of the driver's obligations that breaks. Default is the
    real contract; `progress=2` is jolt-bencode's own stronger guarantee (the
    shortest bencode value, `le`/`de`/`0:`, is two bytes)."""
    ok_clauses = [gt(f"{r}.cursor.position", V(f"{c}.position") + (progress - 1))]
    if preserve_window:
        ok_clauses.append(SAME_WINDOW(f"{r}.cursor.window", f"{c}.window"))
    if bound_result:
        ok_clauses.append(le(f"{r}.cursor.position", f"{c}.window.length"))
    need = (SAME_CURSOR(f"{r}.cursor", c) if preserve_on_need
            else And([SAME_WINDOW(f"{r}.cursor.window", f"{c}.window"),
                      ge(f"{r}.cursor.position", f"{c}.position"),
                      le(f"{r}.cursor.position", f"{c}.window.length")]))
    return And([
        Or([STATUS(r, OK), STATUS(r, NEED_MORE), STATUS(r, INVALID)]),
        Imp(STATUS(r, OK), And(ok_clauses)),
        Imp(STATUS(r, NEED_MORE), need),
        Imp(STATUS(r, INVALID), And([
            SAME_CURSOR(f"{r}.cursor", c),
            ge(f"{r}.offset", 0),
            le(f"{r}.offset", f"{c}.window.length"),
        ])),
    ])


# ---------------------------------------------------------------------------
# 5. Refined types, subtyping, and a VC-collecting context
# ---------------------------------------------------------------------------


@dataclass
class Base:
    """{ v : sort | ref(v) }  -- `ref` is HOAS: it takes the path naming the
    value, so enclosing binders are captured by Python closure."""
    sort: str
    ref: Callable[[str], Formula]


@dataclass
class DFun:
    """(x : arg) -> res(x)  -- a DEPENDENT function type. The binder is what
    lets a result refinement mention the argument, which is exactly what the
    transactional property needs; see the report."""
    binder: str
    arg: object
    res: Callable[[str], object]


CursorT = Base("Cursor", WF_CURSOR)
WindowT = Base("Window", WF_WINDOW)
NatT = Base("Int", lambda v: ge(v, 0))


def StepT(**kw) -> DFun:
    return DFun("c", CursorT,
                lambda c: Base("StepResult", lambda r: STEP_CONTRACT(c, r, **kw)))


def subtype(env: Formula, sub_t, sup_t, path: str, out: list, label: str) -> None:
    """Standard refinement subtyping. Contravariant in the argument,
    covariant in the result, with the argument's binder in scope for the
    result -- the rule that makes a dependent function type usable."""
    if isinstance(sub_t, Base) and isinstance(sup_t, Base):
        assert sub_t.sort == sup_t.sort, (sub_t.sort, sup_t.sort)
        out.append((f"{label} @{path}",
                    Imp(And([env, sub_t.ref(path)]), sup_t.ref(path))))
    elif isinstance(sub_t, DFun) and isinstance(sup_t, DFun):
        a = f"{path}#arg"
        subtype(env, sup_t.arg, sub_t.arg, a, out, f"{label}/arg(contra)")
        env2 = And([env, sup_t.arg.ref(a)])
        subtype(env2, sub_t.res(a), sup_t.res(a), f"{path}#res", out,
                f"{label}/res(co)")
    else:
        raise TypeError("cannot compare a base type with a function type")


class Ctx:
    """A typing context that accumulates hypotheses and discharges goals."""

    def __init__(self, name: str, hyps=None, log=None):
        self.name = name
        self.hyps = list(hyps or [])
        self.log = log if log is not None else []

    def assume(self, f: Formula) -> None:
        self.hyps.append(f)

    def branch(self, label: str) -> "Ctx":
        return Ctx(f"{self.name}/{label}", self.hyps, self.log)

    def prove(self, label: str, goal: Formula) -> bool:
        ok = valid(Imp(And(self.hyps), goal))
        self.log.append((f"{self.name}: {label}", ok))
        return ok

    def apply_fun(self, ftype: DFun, argpath: str, respath: str,
                  label: str) -> None:
        """The dependent application rule: check the argument against the
        declared domain, then assume the result refinement with the binder
        instantiated to the actual argument path."""
        self.prove(f"{label}: argument {argpath} satisfies domain",
                   ftype.arg.ref(argpath))
        rt = ftype.res(argpath)
        assert isinstance(rt, Base)
        self.assume(rt.ref(respath))


def report(title: str, log) -> bool:
    all_ok = all(ok for _, ok in log)
    for lbl, ok in log:
        if not ok:
            print(f"        - unmet: {lbl}")
    return all_ok


# ---------------------------------------------------------------------------
# 6. First-order cases
# ---------------------------------------------------------------------------
#
# These establish the baseline: the E3 obligations that the design says are
# already refinement-shaped really are dischargeable here, and a known-bad
# variant of each is rejected. Nothing higher-order yet.


def case_read_window_ok(buggy=False):
    ctx = Ctx("read-window")
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(ge("n", 0))
    ctx.assume(READ_WINDOW_CONTRACT("c", "n", "r", buggy=buggy))
    ok = ctx.branch("ok")
    ok.assume(STATUS("r", OK))
    ok.prove("child window is well-formed", WF_WINDOW("r.window"))
    ok.prove("child window is contained in the parent",
             CONTAINED("r.window", "c.window"))
    ok.prove("next cursor is well-formed", WF_CURSOR("r.cursor"))
    ins = ctx.branch("insufficient")
    ins.assume(STATUS("r", INSUFFICIENT))
    ins.prove("returned cursor is well-formed", WF_CURSOR("r.cursor"))
    ins.prove("returned cursor did not move",
              eq("r.cursor.position", "c.position"))
    return ctx.log


def case_decode_transactional():
    ctx = Ctx("decode")
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(STEP_CONTRACT("c", "r", progress=2))
    ok = ctx.branch("ok")
    ok.assume(STATUS("r", OK))
    ok.prove("next cursor is well-formed", WF_CURSOR("r.cursor"))
    ok.prove("consumption is strictly positive", gt("r.cursor.position", "c.position"))
    for tag, name in ((NEED_MORE, "need-more"), (INVALID, "invalid")):
        b = ctx.branch(name)
        b.assume(STATUS("r", tag))
        b.prove("nothing was consumed", eq("r.cursor.position", "c.position"))
        b.prove("the same window is returned",
                SAME_WINDOW("r.cursor.window", "c.window"))
        b.prove("returned cursor is well-formed", WF_CURSOR("r.cursor"))
    return ctx.log


def case_identity_is_not_a_refinement():
    """Add a ghost field `id` standing for object identity and ask whether the
    strongest statement the refinement logic can make about the failing cursor
    -- structural equality -- implies it. It does not."""
    ctx = Ctx("identity")
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(SAME_CURSOR("r.cursor", "c"))
    ctx.prove("structural equality implies object identity",
              eq("r.cursor.id", "c.id"))
    return ctx.log


def case_decode_subsumes_step():
    out = []
    subtype(TRUE, StepT(progress=2), StepT(progress=1), "step", out,
            "bencode/decode <: Step")
    return [(lbl, valid(g)) for lbl, g in out]


def case_step_that_advances_on_need_more():
    out = []
    subtype(TRUE, StepT(preserve_on_need=False), StepT(), "step", out,
            "advancing-on-need-more <: Step")
    return [(lbl, valid(g)) for lbl, g in out]


def case_step_without_progress():
    """progress=0 means :ok may consume nothing. Not a subtype of the
    contract the driver relies on."""
    out = []
    subtype(TRUE, StepT(progress=0), StepT(progress=1), "step", out,
            "no-progress <: Step")
    return [(lbl, valid(g)) for lbl, g in out]


CASES = [
    ("read-window: bounds, containment, transactional cursor", "types",
     lambda: case_read_window_ok(buggy=False),
     "the jolt-bytes obligations, as refinements over QF-LIA"),

    ("read-window with the size test the source warns against", "fails",
     lambda: case_read_window_ok(buggy=True),
     "size <= length instead of size <= length - position: the next cursor "
     "escapes its window"),

    ("decode: the transactional trichotomy", "types",
     case_decode_transactional,
     "on non-:ok the position is unchanged and the window is the same"),

    ("bencode/decode <: Step (stronger progress)", "types",
     case_decode_subsumes_step,
     "refinement subtyping, covariant in the result"),

    ("a step that advances on :need-more is not a Step", "fails",
     case_step_that_advances_on_need_more,
     "the transactional clause is load-bearing and checkable"),

    ("a step with no progress guarantee is not a Step", "fails",
     case_step_without_progress,
     ":ok consuming zero bytes fails the subtype check"),

    ("identity-is-not-a-refinement", "fails",
     case_identity_is_not_a_refinement,
     "KNOWN GAP: 'the identical original Cursor' is stronger than structural "
     "equality; see the report"),
]


def run_cases(title: str, cases) -> int:
    print(title)
    print()
    failures = 0
    for name, expected, thunk, why in cases:
        try:
            log = thunk()
            got = "types" if all(ok for _, ok in log) else "fails"
        except SolverLimit as exc:
            got, log = f"UNKNOWN ({exc})", []
        ok = got == expected
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {name}: expected {expected}, got {got}")
        print(f"          {why}")
        if got == "fails":
            for lbl, good in log:
                if not good:
                    print(f"          unmet: {lbl}")
    print(f"\n{len(cases) - failures}/{len(cases)} as expected"
          + ("" if not failures else f"; {failures} FAILED"))
    return 1 if failures else 0


def _solver_selftest() -> int:
    checks = [
        ("x >= 0 => x + 1 >= 1", valid(Imp(ge("x", 0), ge(V("x") + 1, 1))), True),
        ("x >= 0 => x - 1 >= 0", valid(Imp(ge("x", 0), ge(V("x") - 1, 0))), False),
        ("x <= y /\\ y <= z => x <= z",
         valid(Imp(And([le("x", "y"), le("y", "z")]), le("x", "z"))), True),
        ("x > 0 => x >= 1 (integers)", valid(Imp(gt("x", 0), ge("x", 1))), True),
        ("disjunction", valid(Imp(Or([eq("x", 1), eq("x", 2)]), ge("x", 1))), True),
    ]
    bad = 0
    for label, got, want in checks:
        if got != want:
            bad += 1
            print(f"  [FAIL] solver self-test: {label}")
    if not bad:
        print(f"  [ok ] solver self-test: {len(checks)}/{len(checks)}")
    return bad


def main() -> int:
    print("probe 3: capability-tier refinements over the sans-io decoder")
    print("types are hand-annotated and CHECKED; nothing is inferred\n")
    bad = _solver_selftest()
    print()
    return (1 if bad else 0) | run_cases(
        "first-order obligations (the E3 surface)", CASES)


if __name__ == "__main__":
    raise SystemExit(main())
