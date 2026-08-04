(ns perturb.check
  "A STATIC capability checker over real Jolt IR. It says no.

  This is the first thing in perturb that rejects a program. Everything before it
  — `perturb.cap`'s declarations, the operation annotations, the ledger — is
  description. This namespace reads the same declarations as a SPECIFICATION and
  refuses code that violates them, before the code runs.

  WHERE THE RULES COME FROM. Not from here. `docs/research/prototypes/` holds a
  rule set that was validated against artifacts perturb did not author —
  `mode_checker.py` against jolt-hako's `proofs/prolog/queries.json`,
  `equivalence.py` differentially against `ownership.pl` to depth 10 with zero
  disagreements, `controlflow.py` and `multicap.py` as probes designed to break
  it. The judgements below are ports of those, named after the prototype and the
  finding that produced them:

    LIVE       use of a consumed binding is use-after-move   (multicap.Store.live)
    TYPESTATE  an operation is legal only from declared states (E5; move_to's
               `sources` frozenset, here read off the declared machine)
    AFFINE     capabilities bind affinely: `(let [d c] …)` MOVES c, there is no
               non-moving alias                              (E6 probe 2)
    JOIN       both arms of an `if` are checked from the same environment and
               the join must AGREE                           (E6 probe 1)
    PRESERVE   a loop body must be environment-preserving at every back edge
                                                             (E6 probe 1)
    LINEAR     `linearity :once` + a declared terminal state: a capability that
               is still live and non-terminal when its binding goes out of scope
               is a leak
    UNRESOLVED one operation may declare several `:to`s — a SUM — and a
               capability in a sum state may be used only by an operation that
               admits EVERY member, or after a declared discriminator has
               eliminated it at an `if` (E26 finding 7; report-limits item 14)
    OBLIGATION the debt to run a destructor is TRACKED SEPARATELY from the
               state and from the name. A transition may declare an
               `:obligation` delta; with none declared it is derived from `:to`
               being terminal, which is what the leak rule used to test
               directly. So a TERMINAL state may be ABSORBING — obligation
               discharged, NAME STILL ALIVE, only the destructor and the
               observers legal — and an edge out of a discharged state that does
               not discharge is refused where it is written (E30 finding 1,
               E33's conceptual correction and its side condition; items 16, 18)
    RESULT     the transition relation is
               `(capability, operation, source-state, result-label) ->
                destination + obligation delta`. An annotation that OMITS
               `:state` is READ OFF that relation at each call site, so a
               destination may depend on its SOURCE — which the
               operation-keyed relation could not express (E30 finding 3, E33;
               item 17)
    CANCELLED  a capability may declare one of its own states CANCELLED; from
               it the DESTRUCTOR is the only legal operation, and reaching it
               discharges NOTHING — a cancelled capability that never reaches
               its destructor is still a leak. This is §4.6's cancellation
               piece 5, and it discharges ONE of Fowler's two quandaries: no
               peer is notified (E26 finding 8, scoped by E27; item 15)
    SHAREABLE  the classifier for a freely duplicable value is an INTERFACE
               property, not an allocation property. A value may be contracted
               and weakened when aliases cannot observe mutation, copying
               cannot duplicate a finalization obligation, discarding cannot
               leak one, and EVERY TRANSITIVELY REACHABLE EXPOSED COMPONENT is
               itself unrestricted — or is sealed behind an observationally
               immutable interface. `perturb.share` decides that; this
               namespace builds the profile from its own abstract value at the
               two sites where a value is duplicated or stored (a closure
               capture, and a composite) and prints the decision instead of
               asserting a reason. E37's reframe: the transitivity clause is
               §4.6's root cause and not a second tier, because an immutable
               shell holding a capability duplicates ROUTES to it (item 19)

  ONE JUDGEMENT IS NOT A MODE JUDGEMENT AT ALL, AND IS THE OTHER TIER:

    REFINE     an EDGE of a typestate machine may carry a §1.3 arithmetic side
               condition (`perturb.cap/refinements`), discharged against ghost
               state carried along with the capability. Three outcomes: proved,
               REFUTED with a counterexample, and REFUSED — the last is a
               rejection with its own diagnostic kind, never an accept. What is
               decided and what is refused is stated exactly in `perturb.refine`
               and in `report-limits` item 10. E18 finding 3.

  ONE JUDGEMENT IS NOT A PORT, AND IS FLAGGED AS SUCH. The Python prototypes have
  no non-local exit, so they never had to say what `recur`/`throw` do to a join.
  This checker treats a path ending in `recur` or `throw` as UNREACHABLE at the
  join (bottom), so joining with it is the identity. Without that every ordinary
  `loop` fails the join rule at its own back edge. It is the standard treatment
  and it is sound, but it is a judgement this checker adds, not one the validated
  rule set supplied.

  WHAT IT READS. `perturb.cap/checker-input` — the declarations and the operation
  annotations, exactly as emitted, as the specification. It does not re-derive
  them from the source. And real Jolt IR, captured by `perturb.ir` from the
  compile spine — not source forms, not a model.

  A SECOND SET OF RULES, ON THE DECLARATIONS THEMSELVES. The judgements above are
  about a program. `annotation-faults` and `check-annotation-consistency!` are
  about a DECLARATION: whether an annotation can be read at all, and whether it
  agrees with the machine it claims to be an edge of. They are separate because
  E18 found four defects that were not in the flow rules — the flow rules met a
  second protocol with two capabilities and survived unchanged — but in what the
  declaration language could say. They are gated by `run-declaration-corpus`
  against fixtures that name no code, because three of the four had no artifact
  except a diagnostic raised against an axiom, which was collected and discarded
  for two sections.

  WHAT IT TRUSTS — two classes, and the second is larger than it looks. An
  operation in a capability's declared `:transitions` is a PRIMITIVE of the state
  machine, and one in its `:representation` is INSIDE the capability's
  implementation. Both work below the abstraction the modes describe, so the
  checker believes their annotations rather than checking their bodies. The first
  class is `mode_checker.py`'s posture exactly — RULES are axioms, `check` checks
  sequences. The second is new, and it was not designed: positioned `:at` specs
  made `perturb.nrepl`'s protocol layer checkable, and the moment it was, the
  implementation layer under it started failing on `(:perturb.nrepl/buf c)` —
  which is not a capability operation and cannot be given a signature. Naming
  that boundary by listing operations is a placeholder for a module system
  (PERTURB-DESIGN E17). Everything else is checked, including a function that
  carries an annotation but is neither.

  WHAT IT CANNOT SEE — read this before believing an `ok`. See `report-limits`.")

(require '[perturb.cap :as cap])
(require '[perturb.ir :as pir])
(require '[perturb.script :as script])
(require '[perturb.effect :as fx])
(require '[perturb.octet :as o])
(require '[perturb.refine :as ref])
(require '[perturb.share :as sh])
(require '[clojure.string :as str])

;; --- diagnostics ------------------------------------------------------------

(def diagnostics (atom []))

(defn- report! [d] (swap! diagnostics conj d) nil)

(defn- render-one [d]
  (str "  " (name (:kind d))
       (if (:cap d) (str "  " (:cap d)) "")
       "\n"
       (reduce (fn [acc l] (str acc "    " l "\n")) "" (:detail d))))

(defn render [ds]
  (reduce (fn [acc d] (str acc (render-one d))) "" ds))

;; --- the specification, read from perturb.cap -------------------------------

(defn spec-from
  "The checker's specification, built from one `cap/checker-input`-shaped map.
  Nothing here is derived from source: the declarations and annotations are taken
  as given. Split out from `spec` so the declaration-level rules can be run
  against hand-built fixtures that name no code (see `run-declaration-corpus`).

  THE PRIMITIVE TABLE IS KEYED BY `[capability operation]`, NOT BY OPERATION.
  Keying by operation alone meant a second declaration naming the same operation
  OVERWROTE the first, so an operation belonged to at most one capability and
  `perturb.http`'s 10 declared transition entries were seen as 9 primitives.
  `accept` (mint a ServerConn from a Listener), `respond-begin` (mint a
  ResponseBody from a ServerConn) and `body-finish!` (end a ResponseBody, return
  the ServerConn) each advance TWO machines, and none of them could be declared
  (PERTURB-DESIGN E18 finding 1(a)).

  The value stored is the DECLARED TRANSITION ENTRY ITSELF with `:cap` added, not
  a three-key summary of it. Any further key a declaration puts on a transition —
  a side condition, a refinement — travels with it and needs no change here.

  A SUM `:to`: THE VALUE IS A VECTOR OF ENTRIES, NOT ONE ENTRY. It used to be one
  entry, stored with `assoc`, so a machine declaring several `:to`s for one
  `[capability operation]` had all but the last SILENTLY overwritten — which is
  what made `casselc/db`'s three-destination `begin` undeclarable (E26 finding
  7). Nothing in `perturb.cap` ever rejected the shape; this table lost it.
  Grouping by `[capability operation from]` is done where it is used, in
  `check-annotation-consistency!`, because that is the only rule that needs the
  group rather than the edges."
  [ci]
  (let [decls (:perturb.cap/declarations ci)
        ops   (:perturb.cap/operations ci)
        prims (reduce (fn [acc e]
                        (let [cname (first e)
                              ts    (:perturb.cap/typestate (second e))]
                          (reduce (fn [a t]
                                    (update a [cname (:op t)]
                                            (fn [v] (conj (or v []) (assoc t :cap cname)))))
                                  acc (:transitions ts))))
                      {} decls)
        ;; opsym -> every declared edge that operation is, across all machines,
        ;; in a stable order. An operation with no entry here is not a primitive.
        by-op (reduce (fn [acc k]
                        (update acc (second k) (fn [v] (into (or v []) (get prims k)))))
                      {} (sort-by str (keys prims)))
        ;; DISCRIMINATORS (E26 finding 7). opsym -> the declared predicates that
        ;; narrow a sum state at an `if`, each carrying the capability it
        ;; discriminates. Empty for every capability that declares none, which is
        ;; every capability but one.
        discr (reduce (fn [acc e]
                        (let [cname (first e)]
                          (reduce (fn [a d]
                                    (update a (:op d)
                                            (fn [v] (conj (or v []) (assoc d :cap cname)))))
                                  acc (:perturb.cap/discriminator (second e)))))
                      {} decls)
        ;; Operations INSIDE the capability's implementation: they construct or
        ;; read its concrete value, below the abstraction the modes describe.
        ;; Their bodies are axioms for exactly the reason a transition's body is.
        ;; Listing them by name is a placeholder for a module boundary, which
        ;; §1.2 does not have — see report-limits.
        repr  (reduce (fn [acc e]
                        (reduce (fn [a s] (assoc a s (first e)))
                                acc (:perturb.cap/representation (second e))))
                      {} decls)]
    {:declarations decls :operations ops
     :primitives prims :transitions-of by-op :representation repr
     :discriminators discr
     ;; REFINEMENT (E18 finding 3). [capability operation] -> a VECTOR of the
     ;; refinements on that operation's edges. Keyed by the PAIR, which is also
     ;; how `prims` is keyed — the two fixes met here and agreed — and holding a
     ;; COLLECTION, which is the same fix `prims` got for E26 finding 7 and
     ;; which `cap/refinements` had not had until now (old item 14(j)).
     :refinements (cap/refinements decls)}))

(defn spec
  "The checker's specification, built from `cap/checker-input`."
  []
  (spec-from (cap/checker-input)))

(defn- decl-of [sp c] (get (:declarations sp) c))

;; --- states, and the one that is a SUM ---------------------------------------
;;
;; A capability's state is a single keyword, or nil, or — since E26 finding 7 —
;; a SUM: the set of states an operation with several declared `:to`s may have
;; left it in, not knowable until run time. The two sides of a spec entry read a
;; collection differently, and the asymmetry is the feature:
;;
;;   :consumes / :borrows  `:state [:a :b]`  = ANY OF. The operation is legal
;;                         from either, exactly as move_to's `sources` frozenset
;;                         has meant since E5. Unchanged.
;;   :produces             `:state [:a :b]`  = A SUM. The capability is now in
;;                         one of these and the program does not know which.
;;
;; Every state below that is not a collection behaves exactly as it did before
;; this existed, which is what makes the feature opt-in per operation.

(defn- sum? [s] (coll? s))

(defn- sum-members [s] (if (coll? s) (vec (distinct s)) [s]))

(defn- norm-state
  "Canonical form of a produced state. A single state stays itself; a collection
  becomes a SORTED vector so that two sums with the same members are `=`; and a
  ONE-MEMBER collection collapses to the state itself, so a machine with one
  `:to` can never accidentally acquire a sum."
  [s]
  (if (coll? s)
    (let [v (vec (sort-by str (distinct s)))]
      (if (= 1 (count v)) (first v) v))
    s))

(defn- admits?
  "Does a spec entry's `:state` admit ONE state? `:any`, a collection, or a
  single state — the direct analogue of move_to's `sources` frozenset in
  mode_checker.py."
  [want got]
  (cond
    (= :any want) true
    (coll? want)  (contains? (set want) got)
    :else         (= want got)))

(defn- state-ok?
  "Does a spec entry's `:state` admit the state a capability is ACTUALLY in?

  For a single state this is `admits?` and nothing has changed. For a SUM the
  rule is TOTAL: every member must be admitted, because the program does not
  know which one it has. That is what makes a `:from :any` destructor consumable
  with no case split and an `:open`-only operation not."
  [want got]
  (every? (fn [m] (admits? want m)) (sum-members got)))

(defn- unadmitted
  "The members of a sum the entry does NOT admit, for the diagnostic."
  [want got]
  (vec (remove (fn [m] (admits? want m)) (sum-members got))))

(defn- terminal?
  "A SUM IS TERMINAL ONLY IF EVERY MEMBER IS. A capability that might be in a
  non-terminal state still owes its obligation, and the checker cannot tell
  which member it got."
  [sp c st]
  (let [ts   (:perturb.cap/typestate (decl-of sp c))
        t    (:terminal ts)
        tset (if (coll? t) (set t) #{t})]
    (every? (fn [m] (contains? tset m)) (sum-members st))))

(defn- want-str [want] (if (coll? want) (str (vec want)) (str want)))

;; --- THE OBLIGATION, SEPARATED FROM THE STATE (E33) --------------------------
;;
;; `terminal?` above answers "is this state one the machine calls terminal". It
;; used to be asked as if it also answered "does this capability still owe its
;; destructor", and E33 is that those are two questions. They are now asked
;; separately: a live capability carries `:owes`, an edge carries an obligation
;; DELTA, and `terminal?` is only the DERIVATION for an edge that declares none.
;;
;; DERIVATION IS EXACTLY THE OLD BEHAVIOUR. An edge with no `:obligation`
;; discharges iff every member of its `:to` is terminal, which is the test
;; `check-scope-exit` used to apply to the state directly. So every declaration
;; written before this key existed produces the same verdicts, and that is
;; checked by three corpora rather than asserted.

(defn- edge-delta
  "The obligation delta of one declared edge: `:acquire`, `:retain` or
  `:discharge`. Declared if `:obligation` is present, DERIVED from `:to` being
  terminal otherwise."
  [sp c t]
  (or (get t cap/obligation-key)
      (if (terminal? sp c (norm-state (:to t))) :discharge :retain)))

(defn- apply-delta
  "One delta against a capability's current `:owes`."
  [owes d]
  (cond (= :discharge d) false
        (= :acquire d)   true
        :else            owes))

(defn- owes?
  "Does this live capability still owe its destructor? Falls back to the state
  test for a capability minted before `:owes` was recorded, so the two can never
  disagree about a declaration that says nothing."
  [sp lc]
  (if (contains? lc :owes)
    (:owes lc)
    (not (terminal? sp (:cap lc) (:state lc)))))

;; --- (capability, operation, source-state, result-label) -> to + delta --------
;;
;; E33's primitive relation, read off the machine at a CALL SITE. This is what
;; makes the destination a function of the SOURCE rather than of the operation
;; alone, and it is the whole of E30 finding 3's fix: the annotation no longer
;; has to repeat a `:to` that depends on a `:from` it cannot see.

(defn- edges-applicable
  "The declared edges of `[cap opsym]` whose `:from` admits the state the
  capability is ACTUALLY in. `state-ok?` and not `admits?`, so a capability in a
  sum state selects only edges that admit every member."
  [sp c opsym state]
  (vec (filter (fn [t] (state-ok? (:from t) state))
               (get (:primitives sp) [c opsym]))))

(defn- machine-destination
  "Where the machine says `[cap opsym]` lands a capability that is ACTUALLY in
  `state`, or nil if no declared edge applies.

  RESULT LABELS COLLAPSE WHEN THEY AGREE. Every applicable edge is a
  `(source, label)` pair; the destination is the union of their `:to`s, so two
  labels sharing one destination produce a SINGLE state and no sum at all —
  which is `close!`'s `:won`/`:lost` and why an idempotent close is not a
  second close. Labels that disagree produce the sum, and the sum machinery is
  the fallback exactly as E33 says it should be.

  THE DELTA IS THE CONSERVATIVE MEET: `:discharge` only when every applicable
  edge discharges, `:acquire` if any acquires. The call takes one edge and which
  one is not knowable until run time, so a capability is discharged only if it
  is discharged whichever label came back."
  [sp c opsym state]
  (let [es (edges-applicable sp c opsym state)]
    (when (seq es)
      (let [ds (set (map (fn [t] (edge-delta sp c t)) es))]
        {:to        (norm-state (vec (mapcat (fn [t] (sum-members (:to t))) es)))
         :delta     (cond (= ds #{:discharge}) :discharge
                          (contains? ds :acquire) :acquire
                          :else :retain)
         :declared? (boolean (some (fn [t] (contains? t cap/obligation-key)) es))
         :labels    (vec (distinct (remove nil? (map (fn [t] (get t cap/result-key)) es))))
         :edges     (vec es)}))))

(defn- derived-state?
  "A `:consumes` / `:borrows` / `:produces` entry that OMITS `:state` asks the
  machine instead of repeating it. That is the opt-in: every entry written
  before this existed carries a `:state` and is read exactly as before."
  [entry] (not (contains? entry :state)))

(defn- in-place?
  "A `:produces` entry carrying `:arg` is an IN-PLACE produce: the capability
  does not travel in the result, it changes state where the named argument
  stands. §4.6's in-place item, E30 finding 2, tally row 50."
  [entry] (contains? entry :arg))

(defn- declared-sources
  "Every source state an operation declares an edge from, for one capability,
  with `:from` collections FLATTENED — a source collection is sugar for one edge
  per member, so this is the set of states the operation is legal in."
  [sp c opsym]
  (vec (distinct (mapcat (fn [t] (sum-members (:from t)))
                         (get (:primitives sp) [c opsym])))))

(defn- produced-ok?
  "The `:produces` side of a comparison, where a collection means a SUM and not
  `any of`. Two sums agree when their members agree as a SET; a sum never
  matches a single state, in either direction."
  [want got]
  (if (or (sum? want) (sum? got))
    (= (set (sum-members want)) (set (sum-members got)))
    (state-ok? want got)))

;; --- CANCELLED: MUST_CLOSE as a state (§4.6 piece 5, E26 finding 8, E27) -----
;;
;; A capability may declare ONE of its own states to be the CANCELLED state:
;;
;;   :perturb.cap/cancelled :poisoned
;;
;; and then, by construction rather than by a new rule:
;;
;;   - an operation that lands there is a cancellation. `perturb.dbtx/abort!`
;;     is `:from [:idle :open] :to :poisoned`, and `begin`'s R3-R7 summand
;;     reaches the same state from the other direction — the runtime's
;;     uncertainty and the developer's decision arrive at one place.
;;   - EVERY OPERATION EXCEPT THE DESTRUCTOR IS REFUSED, because no other
;;     operation's `:from` admits that state. That needs no new rule at all;
;;     what it gets here is a better DIAGNOSTIC (`cancelled-use`), because
;;     `typestate` is true and unhelpful when the cause is a cancellation.
;;   - FAILING TO REACH THE DESTRUCTOR IS STILL A LEAK, because the cancelled
;;     state is not terminal — enforced at the declaration, below. If reaching
;;     it discharged the obligation the mechanism would be silent discard with
;;     extra steps, which is exactly what Fowler §1.3 rejects.
;;
;; The destructor needs no case split: `:from :any` admits every summand, which
;; is the sum `:to` machinery of E26 finding 7 doing the work that would
;; otherwise need a `cancel` term per capability class.
;;
;; WHAT IT DOES NOT DO IS THE HEADLINE — see `report-limits` item 15. No peer is
;; notified. This is not cancellation in EGV's sense.

(defn- cancelled-state-of
  "The state this capability declares CANCELLED, or nil. nil for every
  capability that does not opt in, which is every capability but one."
  [sp c] (get (decl-of sp c) cap/cancelled-key))

(defn- edges-of [sp c] (vec (:transitions (:perturb.cap/typestate (decl-of sp c)))))

(defn- edges-from
  "Every declared edge whose `:from` admits one concrete state."
  [sp c st] (vec (filter (fn [t] (admits? (:from t) st)) (edges-of sp c))))

(defn- destructor-edge?
  "An edge is a DESTRUCTOR edge when every member of its `:to` is terminal:
  after it, nothing is owed. That is the whole definition, and it is read off
  the machine rather than declared separately, so it cannot drift from it."
  [sp c t] (terminal? sp c (norm-state (:to t))))

(defn- destructor-lines
  "What a reader may still DO with a cancelled capability, printed from the
  declaration rather than invented."
  [sp c st]
  (let [ds (filter (fn [t] (destructor-edge? sp c t)) (edges-from sp c st))]
    (if (empty? ds)
      [(str "no destructor is declared out of " st " — see the declaration rule")]
      (into ["the only operation(s) this state admits, and they all dispose of it:"]
            (map (fn [t] (str "  " (:op t) "   " (want-str (:from t)) " -> "
                              (want-str (:to t))))
                 ds)))))

(defn- cancelled-leak-lines
  "The extra lines a `dangling` carries when the capability is sitting in its
  declared cancelled state. REACHING THE CANCELLED STATE DISCHARGES NOTHING."
  [sp c st]
  (concat
    [(str st " is the DECLARED CANCELLED state of " c ", and it is NOT terminal:")
     "cancelling a capability records that its protocol was abandoned; it does"
     "not dispose of it, and a mechanism in which it did would be silent discard"
     "with extra steps (Fowler §1.3 rejects exactly that)"]
    (destructor-lines sp c st)))

;; --- values -----------------------------------------------------------------
;;
;; The abstract domain has exactly one composite: a TUPLE. That is not a
;; simplification for its own sake — it is the shape §1.2's positioned
;; :consumes / :produces can name, and nothing else here can be. A capability
;; entering a map or a set is still an escape, because no annotation can say
;; where it went.

(def ^:private OPAQUE {:v :opaque})

(defn- tuple [items] {:v :tuple :items (vec items)})

(defn- nth' [v i] (if (and v (< i (count v))) (nth v i) nil))

(def ^:private bid-counter (atom 0))
(defn- fresh-bid [] (swap! bid-counter inc))

(defn- path-str [p] (if (empty? p) "" (reduce (fn [a i] (str a "[" i "]")) "" p)))

(defn- lookup
  "The scope entry for `nm`: {:bid b :val v}, or nil."
  [st nm]
  (loop [i (dec (count (:scopes st)))]
    (if (neg? i)
      nil
      (let [f (nth (:scopes st) i)]
        (if (contains? f nm) (get f nm) (recur (dec i)))))))

(defn- push-scope [st] (update st :scopes conj {}))
(defn- pop-scope  [st] (assoc st :scopes (vec (butlast (:scopes st)))))

(defn- bind-name [st nm entry]
  (let [i (dec (count (:scopes st)))]
    (assoc st :scopes (assoc (:scopes st) i (assoc (nth (:scopes st) i) nm entry)))))

(defn- bind-cap [st bid m] (assoc st :caps (assoc (:caps st) bid m)))

(defn- mark-moved [st bid m]
  (if (nil? bid) st (assoc st :moved (assoc (:moved st) bid m))))

(defn- live-cap
  "If `val` is a LEAF denoting a capability that is live (bound and not moved, or
  freshly produced), return {:bid :cap :state :name :pos}; else nil."
  [st val]
  (cond
    ;; REFINEMENT: :refine / :rlog ride along with the capability wherever it
    ;; goes. They are empty for every capability that declares no refinement.
    ;; `:owes` rides along only when it was actually recorded. It is never
    ;; assoc'd as nil, because `owes?` distinguishes "recorded as discharged"
    ;; from "not recorded, derive it from the state" by `contains?`.
    (= :fresh (:v val)) (let [m {:bid nil :cap (:cap val) :state (:state val) :name nil
                                 :refine (:refine val) :rlog (:rlog val)}]
                          (if (contains? val :owes) (assoc m :owes (:owes val)) m))
    (= :cap (:v val))   (let [b (:bid val)]
                          (when (and (contains? (:caps st) b)
                                     (not (contains? (:moved st) b)))
                            (assoc (get (:caps st) b) :bid b)))
    :else nil))

;; --- paths into a value -----------------------------------------------------

(defn- val-at
  "Follow a path of tuple indices into `v`. An empty path is `v` itself; a path
  that does not exist in the abstract value is OPAQUE, never an error."
  [v path]
  (reduce (fn [x i]
            (if (= :tuple (:v x)) (or (nth' (:items x) i) OPAQUE) OPAQUE))
          (or v OPAQUE) path))

(defn- leaves
  "Every leaf of `v` with its path: [{:path p :val leaf} …]."
  ([v] (leaves v []))
  ([v path]
   (if (= :tuple (:v v))
     (reduce (fn [acc i] (into acc (leaves (nth (:items v) i) (conj path i))))
             [] (range (count (:items v))))
     [{:path path :val v}])))

(defn- cap-leaves [v] (filter (fn [l] (= :cap (:v (:val l)))) (leaves v)))

(defn- live-caps
  "Every position of `v` holding a LIVE capability: [{:path p :lc lc} …]."
  [st v]
  (remove nil?
          (map (fn [l]
                 (let [lc (live-cap st (:val l))]
                   (when lc {:path (:path l) :lc lc})))
               (leaves v))))

(defn- held-bids [v] (set (map (fn [l] (:bid (:val l))) (cap-leaves v))))

(defn- walk-leaves
  "Thread `st` through every leaf of `v`, rebuilding the tuple structure.
  `f` is (st leaf path) -> {:st :val}. Returns {:st :val}."
  ([st v f] (walk-leaves st v f []))
  ([st v f path]
   (if (= :tuple (:v v))
     (let [r (reduce (fn [acc i]
                       (let [rr (walk-leaves (:st acc) (nth (:items v) i) f (conj path i))]
                         {:st (:st rr) :items (conj (:items acc) (:val rr))}))
                     {:st st :items []} (range (count (:items v))))]
       {:st (:st r) :val (tuple (:items r))})
     (f st v path))))

;; --- THE TRANSITIVE SHAREABILITY RULE, READ OFF THE ABSTRACT VALUE ----------
;;
;; E37's reframe. The checker's abstract value is already a tree — a tuple of
;; leaves, each a capability, a dead capability, or opaque — so the profile
;; `perturb.share` classifies is a direct reading of it and nothing is invented
;; on the way. What that buys is that the REASON printed at a capture or an
;; escape is DERIVED from the value's shape rather than asserted: the old
;; `capture` message said "a `linearity :once` capability may not be closed
;; over", which E37 says is the wrong reason. The right one is that the closure
;; is a non-shareable value because a transitively reachable component is, and
;; the witness path says which one.
;;
;; AN OPAQUE LEAF IS TAGGED WITH WHY IT IS OPAQUE, and `perturb.share` REFUSES
;; to promote it. That refusal is NOT turned into a rejection here, and the
;; reason is honest rather than lenient: every result of an unannotated call is
;; opaque to this checker, so denying all of them would reject essentially every
;; program while carrying no evidence at all. See report-limits item 19.

(defn- share-profile
  "The `perturb.share` profile of one abstract value."
  [st v]
  (cond
    (nil? v)          [:opaque :perturb.check/absent]
    (= :cap (:v v))   [:cap (:cap (get (:caps st) (:bid v)))]
    (= :dead (:v v))  [:cap (:cap v)]
    (= :tuple (:v v)) (vec (cons :product
                                 (map (fn [i] (share-profile st i)) (:items v))))
    :else             [:opaque :perturb.check/unannotated]))

(defn- share-lines
  "The classification of `prof`, as diagnostic lines. Declarations come from
  `perturb.share`'s registry, which is empty unless something declared one —
  and nothing in perturb's own corpora does, so a capability leaf decides every
  profile here on its own."
  [prof]
  (sh/decision-lines prof (sh/classify (sh/interfaces) prof)))

(defn- shape-of
  "The capability shape of `v`: which positions hold which capability in which
  state. This is what a loop back edge has to preserve."
  [st v]
  (vec (sort-by (fn [e] (str (:path e)))
                (map (fn [e] {:path (:path e) :cap (:cap (:lc e)) :state (:state (:lc e))})
                     (live-caps st v)))))

;; --- the walk ---------------------------------------------------------------

(declare w)

(defn- w-seq
  "Walk nodes left to right, threading state; returns {:st :vals}."
  [st nodes]
  (reduce (fn [acc n]
            (let [r (w (:st acc) n)]
              {:st (:st r) :vals (conj (:vals acc) (:val r))}))
          {:st st :vals []} nodes))

(defn- describe-node [n]
  (cond
    (nil? n) "?"
    (= :local (:op n)) (str "`" (:name n) "`")
    (= :invoke (:op n)) (let [f (:fn n)]
                          (if (= :var (:op f))
                            (str "(" (:ns f) "/" (:name f) " …)")
                            "a call"))
    (= :const (:op n)) (pr-str (:val n))
    :else (str "a " (name (:op n)) " expression")))

;; ===========================================================================
;; REFINEMENTS ATTACHED TO A TRANSITION — the two tiers, joined (E18 finding 3)
;; ===========================================================================
;;
;; §1.2's typestate axis says which operation is legal from which STATE. §1.3
;; reserves arithmetic side conditions for refinements. `perturb.cap/refinements`
;; is where an edge of a machine carries one, and this is the part of the checker
;; that carries the ghost state along a program and hands the formula to
;; `perturb.refine`.
;;
;; WHAT IS DECIDED, EXACTLY. A capability's ghost variables are abstract
;; integers: `k + SUM c_i a_i`, or UNKNOWN. An atom is minted per BINDING
;; OCCURRENCE, so a name used twice is one atom and `declared = (ocount b)`
;; against `written = 0 + (ocount b)` discharges without either number being
;; known. Two syntactically different expressions are never identified.
;;
;; WHAT IS REFUSED, AND WHY REFUSED RATHER THAN ACCEPTED. Everything else:
;;
;;   - a ghost value that is not a linear term over constants and atoms;
;;   - a refinement crossing a LOOP boundary in either direction — the body is
;;     walked once, a trip count is data, and the honest fixpoint here is
;;     UNKNOWN, not "whatever one pass computed";
;;   - a refinement crossing a FUNCTION boundary — there is no interprocedural
;;     refinement, so a capability arriving as a parameter has unknown ghosts;
;;   - a formula the fragment does not decide.
;;
;; Each of those is `refinement-undischarged`, which is a REJECTION with its own
;; diagnostic kind. A checker that accepted on "don't know" would be a machine
;; for producing false accepts that looks like a checker (E15, E17).

(declare aeval-int)

(defn- invoke-sym [node]
  (if (and (= :invoke (:op node)) (= :var (:op (:fn node))))
    (symbol (:ns (:fn node)) (:name (:fn node)))
    nil))

(defn- arg-node [node i]
  (let [as (:args node)] (if (and as (< i (count as))) (nth as i) nil)))

(defn- aeval-len
  "The abstract OCTET LENGTH of the value an IR node produces.

  `(o/encode-utf8 \"abc\")` is evaluated by CALLING the real encoder on the
  literal, not by counting characters: those two numbers differ and only one of
  them is what goes on the wire. Anything else is UNKNOWN — including a string
  that is not a literal, which is the common case and is meant to be."
  [st node]
  (let [s (invoke-sym node)]
    (cond
      (nil? node) (ref/top "an expression that is not there")

      (= :local (:op node))
      (let [e (lookup st (:name node))]
        (if (and e (:olen e))
          (:olen e)
          (ref/top (str "the length of `" (:name node) "`, which is not bound here"))))

      (= 'perturb.octet/encode-utf8 s)
      (let [a (arg-node node 0)]
        (if (and a (= :const (:op a)) (string? (:val a)))
          (ref/konst (o/ocount (o/encode-utf8 (:val a))))
          (ref/top "the length of a string that is not a literal")))

      (= 'perturb.octet/oconcat s)
      (ref/add (aeval-len st (arg-node node 0)) (aeval-len st (arg-node node 1)))

      (= 'perturb.octet/odrop s)
      (ref/sub (aeval-len st (arg-node node 0)) (aeval-int st (arg-node node 1)))

      (= 'perturb.octet/osub s)
      (ref/sub (aeval-int st (arg-node node 2)) (aeval-int st (arg-node node 1)))

      :else
      (ref/top (str "the length of " (describe-node node)
                    ", which this checker does not evaluate")))))

(defn- aeval-int
  "The abstract INTEGER an IR node produces."
  [st node]
  (let [s (invoke-sym node)]
    (cond
      (nil? node) (ref/top "an expression that is not there")

      (and (= :const (:op node)) (integer? (:val node))) (ref/konst (:val node))

      (= :local (:op node))
      (let [e (lookup st (:name node))]
        (if (and e (:int e))
          (:int e)
          (ref/top (str "`" (:name node) "`, which is not bound here"))))

      (= 'perturb.octet/ocount s) (aeval-len st (arg-node node 0))

      (= 'clojure.core/+ s)
      (reduce (fn [acc n] (ref/add acc (aeval-int st n))) (ref/konst 0) (:args node))

      (= 'clojure.core/- s)
      (if (= 1 (count (:args node)))
        (ref/neg (aeval-int st (arg-node node 0)))
        (reduce (fn [acc n] (ref/sub acc (aeval-int st n)))
                (aeval-int st (arg-node node 0)) (rest (:args node))))

      (= 'clojure.core/inc s) (ref/add (aeval-int st (arg-node node 0)) (ref/konst 1))
      (= 'clojure.core/dec s) (ref/sub (aeval-int st (arg-node node 0)) (ref/konst 1))

      :else (ref/top (str (describe-node node)
                          ", which this checker does not evaluate")))))

(defn- bound-ints
  "What a NAME denotes, as an abstract integer and as an abstract octet length.

  When the initialiser is opaque the name still denotes ONE fixed value, so a
  FRESH ATOM is minted rather than UNKNOWN: two later uses of the same name then
  share it, and `(ocount b)` on both sides of an obligation cancels. This is the
  whole of the symbolic reasoning, and it is sound only because a binding is
  immutable — for a LOOP binding the atom means `the value in the iteration
  being analysed`, which is why a refinement is not allowed to leave a loop."
  [st nm init]
  (let [i (aeval-int st init)
        l (aeval-len st init)]
    {:int  (if (ref/top? i) (ref/atom-term :val (fresh-bid) nm) i)
     :olen (if (ref/top? l) (ref/atom-term :len (fresh-bid) nm) l)}))

(defn- arg-fn
  "Resolve `(arg n)` / `(ocount (arg n))` in a DECLARED term against the actual
  argument expressions at one call site."
  [st nodes]
  (fn [kind i]
    (if (or (nil? i) (>= i (count nodes)))
      (ref/top (str "argument " i ", which this call does not have"))
      (if (= :len kind)
        (aeval-len st (nth nodes i))
        (aeval-int st (nth nodes i))))))

(defn- ghost-line [nm term where]
  (str "  " nm " := " (ref/render term) "   at " (pir/site where)))

(defn- refine-init
  "The ghost environment a `:produces` entry declares with `:init`, or nil."
  [st entry nodes]
  (let [r    (get entry cap/refine-key)
        init (:init r)]
    (if (nil? init)
      nil
      (let [af  (arg-fn st nodes)
            ks  (sort-by str (keys init))
            env (reduce (fn [acc k] (assoc acc k (ref/term {} af (get init k)))) {} ks)]
        {:refine env
         :rlog   (vec (map (fn [k] (ghost-line k (get env k) (:pos st))) ks))}))))

;; --- WHICH EDGES OF AN OPERATION APPLY TO ONE CALL --------------------------
;;
;; An operation may declare several edges under one [capability operation] key:
;; several `:from`s (which has always been possible) and, since E26 finding 7,
;; several `:to`s from one `:from` — a SUM. The refinement tier has to say which
;; of them a given call site is subject to, and the answer is the same totality
;; rule the typestate side already uses.

(defn- applicable-edges
  "The declared edges of `[cap opsym]` whose `:from` admits the state the
  capability is ACTUALLY in at this call. `state-ok?` and not `admits?`, so a
  capability in a sum state selects only edges that admit every member — the
  same rule that lets a `:from :any` destructor consume a sum."
  [sp cap-name opsym state]
  (edges-applicable sp cap-name opsym state))

(defn- applicable-refinements
  "The refinements on those edges. NO FALLBACK: if the call typechecked through
  an edge that carries no refinement, there is no obligation to discharge, and
  reaching for one from another `:from` would discharge a condition that is not
  on this path."
  [sp cap-name opsym state]
  (vec (filter (fn [r] (state-ok? (:from r) state))
               (get (:refinements sp) [cap-name opsym]))))

(defn- refine-update
  "Apply the `:update`s on `[cap opsym]` to a live capability's ghost state. With
  no `:update` the state is carried across unchanged, which is what makes an
  operation that does not touch the arithmetic transparent to it.

  SEVERAL APPLICABLE EDGES — the sum case. The call takes ONE of them and which
  one is not knowable until run time, so:

    - every applicable edge carries the SAME `:update`  -> apply it, exactly as
      the single-edge case always did;
    - the applicable edges DISAGREE (different `:update`s, or one has an
      `:update` and another does not, which is `unchanged` and is a different
      answer) -> every ghost variable any of them touches becomes UNKNOWN, with
      the reason recorded. That is the same treatment `join-refine` gives the
      two arms of an `if` and `widen-caps` gives a loop, for the same reason:
      the checker does not know which value it has, and guessing is how a
      checker becomes a false-accept generator.

  Before this, the table held ONE entry per [capability operation] and a second
  summand's refinement was silently dropped — old `report-limits` item 14(j)."
  [st sp cap-name opsym lc nodes]
  (let [env   (or (:refine lc) {})
        edges (applicable-edges sp cap-name opsym (:state lc))
        ups   (vec (map (fn [t] (:update (get t cap/refine-key))) edges))
        real  (vec (remove nil? ups))]
    (cond
      (or (empty? real) (empty? env))
      {:refine env :rlog (vec (:rlog lc))}

      (or (> (count (distinct real)) 1) (< (count real) (count ups)))
      (let [ks   (vec (sort-by str (distinct (reduce (fn [a u] (into a (keys u))) [] real))))
            why  (str "unknown here — `" opsym "` declares " (count edges)
                      " destinations from this state and they do not agree about"
                      " this ghost variable; which one the call took is not"
                      " knowable until run time")
            env2 (reduce (fn [acc k] (assoc acc k (ref/top why))) env ks)]
        {:refine env2
         :rlog   (into (vec (:rlog lc))
                       (map (fn [k] (str "  " k " := unknown   (a sum with disagreeing"
                                         " :update's) at " (pir/site (:pos st))))
                            ks))})

      :else
      (let [u    (first real)
            af   (arg-fn st nodes)
            ks   (sort-by str (keys u))
            env2 (reduce (fn [acc k] (assoc acc k (ref/term env af (get u k)))) env ks)]
        {:refine env2
         :rlog   (into (vec (:rlog lc))
                       ;; the DECLARED term is printed beside the value it took,
                       ;; because the term is the part that is believed and the
                       ;; value is the part that is computed
                       (map (fn [k] (str "  " k " := " (pr-str (get u k))
                                         "   = " (ref/render (get env2 k))
                                         "   at " (pir/site (:pos st))))
                            ks))}))))

(defn- check-requires-one!
  "Discharge ONE edge's `:requires` against the ghost state of the capability
  being consumed. Three outcomes and only three: proved, refuted, or REFUSED.
  Returns nil either way — this reports, it does not change the abstract state."
  [st sp cap-name opsym lc nodes r n-applicable]
  (let [req (:requires r)]
    (when (and req lc)
      (let [env (or (:refine lc) {})
            v   (ref/decide env (arg-fn st nodes) req)
            head (concat
                   [(str "obligation    " (:name r) "   " (pr-str req))
                    (str "on transition " opsym "  " (want-str (:from r))
                         " -> " (want-str (:to r)))]
                   (if (> n-applicable 1)
                     [(str "              one of " n-applicable
                           " destinations this operation declares from this state;")
                      (str "              EVERY one of them must be discharged, because which")
                      (str "              destination the call takes is not knowable until run time")]
                     []))
            tail (concat (ref/env-lines env)
                         (vec (:rlog lc))
                         [(str "at            " (pir/site (:pos st)))
                          (str "in            " (:in st))])]
        (cond
          (= :valid v) nil

          (= :refuted v)
          (report! {:kind :refinement :cap cap-name :op opsym
                    :detail (concat head
                                    ["the side condition is FALSE — this is a decided"
                                     "counterexample, not a failure to prove:"]
                                    tail)})

          :else
          (report! {:kind :refinement-undischarged :cap cap-name :op opsym
                    :detail (concat head
                                    [(str "CANNOT DISCHARGE: "
                                          (cond
                                            (empty? env)
                                            (str "this " cap-name " carries no ghost state at all"
                                                 " — it arrived as a parameter, or from an"
                                                 " operation with no :init, and there is no"
                                                 " interprocedural refinement here")
                                            (not (nil? (ref/first-unknown-reason env)))
                                            (ref/first-unknown-reason env)
                                            :else
                                            (str "the two sides are both known and do NOT"
                                                 " normalise to the same linear term."
                                                 " perturb.refine relates atoms syntactically,"
                                                 " has no case split and takes no hypotheses,"
                                                 " so this is refused rather than decided")))
                                     "the program is REJECTED rather than accepted: this checker"
                                     "does not treat `I cannot tell` as `yes` (perturb.refine)"]
                                    tail)}))))))

(defn- check-requires!
  "Discharge EVERY applicable edge's `:requires` against the ghost state of the
  capability being consumed.

  EVERY, not the first and not the last. One operation may declare several
  destinations from one state and each of them may carry its own side
  condition; the call takes one of them and which one is not knowable until run
  time, so a program is correct only if it satisfies all of them. This is the
  totality rule `state-ok?` uses on the typestate side, at the refinement tier.

  Before `cap/refinements` held a vector, the table held one entry per
  [capability operation] and the LAST declared summand silently overwrote the
  others — so a refuted obligation on an earlier summand became an ACCEPT. That
  is old `report-limits` item 14(j), and `perturb.dbtxcorpus/Meter` is the
  fixture that would have caught it."
  [st sp cap-name opsym lc nodes]
  (when lc
    (let [rs (applicable-refinements sp cap-name opsym (:state lc))]
      (doseq [r rs]
        (check-requires-one! st sp cap-name opsym lc nodes r (count rs))))))

(defn- widen-caps
  "Every ghost variable of every capability in `bids` becomes UNKNOWN.

  This is the loop rule for the refinement tier, and it is deliberately blunt: a
  loop body is walked ONCE and its trip count is data, so the only sound value
  for a ghost variable a loop can change is UNKNOWN. A capability created AND
  discharged inside one loop body is untouched by this, which is why a body
  streamed per request inside a keep-alive loop still decides."
  [st bids why]
  (reduce
    (fn [s bid]
      (let [c (get (:caps s) bid)]
        (if (or (nil? c) (empty? (:refine c)))
          s
          (assoc s :caps
                 (assoc (:caps s) bid
                        (assoc c
                               :refine (reduce (fn [acc k] (assoc acc k (ref/top why)))
                                               {} (keys (:refine c)))
                               :rlog (conj (vec (:rlog c))
                                           (str "  ghost state of `" (:name c)
                                                "` becomes " why))))))))
    st bids))

(defn- map-fresh
  "Rewrite every :fresh leaf of an abstract value."
  [v f]
  (if (= :tuple (:v v))
    (tuple (map (fn [x] (map-fresh x f)) (:items v)))
    (if (= :fresh (:v v)) (f v) v)))

(defn- with-refinements
  "Give every capability an annotated call PRODUCES its ghost state.

  Two sources, in this order. `:init` on the `:produces` entry MINTS the ghost
  state — that is the creation edge, and it is written there rather than on a
  transition because a capability minted by another machine's operation cannot
  name that operation among its own transitions (E18 finding 1a). Otherwise the
  produced capability is the SAME runtime thing as the one consumed under the
  same capability name, so it inherits that one's ghost state through the
  transition's `:update`.

  A capability named by two `:produces` entries of one operation is ambiguous
  here and gets no ghost state, which means any obligation on it is later
  refused rather than guessed at."
  [st sp opsym nodes lcs prod v]
  (let [tally  (reduce (fn [acc p] (assoc acc (:cap p) (inc (get acc (:cap p) 0)))) {} prod)
        by-cap (reduce (fn [acc p] (assoc acc (:cap p) p)) {} prod)]
    (map-fresh
      v
      (fn [leaf]
        (let [c (:cap leaf)
              p (get by-cap c)]
          (if (or (nil? p) (> (get tally c 0) 1))
            leaf
            (let [i (refine-init st p nodes)]
              (if i
                (assoc leaf :refine (:refine i) :rlog (:rlog i))
                (let [lc (get lcs c)]
                  (if (and lc (seq (:refine lc)))
                    (let [u (refine-update st sp c opsym lc nodes)]
                      (assoc leaf :refine (:refine u) :rlog (:rlog u)))
                    leaf))))))))))

;; LIVE (multicap.Store.live) + capture, now at every position of the value.
;;
;; Naming a value is NOT using every capability inside it. `(second r)` reads the
;; same `r` that `(first r)` already moved, and must not be a use-after-move of
;; position 0. So a consumed position becomes a :dead leaf here and the
;; diagnostic is raised where a dead leaf is actually USED — by `consume-arg`,
;; by an unannotated callee, or by returning it. Capture is different: the
;; closure body could reach any position, so it is reported on sight.
(defn- w-local [st node]
  (let [nm (:name node)
        e  (lookup st nm)]
    (if (nil? e)
      {:st st :val OPAQUE}
      (walk-leaves
        st (:val e)
        (fn [s leaf path]
          (let [bid (:bid leaf)]
            (cond
              (not= :cap (:v leaf)) {:st s :val leaf}

              (contains? (:moved s) bid)
              (let [m (get (:moved s) bid)
                    c (get (:caps s) bid)]
                {:st s :val {:v :dead :bid bid :cap (:cap c) :state (:state c)
                             :name (str nm (path-str path))
                             :bound-pos (:pos c) :by (:by m) :at-pos (:pos m)}})

              (contains? (:caps s) bid)
              (let [c (get (:caps s) bid)]
                (when (> (:fn-depth s) (:fn-depth c))
                  ;; THE TRANSITIVE SHAREABILITY RULE (E37). The value being
                  ;; closed over is the WHOLE binding, not the leaf: a closure
                  ;; over `[conn frames]` is mixed because one FIELD is, and the
                  ;; witness path says which. The consumer of the closure is
                  ;; recorded by `w-fn`, and `perturb.share/capture-disposition`
                  ;; says whether the rule DECIDES this capture or refuses it —
                  ;; refuses being the answer whenever the callee's higher-order
                  ;; retention contract is undeclared, which §4.6 says perturb
                  ;; has no notation for.
                  (let [prof [:closure (share-profile s (:val e))]
                        disp (sh/capture-disposition
                               {:consumer (:closure-consumer s)
                                :contention (:perturb.cap/contention
                                              (decl-of (:spec s) (:cap c)))
                                :cap (:cap c)})]
                    (report! {:kind :capture :cap (:cap c)
                              :share (sh/classify (sh/interfaces) prof)
                              :disposition disp
                              :detail (vec (concat
                                             [(str "capability    `" nm (path-str path) "` : "
                                                   (:cap c) "@" (:state c))
                                              (str "captured by a nested fn at "
                                                   (pir/site (:pos s)))]
                                             (share-lines prof)
                                             [(str "route         "
                                                   (if (:decided? disp)
                                                     "DECIDED — this capture is a violation"
                                                     "REFUSED — the classification stands, the question does not")
                                                   "  (" (name (:ground disp)) ")")]
                                             (map (fn [l] (str "  " l)) (:why disp))
                                             [(str "in            " (:in s))]))})))
                {:st s :val leaf})

              :else {:st s :val OPAQUE})))))))

(defn- report-use-after-move! [st leaf ctx]
  (report! {:kind :use-after-move :cap (:cap leaf)
            :detail [(str "capability    `" (:name leaf) "` : " (:cap leaf)
                          "@" (:state leaf)
                          ", bound at " (pir/site (:bound-pos leaf)))
                     (str "consumed by   " (:by leaf) "  at " (pir/site (:at-pos leaf)))
                     (str "used again at " (pir/site (:pos st))
                          (if ctx (str "  (argument to " ctx ")") ""))
                     (str "in            " (:in st))]})
  nil)

(defn- dead-leaves [v] (filter (fn [l] (= :dead (:v (:val l)))) (leaves v)))

(defn- entry-path [entry] (vec (or (:at entry) [])))

(defn- entry-site
  "How an annotation entry names its argument, for a diagnostic. `:arg` is
  mandatory, so there is no unpositioned rendering left to produce."
  [entry]
  (str "argument " (:arg entry) (path-str (entry-path entry))))

(defn- discriminator-lines
  "What a reader can DO about a sum, printed from the declaration rather than
  invented: the predicates declared for this capability and what each arm of
  each one knows. A capability with no declared discriminator is a capability
  whose sum cannot be eliminated at all, and the diagnostic says so."
  [sp c]
  (let [ds (:perturb.cap/discriminator (decl-of sp c))]
    (if (empty? ds)
      [(str "NO :perturb.cap/discriminator is declared for " c ": this sum cannot be")
       (str "eliminated by a case split, so the only operations that may consume it")
       (str "are those whose :from admits every member (a `:from :any` destructor)")]
      (into ["the declared discriminator(s) that can eliminate this sum, at an `if`:"]
            (map (fn [d]
                   (str "  (" (:op d) " arg" (:arg d) ")   true -> " (want-str (:true d))
                        "   false -> " (want-str (:false d))))
                 ds)))))

(defn- report-state-unresolved!
  "A SUM REACHED A USE THAT DOES NOT ADMIT ALL OF IT (E26 finding 7).

  Deliberately NOT `:typestate`. A typestate rejection says the program is in the
  wrong state; this one says the program is in several states at once and has not
  said which — the fault is a missing case split, not a wrong operation, and the
  two want different things from the reader."
  [st sp opsym entry lc]
  (report! {:kind :state-unresolved :cap (:cap entry) :op opsym
            :detail (concat
                      [(str "operation     " opsym " requires " (:cap entry) "@"
                            (want-str (:state entry)))
                       (str "capability    "
                            (if (:name lc) (str "`" (:name lc) "` ") "")
                            "is in a SUM state " (want-str (:state lc)))
                       (str "              — one operation declared several `:to`s and"
                            " which one it took")
                       (str "                is not knowable until run time")
                       (if (:derived-machine entry)
                         ;; A DERIVED entry admits a state iff the machine has an
                         ;; edge from it, and a SUM iff ONE edge admits every
                         ;; member. Printing `unadmitted` here would say `[]` and
                         ;; be actively misleading: each member is fine on its
                         ;; own, and no single edge covers them together.
                         (str "no single declared edge of " opsym " admits every member"
                              " at once")
                         (str "not admitted  " (want-str (unadmitted (:state entry) (:state lc)))
                              "   of " (want-str (:state lc))))
                       (str "at            " (pir/site (:pos st)))]
                      (discriminator-lines sp (:cap entry))
                      [(str "in            " (:in st))])})
  nil)

(defn- report-cancelled-use!
  "AN OPERATION THAT IS NOT THE DESTRUCTOR, APPLIED TO A CANCELLED CAPABILITY.

  Deliberately NOT `:typestate`, for the same reason `state-unresolved` is not.
  A `typestate` verdict here would be true and useless: it would say the handle
  is in `:poisoned` and the operation wants `:open`, as if the program had
  simply picked the wrong moment. What actually happened is that the protocol
  was ABANDONED, and the only thing left to do with the capability is dispose
  of it. The reader wants to be told that, and to be told that the abandonment
  did not discharge the obligation."
  [st sp opsym entry lc]
  (let [c  (:cap entry)
        cs (:state lc)]
    (report! {:kind :cancelled-use :cap c :op opsym
              :detail (concat
                        [(str "operation     " opsym " requires " c "@"
                              (want-str (:state entry)))
                         (str "capability    "
                              (if (:name lc) (str "`" (:name lc) "` ") "")
                              "is in the CANCELLED state " cs)
                         (str "              its protocol was abandoned; a cancelled"
                              " capability admits")
                         (str "              nothing but its destructor (PERTURB-DESIGN"
                              " §4.6 piece 5)")
                         (str "at            " (pir/site (:pos st)))]
                        (destructor-lines sp c cs)
                        [(str "and reaching " cs " discharged NOTHING — it is not terminal, so")
                         (str "failing to reach the destructor is still a leak")
                         (str "in            " (:in st))])})
    nil))

(defn- report-unpositioned! [st opsym entry move?]
  (report! {:kind :annotation-unpositioned :cap (:cap entry) :op opsym
            :detail [(str "operation     " opsym (if move? " consumes " " borrows ")
                          (:cap entry) "@" (want-str (:state entry))
                          " and does not say WHICH argument holds it")
                     (str "at            " (pir/site (:pos st)))
                     "every :consumes / :borrows entry must carry `:arg n`"
                     "an unpositioned entry is refused, not guessed"]})
  nil)

(defn- consume-arg
  "TYPESTATE + LIVE. Apply one :consumes/:borrows entry of `opsym` to the argument
  list. Returns {:st st :used i|nil :ok bool}.

  POSITIONED, AND ONLY POSITIONED. The entry carries `:arg n` (and optionally
  `:at [i …]`) and the capability is looked for exactly there and nowhere else.
  The fallback that matched specs to parameters IN ORDER when `:arg` was absent
  is REMOVED: it was the checker's own convention rather than §1.2's (E17
  nonclaim 4), and with two capabilities it bound the wrong parameter to the
  wrong capability and produced five diagnostics, none of which named the
  annotation (E18 finding 1(d)). An entry without `:arg` is now refused here and
  at the annotation's own declaration.

  A `:state` MAY BE OMITTED, AND THEN THE MACHINE ANSWERS (E33). An entry that
  writes no `:state` admits exactly the states for which the machine declares an
  edge of `[cap opsym]` — the `:from` side of E33's primitive relation, read
  rather than repeated. That is what lets an operation whose destination depends
  on its source be annotated ONCE instead of once per source, and it is opt-in:
  every entry written before this existed carries a `:state` and takes the same
  path it always did.

  This does NOT establish that the argument really holds what the entry says —
  the callee's body is an axiom or is checked separately."
  [st sp opsym entry vals nodes move? keep-name?]
  (let [want-cap   (:cap entry)
        ;; `move?` still says what the entry IS, for the diagnostics; `moves?`
        ;; says whether the NAME dies, which an in-place produce overrides.
        moves?     (and move? (not keep-name?))
        derived?   (derived-state? entry)
        want-state (if derived?
                     (norm-state (declared-sources sp want-cap opsym))
                     (:state entry))
        path       (entry-path entry)
        i          (:arg entry)
        hit (when i
              (let [leaf (val-at (nth' vals i) path)
                    lc   (live-cap st leaf)]
                (when (and lc (= (:cap lc) want-cap)) {:i i :lc lc :leaf leaf})))
        dead-leaf (when i
                    (let [leaf (val-at (nth' vals i) path)]
                      (when (and (= :dead (:v leaf)) (= want-cap (:cap leaf))) leaf)))
        dead-here? (not (nil? dead-leaf))
        ;; ADMISSION. For a written `:state` this is `state-ok?` and nothing has
        ;; changed. For an omitted one it is "the machine declares an edge of
        ;; this operation from the state the capability is actually in" — the
        ;; same totality rule, since `edges-applicable` selects with `state-ok?`
        ;; and therefore admits a sum only when EVERY member is admitted.
        admits-actual?
        (fn [got] (if derived?
                    (seq (edges-applicable sp want-cap opsym got))
                    (state-ok? want-state got)))]
    (cond
      (nil? i)
      (do (report-unpositioned! st opsym entry move?)
          {:st st :used nil :ok false})

      (nil? hit)
      (do (if dead-here?
            ;; the capability IS at that position — it is just already consumed.
            ;; One program error, one diagnostic: use-after-move, not "no
            ;; argument is a capability".
            (report-use-after-move! st dead-leaf opsym)
            (report! {:kind (if move? :untracked-consume :untracked-borrow)
                      :cap want-cap :op opsym
                      :detail [(str "operation     " opsym
                                    (if move? " consumes " " borrows ")
                                    want-cap "@" (want-str want-state))
                               (str "expected at   " (entry-site entry))
                               (str "at            " (pir/site (:pos st)))
                               (str "but that position is not a tracked capability of that type")
                               (str "arguments     "
                                    (str/join ", " (map describe-node nodes)))
                               (str "in            " (:in st))]}))
          {:st st :used nil :ok false :lc nil})

      (not (admits-actual? (:state (:lc hit))))
      (do (cond
            (sum? (:state (:lc hit)))
            ;; A SUM that is not admitted in full. Its own diagnostic kind, and
            ;; the abstract state moves exactly as a typestate rejection does —
            ;; the difference is what the reader is told, not what is tracked.
            ;; A DERIVED entry is shown the machine's own `:from`s, since that
            ;; is what it asked to be compared against.
            (report-state-unresolved! st sp opsym
                                      (assoc entry :state want-state
                                             :derived-machine derived?)
                                      (:lc hit))

            ;; THE CANCELLED STATE. Same treatment, third kind: the state is
            ;; known and it is the one the capability was cancelled into, so the
            ;; useful thing to say is that the protocol was abandoned and only
            ;; the destructor is left — not that `:open` was wanted.
            (= (:state (:lc hit)) (cancelled-state-of sp want-cap))
            (report-cancelled-use! st sp opsym (assoc entry :state want-state)
                                   (:lc hit))

            :else
            (report! {:kind :typestate :cap want-cap :op opsym
                      :detail [(str "operation     " opsym " requires " want-cap "@"
                                    (want-str want-state))
                               (str "capability    "
                                    (if (:name (:lc hit)) (str "`" (:name (:lc hit)) "` ") "")
                                    "is in state " (:state (:lc hit)))
                               (str "at            " (pir/site (:pos st)))
                               (str "in            " (:in st))]}))
          {:st (if moves? (mark-moved st (:bid (:lc hit)) {:by opsym :pos (:pos st)}) st)
           :used (:i hit) :ok false :lc (:lc hit)})

      :else
      ;; REFINEMENT: `:lc` is returned so the caller can discharge a side
      ;; condition against the ghost state of the capability just consumed.
      {:st (if moves? (mark-moved st (:bid (:lc hit)) {:by opsym :pos (:pos st)}) st)
       :used (:i hit) :ok true :lc (:lc hit)})))

(defn- resolve-produced
  "THE `:produces` SIDE OF E33's PRIMITIVE RELATION, resolved at one call site.

  Returns {:state s :owes b :ok true} or {:ok false :why lines}.

  Two sources for the destination, and the first is the whole of the E33 change:

    - `:state` OMITTED — ask the MACHINE. `(capability, operation, source-state)`
      selects the applicable edges; their result labels are collapsed by
      `machine-destination`, which is where two labels that share a destination
      stop being a sum. The source is the state the CONSUMED capability of the
      same name is actually in at this call, so the destination is a function of
      the SOURCE and E30 finding 3's from-to pairing gap closes.
    - `:state` WRITTEN — exactly as before, believed, not compared here.

  And the OBLIGATION is resolved separately from the state, which is the point:
  an explicitly declared `:obligation` on any applicable edge is applied as a
  DELTA to what the capability already owed; with none declared it is derived
  from the destination being terminal, which is the pre-E33 behaviour."
  [st sp opsym entry lcs]
  (let [c   (:cap entry)
        lc  (get lcs c)
        src (when lc (:state lc))
        md  (machine-destination sp c opsym src)
        settle
        (fn [s]
          {:ok true :state s
           :owes (if (and md (:declared? md))
                   (apply-delta (if lc (owes? sp lc) true) (:delta md))
                   (not (terminal? sp c s)))})]
    (if (derived-state? entry)
      (if (nil? md)
        {:ok false
         :why [(str "operation     " opsym " produces " c
                    " and writes no `:state`, so the destination must be read")
               (str "              off the machine — but " c
                    " declares no edge of " opsym)
               (str "from          " (if lc (str "state " (want-str src))
                                         "no consumed capability of that name"))
               (str "declared      " (pr-str (declared-sources sp c opsym)))
               (str "at            " (pir/site (:pos st)))]}
        (settle (:to md)))
      (settle (norm-state (:state entry))))))

(defn- produced-value
  "Build the abstract result of an annotated call from its :produces entries.

  An entry with no `:at` produces the capability BARE — the result is the
  capability itself. An entry with `:at [i]` puts it at position i of a tuple.
  Mixing the two in one annotation is a contradiction and is refused; so is a
  path deeper than one level, which this checker does not implement.

  IN-PLACE ENTRIES ARE NOT HERE. An entry carrying `:arg` does not travel in the
  result at all — see `w-annotated-invoke` — so it is filtered out before this
  runs and cannot make the result a tuple it is not."
  [st opsym prod states]
  (let [paths (map entry-path prod)
        leaf  (fn [p] {:v :fresh :cap (:cap p) :state (:state (get states p))
                       :owes (:owes (get states p))})]
    (cond
      (empty? prod) OPAQUE

      (every? empty? paths)
      (if (= 1 (count prod))
        (leaf (first prod))
        (do (report! {:kind :annotation-unsupported :op opsym
                      :detail [(str "operation     " opsym " declares " (count prod)
                                    " produced capabilities, none of them positioned")
                               (str "add `:at [i]` to each so the checker can say which")
                               (str "result position it lands in")
                               (str "at            " (pir/site (:pos st)))]})
            OPAQUE))

      (some empty? paths)
      (do (report! {:kind :annotation-unsupported :op opsym
                    :detail [(str "operation     " opsym
                                  " mixes positioned and unpositioned :produces")
                             (str "a result cannot be both a bare capability and a tuple")
                             (str "at            " (pir/site (:pos st)))]})
          OPAQUE)

      (some (fn [p] (not= 1 (count p))) paths)
      (do (report! {:kind :annotation-unsupported :op opsym
                    :detail [(str "operation     " opsym " declares a :produces path "
                                  (pr-str (first (filter (fn [p] (not= 1 (count p))) paths))))
                             (str "this checker implements one level of tuple nesting only")
                             (str "at            " (pir/site (:pos st)))]})
          OPAQUE)

      :else
      (let [by-i (reduce (fn [m p] (assoc m (first (entry-path p)) p)) {} prod)
            n    (inc (apply max (map first paths)))]
        (tuple (map (fn [i]
                      (let [p (get by-i i)]
                        (if p (leaf p) OPAQUE)))
                    (range n)))))))

;; --- IN PLACE: the name lives and the state moves (E30 finding 2, row 50) ----
;;
;; `:arg n` on a `:produces` entry used to be ACCEPTED AND SILENTLY IGNORED: no
;; diagnostic, and the capability landed in the result anyway, so variant 4 of
;; `perturb.tcpcap` got a `no-signature` because `clojure.core/true?` received a
;; `Connection@:closed`. A declaration language that takes a key it does not
;; implement is a false-silence hole; the key is implemented here.
;;
;; WHAT IT DOES. The capability at argument n does NOT move. Its binding keeps
;; its identity — the SAME binding id, deliberately — and only its state and its
;; obligation change. Keeping the id is what keeps every other rule working
;; unchanged: `check-scope-exit` still sees the binding the enclosing `let`
;; recorded, `shape-of` still describes it at a loop back edge, and the join rule
;; still compares the same `:moved` sets. Minting a fresh id here would have
;; taken the capability off the enclosing scope's list and turned a leak into a
;; silent accept.
;;
;; WHAT IT REFUSES, LOUDLY, rather than resolving:
;;   - `:arg` together with `:at` on one entry — a capability cannot both stay
;;     where it is and land in the result;
;;   - `:arg n` where argument n is not a plain local NAME. There is nothing to
;;     rebind, so the produced capability would vanish and its obligation with
;;     it. That is the false accept this whole mechanism exists to avoid.

(defn- apply-in-place
  "Land one in-place `:produces` entry on the argument it names."
  [st sp opsym entry nodes lcs states]
  (let [c    (:cap entry)
        i    (:arg entry)
        node (nth' nodes i)
        lc   (get lcs c)
        res  (get states entry)]
    (cond
      (seq (entry-path entry))
      (do (report! {:kind :annotation-unsupported :op opsym :cap c
                    :detail [(str "operation     " opsym " declares a :produces entry with"
                                  " BOTH `:arg " i "` and `:at " (pr-str (entry-path entry)) "`")
                             "`:arg` is an IN-PLACE produce — the capability stays where it is —"
                             "and `:at` is a position in the RESULT. It cannot be both."
                             (str "at            " (pir/site (:pos st)))]})
          st)

      (or (nil? node) (not= :local (:op node)) (nil? lc) (nil? (:bid lc)))
      (do (report! {:kind :in-place-unnamed :op opsym :cap c
                    :detail [(str "operation     " opsym " declares :produces " c
                                  " IN PLACE at `:arg " i "`")
                             (str "argument " i "      " (describe-node node))
                             "an in-place produce rebinds the NAME the argument is; there is no"
                             "name here, so the produced capability — and the obligation it"
                             "carries — would simply vanish. Refused rather than lost."
                             (str "at            " (pir/site (:pos st)))
                             (str "in            " (:in st))]})
          st)

      :else
      (let [bid (:bid lc)
            old (get (:caps st) bid)
            u   (when (seq (:refine lc)) (refine-update st sp c opsym lc nodes))]
        ;; THE SAME BINDING ID, deliberately. The name did not move, so nothing
        ;; about which scope owes this capability changes; only its state and
        ;; its obligation do.
        (bind-cap st bid (merge (or old {})
                                {:cap c :state (:state res) :owes (:owes res)}
                                (if u {:refine (:refine u) :rlog (:rlog u)} {})))))))

(defn- w-annotated-invoke [st sp node opsym ann]
  (let [r     (w-seq (assoc st :use-ctx opsym) (:args node))
        st1   (assoc (:st r) :use-ctx nil)
        vals  (:vals r)
        nodes (:args node)
        step  (fn [acc entry move? keep-name?]
                (let [rr (consume-arg (:st acc) sp opsym entry vals nodes
                                      move? keep-name?)]
                  ;; REFINEMENT: discharge this edge's side condition, if it has
                  ;; one, against the ghost state of the capability consumed
                  ;; here. Only when the typestate half succeeded — a refinement
                  ;; diagnostic on top of a `typestate` one is noise about a
                  ;; program that is already rejected for a better reason.
                  (when (:ok rr)
                    (check-requires! (:st acc) sp (:cap entry) opsym (:lc rr) nodes))
                  {:st (:st rr)
                   :ok (and (:ok acc) (:ok rr))
                   :lcs (if (:ok rr) (assoc (:lcs acc) (:cap entry) (:lc rr)) (:lcs acc))}))
        prod  (vec (:produces ann))
        ;; THE THIRD NOTION, DECIDED HERE AND NOWHERE ELSE (E33). A capability
        ;; the annotation produces back IN PLACE does not move: the caller's
        ;; binding is alive after this call whatever the call does. So its
        ;; `:consumes` entry is checked for TYPESTATE but does not kill the
        ;; name — including when the typestate check FAILS, because "the
        ;; operation was illegal here" and "the name is dead" are exactly the
        ;; two things `:linearity :once` conflated. Without this, one rejected
        ;; call cascades into a `use-after-move` on every later mention of a
        ;; connection that is demonstrably still usable.
        in-place-caps (set (map :cap (filter in-place? prod)))
        a1    (reduce (fn [acc e] (step acc e false false))
                      {:st st1 :ok true :lcs {}} (:borrows ann))
        a2    (reduce (fn [acc e]
                        (step acc e true (contains? in-place-caps (:cap e))))
                      a1 (:consumes ann))
        st2   (:st a2)]
    ;; the operation did not type-check, so its result has no capability type
    (if (not (:ok a2))
      {:st st2 :val OPAQUE}
      ;; E33's relation, resolved once per produced entry: destination AND
      ;; obligation delta, from the source state the consumed capability is in.
      (let [states (reduce (fn [m e] (assoc m e (resolve-produced st2 sp opsym e (:lcs a2))))
                           {} prod)
            bad    (filter (fn [e] (not (:ok (get states e)))) prod)]
        (if (seq bad)
          (do (doseq [e bad]
                (report! {:kind :annotation-underived-state :op opsym :cap (:cap e)
                          :detail (:why (get states e))}))
              {:st st2 :val OPAQUE})
          (let [inp  (filter in-place? prod)
                outp (vec (remove in-place? prod))
                st3  (reduce (fn [s e] (apply-in-place s sp opsym e nodes (:lcs a2) states))
                             st2 inp)]
            {:st st3
             :val (with-refinements st3 sp opsym nodes (:lcs a2) outp
                                    (produced-value st3 opsym outp states))}))))))

(defn- report-no-signature! [st callee vals nodes]
  (doseq [i (range (count vals))]
    ;; a consumed capability handed to anything at all is a use-after-move
    (doseq [l (dead-leaves (nth vals i))]
      (report-use-after-move! st (:val l) callee))
    (doseq [e (live-caps st (nth vals i))]
      (let [lc (:lc e)]
        (report! {:kind :no-signature :cap (:cap lc)
                  :detail [(str "callee        " (or callee "a computed function")
                                "  declares no capability signature")
                           (str "argument      " i (path-str (:path e)) "  "
                                (if (:name lc) (str "`" (:name lc) "` : ") "")
                                (:cap lc) "@" (:state lc))
                           (str "at            " (pir/site (:pos st)))
                           (str "a capability may not be passed to a function that does not")
                           (str "declare :consumes / :borrows / :produces for it")
                           (str "in            " (:in st))]}))))
  nil)

(defn- w-plain-invoke [st node callee]
  (let [r    (w-seq (assoc st :use-ctx callee) (:args node))
        st1  (assoc (:st r) :use-ctx nil)]
    (report-no-signature! st1 callee (:vals r) (:args node))
    {:st st1 :val OPAQUE}))

(defn- projection-index
  "`first`, `second`, and `nth` with a constant index are TUPLE ELIMINATORS: they
  are how a capability gets back out of a positioned :produces. They move
  nothing and consume nothing. Any other function applied to a value holding a
  capability is a boundary and is reported.

  THE 3-ARITY IS DESTRUCTURING. `(let [[c frames] r] …)` lowers to
  `(nth G__287 0 nil)` — a THIRD argument, the not-found default — and requiring
  exactly two arguments here rejected every destructuring bind of a capability.
  Found by testing `report-limits` item 8, which had claimed the opposite risk.
  The not-found default is sound to ignore: an index past the end of the
  abstract tuple already yields OPAQUE."
  [opsym args]
  (cond
    (= 'clojure.core/first opsym)  0
    (= 'clojure.core/second opsym) 1
    (and (= 'clojure.core/nth opsym)
         (contains? #{2 3} (count args))
         (= :const (:op (nth args 1)))
         (integer? (:val (nth args 1))))
    (:val (nth args 1))
    :else nil))

(defn- w-projection [st node i]
  (let [r    (w-seq st (:args node))
        st1  (:st r)
        v    (first (:vals r))]
    (if (= :tuple (:v v))
      {:st st1 :val (or (nth' (:items v) i) OPAQUE)}
      ;; not a tuple: projecting a bare capability is a boundary like any other
      (do (report-no-signature! st1 (str "clojure.core/…" ) (:vals r) (:args node))
          {:st st1 :val OPAQUE}))))

(defn- w-invoke [st sp node]
  (let [f (:fn node)]
    (if (= :var (:op f))
      (let [opsym (symbol (:ns f) (:name f))
            ann   (get (:operations sp) opsym)
            pidx  (projection-index opsym (:args node))]
        (cond
          ann        (w-annotated-invoke st sp node opsym ann)
          (not (nil? pidx)) (w-projection st node pidx)
          :else      (w-plain-invoke st node opsym)))
      (let [rf (w st f)]
        (w-plain-invoke (:st rf) node nil)))))

;; LINEAR — a live, non-terminal capability whose binding goes out of scope.
;; A capability that is REACHABLE IN THE RESULT — at any position, not only as
;; the result itself — has not gone out of scope; it left with the value.
(defn- check-scope-exit [st sp bids result-val where]
  (let [escaping (held-bids result-val)]
   (reduce
    (fn [s bid]
      (let [c (get (:caps s) bid)]
        (if (or (nil? c)
                (contains? (:moved s) bid)
                (contains? escaping bid)
                ;; THE LEAK RULE NOW ASKS ABOUT THE OBLIGATION, NOT THE STATE.
                ;; For every declaration that names no `:obligation` the two
                ;; are the same question and the answer is identical; the
                ;; separation is what lets an absorbing terminal state keep the
                ;; NAME alive without keeping the DEBT alive (E33).
                (not (owes? sp c)))
          s
          (do (report! {:kind :dangling :cap (:cap c)
                        :detail (concat
                                  [(str "capability    `" (:name c) "` : " (:cap c)
                                        "@" (:state c))
                                   (str "bound at      " (pir/site (:pos c)))
                                   (str "goes out of scope at " where
                                        " still owing its destructor")]
                                  ;; CANCELLED IS NOT DISCHARGED. The verdict and
                                  ;; the kind are the ordinary leak's — what is
                                  ;; added is why the program's author may have
                                  ;; thought otherwise.
                                  (if (= (:state c) (cancelled-state-of sp (:cap c)))
                                    (cancelled-leak-lines sp (:cap c) (:state c))
                                    [])
                                  [(str "in            " (:in s))])})
              s))))
    st bids)))

(defn- rebind
  "Give `v` to the name `nm`. AFFINE: every live capability position in `v` is
  MOVED out of whatever binding held it and re-bound under a FRESH binding id
  (E6 probe 2 — there is no non-moving alias). The fresh id at every binding
  occurrence is the alpha-conversion §2.1 says `:local`'s name-only shape forces
  on a checker. Returns {:st :val :bids}."
  [st nm v pos]
  (let [r (walk-leaves
            st v
            (fn [s leaf path]
              (let [lc (live-cap s leaf)]
                (if (nil? lc)
                  {:st s :val leaf}
                  (let [s1 (if (= :cap (:v leaf))
                             (mark-moved s (:bid leaf)
                                         {:by (str "binding `" nm (path-str path)
                                                   "` (affine move)")
                                          :pos pos})
                             s)
                        b  (fresh-bid)
                        m  {:cap (:cap lc) :state (:state lc)
                            :name (str nm (path-str path))
                            :pos pos :fn-depth (:fn-depth s)
                            ;; REFINEMENT: an affine move carries the
                            ;; ghost state with the capability.
                            :refine (:refine lc) :rlog (:rlog lc)}
                        ;; and so does the OBLIGATION, which is a different
                        ;; thing from the state and travels with the resource
                        ;; rather than with the name (E33).
                        m  (if (contains? lc :owes) (assoc m :owes (:owes lc)) m)]
                    {:st (bind-cap s1 b m)
                     :val {:v :cap :bid b}})))))]
    {:st (:st r) :val (:val r) :bids (vec (map (fn [l] (:bid (:val l)))
                                               (cap-leaves (:val r))))}))

(defn- w-bindings
  "Bind a :let / :loop binding vector."
  [st bindings]
  (reduce
    (fn [acc b]
      (let [nm   (nth b 0)
            init (nth b 1)
            rr   (w (:st acc) init)
            st1  (:st rr)
            rb   (rebind st1 nm (:val rr) (:pos st1))
            ;; REFINEMENT: what this NAME denotes, as an abstract integer and as
            ;; an abstract octet length. Costs one atom per binding and is what
            ;; lets a run-time length appear on both sides of an obligation.
            ints (bound-ints st1 nm init)
            st2  (bind-name (:st rb) nm {:bid (first (:bids rb)) :val (:val rb)
                                         :int (:int ints) :olen (:olen ints)})]
        {:st st2
         :bids (into (:bids acc) (:bids rb))
         :vals (conj (:vals acc) (:val rb))
         :shapes (conj (:shapes acc) (shape-of st2 (:val rb)))}))
    {:st st :bids [] :vals [] :shapes []} bindings))

(defn- w-let [st sp node]
  (let [r   (w-bindings (push-scope st) (:bindings node))
        rb  (w (:st r) (:body node))
        st2 (check-scope-exit (:st rb) sp (:bids r) (:val rb) "the end of the let")]
    {:st (pop-scope st2) :val (:val rb)}))

;; PRESERVE (controlflow.py's loop rule) at every back edge
(defn- w-recur [st sp node]
  (let [lc-ctx (:loop-ctx st)
        r      (w-seq st (:args node))
        st1    (:st r)
        vals   (:vals r)]
    (if (nil? lc-ctx)
      {:st (assoc st1 :bottom true) :val OPAQUE}
      (let [st2
            (reduce
              (fn [s i]
                (let [want (nth' (:shapes lc-ctx) i)
                      got  (shape-of s (nth' vals i))]
                  (cond
                    (and (empty? want) (empty? got)) s

                    (not= (map (fn [e] [(:path e) (:cap e) (:state e)]) want)
                          (map (fn [e] [(:path e) (:cap e) (:state e)]) got))
                    (do (report! {:kind :loop-not-preserving
                                  :cap (:cap (or (first want) (first got)))
                                  :detail [(str "back edge at  " (pir/site (:pos s)))
                                           (str "loop binding " i " entered holding "
                                                (if (empty? want) "no capability"
                                                    (str/join ", "
                                                      (map (fn [e] (str (:cap e) "@" (:state e)
                                                                        (path-str (:path e))))
                                                           want))))
                                           (str "and re-enters holding "
                                                (if (empty? got) "no capability"
                                                    (str/join ", "
                                                      (map (fn [e] (str (:cap e) "@" (:state e)
                                                                        (path-str (:path e))))
                                                           got))))
                                           (str "the body is not environment-preserving;"
                                                " it cannot run twice")
                                           (str "in            " (:in s))]}) s)

                    :else
                    (reduce (fn [s2 e]
                              (mark-moved s2 (:bid (:lc e))
                                          {:by "recur (affine move into the loop binding)"
                                           :pos (:pos s2)}))
                            s (live-caps s (nth' vals i))))))
              st1 (range (count (:shapes lc-ctx))))
            ;; every loop binding's OLD capability must be gone by the back edge
            st3 (check-scope-exit st2 sp (:bids lc-ctx) OPAQUE "the loop back edge")
            ;; nothing outside the loop may have been consumed inside it
            st4 (reduce (fn [s bid]
                          (if (and (contains? (:moved s) bid)
                                   (not (contains? (:entry-moved lc-ctx) bid)))
                            (let [c (get (:caps s) bid)]
                              (report! {:kind :loop-not-preserving :cap (:cap c)
                                        :detail [(str "back edge at  " (pir/site (:pos s)))
                                                 (str "`" (:name c) "` : " (:cap c)
                                                      " was consumed inside the loop body")
                                                 (str "but is bound outside it — the body cannot run twice")
                                                 (str "in            " (:in s))]})
                              s)
                            s))
                        st3 (:outer lc-ctx))]
        {:st (assoc st4 :bottom true) :val OPAQUE}))))

(defn- w-loop [st sp node]
  (let [r    (w-bindings (push-scope st) (:bindings node))
        ;; REFINEMENT: a refinement does not cross a loop boundary, in EITHER
        ;; direction. The body is walked once and the trip count is data, so
        ;; every ghost variable live at loop entry becomes unknown, and so does
        ;; every one leaving on the loop's result. A capability created and
        ;; discharged inside one body is untouched, which is the class that
        ;; still decides.
        st1  (widen-caps (:st r) (keys (:caps (:st r)))
                         (str "unknown here — the capability crossed a loop boundary (loop entry, "
                              (pir/site (:pos st)) ")"))
        outer (remove (fn [b] (contains? (set (:bids r)) b)) (keys (:caps st1)))
        ctx  {:bids (:bids r) :shapes (:shapes r)
              :entry-moved (:moved st1) :outer (vec outer)}
        rb   (w (assoc st1 :loop-ctx ctx) (:body node))
        stb  (assoc (:st rb) :loop-ctx (:loop-ctx st))
        st2  (check-scope-exit stb sp (:bids r) (:val rb) "the end of the loop")
        st3  (widen-caps st2 (held-bids (:val rb))
                         (str "unknown here — the capability crossed a loop boundary (loop exit, "
                              (pir/site (:pos st)) ")"))]
    {:st (pop-scope st3) :val (:val rb)}))

;; REFINEMENT at a join: a ghost variable with two different values after the
;; two arms is unknown afterwards. Sound and cheap; no attempt is made to relate
;; the two, which would need the case split `perturb.refine` does not have.
(defn- join-refine [st1 merged sa sb]
  (reduce
    (fn [acc bid]
      (let [ca (get (:caps sa) bid)
            cb (get (:caps sb) bid)]
        (if (and ca cb (seq (:refine ca)) (not= (:refine ca) (:refine cb)))
          (assoc acc bid
                 (assoc (get acc bid)
                        :refine (reduce (fn [e k] (assoc e k (ref/top "unknown here — the two arms of an if left it with different values")))
                                        {} (keys (:refine ca)))
                        :rlog (conj (vec (:rlog (get acc bid)))
                                    (str "  ghost state widened to unknown at the if at "
                                         (pir/site (:pos st1))))))
          acc)))
    merged (keys merged)))

;; JOIN (controlflow.py's if rule) with bottom for non-local exits
(defn- join-vals [st a b]
  (cond
    (and (= :tuple (:v a)) (= :tuple (:v b))
         (= (count (:items a)) (count (:items b))))
    (tuple (map (fn [i] (join-vals st (nth (:items a) i) (nth (:items b) i)))
                (range (count (:items a)))))
    (and (= :cap (:v a)) (= :cap (:v b)) (= (:bid a) (:bid b))) a
    (and (= :fresh (:v a)) (= :fresh (:v b))
         (= (:cap a) (:cap b)) (= (:state a) (:state b))) a
    :else OPAQUE))

;; --- the case split: eliminating a sum at an `if` (E26 finding 7) ------------
;;
;; A sum `:to` states that one operation has several destinations; a
;; DISCRIMINATOR states which of them a predicate's two arms rule out. This is
;; the only place the two meet, and it is deliberately the smallest thing that
;; could work:
;;
;;   - the test must be a DIRECT invoke of a declared discriminator. `(let [ok
;;     (autocommit? t)] (if ok …))` narrows nothing, because the checker has no
;;     boolean domain and inventing one to carry the fact would be a second
;;     abstract value with its own join rule;
;;   - the discriminated argument must be a LOCAL naming the capability bare.
;;     A capability inside a tuple at the test position is not narrowed;
;;   - narrowing INTERSECTS. It never widens, never introduces a state the
;;     capability was not already in, and does nothing at all to a capability
;;     that is not in a sum — which is what keeps every pre-existing program's
;;     verdict exactly where it was;
;;   - an EMPTY intersection leaves the state alone. The arm is unreachable, and
;;     saying so would need a bottom for state as well as for control flow.
;;
;; NOTHING VERIFIES THE DISCRIMINATOR. `autocommit?`'s body is an axiom exactly
;; as a transition's is; the declaration is believed. See report-limits item 14.

(defn- narrow-state
  "Intersect a sum with what one arm of a discriminator knows."
  [state arm]
  (if (not (sum? state))
    state
    (let [keep (filter (fn [m] (admits? arm m)) (sum-members state))]
      (if (empty? keep) state (norm-state keep)))))

(defn- discriminator-narrowings
  "The narrowings the test of an `if` licenses: [{:bid :then :else}]. Empty
  unless the test is an invoke of a declared discriminator whose `:arg` position
  is a local holding a LIVE capability of the declared type in a SUM state."
  [st sp node]
  (let [ds (get (:discriminators sp) (invoke-sym node))]
    (if (empty? ds)
      []
      (remove
        nil?
        (map (fn [d]
               (let [a (arg-node node (:arg d))]
                 (when (and a (= :local (:op a)))
                   (let [e  (lookup st (:name a))
                         lc (when e (live-cap st (:val e)))]
                     (when (and lc (:bid lc) (= (:cap lc) (:cap d)) (sum? (:state lc)))
                       {:bid  (:bid lc)
                        :then (narrow-state (:state lc) (:true d))
                        :else (narrow-state (:state lc) (:false d))})))))
             ds)))))

(defn- apply-narrowings
  "Replace the state of each narrowed capability for the walk of one arm."
  [st narrs k]
  (reduce (fn [s n]
            (let [c (get (:caps s) (:bid n))]
              (if (nil? c) s (assoc s :caps (assoc (:caps s) (:bid n)
                                                   (assoc c :state (get n k)))))))
          st narrs))

(defn- restore-narrowings
  "Put the FULL sum back after the join. Outside the `if` the program no longer
  knows which arm ran, so neither may the checker."
  [caps st1 narrs]
  (reduce (fn [m n]
            (let [c0 (get (:caps st1) (:bid n))
                  c  (get m (:bid n))]
              (if (or (nil? c0) (nil? c)) m
                  (assoc m (:bid n) (assoc c :state (:state c0))))))
          caps narrs))

(defn- w-if [st sp node]
  (let [rt    (w st (:test node))
        st1   (:st rt)
        narrs (discriminator-narrowings st1 sp (:test node))
        ra  (w (apply-narrowings st1 narrs :then) (:then node))
        rb  (w (apply-narrowings st1 narrs :else) (:else node))
        sa  (:st ra)
        sb  (:st rb)]
    (cond
      (and (:bottom sa) (:bottom sb)) {:st (assoc st1 :bottom true) :val OPAQUE}
      (:bottom sa) {:st sb :val (:val rb)}
      (:bottom sb) {:st sa :val (:val ra)}
      :else
      (let [bids (keys (:caps st1))
            bad  (filter (fn [b] (not= (contains? (:moved sa) b)
                                       (contains? (:moved sb) b)))
                         bids)]
        (doseq [b bad]
          (let [c (get (:caps st1) b)]
            (report! {:kind :join :cap (:cap c)
                      :detail [(str "capability    `" (:name c) "` : " (:cap c)
                                    "@" (:state c))
                               (str "at the if at  " (pir/site (:pos st1)))
                               (str "consumed in the "
                                    (if (contains? (:moved sa) b) "then" "else")
                                    " arm and not in the other")
                               (str "\"may or may not have been moved\" is not a mode;"
                                    " no sound join exists")
                               (str "in            " (:in st1))]})))
        (let [va (:val ra) vb (:val rb)
              la (shape-of sa va) lb (shape-of sb vb)]
          (when (not= (map (fn [e] [(:path e) (:cap e) (:state e)]) la)
                      (map (fn [e] [(:path e) (:cap e) (:state e)]) lb))
            (report! {:kind :join :cap (:cap (or (first la) (first lb)))
                      :detail [(str "at the if at  " (pir/site (:pos st1)))
                               (str "the two arms yield different capability shapes")
                               (str "then: " (pr-str (map (fn [e] [(:path e) (:state e)]) la)))
                               (str "else: " (pr-str (map (fn [e] [(:path e) (:state e)]) lb)))
                               (str "in            " (:in st1))]}))
          ;; REFINEMENT: a ghost variable the two arms disagree about becomes
          ;; unknown. No diagnostic here — the join of the MODES is what the
          ;; `join` rule is about, and an obligation later discharged against an
          ;; unknown is refused where it is discharged, with the reason.
          ;; THE JOIN RULE IS UNCHANGED, INCLUDING WHERE A NARROWING MADE IT
          ;; BITE. `restore-narrowings` only undoes the narrowing itself: if two
          ;; arms resolved a sum and then left the capability in states the join
          ;; objects to, the objection above has already been reported and this
          ;; does not soften it.
          ;;
          ;; A narrowing DOES survive when the other arm ended in `throw` or
          ;; `recur`: those cases return that arm's state above, unrestored,
          ;; because the path that would have contradicted it does not reach
          ;; here. Sound, and nothing in the corpus exercises it.
          {:st (assoc st1 :caps (restore-narrowings
                                  (join-refine st1 (merge (:caps sa) (:caps sb)) sa sb)
                                  st1 narrs)
                          :moved (merge (:moved sa) (:moved sb)))
           :val (join-vals st1 va vb)})))))

(defn- w-composite
  "A map, set, or assignment target. Unlike a vector, these have no path syntax
  in an annotation, so a capability entering one cannot be followed out."
  [st node kind items]
  (let [r (w-seq st items)]
    (doseq [v (:vals r)]
      (doseq [e (live-caps (:st r) v)]
        (let [lc   (:lc e)
              ;; THE TRANSITIVE RULE AGAIN, at the other duplication site. The
              ;; container is persistent and immutable and that is exactly the
              ;; point: `[:coll …]` is shareable iff its element profile is, and
              ;; an immutable shell around a capability does not launder the
              ;; route it carries.
              prof [:coll (share-profile (:st r) v)]]
          (report! {:kind :escape :cap (:cap lc)
                    :share (sh/classify (sh/interfaces) prof)
                    :detail (vec (concat
                                   [(str "capability    "
                                         (if (:name lc) (str "`" (:name lc) "` : ") "")
                                         (:cap lc) "@" (:state lc))
                                    (str "enters a " kind " at " (pir/site (:pos (:st r))))]
                                   (share-lines prof)
                                   [(str "a capability path names TUPLE positions only, so the")
                                    (str "checker cannot follow it out of a " kind " (E13, E15)")
                                    (str "in            " (:in (:st r)))]))}))))
    {:st (:st r) :val OPAQUE}))

(defn- w-vector
  "A vector IS the tuple the abstract domain models, so a capability may pass
  through one. This is what positioned `:produces [{… :at [0]}]` describes, and
  it is the difference between E15's `ping` and `ping-tuple`."
  [st node]
  (let [r (w-seq st (:items node))]
    {:st (:st r) :val (tuple (:vals r))}))

(defn- w-fn [st sp node]
  ;; A closure body is analysed for diagnostics, but its state does not propagate:
  ;; the checker does not model when (or how often) the closure runs.
  (doseq [ar (:arities node)]
    ;; WHO GETS THE CLOSURE. `:use-ctx` names the callee whose ARGUMENT this fn
    ;; node is, and it is recorded here because inside the body it is
    ;; immediately overwritten by the closure's own calls. It is the only thing
    ;; that distinguishes `(future #(… conn …))` — a route across a thread
    ;; boundary, which the declared contention axis decides — from
    ;; `(thrown-by #(… conn …))`, where the callee's retention contract is
    ;; undeclared and the rule REFUSES rather than guessing. See
    ;; `perturb.share/capture-disposition`.
    (let [st1 (push-scope (assoc (assoc st :closure-consumer (:use-ctx st))
                                 :fn-depth (inc (:fn-depth st))))
          st2 (reduce (fn [s p] (bind-name s p {:bid (fresh-bid) :val OPAQUE}))
                      st1 (:params ar))
          st3 (if (:rest ar)
                (bind-name st2 (:rest ar) {:bid (fresh-bid) :val OPAQUE}) st2)]
      (w (assoc st3 :loop-ctx nil) (:body ar))))
  {:st st :val OPAQUE})

(defn w [st node]
  (if (nil? node)
    {:st st :val OPAQUE}
    (let [sp (:spec st)
          st (if (:pos node) (assoc st :pos (:pos node)) st)
          op (:op node)]
      (cond
        (= op :local)  (w-local st node)
        (= op :invoke) (w-invoke st sp node)
        (= op :let)    (w-let st sp node)
        (= op :loop)   (w-loop st sp node)
        (= op :recur)  (w-recur st sp node)
        (= op :if)     (w-if st sp node)
        (= op :fn)     (w-fn st sp node)
        (= op :vector) (w-vector st node)
        (= op :set)    (w-composite st node "set" (:items node))
        (= op :map)    (w-composite st node "map"
                                    (reduce (fn [a p] (conj (conj a (first p)) (second p)))
                                            [] (:pairs node)))
        (= op :do)
        (let [r (reduce (fn [acc s]
                          (let [rr (w (:st acc) s)]
                            (doseq [e (live-caps (:st rr) (:val rr))]
                              (let [lc (:lc e)]
                                (when (and (nil? (:bid lc))
                                           (owes? sp lc))
                                  (report! {:kind :dangling :cap (:cap lc)
                                            :detail [(str "capability    " (:cap lc) "@" (:state lc)
                                                          (path-str (:path e)))
                                                     (str "produced at   " (pir/site (:pos (:st rr))))
                                                     (str "and discarded in statement position")
                                                     (str "in            " (:in (:st rr)))]}))))
                            {:st (:st rr)}))
                        {:st st} (:statements node))]
          (w (:st r) (:ret node)))

        (= op :throw)  (let [r (w st (:expr node))]
                         {:st (assoc (:st r) :bottom true) :val OPAQUE})
        (= op :coerce) (w st (:expr node))
        (= op :host-call)
        (let [rt (w st (:target node))]
          (w-plain-invoke (:st rt) node (str "." (:method node))))
        (= op :try)
        (let [r (w st (:body node))
              r2 (w (:st r) (:catch-body node))
              r3 (w (:st r2) (:finally node))]
          (report! {:kind :unsupported-construct
                    :detail [(str "a try/catch at " (pir/site (:pos (:st r3))))
                             "the checker walks its arms but has no exception-path join;"
                             "any capability discipline across the handler is UNCHECKED"
                             (str "in            " (:in st))]})
          {:st (:st r3) :val OPAQUE})
        (= op :set-var)  (w-composite st node "var assignment" [(:val node)])
        (= op :set-field) (w-composite st node "field assignment" [(:obj node) (:val node)])
        (= op :def)      (w st (:init node))
        :else {:st st :val OPAQUE}))))

;; --- per-def entry ----------------------------------------------------------

(defn- primitive? [sp opsym] (contains? (:transitions-of sp) opsym))
(defn- representation? [sp opsym] (contains? (:representation sp) opsym))
(defn- axiom? [sp opsym] (or (primitive? sp opsym) (representation? sp opsym)))

;; --- the declaration language's own rules -----------------------------------
;;
;; Everything from here to `check-def!` reads an ANNOTATION against a DECLARED
;; MACHINE and never reads a body. These are the rules PERTURB-DESIGN §4.6 and
;; E18 finding 1 say were missing; each one refuses a shape the language could
;; previously state and the checker could previously only misread.

(defn- entries-for [entries c] (filter (fn [e] (= c (:cap e))) entries))

(defn- annotation-faults
  "Faults that make an annotation UNREADABLE, as data. Returns [] for a usable
  annotation.

  Two faults, both found by E18:

    :annotation-unpositioned          a :consumes / :borrows entry with no `:arg`
    :annotation-duplicates-capability the same capability in :borrows and
                                      :produces

  This does NOT establish that a fault-free annotation describes the code.
  Nothing here reads a body; `check-def!` does that, separately, and only for a
  fault-free annotation — a refused annotation is not a specification, so there
  is nothing to check a body against."
  [ann]
  (let [ins (concat (:consumes ann) (:borrows ann))]
    (vec (concat
           (map (fn [e] {:fault :annotation-unpositioned :cap (:cap e) :entry e})
                (filter (fn [e] (nil? (:arg e))) ins))
           (map (fn [c] {:fault :annotation-duplicates-capability :cap c})
                (filter (fn [c] (some (fn [p] (= c (:cap p))) (:produces ann)))
                        (distinct (map :cap (:borrows ann)))))))))

(defn- check-annotation-wellformed!
  "Report every fault of `ann`. Returns true when the annotation is USABLE."
  [opsym ann]
  (let [fs (annotation-faults ann)]
    (doseq [f fs]
      (if (= :annotation-unpositioned (:fault f))
        (report! {:kind :annotation-unpositioned :op opsym :cap (:cap f)
                  :detail [(str "operation     " opsym)
                           (str "a :consumes / :borrows entry names " (:cap f) "@"
                                (want-str (:state (:entry f))) " and carries no `:arg`")
                           "every such entry must say WHICH parameter holds the capability"
                           "matching specs to parameters in order was this checker's own"
                           "convention, not §1.2's, and it is removed (E17 nonclaim 4, E18 1(d))"
                           "the annotation is REFUSED and this operation's body is not checked"]})
        (report! {:kind :annotation-duplicates-capability :op opsym :cap (:cap f)
                  :detail [(str "operation     " opsym)
                           (str (:cap f) " is both :borrows and :produces")
                           "a borrowed capability stays the CALLER's; producing it as well"
                           "mints a SECOND abstract capability for one runtime object, and"
                           "the caller is then reported for leaking whichever one it does"
                           "not dispose of (PERTURB-DESIGN E18 finding 1(c))"
                           "write :consumes + :produces to hand it back, or :borrows alone"
                           "to look at it without taking it"
                           "the annotation is REFUSED and this operation's body is not checked"]})))
    (empty? fs)))

(defn- check-annotation-consistency!
  "Compare an annotation against the declared machines, PER CAPABILITY.

  The old form took the annotation's FIRST `:consumes` entry and its FIRST
  `:produces` entry and compared that one pair against whichever single
  transition the operation-keyed table happened to hold. With two machines that
  is a category error: it compared `perturb.http/body-finish!`'s ResponseBody
  `:from` against its ServerConn `:to` and reported `:open -> :reading`, a
  disagreement that was correct about the data and wrong about the program (E18
  finding 1(a)).

  Four rules, one per capability the operation declares an edge of:

    1. the `:consumes` state for that capability must be the declared `:from`.
       A CREATING edge declares `:from nil` and its annotation consumes nothing,
       so nil = nil and no extra rule is needed — see the note on `:created` in
       `perturb.nrepl` for why the alternative was rejected (E18 finding 1(b)).
    2. the `:produces` state must be the declared `:to`, EXCEPT that a `:to`
       which is TERMINAL need not be produced at all. `produced and dropped` and
       `not produced` are indistinguishable to every rule this checker has — the
       leak rule exempts terminal states — so requiring the annotation to state
       which one it meant would be requiring a distinction the checker cannot
       make. `body-finish!` consumes a ResponseBody and hands back only the
       connection, and that is the shape.
    3. a capability named in the annotation of an operation that IS a primitive
       must have a declared edge for it. This is what forces `respond-begin` to
       appear in ResponseBody's `:transitions` and `body-finish!` in
       ServerConn's: both mint or move a machine they did not declare.
    4. a capability consumed or produced TWICE by one primitive has no single
       edge to compare, and the checker says so rather than taking the first.
       Nothing in perturb has this shape; the silent `first` that used to stand
       here is precisely what hid (a) for two sections.

  RULE 2 HAS A SECOND HALF SINCE E26 FINDING 7. The declared edges are GROUPED by
  `[operation capability from]`. A group with one `:to` is the rule above,
  unchanged and unchanged in every diagnostic it prints. A group with SEVERAL
  `:to`s is a sum, and then the `:produces` `:state` for that capability must be
  a COLLECTION equal AS A SET to the group's destinations. Under-covering leaves
  a caller unprepared for a state the operation can produce; over-covering forces
  case splits on states nothing reaches. Both are `annotation-inconsistent`, and
  the terminal exemption generalises the way the leak rule does: a sum need not
  be produced at all when EVERY member is terminal.

  RULE 2 HAS A THIRD HALF SINCE E33, AND IT IS A SUBTRACTION. An entry that
  OMITS `:state` is not repeating the machine — it is asking to be read off it —
  so there is nothing to compare and the comparison is SKIPPED for that side.
  That is the whole of E30 finding 3's fix: `shutdown-write!` has four `:from`s
  with four correspondingly different `:to`s, and under the old rule its one
  annotation had to disagree with three of the four groups. With `:state`
  omitted there are no groups to disagree with, because the annotation no longer
  states a destination the machine already states better.

  It does NOT establish that the operation's body performs the transition. Every
  transition body is an axiom, unchanged since `mode_checker.py`."
  [sp opsym ann]
  ;; A `:state` MAY ONLY BE OMITTED WHERE THERE IS A MACHINE TO READ IT OFF.
  ;; A derived operation — one that is not a declared transition of the
  ;; capability — has no edges, so "ask the machine" has no answer and the
  ;; entry is refused where it is written rather than silently admitting
  ;; everything.
  (doseq [e (concat (:borrows ann) (:consumes ann) (:produces ann))]
    (when (and (derived-state? e)
               (empty? (get (:primitives sp) [(:cap e) opsym])))
      (report! {:kind :annotation-underived-state :op opsym :cap (:cap e)
                :detail [(str "operation     " opsym " names " (:cap e)
                              " and writes no `:state`")
                         (str "but " (:cap e) " declares no transition edge for " opsym
                              ", so there is no")
                         "machine to read the state off. `:state` may be omitted ONLY on an"
                         "operation that IS a declared transition of that capability — that is"
                         "what makes the omission a READ of E33's relation rather than a blank"
                         "cheque (E33; report-limits item 16)"]})))
  (let [ts (get (:transitions-of sp) opsym)]
    (when (seq ts)
      (let [declared (set (map :cap ts))]
        (doseq [c (distinct (map :cap (concat (:consumes ann) (:produces ann))))]
          (when (not (contains? declared c))
            (report! {:kind :annotation-undeclared-transition :op opsym :cap c
                      :detail [(str "operation     " opsym " is a declared transition of "
                                    (str/join ", " (sort (map str declared))))
                               (str "and its annotation also moves " c)
                               (str "but " c "'s machine declares no edge for " opsym)
                               "an operation that advances two machines must declare an"
                               "edge in each; the primitive table is keyed [capability op]"]}))))
      ;; GROUPED BY [capability from]. `opsym` is fixed here, so this is the
      ;; [operation capability from] grouping. Every existing machine yields
      ;; one-element groups and reaches the same comparison it always did.
      (let [groups (reduce (fn [acc t]
                            (update acc [(:cap t) (:from t)]
                                    (fn [v] (conj (or v []) t))))
                          {} ts)]
       (doseq [g (sort-by (fn [e] (str (first e))) (seq groups))]
        (let [c    (first (first g))
              from-decl (second (first g))
              tos  (vec (distinct (map :to (second g))))
              cs   (entries-for (:consumes ann) c)
              ps   (entries-for (:produces ann) c)]
          (if (or (> (count cs) 1) (> (count ps) 1))
            (report! {:kind :annotation-ambiguous-edge :op opsym :cap c
                      :detail [(str "operation     " opsym " declares the edge "
                                    from-decl " -> " (want-str (norm-state tos)) " of " c)
                               (str "and its annotation names " c " " (count cs)
                                    " time(s) in :consumes and " (count ps)
                                    " time(s) in :produces")
                               "there is no single pair to compare it against"]})
            (let [from (:state (first cs))
                  to   (:state (first ps))
                  ;; E33: an entry that OMITS `:state` is READ OFF the machine
                  ;; at each call site, so there is nothing here to compare and
                  ;; the corresponding half of the rule is skipped. Both halves
                  ;; are independent — an operation may state its source and
                  ;; derive its destination, which is the common shape.
                  from-derived? (and (seq cs) (derived-state? (first cs)))
                  to-derived?   (and (seq ps) (derived-state? (first ps)))
                  many? (> (count tos) 1)
                  to-decl (norm-state tos)
                  dropped-at-terminal?
                  (and (nil? to) (every? (fn [d] (terminal? sp c d)) tos))
                  agree? (if many?
                           (and (not (nil? to)) (= (set tos) (set (sum-members to))))
                           (= to (first tos)))
                  missing (vec (remove (fn [d] (contains? (set (sum-members to)) d)) tos))
                  extra   (vec (remove (fn [d] (contains? (set tos) d))
                                       (if (nil? to) [] (sum-members to))))]
              (when (or (and (not from-derived?) (not= from from-decl))
                        (and (not to-derived?)
                             (not agree?) (not dropped-at-terminal?)))
                (report! {:kind :annotation-inconsistent :op opsym :cap c
                          :detail
                          (concat
                            [(str "operation     " opsym)
                             (str "declared edge" (if many? "s    " "     ")
                                  (pr-str from-decl) " -> " (pr-str to-decl)
                                  (if many?
                                    (str "   (" (count tos)
                                         " destinations, chosen at run time)") ""))
                             (str "annotation says   " (pr-str from) " -> " (pr-str to))]
                            (if (and many? (= from from-decl))
                              (concat
                                [(str "a group of transitions sharing an :op and a :from must be")
                                 (str "matched by a :produces :state that is a COLLECTION equal to")
                                 (str "the group's :to's AS A SET")]
                                (if (seq missing)
                                  [(str "not covered   " (want-str missing))] [])
                                (if (seq extra)
                                  [(str "not declared  " (want-str extra))] []))
                              [(str "the declaration and the annotation disagree about "
                                    c "'s edge")]))}))))))))))

;; --- the cancelled state's own rule: A DECLARATION MUST NOT BE ABLE TO LIE ---

(defn check-cancellation-declarations!
  "Refuse a `:perturb.cap/cancelled` declaration that does not mean what it says.

  Every other key in a declaration is an AXIOM: `:from`/`:to` are believed,
  `:perturb.cap/discriminator` is believed, and `report-limits` items 1 and
  14(f) say so. This one cannot be, and the reason is specific rather than a
  general preference for checking things.

  A cancelled state is a claim about what is IMPOSSIBLE — that nothing but the
  destructor is legal from it. Every other axiom is a claim about what a body
  DOES, and a body that disobeys it is a wrong program that the checker was
  told to trust. This claim is instead discharged BY THE MACHINE ITSELF: the
  states an operation admits are the `:from`s written in the same declaration,
  so `only the destructor is legal here` is a property of the declaration, not
  of any body. A declaration that names a cancelled state with an ordinary
  outgoing edge would therefore be internally inconsistent, and every program
  holding that capability would be accepted on a premise the same file
  contradicts. So it is refused where it is written.

  Five rules, each a way the declaration could lie:

    1. the cancelled state must be a DECLARED state;
    2. it must NOT be terminal — if reaching it discharged the obligation, the
       mechanism would be silent discard with extra steps, which is precisely
       what Fowler §1.3 rejects and what §4.6 piece 5 exists to avoid;
    3. something must reach it, or the mechanism is decoration;
    4. something must leave it, or the state is inescapable and every program
       that reaches it leaks with no way to be written correctly — that is
       report-limits item 14(e)'s hazard, made LOUD instead of silent;
    5. EVERY edge that leaves it must be a DESTRUCTOR edge — every member of
       its `:to` terminal. This is the rule the mechanism is named for.

  One diagnostic per capability: the first rule it breaks. Returns nothing."
  [sp]
  (doseq [e (sort-by (fn [x] (str (first x))) (seq (:declarations sp)))]
    (let [c  (first e)
          d  (second e)
          cs (get d cap/cancelled-key)
          ts (:perturb.cap/typestate d)]
      (when (not (nil? cs))
        (let [states  (set (:states ts))
              term    (let [t (:terminal ts)] (if (coll? t) (set t) #{t}))
              out     (edges-from sp c cs)
              in      (filter (fn [t] (contains? (set (sum-members (:to t))) cs))
                              (edges-of sp c))
              bad-out (remove (fn [t] (destructor-edge? sp c t)) out)
              say (fn [lines]
                    (report! {:kind :cancelled-state-unsound :cap c
                              :detail (concat
                                        [(str "capability    " c " declares "
                                              cap/cancelled-key " " cs)]
                                        lines
                                        ["the DECLARATION is refused: a cancelled state is the one"
                                         "axiom this checker will not take on trust, because it is a"
                                         "claim about what is impossible and the machine beside it"
                                         "settles that claim (PERTURB-DESIGN §4.6 piece 5, E27)"])}))]
          (cond
            (not (contains? states cs))
            (say [(str "rule 1        the cancelled state must be one of the machine's own")
                  (str "declared      " (pr-str (vec (:states ts))))
                  (str cs " is not among them, so no edge can be checked against it")])

            (contains? term cs)
            (say [(str "rule 2        the cancelled state must NOT be terminal")
                  (str "declared      :terminal " (pr-str (:terminal ts)))
                  "a terminal cancelled state means reaching it DISCHARGES the"
                  "obligation, so `cancel and walk away` would type-check — that is"
                  "silent discard with extra steps, and Fowler §1.3 rejects it by name"
                  "as the reason affine types are not enough"])

            (empty? in)
            (say [(str "rule 3        nothing reaches " cs)
                  "no declared edge has it as a `:to`, so no operation can cancel this"
                  "capability and the declaration claims a mechanism that does not exist"])

            (empty? out)
            (say [(str "rule 4        nothing leaves " cs)
                  "no declared edge admits it as a `:from`, so a cancelled capability"
                  "can never be disposed of: it is not terminal, so every program that"
                  "reaches this state leaks and none of them can be written correctly"
                  "(report-limits item 14(e), made loud)"])

            (seq bad-out)
            (say (concat
                   [(str "rule 5        the ONLY edge out of a cancelled state may be the"
                         " destructor")
                    (str "              — an edge every member of whose `:to` is terminal")]
                   (map (fn [t]
                          (str "offending     " (:op t) "   " (want-str (:from t))
                               " -> " (want-str (:to t))
                               "   (not terminal)"))
                        bad-out)
                   ["a cancelled state that admits ordinary work is not MUST_CLOSE, it is"
                    "an ordinary state with a misleading name, and a program that reached"
                    "it would be accepted on the strength of a discipline nothing enforces"]))

            :else nil))))))

;; --- THE ABSORBING TERMINAL STATE, AND ITS ONE SIDE CONDITION ---------------
;;
;; PERTURB-DESIGN E30 finding 1 names the missing concept and E33 supplies the
;; side condition that makes it safe:
;;
;;   > an ABSORBING terminal state — a terminal state that admits its own
;;   > destructor and its observers as SELF-LOOPS. Not "terminal" in the current
;;   > sense (obligation discharged, name dead), but OBLIGATION DISCHARGED, NAME
;;   > STILL ALIVE, ONLY THESE OPERATIONS LEGAL.
;;
;;   > an observer self-loop must not re-acquire the discharged resource.
;;
;; The first half needs no rule at all once the three notions are separate: the
;; name stays alive because the annotation says `:produces … :arg 0` (in place)
;; or `:borrows` rather than `:consumes`, and the obligation stays discharged
;; because `:owes` is a fact about the resource rather than a re-derivation from
;; the state. `:closed -> :closed` is then an ordinary edge, exactly as
;; `shutdown-write!`'s `:write-shut -> :write-shut` self-loop already was — which
;; is E30's own corroboration that idempotence is not what `:linearity :once`
;; rejects, idempotence AT A TERMINAL STATE is.
;;
;; The SECOND half is a rule, and it is the load-bearing one. Nothing in the
;; machinery above stops a declaration writing an edge out of a discharged state
;; that lands somewhere non-terminal — `:closed -> :open`, a "reopen" — and such
;; an edge silently RE-ACQUIRES a resource the checker has already been told is
;; disposed of. Every program holding it would then be accepted on the strength
;; of an obligation nothing owes. So: an edge whose `:from` admits a terminal
;; state must DISCHARGE. A declaration that means to re-acquire must say
;; `:obligation :acquire`, in which case the state it lands in owes a destructor
;; again and the leak rule is back on — which is the honest encoding, and is
;; refused HERE only when it is written as an accident instead.

(defn check-absorbing-declarations!
  "Refuse an edge out of a discharged state that does not leave it discharged.

  Read off the machine, no body involved, one diagnostic per offending edge.
  `:terminal` is the declaration's own word for `nothing further is owed`, so an
  operation legal from a terminal state is by construction an operation on an
  already-disposed resource: an observer, or the destructor again. Either is
  fine. What is not fine is one that quietly puts the resource back in debt,
  because the obligation was already accounted for and there is nobody left to
  discharge it a second time."
  [sp]
  (doseq [e (sort-by (fn [x] (str (first x))) (seq (:declarations sp)))]
    (let [c    (first e)
          ts   (:perturb.cap/typestate (second e))
          term (let [t (:terminal ts)] (if (coll? t) (vec t) [t]))]
      (doseq [t (sort-by (fn [x] (str (:op x) (:from x) (:to x))) (edges-of sp c))]
        (let [srcs (vec (filter (fn [s] (and (not (nil? s)) (admits? (:from t) s))) term))
              d    (edge-delta sp c t)]
          (when (and (seq srcs) (not= :discharge d))
            (report! {:kind :absorbing-state-unsound :cap c :op (:op t)
                      :detail
                      [(str "capability    " c " declares :terminal " (pr-str term))
                       (str "edge          " (:op t) "   " (want-str (:from t))
                            " -> " (want-str (:to t))
                            (if (contains? t cap/result-key)
                              (str "   (result " (get t cap/result-key) ")") ""))
                       (str "              admits the DISCHARGED state(s) " (want-str srcs)
                            " and its obligation")
                       (str "              delta is " d ", not :discharge")
                       "an absorbing terminal state admits its destructor and its observers"
                       "as SELF-LOOPS: obligation discharged, name still alive, only these"
                       "operations legal. An edge out of it that does not discharge"
                       "RE-ACQUIRES a resource this declaration has already accounted for,"
                       "and every program holding it would be accepted on an obligation"
                       "nobody owes (PERTURB-DESIGN E30 finding 1, E33's side condition)."
                       "Write `:obligation :acquire` if re-acquisition is really meant — the"
                       "leak rule then applies to the reacquired handle, which is the honest"
                       "encoding and is what this refuses to infer on your behalf."]})))))))

;; --- SOURCE COLLECTIONS ARE SUGAR, AND ONLY WHERE THEY ARE SUGAR ------------
;;
;; E33: "with source collections as sugar ONLY where every source shares a
;; destination and obligation delta." `:from [:a :b] :to :x` expands to two
;; edges; that is sugar and it is fine. What is not sugar is a collection that
;; OVERLAPS another edge of the same operation with a different source, because
;; then one state has two destinations that the shorthand hides — and the reader
;; of the declaration cannot tell whether the author meant a sum or forgot the
;; overlap.
;;
;; TWO EDGES WITH THE *SAME* `:from` ARE NOT THIS. That is an honest sum (E26
;; finding 7's `begin`, three entries, one `:from :idle`), or a result-labelled
;; family (E33's `close!`, `:won` and `:lost`), and both are declared on purpose.
;; The rule fires only when the sources DIFFER and still overlap.

(defn check-edge-overlap!
  "Refuse a `:from` collection that overlaps a differently-sourced edge of the
  same operation with a different destination or obligation delta."
  [sp]
  (doseq [e (sort-by (fn [x] (str (first x))) (seq (:declarations sp)))]
    (let [c  (first e)
          es (edges-of sp c)
          by (reduce (fn [acc t] (update acc (:op t) (fn [v] (conj (or v []) t)))) {} es)]
      (doseq [g (sort-by (fn [x] (str (first x))) (seq by))]
        (let [op (first g)
              ts (second g)]
          (doseq [i (range (count ts))]
            (doseq [j (range (inc i) (count ts))]
              (let [a (nth ts i) b (nth ts j)]
                (when (and (not= (:from a) (:from b))
                           (or (= :any (:from a)) (= :any (:from b))
                               (seq (filter (fn [s] (admits? (:from b) s))
                                            (sum-members (:from a)))))
                           (or (not= (norm-state (:to a)) (norm-state (:to b)))
                               (not= (edge-delta sp c a) (edge-delta sp c b))))
                  (report! {:kind :edge-source-overlap :cap c :op op
                            :detail
                            [(str "capability    " c)
                             (str "edge          " op "   " (want-str (:from a))
                                  " -> " (want-str (:to a))
                                  "   (obligation " (edge-delta sp c a) ")")
                             (str "edge          " op "   " (want-str (:from b))
                                  " -> " (want-str (:to b))
                                  "   (obligation " (edge-delta sp c b) ")")
                             "their sources OVERLAP and are not the same source, and their"
                             "destinations or obligation deltas differ. A `:from` collection is"
                             "sugar for one edge per member and is legal ONLY where every member"
                             "shares a destination and a delta (E33); here it hides a"
                             "source-dependent destination behind a shorthand, and a reader"
                             "cannot tell an intended sum from a forgotten overlap."
                             "Write the sources out, or give the two edges the SAME `:from` and a"
                             "`:result` label each, which is how a genuine sum is declared."]}))))))))))

(defn check-def!
  "Check one captured :def node. Returns the number of diagnostics it produced."
  [sp node]
  (let [opsym (symbol (:ns node) (:name node))
        ann   (get (:operations sp) opsym)
        init  (:init node)
        before (count @diagnostics)
        ann-ok (if ann (check-annotation-wellformed! opsym ann) true)]
    (when ann (check-annotation-consistency! sp opsym ann))
    (cond
      (axiom? sp opsym) 0   ;; axiom of the machine, or inside its representation
      ;; A REFUSED ANNOTATION IS NOT A SPECIFICATION. There is nothing sound to
      ;; check the body against, so it is not checked and the refusal is the
      ;; whole of the rejection. That is the difference the fix makes: E18's
      ;; unpositioned helper drew FIVE diagnostics against calls in its body,
      ;; none of which named the annotation; it now draws one per unpositioned
      ;; entry, each naming it.
      (not ann-ok) 0
      (or (nil? init) (not= :fn (:op init))) 0
      :else
      (do
        (doseq [ar (:arities init)]
          (let [st0 {:scopes [{}] :caps {} :moved {} :pos (:pos node)
                     :in (str opsym) :spec sp :fn-depth 0 :bottom false
                     :loop-ctx nil :use-ctx nil}
                ;; A derived (non-primitive) operation's annotation binds its
                ;; parameters, and every entry says which parameter exactly.
                ;; `ann-ok` above guarantees `:arg` is present on all of them:
                ;; the in-order fallback is gone.
                specs (vec (concat (:borrows ann) (:consumes ann)))
                spec-for (fn [i] (first (filter (fn [e] (= i (:arg e))) specs)))
                ;; IN-PLACE `:produces` ENTRIES, BY ARGUMENT. A parameter that is
                ;; consumed and produced back IN PLACE stays the caller's — the
                ;; name it was passed under is still alive at the call site — so
                ;; it is not this function's to dispose of, exactly as a borrowed
                ;; parameter is not. What this function DOES owe is the state
                ;; transition it declared, and that is checked below.
                in-place-at (reduce (fn [m e] (if (in-place? e) (assoc m (:arg e) e) m))
                                    {} (:produces ann))]
            (let [r0  (reduce
                        (fn [acc i]
                          (let [s   (:st acc)
                                p   (nth (:params ar) i)
                                e   (spec-for i)]
                            (if (and ann e)
                              ;; the parameter holds the capability at the
                              ;; entry's path — bare if there is none
                              (let [bid (fresh-bid)
                                    ;; REFINEMENT: a parameter carries NO ghost
                                    ;; state. There is no interprocedural
                                    ;; refinement here, so an obligation on a
                                    ;; capability that arrived as an argument is
                                    ;; refused rather than guessed at — see
                                    ;; check-requires!.
                                    ;; A COLLECTION HERE IS `ANY OF`, NOT A SUM.
                                    ;; This is the `:from` side: the parameter
                                    ;; arrives in ONE of the admitted states and
                                    ;; the body may assume the weakest, so the
                                    ;; first is taken exactly as before E26
                                    ;; finding 7. A parameter that really is in a
                                    ;; sum state cannot be declared — see
                                    ;; report-limits item 14.
                                    s1  (bind-cap s bid
                                                  {:cap (:cap e)
                                                   :state (if (coll? (:state e))
                                                            (first (:state e)) (:state e))
                                                   :name (str p (path-str (entry-path e)))
                                                   :pos (:pos node) :fn-depth 0
                                                   :refine {} :rlog []})
                                    path (entry-path e)
                                    v   (if (empty? path)
                                          {:v :cap :bid bid}
                                          (tuple (map (fn [k]
                                                        (if (= k (first path))
                                                          {:v :cap :bid bid} OPAQUE))
                                                      (range (inc (first path))))))]
                                {:st (bind-name s1 p {:bid bid :val v})
                                 ;; a BORROWED parameter is not this function's
                                 ;; to consume: the caller keeps it, so it must
                                 ;; not be required to reach a terminal state
                                 ;; here. Only consumed parameters are — and an
                                 ;; IN-PLACE produced one is not consumed either,
                                 ;; because the caller's name survives the call.
                                 :bids (if (or (some (fn [b] (= b e)) (:borrows ann))
                                               (contains? in-place-at i))
                                         (:bids acc)
                                         (conj (:bids acc) bid))
                                 :in-place (if (contains? in-place-at i)
                                             (assoc (:in-place acc) i
                                                    {:bid bid :entry (get in-place-at i)
                                                     :param p})
                                             (:in-place acc))})
                              {:st (bind-name s p {:bid (fresh-bid) :val OPAQUE})
                               :bids (:bids acc) :in-place (:in-place acc)})))
                        {:st st0 :bids [] :in-place {}} (range (count (:params ar))))
                  st1 (:st r0)
                  rb  (w st1 (:body ar))
                  stb (:st rb)
                  got (shape-of stb (:val rb))]
              ;; A derived operation must return what it declares — at the
              ;; positions it declares. This is where `ping-tuple` stops being a
              ;; rejection: [conn :pinged] with `:produces [{… :at [0]}]` now
              ;; matches, and [:pinged conn] does not.
              ;; AN IN-PLACE PRODUCE ON A DERIVED OPERATION IS CHECKED, NOT
              ;; BELIEVED. The parameter is still bound at the end of the body,
              ;; so the state it ended in is observable here — unlike a declared
              ;; transition, whose body is an axiom. If the body did not move it
              ;; where the annotation says, that is a `produces-mismatch` about
              ;; a name rather than about a result position.
              (when ann
                (doseq [k (sort (keys (:in-place r0)))]
                  (let [ip  (get (:in-place r0) k)
                        c   (get (:caps stb) (:bid ip))
                        wnt (norm-state (:state (:entry ip)))]
                    (when (and c (not (contains? (:moved stb) (:bid ip)))
                               (not (derived-state? (:entry ip)))
                               (not (produced-ok? wnt (:state c))))
                      (report! {:kind :produces-mismatch :op opsym :cap (:cap (:entry ip))
                                :detail [(str "operation     " opsym " declares :produces "
                                              (:cap (:entry ip)) "@" (want-str wnt)
                                              " IN PLACE at `:arg " k "`")
                                         (str "parameter     `" (:param ip) "` ends the body in "
                                              (want-str (:state c)))
                                         "an in-place produce is a promise about the argument the"
                                         "caller still holds, and the body did not keep it"
                                         (str "at            " (pir/site (:pos stb)))]})))))
              (when ann
                (let [want (vec (sort-by (fn [e] (str (:path e)))
                                         (map (fn [p] {:path (entry-path p) :cap (:cap p)
                                                       :state (:state p)})
                                              (remove in-place? (:produces ann)))))]
                  (cond
                    (not= (map :path want) (map :path got))
                    (report! {:kind :produces-mismatch :op opsym
                              :cap (:cap (or (first want) (first got)))
                              :detail [(str "operation     " opsym " declares :produces at "
                                            (pr-str (mapv (fn [e] (path-str (:path e))) want)))
                                       (str "but its body yields capabilities at "
                                            (pr-str (mapv (fn [e] (path-str (:path e))) got)))
                                       (str "at            " (pir/site (:pos stb)))]})

                    ;; `produced-ok?` and not `state-ok?`: on the PRODUCED side a
                    ;; collection is a sum, so a body that yields one state does
                    ;; not satisfy an annotation promising three, and a body that
                    ;; yields a sum does not satisfy one promising a single state.
                    (not (every? (fn [i]
                                   (and (= (:cap (nth want i)) (:cap (nth got i)))
                                        (produced-ok? (:state (nth want i))
                                                      (:state (nth got i)))))
                                 (range (count want))))
                    (report! {:kind :produces-mismatch :op opsym :cap (:cap (first want))
                              :detail [(str "operation     " opsym " declares "
                                            (pr-str (mapv (fn [e] (str (:cap e) "@"
                                                                       (want-str (:state e)))) want)))
                                       (str "but its body yields "
                                            (pr-str (mapv (fn [e] (str (:cap e) "@" (:state e))) got)))
                                       (str "at            " (pir/site (:pos stb)))]})
                    :else nil)))
              ;; an UNannotated function may not hand a capability to its caller
              (when (and (nil? ann) (seq got))
                (doseq [e got]
                  (report! {:kind :escape :cap (:cap e)
                            :detail [(str "capability    " (:cap e) "@" (:state e)
                                          (path-str (:path e)))
                                     (str "is returned by " opsym
                                          ", which declares no :produces")
                                     (str "at            " (pir/site (:pos stb)))]})))
              (check-scope-exit stb sp (:bids r0) (:val rb)
                                (str "the end of " opsym)))))
        (- (count @diagnostics) before)))))

;; --- the gate ---------------------------------------------------------------

(defn check-namespace!
  "Check every captured :def of `ns-name`. Returns a vector of
  {:var sym :diagnostics [..]} in load order."
  [sp ns-name]
  (vec (map (fn [node]
              (let [before (count @diagnostics)]
                (check-def! sp node)
                {:var (symbol (:ns node) (:name node))
                 :diagnostics (vec (drop before @diagnostics))}))
            (pir/defs-in ns-name))))

(defn report-limits []
  ["WHAT THIS CHECKER CANNOT SEE"
   ""
   "  1. AXIOMS — the largest hole, and the number that looks best is the one to"
   "     distrust. Two classes of function have unchecked bodies: the declared"
   "     :transitions, and the operations listed in a capability's"
   "     :representation. perturb.nrepl has 5 of the second and 12 unchecked"
   "     concrete-map accesses; perturb.http has ZERO of the second and 31,"
   "     because every access there was written INSIDE a transition instead of in"
   "     a helper the list would have named. An empty :representation is not a"
   "     boundary that moved, it is a boundary that was hidden. Both lists are"
   "     placeholders for a module scope §1.2 does not have."
   "     PERTURB-DESIGN E17, E18 finding 4."
   ""
   "  2. :consumes / :produces are POSITIONED — `:arg n` names a parameter,"
   "     `:at [i]` a tuple position of the result — which is what let the real"
   "     client be annotated at all (E15). `:arg` is now MANDATORY: the fallback"
   "     that matched specs to parameters in order is removed, and an entry"
   "     without `:arg` is refused where it is written rather than guessed at."
   "     One limit remains: paths are ONE level of tuple nesting only."
   ""
   "  3. Closure bodies are walked for diagnostics but their state does not"
   "     propagate: the checker does not model whether or how often a closure runs."
   "     Capturing a live capability is still rejected outright. What changed is"
   "     the REASON and how much of it is decided rather than asserted — see"
   "     item 19: the closure is classified non-shareable by the transitive rule,"
   "     and whether that particular capture is a violation is DECIDED only when"
   "     the consumer introduces concurrency and the capability declares"
   "     `:contention :thread-confined`. Every other consumer is a REFUSAL with"
   "     the missing declaration named. The rejection is unchanged either way."
   ""
   "  4. try/catch has no exception-path join. Any capability discipline across a"
   "     handler is unchecked and the checker says so where it finds one."
   ""
   "  5. Only `let`/`loop` binding forms and TUPLES carry capabilities. A vector"
   "     is the one composite the abstract domain models, because `:at [i]` can"
   "     name a position in one. A capability stored in an atom, a var, a map or"
   "     a set is rejected, never tracked."
   ""
   "  6. Interprocedural flow is by ANNOTATION only. There is no inference: an"
   "     unannotated function that takes a connection is rejected, not analysed."
   ""
   "  7. The IR it reads is post-const-fold (perturb.ir), and only for namespaces"
   "     required AFTER the tap is installed."
   ""
   "  8. CORRECTED, BY MEASUREMENT. This item used to read: destructuring,"
   "     `peek`, `last` or a computed index `lose the capability to OPAQUE —"
   "     which is silent, not a diagnostic. This is the most likely place for a"
   "     FALSE ACCEPT to hide today.` That was wrong in both halves. Probed with"
   "     one program per eliminator: NONE is silent. A tuple holding a live"
   "     capability passed to an unannotated callee draws `no-signature`, and"
   "     the capability then draws `dangling` at scope exit. There was no false"
   "     accept to find."
   ""
   "     The real defect was the opposite one and it was worse in practice: a"
   "     FALSE REJECT on idiomatic Clojure. `(let [[c frames] r] …)` lowers to"
   "     `(nth G__287 0 nil)` — three arguments, the third a not-found default —"
   "     and `projection-index` demanded exactly two, so every destructuring"
   "     bind of a capability was refused with four diagnostics. Fixed; the"
   "     corpus now carries `destructure-and-close` (accept, and it runs) and"
   "     `destructure-and-drop` (reject, dangling)."
   ""
   "     What remains true: `peek`, `last` and `nth` with a COMPUTED index are"
   "     still not eliminators, and reject. That is a real limit, but it is a"
   "     loud one, and the item overstated it as a soundness risk."
   ""
   "  9. AN OPERATION MAY NOW ADVANCE TWO MACHINES, AND NOTHING CHECKS THAT IT"
   "     DOES. The primitive table is keyed [capability operation], so"
   "     `accept`, `respond-begin` and `body-finish!` each declare an edge in"
   "     two machines and the consistency rule compares each separately (E18"
   "     finding 1(a), closed). What is unchanged is the class those bodies are"
   "     in: all three are AXIOMS, so nothing establishes that `body-finish!`"
   "     really moves BOTH machines, and nothing relates the two edges in time —"
   "     the `body-finished-before-conn-reused` obligation is still written as"
   "     data with nothing to discharge it. Declaring the shape is not checking"
   "     it, and the second capability doubles what item 1 covers up."
   ""
   " 10. A TRANSITION CAN NOW CARRY A REFINEMENT — and here is the whole of what"
   "     that decides. `ResponseBody`'s `:open -> :finished` edge carries"
   "     `(= written declared)` and `perturb.refine` decides it in a GROUND"
   "     LINEAR FRAGMENT: a ghost variable is `k + SUM c_i a_i` over the integers"
   "     or it is UNKNOWN, an atom is minted per BINDING OCCURRENCE, and the"
   "     procedure is normalisation plus a sign test. It is COMPLETE where both"
   "     sides are constants — that part is evaluation, and calling it a solver"
   "     would be a false claim — and SOUND BUT INCOMPLETE once an atom"
   "     survives. There is no case split, no elimination and no way to assume a"
   "     hypothesis, so `3 = ocount(b)` is refused, not decided."
   ""
   "     FOUR THINGS IT REFUSES, each one a REJECTION with the kind"
   "     `refinement-undischarged` rather than an accept:"
   "       (a) a refinement crossing a LOOP boundary in either direction. The"
   "           body is walked once and the trip count is data, so every ghost"
   "           variable live at the boundary becomes unknown. There is NO"
   "           invariant syntax: a programmer who knows the loop writes exactly"
   "           N octets has no way to say so, and the program stays rejected."
   "           perturb.httpcorpus/body-written-in-a-loop is refused here and a"
   "           checker that believed one pass would ACCEPT it."
   "       (b) a refinement crossing a FUNCTION boundary. There is no"
   "           interprocedural refinement at all: a capability that arrives as a"
   "           parameter has no ghost state, so an obligation on it is refused."
   "       (c) a ghost value that is not a linear term over constants and atoms."
   "       (d) any formula outside the fragment above."
   ""
   "     AND FOUR THINGS IT DOES NOT ESTABLISH. The `:update` on `body-write` is"
   "     an ANNOTATION ON AN AXIOM — nothing checks that `body-write` writes"
   "     `(ocount ov)` octets, exactly as nothing checks that a transition obeys"
   "     its own `:from`/`:to`. Atoms are compared SYNTACTICALLY, so two"
   "     expressions denoting the same integer are two atoms. The `:init` rides"
   "     on a `:produces` entry rather than on a transition, because a capability"
   "     minted by another machine's operation cannot name it (item 9). And ONE"
   "     capability in perturb carries a refinement; a second would test whether"
   "     any of this generalises."
   ""
   "     NOTHING IN §1.2 CAN STILL RELATE TWO MACHINES IN TIME."
   "     `body-finished-before-conn-reused` is an ordering between ResponseBody"
   "     and ServerConn, not arithmetic on one edge, and it is untouched."
   "     E18 finding 3, half closed."
   ""
   " 11. `:borrows` AND `:produces` OF THE SAME CAPABILITY is now REFUSED at the"
   "     annotation, because it mints two abstract capabilities for one runtime"
   "     object (E18 finding 1(c)). WHAT DID NOT CHANGE: a caller of such an"
   "     operation is still analysed with the refused annotation, and"
   "     perturb.httpcorpus/uses-borrow-and-return is still rejected for"
   "     `dangling` on a listener it disposed of correctly. Suppressing that"
   "     would mean changing what the flow rules do at a call site, which is a"
   "     rule change and not a declaration-language change. The fault is now"
   "     findable at its cause; the consequence at the call site is still"
   "     reported and still misleading on its own."
   ""
   " 12. A REFUSED ANNOTATION MEANS AN UNCHECKED BODY. `annotation-unpositioned`"
   "     and `annotation-duplicates-capability` refuse an annotation where it is"
   "     written, and the operation's body is then not checked at all — there is"
   "     no sound specification to check it against. The verdict is a rejection"
   "     either way, so this cannot hide a false accept, but it does mean a"
   "     refused operation's body has NEVER been read: fixing the annotation can"
   "     surface diagnostics that were never suppressed, only never reached."
   ""
   " 13. THE DECLARATION RULES DO NOT READ A BODY. `annotation-inconsistent`,"
   "     `annotation-undeclared-transition` and `annotation-ambiguous-edge`"
   "     compare an annotation with a declared machine and nothing else. That an"
   "     operation DECLARES two edges is not evidence that it takes them: all"
   "     nine of perturb.http's transitions are still axioms (item 1), and the"
   "     ledger is the only thing that ever observed one."
   ""
   " 14. ONE OPERATION MAY NOW HAVE SEVERAL `:to`s — A SUM — AND HERE IS EXACTLY"
   "     WHAT THAT DOES AND DOES NOT DO. `casselc/db`'s `verified-sqlite-begin!`"
   "     has three destinations chosen at run time, and E26 finding 7 is that"
   "     perturb could not declare it. It can now: several `:transitions` entries"
   "     sharing an `:op` and a `:from`, a `:produces` `:state` that is a"
   "     COLLECTION, and `perturb.dbtx` is the machine."
   ""
   "     WHAT IT DOES."
   "       (a) OPT-IN, PER OPERATION. A group of declared edges with ONE `:to` is"
   "           compared exactly as before, produces exactly the diagnostics it"
   "           did before, and cannot acquire a sum: a one-member collection"
   "           collapses to the state itself. No pre-existing corpus verdict"
   "           moved, and that is checked by the two corpora that were already"
   "           here, not asserted."
   "       (b) USE IS TOTAL OR IT IS REFUSED. A capability in a sum state may be"
   "           passed to an operation only if the operation's `:from` admits"
   "           EVERY member. Otherwise `state-unresolved` — a distinct kind from"
   "           `typestate`, because the program is not in the wrong state, it is"
   "           in several and has not said which. The useful consequence is that"
   "           a totally-admitting operation — a `:from :any` destructor — needs"
   "           no case split at all."
   "       (c) ELIMINATION IS BY A DECLARED PREDICATE."
   "           `:perturb.cap/discriminator` names an operation, an argument"
   "           position, and the states each arm of an `if` knows; the checker"
   "           intersects. Two nested discriminators resolve a three-way sum."
   "       (d) A SUM IS TERMINAL ONLY IF EVERY MEMBER IS, so the leak rule holds"
   "           over sums with no special case."
   ""
   "     WHAT IT DOES NOT DO, AND THIS IS THE PART TO READ."
   "       (e) A SUM WITH NO DECLARED DISCRIMINATOR IS UNRESOLVABLE. There is no"
   "           inference from a program's own tests: a predicate the checker was"
   "           not told about narrows nothing. Such a capability can ONLY be"
   "           consumed by an operation that admits every member, and if the"
   "           machine has no such operation the capability can never be"
   "           consumed at all — it will leak at scope exit and the program has"
   "           no way to be written correctly. Declaring a sum without a"
   "           discriminator is therefore a decision to make a state"
   "           unusable, and nothing warns about it."
   "       (f) NOTHING CHECKS A DISCRIMINATOR. `autocommit?`'s body is an AXIOM"
   "           exactly as a transition's is (item 1). A discriminator that lies"
   "           narrows to the wrong state and the checker will accept a program"
   "           that cannot run — the same class of false accept E15 found, moved"
   "           to a new declaration key."
   "       (g) THE TEST MUST BE A DIRECT CALL. `(if (autocommit? t) …)` narrows;"
   "           `(let [ok (autocommit? t)] (if ok …))` does not, because the"
   "           checker has no boolean domain to carry the fact in. Nor is the"
   "           discriminated argument allowed to be inside a tuple. Both are"
   "           silent: the program is simply still rejected, with (b)'s"
   "           diagnostic and no hint that the shape was the problem."
   "       (h) NARROWING IS LOST AT THE JOIN. After an ordinary `if` the"
   "           capability is back in the full sum, because the program no longer"
   "           knows which arm ran. A resolved state can only be USED inside the"
   "           arm that resolved it. (It does survive when the other arm ends in"
   "           `throw` or `recur`, which is sound and which nothing exercises.)"
   "       (i) THE `:from` SIDE STILL MEANS `ANY OF`. A collection in `:consumes`"
   "           or `:borrows` is a set of admissible sources, and a PARAMETER"
   "           declared that way is bound in the first of them. A function"
   "           parameter that is genuinely in a sum state cannot be declared, so"
   "           a sum cannot cross a function boundary — it must be eliminated in"
   "           the same function that produced it."
   "       (j) A REFINEMENT ON A SUMMAND — CLOSED, AND IT WAS A REAL DEFECT."
   "           `perturb.cap/refinements` was keyed [capability operation] with"
   "           `assoc`, so two edges of one group each carrying a"
   "           `:perturb.cap/refine` collided and the one declared LAST silently"
   "           won — the same defect the primitive table had before E26 finding"
   "           7, in the one table that was not fixed. The table now holds a"
   "           VECTOR per key and the consumers were changed with it:"
   "             - EVERY applicable summand's `:requires` is discharged, because"
   "               the call takes one of them and which one is not knowable"
   "               until run time (the totality rule (b) states, at the"
   "               refinement tier);"
   "             - applicable summands that DISAGREE about an `:update` widen"
   "               that ghost variable to unknown, exactly as the two arms of an"
   "               `if` and a loop boundary already do;"
   "             - `applicable` means `:from` admits the state the capability is"
   "               actually in, with NO fallback: an operation that typechecked"
   "               through an edge carrying no refinement owes nothing."
   "           `perturb.dbtxcorpus/meter-split-refutes-one-summand` is the"
   "           fixture: two summands, the refuted obligation declared FIRST, so"
   "           the old table dropped it and ACCEPTED the program."
   ""
   " 15. CANCELLATION IS A STATE, AND HERE IS WHAT A STATE CANNOT DO."
   "     PERTURB-DESIGN §4.6 piece 5. A capability may declare one of its own"
   "     states CANCELLED (`:perturb.cap/cancelled`); an operation that lands"
   "     there abandons the protocol; nothing but the destructor is legal"
   "     afterwards; and reaching it discharges NOTHING, so a cancelled"
   "     capability that never reaches its destructor is still `dangling`."
   ""
   "     THE HEADLINE IS WHAT IT DOES NOT DO. Fowler et al. (POPL 2019) §1.4"
   "     names TWO quandaries of silently discarded endpoints:"
   ""
   "       \"First, a developer receives no feedback if they accidentally forget"
   "        to finish a protocol implementation. Second, if an exception is"
   "        raised in an evaluation context that captures an open endpoint then"
   "        THE PEER MAY BE LEFT WAITING FOREVER.\""
   ""
   "     THIS MECHANISM DISCHARGES THE FIRST AND CANNOT TOUCH THE SECOND,"
   "     because a state is a fact about the HOLDER. NO PEER IS NOTIFIED. There"
   "     is no zapper thread, nothing propagates through buffered values, and"
   "     nothing raises in a counterparty on receive or close. EGV's"
   "     `E-Cancel`/`E-Zap`/`E-ReceiveZap`/`E-CloseZap` are the operational"
   "     content this does not have, and T-Cancel's own note says that content"
   "     is what stops cancellation violating progress. So:"
   ""
   "       THIS IS NOT CANCELLATION IN EGV'S SENSE AND MUST NOT BE DESCRIBED AS"
   "       IF IT WERE. It is §4.6 pieces 1-3 collapsed for the PEERLESS case,"
   "       and it is sufficient exactly when piece 4 is vacuous — when no peer's"
   "       progress depends on being told. That is TRUE of a database"
   "       transaction, which is why `perturb.dbtx` declares it, and FALSE of"
   "       `perturb.http/ServerConn`, whose counterparty is a client holding an"
   "       open socket, which is why `perturb.http` declares nothing of the kind"
   "       and why the key is opt-in rather than implicit for every capability."
   ""
   "     FIVE MORE THINGS IT DOES NOT DO."
   "       (a) NOTHING INSERTS THE CANCELLATION. §4.6 piece 2 — a live-capability"
   "           set at every `abort!` site, discharged by the compiler the way"
   "           EGV's `E-Raise` cancels every endpoint in the enclosing pure"
   "           context — is NOT built. A programmer who wants a capability"
   "           cancelled writes the call. A real non-local exit past a live"
   "           capability is still item 4's hole: `try`/`catch` has no"
   "           exception-path join, so the checker cannot even see the exit."
   "       (b) IT DOES NOT PROPAGATE. §4.6 piece 3: cancelling a capability says"
   "           nothing about capabilities reachable from it. The abstract domain"
   "           has one composite and a capability inside another capability"
   "           cannot be named at all."
   "       (c) THE CANCELLING OPERATION'S BODY IS AN AXIOM, like every other"
   "           transition (item 1). Nothing checks that `perturb.dbtx/abort!`"
   "           actually abandons anything; what is checked is what the machine"
   "           permits afterwards."
   "       (d) IT IS ONE STATE, NOT A MODE. A capability cancelled in one arm of"
   "           an `if` and not the other is an ordinary join failure, and there"
   "           is no `:maybe-cancelled`. §4.6's drop-flag item is untouched."
   "       (e) IT SAYS NOTHING ABOUT TIME OR ORDER. `MUST_CLOSE` requires that"
   "           the destructor is reached, not that it is reached promptly, and"
   "           nothing relates it to any other machine (item 9)."
   ""
   "     WHAT IS NOT AN AXIOM, UNIQUELY. The declaration itself is CHECKED —"
   "     `check-cancellation-declarations!` refuses a cancelled state that is"
   "     terminal, unreachable, inescapable, undeclared, or that has an outgoing"
   "     edge which is not a destructor. That is the one declaration key in"
   "     perturb the checker will not believe, because unlike `:from`/`:to` it"
   "     is a claim about what is IMPOSSIBLE, and the machine written beside it"
   "     settles that claim without reading any body."
   ""
   " 16. THE THREE NOTIONS ARE NOW THREE THINGS, AND HERE IS WHAT EACH ONE"
   "     MEANS ALONE. PERTURB-DESIGN E33: a stable shared handle, its current"
   "     protocol state, and the obligation to discharge the underlying resource"
   "     are THREE DIFFERENT THINGS. `:linearity :once` treated them as one, and"
   "     E30 measured the bill: an idempotent compare-and-set `close!` drew"
   "     `use-after-move`, so did `closed?` and `connection-info` — pure"
   "     observers declared legal in EVERY state — and 12 of 23 substantive"
   "     rejections were in-place typestate. They are separated as follows."
   ""
   "       (a) THE PROTOCOL STATE is `:to` on a transition. ALONE it says which"
   "           operations are legal next. It no longer implies anything about"
   "           the obligation or about whether the name survives."
   "       (b) THE OBLIGATION is `:obligation` on a transition — `:acquire`,"
   "           `:retain` or `:discharge` — and it is carried on the capability"
   "           as `owes`. ALONE it says whether the resource still needs its"
   "           destructor. AN EDGE THAT DECLARES NOTHING IS DERIVED exactly as"
   "           before: discharged iff every member of `:to` is terminal. That"
   "           derivation is why no pre-existing verdict moved."
   "       (c) THE NAME is the SHAPE OF THE ANNOTATION, not a key on the"
   "           machine. `:borrows` keeps it and moves nothing;"
   "           `:consumes` + `:produces … :at` kills it and hands the capability"
   "           back in the RESULT; `:consumes` + `:produces … :arg n` keeps it"
   "           and changes the state WHERE IT STANDS. Whether a binding survives"
   "           is a property of one operation's calling convention, not of the"
   "           resource — which is exactly the distinction that was collapsed."
   ""
   "     WHAT THIS DOES NOT DO."
   "       (d) IT IS NOT ALIAS CONTROL. E33's own caution: identity-indexed"
   "           protocol state needs an alias policy, tracked keys and pre/post"
   "           specifications, and `E30's 12-of-23 shape is not shown to"
   "           disappear under permissions`. What is here is value threading"
   "           internally with an in-place PRESENTATION on top. One name is"
   "           tracked; a second name for the same runtime object is still an"
   "           affine move and still kills the first."
   "       (e) NOTHING VERIFIES AN `:obligation`. It is an axiom like `:from`"
   "           and `:to` (item 1). A transition that declares `:discharge` and"
   "           does not close anything is believed, and every program holding"
   "           that capability is accepted on it. The ONE thing that is checked"
   "           is the absorbing-state side condition below, and it is checked"
   "           for the same reason the cancelled state is: it is a claim about"
   "           what is impossible, settled by the machine beside it."
   "       (f) THERE IS NO CAS, NO RACE AND NO THREAD HERE. Calling the boolean"
   "           a `race witness` is reading the LIBRARY's contract, not modelling"
   "           it. `:contention` is still `:thread-confined` everywhere (I20),"
   "           and E33's open question — a typed account of CAS-based discharge"
   "           among an UNKNOWN ALIAS SET — is untouched by any of this. Both"
   "           round-2 surveys record it as an absence and neither claims it as"
   "           novelty; nor does this."
   ""
   " 17. RESULT LABELS ARE THE PRIMARY MECHANISM AND THE SUM IS THE FALLBACK —"
   "     WHICH IS AN ORDERING, NOT A NEW SOLVER. E33: both round-2 surveys"
   "     independently name the same primitive relation, and it is the"
   "     established syntax (Mungo/StMungo `Status open(): <OK: Open, ERROR:"
   "     end>`; Vault keys by source AND destination; Fugue computes"
   "     postconditions from receiver, state, arguments and results):"
   ""
   "       (capability, operation, source-state, result-label)"
   "           -> destination-state + obligation delta"
   ""
   "     WHAT IT DOES."
   "       (a) A TRANSITION MAY CARRY `:result`. Two edges from ONE source with"
   "           different labels that share a destination AND an obligation delta"
   "           COLLAPSE: there is no sum and no case split. That is the"
   "           compare-and-set `close!` — `:won` and `:lost` both land in"
   "           `:closed` and both discharge — and it is why an idempotent close"
   "           is not a second close."
   "       (b) AN ANNOTATION MAY OMIT `:state`, and then the MACHINE answers, at"
   "           each call site, from the state the capability is ACTUALLY in. The"
   "           transition relation is therefore a function of (operation,"
   "           SOURCE) where it used to have to be a function of the operation"
   "           alone. That is the whole of E30 finding 3: `shutdown-write!` has"
   "           four sources with four correspondingly different destinations,"
   "           and one annotation had to be compared against every group, so it"
   "           had to disagree with all but one — 6 diagnostics."
   "       (c) SOURCE COLLECTIONS ARE SUGAR ONLY WHERE THEY ARE SUGAR."
   "           `check-edge-overlap!` refuses a `:from` collection that overlaps"
   "           a differently-sourced edge of the same operation with a different"
   "           destination or delta. Two edges with the SAME `:from` are"
   "           untouched — that is an honest sum, or a labelled family."
   ""
   "     WHAT IT DOES NOT DO, AND THIS IS THE PART TO READ."
   "       (d) A RESULT LABEL IS NEVER READ. There is no result domain here and"
   "           no call site observes which label came back. Where labels from"
   "           one source DISAGREE the destination is their UNION — a sum — and"
   "           item 14's discriminator machinery is the fallback, unchanged."
   "           The labels are documentation to a human and a grouping key to the"
   "           checker; they are not evidence."
   "       (e) THE OBLIGATION DELTA OVER SEVERAL APPLICABLE EDGES IS THE"
   "           CONSERVATIVE MEET: discharged only if every applicable edge"
   "           discharges, acquiring if any acquires. A capability is never"
   "           assumed disposed of because one possible label would have"
   "           disposed of it."
   "       (f) `:state` MAY ONLY BE OMITTED ON A DECLARED TRANSITION of that"
   "           capability. Anywhere else there is no machine to read and the"
   "           entry is refused (`annotation-underived-state`) rather than"
   "           silently admitting every state."
   "       (g) IT DOES NOT MAKE A DECLARATION TRUE. Item 13 is unchanged: that"
   "           an operation declares an edge is not evidence it takes it, and a"
   "           `:result` label is one more thing nothing checks."
   ""
   " 18. AN ABSORBING TERMINAL STATE, AND THE ONE RULE THAT MAKES IT SAFE."
   "     E30 finding 1 named the missing concept: a terminal state that admits"
   "     its own destructor and its observers as SELF-LOOPS — obligation"
   "     discharged, NAME STILL ALIVE, only these operations legal. It is §4.6"
   "     piece 5's `MUST_CLOSE` shifted one step past the end."
   ""
   "     IT NEEDS NO NEW RULE TO EXIST, which is the argument that it is the"
   "     right decomposition: the name survives because the annotation says"
   "     `:produces … :arg n` or `:borrows`, and the obligation stays discharged"
   "     because `owes` is a fact about the resource rather than a re-derivation"
   "     from the state at scope exit. `:closed -> :closed` is then an ordinary"
   "     edge, exactly as `shutdown-write!`'s `:write-shut -> :write-shut`"
   "     self-loop always was — E30's own corroboration that idempotence is not"
   "     what `:linearity :once` rejects, idempotence AT A TERMINAL STATE is."
   ""
   "     IT NEEDS ONE RULE TO BE SAFE, and E33 supplies it: AN OBSERVER SELF-LOOP"
   "     MUST NOT RE-ACQUIRE THE DISCHARGED RESOURCE."
   "     `check-absorbing-declarations!` refuses any edge whose `:from` admits a"
   "     terminal state and whose obligation delta is not `:discharge`. Without"
   "     it a declaration could write `:closed -> :open` and every program"
   "     holding that capability would be accepted on a debt nobody owes. Like"
   "     the cancelled-state rule, this is a claim about what is IMPOSSIBLE and"
   "     is settled by the machine written beside it, so it is CHECKED rather"
   "     than believed."
   ""
   "     WHAT IT DOES NOT DO."
   "       (h) IT DOES NOT DISCHARGE ANYTHING BY ITSELF. A capability that never"
   "           reaches the destructor is still `dangling`, whatever observers"
   "           were called on it. `perturb.dbtxcorpus/sock-never-closed` and"
   "           `sock-observed-but-never-closed` are the gated controls, and both"
   "           are REJECTS."
   "       (i) `:any` COUNTS AS ADMITTING THE TERMINAL STATE. A `:from :any`"
   "           destructor makes its own terminal state absorbing whether the"
   "           author meant it to be or not. That is sound — the edge"
   "           discharges — but it is not a decision anyone wrote down, and it"
   "           is the reason `perturb.dbtx/Tx` reports as absorbing."
   "       (j) IN-PLACE IS ONE LEVEL AND ONE NAME. `:arg n` requires argument n"
   "           to be a plain local; anything else is `in-place-unnamed` and is"
   "           refused, because the produced capability — and its obligation —"
   "           would otherwise vanish silently. `:arg` together with `:at` is"
   "           `annotation-unsupported`."
   "       (k) AN IN-PLACE PRODUCE ON A DECLARED TRANSITION IS STILL AN AXIOM"
   "           (item 1). On a DERIVED operation it is CHECKED — the parameter is"
   "           still bound at the end of the body, so the state it ended in is"
   "           observable and a body that did not move it draws"
   "           `produces-mismatch`. That is a small enlargement of what is"
   "           checked rather than of what is trusted, and it is the only one"
   "           here."
   ""
   " 19. THE TRANSITIVE SHAREABILITY RULE — WHAT IT DECIDES HERE, AND THE FOUR"
   "     THINGS IT DOES NOT."
   "     E37's reframe: the classifier for a freely duplicable value is an"
   "     INTERFACE property, and its transitivity clause is §4.6's root cause"
   "     rather than a second tier. `perturb.share` holds the rule; this"
   "     namespace builds a profile from its own abstract value at the two"
   "     duplication sites — a closure capture and a composite — and PRINTS the"
   "     classification with the witness path instead of asserting a reason."
   "     `perturb.sharecheck` runs the rule's boundary table and the three-way"
   "     control."
   ""
   "       (a) NO VERDICT MOVED, AND THAT IS THE INTENDED RESULT ON THIS CORPUS."
   "           Every profile the checker can build has a capability leaf in it"
   "           already — that is why the site raised a diagnostic at all — so the"
   "           rule confirms `mixed` everywhere it is consulted. It buys a"
   "           derived reason and a witness path, not a changed accept set. A"
   "           rule that had moved a verdict here would have done it by"
   "           weakening the check."
   "       (b) `:refused` IS NOT WIRED TO A REJECTION. `perturb.share` denies"
   "           promotion to an opaque/FFI value with no trusted declaration."
   "           Turning that into a rejection here would reject essentially every"
   "           program, because EVERY result of an unannotated call is opaque to"
   "           this checker and carries no provenance to declare. The refusal is"
   "           reported by `-M:share`, where it is about a profile, and is not"
   "           enforced here, where it would be about ignorance."
   "       (c) A PROFILE IS NOT A TYPE. `[:coll p]` says every element has"
   "           profile p, not which ones do; there are no field names and no"
   "           arity. That is enough for transitive reachability and is"
   "           deliberately not enough for anything else."
   "       (d) THE HIGHER-ORDER QUESTION IS STILL OPEN, AND IS NOW NAMED AT THE"
   "           SITE. `capture-disposition` refuses every consumer that is not a"
   "           declared concurrency introducer, because whether a callee calls"
   "           the closure once and drops it, retains it, or copies it is not"
   "           written down anywhere. §4.6: `(f c)` cannot be annotated because"
   "           the callee is a parameter. Deciding those would need a notation"
   "           this language does not have, and guessing would be a false"
   "           accept."])

(def ^:private line
  "========================================================================")

(defn- verdict [rs] (if (empty? (:diagnostics rs)) :accept :reject))

(defn- kinds [rs] (set (map :kind (:diagnostics rs))))

(defn- run-corpus
  "Check one corpus namespace against its own recorded expectations.

  CHANGED (E18): was hard-wired to `perturb.corpus`. It now takes the namespace,
  the expectations var and a banner, because perturb has a second protocol and a
  second corpus. No rule changed; the parameter list did."
  [sp ns-name exp-sym banner]
  (let [results (check-namespace! sp ns-name)
        by-var  (reduce (fn [m r] (assoc m (:var r) r)) {} results)
        exps    (deref (resolve exp-sym))]
    (println (str "== corpus: " ns-name " ================================================"))
    (println (str "   " banner))
    (println)
    (let [fails
          (reduce
            (fn [acc e]
              (let [r   (get by-var (:var e))
                    got (if (nil? r) :missing (verdict r))
                    ok  (and (= got (:expect e))
                             (or (nil? (:kind e))
                                 (contains? (kinds r) (:kind e))))]
                (println (str "  [" (if ok "ok  " "FAIL") "] "
                              (:var e) "  expected " (name (:expect e))
                              ", got " (name got)
                              (if (= :reject got)
                                (str "  " (vec (sort (map name (kinds r)))))
                                "")))
                (when (not ok)
                  (println (render (:diagnostics r))))
                (if ok acc (conj acc (:var e)))))
            [] exps)]
      (println)
      (println (str "  " (- (count exps) (count fails)) "/" (count exps)
                    " decided as recorded"
                    (if (empty? fails) "" (str "; FAILED " (vec fails)))))
      (println)
      (println "  the first rejection, in full:")
      (println)
      (let [r (get by-var (:var (first (filter (fn [e] (= :reject (:expect e))) exps))))]
        (print (render (:diagnostics r))))
      fails)))

(defn- run-declaration-corpus
  "Gate the DECLARATION language against hand-built fixtures.

  Every other stage checks a PROGRAM. This one checks a declaration against an
  annotation and reads no body, because that is where E18 finding 1's four
  defects live: the flow rules met two capabilities and survived on the first
  attempt, and what could not be stated was the declaration. Three of the four
  had no artifact except a diagnostic raised against an axiom, which the report
  stage used to discard.

  A fixture is `{:name :declarations :operations :expect}` and `:expect` is the
  exact SET of diagnostic kinds. It does not establish anything about a body."
  [corpus-sym banner]
  (println (str "== the DECLARATION language: " corpus-sym " ================"))
  (println (str "   " banner))
  (println)
  (let [fixtures (deref (resolve corpus-sym))
        fails
        (reduce
          (fn [acc f]
            (let [sp     (spec-from {:perturb.cap/declarations (:declarations f)
                                     :perturb.cap/operations   (:operations f)})
                  before (count @diagnostics)]
              (doseq [e (sort-by (fn [x] (str (first x))) (seq (:operations f)))]
                (check-annotation-wellformed! (first e) (second e))
                (check-annotation-consistency! sp (first e) (second e)))
              (let [ds  (vec (drop before @diagnostics))
                    got (set (map :kind ds))
                    ok  (= got (set (:expect f)))]
                (println (str "  [" (if ok "ok  " "FAIL") "] " (:name f)
                              "  expected " (pr-str (vec (sort (map name (:expect f)))))
                              ", got " (pr-str (vec (sort (map name got))))))
                (when (not ok) (println (render ds)))
                (if ok acc (conj acc (:name f))))))
          [] fixtures)]
    (println)
    (println (str "  " (- (count fixtures) (count fails)) "/" (count fixtures)
                  " declaration fixtures decided as recorded"
                  (if (empty? fails) "" (str "; FAILED " (vec fails)))))
    fails))

(defn- run-cancellation-corpus
  "Gate the CANCELLED-STATE declaration rule against hand-built machines.

  Same shape and same posture as `run-declaration-corpus`: no program, no body,
  no annotation — a `:perturb.cap/cancelled` key and the machine it claims to be
  a state of. It is separate from that corpus because it checks a different
  thing: `check-annotation-consistency!` asks whether an ANNOTATION agrees with
  a machine, and this asks whether a MACHINE is internally consistent with its
  own claim about what is impossible.

  A fixture is `{:name :declarations :expect}` and `:expect` is the exact SET of
  diagnostic kinds — `[]` for the well-formed control, which is here so that the
  rule cannot pass by rejecting everything."
  [corpus-sym banner]
  (println (str "== the CANCELLED-STATE declaration rule: " corpus-sym " ======"))
  (println (str "   " banner))
  (println)
  (let [fixtures (deref (resolve corpus-sym))
        fails
        (reduce
          (fn [acc f]
            (let [sp     (spec-from {:perturb.cap/declarations (:declarations f)
                                     :perturb.cap/operations   {}})
                  before (count @diagnostics)]
              (check-cancellation-declarations! sp)
              (let [ds  (vec (drop before @diagnostics))
                    got (set (map :kind ds))
                    ok  (= got (set (:expect f)))]
                (println (str "  [" (if ok "ok  " "FAIL") "] " (:name f)
                              "  expected " (pr-str (vec (sort (map name (:expect f)))))
                              ", got " (pr-str (vec (sort (map name got))))))
                (when (not ok) (println (render ds)))
                (if ok acc (conj acc (:name f))))))
          [] fixtures)]
    (println)
    (println (str "  " (- (count fixtures) (count fails)) "/" (count fixtures)
                  " cancellation declaration fixtures decided as recorded"
                  (if (empty? fails) "" (str "; FAILED " (vec fails)))))
    fails))

(defn- run-machine-corpus
  "Gate the two E33 MACHINE rules against hand-built machines: the absorbing
  terminal state's side condition, and the source-collection overlap rule.

  Same posture as `run-cancellation-corpus` — no program, no body, no
  annotation. A machine and its own claims about obligations. The two well-formed
  controls are here so neither rule can pass by rejecting everything, and one of
  them is an ABSORBING state done right: a terminal state with a destructor
  self-loop and an observer, which must draw nothing."
  [corpus-sym banner]
  (println (str "== the MACHINE rules (E33): " corpus-sym " ================"))
  (println (str "   " banner))
  (println)
  (let [fixtures (deref (resolve corpus-sym))
        fails
        (reduce
          (fn [acc f]
            (let [sp     (spec-from {:perturb.cap/declarations (:declarations f)
                                     :perturb.cap/operations   {}})
                  before (count @diagnostics)]
              (check-absorbing-declarations! sp)
              (check-edge-overlap! sp)
              (let [ds  (vec (drop before @diagnostics))
                    got (set (map :kind ds))
                    ok  (= got (set (:expect f)))]
                (println (str "  [" (if ok "ok  " "FAIL") "] " (:name f)
                              "  expected " (pr-str (vec (sort (map name (:expect f)))))
                              ", got " (pr-str (vec (sort (map name got))))))
                (when (not ok) (println (render ds)))
                (if ok acc (conj acc (:name f))))))
          [] fixtures)]
    (println)
    (println (str "  " (- (count fixtures) (count fails)) "/" (count fixtures)
                  " machine fixtures decided as recorded"
                  (if (empty? fails) "" (str "; FAILED " (vec fails)))))
    fails))

(defn- run-real-machine-declarations
  "The same two rules over the declarations perturb ACTUALLY ships. A fixture
  corpus that passes while a real declaration re-acquires a discharged resource
  would be a gate measuring itself."
  [sp]
  (println)
  (println "== the E33 machine rules, over perturb's OWN declarations =============")
  (let [before (count @diagnostics)]
    (check-absorbing-declarations! sp)
    (check-edge-overlap! sp)
    (let [ds  (vec (drop before @diagnostics))
          bad (set (map :cap ds))]
      (println "   (a) no edge out of a DISCHARGED state may fail to leave it discharged;")
      (println "   (b) a `:from` collection may not overlap a differently-sourced edge of")
      (println "       the same operation with a different destination or delta.")
      (println)
      (doseq [e (sort-by (fn [x] (str (first x))) (seq (:declarations sp)))]
        (let [c    (first e)
              ts   (:perturb.cap/typestate (second e))
              term (let [t (:terminal ts)] (if (coll? t) (vec t) [t]))
              abs  (vec (filter (fn [t] (some (fn [s] (and (not (nil? s))
                                                           (admits? (:from t) s)))
                                              term))
                                (edges-of sp c)))]
          (println (str "  [" (if (contains? bad c) "NO  " "ok  ") "] " c
                        "   terminal " (pr-str term)
                        (if (seq abs)
                          (str ", ABSORBING: " (count abs) " edge(s) legal from it — "
                               (str/join ", " (sort (distinct (map (fn [t] (str (:op t))) abs)))))
                          ", no edge is legal from it")))))
      (when (seq ds) (println) (print (render ds)))
      (println)
      (println (str "  " (count ds) " unsound machine declaration(s)"))
      (vec bad))))

(defn- run-real-cancellation-declarations
  "The same rule, over the declarations perturb ACTUALLY ships. A fixture corpus
  that passes while the real declaration is unsound would be a gate measuring
  itself, so this runs on `spec` and its diagnostics fail the gate."
  [sp]
  (println)
  (println "== the cancelled-state rule, over perturb's OWN declarations ==========")
  (let [before (count @diagnostics)
        all    (sort-by (fn [e] (str (first e))) (seq (:declarations sp)))
        decl   (filter (fn [e] (not (nil? (get (second e) cap/cancelled-key)))) all)]
    (check-cancellation-declarations! sp)
    (let [ds  (vec (drop before @diagnostics))
          bad (set (map :cap ds))]
      (println (str "   " (count decl) " of " (count all)
                    " declared capabilities opt in to a cancelled state. The key is"))
      (println "   opt-in exactly because E27's sufficiency condition is per capability:")
      (println "   sufficient when no peer's progress depends on being told, which is true")
      (println "   of a database transaction and false of perturb.http's ServerConn.")
      (println)
      (doseq [e all]
        (let [c  (first e)
              cs (get (second e) cap/cancelled-key)]
          (println (str "  ["
                        (cond (nil? cs) " -  "
                              (contains? bad c) "NO  "
                              :else "ok  ")
                        "] " c
                        (if (nil? cs)
                          "   declares no cancelled state"
                          (str "   cancelled " cs))))))
      (when (seq ds) (println) (print (render ds)))
      (println)
      (println (str "  " (count ds) " unsound cancelled-state declaration(s)"))
      (vec bad))))

(defn- run-accepts
  "EXECUTE every accepted corpus program under the scripted handler.

  This stage exists because of E15. The corpus's first accept set was checked
  and never run, and every entry in it threw: the checker's model of `request`
  contradicted `request`. An acceptance that cannot run is not a weak result,
  it is a wrong one, and only running it says which. Returns the vars that
  failed to complete.

  CHANGED (E18): takes the expectations var, and honours a `:handler` key naming
  a 0-arg var that builds the handler. Both are plumbing — an HTTP server cannot
  be run against a scripted nREPL peer — and neither is a rule."
  [exp-sym]
  (println)
  (println (str "== every ACCEPT of " exp-sym " is executed ================"))
  (println "   the same programs, run under perturb.script's in-memory handler.")
  (println "   E15: the previous accept set type-checked and threw.")
  (println)
  (let [exps  (deref (resolve exp-sym))
        runs  (filter (fn [e] (and (= :accept (:expect e)) (:run e))) exps)
        no-run (filter (fn [e] (and (= :accept (:expect e)) (nil? (:run e)))) exps)
        fails
        (reduce
          (fn [acc e]
            (let [f  (deref (resolve (:var e)))
                  hh (if (:handler e)
                       ((deref (resolve (:handler e))))
                       ((resolve 'perturb.script/model-handler) {:chunk-size 1}))
                  r  (try
                       (with-bindings
                         {(resolve 'perturb.effect/*handlers*)
                          {'perturb.wire/socket hh}}
                         {:ok (apply f (:run e))})
                       (catch :default t {:err (pr-str t)}))]
              (println (str "  [" (if (:ok r) "ok  " "FAIL") "] " (:var e)
                            "  " (pr-str (:run e))
                            "  -> " (if (:ok r) (pr-str (:ok r)) (str "THREW " (:err r)))))
              (if (:ok r) acc (conj acc (:var e)))))
          [] runs)]
    (doseq [e no-run]
      (println (str "  [ -  ] " (:var e)
                    "  takes a live capability; exercised through its caller")))
    (println)
    (println (str "  " (- (count runs) (count fails)) "/" (count runs)
                  " accepted programs ran to completion"
                  (if (empty? fails) "" (str "; FAILED " (vec fails)))))
    fails))

(defn- run-implementation
  "Check one real implementation namespace and REPORT. Not a gate.

  CHANGED (E18) in two ways, both flagged:
    - it takes the namespace, so the same stage can report on `perturb.nrepl`
      and on `perturb.http`;
    - it prints the diagnostics of AXIOMS as well as of checked functions. The
      previous version computed `bad` over the non-axioms only, so a diagnostic
      raised against an axiom — `check-annotation-consistency!` is the only
      thing that can raise one — was collected and never shown. That is how the
      pre-existing inconsistency on `perturb.nrepl/open` stayed invisible; it is
      printed below now. No rule changed: the same diagnostics, displayed."
  [sp ns-name banner]
  (println)
  (println (str "== " ns-name " ==========================================="))
  (doseq [l banner] (println (str "   " l)))
  (println)
  (let [results (check-namespace! sp ns-name)
        ax?     (fn [r] (axiom? sp (:var r)))
        checked (remove ax? results)
        bad     (filter (fn [r] (seq (:diagnostics r))) checked)
        ax-bad  (filter (fn [r] (seq (:diagnostics r))) (filter ax? results))]
    (doseq [r results]
      (println (str "  "
                    (cond (and (ax? r) (seq (:diagnostics r))) "[axiom!]"
                          (ax? r) "[axiom] "
                          (empty? (:diagnostics r)) "[ok   ] "
                          :else "[NO   ] ")
                    (:var r)
                    (cond (primitive? sp (:var r)) "  transition of the declared machine"
                          (representation? sp (:var r)) "  inside the capability's representation"
                          (empty? (:diagnostics r)) ""
                          :else (str "  " (vec (sort (map name (kinds r)))))))))
    (println)
    (doseq [r bad]
      (println (str "  --- " (:var r)))
      (print (render (:diagnostics r))))
    (when (seq ax-bad)
      (println "  --- diagnostics raised against AXIOMS (previously collected and never shown):")
      (doseq [r ax-bad]
        (println (str "  --- " (:var r)))
        (print (render (:diagnostics r)))))
    ;; READ THIS COUNT CAREFULLY. It is small on purpose: an axiom is a function
    ;; whose body was NOT checked, and every axiom is a place a wrong program
    ;; could hide. The number that matters is how many were checked, not how
    ;; many passed.
    (println (str "  " (count checked) " of " (count results)
                  " functions in " ns-name " were CHECKED; " (count bad)
                  " rejected."))
    (println (str "  " (count (filter (fn [r] (primitive? sp (:var r))) results))
                  " are transitions of a declared machine and "
                  (count (filter (fn [r] (representation? sp (:var r))) results))
                  " are inside a representation — those "
                  (count (filter ax? results)) " bodies are AXIOMS, believed, not checked."))
    bad))

(defn- collect-locals [x acc]
  (cond
    (map? x) (if (= :local (:op x))
               (conj acc x)
               (reduce (fn [a e] (collect-locals (second e) a)) acc (seq x)))
    (coll? x) (reduce (fn [a e] (collect-locals e a)) acc x)
    :else acc))

(defn report-local-finding
  "§2.1's `:local` claim, measured on real IR instead of inferred from source."
  []
  (let [n  (get @pir/captured "perturb.corpus/shadowed-rebind")
        ar (first (:arities (:init n)))
        bs (:bindings (:body ar))
        ls (collect-locals (:body ar) [])]
    (println)
    (println "== §2.1, now measured ==================================================")
    (println "   \"`:local` carries a name, not binding identity — linearity checking")
    (println "    needs alpha-conversion or a `:binding-id`.\" PERTURB-DESIGN §1.1 states")
    (println "   this from reading jolt-core/jolt/ir.clj; §4 records it as UNTESTED and")
    (println "   says to assume it may be wrong until a checker walks real IR.")
    (println)
    (println "   perturb.corpus/shadowed-rebind binds three DIFFERENT Connection")
    (println "   instances to one name. Its real IR, as the back end received it:")
    (println)
    (let [cs (filter (fn [l] (= "c" (:name l))) ls)]
      (println (str "     :let binding names     " (pr-str (vec (map first bs)))
                    "   <- three separate bindings"))
      (println (str "     :local nodes naming c  " (count cs)
                    ", every one of them exactly " (pr-str (first cs))))
      (println (str "     a :binding-id key?     "
                    (pr-str (contains? (first cs) :binding-id)))))
    (println)
    (println "   Three capability instances, one node shape, no :binding-id and no")
    (println "   alpha-renaming: the analyzer's lexical env is a SET of names")
    (println "   (jolt-core/jolt/analyzer.clj:84-86), so a shadowing binding reuses the")
    (println "   name outright. THE CLAIM HOLDS. The checker therefore allocates its")
    (println "   own binding id at every binding occurrence and keys linearity on that;")
    (println "   perturb.corpus/shadowing-hides-a-leak is the program a name-keyed")
    (println "   checker would wrongly accept, and it is in the reject corpus above.")))

(defn report-obligation-finding
  "E18 finding 3, and what is now done about it. Printed from the DIAGNOSTICS
  the run above actually raised, not asserted.

  This stage is not a gate — `run-corpus` is. It exists so that the shape of the
  answer is visible: one obligation, two outcomes that are decisions and one
  that is a REFUSAL, and no fourth outcome in which the checker shrugs and
  accepts."
  []
  (let [ds  (deref diagnostics)
        of  (fn [k] (filter (fn [d] (= k (:kind d))) ds))
        bad (of :refinement)
        und (of :refinement-undischarged)
        one (fn [d]
              (println)
              (print (render-one d)))]
    (println)
    (println "== a refinement attached to a typestate transition ====================")
    (println "   §1.2's four axes are uniqueness, linearity, typestate and contention,")
    (println "   and E18 finding 3 is that a Content-Length body's terminal condition")
    (println "   is none of them: `:finished` is a state, `wrote exactly N` is")
    (println "   arithmetic over a run-time integer. §1.3 reserves that class for")
    (println "   refinements and nothing attached one to a state.")
    (println)
    (println "   It is attached now, as ONE EXTRA KEY on a transition map:")
    (println)
    (println "     {:op 'perturb.http/body-finish! :from :open :to :finished")
    (println "      :perturb.cap/refine {:name     wrote-exactly-content-length")
    (println "                           :requires (= written declared)}}")
    (println)
    (println "   `declared` and `written` are ghost variables, minted by the")
    (println "   operation that mints the capability and moved by `:update` on the")
    (println "   write edge. perturb.refine decides the formula; -M:refine is that")
    (println "   procedure alone, with every case it must REFUSE listed beside every")
    (println "   case it decides.")
    (println)
    (println (str "   " (count bad) " program(s) REFUTED it and " (count und)
                  " could not be discharged at all."))
    (println)
    (println "   --- the obligation, DECIDED FALSE:")
    (if (empty? bad)
      (println "   none — the corpus changed")
      (doseq [d bad] (one d)))
    (println)
    (println "   --- the obligation, REFUSED. This is the honest answer for the")
    (println "       undecidable case and it is a REJECTION, with its own kind, not")
    (println "       a silent accept (E15's false accept, E17's warning):")
    (if (empty? und)
      (println "   none — the corpus changed")
      (doseq [d und] (one d)))
    (println)
    (println "   WHAT IS NOT CLOSED. The second obligation on the same capability —")
    (println "   `body-finished-before-conn-reused` — relates TWO machines in time")
    (println "   and is still unstatable: it is not arithmetic on one edge, it is an")
    (println "   ordering between two typestate machines, and §1.2 has no way to")
    (println "   relate two of them. Nothing here touched it.")))

(defn report-sum-finding
  "E26 finding 7, and what is now done about it. Printed from the DIAGNOSTICS the
  run above actually raised, not asserted.

  Not a gate — `run-corpus` over `perturb.dbtxcorpus` is. It exists so the shape
  of the answer is visible: a declaration that could not be written at all, a
  rejection whose remedy is a case split rather than a different operation, and
  the one thing that stays unresolvable."
  [sp]
  (let [ds (filter (fn [d] (= :state-unresolved (:kind d))) (deref diagnostics))
        tx (get (:declarations sp) 'perturb.dbtx/Tx)]
    (println)
    (println "== a SUM :to, and the case split that eliminates it ===================")
    (println "   `casselc/db`'s verified-sqlite-begin! is a typestate machine written")
    (println "   by hand in another language, and ONE of its operations has THREE")
    (println "   destinations chosen at run time: BEGIN took effect, BEGIN provably")
    (println "   did not, or the uncertainty is unresolvable and the handle is")
    (println "   poisoned. perturb could not declare it — not because an edge out of")
    (println "   any state was missing (`:from` has taken a collection since E5) but")
    (println "   because the primitive table stored ONE entry per [capability")
    (println "   operation] and the second silently overwrote the first.")
    (println)
    (println "   Declared now, as several entries sharing an :op and a :from:")
    (println)
    (doseq [t (:transitions (:perturb.cap/typestate tx))]
      (println (str "     {:op " (:op t) "  :from " (pr-str (:from t))
                    "  :to " (pr-str (:to t)) "}")))
    (println)
    (println "   and eliminated by a declared predicate, not by an inference:")
    (println)
    (doseq [d (:perturb.cap/discriminator tx)]
      (println (str "     " (pr-str d))))
    (println)
    (println (str "   " (count ds) " program(s) were REFUSED for using a sum they had not"
                  " resolved."))
    (println "   Each names the members, the ones the operation does not admit, and")
    (println "   the discriminators that would have eliminated them:")
    (if (empty? ds)
      (println "   none — the corpus changed")
      (doseq [d ds] (println) (print (render-one d))))
    (println)
    (println "   WHAT IS NOT CLOSED. The discriminator is an AXIOM: nothing checks")
    (println "   that `autocommit?` returns true exactly when the handle is :idle,")
    (println "   any more than anything checks that `begin` takes one of the three")
    (println "   edges it declares. And a sum with NO declared discriminator cannot")
    (println "   be eliminated at all — it can only be consumed by an operation")
    (println "   whose :from admits every member. See report-limits item 14.")))

(defn report-cancellation-finding
  "§4.6 piece 5, and what is now done about it. Printed from the DIAGNOSTICS the
  run above actually raised, not asserted.

  Not a gate — `run-corpus` over `perturb.dbtxcorpus` and
  `run-cancellation-corpus` are. It exists so the shape of the answer is
  visible, and so that the thing this mechanism does NOT do is printed beside
  the thing it does rather than only in `report-limits`."
  [sp]
  (let [ds  (deref diagnostics)
        cu  (filter (fn [d] (= :cancelled-use (:kind d))) ds)
        cd  (filter (fn [d] (= :cancelled-state-unsound (:kind d))) ds)
        tx  (get (:declarations sp) 'perturb.dbtx/Tx)
        cs  (get tx cap/cancelled-key)]
    (println)
    (println "== MUST_CLOSE: cancellation as a STATE, not a term ====================")
    (println "   PERTURB-DESIGN §4.6's cancellation list had four pieces, all built on")
    (println "   Fowler's `cancel` TERM typed as a consuming use. E26 finding 8 read")
    (println "   `casselc/db` and found a fifth: the obligation is discharged by")
    (println "   ENTERING A STATE whose only legal outgoing edge is the destructor. No")
    (println "   new term, and no live-capability set enumerated at abort sites.")
    (println)
    (println (str "   perturb.dbtx/Tx declares " cap/cancelled-key " " cs ", and the machine"))
    (println "   is what makes the claim true rather than a comment:")
    (println)
    (doseq [t (:transitions (:perturb.cap/typestate tx))]
      (println (str "     {:op " (:op t) "  :from " (pr-str (:from t))
                    "  :to " (pr-str (:to t)) "}"
                    (cond (and (= cs (:to t)) (admits? (:from t) cs))
                          "   <- IMPOSSIBLE: would be an edge out that is not the destructor"
                          (= cs (:to t)) "   <- reaches the cancelled state"
                          (admits? (:from t) cs) "   <- the ONLY edge OUT, and it is the destructor"
                          :else ""))))
    (println)
    (println "   THE DESTRUCTOR NEEDS NO CASE SPLIT. `:from :any` admits every summand,")
    (println "   which is E26 finding 7's sum machinery doing the work a `cancel` term")
    (println "   per capability class would otherwise have to do. That is the whole")
    (println "   reason this piece is cheaper than pieces 1 and 2 together.")
    (println)
    (println (str "   " (count cu) " program(s) used a cancelled capability for something other"))
    (println "   than its destructor, and were REFUSED:")
    (if (empty? cu)
      (println "   none — the corpus changed")
      (doseq [d cu] (println) (print (render-one d))))
    (println)
    (println (str "   " (count cd) " declaration(s) — all of them FIXTURES from"))
    (println "   perturb.dbtxcorpus/cancellation-corpus, none of them shipped — claimed a")
    (println "   cancelled state the machine beside them contradicts. THE DECLARATION IS")
    (println "   THE ONE AXIOM THIS CHECKER WILL NOT TAKE: `:from`/`:to` are claims about")
    (println "   what a body does, and a cancelled state is a claim about what is")
    (println "   IMPOSSIBLE — settled by the `:from`s written in the same map, with no")
    (println "   body read.")
    (if (empty? cd)
      (println "   none — the fixture corpus changed")
      (doseq [d cd] (println) (print (render-one d))))
    (println)
    (println "   WHAT THIS IS NOT, AND IT IS THE HEADLINE. Fowler et al. POPL 2019 §1.4")
    (println "   names TWO quandaries of silently discarded endpoints: a developer gets")
    (println "   no feedback for an unfinished protocol, AND \"the peer may be left")
    (println "   waiting forever\". A STATE DISCHARGES THE FIRST AND CANNOT TOUCH THE")
    (println "   SECOND, because a state is a fact about the HOLDER. NO PEER IS")
    (println "   NOTIFIED here: there is no zapper thread, nothing propagates through")
    (println "   buffered values, and nothing raises in a counterparty. This is not")
    (println "   cancellation in EGV's sense and must not be described as if it were.")
    (println)
    (println "   It is sufficient exactly when that second quandary is vacuous — no")
    (println "   peer whose progress depends on being told. TRUE of a database")
    (println "   transaction, which `sqlite3_close_v2` disposes of with nothing")
    (println "   blocked; FALSE of perturb.http's ServerConn, whose counterparty is a")
    (println "   client on an open socket. That is why the key is declared per")
    (println "   capability and not implicit for all of them, and why perturb.http")
    (println "   declares none. See report-limits item 15.")))

(defn -main [& _]
  (println line)
  (println "perturb.check — static capability checking over real Jolt IR")
  (println line)
  (println)
  (pir/capture! ['perturb.nrepl 'perturb.corpus 'perturb.http 'perturb.httpcorpus
                 'perturb.dbtx 'perturb.dbtxcorpus])
  (let [sp (spec)]
    (println (str "  capabilities declared : " (vec (keys (:declarations sp)))))
    (println (str "  operations annotated  : " (vec (sort (map str (keys (:operations sp)))))))
    (println (str "  machine primitives    : "
                  (vec (sort (map (fn [k] (str (second k) " of " (first k)))
                                  (keys (:primitives sp)))))))
    (println (str "  declared edges        : "
                  (reduce (fn [n v] (+ n (count v))) 0 (vals (:primitives sp)))
                  " across " (count (:declarations sp)) " capabilities, "
                  (count (:transitions-of sp)) " distinct operations"
                  "  (keyed [capability operation]: E18 1(a);"
                  " several edges per key: E26 7)"))
    (println (str "  IR defs captured      : " (count @pir/captured)))
    (println)
    (let [dfails (run-declaration-corpus 'perturb.httpcorpus/declaration-corpus
                                         "an annotation against a machine; no program, no body")
          cdfails (run-cancellation-corpus
                    'perturb.dbtxcorpus/cancellation-corpus
                    "a machine against its own claim about what is impossible")
          cxfails (run-real-cancellation-declarations sp)
          mdfails (run-machine-corpus
                    'perturb.dbtxcorpus/machine-corpus
                    "the absorbing terminal state, and source collections as sugar")
          mxfails (run-real-machine-declarations sp)
          fails  (run-corpus sp "perturb.corpus" 'perturb.corpus/expectations
                             "nREPL: one capability, a straight-line typestate")
          rfails (run-accepts 'perturb.corpus/expectations)
          hfails (run-corpus sp "perturb.httpcorpus" 'perturb.httpcorpus/expectations
                             "HTTP: two capabilities at once, a typestate CYCLE, an obligation")
          hrfails (run-accepts 'perturb.httpcorpus/expectations)
          sfails (run-corpus sp "perturb.dbtxcorpus" 'perturb.dbtxcorpus/expectations
                             "a SUM :to: one operation, three destinations, one case split")
          srfails (run-accepts 'perturb.dbtxcorpus/expectations)]
      (run-implementation sp "perturb.nrepl"
                          ["perturb.nrepl, unmodified, checked by the same rules. This is NOT a"
                           "gate: it is the measurement §1.2 and §4.6 say has never been taken."])
      (run-implementation sp "perturb.http"
                          ["perturb's SECOND protocol. Three capabilities, TWELVE declared edges"
                           "across NINE operations — three of them advance two machines at once —"
                           "and ZERO :perturb.cap/representation entries; see the note in that"
                           "namespace for why that zero is repackaging and not progress (E18)."])
      (run-implementation sp "perturb.dbtx"
                          ["the THIRD machine: one operation, three destinations. Read the count"
                           "at the bottom, not the corpus above it — a machine whose every"
                           "operation is a transition is a machine whose every body is an AXIOM,"
                           "and that includes the two predicates the case split trusts."])
      (report-obligation-finding)
      (report-sum-finding sp)
      (report-cancellation-finding sp)
      (report-local-finding)
      (println)
      (doseq [l (report-limits)] (println (str "  " l)))
      (println)
      (println line)
      (let [all (concat dfails cdfails cxfails mdfails mxfails
                        fails rfails hfails hrfails sfails srfails)]
        (if (empty? all)
          (do (println "CHECK OK — every declaration fixture (annotation, cancelled-state AND")
              (println "           machine), every corpus verdict in ALL THREE corpora, and")
              (println "           every accepted program's run, is the recorded one")
              (System/exit 0))
          (do (println (str "CHECK FAILED — declarations " (vec dfails)
                            "  cancelled-state " (vec (concat cdfails cxfails))
                            "  machine " (vec (concat mdfails mxfails))
                            "  verdicts " (vec (concat fails hfails sfails))
                            "  runs " (vec (concat rfails hrfails srfails))))
              (System/exit 1)))))))

