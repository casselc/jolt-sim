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

;; --- the LAYERING EVENT LOG (PERTURB-DESIGN E33's B6) -----------------------
;;
;; WHY `*trace*` IS NOT ENOUGH, WHICH IS THE WHOLE REASON THIS SECTION EXISTS.
;; `*trace*` appends ONE record per SUCCESSFUL perform, flat, in time order. Read
;; it and you can say how many operations happened and at which sites. You cannot
;; say:
;;
;;   - WHICH operation caused which. E29's stack is a TREE — one `perturb.http`
;;     `recv` causes 154 `perturb.tlsish/under-recv`s — and a flat list has no
;;     edge to hang that on. Every clause of B6 is about that edge.
;;   - THAT AN OPERATION WAS REFUSED. A `[:abort ...]` reply never reaches
;;     `*trace*`, so the one fact E29's control 2 is about — a refusal below
;;     became a success above — is invisible in the artifact that records the run.
;;   - WHICH HANDLER ANSWERED. `perform` looks a handler up and calls it and the
;;     identity of the thing it called is not written down anywhere, so `a layer
;;     intercepted its own forwarded operation` cannot be stated, let alone
;;     checked. That is E29 finding 1 (`via` supplies the handler-pop by hand and
;;     NOTHING checks it) with nothing to check it WITH.
;;
;; So: a second, opt-in channel. `*trace*` is untouched — same records, same
;; order, same keys — because four namespaces read it and none of them should
;; have to change. `*events*` is nil unless a caller binds it, and when it is nil
;; every addition below costs one `nil` test per perform and allocates nothing.
;;
;; WHAT IS RECORDED, AND IT IS DELIBERATELY FOUR EVENT KINDS AND NO MORE:
;;
;;   :request  an operation was ATTEMPTED at the boundary and a handler was
;;             selected (or was found MISSING, in which case `:handler` is nil).
;;             Carries its own `:id`, its `:parent` — the id of the request whose
;;             handler was executing when it was attempted — its `:depth`, its
;;             ROUTE, the `:handler` OBJECT that answered it, and the
;;             outward-operation instance it was forwarded with, if any.
;;   :reply    that request returned a validated result.
;;   :refusal  that request was refused, with the abort kind. This is the event
;;             `*trace*` structurally cannot have.
;;   :mint     an OUTWARD-OPERATION INSTANCE was created. See `outward!`.
;;   :consume  an outward-operation instance was presented to `forward!`. Written
;;             BEFORE the admissibility tests, so a forward that is REFUSED for
;;             reusing an instance still leaves the reuse in the trace.
;;   :finalize a rung's finalizer ran, with the outcome that reached it.
;;
;; THE ROUTE FIELD, AND WHY IT IS NOT COSMETIC
;; (PRACTICAL-ADOPTION-AND-SIM-LEARNINGS §A3, nonclaims):
;;
;;   > The native `proceed` and a layer `forward` are different mechanisms and
;;   > must remain different trace routes.
;;
;; `perturb.effect` has a dynamic operation vocabulary and implicit tail
;; resumption with no captured continuation. Jolt's native `proceed` seam is
;; separately scoped, owner-thread-bound, LIFO and one-shot. They are not the
;; same thing and a trace that spelled them the same way could not distinguish
;; `the layer forwarded' from `the runtime resumed'. So every request carries
;; `:perturb.effect/route`:
;;
;;   :perform  application code (or any code that is not a rung forwarding
;;             outward) crossed the boundary; the handler came from `*handlers*`.
;;   :forward  a RUNG forwarded outward to its NAMED OUTER INSTANCE. The handler
;;             did not come from `*handlers*` and no effect name was rebound.
;;   :proceed  RESERVED AND NEVER EMITTED HERE. perturb has no native proceed.
;;             `perturb.layer` asserts its absence rather than assuming it, so
;;             that routing a resumption through this log later is a DETECTED
;;             merge instead of a silent one.
;;
;; `:parent` IS THE CORRELATION AND IT IS FREE. `*frame*` is bound for exactly
;; the extent of the handler call, in the same `binding` form as `*handling*`,
;; so a perform issued by a handler is a child of the request that handler is
;; serving and a perform issued by application code is a root. No handler has to
;; cooperate, declare anything, or be rewritten for this to be true.
;;
;; WHAT THIS IS NOT. It is a record of crossings that HAPPENED. It says nothing
;; about a path not taken, and a layer that composes by CALLING the rung below
;; (`perturb.tlsish/*call-over-call*`) produces no events for the crossings it
;; made — which is not a blind spot but the point: that ABSENCE is what E29's
;; control 3 is, and `perturb.layer` reads it as a violation rather than as
;; silence.

(def ^:dynamic *events*
  "An atom collecting layering events, or nil. `perturb.layer` is the consumer."
  nil)

(def ^:dynamic *frame*
  "The layering-event descriptor of the request whose handler is executing on
  THIS thread, or nil.

  `*handling*` already carries effect/op/site for `native!`'s benefit; this
  carries the one thing `*handling*` does not and cannot be given without
  changing what `native!` reads — an IDENTITY, so a nested perform can name its
  parent. Kept separate for that reason rather than merged into `*handling*`."
  nil)

(def ^:dynamic *event-mark*
  "A thunk returning an opaque mark stamped on every layering event, or nil.

  perturb.effect does not know what capabilities are and must not learn. The
  consumer supplies a clock — `perturb.layer` binds this to the length of
  `perturb.cap/ledger` — so a request's extent can be projected onto the
  transitions taken inside it WITHOUT this namespace acquiring a dependency on
  the capability tier."
  nil)

(def ^:dynamic *outward*
  "The OUTWARD-OPERATION INSTANCE the next forward on this thread will carry, or
  nil. Bound to nil for the extent of every handler call, so an instance is
  consumed by exactly the one forward it was minted for unless a layer goes out
  of its way to re-supply it — which is precisely the shape E33 records Tang as
  REJECTING, and precisely what `perturb.layer`'s clause 4 looks for."
  nil)

(def ^:dynamic *owner*
  "The OWNER TOKEN of the thread of control that may use this rung's instances.

  §A3's controls require `wrong-thread ... use of proceed must fail`. perturb has
  never run on two threads (E29 nonclaim 1) and Chez threading is not something
  this artifact is going to acquire to make a control green, so the ownership is
  represented by a TOKEN rather than by a thread id: an instance records the
  token in force when it was minted, and `forward!` refuses when the token in
  force differs. Rebinding this var is what a second thread of control WOULD
  change, so the control exercises the CHECK honestly and claims nothing about
  threading. Said in `perturb.layer/report-limits` too, so it cannot be misread
  from the passing line alone."
  :perturb.effect/main)

(def ^:dynamic *finalizing*
  "True for exactly the dynamic extent of a rung's finalizer. §A3: a finalizer
  `may report transfer/discharge but may not retry native proceed`. perturb's
  analogue of retrying a proceed is FORWARDING, so `forward!` refuses while this
  is true."
  false)

(def ^:private event-counter
  "One counter for request ids, handler-instance ids and outward-operation
  instance ids, so no id of any kind is ever confusable with another."
  (atom 0))

(def ^:private seq-counter
  "Monotone sequence number stamped on EVERY event. Dense and strictly
  increasing within a run, which is what makes a dropped or reordered record
  detectable — see `perturb.layer`'s replay-coherence check (§A3: `a
  forged/reordered projected trace must fail replay/coherence`)."
  (atom 0))

(def ^:private attempt-counter
  "Dense ordinal over ATTEMPTED OPERATIONS ONLY, stamped on `:request` events.

  Separate from `seq-counter` because the two answer different questions and one
  counter cannot answer both. `:seq` orders EVERY event, so it is sparse across
  requests — mints, consumes and replies sit between them — and a projection with
  one record per attempt has gaps in it by construction. `:attempt` is 1..N with
  nothing between, so a DROPPED record is a hole rather than a smaller gap.
  Detecting a dropped record was §A3's `a forged/reordered projected trace must
  fail replay/coherence` and it is the one forgery a sparse ordering cannot see."
  (atom 0))

(defn reset-event-counters!
  "Restart the sequence and attempt numbering. Called by `perturb.layer/record!`
  so that a run's canonical trace starts at 1 and its density is checkable."
  []
  (reset! seq-counter 0)
  (reset! attempt-counter 0))

(defmacro with-events
  [a & body]
  `(binding [perturb.effect/*events* ~a] ~@body))

(defmacro with-event-mark
  [f & body]
  `(binding [perturb.effect/*event-mark* ~f] ~@body))

(defmacro with-outward
  [d & body]
  `(binding [perturb.effect/*outward* ~d] ~@body))

(defmacro with-owner
  [t & body]
  `(binding [perturb.effect/*owner* ~t] ~@body))

(defn- emit!
  "Append one layering event, stamped with a dense sequence number and the
  consumer's mark. Inert when no event log is bound."
  [ev]
  (when *events*
    (swap! *events* conj
           (assoc ev :perturb.effect/seq  (swap! seq-counter inc)
                     :perturb.effect/mark (if *event-mark* (*event-mark*) nil))))
  ev)

;; --- handler instances and the named outer instance (§A3) -------------------
;;
;; THE MECHANISM, AND WHY THIS ONE. E33's first framing says the
;; adapter-self-interception clause `needs explicit effect instances or labels
;; rather than a convention every layer author holds`, and offers three ways to
;; get one. PRACTICAL-ADOPTION-AND-SIM-LEARNINGS §A3 answers the same question
;; independently and more concretely: `Give a Perturb handler instance an
;; explicit identity, named outer instance, and finalize!; make forwarding a
;; distinct operation which targets that outer instance RATHER THAN REBINDING
;; THE SAME NAME.` That is what is built here, and it is strictly stronger than
;; the alternative this work started with (record the handler OBJECT on each
;; request and let a trace checker notice a forward that came back). Stronger
;; for one reason worth naming: rebinding-plus-detection makes self-interception
;; a REPORTED VIOLATION, and naming the outer instance makes it a REFUSAL —
;; `forward!` cannot reach a handler that is not the named outer, so E29
;; finding 1's `a layer that forgot would loop rather than be refused` is closed
;; at the mechanism rather than at the report.
;;
;; WHAT IT IS NOT. It is not first-class effect instances: `perturb.wire/socket`
;; is unchanged, `*handlers*` is unchanged, `perturb.http` is unchanged, and a
;; one-rung program never mints an instance. §A3 explicitly says not to add
;; first-class effects, compiler lowering, effect rows or delimited control, and
;; none of that is here.

(defn instance?
  [x]
  (and (map? x) (contains? x :perturb.effect/instance-id)))

(defn instance!
  "Mint a HANDLER INSTANCE: an explicit identity for one installed handler,
  with a NAMED OUTER INSTANCE it may forward to (or nil at the bottom rung).

  `finalize` is `(fn [outcome data] -> report-map)` or nil; see `finalize!`."
  [nm handler outer finalize]
  (let [i {:perturb.effect/instance-id (swap! event-counter inc)
           :perturb.effect/name        nm
           :perturb.effect/handler     handler
           :perturb.effect/outer       outer
           :perturb.effect/finalize    finalize
           :perturb.effect/owner       *owner*
           :perturb.effect/state       (atom {:finalized 0})}]
    (emit! {:perturb.effect/event    :rung
            :perturb.effect/rung     (:perturb.effect/instance-id i)
            :perturb.effect/name     nm
            :perturb.effect/outer    (if outer (:perturb.effect/instance-id outer) nil)
            :perturb.effect/finalizable (some? finalize)})
    i))

(defn as-instance
  "Lift a bare handler fn to a bottom-rung instance, or pass an instance
  through unchanged."
  [nm h]
  (if (instance? h) h (instance! nm h nil nil)))

(defn outward!
  "Mint one OUTWARD-OPERATION INSTANCE for a forward at `site`.

  The per-forward half of the mechanism. A handler instance says WHO; this says
  WHICH FORWARD, which is what Tang's admissibility conditions are about — an
  instance consumed twice is an outward operation that resumed many times, an
  instance never consumed is one that resumed zero times, and an instance
  consumed under a request other than the one that minted it escaped the extent
  that created it. None of the three is sayable with a layer label alone, which
  is why there are two mechanisms and not one.

  Always mints, whether or not anyone is watching: §A3 requires that
  `second-use ... must fail`, and a refusal that only happens when a log is
  bound is a report, not a refusal."
  [site]
  (let [d {:perturb.effect/instance   (swap! event-counter inc)
           :perturb.effect/site       site
           :perturb.effect/created-in (if *frame* (:perturb.effect/id *frame*) nil)
           :perturb.effect/used       (atom 0)}]
    (emit! (assoc d :perturb.effect/event :mint))
    d))

(defn outward-for
  "The outward-operation instance the next forward at `site` should carry: the
  one the caller supplied with `with-outward`, or a fresh one. A layer that
  re-supplies a stored instance is REUSING it, which is the thing under test."
  [site]
  (if *outward* *outward* (outward! site)))

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
  must not poison the controller latch.

  THE FIVE ADDED FOR §A3 ARE BOUNDARY FAILURES BY THE SAME TEST. A layer that
  forwards to itself, forwards with no outer instance named, forwards under the
  wrong owner token, forwards from inside a finalizer, or re-uses an
  outward-operation instance has not made a HANDLER DECISION about a protocol —
  it has broken the composition rule the boundary exists to hold. §A3 requires
  each of them to FAIL; latching is what stops the failure from being caught
  away by the layer that caused it."
  #{:unhandled-effect :unhandled-native-effect
    :self-forward :no-outer-instance :wrong-owner
    :forward-in-finalizer :outward-instance-reused})

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

(defn refuse!
  "Abort, attributing the refusal to request `id` in the layering event log.

  `abort!` is this with the id read from `*frame*`, which is right everywhere
  except inside `perform` itself: there the handler's dynamic extent has already
  unwound by the time a `[:abort ...]` reply is turned into a refusal, so
  `*frame*` names the PARENT and the refusal would be charged one rung too high.
  `perform` passes the id explicitly.

  The order is the load-bearing part and it is unchanged: `latch!` writes before
  the throw. The event is appended between them and is inert when no event log
  is bound."
  [id kind data]
  (when (contains? latching-aborts kind)
    (latch! kind data))
  (emit! {:perturb.effect/event :refusal
          :perturb.effect/id    id
          :perturb.effect/abort kind
          :perturb.effect/data  data})
  (throw (ex-info (str "perturb.effect: abort " kind)
                  (assoc data :perturb.effect/abort kind))))

(defn abort!
  "Abort. Boundary failures (`latching-aborts`) are written into run state
  BEFORE the throw, so catching the exception cannot undo the record."
  [kind data]
  (refuse! (if *frame* (:perturb.effect/id *frame*) nil) kind data))

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

(defn- cross!
  "The boundary crossing itself, shared by `perform` and `forward!`.

  ONE IMPLEMENTATION ON PURPOSE. §A3 wants forwarding to be a DISTINCT
  OPERATION with a distinct trace route, not a distinct set of guarantees: a
  forward must get the same arity check, the same result predicate, the same
  `*handling*` binding, the same `*trace*` record and the same run accounting as
  an application perform, or `perturb.tlsdemo`'s per-rung counts would stop
  meaning the same thing at the two rungs. `route` and `rung` are what differ,
  and they differ only in the event log."
  [eff op site args h route rung]
  (let [nm   (effect-name eff)
        decl (get (ops eff) op)]
    (when (nil? decl)
      (abort! :unknown-op {:perturb.effect/effect nm :perturb.effect/op op :perturb.effect/site site}))
    (when (not= (count args) (:arity decl))
      (abort! :arity {:perturb.effect/effect nm :perturb.effect/op op :perturb.effect/site site
                      :perturb.effect/expected (:arity decl)
                      :perturb.effect/actual (count args)}))
    (let [
          ;; The request id is minted BEFORE the missing-handler test, so a
          ;; forward that found no handler is a request in the log with
          ;; `:handler nil` rather than a refusal charged to the rung above it.
          ;; That is E29's control 1 made legible without changing what it does.
          rid  (if *events* (swap! event-counter inc) nil)
          outw *outward*
          frm  (if rid
                 {:perturb.effect/id      rid
                  :perturb.effect/depth   (+ 1 (if *frame* (:perturb.effect/depth *frame*) 0))
                  :perturb.effect/handler h}
                 *frame*)]
      (when rid
        (emit! {:perturb.effect/event   :request
                :perturb.effect/attempt (swap! attempt-counter inc)
                :perturb.effect/id      rid
                :perturb.effect/parent  (if *frame* (:perturb.effect/id *frame*) nil)
                :perturb.effect/depth   (:perturb.effect/depth frm)
                :perturb.effect/effect  nm
                :perturb.effect/op      op
                :perturb.effect/site    site
                :perturb.effect/args    args
                :perturb.effect/handler h
                :perturb.effect/route   route
                :perturb.effect/rung    (if rung (:perturb.effect/instance-id rung) nil)
                :perturb.effect/rung-name (if rung (:perturb.effect/name rung) nil)
                :perturb.effect/outward outw}))
      (when (nil? h)
        ;; §1.1's `:extern` with an effect row would make this a compile error.
        ;; Until then it is a fail-closed abort, and `refuse!` latches it: an
        ;; effect that reached the boundary with no handler cannot be caught
        ;; away.
        (refuse! rid :unhandled-effect {:perturb.effect/effect nm :perturb.effect/op op
                                        :perturb.effect/site site}))
      ;; Observed HERE, not after validation: the required set exists to prove
      ;; the run was not vacuous, and "this op reached the boundary and found a
      ;; registered handler" is the fact it needs. A handler that then aborts
      ;; still crossed the boundary.
      (observe! (op-symbol nm op))
      (when *run* (swap! *run* update :perturb.effect/performs inc))
      (let [reply (binding [*handling* {:perturb.effect/effect nm
                                        :perturb.effect/op op
                                        :perturb.effect/site site}
                            *frame*   frm
                            ;; Consumed: the instance this forward carries does
                            ;; not leak into whatever the handler performs next.
                            *outward* nil]
                    (h op site args))]
        (cond
          (not (vector? reply))
          (refuse! rid :malformed-handler-reply {:perturb.effect/effect nm :perturb.effect/op op
                                                 :perturb.effect/site site :perturb.effect/reply reply})

          (= :abort (first reply))
          (refuse! rid :handler-abort {:perturb.effect/effect nm :perturb.effect/op op
                                       :perturb.effect/site site :perturb.effect/reason (second reply)})

          (not= :ok (first reply))
          (refuse! rid :malformed-handler-reply {:perturb.effect/effect nm :perturb.effect/op op
                                                 :perturb.effect/site site :perturb.effect/reply reply})

          :else
          (let [v    (second reply)
                pred (:result-pred decl)]
            (if (and pred (not (pred v)))
              ;; This is the `validated` in `substitute a validated result`. A
              ;; handler that lies about its result type is stopped here, not
              ;; downstream in the codec.
              (refuse! rid :invalid-result {:perturb.effect/effect nm :perturb.effect/op op
                                            :perturb.effect/site site :perturb.effect/value v})
              (do
                (when *trace*
                  (swap! *trace* conj {:perturb.effect/effect nm
                                       :perturb.effect/op op
                                       :perturb.effect/site site
                                       :perturb.effect/args args
                                       :perturb.effect/result v}))
                (when rid
                  (emit! {:perturb.effect/event  :reply
                          :perturb.effect/id     rid
                          :perturb.effect/result v}))
                v))))))))

(defn perform
  "Perform `op` of `eff` at `site` with `args` (a vector).

  Returns the handler's result if and only if it satisfies the op's declared
  result predicate. Otherwise aborts. There is no third outcome: no resumption,
  no continuation capture, no handler-supplied control flow.

  ROUTE `:perform`. The handler is looked up in `*handlers*` by effect name,
  which is the one and only thing that has ever changed here."
  [eff op site args]
  (cross! eff op site args (get *handlers* (effect-name eff)) :perform nil))

;; --- forwarding: a DISTINCT operation targeting a NAMED outer instance ------

(defn forward!
  "Forward `op` of `eff` outward from handler instance `rung` to `rung`'s NAMED
  OUTER INSTANCE.

  THIS IS NOT `perform` WITH A REBINDING, AND THE DIFFERENCE IS THE POINT.
  E29 finding 1: `perturb.effect/*handlers*` is a flat map and `perform` does not
  pop the executing handler, so a handler that performs its own effect re-enters
  ITSELF; `perturb.tlsish/via` supplied the missing pop by hand and NOTHING
  CHECKED THAT A LAYER DID SO — `a layer that forgot would loop, not be refused`.
  Naming the outer instance removes the possibility rather than detecting it:
  there is no lookup by effect name, so there is nothing to forget and nothing to
  rebind. `*handlers*` is not consulted and is not modified.

  The five refusals below are §A3's controls, and each is fail-closed and
  LATCHED (see `latching-aborts`), so the layer that caused one cannot catch it
  away:

    :self-forward           the named outer is this instance, or is the handler
                            already executing on this thread. `self-forward must
                            fail` is §A3's first control and this is it.
    :no-outer-instance      a rung tried to forward with no outer named. The
                            bottom rung has nothing below it and must answer.
    :wrong-owner            the owner token in force is not the one the instance
                            was minted under. §A3's `wrong-thread` control,
                            represented as a token — see `*owner*`.
    :forward-in-finalizer   §A3: a finalizer may report transfer or discharge
                            but may not retry the outward mechanism.
    :outward-instance-reused  the outward-operation instance presented has
                            already been consumed. §A3's `second-use`, and E33's
                            multi-shot rejection, as a refusal.

  ROUTE `:forward`, never `:perform` and never `:proceed`. §A3's nonclaim is
  that `the native proceed and a layer forward are different mechanisms and must
  remain different trace routes`, and perturb has no native proceed at all."
  [rung eff op site args]
  (let [outer (:perturb.effect/outer rung)
        outw  *outward*]
    ;; Written BEFORE the tests, so a forward refused for reusing an instance
    ;; still leaves the reuse in the trace rather than vanishing with the throw.
    (emit! {:perturb.effect/event    :consume
            :perturb.effect/instance (if outw (:perturb.effect/instance outw) nil)
            :perturb.effect/rung     (:perturb.effect/instance-id rung)
            :perturb.effect/in       (if *frame* (:perturb.effect/id *frame*) nil)
            :perturb.effect/site     site})
    (when *finalizing*
      (abort! :forward-in-finalizer
              {:perturb.effect/rung (:perturb.effect/name rung)
               :perturb.effect/op op :perturb.effect/site site}))
    (when (not= *owner* (:perturb.effect/owner rung))
      (abort! :wrong-owner
              {:perturb.effect/rung (:perturb.effect/name rung)
               :perturb.effect/expected (:perturb.effect/owner rung)
               :perturb.effect/actual *owner*
               :perturb.effect/site site}))
    (when (nil? outer)
      (abort! :no-outer-instance
              {:perturb.effect/rung (:perturb.effect/name rung)
               :perturb.effect/op op :perturb.effect/site site}))
    (when (or (= (:perturb.effect/instance-id outer) (:perturb.effect/instance-id rung))
              (and *frame*
                   (some? (:perturb.effect/handler outer))
                   (= (:perturb.effect/handler outer) (:perturb.effect/handler *frame*))))
      (abort! :self-forward
              {:perturb.effect/rung  (:perturb.effect/name rung)
               :perturb.effect/outer (:perturb.effect/name outer)
               :perturb.effect/op op :perturb.effect/site site}))
    (when (and outw (> (swap! (:perturb.effect/used outw) inc) 1))
      (abort! :outward-instance-reused
              {:perturb.effect/rung (:perturb.effect/name rung)
               :perturb.effect/instance (:perturb.effect/instance outw)
               :perturb.effect/created-in (:perturb.effect/created-in outw)
               :perturb.effect/op op :perturb.effect/site site}))
    (cross! eff op site args (:perturb.effect/handler outer) :forward rung)))

;; --- finalisation (§A3) -----------------------------------------------------

(defn finalize!
  "Run `rung`'s finalizer for `outcome` (`:ok`, `:abort` or `:threw`).

  §A3: `Run finalization on success, abort, and exception; it may report
  transfer/discharge but may not retry native proceed.` The finalizer returns a
  REPORT — anything it wants to say about what it transferred or discharged —
  which is recorded in the event log and returned. It runs with `*finalizing*`
  true, so `forward!` refuses inside it.

  E33 records finalisation as one of the two things to ADOPT from runners
  (`explicit finalisation and explicit outer-operation selection`) without
  adopting the calculus, and this is deliberately the engineering half only: no
  kernel state, no co-operations, no user/kernel distinction, and no claim that
  a finalizer discharges anything the run did not already do."
  [rung outcome data]
  (let [n (:finalized (swap! (:perturb.effect/state rung) update :finalized inc))
        f (:perturb.effect/finalize rung)
        rep (if (and f (= 1 n))
              (binding [*finalizing* true] (f outcome data))
              nil)]
    (emit! {:perturb.effect/event    :finalize
            :perturb.effect/rung     (:perturb.effect/instance-id rung)
            :perturb.effect/name     (:perturb.effect/name rung)
            :perturb.effect/outcome  outcome
            :perturb.effect/nth      n
            :perturb.effect/report   rep})
    rep))

(defmacro with-rung
  "Run `body` as the extent of rung `inst`, finalising on success, abort AND
  exception. There is no arm on which the finalizer does not run, which is the
  whole of what §A3 asks for."
  [inst & body]
  `(let [i# ~inst]
     (try
       (let [v# (do ~@body)]
         (perturb.effect/finalize! i# :ok nil)
         v#)
       (catch :default e#
         (perturb.effect/finalize! i#
                                   (if (:perturb.effect/abort (ex-data e#)) :abort :threw)
                                   {:perturb.effect/abort (:perturb.effect/abort (ex-data e#))})
         (throw e#)))))
