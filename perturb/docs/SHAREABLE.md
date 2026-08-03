# SHAREABLE — Jolt/Chez internals perturb uses that are semantics-neutral

**Purpose.** `PERTURB-DESIGN.md` §8 proposes drawing the perturb/Jolt boundary at
**semantics-neutrality**: things whose behaviour does not encode a language
decision could plausibly be a shared lower layer, shrinking the rebase surface to
the part that actually differs. §8 also says the evidence for where that line
falls is missing. This log is the beginning of that evidence, collected from the
one thing that produces it — code perturb actually runs.

**Columns.** what · why it is semantics-neutral · what depending on it costs.

Only entries perturb's running code actually uses. Where a dependency turned out
*not* to be neutral it is in `INHERITED.md` instead, and several started here and
moved.

---

## S1 — Chez foreign memory: `foreign-alloc`/`foreign-ref`/`foreign-set!`

**What.** `jolt.ffi/alloc`, `free`, `read`, `write` — a raw address plus a
width-tagged accessor table (`host/chez/java/ffi.ss:60`).

**Why neutral.** A native address and a width are ABI facts, not language
decisions. `'unsigned-8` at offset *i* means the same thing in any language
compiled to this host. Notably the *signedness* question that divides Jolt from
perturb (§2 row 1) does **not** arise here: the FFI type table already offers
both `:int8` and `:uint8` over the same storage, and each names an interpretation
of identical bits. That is §8's line — "storage is shareable; the accessor is
where divergence lives" — appearing already realised inside Jolt's own FFI.

**Cost of depending on it.** Low, and this is the strongest entry in the log:
perturb's wire path is *only* correct because this layer exists. But the
dependency is on an interop convenience with no specification, so the cost is
tracking undocumented drift in the keyword table. A shared layer should promote
that table to a stated contract.

---

## S2 — the loader, deps resolution, and the `jolt` launcher

**What.** `deps.edn` `:paths`/`:aliases`, `-M:alias`, namespace→file resolution,
`require` graph ordering, the dev boot cache.

**Why neutral.** Where source lives and in what order it is loaded says nothing
about what the language means. §8 already lists "loader, deps resolution, AOT
cache, image build, bootstrap seed" on the shareable side, and `jolt-toolchains`
is cited there as the existing precedent for a separately-versioned piece.

**Cost of depending on it.** Very low. `perturb/deps.edn` is four lines and its
whole content is a path and two aliases. Nothing perturb decides is expressed
through it. The one caveat is I11 in `INHERITED.md`: load order is observable
because loading a namespace can perform I/O, which is a *language* question
sitting on top of a neutral mechanism.

---

## S3 — persistent vector and hash-map implementations (HAMT/vector trie)

**What.** The concrete data structures behind `conj`, `nth`, `subvec`, `assoc`,
`get` — not the `clojure.core` names, the implementations under them.

**Why neutral.** A HAMT is an algorithm. Which key equality it uses and which
iteration order it exposes are semantics (§2 row 3 changes both), but the trie
machinery itself is parameterised over those and is not.

**Cost of depending on it.** Low *if* the equality/hash functions are a
parameter of the shared layer rather than baked into it. If they are baked in,
this entry collapses into `INHERITED.md` — perturb's `=` is total (§2 row 3) and
Jolt's is not, so a shared collection with a hardwired `=` is not shareable.
**That parameterisation is the thing to check before proposing extraction**, and
this artifact did not check it: the bencode profile has no doubles, so the
divergence never fired (see I9).

---

## S4 — the numeric tower and bit operations

**What.** `bit-and`, `bit-or`, `bit-shift-right`, exact integers of unbounded
range, `+`/`<`/`=` over them. perturb's UTF-8 codec and bencode integer parser
are built entirely from these.

**Why neutral.** Chez's exact integer arithmetic is mathematically determined —
there is no host convention to inherit, which is exactly why §1.5's byte
complaint is about the *byte* type and not about arithmetic. Every value in
perturb's codec stays in `0..255` or is an unbounded exact integer, and no
operation on that path has an implementation-defined answer.

**Cost of depending on it.** Low, with one named exception §1.6 already reserves:
the tower is listed in §8's *not-shared* column "where it diverges". Nothing in
this artifact exercises a divergence — no floats, no fixed-width overflow, no
`unchecked-*` — so on the evidence available, the *integer* part of the tower is
neutral and the divergence risk lives in the float and narrowing operations
perturb does not use.

---

## S5 — string storage as Unicode scalar values

**What.** Chez strings hold code points, so `(int c)` over a Jolt string yields
scalar values directly and perturb's UTF-8 encoder needs no surrogate
reassembly.

**Why neutral.** Storing text as scalar values is the representation-free
choice; UTF-16 with surrogate pairs is the accident. perturb wants exactly what
Chez already does.

**Cost of depending on it.** Low, but the entry is **half-blocked**: the storage
is neutral and correct, while Jolt's `char` *constructor* re-imposes the JVM
16-bit limit on top of it (I2). So this is a case where the shareable layer is
already right and the semantics-bearing overlay above it is what breaks —
which is mild evidence for §8's thesis that the boundary is real, since the
break falls exactly at the overlay.

---

## S6 — `jolt.ffi/defcfn` lowering to a Chez `foreign-procedure`

**What.** The compile-time-typed signature → `foreign-procedure` lowering,
including `:blocking` (emit collect-safe so a blocking syscall does not pin the
collector).

**Why neutral.** Calling convention, argument marshalling and GC-safety of a
blocking call are ABI and runtime facts. §8 lists "FFI plumbing, the pointer-loan
machinery" as shareable and this is the same layer.

**Cost of depending on it.** Medium, and lower than it looks. The *plumbing* is
neutral; the *hole in the type system* is not (I7 — `defcfn` is an untyped,
un-effected escape, which §1.1 wants replaced by `:extern` with an effect row).
The useful observation for §8 is that these separate cleanly: an `:extern` with a
declared effect row can lower to the *same* `foreign-procedure` emission. The
divergence is in the declaration, not the emission — the same shape as S1.

---

## S7 — `jolt.nrepl`'s bencode encoder, used as a differential oracle

**What.** `jolt.nrepl/bencode` and `bdecode` (private vars, reached via
`resolve`), used to cross-check perturb's codec over the overlapping profile.

**Why neutral.** Not shareable *code* — it is string-based and latin1-framed,
which is exactly what perturb declines (CLAIM 1). It is listed because the
**role** is neutral: an independently-written implementation of a wire format is
a valid oracle regardless of which language wrote it, and §1.6's closing note
("the existing oracle corpora remain valid as *value* tests") says precisely
this. The reusable asset is the corpus and the oracle relationship, not the
implementation.

**Cost of depending on it.** Zero for correctness (a disagreement is
investigated, never adopted), and it costs one thing worth naming: the oracle
can only test the *overlap*, so it is silent about every place perturb
diverges. It found no disagreement on the overlap; that is a narrower result
than "the codec is right".

---

## Not shareable — recorded here because the check was made

These were examined while writing this artifact and land on the far side of §8's
line. They are in `INHERITED.md` with full entries; listed here so the log shows
what was tested rather than only what passed.

| candidate | why it is semantics-bearing |
| --- | --- |
| `byte-array` / `aget` | the accessor sign-folds; worse, `na-byte-of` narrows **on store** (`natives-array.ss`), so storage is signed too — §8's "storage is shareable" is *false for Jolt byte arrays as built today* |
| `clojure.core/char` | re-imposes the JVM 16-bit char range on neutral Chez storage (I2, S5) |
| dynamic vars / `binding` | handler installation is an effect-system decision, not plumbing (I3) |
| `throw`/`ex-info` | the error model is §1.4/§2.4 semantics, unexamined (I4) |
| `atom` | mutable cells are capability-tier by §1.2 (I10) |
| `=` / `compare` | register row 3 (I9) |

**The most useful finding for §8 is the first row.** §8 says the byte work
"lands exactly on this line" and that "bytevector *storage* is correct for both".
On this baseline that is not yet true: `na-byte-of` folds the sign at store
time into a boxed vector, so a Jolt byte array is not octet storage with a
signed accessor — it is signed storage. The proposed shared layer therefore does
not exist yet even as an implementation detail, and the first probe §8 names
(the byte-backing change) is a prerequisite for the boundary rather than a
demonstration of it.
