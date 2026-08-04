(ns perturb.dbtx
  "A transaction handle whose `begin` has THREE destinations, chosen at run time.

  WHERE THIS COMES FROM. Not from perturb. `casselc/db`'s `verified-sqlite-begin!`
  (PRs #1-#3, read for PERTURB-DESIGN E26) is a typestate machine written by hand
  in another language: when `execute! \"BEGIN\"` throws, the physical transaction
  may or may not have opened, and eight outcomes are resolved by three probes of
  `sqlite3_get_autocommit`. E26 finding 7 records what that costs perturb's
  declaration language:

    R0        BEGIN took effect                                :idle -> :open
    R1, R2    proven not to have taken effect, or rolled back
              and re-probed clean                              :idle -> :idle
    R3-R7     rollback lied, rollback errored, a probe threw,
              or someone else's transaction holds the engine   :idle -> :poisoned

  The `:from` side was never the gap — `state-ok?` has taken a collection since
  E5 and `perturb.http/close-conn!` ships `:from [:reading :responding :writing]`.
  The gap is the `:to` side: ONE operation, THREE destinations, and no way to say
  so. This namespace says so, and `perturb.dbtxcorpus` is what the checker does
  about it.

  HOW A SUM IS DECLARED. Several `:transitions` entries sharing an `:op` AND a
  `:from`, with different `:to`s — `begin` is three entries and `abort!` is two.
  Nothing in `perturb.cap` ever rejected that shape; what rejected it was the
  checker's primitive table, which was keyed `[capability operation]` with
  `assoc`, so the second entry silently overwrote the first. The annotation's
  `:produces` `:state` is then a COLLECTION, and `check-annotation-consistency!`
  requires it to equal the group's `:to`s AS A SET.

  HOW A SUM IS ELIMINATED. `:perturb.cap/discriminator` names a predicate and
  what its two arms know:

    {:op 'perturb.dbtx/autocommit? :arg 0 :true :idle :false [:open :poisoned]}

  In an `if` whose test is that call on a capability in a sum state, the checker
  intersects the sum with each arm's states before walking the arm. Two nested
  discriminators resolve a three-way sum. NOTHING CHECKS THE DISCRIMINATOR: like
  every `:transitions` entry, it is an axiom, and `autocommit?`'s body is
  believed rather than read. It is the same class of trust as `:from`/`:to`
  themselves — see `perturb.check/report-limits` item 14.

  WHAT IS NOT DECLARED, AND WHY. E26 finding 7 prints the machine with a
  `:pending-cleanup` state. It is omitted here for the reason `perturb.nrepl`
  deleted `:created`: no operation is legal from it and no value is ever in it,
  so it would be a name for the machine's absence rather than a state of it. If a
  cleanup-inspection edge is ever written, it comes back with an inhabitant.

  WHAT THIS IS NOT. Not SQLite, not a database, not I/O. `begin`'s destination is
  read off an outcome recorded by `connect`, because the point of the fixture is
  which programs the CHECKER accepts, and a real probe would make the corpus a
  test of SQLite. The runtime outcome is exactly what a real driver's three
  probes would have decided, and E26's own note applies: the honest positive
  control for a branch like R3 is a simulated driver, not an injected wrapper."
  (:require [perturb.cap :as cap]))

;; --- the capability declaration, as data ------------------------------------

(def tx-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.dbtx/Tx
       :perturb.cap/doc        "A database transaction handle: a connection plus the depth bookkeeping."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined

       :perturb.cap/typestate
       {:states   [:idle :open :poisoned :closed]
        :initial  :idle
        ;; AS E26 FINDING 7 PRINTS IT. `:poisoned` is terminal: the handle is
        ;; unusable and nothing further is owed to it. E26 finding 8 reads the
        ;; same code the other way — `ensure-transaction-usable!` rejects every
        ;; operation except cleanup inspection and `close`, which is an
        ;; obligation to close and therefore NOT terminal. The two readings
        ;; disagree and this one follows the declared machine; which is right is
        ;; a question about `casselc/db`, not about the checker. What hangs on
        ;; it is `abort!` below: with `:poisoned` non-terminal its `:to` would be
        ;; a sum with a non-terminal member, and the middle arm of
        ;; `perturb.dbtxcorpus/begin-with-full-case-split` would leak.
        :terminal [:poisoned :closed]

        :transitions
        ;; THE SUM. Three entries, one `:op`, one `:from`, three `:to`s.
        [{:op 'perturb.dbtx/connect  :from nil   :to :idle}
         {:op 'perturb.dbtx/begin    :from :idle :to :open}      ; R0
         {:op 'perturb.dbtx/begin    :from :idle :to :idle}      ; R1, R2
         {:op 'perturb.dbtx/begin    :from :idle :to :poisoned}  ; R3-R7
         {:op 'perturb.dbtx/commit!  :from :open :to :idle}
         {:op 'perturb.dbtx/unwind!  :from :idle :to :closed}
         ;; A SECOND SUM, AND BOTH ITS MEMBERS ARE TERMINAL. Releasing a poisoned
         ;; handle either disposes of the stranded transaction or does not; the
         ;; caller owes nothing either way, so no case split is needed to consume
         ;; the result — and there is nothing left to consume it with.
         {:op 'perturb.dbtx/abort!   :from :poisoned :to :closed}
         {:op 'perturb.dbtx/abort!   :from :poisoned :to :poisoned}
         ;; THE TOTALLY-ADMITTING DESTRUCTOR. `:from :any` admits every summand,
         ;; so a Tx in a sum state may be handed to it with no case split at all.
         {:op 'perturb.dbtx/close!   :from :any  :to :closed}]}

       ;; --- the case split, declared ------------------------------------------
       ;;
       ;; A predicate, the argument it interrogates, and what each arm knows.
       ;; `:true` and `:false` are STATES OR COLLECTIONS OF STATES, intersected
       ;; with whatever sum the capability is actually in — so `poisoned?`'s
       ;; `:false [:idle :open]` narrows [:open :poisoned] to :open and the
       ;; three-way sum falls to a single state after two nested tests.
       :perturb.cap/discriminator
       [{:op 'perturb.dbtx/autocommit? :arg 0 :true :idle     :false [:open :poisoned]}
        {:op 'perturb.dbtx/poisoned?   :arg 0 :true :poisoned :false [:idle :open]}]

       ;; The abstraction boundary (E17): these read the handle's concrete map.
       ;; The two discriminating predicates are here for the same reason
       ;; `perturb.nrepl/state` is — `(:perturb.dbtx/state t)` is not a
       ;; capability operation and cannot be given a signature.
       :perturb.cap/representation
       ['perturb.dbtx/tx 'perturb.dbtx/tx-id 'perturb.dbtx/tx-state
        'perturb.dbtx/autocommit? 'perturb.dbtx/poisoned?
        'perturb.dbtx/cleanup-errors]

       ;; §1.3 obligations. Hand-written; nothing discharges them.
       :perturb.cap/obligations
       '[{:name every-begin-lands-in-one-declared-destination
          :formula (forall [e ledger]
                     (implies (= :begin (:op e))
                              (member (:to e) [:open :idle :poisoned])))}
         {:name poison-carries-its-cause
          :formula (forall [e ledger]
                     (implies (= :poisoned (:to e)) (not (empty? cleanup-errors))))}]
       :perturb.cap/locality :dropped-by-design})))

;; --- the handle's concrete value --------------------------------------------

(def ^:private id-counter (atom 0))

(defn- fresh-id [path] (str "perturb-tx-" path "-" (swap! id-counter inc)))

(defn tx
  "Build the concrete handle. Inside the representation."
  [id state outcome errors]
  {:perturb.cap/capability 'perturb.dbtx/Tx
   :perturb.cap/id         id
   :perturb.dbtx/state     state
   :perturb.dbtx/outcome   outcome
   :perturb.dbtx/errors    errors})

(defn tx-id
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]}}
  [t] (:perturb.cap/id t))

(defn tx-state
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]}}
  [t] (:perturb.dbtx/state t))

(defn cleanup-errors
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]}}
  [t] (:perturb.dbtx/errors t))

;; --- the two discriminating predicates --------------------------------------
;;
;; They BORROW: a predicate that consumed its argument could not be the test of
;; an `if` whose arms then use it. `:state :any` because the whole point is that
;; the state is not known when the predicate is called.

(defn autocommit?
  "R6's probe, in miniature: is the engine in autocommit — i.e. is there no
  transaction open on this handle? `:true :idle`."
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]}}
  [t] (= :idle (:perturb.dbtx/state t)))

(defn poisoned?
  "Did the recovery fail to resolve the uncertainty? `:true :poisoned`."
  {:perturb.cap/op {:borrows [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]}}
  [t] (= :poisoned (:perturb.dbtx/state t)))

;; --- the operations ---------------------------------------------------------

(defn connect
  "Open a handle. The CREATING edge: `:from nil`, consuming nothing.

  `outcome` is what a real driver's probes would have decided about the `BEGIN`
  that has not happened yet — see the namespace note on why it is recorded here
  rather than probed."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.dbtx/Tx :state :idle}]}}
  [path outcome]
  (let [id (fresh-id path)]
    (cap/transition! 'perturb.dbtx/Tx id nil :idle :perturb.dbtx/connect)
    (tx id :idle outcome [])))

(defn begin
  "THE OPERATION THIS WHOLE FIXTURE EXISTS FOR. One call, three destinations.

  The annotation's `:produces` `:state` is a COLLECTION equal as a set to the
  three declared `:to`s. The ledger still records ONE concrete destination per
  run — the sum is what the checker must reason about before the run, not what
  the run does."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtx/Tx :state :idle :arg 0}]
                    :produces [{:cap 'perturb.dbtx/Tx :state [:open :idle :poisoned]}]}}
  [t]
  (let [o  (:perturb.dbtx/outcome t)
        st (cond (= :took-effect o)  :open      ; R0
                 (= :proven-clean o) :idle      ; R1, R2
                 :else               :poisoned) ; R3-R7
        es (if (= :poisoned st)
             ["BEGIN threw and the post-probe still reports a transaction open"]
             [])]
    (cap/transition! 'perturb.dbtx/Tx (tx-id t) :idle st :perturb.dbtx/begin)
    (tx (tx-id t) st o es)))

(defn commit!
  "Legal from :open ALONE. This is the operation that makes the sum bite: with no
  case split it is `state-unresolved`, because two of `begin`'s three
  destinations do not admit it."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtx/Tx :state :open :arg 0}]
                    :produces [{:cap 'perturb.dbtx/Tx :state :idle}]}}
  [t]
  (cap/transition! 'perturb.dbtx/Tx (tx-id t) :open :idle :perturb.dbtx/commit)
  (tx (tx-id t) :idle (:perturb.dbtx/outcome t) (:perturb.dbtx/errors t)))

(defn unwind!
  "Legal from :idle alone: there is no transaction to end, so release the handle."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtx/Tx :state :idle :arg 0}]
                    :produces [{:cap 'perturb.dbtx/Tx :state :closed}]}}
  [t]
  (cap/transition! 'perturb.dbtx/Tx (tx-id t) :idle :closed :perturb.dbtx/unwind)
  (tx (tx-id t) :closed (:perturb.dbtx/outcome t) (:perturb.dbtx/errors t)))

(defn abort!
  "Legal from :poisoned alone, and its own `:to` is a SUM of two TERMINAL states.
  Whether the stranded transaction was disposed of or not, nothing further is
  owed, so the result needs no case split — which is the terminal-sum rule doing
  visible work."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtx/Tx :state :poisoned :arg 0}]
                    :produces [{:cap 'perturb.dbtx/Tx :state [:poisoned :closed]}]}}
  [t]
  (let [st (if (empty? (:perturb.dbtx/errors t)) :closed :poisoned)]
    (cap/transition! 'perturb.dbtx/Tx (tx-id t) :poisoned st :perturb.dbtx/abort)
    (tx (tx-id t) st (:perturb.dbtx/outcome t) (:perturb.dbtx/errors t))))

(defn close!
  "The destructor, legal from EVERY state — `sqlite3_close_v2` disposes of the
  handle whatever it is holding. `:from :any` admits every member of any sum, so
  a Tx straight out of `begin` may be handed to it with no case split at all."
  {:perturb.cap/op {:consumes [{:cap 'perturb.dbtx/Tx :state :any :arg 0}]
                    :produces [{:cap 'perturb.dbtx/Tx :state :closed}]}}
  [t]
  (cap/transition! 'perturb.dbtx/Tx (tx-id t) (:perturb.dbtx/state t) :closed
                   :perturb.dbtx/close)
  (tx (tx-id t) :closed (:perturb.dbtx/outcome t) (:perturb.dbtx/errors t)))

;; register the annotations for the checker
(cap/annotate-op! (var tx-id)          (:perturb.cap/op (meta (var tx-id))))
(cap/annotate-op! (var tx-state)       (:perturb.cap/op (meta (var tx-state))))
(cap/annotate-op! (var cleanup-errors) (:perturb.cap/op (meta (var cleanup-errors))))
(cap/annotate-op! (var autocommit?)    (:perturb.cap/op (meta (var autocommit?))))
(cap/annotate-op! (var poisoned?)      (:perturb.cap/op (meta (var poisoned?))))
(cap/annotate-op! (var connect)        (:perturb.cap/op (meta (var connect))))
(cap/annotate-op! (var begin)          (:perturb.cap/op (meta (var begin))))
(cap/annotate-op! (var commit!)        (:perturb.cap/op (meta (var commit!))))
(cap/annotate-op! (var unwind!)        (:perturb.cap/op (meta (var unwind!))))
(cap/annotate-op! (var abort!)         (:perturb.cap/op (meta (var abort!))))
(cap/annotate-op! (var close!)         (:perturb.cap/op (meta (var close!))))
