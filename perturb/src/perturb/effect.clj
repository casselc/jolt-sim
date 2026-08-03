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

;; --- aborting (INHERITED I4) ------------------------------------------------

(defn abort!
  [kind data]
  (throw (ex-info (str "perturb.effect: abort " kind)
                  (assoc data :perturb.effect/abort kind))))

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
        (abort! :unhandled-effect {:perturb.effect/effect nm :perturb.effect/op op
                                   :perturb.effect/site site}))
      (let [reply (h op site args)]
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
