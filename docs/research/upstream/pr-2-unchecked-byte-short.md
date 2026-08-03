# PR 2 — Make unchecked-byte and unchecked-short host natives

**Branch:** `perf/unchecked-byte-short` → `main`
**Base:** `feat/allocation-gate` (PR 1). Stacked deliberately — see below.
**Patch:** `patches/0002-Make-unchecked-byte-and-unchecked-short-host-natives.patch`

```
 host/chez/converters.ss            |  17 +
 host/chez/seed/image.ss            | 688 +++++++++++++++---------------
 host/chez/seed/prelude.ss          | 352 +++++++--------
 jolt-core/clojure/core/22-coll.clj |  10 +-
 test/chez/alloc-baseline.edn       |   2 +-
```

The two seed files are `make remint` output; the reviewable diff is 27 lines.

---

## Description

`unchecked-long` and `unchecked-int` are host natives in
`host/chez/converters.ss`. `unchecked-byte` and `unchecked-short` were the only
members of that family still defined in the `clojure.core` overlay:

```clojure
(defn unchecked-byte [x]
  (let [b (bit-and (unchecked-long x) 0xff)] (if (< b 128) b (- b 256))))
```

For what is one mask and one compare, that form costs a var deref and a generic
invoke to reach the fn, a second var deref plus invoke to reach
`unchecked-long`, and then generic `bit-and`, `<` and `-`. This moves both to
`converters.ss` in the same shape `unchecked-int` already uses — mask to the
width, sign-fold at the half point.

`unchecked-char` deliberately stays in the overlay: it returns a char rather than
an exact integer and is not on the same path.

### Measurement — exact

The allocation counter needs no repeats. 10,000 calls, byte-identical across
runs:

| case | before | after | per call |
|---|---|---|---|
| `(unchecked-byte 200)` | 1,760,336 B | 336 B | **176.0 → 0.02** |
| `(unchecked-short 40000)` | 1,760,992 B | 336 B | **176.1 → 0.02** |

The conversion went from allocating 176 bytes per call to allocating nothing;
the residual 336 is the harness loop, which the gate's `bit-and-mask` row
independently pins at 160.

`test/chez/alloc-baseline.edn` is updated to the new floor in this commit, so a
regression back to the overlay form fails `make allocgate` rather than being
argued about in a review. **This is the reason the PR is stacked on the gate**
rather than sent independently.

### Measurement — wall clock

`repeat-sample.py`-style harness: 11 whole-process samples per side, each sample
timing 5,000 repetitions of a 22-element loop; median with min/max, spread quoted
as (max−min)/median. Rule applied throughout: *a difference smaller than a
label's spread is not a result.*

| label (ns per 22-element loop) | before | spread | after | spread | Δ median |
|---|---|---|---|---|---|
| loop floor *(control)* | 4,176 | 9.4% | 4,323 | 91.7% | +3.5% |
| `(aget b i)` *(control)* | 6,514 | 27.0% | 6,817 | 89.9% | +4.7% |
| `(unchecked-byte 200)` | 12,207 | 12.5% | 7,411 | 78.5% | **−39%** |
| `(unchecked-short 40000)` | 12,211 | 114.1% | 6,887 | 26.2% | **−44%** |
| `(unchecked-byte (aget b i))` | 18,245 | 69.3% | 11,692 | 24.6% | **−36%** |

Net of the loop floor, the conversion goes 8,031 → 3,088 ns (−62%) and a signed
byte read goes 14,069 → 7,369 ns (−48%). The two controls do not move.

**Honestly: the timing does not carry this PR.** The after-batch spreads reached
78–92% on this host, which is at or past the point where the harness's own rule
stops resolving a 39% median move. The exact allocation counter is what makes
the result unambiguous, and the timing is reported for direction and magnitude
only.

### Why the remaining cost is not in this commit

The body is now native, but the call site is not:

```
(unchecked-byte x)
  ->  (jolt-invoke1 (var-deref "clojure.core" "unchecked-byte") x)
```

(read off `jolt.host/emitted-scheme` on this tree). A native body cannot remove a
var deref and a generic invoke; that would be a `native-op` registry entry, which
is a separate decision about whether these belong in the inlined-operation set.
Not attempted here.

### Semantics

Spot-checked on jolt against the Clojure/JVM contract:

```
(unchecked-byte 200)                 => -56
(unchecked-byte -1)                  => -1
(unchecked-byte 127)                 => 127
(unchecked-byte 128)                 => -128
(unchecked-byte 255)                 => -1
(unchecked-byte -129)                => 127
(unchecked-byte 9223372036854775807) => -1
(unchecked-byte 3.9)                 => 3
(unchecked-byte \A)                  => 65
(unchecked-short 40000)              => -25536
(unchecked-short -1)                 => -1
(unchecked-short 32767)              => 32767
(unchecked-short 32768)              => -32768
(unchecked-short 65536)              => 0
(unchecked-short \A)                 => 65
(unchecked-short 1.9)                => 1
(unchecked-char 65)                  => \A      (unchanged, still in the overlay)
```

**Not machine-compared against the JVM.** No JVM Clojure was available in the
environment these were taken in, so `make certify` did not run. That gate is the
one a reviewer should want green on this change, and CI will run it.

### Seed

The seed was re-minted from this tree with `make remint` (converged in 2 passes),
not lifted from anywhere. `make selfhost` — the byte-fixpoint check that the
rebuilt seed equals the checked-in one — passes.

---

## Gates

```
unit           pass
allocgate      8/8
manifestcheck  pass
narrow         10/10
contagion      20/20
numeric        124/124
oparity        263/263
selfhost       rebuild == checked-in seed
```

Not run here (environment cannot link a binary; no JVM Clojure):
`buildsmoke`, `buildlibsmoke`, `staticnativesmoke`, the AOT-cache gates, and
`certify`.

---

## Suggested `CHANGELOG.md` entry (`[Unreleased]` → `Changed`)

> - **`unchecked-byte` and `unchecked-short` are host natives now.** They were
>   the last members of the wrapping-coercion family still defined in the
>   `clojure.core` overlay, which cost a var deref, a nested call to
>   `unchecked-long`, and generic `bit-and`/`<`/`-` per conversion — for one mask
>   and one compare. Each conversion allocated 176 bytes; it now allocates
>   nothing (10,000 calls: 1,760,336 → 336 bytes, the remainder being the
>   measurement loop). `unchecked-char` stays in the overlay: it returns a char,
>   not an exact integer.
