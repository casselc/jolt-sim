(ns perturb.tcpcheck
  "Run `perturb.check` over `casselc/jolt-tcp`'s CLIENT — a library perturb does
  not own, did not write, cannot modify, and whose authors have never read
  PERTURB-DESIGN.

  PERTURB-DESIGN §5 ladder step 1a. `perturb.streamcheck` is the pattern; the
  difference is the distance. `perturb.stream` was a port living in perturb's
  own tree. `teensyp.client` is 773 lines on a pull-request branch of somebody
  else's repository, and `teensyp.client-test` is its own test suite, including
  a real loopback contract that opens a server, sends, half-closes, reads to EOF
  and closes twice. Both are compiled here from the library's source files,
  unmodified, and checked against the FOUR declarations in `perturb.tcpcap` —
  the machine as the library implements it, and then the three positions the
  language can take on its idempotent `close!`.

  THIS IS NOT A GATE. It records no expectations and asserts nothing. The point
  is to find out what the verdicts are.

  EVERY REJECTION BELOW IS A FACT ABOUT THE RULES. jolt-tcp's client works: its
  test suite passes against a real loopback socket under the compiler it is
  written for. Nothing here says otherwise, and nothing here verifies it either
  — a declaration checked against a library is a statement about what the
  declaration language can say, not a proof about the library.

  HOW THE IR IS OBTAINED, AND WHAT IT COSTS.

    1. `perturb.ir/install!` taps the compile spine, then this namespace
       `load-file`s the library's source. Nothing is on the classpath and no
       dependency is resolved: the path is `$JOLT_TCP_ROOT`, defaulting to
       the clone the session was given. `-M:tcpcheck` opens no socket — under
       `strace -e trace=socket,connect,bind,accept,listen` the run makes zero
       such calls.
    2. `jolt.net` is a NAME-ONLY STUB (`dev/tcpstub/jolt/net.clj`) because the
       real `jolt-net` needs `jolt.ffi`'s `:varargs-after`, which the compiler at
       /home/user/jolt does not have. Every stub function throws. The IR that
       gets checked is `teensyp.client`'s own.
    3. `perturb.ir`'s tap fires on a top-level `:def` only. `clojure.test/deftest`
       expands to a `do`, so every test in the suite is invisible to it. This
       namespace installs a SECOND tap that descends one `do` level and files the
       `:def`s it finds into `perturb.ir`'s own atoms. That is a change to how IR
       is CAPTURED, made here, in a new namespace; `perturb.ir` and
       `perturb.check` are untouched.
    4. Loading `teensyp/client_test.clj` EXECUTES ONE FORM. A paren at
       `client_test.clj:202` closes `deftest operation-deadlines-bound-wait-and-retry`
       early, so the `(testing \"a native reset reported with readiness is never
       rewritten\" …)` block at line 204 is a TOP-LEVEL form rather than part of
       any test. It runs at load, it calls `receive-loop!` with hand-made
       closures, it touches no socket — and against the stub it fails, harmlessly
       and loudly. Its output is captured and reported rather than hidden. That
       is a defect in the library found by loading it, not by checking it: under
       the real runner that assertion is never reported as a test at all."
  (:require [perturb.ir :as pir]
            [perturb.cap :as cap]
            [perturb.check :as chk]
            [perturb.tcpcap :as tcap]
            [jolt.passes.numeric :as jnum]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; capture
;; ---------------------------------------------------------------------------

(def default-root
  "/tmp/claude-0/-home-user/40949ba0-435d-5ac0-bbfb-b7fa386e6d2f/scratchpad")

(defn- root []
  (or (System/getenv "JOLT_TCP_ROOT") default-root))

(def ^:private do-tap-installed (atom false))

(defn- file! [node]
  (when (= :def (:op node))
    (let [k (str (:ns node) "/" (:name node))]
      (when (not (contains? @pir/captured k))
        (swap! pir/capture-order conj k))
      (swap! pir/captured assoc k node))))

(defn install-do-tap!
  "A second tap, one level deep, so a `deftest` is not invisible.

  `perturb.ir/install!` files a top-level form only when its `:op` is `:def`.
  `clojure.test/deftest` expands to `(do (defn …) (alter-meta! …) (swap! …)
  (var …))`, and jolt hands that `do` to the pass pipeline whole, so 13 of the
  19 definitions in `teensyp.client-test` — every test in it — were being
  silently dropped. This descends exactly one level and files the `:def`
  children. It does not recurse: a `def` nested deeper than a top-level `do` is
  not a top-level definition and this is not the place to start pretending
  otherwise."
  []
  (when (not @do-tap-installed)
    (reset! do-tap-installed true)
    (alter-var-root
      (var jnum/annotate)
      (fn [old]
        (fn [node]
          (when (= :do (:op node))
            (doseq [c (concat (:statements node) (:body node) [(:ret node)])]
              (file! c)))
          (old node))))))

(defn load-library!
  "Compile the library's real source under the tap. Returns whatever the load of
  the test namespace printed, because one form of it runs."
  []
  (pir/install!)
  (install-do-tap!)
  (let [r (root)]
    ;; The launcher cd's to the jolt repo root and passes the project dir in
    ;; JOLT_PWD, so the stub is named from there rather than from `.`.
    (load-file (str (or (System/getenv "JOLT_PWD") ".") "/dev/tcpstub/jolt/net.clj"))
    (load-file (str r "/jolt-tcp/src/teensyp/buffer.clj"))
    (load-file (str r "/jolt-tcp/src/teensyp/concurrent.clj"))
    (load-file (str r "/jolt-tcp/src/teensyp/client.clj"))
    (load-file (str r "/jolt-tcp/src/teensyp/server.clj"))
    (with-out-str (load-file (str r "/jolt-tcp/test/teensyp/client_test.clj")))))

;; ---------------------------------------------------------------------------
;; the taxonomy
;; ---------------------------------------------------------------------------
;;
;; §5 asks for the count that matters: how many rejections are §4.6's single root
;; cause — "a capability may only live in a `let`/`loop` binding whose shape is
;; known at compile time" — and how many are something else. The buckets are
;; assigned by diagnostic KIND plus the shape that produced it, and the two
;; ambiguous kinds are listed under both with the reason printed.

(def buckets
  [{:name :root-cause-4.6
    :kinds #{:capture :escape :no-signature :loop-not-preserving}
    :why ["§4.6's single root cause: a capability may only live in a binding of"
          "statically known shape — into a closure, a collection, a"
          "function-valued parameter, or selected by a runtime value."]}
   {:name :in-place-typestate
    :kinds #{:use-after-move :dangling :produces-mismatch}
    :why ["NEW: the linear discipline meets a resource whose state changes IN"
          "PLACE. :consumes/:produces is value threading and the connection is"
          "a stable name over a mutable atom, so `use-after-move` = the name"
          "died at a transition that could not return it, and `dangling` = the"
          "only encoding that keeps the name alive cannot consume it either."]}
   {:name :from-to-pairing
    :kinds #{:annotation-inconsistent :annotation-ambiguous-edge
             :annotation-undeclared-transition}
    :why ["NEW: an operation whose destination DEPENDS ON ITS SOURCE cannot be"
          "annotated. Several declared edges, one annotation, one comparison"
          "each, so all but one group must disagree."]}
   {:name :sum-unresolved
    :kinds #{:state-unresolved}
    :why ["E26 finding 7's sum, reached and not eliminable: no declarable"
          "discriminator partitions the axis the peer decides."]}
   {:name :known-E23
    :kinds #{:unsupported-construct :untracked-borrow :untracked-consume}
    :why ["Already recorded by E23/E15: exception paths are unwalked, and"
          "interprocedural flow is by annotation only — an unannotated NAMED"
          "callee is opaque, and annotating it is the whole fix."]}
   {:name :typestate-proper
    :kinds #{:typestate :cancelled-use}
    :why ["The typestate axis doing its job: an operation applied in a state"
          "its declaration forbids."]}
   {:name :declaration-refused
    :kinds #{:annotation-unpositioned :annotation-duplicates-capability
             :annotation-unsupported :annotation-underived-state
             :in-place-unnamed}
    :why ["The annotation itself was refused before any body was read."]}
   {:name :machine-unsound
    :kinds #{:absorbing-state-unsound :edge-source-overlap
             :cancelled-state-unsound}
    :why ["The MACHINE contradicts one of its own claims about what is"
          "impossible — an edge out of a discharged state that re-acquires, a"
          "source collection hiding a source-dependent destination, or a"
          "cancelled state with an ordinary way out."]}
   {:name :refinement
    :kinds #{:refinement :refinement-undischarged}
    :why ["The refinement tier."]}])

(defn- computed-callee?
  "`no-signature` covers two different rules and the diagnostic says which. A
  callee that is `a computed function` is §4.6's forbidden shape — a capability
  handed to a function-valued expression, which is `with-conn`, every reactor
  callback and E23's unexpressible `(f c)`. A callee that is a NAMED VAR is the
  ordinary ergonomic cost E23 measured: interprocedural flow is by annotation
  only, so annotating the callee fixes it and no rule is missing. Counting them
  together would inflate §4.6's share, which is the number §5 asks for."
  [d]
  (some (fn [l] (and (string? l) (str/includes? l "a computed function")))
        (:detail d)))

(defn- bucket-of [d]
  (let [kind (:kind d)]
    (cond
      (and (= :no-signature kind) (not (computed-callee? d))) :known-E23
      :else (or (:name (first (filter (fn [b] (contains? (:kinds b) kind)) buckets)))
                :unclassified))))

;; ---------------------------------------------------------------------------
;; printing
;; ---------------------------------------------------------------------------

(defn- kinds-of [r] (vec (sort (distinct (map (fn [d] (name (:kind d))) (:diagnostics r))))))

(defn- axiom? [sp r]
  (or (contains? (:transitions-of sp) (:var r))
      (contains? (:representation sp) (:var r))))

(defn- verdict-line [sp r]
  (str "  "
       (cond (and (axiom? sp r) (empty? (:diagnostics r))) "[axiom] "
             (axiom? sp r)                                 "[ax NO] "
             (empty? (:diagnostics r))                     "[ok   ] "
             :else                                         "[NO   ] ")
       (:var r)
       (if (empty? (:diagnostics r)) "" (str "  " (kinds-of r)))))

(defn- tally [results]
  (reduce (fn [acc d] (update acc (bucket-of d) (fn [n] (inc (or n 0)))))
          {} (mapcat :diagnostics results)))

(defn- kind-tally [results]
  (reduce (fn [acc d] (update acc (name (:kind d)) (fn [n] (inc (or n 0)))))
          {} (mapcat :diagnostics results)))

(defn- print-tally [t total]
  (doseq [b buckets]
    (let [n (get t (:name b) 0)]
      (when (pos? n)
        (println (str "    " (format "%3d" n) "  " (name (:name b))))
        (doseq [l (:why b)] (println (str "         " l))))))
  (let [u (get t :unclassified 0)]
    (when (pos? u) (println (str "    " (format "%3d" u) "  unclassified"))))
  (println (str "    ---  " total " diagnostics in total")))

(def namespaces ["teensyp.client" "teensyp.client-test"])

(defn- run-variant [v]
  (let [sp (chk/spec-from (tcap/checker-input (:decl v) (:ops v)))]
    {:v v :sp sp
     :results (reduce (fn [acc n] (assoc acc n (chk/check-namespace! sp n)))
                      {} namespaces)}))

(defn- all-results [r] (mapcat (fn [n] (get (:results r) n)) namespaces))

(defn- print-lines [r]
  (doseq [n namespaces]
    (let [rs (get (:results r) n)
          bad (filter (fn [x] (seq (:diagnostics x))) rs)]
      (println)
      (println (str "  -- " n "  (" (count rs) " definitions, "
                    (count bad) " with diagnostics) ------------"))
      (doseq [x rs] (println (verdict-line (:sp r) x))))))

(defn- print-diagnostics [r]
  (doseq [n namespaces]
    (doseq [x (filter (fn [x] (seq (:diagnostics x))) (get (:results r) n))]
      (println (str "  === " (:var x)))
      (print (chk/render (:diagnostics x))))))

(defn- sig [r]
  (reduce (fn [acc x] (assoc acc (:var x) (kinds-of x))) {} (all-results r)))

(defn- print-delta [base r]
  (let [b (sig base) c (sig r)
        changed (vec (sort (filter (fn [k] (not= (get b k) (get c k)))
                                   (distinct (concat (keys b) (keys c))))))]
    (println)
    (println (str "  " (count changed) " definition(s) changed verdict against variant "
                  (:label (:v base)) ":"))
    (doseq [k changed]
      (println (str "    " k))
      (println (str "      variant " (:label (:v base)) ": "
                    (if (empty? (get b k)) "[ok]" (get b k))))
      (println (str "      here      : " (if (empty? (get c k)) "[ok]" (get c k)))))
    (println)
    (doseq [k changed]
      (let [x (first (filter (fn [x] (= k (:var x))) (all-results r)))]
        (when (seq (:diagnostics x))
          (println (str "  === " (:var x)))
          (print (chk/render (:diagnostics x))))))))

(defn- print-summary [r]
  (let [all (all-results r)
        bad (filter (fn [x] (seq (:diagnostics x))) all)
        ds  (mapcat :diagnostics all)]
    (println)
    (println (str "  " (count all) " definitions checked; " (count bad)
                  " with diagnostics; " (count ds) " diagnostics."))
    (println)
    (println "  BY CAUSE:")
    (print-tally (tally all) (count ds))
    (println)
    (println "  BY DIAGNOSTIC KIND, PER NAMESPACE:")
    (doseq [n namespaces]
      (println (str "    " n ":"))
      (let [t (kind-tally (get (:results r) n))]
        (if (empty? t)
          (println "      (none)")
          (doseq [e (sort-by (fn [e] (- (second e))) (seq t))]
            (println (str "      " (format "%4d" (second e)) "  " (first e)))))))))

;; ---------------------------------------------------------------------------
;; THE ACID TEST
;; ---------------------------------------------------------------------------
;;
;; E30's decisive evidence was four diagnostics in one `finally` block of
;; `public-client-loopback-contract` — a program the library's own suite runs
;; against a real loopback socket and passes:
;;
;;     (is (true?  (client/close! connection)))
;;     (is (= ::client/closed … (client/receive-at-most! connection 1 …)))
;;     (is (false? (client/close! connection)))
;;     (is (client/closed? connection))
;;     (is (= :closed (:state (client/connection-info connection))))
;;
;; The second `close!`, the `closed?` and the `connection-info` all drew
;; `use-after-move`, and E30's point is that `closed?` and `connection-info` are
;; declared legal in EVERY state and were refused anyway — because
;; `use-after-move` is about the NAME, not about the state. This checks, from
;; the diagnostics the run above actually raised, that each of those is gone and
;; that the leak rule is still live.

(def acid-test-var 'teensyp.client-test/public-client-loopback-contract)

(defn- acid-lines
  "Every line of every diagnostic raised against the acid-test function, EXCEPT
  `unsupported-construct`. That kind names the `try`/`catch` that `clojure.test/is`
  expands to and says nothing about the capability at all — E23's limit, whose
  DENSITY (one per assertion) E30 measured and which no declaration can change.
  Counting it here would report every assertion in the block as a failure."
  [r]
  (let [x (first (filter (fn [x] (= acid-test-var (:var x)))
                         (get (:results r) "teensyp.client-test")))]
    (mapcat :detail (remove (fn [d] (= :unsupported-construct (:kind d)))
                            (:diagnostics x)))))

(defn- mentions? [ls needle]
  (some (fn [l] (and (string? l) (str/includes? l needle))) ls))

(defn- acid-row [ls label needle]
  (let [hit (mentions? ls needle)]
    (println (str "    [" (if hit "NO  " "ok  ") "] " label))))

(defn- print-acid-test [r]
  (let [ls  (acid-lines r)
        all (all-results r)
        ds  (mapcat :diagnostics all)
        uam (filter (fn [d] (= :use-after-move (:kind d))) ds)
        dng (filter (fn [d] (= :dangling (:kind d))) ds)]
    (println)
    (println "  THE ACID TEST — the finally block of public-client-loopback-contract.")
    (println "  Each row is `no CAPABILITY diagnostic names this site`, read off the run")
    (println "  above. `unsupported-construct` is excluded and only that: it names the")
    (println "  try/catch `(is …)` expands to, not the connection.")
    (println)
    (acid-row ls "the compare-and-set second `close!`   (client_test.clj:638)"
              "client_test.clj:638")
    (acid-row ls "`closed?` on a closed connection      (client_test.clj:639)"
              "client_test.clj:639")
    (acid-row ls "`connection-info` after close         (client_test.clj:640)"
              "client_test.clj:640")
    (println)
    (println (str "    " (count uam)
                  "  use-after-move over BOTH namespaces (E30's variant 2 reported 4"))
    (println "       in this one function alone, and 11 under variant 1)")
    (println (str "    " (count dng)
                  "  dangling — a connection that never reaches the destructor is STILL"))
    (println "       a leak, which is the half of this that must not have been weakened;")
    (println "       `perturb.dbtxcorpus/sock-never-closed` is the gated control for it")
    (println "       and it is a REJECT.")))

(defn- banner [s]
  (println)
  (println (str "== " s " " (apply str (repeat (max 0 (- 74 (count s))) "=")))))

(defn -main [& _]
  (let [noise (load-library!)
        nclient (count (pir/defs-in "teensyp.client"))
        ntest   (count (pir/defs-in "teensyp.client-test"))]
    (banner "jolt-tcp's CLIENT, checked — PERTURB-DESIGN §5 ladder step 1a")
    (println "   The library is casselc/jolt-tcp at refs/pr/2. It is unmodified, it is")
    (println "   not a dependency of this project, and its authors have not read the")
    (println "   design record. The declarations are in perturb.tcpcap, written from")
    (println "   outside. NOTHING HERE IS A GATE: no expectation is recorded, and every")
    (println "   verdict below is EVIDENCE ABOUT THE RULES, not about jolt-tcp.")
    (println)
    (println (str "   captured  " nclient " definitions from teensyp.client (773 lines)"))
    (println (str "             " ntest " definitions from teensyp.client-test (642 lines)"))
    (println (str "   jolt.net  name-only stub (dev/tcpstub/jolt/net.clj); every fn throws"))
    (println "   no socket: under strace -e socket,connect,bind,accept,listen this")
    (println "             run makes ZERO such calls.")
    (println)
    (println "   ONE DIAGNOSTIC KIND WILL DOMINATE, AND ITS CAUSE IS ARITHMETIC.")
    (println "   `clojure.test/is` expands to a `try`/`catch` (jolt's stdlib/clojure/")
    (println "   test.clj:223 onward), and client_test.clj contains 96 `(is …)` forms")
    (println "   and 11 explicit `(try …)` forms. E23 recorded try/catch as a limit;")
    (println "   what this measures is its DENSITY — in a test-bearing corpus it is one")
    (println "   `unsupported-construct` per assertion. Of teensyp.client's own 10 real")
    (println "   `try` forms, 9 are reported and the tenth is inside build-connection!,")
    (println "   whose body is an axiom.")
    (println)
    (println "   ONE FORM RAN AT LOAD, AND THAT IS A FINDING ABOUT THE LIBRARY.")
    (println "   teensyp/client_test.clj:204's `testing` block is a TOP-LEVEL form — a")
    (println "   paren at line 202 closes the deftest above it early — so it executes")
    (println "   when the file is loaded and is never registered, run or reported as a")
    (println "   test. Against the name-only stub it fails. Captured verbatim:")
    (doseq [l (str/split-lines (str noise))]
      (when (seq (str/trim l)) (println (str "     | " l))))

    (let [runs (vec (map run-variant tcap/variants))
          v1   (nth runs 0)
          v2   (nth runs 1)]
      ;; --- variant 1, in full ---------------------------------------------
      (banner (str "VARIANT 1 — " (:title (:v v1))))
      (println "   The machine perturb.tcpcap derives from client.clj:516's phase atom,")
      (println "   multiplied out, with :linearity :once and :closed terminal. Every")
      (println "   state-changing operation is :consumes + :produces, because that is")
      (println "   the only way §1.2 has of advancing a machine.")
      (print-lines v1)
      (println)
      (print-diagnostics v1)
      (print-summary v1)

      ;; --- variant 2, in full ---------------------------------------------
      (banner (str "VARIANT 2 — " (:title (:v v2))))
      (println "   Variant 1's rejections MASK the collision this rung exists to force:")
      (println "   the first in-place transition kills the name, so the double `close!`")
      (println "   never gets a diagnostic of its own. Variant 2 declares the largest")
      (println "   sub-machine with no in-place transition — two states, and the only")
      (println "   operation that advances it is the destructor. Half-close, and the")
      (println "   statically-checked `no send-all! after shutdown-write!` guard variant")
      (println "   1 could express, are the price.")
      (print-lines v2)
      (println)
      (print-diagnostics v2)
      (print-summary v2)

      ;; --- variants 3 and 4, as deltas against variant 2 -------------------
      (doseq [r (subvec runs 2 4)]
        (banner (str "VARIANT " (:label (:v r)) " — " (:title (:v r))))
        (when (= "4" (:label (:v r)))
          (println "   THIS VARIANT'S VERDICTS CHANGED, AND THAT IS THE POINT. E30 ran it")
          (println "   against a checker that ACCEPTED `:arg` on a `:produces` entry and")
          (println "   SILENTLY IGNORED it — tally row 50 — and recorded 4 use-after-move")
          (println "   plus one new no-signature, because the produced capability landed on")
          (println "   the return value and clojure.core/true? received a Connection@:closed.")
          (println "   The key is implemented now. The DECLARATION is byte-for-byte the one")
          (println "   E30 ran; only what the checker does with it has changed."))
        (print-delta v2 r)
        (print-summary r))

      ;; --- variant 5, in full, against variant 1 ---------------------------
      (let [v5 (nth runs 4)]
        (banner (str "VARIANT 5 — " (:title (:v v5))))
        (println "   E33's conceptual correction, implemented: a stable shared handle, its")
        (println "   current protocol state, and the obligation to discharge the underlying")
        (println "   resource are THREE DIFFERENT THINGS, and this declaration says all")
        (println "   three separately. The MACHINE is variant 1's machine — nothing deleted,")
        (println "   nothing invented — plus `:result` labels; the ANNOTATIONS stop repeating")
        (println "   it and stop claiming return values these operations do not have.")
        (print-lines v5)
        (println)
        (print-diagnostics v5)
        (print-summary v5)
        (println)
        (println "  --- against VARIANT 1, which is the same machine under `:linearity :once`:")
        (print-delta v1 v5)
        (print-acid-test v5)))

    (banner "READ THESE AS EVIDENCE ABOUT THE RULES")
    (println "   jolt-tcp's client works. Its own suite exercises this exact lifecycle")
    (println "   over a real loopback socket and passes, under the compiler it targets.")
    (println "   Every rejection above is the checker refusing a program that works, and")
    (println "   every clean verdict above is either a function that never touches the")
    (println "   capability or a body that is an axiom.")
    (println "   A declaration checked against a library is not the library verified.")
    (println)
    (System/exit 0)))
