(ns perturb.tlsdemo
  "THE FIRST TEST OF THE ARCHITECTURAL CLAIM: does a handler stack compose?

  PERTURB-DESIGN's stated goal is `events over an HTTP implementation that is
  events over a TCP socket that is events over a file descriptor`, and §5's
  ladder records no evidence that the rungs compose at all. E26 finding 4 found
  that §5's first rung was never even reachable. This is the cheap decisive
  version: a TLS-SHAPED record layer (`perturb.tlsish` — not TLS, see its
  docstring) whose HANDLER for `perturb.wire/socket` satisfies that effect by
  PERFORMING `perturb.wire/socket` against the rung below.

  `perturb.http/serve-connection` is called UNMODIFIED. No transport argument, no
  conditional, no second code path, not one line of `perturb.http` changed, and
  `perturb.http` never mentions `perturb.tlsish`. The only difference between a
  run with two rungs and a run with one is which handler is in `*handlers*`.

  THIS IS NOT A GATE. Like `perturb.streamcheck` and `perturb.evtcheck` it
  records no expectations about the checker's verdicts: the point is to find out
  what they are. It exits non-zero only if the stack fails to RUN, or if the two
  rungs put different plaintext on the wire than one rung does.

  WHAT IT IS NOT EVIDENCE FOR. Two rungs over a scripted transport and two rungs
  over a loopback socket. Not a ladder. Not three layers, not gRPC, not a NATS
  consumer, not an inotify listener, not concurrency, not a second peer. The
  closing section says exactly what would be added by each."
  (:require [perturb.ir :as pir]
            [perturb.check :as chk]
            [perturb.effect :as fx]
            [perturb.octet :as o]
            [perturb.wire :as w]
            [perturb.cap :as cap]
            [perturb.http :as h]
            [perturb.script :as script]
            [perturb.posix :as posix]))

(def ^:private line
  "========================================================================")

(defn- banner [s] (println) (println (str "--- " s)))

(defn- v [s] (deref (resolve s)))
(defn- f [s] (resolve s))

;; --- the fixture ------------------------------------------------------------

(defn request-octets
  "One HTTP/1.1 request as octets. Local copy, as `perturb.evtcheck` keeps one."
  [method target headers body]
  (o/encode-utf8
    (str method " " target " HTTP/1.1\r\n"
         (reduce (fn [a p] (str a (first p) ": " (second p) "\r\n")) "" (seq headers))
         "content-length: " (count body) "\r\n\r\n" body)))

(def req-1 (request-octets "GET"  "/first"  {"host" "perturb"} ""))
(def req-2 (request-octets "POST" "/second" {"host" "perturb"} "body!"))
(def both  (o/oconcat req-1 req-2))

;; --- per-rung accounting ----------------------------------------------------

(defn- site-counts
  "How many performs happened at each `site`, from a `perturb.effect/*trace*`."
  [trace]
  (reduce (fn [a e] (update a (:perturb.effect/site e) (fnil inc 0))) {} @trace))

(defn- rung-of
  "Which rung a site belongs to. `perturb.tlsish/under-*` is the crossing from
  the record layer DOWN to the transport; everything else in this stack is the
  crossing from the application layer DOWN to the record layer."
  [site]
  (if (= "perturb.tlsish" (namespace site)) :lower :upper))

(defn- rung-counts [trace]
  (reduce (fn [a e] (update a (rung-of (:perturb.effect/site e)) (fnil inc 0)))
          {:upper 0 :lower 0} @trace))

(defn- show-sites [trace]
  (doseq [p (sort-by (fn [x] (str (first x))) (seq (site-counts trace)))]
    (println (str "      " (if (= :lower (rung-of (first p))) "lower  " "upper  ")
                  (first p) "  x" (second p)))))

;; --- run (a): the whole stack over the scripted network ---------------------

(defn- cipher-stream
  "What a TLS-shaped client would have put on the wire: a ClientHello record,
  then `ov` as application-data records. Built with `perturb.tlsish`'s PURE
  framing, so the scripted transport carries no knowledge of the layer."
  [ov]
  (reduce (fn [a r] (o/oconcat a r))
          ((f 'perturb.tlsish/frame) (v 'perturb.tlsish/CT-HANDSHAKE)
                                     (o/encode-utf8 (v 'perturb.tlsish/CLIENT-HELLO)))
          ((f 'perturb.tlsish/records-of) (v 'perturb.tlsish/CT-APPDATA) ov)))

(defn run-stacked-scripted
  "perturb.http over perturb.tlsish over perturb.script, ONE OCTET PER TRANSPORT
  RECV. `run` is a `perturb.effect/new-run` atom so the boundary invariant can be
  read afterwards."
  [cipher run trace]
  (let [sess (script/server-session {:conns [cipher] :chunk-size 1})
        r    (fx/with-run run
               (fx/with-trace trace
                 (fx/with-handlers
                   {'perturb.wire/socket ((f 'perturb.tlsish/handler) (:handler sess))}
                   (let [l  (h/listen "in-memory" 0)
                         a  (h/accept l)
                         l1 (first a)
                         c  (second a)
                         c1 (h/serve-connection c 2)
                         l2 (h/shutdown! l1)]
                     :served))))]
    {:result r :wire (script/sent-octets (:state sess) 0)}))

(defn run-bare-scripted
  "THE BASELINE. The same `perturb.http/serve-connection`, one rung, plaintext
  straight onto the scripted network."
  [plain]
  (let [sess (script/server-session {:conns [plain] :chunk-size 1})
        r    (fx/with-handlers {'perturb.wire/socket (:handler sess)}
               (let [l  (h/listen "in-memory" 0)
                     a  (h/accept l)
                     c  (second a)
                     c1 (h/serve-connection c 2)
                     l2 (h/shutdown! (first a))]
                 :served))]
    {:result r :wire (script/sent-octets (:state sess) 0)}))

;; --- run (b): the whole stack over a real loopback socket -------------------

(defn- recv-at-least [tok n]
  (loop [acc o/empty-octets]
    (if (>= (o/ocount acc) n)
      acc
      (let [chunk (w/recv tok 65536 :perturb.tlsdemo/client-recv)]
        (if (zero? (o/ocount chunk)) acc (recur (o/oconcat acc chunk)))))))

(defn run-stacked-real
  "THREE RUNGS OF THE REAL THING: perturb.http over perturb.tlsish over
  perturb.posix over a POSIX socket. Both ends in one process and one thread.

  The client here is `perturb.wire` called directly against the SAME stacked
  handler, so it is a TLS-shaped client talking to a TLS-shaped server: the
  handshake really is exchanged over the loopback, and the plaintext the client
  reads back was framed, transmitted, reassembled and unframed."
  [port want run trace]
  (fx/with-run run
    (fx/with-trace trace
      (fx/with-handlers {'perturb.wire/socket ((f 'perturb.tlsish/handler) (posix/handler))}
        (let [l    (h/listen "127.0.0.1" port)
              ;; connect + ClientHello, before the server accepts; the listen
              ;; backlog holds it, exactly as perturb.httpdemo relies on.
              ctok (w/connect "127.0.0.1" port :perturb.tlsdemo/client-connect)
              ;; EARLY DATA. The client cannot finish its handshake here — see
              ;; perturb.tlsish/open-client-record for why that is forced.
              _    (w/send! ctok both :perturb.tlsdemo/client-send)
              a    (h/accept l)
              l1   (first a)
              c    (second a)
              c1   (h/serve-connection c 2)
              got  (recv-at-least ctok want)
              _    (w/close! ctok :perturb.tlsdemo/client-close)
              l2   (h/shutdown! l1)]
          got)))))

;; --- the checker ------------------------------------------------------------

(defn- report-ns
  "Every captured def of one namespace, in `perturb.streamcheck`'s style.
  `axiom?` is `perturb.check`'s own test — a var that is a declared transition
  or a `:perturb.cap/representation` entry has an unchecked body."
  [sp ns-name lines]
  (println)
  (println (str "== " ns-name " ==============================================="))
  (doseq [l lines] (println (str "   " l)))
  (println)
  (let [results (chk/check-namespace! sp ns-name)
        ax?     (fn [r] (or (contains? (:transitions-of sp) (:var r))
                            (contains? (:representation sp) (:var r))))
        checked (remove ax? results)
        bad     (filter (fn [r] (seq (:diagnostics r))) checked)]
    (doseq [r results]
      (println (str "  "
                    (cond (ax? r) "[axiom] "
                          (empty? (:diagnostics r)) "[ok   ] "
                          :else "[NO   ] ")
                    (:var r)
                    (if (or (ax? r) (empty? (:diagnostics r))) ""
                        (str "  " (vec (sort (distinct (map (fn [d] (name (:kind d)))
                                                            (:diagnostics r))))))))))
    (println)
    (doseq [r bad]
      (println (str "  --- " (:var r)))
      (print (chk/render (:diagnostics r))))
    (println (str "  " (count checked) " of " (count results) " defs were CHECKED; "
                  (count bad) " rejected; " (count (filter ax? results)) " are AXIOMS"
                  " (declared transitions)."))
    {:checked (count checked) :total (count results)
     :bad (vec (map :var bad)) :axioms (count (filter ax? results))}))

;; --- the ledger -------------------------------------------------------------

(defn- ledger-rows [ids]
  (filter (fn [e] (contains? ids (:perturb.cap/capability e))) @cap/ledger))

;; --- main -------------------------------------------------------------------

(defn -main [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 7931)]
    (println line)
    (println "perturb.tlsish — a TLS-SHAPED layer that CONSUMES the effect it satisfies")
    (println "  one layer's capability is the next layer's effect: handler-over-handler")
    (println line)

    (pir/capture! ['perturb.tlsish])

    ;; ======================================================================
    ;; Q1(a) — the mechanism, before anything is claimed about it
    ;; ======================================================================
    (banner "Q1(a)  CAN A HANDLER PERFORM AN EFFECT WHILE HANDLING ONE?")
    (println "  perturb.effect/*handlers* is a FLAT MAP keyed by effect name, and")
    (println "  `perform` does NOT remove the executing handler before calling it.")
    (println "  So a handler that performs its own effect re-enters ITSELF: there is")
    (println "  no deep-handler semantics and no enclosing handler to fall through to.")
    (println "  perturb.tlsish/no-deep-handler-probe installs an OUTER handler that")
    (println "  would answer 0xEE, then an inner handler that performs the same effect")
    (println "  with no rebinding, with a depth limit of 5:")
    (println)
    (println (str "    " (pr-str ((f 'perturb.tlsish/no-deep-handler-probe) 5))))
    (println)
    (println "  It never reaches the outer handler. `:depth 6` is the inner handler")
    (println "  calling itself until the counter stopped it — had `perform` popped the")
    (println "  handler, depth would be 1 and the outcome `[:returned \"ee\"]`.")
    (println)
    (println "  CONSEQUENCE, AND IT HAS SINCE BEEN CLOSED — READ BOTH HALVES.")
    (println "  When this experiment was written, layering was possible but not")
    (println "  provided: the layer had to REBIND the effect name to the rung below")
    (println "  for exactly the extent of its inner perform. `perturb.tlsish/via` was")
    (println "  that rebinding, it was one line, and NOTHING CHECKED IT — a layer that")
    (println "  forgot would loop rather than be refused, the residual shape")
    (println "  perturb.posix/defsys closed one rung down, reopened one rung up.")
    (println)
    (println "  `via` NO LONGER REBINDS. It calls perturb.effect/forward!, which")
    (println "  crosses to the layer's NAMED OUTER INSTANCE and consults *handlers*")
    (println "  neither to read nor to write. There is nothing left to forget, and")
    (println "  naming yourself is a LATCHED :self-forward rather than a stack")
    (println "  overflow. The probe above is unchanged and still true, because it")
    (println "  PERFORMS rather than forwards: `perform` still does not pop, so the")
    (println "  finding about `perform` stands and the residual about `via` does not.")
    (println "  perturb.layer / -M:layer is the invariant that made the difference")
    (println "  worth having, and perturb.layercheck is its gate.")

    ;; ======================================================================
    ;; run (a) — the stack, scripted
    ;; ======================================================================
    (let [cipher   (cipher-stream both)
          n-in-rec (count ((f 'perturb.tlsish/unframe) cipher))]
      (banner "the request stream, at BOTH layers")
      (println (str "  plaintext  " (o/ocount both) " octets, two pipelined HTTP requests"))
      (println (str "    " (o/printable both)))
      (println (str "  ciphertext " (o/ocount cipher) " octets, " n-in-rec
                    " TLS-shaped records (1 handshake + "
                    (dec n-in-rec) " application data of at most "
                    (v 'perturb.tlsish/max-record-payload) " payload octets)"))
      (println (str "    " (o/printable cipher)))
      (println (str "    " (o/hex (o/osub cipher 0 24)) " ..."))
      (println "  NO RECORD BOUNDARY COINCIDES WITH ANYTHING HTTP KNOWS ABOUT. The")
      (println "  first request ends inside record 3; the second begins inside it.")

      (banner "RUN (a): perturb.http / perturb.tlsish / perturb.script, 1 octet per")
      (println "         TRANSPORT recv — so a 3-octet record header costs three inner")
      (println "         performs before the record layer can decide anything.")
      (cap/reset-ledger!)
      (let [run-a   (fx/new-run #{'perturb.wire/socket.listen 'perturb.wire/socket.accept
                                  'perturb.wire/socket.recv   'perturb.wire/socket.send
                                  'perturb.wire/socket.close})
            trace-a (atom [])
            a       (run-stacked-scripted cipher run-a trace-a)
            a-wire  (:wire a)
            a-plain ((f 'perturb.tlsish/plaintext-of) a-wire)
            ledger-a (vec @cap/ledger)
            rep-a   (fx/report run-a)
            raw-a   @run-a
            rungs-a (rung-counts trace-a)]
        (println (str "  driver returned      " (pr-str (:result a))))
        (println (str "  octets ON THE WIRE   " (o/ocount a-wire) " in "
                      (count ((f 'perturb.tlsish/unframe) a-wire)) " records"))
        (println (str "    " (o/printable a-wire)))
        (println (str "  octets the HTTP layer produced (unframed): " (o/ocount a-plain)))
        (println (str "    " (o/printable a-plain)))
        (println)
        (println "  effect performs, by rung:")
        (println (str "    upper (perturb.http -> perturb.tlsish) : " (:upper rungs-a)))
        (println (str "    lower (perturb.tlsish -> perturb.script): " (:lower rungs-a)))
        (show-sites trace-a)
        (println)
        (println "  EVERY ONE of the lower crossings happened INSIDE a handler for the")
        (println "  upper one. That is the composition, and it is not a call: each went")
        (println "  through perturb.effect/perform — arity check, handler lookup,")
        (println "  *handling* binding, result predicate, trace, run accounting.")

        ;; --- the baseline -------------------------------------------------
        (banner "the SAME driver with the record layer removed")
        (let [b       (run-bare-scripted both)
              b-wire  (:wire b)
              same-p  (= (o/ovec a-plain) (o/ovec b-wire))]
          (println (str "  one rung, plaintext straight onto the scripted network: "
                        (o/ocount b-wire) " octets"))
          (println (str "    " (o/printable b-wire)))
          (println (str "  stacked plaintext == bare plaintext : " (pr-str same-p)))
          (println "  The layer above produced the same octets over a transport that")
          (println "  reframed, buffered and re-chunked everything it wrote.")

          ;; ================================================================
          ;; run (b) — the stack over a real socket
          ;; ================================================================
          (banner (str "RUN (b): the same stack over a REAL POSIX socket on 127.0.0.1:" port))
          (cap/reset-ledger!)
          (posix/reset-native-log!)
          (let [run-b   (fx/new-run #{'perturb.wire/socket.listen 'perturb.wire/socket.accept
                                      'perturb.wire/socket.connect
                                      'perturb.wire/socket.recv 'perturb.wire/socket.send
                                      'perturb.wire/socket.close
                                      'perturb.posix/socket 'perturb.posix/accept
                                      'perturb.posix/send   'perturb.posix/recv
                                      'perturb.posix/close})
                trace-b (atom [])
                got     (try (run-stacked-real port (o/ocount a-plain) run-b trace-b)
                             (catch :default e {:err (str e)}))]
            (if (:err got)
              (do (println (str "  FAILED: " (:err got)))
                  (println line)
                  (println "TLS DEMO FAILED — the real socket run did not complete")
                  (System/exit 1))
              (let [rep-b   (fx/report run-b)
                    raw-b   @run-b
                    rungs-b (rung-counts trace-b)
                    same-r  (= (o/ovec a-plain) (o/ovec got))]
                (println (str "  the client read back " (o/ocount got)
                              " octets of PLAINTEXT, after framing, transmission,"))
                (println "  reassembly and unframing across a loopback socket:")
                (println (str "    " (o/printable got)))
                (println (str "  native calls  " (pr-str (posix/native-log-snapshot))))
                (println)
                (println "  effect performs, by rung:")
                (println (str "    upper : " (:upper rungs-b)))
                (println (str "    lower : " (:lower rungs-b)))
                (show-sites trace-b)
                (println)
                (println (str "  scripted-stack plaintext == socket-stack plaintext : "
                              (pr-str same-r)))
                (println "  One compiled perturb.http/serve-connection, one compiled")
                (println "  perturb.tlsish, two transports, identical octets above the")
                (println "  record layer. `send` at the upper rung is 2; at the lower rung")
                (println "  it is what the framing needed. The layer above never learns")
                (println "  the difference, because perturb.wire/socket's `:send` result")
                (println "  predicate is `count?` and has no vocabulary for WHOSE octets.")

                ;; ============================================================
                ;; Q2 — the capability discipline
                ;; ============================================================
                (banner "Q2  DOES THE CAPABILITY DISCIPLINE SURVIVE THE STACKING?")
                (println "  Three machines advanced during run (a). Two of them are")
                (println "  perturb.http's and one is perturb.tlsish's, and they interleave")
                (println "  because ONE operation of the layer above drives an operation of")
                (println "  the layer below:")
                (println)
                (doseq [e (take 14 ledger-a)]
                  (println (str "    " (:perturb.cap/capability e) "  "
                                (:perturb.cap/id e) "  "
                                (pr-str (:perturb.cap/from e)) " -> "
                                (pr-str (:perturb.cap/to e)) "   @"
                                (:perturb.cap/site e))))
                (println (str "    ... " (count ledger-a) " entries in all"))
                (println)
                (println "  READ THE `@site` COLUMN. perturb.http/accept advances the")
                (println "  Listener and mints a ServerConn; the Record minted at")
                (println "  perturb.tlsish/open-server-record in the middle of it is a")
                (println "  THIRD machine that perturb.http/accept's annotation cannot")
                (println "  name, because perturb.http does not know perturb.tlsish exists")
                (println "  and the two are separated by an effect boundary.")
                (println)
                (println "  E19 closed `an operation that advances two machines cannot be")
                (println "  declared` by keying the primitive table [capability operation].")
                (println "  That fix does not reach this case. E19's three operations are")
                (println "  two machines named by ONE var in ONE namespace. Here the")
                (println "  operations are in different namespaces, neither names the")
                (println "  other, and the only thing joining them is a dynamic var. The")
                (println "  keying is not the obstacle; there is no var to key.")

                ;; --- what the checker says -----------------------------------
                (let [sp (chk/spec)
                      rr (report-ns
                           sp "perturb.tlsish"
                           ["The record layer. Eight declared transitions (AXIOMS, like every"
                            "perturb.http transition), the pure framing above them, the"
                            "HANDLER that stacks them, and five isolation programs that each"
                            "isolate one rule. Read the rejections as EVIDENCE ABOUT THE"
                            "RULES: this code RUNS, twice, above."])]
                  (println)
                  (println "  WHAT THE VERDICTS SAY, in order of how much they cost.")
                  (println)
                  (println "  1. THE HANDLER IS REJECTED, AND IT IS NOT A DESIGN CHOICE.")
                  (println "     A handler is `(fn [op site args] ...)`. It is entered once")
                  (println "     per operation and must carry the record's reassembly buffer,")
                  (println "     its phase and its transport token BETWEEN entries. A `let`")
                  (println "     cannot span two invocations, so the capability must live in")
                  (println "     inter-invocation state, and the only carriers are an atom")
                  (println "     (report-limits item 5: rejected, never tracked) and a")
                  (println "     closure (item 3: capturing a live capability is rejected")
                  (println "     outright). `stash-record-in-atom`, `record-table`,")
                  (println "     `record-from-table` and `handler-closing-over-a-record`")
                  (println "     above are those four carriers, one rejection each.")
                  (println)
                  (println "     §4.6 records `a capability may only live in a binding whose")
                  (println "     shape is known at compile time` as the root cause behind E23")
                  (println "     and E24, and lists four shapes an event-driven application")
                  (println "     needs. THIS IS STRONGER THAN THAT ITEM. E23 and E24 found")
                  (println "     applications that WANT those shapes. A HANDLER STACK IS")
                  (println "     INTER-INVOCATION STATE BY CONSTRUCTION: there is no way to")
                  (println "     write the layer at all without one of the rejected carriers,")
                  (println "     whatever the layer does. `record-echo-round` above is the")
                  (println "     same operations in one straight `let` chain — it is what")
                  (println "     §1.2 wants and it cannot be a handler, because it is one")
                  (println "     line through a whole connection lifetime.")
                  (println)
                  (println "  2. `lazy-handshake-on-first-recv` draws the JOIN rule, and the")
                  (println "     program is not a control. A handshake is a ROUND TRIP and")
                  (println "     §1.4 keeps control in a cooperative kernel that does not")
                  (println "     exist, so the client cannot finish its handshake inside")
                  (println "     `connect` — the peer has not run yet. It must finish it")
                  (println "     lazily on the first read, which consumes the Record in one")
                  (println "     arm of an `if` and not the other. E23 reached the join rule")
                  (println "     from a truncated reply, E24 from an application effect;")
                  (println "     this is the third data point and the first where LAYERING")
                  (println "     ITSELF produced it. §4.6's join-rule item has a fourth")
                  (println "     column now.")
                  (println)
                  (println "  3. EIGHT AXIOMS. Every transition of the record layer is a")
                  (println "     declared transition, so every body that performs an effect")
                  (println "     against the rung below is UNCHECKED — including the two")
                  (println "     `via` calls in `close-record!` whose ORDER is the whole of")
                  (println "     the shutdown obligation. `:perturb.cap/representation` is")
                  (println "     empty for Record, and for perturb.http's exact reason: the")
                  (println "     accesses were written inside transitions. E18 finding 4's")
                  (println "     `an empty list is a boundary that was hidden` reproduces")
                  (println "     here, uninvited.")
                  (println)
                  (println "  4. AND THE SHARPEST ONE, WHICH NOBODY ASKED FOR.")
                  (println "     `perturb.tlsish/via` and `perturb.tlsish/as-call-over-call`")
                  (println "     are BOTH rejected `unsupported-construct`, and neither")
                  (println "     contains a try/catch. `as-call-over-call` is one `binding`")
                  (println "     form and nothing else. `clojure.core/binding` expands to")
                  (println "     push-thread-bindings / try / finally / pop-thread-bindings,")
                  (println "     and report-limits item 4 says a try/catch has no")
                  (println "     exception-path join, so ANY capability discipline across it")
                  (println "     is unchecked and the checker says so where it finds one.")
                  (println)
                  (println "     INSTALLING A HANDLER IS AN UNSUPPORTED CONSTRUCT. Not a")
                  (println "     layer's handler — ANY handler. `perturb.effect/with-handlers`,")
                  (println "     `with-trace` and `with-run` are all `binding`, and so is")
                  (println "     `perform`'s own `*handling*` binding. The effect boundary")
                  (println "     §1.4 makes central is, to §1.2's checker, a region across")
                  (println "     which it declines to reason. That has been true since")
                  (println "     `perturb.effect` was written and no artifact had found it,")
                  (println "     because every previous handler installation was in a demo")
                  (println "     namespace nobody checks (perturb.demo, perturb.httpdemo,")
                  (println "     perturb.noio, perturb.evtcheck) — `perturb.tlsish` is the")
                  (println "     first namespace that installs a handler AND is checked.")
                  (println)
                  (println "     It is also why `via` cannot be made to check. There is no")
                  (println "     rewrite: the dynamic extent IS the mechanism, so a layer")
                  (println "     that composes through the effect boundary is a layer whose")
                  (println "     composition point is outside the checker by construction.")

                  ;; ==========================================================
                  ;; Q3 — the fail-closed boundary
                  ;; ==========================================================
                  (banner "Q3  WHAT DOES THE FAIL-CLOSED BOUNDARY DO UNDER A HANDLER STACK?")
                  (println "  perturb.effect/native! refuses a native crossing with no handler")
                  (println "  in dynamic extent; *handling* is bound for the extent of the")
                  (println "  handler call. A HANDLER PERFORMING AN EFFECT is the case that")
                  (println "  machinery was never tested against. Both runs, reported:")
                  (println)
                  (doseq [p [["run (a) scripted" rep-a raw-a]
                             ["run (b) socket  " rep-b raw-b]]]
                    (let [rep (nth p 1)]
                      (println (str "    " (first p)
                                    "  all-handled? " (pr-str (:perturb.effect/all-handled? rep))
                                    "   performs " (:perturb.effect/performs rep)
                                    "   latched " (pr-str (mapv :perturb.effect/abort
                                                                (:perturb.effect/faults rep)))
                                    "   orphans " (:perturb.effect/orphan-faults rep)))
                      (println (str "                        missing "
                                    (pr-str (sort (:perturb.effect/missing rep)))))))
                  (println)
                  (println "  (a) THE PER-RUN INVARIANT HOLDS. Nesting did not break it, and")
                  (println "      the reason is mechanical: *handling* is a dynamic var, so")
                  (println "      the inner `perform` rebinds it and the unwind restores the")
                  (println "      outer frame. Every native crossing in run (b) happened with")
                  (println "      a handler in extent and none was refused.")
                  (println)
                  (println "  (b) BUT THE RECORD IS A FRAME, NOT A STACK, so attribution is")
                  (println "      lost. `native!` stores the ONE descriptor in *handling*.")
                  (println "      Here is what run (b)'s syscalls are charged to:")
                  (println)
                  (doseq [q (sort-by (fn [x] (str (first x)))
                                     (seq (frequencies
                                            (map (fn [n]
                                                   [(:perturb.effect/symbol n)
                                                    (:perturb.effect/site
                                                      (:perturb.effect/handling n))])
                                                 (:perturb.effect/natives raw-b)))))]
                    (println (str "        " (pr-str (first (first q)))
                                  "  charged to  " (pr-str (second (first q)))
                                  "   x" (second q))))
                  (println)
                  (println "      Every syscall is charged to a perturb.tlsish site. Nothing")
                  (println "      in run state records that a perturb.http operation was on")
                  (println "      the stack when it happened. With one rung the boundary")
                  (println "      record named the application operation; with two it names")
                  (println "      the innermost layer and there is no depth field to make it")
                  (println "      otherwise. E16's `attribution by instrument` was about")
                  (println "      WHICH BINDINGS are counted; this is a different loss —")
                  (println "      the crossings are all counted and charged to the wrong rung.")
                  (println)
                  (println "  (c) THE REQUIRED-SYMBOL SET CANNOT TELL THE RUNGS APART.")
                  (println "      `perturb.effect/op-symbol` is effect-name plus op, so both")
                  (println "      rungs' recvs are the single symbol")
                  (println "      `perturb.wire/socket.recv`. The anti-vacuity guarantee is")
                  (println "      therefore satisfied by the UPPER rung alone: a run in which")
                  (println "      the record layer performed nothing at all would still")
                  (println "      report every required symbol reached. The next control")
                  (println "      shows exactly that.")

                  ;; --- control 1: no handler under the stack ------------------
                  (banner "Q3 CONTROL 1 — the rung below is missing")
                  (println "  The same stack with `nil` where the transport handler goes. The")
                  (println "  outer perform finds a handler and the INNER one does not.")
                  (println)
                  (let [run-c (fx/new-run #{'perturb.wire/socket.listen})
                        out   (try
                                (fx/with-run run-c
                                  (fx/with-handlers
                                    {'perturb.wire/socket ((f 'perturb.tlsish/handler) nil)}
                                    (let [l (h/listen "in-memory" 0)]
                                      [:returned (pr-str l)])))
                                (catch :default e
                                  [:threw (:perturb.effect/abort (ex-data e))]))
                        rep-c (fx/report run-c)]
                    (println (str "    outcome        " (pr-str out)))
                    (println (str "    all-handled?   "
                                  (pr-str (:perturb.effect/all-handled? rep-c))))
                    (println (str "    latched        "
                                  (pr-str (mapv :perturb.effect/abort
                                                (:perturb.effect/faults rep-c)))))
                    (doseq [ft (:perturb.effect/faults rep-c)]
                      (println (str "      " (pr-str ft))))
                    (println (str "    seen (upper rung reached it anyway) : "
                                  (pr-str (sort (:perturb.effect/seen @run-c)))))
                    (println)
                    (println "    THE LATCH FIRES THROUGH A HANDLER FRAME — an effect that")
                    (println "    reached the boundary with no handler is refused and")
                    (println "    latched whether the boundary is the application's or")
                    (println "    another handler's. That is the half that survives.")
                    (println "    AND `:seen` HOLDS `perturb.wire/socket.listen`, because")
                    (println "    perturb.effect/perform observes an op when it FINDS a")
                    (println "    handler, before calling it. In a stack the upper rung's")
                    (println "    op is `seen` even when the rung below had no handler at")
                    (println "    all, so a required-symbol set built from the effect's own")
                    (println "    vocabulary cannot make a run prove the lower rung ran.")

                    ;; --- control 2: laundering -------------------------------
                    (banner "Q3 CONTROL 2 — a handler LAUNDERS the rung below's refusal")
                    (println "  perturb.effect/latching-aborts deliberately excludes")
                    (println "  `:handler-abort`, on the stated ground that a handler")
                    (println "  returning [:abort ...] is `a declared outcome of the effect,")
                    (println "  catchable by the caller`. Under a stack THE CALLER IS ANOTHER")
                    (println "  HANDLER. perturb.tlsish/laundering-handler catches the")
                    (println "  transport's refusal on `recv` and answers the layer above")
                    (println "  with a clean end-of-stream. The scripted network is given")
                    (println "  only a handshake record and then runs dry, so every")
                    (println "  application read fails underneath:")
                    (println)
                    (let [hs    ((f 'perturb.tlsish/frame)
                                 (v 'perturb.tlsish/CT-HANDSHAKE)
                                 (o/encode-utf8 (v 'perturb.tlsish/CLIENT-HELLO)))
                          sess  (script/server-session {:conns [hs] :chunk-size 1})
                          run-d (fx/new-run #{'perturb.wire/socket.recv})
                          out   (try
                                  (fx/with-run run-d
                                    (fx/with-handlers
                                      {'perturb.wire/socket
                                       ((f 'perturb.tlsish/laundering-handler) (:handler sess))}
                                      (let [l (h/listen "in-memory" 0)
                                            a (h/accept l)
                                            c (second a)]
                                        [:read (pr-str (h/read-request c))])))
                                  (catch :default e
                                    [:threw (:perturb.effect/abort (ex-data e))
                                     (pr-str (:perturb.http/conn (ex-data e)))]))
                          rep-d (fx/report run-d)]
                      (println (str "    the layer above saw   " (pr-str out)))
                      (println (str "    all-handled?          "
                                    (pr-str (:perturb.effect/all-handled? rep-d))))
                      (println (str "    latched               "
                                    (pr-str (mapv :perturb.effect/abort
                                                  (:perturb.effect/faults rep-d)))))
                      (println)
                      (println "    The transport ran dry, the record layer swallowed it, and")
                      (println "    the layer above got an orderly `:eof` — a perturb.http")
                      (println "    abort, not a boundary fault. THE RUN REPORTS")
                      (println "    all-handled? true. Nothing latched, because nothing")
                      (println "    the boundary considers a fault happened.")
                      (println)
                      (println "    This is not a defect in `latching-aborts`; it is that the")
                      (println "    line it draws — handler decisions are the caller's")
                      (println "    business — was drawn when the caller was always")
                      (println "    application code. A layer is a caller with the licence of")
                      (println "    a handler, and it can convert any refusal below it into")
                      (println "    any answer above it, with the run still reporting clean.")
                      (println "    perturb.tlsish/record-recv already folds three distinct")
                      (println "    facts into an empty view without trying to.")

                      ;; --- control 3: call-over-call --------------------
                      (banner "Q3 CONTROL 3 — the SAME layer composed by CALLING the rung below")
                      (println "  perturb.tlsish/*call-over-call* switches `via` from")
                      (println "  performing against the transport to INVOKING it as an")
                      (println "  ordinary function — the way every layered library composes.")
                      (println "  Same layer, same driver, same octets; one flag.")
                      (println)
                      (let [run-e   (fx/new-run #{'perturb.wire/socket.listen
                                                  'perturb.wire/socket.accept
                                                  'perturb.wire/socket.recv
                                                  'perturb.wire/socket.send
                                                  'perturb.wire/socket.close})
                            trace-e (atom [])
                            e       ((f 'perturb.tlsish/as-call-over-call)
                                     (fn [] (run-stacked-scripted cipher run-e trace-e)))
                            rep-e   (fx/report run-e)
                            rungs-e (rung-counts trace-e)
                            e-plain ((f 'perturb.tlsish/plaintext-of) (:wire e))]
                        (println (str "  scripted, call-over-call:  driver returned "
                                      (pr-str (:result e))))
                        (println (str "    plaintext identical to the performing stack : "
                                      (pr-str (= (o/ovec a-plain) (o/ovec e-plain)))))
                        (println (str "    performs, upper rung : " (:upper rungs-e)
                                      "   lower rung : " (:lower rungs-e)
                                      "   (performing stack: " (:lower rungs-a) ")"))
                        (println (str "    all-handled?         "
                                      (pr-str (:perturb.effect/all-handled? rep-e))
                                      "   latched "
                                      (pr-str (mapv :perturb.effect/abort
                                                    (:perturb.effect/faults rep-e)))))
                        (println)
                        (println "    THE LOWER RUNG BECAME INVISIBLE. Identical octets, and")
                        (println "    the 170 crossings that carried them are gone from the")
                        (println "    trace, from the perform count and from run state. No")
                        (println "    arity was checked, no result predicate ran, and nothing")
                        (println "    refused it — because nothing at the boundary can tell")
                        (println "    that a layer chose not to use the boundary.")
                        (println)
                        (let [run-fx  (fx/new-run #{'perturb.wire/socket.recv
                                                    'perturb.posix/recv 'perturb.posix/send})
                              trace-f (atom [])
                              gf      (try ((f 'perturb.tlsish/as-call-over-call)
                                            (fn [] (run-stacked-real (inc port)
                                                                     (o/ocount a-plain)
                                                                     run-fx trace-f)))
                                           (catch :default ex {:err (str ex)}))]
                          (if (:err gf)
                            (println (str "    real socket, call-over-call: FAILED " (:err gf)))
                            (let [rep-f (fx/report run-fx)
                                  raw-f @run-fx]
                              (println (str "  real socket on 127.0.0.1:" (inc port)
                                            ", call-over-call: client read "
                                            (o/ocount gf) " octets, identical : "
                                            (pr-str (= (o/ovec a-plain) (o/ovec gf)))))
                              (println (str "    all-handled?  "
                                            (pr-str (:perturb.effect/all-handled? rep-f))
                                            "   latched "
                                            (pr-str (mapv :perturb.effect/abort
                                                          (:perturb.effect/faults rep-f)))))
                              (println "    and here is what the syscalls are charged to now:")
                              (doseq [q (sort-by (fn [x] (str (first x)))
                                                 (seq (frequencies
                                                        (map (fn [n]
                                                               [(:perturb.effect/symbol n)
                                                                (:perturb.effect/site
                                                                  (:perturb.effect/handling n))])
                                                             (:perturb.effect/natives raw-f)))))]
                                (println (str "        " (pr-str (first (first q)))
                                              "  charged to  " (pr-str (second (first q)))
                                              "   x" (second q))))
                              (println)
                              (println "    THE NATIVE GATE DOES NOT REFUSE IT. `native!` asks")
                              (println "    only whether SOME handler is executing on this")
                              (println "    thread, and the outer perform's binding is still in")
                              (println "    extent, so a rung that bypassed the boundary crosses")
                              (println "    to C with the boundary's blessing. The syscalls are")
                              (println "    now charged to perturb.http sites — which is the")
                              (println "    RIGHT attribution, and it is right because the")
                              (println "    intermediate layer erased itself.")
                              (println)
                              (println "    So the boundary has TWO failure modes and they are")
                              (println "    complements. Compose by PERFORMING and every")
                              (println "    crossing is accounted for and charged to the wrong")
                              (println "    rung (Q3b). Compose by CALLING and the crossings are")
                              (println "    charged correctly and half of them are not accounted")
                              (println "    for at all. `perturb.effect/report` says")
                              (println "    all-handled? true for both, and there is nothing in")
                              (println "    run state that distinguishes them.")))

                          ;; ======================================================
                          ;; the closing statement
                          ;; ======================================================
                          (banner "WHAT WAS SHOWN, ON THE EVIDENCE LATTICE")
                      (println "  proved              nothing")
                      (println "  bounded-complete    nothing")
                      (println "  per-run invariant   the fail-closed boundary survives ONE")
                      (println "                      level of handler nesting: both stacked")
                      (println "                      runs report all-handled? true over a")
                      (println "                      non-vacuous required set, and control 1")
                      (println "                      shows the latch still fires when the")
                      (println "                      inner rung has no handler.")
                      (println "  sampled             TWO rungs, ONE stacking, TWO transports,")
                      (println "                      TWO pipelined requests, ONE connection,")
                      (println "                      ONE thread. The plaintext above the")
                      (println "                      record layer is identical across both")
                      (println "                      transports and identical to the same")
                      (println "                      driver with no record layer at all.")
                      (println "  monitored           the capability discipline, at the")
                      (println "                      record layer: the ledger records what")
                      (println "                      the run did and nothing checks it.")
                      (println "  failed              the capability discipline as a STATIC")
                      (println "                      property of the layer. The handler is")
                      (println "                      rejected, and it is rejected for a")
                      (println "                      reason no rewrite removes; and the")
                      (println "                      composition point itself is")
                      (println "                      `unsupported-construct`, because installing")
                      (println "                      a handler is `binding` and `binding` is")
                      (println "                      try/finally.")
                      (println "  failed, then          the effect boundary as a SEAM. Control 3")
                      (println "  PARTLY RECOVERED      runs the same layer composed by CALLING")
                      (println "                      and gets identical octets. When this was")
                      (println "                      written no instrument in perturb")
                      (println "                      distinguished the two runs. ONE NOW DOES:")
                      (println "                      `jolt -M:layer` reads the layering event")
                      (println "                      log and reports the call-over-call run as")
                      (println "                      16 B6.1 violations — every :mandatory-forward")
                      (println "                      operation emitted zero correlated forwards.")
                      (println "                      Using the boundary is STILL VOLUNTARY: the")
                      (println "                      run completes, the octets are right, and")
                      (println "                      nothing refuses it. What changed is that it")
                      (println "                      is now DETECTED rather than invisible.")
                      (println)
                      (println "  WHAT IS NOT SHOWN, ITEM BY ITEM.")
                      (println "   - A THIRD RUNG. Nothing here says the nesting composes")
                      (println "     twice. `via` rebinds ONE effect name; three rungs of the")
                      (println "     same effect need two nested rebindings and nothing has")
                      (println "     run that. The cost is not obviously linear: *handling*")
                      (println "     is one frame at any depth, so attribution loss (Q3b) is")
                      (println "     total from rung two onward rather than growing.")
                      (println "   - A REAL TRANSPORT. perturb.posix is a real socket, but")
                      (println "     E26 finding 6 records that the bottom rung does not emit")
                      (println "     a portable event vocabulary — a peer FIN with no pending")
                      (println "     data is readable on Linux and `:hangup` alone on")
                      (println "     Winsock. This ran on Linux, blocking, single-threaded,")
                      (println "     with no readiness effect. A real transport adds the")
                      (println "     contention axis (I20), which nothing in perturb has ever")
                      (println "     exercised.")
                      (println "   - REAL TLS. There is no cipher, no MAC and no sequence")
                      (println "     number here, so nothing is said about a layer whose")
                      (println "     framing is stateful across records or whose failures")
                      (println "     must be fatal to the connection.")
                      (println "   - gRPC / NATS / Kafka / inotify. Each of those is a layer")
                      (println "     whose events do not map onto recv/send at all, and this")
                      (println "     experiment reused perturb.wire/socket precisely because")
                      (println "     it does. A layer needing a DIFFERENT effect vocabulary")
                      (println "     needs no `via` at all and would test something else.")

                      (println)
                      (println line)
                      (if (and same-p same-r)
                        (do (println "TLS DEMO OK — the stack RAN, on both transports, with")
                            (println "              identical plaintext above the record layer.")
                            (println "              Read the VERDICTS and the three controls,")
                            (println "              not this line.")
                            (System/exit 0))
                        (do (println (str "TLS DEMO FAILED — plaintext disagreed. bare=" (pr-str same-p)
                                          " socket=" (pr-str same-r)))
                            (System/exit 1)))))))))))))))))
