(ns perturb.corpus
  "The acceptance corpus for `perturb.check`. REAL perturb source, compiled by
  Jolt, checked from the IR the back end was handed — not a model, not a list of
  operation names.

  The point is what the CHECKER says about each one, and `expectations` below
  records what it must say. A corpus entry that flips verdict is a regression in
  the checker or a change in the rule set; either way the gate fails.

  Intended rejections and intended acceptances are interleaved deliberately: the
  interesting pairs differ by one line.

  EVERY ACCEPT IS ALSO RUN. This corpus's first version claimed each entry
  \"would run\" and none was ever executed; E15 found that the accept set threw,
  because §1.2's then-unpositioned `:produces` forced the checker to model
  `request` as returning the connection bare when it really returns
  `[conn frames]`. The rule set now has positions, and the gate now EXECUTES
  every accepted program under the scripted handler. A checker that accepts a
  program that cannot run is not a weaker checker — it is a wrong one, and only
  running it says which.")

(require '[perturb.nrepl :as n])
(require '[perturb.cap :as cap])

;; ===========================================================================
;; ACCEPT
;; ===========================================================================

(defn open-request-close
  "The straight-line correct path. `request` produces the connection at position
  0 of a pair, so the caller projects it back out with `first` — the tuple
  eliminator — and closes that."
  [host port code]
  (let [c  (n/open host port)
        r  (n/request c {"op" "eval" "code" code})
        c1 (first r)
        c2 (n/close! c1)]
    :done))

(defn shadowed-rebind
  "The same correct path written the way people actually write it — one name,
  rebound. Each binding occurrence is a DIFFERENT capability instance; `:local`
  cannot tell them apart, and the checker's own binding ids must."
  [host port code]
  (let [c (n/open host port)
        c (first (n/request c {"op" "eval" "code" code}))
        c (n/close! c)]
    :done))

(defn both-arms-close
  "controlflow.py's `same-move-in-both-arms`: an agreed join carries the state
  forward soundly."
  [host port flag code]
  (let [c  (n/open host port)
        c1 (if flag (n/close! c) (n/close! c))]
    :done))

(defn loop-of-requests
  "controlflow.py's `loop-of-region-reads`, on a real driver shape: the body is
  environment-preserving at the back edge (active in, active out), so it
  iterates. The `first` is what keeps it preserving — recurring with the raw
  pair would change the binding's capability SHAPE, and is `loop-shape-drift`
  below."
  [host port codes]
  (let [c0 (n/open host port)
        c1 (loop [c c0 fs codes]
             (if (empty? fs)
               c
               (recur (first (n/request c {"op" "eval" "code" (first fs)}))
                      (rest fs))))
        c2 (n/close! c1)]
    :done))

(defn ping
  "A DERIVED operation: annotated, but not a transition of the declared machine,
  so the checker checks its body against its own annotation instead of believing
  it. It returns the connection BARE — `:produces` with no `:at`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active :arg 0}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active}]}}
  [c]
  (first (n/request c {"op" "eval" "code" "(+ 1 1)"})))

(cap/annotate-op! (var ping) (:perturb.cap/op (meta (var ping))))

(defn uses-ping
  "Composition, through a helper that returns the capability bare."
  [host port]
  (let [c  (n/open host port)
        c1 (ping c)
        c2 (n/close! c1)]
    :done))

(defn ping-tuple
  "THE E15 PAIR, other half. Same function as `ping`, but it returns the pair the
  real client returns — and now it can SAY so. Before positions this was a
  rejection and `ping` was an acceptance, though the two return the same shape;
  that distinction was syntactic and is what E15 refuted."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active :arg 0}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active :at [0]}]}}
  [c]
  (let [c1 (first (n/request c {"op" "eval" "code" "1"}))]
    [c1 :pinged]))

(cap/annotate-op! (var ping-tuple) (:perturb.cap/op (meta (var ping-tuple))))

(defn uses-ping-tuple
  "Composition through a helper that returns the capability INSIDE a pair. This
  is the shape `perturb.nrepl/clone-session` and `/eval-code` are written in, and
  the shape the checker could not express at all until `:at` existed."
  [host port]
  (let [r  (ping-tuple (n/open host port))
        c1 (first r)
        c2 (n/close! c1)]
    :done))

(defn ping-with-code-first
  "ACCEPT. The capability is parameter 1 and an ordinary value is parameter 0.
  `:arg 1` is the only thing that says so, and since the in-order fallback was
  removed it is the only thing that CAN. Written as the positive half of the pair
  with `unpositioned-ping` below, which is this function with `:arg` deleted."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active :arg 1}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active :at [0]}]}}
  [code c]
  (let [c1 (first (n/request c {"op" "eval" "code" code}))]
    [c1 :pinged]))

(cap/annotate-op! (var ping-with-code-first)
                  (:perturb.cap/op (meta (var ping-with-code-first))))

(defn uses-ping-with-code-first
  "ACCEPT, and it RUNS. Composition through a helper whose capability is not its
  first parameter."
  [host port]
  (let [r  (ping-with-code-first "(+ 1 1)" (n/open host port))
        c1 (first r)
        c2 (n/close! c1)]
    :done))

;; ===========================================================================
;; REJECT
;; ===========================================================================

(defn unpositioned-ping
  "REJECT — AND THE DIAGNOSTIC NAMES THE ANNOTATION. `ping-with-code-first` with
  `:arg 1` deleted from its `:consumes` entry.

  The checker used to match specs to parameters IN ORDER when `:arg` was absent,
  which bound the STRING `code` to the Connection spec and left `c` untracked.
  Measured against the previous checker, this function drew FOUR diagnostics —
  escape, untracked-consume, produces-mismatch, dangling — of which the first
  reads

      escape  perturb.nrepl/Connection
        capability    `code` : perturb.nrepl/Connection@:active
        enters a map at perturb/src/perturb/corpus.clj:158:19

  naming a parameter that is a string and a line that is `{\"op\" \"eval\" …}`.
  None of the four named the annotation. That is E18 finding 1(d)'s
  two-capability result reproduced on one capability, which §1.2 had called
  merely unprincipled.

  The fallback is removed: an entry without `:arg` is refused where it is
  written, and the body is not checked at all, because a refused annotation is
  not a specification to check a body against. One diagnostic, and it names the
  annotation."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active :at [0]}]}}
  [code c]
  (let [c1 (first (n/request c {"op" "eval" "code" code}))]
    [c1 :pinged]))

(cap/annotate-op! (var unpositioned-ping)
                  (:perturb.cap/op (meta (var unpositioned-ping))))

(defn use-after-close
  "INHERITED I16, verbatim: `(let [c (open …)] (close! c) (request c …))`
  compiles and runs on Jolt today. This is the program the checker exists to
  refuse."
  [host port]
  (let [c (n/open host port)]
    (n/close! c)
    (n/request c {"op" "eval" "code" "(+ 1 2)"})
    :done))

(defn double-close
  "Second close! from :closed — a typestate violation, not a linearity one,
  because the successor was bound."
  [host port]
  (let [c  (n/open host port)
        c1 (n/close! c)
        c2 (n/close! c1)]
    :done))

(defn use-after-move
  "E6 probe 2: capabilities bind affinely. `(let [d c] …)` is a MOVE, so `c` is
  dead afterwards even though nothing consumed it."
  [host port]
  (let [c  (n/open host port)
        d  c
        c1 (n/close! d)
        c2 (n/request c {"op" "eval" "code" "1"})]
    :done))

(defn dangling-connection
  "linearity :once plus a declared terminal state: a connection that never
  reaches :closed leaks at scope exit."
  [host port]
  (let [c (n/open host port)]
    :opened))

(defn drops-the-connection
  "POSITIONS, ON THE LEAK SIDE. The pair is bound and only its SECOND position is
  used; the connection at position 0 is never closed. Nothing here mentions a
  connection after the request — the leak is visible only because the checker
  tracks capabilities per position."
  [host port]
  (let [c      (n/open host port)
        r      (n/request c {"op" "eval" "code" "1"})
        frames (second r)]
    frames))

(defn shadowing-hides-a-leak
  "THE BINDING-IDENTITY CASE. The inner `c` shadows the outer one and is the one
  that gets closed. A checker keyed on the NAME `c` sees a close and accepts;
  the outer connection leaks. §2.1 predicted this from reading ir.clj — here it
  is on running code."
  [host port]
  (let [c (n/open host port)]
    (let [c (n/open host port)]
      (n/close! c)
      :done)))

(defn conditional-close
  "E6 probe 1's known usability risk, on a real connection: `if (flag) close(c)`
  leaves the capability maybe-moved, and \"maybe moved\" is not a mode."
  [host port flag]
  (let [c (n/open host port)]
    (if flag (n/close! c) nil)
    :done))

(defn conditional-close-then-use
  "The same shape §1.2 names verbatim — `if (c) { b = detach_result(b) };
  use(b)` — written with a connection. Rejected at the join, before the use."
  [host port flag]
  (let [c (n/open host port)]
    (if flag (n/close! c) nil)
    (n/request c {"op" "eval" "code" "1"})
    :done))

(defn loop-that-closes
  "controlflow.py's `loop-that-moves`: the back edge re-enters at :closed, so the
  body cannot run twice."
  [host port k]
  (let [c0 (n/open host port)]
    (loop [c c0 i k]
      (if (zero? i)
        :done
        (recur (n/close! c) (dec i))))))

(defn loop-shape-drift
  "POSITIONS, ON THE LOOP RULE. `loop-of-requests` without the `first`: the
  binding enters holding the connection BARE and re-enters holding it at
  position 0 of a pair. Same capability, same state, different SHAPE — and the
  body therefore cannot run twice. Before positions the checker could not see
  this at all, because it modelled the pair as the connection."
  [host port codes]
  (let [c0 (n/open host port)
        c1 (loop [c c0 fs codes]
             (if (empty? fs)
               c
               (recur (n/request c {"op" "eval" "code" (first fs)}) (rest fs))))
        c2 (n/close! c1)]
    :done))

(defn helper-without-a-signature
  "An ordinary function that takes a connection. Interprocedural flow is by
  annotation only, so the parameter is not a tracked capability and the
  operation has nothing legal to consume. `perturb.nrepl/clone-session` used to
  be exactly this; it now carries a positioned signature and checks."
  [c]
  (n/request c {"op" "eval" "code" "1"}))

(defn wrong-position
  "POSITIONS, ON THE ANNOTATION SIDE. Declares the connection at position 0 and
  returns it at position 1. The body and the annotation disagree, and that is
  now a statement the language can make — which is the whole point of `:at`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active :arg 0}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active :at [0]}]}}
  [c]
  (let [c1 (first (n/request c {"op" "eval" "code" "1"}))]
    [:pinged c1]))

(cap/annotate-op! (var wrong-position) (:perturb.cap/op (meta (var wrong-position))))

(defn connection-into-a-map
  "A tuple position can be named by an annotation; a map key cannot. The
  capability enters a value the checker cannot follow it out of."
  [host port]
  (let [c (n/open host port)]
    {:conn c}))

(defn capture-in-closure
  "A `linearity :once` capability closed over by a fn that may run any number of
  times."
  [host port codes]
  (let [c (n/open host port)]
    (map (fn [f] (n/request c {"op" "eval" "code" f})) codes)))

;; ===========================================================================
;; what the checker must say — and, for every accept, that it RUNS
;; ===========================================================================

(def expectations
  "var -> the verdict the gate requires; for a rejection, at least one diagnostic
  kind that must appear; for an acceptance, `:run` gives the arguments the gate
  calls it with under the scripted handler. An accept with no `:run` is one that
  needs a live capability as an argument and so cannot be called standalone —
  each of those is exercised through a caller that IS run."
  [{:var 'perturb.corpus/open-request-close :expect :accept
    :run ["in-memory" 0 "(+ 1 1)"]}
   {:var 'perturb.corpus/shadowed-rebind    :expect :accept
    :run ["in-memory" 0 "(+ 1 1)"]}
   {:var 'perturb.corpus/both-arms-close    :expect :accept
    :run ["in-memory" 0 true "(+ 1 1)"]}
   {:var 'perturb.corpus/loop-of-requests   :expect :accept
    :run ["in-memory" 0 ["(+ 1 1)" "(+ 2 2)"]]}
   {:var 'perturb.corpus/ping               :expect :accept}       ;; run via uses-ping
   {:var 'perturb.corpus/uses-ping          :expect :accept :run ["in-memory" 0]}
   {:var 'perturb.corpus/ping-tuple         :expect :accept}       ;; run via uses-ping-tuple
   {:var 'perturb.corpus/uses-ping-tuple    :expect :accept :run ["in-memory" 0]}
   ;; run via uses-ping-with-code-first
   {:var 'perturb.corpus/ping-with-code-first :expect :accept}
   {:var 'perturb.corpus/uses-ping-with-code-first :expect :accept :run ["in-memory" 0]}

   {:var 'perturb.corpus/use-after-close        :expect :reject :kind :use-after-move}
   {:var 'perturb.corpus/double-close           :expect :reject :kind :typestate}
   {:var 'perturb.corpus/use-after-move         :expect :reject :kind :use-after-move}
   {:var 'perturb.corpus/dangling-connection    :expect :reject :kind :dangling}
   {:var 'perturb.corpus/drops-the-connection   :expect :reject :kind :dangling}
   {:var 'perturb.corpus/shadowing-hides-a-leak :expect :reject :kind :dangling}
   {:var 'perturb.corpus/conditional-close      :expect :reject :kind :join}
   {:var 'perturb.corpus/conditional-close-then-use :expect :reject :kind :join}
   {:var 'perturb.corpus/loop-that-closes       :expect :reject :kind :loop-not-preserving}
   {:var 'perturb.corpus/loop-shape-drift       :expect :reject :kind :loop-not-preserving}
   {:var 'perturb.corpus/helper-without-a-signature :expect :reject :kind :untracked-consume}
   {:var 'perturb.corpus/wrong-position         :expect :reject :kind :produces-mismatch}
   {:var 'perturb.corpus/connection-into-a-map  :expect :reject :kind :escape}
   {:var 'perturb.corpus/capture-in-closure     :expect :reject :kind :capture}
   {:var 'perturb.corpus/unpositioned-ping      :expect :reject
    :kind :annotation-unpositioned}])
