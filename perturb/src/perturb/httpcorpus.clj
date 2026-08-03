(ns perturb.httpcorpus
  "The acceptance corpus for perturb's SECOND protocol. Same discipline as
  `perturb.corpus`: real perturb source, compiled by Jolt, checked from the IR
  the back end was handed, and every ACCEPT is also EXECUTED under a scripted
  handler as part of the gate (E15).

  `perturb.corpus` tests the rules against a capability shape they were designed
  around — one connection, a straight-line typestate. This one tests them against
  four shapes they were NOT designed around, and each section below says which:

    1. TWO CAPABILITIES AT ONCE — a Listener and a ServerConn live in the same
       scope, produced by one call at two positions, and passed to one helper at
       two argument positions.
    2. A TYPESTATE CYCLE — keep-alive returns a connection to :reading after
       responding. The loop rule preserves SHAPE, and a cycle is where shape
       preservation and state preservation coincide by construction.
    3. A RESOURCE THAT MUST BE FINISHED — a Content-Length body owes the wire
       exactly N octets. `body-never-finished` is what the typestate axis CAN
       say; `short-body-still-type-checks` is what it CANNOT.
    4. THE ABSTRACTION BOUNDARY — see `perturb.http/representation-note`; there
       is no corpus program for it, because it is a fact about which bodies are
       read at all.

  The scripted network delivers ONE OCTET PER RECV. Every accepted program here
  therefore drives `perturb.http/parse-request` through its `:need-more` arm on
  nearly every octet of every request, and any drift in the exact-original-cursor
  contract shows up as a run failure rather than as nothing."
  (:require [perturb.http :as h]
            [perturb.octet :as o]
            [perturb.script :as script]
            [perturb.cap :as cap]))

;; --- fixtures: request octets, and the scripted networks over them ----------

(defn request-octets
  "Build one HTTP/1.1 request as octets. `headers` must NOT contain
  content-length: `perturb.http` refuses a repeated field name, which is the
  point of refusing it."
  [method target headers body]
  (o/encode-utf8
    (str method " " target " HTTP/1.1\r\n"
         (reduce (fn [a p] (str a (first p) ": " (second p) "\r\n"))
                 "" (sort-by (fn [p] (first p)) (seq headers)))
         "content-length: " (count body) "\r\n"
         "\r\n" body)))

(def one-request  (request-octets "GET" "/one" {"host" "perturb"} ""))
(def post-request (request-octets "POST" "/echo" {"host" "perturb"} "hello"))

(defn- stream [n]
  (loop [i 0 acc o/empty-octets]
    (if (>= i n)
      acc
      (recur (inc i)
             (o/oconcat acc (request-octets "GET" (str "/" i) {"host" "perturb"} ""))))))

(def two-requests   (stream 2))
(def three-requests (stream 3))

;; 0-arg handler constructors. The gate calls these to build the handler each
;; accepted program runs under; `:chunk-size 1` is the default and is the whole
;; reason these exist.
(defn net-1 [] (script/server-handler {:conns [one-request]}))
(defn net-2 [] (script/server-handler {:conns [two-requests]}))
(defn net-3 [] (script/server-handler {:conns [three-requests]}))
(defn net-post [] (script/server-handler {:conns [post-request]}))

;; ===========================================================================
;; 1. TWO CAPABILITIES AT ONCE
;; ===========================================================================

(defn accept-serve-shutdown
  "ACCEPT. The straight-line correct server. `accept` returns a pair holding TWO
  DIFFERENT capabilities, and both are projected out and disposed of: the
  connection is served and closed, the listener is shut down. Nothing in
  `perturb.corpus` has a second capability at all."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)
        c1 (h/serve-connection c 2)
        l2 (h/shutdown! l1)]
    :done))

(defn serve-with-listener-held
  "ACCEPT. A DERIVED operation whose signature mentions two different
  capabilities at two argument positions and returns both at two result
  positions. This is the signature shape item 1 of the brief asks about, and it
  is checked against this body rather than believed — it is not a transition of
  either machine.

  Note the listener is neither consumed nor changed here; it is simply held live
  across the connection's whole lifetime and handed back."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener   :state :listening :arg 0}
                               {:cap 'perturb.http/ServerConn :state :reading   :arg 1}]
                    :produces [{:cap 'perturb.http/Listener   :state :listening :at [0]}
                               {:cap 'perturb.http/ServerConn :state :closed    :at [1]}]}}
  [l c]
  (let [c1 (h/serve-connection c 1)]
    [l c1]))

(cap/annotate-op! (var serve-with-listener-held)
                  (:perturb.cap/op (meta (var serve-with-listener-held))))

(defn uses-two-cap-helper
  "ACCEPT. Composition through a helper that takes and returns two different
  capabilities. Both are projected back out of the returned pair."
  [host port]
  (let [l0 (h/listen host port)
        a  (h/accept l0)
        l1 (first a)
        c  (second a)
        p  (serve-with-listener-held l1 c)
        l2 (first p)
        c1 (second p)
        l3 (h/shutdown! l2)]
    :done))

(defn accept-drops-the-listener
  "REJECT. Only position 1 of `accept`'s result is used. The listener at position
  0 is never shut down — the same shape as `perturb.corpus/drops-the-connection`,
  but now the leak is one of TWO capabilities produced by one call, which is what
  makes it a test of positions rather than of counting."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        c  (second a)
        c1 (h/serve-connection c 1)]
    :done))

(defn accept-drops-the-connection
  "REJECT. The mirror image: the listener is threaded correctly and the accepted
  connection at position 1 is dropped on the floor."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        l2 (h/shutdown! l1)]
    :done))

(defn swapped-two-cap-produces
  "REJECT. Declares the Listener at result position 0 and the ServerConn at 1,
  and returns them the other way round. `perturb.corpus/wrong-position` is the
  one-capability version; this is the version where the two positions hold
  DIFFERENT capabilities, so the mismatch is in the capability and not only in
  the index."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener   :state :listening :arg 0}
                               {:cap 'perturb.http/ServerConn :state :reading   :arg 1}]
                    :produces [{:cap 'perturb.http/Listener   :state :listening :at [0]}
                               {:cap 'perturb.http/ServerConn :state :closed    :at [1]}]}}
  [l c]
  (let [c1 (h/serve-connection c 1)]
    [c1 l]))

(cap/annotate-op! (var swapped-two-cap-produces)
                  (:perturb.cap/op (meta (var swapped-two-cap-produces))))

(defn listener-into-a-map-with-its-connection
  "REJECT. Both capabilities enter a map at once. A tuple position can be named
  by `:at`; a map key cannot, so neither can be followed out."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)]
    {:listener l1 :conn c}))

(defn borrow-and-return-listener
  "ACCEPT — AND IT SHOULD NOT BE. The listener is `:borrows`, so the caller keeps
  it; and it is also `:produces` at position 0, so the caller is handed one back.
  Both halves check here: the borrowed parameter is not this function's to close
  (E17's rule), and the returned value really does hold a Listener at position 0.

  The annotation is nonetheless self-contradictory — one runtime listener, two
  abstract capabilities — and NOTHING in the annotation language or the checker
  says so. The cost lands on the CALLER (`uses-borrow-and-return`, rejected
  below) as a leak of a capability the caller did nothing wrong with.
  PERTURB-DESIGN E18 finding 1."
  {:perturb.cap/op {:borrows  [{:cap 'perturb.http/Listener   :state :listening :arg 0}]
                    :consumes [{:cap 'perturb.http/ServerConn :state :reading   :arg 1}]
                    :produces [{:cap 'perturb.http/Listener   :state :listening :at [0]}
                               {:cap 'perturb.http/ServerConn :state :closed    :at [1]}]}}
  [l c]
  (let [c1 (h/serve-connection c 1)]
    [l c1]))

(cap/annotate-op! (var borrow-and-return-listener)
                  (:perturb.cap/op (meta (var borrow-and-return-listener))))

(defn uses-borrow-and-return
  "REJECT, AND THE DIAGNOSTIC BLAMES THE WRONG LINE. Every line of this is the
  same as `uses-two-cap-helper`, which is accepted; the only difference is that
  the helper it calls declares the listener `:borrows` as well as `:produces`.
  The checker therefore holds TWO live Listener capabilities — `l1`, which the
  borrow left alive, and the fresh one at position 0 of the result — shuts one
  down and reports the other as dangling.

  The program is fine. The annotation is not, and the checker has no rule that
  can point at it."
  [host port]
  (let [l0 (h/listen host port)
        a  (h/accept l0)
        l1 (first a)
        c  (second a)
        p  (borrow-and-return-listener l1 c)
        l2 (first p)
        c1 (second p)
        l3 (h/shutdown! l2)]
    :done))

;; ===========================================================================
;; 2. A TYPESTATE CYCLE
;; ===========================================================================

(defn loop-holding-both
  "ACCEPT. A loop with TWO CAPABILITY BINDINGS: the listener at binding 0 and the
  connection at binding 1, both live at every back edge. The loop rule indexes
  its shape invariant by binding POSITION; nothing before this had two positions
  for it to get wrong, and `loop-holding-both-swapped` below is the control."
  [host port n]
  (let [l0 (h/listen host port)
        a  (h/accept l0)
        l1 (first a)
        c0 (second a)
        p  (loop [l l1 c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)
                   c2  (h/respond! c1 (h/render req))]
               (if (<= i 1)
                 [l c2]
                 (recur l c2 (dec i)))))
        l2 (first p)
        c9 (second p)
        c8 (h/close-conn! c9)
        l3 (h/shutdown! l2)]
    :done))

(defn loop-holding-both-swapped
  "REJECT. `loop-holding-both` with the two loop bindings exchanged at the back
  edge. Both positions drift at once and each is a different capability, so the
  rule has to be comparing the capability and not only the state."
  [host port n]
  (let [l0 (h/listen host port)
        a  (h/accept l0)
        l1 (first a)
        c0 (second a)
        p  (loop [l l1 c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)
                   c2  (h/respond! c1 (h/render req))]
               (if (<= i 1)
                 [l c2]
                 (recur c2 l (dec i)))))
        l2 (first p)
        c9 (second p)
        c8 (h/close-conn! c9)
        l3 (h/shutdown! l2)]
    :done))

(defn accept-loop-leaks-connections
  "REJECT. The other way a keep-alive-shaped loop drops a connection: the loop is
  environment-preserving in the LISTENER — which is the binding the rule watches
  — and mints a ServerConn on every pass that nothing ever closes. The listener's
  invariant holds and the program still leaks."
  [host port n]
  (let [l0 (h/listen host port)]
    (loop [l l0 i n]
      (if (<= i 0)
        (h/shutdown! l)
        (let [a  (h/accept l)
              l1 (first a)
              c  (second a)]
          (recur l1 (dec i)))))))

(defn keep-alive-loop
  "ACCEPT — AND THE MAIN RESULT OF THIS FILE. A hand-written keep-alive loop:
  the same connection serves request -> response -> request -> … and is closed
  on the last pass. The loop binding enters at ServerConn@:reading, goes to
  :responding, and `respond!` brings it back to :reading, so the back edge
  re-enters at the shape it entered with.

  `perturb.nrepl`'s machine cannot produce this program: created -> active ->
  closed has no edge back to a state it has left."
  [host port n]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        c9 (loop [c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)
                   ov  (h/render req)]
               (if (<= i 1)
                 (h/close-conn! (h/respond! c1 ov))
                 (recur (h/respond! c1 ov) (dec i)))))
        l2 (h/shutdown! l1)]
    :done))

(defn keepalive-recurs-mid-cycle
  "REJECT. The same loop, recurring with the connection still at :responding —
  a response is owed and the body reads another request. Same capability, same
  path, DIFFERENT STATE at the back edge. This is the negative the cycle needs:
  the loop rule must accept a return to the start of the cycle and refuse a stop
  half way round it."
  [host port n]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        c9 (loop [c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)]
               (if (<= i 1)
                 (h/close-conn! c1)
                 (recur c1 (dec i)))))
        l2 (h/shutdown! l1)]
    :done))

(defn keepalive-drops-the-connection
  "REJECT. The loop's exit arm forgets to close. Every other line is the accepted
  program; the connection simply leaves scope at :responding."
  [host port n]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        c9 (loop [c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)]
               (if (<= i 1)
                 :done
                 (recur (h/respond! c1 (h/render req)) (dec i)))))
        l2 (h/shutdown! l1)]
    :done))

(defn accept-loop-shuts-down-inside
  "REJECT. The ACCEPT loop, not the keep-alive loop: the listener is shut down
  inside the body, so the back edge re-enters at Listener@:closed and the body
  cannot run twice. `perturb.corpus/loop-that-closes` is the one-capability
  version; here the loop body also creates and destroys a second capability on
  every pass, which the rule has to see through."
  [host port n]
  (let [l0 (h/listen host port)]
    (loop [l l0 i n]
      (if (<= i 0)
        :done
        (let [a  (h/accept l)
              l1 (first a)
              c  (second a)
              c1 (h/serve-connection c 1)
              l2 (h/shutdown! l1)]
          (recur l2 (dec i)))))))

(defn respond-without-reading
  "REJECT. `respond!` is legal only from :responding. A freshly accepted
  connection is at :reading, so this is a response to a request that was never
  read — the protocol error the cycle exists to make statable."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)
        c1 (h/respond! c (o/encode-utf8 "x"))
        c2 (h/close-conn! c1)
        l2 (h/shutdown! l1)]
    :done))

(defn read-twice-without-responding
  "REJECT. Two requests read with no response between them. HTTP/1.1 pipelining
  permits this at the WIRE level and this capability's machine does not: the
  machine encodes one-response-per-request, which is a decision, and the
  rejection is where that decision becomes visible."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c  (second a)
        r1 (h/read-request c)
        c1 (first r1)
        r2 (h/read-request c1)
        c2 (first r2)
        c3 (h/close-conn! c2)
        l2 (h/shutdown! l1)]
    :done))

(defn keepalive-conditional-close-value
  "REJECT — AND THIS IS THE SHAPE AN HTTP DRIVER MOST NATURALLY WANTS.

  `respond!`, then decide: if the peer asked for `Connection: close`, close;
  otherwise carry the connection on to the next request. Written the obvious way,
  the `if` yields a CONNECTION and one arm has consumed it. That is E6 probe 1's
  `if (c) { b = detach_result(b) }; use(b)` exactly, and §1.2 records it as a
  `known usability risk, unquantified`, with one data point: it fires zero times
  in `perturb.nrepl` (E15).

  It fires here, on the first driver anyone would write for the second protocol.
  `perturb.http/serve-connection` and `keep-alive-loop` above are the SAME driver
  written so it does not: the close is moved INSIDE the branch and the `recur`
  into the other, which makes the second arm bottom and the join vacuous. That
  rewrite is available and it is not obvious, and knowing it is required is now
  a fact about writing perturb code rather than an argument about one.
  PERTURB-DESIGN E18 finding 2."
  [host port n]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        c9 (loop [c c0 i n]
             (let [r   (h/read-request c)
                   c1  (first r)
                   req (second r)
                   c2  (h/respond! c1 (h/render req))
                   c3  (if (h/keep-alive? req) c2 (h/close-conn! c2))]
               (if (<= i 1) c3 (recur c3 (dec i)))))
        c8 (h/close-conn! c9)
        l2 (h/shutdown! l1)]
    :done))

(defn unpositioned-two-cap-helper
  "REJECT, AND THE DIAGNOSTIC NAMES THE WRONG ARGUMENT.

  An annotation whose entries omit `:arg`. `perturb.check` then falls back to
  matching specs to parameters IN ORDER — its own convention, not §1.2's, and
  `report-limits` item 2 already flags it. With ONE capability that fallback is
  merely unprincipled. With TWO it silently binds `c` to the Listener spec and
  `l` to the ServerConn spec, and the rejection that follows is reported against
  the call in the body rather than against the annotation that caused it.

  Kept as a rejection because it IS one; recorded because the reason it gives is
  not the reason it is wrong."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener   :state :listening}
                               {:cap 'perturb.http/ServerConn :state :reading}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [c l]
  (let [c1 (h/serve-connection c 1)]
    (h/shutdown! l)))

(cap/annotate-op! (var unpositioned-two-cap-helper)
                  (:perturb.cap/op (meta (var unpositioned-two-cap-helper))))

;; ===========================================================================
;; 3. A RESOURCE THAT MUST BE FINISHED, NOT CLOSED
;; ===========================================================================

(defn stream-a-body
  "ACCEPT. A three-capability program: Listener, ServerConn and ResponseBody are
  all live in the same scope. The body declares 6 octets and writes 6, and is
  finished; the connection comes back at :reading and is closed.

  The checker accepts this. Note carefully WHAT it accepted: that the body
  reached :finished. It did not check the 6."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        r  (h/read-request c0)
        c1 (first r)
        rb (h/respond-begin c1 200 "OK" {"content-type" "text/plain"} 6)
        c2 (first rb)
        b0 (second rb)
        b1 (h/body-write b0 (o/encode-utf8 "abc"))
        b2 (h/body-write b1 (o/encode-utf8 "def"))
        c3 (h/body-finish! c2 b2)
        c4 (h/close-conn! c3)
        l2 (h/shutdown! l1)]
    :done))

(defn short-body-still-type-checks
  "ACCEPT — AND THAT IS THE FINDING. Byte for byte `stream-a-body` with one
  `body-write` removed. It declares Content-Length: 6 and writes 3.

  `perturb.check` accepts it, the gate RUNS it, and it completes: the response
  it puts on the wire is malformed and no rule in §1.2 can say so, because
  `:finished` is a state and `wrote exactly N` is arithmetic over a run-time
  integer. `perturb.http/body-finish!` records the violation in the ledger
  instead of aborting, precisely so that this stays a STATIC gap rather than
  being papered over by a dynamic check.

  PERTURB-DESIGN E18 finding 3. The gate prints the ledger entry."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        r  (h/read-request c0)
        c1 (first r)
        rb (h/respond-begin c1 200 "OK" {"content-type" "text/plain"} 6)
        c2 (first rb)
        b0 (second rb)
        b1 (h/body-write b0 (o/encode-utf8 "abc"))
        c3 (h/body-finish! c2 b1)
        c4 (h/close-conn! c3)
        l2 (h/shutdown! l1)]
    :done))

(defn body-never-finished
  "REJECT. The half of the obligation the typestate axis CAN state: the body is
  written to and then simply left. `:open` is not terminal, so it leaks at scope
  exit. Note the connection is closed legally from :writing — nothing else in
  this program is wrong."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        r  (h/read-request c0)
        c1 (first r)
        rb (h/respond-begin c1 200 "OK" {} 6)
        c2 (first rb)
        b0 (second rb)
        b1 (h/body-write b0 (o/encode-utf8 "abc"))
        c3 (h/close-conn! c2)
        l2 (h/shutdown! l1)]
    :done))

(defn write-after-finish
  "REJECT. A write to a body that `body-finish!` already consumed. Use after
  move, on the third capability."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        r  (h/read-request c0)
        c1 (first r)
        rb (h/respond-begin c1 200 "OK" {} 3)
        c2 (first rb)
        b0 (second rb)
        b1 (h/body-write b0 (o/encode-utf8 "abc"))
        c3 (h/body-finish! c2 b1)
        b2 (h/body-write b1 (o/encode-utf8 "x"))
        c4 (h/close-conn! c3)
        l2 (h/shutdown! l1)]
    :done))

(defn finish-with-swapped-arguments
  "REJECT. `body-finish!` takes the connection at argument 0 and the body at
  argument 1. Swapping them is a type error the UNPOSITIONED annotation of E15
  could not have caught — with specs matched to arguments in order, a call
  holding one of each capability satisfies both entries whichever way round they
  are written."
  [host port]
  (let [l  (h/listen host port)
        a  (h/accept l)
        l1 (first a)
        c0 (second a)
        r  (h/read-request c0)
        c1 (first r)
        rb (h/respond-begin c1 200 "OK" {} 3)
        c2 (first rb)
        b0 (second rb)
        b1 (h/body-write b0 (o/encode-utf8 "abc"))
        c3 (h/body-finish! b1 c2)
        c4 (h/close-conn! c3)
        l2 (h/shutdown! l1)]
    :done))

;; ===========================================================================
;; what the checker must say — and, for every accept, that it RUNS
;; ===========================================================================

(def expectations
  "Same contract as `perturb.corpus/expectations`, plus one key: `:handler` names
  a 0-arg var that builds the scripted network the program runs under. Without
  it the gate would run an HTTP server against an nREPL peer."
  [{:var 'perturb.httpcorpus/accept-serve-shutdown :expect :accept
    :run ["in-memory" 0] :handler 'perturb.httpcorpus/net-2}
   {:var 'perturb.httpcorpus/serve-with-listener-held :expect :accept}
   {:var 'perturb.httpcorpus/uses-two-cap-helper :expect :accept
    :run ["in-memory" 0] :handler 'perturb.httpcorpus/net-1}
   {:var 'perturb.httpcorpus/keep-alive-loop :expect :accept
    :run ["in-memory" 0 3] :handler 'perturb.httpcorpus/net-3}
   {:var 'perturb.httpcorpus/stream-a-body :expect :accept
    :run ["in-memory" 0] :handler 'perturb.httpcorpus/net-post}
   {:var 'perturb.httpcorpus/short-body-still-type-checks :expect :accept
    :run ["in-memory" 0] :handler 'perturb.httpcorpus/net-post}

   {:var 'perturb.httpcorpus/borrow-and-return-listener :expect :accept}
   {:var 'perturb.httpcorpus/loop-holding-both :expect :accept
    :run ["in-memory" 0 2] :handler 'perturb.httpcorpus/net-2}

   {:var 'perturb.httpcorpus/uses-borrow-and-return      :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/loop-holding-both-swapped   :expect :reject
    :kind :loop-not-preserving}
   {:var 'perturb.httpcorpus/accept-loop-leaks-connections :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/accept-drops-the-listener   :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/accept-drops-the-connection :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/swapped-two-cap-produces    :expect :reject :kind :produces-mismatch}
   {:var 'perturb.httpcorpus/listener-into-a-map-with-its-connection
    :expect :reject :kind :escape}
   {:var 'perturb.httpcorpus/keepalive-recurs-mid-cycle  :expect :reject
    :kind :loop-not-preserving}
   {:var 'perturb.httpcorpus/keepalive-drops-the-connection :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/keepalive-conditional-close-value :expect :reject :kind :join}
   {:var 'perturb.httpcorpus/unpositioned-two-cap-helper :expect :reject
    :kind :untracked-consume}
   {:var 'perturb.httpcorpus/accept-loop-shuts-down-inside  :expect :reject
    :kind :loop-not-preserving}
   {:var 'perturb.httpcorpus/respond-without-reading     :expect :reject :kind :typestate}
   {:var 'perturb.httpcorpus/read-twice-without-responding :expect :reject :kind :typestate}
   {:var 'perturb.httpcorpus/body-never-finished         :expect :reject :kind :dangling}
   {:var 'perturb.httpcorpus/write-after-finish          :expect :reject :kind :use-after-move}
   {:var 'perturb.httpcorpus/finish-with-swapped-arguments :expect :reject
    :kind :untracked-consume}])
