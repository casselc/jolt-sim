(ns perturb.region
  "A REGION: a bounded set of capabilities with ONE static identity.

  WHY THIS EXISTS. PERTURB-DESIGN §4.6's root cause is that a capability may
  only live in a binding of statically known shape, and E24 named the four
  shapes that rules out — collection, growth, runtime selection, dynamic
  sharing. `perturb.evt`'s driver B is all four in one data structure: a map
  from a runtime id to a ServerConn, that grows, dispatched by a value the
  application chose, behind a function-valued parameter. It is rejected function
  by function, and the rejections are E24's measurement.

  THE CONCESSION, STATED FIRST. A region does NOT make the checker able to
  follow a capability into a map. It makes the checker STOP TRYING, and keep the
  obligation instead:

    - the REGION is the tracked capability, and it has a static identity;
    - membership is a RUN-TIME fact, checked by this namespace;
    - `close!` is the only way out, and a region that is not empty when it
      closes is a violation WITH A COUNT.

  So a leaked connection is reported as `region exited holding 2` at a source
  location, and NOT as an identity the checker can name. That is strictly less
  than driver A's register file establishes, and strictly more than nothing,
  which is what driver B establishes today.

  WHY THERE IS ONE OPERATION PER MEMBER STATE. `:emits` is a static annotation,
  so `take` cannot decide at run time which state to emit its member
  at. `take-reading` and `take-responding` are two operations because ServerConn
  has two states driver B holds members in. A real implementation would generate
  these per (capability × state); doing it by hand is a NOTATIONAL COST the
  design has to own, and it is written out here rather than hidden behind a
  macro so the cost is visible. See `perturb.regioncheck`'s residuals.

  THE VIOLATION POLICY IS A PARAMETER, and that is the experiment.
  `:perturb.region/on-violation` is `:report` or `:refuse`:

    :report  record the violation, hand the member over anyway, keep running.
             This is what lets driver B' put the SAME OCTETS on the wire as
             driver B, so the two are comparable at all.
    :refuse  `fx/abort!`, latched.

  Both are run by `perturb.regioncheck`, because the difference between them is
  the measurement: `:report` says what a region WOULD have caught, and `:refuse`
  says what catching it COSTS."
  (:require [perturb.cap :as cap]
            [perturb.effect :as fx]))

(def ^:private counter (atom 0))

(defn- fresh-id [p]
  (swap! counter inc)
  (str p (deref counter)))

;; --- the violation log ------------------------------------------------------
;;
;; A region under `:report` must still SAY what it saw, or the mode is just a
;; way of being wrong quietly. The log is process-global and is reset by the
;; harness, in the same style as `perturb.cap`'s ledger.

(def violations (atom []))

(defn reset-violations! [] (reset! violations []))

(defn- violate!
  "Record a region violation. Under `:refuse` this aborts (latched); under
  `:report` it records and returns nil so the caller proceeds."
  [r kind detail]
  (let [v (assoc detail :perturb.region/kind kind
                        :perturb.region/tag (:perturb.region/tag r)
                        :perturb.region/region (:perturb.cap/id r))]
    (swap! violations conj v)
    (when (= :refuse (:perturb.region/on-violation r))
      (fx/abort! kind v))
    nil))

;; --- the capability ---------------------------------------------------------

(def region-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.region/Region
       :perturb.cap/doc
       (str "A bounded set of capabilities with one static identity. Members are "
            "admitted and taken by run-time key; the region's own typestate is "
            "the static half and its membership is the dynamic half.")
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined

       ;; THE MACHINE IS DELIBERATELY TRIVIAL. Every membership operation is a
       ;; self-loop on :open. The region's typestate carries no information
       ;; about its CONTENTS, and it must not pretend to: the whole point is
       ;; that the contents are a run-time fact. What the machine gives is the
       ;; one thing a static checker can supply here — that the region is
       ;; closed exactly once, on every path.
       :perturb.cap/typestate
       {:states   [:open :closed]
        :initial  :open
        :terminal :closed
        :transitions
        [{:op 'perturb.region/open            :from nil   :to :open}
         {:op 'perturb.region/put-reading!    :from :open :to :open}
         {:op 'perturb.region/put-responding! :from :open :to :open}
         {:op 'perturb.region/take-reading    :from :open :to :open}
         {:op 'perturb.region/take-responding :from :open :to :open}
         {:op 'perturb.region/close!          :from :open :to :closed}]}

       ;; THE ACCESSORS ARE INSIDE THE REPRESENTATION. `member?`, `state-of`,
       ;; `size` and `stats` read the region's own fields, and a keyword applied
       ;; to a tracked capability is `a computed function` to the checker — the
       ;; same rule that refuses `clojure.core/assoc` on a ServerConn. Listing
       ;; them is not a weakening: it says these four are the region's own
       ;; representation, which is what `perturb.nrepl` does with its five.
       :perturb.cap/representation
       ['perturb.region/member? 'perturb.region/state-of
        'perturb.region/size    'perturb.region/stats]

       ;; THE OBLIGATION IS THE POINT. `exit-empty` is the transferred debt: a
       ;; member that entered the region and never left is a leak, and the
       ;; region is the only thing that can still say so.
       :perturb.cap/obligations
       '[{:name exit-empty
          :formula (forall [e ledger]
                     (implies (= :closed (:to e))
                              (= 0 (:perturb.region/size e))))}]

       :perturb.cap/locality :dropped-by-design})))

;; --- opening and closing ----------------------------------------------------

(defn open
  "Open a region. `bound` is the declared maximum membership; exceeding it is a
  violation, because an unbounded region is a leak with better manners."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [tag bound on-violation]
  (let [id (fresh-id "perturb-region-")]
    (cap/transition! 'perturb.region/Region id nil :open :perturb.region/open)
    {:perturb.cap/capability      'perturb.region/Region
     :perturb.cap/id              id
     :perturb.cap/state           :open
     :perturb.region/tag          tag
     :perturb.region/bound        bound
     :perturb.region/on-violation on-violation
     :perturb.region/members      {}
     :perturb.region/puts         0
     :perturb.region/takes        0
     :perturb.region/peak         0}))

(defn close!
  "Close the region. THE EXIT RULE. A non-empty region at close is the
  transferred obligation coming due, and the report carries the COUNT and the
  KEYS — which is exactly as much as a region can know and exactly as much as
  driver B's map can never say."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :closed}]}}
  [r]
  (let [ms (:perturb.region/members r)
        n  (count ms)]
    (when (pos? n)
      (violate! r :perturb.region/exit-not-empty
                {:perturb.region/size n
                 :perturb.region/keys (vec (sort (keys ms)))}))
    (cap/transition! 'perturb.region/Region (:perturb.cap/id r) :open :closed
                     :perturb.region/close)
    (assoc r :perturb.cap/state :closed)))

;; --- membership -------------------------------------------------------------

(defn- put*
  [r k m st site]
  (let [ms (:perturb.region/members r)]
    (when (contains? ms k)
      (violate! r :perturb.region/duplicate-key {:perturb.region/key k}))
    (when (>= (count ms) (:perturb.region/bound r))
      (violate! r :perturb.region/over-bound
                {:perturb.region/key k :perturb.region/bound (:perturb.region/bound r)}))
    (let [ms1 (assoc ms k {:perturb.region/member m :perturb.region/state st})
          n   (count ms1)]
      (cap/transition! 'perturb.region/Region (:perturb.cap/id r) :open :open site)
      (assoc r :perturb.region/members ms1
               :perturb.region/puts (inc (:perturb.region/puts r))
               :perturb.region/peak (if (> n (:perturb.region/peak r))
                                      n (:perturb.region/peak r))))))

(defn- take*
  "Remove the member at `k`, which the caller asserts is at `st`.

  THE ASSERTION IS CHECKED HERE AND NOWHERE ELSE, and that is the whole
  substance of the trade. The static checker sees a ServerConn@st appear from an
  operation that declares it; whether the thing in the region really was at `st`
  is a run-time question, and this is the run-time answer."
  [r k st site]
  (let [e (get (:perturb.region/members r) k)]
    (cond
      (nil? e)
      (do (violate! r :perturb.region/absent
                    {:perturb.region/key k :perturb.region/wanted st})
          [r nil])

      (not= st (:perturb.region/state e))
      (do (violate! r :perturb.region/state-mismatch
                    {:perturb.region/key  k
                     :perturb.region/held (:perturb.region/state e)
                     :perturb.region/wanted st})
          ;; UNDER :report WE HAND IT OVER ANYWAY. That is what makes driver B'
          ;; produce driver B's octets, and it is the honest thing to do: driver
          ;; B performs this operation today with nothing recording it at all.
          (do (cap/transition! 'perturb.region/Region (:perturb.cap/id r) :open :open site)
              [(assoc r :perturb.region/members (dissoc (:perturb.region/members r) k)
                        :perturb.region/takes (inc (:perturb.region/takes r)))
               (:perturb.region/member e)]))

      :else
      (do (cap/transition! 'perturb.region/Region (:perturb.cap/id r) :open :open site)
          [(assoc r :perturb.region/members (dissoc (:perturb.region/members r) k)
                    :perturb.region/takes (inc (:perturb.region/takes r)))
           (:perturb.region/member e)]))))

(defn put-reading!
  "Admit a ServerConn@:reading. CONSUMES the connection: after this call the
  caller does not have it, which is what removes `dangling` and `no-signature`
  from driver B's table write."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                     :absorbs [{:cap 'perturb.http/ServerConn :state :reading
                                :arg 2 :holder-arg 0}]
                     :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r k c] (put* r k c :reading :perturb.region/put-reading))

(defn put-responding!
  "Admit a ServerConn@:responding."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                     :absorbs [{:cap 'perturb.http/ServerConn :state :responding
                                :arg 2 :holder-arg 0}]
                     :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r k c] (put* r k c :responding :perturb.region/put-responding))

(defn take-reading
  "-> [region' conn@:reading]. EMITS a tracked ServerConn, which is what
  removes `untracked-consume` from every operation driver B performs on a member."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                     :produces [{:cap 'perturb.region/Region :state :open :at [0]}]
                     :emits [{:cap 'perturb.http/ServerConn :state :reading
                              :at [1] :holder-arg 0}]}}
  [r k] (take* r k :reading :perturb.region/take-reading))

(defn take-responding
  "-> [region' conn@:responding]."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                     :produces [{:cap 'perturb.region/Region :state :open :at [0]}]
                     :emits [{:cap 'perturb.http/ServerConn :state :responding
                              :at [1] :holder-arg 0}]}}
  [r k] (take* r k :responding :perturb.region/take-responding))

;; --- queries ----------------------------------------------------------------
;;
;; These BORROW the region. They must be annotated for the same reason
;; `clojure.core/assoc` could not be used on a capability: an unannotated callee
;; receiving one is `no-signature`.

(defn skip
  "THE IDENTITY EDGE, and it is a cost rather than a convenience.

  A branch that consumes the region in one arm and not the other is a `join`
  rejection: \"may or may not have been moved\" is not a mode. Affine systems
  all need this — it is why Rust makes a move explicit in both arms — and the
  honest place to put it is an operation that consumes and produces the region
  and does nothing. It is NOT a hole: it cannot manufacture a member, and it
  cannot change a state."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r] r)

(defn member?
  {:perturb.cap/op {:borrows [{:cap 'perturb.region/Region :state :open :arg 0}]}}
  [r k] (contains? (:perturb.region/members r) k))

(defn state-of
  {:perturb.cap/op {:borrows [{:cap 'perturb.region/Region :state :open :arg 0}]}}
  [r k]
  (let [e (get (:perturb.region/members r) k)]
    (if (nil? e) nil (:perturb.region/state e))))

(defn size
  {:perturb.cap/op {:borrows [{:cap 'perturb.region/Region :state :open :arg 0}]}}
  [r] (count (:perturb.region/members r)))

(defn stats
  {:perturb.cap/op {:borrows [{:cap 'perturb.region/Region :state [:open :closed] :arg 0}]}}
  [r]
  {:perturb.region/tag   (:perturb.region/tag r)
   :perturb.region/size  (count (:perturb.region/members r))
   :perturb.region/puts  (:perturb.region/puts r)
   :perturb.region/takes (:perturb.region/takes r)
   :perturb.region/peak  (:perturb.region/peak r)
   :perturb.region/bound (:perturb.region/bound r)})

(cap/annotate-op! (var open)             (:perturb.cap/op (meta (var open))))
(cap/annotate-op! (var close!)           (:perturb.cap/op (meta (var close!))))
(cap/annotate-op! (var put-reading!)     (:perturb.cap/op (meta (var put-reading!))))
(cap/annotate-op! (var put-responding!)  (:perturb.cap/op (meta (var put-responding!))))
(cap/annotate-op! (var take-reading)     (:perturb.cap/op (meta (var take-reading))))
(cap/annotate-op! (var take-responding)  (:perturb.cap/op (meta (var take-responding))))
(cap/annotate-op! (var skip)             (:perturb.cap/op (meta (var skip))))
(cap/annotate-op! (var member?)          (:perturb.cap/op (meta (var member?))))
(cap/annotate-op! (var state-of)         (:perturb.cap/op (meta (var state-of))))
(cap/annotate-op! (var size)             (:perturb.cap/op (meta (var size))))
(cap/annotate-op! (var stats)            (:perturb.cap/op (meta (var stats))))
