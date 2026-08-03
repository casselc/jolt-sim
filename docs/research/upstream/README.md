# Upstream contribution assessment — jolt-lang/jolt

Assessment of six performance/tooling commits developed on a fork, re-checked
against **real upstream `jolt-lang/jolt`**, with drafted pull-request
descriptions. Nothing here has been pushed or opened upstream.

---

## 1. The upstream baseline

| | |
|---|---|
| HEAD | `0d84e4e0` — *Merge pull request #529 from ingydotnet/clojurestar-deps* |
| `git describe --tags` | `v0.5.20-4-g0d84e4e0` |
| CHANGELOG top released section | `[0.5.20] - 2026-08-02`; `[Unreleased]` present and empty |
| Merge base with the fork branch | `da59e49d` — *Merge pull request #516 from jolt-lang/fix/string-surface-gaps* |
| Drift since that base | **31 commits**, spanning releases 0.5.17, 0.5.18, 0.5.19 and 0.5.20 |

So the fork's "v0.5.17 candidate" base is four releases behind. The re-assessment
below is against `0d84e4e0`, not against that candidate.

### Verification environment

Scratch clone at `…/scratchpad/upstream-jolt`, Chez Scheme 10.4.1 (`ta6le`),
`CHEZ=/usr/local/bin/chez`, plus `JOLT_CHEZ_CSV=/usr/lib/csv10.4.1/ta6le`
(the wrapper's derived csv dir does not exist on this host).

Two things could **not** be verified here and are called out again in each draft:

* **`make build` / `buildsmoke` / the `bench/` suite** — linking a binary needs
  `liblz4` development files, absent on this host. Anything requiring an AOT
  build is unverified.
* **`make certify`** — no JVM Clojure on PATH, so the conformance oracle did not
  run. JVM-semantics claims below are hand-checked against the Clojure/JVM spec,
  not machine-compared.

---

## 2. Verdict per commit

| # | Fork commit | Verdict |
|---|---|---|
| 1 | `jolt.perf` allocation counters + `make allocgate` | **Half redundant.** Reshaped and kept. |
| 2 | `jolt.perf/emitted-scheme`, `optimized-scheme` | **Keep**, rehomed to `jolt.host`. |
| 3 | Memoize inline protocol method resolution | **Keep.** Strongest of the six. |
| 4 | Resolve inline collection methods from the descriptor | **Drop.** |
| 5 | Nativise `unchecked-byte` / `unchecked-short` | **Keep.** Exact evidence. |
| 6 | Route `jolt-array` backing access through the `ja-*` seam | **Defer.** Not redundant, but must not go alone. |

### 1 — allocation counters + gate: upstream already did the counters

Upstream PR #514 (`2dd93afd`, *Expose Chez's telemetry primitives through
jolt.host*, released in 0.5.16) already publishes `jolt.host/bytes-allocated`,
`gc-count`, `gc-cpu-nanos`, `gc-real-nanos`, `gc-bytes`, `cpu-nanos`,
`real-nanos`, `current-memory-bytes`, `maximum-memory-bytes`. The fork's
`jolt.perf/bytes-allocated`, `gc-count`, `gc-cpu-ms`, `cpu-ms` are all duplicates
of that surface under a second namespace. **Dropped.**

What survives is sharper than the original. Upstream's `bytes-allocated` is the
**live heap**, and the fork's gate is built on it. Running the fork's eight cases
against it on upstream produced

```
unchecked-byte     -9799504 bytes
```

— a *negative* allocation, because a collection landed inside the measured
window. The fork worked around this with a `collect!` before each case; that
narrows the window but does not close it.

Chez has the right primitive and upstream does not expose it: `sstats-bytes`, the
cumulative allocation total, which is monotone. A raw-Chez control (same
300,000-iteration allocating loop, a full collection between runs):

```
sstats-bytes delta   14,442,224   14,441,664   14,442,320     spread 0.005%
bytes-allocated delta                          -2,381,376     (same work)
```

So the reshaped commit adds **one** var, `jolt.host/bytes-allocated-total`, and
builds the gate on it. All eight gate cases then came out **byte-identical across
three runs** on upstream HEAD. See `pr-1-allocation-gate.md`.

### 2 — compiler introspection: keep, rehome

Nothing upstream exposes the emitted or cp0-optimized Scheme for a form.
`jolt-analyze-emit-form` exists in `host/chez/compile-eval.ss` and is exactly
what the evaluator reads; the two accessors are ten lines on top of it.

The fork put them in a new `jolt.perf` namespace. Upstream has a maintained
`jolt.host` surface with a checked-in manifest (`host/chez/jolt-host-manifest.txt`)
and a `manifestcheck` CI gate, and `jolt.host` already carries the compiler-facing
`form-*`, `compile-ns`, `find-var` group. Rehomed there; manifest updated;
`manifestcheck` passes.

**The example the fork flagged as needing re-checking is correct as re-stated.**
Upstream `a63f449c` did change `bit-and`'s lowering to the `jolt-bit-and` helper,
and on `0d84e4e0` the tool reports exactly the fork's corrected reading:

```
(bit-and ^long x 255)  ->  (jolt-bit-and x 255)        unchanged by cp0
```

Nothing to fix or drop. See `pr-4-compiler-introspection.md`.

### 3 — memoize inline protocol method resolution: keep

`find-method-any-protocol` and `find-method-any-protocol-arity` are **byte-for-byte
unchanged** on upstream HEAD; the commit cherry-picks clean. Re-measured on
upstream, and the case is now stronger than the fork's, because the win shows up
in the *exact* allocation counter as well as in wall clock — the old scan calls
`hashtable-keys`, which allocates a fresh key vector per call.

Exact, byte-identical across runs (100,000 calls):

| case | before | after | Δ |
|---|---|---|---|
| `(nth pvec 3)` *(control)* | 11,200,816 | 11,200,816 | 0% |
| `(nth deftype 3)` | 22,401,472 | 16,001,488 | **−28.6%** |
| `(count deftype)` | 20,800,816 | 14,400,816 | **−30.8%** |
| `(.b deftype)` | 44,801,472 | 19,200,816 | **−57.1%** |

Net of the 112 bytes/call closure-invoke floor the controls share, that is
112 → 48, 96 → 32 and 336 → 80 bytes per call. See
`pr-3-inline-protocol-memoization.md` for the wall-clock numbers and their
(honestly poor) spread.

### 4 — resolve from the descriptor's ptable: **drop**

Three reasons, any one sufficient:

1. **It was never a measured result.** The fork's own message says so: an 11%
   median move against a 16–37% per-label spread, which its harness's stated rule
   ("a difference smaller than the spread is not a result") rejects. Nothing about
   upstream changes that.
2. **It does not apply alone.** Cherry-picked onto `0d84e4e0` it conflicts in
   `host/chez/records.ss`; it is a sequel to #3 and only applies behind it.
3. **A reviewer would rightly push back on the design.** Upstream *already* has
   a descriptor-ptable fast path — `find-protocol-method-desc`, keyed by
   `intern-pm-key` gensyms — for the (protocol, method) case. This commit adds a
   *second*, differently-keyed cache into the same table under a reserved gensym,
   for the any-protocol case. That is a real coherence cost to carry for a number
   nobody can resolve.

If someone later resolves it on a quiet machine, it can be revisited on top of #3.
It is not part of the recommended PR set.

### 5 — nativise `unchecked-byte` / `unchecked-short`: keep

Still true on upstream HEAD: `unchecked-long` and `unchecked-int` are host
natives in `host/chez/converters.ss`, while `unchecked-byte` and `unchecked-short`
are Clojure `defn`s in `jolt-core/clojure/core/22-coll.clj` — untouched by the 31
commits of drift. The two source hunks apply; only the seed conflicts, and the
seed must be re-minted rather than cherry-picked (`make remint`, converged in 2
passes; `make selfhost` byte-fixpoint holds).

Exact result, 10,000 calls, byte-identical across runs:

```
unchecked-byte    1,760,336 -> 336 bytes      176.0 -> 0.02 bytes/call
unchecked-short   1,760,992 -> 336 bytes      176.1 -> 0.02 bytes/call
```

See `pr-2-unchecked-byte-short.md`.

### 6 — `ja-*` seam: **defer, do not send standalone**

Not redundant. Upstream has the seam helpers (`ja-len`, `ja-check`, `ja-ref`,
`ja-set!`, `ja->list`, `ja-copy`, `na-make-backing`, `na-list->backing`) but has
not finished routing call sites through them: **39 `(jolt-array-vec …)`
references remain outside the seed image and the deliberate flvector fast path**
— `host-static-classes.ss` 13, `natives-array.ss` 11, `byte-buffer.ss` 8,
`host-static-methods.ss` 2, `io-streams.ss` 2, and one each in `ffi.ss`,
`natives-str.ss` and `nio-file.ss` — many still applying
`vector-ref` / `vector-length` /
`vector-set!` / `vector->list` directly to a backing. `ja-equal?` still does not
exist.

Two reasons not to send it now:

* **It cannot be cherry-picked, only re-derived.** It conflicts in
  `host/chez/java/ffi.ss` against HEAD, and the fork's own message records six of
  seven files conflicting against a base four releases older than this one.
  Upstream has since improved several of the call sites it rewrites (`na-aset-byte`
  returning the narrowed byte, `ByteBuffer.put`'s `na-byte-of` coercion,
  `Random.nextBytes`' JVM-matching stream, `InputStream.read`'s `na-u8->byte`).
  A re-derivation is a day's careful work with a mechanical bind-site sweep, not
  a rebase.
* **Standalone it has no payoff to offer a reviewer.** It is a large mechanical
  diff whose entire justification is a *follow-up* — switching the byte-array
  backing from a Scheme vector to a Chez bytevector — that is not being proposed.
  Its own commit message even records the semantic decision that follow-up
  forces (`ja-equal?` is `equal?` today, so
  `(java.util.Arrays/equals (byte-array [1 2]) (int-array [1 2]))` is `true` and
  would become `false`).

**Recommendation:** hold it, and send it as the first commit of the PR that
actually changes the representation, where the mechanical churn buys something.

---

## 3. Recommended PR shape

**Four small PRs, not one.** They touch disjoint files, carry independent
evidence, and have exactly one ordering dependency between them.

| Order | Branch (suggested) | Title | Files |
|---|---|---|---|
| 1 | `feat/allocation-gate` | Add an allocation regression gate | `host/chez/rt.ss`, `host/chez/jolt-host-manifest.txt`, `test/chez/{alloc-gate.clj,alloc-baseline.edn,unit.edn}`, `Makefile` |
| 2 | `perf/unchecked-byte-short` | Make unchecked-byte and unchecked-short host natives | `host/chez/converters.ss`, `jolt-core/clojure/core/22-coll.clj`, `host/chez/seed/*`, `test/chez/alloc-baseline.edn` |
| — | `perf/inline-protocol-memo` | Memoize inline protocol method resolution | `host/chez/records.ss` |
| — | `feat/compiler-introspection` | Expose the emitted and cp0-optimized Scheme for a form | `host/chez/compile-eval.ss`, `host/chez/jolt-host-manifest.txt`, `test/chez/unit.edn` |

Only 1 → 2 is ordered, and deliberately so: landing the gate first records
`unchecked-byte` at 1,760,336 bytes, and PR 2's diff then moves that baseline row
to 336. The regression is fenced by the gate rather than argued about in a
review thread. The other two are independent of everything, including each other,
and can go in any order or in parallel.

Reasons not to combine:

* PR 2 rewrites the seed image (`host/chez/seed/image.ss`, `prelude.ss` — ~1,000
  lines of generated diff). Anything bundled with it is unreviewable.
* PR 3 is a 60-line change to the runtime's dispatch core with invalidation
  semantics that need their own review attention.
* PR 4 adds public surface to `jolt.host`, which is manifest-gated and a
  compatibility commitment. That is a different kind of decision from a perf fix.

### Matching upstream conventions

Checked against the repo, not against the fork:

* **No `CONTRIBUTING`, no `.github/PULL_REQUEST_TEMPLATE`, no `docs/`.** There is
  no template to follow; the commit message *is* the documentation vehicle here.
* **Commit subjects are imperative, sentence case, no scope prefix, no trailing
  period**: *"Drop returned calls from the tail-frame backtrace"*, *"Expose Chez's
  telemetry primitives through jolt.host"*, *"Report the line a frame was on, not
  the line it was defined on"*. The fork's `feat(perf):` / `perf(records):` /
  `refactor(host):` Conventional-Commits prefixes **do not match** and have been
  dropped from every draft.
* **Bodies are long, prose, and evidence-led** — they explain the motivation,
  show before/after output, and state what was deliberately *not* done. All four
  drafts follow that register.
* **Branches are `<kind>/<slug>`**: `fix/`, `feat/`, `chore/`, `build/`, `deps/`,
  `cli/`, `conformance/`, `followups/`. `perf/` is not attested but reads
  naturally in that set.
* **PRs merge with a merge commit** (`Merge pull request #N from …`), so each PR
  keeps its own commits — the stacking above is fine.
* **New `jolt.host` vars require a manifest row** (`host/chez/jolt-host-manifest.txt`,
  gated by `make manifestcheck`) **and unit rows** (`test/chez/unit.edn`) — this
  is what upstream's own telemetry PR did. Both drafts that add surface do both.
* **`CHANGELOG.md` follows Keep a Changelog** with an `[Unreleased]` section;
  feature commits sometimes update it (`e3c82f25`) and sometimes leave it to the
  release commit (`2dd93afd`). Each draft carries suggested `[Unreleased]` text
  the maintainer can take or leave.
* **Perf tooling precedent**: `printperf` and `aotcacheperf` are Makefile targets
  *outside* `ci`, with an explicit note that *"the repo has no wall-clock
  assertions in its default gates, and a timing floor would be flaky on loaded
  CI."* The allocation gate is proposed **inside** `ci` because it asserts on a
  deterministic counter rather than a time — that argument is made explicitly in
  PR 1, along with the fallback of parking it beside `printperf` if the
  maintainer disagrees.
* **Benchmarks are portable Clojure under `bench/`, run by `bench/run.sh`**, and
  upstream's own perf work adds one (`bench/printing.clj` shipped with *"Cache the
  resolved var cells on the printer's per-item paths"*). PR 3 should arguably add
  `bench/inline_dispatch.clj`; the harness source is in that draft's appendix but
  is **not** included in the branch, because `bench/run.sh` needs an AOT build and
  could not be run on this host.

---

## 4. Cherry-pick results against real upstream

Each fork commit, applied individually to `0d84e4e0`:

| Fork commit | Result | Conflicting files |
|---|---|---|
| `82217c61` alloc counters + gate | conflict | `Makefile` |
| `d2d33db6` emitted/optimized-scheme | conflict | `host/chez/natives-misc.ss` |
| `aed82493` memoize protocol resolution | **clean** | — |
| `7b507c0f` descriptor ptable | conflict | `host/chez/records.ss` (sequel to `aed82493`) |
| `2da25e90` nativise unchecked-byte/short | conflict | `host/chez/seed/image.ss`, `test/chez/alloc-baseline.edn` |
| `22186094` `ja-*` seam | conflict | `host/chez/java/ffi.ss` |

Three of these are shallow: `alloc-baseline.edn` and `records.ss` conflict only
because the series is stacked, and `seed/image.ss` is generated (`make remint`).
The Makefile and `natives-misc.ss` conflicts are real drift. `ffi.ss` is real
drift over code upstream has since extended.

The branches actually verified are exported as patches in `patches/`:

```
patches/0001-Add-an-allocation-regression-gate.patch
patches/0002-Make-unchecked-byte-and-unchecked-short-host-natives.patch
patches/0003-Memoize-inline-protocol-method-resolution.patch
patches/0004-Expose-the-emitted-and-cp0-optimized-Scheme-for-a-fo.patch
```

Patches 1+2 stack; 3 and 4 each apply directly to `0d84e4e0`.

---

## 5. Gate results on upstream HEAD

Baseline (`0d84e4e0`, unmodified): `make unit` passes.

| Gate | PR 1+2 | PR 3 | PR 4 |
|---|---|---|---|
| `unit` | pass (23/23 telemetry) | pass | pass (5/5 introspect) |
| `allocgate` | 8/8 | — | — |
| `manifestcheck` | pass | — | pass |
| `narrow` | 10/10 | — | — |
| `contagion` | 20/20 | — | — |
| `numeric` | 124/124 | — | — |
| `oparity` | 263/263 | — | — |
| `selfhost` (byte-fixpoint) | pass | — | — |
| `devirt` | — | 12/12 | — |
| `pic` | — | 22/22 | — |
| `protoret` | — | 4/4 | — |
| `infer` | — | 45/45 | — |
| `values` | — | 70/70 | — |
| `corpus` | — | pass | — |
| `transient` | — | 17/17 | — |
| `inline` | — | 12/12 | — |
| `dcerefs` | — | 27/27 | — |

Not run anywhere: `buildsmoke`, `buildlibsmoke`, `staticnativesmoke`,
`aotcache*`, `certify`, and the `bench/` suite — see the environment note in §1.
A full `make ci` was therefore **not** run; the subsets above were chosen per
change.

---

## 6. What could not be verified

* **`make ci` in full**, and any gate needing a linked binary or JVM Clojure
  (§1). PR 2 changes numeric semantics, so `certify` is exactly the gate a
  maintainer will want green; it must be run in CI.
* **JVM oracle comparison for `unchecked-byte`/`unchecked-short`.** The values
  were produced on jolt and checked by hand against the JVM contract
  (`(unchecked-byte 200)` → `-56`, `(unchecked-byte -129)` → `127`,
  `(unchecked-short 65536)` → `0`, `(unchecked-byte Long/MAX_VALUE)` → `-1`, …).
  No machine comparison was made.
* **Cross-platform stability of the allocation baseline.** It was recorded on
  x86-64 Linux / Chez 10.4.1 and is byte-identical there. The `tests.yml`
  workflow — the only one running `make test` — pins that platform and builds
  Chez 10.4.1 from source, so CI is consistent with the recorded numbers.
  `release.yml` and `cross-smoke.yml` do not run `make ci`. A developer on
  macOS/arm64 may still see different byte counts; the 2% tolerance is not
  intended to absorb that, and PR 1 says so.
* **End-to-end effect of any of these on a real program.** No claim is made. The
  measurements are microbenchmarks that isolate one path each.
* **Wall-clock resolution for PR 3's `nth`/`count` cases.** The host's run-to-run
  spread rose to 40%+ mid-session and a ratio-normalisation attempt did not help;
  those two deltas are reported as directional and unresolved. The exact
  allocation deltas are what carries that PR.
