# Transcript — the run this artifact is evidence from

Captured verbatim. Reproduce with `dev/run-demo.sh`.

```
host      Linux x86_64
chez      10.4.1
jolt      v0.4.15-209-g22186094 (commit 22186094)
server    jolt nrepl-server 7899   (jolt-core/jolt/nrepl.clj)
invoked   JOLT_CHEZ=/usr/local/bin/chez jolt -M:selftest | -M:oracle | -M:demo 7899
```

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

  WHERE THE CLAIM LEAKS (INHERITED I11, I12):
    perturb.posix calls (ffi/load-library) at namespace load and
    binds five syscalls with defcfn at load. That is I/O outside any
    handler, and it happens even on the scripted runs. Console output
    (println) is likewise unmediated.

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
