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

(defn spec
  "The checker's specification, built from `cap/checker-input`. Nothing here is
  derived from source: the declarations and annotations are taken as given."
  []
  (let [ci    (cap/checker-input)
        decls (:perturb.cap/declarations ci)
        ops   (:perturb.cap/operations ci)
        ;; opsym -> the transition it is a primitive of, if any
        prims (reduce (fn [acc e]
                        (let [decl (second e)
                              ts   (:perturb.cap/typestate decl)]
                          (reduce (fn [a t]
                                    (assoc a (:op t) {:cap (first e)
                                                      :from (:from t) :to (:to t)}))
                                  acc (:transitions ts))))
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
    {:declarations decls :operations ops :primitives prims :representation repr}))

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
    (= :fresh (:v val)) {:bid nil :cap (:cap val) :state (:state val) :name nil}
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
  "How an annotation entry names its argument, for a diagnostic."
  [entry]
  (if (:arg entry)
    (str "argument " (:arg entry) (path-str (entry-path entry)))
    "any argument (UNPOSITIONED)"))

(defn- consume-arg
  "TYPESTATE + LIVE. Apply one :consumes/:borrows entry of `opsym` to the argument
  list. Returns {:st st :used bid|nil}.

  POSITIONED. If the entry carries `:arg n` (and optionally `:at [i …]`), the
  capability is looked for exactly there and nowhere else. Without `:arg` the
  checker falls back to scanning the argument list, which is what §1.2's
  original unpositioned shape forces and what E15 showed is not sound enough to
  build on."
  [st sp opsym entry vals nodes used move?]
  (let [want-cap   (:cap entry)
        want-state (:state entry)
        path       (entry-path entry)
        hit (if (:arg entry)
              (let [i    (:arg entry)
                    leaf (val-at (nth' vals i) path)
                    lc   (live-cap st leaf)]
                (when (and lc (= (:cap lc) want-cap)) {:i i :lc lc :leaf leaf}))
              (loop [i 0]
                (if (>= i (count vals))
                  nil
                  (let [lc (live-cap st (nth vals i))]
                    (if (and lc (= (:cap lc) want-cap) (not (contains? used i)))
                      {:i i :lc lc :leaf (nth vals i)}
                      (recur (inc i)))))))
        dead-leaf (if (:arg entry)
                    (let [leaf (val-at (nth' vals (:arg entry)) path)]
                      (when (and (= :dead (:v leaf)) (= want-cap (:cap leaf))) leaf))
                    (first (remove nil?
                                   (map (fn [v]
                                          (:val (first (filter (fn [l]
                                                                 (and (= :dead (:v (:val l)))
                                                                      (= want-cap (:cap (:val l)))))
                                                               (leaves v)))))
                                        vals))))
        dead-here? (not (nil? dead-leaf))]
    (cond
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
          {:st st :used nil :ok false})

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
           :used (:i hit) :ok false})

      :else
      {:st (if move? (mark-moved st (:bid (:lc hit)) {:by opsym :pos (:pos st)}) st)
       :used (:i hit) :ok true})))

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
                (let [rr (consume-arg (:st acc) sp opsym entry vals nodes (:used acc) move?)]
                  {:st (:st rr) :used (conj (:used acc) (:used rr))
                   :ok (and (:ok acc) (:ok rr))}))
        a1    (reduce (fn [acc e] (step acc e false)) {:st st1 :used #{} :ok true} (:borrows ann))
        a2    (reduce (fn [acc e] (step acc e true))  a1                           (:consumes ann))
        st2   (:st a2)]
    ;; the operation did not type-check, so its result has no capability type
    (if (not (:ok a2))
      {:st st2 :val OPAQUE}
      {:st st2 :val (produced-value st2 opsym (:produces ann))})))

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
  capability is a boundary and is reported."
  [opsym args]
  (cond
    (= 'clojure.core/first opsym)  0
    (= 'clojure.core/second opsym) 1
    (and (= 'clojure.core/nth opsym)
         (= 2 (count args))
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
                                         :pos pos :fn-depth (:fn-depth s)})
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
            st2  (bind-name (:st rb) nm {:bid (first (:bids rb)) :val (:val rb)})]
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
        st1  (:st r)
        outer (remove (fn [b] (contains? (set (:bids r)) b)) (keys (:caps st1)))
        ctx  {:bids (:bids r) :shapes (:shapes r)
              :entry-moved (:moved st1) :outer (vec outer)}
        rb   (w (assoc st1 :loop-ctx ctx) (:body node))
        stb  (assoc (:st rb) :loop-ctx (:loop-ctx st))
        st2  (check-scope-exit stb sp (:bids r) (:val rb) "the end of the loop")]
    {:st (pop-scope st2) :val (:val rb)}))

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
          {:st (assoc st1 :caps (merge (:caps sa) (:caps sb))
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

(defn- primitive? [sp opsym] (contains? (:primitives sp) opsym))
(defn- representation? [sp opsym] (contains? (:representation sp) opsym))
(defn- axiom? [sp opsym] (or (primitive? sp opsym) (representation? sp opsym)))

(defn- check-annotation-consistency! [sp opsym ann]
  (let [t (get (:primitives sp) opsym)]
    (when t
      (let [c  (first (:consumes ann))
            p  (first (:produces ann))
            from (if c (:state c) nil)
            to   (if p (:state p) nil)]
        (when (or (not= from (:from t)) (not= to (:to t)))
          (report! {:kind :annotation-inconsistent :op opsym :cap (:cap t)
                    :detail [(str "declared machine: " (:from t) " -> " (:to t))
                             (str "operation annotation: " from " -> " to)
                             "the two data sources cap/checker-input emits disagree"]}))))))

(defn check-def!
  "Check one captured :def node. Returns the number of diagnostics it produced."
  [sp node]
  (let [opsym (symbol (:ns node) (:name node))
        ann   (get (:operations sp) opsym)
        init  (:init node)
        before (count @diagnostics)]
    (when ann (check-annotation-consistency! sp opsym ann))
    (cond
      (axiom? sp opsym) 0   ;; axiom of the machine, or inside its representation
      (or (nil? init) (not= :fn (:op init))) 0
      :else
      (do
        (doseq [ar (:arities init)]
          (let [st0 {:scopes [{}] :caps {} :moved {} :pos (:pos node)
                     :in (str opsym) :spec sp :fn-depth 0 :bottom false
                     :loop-ctx nil :use-ctx nil}
                ;; A derived (non-primitive) operation's annotation binds its
                ;; parameters. Entries carrying `:arg n` say which parameter
                ;; exactly; entries without it fall back to matching specs to
                ;; parameters IN ORDER, which is the checker's own convention
                ;; and not §1.2's — see report-limits.
                specs (vec (concat (:borrows ann) (:consumes ann)))
                positioned? (and (seq specs) (every? (fn [e] (:arg e)) specs))
                spec-for (fn [i]
                           (if positioned?
                             (first (filter (fn [e] (= i (:arg e))) specs))
                             (nth' specs i)))]
            (let [r0  (reduce
                        (fn [acc i]
                          (let [s   (:st acc)
                                p   (nth (:params ar) i)
                                e   (spec-for i)]
                            (if (and ann e)
                              ;; the parameter holds the capability at the
                              ;; entry's path — bare if there is none
                              (let [bid (fresh-bid)
                                    s1  (bind-cap s bid
                                                  {:cap (:cap e)
                                                   :state (if (coll? (:state e))
                                                            (first (:state e)) (:state e))
                                                   :name (str p (path-str (entry-path e)))
                                                   :pos (:pos node) :fn-depth 0})
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
   "     client be annotated at all (E15). Two limits remain: paths are ONE level"
   "     of tuple nesting only, and an annotation whose entries omit `:arg` still"
   "     falls back to matching specs to parameters IN ORDER. With ONE capability"
   "     that fallback is unprincipled; with TWO it binds the wrong parameter to"
   "     the wrong capability and emits five diagnostics, none naming the"
   "     annotation (perturb.httpcorpus/unpositioned-two-cap-helper, E18)."
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
   "  8. Only `first`, `second` and `nth`-with-a-constant eliminate a tuple."
   "     Destructuring, `peek`, `last`, or a computed index lose the capability"
   "     to OPAQUE — which is silent, not a diagnostic. This is the most likely"
   "     place for a FALSE ACCEPT to hide today."
   ""
   "  9. AN OPERATION BELONGS TO AT MOST ONE CAPABILITY. `spec`'s primitive table"
   "     is keyed by operation symbol, so a second declaration naming the same"
   "     operation OVERWRITES the first. perturb.http declares 10 transitions"
   "     across three capabilities and this checker sees 9 primitives."
   "     `perturb.http/accept` mints a ServerConn from a Listener and"
   "     `body-finish!` ends a ResponseBody and returns a ServerConn: both are"
   "     ordinary, neither can be declared, and both draw a spurious"
   "     `annotation-inconsistent` printed above. So does `perturb.nrepl/open`,"
   "     which has since E17 and was never displayed. E18 finding 1."
   ""
   " 10. A STATE CANNOT CARRY A REFINEMENT, so an obligation is unstatable. A"
   "     Content-Length body writer that declares 6 octets and writes 3 reaches"
   "     :finished and is ACCEPTED — see the section above, which prints what it"
   "     put on the wire. Nothing in §1.2 can relate two machines in time"
   "     either. E18 finding 3."
   ""
   " 11. `:borrows` AND `:produces` OF THE SAME CAPABILITY duplicates it. The"
   "     annotation is legal, both halves check, and the caller is then reported"
   "     for leaking a capability it disposed of correctly"
   "     (perturb.httpcorpus/uses-borrow-and-return). E18 finding 1(c)."])

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
  "E18 finding 3, printed from the LEDGER rather than asserted.

  `perturb.httpcorpus/short-body-still-type-checks` was ACCEPTED above and RAN
  above. This prints what it put on the wire while doing so. Nothing here is a
  gate: the point is that the checker had nothing to say."
  []
  (let [vs (filter (fn [e] (= :VIOLATED (:perturb.http/verdict e)))
                   (deref (deref (resolve 'perturb.cap/ledger))))]
    (println)
    (println "== the obligation the typestate axis cannot state =====================")
    (println "   §1.2's four axes are uniqueness, linearity, typestate and contention.")
    (println "   A Content-Length response body's terminal condition is not a state:")
    (println "   it is `wrote exactly N octets`, arithmetic over a run-time integer.")
    (println)
    (if (empty? vs)
      (println "   no violation recorded — the accept set did not run, or was changed")
      (doseq [e vs]
        (println (str "   " (:perturb.cap/id e) "  declared Content-Length "
                      (:perturb.http/declared e) ", wrote " (:perturb.http/written e)
                      "  -> " (name (:perturb.http/verdict e))))))
    (println)
    (println "   perturb.check ACCEPTED the program that did this, and the gate RAN it")
    (println "   to completion. The capability reached :finished, which is all the")
    (println "   typestate axis can require. The obligation is written as data on")
    (println "   perturb.http/body-capability with :class :refinement, and nothing in")
    (println "   perturb discharges a refinement (§1.3 reserves it for Ansatz).")))

(defn -main [& _]
  (println line)
  (println "perturb.check — static capability checking over real Jolt IR")
  (println line)
  (println)
  (pir/capture! ['perturb.nrepl 'perturb.corpus 'perturb.http 'perturb.httpcorpus])
  (let [sp (spec)]
    (println (str "  capabilities declared : " (vec (keys (:declarations sp)))))
    (println (str "  operations annotated  : " (vec (sort (map str (keys (:operations sp)))))))
    (println (str "  machine primitives    : " (vec (sort (map str (keys (:primitives sp)))))))
    (println (str "  IR defs captured      : " (count @pir/captured)))
    (println)
    (let [fails  (run-corpus sp "perturb.corpus" 'perturb.corpus/expectations
                             "nREPL: one capability, a straight-line typestate")
          rfails (run-accepts 'perturb.corpus/expectations)
          hfails (run-corpus sp "perturb.httpcorpus" 'perturb.httpcorpus/expectations
                             "HTTP: two capabilities at once, a typestate CYCLE, an obligation")
          hrfails (run-accepts 'perturb.httpcorpus/expectations)]
      (run-implementation sp "perturb.nrepl"
                          ["perturb.nrepl, unmodified, checked by the same rules. This is NOT a"
                           "gate: it is the measurement §1.2 and §4.6 say has never been taken."])
      (run-implementation sp "perturb.http"
                          ["perturb's SECOND protocol. Three capabilities, ten transitions, and"
                           "ZERO :perturb.cap/representation entries — see the note in that"
                           "namespace for why that is repackaging and not progress (E18)."])
      (report-obligation-finding)
      (report-local-finding)
      (println)
      (doseq [l (report-limits)] (println (str "  " l)))
      (println)
      (println line)
      (let [all (concat fails rfails hfails hrfails)]
        (if (empty? all)
          (do (println "CHECK OK — every corpus verdict in BOTH corpora is the recorded one,")
              (println "           and every accepted program runs")
              (System/exit 0))
          (do (println (str "CHECK FAILED — verdicts " (vec (concat fails hfails))
                            "  runs " (vec (concat rfails hrfails))))
              (System/exit 1)))))))

