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

Entries are not deleted when they are closed. I11 is closed and stays, with its
original premise and the measurement that corrected it, because "what we
believed and what turned out to be true" is the part a later reader cannot
reconstruct.

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

## I11 — ~~`require`-time side effects: `jolt.ffi/load-library` at namespace load~~ CLOSED

**Status: closed.** Kept in place, with the original entry's premise corrected,
because the correction is the useful part.

**What the entry used to say.** `perturb.posix` calls `(ffi/load-library)` at
namespace-load time *because `defcfn` resolves the C entry point when the `def`
is evaluated*, so loading a perturb namespace performs I/O outside any handler.

**The premise was wrong, and this was measured.** `defcfn` does **not** resolve
at `def`. `jolt.ffi/defcfn` expands to `(def name (jolt.ffi/__cfn ...))` and the
backend lowers `__cfn` to a closure with the resolution deferred inside it
(`jolt-core/jolt/backend_scheme.clj:589-617`, comment "Lazy resolution: the
foreign-procedure form is deferred inside a closure … critical for `:optional
:jolt/native` libs whose load-object runs in the scheme-start launcher"; pinned
by Jolt's own `test/chez/ffi-native-error-test.ss:61`). Directly measured:

```
$ jolt -e-ish probe: a namespace with (ffi/defcfn c-bogus "no_such_symbol" [] :int)
  and NO load-library at all
  namespace loads                          -> ok, the var is a fn
  (c-getpid) with no load-library ever      -> 15203      ; resolves anyway
  (c-bogus)                                 -> throws
```

Two facts fell out. First, **`def` resolves nothing**, so the "binds five
syscalls at load" half of the leak never existed. Second, and unrelated to the
leak, **libc symbols resolve on this host without `load-library` at all** — the
no-argument form is `(load-shared-object #f)`, i.e. `dlopen(NULL)`, and Chez's
foreign-entry table already sees the process's own symbols. perturb keeps the
call rather than relying on that, because relying on it would be a new,
undocumented inheritance (see S8).

**What the leak actually was, and what closed it.** One unconditional call to
`ffi/load-library` at namespace load: a mutation of the process's dynamic-loader
state performed by *requiring* a namespace, outside any handler, on runs that
never touch a socket. It is now `perturb.posix/ensure-native!`, called from the
`sys-*` wrappers, which are reached only from `perturb.posix/handler`, which is
reached only from `perturb.effect/perform`. Library loading and every symbol
resolution now happen strictly inside the dynamic extent of a handled effect.

**Why not a `:load-library` op on the effect.** `perturb.wire/socket` is the
contract the *scripted* handler also satisfies, and a scripted handler has no
library. Declaring the op would push a posix implementation detail into the
interface the codec and session code name — the opposite of what §1.4 asks.
Laziness keeps the interface at four ops and still puts the binding inside a
handler.

**A finding about instruments, not about perturb.** At the syscall level on this
host, the old leak was **invisible**:

```
(jolt.ffi/load-library)              -> 0 syscalls          ; dlopen(NULL)
(jolt.ffi/load-library "libz.so.1")  -> openat + 4 mmap + mprotect + ...
```

`dlopen(NULL)` touches no file and `dlsym` does not syscall, so strace could
never have seen either half of I11. That is why the closure is verified by two
instruments, not one: `perturb.posix/native-log` (an instrumented
`load-library` plus per-binding call counts) for the invisible half, and strace
for everything else. `perturb.posix/c-absent-canary` — a `defcfn` on a symbol
that exists in no object in the process, left in the shipped namespace — is the
standing proof that `def` resolves nothing: if Jolt ever changed to eager
resolution, requiring `perturb.posix` would fail outright.

**Verified by** `jolt -M:noio` and `perturb/dev/verify-noio.sh`. A complete
scripted session (clone, three evals, close, one octet per recv) runs between
two marker writes; the strace window holds **zero syscalls attributable to
perturb** (residual: six `clock_gettime(CLOCK_PROCESS_CPUTIME_ID)` from Chez's
collector, printed rather than filtered). The positive control
(`-M:noio --touch-native`) puts one real `:connect` in the same window and the
window then shows `socket`/`connect`/`close` and `native-log` reads
`{:library-loads 1 :calls 3}`. Without that control the clean window would not
be evidence.

**What is still inherited here.** Namespace loading is *still not an effect*.
perturb has simply arranged that its namespaces do nothing at load. A namespace
that wanted to do something at load would have no way to declare it, and nothing
would stop it. That is the principled version of this entry and it is not done —
see also `SHAREABLE.md` S2's caveat, which now stands on its own rather than
pointing at a live leak.

---

## I12 — `.nrepl-port`, `println`, and `*out*` for the transcript — STILL OPEN, now measured

**Inherited:** the demo prints with `println` to Jolt's `*out*` and reads the
port from the file `jolt.nrepl/start` writes.

**Deliberate or convenience:** convenience. Console output is unmediated I/O
sitting beside a demo whose whole point is that I/O goes through a declared
effect.

**Now measured rather than asserted.** `dev/verify-noio.sh` RUN 3 runs the same
scripted session as RUN 1 but prints its three values *inside* the marked
window. The window then contains exactly three syscalls:

```
write(1, "  => \"scripted<(+ 1 2)>\"\n", 25) = 25
write(1, "  => \"scripted<(clojure.string/u"..., 57) = 57
write(1, "  => \"scripted<(str \\\"lambda is "..., 46) = 46
```

That is the whole of this leak, at its exact size: one `write(2)` per `println`,
outside any handler. RUN 1 is the same code with the printing moved after the
closing marker, and its window is clean. So the console leak is now bounded and
located, not merely admitted.

**Decision: left unrouted, deliberately, and this is the reason.** An effect
does not remove I/O — it makes I/O **substitutable**. The socket effect earns
its cost because a second and third implementation of the same interface exist
and run the same var: RUN B and RUN C in the demo are what turn "the codec does
not name a socket" from a claim into a measurement, and the octet-identical sent
bytes between RUN A and RUN C is the artifact's strongest evidence. Console
output has no second consumer. Nothing in perturb reads perturb's console
output, so a console handler would have nothing to be checked against; it would
move the `write(2)` behind a name and produce no new fact. The previous
session's reasoning ("a second effect adds no new evidence") is confirmed by the
measurement rather than displaced by it.

**What that costs, stated plainly.** CLAIM 2 is about *socket* I/O and holds for
socket I/O without qualification now that I11 is closed. It is **not** a claim
that a perturb program performs no unmediated I/O, and this entry is the reason
why. A perturb that took §1.4 to its conclusion would have every effect
declared, console included, and the effect row on the signature (I3) would say
so — at which point the console effect is not ceremony, because the row makes it
checkable. Without I3's static half there is nothing to check, which is exactly
why routing it today buys nothing.

**Cost to replace:** low — one more declared effect with a `println` handler.
Reported in the CLAIM 2 section of every demo run, not quietly dropped.

---

## I17 — `__cfn`'s lazy, memoised, per-binding symbol resolution

**Inherited:** closing I11 leans on a specific property of Jolt's backend that
`jolt.ffi`'s public docstring does not state. `jolt.ffi/defcfn` expands to
`(def name (jolt.ffi/__cfn csym argtypes rettype opts))`, and
`jolt-core/jolt/backend_scheme.clj`'s `emit-ffi-fn` lowers `__cfn` to

```scheme
(let ((p #f))
  (lambda (a0 ...) ((or p (begin (set! p (foreign-procedure "csym" (...) ...)) p)) a0 ...)))
```

perturb now depends on three things in that shape: (a) evaluating the `def`
resolves nothing, (b) resolution happens on the **first call** of that binding,
(c) the cell `p` is **per binding**, so "binding X was never called" is exactly
"symbol X was never resolved". (c) is what makes `perturb.posix/native-log`'s
per-op counts a sound proxy for symbol resolution, which is otherwise invisible
(`dlsym` issues no syscall).

**Deliberate or convenience:** convenience, and load-bearing. It is why I11
could be closed by a five-line `ensure-native!` instead of by designing
namespace loading as an effect. Note the asymmetry: Jolt introduced this
laziness for its own reason — `:optional :jolt/native` libraries whose
`load-object` runs in the scheme-start launcher, after the heap is built (the
comment at `backend_scheme.clj:609`) — and perturb is free-riding on a
convenience with a different motivation. It is pinned by one Jolt test
(`test/chez/ffi-native-error-test.ss:61`), so it is not purely accidental, but
it is a backend emission detail with no statement in `jolt.ffi`'s API docs.

**Cost to replace:** low in code, and perturb has already paid part of it —
`perturb.posix/c-absent-canary` is a `defcfn` on a symbol that exists in no
object in the process, kept in the shipped namespace precisely so that a Jolt
that switched to eager resolution would break the artifact loudly at `require`
rather than silently reopening I11. If Jolt did switch, perturb would need its
own indirection (resolve through a `foreign-fn` built inside the handler), which
is a handful of lines. The real cost is conceptual: **whether binding a foreign
symbol is an effect is a language decision, and perturb is currently taking
Jolt's answer to it.** §1.1's `:extern` with a declared effect row is where that
decision belongs.

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

**AMENDED — `perturb.check` now rejects that program.** The sentence above ("Jolt
enforces nothing") is still true of *Jolt*, and stays. What changed is that
perturb no longer relies on Jolt to enforce it: `perturb.check` reads
`perturb.cap/checker-input` as a specification and real Jolt IR as the program,
and refuses `perturb.corpus/use-after-close` — which is I16's example, verbatim —
before anything runs. `jolt -M:check` is the gate.

The cost line above said the replacement needs "binding identity in the IR
(`:binding-id`, §1.1), a mode system, and a checker". Two of the three are now
here. The first is not, and cannot be: the IR has no binding identity (I18), so
the checker manufactures its own binding id at every binding occurrence. That is
the alpha-conversion §1.1 named as the alternative, and it is what makes
`perturb.corpus/shadowing-hides-a-leak` a rejection rather than a false accept.

`perturb.nrepl` itself does NOT pass the checker. Three of its functions are
rejected — `clone-session`, `eval-code` and `session` — because they pass a
connection across an unannotated function boundary and return it inside a pair.
The affine hand-threading this entry describes is real, and it is still not
enough: it is invisible at the function boundary, where §1.2's `:consumes` /
`:produces` have no way to say *which* result position holds the capability.

**AMENDED AGAIN — and that gap cuts the other way too.** The paragraph above
reads as though the unpositioned annotation only costs *rejections* of correct
code. Running the checker's own accept set shows otherwise. Because `request`'s
`:produces` cannot say the connection is at position 0 of `[conn' frames]`, the
checker models a call's whole result as the successor capability — so
`perturb.corpus/open-request-close`, which the checker ACCEPTS, hands the pair to
`close!` and throws under the scripted handler. The hand-threading discipline
this entry credits is therefore not merely unchecked at the boundary; the
checker's current model of the boundary *disagrees with the code that actually
runs*. See PERTURB-DESIGN E15.

---

## I18 — `jolt.ir`'s `:local` has no binding identity, and the compile spine has no IR hook

**Inherited:** two facts about Jolt's compiler that `perturb.check` depends on,
both now measured rather than read.

*First:* `{:op :local :name "c"}` is the whole node. `PERTURB-DESIGN` §1.1 says so
from reading `jolt-core/jolt/ir.clj` and §4 records the claim as untested. It is
true. `jolt -M:check` prints the evidence from the IR the back end was handed for
`perturb.corpus/shadowed-rebind`: three `:let` bindings all named `"c"`, holding
three different Connection instances, and one single `:local` node shape between
them with no `:binding-id`. The analyzer's lexical environment is a *set* of
names (`analyzer.clj:84-86`), so a shadowing binding reuses the name outright and
nothing downstream can tell the instances apart.

*Second:* there is no supported way for Jolt-level code to obtain IR.
`jolt.analyzer/analyze` needs a `ctx` that is a Chez record (`make-chez-actx`,
`host-contract.ss:20-21`) with no Jolt-level constructor, and the compile spine
`var-deref`s both `jolt.analyzer/analyze` and `jolt.passes/run-passes` at host
load time (`compile-eval.ss:12-20`), so redefining either var does nothing.
`perturb.ir` therefore taps the one var `run-passes` still calls through its
cell — `jolt.passes.numeric/annotate` — with `alter-var-root`. Nothing in
`/home/user/jolt` is modified.

**Deliberate or convenience:** convenience, and the least defensible dependency in
perturb. It is a private implementation detail of a pass pipeline being used as
an interface. It also fixes what the checker sees: post-const-fold IR, and only
for namespaces required after the tap is installed.

**Cost to replace:** low in lines, and it is a *Jolt* change rather than a perturb
one — the compiler needs a supported "give me the IR for this unit" entry point,
which is a prerequisite for any checker running as a compiler pass rather than
beside one. The `:binding-id` half is the higher cost and the more important:
until the IR carries binding identity, every consumer of Jolt IR that cares about
linearity has to re-derive scopes, and two consumers that do it slightly
differently will disagree about the same program.
