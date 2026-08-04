(ns perturb.gatecheck
  "Every native binding in `perturb.posix` is gated, or it is on a list of one.

  WHAT THIS CLOSES. `perturb.effect/native!` fails closed at the native crossing
  and `perturb.effect/latch!` records the refusal before the throw, so a caller
  that catches it cannot make the run report success. That is a real boundary,
  but it is perturb's OWN boundary: it runs because `perturb.posix/gate!` calls
  it, and `gate!` runs because someone wrote it. `jolt.sim` intercepts at the
  runtime's FFI descriptor layer, so a binding it never heard of is still
  intercepted. perturb does not. The residual recorded with the fail-closed
  commit was exactly this: a `jolt.ffi/defcfn` added without a matching `gate!`
  is invisible to the boundary — not refused, not counted, not latched.

  `perturb.posix/defsys` is the first half of the fix: it emits the `__cfn` form
  and the gated wrapper as ONE `def`, closing over the raw callable so that no
  var names it, which makes the ungated binding unrepresentable inside `defsys`
  rather than merely absent. This namespace is the second half: it makes NOT
  USING `defsys` detectable.

  HOW IT DETECTS, AND WHY THIS WAY. It reads real Jolt IR — the post-passes
  node the back end is handed, captured by `perturb.ir` from the compile spine —
  and enumerates every `:op :ffi-fn` node in `perturb.posix`. Three mechanisms
  were available and this is the one that is hardest to defeat BY ACCIDENT:

    - A SOURCE SCAN (grep the file for `defcfn`) sees a spelling, not a
      binding. `jolt.ffi/foreign-fn`, a bare `jolt.ffi/__cfn`, or any macro
      that expands to one, all bind native code and none of them contains the
      token `defcfn`. It also reads a file, where the compiler reads a
      namespace: the two can be different files.

    - A RUNTIME VAR SCAN sees what became a var. A `foreign-fn` evaluated
      inside a `defn` body is a native binding with no var at all, and a
      binding whose var is created by a macro is indistinguishable from one
      that was gated.

    - A MACRO-TIME REGISTRY — `defsys` appending its own name to a set — is
      circular. It can only tell you about bindings that went through `defsys`,
      and a binding that did not go through `defsys` is the entire hypothesis
      under test. It answers the question by assuming it.

  The IR scan has none of those properties. `:op :ffi-fn` is what the ANALYZER
  produces for the `jolt.ffi/__cfn` special form (jolt-core/jolt/analyzer.clj
  `analyze-ffi-fn`), and every surface spelling reduces to it: `defcfn` is
  `(def name (__cfn ...))`, `foreign-fn` is `(__cfn ...)`. It sees the program
  the compiler compiled, from whatever file the compiler read, at whatever depth
  the form was written — top level or nested inside a function body.

  WHAT IT COVERS AND WHAT IT DOES NOT. It covers `perturb.posix`. It does NOT
  make perturb's boundary equivalent to `jolt.sim`'s: a `defcfn` in another
  namespace is outside this check entirely, and so is any host escape that is
  not an FFI form. See `limits` below, which is printed on every run.")

(require '[perturb.ir :as pir])
(require '[clojure.string :as str])

;; NOTE: this namespace deliberately does NOT `:require` perturb.posix. The IR
;; tap has to be installed before the namespace under audit is analyzed, and a
;; load-time require would load it first — the capture would be empty and the
;; check would pass vacuously. `-main` requires it, through `pir/capture!`.

(def audited-ns "perturb.posix")

(def required-csyms
  "ANTI-VACUITY. A scan that finds nothing passes trivially, which is the same
  defect as a monitor that never fires and the same one `perturb.effect/report`
  guards with its required-symbol set. These nine C entry points must be present
  AND gated for this check to mean anything; if the walk stops finding them, the
  walk is broken, not the namespace clean."
  #{"socket" "connect" "send" "recv" "close" "bind" "listen" "accept" "setsockopt"})

(def expected-allow-list
  "The allow-list is allowed to be exactly this. `perturb.posix/ungated-bindings`
  is where the exception is DECLARED; this is where it is BOUNDED. Adding a
  second ungated binding is then a failing change in two places rather than a
  quiet one in neither."
  '#{c-absent-canary})

;; --- walking the IR ---------------------------------------------------------

(defn- walk
  "Every map node in `x`'s tree, depth-first, including `x`."
  [x]
  (cond
    (map? x)    (cons x (mapcat (fn [kv] (walk (second kv))) (seq x)))
    (vector? x) (mapcat walk x)
    (seq? x)    (mapcat walk x)
    :else       nil))

(defn- nodes-of-op [x op] (filter (fn [n] (= op (:op n))) (walk x)))

(defn- local-refs
  "Count of `{:op :local :name nm}` nodes in `x`'s tree."
  [x nm]
  (count (filter (fn [n] (= nm (:name n))) (nodes-of-op x :local))))

(defn- gate-invoke?
  "Is `n` `(perturb.posix/gate! :kw)` with `(name :kw)` = `csym`?"
  [n csym]
  (and (= :invoke (:op n))
       (= :var (:op (:fn n)))
       (= "perturb.posix" (:ns (:fn n)))
       (= "gate!" (:name (:fn n)))
       (= 1 (count (:args n)))
       (= :const (:op (first (:args n))))
       (keyword? (:val (first (:args n))))
       (= csym (name (:val (first (:args n)))))))

(defn defsys-faults
  "Why `d` is not the shape `perturb.posix/defsys` emits — empty if it is.

  The shape, on the post-passes IR:

      (def NAME (let [L (__cfn CSYM ARGTYPES RET)]
                  (fn [a0 .. an] (gate! :CSYM) (L a0 .. an))))

  Each clause below is load-bearing. `L` referenced exactly once, in the RET of
  the wrapper, is the clause that says the raw callable does not escape: there
  is no var for it and no other reader of it, so `gate!` is not merely called
  first, it is the only path to the entry point. The gate keyword being the C
  symbol is what stops the latched name and the resolved entry point drifting
  apart."
  [d]
  (let [init  (:init d)
        binds (:bindings init)
        b0    (first binds)
        L     (first b0)
        ffi   (second b0)
        fnode (:body init)
        ars   (:arities fnode)
        ar    (first ars)
        body  (:body ar)
        sts   (:statements body)
        ret   (:ret body)
        ps    (:params ar)]
    (vec
      (remove
        nil?
        [(when-not (= :let (:op init))
           (str "init is " (pr-str (:op init)) ", not a let binding the __cfn"))
         (when-not (= 1 (count binds))
           (str "let binds " (count binds) " names, not 1"))
         (when-not (= :ffi-fn (:op ffi))
           "the let's single binding is not the __cfn form")
         (when-not (= :fn (:op fnode))
           (str "the let's body is " (pr-str (:op fnode)) ", not a fn"))
         (when-not (= 1 (count ars))
           (str "the wrapper has " (count ars) " arities, not 1"))
         (when-not (= (count ps) (count (:argtypes ffi)))
           (str "the wrapper takes " (count ps) " args but the C signature takes "
                (count (:argtypes ffi))))
         (when-not (= :do (:op body))
           "the wrapper body is a single expression — nothing is sequenced before the call")
         (when-not (= 1 (count sts))
           (str "the wrapper body has " (count sts) " statements before its result, not 1"))
         (when-not (gate-invoke? (first sts) (:csym ffi))
           (str "the statement before the call is not (gate! :" (:csym ffi) ")"))
         (when-not (and (= :invoke (:op ret))
                        (= :local (:op (:fn ret)))
                        (= L (:name (:fn ret))))
           "the wrapper's result is not a call of the bound __cfn")
         (when-not (= (mapv (fn [p] {:op :local :name p}) ps)
                      (mapv (fn [a] {:op :local :name (:name a)}) (:args ret)))
           "the wrapper does not pass its own parameters straight through")
         (when-not (= 1 (local-refs d L))
           (str "the raw callable `" L "` is referenced " (local-refs d L)
                " times — it escapes the gated wrapper"))]))))

;; --- the audit --------------------------------------------------------------

(defn audit
  "Classify every FFI binding the IR of `audited-ns` contains."
  [allow-list]
  (let [defs (pir/defs-in audited-ns)
        with-ffi (filter (fn [d] (seq (nodes-of-op d :ffi-fn))) defs)
        rows (mapv
               (fn [d]
                 (let [ffis   (vec (nodes-of-op d :ffi-fn))
                       nm     (symbol (:name d))
                       faults (if (= 1 (count ffis))
                                (defsys-faults d)
                                [(str "one def carries " (count ffis)
                                      " __cfn forms; defsys emits exactly one")])
                       gated? (empty? faults)]
                   {:var      nm
                    :csyms    (mapv :csym ffis)
                    :pos      (pir/site (:pos d))
                    :gated?   gated?
                    :allowed? (contains? allow-list nm)
                    :faults   faults}))
               with-ffi)]
    {:defs-captured (count defs)
     :rows          rows
     :gated         (filterv :gated? rows)
     ;; An ungated binding is a violation UNLESS it is on the named allow-list.
     :violations    (filterv (fn [r] (and (not (:gated? r)) (not (:allowed? r)))) rows)
     :allowed       (filterv (fn [r] (and (not (:gated? r)) (:allowed? r))) rows)}))

(defn limits []
  ["WHAT THIS CHECK COVERS, AND WHAT IT STILL DOES NOT"
   ""
   "  COVERS. Every jolt.ffi native binding reachable in the captured IR of"
   (str "  " audited-ns " — at any depth, under any surface spelling (defcfn,")
   "  foreign-fn, a bare __cfn, or a macro expanding to one), because it matches"
   "  the analyzer's :ffi-fn node rather than any source token. Each must be the"
   "  shape perturb.posix/defsys emits — __cfn bound to a local that is read"
   "  exactly once, in the result position of a wrapper whose only preceding"
   "  statement is (gate! :the-c-symbol) — or be on the named allow-list."
   ""
   "  DOES NOT COVER 1 — OTHER NAMESPACES. This is the residual that remains,"
   "  and it is the difference from jolt.sim. jolt.sim intercepts at the"
   "  runtime's FFI DESCRIPTOR layer, so a binding it never heard of is still"
   "  intercepted. This check enumerates one namespace. A jolt.ffi/defcfn"
   "  written in perturb.nrepl, perturb.http, or a namespace added tomorrow is"
   "  outside it, and perturb.effect/native! would never run for it."
   ""
   "  DOES NOT COVER 2 — NON-FFI HOST ESCAPES. Nothing here sees a host interop"
   "  call, a Chez primitive reached some other way, or I/O performed by a"
   "  library perturb requires. It is a check about jolt.ffi bindings only."
   ""
   "  DOES NOT COVER 3 — TOP-LEVEL FORMS THAT ARE NOT DEFS. perturb.ir's tap"
   "  records :def nodes. A __cfn written in a top-level expression that defines"
   "  nothing would not be captured. It has nowhere to be stored, so it could"
   "  only be called immediately, but the hole is real and is stated rather than"
   "  filtered."
   ""
   "  DOES NOT COVER 4 — WHAT THE GATE DOES. This is an enumeration check. That"
   "  gate! fails closed and latches is perturb.effect's property, asserted"
   "  per-run by perturb.effect/report and controlled by -M:noio"
   "  --unhandled-native. This check only says nothing crosses without reaching"
   "  it."])

(defn -main [& _]
  (let [line (apply str (repeat 74 "="))]
    (println line)
    (println "perturb.gatecheck — every native binding in perturb.posix is gated")
    (println line)
    (println)
    ;; The tap must be installed before perturb.posix is analyzed; capture!
    ;; installs and only then requires. See the note at the top of this file.
    (println (str "  IR captured: " (pir/capture! [(symbol audited-ns)])
                  " defs across all namespaces"))
    (let [allow-list (deref (resolve (symbol audited-ns "ungated-bindings")))
          a          (audit allow-list)
          found      (set (mapcat :csyms (:gated a)))
          missing    (set (remove found required-csyms))
          allow-ok?  (= allow-list expected-allow-list)
          ;; Every allow-list entry must name a binding that is actually there
          ;; and actually ungated, so the list cannot carry dead names that
          ;; quietly pre-authorise a future one.
          allow-live (set (map :var (:allowed a)))
          allow-dead (set (remove allow-live allow-list))]
      (println (str "  defs of " audited-ns " walked: " (:defs-captured a)))
      (println (str "  FFI bindings found: " (count (:rows a))))
      (println)
      (println "  GATED — emitted by perturb.posix/defsys, __cfn behind (gate! :csym):")
      (doseq [r (:gated a)]
        (println (str "    ok   " (:var r) "   C:" (str/join "," (:csyms r))
                      "   at " (:pos r))))
      (println)
      (println (str "  ALLOW-LISTED — ungated on purpose, declared in "
                    audited-ns "/ungated-bindings:"))
      (if (seq (:allowed a))
        (doseq [r (:allowed a)]
          (println (str "    ok   " (:var r) "   C:" (str/join "," (:csyms r))
                        "   at " (:pos r)))
          ;; Only the first reason: the point is that it IS ungated and is
          ;; excused by name, not the full diff against the defsys shape.
          (println (str "           ungated (" (first (:faults r))
                        "), excused by ungated-bindings")))
        (println "    none"))
      (println)
      (if (seq (:violations a))
        (do
          (println "  UNGATED AND NOT ALLOW-LISTED:")
          (doseq [r (:violations a)]
            (println (str "    FAIL " (:var r) "   C:" (str/join "," (:csyms r))
                          "   at " (:pos r)))
            (doseq [f (:faults r)] (println (str "           " f)))))
        (println "  UNGATED AND NOT ALLOW-LISTED: none"))
      (println)
      (doseq [l (limits)] (println (str "  " l)))
      (println)
      (println line)
      (println "VERDICT")
      (println line)
      (let [fail (atom 0)]
        (if (empty? (:violations a))
          (println (str "  PASS  binding coverage: all " (count (:rows a))
                        " jolt.ffi bindings in " audited-ns
                        " are defsys-gated (" (count (:gated a))
                        ") or allow-listed (" (count (:allowed a)) ")"))
          (do (println (str "  FAIL  binding coverage: " (count (:violations a))
                            " ungated jolt.ffi binding(s) in " audited-ns
                            " on no allow-list"))
              (reset! fail 1)))
        (if (empty? missing)
          (println (str "  PASS  anti-vacuity: the walk found all "
                        (count required-csyms) " required C entry points, gated"
                        " — so a passing scan is a scan that ran"))
          (do (println (str "  FAIL  anti-vacuity: the walk did not find gated "
                            (pr-str (sort missing))
                            " — the scan is broken, not the namespace clean"))
              (reset! fail 1)))
        (if (and allow-ok? (empty? allow-dead))
          (println (str "  PASS  allow-list is exactly " (pr-str (sort allow-list))
                        ", and its entry is a binding that is really there"))
          (do (println (str "  FAIL  allow-list is " (pr-str (sort allow-list))
                            ", expected " (pr-str (sort expected-allow-list))
                            (when (seq allow-dead)
                              (str "; dead entries " (pr-str (sort allow-dead))))))
              (reset! fail 1)))
        (flush)
        (System/exit @fail)))))
