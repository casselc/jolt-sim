(ns perturb.layercheck
  "THE B6 GATE: the known-good composition, and every negative control it has.

  `perturb.layer` states the invariant and checks a trace. This RUNS the traces
  and asserts the verdicts, which is what makes it a GATE and not a report —
  unlike `perturb.tlsdemo`, `perturb.streamcheck`, `perturb.evtcheck` and
  `perturb.tcpcheck`, which record no expectations because the point was to find
  out what the verdicts were. Here the verdicts are known and a change to any of
  them is a failing build.

  WHAT IT ASSERTS, IN THE ORDER IT RUNS THEM:

    A  KNOWN-GOOD. perturb.http over perturb.tlsish over perturb.script, one
       octet per transport recv, two pipelined requests. Every clause must pass.
       Without this the gate would be a machine for proving that broken things
       are broken.
    B  NEGATIVE CONTROL 1 — ABORT LAUNDERING (E29 control 2). The transport
       REFUSES a read and the record layer answers `[:ok empty]`. B6.3 must fire.
    B' THE SAME PROGRAM WITH A DECLARED ERROR-MAPPING EDGE must PASS. Without
       this arm the clause would be untestable — a rule nothing can satisfy is
       not a rule.
    C  NEGATIVE CONTROL 2 — CALL-OVER-CALL (E29 control 3). The same layer
       composed by CALLING the rung below. Identical octets, and B6.1 must fire.
    D  NEGATIVE CONTROL 3 — MULTI-SHOT OUTWARD OPERATION (E33, both surveys
       asked for this by name). B6.4 must fire AND the run must be refused.
    E  §A3's RUNTIME REFUSALS, one at a time: self-forward, wrong owner, forward
       from inside a finalizer, second use of an outward instance. Each must
       ABORT, and the abort must be LATCHED.
    F  FORGERY. Four mutations of the known-good canonical trace — drop a
       record, swap two, re-point a parent, forge a route — each of which must
       fail replay coherence.
    G  SELF-INTERCEPTION BACKSTOP. `no-deep-handler-probe` reaches the boundary
       by PERFORMING, which `forward!` cannot refuse; the trace clause must see
       it.

  WHAT A PASS MEANS AND DOES NOT MEAN is in the closing section, and it is short
  because the honest answer is short: two rungs, one thread, one scripted
  transport, one recorded run per control."
  (:require [perturb.effect :as fx]
            [perturb.layer :as lay]
            [perturb.octet :as o]
            [perturb.wire :as w]
            [perturb.cap :as cap]
            [perturb.http :as h]
            [perturb.script :as script]
            [perturb.tlsish :as tls]))

(def ^:private line
  "========================================================================")

(defn- banner [s] (println) (println (str "--- " s)))

;; --- the fixture -------------------------------------------------------------

(defn- request-octets
  [method target headers body]
  (o/encode-utf8
    (str method " " target " HTTP/1.1\r\n"
         (reduce (fn [a p] (str a (first p) ": " (second p) "\r\n")) "" (seq headers))
         "content-length: " (count body) "\r\n\r\n" body)))

(def req-1 (request-octets "GET"  "/first"  {"host" "perturb"} ""))
(def req-2 (request-octets "POST" "/second" {"host" "perturb"} "body!"))
(def both  (o/oconcat req-1 req-2))

(defn- cipher-stream
  "What a TLS-shaped client would have put on the wire: a ClientHello record,
  then `ov` as application-data records."
  [ov]
  (reduce (fn [a r] (o/oconcat a r))
          (tls/frame tls/CT-HANDSHAKE (o/encode-utf8 tls/CLIENT-HELLO))
          (tls/records-of tls/CT-APPDATA ov)))

(def cipher (cipher-stream both))

(defn- refusing-network
  "A scripted server-side network that REFUSES a read once its queue is dry.

  E29's control 2 was described as `the record layer catches :handler-abort from
  the rung below`, and running it shows that it does not: `perturb.script`'s
  server session answers a dry connection `[:ok empty]`, which is an orderly
  end-of-stream and not a refusal, so `laundering-handler`'s catch never fires
  on that fixture. The laundering it demonstrated was of an EOF. This wrapper
  supplies the refusal the control is actually about, and it is the smallest
  change that does: one arm, on `:recv`, when the queue is empty."
  [conn]
  (let [sess (script/server-session {:conns [conn] :chunk-size 1})
        inner (:handler sess)]
    {:handler (fn [op site args]
                (if (= op :recv)
                  (let [r (inner op site args)]
                    (if (and (= :ok (first r)) (zero? (o/ocount (second r))))
                      [:abort {:reason :transport-dry}]
                      r))
                  (inner op site args)))
     :state (:state sess)}))

;; --- the layer declaration ---------------------------------------------------

(defn- octets-size [v] (if (o/octets? v) (o/ocount v) nil))

(defn- record-layer
  "`perturb.tlsish`'s record layer, DECLARED. `error-map` is the only thing that
  varies between control B and control B'."
  [handler-fn error-map]
  (lay/layer
    {:perturb.layer/name       'perturb.tlsish/handler
     :perturb.layer/handler    handler-fn
     :perturb.layer/capability 'perturb.tlsish/Record
     :perturb.layer/ops        lay/wire-socket-classes
     :perturb.layer/error-map  error-map
     :perturb.layer/size       octets-size}))

(def eof-mapping-edge
  "THE EXPLICIT ERROR-MAPPING EDGE, and it is deliberately narrow.

  It says: this layer converts a refused transport read into an end-of-stream
  for the layer above, on `recv`, and on nothing else. It does NOT license the
  same conversion on `accept`, `send` or `close`, and it does not license
  converting any other abort kind. B6.3 is satisfied by the edge that was
  declared, not by having declared something."
  [{:op :recv :abort :handler-abort
    :note "a refused transport read is reported upward as end-of-stream, which is
           what perturb.wire/socket's empty view already means"}])

;; --- runs --------------------------------------------------------------------

(defn- run-stacked
  "perturb.http over perturb.tlsish over `net`, inside `with-rung` so the record
  layer's finaliser runs on every arm.

  THE LAYER IS BUILT INSIDE THE RECORDED EXTENT ON PURPOSE. A rung instance
  announces itself with a `:rung` event when it is minted, and B6.5 needs those
  events to know which rungs existed. Building the handler before `record!` — the
  obvious way to write this — produces a run whose rungs are invisible and whose
  finalisation clause therefore passes by finding nothing. `handler-out` is how
  the caller gets the handler object back afterwards, which `perturb.layer` needs
  to attribute requests to the declared layer."
  [net wrap handler-out]
  (fn []
    (let [rung (atom nil)
          hf   (wrap net rung)]
      (reset! handler-out hf)
      (fx/with-rung @rung
        (fx/with-handlers {'perturb.wire/socket hf}
          (let [l  (h/listen "in-memory" 0)
                a  (h/accept l)
                l1 (first a)
                c  (second a)
                c1 (h/serve-connection c 2)
                l2 (h/shutdown! l1)]
            :served))))))

(defn- known-good []
  (let [sess (script/server-session {:conns [cipher] :chunk-size 1})
        hp   (atom nil)
        rec  (lay/record! (run-stacked (:handler sess)
                                       (fn [n a] (tls/handler n a)) hp))]
    {:recorded rec :handler @hp
     :sent (fn [] (script/sent-octets (:state sess) 0))}))

(defn- laundering []
  (let [net (refusing-network (tls/frame tls/CT-HANDSHAKE
                                         (o/encode-utf8 tls/CLIENT-HELLO)))
        hp  (atom nil)
        rec (lay/record! (run-stacked (:handler net)
                                      (fn [n a] (tls/laundering-handler n a)) hp))]
    {:recorded rec :handler @hp}))

(defn- call-over-call []
  (let [sess (script/server-session {:conns [cipher] :chunk-size 1})
        hp   (atom nil)
        th   (run-stacked (:handler sess) (fn [n a] (tls/handler n a)) hp)
        rec  (lay/record! (fn [] (tls/as-call-over-call th)))]
    {:recorded rec :handler @hp
     :sent (fn [] (script/sent-octets (:state sess) 0))}))

(defn- multi-shot []
  (let [sess (script/server-session {:conns [cipher] :chunk-size 1})
        hp   (atom nil)
        rec  (lay/record!
               (fn []
                 (let [hf (tls/multishot-outward-handler (:handler sess))]
                   (reset! hp hf)
                   (fx/with-handlers {'perturb.wire/socket hf}
                     (let [l (w/listen "in-memory" 0 :perturb.layercheck/ms-listen)
                           c (w/accept l :perturb.layercheck/ms-accept)
                           a (w/recv c 64 :perturb.layercheck/ms-recv-1)
                           b (w/recv c 64 :perturb.layercheck/ms-recv-2)]
                       [:read (o/ocount a) (o/ocount b)])))))]
    {:recorded rec :handler @hp}))

;; --- assertions --------------------------------------------------------------

(defn- show [label result]
  (println (str "  " label))
  (doseq [l (lay/render result)] (println l)))

(defn- expect-clean
  [label result fails]
  (show label result)
  (if (empty? (:perturb.layer/violations result))
    (do (println "    VERDICT: PASS — every clause held") fails)
    (do (println "    VERDICT: FAIL — this run was supposed to be clean")
        (conj fails label))))

(defn- expect-clause
  [label result want-clause want-rules fails]
  (show label result)
  (let [cs (lay/clauses-hit result)
        rs (lay/rules-hit result)
        missing-rules (vec (remove rs want-rules))]
    (if (and (contains? cs want-clause) (empty? missing-rules))
      (do (println (str "    VERDICT: PASS — detected " (name want-clause)
                        " with rule(s) " (pr-str (vec want-rules))))
          fails)
      (do (println (str "    VERDICT: FAIL — wanted " (name want-clause)
                        " rules " (pr-str (vec want-rules))
                        ", got clauses " (pr-str (vec (sort (map str cs))))
                        " rules " (pr-str (vec (sort (map str rs))))))
          (conj fails label)))))

(defn- aborts-with
  "Run `thunk` and report the abort kind it raised, plus whether it LATCHED."
  [thunk]
  (let [run (fx/new-run #{})
        out (try (fx/with-run run (thunk)) [:returned :no-abort]
                 (catch :default e
                   [:aborted (:perturb.effect/abort (ex-data e))]))]
    {:outcome out
     :latched (mapv :perturb.effect/abort
                    (:perturb.effect/faults (fx/report run)))}))

(defn- expect-abort
  [label want thunk fails]
  (let [r (aborts-with thunk)
        got (second (:outcome r))]
    (println (str "  " label))
    (println (str "      outcome " (pr-str (:outcome r))
                  "   latched " (pr-str (:latched r))))
    (if (and (= want got) (contains? (set (:latched r)) want))
      (do (println (str "      VERDICT: PASS — refused with " (pr-str want)
                        ", and the refusal is latched so it cannot be caught away"))
          fails)
      (do (println (str "      VERDICT: FAIL — wanted a LATCHED " (pr-str want)))
          (conj fails label)))))

;; --- forgery -----------------------------------------------------------------

(defn- forge
  [label canon mutate fails]
  (let [bad (mutate canon)
        vs  (lay/replay-coherence bad)]
    (println (str "  " label))
    (if (seq vs)
      (do (doseq [v (take 2 vs)]
            (println (str "      detected [" (name (:perturb.layer/rule v)) "] "
                          (:perturb.layer/message v))))
          (println "      VERDICT: PASS — the forged projection fails replay coherence")
          fails)
      (do (println "      VERDICT: FAIL — the forged projection was accepted")
          (conj fails label)))))

;; --- main --------------------------------------------------------------------

(defn -main [& _]
  (println line)
  (println "perturb.layer — E33's B6 invariant, EXECUTED over the effect trace")
  (println "  a layer either takes a declared local transition or emits exactly one")
  (println "  correlated forward; the projection onto each capability must be a legal")
  (println "  protocol trace; a lower refusal may not become upper success except")
  (println "  through an explicit error-mapping edge; and the adapter must not")
  (println "  intercept its own forwarded operation.")
  (println line)

  (banner "THE OPERATION CLASSES — the survey's objection, answered in code")
  (println "  `forwarding may be too narrow — a useful layer can interpret locally")
  (println "   rather than forward, so B6 must state exactly which operation classes")
  (println "   require correlation.`")
  (println)
  (println "  The record layer is a live example of the objection: one 121-octet")
  (println "  `send` becomes EIGHT forwards, and most upper `recv`s are answered")
  (println "  out of the reassembly buffer with no forward at all. A literal")
  (println "  `exactly one correlated forward` rejects the known-good on both.")
  (println "  The line drawn, and the principle that draws it:")
  (println)
  (println "    an operation whose result MINTS OR RETIRES A HANDLE in the lower")
  (println "    layer's namespace must correlate one-to-one, because the layer")
  (println "    cannot manufacture that handle. an operation that only MOVES")
  (println "    OCTETS may be reframed, or served out of a buffer the layer")
  (println "    filled by forwarding earlier.")
  (println)
  (doseq [p (sort-by (fn [x] (str (first x))) (seq lay/wire-socket-classes))]
    (println (str "    " (first p) "  -> " (:class (second p))
                  "   lower " (:lower (second p))
                  (if (seq (:ancillary (second p)))
                    (str "   ancillary " (pr-str (:ancillary (second p)))) "")
                  (if (:credit (second p)) "   credit-checked" ""))))

  (let [fails
        (let [;; ============================================================
              ;; A — the known-good
              ;; ============================================================
              kg   (known-good)
              kgh  (:handler kg)
              kgd  [(record-layer kgh [])]
              kgr  (lay/check kgd (:recorded kg))
              f0   (do (banner "A  KNOWN-GOOD: perturb.http / perturb.tlsish / perturb.script")
                       (println (str "  driver returned " (pr-str (:perturb.layer/value (:recorded kg)))
                                     ", " (o/ocount ((:sent kg))) " octets on the wire in "
                                     (count (tls/unframe ((:sent kg)))) " records"))
                       (println "  Two pipelined HTTP requests, one octet per transport recv, no")
                       (println "  record boundary aligned with an HTTP one. Every clause must hold.")
                       (expect-clean "A known-good" kgr []))
              f0   (do (when (seq (:perturb.layer/mapped kgr))
                         (println (str "    error-mapping edges exercised: "
                                       (pr-str (:perturb.layer/mapped kgr)))))
                       (when (seq (:perturb.layer/advisories kgr))
                         (println)
                         (println "    ADVISORIES (reported, not failed — they are about code this")
                         (println "    experiment does not own):")
                         (doseq [a (take 6 (:perturb.layer/advisories kgr))]
                           (println (str "      [" (name (:perturb.layer/rule a)) "] "
                                         (:perturb.layer/message a))))
                         (println (str "      ... " (count (:perturb.layer/advisories kgr))
                                       " advisories in all")))
                       f0)

              ;; ============================================================
              ;; B — abort laundering
              ;; ============================================================
              ld   (laundering)
              ldr  (lay/check [(record-layer (:handler ld) [])] (:recorded ld))
              f1   (do (banner "B  NEGATIVE CONTROL 1 — ABORT LAUNDERING (E29 control 2)")
                       (println "  The transport REFUSES a read once dry; perturb.tlsish/")
                       (println "  laundering-handler catches it and answers [:ok empty]. The layer")
                       (println "  above sees an orderly end-of-stream and the RUN STILL REPORTS")
                       (println "  all-handled? true, because :handler-abort is not a latching")
                       (println "  abort. That is exactly `a lower refusal became upper success`.")
                       (println (str "  the run ended: "
                                     (pr-str (or (:perturb.layer/threw (:recorded ld))
                                                 (:perturb.layer/value (:recorded ld))))))
                       (expect-clause "B laundering" ldr :b6.3 [:unmapped-refusal] f0))

              ;; ============================================================
              ;; B' — the same program with the edge declared
              ;; ============================================================
              ldr2 (lay/check [(record-layer (:handler ld) eof-mapping-edge)] (:recorded ld))
              f2   (do (banner "B' THE SAME TRACE WITH AN EXPLICIT ERROR-MAPPING EDGE")
                       (println "  Same run, same trace, same checker. The only difference is that")
                       (println "  the layer now DECLARES {:op :recv :abort :handler-abort}. If")
                       (println "  this did not pass, B6.3 would be a rule nothing can satisfy.")
                       (let [r (expect-clean "B' error-mapping edge declared" ldr2 f1)]
                         (println (str "    edges exercised: " (pr-str (:perturb.layer/mapped ldr2))))
                         r))

              ;; ============================================================
              ;; C — call-over-call
              ;; ============================================================
              cc   (call-over-call)
              ccr  (lay/check [(record-layer (:handler cc) [])] (:recorded cc))
              f3   (do (banner "C  NEGATIVE CONTROL 2 — CALL-OVER-CALL (E29 control 3)")
                       (println "  The SAME layer composed by CALLING the rung below. E29 measured")
                       (println "  identical octets with 170 crossings gone from the trace and")
                       (println "  nothing able to tell the two runs apart. B6 must tell them apart.")
                       (println (str "  octets on the wire: " (o/ocount ((:sent cc)))
                                     " — identical to the known-good: "
                                     (pr-str (= (o/ovec ((:sent kg))) (o/ovec ((:sent cc)))))))
                       (println (str "  forwards recorded: "
                                     (:forwards (:perturb.layer/summary ccr))
                                     "   (known-good: "
                                     (:forwards (:perturb.layer/summary kgr)) ")"))
                       (expect-clause "C call-over-call" ccr :b6.1
                                      [:mandatory-forward :fan-out] f2))

              ;; ============================================================
              ;; D — multi-shot outward operation
              ;; ============================================================
              ms   (multi-shot)
              msr  (lay/check [(record-layer (:handler ms) [])] (:recorded ms))
              f4   (do (banner "D  NEGATIVE CONTROL 3 — MULTI-SHOT OUTWARD OPERATION")
                       (println "  E33 records Tang's rejection: an outward operation that is")
                       (println "  control-flow-unrestricted and might resume zero or many times.")
                       (println "  perturb has NO resumptions (charter D4), so what is reproduced")
                       (println "  is the property the condition is about — an outward operation")
                       (println "  as a value whose invocation count and extent are unconstrained.")
                       (println (str "  the run ended: "
                                     (pr-str (or (:perturb.layer/threw (:recorded ms))
                                                 (:perturb.layer/value (:recorded ms))))))
                       (expect-clause "D multi-shot" msr :b6.4
                                      [:zero-shot-outward :multi-shot-outward
                                       :escaped-outward] f3))

              ;; ============================================================
              ;; E — the runtime refusals (PRACTICAL-ADOPTION §A3)
              ;; ============================================================
              f5   (do (banner "E  §A3's RUNTIME REFUSALS — each must FAIL, and each must LATCH")
                       (println "  These are not trace findings. perturb.effect/forward! refuses")
                       (println "  them at the crossing, and latching-aborts carries every one, so")
                       (println "  a layer cannot catch its own violation away.")
                       (println)
                       (let [net (:handler (script/server-session {:conns [cipher] :chunk-size 1}))
                             a1  (expect-abort
                                   "E1 self-forward (a rung whose named outer is itself)"
                                   :self-forward
                                   (fn []
                                     (fx/with-handlers
                                       {'perturb.wire/socket (tls/self-forwarding-handler net)}
                                       (w/listen "in-memory" 0 :perturb.layercheck/self)))
                                   f4)
                             a2  (expect-abort
                                   "E2 wrong owner (the rung is used under a different owner token)"
                                   :wrong-owner
                                   (fn []
                                     (let [r (fx/instance! 'perturb.layercheck/owned nil
                                                           (fx/as-instance
                                                             'perturb.layercheck/net net) nil)]
                                       (fx/with-owner :perturb.layercheck/other-thread
                                         (fx/forward! r w/socket :listen
                                                      :perturb.layercheck/owner ["h" 0]))))
                                   a1)
                             a3  (expect-abort
                                   "E3 forward from inside a finalizer"
                                   :forward-in-finalizer
                                   (fn []
                                     (fx/with-rung (tls/finalizer-forwarding-handler net)
                                       :body-did-nothing))
                                   a2)
                             a4  (expect-abort
                                   "E4 second use of one outward-operation instance"
                                   :outward-instance-reused
                                   (fn []
                                     (let [r (fx/instance! 'perturb.layercheck/reuser nil
                                                           (fx/as-instance
                                                             'perturb.layercheck/net net) nil)
                                           k (fx/outward! :perturb.layercheck/once)]
                                       (fx/with-outward k
                                         (fx/forward! r w/socket :listen
                                                      :perturb.layercheck/reuse-1 ["h" 0]))
                                       (fx/with-outward k
                                         (fx/forward! r w/socket :listen
                                                      :perturb.layercheck/reuse-2 ["h" 0]))))
                                   a3)]
                         a4))

              ;; ============================================================
              ;; F — forged / reordered projections
              ;; ============================================================
              canon (:perturb.layer/canonical kgr)
              f6   (do (banner "F  FORGERY — a forged or reordered projection must FAIL replay")
                       (println "  §A3: `a forged/reordered projected trace must fail")
                       (println "  replay/coherence`. E26's replay-history! posture, one rung up.")
                       (println (str "  the known-good canonical projection is "
                                     (count canon) " records, one per ATTEMPTED operation,"))
                       (println "  each carrying route and outcome including abort and malformed")
                       (println "  reply. Four mutations, each of which must be caught:")
                       (println)
                       (let [g1 (forge "F1 drop one record from the middle"
                                       canon
                                       (fn [c] (vec (concat (take 20 c) (drop 21 c))))
                                       f5)
                             g2 (forge "F2 swap two adjacent records"
                                       canon
                                       (fn [c] (vec (concat (take 20 c)
                                                            [(nth c 21) (nth c 20)]
                                                            (drop 22 c))))
                                       g1)
                             g3 (forge "F3 re-point a forward's parent at a LATER request"
                                       canon
                                       (fn [c] (assoc c 20 (assoc (nth c 20)
                                                                  :perturb.layer/parent
                                                                  (:perturb.layer/id (last c)))))
                                       g2)
                             g4 (forge "F4 forge the route: relabel a :forward as a :perform"
                                       canon
                                       (fn [c] (mapv (fn [r]
                                                       (if (= :forward (:perturb.layer/route r))
                                                         (assoc r :perturb.layer/route :perform)
                                                         r))
                                                     c))
                                       g3)
                             g5 (forge "F5 route a native :proceed through the layer log"
                                       canon
                                       (fn [c] (assoc c 20 (assoc (nth c 20)
                                                                  :perturb.layer/route :proceed)))
                                       g4)]
                         g5))

              ;; ============================================================
              ;; G — self-interception backstop
              ;; ============================================================
              probe (lay/record!
                      (fn [] (tls/no-deep-handler-probe 5)))
              pr-r  (lay/check [] probe)
              f7    (do (banner "G  SELF-INTERCEPTION BACKSTOP — the case forward! cannot refuse")
                        (println "  perturb.tlsish/no-deep-handler-probe reaches the boundary by")
                        (println "  PERFORMING, not forwarding: it installs a handler that performs")
                        (println "  its own effect with no rebinding and no named outer. `perform`")
                        (println "  does not pop the executing handler (E29 finding 1), so the")
                        (println "  refusal in `forward!` cannot see it — only the trace clause can.")
                        (println (str "    probe returned " (pr-str (:perturb.layer/value probe))))
                        (expect-clause "G self-interception" pr-r :b6.4
                                       [:self-interception] f6))]
          f7)]

    ;; ==================================================================
    ;; what this is worth
    ;; ==================================================================
    (banner "WHAT WAS SHOWN, ON THE EVIDENCE LATTICE")
    (println "  proved              nothing")
    (println "  bounded-complete    nothing")
    (println "  monitored           B6, over the runs this gate executes. Every")
    (println "                      clause is a fact about ONE RECORDED TRACE:")
    (println "                      the known-good passes all six, and each")
    (println "                      negative control fails the one named clause.")
    (println "  sampled             TWO rungs, ONE thread, ONE scripted transport,")
    (println "                      TWO pipelined requests, ONE connection. E29's")
    (println "                      real-socket rung is NOT run here — perturb.tlsdemo")
    (println "                      still runs it and this gate does not need a")
    (println "                      socket to state the invariant.")
    (println "  failed              nothing NEW. E29's failures stand: the capability")
    (println "                      discipline as a STATIC property of the layer, and")
    (println "                      the effect boundary as a seam a layer is obliged")
    (println "                      to use. B6 makes the second DETECTABLE; it does")
    (println "                      not make it obligatory, because a layer that")
    (println "                      calls the rung below is still a layer that runs.")
    (println)
    (println "  WHAT A THIRD RUNG WOULD ADD. `via` names ONE outer instance; three")
    (println "  rungs need two, nested, and nothing has run that. B6.1's correlation")
    (println "  is stated per adjacent pair, so a three-rung stack would test whether")
    (println "  the relation composes — which is the question E29 left open and this")
    (println "  does not answer.")
    (println "  WHAT A REAL TRANSPORT WOULD ADD. Contention (I20), a readiness")
    (println "  vocabulary (E26 finding 6), and a second thread of control — under")
    (println "  which *frame*, *outward* and *owner* become claims rather than")
    (println "  bookkeeping. The owner check here rebinds a TOKEN; perturb has never")
    (println "  run on two threads.")
    (println)
    (doseq [l (lay/report-limits)] (println (str "  " l)))

    (println)
    (println line)
    (if (empty? fails)
      (do (println "LAYER GATE OK — the known-good composition passed every clause, each")
          (println "                negative control failed the clause it was built for, every")
          (println "                §A3 refusal fired and latched, and five forged projections")
          (println "                were rejected. E33's label stands: a testable design")
          (println "                hypothesis, not a theorem.")
          (System/exit 0))
      (do (println (str "LAYER GATE FAILED — " (pr-str (vec fails))))
          (System/exit 1)))))
