# Transcript — the run this artifact is evidence from

Captured verbatim. Reproduce with `dev/run-demo.sh`.

```
host      Linux x86_64
chez      10.4.1
jolt      v0.4.15-209-g22186094 (commit 22186094)
server    jolt nrepl-server 7899   (jolt-core/jolt/nrepl.clj)
invoked   JOLT_CHEZ=/usr/local/bin/chez jolt -M:selftest | -M:oracle | -M:demo 7899
          then dev/verify-noio.sh (strace -f)
```

The `-M:demo` block below is from the run that produced the wire transcript; its
CLAIM 2 section was re-captured after `INHERITED.md` I11 was closed, which is why
its session id differs from the wire transcript's. The `verify-noio.sh` block at
the end is verbatim from one invocation.

## `jolt -M:selftest`

```
== perturb.octet ==
  ok   octet? rejects -1
  ok   octet? accepts 255
  ok   octet? rejects 256
  ok   construction rejects a signed byte
  ok   oref returns 0..255, no fold
  ok   the same values through a jolt byte array read back signed
  ok   the fold recovers them — this is the work perturb's path never does
== perturb.octet UTF-8 ==
  ok   ascii
  ok   U+03BB lambda
  ok   lambda roundtrips to a host string
  ok   decoder handles an astral code point (U+1D11E)
  ok   but ->str cannot build the host string for it (INHERITED I2)
  ok   overlong 2-byte form rejected
  ok   surrogate rejected
  ok   truncated 3-byte rejected
== perturb.bencode roundtrips ==
  ok   int -> :ok at exact frame end
  ok   int roundtrip
  ok   negative int -> :ok at exact frame end
  ok   negative int roundtrip
  ok   zero -> :ok at exact frame end
  ok   zero roundtrip
  ok   string -> :ok at exact frame end
  ok   string roundtrip
  ok   empty list -> :ok at exact frame end
  ok   empty list roundtrip
  ok   list -> :ok at exact frame end
  ok   list roundtrip
  ok   dict -> :ok at exact frame end
  ok   dict roundtrip
  ok   nested dict -> :ok at exact frame end
  ok   nested dict roundtrip
  ok   high octets survive as text -> :ok at exact frame end
  ok   high octets survive as text roundtrip
== perturb.bencode trichotomy (E4) ==
  ok   dict — all 33 proper prefixes are [:need-more 0]
  ok   nested list — all 22 proper prefixes are [:need-more 0]
  ok   trailing garbage does not affect the frame
  ok   invalid leading octet
  ok   non-digit in integer
== raw byte strings stay octets ==
  ok   a byte string above 0x7f decodes to octets, not a signed anything

SELFTEST OK
```

## `jolt -M:oracle`

```
== differential oracle: perturb.bencode vs jolt.nrepl (jolt-core/jolt/nrepl.clj:128) ==
  20 values, 0 disagreement(s)

  checks per value:
    1. encoders agree octet for octet
    2. perturb decodes jolt's octets back to the original value
    3. jolt decodes perturb's octets to jolt's own rendering of them

  scope: this compares the OVERLAP only. It says nothing about
  perturb's raw byte-string type, its octet decoding of bencode
  strings, or any divergence in PERTURB-DESIGN §2. Check 3 needed a
  model of jolt's asymmetric text boundary (see `jolt-view`); that
  asymmetry is an oracle finding, not a disagreement.
```

## `jolt -M:demo 7899` — against a live jolt nREPL server

```
perturb — nREPL client over a declared socket effect
forms to evaluate: ["(+ 1 2)" "(clojure.string/upper-case \"perturb\")" "(str \"lambda is \" \\u03bb)"]

=== RUN A — real POSIX socket ===========================================
  connecting to 127.0.0.1:7899
  => "3"
  => "\"PERTURB\""
  => "\"lambda is λ\""

=== RUN B — scripted in-memory handler (1 octet per recv) ===============
  => "scripted<(+ 1 2)>"
  => "scripted<(clojure.string/upper-case \"perturb\")>"
  => "scripted<(str \"lambda is \" \\u03bb)>"

=== RUN C — replay of RUN A's recorded octets, rechunked to 1 per recv ==
  => "3"
  => "\"PERTURB\""
  => "\"lambda is λ\""

  values identical to RUN A: true
  bytes SENT identical to RUN A, octet for octet: true  (4 frames, 223 octets)
  -> the same encoder produced the same wire bytes under a
     handler that is not a socket, and the same decoder read
     them back one octet at a time.

=== WIRE TRANSCRIPT (real socket) =======================================
  connect 127.0.0.1:7899
  send  site=:perturb.nrepl/send (20 octets)
    hex   64 32 3a 69 64 31 3a 31 32 3a 6f 70 35 3a 63 6c 6f 6e 65 65
    ascii d2:id1:12:op5:clonee
  recv  site=:perturb.nrepl/recv (62 octets)
    hex   64 32 3a 69 64 31 3a 31 31 31 3a 6e 65 77 2d 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 37 3a 73 65 73 73 69 6f 6e 34 3a 6e 6f 6e 65 36 3a 73 74 61 74 75 73 6c 34 3a 64 6f 6e 65 65 65
    ascii d2:id1:111:new-session6:jolt-67:session4:none6:statusl4:doneee
  send  site=:perturb.nrepl/send (51 octets)
    hex   64 34 3a 63 6f 64 65 37 3a 28 2b 20 31 20 32 29 32 3a 69 64 31 3a 32 32 3a 6f 70 34 3a 65 76 61 6c 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 65
    ascii d4:code7:(+ 1 2)2:id1:22:op4:eval7:session6:jolt-6e
  recv  site=:perturb.nrepl/recv (62 octets)
    hex   64 32 3a 69 64 31 3a 32 32 3a 6e 73 34 3a 75 73 65 72 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 36 3a 73 74 61 74 75 73 6c 34 3a 64 6f 6e 65 65 35 3a 76 61 6c 75 65 31 3a 33 65
    ascii d2:id1:22:ns4:user7:session6:jolt-66:statusl4:donee5:value1:3e
  send  site=:perturb.nrepl/send (82 octets)
    hex   64 34 3a 63 6f 64 65 33 37 3a 28 63 6c 6f 6a 75 72 65 2e 73 74 72 69 6e 67 2f 75 70 70 65 72 2d 63 61 73 65 20 22 70 65 72 74 75 72 62 22 29 32 3a 69 64 31 3a 33 32 3a 6f 70 34 3a 65 76 61 6c 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 65
    ascii d4:code37:(clojure.string/upper-case "perturb")2:id1:32:op4:eval7:session6:jolt-6e
  recv  site=:perturb.nrepl/recv (70 octets)
    hex   64 32 3a 69 64 31 3a 33 32 3a 6e 73 34 3a 75 73 65 72 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 36 3a 73 74 61 74 75 73 6c 34 3a 64 6f 6e 65 65 35 3a 76 61 6c 75 65 39 3a 22 50 45 52 54 55 52 42 22 65
    ascii d2:id1:32:ns4:user7:session6:jolt-66:statusl4:donee5:value9:"PERTURB"e
  send  site=:perturb.nrepl/send (70 octets)
    hex   64 34 3a 63 6f 64 65 32 35 3a 28 73 74 72 20 22 6c 61 6d 62 64 61 20 69 73 20 22 20 5c 75 30 33 62 62 29 32 3a 69 64 31 3a 34 32 3a 6f 70 34 3a 65 76 61 6c 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 65
    ascii d4:code25:(str "lambda is " \u03bb)2:id1:42:op4:eval7:session6:jolt-6e
  recv  site=:perturb.nrepl/recv (76 octets)
    hex   64 32 3a 69 64 31 3a 34 32 3a 6e 73 34 3a 75 73 65 72 37 3a 73 65 73 73 69 6f 6e 36 3a 6a 6f 6c 74 2d 36 36 3a 73 74 61 74 75 73 6c 34 3a 64 6f 6e 65 65 35 3a 76 61 6c 75 65 31 34 3a 22 6c 61 6d 62 64 61 20 69 73 20 ce bb 22 65
    ascii d2:id1:42:ns4:user7:session6:jolt-66:statusl4:donee5:value14:"lambda is .."e
  close

=== CLAIM 1 — wire bytes are unsigned octets, 0..255 ====================
  PERTURB-DESIGN §1.5. No unchecked-byte fold anywhere on the path.

  received 270 octets across 4 recv() calls
  octets above 0x7f on the wire: 2
  first at offset 266, window [262,270):
    perturb   oref -> [32 105 115 32 206 187 34 101]
    jolt      aget over (byte-array ...) -> [32 105 115 32 -50 -69 34 101]
    the two differ above 0x7f. perturb never built the byte array.

  what perturb.posix actually does:
    send: (ffi/write p :uint8 i (o/oref ov i))   -> foreign-set! 'unsigned-8
    recv: (ffi/read  p :uint8 i)                 -> foreign-ref  'unsigned-8
  what jolt.nrepl does:
    send: (byte-array (map int s)) then write-array  -> na-byte-of narrows on STORE
    recv: (String. (ffi/read-array buf n) "ISO-8859-1")

  measured on this baseline:
    (o/oref (o/octets [200 255 128]) i) = [200 255 128]
    (aget (byte-array [200 255 128]) i)  = [-56 -1 -128]
    -> jolt byte arrays store signed (natives-array.ss na-byte-of),
       so this is not an accessor perturb can swap out. See SHAREABLE.md.

=== CLAIM 2 — I/O goes through a declared effect, same code, two handlers 
  PERTURB-DESIGN §1.4. Handlers substitute a validated result or abort.

  the effect, as declared data:
    :close  arity 1
    :connect  arity 2
    :recv  arity 2
    :send  arity 2

  one var ran under every handler below:
    #'perturb.nrepl/session

  handler posix (real socket)
    session     "jolt-6"
    values      ["3" "\"PERTURB\"" "\"lambda is λ\""]
    effect ops  {:connect 1, :send 4, :recv 4, :close 1}
    perform sites [:perturb.nrepl/connect :perturb.nrepl/send :perturb.nrepl/recv :perturb.nrepl/close]
  handler script (model server)
    session     "scripted-session-1"
    values      ["scripted<(+ 1 2)>" "scripted<(clojure.string/upper-case \"perturb\")>" "scripted<(str \"lambda is \" \\u03bb)>"]
    effect ops  {:connect 1, :send 4, :recv 384, :close 1}
    perform sites [:perturb.nrepl/connect :perturb.nrepl/send :perturb.nrepl/recv :perturb.nrepl/close]
  handler script (replay of A)
    session     "jolt-6"
    values      ["3" "\"PERTURB\"" "\"lambda is λ\""]
    effect ops  {:connect 1, :send 4, :recv 270, :close 1}
    perform sites [:perturb.nrepl/connect :perturb.nrepl/send :perturb.nrepl/recv :perturb.nrepl/close]

  the handlers are NOT interchangeable in what they do:
    posix (real socket) -> recv calls: 4
    script (model server) -> recv calls: 384
    script (replay of A) -> recv calls: 270
  the scripted handlers deliver one octet per recv, so the same driver
  crosses the :need-more arm hundreds of times where the socket crossed it
  a handful. E4's exact-original-cursor contract is what makes that safe.

  handler-result validation (the `validated` in §1.4):
    a handler returning a string from :recv -> :invalid-result
    an unhandled effect -> :unhandled-effect

  I11 IS CLOSED — the native binding is lazy and handler-local:
    perturb.posix no longer calls load-library at namespace load. It
    loads no library and resolves no C symbol until a `sys-*` wrapper
    runs, and those are reached only from the handler, which is reached
    only from perturb.effect/perform. Measured on THIS run —
    perturb.posix/native-log sampled at each stage:
      at startup            {:library-loads 0, :calls 0, :by-op {}}
      after RUN A           {:library-loads 1, :calls 11, :by-op {:socket 1, :connect 1, :send 4, :recv 4, :close 1}}
      after RUN B           {:library-loads 1, :calls 11, :by-op {:socket 1, :connect 1, :send 4, :recv 4, :close 1}}
      after RUN C           {:library-loads 1, :calls 11, :by-op {:socket 1, :connect 1, :send 4, :recv 4, :close 1}}
    a scripted run adds nothing to that log; only the socket run does.

    that `def` resolves no C entry point is not an assumption — the
    absent-symbol canary is bound by `defcfn` at namespace load and
    names a symbol that exists in no object in this process:
      requiring perturb.posix succeeded (this program is running)
      (perturb.posix/absent-canary-probe) -> [:threw "#object[:object]"]
    -> resolution happens at CALL. Zero native calls is zero symbols.

    process-level check: `jolt -M:noio` runs a complete scripted session
    between two marker writes; `dev/verify-noio.sh` straces it and shows
    zero syscalls attributable to perturb in that window, with a positive
    control (--touch-native) that does fire. See INHERITED I11.

  I12 IS STILL OPEN, AND NOW MEASURED — console output is unmediated:
    every line of this transcript is a write(2) outside any handler.
    dev/verify-noio.sh RUN 3 counts them exactly. Left unrouted on
    purpose: an effect does not remove I/O, it makes I/O SUBSTITUTABLE.
    The socket effect earns that because RUN B and RUN C are a second
    and third implementation of the same interface running the same var.
    Nothing in perturb consumes perturb's console output, so a console
    handler would have no second implementation to be checked against —
    it would move the write(2) behind a name without adding a fact.
    Recorded, not dropped: INHERITED I12.

=== CLAIM 3 — capability discipline, hand-annotated, NOT checked ========
  PERTURB-DESIGN §1.2. Annotations are data; no checker exists or is built.

  declared capabilities:
    perturb.nrepl/Connection
      uniqueness :unique  linearity :once  contention :thread-confined
      typestate  [:created :active :closed] initial :created terminal :closed
    perturb.posix/NativeBuffer
      uniqueness :unique  linearity :once  contention :thread-confined
      typestate  [:allocated :freed] initial :allocated terminal :freed

  operation annotations (also on each var's metadata):
    perturb.nrepl/open  {:consumes [], :produces [{:cap perturb.nrepl/Connection, :state :active}]}
    perturb.nrepl/request  {:consumes [{:cap perturb.nrepl/Connection, :state :active}], :produces [{:cap perturb.nrepl/Connection, :state :active}]}
    perturb.nrepl/close!  {:consumes [{:cap perturb.nrepl/Connection, :state :active}], :produces [{:cap perturb.nrepl/Connection, :state :closed}]}

  observed ledger, per capability instance:
    perturb-conn-1  [nil :created :active :active :active :active :active :closed]
    perturb-conn-2  [nil :created :active :active :active :active :active :closed]
    perturb-conn-3  [nil :created :active :active :active :active :active :closed]

    connections opened: 3
    reached :closed exactly once each: [1 1 1]
    native buffers alloc/free pairs: 8 freed / 8 allocated

  READ THIS AS EVIDENCE OF ITS ACTUAL STRENGTH: the ledger is an
  observation of one run, and the annotations are hand-written. Nothing
  above rejects anything — perturb.cap/note! would happily record a
  transition the declared machine forbids. The affine threading in
  perturb.nrepl (each op consumes the connection and returns its
  successor) is what makes use-after-close hard to write; it is not
  what makes it impossible.

  checker-input keys emitted for a future checker:  [:perturb.cap/declarations :perturb.cap/operations :perturb.cap/ledger]

=== END =================================================================
  logs: perturb/docs/SHAREABLE.md, perturb/docs/INHERITED.md
```

## `dev/verify-noio.sh` — INHERITED I11's process-level check

Run under `strace -f`. `jolt -M:noio` writes `PERTURB-NOIO-BEGIN`, runs a complete
scripted nREPL session printing nothing, then writes `PERTURB-NOIO-END`; the
script reports every syscall between the two writes. RUN 2 is the positive
control — without it the clean window in RUN 1 would not be evidence. RUN 4 shows
why there are two instruments: `dlopen(NULL)` is free of syscalls, so strace
could never have seen the leak I11 named.

```
========================================================================
RUN 1 — scripted only. Nothing in the window may be perturb's.
========================================================================
--- jolt -M:noio
    mode: scripted only
    
      namespaces loaded, including perturb.posix and perturb.demo
      scripted session id   "scripted-session-1"
      scripted values       ["scripted<(+ 1 2)>" "scripted<(clojure.string/upper-case \"perturb\")>" "scripted<(str \"lambda is \" \\u03bb)>"]
      effect ops performed  {:connect 1, :send 4, :recv 384, :close 1}
    
      perturb.posix/native-log — the instrumented load-library and the
      five syscall bindings, counted at the only place that reaches them:
        {:library-loads 0, :calls 0, :by-op {}}
    
      absent-symbol canary (perturb.posix/c-absent-canary):
        this namespace required perturb.posix and loaded -> a defcfn `def`
        resolved no entry point, or that require would have failed.
        calling it now -> [:threw "#object[:object]"]
        -> resolution happens at CALL, so the `:calls` count above is also
           the count of C symbols this process resolved from perturb.posix.
    
      VERDICT (in-process): scripted run loaded no library and resolved no symbol

  syscalls in the window: 6 total
    6  clock_gettime(CLOCK_PROCESS_CPUTIME_ID)  — Chez collector accounting
    0  attributable to perturb
  (full window, unfiltered:)
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=837087102}) = 0
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=838778124}) = 0
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=858119048}) = 0
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=859201773}) = 0
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=878636555}) = 0
    31347 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=879103713}) = 0

========================================================================
RUN 2 — POSITIVE CONTROL. Same shape, one real connect() in the window.
========================================================================
--- jolt -M:noio --touch-native
    mode: POSITIVE CONTROL (--touch-native)
    
      namespaces loaded, including perturb.posix and perturb.demo
      scripted session id   "scripted-session-1"
      scripted values       ["scripted<(+ 1 2)>" "scripted<(clojure.string/upper-case \"perturb\")>" "scripted<(str \"lambda is \" \\u03bb)>"]
      effect ops performed  {:connect 1, :send 4, :recv 384, :close 1}
      positive control      [:aborted ":handler-abort"]
    
      perturb.posix/native-log — the instrumented load-library and the
      five syscall bindings, counted at the only place that reaches them:
        {:library-loads 1, :calls 3, :by-op {:socket 1, :connect 1, :close 1}}
    
      absent-symbol canary (perturb.posix/c-absent-canary):
        this namespace required perturb.posix and loaded -> a defcfn `def`
        resolved no entry point, or that require would have failed.
        calling it now -> [:threw "#object[:object]"]
        -> resolution happens at CALL, so the `:calls` count above is also
           the count of C symbols this process resolved from perturb.posix.
    
      VERDICT (in-process): control fired — the instrument is live

  syscalls in the window: 9 total
    6  clock_gettime(CLOCK_PROCESS_CPUTIME_ID)  — Chez collector accounting
    3  attributable to perturb
  the 3 attributable syscalls:
    31384 socket(AF_INET, SOCK_STREAM, IPPROTO_IP) = 5
    31384 connect(5, {sa_family=AF_INET, sin_port=htons(9), sin_addr=inet_addr("127.0.0.1")}, 16) = -1 ECONNREFUSED (Connection refused)
    31384 close(5)                          = 0
  (full window, unfiltered:)
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=766477175}) = 0
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=768145461}) = 0
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=786436107}) = 0
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=787058122}) = 0
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=804054698}) = 0
    31384 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=804488328}) = 0
    31384 socket(AF_INET, SOCK_STREAM, IPPROTO_IP) = 5
    31384 connect(5, {sa_family=AF_INET, sin_port=htons(9), sin_addr=inet_addr("127.0.0.1")}, 16) = -1 ECONNREFUSED (Connection refused)
    31384 close(5)                          = 0

========================================================================
RUN 3 — LEAK 2 (INHERITED I12), measured. Same scripted run, but the three
  values are printed INSIDE the window with unmediated println.
========================================================================
--- jolt -M:noio --print-inside
    mode: LEAK 2 EXHIBIT (--print-inside)
    
      namespaces loaded, including perturb.posix and perturb.demo
      scripted session id   "scripted-session-1"
      scripted values       ["scripted<(+ 1 2)>" "scripted<(clojure.string/upper-case \"perturb\")>" "scripted<(str \"lambda is \" \\u03bb)>"]
      effect ops performed  {:connect 1, :send 4, :recv 384, :close 1}
    
      perturb.posix/native-log — the instrumented load-library and the
      five syscall bindings, counted at the only place that reaches them:
        {:library-loads 0, :calls 0, :by-op {}}
    
      absent-symbol canary (perturb.posix/c-absent-canary):
        this namespace required perturb.posix and loaded -> a defcfn `def`
        resolved no entry point, or that require would have failed.
        calling it now -> [:threw "#object[:object]"]
        -> resolution happens at CALL, so the `:calls` count above is also
           the count of C symbols this process resolved from perturb.posix.
    
      VERDICT (in-process): scripted run loaded no library and resolved no symbol

  syscalls in the window: 9 total
    6  clock_gettime(CLOCK_PROCESS_CPUTIME_ID)  — Chez collector accounting
    3  attributable to perturb
  the 3 attributable syscalls:
    32147 write(1, "  => \"scripted<(+ 1 2)>\"\n", 25) = 25
    32147 write(1, "  => \"scripted<(clojure.string/u"..., 57) = 57
    32147 write(1, "  => \"scripted<(str \\\"lambda is "..., 46) = 46
  (full window, unfiltered:)
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=187494408}) = 0
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=188877874}) = 0
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=206376660}) = 0
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=206956726}) = 0
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=223700205}) = 0
    32147 clock_gettime(CLOCK_PROCESS_CPUTIME_ID, {tv_sec=8, tv_nsec=224086993}) = 0
    32147 write(1, "  => \"scripted<(+ 1 2)>\"\n", 25) = 25
    32147 write(1, "  => \"scripted<(clojure.string/u"..., 57) = 57
    32147 write(1, "  => \"scripted<(str \\\"lambda is "..., 46) = 46

========================================================================
WHOLE-PROCESS network syscalls (not just the window)
========================================================================
  clean: 0
  ctl: 2
  loud: 0

========================================================================
RUN 4 — instrument sensitivity: what a REAL library load looks like.
  (jolt.ffi/load-library)             -> dlopen(NULL), no syscalls
  (jolt.ffi/load-library "libz.so.1") -> openat + mmap, plainly visible
========================================================================
  load-library noarg -> 0 syscalls in window
  load-library named -> 14 syscalls in window
    483   openat(AT_FDCWD, "/etc/ld.so.cache", O_RDONLY|O_CLOEXEC) = 5
    483   fstat(5, {st_mode=S_IFREG|0644, st_size=34091, ...}) = 0
    483   mmap(NULL, 34091, PROT_READ, MAP_PRIVATE, 5, 0) = 0x7f469bfae000
    483   close(5)                          = 0
    483   openat(AT_FDCWD, "/lib/x86_64-linux-gnu/libz.so.1", O_RDONLY|O_CLOEXEC) = 5
    483   read(5, "\177ELF\2\1\1\0\0\0\0\0\0\0\0\0\3\0>\0\1\0\0\0\0\0\0\0\0\0\0\0"..., 832) = 832
    483   fstat(5, {st_mode=S_IFREG|0644, st_size=113000, ...}) = 0
    483   mmap(NULL, 110744, PROT_READ, MAP_PRIVATE|MAP_DENYWRITE, 5, 0) = 0x7f469be73000

========================================================================
VERDICT
========================================================================
  PASS  scripted run: 0 syscalls attributable to perturb between the markers
        (residual is Chez collector clock_gettime only, printed above)
  PASS  positive control: 2 socket/connect calls in the window
        -> the instrument is live, so the clean window is a measurement
  NOTE  leak 2 exhibit: 3 write() syscalls in the window when the same
        scripted run prints its three values. Console output is real,
        unmediated I/O and this is its exact size (INHERITED I12).
```

## `jolt -M:check` — the static capability checker

Static. Nothing in `perturb.corpus` is called; no socket is opened. Reproduce
with `jolt -M:check` (also run by `dev/run-demo.sh`).

```
========================================================================
perturb.check — static capability checking over real Jolt IR
========================================================================

  capabilities declared : [perturb.nrepl/Connection]
  operations annotated  : ["perturb.corpus/ping" "perturb.corpus/ping-tuple" "perturb.nrepl/close!" "perturb.nrepl/open" "perturb.nrepl/request"]
  machine primitives    : ["perturb.nrepl/close!" "perturb.nrepl/open" "perturb.nrepl/request"]
  IR defs captured      : 97

== corpus ==============================================================
   real perturb source, compiled by Jolt, checked from its own IR

  [ok  ] perturb.corpus/open-request-close  expected accept, got accept
  [ok  ] perturb.corpus/shadowed-rebind  expected accept, got accept
  [ok  ] perturb.corpus/both-arms-close  expected accept, got accept
  [ok  ] perturb.corpus/loop-of-requests  expected accept, got accept
  [ok  ] perturb.corpus/ping  expected accept, got accept
  [ok  ] perturb.corpus/uses-ping  expected accept, got accept
  [ok  ] perturb.corpus/use-after-close  expected reject, got reject  ["use-after-move"]
  [ok  ] perturb.corpus/double-close  expected reject, got reject  ["typestate"]
  [ok  ] perturb.corpus/use-after-move  expected reject, got reject  ["use-after-move"]
  [ok  ] perturb.corpus/dangling-connection  expected reject, got reject  ["dangling"]
  [ok  ] perturb.corpus/shadowing-hides-a-leak  expected reject, got reject  ["dangling"]
  [ok  ] perturb.corpus/conditional-close  expected reject, got reject  ["join"]
  [ok  ] perturb.corpus/conditional-close-then-use  expected reject, got reject  ["join" "use-after-move"]
  [ok  ] perturb.corpus/loop-that-closes  expected reject, got reject  ["dangling" "loop-not-preserving"]
  [ok  ] perturb.corpus/helper-without-a-signature  expected reject, got reject  ["untracked-consume"]
  [ok  ] perturb.corpus/ping-tuple  expected reject, got reject  ["dangling" "escape" "produces-mismatch"]
  [ok  ] perturb.corpus/capture-in-closure  expected reject, got reject  ["capture" "dangling"]

  17/17 decided as recorded

  the first rejection, in full:

  use-after-move  perturb.nrepl/Connection
    capability    `c` : perturb.nrepl/Connection@:active, bound at perturb/src/perturb/corpus.clj:90:11
    consumed by   perturb.nrepl/close!  at perturb/src/perturb/corpus.clj:91:5
    used again at perturb/src/perturb/corpus.clj:92:5  (argument to perturb.nrepl/request)
    in            perturb.corpus/use-after-close

== the real nREPL client ===============================================
   perturb.nrepl, unmodified, checked by the same rules. This is NOT a
   gate: it is the measurement §1.2 and §4.6 say has never been taken.

  [ok  ] perturb.nrepl/connection-capability
  [ok  ] perturb.nrepl/id-counter
  [ok  ] perturb.nrepl/fresh-id
  [ok  ] perturb.nrepl/conn
  [ok  ] perturb.nrepl/state
  [ok  ] perturb.nrepl/conn-id
  [ok  ] perturb.nrepl/compact
  [ok  ] perturb.nrepl/read-frame
  [ok  ] perturb.nrepl/done?
  [NO  ] perturb.nrepl/clone-session  ["untracked-consume"]
  [NO  ] perturb.nrepl/eval-code  ["untracked-consume"]
  [NO  ] perturb.nrepl/session  ["dangling" "no-signature" "untracked-consume"]

  --- perturb.nrepl/clone-session
  untracked-consume  perturb.nrepl/Connection
    operation     perturb.nrepl/request consumes perturb.nrepl/Connection@:active
    at            perturb/src/perturb/nrepl.clj:168:16
    but no argument is a tracked capability of that type
    arguments     `c`, a map expression
    in            perturb.nrepl/clone-session
  --- perturb.nrepl/eval-code
  untracked-consume  perturb.nrepl/Connection
    operation     perturb.nrepl/request consumes perturb.nrepl/Connection@:active
    at            perturb/src/perturb/nrepl.clj:179:16
    but no argument is a tracked capability of that type
    arguments     `c`, a map expression
    in            perturb.nrepl/eval-code
  --- perturb.nrepl/session
  no-signature  perturb.nrepl/Connection
    callee        perturb.nrepl/clone-session  declares no capability signature
    argument      `c0` : perturb.nrepl/Connection@:active
    at            perturb/src/perturb/nrepl.clj:199:12
    a capability may not be passed to a function that does not
    declare :consumes / :borrows / :produces for it
    in            perturb.nrepl/session
  untracked-consume  perturb.nrepl/Connection
    operation     perturb.nrepl/close! consumes perturb.nrepl/Connection@:active
    at            perturb/src/perturb/nrepl.clj:204:18
    but no argument is a tracked capability of that type
    arguments     `c`
    in            perturb.nrepl/session
  dangling  perturb.nrepl/Connection
    capability    `c0` : perturb.nrepl/Connection@:active
    bound at      perturb/src/perturb/nrepl.clj:198:12
    goes out of scope at the end of the let without reaching a terminal state
    in            perturb.nrepl/session
  3 of 12 checkable functions in perturb.nrepl are REJECTED.
  3 are primitives of the declared machine and were not checked.

== §2.1, now measured ==================================================
   "`:local` carries a name, not binding identity — linearity checking
    needs alpha-conversion or a `:binding-id`." PERTURB-DESIGN §1.1 states
   this from reading jolt-core/jolt/ir.clj; §4 records it as UNTESTED and
   says to assume it may be wrong until a checker walks real IR.

   perturb.corpus/shadowed-rebind binds three DIFFERENT Connection
   instances to one name. Its real IR, as the back end received it:

     :let binding names     ["c" "c" "c"]   <- three separate bindings
     :local nodes naming c  2, every one of them exactly {:op :local, :name "c"}
     a :binding-id key?     false

   Three capability instances, one node shape, no :binding-id and no
   alpha-renaming: the analyzer's lexical env is a SET of names
   (jolt-core/jolt/analyzer.clj:84-86), so a shadowing binding reuses the
   name outright. THE CLAIM HOLDS. The checker therefore allocates its
   own binding id at every binding occurrence and keys linearity on that;
   perturb.corpus/shadowing-hides-a-leak is the program a name-keyed
   checker would wrongly accept, and it is in the reject corpus above.

  WHAT THIS CHECKER CANNOT SEE
  
    1. An operation in a capability's declared :transitions is an AXIOM. The
       bodies of perturb.nrepl/open, /request and /close! are not checked;
       their annotations are believed. mode_checker.py's RULES have the same
       status, so this is the ported posture, not a new hole — but it is a hole:
       nothing checks that close! actually closes.
  
    2. :consumes / :produces are UNPOSITIONED. A function returning [conn value]
       cannot say where the capability is, so the checker can only reject it.
       For a DERIVED annotated operation the checker matches capability specs to
       parameters in order; that convention is the checker's own and is not in
       §1.2.
  
    3. Closure bodies are walked for diagnostics but their state does not
       propagate: the checker does not model whether or how often a closure runs.
       Capturing a live capability is rejected outright rather than reasoned about.
  
    4. try/catch has no exception-path join. Any capability discipline across a
       handler is unchecked and the checker says so where it finds one.
  
    5. Only `let`/`loop` binding forms carry capabilities. A capability stored in
       an atom, a var, a map or a vector is rejected, never tracked.
  
    6. Interprocedural flow is by ANNOTATION only. There is no inference: an
       unannotated function that takes a connection is rejected, not analysed.
  
    7. The IR it reads is post-const-fold (perturb.ir), and only for namespaces
       required AFTER the tap is installed.

========================================================================
CHECK OK — every corpus verdict is the recorded one
```
