#!/usr/bin/env python3
"""Probe 3, part 2: a sans-io DRIVER that takes the step function as an
argument.

This is the first of the two places PERTURB-DESIGN.md Q2's unresolved risk
lands. The driver is the glue pumping bytes between a byte source and a
decoder step function:

    drive(step, src, c) =
      case step(c) of
        :ok(v, c')     -> emit v; drive(step, src, c')
        :need-more     -> case refill(src, c) of
                            some(c'') -> drive(step, src, c'')
                            none      -> stop
        :invalid(_, o) -> stop

`step` is a PARAMETER. The driver does not know it is bencode. If the step
function's type carries capability refinements, the driver is higher-order
over refined types, and the question is whether its body can be typed knowing
only the step's DECLARED refinement.

Everything here is hand-annotated and checked, as in refinement.py. The
obligations are generated mechanically by `Ctx.apply_fun` (the dependent
application rule) and `Ctx.prove`; only the annotations are written by hand.

Usage:  python3 higherorder.py
"""

from __future__ import annotations

from refinement import (
    INVALID, NEED_MORE, NONE, OK, SOME, And, Base, Ctx, CursorT, DFun, Formula,
    Imp, Or, SAME_BACKING, SAME_WINDOW, STATUS, STEP_CONTRACT, StepT, TRUE,
    V, WF_CURSOR, WF_WINDOW, eq, ge, gt, le, lt, run_cases, subtype, valid,
)

# ---------------------------------------------------------------------------
# The byte source and its refill operation
# ---------------------------------------------------------------------------
#
# `refill` is the only thing in the loop that is not the step function. It
# reads more bytes and hands back a cursor over a LARGER window at the SAME
# position. The source's residual budget is threaded as a ghost variable
# (`src.remaining` / `q.src.remaining`) rather than as a linear argument --
# a modelling shortcut, stated so it is not mistaken for a result.


def RefillT(budget: bool) -> DFun:
    """(refill src c) -> {:status :some :cursor c'} | {:status :none}

    `budget=True` additionally declares that a successful refill strictly
    consumes the source. That clause is not needed to TYPE the driver; it is
    needed to prove the driver TERMINATES. See the termination cases."""

    def res(c: str):
        def ref(q: str) -> Formula:
            some = [
                WF_WINDOW(f"{q}.cursor.window"),
                eq(f"{q}.cursor.position", f"{c}.position"),
                ge(f"{q}.cursor.window.length", f"{c}.window.length"),
                ge(f"{q}.src.remaining", 0),
            ]
            if budget:
                some.append(lt(f"{q}.src.remaining", "src.remaining"))
            return And([
                Or([STATUS(q, SOME), STATUS(q, NONE)]),
                Imp(STATUS(q, SOME), And(some)),
                Imp(STATUS(q, NONE), ge(f"{q}.src.remaining", 0)),
            ])

        return Base("RefillResult", ref)

    return DFun("c", CursorT, res)


# ---------------------------------------------------------------------------
# Termination metrics
# ---------------------------------------------------------------------------


def CURSOR_METRIC(c: str):
    return V(f"{c}.window.length") - V(f"{c}.position")


def cursor_metric(rem_out, rem_in, c_out, c_in) -> Formula:
    return gt(CURSOR_METRIC(c_in), CURSOR_METRIC(c_out))


def lex_metric(rem_out, rem_in, c_out, c_in) -> Formula:
    """(source budget, bytes left in the window), lexicographic."""
    return Or([
        gt(rem_in, rem_out),
        And([eq(rem_in, rem_out), cursor_metric(rem_out, rem_in, c_out, c_in)]),
    ])


def no_metric(rem_out, rem_in, c_out, c_in) -> Formula:
    return TRUE


# ---------------------------------------------------------------------------
# The driver
# ---------------------------------------------------------------------------


def check_driver(step_type: DFun, refill_type: DFun, metric=no_metric,
                 name="drive") -> list:
    log: list = []
    ctx = Ctx(name, log=log)
    # the driver's own signature: drive : Step -> Source -> (c : Cursor) -> ...
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(ge("src.remaining", 0))

    # r = step(c)  -- dependent application; the binder is instantiated to `c`,
    # which is what lets the result refinement talk about the input cursor.
    ctx.apply_fun(step_type, "c", "r", "step(c)")

    ok = ctx.branch("ok")
    ok.assume(STATUS("r", OK))
    ok.prove("recursive call takes a well-formed Cursor", WF_CURSOR("r.cursor"))
    ok.assume(eq("src2.remaining", "src.remaining"))     # source untouched
    ok.prove("metric decreases", metric("src2.remaining", "src.remaining",
                                        "r.cursor", "c"))

    nm = ctx.branch("need-more")
    nm.assume(STATUS("r", NEED_MORE))
    nm.prove("retry starts from a well-formed Cursor", WF_CURSOR("r.cursor"))
    nm.prove("retry starts where we started", eq("r.cursor.position", "c.position"))
    nm.apply_fun(refill_type, "r.cursor", "q", "refill(src, c)")
    got = nm.branch("refilled")
    got.assume(STATUS("q", SOME))
    got.prove("recursive call takes a well-formed Cursor", WF_CURSOR("q.cursor"))
    got.prove("metric decreases", metric("q.src.remaining", "src.remaining",
                                         "q.cursor", "c"))

    inv = ctx.branch("invalid")
    inv.assume(STATUS("r", INVALID))
    inv.prove("reported offset is inside the window",
              And([ge("r.offset", 0), le("r.offset", "c.window.length")]))
    inv.prove("nothing was consumed", eq("r.cursor.position", "c.position"))
    return log


# ---------------------------------------------------------------------------
# A retry wrapper: Step -> Step. This is the natural way to hide :need-more,
# and it does not type. See the report.
# ---------------------------------------------------------------------------


def RetryingStepT() -> DFun:
    """The best type a `retrying` wrapper can honestly claim: on :ok the
    cursor is well-formed and has advanced, but its WINDOW is a refilled one,
    not the caller's."""

    def res(c: str):
        def ref(r: str) -> Formula:
            return And([
                Or([STATUS(r, OK), STATUS(r, NEED_MORE), STATUS(r, INVALID)]),
                Imp(STATUS(r, OK), And([
                    WF_CURSOR(f"{r}.cursor"),
                    SAME_BACKING(f"{r}.cursor.window", f"{c}.window"),
                    gt(f"{r}.cursor.position", f"{c}.position"),
                ])),
                Imp(STATUS(r, NEED_MORE), eq(f"{r}.cursor.position", f"{c}.position")),
                Imp(STATUS(r, INVALID), eq(f"{r}.cursor.position", f"{c}.position")),
            ])

        return Base("StepResult", ref)

    return DFun("c", CursorT, res)


def StepFreshT() -> DFun:
    """A de-framing / decompressing stage: on :ok it hands back a cursor
    positioned at 0 over a FRESH non-empty window holding the decoded frame.
    Well-formed and useful, but unrelated to the caller's window.

    The first version of this case asserted it was incomparable with Step and
    the solver refuted that: with the `position >= 1` form, Step really IS a
    subtype. Positioning the fresh cursor at 0 -- which is what a de-framing
    stage actually does -- makes the two genuinely incomparable."""

    def res(c: str):
        def ref(r: str) -> Formula:
            return And([
                Or([STATUS(r, OK), STATUS(r, NEED_MORE), STATUS(r, INVALID)]),
                Imp(STATUS(r, OK), And([WF_CURSOR(f"{r}.cursor"),
                                        eq(f"{r}.cursor.position", 0),
                                        ge(f"{r}.cursor.window.length", 1)])),
                Imp(STATUS(r, NEED_MORE), WF_CURSOR(f"{r}.cursor")),
                Imp(STATUS(r, INVALID), WF_CURSOR(f"{r}.cursor")),
            ])

        return Base("StepResult", ref)

    return DFun("c", CursorT, res)


def _subtype_case(sub_t, sup_t, label):
    out: list = []
    subtype(TRUE, sub_t, sup_t, "step", out, label)
    return [(lbl, valid(g)) for lbl, g in out]


# ---------------------------------------------------------------------------
# The obligation the refinement logic cannot state
# ---------------------------------------------------------------------------


def case_refill_prefix(with_axiom: bool):
    """Retrying the step after a refill is only meaningful if the refilled
    window agrees with the old one below `position`. That is a statement about
    ARRAY CONTENT at an arbitrary index."""
    ctx = Ctx("refill-prefix")
    ctx.assume(WF_CURSOR("c"))
    ctx.assume(eq("q.cursor.position", "c.position"))
    ctx.assume(ge("q.cursor.window.length", "c.window.length"))
    ctx.assume(And([ge("i", 0), lt("i", "c.window.length")]))
    if with_axiom:
        # ONE instance of  forall i. 0 <= i < old.length => new[i] = old[i]
        ctx.assume(eq("byte_new#i", "byte_old#i"))
    ctx.prove("the retried step reads the same byte at i",
              eq("byte_new#i", "byte_old#i"))
    return ctx.log


CASES = [
    ("driver body types against the step PARAMETER", "types",
     lambda: check_driver(StepT(), RefillT(budget=True)),
     "every obligation is discharged from the step's DECLARED refinement; "
     "the driver never sees bencode"),

    ("driver instantiated at bencode/decode (subsumption)", "types",
     lambda: _subtype_case(StepT(progress=2), StepT(), "decode <: Step")
             + check_driver(StepT(), RefillT(budget=True)),
     "check the driver once at the parameter type, then subsume the concrete "
     "step -- no re-checking of the body"),

    ("driver body fails if the step may return a foreign window", "fails",
     lambda: check_driver(StepT(preserve_window=False), RefillT(budget=True)),
     "WF_CURSOR(r.cursor) is not derivable: nothing bounds the result "
     "position by the RESULT window's length"),

    ("driver body fails if the step may overrun the window", "fails",
     lambda: check_driver(StepT(bound_result=False), RefillT(budget=True)),
     "the same window, but no upper bound on the new position"),

    ("termination: bytes-left-in-window metric", "fails",
     lambda: check_driver(StepT(), RefillT(budget=True), metric=cursor_metric),
     "refill GROWS the window, so the cursor metric increases across "
     ":need-more -- the metric is wrong, not the driver"),

    ("termination: lexicographic metric, unrefined source", "fails",
     lambda: check_driver(StepT(), RefillT(budget=False), metric=lex_metric),
     "nothing says a refill consumes the source, so neither lex component "
     "decreases"),

    ("termination: lexicographic metric, refined source", "types",
     lambda: check_driver(StepT(), RefillT(budget=True), metric=lex_metric),
     "(source budget, bytes left) decreases; note this REQUIRES refining the "
     "byte source, which is a second capability the driver's type must carry"),

    ("a `retrying : Step -> Step` wrapper is not a Step", "fails",
     lambda: _subtype_case(RetryingStepT(), StepT(), "retrying <: Step"),
     "Step pins the result window to the ARGUMENT's window; refilling "
     "replaces it. Step is not closed under retry-wrapping"),

    ("a fresh-window step and a same-window step are incomparable (1)", "fails",
     lambda: _subtype_case(StepFreshT(), StepT(), "fresh <: Step"),
     "neither ordering holds, so ONE fixed-refinement driver cannot serve "
     "both -- see case (2)"),

    ("a fresh-window step and a same-window step are incomparable (2)", "fails",
     lambda: _subtype_case(StepT(), StepFreshT(), "Step <: fresh"),
     "the other direction also fails; subsumption cannot bridge them"),

    ("refill prefix-preservation is not derivable in QF-LIA", "fails",
     lambda: case_refill_prefix(with_axiom=False),
     "position and length arithmetic say nothing about array CONTENT"),

    ("refill prefix-preservation holds given one axiom instance", "types",
     lambda: case_refill_prefix(with_axiom=True),
     "and quantifying that instance over all i leaves the quantifier-free "
     "fragment -- it needs the theory of arrays or an opaque predicate"),
]


def main() -> int:
    print("probe 3 / driver: higher-order over a refined step function\n")
    return run_cases("the sans-io driver", CASES)


if __name__ == "__main__":
    raise SystemExit(main())
