# PR 4 — Expose the emitted and cp0-optimized Scheme for a form

**Branch:** `feat/compiler-introspection` → `main`
**Base:** `0d84e4e0` (v0.5.20-4) — independent of PRs 1, 2 and 3
**Patch:** `patches/0004-Expose-the-emitted-and-cp0-optimized-Scheme-for-a-fo.patch`

```
 host/chez/compile-eval.ss        | 36 ++++++++++++++
 host/chez/jolt-host-manifest.txt |  2 +
 test/chez/unit.edn               | 12 +++++
```

---

## Description

Whether a jolt expression became a machine primitive or stayed a generic dispatch
is a **static fact**, readable off the compiler output. It does not need
benchmarking, and reading it is how a claim like "this path is generic" gets
settled before anyone writes one. Chez's cp0 output distinguishes the two
plainly:

```scheme
(bitwise-and x 255)  ->  (if (fixnum? x) (fxand 255 x) (bitwise-and 255 x))
(fxand x 255)        ->  (fxand 255 x)
```

The first carries a type test on every call; the second is one instruction.

`host/chez/compile-eval.ss` already has `jolt-analyze-emit-form` — the exact
string the evaluator reads. This publishes two accessors on top of it, next to
that definition:

* `jolt.host/emitted-scheme` — what this back end produced for a form.
* `jolt.host/optimized-scheme` — the same after Chez's `expand/optimize` (cp0),
  which is where inlining and primitive selection have happened. `print-gensym`
  is off so bindings read as `x` rather than
  `#{x a6asprai2hcqbmswpvb4wleny-0}`.

Both take a form that has already been read — a quoted form is the usual way
to get one — and a namespace string; `nil` means the current namespace.

### Caveat, documented at the definition

Both **analyze** the form, so the ordinary analysis side effects apply — a `def`
interns its var, an `ns` form switches namespace. These are for expressions, not
programs, unless those effects are wanted.

### Worked examples on this tree

```
(aget b i)
  emitted:   (jolt-nth (var-deref "demo" "b") (var-deref "demo" "i"))
```

An array read is the generic collection dispatch, not a primitive. Unchanged by
cp0.

```
(unchecked-byte x)
  emitted:   (jolt-invoke1 (var-deref "clojure.core" "unchecked-byte")
                          (var-deref "demo" "x"))
```

The call site is a var deref plus a generic invoke regardless of what the callee
is — which is the ceiling on what nativising a `clojure.core` fn body can buy,
and is the kind of thing this tool exists to make visible before someone
optimizes the body and is puzzled by the result.

```
(bit-and ^long x 255)
  emitted:   (jolt-bit-and x 255)
  optimized: (jolt-bit-and x 255)        unchanged by cp0
```

This is the deliberate lowering from `a63f449c` (*Give host operation errors a
JVM class*): `bit-and`/`or`/`xor`/`not`'s `:call` goes through the `jolt-bit-and`
helper so an inlined bit op throws `IllegalArgumentException` like the JVM
instead of raising a classless Chez condition, and `(bit-and bignum 1)` no longer
truncates. The runtime type test still exists — it moved inside the helper, where
cp0 at the call site cannot see it. Reported here as a correct fact about a
deliberate emission, not as something to fix.

```
(fn [^long y] (bit-and y 255))
  emitted:   (lambda (y) (let fnrec5234 ((y (jolt->fx y))) (jolt-bit-and y 255)))
  optimized: (lambda (y) (let ((y (jolt->fx y))) (jolt-bit-and y 255)))
```

cp0 collapsing a non-recursive `fnrec` loop into a `let` — a case where the two
accessors differ, which is the reason both exist.

One thing to know when reading the output: with source registration on (the
default for `jolt run` / `joltc`), each top-level form is wrapped in the
tail-frame save/unwind pair added in 0.5.20. It is not part of the expression
being inspected.

### Surface and gates

`jolt.host` is manifest-gated, so both names are added to
`host/chez/jolt-host-manifest.txt` and `make manifestcheck` passes. Five rows are
added to `test/chez/unit.edn` under a new `introspect` suite, covering: the
generic bit-op lowering, the generic collection dispatch, that `optimized-scheme`
returns a form, that `print-gensym` is off (a binding reads by its source name),
and that an explicit namespace argument is honoured over the current one.

---

## Gates

```
unit           pass  (5/5 introspect)
manifestcheck  pass
```

Not run here (environment cannot link a binary; no JVM Clojure):
`buildsmoke`, the AOT gates, `certify`. None is touched by this change.

---

## Suggested `CHANGELOG.md` entry (`[Unreleased]` → `Added`)

> - **`jolt.host/emitted-scheme` and `jolt.host/optimized-scheme`.** Given a
>   form, they return the Scheme this back end emitted for it, and the same after
>   Chez's cp0 — where inlining and primitive selection have happened. Whether an
>   expression became a machine primitive or stayed a generic dispatch is a
>   static fact, and this makes it readable without a benchmark. Both analyze the
>   form, so a `def` interns its var and an `ns` form switches namespace.
