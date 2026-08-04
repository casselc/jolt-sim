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
       octet per transport recv, two pipelined requests. Every clause THE TRACE
       EXERCISES must pass, and the clauses it does not exercise must SAY SO.
       Without this the gate would be a machine for proving that broken things
       are broken.
       THIS ARM'S CLAIM WAS OVERSTATED UNTIL THE THIRD OUTCOME LANDED. It used
       to read `every clause must pass`, and E35 recorded that it did. FIVE of
       six do, on evidence. The sixth, B6.3, is not exercised by this trace at
       all — it has zero refusals, and error mapping is a rule about refusals —
       so its silence was a vacuous pass. It now reports :inconclusive here and
       reaches a verdict in B and B', which is where the refusal is.
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

  AND ONE ASSERTION THAT CUTS ACROSS ALL OF THEM: THE VACUITY. Every run also
  asserts, BY EQUALITY, the exact set of clauses and arms it carries no
  evidence for. `perturb.layer` reports three outcomes now — `:pass`,
  `:violation`, `:inconclusive` — and the third one is a MEASUREMENT this file
  pins, so a clause that quietly stops being exercised fails the build instead
  of continuing to read as a pass. The numbers are in the `-inconclusive-`
  defs below, each with the reason beside it.

  WHAT A PASS MEANS AND DOES NOT MEAN is in the closing section, and it is short
  because the honest answer is short: two rungs, one thread, one scripted
  transport, one recorded run per control — and, on the known-good, five of six
  clauses."
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

(def ^:private tally
  "Every checked run, in the order the gate ran it, so the closing block can
  print the vacuity ACROSS fixtures. A clause's state on one trace is a local
  fact; `which clause reaches a verdict on which fixture, and which reaches one
  nowhere` is the fact the record needs, and it cannot be read off any single
  run's report."
  (atom []))

(defn- show [label result]
  (swap! tally conj [label result])
  (println (str "  " label))
  (doseq [l (lay/render result)] (println l)))

(defn- vacuity-table
  "The cross-fixture table: six clauses down, every checked run across, and the
  count of runs in which each clause reached a verdict at all."
  []
  (let [runs @tally]
    (println "  clause                                        exercised on")
    (doseq [c lay/clauses]
      (let [hit (filter (fn [r] (not= :inconclusive
                                      (get (lay/clauses-hit (second r)) c)))
                        runs)]
        (println (str "    " (subs (str c "                    ") 0 8)
                      (subs (str (count hit) " of " (count runs) " run(s)            ") 0 18)
                      (reduce (fn [t r] (str t (if (= t "") "" ", ") (first r)))
                              "" hit)))))
    (println)
    (println "  arm (finer: a clause passes on ONE exercised arm)")
    (doseq [a [:correlation :credit :projection :error-mapping :outward
               :finalisation :replay :propagation]]
      (let [hit (filter (fn [r] (not (contains? (lay/arms-with (second r) :inconclusive) a)))
                        runs)]
        (println (str "    " (subs (str (name a) "                    ") 0 16)
                      (subs (str (count hit) " of " (count runs) " run(s)            ") 0 18)
                      (reduce (fn [t r] (str t (if (= t "") "" ", ") (first r)))
                              "" hit)))))))

(defn- kws [s] (vec (sort (map str s))))

(defn- expect-vacuity
  "THE EXPECTATION THAT PINS THE VACUITY, and it is an EQUALITY on both sides.

  Not `at most these are inconclusive` and not `at least these are exercised`:
  the set of clauses this trace carries no evidence for is a MEASUREMENT, and
  a measurement that drifts in either direction is a changed fact about the
  gate. A clause that quietly stops being exercised must fail here — that is
  the negative control this file could not previously express — and a clause
  that starts being exercised must fail here too, because the honest reason
  for that is usually that a fixture grew traffic to cover a clause rather
  than a layer being tested on new evidence.

  Arm-level as well as clause-level, because a clause PASSES on one exercised
  arm while a sibling arm weighs nothing, and that is the residual vacuity the
  roll-up hides."
  [label result want-clauses want-arms fails]
  (let [got-c (lay/clauses-with result :inconclusive)
        got-a (lay/arms-with result :inconclusive)]
    (if (and (= (set want-clauses) got-c) (= (set want-arms) got-a))
      (do (println (str "    VACUITY: as measured — inconclusive clause(s) "
                        (pr-str (kws got-c)) ", unexercised arm(s) "
                        (pr-str (kws got-a))))
          fails)
      (do (println (str "    VACUITY: CHANGED — wanted inconclusive clauses "
                        (pr-str (kws want-clauses)) " arms " (pr-str (kws want-arms))
                        ", got clauses " (pr-str (kws got-c))
                        " arms " (pr-str (kws got-a))))
          (conj fails (str label " [vacuity]"))))))

(defn- expect-clean
  "`clean` now means TWO things, and it did not before: no clause found a
  violation, AND every clause reported as exercised was genuinely exercised.
  The inconclusive set is passed in rather than tolerated, so `this run was
  clean` can no longer be satisfied by a clause that was never asked."
  [label result inconclusive-clauses inconclusive-arms fails]
  (show label result)
  (let [f (if (empty? (:perturb.layer/violations result))
            (do (println (str "    VERDICT: PASS — no clause violated; "
                              (:clauses-pass (:perturb.layer/summary result))
                              " of 6 clauses were EXERCISED and held, "
                              (:clauses-inconclusive (:perturb.layer/summary result))
                              " were not exercised at all"))
                fails)
            (do (println "    VERDICT: FAIL — this run was supposed to be clean")
                (conj fails label)))]
    (expect-vacuity label result inconclusive-clauses inconclusive-arms f)))

(defn- expect-clause
  [label result want-clause want-rules inconclusive-clauses inconclusive-arms fails]
  (show label result)
  (let [cs (lay/clauses-hit result)
        rs (lay/rules-hit result)
        missing-rules (vec (remove rs want-rules))
        f (if (and (= :violation (get cs want-clause)) (empty? missing-rules))
            (do (println (str "    VERDICT: PASS — detected " (name want-clause)
                              " with rule(s) " (pr-str (vec want-rules))))
                fails)
            (do (println (str "    VERDICT: FAIL — wanted " (name want-clause)
                              " rules " (pr-str (vec want-rules))
                              ", got clause states " (pr-str cs)
                              " rules " (pr-str (kws rs))))
                (conj fails label)))]
    (expect-vacuity label result inconclusive-clauses inconclusive-arms f)))

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
        vs  (:perturb.layer/violations (lay/replay-coherence bad))]
    (println (str "  " label))
    (if (seq vs)
      (do (doseq [v (take 2 vs)]
            (println (str "      detected [" (name (:perturb.layer/rule v)) "] "
                          (:perturb.layer/message v))))
          (println "      VERDICT: PASS — the forged projection fails replay coherence")
          fails)
      (do (println "      VERDICT: FAIL — the forged projection was accepted")
          (conj fails label)))))

;; --- the vacuity, as measured -------------------------------------------------
;;
;; EVERY LINE BELOW IS A MEASUREMENT, NOT A TARGET. Each pair records which
;; clauses and which arms the named fixture carries NO EVIDENCE for. They are
;; asserted by equality, so a clause that stops being exercised fails the gate
;; — and so does a clause that starts being exercised, because the usual reason
;; for that is a fixture grown to cover a clause rather than a layer tested on
;; new evidence.
;;
;; The one number the record cares about is the first pair: on the known-good,
;; how much of B6 is actually being checked and how much was reading as a pass
;; because it was handed nothing.

(def kg-inconclusive-clauses
  "A — KNOWN-GOOD, AND THE NUMBER THIS WORK WAS FOR.

  FIVE of the six clauses are exercised on the known-good trace and hold. ONE
  — B6.3, error mapping — is NOT, and its previous `pass` was vacuous by
  construction: the trace contains 0 refusals, `check-error-mapping` folds over
  refusals inside a request's extent, so the fold visited nothing and emitted
  nothing, and a binary checker read that as success.

  E35's sentence `known-good passes every clause` was therefore 5/6 true. It is
  corrected, not deleted: the trace is real, 711 events and 184 attempted
  operations are real, and the five clauses that hold on it hold on evidence.

  THE FIX IS NOT TO GIVE THE KNOWN-GOOD A REFUSAL. B6.3's evidence lives in
  control B, which refuses a transport read on purpose; that is where the
  clause reaches `violation` (B) and `pass` (B'). Adding a refusal here to
  make the clause look covered would rebuild the vacuous pass with extra
  steps."
  [:b6.3])
(def kg-inconclusive-arms
  "Also `:propagation` — B6.6's second arm — inside a clause that PASSES on its
  replay arm over 184 records. The known-good is not refused, so no attempt
  ends `:propagated` and the arm weighs nothing. This is the residual vacuity
  the clause roll-up hides and the arm set exposes."
  [:error-mapping :propagation])

(def ld-inconclusive-clauses
  "B — ABORT LAUNDERING. All six clauses exercised: this is the ONLY fixture in
  the gate that puts evidence in front of B6.3."
  [])
(def ld-inconclusive-arms [:propagation])

(def ld2-inconclusive-clauses
  "B' — THE SAME TRACE WITH THE EDGE DECLARED. 6 of 6 clauses exercised and
  holding — the only run in this gate of which that is true, and the only place
  `every clause passed` can be said without qualification."
  [])
(def ld2-inconclusive-arms [:propagation])

(def cc-inconclusive-clauses
  "C — CALL-OVER-CALL. B6.5 joins B6.3: a run in which NOTHING FORWARDED has no
  rung in finalisation's scope, so the clause has no obligation to check. That
  is a second vacuous pass this control was previously reporting, and it is a
  direct consequence of the defect the control exists to demonstrate."
  [:b6.3 :b6.5])
(def cc-inconclusive-arms [:error-mapping :finalisation :propagation])

(def ms-inconclusive-clauses
  "D — MULTI-SHOT OUTWARD OPERATION. The fixture drives `perturb.wire` directly
  rather than through `perturb.http`, so no capability transition is ever noted
  and B6.2 projects onto an empty ledger."
  [:b6.2 :b6.3])
(def ms-inconclusive-arms [:projection :error-mapping :propagation])

(def probe-inconclusive-clauses
  "G — SELF-INTERCEPTION BACKSTOP. Checked with NO declared layers at all
  (`(lay/check [] probe)`), which is the honest shape for a probe that declares
  none — and four of the six clauses have nothing to read as a result. Only
  B6.4, the clause the control is for, and B6.6 reach a verdict."
  [:b6.1 :b6.2 :b6.3 :b6.5])
(def probe-inconclusive-arms
  "`:propagation` is absent from this list, and it is the only fixture for which
  that is true: the probe is refused, so five of its attempts end `:propagated`
  and B6.6's second arm has records to adjudicate."
  [:correlation :credit :projection :error-mapping :finalisation])

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
                       (println "  record boundary aligned with an HTTP one. Every clause THIS")
                       (println "  TRACE EXERCISES must hold, and the clauses it does not exercise")
                       (println "  must say so — see the vacuity line.")
                       (expect-clean "A known-good" kgr
                                     kg-inconclusive-clauses kg-inconclusive-arms []))
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
                       (expect-clause "B laundering" ldr :b6.3 [:unmapped-refusal]
                                      ld-inconclusive-clauses ld-inconclusive-arms f0))

              ;; ============================================================
              ;; B' — the same program with the edge declared
              ;; ============================================================
              ldr2 (lay/check [(record-layer (:handler ld) eof-mapping-edge)] (:recorded ld))
              f2   (do (banner "B' THE SAME TRACE WITH AN EXPLICIT ERROR-MAPPING EDGE")
                       (println "  Same run, same trace, same checker. The only difference is that")
                       (println "  the layer now DECLARES {:op :recv :abort :handler-abort}. If")
                       (println "  this did not pass, B6.3 would be a rule nothing can satisfy.")
                       (println "  This is also the ONE ARM in the gate where B6.3 reaches a")
                       (println "  verdict rather than reporting :inconclusive.")
                       (let [r (expect-clean "B' error-mapping edge declared" ldr2
                                             ld2-inconclusive-clauses ld2-inconclusive-arms f1)]
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
                                      [:mandatory-forward :fan-out]
                                      cc-inconclusive-clauses cc-inconclusive-arms f2))

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
                                       :escaped-outward]
                                      ms-inconclusive-clauses ms-inconclusive-arms f3))

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
                                       [:self-interception]
                                       probe-inconclusive-clauses probe-inconclusive-arms f6))]
          f7)]

    ;; ==================================================================
    ;; what this is worth
    ;; ==================================================================
    (banner "HOW MUCH OF B6 IS ACTUALLY EXERCISED — the measurement, per fixture")
    (println "  A clause is `exercised` on a run when at least one of its arms")
    (println "  adjudicated at least one unit of evidence. A clause that adjudicated")
    (println "  NOTHING reports :inconclusive and is counted here as not exercised,")
    (println "  because its silence carries no information about the layer.")
    (println)
    (vacuity-table)
    (println)
    (println "  READ IT THIS WAY, AND DISCOUNT IT TWICE.")
    (println "  1. The denominator is 6 CHECKED RUNS, not 6 independent traces. B")
    (println "     and B' are THE SAME RECORDED TRACE checked against two layer")
    (println "     declarations, so B6.3's `2 of 6` is really ONE trace — the only")
    (println "     one in the gate that refuses anything inside a layer's extent.")
    (println "  2. No single fixture exercises all six clauses except B', so the")
    (println "     gate's coverage of B6 is a UNION over fixtures and not a property")
    (println "     of the known-good. The known-good is 5 of 6.")
    (println "  Nothing here is a coverage denominator in E18 finding 4's sense: the")
    (println "  fixtures were not enumerated from anything, and `6 of 6` for B6.4")
    (println "  means `every fixture happened to contain one`, not `complete`.")

    (banner "WHAT WAS SHOWN, ON THE EVIDENCE LATTICE")
    (println "  proved              nothing")
    (println "  bounded-complete    nothing")
    (println "  monitored           B6, over the runs this gate executes, AND ONLY")
    (println "                      WHERE A RUN CARRIED EVIDENCE. Every clause is a")
    (println "                      fact about ONE RECORDED TRACE. The known-good")
    (println "                      exercises FIVE of six and holds on all five;")
    (println "                      B6.3 is :inconclusive there because that trace")
    (println "                      refuses nothing, and it reaches a verdict in")
    (println "                      controls B and B'. Each negative control fails")
    (println "                      the one named clause.")
    (println "  NOT MONITORED, and this line is new: a clause reported")
    (println "                      :inconclusive on a run is not monitored ON THAT")
    (println "                      RUN. Across the whole gate every clause reaches")
    (println "                      a verdict SOMEWHERE — B6.3 only in B/B', B6.5")
    (println "                      not in C, B6.2 not in D — so the union covers")
    (println "                      six of six and no single fixture does. B6.6's")
    (println "                      :propagation arm reaches a verdict in exactly")
    (println "                      ONE place, the G probe, because it is the only")
    (println "                      run here that propagates a refusal.")
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
      (do (println "LAYER GATE OK — the known-good composition passed every clause IT")
          (println "                EXERCISES (five of six; B6.3 is :inconclusive there and")
          (println "                reaches its verdict in controls B and B'), each negative")
          (println "                control failed the clause it was built for, the vacuity of")
          (println "                every fixture matched its recorded measurement, every §A3")
          (println "                refusal fired and latched, and five forged projections were")
          (println "                rejected. E33's label stands: a testable design hypothesis,")
          (println "                not a theorem.")
          (System/exit 0))
      (do (println (str "LAYER GATE FAILED — " (pr-str (vec fails))))
          (System/exit 1)))))
