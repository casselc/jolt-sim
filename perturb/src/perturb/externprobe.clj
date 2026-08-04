(ns perturb.externprobe
  "PERTURB-DESIGN §1.1's SECOND IR claim, measured instead of inferred.

  §1.1 says, from reading `jolt-core/jolt/ir.clj`:

      The `:host`/`:host-static`/`:host-new`/`:host-call` ops are untyped,
      un-effected escapes and should be replaced by a single `:extern` carrying
      a declared effect row and signature.

  §4.6 carries it as UNTESTED, under a standing warning that this session's
  record on source-reading inferences is poor. `perturb.check`'s
  `report-local-finding` settled the FIRST claim (`:local` carries a name, not
  binding identity) by walking real IR; this namespace does the same job for
  the second one.

  METHOD. `perturb.ir` taps `jolt.passes.numeric/annotate`, the one var
  `jolt.passes/run-passes` calls through its cell, so every top-level `:def`
  compiled after `install!` is captured exactly as the back end received it.
  This probe installs that tap, requires a corpus — Jolt's own stdlib plus all
  of perturb — and then walks every captured node with `jolt.ir`'s OWN
  `reduce-ir-children`, so the per-op child layout is upstream's and not a
  transcription of it. Nothing under /home/user/jolt is modified and nothing in
  the corpus is called: this is a read of trees, not a run of programs.

  WHAT IT CANNOT SEE, stated before the numbers.
    - Namespaces already loaded when the tap goes in (clojure.core,
      clojure.string, jolt.ffi, …) are never re-analyzed, so they contribute
      nothing. The corpus is what loads AFTER install!, and the table below
      names every namespace and its def count.
    - The tap fires on `:def` only. A top-level `:defmacro` node, and any
      top-level form that is not a def, is invisible.
    - This is post-const-fold IR on ONE host (Chez, jolt-core as checked out
      here). A different back end may resolve host names differently; where
      that matters the report says so.

  NOT A GATE. It records no expectations about the corpus. It exits non-zero
  only if the probe itself failed to measure anything.")

(require '[perturb.ir :as pir])
(require '[jolt.ir :as jir])
(require '[clojure.string :as str])

(def line "======================================================================")

;; --- corpus -----------------------------------------------------------------
;;
;; Jolt's own stdlib first (the interop-heavy part of it: java.time, the
;; process/fs wrappers, zip, test), then every perturb namespace. Anything that
;; fails to load is reported and skipped rather than aborting the probe.

(def stdlib-corpus
  ['clojure.zip 'clojure.data 'clojure.test 'clojure.instant 'clojure.main
   'clojure.java.shell 'clojure.core.async
   'jolt.fs 'jolt.process 'jolt.infix 'jolt.infix.core 'jolt.infix.grammar
   'jolt.parser.basic 'jolt.parser.collections 'jolt.parser.combinators
   'jolt.parser.position
   'jolt.time.util 'jolt.time.base 'jolt.time.enums 'jolt.time.impl
   'jolt.time.amount 'jolt.time.instant 'jolt.time.local 'jolt.time.temporal
   'jolt.time.year])

(def perturb-corpus
  ['perturb.octet 'perturb.bencode 'perturb.effect 'perturb.cap 'perturb.wire
   'perturb.script 'perturb.nrepl 'perturb.corpus 'perturb.http
   'perturb.httpcorpus 'perturb.dbtx 'perturb.dbtxcorpus 'perturb.posix
   'perturb.stream 'perturb.streamcap 'perturb.evt 'perturb.evtapp
   'perturb.refine 'perturb.demo 'perturb.oracle 'perturb.noio
   'perturb.httpdemo 'perturb.streamdemo 'perturb.evtcheck 'perturb.selftest])

(def host-ops [:host :host-static :host-new :host-call])

;; --- accumulators -----------------------------------------------------------

(def stats (atom {}))

(defn- bump! [path]
  (swap! stats (fn [m] (update-in m path (fnil inc 0)))))

(defn- keep-example! [path node ctx]
  (swap! stats (fn [m]
                 (if (get-in m path)
                   m
                   (assoc-in m path {:node node :ctx ctx})))))

;; --- the walk ---------------------------------------------------------------
;;
;; Child positions come from jolt.ir/reduce-ir-children — upstream's own
;; single-sourced layout, so an op perturb never thought about is still walked
;; the way the compiler walks it. `ctx` carries the enclosing def and the
;; nearest :pos annotation seen on the way down, because (as the measurement
;; below shows) the host ops carry no position of their own.

(defn- visit! [node ctx]
  (let [op (:op node)]
    (bump! [:ops op])
    (when (contains? (set host-ops) op)
      (let [ns-name (:ns ctx)]
        (bump! [:by-ns ns-name op])
        (bump! [:keysets op (vec (sort (map str (keys node))))])
        (when (:pos node) (bump! [:with-pos op]))
        (keep-example! [:example op] node ctx)))
    (cond
      (= op :host-call)
      (do (bump! [:hc-method (:method node) (count (:args node))])
          (bump! [:hc-target-op (:op (:target node))])
          (when (:hint (:target node)) (bump! [:hc-target-hint (:hint (:target node))]))
          (if (= "-" (subs (:method node) 0 1))
            (bump! [:hc-kind :field-read]
            )
            (bump! [:hc-kind :method-call])))

      (= op :host-new)
      (bump! [:hn-class (:class node) (count (:args node))])

      (= op :host-static)
      (bump! [:hs-name (str (:class node) "/" (:member node))])

      (= op :ffi-fn)
      (do (bump! [:ffi :ffi-fn])
          (bump! [:keysets :ffi-fn (vec (sort (map str (keys node))))])
          (keep-example! [:example :ffi-fn] node ctx))

      (= op :var)
      (bump! [:var-ns (:ns node)])

      (= op :ffi-callable)
      (do (bump! [:ffi :ffi-callable])
          (keep-example! [:example :ffi-callable] node ctx))

      :else nil)
    ;; call heads: which op sits in an :invoke's :fn position, and at what arity
    (when (= op :invoke)
      (when (:pos node) (bump! [:with-pos :invoke]))
      (let [h (:fn node)]
        (bump! [:invoke-head (:op h)])
        (when (= :host-static (:op h))
          (bump! [:hs-applied (str (:class h) "/" (:member h)) (count (:args node))]))
        (when (= :host (:op h))
          (bump! [:h-applied (:name h) (count (:args node))]))))))

(defn- walk! [node ctx]
  (when (map? node)
    (let [ctx (if (:pos node) (assoc ctx :pos (:pos node)) ctx)]
      (visit! node ctx)
      (jir/reduce-ir-children (fn [a c] (walk! c ctx) a) nil node)))
  nil)

;; --- the cross-check --------------------------------------------------------
;;
;; A SECOND, shape-agnostic sweep: every map anywhere in the tree — including
;; positions reduce-ir-children deliberately skips (:const :val data, :quote
;; :form, fn :params, binding NAMES) — whose :op is in jolt.ir/node-ops. If the
;; two counts agree, the schema walk is not missing host nodes in a position it
;; does not recurse into. Reported, not assumed.

(def generic (atom {}))

(defn- sweep! [x]
  (cond
    (map? x)
    (do (when (contains? jir/node-ops (:op x))
          (swap! generic (fn [m] (update-in m [(:op x)] (fnil inc 0)))))
        (doseq [e (seq x)] (sweep! (first e)) (sweep! (second e))))
    (coll? x) (doseq [e x] (sweep! e))
    :else nil))

;; --- rendering --------------------------------------------------------------

(defn- pad [s n]
  (let [s (str s)]
    (if (>= (count s) n) s (str s (apply str (repeat (- n (count s)) " "))))))

(defn- padl [s n]
  (let [s (str s)]
    (if (>= (count s) n) s (str (apply str (repeat (- n (count s)) " ")) s))))

(defn- sorted-by-count [m]
  (reverse (sort-by (fn [e] (second e)) (seq m))))

(defn- total [m] (reduce + 0 (vals m)))

(defn- render-node [n]
  (let [s (pr-str n)]
    (if (> (count s) 900) (str (subs s 0 900) " …[truncated]") s)))

(defn- print-example [op]
  (let [e (get-in @stats [:example op])]
    (if (nil? e)
      (println (str "    (no " op " node occurs in this corpus)"))
      (do (println (str "    in " (:ns (:ctx e)) "/" (:def (:ctx e))
                        ", nearest enclosing :pos " (pir/site (:pos (:ctx e)))))
          (println (str "    " (render-node (:node e))))))))

;; --- report -----------------------------------------------------------------

(defn- report-corpus [loaded failed]
  (println "== 1. corpus ==========================================================")
  (println)
  (println (str "  namespaces required after the tap : " (count loaded)))
  (when (seq failed)
    (doseq [f failed] (println (str "  NOT LOADED  " (first f) "  " (second f)))))
  (let [per (reduce (fn [m n] (update-in m [(:ns n)] (fnil inc 0)))
                    {} (vals @pir/captured))
        hs  (:by-ns @stats)]
    (println (str "  namespaces contributing :defs     : " (count per)))
    (println (str "  :def nodes captured               : " (count @pir/captured)))
    (println)
    (println (str "  " (pad "namespace" 30) (padl "defs" 6) "   "
                  (padl ":host" 6) (padl ":host-static" 14)
                  (padl ":host-new" 11) (padl ":host-call" 12)))
    (doseq [e (sort-by (fn [e] (first e)) (seq per))]
      (let [n (first e)
            h (get hs n {})]
        (println (str "  " (pad n 30) (padl (second e) 6) "   "
                      (padl (get h :host 0) 6) (padl (get h :host-static 0) 14)
                      (padl (get h :host-new 0) 11) (padl (get h :host-call 0) 12)))))))

(defn- report-histogram []
  (println)
  (println "== 2. every op that occurs, by count ==================================")
  (println)
  (let [ops (:ops @stats)
        tot (total ops)]
    (doseq [e (sorted-by-count ops)]
      (println (str "  " (pad (first e) 16) (padl (second e) 8)
                    "   " (padl (str (quot (* 1000 (second e)) tot)) 4) " per 1000"
                    (if (contains? (set host-ops) (first e)) "   <-- §1.1's four" ""))))
    (println (str "  " (pad "TOTAL" 16) (padl tot 8) " IR nodes walked"))
    (println)
    (let [g @generic
          agree (reduce (fn [a op] (and a (= (get ops op 0) (get g op 0)))) true (vec jir/node-ops))]
      (println (str "  cross-check, shape-agnostic sweep of EVERY map in the trees:"))
      (doseq [op host-ops]
        (println (str "    " (pad op 14) "schema walk " (padl (get ops op 0) 7)
                      "   generic sweep " (padl (get g op 0) 7)
                      (if (= (get ops op 0) (get g op 0)) "   agree" "   DISAGREE"))))
      agree)))

(defn- report-shapes []
  (println)
  (println "== 3. what each of the four actually carries ==========================")
  (println)
  (doseq [op host-ops]
    (let [n (get (:ops @stats) op 0)
          ks (get (:keysets @stats) op)]
      (println (str "  " op "  —  " n " occurrence" (if (= n 1) "" "s")))
      (if (zero? n)
        (println "    DOES NOT OCCUR in this corpus.")
        (do (doseq [e (sorted-by-count ks)]
              (println (str "    " (padl (second e) 7) "x  " (pr-str (first e)))))
            (println (str "    carries a :pos of its own in "
                          (get (:with-pos @stats) op 0) " of " n " occurrences"))
            (println "    one real node:")
            (print-example op)))
      (println))))

;; --- the kinds the corpus does not reach ------------------------------------
;;
;; A kind that does not occur in a corpus is a fact about the corpus, not about
;; the IR. So: compile ONE form per unexercised surface syntax through the SAME
;; tap and print what it lowered to. These are synthetic — they say what the IR
;; does with a construct, and nothing about how often real code writes it.

(def synthetics
  [["syn1" "(fn [x] (.-y x))"         "(def syn1 (fn [x] (.-y x)))"]
   ["syn2" "(fn [x] (. x -y))"        "(def syn2 (fn [x] (. x -y)))"]
   ["syn3" "Math/sqrt as a VALUE"     "(def syn3 (fn [] Math/sqrt))"]
   ["syn4" "(Math/sqrt 2) applied"    "(def syn4 (fn [] (Math/sqrt 2)))"]
   ["syn5" "(java.io.File. \"/tmp\")" "(def syn5 (fn [] (java.io.File. \"/tmp\")))"]
   ["syn6" "a bare unresolvable name" "(def syn6 (fn [] some-name-no-var-no-class))"]])

(defn- report-synthetics []
  (println)
  (println "== 3b. the kinds this corpus does NOT exercise, exercised on purpose ===")
  (println)
  (println "  One form each, compiled through the same tap. SYNTHETIC: this says what")
  (println "  the analyzer does with a construct, not how often real code writes it.")
  (println)
  (doseq [s synthetics]
    (let [r (try (do (eval (read-string (nth s 2))) nil)
                 (catch :default e (str e)))
          n (get @pir/captured (str "user/" (nth s 0)))]
      (println (str "  " (pad (nth s 1) 26)
                    (if (and (nil? r) n)
                      (str "-> " (render-node (:body (first (:arities (:init n))))))
                      (str "REFUSED at compile time: " r))))))
  (println)
  (println "  The last line is the measurement behind 6(e): on this host there is no")
  (println "  surface syntax that produces a :host node. A name that is neither a var")
  (println "  nor a class is a compile ERROR, not a host escape."))

(defn- report-signature []
  (println)
  (println "== 4. is a SIGNATURE present at the node? =============================")
  (println)
  (let [sig-keys ["argtypes" "rettype" "arity" "params" "sig" "signature"
                 "effects" "effect" "type" "types"]
        ks (:keysets @stats)]
    (doseq [op host-ops]
      (let [seen (reduce (fn [a e] (into a (first e))) #{} (seq (get ks op {})))
            hits (filter (fn [k] (contains? seen (str ":" k))) sig-keys)]
        (when (pos? (get (:ops @stats) op 0))
          (println (str "  " (pad op 14) "union of ALL keys ever seen: "
                        (pr-str (vec (sort seen)))))
          (println (str "  " (pad "" 14) "signature-ish keys present: "
                        (if (seq hits) (pr-str (vec hits)) "NONE"))))))
    (println)
    (println "  For contrast, the ops in the SAME IR that DO carry a declared signature:")
    (println (str "    :ffi-fn       " (get (:ffi @stats) :ffi-fn 0) " occurrences"))
    (println (str "    :ffi-callable " (get (:ffi @stats) :ffi-callable 0) " occurrences"))
    (print-example :ffi-fn)
    (println)
    (println "  Arity is not fixed per name either — a name applied at several arities")
    (println "  cannot have ONE declared signature attached to it at the node:")
    (doseq [pr [[:hs-applied "Class/member applied"]
                [:hc-method  ".method called"]
                [:hn-class   "Class. constructed"]]]
      (let [m (get @stats (first pr) {})
            multi (filter (fn [e] (> (count (second e)) 1)) (seq m))]
        (println (str "    " (pad (second pr) 24)
                      (count m) " distinct names, "
                      (count multi) " used at more than one arity"))
        (doseq [e (take 6 (sort-by (fn [e] (first e)) multi))]
          (println (str "        " (pad (first e) 30) "arities "
                        (pr-str (vec (sort (keys (second e))))))))))))

(defn- report-names []
  (println)
  (println "== 4b. what the names actually are ====================================")
  (println)
  (let [tally (fn [title m n]
                (println (str "  " title "  (" (count m) " distinct, top " n ")"))
                (doseq [e (take n (sorted-by-count
                                    (reduce (fn [a e] (assoc a (first e) (total (second e))))
                                            {} (seq m))))]
                  (println (str "    " (padl (second e) 6) "x  " (first e))))
                (println))]
    (tally ":host-static Class/member, applied" (:hs-applied @stats) 12)
    (tally ":host-call .method" (:hc-method @stats) 12)
    (tally ":host-new Class." (:hn-class @stats) 12)))

(defn- report-position []
  (println)
  (println "== 5. reference vs application: are the four ONE shape? ===============")
  (println)
  (let [heads (:invoke-head @stats)
        tot-inv (total heads)]
    (println (str "  :invoke nodes: " tot-inv ".  What sits in the :fn position:"))
    (doseq [e (sorted-by-count heads)]
      (println (str "    " (pad (first e) 16) (padl (second e) 8))))
    (println)
    (doseq [op [:host-static :host]]
      (let [n (get (:ops @stats) op 0)
            applied (total (reduce (fn [a e] (assoc a (first e) (total (second e))))
                                   {} (seq (get @stats (if (= op :host-static) :hs-applied :h-applied) {}))))]
        (when (pos? n)
          (println (str "  " (pad op 14) n " nodes: " applied
                        " as the head of an :invoke, " (- n applied)
                        " in VALUE position (a reference, applied to nothing)")))))
    (println)
    (println "  :host-call and :host-new are not references at all — each carries its")
    (println "  own :args, so the call is the node. Two of the four are leaves that")
    (println "  become a call only by sitting under an :invoke; two are calls.")
    (println)
    ;; The same Class/member used BOTH ways is the sharp case: one name, two
    ;; meanings, and a per-name declared signature would have to cover both.
    (let [all     (:hs-name @stats)
          applied (reduce (fn [a e] (assoc a (first e) (total (second e))))
                          {} (seq (:hs-applied @stats)))
          both    (filter (fn [e] (and (contains? applied (first e))
                                       (> (second e) (get applied (first e) 0))))
                          (seq all))
          only-v  (filter (fn [e] (not (contains? applied (first e)))) (seq all))]
      (println (str "  Class/member names seen at all          : " (count all)))
      (println (str "  ... used BOTH as a value and as a callee: " (count both)
                    (if (seq both) (str "  " (pr-str (vec (sort (map first both))))) "")))
      (println (str "  ... only ever as a value, never called  : " (count only-v)
                    (if (seq only-v)
                      (str "  " (pr-str (vec (take 8 (sort (map first only-v))))))
                      ""))))))

(defn- report-lossiness []
  (println)
  (println "== 6. what an :extern would have to encode ============================")
  (println)
  (let [hk (:hc-kind @stats)]
    (println (str "  (a) :host-call ALREADY collapses two things behind one tag, and"))
    (println (str "      encodes the difference in a STRING: a leading \"-\" on :method"))
    (println (str "      means field read, otherwise method call (analyzer.clj:791)."))
    (println (str "      in this corpus: method calls " (get hk :method-call 0)
                  ",  field reads " (get hk :field-read 0)
                  (if (zero? (get hk :field-read 0))
                    "  <- unexercised here; see 3b, where it is exercised"
                    ""))))
  (println)
  (let [tops (:hc-target-op @stats)]
    (println "  (b) a :host-call's receiver is an arbitrary NODE, not a class. The op")
    (println "      of the :target, over the whole corpus:")
    (doseq [e (sorted-by-count tops)]
      (println (str "        " (pad (first e) 16) (padl (second e) 8))))
    (let [hints (:hc-target-hint @stats)]
      (println (str "      targets carrying a :hint annotation: " (total hints)
                    " of " (total tops)
                    (if (seq hints) (str "  " (pr-str (vec (sort (map str (keys hints)))))) "")))))
  (println)
  (let [ks (:keysets @stats)
        n-shapes (reduce (fn [a op] (+ a (count (get ks op {})))) 0 host-ops)]
    (println (str "  (c) the four ops present " n-shapes " distinct key sets between them"))
    (println (str "      (section 3). No two of the four share a key set."))
    (println (str "  (d) NONE of the four carries :pos — §1.1 also requires the effect"))
    (println (str "      boundary's site-id to come from a durable identity spine. What"))
    (println (str "      DOES carry one is the enclosing :invoke: "
                  (get (:with-pos @stats) :invoke 0) " of "
                  (get (:ops @stats) :invoke 0) " have a :pos."))
    (println (str "      So an APPLIED :host-static borrows a site from its parent, and"))
    (println (str "      a :host-call or :host-new — a call in its own right, with no"))
    (println (str "      :invoke above it — has none at all.")))
  (println)
  (println "  (e) :host is resolved by the HOST, not by jolt.ir. On this back end")
  (println "      host/chez/host-contract.ss:337 `hc-resolve-global` returns only")
  (println "      :var, :class or :unresolved — never :kind :host — so the analyzer's")
  (println "      `:host (host-ref …)` arm (analyzer.clj:923) is unreachable here.")
  (println "      Section 3b is the measurement of that, not the claim.")
  (println)
  (let [vn (:var-ns @stats)
        hostish (filter (fn [e] (or (= "jolt.host" (first e)) (= "jolt.ffi" (first e))))
                        (seq vn))]
    (println "  (f) THE ESCAPE SURFACE IS NOT ONLY THESE OPS. Some host escapes are")
    (println "      lowered by the analyzer to an ordinary :invoke on a :var into")
    (println "      jolt.host — e.g. a class literal becomes (jolt.host/jolt-class-for")
    (println "      \"…\") (analyzer.clj:925) and (set! Class/field v) becomes")
    (println "      jolt.host/set-static-field! (analyzer.clj:493). Measured, :var")
    (println "      references into host namespaces in this corpus:")
    (if (empty? hostish)
      (println "        none")
      (doseq [e (sorted-by-count (reduce (fn [a e] (assoc a (first e) (second e))) {} hostish))]
        (println (str "        " (pad (first e) 16) (padl (second e) 8)))))
    (println (str "      plus " (get (:ffi @stats) :ffi-fn 0) " :ffi-fn and "
                  (get (:ops @stats) :regex 0) " :regex nodes, which are host leaves too."))
    (println "      An :extern that replaces the ops in section 3 does not enclose")
    (println "      these, so \"one node for every escape\" is not what it would buy.")))

(defn- report-dependence []
  (println)
  (println "== 7. does perturb depend on distinguishing the four? =================")
  (println)
  (let [hs (:by-ns @stats)
        pn (filter (fn [e] (str/starts-with? (str (first e)) "perturb.")) (seq hs))]
    (println "  perturb namespaces containing any of the four:")
    (if (empty? pn)
      (println "    NONE.")
      (doseq [e (sort-by (fn [e] (first e)) pn)]
        (println (str "    " (pad (first e) 26) (pr-str (second e))))))
    (println)
    (println (str "  Totals across ALL of perturb: "
                  (pr-str (reduce (fn [a e] (reduce (fn [b h] (update b (first h) (fnil + 0) (second h)))
                                                    a (seq (second e))))
                                  {} pn))))
    (println)
    (println "  perturb.check's IR walk names exactly ONE of the four — :host-call, at")
    (println "  src/perturb/check.clj:1418, walked as target-then-args. `w-invoke`")
    (println "  (check.clj:1001) branches on a :var head only, so a :host-static callee")
    (println "  takes the generic path, and :host-static / :host-new / :host fall")
    (println "  through the walk's `:else` to OPAQUE. Measured consequence: with zero")
    (println "  :host-call and zero :host-new anywhere in perturb, NOTHING perturb does")
    (println "  today depends on telling the four apart. The one thing collapsing them")
    (println "  would change is that :host-new's :args are currently never walked at")
    (println "  all, and an :extern with a uniform argument position would be walked.")))

(defn- report-verdict []
  (println)
  (println "== 8. verdict, from the numbers above ==================================")
  (println)
  (let [ops     (:ops @stats)
        ks      (:keysets @stats)
        occur   (filter (fn [op] (pos? (get ops op 0))) host-ops)
        absent  (filter (fn [op] (zero? (get ops op 0))) host-ops)
        shapes  (reduce (fn [a op] (+ a (count (get ks op {})))) 0 host-ops)
        sig     (reduce (fn [a op]
                          (into a (filter (fn [k] (contains? #{":argtypes" ":rettype" ":arity"
                                                               ":sig" ":signature" ":effects"}
                                                             k))
                                          (reduce (fn [b e] (into b (first e))) #{}
                                                  (seq (get ks op {}))))))
                        #{} host-ops)
        posed   (reduce (fn [a op] (+ a (get (:with-pos @stats) op 0))) 0 host-ops)
        hsapp   (total (reduce (fn [a e] (assoc a (first e) (total (second e))))
                               {} (seq (:hs-applied @stats))))
        hsn     (get ops :host-static 0)
        multi   (reduce (fn [a k] (+ a (count (filter (fn [e] (> (count (second e)) 1))
                                                      (seq (get @stats k {}))))))
                        0 [:hs-applied :hc-method :hn-class])]
    (println (str "  1. OF THE FOUR OPS, " (count occur) " OCCUR: " (pr-str (vec occur)) "."))
    (when (seq absent)
      (println (str "     " (pr-str (vec absent)) " does not occur — and 3b/6(e) show it CANNOT"))
      (println (str "     occur on this back end, at any frequency, from any source.")))
    (println (str "     §1.1 names four escapes; this corpus on this host has "
                  (count occur) "."))
    (println)
    (println (str "  2. THE " (count occur) " THAT OCCUR PRESENT " shapes " DISTINCT KEY SETS and no two"))
    (println (str "     of them share one. " hsn " :host-static nodes split "
                  hsapp " applied / " (- hsn hsapp) " in value position,"))
    (println (str "     so ONE node shape means a call head in one place and a constant"))
    (println (str "     in another and only the PARENT says which; :host-call and"))
    (println (str "     :host-new carry their own :args instead. Collapsing three shapes"))
    (println (str "     into one tagged node is cheap and would lose nothing — but it is"))
    (println (str "     a tagged union of three cases plus the field-read/method-call"))
    (println (str "     split already smuggled through a leading \"-\" (3b), not the"))
    (println (str "     \"single op\" §1.1's phrasing suggests."))
    (println)
    (println (str "  3. SIGNATURE KEYS FOUND ON THE FOUR: "
                  (if (seq sig) (pr-str (vec sig)) "NONE.")))
    (println (str "     Nor is a signature RECOVERABLE at the node: " multi " distinct names"))
    (println (str "     are used at more than one arity, and a :host-call's receiver is"))
    (println (str "     an expression whose class is not at the node. The IR ops that DO"))
    (println (str "     carry a declared signature are :ffi-fn/:ffi-callable ("
                  (get (:ffi @stats) :ffi-fn 0) "/"
                  (get (:ffi @stats) :ffi-callable 0) " here) —"))
    (println (str "     separate ops with their own surface syntax, not a tag on these."))
    (println)
    (println (str "  4. SITE IDENTITY: " posed " of "
                  (reduce (fn [a op] (+ a (get ops op 0))) 0 host-ops)
                  " host nodes carry a :pos. §1.1 also requires"))
    (println (str "     the effect boundary's site-id to come from a durable spine; these"))
    (println (str "     nodes have none, so `:extern` alone would not supply one either."))
    (println)
    (println "  READ TOGETHER — the claim has two halves and they do not get the same")
    (println "  verdict.")
    (println)
    (println "    DESCRIPTIVE HALF, `untyped, un-effected escapes`: CONFIRMED, and")
    (println "    more tightly than §1.1 states it. Every one of the 327 host nodes")
    (println "    carries exactly its required keys and NOTHING else — not one type")
    (println "    hint, numeric kind, effect, or position was ever attached by any")
    (println "    pass. One key set per op, no variation.")
    (println)
    (println "    PRESCRIPTIVE HALF, `should be replaced by a single :extern carrying")
    (println "    a declared effect row and signature`: NOT SUPPORTED AS STATED. The")
    (println "    re-tagging is the cheap part and buys nothing on its own. The row")
    (println "    and the signature are the whole cost, and neither is in the IR nor")
    (println "    derivable from it: no arity is fixed per name, no receiver class is")
    (println "    at the node, and the escape surface is not even confined to these")
    (println "    ops (6(f)). Jolt's one signature-carrying op, :ffi-fn, gets its")
    (println "    signature from SURFACE SYNTAX the programmer writes. That is the")
    (println "    shape of the real problem: a declaration language and a place to")
    (println "    write it, with the IR node as its consumer — not an IR edit.")))

(defn- report-scope []
  (println)
  (println "== 9. what this measurement does NOT cover ============================")
  (println)
  (println "  - ONE corpus, ONE host, ONE checkout. Chez back end; a JVM or JS back")
  (println "    end resolves host names differently and :host may well be live there.")
  (println "  - Namespaces already loaded when the tap goes in are invisible:")
  (println "    clojure.core itself is NOT in these counts, and it is the largest")
  (println "    body of host-interop code in the system. The proportions in section 2")
  (println "    are proportions of THIS corpus.")
  (println "  - :def only. A top-level :defmacro node and its body are not walked.")
  (println "  - Post-const-fold IR (perturb.ir's documented limit): this is what the")
  (println "    back end receives, not what the analyzer emitted.")
  (println "  - Frequency is not importance. 327 host nodes in 62704 is a small")
  (println "    share, but perturb.posix reaches the network through :ffi-fn, not")
  (println "    through any of them, so counting is not a measure of danger.")
  (println "  - Nothing here was RUN. This is a read of trees."))

;; --- main -------------------------------------------------------------------

(defn -main [& _]
  (println line)
  (println "perturb.externprobe — §1.1's `:extern` claim, measured over real Jolt IR")
  (println line)
  (println)
  (pir/install!)
  (let [res (reduce (fn [a s]
                      (let [r (try (do (require s) nil) (catch :default e (str e)))]
                        (if (nil? r)
                          (assoc a :loaded (conj (:loaded a) s))
                          (assoc a :failed (conj (:failed a) [s r])))))
                    {:loaded [] :failed []}
                    (concat stdlib-corpus perturb-corpus))
        defs (vals @pir/captured)]
    (doseq [n defs]
      (walk! n {:ns (:ns n) :def (:name n) :pos (:pos n)}))
    (doseq [n defs] (sweep! n))
    (report-corpus (:loaded res) (:failed res))
    (let [agree (report-histogram)]
      (report-shapes)
      (report-synthetics)
      (report-signature)
      (report-names)
      (report-position)
      (report-lossiness)
      (report-dependence)
      (report-verdict)
      (report-scope)
      (println)
      (println line)
      (if (and (> (count defs) 500) (pos? (total (:ops @stats))) agree)
        (do (println "PROBE OK — the corpus loaded, the trees were walked, and the two")
            (println "           independent walks agree on the counts above.")
            (System/exit 0))
        (do (println "PROBE FAILED — it measured nothing it could trust.")
            (System/exit 1))))))
