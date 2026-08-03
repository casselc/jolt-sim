(ns perturb.refine
  "A DECISION PROCEDURE for the side conditions §1.3 reserves for refinements,
  and an abstract domain small enough to say exactly what it decides.

  WHAT THIS IS FOR. PERTURB-DESIGN E18 finding 3: `ResponseBody`'s terminal
  condition is not a state. `:finished` says the writer stopped; the obligation
  is `wrote exactly N`, arithmetic over an integer that is not known until run
  time. §1.2's four axes cannot state it. This namespace is the arithmetic half,
  kept away from `perturb.check` so that the checker's edit is small and so that
  what the arithmetic decides can be read, and run, on its own (`-M:refine`).

  WHAT IT IS NOT — READ THIS BEFORE BELIEVING A `valid`.

  IT IS NOT AN SMT SOLVER, AND IT IS NOT `prototypes/refinement.py`. There is no
  case split, no Fourier-Motzkin, no simplex, and no way to combine two
  hypotheses. It decides a GROUND fragment:

    terms    t ::= k | a | t + t | t - t | -t | k * t        (k, a integers;
                   `a` an opaque atom standing for one run-time integer)
    formulas f ::= (= t t) | (<= t t) | (< t t) | (>= t t) | (> t t)
                 | (and f ...)

  A term normalises to `k + SUM c_i a_i` over the integers, or to TOP. The
  procedure is then:

    (= t u)   VALID   if t - u normalises to the zero form
              REFUTED if t - u normalises to a NON-ZERO CONSTANT
              UNKNOWN otherwise
    (<= t u)  VALID   if u - t is provably non-negative — its constant and every
                      coefficient are >= 0 and every atom it mentions is a
                      LENGTH atom, which cannot be negative
              REFUTED if -1 - (u - t) is provably non-negative
              UNKNOWN otherwise

  So: COMPLETE on the variable-free case (both sides constants — there it is
  plain evaluation over the integers, and calling that a solver would be a
  false claim), SOUND AND INCOMPLETE once an atom survives. `x - 3 = 0` is
  UNKNOWN here and an SMT solver would report it satisfiable-not-valid, which
  is the same answer for this purpose: not discharged.

  ATOMS ARE COMPARED SYNTACTICALLY. An atom is minted per BINDING OCCURRENCE by
  the checker, so `(ocount b)` in two places is one atom exactly when the two
  places name the same binding. Two DIFFERENT expressions that happen to denote
  the same integer are two different atoms and are never identified. That is
  where the incompleteness lives, and it is deliberate: identifying them would
  need the theory this namespace does not implement.

  UNKNOWN IS A REFUSAL, NOT AN ACCEPT. `perturb.check` turns UNKNOWN into a
  diagnostic (`refinement-undischarged`) and REJECTS the program. E15 caught a
  checker whose accept set could not run and E17 warned about the shape; a
  checker that accepted on \"don't know\" would look like a checker and be a
  false accept generator.")

(require '[clojure.string :as str])

;; --- abstract integers ------------------------------------------------------
;;
;; TOP        {:top true :why "..."}   — no information, and why there is none
;; LINEAR     {:k n :t {atom coeff}}   — n + SUM coeff*atom, coefficients non-zero
;;
;; An ATOM is [:val id name] or [:len id name]. `:len` atoms stand for the octet
;; length of a value and are therefore KNOWN NON-NEGATIVE; `:val` atoms stand
;; for an arbitrary integer and are not. Nothing else distinguishes them.

(defn top
  "No information about this integer, and the reason there is none. The reason
  is carried because it is the whole content of a `cannot discharge` message."
  [why] {:top true :why why})

(defn top? [a] (true? (:top a)))

(defn konst [n] {:k n :t {}})

(defn atom-term
  "The term denoting one opaque run-time integer. `kind` is :val or :len."
  [kind id nm] {:k 0 :t {[kind id (str nm)] 1}})

(defn- prune [m]
  (reduce (fn [acc e] (if (zero? (second e)) acc (assoc acc (first e) (second e))))
          {} m))

(defn add [a b]
  (cond
    (top? a) a
    (top? b) b
    :else {:k (+ (:k a) (:k b))
           :t (prune (reduce (fn [acc e]
                               (assoc acc (first e)
                                      (+ (get acc (first e) 0) (second e))))
                             (:t a) (:t b)))}))

(defn neg [a]
  (if (top? a)
    a
    {:k (- (:k a)) :t (reduce (fn [acc e] (assoc acc (first e) (- (second e))))
                              {} (:t a))}))

(defn sub [a b] (add a (neg b)))

(defn scale [k a]
  (cond
    (top? a) a
    (zero? k) (konst 0)
    :else {:k (* k (:k a))
           :t (reduce (fn [acc e] (assoc acc (first e) (* k (second e)))) {} (:t a))}))

(defn zero-term? [a] (and (not (top? a)) (zero? (:k a)) (empty? (:t a))))

(defn const-val
  "The integer this term denotes, or nil if it is not a constant."
  [a] (if (and (not (top? a)) (empty? (:t a))) (:k a) nil))

(defn- nonneg?
  "A SUFFICIENT condition for `a >= 0`: the constant and every coefficient are
  non-negative and every atom is a length. Not necessary — `len(x) - len(x)` is
  zero and is caught by normalisation, but `2*len(x) - len(x)` is not proved
  here because the coefficients are subtracted before this is asked."
  [a]
  (and (not (top? a))
       (>= (:k a) 0)
       (reduce (fn [acc e] (and acc (> (second e) 0) (= :len (first (first e)))))
               true (:t a))))

(defn render
  "A term, as a diagnostic prints it."
  [a]
  (if (top? a)
    "unknown"
    (let [ts (sort-by (fn [e] (nth (first e) 2))
                      (seq (:t a)))
          part (fn [e]
                 (let [c (second e)
                       n (str (if (= :len (first (first e))) "ocount " "")
                              (nth (first e) 2))]
                   (cond (= 1 c) n
                         (= -1 c) (str "-" n)
                         :else (str c "*" n))))
          body (str/join " + " (map part ts))]
      (cond
        (empty? ts) (str (:k a))
        (zero? (:k a)) body
        :else (str body " + " (:k a))))))

;; --- terms and formulas, as the declaration writes them ---------------------

(defn term
  "Evaluate a DECLARED term against a ghost environment. `argf` resolves the two
  forms that reach outside the environment: `(arg n)` is the integer value of
  the operation's argument n, and `(ocount (arg n))` is its octet length.
  Returns an abstract integer, never throws — an unrecognised form is TOP with
  its own reason attached."
  [env argf t]
  (cond
    (integer? t) (konst t)

    (symbol? t)
    (let [v (get env t)]
      (if (nil? v) (top (str "there is no ghost variable `" t "` here")) v))

    (or (list? t) (seq? t))
    (let [h  (first t)
          as (vec (rest t))]
      (cond
        (= 'arg h)
        (if (and (= 1 (count as)) (integer? (first as)))
          (argf :val (first as))
          (top "malformed (arg n)"))

        (= 'ocount h)
        (if (and (= 1 (count as))
                 (or (list? (first as)) (seq? (first as)))
                 (= 'arg (first (first as)))
                 (integer? (second (first as))))
          (argf :len (second (first as)))
          (top "ocount of something that is not an argument"))

        (= '+ h) (reduce (fn [acc x] (add acc (term env argf x))) (konst 0) as)

        (= '- h)
        (if (= 1 (count as))
          (neg (term env argf (first as)))
          (reduce (fn [acc x] (sub acc (term env argf x)))
                  (term env argf (first as)) (rest as)))

        (= '* h)
        (cond
          (and (= 2 (count as)) (integer? (first as)))
          (scale (first as) (term env argf (second as)))
          (and (= 2 (count as)) (integer? (second as)))
          (scale (second as) (term env argf (first as)))
          :else (top "multiplication of two non-constants is outside linear arithmetic"))

        :else (top (str "`" h "` is not in this term language"))))

    :else (top (str (pr-str t) " is not a term"))))

;; :valid / :refuted / :unknown. Nothing else is ever returned.

(defn- worse [a b]
  (cond (or (= :refuted a) (= :refuted b)) :refuted
        (or (= :unknown a) (= :unknown b)) :unknown
        :else :valid))

(defn- dec-eq [d]
  (cond
    (top? d) :unknown
    (zero-term? d) :valid
    (not (nil? (const-val d))) :refuted
    :else :unknown))

(defn- dec-le
  "a <= b."
  [a b]
  (let [d (sub b a)]
    (cond
      (top? d) :unknown
      (nonneg? d) :valid
      (nonneg? (sub (konst -1) d)) :refuted
      :else :unknown)))

(defn decide
  "Decide one declared formula against a ghost environment.

  Returns :valid, :refuted or :unknown. :unknown means THIS PROCEDURE cannot
  decide it — not that the formula is false, and not that it is true. Callers
  must treat it as a refusal; see the namespace docstring."
  [env argf f]
  (if (not (or (list? f) (seq? f)))
    :unknown
    (let [h (first f)
          a (fn [] (term env argf (second f)))
          b (fn [] (term env argf (nth (vec f) 2)))]
      (cond
        (= 'and h)  (reduce (fn [acc g] (worse acc (decide env argf g))) :valid (rest f))
        (= '= h)    (dec-eq (sub (a) (b)))
        (= '<= h)   (dec-le (a) (b))
        (= '< h)    (dec-le (add (a) (konst 1)) (b))
        (= '>= h)   (dec-le (b) (a))
        (= '> h)    (dec-le (add (b) (konst 1)) (a))
        :else       :unknown))))

(defn first-unknown-reason
  "The reason the first TOP in `env` is TOP, for a `cannot discharge` message."
  [env]
  (reduce (fn [acc e]
            (if (and (nil? acc) (top? (second e)))
              (str "`" (first e) "` is " (:why (second e)))
              acc))
          nil (sort-by (fn [e] (str (first e))) (seq env))))

(defn env-lines
  "The ghost environment, one variable per line, for a diagnostic."
  [env]
  (map (fn [e] (str "  " (first e) " = " (render (second e))))
       (sort-by (fn [e] (str (first e))) (seq env))))

;; --- the self-test ----------------------------------------------------------
;;
;; This is the artifact for the claim in the docstring. Every case says which of
;; the three answers it must get, INCLUDING the ones that must be :unknown —
;; those are the boundary, and a procedure that started answering them would be
;; claiming something this one does not.

(def ^:private LEN-A (atom-term :len 1 "a"))
(def ^:private LEN-B (atom-term :len 2 "b"))
(def ^:private VAL-N (atom-term :val 3 "n"))

(defn- no-args [_ _] (top "no arguments in this test"))

(def cases
  "[label env formula expected why]"
  [["6 = 3 + 3, both constants"
    {'declared (konst 6) 'written (konst 6)} '(= written declared) :valid
    "the ground case: evaluation over the integers, complete"]

   ["6 = 3, both constants"
    {'declared (konst 6) 'written (konst 3)} '(= written declared) :refuted
    "a non-zero constant difference is a genuine counterexample"]

   ["ocount(b) = ocount(b), one atom each side"
    {'declared LEN-A 'written LEN-A} '(= written declared) :valid
    "the run-time length case: normalisation, no solver needed"]

   ["0 + ocount(b) = ocount(b)"
    {'declared LEN-A 'written (add (konst 0) LEN-A)} '(= written declared) :valid
    "the shape a body written in one call actually produces"]

   ["ocount(a) = ocount(b), two DIFFERENT atoms"
    {'declared LEN-A 'written LEN-B} '(= written declared) :unknown
    "syntactically different atoms are never identified; REFUSED, not accepted"]

   ["3 = ocount(b)"
    {'declared LEN-A 'written (konst 3)} '(= written declared) :unknown
    "a constant against an atom: might hold at run time, not decided here"]

   ["ocount(a) + ocount(b) = ocount(b) + ocount(a)"
    {'declared (add LEN-A LEN-B) 'written (add LEN-B LEN-A)} '(= written declared) :valid
    "linear normalisation is commutative and associative"]

   ["2*ocount(a) = ocount(a) + ocount(a)"
    {'declared (scale 2 LEN-A) 'written (add LEN-A LEN-A)} '(= written declared) :valid
    "coefficients add"]

   ["written unknown"
    {'declared (konst 6) 'written (top "widened at a loop")} '(= written declared) :unknown
    "TOP propagates and is never mistaken for a decision"]

   ["0 <= ocount(a)"
    {'x LEN-A} '(<= 0 x) :valid
    "a LENGTH atom cannot be negative — the one fact about atoms this has"]

   ["0 <= n, n an arbitrary integer"
    {'x VAL-N} '(<= 0 x) :unknown
    "a :val atom carries no sign, and no hypothesis can be added to give it one"]

   ["ocount(a) <= ocount(a) + 3"
    {'x LEN-A 'y (add LEN-A (konst 3))} '(<= x y) :valid
    "the difference is a non-negative constant"]

   ["ocount(a) + 3 <= ocount(a)"
    {'x (add LEN-A (konst 3)) 'y LEN-A} '(<= x y) :refuted
    "and the reverse is refuted, by the same normalisation"]

   ["3 < 6 and 6 = 6"
    {} '(and (< 3 6) (= 6 6)) :valid
    "conjunction"]

   ["3 < 6 and 6 = 7"
    {} '(and (< 3 6) (= 6 7)) :refuted
    "one refuted conjunct refutes the conjunction"]

   ["3 < 6 and ocount(a) = 3"
    {'x LEN-A} '(and (< 3 6) (= x 3)) :unknown
    "one undecided conjunct leaves the conjunction undecided"]

   ["(* n n) is not linear"
    {'n VAL-N} '(= 0 (* n n)) :unknown
    "non-linear multiplication is refused rather than approximated"]

   ["a formula head this language does not have"
    {} '(divides 2 written) :unknown
    "an unrecognised formula is UNKNOWN, never :valid"]])

(defn -main [& _]
  (println "========================================================================")
  (println "perturb.refine — the ground linear fragment, and its exact boundary")
  (println "========================================================================")
  (println)
  (println "  Not an SMT solver: no case split, no elimination, no hypotheses.")
  (println "  Complete where both sides are constants; sound and INCOMPLETE once an")
  (println "  atom survives. :unknown is a REFUSAL at the checker, never an accept.")
  (println)
  (let [fails
        (reduce
          (fn [acc c]
            (let [got (decide (nth c 1) no-args (nth c 2))
                  ok  (= got (nth c 3))]
              (println (str "  [" (if ok "ok  " "FAIL") "] " (nth c 0)))
              (println (str "         expected " (name (nth c 3)) ", got " (name got)
                            "   — " (nth c 4)))
              (if ok acc (conj acc (nth c 0)))))
          [] cases)]
    (println)
    (println (str "  " (- (count cases) (count fails)) "/" (count cases)
                  " as expected"
                  (if (empty? fails) "" (str "; FAILED " (vec fails)))))
    (println)
    (println "  How many of these are decided by EVALUATION rather than by anything")
    (println "  deserving the word solver: every case whose environment holds only")
    (println "  constants. The atom cases are decided by linear normalisation, which")
    (println "  is the whole of the reasoning here.")
    (println "========================================================================")
    (if (empty? fails)
      (do (println "REFINE OK") (System/exit 0))
      (do (println "REFINE FAILED") (System/exit 1)))))
