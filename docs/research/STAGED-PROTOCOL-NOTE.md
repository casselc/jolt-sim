# A staged protocol universe: the idea, the precedents, and a falsifiable first step

**Status:** design note, nothing authorized. Kept out of `PERTURB-DESIGN.md`
deliberately — it proposes a different architecture from the one §1.2 records,
and it should earn a finding number by producing an artifact, not by being
written down.

---

## The idea, stated tightly

Protocols, sessions and capability state machines live in a **separate typed
universe** with its own surface syntax and its own elaborator — a compiler
distinct from the one that compiles ordinary perturb. That universe runs at
compile time, is checked on its own terms, and **shapes the program that is
emitted**: the capability declarations, the operation annotations, and possibly
the driver skeleton are *generated* rather than hand-written.

Two intuitions from the same sentence, both correct and worth separating:

- **Zig `comptime`** — a static stage that executes during compilation and emits
  the dynamic stage. The general theory is **multi-stage programming**: MetaML
  and MetaOCaml (Taha & Sheard), Terra (Lua staging a low-level language),
  Template Haskell. Two-level languages.
- **F\* / EverParse** — a *specification* universe that is erased at extraction,
  leaving only the program. The proofs do not ship.

## Why this is more interesting for perturb than for a generic language

Every failure from E15 to E19 has one root: **the annotation is a second source
of truth that can disagree with the code, and with the machine.**

| finding | the disagreement |
| --- | --- |
| E15 | `:produces` said "the connection", `request` returned `[conn frames]` — a false accept, found by *running* the accept set |
| E17 | the protocol layer became checkable and the implementation layer under it began failing |
| E18 1(a) | an operation advancing two machines could not be declared; the table silently kept one edge |
| E18 1(b) | the machine declared `:created`, a state nothing is ever in |
| E19 | a **third** two-machine operation, `respond-begin`, disagreed *silently* — it minted an undeclared capability and drew no diagnostic at all |
| E19 (this note's occasion) | `report-limits` item 8 asserted a silent false accept in the eliminators; measurement found none, and found a false *reject* on idiomatic destructuring instead |

Rules were added for each. A rule catches a disagreement; a **projection makes
it unrepresentable**. If one protocol declaration generated both the transition
table and the annotations, E18 1(a) and 1(b) could not have been written, and
E19's silent third operation could not exist — not because a rule caught it, but
because there would be nothing for the two sources to disagree about.

## The precedents, and what each one actually demonstrates

**EverParse / 3D** — the shape the user named. 3D is a separate C-like
*format description* language; the toolchain emits verified Low\* which extracts
to C. Deployed: every network packet in Hyper-V, ~100 message formats across four
proprietary protocols, replacing hand-written parsers. The spec is not annotation
on the program; the spec *is* the source and the program is output.
(Appendix D.7.)

**Scribble** — the same shape for protocols, and the one methodology in E20's
survey applied to real HTTP and SMTP. A global protocol → projection to endpoint
types → **generated** endpoint API, one channel class per protocol state (Hu &
Yoshida, FASE 2016). Typestate-as-types, generated rather than hand-written.
Note its trade, which E20 recorded: ordering is checked statically, **linearity
dynamically**, because the host had no linear types. (Appendix D.2.)

**Turnstile / "Type Systems as Macros"** (Chang, Knauth, Greenman, POPL 2017) —
the Lisp-native version, and the closest thing to a roadmap. The type checker
**is** the macroexpander: typing rules are macros, elaboration emits a checked
core. Racket's `#lang` machinery is the general form — a language with its own
reader, expander and static semantics lowering into the host. This has been
shipping in a Lisp for a decade.

**Nanopass** (Sarkar, Waddell, Dybvig, ICFP 2004; Keep & Dybvig, ICFP 2013) —
and Chez is itself nanopass-structured, so the substrate is already the right
shape. A defined-language grammar per pass with checks between passes is what
that framework exists for.

**P** (Microsoft) — a DSL for asynchronous event-driven state machines that
compiles to C/C\#, with model checking in the loop; used on Azure and S3.
Protocol state machines in a separate universe, verified there, generating the
implementation.

**Idris `Control.ST`** — the state machine is a type index, so it is a separate
universe interpreted by the elaborator, shaping the program. E20 called it the
closest existing design to what §1.2 wants. Idris 1 only. (Appendix D.3.)

## The two costs, and the second is ours specifically

**Blame across the boundary gets worse.** Q4 is already open on macro
provenance, and E20 found Racket has carried this problem for fifteen years
*with* full syntax-object provenance (Culpepper, Tobin-Hochstadt, Flatt, 2007).
Generated code makes every diagnostic point at code the user did not write, and
perturb's diagnostics naming the right thing is a property E18 1(d) and E19
worked specifically to obtain.

**We would lose what has actually been finding the bugs.** The checker's
credibility comes from reading real IR of code written by a human *for other
reasons* — `perturb.nrepl` was written as a working client and then checked, and
that is why its rejection was a finding rather than a demo. E15's false accept
was caught by **running** the accept set. Generate the client from a spec and
the checker is verifying its own output; the ledger and the run stage both stop
being independent evidence. F\*'s answer is erasure — the proof universe
vanishes. Ours cannot fully vanish, because §1.4's ledger and the executed
accept set are load-bearing.

**A third, smaller:** every system in the precedent list inherits its host's
module system, and perturb has no host to inherit one from (E20, and §4.6's
module-boundary item). A staged universe does not supply one.

## The falsifiable first step

Not a language. One namespace, no new theory:

> **`defmachine`** — a form that generates what `perturb.http` currently
> hand-writes: the capability declaration, the `:transitions` table, and the
> `:consumes` / `:borrows` / `:produces` / `:arg` / `:at` annotations on each
> operation.
>
> Then **diff the generated annotations against the hand-written ones**, and
> require the corpus to decide identically under both.

Three outcomes, all informative:

1. **Byte-identical, corpus unchanged.** The annotation language is derivable,
   which means it is redundant, which is the argument for the staged universe
   stated as a measurement rather than an aesthetic.
2. **They differ, and the generated one is right.** A fourth disagreement of the
   E18/E19 family, found by construction. Given that the hand-written set has
   been wrong three times, this is the outcome to expect.
3. **They differ, and the generated one is wrong.** The declaration carries
   information the machine description does not, which names exactly what a
   protocol language would have to add.

It costs one namespace and a gate stage, it cannot regress anything (the
existing annotations stay until the generated ones decide the corpus
identically), and it produces evidence either way. That is the standing method:
no claim stronger than its artifact.

## What to ask the literature, if a survey is running

- **Turnstile** — does the "type system as macroexpander" approach carry
  *linear* or *substructural* typing, or only conventional type systems? That is
  the load-bearing question for a Lisp-hosted capability tier.
- **3D → EverParse** — is the pipeline's spec language independently checked, or
  only checked via the F\* it emits? Determines whether the second universe needs
  its own metatheory or can borrow the first's.
- Has anyone generated **linearity / typestate annotations** — as opposed to
  APIs or parsers — from a protocol description? E20 found nobody typing the
  sans-io shape; this is the adjacent negative claim and it should be searched
  for properly rather than assumed.
