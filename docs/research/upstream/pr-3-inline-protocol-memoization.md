# PR 3 — Memoize inline protocol method resolution

**Branch:** `perf/inline-protocol-memo` → `main`
**Base:** `0d84e4e0` (v0.5.20-4) — independent of PRs 1, 2 and 4
**Patch:** `patches/0003-Memoize-inline-protocol-method-resolution.patch`

```
 host/chez/records.ss | 75 ++++++++++++++++++++++++++++++++-----------
```

---

## Description

A `deftype` that implements a collection interface inline —
`clojure.lang.Indexed`, `Counted`, `Associative`, `ILookup`, `ISeq`,
`IPersistentCollection` — is reached by the core collection fns through
`find-method-any-protocol` / `find-method-any-protocol-arity`. Both rescan the
type's nested per-protocol tables on **every call**:

```scheme
(define (find-method-any-protocol type-tag method)
  (let ((ti (hashtable-ref type-registry type-tag #f)))
    (and ti
         (let* ((ks (hashtable-keys ti)) (n (vector-length ks)))
           ...))))
```

`hashtable-keys` allocates a fresh key vector per call, and the loop then costs
up to 2N `hashtable-ref`s for a type implementing N protocols. An indexed reader
over such a type pays that **once per element**. A `.field` read on a `deftype`
resolves through the same scan, so it pays it too.

This flattens each type's protocols into one `method-name -> impl-list` table,
memoized in a weak `eq` hashtable keyed by the per-type table's identity and
stamped with `jolt-proto-epoch` — the same guard the emitted PIC already uses.

### Invalidation

* `register-protocol-method` bumps `jolt-proto-epoch`, so a new or replaced impl
  misses.
* A type pruned from `type-registry` and re-registered gets a *fresh* per-type
  table, so it misses on identity even within one epoch.
* Weak keys let a pruned type's entry be collected (the unit and corpus
  harnesses prune per unit).

Two other writers into `type-registry` exist and neither bumps the epoch. Both
are safe, and the reasoning is recorded at the definition so the next reader does
not have to re-derive it:

* `mark-extend!` stores the `"__jolt_extend__"` marker into an *inner*
  per-protocol table. The marker is never looked up through
  `find-method-any-protocol` (`extenders` reads the nested table directly), and
  `mark-extend!` is in any case always called immediately after
  `register-protocol-method`, which has already bumped.
* `register-inline-protocol!` adds an *empty* table for a marker protocol, which
  contributes no methods to the flat table.

### Precedence is unchanged

The flat list preserves `hashtable-keys` order, so the "any protocol" answer is
the head, and the arity-selecting variant takes the first impl accepting the
call's argument count with the head as fallback — exactly the old
`(or fallback f)` rule. The `data.priority-map` shape that motivated
`find-method-any-protocol-arity` (a `seq` registered at two arities under two
interfaces) behaves identically.

### Measurement — exact

The old scan's `hashtable-keys` call is an allocation, so the win is visible in
the allocation counter, which does not vary run to run. Harness: a `deftype`
implementing four plain protocols plus `Indexed` and `Counted`, whose method
bodies return a constant, so what is measured is dispatch plus resolution and not
the body. 100,000 calls, **byte-identical across runs**:

| case | before | after | Δ | per call, net of the 112 B invoke floor |
|---|---|---|---|---|
| `(nth pvec 3)` *(control)* | 11,200,816 | 11,200,816 | **0%** | 0 → 0 |
| `(nth deftype 3)` | 22,401,472 | 16,001,488 | **−28.6%** | 112 → 48 B (−57%) |
| `(count deftype)` | 20,800,816 | 14,400,816 | **−30.8%** | 96 → 32 B (−67%) |
| `(.b deftype)` | 44,801,472 | 19,200,816 | **−57.1%** | 336 → 80 B (−76%) |

The persistent-vector control does not move, which is the point: the change is
confined to the `jrec` path.

The `.b` row is the surprise. A dot-form field read on a `deftype` goes through
the same scan, and it was allocating three times what an indexed read did.

### Measurement — wall clock, and its limits

15 whole-process samples per side, each timing 20,000 repetitions of a
22-element loop; median with min/max, spread as (max−min)/median. Rule: *a
difference smaller than a label's spread is not a result.*

| label (ns per 22-element loop) | before | spread | after | spread | Δ median |
|---|---|---|---|---|---|
| loop floor *(control)* | 4,568 | 39.9% | 4,541 | 43.5% | −0.6% |
| `(nth pvec i)` *(control)* | 7,862 | 50.3% | 7,713 | 31.4% | −1.9% |
| `(nth deftype i)` | 25,541 | 24.3% | 18,353 | 40.4% | −28% |
| `(count deftype)` | 24,790 | 19.4% | 19,914 | 41.3% | −20% |
| `(.b deftype)` | 80,267 | 23.7% | 38,741 | 42.8% | −52% |

**Only the `.b` row resolves.** Its delta (41,526 ns) is two and a half times its
after-batch spread (16,581 ns). The `nth` and `count` deltas (7,188 and 4,876 ns)
sit *inside* the after-batch spread (7,414 and 8,225 ns), so by the harness's own
rule they are **directional, not resolved**, on this host. The minima are cleanly
separated across every batch (24,072 → 16,687 for `nth`), and both controls are
flat, but a minimum is not the statistic being reported and is not being promoted
to one.

A ratio-normalisation pass — dividing each label by `nth pvec` measured in the
same process, to cancel process-level noise — was run and **did not help**: host
noise rose mid-session and the normalised spread reached 51% for `nth`. It is
recorded here as a method that failed rather than as a result.

The exact allocation deltas above are what carries this PR. They agree with the
timing in direction and roughly in magnitude, and they are reproducible byte for
byte.

### What is not claimed

* No end-to-end effect on any real program. These are microbenchmarks that
  isolate one path.
* Nothing about the emitted PIC path, which is unaffected — this is the
  *fallback* resolution the core collection fns take, not the inline cache.

---

## Gates

```
unit       pass
devirt     12/12
pic        22/22
protoret   4/4
infer      45/45
values     70/70
corpus     pass (9 allowlisted failures tolerated, unchanged)
transient  17/17
inline     12/12
dcerefs    27/27
```

Not run here (environment cannot link a binary; no JVM Clojure):
`buildsmoke`, the AOT gates, `certify`.

---

## Appendix — suggested `bench/inline_dispatch.clj`

Upstream's perf work adds a bench alongside the fix (`bench/printing.clj` shipped
with *"Cache the resolved var cells on the printer's per-item paths"*). The
suite's `dispatch` and `mono-dispatch` cover protocol dispatch through
`defrecord`; neither covers a `deftype` implementing a **collection interface
inline**, which is the path this changes.

This is **not** in the branch: `bench/run.sh` builds an optimized standalone
binary, which needs Chez's kernel dev files, and that could not be done in the
environment the rest of this PR was verified in. It is offered for the maintainer
to take, adapt or decline.

```clojure
;; inline-dispatch — a deftype that implements a clojure.lang COLLECTION
;; INTERFACE inline, driven through the core collection fns. Unlike `dispatch`
;; and `mono-dispatch` (which call a protocol method directly on records), this
;; reaches the type through nth/count/get, i.e. the find-method-any-protocol
;; fallback, which is a different resolution path.
;;
;; Portable Clojure (jolt + JVM Clojure).
;;   bench/run.sh inline-dispatch 200000
(ns inline-dispatch)

(defprotocol Tagged (tag [x]))
(defprotocol Sized  (weight [x]))

(deftype Window [^bytes buf offset length]
  Tagged (tag [_] :window)
  Sized  (weight [_] length)
  clojure.lang.Indexed
  (nth [_ i]    (aget buf (+ offset i)))
  (nth [_ i nf] (if (< i length) (aget buf (+ offset i)) nf))
  clojure.lang.Counted
  (count [_] length))

(defn build [n] (->Window (byte-array n (byte 7)) 0 n))

(defn sum-nth [w n]
  (loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (nth w i))))))

(defn sum-count [w n]
  (loop [i 0 s 0] (if (= i n) s (recur (inc i) (+ s (count w))))))

(defn -main [& args]
  (let [n (if (seq args) (Long/parseLong (first args)) 200000)
        w (build 64)]
    (println (+ (sum-nth w n) (sum-count w n)))))
```

---

## Suggested `CHANGELOG.md` entry (`[Unreleased]` → `Changed`)

> - **A `deftype` that implements a collection interface inline resolves its
>   methods once per type, not once per call.** `find-method-any-protocol` walked
>   the type's nested per-protocol tables on every call, allocating a fresh key
>   vector each time — a cost an indexed reader paid per element, and a `.field`
>   read on a `deftype` paid too. The tables are now flattened per type and
>   memoized behind the same `jolt-proto-epoch` guard the inline cache uses, so a
>   new or replaced impl still invalidates. Measured over 100,000 calls:
>   `(nth deftype i)` allocates 28.6% less, `(count deftype)` 30.8% less, and a
>   `.field` read **57% less**. Method precedence is unchanged.
