#!/usr/bin/env python3
"""Probe 3, part 3: TRANSDUCERS over a capability-carrying pipeline.

The second place PERTURB-DESIGN.md Q2's unresolved risk lands. Charter §1.2 H4
(adopted unchanged in §15.3) makes transducers the composable primitive and
eager `into`/`transduce` drivers the canonical consumers. A transducer is a
function from one step/reducer to another, so a transducer over a pipeline
whose elements carry capability refinements composes REFINED transformations.

The driver (higherorder.py) turned out to be higher-order at a FIXED
refinement: it is checked once against the step parameter's declared contract
and every concrete step reaches it by subsumption. A transducer is not, and
this file measures exactly where that breaks and what closes it.

The instrument is a refinement variable with a declared ARITY:

    RefVar("p", 1)   p :: Elem -> Bool
    RefVar("p", 2)   p :: Cursor -> Elem -> Bool

Arity is the load-bearing parameter. A zero-copy element -- a Window sliced
out of the input buffer -- has a refinement that mentions the STEP'S OWN
cursor binder, which an arity-1 predicate cannot see.

Usage:  python3 transducer.py
"""

from __future__ import annotations

from refinement import (
    INVALID, NEED_MORE, OK, And, Base, Ctx, CursorT, DFun, Formula, Imp, Or,
    CONTAINED, SAME_BACKING, SAME_CURSOR, STATUS, STEP_CONTRACT, TRUE, V,
    WF_CURSOR, WF_WINDOW, eq, ge, gt, le, run_cases, subtype, valid,
)


# ---------------------------------------------------------------------------
# Refinement variables
# ---------------------------------------------------------------------------


class RefinementArityError(Exception):
    """A predicate hole was asked for something its arity cannot express."""


class UnresolvedRefinement(Exception):
    """A refinement variable reached the solver uninstantiated."""


class RefVar:
    """An abstract refinement (LiquidHaskell's `forall <p :: ... -> Bool>`).

    Instantiation is BY HAND here. Inferring these is Horn-clause constraint
    solving over predicate variables, which is what liquid inference does and
    what HM unification does not; not attempted, and that is stated rather
    than papered over."""

    def __init__(self, name: str, arity: int):
        self.name, self.arity, self.pred = name, arity, None

    def instantiate(self, fn):
        n = fn.__code__.co_argcount
        if n != self.arity:
            raise RefinementArityError(
                f"refinement variable {self.name} has arity {self.arity}; "
                f"the predicate needs {n} argument(s). An arity-{self.arity} "
                f"hole cannot mention the step's cursor binder.")
        self.pred = fn
        return self

    def __call__(self, *args) -> Formula:
        if self.pred is None:
            raise UnresolvedRefinement(
                f"refinement variable {self.name} was never instantiated; "
                f"HM unification has nothing to solve it with")
        if len(args) != self.arity:
            raise RefinementArityError(
                f"refinement variable {self.name} applied to {len(args)} "
                f"argument(s) but has arity {self.arity}")
        return self.pred(*args)


def at(rv: RefVar):
    """Adapt a refinement variable to the uniform (cursor, value) shape the
    step type needs. An arity-1 hole simply drops the cursor -- which is the
    whole problem for zero-copy elements."""
    if rv.arity == 1:
        return lambda c, v: rv(v)
    return lambda c, v: rv(c, v)


# ---------------------------------------------------------------------------
# A step whose element carries a refinement
# ---------------------------------------------------------------------------


def StepOfT(elem, **kw) -> DFun:
    """Step[{v | elem(c, v)}] -- the trichotomy contract of refinement.py,
    plus an element refinement that may mention the step's own cursor
    binder `c`."""
    return DFun("c", CursorT, lambda c: Base("StepResult", lambda r: And([
        STEP_CONTRACT(c, r, **kw),
        Imp(STATUS(r, OK), elem(c, f"{r}.value")),
    ])))


# --- concrete element refinements ------------------------------------------

NONEMPTY = lambda w: ge(f"{w}.length", 1)
AT_LEAST_4 = lambda w: ge(f"{w}.length", 4)
ANY = lambda w: TRUE

# The zero-copy element: the decoded value IS a sub-Window of the input
# buffer, not a copy out of it. Its refinement mentions the step's cursor.
#
# jolt-bencode does NOT do this today -- `decode-utf8` copies out to a String,
# which is why every element on the v0 path is ordinary-tier. It is the
# direction §8/E7 and §12/E11's performance findings push toward.
ZERO_COPY = lambda c, w: And([
    WF_WINDOW(w),
    SAME_BACKING(w, f"{c}.window"),
    ge(f"{w}.offset", V(f"{c}.window.offset") + V(f"{c}.position")),
    le(V(f"{w}.offset") + V(f"{w}.length"),
       V(f"{c}.window.offset") + V(f"{c}.window.length")),
])


# ---------------------------------------------------------------------------
# mapStep : (f : A -> B) -> Step[A] -> Step[B]
# ---------------------------------------------------------------------------


def check_map_step(elem_in, elem_out, name: str, extra=None) -> list:
    """Type the body of

        mapStep f s = \\c -> case s c of
                              :ok(a, c') -> :ok(f a, c')
                              other      -> other

    against  StepOf(elem_in) -> StepOf(elem_out), given
    f : {a | elem_in(c, a)} -> {b | elem_out(c, b)}.

    Note where `c` comes from in f's type: it is the STEP'S binder. That is
    the dependency the arity cases below are about."""
    log: list = []
    ctx = Ctx(name, log=log)
    ctx.assume(WF_CURSOR("c"))
    ctx.apply_fun(StepOfT(elem_in), "c", "r", "s(c)")
    # the wrapper rebuilds the result, keeping status/cursor/offset
    ctx.assume(And([eq("r2.status", "r.status"),
                    SAME_CURSOR("r2.cursor", "r.cursor"),
                    eq("r2.offset", "r.offset")]))
    ctx.prove("wrapper preserves the trichotomy contract",
              STEP_CONTRACT("c", "r2"))
    ok = ctx.branch("ok")
    ok.assume(STATUS("r", OK))
    f_type = DFun("a", Base("Elem", lambda a: elem_in("c", a)),
                  lambda a: Base("Elem", lambda b: elem_out("c", b)))
    ok.apply_fun(f_type, "r.value", "r2.value", "f(a)")
    ok.prove("output element satisfies the declared refinement",
             elem_out("c", "r2.value"))
    if extra is not None:
        extra(ok)
    return log


def use_at(declared_in, declared_out, actual_in, actual_out, label) -> list:
    """Use a transducer DECLARED at (declared_in -> declared_out) where the
    caller supplies a step refined by actual_in and needs actual_out."""
    out: list = []
    subtype(TRUE, StepOfT(actual_in), StepOfT(declared_in), "arg", out,
            f"{label}/supplied step <: declared domain")
    subtype(TRUE, StepOfT(declared_out), StepOfT(actual_out), "res", out,
            f"{label}/declared codomain <: needed")
    return [(lbl, valid(g)) for lbl, g in out]


def guard(thunk):
    """Run a check, turning a refinement-system error into a recorded
    failure rather than a crash."""
    def go():
        try:
            return thunk()
        except (RefinementArityError, UnresolvedRefinement) as exc:
            return [(f"{type(exc).__name__}: {exc}", False)]
    return go


# --- instantiations --------------------------------------------------------


def mono_reuse():
    """One monomorphic mapStep, declared at NONEMPTY -> NONEMPTY, reused where
    the caller needs AT_LEAST_4 out."""
    e = lambda p: (lambda c, v: p(v))
    return use_at(e(NONEMPTY), e(NONEMPTY), e(AT_LEAST_4), e(AT_LEAST_4),
                  "monomorphic mapStep")


def poly_reuse():
    """The same use, with an arity-1 abstract refinement instantiated at the
    use site."""
    p = RefVar("p", 1).instantiate(lambda w: ge(f"{w}.length", 4))
    q = RefVar("q", 1).instantiate(lambda w: ge(f"{w}.length", 4))
    e = lambda p_: (lambda c, v: p_(v))
    return use_at(at(p), at(q), e(AT_LEAST_4), e(AT_LEAST_4),
                  "abstract-refinement mapStep")


def zero_copy_arity1():
    """Try to give the zero-copy element refinement to an arity-1 hole."""
    p = RefVar("p", 1).instantiate(ZERO_COPY)   # raises: needs 2 arguments
    return check_map_step(at(p), at(p), "mapStep<p:1>")


def zero_copy_arity2():
    p = RefVar("p", 2).instantiate(ZERO_COPY)
    q = RefVar("q", 2).instantiate(ZERO_COPY)
    return check_map_step(
        at(p), at(q), "mapStep<p,q:2>",
        # a downstream obligation with real content: the element the
        # transducer hands on is still contained in the caller's window.
        extra=lambda ok: ok.prove("output element is contained in the input",
                                  CONTAINED("r2.value", "c.window")))


def zero_copy_first_order():
    """The SAME zero-copy element, consumed by a first-order operation
    instead of a combinator. This is §2.2's confinement claim under test:
    no refinement variable, no abstraction, nothing higher-order."""
    ctx = Ctx("first-order consumer")
    ctx.assume(WF_CURSOR("c"))
    ctx.apply_fun(StepOfT(ZERO_COPY), "c", "r", "s(c)")
    ok = ctx.branch("ok")
    ok.assume(STATUS("r", OK))
    consumer = DFun("w", Base("Window", lambda w: CONTAINED(w, "c.window")),
                    lambda w: Base("Unit", lambda u: TRUE))
    ok.apply_fun(consumer, "r.value", "u", "consume(w)")
    return ctx.log


def uninstantiated():
    p = RefVar("p", 1)
    return check_map_step(at(p), at(p), "mapStep<p uninstantiated>")


def polymorphic_driver(bounded: bool):
    """Can the DRIVER be polymorphic in the step's refinement?

    higherorder.py checks the driver once against a FIXED step contract. The
    alternative is `drive : forall <s :: Cursor -> Res -> Bool>. Step<s> -> ...`.
    An unbounded quantifier must hold at EVERY instance, including the weakest
    one (`true`) -- so this is the honest test of whether the fixed contract
    was an accident."""
    s = RefVar("s", 2).instantiate(
        (lambda c, r: STEP_CONTRACT(c, r)) if bounded else (lambda c, r: TRUE))
    ctx = Ctx("drive<s>" + ("[s <= StepContract]" if bounded else "[unbounded]"))
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(s("c", "r"))
    ctx.assume(STATUS("r", OK))
    ctx.prove("recursive call takes a well-formed Cursor", WF_CURSOR("r.cursor"))
    return ctx.log


def compose(q_first, p_second, label):
    """xf1 : ... -> StepOf<q_first>   composed with   xf2 : StepOf<p_second> -> ...
    is well-typed exactly when StepOf<q_first> <: StepOf<p_second>."""
    out: list = []
    e = lambda p_: (lambda c, v: p_(v))
    subtype(TRUE, StepOfT(e(q_first)), StepOfT(e(p_second)), "mid", out, label)
    return [(lbl, valid(g)) for lbl, g in out]


# ---------------------------------------------------------------------------
# Two shapes the confinement claim predicts should be EASY
# ---------------------------------------------------------------------------


def stateful_take(n_ok: bool):
    """`take n` -- a stateful transducer whose state is ordinary-tier.
    Invariant 0 <= k <= n, preserved by the step. `n_ok=False` drops the
    guard, so the invariant breaks."""
    ctx = Ctx("take")
    ctx.assume(ge("n", 0))
    ctx.assume(And([ge("k", 0), le("k", "n")]))
    if n_ok:
        ctx.assume(gt("n", "k"))                 # the emit guard k < n
    ctx.assume(eq("k2", V("k") + 1))
    ctx.prove("invariant preserved", And([ge("k2", 0), le("k2", "n")]))
    return ctx.log


def capability_accumulator(check_room: bool):
    """An eager `into` driver whose ACCUMULATOR is a capability: a unique
    output buffer with 0 <= used <= capacity. The reducing step appends a
    window's worth of bytes."""
    ctx = Ctx("into")
    ctx.assume(And([ge("acc.used", 0), le("acc.used", "acc.capacity")]))
    ctx.assume(WF_WINDOW("w"))
    if check_room:
        ctx.assume(le(V("acc.used") + V("w.length"), "acc.capacity"))
    ctx.assume(eq("acc2.used", V("acc.used") + V("w.length")))
    ctx.assume(eq("acc2.capacity", "acc.capacity"))
    ctx.prove("accumulator invariant preserved",
              And([ge("acc2.used", 0), le("acc2.used", "acc2.capacity")]))
    return ctx.log


CASES = [
    ("mapStep over an ORDINARY-tier element", "types",
     lambda: check_map_step(lambda c, v: TRUE, lambda c, v: TRUE,
                            "mapStep<unrefined>"),
     "nothing to compose: the element refinement is `true`. This is §2.2's "
     "confinement claim working exactly as stated"),

    ("mapStep at ONE fixed element refinement", "types",
     lambda: check_map_step(lambda c, v: NONEMPTY(v),
                            lambda c, v: NONEMPTY(v), "mapStep<fixed>"),
     "a monomorphic refined transducer checks fine -- for its one instance"),

    ("the same monomorphic mapStep reused at a second refinement", "fails",
     mono_reuse,
     "the declared codomain is not a subtype of what the caller needs; the "
     "element refinement is LOST through the combinator"),

    ("mapStep with an arity-1 abstract refinement, instantiated per use", "types",
     poly_reuse,
     "the refinement now travels through the combinator -- this is the "
     "machinery the previous case is missing"),

    ("zero-copy element with an arity-1 refinement variable", "fails",
     guard(zero_copy_arity1),
     "the element is a sub-Window of the INPUT, so its refinement mentions "
     "the step's own cursor binder, which an arity-1 hole cannot see"),

    ("zero-copy element with an arity-2 refinement variable", "types",
     guard(zero_copy_arity2),
     "p :: Cursor -> Window -> Bool, applied at the step's binder; the "
     "containment obligation downstream is discharged, so this is not vacuous"),

    ("zero-copy element consumed FIRST-ORDER (no combinator)", "types",
     guard(zero_copy_first_order),
     "the same refined capability, consumed directly: no refinement variable "
     "is needed at all. This is the confinement claim holding"),

    ("refinement variable left uninstantiated", "fails",
     guard(uninstantiated),
     "there is nothing for HM to unify: solving these is Horn-clause "
     "constraint solving over predicate variables, not type unification"),

    ("driver polymorphic in the step's refinement, UNBOUNDED", "fails",
     guard(lambda: polymorphic_driver(bounded=False)),
     "the quantifier must hold at its weakest instance (`true`), where the "
     "driver's own obligation is unprovable"),

    ("driver polymorphic in the step's refinement, BOUNDED by the contract",
     "types",
     guard(lambda: polymorphic_driver(bounded=True)),
     "a bounded quantifier collapses to the fixed contract plus subsumption, "
     "which is what higherorder.py already checks -- so the fixed-refinement "
     "driver is not an accident"),

    ("composing two transducers, refinements ordered", "types",
     lambda: compose(AT_LEAST_4, NONEMPTY, "xf1 ; xf2"),
     "the first's codomain implies the second's domain -- a QF-LIA query"),

    ("composing two transducers, refinements not ordered", "fails",
     lambda: compose(NONEMPTY, AT_LEAST_4, "xf1 ; xf2"),
     "composition imposes q ⊑ p' between predicate variables; with concrete "
     "instantiations it is decidable, uninstantiated it is a Horn constraint"),

    ("stateful transducer with ORDINARY-tier state (`take n`)", "types",
     lambda: stateful_take(n_ok=True),
     "0 <= k <= n is plain QF-LIA; no capability, no higher-order refinement"),

    ("`take n` without its emit guard", "fails",
     lambda: stateful_take(n_ok=False),
     "negative control: the invariant really is doing work"),

    ("eager `into` over a CAPABILITY accumulator", "types",
     lambda: capability_accumulator(check_room=True),
     "a refined capability threaded first-order through a reducer is fine -- "
     "the refinement is fixed, so no abstraction is needed"),

    ("the same accumulator without a room check", "fails",
     lambda: capability_accumulator(check_room=False),
     "negative control"),
]


def main() -> int:
    print("probe 3 / transducers: composing refined steps\n")
    return run_cases("transducers over a capability-carrying pipeline", CASES)


if __name__ == "__main__":
    raise SystemExit(main())
