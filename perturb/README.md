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
| 2 | socket I/O goes through a declared effect with handlers; the same codec and session code run against a real socket and a scripted handler | `perturb/effect.clj`, `perturb/wire.clj`, `perturb/nrepl.clj` | **holds for the socket, and a scripted run is now measured to perform no I/O** (`INHERITED.md` I11, closed). One leak remains and is deliberate: console output is unmediated `println` (I12), measured at 3 `write(2)` calls in the exhibit run |
| 3 | the connection is a `unique` capability with typestate, closed exactly once, annotated as data a future checker could consume | `perturb/cap.clj`, `perturb/nrepl.clj`, `perturb/check.clj` | **the checker exists and rejects** — `jolt -M:check` refuses use-after-close, double-close, use-after-move and a dangling connection, statically, over real Jolt IR. It also **rejects three functions of `perturb.nrepl` itself**; see below |

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

# the static capability checker: corpus + the real client. No socket, no server,
# and nothing in the corpus is ever called — it is checked, not run.
$JOLT -M:check

# differential oracle against jolt.nrepl's bencode
$JOLT -M:oracle

# start a real server (separate terminal, from any project dir)
$JOLT nrepl-server 7899

# the demo: real socket, then the same session code under two in-memory handlers
$JOLT -M:demo 7899
$JOLT -M:demo --offline      # skip the socket

# the no-I/O verifier: a full scripted session, and what it did or did not touch
$JOLT -M:noio
$JOLT -M:noio --touch-native   # positive control: one real connect()

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

## What runs

```
perturb.octet     unsigned octets 0..255; UTF-8 written over octets; the
                  interop seam that EXHIBITS what a Jolt byte array does
perturb.effect    declared effects; perform validates the handler's result or
                  aborts; no continuations (§1.4 / charter D4)
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
perturb.corpus    the acceptance corpus: real perturb source, never called,
                  17 programs with the verdict each one must get
perturb.nrepl     the session. Threads the connection affinely; drives I/O from
                  nothing but a :need-more.
perturb.posix     handler (a): real TCP, octets via jolt.ffi :uint8
perturb.script    handler (b): in-memory model server and transcript replay,
                  both delivering one octet per recv
perturb.oracle    differential test against jolt.nrepl (jolt-core/jolt/nrepl.clj:128)
perturb.selftest  codec/octet tests
perturb.noio      the no-I/O verifier for CLAIM 2: runs a full scripted session
                  between two markers so a tracer can see what it touched, and
                  reports the instrumented native-call log and the canary
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

`jolt -M:check`. It is static: nothing in the corpus is called, no socket is
opened, and the verdict is reached before any of it could run. It reads
`perturb.cap/checker-input` as the specification and real Jolt IR — captured from
the compile spine by `perturb.ir` — as the program. The judgements are ports of
`docs/research/prototypes/`, which were validated against artifacts perturb did
not author (jolt-hako's `ownership.pl` and `queries.json`).

Corpus: 17 programs, 6 accepted and 11 rejected, all as recorded. The rejections
include use-after-close (`INHERITED.md` I16's example, verbatim), double-close,
use-after-move through an affine rebinding, a dangling connection at scope exit,
a conditional close, a loop that closes on its back edge, and a capability
captured by a closure.

**It also rejects three functions of `perturb.nrepl`, and that is the most useful
thing it does.** `clone-session`, `eval-code` and `session` all fail, at four
sites, for one reason: a connection crosses a function boundary. §1.2's
`:consumes` / `:produces` are *unpositioned*, so a function that returns
`[conn value]` — which is exactly how this client threads the connection — cannot
be annotated at all. `perturb.corpus/ping` is the same helper returning the
connection bare and is accepted; `perturb.corpus/ping-tuple` is that helper with
the pair put back, annotated identically, and is rejected. That is E13's
"composition needs abstract refinements §1.2 does not have" landing on running
code, and no rule was weakened to soften it.

E6 probe 1's join rule also fires on the shape it was predicted to fire on
(`conditional-close`), which §1.2 records as an unquantified usability risk. On
this client it fires zero times outside the corpus: `perturb.nrepl` has one
`if`, in a `loop`, and treating the `recur` arm as unreachable at the join —
the one judgement this checker adds that the Python prototypes never needed —
is what keeps it from firing there.

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
