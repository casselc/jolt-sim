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

  WHAT IT TRUSTS. An operation that appears in a capability's declared
  `:transitions` is a PRIMITIVE of the state machine: its body manipulates the
  capability's representation directly, below the abstraction the modes describe,
  and the checker takes its annotation as an axiom rather than checking its body.
  That is exactly `mode_checker.py`'s posture — RULES are axioms, `check` checks
  sequences. Every other function is checked, including a function that carries an
  operation annotation but is not a transition of the machine.

  WHAT IT CANNOT SEE — read this before believing an `ok`. See `report-limits`.")

(require '[perturb.cap :as cap])
(require '[perturb.ir :as pir])
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
                      {} decls)]
    {:declarations decls :operations ops :primitives prims}))

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

(def ^:private OPAQUE {:v :opaque})

(defn- nth' [v i] (if (and v (< i (count v))) (nth v i) nil))

(def ^:private bid-counter (atom 0))
(defn- fresh-bid [] (swap! bid-counter inc))

(defn- lookup [st nm]
  (loop [i (dec (count (:scopes st)))]
    (if (neg? i)
      nil
      (let [f (nth (:scopes st) i)]
        (if (contains? f nm) (get f nm) (recur (dec i)))))))

(defn- push-scope [st] (update st :scopes conj {}))
(defn- pop-scope  [st] (assoc st :scopes (vec (butlast (:scopes st)))))

(defn- bind-name [st nm bid]
  (let [i (dec (count (:scopes st)))]
    (assoc st :scopes (assoc (:scopes st) i (assoc (nth (:scopes st) i) nm bid)))))

(defn- bind-cap [st bid m] (assoc st :caps (assoc (:caps st) bid m)))

(defn- mark-moved [st bid m]
  (if (nil? bid) st (assoc st :moved (assoc (:moved st) bid m))))

(defn- live-cap
  "If `val` denotes a capability that is live (bound and not moved, or freshly
  produced), return {:bid :cap :state :name :pos}; else nil."
  [st val]
  (cond
    (= :fresh (:v val)) {:bid nil :cap (:cap val) :state (:state val) :name nil}
    (= :cap (:v val))   (let [b (:bid val)]
                          (when (and (contains? (:caps st) b)
                                     (not (contains? (:moved st) b)))
                            (assoc (get (:caps st) b) :bid b)))
    :else nil))

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

;; LIVE (multicap.Store.live) + capture
(defn- w-local [st node]
  (let [nm  (:name node)
        bid (lookup st nm)]
    (cond
      (nil? bid) {:st st :val OPAQUE}

      (contains? (:moved st) bid)
      (let [m (get (:moved st) bid)
            c (get (:caps st) bid)]
        (report! {:kind :use-after-move :cap (:cap c)
                  :detail [(str "capability    `" nm "` : " (:cap c) "@" (:state c)
                                ", bound at " (pir/site (:pos c)))
                           (str "consumed by   " (:by m) "  at " (pir/site (:pos m)))
                           (str "used again at " (pir/site (:pos st))
                                (if (:use-ctx st) (str "  (argument to " (:use-ctx st) ")") ""))
                           (str "in            " (:in st))]})
        ;; :dead, not :opaque — the capability IS here, it is just consumed.
        ;; Downstream rules must not then also complain that the argument is
        ;; not a capability: one program error, one diagnostic.
        {:st st :val {:v :dead :bid bid :cap (:cap c)}})

      (contains? (:caps st) bid)
      (let [c (get (:caps st) bid)]
        (when (> (:fn-depth st) (:fn-depth c))
          (report! {:kind :capture :cap (:cap c)
                    :detail [(str "capability    `" nm "` : " (:cap c) "@" (:state c))
                             (str "captured by a nested fn at " (pir/site (:pos st)))
                             (str "a `linearity :once` capability may not be closed over")
                             (str "in            " (:in st))]}))
        {:st st :val {:v :cap :bid bid}})

      :else {:st st :val OPAQUE})))

(defn- consume-arg
  "TYPESTATE + LIVE. Apply one :consumes/:borrows entry of `opsym` to the argument
  list. Returns {:st st :used bid|nil}."
  [st sp opsym entry vals nodes used move?]
  (let [want-cap   (:cap entry)
        want-state (:state entry)
        hit (loop [i 0]
              (if (>= i (count vals))
                nil
                (let [lc (live-cap st (nth vals i))]
                  (if (and lc (= (:cap lc) want-cap) (not (contains? used i)))
                    {:i i :lc lc}
                    (recur (inc i))))))]
    (cond
      (nil? hit)
      (do (when (not (some (fn [v] (and (= :dead (:v v)) (= want-cap (:cap v)))) vals))
            (report! {:kind (if move? :untracked-consume :untracked-borrow)
                      :cap want-cap :op opsym
                      :detail [(str "operation     " opsym
                                    (if move? " consumes " " borrows ")
                                    want-cap "@" (want-str want-state))
                               (str "at            " (pir/site (:pos st)))
                               (str "but no argument is a tracked capability of that type")
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
        st2   (:st a2)
        prod  (:produces ann)]
    (cond
      ;; the operation did not type-check, so its result has no capability type
      (not (:ok a2)) {:st st2 :val OPAQUE}
      (empty? prod) {:st st2 :val OPAQUE}
      (= 1 (count prod))
      (let [p (first prod)]
        {:st st2 :val {:v :fresh :cap (:cap p) :state (:state p)}})
      :else
      (do (report! {:kind :multi-produce :op opsym
                    :detail [(str "operation     " opsym " declares " (count prod)
                                  " produced capabilities")
                             (str "§1.2's :produces is UNPOSITIONED — the checker cannot say")
                             (str "which result position each one lands in")
                             (str "at            " (pir/site (:pos st2)))]})
          {:st st2 :val OPAQUE}))))

(defn- w-plain-invoke [st node callee]
  (let [r    (w-seq (assoc st :use-ctx callee) (:args node))
        st1  (assoc (:st r) :use-ctx nil)
        live (remove nil? (map (fn [v] (live-cap st1 v)) (:vals r)))]
    (doseq [lc live]
      (report! {:kind :no-signature :cap (:cap lc)
                :detail [(str "callee        " (or callee "a computed function")
                              "  declares no capability signature")
                         (str "argument      "
                              (if (:name lc) (str "`" (:name lc) "` : ") "")
                              (:cap lc) "@" (:state lc))
                         (str "at            " (pir/site (:pos st1)))
                         (str "a capability may not be passed to a function that does not")
                         (str "declare :consumes / :borrows / :produces for it")
                         (str "in            " (:in st1))]}))
    {:st st1 :val OPAQUE}))

(defn- w-invoke [st sp node]
  (let [f (:fn node)]
    (if (= :var (:op f))
      (let [opsym (symbol (:ns f) (:name f))
            ann   (get (:operations sp) opsym)]
        (if ann
          (w-annotated-invoke st sp node opsym ann)
          (w-plain-invoke st node opsym)))
      (let [rf (w st f)]
        (w-plain-invoke (:st rf) node nil)))))

;; LINEAR — a live, non-terminal capability whose binding goes out of scope
(defn- check-scope-exit [st sp bids result-val where]
  (reduce
    (fn [s bid]
      (let [c (get (:caps s) bid)]
        (if (or (nil? c)
                (contains? (:moved s) bid)
                (and (= :cap (:v result-val)) (= bid (:bid result-val)))
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
    st bids))

(defn- w-bindings
  "Bind a :let / :loop binding vector. AFFINE: binding a name to a bare local that
  holds a capability MOVES it (E6 probe 2 — there is no non-moving alias).
  Each binding occurrence gets a FRESH binding id, which is the alpha-conversion
  §2.1 says :local's name-only shape forces on a checker."
  [st bindings]
  (reduce
    (fn [acc b]
      (let [nm   (nth b 0)
            init (nth b 1)
            rr   (w (:st acc) init)
            st1  (:st rr)
            v    (:val rr)
            lc   (live-cap st1 v)
            st2  (if (and lc (= :cap (:v v)))
                   (mark-moved st1 (:bid v) {:by (str "binding `" nm "` (affine move)")
                                             :pos (:pos st1)})
                   st1)
            bid  (fresh-bid)
            st3  (if lc
                   (bind-cap st2 bid {:cap (:cap lc) :state (:state lc) :name nm
                                      :pos (:pos st2) :fn-depth (:fn-depth st2)})
                   st2)]
        {:st (bind-name st3 nm bid) :bids (conj (:bids acc) bid)
         :states (conj (:states acc) (when lc (:state lc)))
         :caps (conj (:caps acc) (when lc (:cap lc)))}))
    {:st st :bids [] :states [] :caps []} bindings))

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
                (let [want-state (nth' (:states lc-ctx) i)
                      want-cap   (nth' (:caps lc-ctx) i)
                      got        (live-cap s (nth' vals i))]
                  (cond
                    (and (nil? want-state) (nil? got)) s
                    (and (nil? want-state) got)
                    (do (report! {:kind :loop-not-preserving :cap (:cap got)
                                  :detail [(str "back edge at  " (pir/site (:pos s)))
                                           (str "loop binding " i " held no capability at entry")
                                           (str "but the recur argument is " (:cap got)
                                                "@" (:state got))
                                           (str "in            " (:in s))]}) s)
                    (nil? got)
                    (do (report! {:kind :loop-not-preserving :cap want-cap
                                  :detail [(str "back edge at  " (pir/site (:pos s)))
                                           (str "loop binding " i " held " want-cap "@"
                                                want-state " at entry")
                                           (str "but the recur argument is not a tracked capability")
                                           (str "in            " (:in s))]}) s)
                    (not= (:state got) want-state)
                    (do (report! {:kind :loop-not-preserving :cap want-cap
                                  :detail [(str "back edge at  " (pir/site (:pos s)))
                                           (str "loop binding " i " entered at " want-cap "@"
                                                want-state " and re-enters at @" (:state got))
                                           (str "the body is not environment-preserving;"
                                                " it cannot run twice")
                                           (str "in            " (:in s))]}) s)
                    :else (mark-moved s (:bid got) {:by "recur (affine move into the loop binding)"
                                                    :pos (:pos s)}))))
              st1 (range (count (:states lc-ctx))))
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
        ctx  {:bids (:bids r) :states (:states r) :caps (:caps r)
              :entry-moved (:moved st1) :outer (vec outer)}
        rb   (w (assoc st1 :loop-ctx ctx) (:body node))
        stb  (assoc (:st rb) :loop-ctx (:loop-ctx st))
        st2  (check-scope-exit stb sp (:bids r) (:val rb) "the end of the loop")]
    {:st (pop-scope st2) :val (:val rb)}))

;; JOIN (controlflow.py's if rule) with bottom for non-local exits
(defn- join-vals [st a b]
  (cond
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
              la (live-cap sa va) lb (live-cap sb vb)]
          (when (not= (nil? la) (nil? lb))
            (report! {:kind :join :cap (:cap (or la lb))
                      :detail [(str "at the if at  " (pir/site (:pos st1)))
                               (str "one arm yields a capability and the other does not")
                               (str "in            " (:in st1))]}))
          {:st (assoc st1 :caps (merge (:caps sa) (:caps sb))
                          :moved (merge (:moved sa) (:moved sb)))
           :val (join-vals st1 va vb)})))))

(defn- w-composite [st node kind items]
  (let [r (w-seq st items)]
    (doseq [v (:vals r)]
      (let [lc (live-cap (:st r) v)]
        (when lc
          (report! {:kind :escape :cap (:cap lc)
                    :detail [(str "capability    "
                                  (if (:name lc) (str "`" (:name lc) "` : ") "")
                                  (:cap lc) "@" (:state lc))
                             (str "enters a " kind " at " (pir/site (:pos (:st r))))
                             (str "§1.2's :consumes / :produces are UNPOSITIONED — a capability")
                             (str "inside a composite value cannot be named by an annotation,")
                             (str "so the checker cannot follow it out (E13)")
                             (str "in            " (:in (:st r)))]}))))
    {:st (:st r) :val OPAQUE}))

(defn- w-fn [st sp node]
  ;; A closure body is analysed for diagnostics, but its state does not propagate:
  ;; the checker does not model when (or how often) the closure runs.
  (doseq [ar (:arities node)]
    (let [st1 (push-scope (assoc st :fn-depth (inc (:fn-depth st))))
          st2 (reduce (fn [s p] (bind-name s p (fresh-bid))) st1 (:params ar))
          st3 (if (:rest ar) (bind-name st2 (:rest ar) (fresh-bid)) st2)]
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
        (= op :vector) (w-composite st node "vector" (:items node))
        (= op :set)    (w-composite st node "set" (:items node))
        (= op :map)    (w-composite st node "map"
                                    (reduce (fn [a p] (conj (conj a (first p)) (second p)))
                                            [] (:pairs node)))
        (= op :do)
        (let [r (reduce (fn [acc s]
                          (let [rr (w (:st acc) s)
                                lc (live-cap (:st rr) (:val rr))]
                            (when (and lc (= :fresh (:v (:val rr)))
                                       (not (terminal? sp (:cap lc) (:state lc))))
                              (report! {:kind :dangling :cap (:cap lc)
                                        :detail [(str "capability    " (:cap lc) "@" (:state lc))
                                                 (str "produced at   " (pir/site (:pos (:st rr))))
                                                 (str "and discarded in statement position")
                                                 (str "in            " (:in (:st rr)))]}))
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
      (primitive? sp opsym) 0   ;; axiom of the machine — not checked, by design
      (or (nil? init) (not= :fn (:op init))) 0
      :else
      (do
        (doseq [ar (:arities init)]
          (let [st0 {:scopes [{}] :caps {} :moved {} :pos (:pos node)
                     :in (str opsym) :spec sp :fn-depth 0 :bottom false
                     :loop-ctx nil :use-ctx nil}
                ;; A derived (non-primitive) operation's annotation binds its
                ;; parameters. §1.2's :consumes is UNPOSITIONED, so the checker
                ;; matches capability specs to parameters IN ORDER. That
                ;; convention is the checker's, not the design's — see
                ;; report-limits.
                specs (vec (concat (:borrows ann) (:consumes ann)))]
            (let [r0  (reduce
                        (fn [acc i]
                          (let [s   (:st acc)
                                p   (nth (:params ar) i)
                                bid (fresh-bid)
                                e   (nth' specs i)
                                s1  (bind-name s p bid)]
                            (if (and ann e)
                              {:st (bind-cap s1 bid
                                             {:cap (:cap e)
                                              :state (if (coll? (:state e))
                                                       (first (:state e)) (:state e))
                                              :name p :pos (:pos node) :fn-depth 0})
                               :bids (conj (:bids acc) bid)}
                              {:st s1 :bids (:bids acc)})))
                        {:st st0 :bids []} (range (count (:params ar))))
                  st1 (:st r0)
                  rb  (w st1 (:body ar))
                  stb (:st rb)
                  lc  (live-cap stb (:val rb))]
              ;; a derived operation must return what it declares
              (when ann
                (let [p (first (:produces ann))]
                  (cond
                    (and (nil? p) lc)
                    (report! {:kind :produces-mismatch :op opsym :cap (:cap lc)
                              :detail [(str "operation     " opsym " declares no :produces")
                                       (str "but its body yields " (:cap lc) "@" (:state lc))]})
                    (and p (nil? lc))
                    (report! {:kind :produces-mismatch :op opsym :cap (:cap p)
                              :detail [(str "operation     " opsym " declares :produces "
                                            (:cap p) "@" (:state p))
                                       (str "but its body does not yield a tracked capability")
                                       (str "at            " (pir/site (:pos stb)))]})
                    (and p lc (not (state-ok? (:state p) (:state lc))))
                    (report! {:kind :produces-mismatch :op opsym :cap (:cap p)
                              :detail [(str "operation     " opsym " declares :produces "
                                            (:cap p) "@" (want-str (:state p)))
                                       (str "but its body yields @" (:state lc))]})
                    :else nil)))
              ;; an UNannotated function may not hand a capability to its caller
              (when (and (nil? ann) lc)
                (report! {:kind :escape :cap (:cap lc)
                          :detail [(str "capability    " (:cap lc) "@" (:state lc))
                                   (str "is returned by " opsym
                                        ", which declares no :produces")
                                   (str "at            " (pir/site (:pos stb)))]}))
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
   "  1. An operation in a capability's declared :transitions is an AXIOM. The"
   "     bodies of perturb.nrepl/open, /request and /close! are not checked;"
   "     their annotations are believed. mode_checker.py's RULES have the same"
   "     status, so this is the ported posture, not a new hole — but it is a hole:"
   "     nothing checks that close! actually closes."
   ""
   "  2. :consumes / :produces are UNPOSITIONED. A function returning [conn value]"
   "     cannot say where the capability is, so the checker can only reject it."
   "     For a DERIVED annotated operation the checker matches capability specs to"
   "     parameters in order; that convention is the checker's own and is not in"
   "     §1.2."
   ""
   "  3. Closure bodies are walked for diagnostics but their state does not"
   "     propagate: the checker does not model whether or how often a closure runs."
   "     Capturing a live capability is rejected outright rather than reasoned about."
   ""
   "  4. try/catch has no exception-path join. Any capability discipline across a"
   "     handler is unchecked and the checker says so where it finds one."
   ""
   "  5. Only `let`/`loop` binding forms carry capabilities. A capability stored in"
   "     an atom, a var, a map or a vector is rejected, never tracked."
   ""
   "  6. Interprocedural flow is by ANNOTATION only. There is no inference: an"
   "     unannotated function that takes a connection is rejected, not analysed."
   ""
   "  7. The IR it reads is post-const-fold (perturb.ir), and only for namespaces"
   "     required AFTER the tap is installed."])

(def ^:private line
  "========================================================================")

(defn- verdict [rs] (if (empty? (:diagnostics rs)) :accept :reject))

(defn- kinds [rs] (set (map :kind (:diagnostics rs))))

(defn- run-corpus [sp]
  (let [results (check-namespace! sp "perturb.corpus")
        by-var  (reduce (fn [m r] (assoc m (:var r) r)) {} results)
        exps    (deref (resolve 'perturb.corpus/expectations))]
    (println "== corpus ==============================================================")
    (println "   real perturb source, compiled by Jolt, checked from its own IR")
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
      (let [r (get by-var 'perturb.corpus/use-after-close)]
        (print (render (:diagnostics r))))
      fails)))

(defn- run-client [sp]
  (println)
  (println "== the real nREPL client ===============================================")
  (println "   perturb.nrepl, unmodified, checked by the same rules. This is NOT a")
  (println "   gate: it is the measurement §1.2 and §4.6 say has never been taken.")
  (println)
  (let [results (check-namespace! sp "perturb.nrepl")
        checked (remove (fn [r] (contains? (:primitives sp) (:var r))) results)
        bad     (filter (fn [r] (seq (:diagnostics r))) checked)]
    (doseq [r checked]
      (println (str "  " (if (empty? (:diagnostics r)) "[ok  ] " "[NO  ] ") (:var r)
                    (if (empty? (:diagnostics r)) ""
                        (str "  " (vec (sort (map name (kinds r)))))))))
    (println)
    (doseq [r bad]
      (println (str "  --- " (:var r)))
      (print (render (:diagnostics r))))
    (println (str "  " (count bad) " of " (count checked)
                  " checkable functions in perturb.nrepl are REJECTED."))
    (println (str "  " (count (:primitives sp))
                  " are primitives of the declared machine and were not checked."))
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

(defn -main [& _]
  (println line)
  (println "perturb.check — static capability checking over real Jolt IR")
  (println line)
  (println)
  (pir/capture! ['perturb.nrepl 'perturb.corpus])
  (let [sp (spec)]
    (println (str "  capabilities declared : " (vec (keys (:declarations sp)))))
    (println (str "  operations annotated  : " (vec (sort (map str (keys (:operations sp)))))))
    (println (str "  machine primitives    : " (vec (sort (map str (keys (:primitives sp)))))))
    (println (str "  IR defs captured      : " (count @pir/captured)))
    (println)
    (let [fails (run-corpus sp)]
      (run-client sp)
      (report-local-finding)
      (println)
      (doseq [l (report-limits)] (println (str "  " l)))
      (println)
      (println line)
      (if (empty? fails)
        (do (println "CHECK OK — every corpus verdict is the recorded one") (System/exit 0))
        (do (println (str "CHECK FAILED — " (vec fails))) (System/exit 1))))))

