(ns perturb.evt
  "AN EVENT-DRIVEN DRIVER over `perturb.http`. It owns every capability there is
  — the Listener, every ServerConn, every ResponseBody — and hands the
  application ordinary data.

  THE HYPOTHESIS THIS EXISTS TO TEST. If user code is `(state, event) ->
  [state', effects]` and holds no capability, then the whole cost of the
  capability discipline collapses into ONE component: this one. So the question
  is not whether `perturb.evtapp` checks (it does; see `perturb.evtcheck`) but
  WHAT THAT COSTS HERE. This namespace is written to make the cost measurable
  rather than to make it small, and it contains TWO drivers on purpose:

    A. THE REGISTER FILE — `drive-two-once`, `drive-two`, `serve-two`. The
       connection table is a LITERAL 2-TUPLE and every position is written down
       in the annotation. It holds a Listener and TWO ServerConns live in one
       scope, reads a request from each before answering either, and it CHECKS.
       Nothing in perturb had two connections live at once before this.

    B. THE TABLE — `accept-into-table`, `read-round`, `apply-effect`,
       `apply-effects`, `close-table`, `serve-table-with-listener`. The
       connection table is a MAP from id to ServerConn, which is what anyone
       would write, and it services N connections and honours the app's
       `[:close id]`. It is REJECTED, function by function, and the rejections
       are the measurement.

  Both run. `perturb.evtcheck` executes them under `perturb.script`'s in-memory
  network and over a real loopback socket, and compares the octets.

  WHAT DRIVER A CANNOT DO, STATED HERE BECAUSE IT IS THE RESULT.

    1. It cannot be indexed by a value. `nth` with a computed index is not a
       tuple eliminator, and `(if (= id 0) … c0 … c1)` consumes a capability in
       one arm of an `if` and not the other, which is the JOIN rule. So the
       connection an effect names has to be known when the driver is WRITTEN.
       `honour-close-effect` below is the smallest program that shows this.
    2. It therefore cannot honour `[:close id]`; it closes both connections at
       the end regardless. An application that emits `[:close 1]` is obeyed by
       driver B and ignored by driver A.
    3. It cannot grow. `table-grows-in-a-loop` below is a loop that accepts into
       a literal tuple, and the loop rule refuses it at the back edge: the shape
       at the top of the loop is `no capability` and at the bottom is `one`.
    4. It cannot answer `no response yet`. Every path through `drive-two-once`
       performs exactly one `respond!` per connection per round, so an
       application that defers (`perturb.evtapp`'s `/wait`) gets a 500 from
       `effect-response-octets` instead of silence.

  AND WHAT NEITHER DRIVER CAN DO, WHICH IS THE RESULT. Driver B CAN defer, and
  when the application defers, driver B reads a second request from a connection
  that still owes a response. That is
  `perturb.httpcorpus/read-twice-without-responding` — rejected as `:typestate`
  when written against a capability — reached by an application that holds no
  capability and is accepted. The boundary did not remove the error; it put it
  somewhere the checker cannot look. `perturb.evtcheck` runs it.

  NO NEW AXIOMS. This namespace adds no capability declaration, no
  `:perturb.cap/representation` entry and no transition. Every function in it is
  inside the checked set — the cost of the table shows up as REJECTIONS, not as
  unchecked bodies. That is a choice: the alternative was to declare a
  `ConnTable` capability and make its operations axioms, which is the move
  `perturb.check`'s report-limits item 1 says is gameable by writing bigger
  functions.

  WHAT THIS IS NOT. Not concurrent — perturb has no threads and no readiness
  effect, so `recv` blocks and `whichever is ready` is not observable. The
  interleaving here is explicit: read from every connection, then apply every
  effect in the order the application produced them, which is enough for a
  response to conn 1 to precede a response to conn 0. A real event loop would
  need the scheduler §1.4 does not have.")

(require '[perturb.http :as h])
(require '[perturb.octet :as o])
(require '[perturb.cap :as cap])

;; ===========================================================================
;; PURE: events in, effects out. No capability appears anywhere in this section
;; ===========================================================================

(defn request-event [id req] [:request id req])

(defn open-events
  "`[:opened 0] … [:opened (dec n)]`."
  [n]
  (loop [i 0 acc []]
    (if (>= i n) acc (recur (inc i) (conj acc [:opened i])))))

(defn effect-octets
  "One `[:respond id status reason headers body-string]` effect -> response
  octets, via `perturb.http/response-octets`. Pure."
  [e]
  (h/response-octets (nth e 2) (nth e 3) (nth e 4) (o/encode-utf8 (nth e 5))))

(defn effect-response-octets
  "The FIRST `[:respond id …]` effect addressed to `id`, as octets.

  RETURNS A 500 WHEN THERE IS NONE, and that is a defect of driver A rather than
  a policy: `drive-two-once` performs exactly one `respond!` per connection per
  round on every path, because making the call conditional is the JOIN rule.
  There is no way to express `this round, say nothing` in the checked driver, so
  the honest thing is to put the reason on the wire."
  [fxs id]
  (loop [i 0]
    (if (>= i (count fxs))
      (h/response-octets 500 "No Response" {"content-type" "text/plain"}
                         (o/encode-utf8 "the application emitted no :respond effect"))
      (let [e (nth fxs i)]
        (if (= :respond (nth e 0))
          (if (= id (nth e 1)) (effect-octets e) (recur (inc i)))
          (recur (inc i)))))))

(defn effect-closes?
  "Does `fxs` hold `[:close id]`? Pure, and driver A cannot act on the answer."
  [fxs id]
  (loop [i 0]
    (if (>= i (count fxs))
      false
      (let [e (nth fxs i)]
        (if (= :close (nth e 0))
          (if (= id (nth e 1)) true (recur (inc i)))
          (recur (inc i)))))))

(defn step-events
  "Feed a vector of events to the application one at a time, threading its state
  and concatenating its effects. -> [state' effects].

  `step` arrives as a PARAMETER, so this namespace names no application. That is
  the boundary the experiment is about, and it costs nothing here: a function
  value holds no capability."
  [state evs step]
  (loop [i 0 s state fxs []]
    (if (>= i (count evs))
      [s fxs]
      (let [r (step s (nth evs i))]
        (recur (inc i) (first r) (into fxs (second r)))))))

;; ===========================================================================
;; DRIVER A — THE REGISTER FILE. Two connections, both positions written down
;; ===========================================================================

(defn drive-two-once
  "One round over TWO connections held live at the same time.

  Reads a request from connection 0, then a request from connection 1, then
  hands BOTH to the application before answering either, then responds to each
  with whatever the application addressed to it. Two ServerConn capabilities are
  live across the whole body and neither is a copy of the other.

  The annotation is the whole of what makes this expressible: `:arg 0` and
  `:arg 1` name the two connections coming in and `:at [0]` / `:at [1]` name
  them going out. That is a CONNECTION TABLE — of exactly two entries, with its
  indices fixed at the time the driver was written."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :reading :arg 0}
                               {:cap 'perturb.http/ServerConn :state :reading :arg 1}]
                    :produces [{:cap 'perturb.http/ServerConn :state :reading :at [0]}
                               {:cap 'perturb.http/ServerConn :state :reading :at [1]}]}}
  [ca cb state step]
  (let [ra   (h/read-request ca)
        ca1  (first ra)
        reqa (second ra)
        rb   (h/read-request cb)
        cb1  (first rb)
        reqb (second rb)
        s1   (step state (request-event 0 reqa))
        s2   (step (first s1) (request-event 1 reqb))
        fxs  (into (second s1) (second s2))
        ca2  (h/respond! ca1 (effect-response-octets fxs 0))
        cb2  (h/respond! cb1 (effect-response-octets fxs 1))]
    [ca2 cb2 (first s2)]))

(cap/annotate-op! (var drive-two-once) (:perturb.cap/op (meta (var drive-two-once))))

(defn drive-two
  "`rounds` rounds over two connections, then close both.

  THE LOOP CARRIES TWO CAPABILITIES AT ONCE. Binding 0 and binding 1 are both
  ServerConn@:reading at the top and at every back edge, which is the cycle
  `perturb.http/serve-connection` has with one connection, doubled. The exit arm
  yields both and the recurring arm is bottom, which is the rewrite
  `perturb.httpcorpus/keepalive-conditional-close-value` records as required."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :reading :arg 0}
                               {:cap 'perturb.http/ServerConn :state :reading :arg 1}]
                    :produces [{:cap 'perturb.http/ServerConn :state :closed :at [0]}
                               {:cap 'perturb.http/ServerConn :state :closed :at [1]}]}}
  [ca cb state0 rounds step]
  (let [p  (loop [x ca y cb s state0 i rounds]
             (let [r  (drive-two-once x y s step)
                   x1 (nth r 0)
                   y1 (nth r 1)
                   s1 (nth r 2)]
               (if (<= i 1)
                 [x1 y1 s1]
                 (recur x1 y1 s1 (dec i)))))
        x9 (nth p 0)
        y9 (nth p 1)
        x8 (h/close-conn! x9)
        y8 (h/close-conn! y9)]
    [x8 y8]))

(cap/annotate-op! (var drive-two) (:perturb.cap/op (meta (var drive-two))))

(defn serve-two-with-listener
  "Accept TWO connections from an already-listening Listener, drive them
  together, shut the listener down.

  THREE CAPABILITIES LIVE IN ONE SCOPE and two of them are the same capability
  in two different instances: `l1`/`l2` is the Listener, `c0` and `c1` are two
  distinct ServerConns, and the listener has to survive both of their lifetimes.
  `perturb.http/serve` has a Listener and ONE connection; this is the shape
  nothing in perturb has had.

  Split from `serve-two` so that a caller can connect its clients between
  `listen` and `accept`, which is what makes the real-socket run single-threaded
  (`perturb.httpdemo` records the same trick for one connection)."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l0 rounds state0 step]
  (let [a0 (h/accept l0)
        l1 (first a0)
        c0 (second a0)
        a1 (h/accept l1)
        l2 (first a1)
        c1 (second a1)
        s0 (first (step-events state0 (open-events 2) step))
        p  (drive-two c0 c1 s0 rounds step)
        d0 (first p)
        d1 (second p)]
    (h/shutdown! l2)))

(cap/annotate-op! (var serve-two-with-listener)
                  (:perturb.cap/op (meta (var serve-two-with-listener))))

(defn serve-two
  "Driver A, whole: listen, accept two, drive, shut down. Takes no capability and
  returns none, so the checker reads all of it."
  [host port rounds state0 step]
  (let [l  (h/listen host port)
        l2 (serve-two-with-listener l rounds state0 step)]
    :served))

;; ===========================================================================
;; DRIVER B — THE TABLE. A map from connection id to ServerConn
;; ===========================================================================
;;
;; Everything from here to `serve-table` is what an event-driven server actually
;; needs: N connections, addressed by the id the application saw, with the
;; capability looked up when an effect names it. Every function below is
;; REJECTED and the shape of each rejection is the answer to `what does the
;; connection table cost`. They are kept, unmodified and RUNNING, for the same
;; reason `perturb.stream` is kept: a rejection of a program that works is
;; evidence about the rules.

(defn accept-into-table
  "Accept `n` connections into a MAP from id to ServerConn.

  REJECTED. `assoc` is an ordinary function and a capability handed to one draws
  `no-signature`; the connection is then never consumed by anything the checker
  can see, so it also draws `dangling` at the end of the loop body. Two
  diagnostics, both naming the table.

  The Listener half of this function is fine and is annotated: it goes in at
  :listening and comes back out at :listening at result position 0. It is only
  the second capability — the one there are N of — that cannot be said."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :listening :at [0]}]}}
  [l0 n]
  (loop [l l0 i 0 t {}]
    (if (>= i n)
      [l t]
      (let [a  (h/accept l)
            l1 (first a)
            c  (second a)]
        (recur l1 (inc i) (assoc t i c))))))

(cap/annotate-op! (var accept-into-table) (:perturb.cap/op (meta (var accept-into-table))))

(defn read-round
  "Read ONE request from every connection still in the table, in id order.
  -> [table' events].

  REJECTED, once: `(get t i)` is opaque, so `read-request` draws
  `untracked-consume`. Note what that diagnostic does NOT say — it does not say
  the connection is in the wrong state, because the checker has no idea there is
  a connection there at all. Everything the typestate axis exists for is off."
  [t0 n]
  (loop [i 0 t t0 evs []]
    (if (>= i n)
      [t evs]
      (let [c (get t i)]
        (if (nil? c)
          (recur (inc i) t evs)
          (let [r   (h/read-request c)
                c1  (first r)
                req (second r)]
            (recur (inc i) (assoc t i c1) (conj evs (request-event i req)))))))))

(defn apply-effect
  "Perform one effect against the table. -> table'.

  THIS IS THE FUNCTION THE HYPOTHESIS NEEDS AND THE CHECKER CANNOT READ. It is
  the only place where application data selects a capability, and it is exactly
  what driver A cannot express. Rejected twice — `respond!` and `close-conn!`
  both draw `untracked-consume` — and the two rejections are the same rejection:
  `(get t id)` is a map lookup, so nothing arrives with a state."
  [t e]
  (let [tag (nth e 0)
        id  (nth e 1)
        c   (get t id)]
    (cond
      (nil? c) t
      (= :respond tag) (assoc t id (h/respond! c (effect-octets e)))
      (= :close tag)   (let [c1 (h/close-conn! c)] (assoc t id nil))
      :else t)))

(defn apply-effects
  "Perform every effect in order. -> table'.

  Not rejected, and that is worth noticing: it never touches a capability, it
  only passes a table around. The unchecked surface is not this function, it is
  the one it calls."
  [t0 fxs]
  (loop [i 0 t t0]
    (if (>= i (count fxs))
      t
      (recur (inc i) (apply-effect t (nth fxs i))))))

(defn close-table
  "Close every connection still in the table. Rejected once, same reason."
  [t0 n]
  (loop [i 0 t t0]
    (if (>= i n)
      t
      (let [c (get t i)]
        (if (nil? c)
          (recur (inc i) t)
          (let [c1 (h/close-conn! c)]
            (recur (inc i) (assoc t i nil))))))))

(defn serve-table-with-listener
  "Driver B's body: accept `nconn` connections, then `rounds` of
  read-every-connection / step-the-app / apply-every-effect, then close whatever
  is left and shut down.

  READ THIS FUNCTION'S VERDICT WITH SUSPICION. It is `[ok]`, and it is the least
  trustworthy `ok` in this namespace: the Listener really is threaded correctly,
  and every ServerConn in it became opaque inside `accept-into-table` before
  control ever reached here. A clean verdict on the function that composes the
  driver is exactly what a hidden boundary looks like from above."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l0 nconn rounds state0 step]
  (let [at (accept-into-table l0 nconn)
        l1 (first at)
        t0 (second at)
        s0 (first (step-events state0 (open-events nconn) step))
        r  (loop [t t0 s s0 i rounds]
             (if (<= i 0)
               [t s]
               (let [rr  (read-round t nconn)
                     t1  (first rr)
                     evs (second rr)
                     sr  (step-events s evs step)
                     s1  (first sr)
                     fxs (second sr)
                     t2  (apply-effects t1 fxs)]
                 (recur t2 s1 (dec i)))))
        t9 (first r)
        tz (close-table t9 nconn)]
    (h/shutdown! l1)))

(cap/annotate-op! (var serve-table-with-listener)
                  (:perturb.cap/op (meta (var serve-table-with-listener))))

(defn serve-table
  "Driver B, whole: listen, then `serve-table-with-listener`."
  [host port nconn rounds state0 step]
  (let [l  (h/listen host port)
        l2 (serve-table-with-listener l nconn rounds state0 step)]
    :served))

;; ===========================================================================
;; THE COST OF THE TABLE, AS THREE PROGRAMS THAT ARE NOT DRIVERS
;; ===========================================================================
;;
;; The three below exist only to isolate one rejection each, so that the report
;; can say WHICH rule refuses a connection table rather than that several do.

(defn accept-into-vector
  "REJECTED, and it is the interesting negative. `perturb.check`'s abstract
  domain DOES model a vector — a literal vector is the tuple `:at [i]` names —
  so it is reasonable to expect a vector table to work where a map does not.

  It does not, and the reason is precise: what the domain models is the VECTOR
  LITERAL NODE, not the vector. `conj` is an ordinary function, so this draws
  `no-signature` and then `dangling`, character for character the same pair as
  `accept-into-table`. Choosing the composite the checker understands buys
  nothing, because there is no way to build one incrementally."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :listening :at [0]}]}}
  [l0 n]
  (loop [l l0 i 0 t []]
    (if (>= i n)
      [l t]
      (let [a  (h/accept l)
            l1 (first a)
            c  (second a)]
        (recur l1 (inc i) (conj t c))))))

(cap/annotate-op! (var accept-into-vector) (:perturb.cap/op (meta (var accept-into-vector))))

(defn table-grows-in-a-loop
  "REJECTED, `loop-not-preserving`. The table is built out of LITERAL tuples, the
  one composite that carries a capability — and it still fails, one rule further
  on: the loop binding enters holding no capability and re-enters holding one,
  so the body cannot run twice.

  This is the rule that makes a connection table a fixed-arity register file and
  not a table. A driver may hold two connections, or seventeen, but the number
  has to be written in the source, because the only way to add one is to write a
  wider literal."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :listening :at [0]}]}}
  [l0 n]
  (loop [l l0 i 0 t []]
    (if (>= i n)
      [l t]
      (let [a  (h/accept l)
            l1 (first a)
            c  (second a)]
        (recur l1 (inc i) [c])))))

(cap/annotate-op! (var table-grows-in-a-loop)
                  (:perturb.cap/op (meta (var table-grows-in-a-loop))))

(defn honour-close-effect
  "REJECTED, `join`. The smallest program that says why driver A cannot obey
  `[:close id]`: the decision is a value the application computed, so one arm of
  the `if` consumes the connection and the other does not.

  `perturb.httpcorpus/keepalive-conditional-close-value` is the same rejection
  reached from the protocol side, where the deciding value came from the peer's
  `Connection:` header. This one reaches it from the APPLICATION side, which is
  the shape the whole event/effect architecture is made of: every effect is a
  decision made in data about a capability held elsewhere. PERTURB-DESIGN E18
  finding 2, arrived at a second way."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :reading :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :closed :at [0]}]}}
  [c fxs id]
  (let [c1 (if (effect-closes? fxs id) (h/close-conn! c) c)]
    [c1]))

(cap/annotate-op! (var honour-close-effect)
                  (:perturb.cap/op (meta (var honour-close-effect))))
