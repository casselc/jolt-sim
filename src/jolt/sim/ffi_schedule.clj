(ns jolt.sim.ffi-schedule
  "Deterministic arrival rendezvous over an existing canonical FFI handler map.

  This coordinator does not implement, replace, or reinterpret any native
  library, socket, poll, or SQLite behavior. It wraps a caller-supplied
  canonical :ffi-handlers map (the exact shape jolt.sim.runtime/run-controlled
  consumes, validated/canonicalized upstream by jolt.sim.handler-pack or
  jolt.sim.runtime/normalize-ffi-handlers) and interposes only the handlers
  named by a plan. Every call to a handler not selected by the plan delegates
  to the original handler unchanged; every call to a wrapped key still receives
  an arrival occurrence (per key, or per [handler-key selector] for semantic
  steps), and only the occurrence a step selects engages the schedule.

  A plan is a nonempty vector of exact closed step maps
  {:id namespaced-keyword :handler-key existing-key :occurrence positive-int}.
  Step ids are unique, and the [handler-key occurrence] selector is unique per
  step. When the selected occurrence of a wrapped key arrives, the coordinator
  assigns it a one-shot gate (a promise). Gates are released in exact plan
  order: a gate is delivered the moment its step is due AND its arrival has
  registered, and that delivery happens before the original handler is invoked.
  This is an admission-order guarantee only: once multiple gates have been
  delivered, ordinary runtime scheduling decides when their handlers execute
  and complete.
  Releasing on arrival -- not on handler completion -- is what keeps a modeled
  poll-then-write pair from deadlocking merely because the modeled poll blocks:
  the write's gate is released while the poll's handler is still running.

  A step may instead carry exactly one extra key, :selector, a namespaced
  keyword naming a stable resource/call role for its handler key:

    {:id namespaced-keyword :handler-key existing-key
     :selector namespaced-keyword :occurrence positive-int}

  Semantic steps exist so one handler key shared by several concurrent roles
  -- for example, two poll callers in one application -- can be scheduled per
  role without hard-coding one global handler occurrence. The plan remains
  plain EDN; functions live only in the coordinator's optional third argument,
  one closed options map:

    {:classifiers {handler-key classifier-fn}}

  A classifier receives the exact invocation descriptor and returns nil or a
  namespaced keyword label. Nil and any valid label the plan does not request
  delegate to the original handler unchanged. For a requested label,
  occurrence counting is per [handler-key selector], so interleaved labels on
  one handler key cannot perturb each other. All steps for one handler key
  must use one mode: mixing semantic and global occurrence steps on the same
  handler key is rejected.

  A step that arrives before it is due simply waits at its gate until the
  earlier planned steps have arrived and been released. Handler results and
  exceptions are preserved verbatim: the wrapped handler returns exactly what
  the original returned and rethrows exactly what the original threw; a handler
  exception is not a schedule violation and does not abort the schedule.

  Malformed plans or options, missing handler keys, duplicate selectors or
  ids, mixed occurrence modes on one handler key, missing, non-callable,
  unknown-key, or unrequested classifiers, missing or incomplete steps at the
  finish check, and any selected arrival after an abort all fail closed with
  typed ex-info. Aborting delivers an internal abort
  decision to every still-blocked gate; a waiter dereferencing such a gate
  throws a typed internal gate-aborted error rather than hanging. Already
  released gates proceed normally -- abort drains only blocked waiters.

  A classifier that returns a non-nil value which is not a namespaced keyword
  fails closed with a typed :jolt.sim.ffi-schedule/classifier-failure error,
  retained as the first schedule failure; a classifier that throws has its
  original throwable retained and rethrown verbatim. Either failure aborts the
  schedule, so abort!/check-complete! drainage cannot strand another selected
  waiter, and the failing invocation never reaches its original handler.

  The coordinator never keys behavior on runtime task ids. Per-key and
  per-[handler-key selector] occurrence counters and the shared schedule state
  are plain atoms coordinated by
  compare-and-set!, so the only synchronization assumed is Clojure promises,
  atoms, and futures -- no JVM-only locks, conditions, or park/unpark.

  Only handler keys named in the plan are wrapped. Unnamed handler values are
  returned identical, and classifier and counter work occurs only for
  plan-selected keys, so unscheduled handlers pay no sim-only overhead."

  ;; Public typed tags. Callers and tests match on :type (and :reason).
  )

(def invalid-plan :jolt.sim.ffi-schedule/invalid-plan)
(def incomplete-schedule :jolt.sim.ffi-schedule/incomplete)
(def gate-aborted :jolt.sim.ffi-schedule/gate-aborted)
(def aborted-arrival :jolt.sim.ffi-schedule/aborted-arrival)
(def classifier-failure :jolt.sim.ffi-schedule/classifier-failure)

(def ^:private step-keys #{:id :handler-key :occurrence})
(def ^:private semantic-step-keys #{:id :handler-key :selector :occurrence})

(defn- namespaced-keyword? [x]
  (and (keyword? x) (some? (namespace x))))

(defn- step-mode
  "Global steps count occurrences per handler key; semantic steps count them
  per [handler-key selector]. One handler key must use one mode across the
  whole plan."
  [step]
  (if (contains? step :selector) :semantic :global))

(defn- selector-tuple
  "The public uniqueness tuple for one step. Preserve the original global
  [handler-key occurrence] shape in validation errors; semantic steps add the
  selector as a third tuple element."
  [step]
  (if (contains? step :selector)
    [(:handler-key step) (:selector step) (:occurrence step)]
    [(:handler-key step) (:occurrence step)]))

(defn- index-key
  "The internally tagged lookup key for one step. Unlike the public validation
  tuple, this shape is private and optimized for unambiguous coordinator
  dispatch."
  [step]
  (if (contains? step :selector)
    [:semantic (:handler-key step) (:selector step) (:occurrence step)]
    [:global (:handler-key step) (:occurrence step)]))

(defn- step-shape-ok?
  "Closed shape check for one step: either the global occurrence shape or the
  semantic :selector shape, with a namespaced keyword :id (and :selector when
  present) and a positive integer :occurrence."
  [step]
  (and (map? step)
       (not (record? step))
       (let [ks (set (keys step))]
         (and (or (= step-keys ks) (= semantic-step-keys ks))
              (namespaced-keyword? (:id step))
              (let [occ (:occurrence step)]
                (and (integer? occ) (pos? occ)))
              (if (contains? step :selector)
                (namespaced-keyword? (:selector step))
                true)))))

(defn- modes-by-handler-key [plan]
  (reduce (fn [m step]
            (let [key (:handler-key step)
                  modes (get m key #{})]
              (assoc m key (conj modes (step-mode step)))))
          {}
          plan))

;; Internal one-shot gate decisions. Namespaced so they cannot collide with a
;; handler return value transported through the same promise.
(def ^:private release-decision ::release)
(def ^:private abort-decision ::abort)

(defn- typed-error [type reason data]
  (ex-info (str "jolt.sim.ffi-schedule " (name reason))
           (assoc data :type type :reason reason)))

(defn- fail! [type reason data]
  (throw (typed-error type reason data)))

(defn- invalid-plan-error [reason data]
  (fail! invalid-plan reason data))

(defn- gate-aborted-error [data]
  (typed-error gate-aborted :gate-aborted data))

(defn- aborted-arrival-error [data]
  (typed-error aborted-arrival :aborted-arrival data))

(defn valid-plan?
  "True when `plan` has a closed step shape the coordinator accepts -- the
  global occurrence shape or the semantic :selector shape -- ignoring whether
  each step's :handler-key exists in any particular handler map and whether
  any classifier options exist. Pure and handler-independent: unique ids,
  unique per-mode selectors, and one occurrence mode per handler key are
  rejected here without loading the runtime; classifier availability is a
  coordinator configuration concern, not plan shape."
  [plan]
  (boolean
   (and (vector? plan)
        (seq plan)
        (every? step-shape-ok? plan)
        (let [ids (mapv :id plan)]
          (= (count ids) (count (set ids))))
        (let [sels (mapv selector-tuple plan)]
          (= (count sels) (count (set sels))))
        (every? (fn [[_key modes]] (= 1 (count modes)))
                (modes-by-handler-key plan)))))

(defn- validate-options
  "Cross-checks the coordinator options against the plan and handlers before
  any state exists. Options are one closed map
  {:classifiers {handler-key classifier-fn}}; with no options at all, any
  semantic step lacks its classifier. A classifier key must exist in
  `handlers` and be requested by at least one semantic step, and its value
  must be callable."
  [handlers plan opts]
  (when (some? opts)
    (when-not (and (map? opts)
                   (not (record? opts))
                   (= #{:classifiers} (set (keys opts)))
                   (map? (:classifiers opts)))
      (invalid-plan-error :malformed-options
                          {:provided-class (str (class opts))})))
  (let [classifiers (if (some? opts) (:classifiers opts) {})
        needed (into #{}
                     (comp (filter #(= :semantic (step-mode %)))
                           (map :handler-key))
                     plan)]
    (doseq [[key classifier] classifiers]
      (when-not (contains? handlers key)
        (invalid-plan-error :classifier-for-unknown-key
                            {:handler-key key}))
      (when-not (contains? needed key)
        (invalid-plan-error :classifier-not-requested
                            {:handler-key key}))
      (when-not (ifn? classifier)
        (invalid-plan-error :classifier-not-callable
                            {:handler-key key
                             :provided-class (str (class classifier))})))
    (doseq [[i step] (map-indexed vector plan)]
      (when (and (= :semantic (step-mode step))
                 (not (contains? classifiers (:handler-key step))))
        (invalid-plan-error :missing-classifier
                            {:index i :handler-key (:handler-key step)})))))

(defn validate-plan
  "Validates `plan` against both its closed shape (global or semantic steps)
  and the existing keys of `handlers`; when supplied, the closed options map
  `opts` is cross-checked against both. Returns `plan` on success; otherwise
  throws :jolt.sim.ffi-schedule/invalid-plan with a :reason naming the exact
  defect (:plan-must-be-a-nonempty-vector, :handlers-must-be-a-map,
  :malformed-step, :duplicate-ids, :duplicate-selectors,
  :mixed-occurrence-modes, :missing-handler-key, :malformed-options,
  :classifier-for-unknown-key, :classifier-not-requested,
  :classifier-not-callable, or :missing-classifier)."
  ([handlers plan]
   (validate-plan handlers plan nil))
  ([handlers plan opts]
   (when-not (map? handlers)
     (invalid-plan-error :handlers-must-be-a-map
                         {:provided-class (str (class handlers))}))
   (when-not (and (vector? plan) (seq plan))
     (invalid-plan-error :plan-must-be-a-nonempty-vector
                         {:provided (str (class plan))}))
   (doseq [[i step] (map-indexed vector plan)]
     (when-not (step-shape-ok? step)
       (invalid-plan-error :malformed-step {:index i :step step})))
   (let [ids (mapv :id plan)]
     (when-not (= (count ids) (count (set ids)))
       (invalid-plan-error :duplicate-ids
                           {:ids (vec ids)})))
   (let [sels (mapv selector-tuple plan)]
     (when-not (= (count sels) (count (set sels)))
       (invalid-plan-error :duplicate-selectors {:selectors sels})))
   (doseq [[key modes] (modes-by-handler-key plan)]
     (when-not (= 1 (count modes))
       (invalid-plan-error :mixed-occurrence-modes {:handler-key key})))
   (doseq [[i step] (map-indexed vector plan)]
     (when-not (contains? handlers (:handler-key step))
       (invalid-plan-error :missing-handler-key
                           {:index i :handler-key (:handler-key step)})))
   (validate-options handlers plan opts)
   plan))

(defn gate-aborted?
  "True only when `error` or one of its first eight causes carries the
  coordinator's internal :gate-aborted tag. A blocked waiter runs inside a
  future, so its thrown gate-aborted error is transported through an
  ExecutionException; recognizing only the outer throwable would let that
  wrapper mask the coordinator decision that closed the gate."
  [error]
  (loop [current error
         remaining 8]
    (cond
      (nil? current)
      false

      (= gate-aborted (:type (ex-data current)))
      true

      (zero? remaining)
      false

      :else
      (recur (ex-cause current) (dec remaining)))))

(defn- initial-state [plan]
  {:plan plan
   :n (count plan)
   :due-index 0
   :gates {}        ;; step-index -> one-shot promise gate
   :arrived #{}     ;; step-indices whose selected arrival registered a gate
   :claimed #{}     ;; release decisions owned by the single physical drainer
   :released #{}    ;; step-indices whose release was physically delivered
   :completed #{}   ;; step-indices whose original handler returned
   ;; Only release decisions are replay choices. Arrival and completion order
   ;; are observed concurrency diagnostics and must not be mislabeled as
   ;; deterministic evidence.
   :release-log []
   :arrival-log []
   :completion-log []
   :in-flight 0     ;; selected calls currently engaged (waiting or executing)
   :drainer nil     ;; unique token for the sole physical gate-delivery owner
   :aborting? false
   :failure nil})

(defn- deliver-abort-gates!
  "Delivers abort only to gates that the physical drainer has not claimed for
  release. A claimed gate is delivered by that same owner before it observes
  the abort; abort-schedule! waits for the owner, so no claimed-but-undelivered
  waiter can remain when abort returns."
  [snapshot]
  (doseq [[idx gate] (:gates snapshot)]
    (when-not (contains? (:claimed snapshot) idx)
      (deliver gate abort-decision))))

(defn- try-claim-drainer!
  "Claims sole ownership of physical promise delivery, returning a unique
  token, or nil when another caller is already draining. The token lives in the
  same atom as arrivals and abort state, so a release-owner CAS cannot miss
  newly registered work."
  [state]
  (loop []
    (let [before @state]
      (if (some? (:drainer before))
        nil
        (let [token (promise)
              staged (assoc before :drainer token)]
          (if (compare-and-set! state before staged)
            token
            (recur)))))))

(defn- drive-owned!
  "Runs all physical gate delivery for one drainer token. Exactly one release
  is claimed and delivered per loop, so a later registration can never deliver
  a later gate ahead of an earlier claimed batch. Release evidence is appended
  only after the promise has actually been delivered.

  When abort is observed, this owner delivers abort to every unclaimed gate and
  relinquishes ownership only through a CAS over the post-delivery snapshot.
  If waking a waiter changes in-flight state, the CAS retries and drainage is
  idempotent."
  [state token]
  (loop []
    (let [before @state
          due-index (:due-index before)
          due-gate (get-in before [:gates due-index])]
      (cond
        (:aborting? before)
        (do
          (deliver-abort-gates! before)
          (if (compare-and-set! state before (assoc before :drainer nil))
            nil
            (recur)))

        (and (< due-index (:n before))
             (some? due-gate)
             (not (contains? (:claimed before) due-index)))
        (let [id (get-in before [:plan due-index :id])
              staged (-> before
                         (update :claimed conj due-index)
                         (assoc :due-index (inc due-index)))]
          (if (compare-and-set! state before staged)
            (do
              ;; No other drainer can run until this owner relinquishes its
              ;; token. Physical delivery therefore follows plan order even if
              ;; another thread registers a later gate at this exact point.
              (deliver due-gate release-decision)
              (swap! state
                     (fn [s]
                       (-> s
                           (update :released conj due-index)
                           (update :release-log conj [:release id]))))
              (recur))
            (recur)))

        :else
        ;; Relinquish only if the exact no-work snapshot is still current. If
        ;; an arrival or abort raced with this decision, keep ownership and
        ;; inspect the new work instead of leaving it without a drainer.
        (if (compare-and-set! state before (assoc before :drainer nil))
          nil
          (recur))))))

(defn- drive-available!
  "Drains available release work when no other caller owns delivery. Arrival
  callers need not wait for an existing owner: they subsequently block on their
  own promise until that owner reaches them."
  [state]
  (when-let [token (try-claim-drainer! state)]
    (drive-owned! state token))
  nil)

(defn- synchronize-drainer!
  "Establishes a handoff after any current drainer by eventually claiming and
  draining once itself. This is used by abort and completeness checking, which
  must not return while an earlier owner is between its release CAS, physical
  promise delivery, evidence append, and ownership relinquishment."
  [state]
  (loop []
    (if-let [token (try-claim-drainer! state)]
      (drive-owned! state token)
      (do
        (Thread/yield)
        (recur))))
  nil)

(defn- abort-schedule!
  "Marks the schedule aborted, then synchronizes with the physical drainer.
  This function returns only after one owner has delivered abort to every
  unclaimed registered gate. Already-claimed releases proceed normally."
  [state]
  (swap! state assoc :aborting? true)
  (synchronize-drainer! state)
  nil)

(defn- register-arrival!
  "Atomically registers the one-shot gate for step `idx`, records its arrival,
  and offers the release frontier to the sole physical drainer. Returns
  {:gate promise} on success; the caller waits on that gate if another owner is
  still delivering earlier work. Returns {:aborted true :state-reason keyword}
  if the schedule has been aborted, in which case no gate is registered and the
  caller must fail closed."
  [state idx]
  (loop []
    (let [before @state]
      (cond
        (:aborting? before)
        {:aborted true :state-reason :schedule-aborted}

        ;; The [handler-key occurrence] selector is unique and occurrences are
        ;; assigned atomically per key, so a second arrival for the same step
        ;; cannot occur under the contract. If that invariant is ever broken,
        ;; atomically abort the whole coordinator and drain all blocked gates
        ;; rather than silently overwriting a gate or stranding other callers.
        (contains? (:gates before) idx)
        (do
          (abort-schedule! state)
          {:aborted true :state-reason :duplicate-step-arrival})

        :else
        (let [gate (promise)
              staged (-> before
                         (assoc-in [:gates idx] gate)
                         (update :arrived conj idx)
                         (update :arrival-log conj
                                 (get-in before [:plan idx :id]))
                         ;; Count ownership before any gate is delivered. A
                         ;; concurrent abort/completeness check can therefore
                         ;; never observe zero while this selected call is
                         ;; already admitted but has not entered its try/finally.
                         (update :in-flight inc))]
          (if (compare-and-set! state before staged)
            (do
              (drive-available! state)
              {:gate gate})
            (recur)))))))

(defn- record-complete!
  "Records an observed completion for a step whose original handler returned
  normally. Completion is diagnostic rather than replay evidence and never
  advances the frontier: release is driven by arrival, not completion, which is
  exactly what prevents a blocking handler from deadlocking a later planned
  arrival."
  [state idx id]
  (swap! state
         (fn [s]
           (if (or (:aborting? s) (contains? (:completed s) idx))
             s
             (-> s
                 (update :completed conj idx)
                 (update :completion-log conj id)))))
  nil)

(defn- retain-failure!
  "Keeps the first schedule failure only. Concurrent post-abort arrivals each
  build their own typed error; exactly the first is retained so the enclosing
  run can surface one canonical cause after drainage."
  [state failure]
  (swap! state
         (fn [s]
           (if (:failure s) s (assoc s :failure failure))))
  nil)

(defn- fail-and-abort!
  "Atomically retains the first schedule failure and closes admission, then
  synchronizes with the physical drainer so every unclaimed blocked gate has
  received an abort decision before returning. No arrival can register between
  observing the failure and closing admission because both facts share one atom
  transition."
  [state failure]
  (swap! state
         (fn [s]
           (assoc (if (:failure s) s (assoc s :failure failure))
                  :aborting? true)))
  (synchronize-drainer! state)
  nil)

(defn- incomplete-error [s missing]
  (let [in-flight (:in-flight s)
        reason (cond
                 (:aborting? s) :aborted-before-completion
                 (pos? in-flight) :calls-in-flight
                 :else :incomplete)]
    (ex-info
     (str "jolt.sim.ffi-schedule " (name reason))
     {:type incomplete-schedule
      :reason reason
      :missing missing
      :in-flight in-flight
      :aborted? (boolean (:aborting? s))})))

(defn- check-complete!
  "Returns nil only after every planned handler completed and no selected call
  remains in flight. Any failure is retained, atomically aborts the schedule,
  releases every blocked gate, and throws the retained primary error."
  [state]
  ;; Joining a selected worker proves its handler returned, but that worker may
  ;; have outrun the separate drainer immediately after its gate was delivered.
  ;; Establish a drainer handoff first so success also implies all physical
  ;; release evidence has been recorded.
  (synchronize-drainer! state)
  (loop []
    (let [before @state
          completed (:completed before)
          missing (into []
                        (keep-indexed (fn [i step]
                                        (when-not (contains? completed i)
                                          (:id step))))
                        (:plan before))]
      (if (and (nil? (:failure before))
               (not (:aborting? before))
               (empty? missing)
               (zero? (:in-flight before))
               (nil? (:drainer before)))
        nil
        (let [candidate (incomplete-error before missing)
              failure (or (:failure before) candidate)
              staged (assoc before :aborting? true :failure failure)]
          (if (compare-and-set! state before staged)
            (do
              ;; Synchronize with an owner that may already have claimed a
              ;; release but not yet delivered it. This prevents completeness
              ;; failure from returning while a selected waiter is stranded.
              (abort-schedule! state)
              (throw failure))
            (recur)))))))

(defn- diagnostics
  "Returns a host-identity-free snapshot of coordinator progress. :in-flight is
  the count of selected calls currently waiting at a gate or executing their
  original handler; the arrival/release/completed sets carry step ids, never
  runtime task ids."
  [state]
  (let [s @state
        plan (:plan s)]
    {:in-flight (:in-flight s)
     :due-index (:due-index s)
     :arrival-order (:arrival-log s)
     :release-order (mapv second (:release-log s))
     :completion-order (:completion-log s)
     :arrived (into #{} (keep-indexed (fn [i step]
                                        (when (contains? (:arrived s) i)
                                          (:id step)))
                                      plan))
     :released (into #{} (keep-indexed (fn [i step]
                                         (when (contains? (:released s) i)
                                           (:id step)))
                                       plan))
     :completed (into #{} (keep-indexed (fn [i step]
                                          (when (contains? (:completed s) i)
                                            (:id step)))
                                        plan))
     :aborted? (boolean (:aborting? s))}))

(defn- invoke-original
  "Calls the original handler, preserving the runtime contract that a nil
  handler is a valid substitution returning nil. Exceptions propagate
  verbatim."
  [original descriptor]
  (if (some? original)
    (original descriptor)
    nil))

(defn- run-classifier
  "Runs the caller-supplied classifier for one invocation of a wrapped
  semantic key and validates its label: nil delegates to the original
  handler, and a namespaced keyword is a valid (planned or unplanned) label.
  Any other non-nil return value fails closed with a typed classifier-failure
  that is retained as the first schedule failure; a classifier that throws
  has its original throwable retained and rethrown verbatim. Either failure
  aborts the schedule first, so an already parked selected waiter is drained
  by the same abort machinery instead of stranding, and the failing invocation
  never reaches its original handler."
  [state classifier key descriptor]
  (let [label (try
                (classifier descriptor)
                (catch :default throwable
                  (fail-and-abort! state throwable)
                  (throw throwable)))]
    (when (and (some? label) (not (namespaced-keyword? label)))
      (let [error (typed-error classifier-failure
                               :classifier-returned-invalid-label
                               {:handler-key key
                                :provided-class (str (class label))})]
        (fail-and-abort! state error)
        (throw error)))
    label))

(defn- engage-selected!
  "Shared selected-arrival path for global and semantic steps: registers the
  one-shot gate, waits for its release or abort decision, then invokes the
  original handler exactly once and records completion. Post-abort arrivals
  and gate-abort decisions throw typed errors; the original handler's result
  or exception is preserved verbatim, and in-flight accounting drains in
  finally even when it throws."
  [state plan idx key original descriptor]
  (let [step (get plan idx)
        id (:id step)
        occ (:occurrence step)
        selector (:selector step)
        data (if (some? selector)
               {:id id :handler-key key :occurrence occ :selector selector}
               {:id id :handler-key key :occurrence occ})
        reg (register-arrival! state idx)]
    (if (:aborted reg)
      (let [error (aborted-arrival-error
                   (assoc data :state-reason (:state-reason reg)))]
        (retain-failure! state error)
        (throw error))
      (let [gate (:gate reg)]
        ;; Registration counted this call in-flight before any gate was
        ;; delivered. Decrement in finally so abort/completeness observes
        ;; drainage even when the handler throws.
        (try
          (let [outcome (deref gate)]
            (when (= outcome abort-decision)
              (throw (gate-aborted-error data)))
            (let [result (invoke-original original descriptor)]
              (record-complete! state idx id)
              result))
          (finally
            (swap! state update :in-flight dec)))))))

(defn coordinator
  "Wraps the canonical FFI handler map `handlers` with the deterministic arrival
  schedule `plan`. Returns a map suitable for later run-controlled composition:

    :handlers          the wrapped handler map. Keys not named by the plan are
                       the original values; named keys are interposed. Install
                       this as :ffi-handlers.
    :check-complete!   (fn []) -- after the caller has quiesced/joined its
                       workers, throws the first retained schedule violation,
                       or :incomplete if any planned step did not complete;
                       returns nil on success. A premature check deliberately
                       aborts.
    :abort!            (fn []) -- aborts the schedule, delivering an abort
                       decision to every still-blocked gate. Already-released
                       gates proceed and remain caller-owned; use fresh-process
                       containment for native handlers that may not return.
    :failure           (fn []) -- the first retained schedule failure, or nil.
    :evidence          (fn []) -- deterministic replay choices only: a vector
                       of [:release id] entries in exact plan order. Raw
                       arrival/completion order remains diagnostic.
    :diagnostics       (fn []) -- current progress including :in-flight count.

  `plan` is validated (shape, handler-key existence, unique ids and selectors,
  one occurrence mode per handler key) before any state is constructed, so a
  malformed configuration returns no coordinator.

  The optional third argument is one closed options map

    {:classifiers {handler-key classifier-fn}}

  enabling semantic :selector steps (see the namespace docstring): each
  classifier receives the exact invocation descriptor and returns nil or a
  namespaced keyword label, and occurrence counting runs per [handler-key
  selector]. Omitting it preserves the exact two-argument behavior for global
  occurrence plans. Classifier and counter work occurs only for handler keys
  named by the plan; unnamed handler values are returned identical, so
  unscheduled handlers pay no sim-only overhead."
  ([handlers plan]
   (coordinator handlers plan nil))
  ([handlers plan opts]
   (validate-plan handlers plan opts)
   (let [classifiers (if (some? opts) (:classifiers opts) {})
         state (atom (initial-state plan))
         selector->index (into {}
                               (map-indexed (fn [i step]
                                              [(index-key step) i]))
                               plan)
         semantic-keys (into #{}
                             (comp (filter #(= :semantic (step-mode %)))
                                   (map :handler-key))
                             plan)
         ;; One occurrence counter per wrapped global key, incremented
         ;; atomically in arrival order. Only keys named by the plan are
         ;; wrapped.
         key->counter (into {}
                            (map (fn [key] [key (atom 0)]))
                            (into #{}
                                  (comp (filter #(= :global (step-mode %)))
                                        (map :handler-key))
                                  plan))
         ;; Semantic steps count per [handler-key selector] pair, so
         ;; interleaved labels on one handler key cannot perturb each other.
         planned-pairs (into #{}
                             (comp (filter #(= :semantic (step-mode %)))
                                   (map (fn [step] [(:handler-key step)
                                                    (:selector step)])))
                             plan)
         pair->counter (into {}
                             (map (fn [pair] [pair (atom 0)]))
                             planned-pairs)
         wrapped-keys (set (map :handler-key plan))]
     (letfn [(wrap-one [key original]
               (if (contains? semantic-keys key)
                 (let [classifier (get classifiers key)]
                   (fn [descriptor]
                     (let [label (run-classifier state classifier key descriptor)]
                       (if (and (some? label)
                                (contains? planned-pairs [key label]))
                         (let [ctr (get pair->counter [key label])
                               occ (swap! ctr inc)
                               idx (get selector->index
                                        [:semantic key label occ])]
                           (if (nil? idx)
                             ;; A requested label, but not the selected
                             ;; occurrence: delegate unchanged.
                             (invoke-original original descriptor)
                             ;; Selected arrival: engage the schedule.
                             (engage-selected! state plan idx key
                                               original descriptor)))
                         ;; Nil or a label no step requests: delegate unchanged.
                         (invoke-original original descriptor)))))
                 (let [ctr (get key->counter key)]
                   (fn [descriptor]
                     (let [occ (swap! ctr inc)
                           idx (get selector->index [:global key occ])]
                       (if (nil? idx)
                         ;; Not selected by the plan: delegate unchanged.
                         (invoke-original original descriptor)
                         ;; Selected arrival: engage the schedule.
                         (engage-selected! state plan idx key
                                           original descriptor)))))))]
       (let [wrapped-handlers
             (into {}
                   (map (fn [[key original]]
                          [key (if (contains? wrapped-keys key)
                                 (wrap-one key original)
                                 original)]))
                   handlers)]
         {:handlers wrapped-handlers
          :check-complete! (fn [] (check-complete! state))
          :abort! (fn [] (abort-schedule! state))
          :failure (fn [] (:failure @state))
          :evidence (fn [] (:release-log @state))
          :diagnostics (fn [] (diagnostics state))
          :gate-aborted? gate-aborted?})))))
