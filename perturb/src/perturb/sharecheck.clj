(ns perturb.sharecheck
  "THE TRANSITIVE SHAREABILITY RULE, EXECUTED — its boundary as a table, its
  declaration rules with their controls, and the THREE-WAY negative control the
  survey designed.

  PERTURB-DESIGN E37. `perturb.share` states the rule and decides a profile;
  this namespace runs it, and runs the control that says whether the separation
  of concerns it claims is real.

  IS A GATE. It records expectations and exits non-zero when one changes. That
  is the opposite posture from `-M:tcpcheck` and it is deliberate: tcpcheck
  measures a library nobody here controls, and every artifact below is perturb's
  own, written to a specification stated in advance.

  WHAT IS RUN, IN ORDER.

    1  THE RULE'S BOUNDARY, AS A TABLE. Every case the classifier decides and
       every case it REFUSES, including the two that pin the parts a naive
       implementation gets wrong: hidden mutation must NOT change a verdict, and
       `:mixed` must dominate `:refused` so that one unclassifiable element
       cannot mask a socket sitting beside it.

    2  THE DECLARATION RULES, EACH BROKEN AND RESTORED. A declaration must not
       be able to lie. The load-bearing one is `seal-hides-authority`: a seal may
       hide MUTATION, it may not hide AUTHORITY.

    3  ARM A — the NEGATIVE control. Capability typestate over a corpus of only
       unrestricted persistent values with no capability payload. It must
       produce no nontrivial obligations and no useful rejections. If it emits
       artificial dead-name or duplication errors instead, THAT IS THE FINDING
       and this gate prints it as one.

    4  ARM B — the POSITIVE countercase. The same shapes with a live capability
       inside the persistent container. Duplication, storage and discard must
       remain VISIBLE, not laundered by the immutable shell. Arm B is also what
       makes arm A a measurement rather than an absence: both arms are checked
       by ONE run of ONE checker, so a clean arm A cannot be an instrument that
       is switched off.

    5  ARM C — the STRUCTURAL countercase. An out-of-bounds access and a broken
       persistence length law, decided by `perturb.refine`'s ground linear
       fragment over abstract lengths built from REAL IR — and NOT by capability
       typestate, which must say nothing about any of them. Its own instrument
       control is run beside it.

    6  THE HIGHER-ORDER DISPOSITION, and the one case it decides.

  WHAT THIS IS NOT — READ THIS BEFORE QUOTING A NUMBER FROM IT.

  NOT A PUBLISHED RESULT AND NOT A REPLICATION OF ONE. Both research passes
  searched for a study that ran a substructural or typestate checker over
  ordinary persistent immutable collections and concluded it was useless.
  Neither found one, and both give the same architectural reason: mature mixed
  systems assign such values an unrestricted kind BEFORE typestate analysis, so
  the experiment is never run. Both also list \"nobody has tried this\" among the
  claims to AVOID, because an absence result is bounded by its own search
  protocol. What runs below is an INTERNAL CONTROL on perturb's own corpus. It
  is evidence about these rules on this code, it is not evidence about the
  literature, and it does not become one by being green.

  NOT A STATEMENT ABOUT PERSISTENT COLLECTIONS IN GENERAL. Arm A is ten
  functions. `perturb.sharecorpus`'s nonclaims and item 19 of
  `perturb.check/report-limits` bound what may be read off them."
  (:require [perturb.ir :as pir]
            [perturb.check :as chk]
            [perturb.share :as sh]
            [perturb.refine :as ref]
            [clojure.string :as str]))

;; `perturb.sharecorpus` is NOT required here, and that is not tidiness.
;; `perturb.ir`'s tap only sees a namespace analyzed AFTER `install!`, and a
;; namespace named in this `:require` would be loaded before `-main` runs. So
;; the corpus is loaded by `pir/capture!` and its expectation tables are read
;; through `resolve` — exactly what `perturb.check/run-corpus` does with its
;; own. Getting this wrong is silent: the arms report NOT CAPTURED rather than
;; passing vacuously, which is why they are written to fail closed.

(defn- corpus-table [s] (deref (resolve s)))

(def ^:private line
  "========================================================================")

(defn- banner [title]
  (println)
  (println (str "== " title " "
                (subs "=======================================================================component"
                      0 (max 3 (- 72 (count title) 4))))))

;; ===========================================================================
;; 1  THE RULE'S BOUNDARY, AS A TABLE
;; ===========================================================================

(def ^:private CONN 'perturb.nrepl/Connection)

(def table-interfaces
  "The interface declarations the boundary table is decided against. Written
  here rather than registered globally: the table is about the RULE, and a rule
  read against a registry that other namespaces can write to would be measuring
  the registry."
  {'octet-window
   {:perturb.share/tag 'octet-window
    :perturb.share/aliases-observe-mutation?         false
    :perturb.share/duplication-duplicates-authority? false
    :perturb.share/discard-leaks-obligation?         false
    :perturb.share/trusted-by "perturb.octet's constructors range-check every element"}

   'octet-window-with-a-cached-hash
   {:perturb.share/tag 'octet-window-with-a-cached-hash
    :perturb.share/aliases-observe-mutation?         false
    :perturb.share/duplication-duplicates-authority? false
    :perturb.share/discard-leaks-obligation?         false
    :perturb.share/trusted-by "perturb.octet's constructors range-check every element"
    :perturb.share/internal-mutation
    {:kind :cached-hash
     :why "the hash is a pure function of the octets and is not observable"}}

   'native-buffer
   {:perturb.share/tag 'native-buffer
    :perturb.share/aliases-observe-mutation?         true
    :perturb.share/duplication-duplicates-authority? false
    :perturb.share/discard-leaks-obligation?         true
    :perturb.share/trusted-by "perturb.posix owns the free(3)"}

   'sealed-window
   {:perturb.share/tag 'sealed-window
    :perturb.share/aliases-observe-mutation?         false
    :perturb.share/duplication-duplicates-authority? false
    :perturb.share/discard-leaks-obligation?         false
    :perturb.share/trusted-by "an observationally immutable facade over a mutable core"
    :perturb.share/seals :prim
    :perturb.share/internal-mutation
    {:kind :path-copying
     :why "structural sharing rewrites interior nodes; no public value changes"}}

   'sealed-connection
   {:perturb.share/tag 'sealed-connection
    :perturb.share/aliases-observe-mutation?         false
    :perturb.share/duplication-duplicates-authority? false
    :perturb.share/discard-leaks-obligation?         false
    :perturb.share/trusted-by "a declaration that is about to be refused"
    :perturb.share/seals [:product :prim [:cap CONN]]}})

(def cases
  "[label profile expected why]. The REFUSED rows are the boundary and they are
  as load-bearing as the decided ones: a classifier that started answering them
  would be claiming something this one does not."
  [["a primitive" :prim :shareable
    "the base case"]

   ["a product of primitives" [:product :prim :prim] :shareable
    "every field is unrestricted, so the product is"]

   ["a product with a capability field" [:product :prim [:cap CONN]] :mixed
    "TRANSITIVITY: one field decides the whole product; witness names it"]

   ["a persistent collection of primitives" [:coll :prim] :shareable
    "arm A's shape"]

   ["a persistent collection holding a socket" [:coll [:product :prim [:cap CONN]]] :mixed
    "E37's sentence, executed: the shell is immutable and duplicating it"]

   ["a collection three deep, socket at the bottom"
    [:coll [:coll [:coll [:cap CONN]]]] :mixed
    "reachability is transitive at any depth; the witness path is [0 0 0]"]

   ["a sum of primitives" [:sum :prim :prim] :shareable
    "every alternative is unrestricted"]

   ["a sum with one capability alternative" [:sum :prim [:cap CONN]] :mixed
    "ONE alternative is enough — the value may be that one"]

   ["a closure over a primitive" [:closure :prim] :shareable
    "capturing is not the problem; what is captured is"]

   ["a closure over a persistent vector" [:closure [:coll :prim]] :shareable
    "arm A's `closure-over-a-vector`, as a profile"]

   ["a closure over a capability" [:closure [:cap CONN]] :mixed
    "§4.6's ROOT CAUSE as one line of the rule: the closure is a route"]

   ["a bare capability" [:cap CONN] :mixed
    "authority-bearing, never promoted, and no declaration can promote it"]

   ["an opaque value with no declaration" [:opaque 'jolt.ffi/pointer] :refused
    "PROMOTION DENIED. Refused is never an accept — perturb.refine's posture"]

   ["an opaque value a trusted declaration promotes" [:opaque 'octet-window] :shareable
    "the three clauses are all false and the declaration names who vouches"]

   ["THE QUALIFICATION: the same value, declaring a cached hash"
    [:opaque 'octet-window-with-a-cached-hash] :shareable
    "HIDDEN MUTATION DOES NOT DEFEAT UNRESTRICTED USE. `classify` never reads"]

   ["an opaque value whose discard leaks an obligation" [:opaque 'native-buffer] :mixed
    "one clause true is enough; the clause that is true is named in the why"]

   ["a value sealed behind an observationally immutable interface"
    [:sealed 'sealed-window :prim] :shareable
    "E37's `or safely sealed behind an observationally immutable interface`"]

   ["a seal that claims to hide a capability"
    [:sealed 'sealed-connection [:product :prim [:cap CONN]]] :refused
    "A SEAL MAY HIDE MUTATION, IT MAY NOT HIDE AUTHORITY — refused where"]

   ["DOMINANCE: one refused element beside a shareable one"
    [:coll [:opaque 'jolt.ffi/pointer]] :refused
    "refused beats shareable, so an undecided element is not promoted"]

   ["DOMINANCE: one refused field beside a CAPABILITY field"
    [:product [:opaque 'jolt.ffi/pointer] [:cap CONN]] :mixed
    "MIXED BEATS REFUSED. Getting this backwards lets an unclassifiable"]

   ["a profile head this rule does not have" [:widget :prim] :refused
    "an unreadable profile is REFUSED, never :shareable"]])

(defn- run-table []
  (banner "1  THE RULE'S BOUNDARY — every case decided, every case REFUSED")
  (println)
  (println "   :shareable  contraction and weakening are sound at this interface")
  (println "   :mixed      they are not — authority-bearing, stays capability-tier")
  (println "   :refused    this procedure will not say. NEVER an accept.")
  (println)
  (reduce
    (fn [acc c]
      (let [prof (nth c 1)
            d    (sh/classify table-interfaces prof)
            ok   (= (:class d) (nth c 2))]
        (println (str "  [" (if ok "ok  " "FAIL") "] " (nth c 0)))
        (println (str "         " (sh/render-profile prof)))
        (println (str "         expected " (name (nth c 2)) ", got " (name (:class d))
                      (if (empty? (:witness d)) "" (str "   witness " (pr-str (:witness d))))))
        (doseq [l (:why d)] (println (str "           " l)))
        (if ok acc (conj acc (nth c 0)))))
    [] cases))

;; ===========================================================================
;; 2  THE DECLARATION RULES — a declaration must not be able to lie
;; ===========================================================================

(def declaration-controls
  "[label declaration expected-fault-kinds why]. Each broken row is followed by
  its REPAIRED twin, so every rule is shown firing and then shown quiet against
  the smallest possible edit."
  (let [good {:perturb.share/tag 'w
              :perturb.share/aliases-observe-mutation?         false
              :perturb.share/duplication-duplicates-authority? false
              :perturb.share/discard-leaks-obligation?         false
              :perturb.share/trusted-by "the constructors range-check"}]
    [["BASELINE — a well-formed promoting interface" good #{}
      "all three clauses false, and it names who vouches"]

     ["BROKEN — a seal over a payload that reaches a capability"
      (assoc good :perturb.share/seals [:product :prim [:cap CONN]])
      #{:seal-hides-authority}
      "no observational claim makes a second route to a socket stop being one"]
     ["RESTORED — the same seal over a payload that reaches none"
      (assoc good :perturb.share/seals [:product :prim :prim]) #{}
      "the seal is fine; what was refused was what it sealed"]

     ["BROKEN — internal mutation of a kind outside the closed set"
      (assoc good :perturb.share/internal-mutation
             {:kind :transient-builder :why "it is only used during construction"})
      #{:mutation-kind-unrecognised}
      "a transient is an AFFINE builder with a freeze boundary, not a hidden"]
     ["RESTORED — a kind that is in it"
      (assoc good :perturb.share/internal-mutation
             {:kind :path-copying :why "interior nodes are rewritten; no public value changes"})
      #{}
      "path copying cannot change public value semantics"]

     ["BROKEN — a recognised kind with no justification"
      (assoc good :perturb.share/internal-mutation {:kind :cached-hash})
      #{:mutation-unjustified}
      "the kind is in the set; why it is vacuous AT THIS INTERFACE is not"]
     ["RESTORED — the same kind, with one"
      (assoc good :perturb.share/internal-mutation
             {:kind :cached-hash :why "a pure function of the elements"})
      #{}
      "one sentence, and it is the sentence a reader needs"]

     ["BROKEN — a promotion nobody signs"
      (dissoc good :perturb.share/trusted-by) #{:untrusted-promotion}
      "promoting to unrestricted is a trust statement and must name its author"]
     ["RESTORED — signed" good #{}
      "the baseline again, reached by adding one key back"]]))

(defn- run-declaration-controls []
  (banner "2  THE DECLARATION RULES — each broken deliberately, then restored")
  (println)
  (println "   The posture is `perturb.check/check-cancellation-declarations!`'s:")
  (println "   a declaration that CLAIMS something impossible is refused WHERE IT")
  (println "   IS WRITTEN, because every program holding the value would")
  (println "   otherwise be accepted on a false premise.")
  (println)
  (reduce
    (fn [acc c]
      (let [fs   (sh/interface-faults (nth c 1))
            got  (set (map :kind fs))
            ok   (= got (nth c 2))]
        (println (str "  [" (if ok "ok  " "FAIL") "] " (nth c 0)))
        (println (str "         expected " (pr-str (vec (sort-by str (nth c 2))))
                      ", got " (pr-str (vec (sort-by str got)))))
        (println (str "         " (nth c 3)))
        (doseq [f fs] (doseq [l (:detail f)] (println (str "           " l))))
        (if ok acc (conj acc (nth c 0)))))
    [] declaration-controls))

;; ===========================================================================
;; 3 & 4  ARMS A AND B — ONE CHECKER RUN, TWO OPPOSITE REQUIREMENTS
;; ===========================================================================

(defn- kinds-of [r] (vec (sort (distinct (map (fn [d] (name (:kind d))) (:diagnostics r))))))

(defn- by-var [results]
  (reduce (fn [m r] (assoc m (:var r) r)) {} results))

(defn- run-arm-a [idx]
  (banner "3  ARM A — the NEGATIVE control: unrestricted values, no payload")
  (println)
  (println "   Capability typestate over ten functions that build, index,")
  (println "   duplicate, discard, nest, loop over and close over persistent")
  (println "   values with NO capability anywhere in them. The requirement is")
  (println "   ZERO diagnostics of ANY kind — no nontrivial obligation and no")
  (println "   useful rejection. An artificial dead-name or duplication error")
  (println "   here IS THE FINDING and is printed as one.")
  (println)
  (let [fails (reduce
                (fn [acc v]
                  (let [r (get idx v)]
                    (cond
                      (nil? r)
                      (do (println (str "  [FAIL] " v "  — NOT CAPTURED"))
                          (conj acc v))
                      (empty? (:diagnostics r))
                      (do (println (str "  [ok  ] " v "  — silent")) acc)
                      :else
                      (do (println (str "  [FAIL] " v "  " (kinds-of r)))
                          (print (chk/render (:diagnostics r)))
                          (conj acc v)))))
                [] (corpus-table (quote perturb.sharecorpus/arm-a)))]
    (println)
    (if (empty? fails)
      (do (println "   ARM A CLEAN. On this corpus the capability tier produces nothing")
          (println "   over unrestricted persistent values — which is what the rule says")
          (println "   it should, and is only a measurement because arm B below is")
          (println "   rejected by THE SAME RUN OF THE SAME CHECKER."))
      (do (println "   ARM A IS NOT CLEAN, AND THAT IS THE RESULT, NOT A BUG TO HIDE.")
          (println "   The capability tier emitted obligations over values that have no")
          (println "   authority to duplicate. Report the diagnostics above verbatim.")))
    fails))

(defn- run-arm-b [idx]
  (banner "4  ARM B — the POSITIVE countercase: a capability inside the shell")
  (println)
  (println "   The same shapes, with `perturb.nrepl/open` where arm A has a")
  (println "   literal. Each must stay VISIBLE. None of these is ever called.")
  (println)
  (let [fails (reduce
                (fn [acc e]
                  (let [r  (get idx (:var e))
                        ks (if r (set (map :kind (:diagnostics r))) #{})
                        ;; A `capture` row also records WHICH WAY the
                        ;; higher-order question went, because that is the one
                        ;; part of this the rule decides rather than classifies
                        ;; — and it is decided from the capability's own
                        ;; declared contention, so it is broken by editing a
                        ;; declaration and not by editing the rule.
                        d  (:disposition
                             (first (filter (fn [x] (= (:kind e) (:kind x)))
                                            (if r (:diagnostics r) []))))
                        ok (and (contains? ks (:kind e))
                                (or (not (contains? e :decided?))
                                    (and (= (:decided? e) (:decided? d))
                                         (= (:ground e) (:ground d)))))]
                    (println (str "  [" (if ok "ok  " "FAIL") "] " (:var e)
                                  "  expected " (:kind e)
                                  ", got " (if r (kinds-of r) "NOT CAPTURED")))
                    (when (contains? e :decided?)
                      (println (str "         disposition: expected decided?="
                                    (:decided? e) " ground=" (:ground e)
                                    "  got decided?=" (:decided? d)
                                    " ground=" (:ground d))))
                    (println (str "         would have been laundered: " (:laundered e)))
                    (if ok acc (conj acc (:var e)))))
                [] (corpus-table (quote perturb.sharecorpus/arm-b)))]
    (println)
    (println "   THE DIAGNOSTICS THEMSELVES — the transitive rule is what writes")
    (println "   the reason lines, and the witness path is what says WHICH")
    (println "   component of the immutable shell decided it:")
    (println)
    (doseq [e (corpus-table (quote perturb.sharecorpus/arm-b))]
      (let [r (get idx (:var e))]
        (when (and r (seq (:diagnostics r)))
          (println (str "  === " (:var e)))
          (print (chk/render (:diagnostics r))))))
    fails))

;; ===========================================================================
;; 5  ARM C — STRUCTURAL: abstract lengths from REAL IR, decided by refine
;; ===========================================================================
;;
;; A deliberately small analysis, and small in a way that is stated rather than
;; discovered: it reads `let` bindings and `perturb.octet`'s length algebra out
;; of the IR the back end was handed, and hands the result to
;; `perturb.refine`'s ground linear fragment. Everything else is an atom.
;;
;; This is NOT an index checker and must not be described as one. It decides the
;; obligations `perturb.sharecorpus/arm-c` declares, over the seven functions
;; that namespace holds, and its verdicts are `:valid` / `:refuted` / `:unknown`
;; with `:unknown` a REFUSAL. What it is here to establish is one thing only:
;; that the defects arm C holds are caught by STRUCTURAL reasoning and are
;; invisible to capability typestate, which is the third leg of the control.

(def ^:private atom-counter (atom 0))
(defn- fresh [] (swap! atom-counter inc))

(defn- ivar [nm] (symbol (str nm)))
(defn- lvar [nm] (symbol (str nm "-count")))

(defn- sym-of [node]
  (if (and (= :invoke (:op node)) (= :var (:op (:fn node))))
    (symbol (:ns (:fn node)) (:name (:fn node)))
    nil))

(defn- argn [node i]
  (let [as (:args node)] (if (and as (< i (count as))) (nth as i) nil)))

(defn- literal-count
  "The element count of a vector written as a literal, or nil. Both shapes are
  accepted because const-folding may have turned the `:vector` node into a
  `:const` before `perturb.ir`'s tap saw it (perturb.ir's own caveat)."
  [node]
  (cond
    (nil? node) nil
    (= :vector (:op node)) (count (:items node))
    (and (= :const (:op node)) (vector? (:val node))) (count (:val node))
    :else nil))

(declare aint)

(defn- alen
  "The abstract OCTET LENGTH of the value an IR node produces."
  [env node]
  (let [s (sym-of node)]
    (cond
      (nil? node) (ref/top "an expression that is not there")

      (= :local (:op node))
      (or (get env (lvar (:name node)))
          (ref/top (str "the length of `" (:name node) "`, which is not bound here")))

      (= 'perturb.octet/octets s)
      (let [c (literal-count (argn node 0))]
        (if c (ref/konst c) (ref/top "octets over something that is not a literal vector")))

      (= 'perturb.octet/oconcat s)
      (ref/add (alen env (argn node 0)) (alen env (argn node 1)))

      (= 'perturb.octet/osub s)
      (ref/sub (aint env (argn node 2)) (aint env (argn node 1)))

      (= 'perturb.octet/odrop s)
      (ref/sub (alen env (argn node 0)) (aint env (argn node 1)))

      :else (ref/top "a length this pass does not evaluate"))))

(defn- aint
  "The abstract INTEGER an IR node produces."
  [env node]
  (let [s (sym-of node)]
    (cond
      (nil? node) (ref/top "an expression that is not there")
      (and (= :const (:op node)) (integer? (:val node))) (ref/konst (:val node))
      (= :local (:op node))
      (or (get env (ivar (:name node)))
          (ref/top (str "`" (:name node) "`, which is not bound here")))
      (= 'perturb.octet/ocount s) (alen env (argn node 0))
      (= 'clojure.core/+ s)
      (reduce (fn [acc n] (ref/add acc (aint env n))) (ref/konst 0) (:args node))
      (= 'clojure.core/- s)
      (if (= 1 (count (:args node)))
        (ref/neg (aint env (argn node 0)))
        (reduce (fn [acc n] (ref/sub acc (aint env n)))
                (aint env (argn node 0)) (rest (:args node))))
      (= 'clojure.core/inc s) (ref/add (aint env (argn node 0)) (ref/konst 1))
      (= 'clojure.core/dec s) (ref/sub (aint env (argn node 0)) (ref/konst 1))
      :else (ref/top "an integer this pass does not evaluate"))))

(defn- bind-name
  "Bind one name to BOTH its abstract integer and its abstract octet length. A
  name whose initialiser this pass cannot evaluate still denotes ONE value, so a
  fresh atom is minted rather than TOP — the same reasoning
  `perturb.check/bound-ints` uses, and sound for the same reason: a binding is
  immutable."
  [env nm init]
  (let [i (aint env init)
        l (alen env init)]
    (assoc (assoc env (ivar nm) (if (ref/top? i) (ref/atom-term :val (fresh) nm) i))
           (lvar nm) (if (ref/top? l) (ref/atom-term :len (fresh) nm) l))))

(defn- walk-env
  "Collect the abstract environment of one function body. `let` only: this pass
  has no join and no fixpoint, so an `if` or a `loop` contributes nothing and
  says so by contributing nothing."
  [env node]
  (cond
    (nil? node) env
    (= :let (:op node))
    (walk-env (reduce (fn [e b] (bind-name e (nth b 0) (nth b 1))) env (:bindings node))
              (:body node))
    (= :do (:op node))
    (walk-env (reduce walk-env env (:statements node)) (:ret node))
    :else env))

(defn- env-of
  "The abstract environment of a captured `:def`, with its parameters bound to
  fresh :val atoms — an argument is an arbitrary integer with no sign and no
  bound, which is why `read-at-a-runtime-index` is UNKNOWN."
  [node]
  (let [init (:init node)]
    (if (or (nil? init) (not= :fn (:op init)))
      {}
      (let [ar (first (:arities init))
            e0 (reduce (fn [e p]
                         (assoc (assoc e (ivar p) (ref/atom-term :val (fresh) p))
                                (lvar p) (ref/atom-term :len (fresh) p)))
                       {} (:params ar))]
        (walk-env e0 (:body ar))))))

(defn- no-args [_ _] (ref/top "this pass has no call-site arguments"))

(defn- run-arm-c [idx]
  (banner "5  ARM C — the STRUCTURAL countercase")
  (println)
  (println "   Two requirements, and both halves are the control:")
  (println "     (i)  the defects ARE caught, by abstract lengths read off real")
  (println "          IR and decided by perturb.refine's ground linear fragment;")
  (println "     (ii) capability typestate says NOTHING about any of them.")
  (println)
  (let [struct-fails
        (reduce
          (fn [acc e]
            (let [node (get @pir/captured (str (:var e)))
                  env  (if node (env-of node) {})
                  got  (ref/decide env no-args (:obligation e))
                  ok   (= got (:expect e))]
              (println (str "  [" (if ok "ok  " "FAIL") "] " (:var e)))
              (println (str "         " (pr-str (:obligation e))))
              (println (str "         expected " (name (:expect e))
                            ", got " (name got) "   — " (:why e)))
              (doseq [l (ref/env-lines env)] (println (str "       " l)))
              (if ok acc (conj acc (:var e)))))
          [] (corpus-table (quote perturb.sharecorpus/arm-c)))
        cap-fails
        (do
          (println)
          (println "   (ii) THE SAME SEVEN FUNCTIONS, THROUGH CAPABILITY TYPESTATE:")
          (println)
          (reduce (fn [acc e]
                    (let [r (get idx (:var e))]
                      (if (and r (empty? (:diagnostics r)))
                        (do (println (str "  [ok  ] " (:var e) "  — silent")) acc)
                        (do (println (str "  [FAIL] " (:var e) "  "
                                          (if r (kinds-of r) "NOT CAPTURED")))
                            (when r (print (chk/render (:diagnostics r))))
                            (conj acc (:var e))))))
                  [] (corpus-table (quote perturb.sharecorpus/arm-c))))]
    (println)
    (println "   THE INSTRUMENT CONTROL. The same obligations, decided against an")
    (println "   EMPTY environment — no IR read at all. Every one must go to")
    (println "   :unknown, so a decided verdict above is attributable to the IR")
    (println "   and not to the formula being trivially true:")
    (println)
    (let [ctl (reduce
                (fn [acc e]
                  (let [got (ref/decide {} no-args (:obligation e))]
                    (println (str "     " (if (= :unknown got) "[ok  ]" "[FAIL]")
                                  " " (:var e) " -> " (name got)))
                    (if (= :unknown got) acc (conj acc (:var e)))))
                [] (corpus-table (quote perturb.sharecorpus/arm-c)))]
      (println)
      (println "   AND THE OTHER DIRECTION: `out-of-bounds-read` and")
      (println "   `broken-length-claim` are the deliberately broken twins of")
      (println "   `in-bounds-read` and `concat-length-law`. Both pairs differ by")
      (println "   one literal, and the pass separates them.")
      (vec (concat struct-fails cap-fails ctl)))))

;; ===========================================================================
;; 6  THE HIGHER-ORDER DISPOSITION
;; ===========================================================================

(def disposition-controls
  "[label input expected-decided? expected-ground why]"
  [["a closure handed to `future`, capability thread-confined"
    {:consumer 'clojure.core/future :contention :thread-confined :cap CONN}
    true :concurrency-introducer
    "DECIDED on the declaration: the route crosses a thread boundary"]

   ["BROKEN CONTROL — the same closure, contention no longer thread-confined"
    {:consumer 'clojure.core/future :contention :shared :cap CONN}
    false :contention-undeclared
    "the one decided case is decided BY the declaration, so removing the"]

   ["RESTORED — thread-confined again"
    {:consumer 'clojure.core/future-call :contention :thread-confined :cap CONN}
    true :concurrency-introducer
    "and `future` expands through `future-call`, which is why both are named"]

   ["a closure handed to an ordinary unannotated callee"
    {:consumer 'teensyp.client-test/thrown-by :contention :thread-confined :cap CONN}
    false :higher-order-undeclared
    "REFUSED: whether the callee retains the closure is written down nowhere"]

   ["a closure in no argument position at all"
    {:consumer nil :contention :thread-confined :cap CONN}
    false :consumer-unknown
    "there is no consumer to ask about"]])

(defn- run-dispositions []
  (banner "6  THE HIGHER-ORDER DISPOSITION — the one case it decides")
  (println)
  (println "   A closure over a capability is MIXED. That is a CLASSIFICATION,")
  (println "   and the rejection follows from it either way. What this decides is")
  (println "   the separate question of whether one particular capture is a")
  (println "   VIOLATION — and it decides exactly one shape, from a declaration")
  (println "   perturb already has. Every other consumer is a REFUSAL with the")
  (println "   missing notation named. §4.6: higher-order capability passing has")
  (println "   no notation at all.")
  (println)
  (reduce
    (fn [acc c]
      (let [d  (sh/capture-disposition (nth c 1))
            ok (and (= (:decided? d) (nth c 2)) (= (:ground d) (nth c 3)))]
        (println (str "  [" (if ok "ok  " "FAIL") "] " (nth c 0)))
        (println (str "         expected decided?=" (nth c 2) " ground=" (nth c 3)))
        (println (str "         got      decided?=" (:decided? d) " ground=" (:ground d)))
        (doseq [l (:why d)] (println (str "           " l)))
        (when (:needs d) (println (str "           NEEDS: " (:needs d))))
        (if ok acc (conj acc (nth c 0)))))
    [] disposition-controls))

;; ===========================================================================

(defn -main [& _]
  (println line)
  (println "perturb.sharecheck — the TRANSITIVE SHAREABILITY RULE, executed")
  (println line)
  (println)
  (println "  E37: the classifier for a freely duplicable value is an INTERFACE")
  (println "  property, not an allocation property, and its transitivity clause")
  (println "  is §4.6's ROOT CAUSE rather than a second tier. A persistent vector")
  (println "  can hold a live socket; a closure can capture one; the shell is")
  (println "  immutable and duplicating it duplicates ROUTES TO THE CAPABILITY.")
  (println)
  (pir/capture! ['perturb.sharecorpus])
  (let [sp   (chk/spec)
        res  (chk/check-namespace! sp "perturb.sharecorpus")
        idx  (by-var res)]
    (println (str "  IR defs captured      : " (count @pir/captured)))
    (println (str "  sharecorpus defs      : " (count res)))
    (println (str "  capabilities declared : " (vec (keys (:declarations sp)))))
    (println (str "  interfaces declared   : " (vec (sort-by str (keys (sh/interfaces))))
                  "   (empty: nothing in perturb declares one yet)"))
    (let [f1 (run-table)
          f2 (run-declaration-controls)
          f3 (run-arm-a idx)
          f4 (run-arm-b idx)
          f5 (run-arm-c idx)
          f6 (run-dispositions)]
      (banner "WHAT MAY BE READ OFF THIS, AND WHAT MAY NOT")
      (println)
      (println "  1. NOT A PUBLISHED RESULT. Both research passes searched for a")
      (println "     study running a substructural or typestate checker over")
      (println "     ordinary persistent immutable collections and found none, and")
      (println "     both say the reason is architectural: mature mixed systems")
      (println "     assign such values an unrestricted kind BEFORE typestate")
      (println "     analysis, so the experiment is never run. Both list \"nobody")
      (println "     has tried this\" among the claims to AVOID. This is an")
      (println "     INTERNAL CONTROL on perturb's own corpus and nothing else.")
      (println)
      (println "  2. ARM A IS TEN FUNCTIONS. It is not a statement about")
      (println "     persistent collections in general, about Jolt's collections,")
      (println "     or about any checker but this one.")
      (println)
      (println "  3. THE RULE MOVED NO VERDICT ON perturb's OWN CORPORA, AND THAT")
      (println "     IS THE INTENDED OUTCOME. Every profile the checker can build")
      (println "     at a capture or a composite already has a capability leaf in")
      (println "     it — that is why the site raised a diagnostic. The rule buys")
      (println "     a DERIVED reason and a witness path. A rule that had moved a")
      (println "     verdict here would have moved it by weakening the check.")
      (println)
      (println "  4. ARM C's PASS IS NOT AN INDEX CHECKER. It reads `let` bindings")
      (println "     and perturb.octet's length algebra, has no join, no fixpoint")
      (println "     and no hypotheses, and refuses everything else. Its `:unknown`")
      (println "     is a REFUSAL, exactly as perturb.refine's is.")
      (println)
      (println "  5. `perturb.share` DECLARES, IT DOES NOT VERIFY. An interface's")
      (println "     three clauses are hand-written axioms about a subject this")
      (println "     procedure never looks at. What is CHECKED is that a")
      (println "     declaration does not contradict itself — section 2 — and in")
      (println "     particular that no seal claims to hide authority.")
      (println)
      (println line)
      (let [all (concat f1 f2 f3 f4 f5 f6)]
        (if (empty? all)
          (do (println "SHARE OK — the rule's boundary, every declaration control, all")
              (println "           three control arms and the disposition table are the")
              (println "           recorded ones")
              (System/exit 0))
          (do (println (str "SHARE FAILED — " (vec all)))
              (System/exit 1)))))))
