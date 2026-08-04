(ns perturb.evtcheck
  "Run `perturb.check` over `perturb.evt` and `perturb.evtapp`, print the
  verdicts, and then RUN both drivers — under `perturb.script`'s in-memory
  network and over a real loopback socket — because a checked artifact that does
  not execute teaches nothing.

  THIS IS NOT A GATE. It records no expectations, in the style of
  `perturb.streamcheck`: the point is to find out what the checker says, not to
  assert it. It exits 0 whatever the verdicts are, and exits non-zero only if
  the code fails to RUN or the two handlers disagree about the octets.

  WHAT IS BEING MEASURED. `perturb.evtapp` holds no capability and carries no
  annotation, so the hypothesis is that it checks clean and that the whole cost
  lands on `perturb.evt`. Both halves of that are printed below. So is the
  POSITIVE CONTROL — four programs in `perturb.evtapp` that are wrong, of which
  the checker rejects two — and the run that shows what the two it accepts
  actually put on the wire.

  THE STRONGEST THING PRINTED BELOW IS NOT A CONTROL. `perturb.evtapp`'s `/wait`
  route defers a response by one round, which is an ordinary event-driven move
  written into the WORKING application. Underneath, it makes the driver read a
  second request from a connection that still owes a response — the program
  `perturb.httpcorpus/read-twice-without-responding` is REJECTED for. It is
  accepted here, it runs, and the only evidence is a ledger that does not join
  up. Nothing was wrong on purpose."
  (:require [perturb.ir :as pir]
            [perturb.check :as chk]
            [perturb.effect :as fx]
            [perturb.octet :as o]
            [perturb.wire :as w]
            [perturb.cap :as cap]
            [perturb.script :as script]
            [perturb.posix :as posix]))

(def ^:private line
  "========================================================================")

(defn- banner [s] (println) (println (str "--- " s)))

(defn- v [s] (deref (resolve s)))

;; --- fixtures ---------------------------------------------------------------

(defn request-octets
  "One HTTP/1.1 request as octets. Local copy so this namespace does not pull in
  `perturb.httpcorpus`."
  [method target body]
  (o/encode-utf8
    (str method " " target " HTTP/1.1\r\nhost: perturb\r\n"
         "content-length: " (count body) "\r\n\r\n" body)))

(defn- reqs [& rs] (reduce (fn [a r] (o/oconcat a r)) o/empty-octets rs))

;; Driver A wants exactly one response per request per connection.
(def a-conn-0 (reqs (request-octets "GET" "/count" "")
                    (request-octets "POST" "/echo" "hello")))
(def a-conn-1 (reqs (request-octets "GET" "/count" "")
                    (request-octets "GET" "/count" "")))

;; Driver B exercises the interleaving: conn 0 DEFERS its first response
;; (`/wait`) so round 1 answers only conn 1, and round 2 answers conn 0 twice.
(def b-conn-0 (reqs (request-octets "GET" "/wait" "")
                    (request-octets "GET" "/count" "")))
(def b-conn-1 (reqs (request-octets "GET" "/count" "")
                    (request-octets "GET" "/bye" "")))

;; --- the verdicts -----------------------------------------------------------

(defn- report-ns
  "One namespace, every captured def, in the style of `perturb.streamcheck`.
  Returns [checked-count total-count rejected-vars]."
  [sp ns-name lines]
  (println)
  (println (str "== " ns-name " ============================================="))
  (doseq [l lines] (println (str "   " l)))
  (println)
  (let [results (chk/check-namespace! sp ns-name)
        ax?     (fn [r] (or (contains? (:primitives sp) (:var r))
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
    (println (str "  " (count checked) " of " (count results)
                  " functions in " ns-name " were CHECKED; " (count bad)
                  " rejected."))
    (println (str "  " (count (filter ax? results))
                  " are axioms (declared transitions or :perturb.cap/representation"
                  " entries)."))
    [(count checked) (count results) (vec (map :var bad))]))

;; --- running ----------------------------------------------------------------

(defn- run-scripted
  "Run `f` with a scripted server network over `conns`. Returns
  {:result r :octets [per-conn octets]}."
  [conns f]
  (let [sess (script/server-session {:conns conns :chunk-size 1})
        r    (fx/with-handlers {'perturb.wire/socket (:handler sess)}
               (f))]
    {:result r
     :octets (vec (map (fn [i] (script/sent-octets (:state sess) i))
                       (range (count conns))))}))

(defn- recv-at-least [tok n]
  (loop [acc o/empty-octets]
    (if (>= (o/ocount acc) n)
      acc
      (let [chunk (w/recv tok 65536 :perturb.evtcheck/client-recv)]
        (if (zero? (o/ocount chunk))
          acc
          (recur (o/oconcat acc chunk)))))))

(defn- run-real
  "The same driver over a REAL loopback socket, single-threaded.

  Both clients connect and send BEFORE the server accepts either: the listen
  backlog holds the connections, so one thread can be both ends. This is
  `perturb.httpdemo`'s trick with two peers instead of one, and it is the
  closest this artifact gets to a server that interleaves — the driver really
  does hold two accepted sockets at once and read from each in turn."
  [port streams drive expect-ns]
  (fx/with-handlers {'perturb.wire/socket (posix/handler)}
    (let [l    ((v 'perturb.http/listen) "127.0.0.1" port)
          k0   (w/connect "127.0.0.1" port :perturb.evtcheck/client-connect)
          k1   (w/connect "127.0.0.1" port :perturb.evtcheck/client-connect)
          _    (w/send! k0 (nth streams 0) :perturb.evtcheck/client-send)
          _    (w/send! k1 (nth streams 1) :perturb.evtcheck/client-send)
          _    (drive l)
          g0   (recv-at-least k0 (nth expect-ns 0))
          g1   (recv-at-least k1 (nth expect-ns 1))
          _    (w/close! k0 :perturb.evtcheck/client-close)
          _    (w/close! k1 :perturb.evtcheck/client-close)]
      [g0 g1])))

(defn- show-octets [label ovs]
  (doseq [i (range (count ovs))]
    (println (str "    " label " conn " i "  " (o/ocount (nth ovs i)) " octets"))
    (println (str "      " (o/printable (nth ovs i))))))

;; --- the ledger, read back for contradictions -------------------------------

(defn ledger-breaks
  "Every place the ledger CONTRADICTS ITSELF: an entry whose `:from` is not the
  previous entry's `:to` for the same capability id.

  This is not a checker. It is the weakest possible after-the-fact reading of a
  run, and it is here because two of `perturb.evtapp`'s wrong programs are
  ACCEPTED statically — so the only evidence that they are wrong is what the run
  did. Note that `perturb.http`'s transitions record their `:from` as a LITERAL,
  so a violated edge still records the state it was supposed to start in; the
  contradiction is between consecutive entries, not inside one."
  []
  (reduce
    (fn [acc e]
      (let [id   (:perturb.cap/id e)
            seen (get (:last acc) id)]
        {:last  (assoc (:last acc) id (:perturb.cap/to e))
         :bad   (if (and seen (not= seen (:perturb.cap/from e)))
                  (conj (:bad acc) {:id id :was seen :claims (:perturb.cap/from e)
                                    :site (:perturb.cap/site e)})
                  (:bad acc))}))
    {:last {} :bad []} @cap/ledger))

;; --- main -------------------------------------------------------------------

(defn -main [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 8090)]
    (println line)
    (println "perturb.evt / perturb.evtapp — a driver that owns every capability,")
    (println "and an application that is a pure (state, event) -> [state', effects]")
    (println line)

    (pir/capture! ['perturb.evt 'perturb.evtapp])
    (let [sp (chk/spec)
          ra (report-ns
               sp "perturb.evtapp"
               ["THE APPLICATION. Pure. No capability, no perturb.cap annotation."
                "`step` and the four helpers above it are the hypothesis; the four"
                "programs below them are the POSITIVE CONTROL and two of them are"
                "wrong in a way nothing here can see."])
          rb (report-ns
               sp "perturb.evt"
               ["THE DRIVER. It owns the Listener, both ServerConns and every"
                "ResponseBody. Driver A (drive-two*/serve-two*) is a fixed 2-slot"
                "register file and CHECKS; driver B (accept-into-table, read-round,"
                "apply-effect, close-table) is a MAP from id to connection and does"
                "not. Neither adds an axiom or a :perturb.cap/representation entry."])]

      (banner "what the two counts mean, side by side")
      (println (str "  perturb.evtapp : " (nth ra 0) " checked, "
                    (count (nth ra 2)) " rejected  " (pr-str (nth ra 2))))
      (println (str "  perturb.evt    : " (nth rb 0) " checked, "
                    (count (nth rb 2)) " rejected  " (pr-str (nth rb 2))))

      ;; --- run driver A -----------------------------------------------------
      (banner "RUN: driver A (the checked one), scripted network, 1 octet per recv")
      (cap/reset-ledger!)
      (let [a (run-scripted
                [a-conn-0 a-conn-1]
                (fn [] ((v 'perturb.evt/serve-two) "in-memory" 0 2
                        (v 'perturb.evtapp/initial-state) (v 'perturb.evtapp/step))))]
        (println (str "  driver returned  " (pr-str (:result a))))
        (show-octets "scripted" (:octets a))
        (println "  Two ServerConns and a Listener were live in one scope for the whole")
        (println "  of that run, and both requests of a round were READ before either")
        (println "  was answered. perturb.check accepts every line of it.")

        (banner (str "RUN: driver A over a real loopback socket on 127.0.0.1:" port))
        (let [ns0 (o/ocount (nth (:octets a) 0))
              ns1 (o/ocount (nth (:octets a) 1))
              got (try (run-real port [a-conn-0 a-conn-1]
                                 (fn [l] ((v 'perturb.evt/serve-two-with-listener) l 2
                                          (v 'perturb.evtapp/initial-state)
                                          (v 'perturb.evtapp/step)))
                                 [ns0 ns1])
                       (catch :default e {:err (str e)}))]
          (if (:err got)
            (do (println (str "  FAILED: " (:err got)))
                (println line)
                (println "EVT FAILED — the real socket run did not complete")
                (System/exit 1))
            (do
              (show-octets "socket  " got)
              (let [same (and (= (o/ovec (nth (:octets a) 0)) (o/ovec (nth got 0)))
                              (= (o/ovec (nth (:octets a) 1)) (o/ovec (nth got 1))))]
                (println (str "  scripted octets == socket octets, BOTH connections : "
                              (pr-str same)))

                ;; --- run driver B ---------------------------------------------
                (banner "RUN: driver B (the rejected one), scripted network")
                (cap/reset-ledger!)
                (let [b (run-scripted
                          [b-conn-0 b-conn-1]
                          (fn [] ((v 'perturb.evt/serve-table) "in-memory" 0 2 2
                                  (v 'perturb.evtapp/initial-state)
                                  (v 'perturb.evtapp/step))))]
                  (println (str "  driver returned  " (pr-str (:result b))))
                  (show-octets "scripted" (:octets b))
                  (println "  conn 0 asked for /wait first, so ROUND 1 answered only conn 1 —")
                  (println "  the application deferred, the driver went on to the other")
                  (println "  connection, and round 2 put TWO responses on conn 0. That is the")
                  (println "  respond-to-whichever-is-ready shape, and it is the shape driver A")
                  (println "  cannot express: `[:close 1]` was also obeyed here and is ignored")
                  (println "  there.")
                  (println)
                  (println "  AND HERE IS THE RESULT NOBODY ASKED FOR. `/wait` is not a control.")
                  (println "  It is an ordinary re-frame move — hold the response, answer later —")
                  (println "  written into the WORKING application, and it makes the driver read")
                  (println "  a second request from a connection that still owes a response. That")
                  (println "  is perturb.httpcorpus/read-twice-without-responding, which the")
                  (println "  checker REJECTS with `:typestate` when it is written against a")
                  (println "  capability. Written as data by a pure step function it is accepted,")
                  (println "  and the only trace of it is that the ledger does not join up:")
                  (println (str "    ledger contradictions during this run : "
                                (pr-str (:bad (ledger-breaks)))))
                  (println "  `was :responding, claims :reading` is the second read; the entry")
                  (println "  after it is the deferred response going out from :reading. The")
                  (println "  application was not wrong on purpose and the driver did what it was")
                  (println "  told; the declared machine was broken by the ARCHITECTURE.")

                  (banner (str "RUN: driver B over a real loopback socket on 127.0.0.1:"
                               (inc port)))
                  (let [bs0 (o/ocount (nth (:octets b) 0))
                        bs1 (o/ocount (nth (:octets b) 1))
                        bgot (try (run-real (inc port) [b-conn-0 b-conn-1]
                                            (fn [l] ((v 'perturb.evt/serve-table-with-listener)
                                                     l 2 2
                                                     (v 'perturb.evtapp/initial-state)
                                                     (v 'perturb.evtapp/step)))
                                            [bs0 bs1])
                                  (catch :default e {:err (str e)}))]
                    (if (:err bgot)
                      (do (println (str "  FAILED: " (:err bgot)))
                          (println line)
                          (println "EVT FAILED — driver B's real socket run did not complete")
                          (System/exit 1))
                      (let [bsame (and (= (o/ovec (nth (:octets b) 0)) (o/ovec (nth bgot 0)))
                                       (= (o/ovec (nth (:octets b) 1)) (o/ovec (nth bgot 1))))]
                        (show-octets "socket  " bgot)
                        (println (str "  scripted octets == socket octets, BOTH connections : "
                                      (pr-str bsame)))

                        ;; --- the positive control, executed ------------------
                        (banner "THE POSITIVE CONTROL, RUN: an application the checker ACCEPTED")
                        (println "  perturb.evtapp/two-responses-for-one-request is [ok] above.")
                        (println "  It emits TWO :respond effects for one :request event, which is")
                        (println "  perturb.httpcorpus/respond-without-reading's error — rejected")
                        (println "  there, accepted here, because here it is two vectors of data.")
                        (println)
                        (cap/reset-ledger!)
                        (let [c (run-scripted
                                  [(request-octets "GET" "/count" "")]
                                  (fn [] ((v 'perturb.evt/serve-table) "in-memory" 0 1 1
                                          (v 'perturb.evtapp/initial-state)
                                          (v 'perturb.evtapp/two-responses-for-one-request))))
                              brk (:bad (ledger-breaks))]
                          (show-octets "wire    " (:octets c))
                          (println)
                          (println "  ONE request, TWO responses on the wire. Neither the checker")
                          (println "  nor the ledger's own from/to fields say so: perturb.http's")
                          (println "  transitions record :from as a literal, so the second respond!")
                          (println "  claims to have started at :responding. The only trace is that")
                          (println "  consecutive entries do not join up:")
                          (println (str "    ledger contradictions : " (pr-str brk)))
                          (println)
                          (println "  perturb.evtapp/responds-after-closing is the other accepted")
                          (println "  wrong program. Driver B does not put it on the wire, and the")
                          (println "  reason is worth stating: `apply-effect` carries a hand-written")
                          (println "  `(nil? c)` guard, which is a DYNAMIC use-after-close check the")
                          (println "  driver had to invent because the static one stops at the map.")

                          (println)
                          (println line)
                          (if (and same bsame)
                            (do (println "EVT OK — both drivers ran under both handlers with identical")
                                (println "         octets. Read the VERDICTS above, not this line: the")
                                (println "         driver that services a table of connections is the")
                                (println "         one the checker refuses.")
                                (System/exit 0))
                            (do (println "EVT FAILED — the two handlers disagreed about the octets")
                                (System/exit 1))))))))))))))))
