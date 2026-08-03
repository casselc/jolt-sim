(ns perturb.httpdemo
  "perturb.http under BOTH handlers, the way `perturb.demo` runs `perturb.nrepl`.

  One var — `perturb.http/serve-connection`, the keep-alive driver — is called
  twice. Once with `perturb.script`'s in-memory network installed, which delivers
  ONE OCTET PER RECV; once with `perturb.posix`'s real TCP socket installed. No
  conditional in the driver, no transport argument, no second code path. The two
  runs' response octets are compared byte for byte and the demo exits non-zero if
  they differ.

  THE REAL-SOCKET HALF IS SINGLE-THREADED, AND THAT IS A DESIGN CONSTRAINT, NOT
  AN ACCIDENT. perturb has no readiness effect and §1.4 keeps control in an
  explicit kernel that does not exist yet, so `accept` and `recv` block. The
  sequence below works anyway because a `listen`ing socket completes a loopback
  `connect` in the kernel without `accept` being called, and because the client
  sends BOTH pipelined requests before the server reads either. Any HTTP server
  that had to interleave two peers would need the scheduler perturb has not
  built. Recorded so the demo is not mistaken for one.

  WHAT THIS DEMO DOES NOT ESTABLISH. It does not show the parser is correct — see
  `perturb.selftest`. It does not show the capability discipline is obeyed — see
  `perturb.check`, which reads the same source statically. It shows two handlers
  produce the same octets from the same compiled driver, and it shows the
  ledger's view of a typestate CYCLE, which is the thing `perturb.nrepl` cannot
  exhibit."
  (:require [perturb.http :as h]
            [perturb.octet :as o]
            [perturb.wire :as w]
            [perturb.effect :as fx]
            [perturb.cap :as cap]
            [perturb.script :as script]
            [perturb.posix :as posix]
            [perturb.httpcorpus :as hc]))

(def ^:private line
  "========================================================================")

(defn- banner [s] (println) (println (str "--- " s)))

;; --- the fixture ------------------------------------------------------------

(def req-1 (hc/request-octets "GET" "/first" {"host" "perturb"} ""))
(def req-2 (hc/request-octets "POST" "/second" {"host" "perturb"} "body!"))
(def both  (o/oconcat req-1 req-2))

;; --- run (a): the scripted network, one octet per recv ----------------------

(defn- count-site [trace site]
  (count (filter (fn [e] (= site (:perturb.effect/site e))) @trace)))

(defn run-scripted []
  (let [sess  (script/server-session {:conns [both] :chunk-size 1})
        trace (atom [])
        r     (fx/with-trace trace
                (fx/with-handlers {'perturb.wire/socket (:handler sess)}
                  (let [l  (h/listen "in-memory" 0)
                        a  (h/accept l)
                        l1 (first a)
                        c  (second a)
                        c1 (h/serve-connection c 2)
                        l2 (h/shutdown! l1)]
                    :served)))]
    {:result r :octets (script/sent-octets (:state sess) 0) :trace trace}))

;; --- run (b): a real POSIX socket, server and client in one process ---------

(defn- recv-at-least
  "Read until at least `n` octets have arrived or the peer closes. Not a parser:
  the demo already knows how long the answer is, and using the parser here would
  make the comparison circular."
  [tok n]
  (loop [acc o/empty-octets]
    (if (>= (o/ocount acc) n)
      acc
      (let [chunk (w/recv tok 65536 :perturb.httpdemo/client-recv)]
        (if (zero? (o/ocount chunk))
          acc
          (recur (o/oconcat acc chunk)))))))

(defn run-real [port expected-n trace]
  (fx/with-trace trace
   (fx/with-handlers {'perturb.wire/socket (posix/handler)}
    (let [l    (h/listen "127.0.0.1" port)
          ;; the client connects BEFORE the server accepts: the listen backlog
          ;; holds the connection, so a single thread can be both ends.
          ctok (w/connect "127.0.0.1" port :perturb.httpdemo/client-connect)
          _    (w/send! ctok both :perturb.httpdemo/client-send)
          a    (h/accept l)
          l1   (first a)
          c    (second a)
          c1   (h/serve-connection c 2)
          got  (recv-at-least ctok expected-n)
          _    (w/close! ctok :perturb.httpdemo/client-close)
          l2   (h/shutdown! l1)]
      got))))

;; --- the cycle, as the ledger saw it ----------------------------------------

(defn- conn-trace [cap-name]
  (filter (fn [e] (= cap-name (:perturb.cap/capability e))) @cap/ledger))

(defn- render-trace [es]
  (reduce (fn [acc e]
            (str acc (if (= "" acc) "" " -> ")
                 (if (= "" acc) (str (:perturb.cap/from e) " -> " (:perturb.cap/to e))
                     (str (:perturb.cap/to e)))))
          "" es))

;; --- the obligation demo ----------------------------------------------------

(defn- show-obligation []
  (banner "the obligation, and what the wire looks like when it is broken")
  (cap/reset-ledger!)
  (let [sess (script/server-session {:conns [req-1] :chunk-size 1})]
    ;; RUN DIRECTLY, NOT THROUGH THE GATE. `perturb.check` now REJECTS this
    ;; program, so it is no longer in any accept set and the gate never executes
    ;; it. It is called here on purpose: a rejection is a claim about a program,
    ;; and the evidence that the claim is about something real is the octets.
    (fx/with-handlers {'perturb.wire/socket (:handler sess)}
      (hc/short-body-still-type-checks "in-memory" 0))
    (let [ov (script/sent-octets (:state sess) 0)
          vs (filter (fn [e] (= :VIOLATED (:perturb.http/verdict e))) @cap/ledger)]
      (println "  perturb.httpcorpus/short-body-still-type-checks is now REJECTED by")
      (println "  perturb.check, which is the change: it used to be accepted and run.")
      (println "  It is executed here directly, so that what the rejected program would")
      (println "  have put on the wire stays visible:")
      (println)
      (println (str "    " (o/printable ov)))
      (println)
      (doseq [e vs]
        (println (str "    ledger: " (:perturb.cap/id e) "  declared Content-Length "
                      (:perturb.http/declared e) ", wrote " (:perturb.http/written e)
                      " -> " (name (:perturb.http/verdict e)))))
      (println)
      (println "  The response header says 6 and the body is 3 octets long. `:finished`")
      (println "  is a state and `wrote exactly N` is arithmetic; §1.2's typestate axis")
      (println "  expresses the first and CANNOT express the second. The second is now")
      (println "  a §1.3 refinement carried on the `:open -> :finished` transition and")
      (println "  discharged statically — `perturb.check` prints the counterexample and")
      (println "  `-M:refine` is the decision procedure with its boundary as a table.")
      (println "  The ledger entry below and the static rejection are two artifacts")
      (println "  about one program; only the static one arrives before it runs.")
      (empty? vs))))

;; --- main -------------------------------------------------------------------

(defn -main [& args]
  (let [port (if (seq args) (Integer/parseInt (first args)) 7899)]
    (println line)
    (println "perturb.http — one keep-alive driver, two handlers")
    (println line)

    (banner "the request stream both runs are given")
    (println (str "  " (o/printable both)))
    (println (str "  " (o/ocount both) " octets, two pipelined requests"))

    (banner "run (a): perturb.script's in-memory network, ONE OCTET PER RECV")
    (cap/reset-ledger!)
    (let [a (run-scripted)
          a-ov (:octets a)]
      (println (str "  driver returned  " (pr-str (:result a))))
      (println (str "  wrote            " (o/ocount a-ov) " octets"))
      (println (str "  " (o/printable a-ov)))
      (println (str "  server recvs     " (count-site (:trace a) :perturb.http/recv)
                    "  for " (o/ocount both) " octets of request"))
      (println "  every one of those but the last returned a `:need-more` from")
      (println "  perturb.http/parse-request carrying the EXACT cursor it was given.")
      (println "  Nothing else in the driver decides to read.")

      (banner "the typestate CYCLE, from the ledger")
      (println "  perturb.nrepl's machine is a line: active -> closed, minted from")
      (println "  nothing (it had a `:created` state until E18 1(b) deleted it).")
      (println "  this one returns to a state it has left, twice:")
      (println)
      (println (str "    ServerConn   " (render-trace (conn-trace 'perturb.http/ServerConn))))
      (println (str "    Listener     " (render-trace (conn-trace 'perturb.http/Listener))))
      (println)
      (println "  Two capabilities were live at once for the whole of the middle of")
      (println "  that trace. perturb.check accepts the source that produced it.")

      (banner (str "run (b): a real POSIX socket on 127.0.0.1:" port))
      (let [btrace (atom [])
            b-ov (try (run-real port (o/ocount a-ov) btrace)
                      (catch :default e {:err (str e)}))]
        (if (:err b-ov)
          (do (println (str "  FAILED: " (:err b-ov)))
              (println line)
              (println "HTTP DEMO FAILED — the real socket run did not complete")
              (System/exit 1))
          (do
            (println (str "  read back        " (o/ocount b-ov) " octets"))
            (println (str "  " (o/printable b-ov)))
            (println (str "  native calls     " (pr-str (posix/native-log-snapshot))))
            (println (str "  server recvs     " (count-site btrace :perturb.http/recv)
                          "  for the SAME two requests"))
            (println "  PIPELINING, and the reason the `:ok` cursor is kept rather than")
            (println "  reset: the loopback delivered both requests in one chunk, so the")
            (println "  second was served out of the buffer with no read at all.")
            (banner "CLAIM 2, second protocol")
            (let [same (= (o/ovec a-ov) (o/ovec b-ov))]
              (println (str "  scripted octets == socket octets : " (pr-str same)))
              (println "  the same compiled `perturb.http/serve-connection` produced both;")
              (println "  one saw the request one octet at a time, the other saw whatever")
              (println "  the loopback happened to deliver.")
              (let [ob-fail (show-obligation)]
                (println)
                (println line)
                (if (and same (not ob-fail))
                  (do (println "HTTP DEMO OK — both handlers, identical octets,")
                      (println "               and the unstatable obligation exhibited")
                      (System/exit 0))
                  (do (println "HTTP DEMO FAILED")
                      (System/exit 1)))))))))))
