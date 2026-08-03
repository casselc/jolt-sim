# INHERITED — Jolt/Clojure behaviour perturb leans on but has NOT decided to adopt

**Purpose.** Every entry here is a behaviour perturb's running code depends on
today, taken from Jolt or from Clojure, that perturb has *not* decided is
perturb's semantics. `PERTURB-DESIGN.md` §8 says perturb may later be self-hosted
on Chez, or on perturb. Each unremarked inherited behaviour is something that
self-host must either reproduce or deliberately break. An entry that is not
written down at the moment it is leaned on becomes invisible, and invisible
inheritance is how "we can self-host later" turns out to be false.

This is the more important of the two logs. `SHAREABLE.md` records things it
would be *fine* to keep sharing; this records things that were **not chosen**.

**Columns.** what was inherited · deliberate or convenience · cost to replace.

Written as the code was written, in the order the dependency was first taken.

---

## I1 — `defn`/`ns`/`require`, the reader, and macroexpansion

**Inherited:** perturb is a namespace set compiled by Jolt's reader, analyzer and
Chez backend. Every form in `perturb/src` is read by Jolt's reader and expanded
by Jolt's macroexpander. Syntax-quote resolution, `:require`/`:as` aliasing,
var interning, and `defn` arity dispatch are all Jolt's.

**Deliberate or convenience:** deliberate, and it is the assignment — the task
says layer on Jolt's host and do not build a separate reader or evaluator. But
it is *inheritance*, not adoption: nothing in `PERTURB-DESIGN.md` has examined
Jolt's reader or its macro system (§4/Q4 explicitly leaves macro provenance
open, and §7 says the charter's formal-core semantics are "available" but
unexamined).

**Cost to replace:** total. This is the substrate. §1.1's decision to fork is
exactly the decision to pay this once and then pay rebase forever (§8).

---

## I2 — `clojure.core/char` caps at 0xFFFF, so perturb strings are BMP-only

**Inherited:** `perturb.octet/->str` turns a decoded code-point vector into a host
string with `(char cp)`. Jolt's `char` throws `Value out of range for char:
65536` — measured, not assumed:

```
$ jolt -e '(println (try (str (char 65536)) (catch :default e (ex-message e))))'
Value out of range for char: 65536
```

This is the **JVM UTF-16 accident** — the exact thing charter §1.3 non-goal 1
names ("JVM UTF-16 surrogate splitting") and that §1.5 cites as precedent for
declining host accidents. perturb's UTF-8 decoder produces full-range Unicode
scalar values correctly; the *host string constructor* refuses them.

**Deliberate or convenience:** convenience, and an unwelcome one. perturb's own
decode path is correct (`perturb.octet/decode-utf8` returns code points up to
U+10FFFF and is exercised on U+1D11E in the self-test). Only the conversion to a
host string is capped.

**Cost to replace:** low, and Chez already has the primitive — Chez strings hold
Unicode scalar values and `integer->char` accepts the full range; only Jolt's
`char` overlay narrows it. Replacing it means one native, or perturb owning its
own string type. Until then, **perturb text is BMP-only on this host**, and the
demo deliberately confines itself to U+03BB so the limit is not silently hidden.
This is the second measured instance of the pattern §1.5 describes (a JVM
convention with no representational justification charging rent), and it is a
*harder* one than bytes: the byte accident cost time, this one costs
expressiveness.

---

## I3 — dynamic vars (`^:dynamic` + `binding`) as the handler-installation mechanism

**Inherited:** `perturb.effect/*handlers*` is a Clojure dynamic var and
`with-handlers` is `binding`. That gives handler installation thread-local,
dynamically-scoped semantics with Clojure's conveyance rules for `future`
(bindings are inherited by a child thread at creation).

**Deliberate or convenience:** convenience. §1.2's **contention** axis exists
precisely to answer "does an owner survive a thread fork", and §1.1 says the
`:host` escapes should be replaced by `:extern` carrying **a declared effect
row**. A real perturb effect system installs handlers through the *type* — the
row on the function's signature — not through a thread-local at runtime. What
is written here is the dynamic-extent shape of the right thing with none of the
static discipline.

**Cost to replace:** high, and it is the main thing this artifact does not do.
Effect rows need analyzer and IR support (§1.1's `:extern`); until then
`perform` cannot be statically guaranteed to find a handler, and
`:unhandled-effect` is a runtime abort where perturb wants a type error. Note
that the *shape* survives replacement: `perform` stays a real call site with a
durable `site` id, which §1.4 requires to keep D3 cheap to add later.

---

## I4 — Clojure exceptions (`ex-info`/`throw`) as the abort mechanism

**Inherited:** §1.4's "substitute a validated result **or abort**" is implemented
as `(throw (ex-info ...))`, i.e. Jolt's condition system under Chez.

**Deliberate or convenience:** convenience. An abort in perturb should be a
declared outcome in the effect's result type, not an out-of-band unwinding
mechanism inherited from a language whose error model §7 explicitly says has not
been re-derived on perturb's terms (charter §2.4 is listed there as
"available… nothing has examined them yet").

**Cost to replace:** medium. Making abort a value means every `perform` site
returns a sum, which is the same trichotomy discipline E4 already validates for
codecs — so the shape is known. The cost is ergonomic (no ambient unwinding) and
it interacts with I3: with effect rows, the abort set is part of the row.

---

## I5 — persistent vectors and maps as the carrier for octet views and decoded frames

**Inherited:** `perturb.octet`'s octet view is a Clojure persistent vector inside
a tagged map; decoded bencode dicts are Clojure hash maps; `nth`/`conj`/`subvec`
are Jolt's `clojure.core`.

**Deliberate or convenience:** convenience for the maps, *forced* for the octets.
E11 already measured what this costs: a Jolt array element is 8 bytes regardless
of kind, and `aget` is generic dispatch, not a primitive read. A persistent
vector of octets is worse still. §1.5 says perturb's byte view should be
"bytevector-backed" — **Jolt exposes no bytevector to Jolt-level code** (checked:
`bytevector` appears in `host/chez/*.ss` only inside the host, never through
`def-var!`), so the design's stated representation is *not reachable from
perturb code today*. See CLAIM-1 in the report.

**Cost to replace:** low *if* the shared lower layer of §8 exists — a
`bytevector` carrier is semantics-neutral storage, which is exactly the line §8
draws ("storage is shareable; the accessor is where divergence lives"). Without
upstream cooperation, perturb needs its own host natives. Note the FFI path
(I6) already reaches real octet storage, so the gap is only for *heap* octet
values, not wire buffers.

---

## I6 — `jolt.ffi` as the only route to real unsigned-octet storage

**Inherited:** `perturb.posix` reads and writes wire octets with
`(ffi/read p :uint8 i)` / `(ffi/write p :uint8 i v)`, which lower to Chez
`foreign-ref`/`foreign-set!` on `'unsigned-8`. This is genuine 1-byte-per-element
unsigned storage with **no sign fold in either direction**, and it is what lets
CLAIM 1 hold on the wire path.

**Deliberate or convenience:** convenience that happens to land in the right
place. perturb did not choose "native memory" as its byte representation; it is
simply the only carrier on this host that is already unsigned. It also drags in
manual `alloc`/`free`, which is why the buffer is modelled as a capability.

**Cost to replace:** low and desirable — a bytevector carrier would replace it
for heap values while the FFI path stays for the socket boundary. But note the
dependency is on `jolt.ffi`'s *type keyword table*
(`host/chez/java/ffi.ss:60`, `uint8|u8|byte -> 'unsigned-8`), an interop detail
with no specification behind it.

---

## I7 — POSIX socket layout and `sockaddr_in` construction copied from `jolt.nrepl`

**Inherited:** `perturb.posix/make-sockaddr` is structurally the same code as
`jolt-core/jolt/nrepl.clj`'s, including the macOS `sin_len` first-byte
convention and the `AF_INET`/`SOCK_STREAM` constants.

**Deliberate or convenience:** convenience. It is correct, it is short, and
rewriting it would prove nothing. But it is *Jolt's* FFI idiom, not a perturb
design: `defcfn` declares an untyped, un-effected escape — precisely the
`:host`-style hole §1.1 says must be replaced by `:extern` with a declared
effect row.

**Cost to replace:** low in lines, high in what it implies — the replacement is
the `:extern` IR work, not a different way to write `bind`.

---

## I8 — bencode dictionary key ordering by `name` string, not by raw byte string

**Inherited:** `perturb.bencode/encode` sorts dict keys with Jolt's default
string `compare` over the key's `name`, matching `jolt.nrepl/bencode`'s
`(sort-by #(name (first %)) v)`. The bencode specification orders keys as **raw
byte strings**.

**Deliberate or convenience:** convenience, taken so the differential oracle
against `jolt.nrepl/bencode` compares like with like. The two orders agree for
ASCII keys, which is the whole nREPL profile, and diverge for keys whose UTF-8
encoding orders differently from their code-point sequence.

**Cost to replace:** trivial (sort the encoded octet vectors), and it should be
replaced — perturb decodes bencode strings as *octets*, so ordering them as
octets is the consistent choice. Left inherited here only to keep the oracle
honest, and flagged so it is not mistaken for a decision.

---

## I9 — `sort-by`, `compare`, and `=` on the decoded value tree

**Inherited:** the oracle compares decoded values with Clojure `=`, and the
encoder orders with Clojure `compare`. Register row 3 (§2) says perturb's `=` is
**total** (`NaN = NaN`) and `==` is IEEE — perturb's `=` is therefore *not* the
`=` used here.

**Deliberate or convenience:** convenience. Nothing in the bencode profile
contains a double, so the divergence is unobservable in this artifact. It is
recorded because "unobservable here" is exactly the condition under which an
inherited semantics survives unnoticed into a self-host.

**Cost to replace:** low for the codec (no floats in bencode), unbounded in
general — it is the register row 3 work.

---

## I10 — `atom`/`swap!` for the capability ledger, the effect trace, and the script cursor

**Inherited:** mutable identity via Clojure atoms.

**Deliberate or convenience:** convenience. §1.2 classifies **mutable cells** as
capability-tier (uniqueness, linearity, contention). A perturb atom would carry
those modes; a Jolt atom carries none, and the ledger is process-global mutable
state reachable from anywhere — the exact shape E2 catalogued as hand-rolled
ownership.

**Cost to replace:** low for the ledger (thread it, as the connection is
threaded), medium for the script handler (it is genuinely stateful I/O), and
the general answer is the capability tier itself.

---

## I11 — `require`-time side effects: `jolt.ffi/load-library` at namespace load

**Inherited:** `perturb.posix` calls `(ffi/load-library)` at namespace-load time
because `defcfn` resolves the C entry point when the `def` is evaluated. So
*loading a perturb namespace performs I/O*, outside any handler.

**Deliberate or convenience:** convenience, and it is a direct contradiction of
CLAIM 2 at the margin: the socket effect is handler-mediated, but the *binding
of the socket syscalls* is not. Under the scripted handler, `perturb.posix` is
still loaded (the demo requires it) and still dlopens libc.

**Cost to replace:** low to make lazy (resolve at first call), high to make
principled (namespace loading is itself an effect, which perturb has not
designed). Reported in the CLAIM 2 section rather than hidden.

---

## I12 — `.nrepl-port`, `println`, and `*out*` for the transcript

**Inherited:** the demo prints with `println` to Jolt's `*out*` and reads the
port from the file `jolt.nrepl/start` writes.

**Deliberate or convenience:** convenience. Console output is unmediated I/O
sitting beside a demo whose whole point is that I/O goes through a declared
effect. It is not routed through `perturb.effect` and it should be, if the
claim is taken at full strength.

**Cost to replace:** low — one more declared effect with a `println` handler.
Left undone deliberately so the gap is visible in the log rather than papered
over by a second effect that adds no evidence.

---

## I13 — `jolt.nrepl`'s wire framing and op vocabulary

**Inherited:** perturb speaks the nREPL op set (`clone`, `eval`, `describe`,
`close`) and the "reply until `status` contains `done`" convention because that
is what the server implements. Also inherited: `jolt.nrepl` encodes `nil` as the
empty bencode string `0:` and encodes keywords by `name` — neither is in the
bencode specification, both are Jolt's choices, and perturb reproduces them so
the oracle overlaps.

**Deliberate or convenience:** deliberate for the op set (it is the protocol),
convenience for the `nil`/keyword encodings.

**Cost to replace:** zero for the protocol (it is external). For the encodings:
they are a lossy convention (`nil` and `""` are indistinguishable on the wire),
and perturb should either forbid `nil` in an encodable value or tag it. Flagged,
not fixed.

---

## I14 — Jolt's reader rejects a bare `:` and a bare `|`, so refinements are re-spelled

**Inherited:** `perturb.bencode/contract` states E13's refinements as data. E13's
prototypes write them in set-builder notation — `(c : Cursor) -> {r | φ(c, r)}`.
Jolt's reader rejects both tokens (`Invalid token: :`, measured while writing the
file), so the contract is written in an s-expression syntax instead
(`{:name pos :sort Int :refine (...)}`).

**Deliberate or convenience:** convenience, but it is a genuine constraint on the
design and worth having hit early. §1.2/§1.3 put refinements at the centre of
perturb's type system. If refinements are eventually written in source rather
than in a side file, **perturb's reader has to accept the notation the
refinement language uses** — and Jolt's does not. That is a reader change, i.e.
a change to the most inherited thing on the list (I1).

**Cost to replace:** low as a reader tweak, but it is a *language surface*
decision made by the host, which is exactly the category §7 says must be
re-derived rather than deferred to. Recorded so the refinement syntax is chosen
on perturb's terms and not by what Jolt's reader happens to tokenise.

---

## I15 — `jolt.nrepl`'s asymmetric text boundary, absorbed by the oracle

**Inherited:** the differential oracle's third check had to model the fact that
`jolt.nrepl/bdecode` UTF-8-decodes dictionary **keys** and leaves every other
byte string as a latin1 string (`nrepl.clj:157`), with the handler decoding the
fields it knows to be text afterwards (`nrepl.clj:320`). `perturb.oracle/jolt-view`
reproduces that boundary so the comparison compares like with like.

**Deliberate or convenience:** deliberate for the oracle (a differential test
that does not model the other side's conventions reports noise), but recorded
because it is the *only* place perturb code encodes a fact about jolt's
semantics. If jolt moves that boundary, perturb's oracle silently starts
reporting disagreements that are not perturb's.

**Cost to replace:** trivial in code, and the entry exists to say what it costs
*conceptually*: an oracle relationship is not free of the other system's
semantics, which weakens the "cite jolt-sim's corpora freely as value tests"
posture of §1.6 by exactly the amount of convention the corpus encodes. Here it
was one function; on a larger corpus it would be more.

---

## I16 — the connection is threaded affinely by hand, not by the language

**Inherited:** nothing, strictly — this is perturb code. Logged because it is the
place the *absence* of an inherited feature is being covered by discipline.

`perturb.nrepl`'s operations consume a connection value and return its
successor, so a use-after-close has to name a value that a `close!` already
consumed. That is the shape §1.2's affine binding would enforce. Jolt enforces
nothing: `(let [c (open ...)] (close! c) (request c ...))` compiles and runs, and
would send on a token that is `nil`.

**Deliberate or convenience:** deliberate, and it is the honest position for an
artifact whose checker is out of scope. But it means CLAIM 3 rests on a
convention a reader has to verify by eye, and the ledger only ever reports what
one run did.

**Cost to replace:** this is §1.2 and §1.3's whole programme — binding identity
in the IR (`:binding-id`, §1.1), a mode system, and a checker. Nothing here
approximates it.
