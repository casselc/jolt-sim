(ns perturb.layer
  "B6, EXECUTED: the layering invariant as a checker over an EFFECT TRACE.

  WHAT THIS IS. PERTURB-DESIGN E29 ran a TLS-shaped record layer whose handler
  satisfies `perturb.wire/socket` by PERFORMING `perturb.wire/socket` outward,
  and found three holes by hand. E33's two independent surveys converged on an
  invariant that makes those holes checkable, and E33 says to take the second
  framing because `it is checkable against code we already have`:

    > for each declared request a layer either takes a declared local transition
    > or emits EXACTLY ONE CORRELATED `forward` to its immediate lower layer;
    > the projection of the composed trace onto each capability must be a legal
    > protocol trace; and A LOWER REFUSAL MAY NOT BECOME UPPER SUCCESS EXCEPT
    > THROUGH AN EXPLICIT ERROR-MAPPING EDGE.

  plus, from the first framing, the fifth condition: THE ADAPTER MUST NOT
  INTERCEPT ITS OWN FORWARDED OPERATION — E29 finding 1, where `via` supplies
  the handler-pop by hand and nothing checks that it did.

  WHAT THIS IS NOT, AND THE LABEL IS E33's OWN. `The B6 invariant is a testable
  design hypothesis, not a theorem.` Nothing here proves anything about a layer.
  It reads ONE RECORDED RUN and reports what that run did. A path not taken is
  not checked; a layer nobody ran is not checked; a clause nothing exercised is
  reported as such rather than as a pass. It is also EMPHATICALLY NOT A STATIC
  CHECKER: `perturb.check` reads Jolt IR and this reads
  `perturb.effect/*events*` and `perturb.cap/ledger`, and the two share no code.
  E29 finding 3 is the reason there is no static option — installing a handler
  is `binding`, `binding` is try/finally, and the composition point of a layered
  program is outside `perturb.check` by construction.

  =========================================================================
  THE LINE B6 HAS TO DRAW, WHICH IS THE SURVEY'S OWN SHARPEST OBJECTION
  =========================================================================

    > `forwarding may be too narrow — a useful layer can interpret locally
    >  rather than forward, so B6 must state exactly which operation classes
    >  require correlation.`

  It is a real objection and the record layer is a live example of it: a 121-
  octet `send` becomes EIGHT forwards, and eight of fourteen upper `recv`s are
  answered out of the reassembly buffer with NO forward at all. A literal
  reading of `exactly one correlated forward` would reject the known-good
  composition on both counts. So the rule is stated here, in code, as three
  operation classes, and the principle that assigns them is one sentence:

    AN OPERATION WHOSE RESULT MINTS OR RETIRES A HANDLE IN THE LOWER LAYER'S
    NAMESPACE MUST CORRELATE ONE-TO-ONE, BECAUSE THE LAYER CANNOT MANUFACTURE
    THAT HANDLE. AN OPERATION THAT ONLY MOVES OCTETS MAY BE REFRAMED, OR SERVED
    OUT OF A BUFFER THE LAYER FILLED BY FORWARDING EARLIER.

  `:mandatory-forward` — `connect`, `listen`, `accept`, `close`.
      EXACTLY ONE correlated forward of the corresponding lower operation. A
      declared local transition is NOT an alternative here, and that asymmetry
      is the whole content of the class: the value handed upward is (or retires)
      a token only the rung below can produce, so a layer that answered locally
      either fabricated a handle or leaked one it was holding. This is also the
      class that makes E29's control 3 a violation rather than a curiosity — a
      layer that CALLS the rung below emits no forward, and `it took a local
      transition instead` is not a defence for `accept`.
      Ancillary forwards of OTHER operations are declared per operation and
      permitted: the record layer's `accept` runs a handshake (`recv`, `send`)
      and its `close` writes a close-notify alert (`send`) first. Declaring them
      is not a loophole — it is the shutdown obligation `close-record!`'s
      docstring says nothing can express, written down where a run can be
      checked against it.

  `:fan-out` — `send`.
      AT LEAST ONE correlated forward, count otherwise unconstrained. Framing
      legitimately multiplies, and `perturb.wire/socket`'s `:send` result
      predicate is `count?` with no vocabulary for whose octets, so the arity is
      not recoverable and pretending otherwise would be arithmetic on nothing.
      What is still checkable is that the octets LEFT: a layer that accepted a
      write and forwarded nothing has dropped it.

  `:demand-driven` — `recv`.
      ZERO OR MORE correlated forwards. A local answer is legal exactly when
      (a) a declared local transition covers it and (b) the layer has CREDIT:
      cumulative octets obtained from forwarded reads must never be less than
      cumulative octets delivered upward. That is what stops `answered locally`
      from meaning `invented'. It is deliberately weak — it is a running total,
      not a provenance relation, so a layer that returned the RIGHT NUMBER of
      WRONG octets passes it. Recorded as a limit, not sold as a guarantee.

  THE HONEST RESIDUAL. This line is drawn for one effect, `perturb.wire/socket`,
  by hand, by reading what its six operations mean. Nothing derives it, nothing
  checks that a layer's declaration of its own classes is truthful, and an
  effect with a different vocabulary needs the judgement made again. That is the
  survey's objection answered for one case, not dissolved.

  =========================================================================
  THE FOUR CLAUSES, AS THEY ARE CHECKED
  =========================================================================

  B6.1 CORRELATED FORWARDING. Per class, above. Plus the base disjunction: a
       request that neither forwarded nor took a declared local transition did
       nothing the composition can account for.
  B6.2 PROTOCOL-TRACE PROJECTION. `perturb.cap/ledger` projected per capability
       id and replayed against the declared typestate machine: every step's
       `:from` must be the state the previous step left, every step must name a
       declared edge, and nothing may move after a terminal state.
  B6.3 ERROR MAPPING. A request that replied `[:ok v]` while a refusal occurred
       inside its extent absorbed that refusal. It is a violation unless the
       layer DECLARED an error-mapping edge for that operation and that abort
       kind. This is E29's control 2 stated as an invariant.
  B6.4 OUTWARD-OPERATION ADMISSIBILITY. Four facts about
       `perturb.effect/outward!` instances and handler identity: no forward may
       be answered by a handler already on the stack (self-interception); an
       instance must be consumed exactly once (not zero times, not twice); and
       it must be consumed under the request that minted it.
  B6.5 FINALISATION (PRACTICAL-ADOPTION-AND-SIM-LEARNINGS §A3). Every rung that
       forwarded anything must have been finalised exactly once. A rung that
       forwarded nothing is exempt and the summary says how many were.
  B6.6 ROUTE INTEGRITY AND REPLAY COHERENCE (§A3). Two things at once. The route
       check is a NONCLAIM MADE EXECUTABLE: §A3 says `the native proceed and a
       layer forward are different mechanisms and must remain different trace
       routes`, so `:proceed` must never appear, a `:forward` must name a rung
       and a `:perform` must not. The replay check folds the CANONICAL
       PROJECTION — one record per attempted operation, carrying route and
       outcome including abort and malformed reply — and requires it to be
       internally coherent: dense strictly-increasing sequence numbers, parents
       that precede their children, depth that follows the parent, marks that do
       not go backwards. E26's `replay-history!` posture, one rung up: a forged
       or reordered trace fails rather than being believed.

  =========================================================================
  THE MECHANISM FOR ADAPTER SELF-INTERCEPTION, AND WHAT IT REPLACED
  =========================================================================

  E33's first framing says the clause `needs explicit effect instances or labels
  rather than a convention every layer author holds`. Three mechanisms were
  available:

    EXPLICIT EFFECT INSTANCES — give `perturb.wire/socket` a per-rung identity
    so the upper and lower rungs are different effects. This changes the effect
    DECLARATION LANGUAGE, therefore `perturb.wire`, `perturb.http`,
    `perturb.script`, `perturb.posix` and every handler map in the artifact —
    and would destroy the property E29 was built to test, that `perturb.http`
    runs UNMODIFIED over a rung it does not name.

    LABELS ON LAYERS — cheap, and too coarse. A label says which layer answered.
    Clause 4's multi-shot arm is about ONE PARTICULAR FORWARD being consumed
    twice, and a layer label cannot distinguish two forwards by the same layer.

    AN EXPLICIT HANDLER INSTANCE WITH A NAMED OUTER INSTANCE, plus a per-forward
    outward-operation instance — CHOSEN, and it is two mechanisms because the
    clause is two questions. `perturb.effect/instance!` gives a rung an identity
    and names the rung it may forward to; `perturb.effect/forward!` targets that
    name and consults `*handlers*` neither to read nor to write. So `who
    answered my forward` is not a comparison after the fact — a forward cannot
    reach anything but the named outer, and naming yourself is refused on the
    spot with a LATCHED `:self-forward`. `perturb.effect/outward!` then answers
    `which forward`, which is what Tang's admissibility conditions need and
    which no layer label can express.

    THIS IS A REVISION AND THE REVISION IS RECORDED. The first implementation of
    this work chose handler-object identity plus outward instances and left
    `via` rebinding the effect name, making self-interception a REPORTED
    VIOLATION of clause 4. PRACTICAL-ADOPTION-AND-SIM-LEARNINGS §A3 independently
    prescribed the stronger form — `make forwarding a distinct operation which
    targets that outer instance rather than rebinding the same name` — and it is
    strictly better for one reason: E29 finding 1's residual was that `a layer
    that forgot would loop rather than be refused`, and detection-after-the-fact
    does not close that; there is nothing to forget when the outer is named. The
    trace-level clause 4 checks are kept as a BACKSTOP, and they still fire —
    `no-deep-handler-probe` performs rather than forwards, so the refusal cannot
    see it and the checker can.

    WHAT IT STILL COSTS. The trace-level half remains a RUNTIME OBSERVATION: it
    reports an interception that OCCURRED, not one that could. And `forward!`'s
    refusals hold only for code that forwards; `perform` still does not pop the
    executing handler, so a layer that reaches the boundary by performing rather
    than forwarding gets E29's old behaviour and only the checker sees it.

  =========================================================================
  WHAT THIS DOES NOT TOUCH
  =========================================================================

  §A3's caution: do not replace the fail-closed FFI seam with a
  convention-based dynamic-var gate. `perturb.effect/native!` and
  `perturb.posix/gate!` are UNCHANGED and this namespace does not read, wrap,
  weaken or stand in for either. B6 OBSERVES the boundary; the boundary is still
  where it was, and `dev/verify-noio.sh` still measures it."
  (:require [perturb.effect :as fx]
            [perturb.cap :as cap]))

;; --- the operation classes, as data -----------------------------------------

(def op-classes
  "The three classes, in the order the docstring argues them."
  [:mandatory-forward :fan-out :demand-driven])

(def wire-socket-classes
  "THE LINE, DRAWN, for `perturb.wire/socket` and for nothing else.

  A layer declaration may override any entry; this is the default a
  `perturb.wire/socket` layer gets if it says nothing, and it is written here
  rather than in a layer so that two layers cannot quietly disagree about what
  `accept` means."
  {:listen  {:class :mandatory-forward :lower :listen  :ancillary #{}}
   :connect {:class :mandatory-forward :lower :connect :ancillary #{:send}}
   :accept  {:class :mandatory-forward :lower :accept  :ancillary #{:recv :send}}
   :close   {:class :mandatory-forward :lower :close   :ancillary #{:send}}
   :send    {:class :fan-out           :lower :send    :ancillary #{}}
   :recv    {:class :demand-driven     :lower :recv    :ancillary #{}
             :credit true}})

(defn layer
  "Declare one LAYER of a stack. Validated for shape only — nothing here checks
  that a layer's declaration of its own operation classes is honest, and
  `report-limits` below says so.

    :perturb.layer/name       symbol, for the report
    :perturb.layer/handler    the handler OBJECT this layer installed; identity
                              is how a request is attributed to a layer
    :perturb.layer/capability the capability whose declared transitions count as
                              this layer's LOCAL transitions, or nil
    :perturb.layer/ops        op -> {:class :lower :ancillary :credit}
    :perturb.layer/error-map  vector of {:op o :abort k :note s} — the explicit
                              error-mapping edges of B6.3, and the ONLY thing
                              that makes a lower refusal a legal upper success
    :perturb.layer/size       (fn [result] -> octet count or nil), for credit"
  [m]
  (let [missing (remove (fn [k] (contains? m k))
                        [:perturb.layer/name :perturb.layer/handler])]
    (when (seq missing)
      (throw (ex-info "perturb.layer: declaration is missing a key"
                      {:perturb.layer/missing (vec missing)}))))
  (let [ops (or (:perturb.layer/ops m) wire-socket-classes)
        bad (remove (fn [p] (contains? (set op-classes) (:class (second p)))) (seq ops))]
    (when (seq bad)
      (throw (ex-info "perturb.layer: unknown operation class"
                      {:perturb.layer/ops (vec (map first bad))})))
    (assoc m :perturb.layer/ops ops
             :perturb.layer/error-map (or (:perturb.layer/error-map m) [])
             :perturb.layer/size (or (:perturb.layer/size m) (fn [_] nil)))))

;; --- recording one run ------------------------------------------------------

(defn record!
  "Run `thunk` with the layering event log and the ledger mark bound, and return
  everything the checker needs.

  The ledger is RESET first and snapshotted after, exactly as `perturb.tlsdemo`
  does around each of its runs, because `perturb.cap/ledger` is process-global
  (INHERITED I10) and a projection over two runs' transitions is meaningless.

  -> {:perturb.layer/events [...] :perturb.layer/ledger [...]
      :perturb.layer/value v | :perturb.layer/threw s}"
  [thunk]
  (let [events (atom [])]
    (cap/reset-ledger!)
    (fx/reset-event-counters!)
    (let [out (try
                (fx/with-events events
                  (fx/with-event-mark (fn [] (count @cap/ledger))
                    {:perturb.layer/value (thunk)}))
                (catch :default e
                  {:perturb.layer/threw (str e)
                   :perturb.layer/abort (:perturb.effect/abort (ex-data e))}))]
      (assoc out :perturb.layer/events (vec @events)
                 :perturb.layer/ledger (vec @cap/ledger)))))

;; --- indexing the event log -------------------------------------------------

(defn- ev-kind [e] (:perturb.effect/event e))

(defn- index-events
  "The request tree. -> {:by-id {id request-with-:pos/:reply/:refusal}
                         :children {parent-id [id ...]}
                         :mints [mint ...]
                         :refusals [refusal-with-:pos ...]}"
  [events]
  (loop [i 0
         by-id {}
         children {}
         mints []
         refusals []]
    (if (>= i (count events))
      {:by-id by-id :children children :mints mints :refusals refusals}
      (let [e (nth events i)
            k (ev-kind e)]
        (cond
          (= :request k)
          (recur (+ i 1)
                 (assoc by-id (:perturb.effect/id e) (assoc e :perturb.layer/pos i))
                 (update children (:perturb.effect/parent e)
                         (fn [v] (conj (or v []) (:perturb.effect/id e))))
                 mints refusals)

          (= :reply k)
          (let [id (:perturb.effect/id e)]
            (recur (+ i 1)
                   (if (contains? by-id id)
                     (assoc by-id id (assoc (get by-id id)
                                            :perturb.layer/reply e
                                            :perturb.layer/reply-pos i))
                     by-id)
                   children mints refusals))

          ;; A refusal raised by APPLICATION code — `perturb.http`'s `:eof`, say —
          ;; has no request of its own: `abort!` reads `*frame*`, and outside a
          ;; handler there is none. It is still a refusal that happened inside
          ;; some enclosing request's extent, which is what B6.3 reads, so it is
          ;; kept in `:refusals` with a nil id and NOT allowed to invent a
          ;; request. (Attaching it to a fabricated node is how a checker starts
          ;; believing its own bookkeeping.)
          (= :refusal k)
          (let [id (:perturb.effect/id e)]
            (recur (+ i 1)
                   (if (contains? by-id id)
                     (assoc by-id id (assoc (get by-id id) :perturb.layer/refusal e))
                     by-id)
                   children mints (conj refusals (assoc e :perturb.layer/pos i))))

          (= :mint k)
          (recur (+ i 1) by-id children (conj mints (assoc e :perturb.layer/pos i)) refusals)

          :else (recur (+ i 1) by-id children mints refusals))))))

(defn- consumes
  "Every `:consume` event, which is written by `perturb.effect/forward!` BEFORE
  the admissibility tests. Counting from these rather than from requests is what
  makes a SECOND use visible: the second forward is refused and never becomes a
  request, so a count taken from requests would say the instance was used once."
  [events]
  (filter (fn [e] (and (= :consume (ev-kind e))
                       (some? (:perturb.effect/instance e))))
          events))

(defn- layer-of
  "The declared layer whose handler answered `req`, or nil. Identity, not name."
  [decls req]
  (let [h (:perturb.effect/handler req)]
    (first (filter (fn [d] (and (some? h) (= h (:perturb.layer/handler d)))) decls))))

(defn- replied? [req] (contains? req :perturb.layer/reply))

(defn- kids [idx id] (or (get (:children idx) id) []))

(defn- descendants
  [idx id]
  (loop [front [id] acc []]
    (if (empty? front)
      acc
      (let [h (first front)
            ks (kids idx h)]
        (recur (into (vec (rest front)) ks) (into acc ks))))))

(defn- v-> [clause rule req msg]
  {:perturb.layer/clause  clause
   :perturb.layer/rule    rule
   :perturb.layer/id      (:perturb.effect/id req)
   :perturb.layer/op      (:perturb.effect/op req)
   :perturb.layer/site    (:perturb.effect/site req)
   :perturb.layer/message msg})

;; --- B6.1 correlated forwarding ---------------------------------------------

(defn- local-transitions
  "The ledger entries taken by `req`'s own layer inside `req`'s extent.

  The extent is the half-open ledger window [mark-at-request, mark-at-reply),
  which is why `perturb.effect/*event-mark*` exists at all: perturb.effect must
  not know what a capability is, so the consumer supplies the clock and gets
  back a window it can interpret."
  [idx ledger decls req lyr]
  (let [lo (:perturb.effect/mark req)
        hi (:perturb.effect/mark (:perturb.layer/reply req))
        cp (:perturb.layer/capability lyr)
        inner (filter (fn [d]
                        (let [r (get (:by-id idx) d)]
                          (and r (replied? r) (= lyr (layer-of decls r)))))
                      (descendants idx (:perturb.effect/id req)))
        holes (mapv (fn [d]
                      (let [r (get (:by-id idx) d)]
                        [(:perturb.effect/mark r)
                         (:perturb.effect/mark (:perturb.layer/reply r))]))
                    inner)]
    (if (or (nil? lo) (nil? hi))
      []
      (vec (filter (fn [e]
                     (and (= cp (:perturb.cap/capability e))
                          (contains? e :perturb.cap/to)))
                   (filter (fn [e] (not (nil? e)))
                           (map (fn [i]
                                  (if (some (fn [h] (and (>= i (first h)) (< i (second h)))) holes)
                                    nil
                                    (nth ledger i)))
                                (range lo (min hi (count ledger))))))))))

(defn- check-correlation
  [idx ledger decls]
  (reduce
    (fn [acc id]
      (let [req (get (:by-id idx) id)
            lyr (layer-of decls req)]
        (if (or (nil? lyr) (not (replied? req)))
          ;; A refused request made no claim upward, and an undeclared handler
          ;; is not a layer under test. Both are silently out of scope, and the
          ;; summary counts them so the exemption is visible.
          acc
          (let [op   (:perturb.effect/op req)
                spec (get (:perturb.layer/ops lyr) op)
                cs   (mapv (fn [k] (get (:by-id idx) k)) (kids idx id))
                fwd  (filter (fn [c] (= (:lower spec) (:perturb.effect/op c))) cs)
                anc  (filter (fn [c] (not= (:lower spec) (:perturb.effect/op c))) cs)
                locs (local-transitions idx ledger decls req lyr)]
            (if (nil? spec)
              (conj acc (v-> :b6.1 :undeclared-operation req
                             (str "operation " op " has no declared class in layer "
                                  (:perturb.layer/name lyr)
                                  " — B6 cannot say whether it may be answered locally")))
              (let [acc1 (reduce (fn [a c]
                                   (if (contains? (or (:ancillary spec) #{})
                                                  (:perturb.effect/op c))
                                     a
                                     (conj a (v-> :b6.1 :undeclared-ancillary-forward req
                                                  (str "forwarded " (:perturb.effect/op c)
                                                       " at " (:perturb.effect/site c)
                                                       ", which is neither the corresponding lower"
                                                       " operation (" (:lower spec)
                                                       ") nor a declared ancillary of " op)))))
                                 acc anc)
                    acc2 (cond
                           (= :mandatory-forward (:class spec))
                           (if (= 1 (count fwd))
                             acc1
                             (conj acc1
                                   (v-> :b6.1 :mandatory-forward req
                                        (str op " is :mandatory-forward and emitted "
                                             (count fwd) " correlated forwards of "
                                             (:lower spec) ", not exactly 1"
                                             " — the result of this operation is a handle in the"
                                             " lower layer's namespace, so a local answer is not"
                                             " an alternative ("
                                             (count locs) " local transition(s) taken)"))))

                           (= :fan-out (:class spec))
                           (if (>= (count fwd) 1)
                             acc1
                             (conj acc1
                                   (v-> :b6.1 :fan-out req
                                        (str op " is :fan-out and emitted 0 correlated forwards of "
                                             (:lower spec)
                                             " — the octets it accepted never left this layer"))))

                           :else acc1)
                    acc3 (if (and (empty? fwd) (empty? locs))
                           (conj acc2 (v-> :b6.1 :no-transition-no-forward req
                                           (str op " emitted no correlated forward and took no"
                                                " declared local transition of "
                                                (:perturb.layer/capability lyr))))
                           acc2)]
                acc3))))))
    []
    (sort (keys (:by-id idx)))))

(defn- check-credit
  "The :demand-driven class's side condition, folded over the run IN REPLY ORDER.

  Reply order, not request order, and the difference is the whole check. A
  request's forwards have HIGHER ids than the request itself — a child is minted
  inside its parent — so folding by id would debit the layer for octets it
  delivered before crediting it with the octets it forwarded for, and the
  known-good would fail on its very first `recv`. A reply is emitted when the
  operation FINISHED, so children's replies precede their parent's, which is
  exactly the order credit accrues in."
  [events idx decls]
  (let [ids (mapv :perturb.effect/id
                  (filter (fn [e] (and (= :reply (ev-kind e))
                                       (contains? (:by-id idx) (:perturb.effect/id e))))
                          events))]
    (first
      (reduce
        (fn [st id]
          (let [acc (first st)
                bal (second st)
                req (get (:by-id idx) id)
                lyr (layer-of decls req)
                par (if (:perturb.effect/parent req)
                      (get (:by-id idx) (:perturb.effect/parent req))
                      nil)
                plyr (if par (layer-of decls par) nil)
                sz  (fn [l r] (if (replied? r)
                                ((:perturb.layer/size l)
                                 (:perturb.effect/result (:perturb.layer/reply r)))
                                nil))]
            (cond
              ;; a forward this layer made: octets IN
              (and plyr
                   (let [s (get (:perturb.layer/ops plyr) (:perturb.effect/op par))]
                     (and s (:credit s) (= (:lower s) (:perturb.effect/op req)))))
              (let [n (sz plyr req)]
                [acc (update bal (:perturb.layer/name plyr)
                             (fn [v] (+ (or v 0) (or n 0))))])

              ;; an answer this layer gave upward: octets OUT, paid from credit
              (and lyr
                   (let [s (get (:perturb.layer/ops lyr) (:perturb.effect/op req))]
                     (and s (:credit s))))
              (let [n (or (sz lyr req) 0)
                    have (or (get bal (:perturb.layer/name lyr)) 0)]
                (if (> n have)
                  [(conj acc (v-> :b6.1 :credit req
                                  (str "answered " n
                                       " octet(s) locally with only " have
                                       " received from below — a :demand-driven local answer"
                                       " must be backed by octets this layer forwarded for")))
                   (assoc bal (:perturb.layer/name lyr) 0)]
                  [acc (assoc bal (:perturb.layer/name lyr) (- have n))]))

              :else [acc bal])))
        [[] {}]
        ids))))

;; --- B6.2 protocol-trace projection -----------------------------------------

(defn- edge-admits? [t from]
  (let [f (:from t)]
    (if (vector? f) (contains? (set f) from) (= f from))))

(defn- same-name?
  "A ledger `:site` keyword and a declared `:op` symbol name the same operation."
  [site op]
  (and (some? site) (some? op)
       (= (namespace site) (namespace op))
       (= (name site) (name op))))

(defn- check-projection
  "The composed trace, projected onto each capability instance and replayed
  against the declared machine."
  [ledger]
  (let [decls (:perturb.cap/capabilities @cap/registry)
        steps (filter (fn [e] (contains? e :perturb.cap/to)) ledger)]
    (first
      (reduce
        (fn [st e]
          (let [acc  (first st)
                adv  (second st)
                seen (nth st 2)
                cp   (:perturb.cap/capability e)
                id   (:perturb.cap/id e)
                d    (get decls cp)
                ts   (:perturb.cap/typestate d)
                cur  (get seen [cp id])
                edges (filter (fn [t] (and (edge-admits? t (:perturb.cap/from e))
                                           (= (:to t) (:perturb.cap/to e))))
                              (:transitions ts))
                mk   (fn [rule msg]
                       {:perturb.layer/clause :b6.2 :perturb.layer/rule rule
                        :perturb.layer/id id :perturb.layer/op cp
                        :perturb.layer/site (:perturb.cap/site e)
                        :perturb.layer/message msg})]
            (cond
              (nil? d)
              [acc (conj adv (mk :undeclared-capability
                                 (str "the ledger moved " cp
                                      " but no capability of that name is declared")))
               seen]

              :else
              (let [acc1 (if (and (contains? seen [cp id])
                                  (not= cur (:perturb.cap/from e)))
                           (conj acc (mk :state-discontinuity
                                         (str cp " " id " left " (pr-str cur)
                                              " but the next step declares :from "
                                              (pr-str (:perturb.cap/from e)))))
                           acc)
                    acc2 (if (and (not (contains? seen [cp id]))
                                  (some? (:perturb.cap/from e)))
                           (conj acc1 (mk :state-discontinuity
                                          (str cp " " id
                                               " first appears with :from "
                                               (pr-str (:perturb.cap/from e))
                                               " rather than being minted from nil")))
                           acc1)
                    acc3 (if (and (contains? seen [cp id])
                                  (= cur (:terminal ts)))
                           (conj acc2 (mk :after-terminal
                                          (str cp " " id " moved after reaching the terminal state "
                                               (pr-str (:terminal ts)))))
                           acc2)
                    acc4 (if (empty? edges)
                           (conj acc3 (mk :undeclared-edge
                                          (str cp " " id " took "
                                               (pr-str (:perturb.cap/from e)) " -> "
                                               (pr-str (:perturb.cap/to e))
                                               ", which is not a declared edge of its machine")))
                           acc3)
                    adv1 (if (and (seq edges)
                                  (empty? (filter (fn [t] (same-name? (:perturb.cap/site e)
                                                                      (:op t)))
                                                  edges)))
                           (conj adv (mk :edge-label
                                         (str "the ledger labels this step @"
                                              (:perturb.cap/site e)
                                              " but the declared edge(s) name "
                                              (pr-str (vec (map :op edges)))
                                              " — the projection is legal, the LABEL is not"
                                              " the operation's declared name")))
                           adv)]
                [acc4 adv1 (assoc seen [cp id] (:perturb.cap/to e))]))))
        [[] [] {}]
        steps))))

(defn- projection-advisories
  [ledger]
  (nth (let [decls (:perturb.cap/capabilities @cap/registry)
             steps (filter (fn [e] (contains? e :perturb.cap/to)) ledger)]
         (reduce
           (fn [st e]
             (let [adv  (second st)
                   seen (nth st 2)
                   cp   (:perturb.cap/capability e)
                   id   (:perturb.cap/id e)
                   d    (get decls cp)
                   ts   (:perturb.cap/typestate d)
                   edges (filter (fn [t] (and (edge-admits? t (:perturb.cap/from e))
                                              (= (:to t) (:perturb.cap/to e))))
                                 (:transitions ts))]
               (if (nil? d)
                 [nil (conj adv {:perturb.layer/clause :b6.2
                                 :perturb.layer/rule :undeclared-capability
                                 :perturb.layer/id id :perturb.layer/op cp
                                 :perturb.layer/site (:perturb.cap/site e)
                                 :perturb.layer/message
                                 (str "the ledger moved " cp " but no capability of that name"
                                      " is declared")})
                  seen]
                 [nil
                  (if (and (seq edges)
                           (empty? (filter (fn [t] (same-name? (:perturb.cap/site e) (:op t)))
                                           edges)))
                    (conj adv {:perturb.layer/clause :b6.2
                               :perturb.layer/rule :edge-label
                               :perturb.layer/id id :perturb.layer/op cp
                               :perturb.layer/site (:perturb.cap/site e)
                               :perturb.layer/message
                               (str "step labelled @" (:perturb.cap/site e)
                                    " takes a declared edge whose :op is "
                                    (pr-str (vec (map :op edges)))
                                    " — legal projection, misnamed label")})
                    adv)
                  (assoc seen [cp id] (:perturb.cap/to e))])))
           [nil [] {}]
           steps))
       1))

;; --- B6.3 error mapping -----------------------------------------------------

(defn- mapped?
  [lyr op kind]
  (seq (filter (fn [m] (and (= op (:op m)) (= kind (:abort m))))
               (:perturb.layer/error-map lyr))))

(defn- check-error-mapping
  [idx decls]
  (reduce
    (fn [acc id]
      (let [req (get (:by-id idx) id)
            lyr (layer-of decls req)]
        (if (or (nil? lyr) (not (replied? req)))
          acc
          (let [lo (:perturb.layer/pos req)
                hi (:perturb.layer/reply-pos req)
                inside (filter (fn [r] (and (> (:perturb.layer/pos r) lo)
                                            (< (:perturb.layer/pos r) hi)))
                               (:refusals idx))]
            (reduce
              (fn [a r]
                (let [k (:perturb.effect/abort r)]
                  (if (mapped? lyr (:perturb.effect/op req) k)
                    a
                    (conj a (v-> :b6.3 :unmapped-refusal req
                                 (str "replied [:ok ...] while a " (pr-str k)
                                      " was refused inside its extent (request "
                                      (:perturb.effect/id r)
                                      "), and layer " (:perturb.layer/name lyr)
                                      " declares no error-mapping edge for {"
                                      (pr-str (:perturb.effect/op req)) " "
                                      (pr-str k) "}"))))))
              acc inside)))))
    []
    (sort (keys (:by-id idx)))))

(defn- mapped-edges-used
  "The B6.3 absorptions that WERE declared, so a passing run shows its edges
  rather than showing nothing."
  [idx decls]
  (reduce
    (fn [acc id]
      (let [req (get (:by-id idx) id)
            lyr (layer-of decls req)]
        (if (or (nil? lyr) (not (replied? req)))
          acc
          (let [lo (:perturb.layer/pos req)
                hi (:perturb.layer/reply-pos req)
                inside (filter (fn [r] (and (> (:perturb.layer/pos r) lo)
                                            (< (:perturb.layer/pos r) hi)))
                               (:refusals idx))]
            (reduce (fn [a r]
                      (if (mapped? lyr (:perturb.effect/op req) (:perturb.effect/abort r))
                        (conj a {:perturb.layer/layer (:perturb.layer/name lyr)
                                 :perturb.layer/op (:perturb.effect/op req)
                                 :perturb.layer/abort (:perturb.effect/abort r)
                                 :perturb.layer/id (:perturb.effect/id req)})
                        a))
                    acc inside)))))
    []
    (sort (keys (:by-id idx)))))

;; --- B6.4 outward-operation admissibility -----------------------------------

(defn- ancestors [idx id]
  (loop [cur (:perturb.effect/parent (get (:by-id idx) id)) acc []]
    (if (nil? cur)
      acc
      (recur (:perturb.effect/parent (get (:by-id idx) cur)) (conj acc cur)))))

(defn- check-outward
  [events idx decls]
  (let [ids (sort (keys (:by-id idx)))
        cons-ev (consumes events)
        ;; (a) self-interception
        self (reduce
               (fn [acc id]
                 (let [req (get (:by-id idx) id)
                       h   (:perturb.effect/handler req)
                       hit (filter (fn [a]
                                     (= h (:perturb.effect/handler (get (:by-id idx) a))))
                                   (ancestors idx id))]
                   (if (or (nil? h) (empty? hit))
                     acc
                     (conj acc (v-> :b6.4 :self-interception req
                                    (str "this forward was answered by the SAME handler object"
                                         " already serving request " (first hit)
                                         " — the adapter intercepted its own forwarded"
                                         " operation"))))))
               [] ids)
        ;; (b)-(d) outward instances
        consumed (filter (fn [id] (some? (:perturb.effect/outward (get (:by-id idx) id)))) ids)
        by-inst  (reduce (fn [m e]
                           (update m (:perturb.effect/instance e)
                                   (fn [v] (conj (or v []) (:perturb.effect/in e)))))
                         {} cons-ev)
        multi (reduce (fn [acc p]
                        (if (<= (count (second p)) 1)
                          acc
                          (conj acc {:perturb.layer/clause :b6.4
                                     :perturb.layer/rule :multi-shot-outward
                                     :perturb.layer/id (first (second p))
                                     :perturb.layer/op :recv
                                     :perturb.layer/site :perturb.layer/outward
                                     :perturb.layer/message
                                     (str "outward-operation instance " (first p)
                                          " was presented to forward! " (count (second p))
                                          " times, under request(s) "
                                          (pr-str (vec (second p)))
                                          " — an outward operation that may resume many"
                                          " times is REJECTED (E33, Tang's admissibility"
                                          " conditions)")})))
                      [] (seq by-inst))
        zero (reduce (fn [acc m]
                       (if (contains? by-inst (:perturb.effect/instance m))
                         acc
                         (conj acc {:perturb.layer/clause :b6.4
                                    :perturb.layer/rule :zero-shot-outward
                                    :perturb.layer/id (:perturb.effect/created-in m)
                                    :perturb.layer/op :mint
                                    :perturb.layer/site (:perturb.effect/site m)
                                    :perturb.layer/message
                                    (str "outward-operation instance "
                                         (:perturb.effect/instance m)
                                         " was minted in request "
                                         (:perturb.effect/created-in m)
                                         " and never consumed — the layer answered without"
                                         " performing the operation it had committed to")})))
                     [] (:mints idx))
        escaped (reduce
                  (fn [acc id]
                    (let [r (get (:by-id idx) id)
                          o (:perturb.effect/outward r)]
                      (if (= (:perturb.effect/created-in o) (:perturb.effect/parent r))
                        acc
                        (conj acc (v-> :b6.4 :escaped-outward r
                                       (str "outward-operation instance "
                                            (:perturb.effect/instance o)
                                            " was minted in request "
                                            (pr-str (:perturb.effect/created-in o))
                                            " but consumed under request "
                                            (pr-str (:perturb.effect/parent r))
                                            " — it escaped the dynamic extent that created it,"
                                            " which is what `control-flow-unrestricted` means"))))))
                  [] consumed)
        uncorrelated (reduce
                       (fn [acc id]
                         (let [r (get (:by-id idx) id)
                               p (if (:perturb.effect/parent r)
                                   (get (:by-id idx) (:perturb.effect/parent r))
                                   nil)]
                           (if (or (nil? p)
                                   (nil? (layer-of decls p))
                                   (= :forward (:perturb.effect/route r)))
                             acc
                             (conj acc (v-> :b6.4 :performed-not-forwarded r
                                            (str "a declared layer reached the boundary by"
                                                 " PERFORMING (route "
                                                 (pr-str (:perturb.effect/route r))
                                                 ") rather than forwarding to a named outer"
                                                 " instance — `perform` does not pop the"
                                                 " executing handler, so nothing refused it"))))))
                       [] ids)]
    (vec (concat self multi zero escaped uncorrelated))))

;; --- B6.5 finalisation (§A3) -------------------------------------------------

(defn- check-finalisation
  "Every rung that FORWARDED anything must have been finalised exactly once.

  Scoped to rungs that forwarded on purpose: a bottom-rung instance lifted from
  a bare transport handler has nothing below it and nothing to hand on, and
  requiring it to finalise would be requiring a ceremony rather than a
  discharge. The summary counts the exempt ones so the scope is visible rather
  than assumed."
  [events idx]
  (let [rungs (filter (fn [e] (= :rung (ev-kind e))) events)
        fins  (filter (fn [e] (= :finalize (ev-kind e))) events)
        used  (set (map (fn [id] (:perturb.effect/rung (get (:by-id idx) id)))
                        (filter (fn [id] (= :forward (:perturb.effect/route
                                                       (get (:by-id idx) id))))
                                (keys (:by-id idx)))))
        n-of  (fn [rid] (count (filter (fn [f] (= rid (:perturb.effect/rung f))) fins)))]
    (reduce
      (fn [acc r]
        (let [rid (:perturb.effect/rung r)]
          (if (not (contains? used rid))
            acc
            (let [n (n-of rid)]
              (cond
                (= n 1) acc
                (= n 0) (conj acc {:perturb.layer/clause :b6.5
                                   :perturb.layer/rule :not-finalized
                                   :perturb.layer/id rid
                                   :perturb.layer/op :rung
                                   :perturb.layer/site (:perturb.effect/name r)
                                   :perturb.layer/message
                                   (str "rung " (:perturb.effect/name r) " (instance " rid
                                        ") forwarded during this run and was never finalised"
                                        " — §A3 requires finalisation on success, abort AND"
                                        " exception")})
                :else (conj acc {:perturb.layer/clause :b6.5
                                 :perturb.layer/rule :double-finalized
                                 :perturb.layer/id rid
                                 :perturb.layer/op :rung
                                 :perturb.layer/site (:perturb.effect/name r)
                                 :perturb.layer/message
                                 (str "rung " (:perturb.effect/name r) " (instance " rid
                                      ") was finalised " n " times")}))))))
      [] rungs)))

;; --- B6.6 route integrity and replay coherence (§A3) -------------------------

(def routes
  "The routes a crossing may take, and the one that must never appear.

  §A3, nonclaims: `The native proceed and a layer forward are different
  mechanisms and must remain different trace routes.` `:proceed` is listed so
  its ABSENCE is asserted rather than assumed — if a native resumption is ever
  routed through this log, the merge is a failing check instead of a silent
  reinterpretation of every `forward` count in the artifact."
  {:perform  "application (or non-forwarding) code crossed the boundary"
   :forward  "a rung crossed to its NAMED OUTER INSTANCE"
   :proceed  "RESERVED: Jolt's native seam — scoped, owner-bound, LIFO, one-shot"})

(defn canonical
  "ONE CANONICAL RECORD PER ATTEMPTED OPERATION (§A3).

  Every attempted operation — including one that was refused, one whose handler
  returned a malformed reply, and one that found no handler at all — appears
  exactly once, carrying its route and its outcome. This is the projection
  replay-coherence is checked over and the projection a consumer should be
  handed; the streaming event log exists because extents need a start and an
  end, not because two representations were wanted."
  [events]
  (let [idx (index-events events)]
    (mapv (fn [id]
            (let [r (get (:by-id idx) id)
                  f (:perturb.layer/refusal r)]
              {:perturb.layer/seq     (:perturb.effect/seq r)
               :perturb.layer/attempt (:perturb.effect/attempt r)
               :perturb.layer/id      id
               :perturb.layer/parent  (:perturb.effect/parent r)
               :perturb.layer/depth   (:perturb.effect/depth r)
               :perturb.layer/route   (:perturb.effect/route r)
               :perturb.layer/rung    (:perturb.effect/rung r)
               :perturb.layer/op      (:perturb.effect/op r)
               :perturb.layer/site    (:perturb.effect/site r)
               :perturb.layer/mark    (:perturb.effect/mark r)
               :perturb.layer/outcome (cond (replied? r) :ok
                                            (some? f)    :refused
                                            :else        :propagated)
               :perturb.layer/abort   (if f (:perturb.effect/abort f) nil)}))
          (sort (keys (:by-id idx))))))

(defn- coh [rule rec msg]
  {:perturb.layer/clause :b6.6 :perturb.layer/rule rule
   :perturb.layer/id (:perturb.layer/id rec)
   :perturb.layer/op (:perturb.layer/op rec)
   :perturb.layer/site (:perturb.layer/site rec)
   :perturb.layer/message msg})

(defn replay-coherence
  "Fold the canonical projection and report every way it fails to hang together.

  This is the check a FORGED or REORDERED trace has to survive, and it is
  deliberately structural: it asks nothing about what the operations meant, only
  whether the record could have been produced by a run. E26's `replay-history!`
  draws the same line one rung down."
  [canon]
  (first
    (reduce
      (fn [st rec]
        (let [acc  (first st)
              prev (second st)
              seen (nth st 2)
              sq   (:perturb.layer/seq rec)
              par  (:perturb.layer/parent rec)
              rt   (:perturb.layer/route rec)
              n    (+ 1 (count seen))
              acc0 (if (= n (:perturb.layer/attempt rec))
                     acc
                     (conj acc (coh :attempt-not-dense rec
                                    (str "this is projected record " n
                                         " but carries attempt ordinal "
                                         (pr-str (:perturb.layer/attempt rec))
                                         " — attempts are dense 1..N, so a hole means a"
                                         " record was DROPPED or the projection was"
                                         " truncated"))))
              acc  acc0
              acc1 (if (and prev (not (> sq (:perturb.layer/seq prev))))
                     (conj acc (coh :sequence-not-increasing rec
                                    (str "sequence " (pr-str sq)
                                         " does not follow " (pr-str (:perturb.layer/seq prev))
                                         " — records were reordered or one was rewritten")))
                     acc)
              acc2 (if (and prev (not (>= (or (:perturb.layer/mark rec) 0)
                                          (or (:perturb.layer/mark prev) 0))))
                     (conj acc1 (coh :mark-went-backwards rec
                                     (str "capability-ledger mark " (pr-str (:perturb.layer/mark rec))
                                          " is behind the previous record's "
                                          (pr-str (:perturb.layer/mark prev)))))
                     acc1)
              acc3 (if (and (some? par) (not (contains? seen par)))
                     (conj acc2 (coh :parent-not-earlier rec
                                     (str "names parent " (pr-str par)
                                          ", which does not appear EARLIER in the projection"
                                          " — a child cannot precede the request that caused it")))
                     acc2)
              acc4 (if (and (some? par) (contains? seen par)
                            (not= (:perturb.layer/depth rec)
                                  (+ 1 (:perturb.layer/depth (get seen par)))))
                     (conj acc3 (coh :depth-mismatch rec
                                     (str "depth " (pr-str (:perturb.layer/depth rec))
                                          " is not one deeper than parent " (pr-str par)
                                          " at depth "
                                          (pr-str (:perturb.layer/depth (get seen par))))))
                     acc3)
              acc5 (cond
                     (= :proceed rt)
                     (conj acc4 (coh :proceed-route rec
                                     (str "route :proceed appeared in a perturb trace."
                                          " perturb has NO native proceed; a layer forward and"
                                          " a native resumption must remain different routes")))
                     (not (contains? routes rt))
                     (conj acc4 (coh :unknown-route rec
                                     (str "route " (pr-str rt) " is not one of "
                                          (pr-str (vec (sort (map str (keys routes))))))))
                     :else acc4)
              acc6 (cond
                     (and (= :forward rt) (nil? (:perturb.layer/rung rec)))
                     (conj acc5 (coh :forward-without-rung rec
                                     "a :forward names no rung instance"))
                     (and (= :perform rt) (some? (:perturb.layer/rung rec)))
                     (conj acc5 (coh :perform-with-rung rec
                                     (str "a :perform names rung instance "
                                          (pr-str (:perturb.layer/rung rec))
                                          " — only a :forward targets a named outer instance")))
                     :else acc5)]
          [acc6 rec (assoc seen (:perturb.layer/id rec) rec)]))
      [[] nil {}]
      canon)))

(defn- check-propagation
  "A record whose outcome is :propagated must have a descendant that was
  actually refused; otherwise an attempt has no outcome at all."
  [canon]
  (let [by-parent (reduce (fn [m r] (update m (:perturb.layer/parent r)
                                            (fn [v] (conj (or v []) r))))
                          {} canon)
        refused?  (fn [r] (or (= :refused (:perturb.layer/outcome r))
                              (= :propagated (:perturb.layer/outcome r))))]
    (reduce (fn [acc r]
              (if (not= :propagated (:perturb.layer/outcome r))
                acc
                (if (seq (filter refused? (or (get by-parent (:perturb.layer/id r)) [])))
                  acc
                  (conj acc (coh :outcome-missing r
                                 (str "attempt " (:perturb.layer/id r)
                                      " has no outcome and no refused descendant to have"
                                      " propagated one — the record is incomplete"))))))
            [] canon)))

;; --- the check ---------------------------------------------------------------

(defn check
  "B6 over one recorded run. -> {:violations [...] :advisories [...] :summary {}}"
  [decls recorded]
  (let [events (:perturb.layer/events recorded)
        ledger (:perturb.layer/ledger recorded)
        idx    (index-events events)
        ids    (keys (:by-id idx))
        served (filter (fn [id] (some? (layer-of decls (get (:by-id idx) id)))) ids)
        canon  (canonical events)
        vs     (vec (concat (check-correlation idx ledger decls)
                            (check-credit events idx decls)
                            (check-projection ledger)
                            (check-error-mapping idx decls)
                            (check-outward events idx decls)
                            (check-finalisation events idx)
                            (replay-coherence canon)
                            (check-propagation canon)))]
    {:perturb.layer/violations vs
     :perturb.layer/advisories (projection-advisories ledger)
     :perturb.layer/mapped     (mapped-edges-used idx decls)
     :perturb.layer/canonical  canon
     :perturb.layer/summary
     {:requests      (count ids)
      :forwards      (count (filter (fn [id] (= :forward (:perturb.effect/route
                                                           (get (:by-id idx) id)))) ids))
      :performs      (count (filter (fn [id] (= :perform (:perturb.effect/route
                                                           (get (:by-id idx) id)))) ids))
      :served-by-declared-layers (count served)
      :refusals      (count (:refusals idx))
      :outward-mints (count (:mints idx))
      :rungs         (count (filter (fn [e] (= :rung (ev-kind e))) events))
      :finalizations (count (filter (fn [e] (= :finalize (ev-kind e))) events))
      :ledger-steps  (count (filter (fn [e] (contains? e :perturb.cap/to)) ledger))
      :events        (count events)}}))

;; --- rendering ---------------------------------------------------------------

(defn- clause-name [c]
  (cond
    (= :b6.1 c) "B6.1 correlated forwarding"
    (= :b6.2 c) "B6.2 protocol-trace projection"
    (= :b6.3 c) "B6.3 error mapping"
    (= :b6.4 c) "B6.4 outward-operation admissibility"
    (= :b6.5 c) "B6.5 finalisation (A3)"
    (= :b6.6 c) "B6.6 route integrity / replay coherence (A3)"
    :else (str c)))

(defn render
  "The verdict, as lines."
  [result]
  (let [vs  (:perturb.layer/violations result)
        s   (:perturb.layer/summary result)]
    (concat
      [(str "    trace: " (:events s) " events, " (:requests s) " attempted operations ("
            (:performs s) " route :perform + " (:forwards s) " route :forward), "
            (:outward-mints s) " outward instances, "
            (:refusals s) " refusals, " (:ledger-steps s) " ledger steps")
       (str "           " (:rungs s) " rung instances, " (:finalizations s)
            " finalisations; " (:served-by-declared-layers s)
            " requests were served by a DECLARED layer and are the ones B6 is about")]
      (if (empty? vs)
        ["    B6: no violation on this trace"]
        (concat
          [(str "    B6: " (count vs) " VIOLATION(S)")]
          (mapv (fn [v]
                  (str "      [" (clause-name (:perturb.layer/clause v)) " / "
                       (name (:perturb.layer/rule v)) "]"
                       "  req " (:perturb.layer/id v)
                       " " (pr-str (:perturb.layer/op v))
                       " @" (:perturb.layer/site v)
                       "\n           " (:perturb.layer/message v)))
                vs))))))

(defn clauses-hit
  "The set of clauses a result has violations for — what a negative control is
  asserted against."
  [result]
  (set (map :perturb.layer/clause (:perturb.layer/violations result))))

(defn rules-hit
  [result]
  (set (map :perturb.layer/rule (:perturb.layer/violations result))))

;; --- what this check does NOT do --------------------------------------------

(defn report-limits
  "Read this beside every verdict above. Each line is a thing a reader could
  reasonably assume and must not."
  []
  ["1.  IT IS A TRACE CHECK. Everything above is about crossings that HAPPENED"
   "    on one recorded run. A path not taken is not checked, and `no violation`"
   "    is not `no violation is possible`. On the evidence lattice this is"
   "    `monitored` for the runs it is wired to, never `proved`."
   "2.  THE OPERATION CLASSES ARE HAND-DRAWN, FOR ONE EFFECT. `wire-socket-classes`"
   "    is a judgement about what perturb.wire/socket's six operations mean."
   "    Nothing derives it and nothing checks that a layer's declaration of its"
   "    own classes is truthful — a layer that declared `accept` :demand-driven"
   "    would be believed."
   "3.  CREDIT IS A RUNNING TOTAL, NOT PROVENANCE. A :demand-driven local answer"
   "    is checked only for octet COUNT against octets received. A layer that"
   "    delivered the right number of invented octets passes B6.1's credit arm."
   "4.  B6.2 REPLAYS THE LEDGER, WHICH IS SELF-REPORTED. perturb.cap/transition!"
   "    is called BY the code it describes, so the projection checks that the"
   "    machine's own account of itself is internally legal. It cannot see a"
   "    transition the code never noted."
   "5.  CLAUSE 4'S TRACE HALF IS A RUNTIME OBSERVATION. `perturb.effect/forward!`"
   "    REFUSES a self-forward, a reused outward instance, a wrong-owner forward"
   "    and a forward from inside a finalizer, and those refusals are latched. The"
   "    trace clauses are a backstop for code that reaches the boundary by"
   "    PERFORMING rather than forwarding, where `perform` still does not pop the"
   "    executing handler and only the checker can see it."
   "6.  OWNERSHIP IS A TOKEN, NOT A THREAD. `perturb.effect/*owner*` represents"
   "    the thread of control that may use a rung. The wrong-owner control"
   "    rebinds the token; perturb has never run on two real threads (E29"
   "    nonclaim 1), so that control exercises the CHECK and says nothing about"
   "    threading. *frame* and *outward* are dynamic vars and are per thread."
   "7.  FINALISATION IS SCOPED TO RUNGS THAT FORWARDED. A rung that forwarded"
   "    nothing is exempt, and a finalizer may only REPORT — it cannot discharge"
   "    an obligation that needs a forward, because forwarding from inside one is"
   "    refused. `close-record!`'s close-notify is exactly such an obligation."
   "8.  REPLAY COHERENCE IS STRUCTURAL. It asks whether a projection could have"
   "    been produced by a run, not whether the run was correct. A forgery that"
   "    is internally consistent passes it."
   "9.  THIS IS NOT THE BOUNDARY. perturb.effect/native! and perturb.posix/gate!"
   "    are untouched; nothing here stands in for the fail-closed FFI seam or"
   "    for dev/verify-noio.sh, and §A3 warns specifically against that swap."
   "10. E33's LABEL STANDS: this is a testable design hypothesis, not a theorem."])
