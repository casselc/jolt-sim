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
  a side condition, a refinement — travels with it and needs no change here."
  [ci]
  (let [decls (:perturb.cap/declarations ci)
        ops   (:perturb.cap/operations ci)
        prims (reduce (fn [acc e]
                        (let [cname (first e)
                              ts    (:perturb.cap/typestate (second e))]
                          (reduce (fn [a t] (assoc a [cname (:op t)] (assoc t :cap cname)))
                                  acc (:transitions ts))))
                      {} decls)
        ;; opsym -> every declared edge that operation is, across all machines,
        ;; in a stable order. An operation with no entry here is not a primitive.
        by-op (reduce (fn [acc k]
                        (update acc (second k) (fn [v] (conj (or v []) (get prims k)))))
                      {} (sort-by str (keys prims)))
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
     ;; REFINEMENT (E18 finding 3). [capability operation] -> the refinement on
     ;; that transition. Keyed by the PAIR, which is now also how `prims` is
     ;; keyed — the two fixes met here and agreed.
     :refinements (cap/refinements decls)}))

(defn spec
  "The checker's specification, built from `cap/checker-input`."
  []
  (spec-from (cap/checker-input)))

(defn- decl-of [sp c] (get (:declarations sp) c))

(defn- terminal? [sp c st]
  (let [ts (:perturb.cap/typestate (decl-of sp c))
        t  (:terminal ts)]
    (if (coll? t) (contains? (set t) st) (= t st))))

(defn- state-ok?
  "A spec entry's :state may be a single state, a collection of states, or :any —
  the direct analogue of move_to's `sources` frozenset in mode_checker.py."
  [want got]
  (cond
    (= :any want) true
    (coll? want)  (contains? (set want) got)
    :else         (= want got)))

(defn- want-str [want] (if (coll? want) (str (vec want)) (str want)))

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
    (= :fresh (:v val)) {:bid nil :cap (:cap val) :state (:state val) :name nil
                         :refine (:refine val) :rlog (:rlog val)}
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

(defn- refine-update
  "Apply the `:update` on `[cap opsym]` to a live capability's ghost state. With
  no `:update` the state is carried across unchanged, which is what makes an
  operation that does not touch the arithmetic transparent to it."
  [st sp cap-name opsym lc nodes]
  (let [u   (:update (get (:refinements sp) [cap-name opsym]))
        env (or (:refine lc) {})]
    (if (or (nil? u) (empty? env))
      {:refine env :rlog (vec (:rlog lc))}
      (let [af   (arg-fn st nodes)
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

(defn- check-requires!
  "Discharge the `:requires` on `[cap opsym]`, if there is one, against the ghost
  state of the capability being consumed. Three outcomes and only three: proved,
  refuted, or REFUSED. Returns nil either way — this reports, it does not
  change the abstract state."
  [st sp cap-name opsym lc nodes]
  (let [r   (get (:refinements sp) [cap-name opsym])
        req (:requires r)]
    (when (and req lc)
      (let [env (or (:refine lc) {})
            v   (ref/decide env (arg-fn st nodes) req)
            head [(str "obligation    " (:name r) "   " (pr-str req))
                  (str "on transition " opsym "  " (:from r) " -> " (:to r))]
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
                  (report! {:kind :capture :cap (:cap c)
                            :detail [(str "capability    `" nm (path-str path) "` : "
                                          (:cap c) "@" (:state c))
                                     (str "captured by a nested fn at " (pir/site (:pos s)))
                                     (str "a `linearity :once` capability may not be closed over")
                                     (str "in            " (:in s))]}))
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

  This does NOT establish that the argument really holds what the entry says —
  the callee's body is an axiom or is checked separately."
  [st sp opsym entry vals nodes move?]
  (let [want-cap   (:cap entry)
        want-state (:state entry)
        path       (entry-path entry)
        i          (:arg entry)
        hit (when i
              (let [leaf (val-at (nth' vals i) path)
                    lc   (live-cap st leaf)]
                (when (and lc (= (:cap lc) want-cap)) {:i i :lc lc :leaf leaf})))
        dead-leaf (when i
                    (let [leaf (val-at (nth' vals i) path)]
                      (when (and (= :dead (:v leaf)) (= want-cap (:cap leaf))) leaf)))
        dead-here? (not (nil? dead-leaf))]
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

      (not (state-ok? want-state (:state (:lc hit))))
      (do (report! {:kind :typestate :cap want-cap :op opsym
                    :detail [(str "operation     " opsym " requires " want-cap "@"
                                  (want-str want-state))
                             (str "capability    "
                                  (if (:name (:lc hit)) (str "`" (:name (:lc hit)) "` ") "")
                                  "is in state " (:state (:lc hit)))
                             (str "at            " (pir/site (:pos st)))
                             (str "in            " (:in st))]})
          {:st (if move? (mark-moved st (:bid (:lc hit)) {:by opsym :pos (:pos st)}) st)
           :used (:i hit) :ok false :lc (:lc hit)})

      :else
      ;; REFINEMENT: `:lc` is returned so the caller can discharge a side
      ;; condition against the ghost state of the capability just consumed.
      {:st (if move? (mark-moved st (:bid (:lc hit)) {:by opsym :pos (:pos st)}) st)
       :used (:i hit) :ok true :lc (:lc hit)})))

(defn- produced-value
  "Build the abstract result of an annotated call from its :produces entries.

  An entry with no `:at` produces the capability BARE — the result is the
  capability itself. An entry with `:at [i]` puts it at position i of a tuple.
  Mixing the two in one annotation is a contradiction and is refused; so is a
  path deeper than one level, which this checker does not implement."
  [st opsym prod]
  (let [paths (map entry-path prod)]
    (cond
      (empty? prod) OPAQUE

      (every? empty? paths)
      (if (= 1 (count prod))
        (let [p (first prod)] {:v :fresh :cap (:cap p) :state (:state p)})
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
                        (if p {:v :fresh :cap (:cap p) :state (:state p)} OPAQUE)))
                    (range n)))))))

(defn- w-annotated-invoke [st sp node opsym ann]
  (let [r     (w-seq (assoc st :use-ctx opsym) (:args node))
        st1   (assoc (:st r) :use-ctx nil)
        vals  (:vals r)
        nodes (:args node)
        step  (fn [acc entry move?]
                (let [rr (consume-arg (:st acc) sp opsym entry vals nodes move?)]
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
        a1    (reduce (fn [acc e] (step acc e false))
                      {:st st1 :ok true :lcs {}} (:borrows ann))
        a2    (reduce (fn [acc e] (step acc e true))  a1 (:consumes ann))
        st2   (:st a2)]
    ;; the operation did not type-check, so its result has no capability type
    (if (not (:ok a2))
      {:st st2 :val OPAQUE}
      {:st st2 :val (with-refinements st2 sp opsym nodes (:lcs a2) (:produces ann)
                                      (produced-value st2 opsym (:produces ann)))})))

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
                (terminal? sp (:cap c) (:state c)))
          s
          (do (report! {:kind :dangling :cap (:cap c)
                        :detail [(str "capability    `" (:name c) "` : " (:cap c)
                                      "@" (:state c))
                                 (str "bound at      " (pir/site (:pos c)))
                                 (str "goes out of scope at " where
                                      " without reaching a terminal state")
                                 (str "in            " (:in s))]})
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
                        b  (fresh-bid)]
                    {:st (bind-cap s1 b {:cap (:cap lc) :state (:state lc)
                                         :name (str nm (path-str path))
                                         :pos pos :fn-depth (:fn-depth s)
                                         ;; REFINEMENT: an affine move carries the
                                         ;; ghost state with the capability.
                                         :refine (:refine lc) :rlog (:rlog lc)})
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

(defn- w-if [st sp node]
  (let [rt  (w st (:test node))
        st1 (:st rt)
        ra  (w st1 (:then node))
        rb  (w st1 (:else node))
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
          {:st (assoc st1 :caps (join-refine st1 (merge (:caps sa) (:caps sb)) sa sb)
                          :moved (merge (:moved sa) (:moved sb)))
           :val (join-vals st1 va vb)})))))

(defn- w-composite
  "A map, set, or assignment target. Unlike a vector, these have no path syntax
  in an annotation, so a capability entering one cannot be followed out."
  [st node kind items]
  (let [r (w-seq st items)]
    (doseq [v (:vals r)]
      (doseq [e (live-caps (:st r) v)]
        (let [lc (:lc e)]
          (report! {:kind :escape :cap (:cap lc)
                    :detail [(str "capability    "
                                  (if (:name lc) (str "`" (:name lc) "` : ") "")
                                  (:cap lc) "@" (:state lc))
                             (str "enters a " kind " at " (pir/site (:pos (:st r))))
                             (str "a capability path names TUPLE positions only, so the")
                             (str "checker cannot follow it out of a " kind " (E13, E15)")
                             (str "in            " (:in (:st r)))]}))))
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
    (let [st1 (push-scope (assoc st :fn-depth (inc (:fn-depth st))))
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
                                           (not (terminal? sp (:cap lc) (:state lc))))
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

  It does NOT establish that the operation's body performs the transition. Every
  transition body is an axiom, unchanged since `mode_checker.py`."
  [sp opsym ann]
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
      (doseq [t ts]
        (let [c  (:cap t)
              cs (entries-for (:consumes ann) c)
              ps (entries-for (:produces ann) c)]
          (if (or (> (count cs) 1) (> (count ps) 1))
            (report! {:kind :annotation-ambiguous-edge :op opsym :cap c
                      :detail [(str "operation     " opsym " declares the edge "
                                    (:from t) " -> " (:to t) " of " c)
                               (str "and its annotation names " c " " (count cs)
                                    " time(s) in :consumes and " (count ps)
                                    " time(s) in :produces")
                               "there is no single pair to compare it against"]})
            (let [from (:state (first cs))
                  to   (:state (first ps))
                  dropped-at-terminal? (and (nil? to) (terminal? sp c (:to t)))]
              (when (or (not= from (:from t))
                        (and (not= to (:to t)) (not dropped-at-terminal?)))
                (report! {:kind :annotation-inconsistent :op opsym :cap c
                          :detail [(str "operation     " opsym)
                                   (str "declared edge     " (pr-str (:from t)) " -> "
                                        (pr-str (:to t)))
                                   (str "annotation says   " (pr-str from) " -> "
                                        (pr-str to))
                                   (str "the declaration and the annotation disagree about "
                                        c "'s edge")]})))))))))

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
                spec-for (fn [i] (first (filter (fn [e] (= i (:arg e))) specs)))]
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
                                 ;; here. Only consumed parameters are.
                                 :bids (if (some (fn [b] (= b e)) (:borrows ann))
                                         (:bids acc)
                                         (conj (:bids acc) bid))})
                              {:st (bind-name s p {:bid (fresh-bid) :val OPAQUE})
                               :bids (:bids acc)})))
                        {:st st0 :bids []} (range (count (:params ar))))
                  st1 (:st r0)
                  rb  (w st1 (:body ar))
                  stb (:st rb)
                  got (shape-of stb (:val rb))]
              ;; A derived operation must return what it declares — at the
              ;; positions it declares. This is where `ping-tuple` stops being a
              ;; rejection: [conn :pinged] with `:produces [{… :at [0]}]` now
              ;; matches, and [:pinged conn] does not.
              (when ann
                (let [want (vec (sort-by (fn [e] (str (:path e)))
                                         (map (fn [p] {:path (entry-path p) :cap (:cap p)
                                                       :state (:state p)})
                                              (:produces ann))))]
                  (cond
                    (not= (map :path want) (map :path got))
                    (report! {:kind :produces-mismatch :op opsym
                              :cap (:cap (or (first want) (first got)))
                              :detail [(str "operation     " opsym " declares :produces at "
                                            (pr-str (mapv (fn [e] (path-str (:path e))) want)))
                                       (str "but its body yields capabilities at "
                                            (pr-str (mapv (fn [e] (path-str (:path e))) got)))
                                       (str "at            " (pir/site (:pos stb)))]})

                    (not (every? (fn [i]
                                   (and (= (:cap (nth want i)) (:cap (nth got i)))
                                        (state-ok? (:state (nth want i)) (:state (nth got i)))))
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
   "     Capturing a live capability is rejected outright rather than reasoned about."
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
   "     ledger is the only thing that ever observed one."])

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

(defn -main [& _]
  (println line)
  (println "perturb.check — static capability checking over real Jolt IR")
  (println line)
  (println)
  (pir/capture! ['perturb.nrepl 'perturb.corpus 'perturb.http 'perturb.httpcorpus])
  (let [sp (spec)]
    (println (str "  capabilities declared : " (vec (keys (:declarations sp)))))
    (println (str "  operations annotated  : " (vec (sort (map str (keys (:operations sp)))))))
    (println (str "  machine primitives    : "
                  (vec (sort (map (fn [k] (str (second k) " of " (first k)))
                                  (keys (:primitives sp)))))))
    (println (str "  declared edges        : " (count (:primitives sp))
                  " across " (count (:declarations sp)) " capabilities, "
                  (count (:transitions-of sp)) " distinct operations"
                  "  (keyed [capability operation]: E18 1(a))"))
    (println (str "  IR defs captured      : " (count @pir/captured)))
    (println)
    (let [dfails (run-declaration-corpus 'perturb.httpcorpus/declaration-corpus
                                         "an annotation against a machine; no program, no body")
          fails  (run-corpus sp "perturb.corpus" 'perturb.corpus/expectations
                             "nREPL: one capability, a straight-line typestate")
          rfails (run-accepts 'perturb.corpus/expectations)
          hfails (run-corpus sp "perturb.httpcorpus" 'perturb.httpcorpus/expectations
                             "HTTP: two capabilities at once, a typestate CYCLE, an obligation")
          hrfails (run-accepts 'perturb.httpcorpus/expectations)]
      (run-implementation sp "perturb.nrepl"
                          ["perturb.nrepl, unmodified, checked by the same rules. This is NOT a"
                           "gate: it is the measurement §1.2 and §4.6 say has never been taken."])
      (run-implementation sp "perturb.http"
                          ["perturb's SECOND protocol. Three capabilities, TWELVE declared edges"
                           "across NINE operations — three of them advance two machines at once —"
                           "and ZERO :perturb.cap/representation entries; see the note in that"
                           "namespace for why that zero is repackaging and not progress (E18)."])
      (report-obligation-finding)
      (report-local-finding)
      (println)
      (doseq [l (report-limits)] (println (str "  " l)))
      (println)
      (println line)
      (let [all (concat dfails fails rfails hfails hrfails)]
        (if (empty? all)
          (do (println "CHECK OK — every declaration fixture and every corpus verdict in BOTH")
              (println "           corpora is the recorded one, and every accepted program runs")
              (System/exit 0))
          (do (println (str "CHECK FAILED — declarations " (vec dfails)
                            "  verdicts " (vec (concat fails hfails))
                            "  runs " (vec (concat rfails hrfails))))
              (System/exit 1)))))))

