(ns perturb.effect
  "Declared effects with handlers.

  PERTURB-DESIGN §1.4 (charter D4, retained on E4's evidence): `Effects
  substitute a validated result or abort; no continuations at that layer.`
  §1.1: the `perform` boundary `must remain a real call site with durable
  identity rather than being inlined at analysis time`, so D3 stays cheap to add.

  WHAT THIS IS. An effect is DATA: a name plus an op table, where each op
  declares its arity and a predicate its result must satisfy. `perform` looks up
  the op, calls the installed handler, and either returns a result that passed
  the declared predicate or aborts. A handler cannot resume, cannot capture the
  continuation, and cannot return an unvalidated value.

  WHAT THIS IS NOT — and this is the honest part. Handler installation is a
  Clojure dynamic var (INHERITED I3). §1.1 wants effects carried as a ROW on the
  signature, checked by the analyzer, with `:extern` replacing the untyped host
  escapes. None of that exists. So an unhandled effect is a runtime abort where
  perturb wants a type error, and nothing prevents a perturb function from
  performing an effect its callers do not know about. The mechanism here is the
  dynamic-extent shape of the right thing with none of the static discipline.

  DURABLE SITE IDENTITY. Every `perform` takes an explicit `site` keyword written
  as a literal at the call site. It is not a pass-attached annotation — charter
  rejected-alternative A1 says identity is not durable through passes, and §1.1
  carries that forward to the effect boundary. Writing it by hand is the cheapest
  possible durable spine and it makes the trace legible.")

;; --- declaring an effect ----------------------------------------------------

(defn effect
  "Declare an effect. `ops` maps op keyword -> {:arity n :result-pred f :doc s}."
  [nm ops]
  {:perturb.effect/name nm
   :perturb.effect/ops  ops})

(defn ops [eff] (:perturb.effect/ops eff))
(defn effect-name [eff] (:perturb.effect/name eff))

;; --- handler installation (INHERITED I3) ------------------------------------

(def ^:dynamic *handlers*
  "effect-name -> (fn [op site args] -> [:ok v] | [:abort reason-map])"
  {})

(def ^:dynamic *trace*
  "An atom collecting performed operations, or nil. §1.4's single-nondeterminism
  -source model wants exactly this: the trace is the sequence of answers, and
  replay is feeding them back. perturb.script does the feeding-back half."
  nil)

(defmacro with-handlers
  [m & body]
  `(binding [perturb.effect/*handlers* (merge perturb.effect/*handlers* ~m)]
     ~@body))

(defmacro with-trace
  [a & body]
  `(binding [perturb.effect/*trace* ~a] ~@body))

;; --- run state and the latch (E26 finding 3) --------------------------------
;;
;; WHAT CHANGED AND WHY. E16 measured that a scripted run performed no syscall
;; outside the handler, with a positive control. Its own nonclaim 2 conceded the
;; limit: attribution was BY INSTRUMENT, over the five (now nine) declared
;; bindings a counter enumerated, so a sixth path was invisible to it.
;; `RUNTIME-OBLIGATION-BRIEF` then rested monitor soundness on that sampled
;; window. `jolt.sim.runtime:1085-1135` shows the stronger posture: do not
;; measure the boundary, FAIL CLOSED at it, and LATCH the failure in run state
;; so application code that catches the exception cannot make the run be
;; reported as successful. That is what the three definitions below are.
;;
;; The latch is the load-bearing half. A fail-closed throw on its own is worth
;; little, because `(try ... (catch :default _ ...))` around it restores exactly
;; the old situation. `latch!` writes BEFORE the throw and nothing in this
;; namespace removes an entry: there is no un-latch, and `report` is a pure read
;; of the atom the run was started with.
;;
;; WHAT THIS IS NOT. It is not `proved`. It is a per-run invariant over the runs
;; that actually EXECUTE — the charter's lattice has no better word for it than
;; a per-run invariant sitting above `monitored`, and it says nothing about a
;; run nobody performed.

(def ^:dynamic *run*
  "This run's boundary state, an atom, or nil.

  `{:perturb.effect/faults [] :perturb.effect/required #{} :perturb.effect/seen #{}
    :perturb.effect/performs 0 :perturb.effect/natives [] :perturb.effect/orphans-at n}`"
  nil)

(def ^:dynamic *handling*
  "The effect descriptor whose handler is executing on THIS thread, or nil.

  Bound by `perform` for exactly the dynamic extent of the handler call, and
  read by `native!`. This is perturb's analogue of jolt-sim's registered
  handler for an intercepted descriptor: a native crossing with this nil has no
  handler accounting for it, wherever in perturb it was written."
  nil)

(def ^:private orphan-faults
  "Latched faults raised with no `*run*` in dynamic extent.

  Process-global, and INHERITED I10 applies to it exactly as it applies to
  `perturb.cap/ledger`. It exists so that a fail-closed throw off the run's
  thread (or before any run was started) is still counted somewhere rather than
  disappearing; `report` charges a run for the orphans that appeared during it.
  A thread that outlives the run and latches afterwards is charged to nobody —
  see the nonclaims in the E26 write-up."
  (atom []))

(defn new-run
  "A fresh run-state atom.

  `required` is the set of symbols that must appear in this run for its report
  to pass. Without it a run that performed no effect at all would satisfy
  `all-handled?` vacuously, which is the same defect as a monitor that never
  fires. Symbols are `op-symbol` results for effect ops and, for native
  crossings, whatever `native!` was called with."
  [required]
  (atom {:perturb.effect/faults     []
         :perturb.effect/required   (set required)
         :perturb.effect/seen       #{}
         :perturb.effect/performs   0
         :perturb.effect/natives    []
         :perturb.effect/orphans-at (count @orphan-faults)}))

(defmacro with-run
  [a & body]
  `(binding [perturb.effect/*run* ~a] ~@body))

(def latching-aborts
  "The abort kinds that are BOUNDARY failures rather than handler decisions.

  `:handler-abort` is deliberately absent. A handler returning `[:abort ...]` —
  a refused `connect`, a `recv` on a listener — is a declared outcome of the
  effect, catchable by the caller, and latching it would make every negative
  test in the artifact fail the run. jolt-sim draws the same line at
  `runtime.clj:1110-1117`: a proceed failure stays an ordinary exception and
  must not poison the controller latch."
  #{:unhandled-effect :unhandled-native-effect})

(defn latch!
  "Record a boundary failure in run state. Append-only; there is no un-latch."
  [kind data]
  (let [entry (assoc data :perturb.effect/abort kind)]
    (if *run*
      (swap! *run* update :perturb.effect/faults conj entry)
      (swap! orphan-faults conj entry))
    entry))

(defn- observe!
  "Note that `sym` was reached inside this run."
  [sym]
  (when *run*
    (swap! *run* update :perturb.effect/seen conj sym)))

(defn op-symbol
  "The required-set name of one op of one effect: `perturb.wire/socket.recv`."
  [effect-nm op]
  (symbol (namespace effect-nm) (str (name effect-nm) "." (name op))))

(defn report
  "The per-run verdict.

  `:perturb.effect/all-handled?` is true only if every one of the following
  holds, and it is a conjunction on purpose:

    - the run latched no boundary fault (nothing reached the boundary with no
      handler, whether or not the caller caught the throw);
    - no orphan fault appeared during the run (a fail-closed throw raised with
      no run state in scope — off-thread, or outside `with-run`);
    - every required symbol was actually reached, so the run was not vacuous.

  Everything it reports is about crossings that HAPPENED. It is silent about
  code that did not run."
  [run]
  (let [s       @run
        seen    (:perturb.effect/seen s)
        missing (set (remove seen (:perturb.effect/required s)))
        orphans (- (count @orphan-faults) (:perturb.effect/orphans-at s))]
    {:perturb.effect/all-handled?  (and (empty? (:perturb.effect/faults s))
                                        (empty? missing)
                                        (zero? orphans))
     :perturb.effect/faults        (:perturb.effect/faults s)
     :perturb.effect/orphan-faults orphans
     :perturb.effect/performs      (:perturb.effect/performs s)
     :perturb.effect/natives       (frequencies (map :perturb.effect/symbol
                                                     (:perturb.effect/natives s)))
     :perturb.effect/required      (:perturb.effect/required s)
     :perturb.effect/missing       missing}))

;; --- aborting (INHERITED I4) ------------------------------------------------

(defn abort!
  "Abort. Boundary failures (`latching-aborts`) are written into run state
  BEFORE the throw, so catching the exception cannot undo the record."
  [kind data]
  (when (contains? latching-aborts kind)
    (latch! kind data))
  (throw (ex-info (str "perturb.effect: abort " kind)
                  (assoc data :perturb.effect/abort kind))))

;; --- the native crossing gate -----------------------------------------------

(defn native!
  "The gate every native crossing in perturb must pass BEFORE it crosses.

  `sym` names the C entry point about to be resolved and called. If no handler
  is executing on this thread, this crossing has no registered handler: latch
  and throw, before the FFI call, before any OS access. Fail closed — there is
  no fall-through to the host.

  Returns `sym` so it can be threaded into the call site."
  [sym]
  (when (nil? *handling*)
    (abort! :unhandled-native-effect
            {:perturb.effect/symbol sym
             :perturb.effect/site   :perturb.effect/no-handler-in-extent}))
  (when *run*
    (swap! *run* update :perturb.effect/natives conj
           {:perturb.effect/symbol sym :perturb.effect/handling *handling*}))
  (observe! sym)
  sym)

;; --- performing -------------------------------------------------------------

(defn perform
  "Perform `op` of `eff` at `site` with `args` (a vector).

  Returns the handler's result if and only if it satisfies the op's declared
  result predicate. Otherwise aborts. There is no third outcome: no resumption,
  no continuation capture, no handler-supplied control flow."
  [eff op site args]
  (let [nm   (effect-name eff)
        decl (get (ops eff) op)]
    (when (nil? decl)
      (abort! :unknown-op {:perturb.effect/effect nm :perturb.effect/op op :perturb.effect/site site}))
    (when (not= (count args) (:arity decl))
      (abort! :arity {:perturb.effect/effect nm :perturb.effect/op op :perturb.effect/site site
                      :perturb.effect/expected (:arity decl)
                      :perturb.effect/actual (count args)}))
    (let [h (get *handlers* nm)]
      (when (nil? h)
        ;; §1.1's `:extern` with an effect row would make this a compile error.
        ;; Until then it is a fail-closed abort, and `abort!` latches it: an
        ;; effect that reached the boundary with no handler cannot be caught
        ;; away.
        (abort! :unhandled-effect {:perturb.effect/effect nm :perturb.effect/op op
                                   :perturb.effect/site site}))
      ;; Observed HERE, not after validation: the required set exists to prove
      ;; the run was not vacuous, and "this op reached the boundary and found a
      ;; registered handler" is the fact it needs. A handler that then aborts
      ;; still crossed the boundary.
      (observe! (op-symbol nm op))
      (when *run* (swap! *run* update :perturb.effect/performs inc))
      (let [reply (binding [*handling* {:perturb.effect/effect nm
                                        :perturb.effect/op op
                                        :perturb.effect/site site}]
                    (h op site args))]
        (cond
          (not (vector? reply))
          (abort! :malformed-handler-reply {:perturb.effect/effect nm :perturb.effect/op op
                                            :perturb.effect/site site :perturb.effect/reply reply})

          (= :abort (first reply))
          (abort! :handler-abort {:perturb.effect/effect nm :perturb.effect/op op
                                  :perturb.effect/site site :perturb.effect/reason (second reply)})

          (not= :ok (first reply))
          (abort! :malformed-handler-reply {:perturb.effect/effect nm :perturb.effect/op op
                                            :perturb.effect/site site :perturb.effect/reply reply})

          :else
          (let [v    (second reply)
                pred (:result-pred decl)]
            (if (and pred (not (pred v)))
              ;; This is the `validated` in `substitute a validated result`. A
              ;; handler that lies about its result type is stopped here, not
              ;; downstream in the codec.
              (abort! :invalid-result {:perturb.effect/effect nm :perturb.effect/op op
                                       :perturb.effect/site site :perturb.effect/value v})
              (do
                (when *trace*
                  (swap! *trace* conj {:perturb.effect/effect nm
                                       :perturb.effect/op op
                                       :perturb.effect/site site
                                       :perturb.effect/args args
                                       :perturb.effect/result v}))
                v))))))))
