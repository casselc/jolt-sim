# perturb — first running artifact

A perturb nREPL **client**: bencode codec, session handling, and enough of a
connection to `clone` a session, `eval` a form against a running Jolt nREPL
server, and print the answer.

It exists to test three claims from `../docs/research/PERTURB-DESIGN.md` against
running code rather than to argue them. Nothing here is a language
implementation: perturb is a namespace set running on the Jolt compiler, using
its reader and runtime, diverging only where semantics differ.

> **Status.** Research artifact. It is not a release, not a library, and the
> honest report of where a claim does *not* hold is a deliverable equal to the
> parts that work.

## The three claims

| | claim | where it lives | verdict |
| --- | --- | --- | --- |
| 1 | wire bytes are unsigned octets, `0..255`, no `unchecked-byte` fold | `perturb/octet.clj`, `perturb/posix.clj` | **holds on the wire path**, by not routing wire bytes through a Jolt byte array at all — see below |
| 2 | socket I/O goes through a declared effect with handlers; the same codec and session code run against a real socket and a scripted handler | `perturb/effect.clj`, `perturb/wire.clj`, `perturb/nrepl.clj`, `perturb/http.clj` | **holds for the socket; a scripted run is measured to perform no I/O** (`INHERITED.md` I11, closed) **and the boundary now also fails closed and latches** — a native crossing with no handler in extent is refused before the syscall and the run cannot then be reported as successful, asserted per run over a required-symbol set with its own positive control. One leak remains and is deliberate: console output is unmediated `println` (I12), measured at 3 `write(2)` calls in the exhibit run |
| 3 | the connection is a `unique` capability with typestate, closed exactly once, annotated as data a future checker could consume | `perturb/cap.clj`, `perturb/nrepl.clj`, `perturb/check.clj` | **the checker exists and rejects** — `jolt -M:check` refuses use-after-close, double-close, use-after-move and a dangling connection, statically, over real Jolt IR, across two protocols. `perturb.nrepl` and `perturb.http` both check above their abstraction boundary; what the rules **cannot say** about the second protocol is the more useful result (E18) |

**Claim 1 needs its qualification stated up front.** `PERTURB-DESIGN` §1.5 and §8
assume byte *storage* is octets and only the accessor folds the sign. On this
baseline that is false: `host/chez/java/natives-array.ss`'s `na-byte-of` narrows
on **store**, so a Jolt byte array holds signed values in a boxed vector.
Choosing a different accessor cannot recover octets. perturb reaches octets by
never building a byte array — native memory through `jolt.ffi`'s `:uint8` for
wire bytes, a range-checked persistent vector for heap values.

**Claim 2's remaining leak is named, not hidden.** `perturb.posix` used to call
`(ffi/load-library)` at namespace load, so *requiring* a namespace performed
I/O even on runs that never touched a socket. That is closed: the library load
and every symbol resolution now happen inside a handler invocation, and
`dev/verify-noio.sh` measures it rather than asserting it (see below). Console
output is still unmediated `println`, on purpose — `INHERITED.md` I12 states the
reason and the measurement, and every demo run prints it.

## Running it

Requires a Jolt checkout. `CHEZ=chez` fails on newer Makefiles; use the path.

```sh
export JOLT_CHEZ=/usr/local/bin/chez
JOLT=/path/to/jolt/bin/jolt

# codec and octet self-tests — no socket, no server
$JOLT -M:selftest

# the refinement decision procedure alone: every case it decides beside every
# case it must REFUSE. The boundary of the arithmetic -M:check discharges.
$JOLT -M:refine

# the static capability checker: TWO corpora + the two real protocol
# namespaces. No socket and no server; every program is checked statically,
# and every ACCEPTED one is then executed under a scripted handler (E15).
$JOLT -M:check

# differential oracle against jolt.nrepl's bencode
$JOLT -M:oracle

# start a real server (separate terminal, from any project dir)
$JOLT nrepl-server 7899

# the demo: real socket, then the same session code under two in-memory handlers
$JOLT -M:demo 7899
$JOLT -M:demo --offline      # skip the socket

# the second protocol: one HTTP/1.1 keep-alive driver under a scripted network
# (one octet per recv) and under a real loopback listener. Needs no server —
# it is both ends. Response octets are compared.
$JOLT -M:http 7900

# the no-I/O verifier: a full scripted session, and what it did or did not touch
$JOLT -M:noio
$JOLT -M:noio --touch-native   # positive control: one real connect()
$JOLT -M:noio --unhandled-native  # positive control for the LATCH: a native
                                  # crossing outside any handler, refused before
                                  # the syscall and unsalvageable afterwards

# the same thing under strace, with the control and a sensitivity check
JOLT=/path/to/jolt/bin/jolt dev/verify-noio.sh
```

`dev/run-demo.sh` does the whole sequence including starting and stopping the
server, and finishes with `verify-noio.sh`.

### How the no-I/O claim is measured

`jolt -M:noio` writes a marker, runs a complete scripted nREPL session (clone,
three evals, close, one octet per `recv`) printing nothing, then writes a closing
marker. `dev/verify-noio.sh` straces the process and reports every syscall
between the two markers.

```
RUN 1  scripted only            0 syscalls attributable to perturb in the window
                                (residual: 6 clock_gettime(CLOCK_PROCESS_CPUTIME_ID)
                                 from Chez's collector, printed, not filtered)
RUN 2  positive control         socket() + connect() + close() appear; native-log
       (--touch-native)         reads {:library-loads 1 :calls 3}
RUN 2b latch positive control   the SAME socket(2), written outside any handler,
       (--unhandled-native)     with the exception caught: 0 syscalls in the window
                                (refused before the crossing) and the run still
                                reports all-handled? false
RUN 3  leak-2 exhibit           exactly 3 write(2) calls — the console leak, sized
       (--print-inside)
RUN 4  instrument sensitivity   (load-library)            -> 0 syscalls
                                (load-library "libz.so.1") -> openat + mmap
```

RUN 2 is load-bearing: a clean trace means nothing unless the same instrument,
pointed at code that does the thing, shows it. RUN 4 is why there are two
instruments rather than one — `dlopen(NULL)` and `dlsym` issue no syscalls at
all, so library loading and symbol resolution are covered by
`perturb.posix/native-log` (an instrumented `load-library` plus per-binding call
counts) and by an absent-symbol canary bound with `defcfn` at namespace load,
while strace covers everything else.

### And what is no longer only measured

Everything above is a measurement of a window, and attribution by instrument:
the counter counts the nine bindings perturb declares, so a tenth path would be
invisible to it. Beside it there is now a **per-run invariant**. A native
crossing with no handler executing on the thread is **refused before the library
load, before symbol resolution and before the syscall**
(`perturb.posix/gate!` → `perturb.effect/native!`), and the refusal is
**latched** in the run's state, which nothing un-latches — so a caller that
catches the exception cannot make the run report success.
`perturb.effect/report` is the per-run verdict: no latched fault, no orphan
fault, and every symbol in a **required set** actually reached, so a run that
performed nothing cannot pass vacuously. `-M:noio` prints it and exits on it,
and RUN 2b is its positive control.

It is not a proof. It holds for the crossings that happened on the runs that
executed, and it does not cover a raw `jolt.ffi/defcfn` binding called without
going through `gate!` — the deliberately ungated `c-absent-canary` is the one
such binding in the artifact today.

## What runs

```
perturb.octet     unsigned octets 0..255; UTF-8 written over octets; the
                  interop seam that EXHIBITS what a Jolt byte array does
perturb.effect    declared effects; perform validates the handler's result or
                  aborts; no continuations (§1.4 / charter D4). Also the
                  fail-closed native gate, the run latch and the per-run report
perturb.wire      the socket effect, declared once. The only I/O the session
                  and codec code can name.
perturb.bencode   sans-io codec over octets, E4's :ok / :need-more / :invalid
                  trichotomy with the exact original cursor
perturb.cap       capability declarations, operation annotations, and an
                  observation ledger. Checks nothing — it is the SPECIFICATION
                  perturb.check reads.
perturb.ir        captures real Jolt IR from the compile spine (INHERITED I18)
perturb.check     the static capability checker. Ports the validated rule set in
                  docs/research/prototypes/ onto real IR and REJECTS.
perturb.corpus    the nREPL acceptance corpus: real perturb source, 25 programs
                  with the verdict each must get; every ACCEPT is also executed
perturb.http      the SECOND protocol. Sans-io HTTP/1.1, server side: three
                  capabilities, a typestate CYCLE, and an obligation §1.2
                  cannot state
perturb.httpcorpus  the HTTP acceptance corpus, 30 programs, plus
                  `declaration-corpus`: 10 hand-built machine/annotation pairs
                  that name no code, gating the DECLARATION language itself
perturb.refine    the refinement decision procedure: a ground linear fragment
                  over the integers, with everything outside it REFUSED
perturb.httpdemo  one keep-alive driver under both handlers, octets compared
perturb.nrepl     the session. Threads the connection affinely; drives I/O from
                  nothing but a :need-more.
perturb.posix     handler (a): real TCP, octets via jolt.ffi :uint8
perturb.script    handler (b): in-memory model server and transcript replay,
                  both delivering one octet per recv
perturb.oracle    differential test against jolt.nrepl (jolt-core/jolt/nrepl.clj:128)
perturb.selftest  codec/octet tests
perturb.noio      the no-I/O verifier for CLAIM 2: runs a full scripted session
                  between two markers so a tracer can see what it touched, and
                  reports the instrumented native-call log and the canary. Also
                  the per-run boundary invariant and its latch positive control
perturb.demo      the transcript and the per-claim evidence
```

## What running it found

Six things, none of which were visible from the design record. Full detail in
`docs/INHERITED.md` and `docs/SHAREABLE.md`; the transcript is `docs/TRANSCRIPT.md`.

1. **Jolt byte arrays store signed, they do not merely read signed.**
   `na-byte-of` narrows on store. §8's "bytevector *storage* is correct for
   both … storage is shareable; the accessor is where divergence lives" does
   not describe this baseline — the shared layer §8 proposes does not exist yet
   even as an implementation detail, and the byte-backing change §8 names as a
   first probe is a *prerequisite* for the boundary rather than a demonstration
   of it.
2. **`jolt.ffi`'s `:uint8` is the only unsigned-octet carrier reachable from
   Jolt-level code.** No bytevector is exposed. §1.5's stated representation
   ("bytevector-backed, unsigned, no conversion at the accessor") is not
   reachable from perturb today. The FFI type table offering `:int8` and
   `:uint8` over the same bits is, though, exactly §8's line already realised
   inside Jolt's own FFI.
3. **`clojure.core/char` caps at 0xFFFF**, so perturb text is BMP-only on this
   host — the JVM UTF-16 accident that charter §1.3 non-goal 1 names, blocking
   perturb's own correct decoder. A harder instance of §1.5's pattern than
   bytes: that one cost time, this one costs expressiveness.
4. **Jolt's reader rejects a bare `:` and a bare `|`**, the tokens E13's
   refinement notation uses. If refinements are ever written in source, the
   reader is on the critical path.
5. **The oracle had to model jolt's asymmetric text boundary** (dict keys are
   UTF-8-decoded, values stay latin1). An oracle relationship is not free of the
   other system's conventions, which is a mild qualification on §1.6's
   "existing corpora remain valid as value tests".
6. **`defcfn` does not resolve its C entry point at `def`.** It lowers to a
   memoised closure that resolves on first call — so the half of I11 that said
   "binding five syscalls at load is I/O" was never true, and the remaining half
   (one `load-library` call at load) was **invisible to strace**, because the
   no-argument form is `dlopen(NULL)` and issues no syscalls. Two consequences.
   First, the leak was real as control flow and unmeasurable as I/O, which is a
   fact about instruments: for this class of leak an instrumented `load-library`
   is sensitive and a syscall tracer is not. Second, perturb's fix now leans on
   a backend emission detail that `jolt.ffi`'s API docs do not state, and *when
   a foreign symbol is bound* is a language decision, not an ABI fact — logged
   as `INHERITED.md` I17 and as an amendment to `SHAREABLE.md` S6.

## The checker — what it rejects, and what it rejects that we wanted to keep

`jolt -M:check`. The verdict is static: no socket is opened and no program runs
before it is decided. Every ACCEPTED program is then EXECUTED under a scripted
handler, because the first accept set type-checked and every entry threw (E15).
It reads
`perturb.cap/checker-input` as the specification and real Jolt IR — captured from
the compile spine by `perturb.ir` — as the program. The judgements are ports of
`docs/research/prototypes/`, which were validated against artifacts perturb did
not author (jolt-hako's `ownership.pl` and `queries.json`).

Two corpora, 55 programs, all decided as recorded: `perturb.corpus` (25 — one
capability, a straight-line typestate) and `perturb.httpcorpus` (30 — two
capabilities live at once, a typestate cycle, an obligation), plus 10
declaration fixtures that check an annotation against a machine and read no body
at all. The program rejections
include use-after-close (`INHERITED.md` I16's example, verbatim), double-close,
use-after-move through an affine rebinding, a dangling connection at scope exit,
a conditional close, a loop that closes on its back edge, a capability captured
by a closure, a listener dropped while its connection is kept, a keep-alive loop
that recurs with a response still owed, and a response body that is never
finished, a body that declares six octets and writes three, and a body written
in a loop whose obligation cannot be discharged at all.

Capability specs are POSITIONED — `:arg n` on `:consumes`/`:borrows`, `:at [i]`
on `:produces` — which is what made the real client annotatable at all (E17),
and what lets `perturb.http/accept` say it consumes a Listener and produces a
Listener *and* a ServerConn at two different result positions.

The DECLARATION language grew its own rules, because E18 found four defects that
were not in the flow rules — those met a second protocol with two capabilities
unchanged — but in what a declaration could say. An operation is now an edge of
**as many machines as it moves**: the primitive table is keyed
`[capability operation]`, so `accept`, `respond-begin` and `body-finish!` each
declare an edge in two machines and each is compared against its annotation per
capability. A machine has **no pre-creation state** — `perturb.nrepl`'s
`:created` was a state nothing was ever in, and it is deleted. `:borrows` plus
`:produces` of the same capability is **refused at the annotation**, and so is a
`:consumes`/`:borrows` entry without `:arg`; the fallback that matched specs to
parameters in order is gone.

A **transition may carry a refinement**, which is the one place §1.2's typestate
axis and §1.3's arithmetic meet. `ResponseBody`'s `:open -> :finished` edge
carries `(= written declared)` as one extra key on its transition map, and
`perturb.check` discharges it against ghost state carried along with the
capability. Three outcomes and only three: proved, REFUTED with the two numbers
printed, and **REFUSED** — a rejection with its own diagnostic kind, for every
case outside the fragment `perturb.refine` decides (`jolt -M:refine` is that
procedure alone, with the cases it must refuse listed beside the cases it
decides). A body written in a **loop** is refused, and a checker that walked the
loop body once and believed the answer would accept it.

These two changes were built concurrently and merged, and each falsified part of
the other's limits list: the refinement work recorded "an operation that
advances two machines cannot be declared" and "`:borrows` and `:produces`
duplicates it", both of which the declaration work fixed; the declaration work
recorded "a state cannot carry a refinement", which the refinement work fixed.
What follows is what neither of them fixed.

**The most useful things it does are still the ones it cannot do.** See E18 in
`docs/research/PERTURB-DESIGN.md`, and the limits list `-M:check` prints:

- a refinement crosses neither a **loop** nor a **function** boundary, and there
  is no invariant syntax with which to supply one, so every such program is
  refused rather than decided;
- declaring that an operation advances two machines is not **checking** that it
  does: all nine of `perturb.http`'s transitions are still axioms, and nothing
  in §1.2 relates two machines in time — `body-finished-before-conn-reused` is
  still data with nothing to discharge it;
- an `:update` is an annotation on an axiom: nothing checks that `body-write`
  writes the octets it says it writes;
- a refused annotation means an **unchecked body** — the rejection is the
  refusal, and the body was never read;
- the caller of an operation whose annotation was refused is still analysed with
  it, so `uses-borrow-and-return` is still rejected for a leak it did not
  commit. Fixing that would be a change to a flow rule, not to the declaration
  language;
- `:perturb.cap/representation` is **gameable**: `perturb.http` has an empty list
  for each of three capabilities and 31 unchecked concrete-map accesses, against
  `perturb.nrepl`'s 5-entry list and 12 accesses. Counting operations counts the
  wrong thing.

E6 probe 1's join rule fires on the shape it was predicted to fire on
(`conditional-close`), which §1.2 records as an unquantified usability risk. It
fires **zero times** in `perturb.nrepl` — and on the **first driver anyone would
write** for HTTP keep-alive, `(if (keep-alive? req) c2 (close-conn! c2))`. An
accepted rewrite exists (close inside the branch, `recur` in the other, so one
arm is bottom) and is not discoverable from the diagnostic.

`-M:check` also prints, from the IR itself, the evidence for §1.1's untested
`:local` claim, and the list of things the checker cannot see. Read the second
list before believing an `ok`.

## The two logs

- **`docs/INHERITED.md`** — every place perturb leans on Jolt or Clojure
  behaviour it has *not decided to adopt*. The more important log: each
  unremarked inheritance is something a future self-host must reproduce or
  break. 16 entries.
- **`docs/SHAREABLE.md`** — Jolt/Chez internals perturb uses that are
  semantics-neutral and could plausibly be a shared lower layer (§8). 7
  entries, plus the list of candidates that were checked and rejected.

## Open

Whether this should become its own repository is **not settled here**. It
currently lives inside `jolt-sim` because that is where the design record is,
and because §7 says jolt-sim is input rather than authority — a subdirectory
makes that relationship easy to change later. Arguments both ways are in the
session report.
