(ns perturb.semantic
  "A SEPARATE, VERSIONED SEMANTIC-EVENT DOCUMENT, and a fold runner over it.

  Step 2 of the convergence slice. The external review's correction is the
  reason this namespace exists rather than an extension to the replay trace,
  and both halves of that correction were verified from source before anything
  here was written:

    - `jolt.sim.kernel/validate-trace!` checks every event's FIXED-POSITION
      shape and requires a vector beginning with exactly one `:run/initial`.
      It would REJECT perturb's events, not absorb them.
    - `jolt.sim.kernel/replay` compares the FULL recorded sequence
      (`first-difference-index`) and throws `replay-diverged` on the first
      difference. Widening the trace breaks exact replay of every trace already
      recorded.
    - `README.md` already says a unified causal trace is later work.

  So the kernel trace is a SCHEDULER artifact and stays byte-identical. This
  document is a SEMANTIC one, versioned on its own, and it REFERENCES a run
  rather than living inside it.

  `run-monitor` MIRRORS `jolt.sim.monitor/run-monitor`'s contract exactly —
  the same `{:id :initial :step :finish}` spec, the same
  `{:state …}`-or-decision step result, the same
  `#{:pass :violation :inconclusive}`, the same
  `{:id :status :detail :index}` return, the same fail-closed typed errors.
  That is deliberate and it is the whole point of the slice: a monitor written
  against this runner is liftable to that one WITHOUT EDITING ITS LOGIC.

  WHAT IS NOT CLAIMED. The lift has not been executed. `jolt.sim.monitor` is
  JVM Clojure and the `clojure` CLI is not installed in this environment (the
  same wall E39 nonclaim 1 hit), so the two runners are shown to agree BY
  CONSTRUCTION AND BY READING, not by running the same monitor under both.
  That is a residual, and `perturb.slicecheck` prints it.

  MISSING CORRELATION IS `:inconclusive`, NEVER A PASS. An event that names a
  request the document never opened is not silently skipped: the monitor is
  handed the event and the fold decides, and the B6.3 monitor treats it as
  missing evidence."
  (:require [perturb.evidence :as ev]))

(def version 1)

(def ^:private doc-keys
  #{:perturb.semantic/version :perturb.semantic/case :perturb.semantic/events})

;; --- the closed event grammar ------------------------------------------------
;;
;; Fixed-position vectors, in the style of `jolt.sim.trace`. Arity is part of
;; the schema so a malformed projection fails closed instead of folding.

(def event-arity
  {:layer/request-opened  5    ; [tag id pos layer op]
   :layer/request-replied 4    ; [tag id pos ok?]
   :layer/refusal         4})  ; [tag id pos abort]

(defn- malformed! [reason detail]
  (throw (ex-info "perturb.semantic: malformed document"
                  {:type :perturb.semantic/malformed
                   :perturb.semantic/reason reason
                   :perturb.semantic/detail detail})))

(defn validate-event!
  [index e]
  (when-not (vector? e) (malformed! :event-not-a-vector {:index index}))
  (when (empty? e) (malformed! :empty-event {:index index}))
  (let [tag (nth e 0)
        n   (get event-arity tag)]
    (when (nil? n) (malformed! :unknown-tag {:index index :tag tag}))
    (when-not (= n (count e))
      (malformed! :wrong-arity {:index index :tag tag :want n :got (count e)})))
  e)

(defn validate-document!
  "Exact top-level keys, supported version, and the complete event schema."
  [doc]
  (when-not (map? doc) (malformed! :not-a-map (str doc)))
  (when-not (= doc-keys (set (keys doc)))
    (malformed! :wrong-keys (vec (sort (keys doc)))))
  (when-not (= version (:perturb.semantic/version doc))
    (malformed! :unsupported-version (:perturb.semantic/version doc)))
  (when-not (map? (:perturb.semantic/case doc))
    (malformed! :case-not-a-map (:perturb.semantic/case doc)))
  (let [evs (:perturb.semantic/events doc)]
    (when-not (vector? evs) (malformed! :events-not-a-vector (str evs)))
    (loop [i 0]
      (when (< i (count evs))
        (validate-event! i (nth evs i))
        (recur (inc i)))))
  doc)

(defn document
  "Builds and validates a semantic document."
  [case-map events]
  (validate-document! {:perturb.semantic/version version
                       :perturb.semantic/case    case-map
                       :perturb.semantic/events  events}))

;; --- the fold runner ---------------------------------------------------------

(def ^:private decision-statuses #{:pass :violation :inconclusive})
(def ^:private spec-keys #{:id :initial :step :finish})

(defn- fail-closed! [id reason detail]
  (throw (ex-info "perturb.semantic: monitor produced an invalid result"
                  {:type :perturb.semantic/invalid-monitor-result
                   :monitor id :reason reason :detail detail})))

(defn- validate-spec! [spec]
  (when-not (map? spec)
    (throw (ex-info "perturb.semantic: invalid monitor spec"
                    {:type :perturb.semantic/invalid-monitor-spec
                     :reason :not-a-map})))
  (when-not (= spec-keys (set (keys spec)))
    (throw (ex-info "perturb.semantic: invalid monitor spec"
                    {:type :perturb.semantic/invalid-monitor-spec
                     :reason :wrong-keys :detail (vec (sort (keys spec)))})))
  spec)

(defn- validate-decision! [id v]
  (when-not (map? v) (fail-closed! id :decision-not-a-map (str v)))
  (when-not (contains? #{#{:status} #{:status :detail}} (set (keys v)))
    (fail-closed! id :decision-wrong-keys (vec (sort (keys v)))))
  (when-not (contains? decision-statuses (:status v))
    (fail-closed! id :decision-bad-status (:status v)))
  v)

(defn- validate-step-result! [id v]
  (when-not (map? v) (fail-closed! id :step-result-not-a-map (str v)))
  (if (= #{:state} (set (keys v))) v (validate-decision! id v)))

(defn run-monitor
  "Folds `spec` over a validated document's events.

  Contract-identical to `jolt.sim.monitor/run-monitor`: `:step` is
  `(fn [state index event] result)` returning `{:state next}` or a decision;
  `:finish` is `(fn [final-state] decision)`; the result is
  `{:id :status :detail :index}` where `:index` is the index of the first
  decision or nil when the decision came from `:finish`.

  ONE DELIBERATE DIFFERENCE, NAMED: this runner passes the document's `:case`
  to `:finish` as well, because a semantic monitor needs the declaration set
  and a scheduler trace has no such thing. `:finish` therefore takes
  `[state case]`. That is the one place the two contracts are not identical
  and it is the one thing a lift would have to reconcile."
  [spec doc]
  (validate-document! doc)
  (validate-spec! spec)
  (let [id     (:id spec)
        step   (:step spec)
        finish (:finish spec)
        evs    (:perturb.semantic/events doc)
        n      (count evs)]
    (loop [state (:initial spec) i 0]
      (if (= i n)
        (let [d (validate-decision! id (finish state (:perturb.semantic/case doc)))]
          {:id id :status (:status d) :detail (:detail d) :index nil})
        (let [r (validate-step-result! id (step state i (nth evs i)))]
          (if (contains? r :state)
            (recur (:state r) (inc i))
            {:id id :status (:status r) :detail (:detail r) :index i}))))))
