(ns perturb.http
  "HTTP/1.1, server side, over `perturb.wire/socket`. perturb's SECOND protocol,
  and it exists to find out where the capability rules break when they meet a
  protocol whose shape differs from the first one.

  WHAT IS DIFFERENT FROM `perturb.nrepl`, and why each difference was chosen:

    1. TWO CAPABILITIES AT ONCE. nREPL has one, a Connection. A server has a
       Listener and a ServerConn, and `accept` mentions both in one signature:
       it consumes a Listener and produces a Listener AND a ServerConn, at two
       different positions of one result. `multicap.py` probed this shape in
       Python; no perturb code had.

    2. A TYPESTATE CYCLE. nREPL's machine is a LINE — active -> closed.
       Keep-alive is a CYCLE: :reading -> :responding -> :reading. The
       loop rule preserves the (path, capability, state) SHAPE at a back edge,
       and a cycle is exactly the case where the shape at the top of the loop is
       reachable again from the bottom.

    3. A RESOURCE THAT MUST BE FINISHED, NOT CLOSED. A Content-Length response
       body owes the wire exactly N octets. `:finished` is a state; \"wrote
       exactly N\" is arithmetic. §1.2's typestate axis expresses the first and
       CANNOT express the second — see `body-finish!` and the obligations on
       `body-capability`, which are §1.3 refinement obligations with nothing to
       discharge them. `perturb.httpcorpus/short-body-still-type-checks` is the
       program that proves the gap: the checker accepts it, it RUNS, and it puts
       a lie on the wire.

  SANS-IO. `parse-request` is a pure step function with `perturb.bencode`'s
  trichotomy — `:ok` with a new cursor, `:need-more` with the EXACT ORIGINAL
  cursor, `:invalid` with reason/offset and the original cursor. E4 measured that
  contract on bencode; it is adopted here for the same reason and stated as data
  in `contract` below. `perturb.script/server-handler` delivers ONE OCTET PER
  RECV, which is what makes the cursor contract load-bearing rather than
  decorative: a request split at every single octet boundary must parse to the
  same request.

  WHAT THIS IS NOT. Not a working HTTP server. No chunked transfer-coding (it is
  refused explicitly rather than mis-parsed), no HTTP/1.0, no pipelined
  responses out of order, no absolute-form request targets, no trailers, no TLS,
  no timeouts, no concurrency. Every one of those is a decision recorded here
  rather than an omission: the target is the capability rules, and each feature
  omitted is a feature that would have added protocol code without adding a new
  capability shape.

  ABSTRACTION BOUNDARY, MEASURED. E17 had to add `:perturb.cap/representation` —
  a hand-written list of operations allowed to touch the Connection's concrete
  map — to stop the checker refusing `(:perturb.nrepl/buf c)`. This namespace
  declares THREE capabilities and the list is EMPTY for all three. That is not a
  fix; see `representation-note` for what it actually cost."
  (:require [perturb.wire :as w]
            [perturb.octet :as o]
            [perturb.effect :as fx]
            [perturb.cap :as cap]))

;; --- octets that mean something in HTTP -------------------------------------

(def ^:private SP 0x20)
(def ^:private HT 0x09)
(def ^:private CR 0x0D)
(def ^:private LF 0x0A)
(def ^:private COLON 0x3A)
(def ^:private A-UP 0x41)
(def ^:private Z-UP 0x5A)
(def ^:private ZERO 0x30)
(def ^:private NINE 0x39)

(def max-head-octets
  "RFC 9112 has no limit; every implementation invents one. jolt-http calls it
  `max-buffer-size`. Making it explicit turns an unbounded `:need-more` into an
  `:invalid`, which is the difference between a parser and a memory leak."
  8192)

(def max-header-fields 64)

(def max-body-octets
  "A Content-Length larger than this is refused before a single body octet is
  waited for. Nothing here streams; a body is delivered whole."
  1048576)

(defn- tchar?
  "RFC 9110 5.6.2 token character, over octets rather than characters."
  [b]
  (or (and (>= b 0x30) (<= b 0x39))
      (and (>= b 0x41) (<= b 0x5A))
      (and (>= b 0x61) (<= b 0x7A))
      (= b 0x21) (= b 0x23) (= b 0x24) (= b 0x25) (= b 0x26) (= b 0x27)
      (= b 0x2A) (= b 0x2B) (= b 0x2D) (= b 0x2E) (= b 0x5E) (= b 0x5F)
      (= b 0x60) (= b 0x7C) (= b 0x7E)))

(defn- token-octets? [ov]
  (let [n (o/ocount ov)]
    (loop [i 0]
      (cond (>= i n) (pos? n)
            (tchar? (o/oref ov i)) (recur (inc i))
            :else false))))

(defn- ows? [b] (or (= b SP) (= b HT)))

(defn- lower-octet [b] (if (and (>= b A-UP) (<= b Z-UP)) (+ b 32) b))

(defn- lower-name
  "ASCII-lowercase a field name at the OCTET level and only then make it text.
  Field names are tokens, so they are ASCII by construction and this is total —
  `clojure.string/lower-case` on a host string would be a locale decision
  perturb has not made (§1.6)."
  [ov]
  (o/->str (o/octets (loop [i 0 acc []]
                       (if (>= i (o/ocount ov))
                         acc
                         (recur (inc i) (conj acc (lower-octet (o/oref ov i)))))))))

;; --- the contract, as data --------------------------------------------------

(def contract
  "`parse-request`'s refinement, in the same shape `perturb.bencode/contract`
  uses, written where a checker could read it. Hand-written and UNCHECKED —
  nothing in perturb discharges it, and `perturb.selftest` only samples it."
  '{:perturb.http/step parse-request
    :perturb.http/args
    [{:name ov  :sort Octets}
     {:name pos :sort Int :refine (and (<= 0 pos) (<= pos (ocount ov)))}]
    :perturb.http/result
    (one-of
      {:tag :ok        :refine (and (< pos pos-out) (<= pos-out (ocount ov)))
                       :view   (identical-backing ov)}
      {:tag :need-more :refine (= pos-out pos)}
      {:tag :invalid   :refine (and (= pos-out pos)
                                    (<= pos off) (<= off (ocount ov)))})
    :perturb.http/notes
    "Same trichotomy as bencode, chosen because E4 measured it over 132,672
     assertions on a codec with the same shape. The property that matters for
     keep-alive and pipelining is the :ok cursor: a buffer holding TWO requests
     must yield the first at pos-out and the second from pos-out with no recv in
     between, which is what perturb.httpdemo exercises on a real socket."})

;; --- pure parsing: no capability appears anywhere below ---------------------
;;
;; Note what this buys. `perturb.nrepl`'s driver, `read-frame`, takes the
;; Connection and is therefore an axiom the checker never reads. Everything from
;; here to `response-octets` takes octets and integers, so `perturb.check`
;; CHECKS it — not for capability discipline (there is none to check) but it is
;; inside the checked set rather than outside it, which is the number E17 says
;; to watch.

(defn- index-of-octet [ov from to b]
  (loop [i from]
    (cond (>= i to) nil
          (= b (o/oref ov i)) i
          :else (recur (inc i)))))

(defn- index-of-crlf
  "Index of the CR of the first CRLF at or after `from`, or nil."
  [ov from n]
  (loop [i from]
    (cond (>= (inc i) n) nil
          (and (= CR (o/oref ov i)) (= LF (o/oref ov (inc i)))) i
          :else (recur (inc i)))))

(defn- trim-ows
  "[from to) with leading and trailing OWS removed, as an octet view."
  [ov from to]
  (let [s (loop [i from] (if (and (< i to) (ows? (o/oref ov i))) (recur (inc i)) i))
        e (loop [i to] (if (and (> i s) (ows? (o/oref ov (dec i)))) (recur (dec i)) i))]
    (o/osub ov s e)))

(defn- parse-request-line
  "[start end) -> [:ok {:method :target :version}] | [:invalid reason off]."
  [ov start end]
  (let [i1 (index-of-octet ov start end SP)]
    (if (nil? i1)
      [:invalid :bad-request-line start]
      (let [i2 (index-of-octet ov (inc i1) end SP)]
        (if (nil? i2)
          [:invalid :bad-request-line start]
          (let [m (o/osub ov start i1)
                t (o/osub ov (inc i1) i2)
                v (o/osub ov (inc i2) end)]
            (cond
              ;; RFC 9110 9.1: the method is a token. Accepting anything up to
              ;; the first space is how two servers come to disagree about what
              ;; was requested (jolt-http makes the same call, for the same
              ;; reason).
              (not (token-octets? m)) [:invalid :bad-method start]
              (zero? (o/ocount t))    [:invalid :empty-target start]
              ;; HTTP/1.0 is REFUSED, not downgraded: its keep-alive default is
              ;; the opposite of 1.1's, and silently guessing which one a peer
              ;; meant is the whole bug class this layer exists to make visible.
              (not= "HTTP/1.1" (o/->str v)) [:invalid :unsupported-version start]
              :else [:ok {:method (o/->str m) :target (o/->str t)
                          :version "HTTP/1.1"}])))))))

(defn- parse-headers
  "-> [:ok headers after] | [:need-more] | [:invalid reason off].
  Field names are lower-cased; a repeated field name is an error rather than a
  silent last-wins, because last-wins on `content-length` is a smuggling vector."
  [ov pos n]
  (loop [i pos acc {} fields 0]
    (let [cr (index-of-crlf ov i n)]
      (cond
        (nil? cr) [:need-more]
        (= cr i)  [:ok acc (+ cr 2)]
        (> fields max-header-fields) [:invalid :too-many-header-fields i]
        :else
        (let [ci (index-of-octet ov i cr COLON)]
          (cond
            (nil? ci)  [:invalid :header-without-colon i]
            (= ci i)   [:invalid :empty-field-name i]
            ;; RFC 9112 5.1: no whitespace between field name and colon.
            (ows? (o/oref ov (dec ci))) [:invalid :space-before-colon i]
            :else
            (let [nm (o/osub ov i ci)]
              (if (not (token-octets? nm))
                [:invalid :bad-field-name i]
                (let [k (lower-name nm)
                      v (trim-ows ov (inc ci) cr)]
                  (if (contains? acc k)
                    [:invalid :repeated-field-name i]
                    (recur (+ cr 2) (assoc acc k (o/->str v)) (inc fields))))))))))))

(defn- digits->int
  "-> [:ok n] | [:invalid]. No sign, no whitespace, no leading `+`."
  [s]
  (let [ov (o/encode-utf8 s)
        n  (o/ocount ov)]
    (if (zero? n)
      [:invalid]
      (loop [i 0 acc 0]
        (if (>= i n)
          [:ok acc]
          (let [b (o/oref ov i)]
            (if (and (>= b ZERO) (<= b NINE))
              (recur (inc i) (+ (* acc 10) (- b ZERO)))
              [:invalid])))))))

(defn- body-length
  "-> [:ok n] | [:invalid reason]. Chunked is REFUSED rather than mis-parsed."
  [headers]
  (cond
    (contains? headers "transfer-encoding") [:invalid :chunked-unsupported]
    (not (contains? headers "content-length")) [:ok 0]
    :else
    (let [r (digits->int (get headers "content-length"))]
      (cond
        (= :invalid (first r)) [:invalid :bad-content-length]
        (> (second r) max-body-octets) [:invalid :body-too-large]
        :else r))))

(defn parse-request
  "One HTTP/1.1 request from `ov` at `pos`. See `contract`.

  -> [:ok request pos'] | [:need-more pos] | [:invalid reason offset pos].

  `request` is {:method :target :version :headers :body}, with `:headers`
  lower-cased host strings and `:body` an OCTET VIEW. Text is a decision made
  per field, above the parser — the same split `perturb.bencode` makes."
  [ov pos]
  (let [n     (o/ocount ov)
        avail (- n pos)
        stuck (fn [] (if (> avail max-head-octets)
                       [:invalid :head-too-large pos pos]
                       [:need-more pos]))]
    (if (>= pos n)
      [:need-more pos]
      (let [cr (index-of-crlf ov pos n)]
        (if (nil? cr)
          (stuck)
          (let [rl (parse-request-line ov pos cr)]
            (if (= :invalid (first rl))
              [:invalid (second rl) (nth rl 2) pos]
              (let [hr (parse-headers ov (+ cr 2) n)]
                (cond
                  (= :need-more (first hr)) (stuck)
                  (= :invalid (first hr))   [:invalid (second hr) (nth hr 2) pos]
                  :else
                  (let [headers (second hr)
                        after   (nth hr 2)
                        bl      (body-length headers)]
                    (if (= :invalid (first bl))
                      [:invalid (second bl) after pos]
                      (let [len (second bl)
                            end (+ after len)]
                        (if (> end n)
                          [:need-more pos]                 ; EXACT original cursor
                          [:ok (assoc (second rl)
                                      :headers headers
                                      :body (o/osub ov after end))
                           end])))))))))))))

;; --- pure response construction ---------------------------------------------

(defn- append-str [acc s] (into acc (o/ovec (o/encode-utf8 s))))

(defn response-head
  "status, reason, headers map, DECLARED body length -> the head as octets,
  terminated by the blank line.

  Header order is SORTED, not insertion order. That is not cosmetic: the demo
  compares the octets the scripted handler saw against the octets the real
  socket saw, byte for byte, and an unordered map would make that comparison a
  coin flip (§2 row 3 — iteration order is deterministic within a build, which
  is weaker than what this needs).

  The length is a PARAMETER, not the length of a body this function can see.
  That is the whole shape of the obligation `body-capability` carries: the head
  commits to a number before anything has been written, and only a later,
  separate event can tell whether the commitment was kept."
  [status reason headers len]
  (let [hs (sort-by (fn [p] (first p))
                    (seq (assoc headers "content-length" (str len))))
        head (reduce (fn [a p]
                       (conj (conj (append-str (conj (conj (append-str a (first p)) COLON) SP)
                                               (second p))
                                   CR)
                             LF))
                     (conj (conj (append-str [] (str "HTTP/1.1 " status " " reason)) CR) LF)
                     hs)]
    (o/octets (conj (conj head CR) LF))))

(defn response-octets
  "A complete response: `response-head` with the body's real length, plus the
  body. The one-shot path, where the commitment cannot be broken because the
  body is already in hand."
  [status reason headers body]
  (o/octets (into (o/ovec (response-head status reason headers (o/ocount body)))
                  (o/ovec body))))

(defn keep-alive?
  "HTTP/1.1 defaults to persistent. `Connection: close` opts out. This is the
  one protocol decision the keep-alive DRIVER reads, and it is deliberately a
  function of an ordinary value: the driver must not need to look inside the
  capability to decide whether the cycle continues."
  [request]
  (not= "close" (get (:headers request) "connection")))

;; --- the capabilities -------------------------------------------------------

(def listener-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.http/Listener
       :perturb.cap/doc        "A bound, listening server socket."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states  [:listening :closed]
        :initial :listening
        :terminal :closed
        :transitions [{:op 'perturb.http/listen    :from nil        :to :listening}
                      {:op 'perturb.http/accept    :from :listening :to :listening}
                      {:op 'perturb.http/shutdown! :from :listening :to :closed}]}
       ;; EMPTY. E17 needed five entries here for the nREPL Connection. See
       ;; `representation-note` — the list being empty is a fact about where the
       ;; code was CUT, not about anything being checked that was not before.
       :perturb.cap/representation []
       :perturb.cap/obligations
       '[{:name listener-closed-exactly-once
          :formula (= 1 (count (filter (fn [e] (= :closed (:to e))) ledger)))}]
       :perturb.cap/locality :dropped-by-design})))

(def conn-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.http/ServerConn
       :perturb.cap/doc        "One accepted connection, server side."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       ;; THE CYCLE. `respond!` goes :responding -> :reading, which is the only
       ;; edge in perturb that returns a capability to a state it has already
       ;; been in. `perturb.nrepl`'s machine is a line and never does.
       :perturb.cap/typestate
       {:states  [:reading :responding :writing :closed]
        :initial :reading
        :terminal :closed
        ;; SIX EDGES, THREE OF WHICH ALSO BELONG TO ANOTHER MACHINE. `accept` is
        ;; also Listener's self-loop, `respond-begin` also mints a ResponseBody,
        ;; and `body-finish!` is the `:writing -> :reading` edge here and
        ;; ResponseBody's `:open -> :finished` at the same time. Until the
        ;; checker's primitive table was keyed by [capability operation] the last
        ;; two could not be written down at all: a second declaration naming an
        ;; operation overwrote the first (PERTURB-DESIGN E18 finding 1(a)).
        ;; `body-finish!` was simply MISSING from this list for that reason, so
        ;; the edge `no-response-owed-at-close` talks about was undeclared.
        :transitions [{:op 'perturb.http/accept        :from nil          :to :reading}
                      {:op 'perturb.http/read-request  :from :reading     :to :responding}
                      {:op 'perturb.http/respond!      :from :responding  :to :reading}
                      {:op 'perturb.http/respond-begin :from :responding  :to :writing}
                      {:op 'perturb.http/body-finish!  :from :writing     :to :reading}
                      {:op 'perturb.http/close-conn!   :from [:reading :responding :writing]
                                                       :to   :closed}]}
       :perturb.cap/representation []
       :perturb.cap/obligations
       '[{:name one-response-per-request
          :formula (forall [e ledger]
                     (implies (= :respond (:op e)) (= :responding (:from e))))}
         {:name no-response-owed-at-close
          :formula (forall [e ledger]
                     (implies (= :closed (:to e)) (not= :writing (:from e))))}]
       :perturb.cap/locality :dropped-by-design})))

(def body-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.http/ResponseBody
       :perturb.cap/doc
       "A Content-Length response body writer. Its terminal condition is an
        OBLIGATION, not merely a state: it owes the wire exactly N octets."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states  [:open :finished]
        :initial :open
        :terminal :finished
        ;; `respond-begin` is the creating edge and it is a transition of
        ;; ServerConn as well. It could not be named here until the primitive
        ;; table was keyed by [capability operation] — the docstring on
        ;; `respond-begin` used to say exactly that (E18 finding 1(a)).
        :transitions [{:op 'perturb.http/respond-begin :from nil   :to :open}
                      {:op 'perturb.http/body-write    :from :open :to :open}
                      {:op 'perturb.http/body-finish!  :from :open :to :finished}]}
       :perturb.cap/representation []
       ;; THE POINT OF THIS CAPABILITY. Neither of these is a typestate
       ;; property. `:finished` says the writer stopped; it does not say it wrote
       ;; the right number of octets, and no assignment of states can, because
       ;; the number is not known until run time. §1.2's four axes cannot state
       ;; it; §1.3 reserves exactly this class for refinements, and nothing in
       ;; perturb discharges a refinement. So these are written and unproven,
       ;; and `perturb.httpcorpus/short-body-still-type-checks` is the program
       ;; that shows the difference is not academic.
       :perturb.cap/obligations
       '[{:name wrote-exactly-content-length
          :formula (= 0 (:remaining (final-state body)))
          :class   :refinement
          :note    "QF-LIA over a run-time integer. NOT expressible on the
                    typestate axis; see PERTURB-DESIGN E18 finding 3."}
         {:name body-finished-before-conn-reused
          :formula (forall [e ledger]
                     (implies (and (= 'perturb.http/ServerConn (:capability e))
                                   (= :reading (:to e))
                                   (= :writing (:from e)))
                              (exists [f ledger]
                                (and (= 'perturb.http/ResponseBody (:capability f))
                                     (= :finished (:to f))
                                     (before f e)))))
          :class   :temporal
          :note    "A cross-capability ordering property. The typestate axis is
                    per capability; nothing in §1.2 relates two machines."}]
       :perturb.cap/locality :dropped-by-design})))

(def representation-note
  "Why all three `:perturb.cap/representation` lists are empty, stated so the
  zero is not read as progress.

  E17 added the list because `perturb.nrepl`'s `conn`, `compact`, `read-frame`,
  `state` and `conn-id` reach into the Connection's concrete map, and
  `(:perturb.nrepl/buf c)` cannot be given a capability signature. Five
  operations, i.e. five bodies that stopped being checked.

  This namespace has zero — and it did not earn it. The concrete map is touched
  in exactly the same number of PLACES; those places were written INSIDE the
  declared transitions, whose bodies are already axioms, instead of in helpers
  called from them. The unchecked surface did not shrink, it was repackaged: it
  is measured in LINES, and counting operations counts the wrong thing.

  Two things this did cost, both real:

    - `read-request` is a 20-line function doing buffer compaction, a recv loop
      and the sans-io driver, all inside one axiom. `perturb.nrepl` at least had
      `compact` and `read-frame` as separate named things a future module system
      could have given a signature. Inlining made the boundary cheaper to
      DECLARE and harder to LATER MOVE.
    - there are no `:borrows` observers here at all. `perturb.nrepl/state` and
      `/conn-id` exist because a caller wants to look at a connection without
      taking it; this namespace cannot offer that without re-opening the list.
      `perturb.httpdemo` therefore reports from the LEDGER instead of from the
      capability, which is a different and weaker thing.

  So the honest reading of \"zero representation operations\" is: the abstraction
  boundary is still hand-drawn, it just moved from a list of names to a decision
  about where to stop writing helper functions. That is the same missing feature
  E17 named — a MODULE — seen from the other side.")

;; --- connection and listener values -----------------------------------------
;; Constructed only inside the transitions below, which are axioms already.

(def ^:private id-counter (atom 0))
(defn- fresh-id [p] (str p (swap! id-counter inc)))

;; --- Listener transitions ---------------------------------------------------

(defn listen
  "Bind and listen. Produces a Listener@:listening, BARE — no `:at`.

  Annotation: consumes nothing. The declared transition is `:from nil`, not
  `:from :created`: a machine has no pre-creation state, because nothing is ever
  in one. `perturb.nrepl/open` used to say `:from :created` while its annotation
  consumed nothing, which is the disagreement E18 finding 1(b) records; that
  state is now deleted and both creating operations read the same way."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.http/Listener :state :listening}]}}
  [host port]
  (let [id  (fresh-id "perturb-listener-")
        tok (w/listen host port :perturb.http/listen)]
    (cap/transition! 'perturb.http/Listener id nil :listening :perturb.http/listen)
    {:perturb.cap/capability 'perturb.http/Listener
     :perturb.cap/id         id
     :perturb.cap/state      :listening
     :perturb.http/token     tok
     :perturb.http/accepted  0}))

(defn accept
  "Accept one connection.

  TWO CAPABILITIES IN ONE SIGNATURE. Consumes a Listener at argument 0 and
  produces a Listener at result position 0 AND a ServerConn at result position 1.
  The listener survives — it must, or a server serves one client — and the
  connection is new. Nothing in perturb had this shape before; `multicap.py`
  probed it in Python and `perturb.check` had never seen it."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener   :state :listening :at [0]}
                               {:cap 'perturb.http/ServerConn :state :reading   :at [1]}]}}
  [l]
  (let [tok  (w/accept (:perturb.http/token l) :perturb.http/accept)
        cid  (fresh-id "perturb-sconn-")]
    (cap/transition! 'perturb.http/Listener (:perturb.cap/id l) :listening :listening
                     :perturb.http/accept)
    (cap/transition! 'perturb.http/ServerConn cid nil :reading :perturb.http/accept)
    [(assoc l :perturb.http/accepted (inc (:perturb.http/accepted l)))
     {:perturb.cap/capability 'perturb.http/ServerConn
      :perturb.cap/id         cid
      :perturb.cap/state      :reading
      :perturb.http/token     tok
      :perturb.http/buf       o/empty-octets
      :perturb.http/pos       0
      :perturb.http/served    0}]))

(defn shutdown!
  "Close the listener. Consumes Listener@:listening, produces Listener@:closed."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l]
  (w/close! (:perturb.http/token l) :perturb.http/shutdown)
  (cap/transition! 'perturb.http/Listener (:perturb.cap/id l) :listening :closed
                   :perturb.http/shutdown)
  (assoc l :perturb.cap/state :closed :perturb.http/token nil))

;; --- ServerConn transitions -------------------------------------------------

(defn read-request
  "Read one request. Consumes ServerConn@:reading, produces
  [ServerConn@:responding request].

  THE SANS-IO DRIVER, and an AXIOM. Nothing but a `:need-more` from a pure step
  function causes a `recv`, and the cursor handed back to `parse-request` is the
  exact one it returned, so a request split at every octet boundary parses the
  same. What makes this different from `perturb.nrepl/read-frame` is the `:ok`
  arm: `pos` is kept, NOT reset, so a buffer that already holds the next
  pipelined request serves it with no recv at all.

  Buffer compaction is inlined here rather than in a `compact` helper. That is
  the whole reason this capability needs no `:perturb.cap/representation` list,
  and `representation-note` says what it cost."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :reading :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :responding :at [0]}]}}
  [c]
  (loop [c c]
    (let [r   (parse-request (:perturb.http/buf c) (:perturb.http/pos c))
          tag (first r)]
      (cond
        (= :ok tag)
        (do (cap/transition! 'perturb.http/ServerConn (:perturb.cap/id c)
                             :reading :responding :perturb.http/read-request)
            [(assoc c :perturb.http/pos (nth r 2) :perturb.cap/state :responding)
             (second r)])

        (= :invalid tag)
        (fx/abort! :http-parse {:perturb.http/reason (second r)
                                :perturb.http/offset (nth r 2)
                                :perturb.http/conn   (:perturb.cap/id c)})

        :else
        (let [chunk (w/recv (:perturb.http/token c) 65536 :perturb.http/recv)]
          (if (zero? (o/ocount chunk))
            (fx/abort! :eof {:perturb.http/conn (:perturb.cap/id c)})
            (recur (assoc c
                          :perturb.http/buf
                          (o/oconcat (o/odrop (:perturb.http/buf c) (:perturb.http/pos c)) chunk)
                          :perturb.http/pos 0))))))))

(defn respond!
  "Write a complete response. Consumes ServerConn@:responding, produces
  ServerConn@:reading BARE.

  THIS IS THE CYCLE EDGE. It is the only operation in perturb that returns a
  capability to a state it has already occupied."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :responding :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :reading}]}}
  [c ov]
  (w/send! (:perturb.http/token c) ov :perturb.http/send)
  (cap/transition! 'perturb.http/ServerConn (:perturb.cap/id c) :responding :reading
                   :perturb.http/respond)
  (assoc c :perturb.cap/state :reading
           :perturb.http/served (inc (:perturb.http/served c))))

(defn close-conn!
  "Close the connection. Legal from :reading, :responding and :writing — the last
  of those is a deliberate hole, and the `no-response-owed-at-close` obligation
  on `conn-capability` is what would close it if anything discharged
  obligations."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn
                                :state [:reading :responding :writing] :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :closed}]}}
  [c]
  (w/close! (:perturb.http/token c) :perturb.http/close)
  (cap/transition! 'perturb.http/ServerConn (:perturb.cap/id c)
                   (:perturb.cap/state c) :closed :perturb.http/close)
  (assoc c :perturb.cap/state :closed :perturb.http/token nil))

;; --- ResponseBody: the obligation capability --------------------------------

(defn respond-begin
  "Start a streamed Content-Length response. Consumes ServerConn@:responding and
  produces [ServerConn@:writing ResponseBody@:open].

  A SECOND two-capability signature, and a different shape from `accept`'s: this
  one MINTS a capability of a second machine. ResponseBody's `:transitions` now
  names it as the creating edge, `nil -> :open`. It could not, while the
  checker's primitive table was keyed by OPERATION and an operation could be a
  transition of at most one capability — PERTURB-DESIGN E18 finding 1(a), now
  keyed by [capability operation]."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :responding :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn   :state :writing :at [0]}
                               {:cap 'perturb.http/ResponseBody :state :open    :at [1]}]}}
  [c status reason headers len]
  (let [head (response-head status reason headers len)
        bid  (fresh-id "perturb-body-")]
    (w/send! (:perturb.http/token c) head :perturb.http/send)
    (cap/transition! 'perturb.http/ServerConn (:perturb.cap/id c) :responding :writing
                     :perturb.http/respond-begin)
    (cap/transition! 'perturb.http/ResponseBody bid nil :open :perturb.http/respond-begin)
    [(assoc c :perturb.cap/state :writing)
     {:perturb.cap/capability 'perturb.http/ResponseBody
      :perturb.cap/id         bid
      :perturb.cap/state      :open
      :perturb.http/token     (:perturb.http/token c)
      :perturb.http/declared  len
      :perturb.http/written   0}]))

(defn body-write
  "Write octets of the body. Consumes ResponseBody@:open, produces
  ResponseBody@:open BARE — a self-loop, the degenerate cycle."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ResponseBody :state :open :arg 0}]
                    :produces [{:cap 'perturb.http/ResponseBody :state :open}]}}
  [b ov]
  (w/send! (:perturb.http/token b) ov :perturb.http/send)
  (cap/transition! 'perturb.http/ResponseBody (:perturb.cap/id b) :open :open
                   :perturb.http/body-write)
  (assoc b :perturb.http/written (+ (:perturb.http/written b) (o/ocount ov))))

(defn body-finish!
  "Finish the body and give the connection back. Consumes ServerConn@:writing at
  argument 0 AND ResponseBody@:open at argument 1; produces ServerConn@:reading.

  THE OBLIGATION IS NOT CHECKED, AND CANNOT BE. `:finished` is reached whether or
  not the declared octet count was written. This function RECORDS the discrepancy
  in the ledger and does not abort, on purpose: an abort would turn a static gap
  into a dynamic check and hide it. `perturb.httpcorpus/short-body-still-type-checks`
  is accepted by `perturb.check`, RUNS to completion under the gate, and emits a
  response whose Content-Length is a lie. That program is the evidence for
  PERTURB-DESIGN E18 finding 3.

  IT ADVANCES TWO MACHINES AT ONCE and both edges are now declared: ResponseBody
  `:open -> :finished` and ServerConn `:writing -> :reading`. The ResponseBody is
  consumed and NOT handed back, which the consistency rule permits because
  `:finished` is terminal — `produced and dropped` and `not produced` are the
  same thing to every rule the checker has. E18 finding 1(a) recorded the
  previous state, where the two edges collapsed onto one operation key and the
  checker reported the impossible `:open -> :reading`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ResponseBody :state :open    :arg 1}
                               {:cap 'perturb.http/ServerConn   :state :writing :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :reading}]}}
  [c b]
  (let [want (:perturb.http/declared b)
        got  (:perturb.http/written b)]
    (when (not= want got)
      (cap/note! {:perturb.cap/capability 'perturb.http/ResponseBody
                  :perturb.cap/id         (:perturb.cap/id b)
                  :perturb.http/obligation :wrote-exactly-content-length
                  :perturb.http/declared   want
                  :perturb.http/written    got
                  :perturb.http/verdict    :VIOLATED}))
    (cap/transition! 'perturb.http/ResponseBody (:perturb.cap/id b) :open :finished
                     :perturb.http/body-finish)
    (cap/transition! 'perturb.http/ServerConn (:perturb.cap/id c) :writing :reading
                     :perturb.http/body-finish)
    (assoc c :perturb.cap/state :reading
             :perturb.http/served (inc (:perturb.http/served c)))))

;; register the annotations for the checker
(cap/annotate-op! (var listen)        (:perturb.cap/op (meta (var listen))))
(cap/annotate-op! (var accept)        (:perturb.cap/op (meta (var accept))))
(cap/annotate-op! (var shutdown!)     (:perturb.cap/op (meta (var shutdown!))))
(cap/annotate-op! (var read-request)  (:perturb.cap/op (meta (var read-request))))
(cap/annotate-op! (var respond!)      (:perturb.cap/op (meta (var respond!))))
(cap/annotate-op! (var close-conn!)   (:perturb.cap/op (meta (var close-conn!))))
(cap/annotate-op! (var respond-begin) (:perturb.cap/op (meta (var respond-begin))))
(cap/annotate-op! (var body-write)    (:perturb.cap/op (meta (var body-write))))
(cap/annotate-op! (var body-finish!)  (:perturb.cap/op (meta (var body-finish!))))

;; --- an application, as an ordinary value -----------------------------------

(defn echo-response
  "request -> [status reason headers body-octets]. Pure. Takes no capability, so
  `perturb.check` reads this body like any other."
  [request]
  (let [body (o/encode-utf8 (str (:method request) " " (:target request)
                                 " " (o/ocount (:body request))))]
    [200 "OK" {"content-type" "text/plain"} body]))

(defn render
  "request -> response octets, via `echo-response`."
  [request]
  (let [r (echo-response request)]
    (response-octets (nth r 0) (nth r 1) (nth r 2) (nth r 3))))

;; --- the drivers: CHECKED, not axioms ---------------------------------------

(defn serve-connection
  "The keep-alive loop. One connection serves request -> response -> request ->
  … until the peer asks to close or `limit` responses have been sent.

  THE CYCLE, RUNNING. The loop binding enters holding ServerConn@:reading;
  `read-request` takes it to :responding and `respond!` brings it back, so the
  back edge re-enters at exactly the shape it entered with. The `if` has one arm
  that closes and one that recurs — the closing arm is where a keep-alive loop
  actually ends, and the checker accepts it because the recurring arm is bottom
  at the join."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/ServerConn :state :reading :arg 0}]
                    :produces [{:cap 'perturb.http/ServerConn :state :closed}]}}
  [c0 limit]
  (loop [c c0 i limit]
    (let [r   (read-request c)
          c1  (first r)
          req (second r)
          ov  (render req)]
      (if (or (<= i 1) (not (keep-alive? req)))
        (close-conn! (respond! c1 ov))
        (recur (respond! c1 ov) (dec i))))))

(defn serve
  "The accept loop. Serves `conns` connections, `reqs` requests each, then shuts
  the listener down.

  TWO CAPABILITIES LIVE IN ONE SCOPE. `l1` is a Listener and `c` is a ServerConn
  and both are bound by the same `let`; the listener has to survive the
  connection's whole lifetime and then be recurred with. This is the shape item 1
  of the brief asks about, and it is the first perturb program to have it."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l0 conns reqs]
  (loop [l l0 i conns]
    (if (<= i 0)
      (shutdown! l)
      (let [a  (accept l)
            l1 (first a)
            c  (second a)
            c1 (serve-connection c reqs)]
        (recur l1 (dec i))))))

(cap/annotate-op! (var serve-connection) (:perturb.cap/op (meta (var serve-connection))))
(cap/annotate-op! (var serve)            (:perturb.cap/op (meta (var serve))))

(defn serve-once
  "listen, accept one connection, serve `reqs` requests on it, shut down.
  The whole server as one unannotated function — it takes no capability and
  returns none, so the checker reads all of it."
  [host port reqs]
  (let [l  (listen host port)
        l1 (serve l 1 reqs)]
    :served))
