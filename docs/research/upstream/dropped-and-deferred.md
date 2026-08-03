# Dropped and deferred

The two of the six commits that are **not** in the recommended PR set, and the
half-commit that was cut out of PR 1. Dropping is a good outcome; this records
why, so the reasoning does not have to be re-derived.

---

## Dropped outright

### The `jolt.perf` counter namespace (half of fork commit 1)

**Upstream already did it, in 0.5.16.** PR #514 (`2dd93afd`, *Expose Chez's
telemetry primitives through jolt.host*) publishes `bytes-allocated`,
`gc-count`, `gc-cpu-nanos`, `gc-real-nanos`, `gc-bytes`, `cpu-nanos`,
`real-nanos`, `current-memory-bytes`, `maximum-memory-bytes` — plus two clocks,
`thread-id`, `scheme-version` and `machine-type` — into `jolt.host`, behind a
checked manifest and a CI gate.

The fork's `jolt.perf/bytes-allocated`, `gc-count`, `gc-cpu-ms` and `cpu-ms` are
duplicates of that under a second namespace name, with the millisecond
granularity upstream deliberately avoided. `collect!` is not duplicated but is
also not needed once the gate uses a cumulative counter.

What survived is `jolt.host/bytes-allocated-total` — the one number Chez tracks
that upstream had not exposed, and the one the gate actually needs. See PR 1.

### Fork commit `7b507c0f` — resolve inline collection methods from the descriptor

Three independent reasons:

1. **It was never a measured result, by its own account.** The fork's commit
   message reports 8,015 → 7,140 ns median, an 11% move against a 16–37%
   per-label spread, and concludes: *"By the harness's own rule — a difference
   smaller than the spread is not a result — THIS IS NOT A MEASURED IMPROVEMENT
   on this host."* Nothing about upstream changes that, and this host is noisier
   than the one that number was taken on, not quieter. Re-taking it would produce
   the same non-answer more expensively.

2. **It does not stand alone.** Cherry-picked onto `0d84e4e0` it conflicts in
   `host/chez/records.ss`. It is a sequel to the memoization commit (PR 3) and
   only applies behind it, so it cannot be offered as an independent
   contribution.

3. **The design invites a reasonable objection.** Upstream already has a
   descriptor-`ptable` fast path: `find-protocol-method-desc`, keyed by
   `intern-pm-key`-minted gensyms, for the (protocol, method) case. This commit
   adds a *second*, differently-keyed cache into the same `ptable` under a
   reserved gensym for the any-protocol case, and relies on the fact that nothing
   anywhere iterates a `ptable`'s entries. That is a real coherence constraint to
   ask a maintainer to carry, and it buys a number nobody can resolve.

Its stated justification — an `eq` ref on an immutable field instead of hashing a
type-tag string per element, reusing invalidation that already exists rather than
adding a side table — is genuine and structurally reasonable. It is just not
enough on its own. If someone resolves the timing on a quiet machine it can be
revisited as a follow-up on top of PR 3.

---

## Deferred

### Fork commit `22186094` — route `jolt-array` backing access through the `ja-*` seam

**Not redundant.** Upstream has the seam helpers — `ja-len`, `ja-check`,
`ja-ref`, `ja-set!`, `ja->list`, `ja-copy`, `na-make-backing`,
`na-list->backing` — and has routed some call sites (`java.util.Arrays/copyOf`,
`/copyOfRange`, `/fill`, `/toString`; `io/copy` and `Files/readAllBytes` through
`na-bv->bytearray`). It has not finished. On `0d84e4e0` there are **39
`(jolt-array-vec …)` references outside the seed image and outside the deliberate
flvector fast path**, spread across eight files:

```
host/chez/java/host-static-classes.ss  13
host/chez/java/natives-array.ss        11  (several already through the seam)
host/chez/java/byte-buffer.ss           8
host/chez/java/host-static-methods.ss   2
host/chez/java/io-streams.ss            2
host/chez/java/ffi.ss                   1
host/chez/java/natives-str.ss           1
host/chez/java/nio-file.ss              1
                                       --
                                       39
```

(plus 6 in `host/chez/run-flarr.ss`, which is the gate script asserting the
flvector fast path's literal emission and is not a call site.)

Many still apply `vector-ref` / `vector-length` / `vector-set!` / `vector->list`
directly to a backing. `ja-equal?` — the one genuinely new helper in the fork's
commit — does not exist upstream.

**But it should not be sent now, for two reasons.**

*It cannot be cherry-picked, only re-derived.* It conflicts in
`host/chez/java/ffi.ss` against HEAD, and the fork's own message records six of
seven files conflicting against a base four releases older than this one.
Upstream has since improved several of the exact call sites this rewrites, and
those improvements must be preserved rather than replayed over:

| site | what upstream did that must survive |
|---|---|
| `na-aset-byte` | added an explicit `ja-check` and returns the **narrowed** byte, so `aset`'s result agrees with a following `aget` |
| `ByteBuffer .put` | narrows a lone byte with `na-byte-of` rather than `jnum->exact`, which would widen the backing outside −128..127 |
| `Random.nextBytes` | draws one 32-bit value per four bytes and folds the sign, matching the JVM's stream for a seed |
| `InputStream.read` | narrows through `na-u8->byte` |
| `jolt.ffi` | grew ranged `read-array!`/`write-array`, up-front range and null-pointer validation, and scoped pointer loans |

A correct re-derivation is a careful mechanical sweep with bind-site tracking
(not grep), plus a differential harness over every converted call site. That is
real work, and it was not done here.

*Standalone it has no payoff to offer.* Its entire justification is a follow-up
that is not being proposed: switching the byte-array backing from a Scheme vector
to a Chez bytevector. As a PR on its own it is a large mechanical diff with no
user-visible benefit, and it asks the maintainer to accept a semantic decision in
advance — `ja-equal?` is `equal?` today, so
`(java.util.Arrays/equals (byte-array [1 2]) (int-array [1 2]))` is `true`, and a
bytevector backing would make it `false`. That decision belongs in the PR that
forces it.

**Recommendation:** hold it, and send it as the first commit of the PR that
actually changes the representation. The seam is the right idea; it just needs
the change it enables to travel with it.
