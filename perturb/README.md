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
| 2 | socket I/O goes through a declared effect with handlers; the same codec and session code run against a real socket and a scripted handler | `perturb/effect.clj`, `perturb/wire.clj`, `perturb/nrepl.clj` | **holds for the socket**, leaks at namespace load (`INHERITED.md` I11) and at console output (I12) |
| 3 | the connection is a `unique` capability with typestate, closed exactly once, annotated as data a future checker could consume | `perturb/cap.clj`, `perturb/nrepl.clj` | **annotations exist and the run obeys them**; nothing checks anything, and nothing here is evidence that a checker would |

**Claim 1 needs its qualification stated up front.** `PERTURB-DESIGN` §1.5 and §8
assume byte *storage* is octets and only the accessor folds the sign. On this
baseline that is false: `host/chez/java/natives-array.ss`'s `na-byte-of` narrows
on **store**, so a Jolt byte array holds signed values in a boxed vector.
Choosing a different accessor cannot recover octets. perturb reaches octets by
never building a byte array — native memory through `jolt.ffi`'s `:uint8` for
wire bytes, a range-checked persistent vector for heap values.

## Running it

Requires a Jolt checkout. `CHEZ=chez` fails on newer Makefiles; use the path.

```sh
export JOLT_CHEZ=/usr/local/bin/chez
JOLT=/path/to/jolt/bin/jolt

# codec and octet self-tests — no socket, no server
$JOLT -M:selftest

# differential oracle against jolt.nrepl's bencode
$JOLT -M:oracle

# start a real server (separate terminal, from any project dir)
$JOLT nrepl-server 7899

# the demo: real socket, then the same session code under two in-memory handlers
$JOLT -M:demo 7899
$JOLT -M:demo --offline      # skip the socket
```

`dev/run-demo.sh` does the whole sequence including starting and stopping the
server.

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
                  observation ledger. Checks nothing.
perturb.nrepl     the session. Threads the connection affinely; drives I/O from
                  nothing but a :need-more.
perturb.posix     handler (a): real TCP, octets via jolt.ffi :uint8
perturb.script    handler (b): in-memory model server and transcript replay,
                  both delivering one octet per recv
perturb.oracle    differential test against jolt.nrepl (jolt-core/jolt/nrepl.clj:128)
perturb.selftest  codec/octet tests
perturb.demo      the transcript and the per-claim evidence
```

## What running it found

Five things, none of which were visible from the design record. Full detail in
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
