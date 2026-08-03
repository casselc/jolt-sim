# PR 1 — Add an allocation regression gate

**Branch:** `feat/allocation-gate` → `main`
**Base:** `0d84e4e0` (v0.5.20-4)
**Patch:** `patches/0001-Add-an-allocation-regression-gate.patch`

```
 Makefile                         |  12 +++-
 host/chez/jolt-host-manifest.txt |   1 +
 host/chez/rt.ss                  |   9 +++
 test/chez/alloc-baseline.edn     |   1 +
 test/chez/alloc-gate.clj         |  90 ++++++++++++++++++++++++++++
 test/chez/unit.edn               |   7 +++
```

---

## Description

The repo deliberately keeps wall-clock assertions out of its default gates —
`printperf`'s own header says so: *"the repo has no wall-clock assertions in its
default gates, and a timing floor would be flaky on loaded CI."* That is the
right call, and it leaves a gap. There is currently no gate that catches a hot
path that started allocating more: boxing that used to be avoided, a primitive
that stopped being a primitive, an intermediate sequence that used to be fused.
Each of those is individually inside the noise of any timing measurement, which
is exactly why they accumulate unnoticed.

Allocation is not subject to that noise. The same work allocates the same number
of bytes on a loaded machine and an idle one, so a gate on bytes needs no
repeats, no medians and no spread analysis. This adds one.

### The counter

`jolt.host/bytes-allocated` (0.5.16) is the **live heap** — what survived the
last collection plus what has been allocated since. It is the right number for a
memory gauge and the wrong one for measuring a workload, because a collection
inside the measured window makes the difference across it *negative*. Running
eight candidate cases against it here produced, among others:

```
unchecked-byte     -9,799,504 bytes
```

Chez tracks the number this needs and it was not exposed: `sstats-bytes`, the
cumulative allocation total, which is monotone. Same 300,000-iteration allocating
loop, measured three times with a full collection between runs:

| counter | run 1 | run 2 | run 3 | spread |
|---|---|---|---|---|
| `sstats-bytes` delta | 14,442,224 | 14,441,664 | 14,442,320 | **0.005%** |
| `bytes-allocated` delta | \-2,381,376 | — | — | meaningless |

So this adds one var, `jolt.host/bytes-allocated-total`, next to the existing
telemetry block, with a comment at the definition explaining why both exist and
which one a caller wants. Manifest row added; `manifestcheck` passes. Three unit
rows cover it, including the property that makes it usable — that a window
containing a collection still yields a non-negative difference.

### The gate

`test/chez/alloc-gate.clj` measures eight representative forms and compares them
to `test/chez/alloc-baseline.edn`, failing on growth beyond 2%.
`ALLOC_GATE_RECORD=1` rewrites the baseline. The cases are chosen to span the
axes this runtime actually has separate machinery for: persistent-structure
building, wrapping coercions, the fixnum-vs-generic arithmetic split, string
building, and a lazy pipeline.

Recorded on this base, **byte-identical across three consecutive runs**:

```
  assoc-small-map        576,160 bytes   (1,000 x  (assoc {:a 1 :b 2} :c 3))
  bit-and-mask               160 bytes   (10,000 x (bit-and 200 0xff))
  generic-add                160 bytes   (10,000 x (+ 1000000 2000000))
  map-filter-reduce    1,772,992 bytes   (200 x    (reduce + (filter even? (map inc (range 50)))))
  str-concat             224,288 bytes   (1,000 x  (str "a" "b" "c"))
  unchecked-byte       1,760,336 bytes   (10,000 x (unchecked-byte 200))
  unchecked-short      1,760,992 bytes   (10,000 x (unchecked-short 40000))
  vec-range-10         2,674,192 bytes   (1,000 x  (vec (range 10)))
```

The 160-byte floor on `bit-and-mask` and `generic-add` is the harness loop
itself; both operations allocate nothing, which is the fact those two rows pin.

### Why this one goes in `ci`

`printperf` and `aotcacheperf` are outside `ci` because they assert on time. This
does not: the counter above moved by 656 bytes in 14.4 MB across runs on a
machine that was simultaneously running other work, and by **zero** across the
gate's own repeats. There is no flakiness budget to spend.

If that argument does not convince, the change to `ci` is one word and the target
can sit beside `printperf` as a manual check instead. The rest of the PR is
unaffected.

### Limitations, stated in the gate's own docstring

* **It does not catch work that got slower without allocating more** — a generic
  dispatch replacing a primitive with the same allocation profile, worse cache
  behaviour, more instructions. Allocation is a proxy, not a cost model.
* **The 2% tolerance is not a noise allowance.** The counter is exact. It is
  slack for unrelated compiler drift between commits, so an unrelated change does
  not have to re-record the baseline.
* **The baseline is platform-specific.** It was recorded on x86-64 Linux under
  Chez 10.4.1 built from source — which is what `.github/workflows/tests.yml`
  pins, and `tests.yml` is the only workflow that runs `make test`. A developer
  on macOS/arm64 may see different counts locally; the tolerance is not intended
  to absorb that, and a per-platform baseline would be the fix if it becomes a
  problem.

---

## Gates

```
unit           pass  (23/23 telemetry, including the three new rows)
manifestcheck  pass
allocgate      8/8
```

`make ci` was not run in full: this environment cannot link a binary (`liblz4`
development files absent), so `buildsmoke` and the AOT gates are unverified here,
and `certify` did not run (no JVM Clojure on PATH). Neither is touched by this
change.

---

## Suggested `CHANGELOG.md` entry (`[Unreleased]` → `Added`)

> - **An allocation regression gate, and the cumulative counter behind it.**
>   `make allocgate` asserts the bytes allocated by eight representative forms
>   against `test/chez/alloc-baseline.edn`. Unlike a timing floor the number does
>   not move with machine load, so the gate runs in `ci`. It is built on a new
>   `jolt.host/bytes-allocated-total` — Chez's *cumulative* allocation total,
>   which is monotone; the existing `bytes-allocated` is the live heap and yields
>   a negative difference when a collection lands inside the measured window.
