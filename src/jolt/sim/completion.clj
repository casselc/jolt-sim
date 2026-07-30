(ns jolt.sim.completion
  "Pure-data completion registry for deterministic simulation operations.

  Each operation is identified by a non-negative integer operation id and is
  either pending (registered, awaiting a terminal outcome) or published (a
  terminal outcome has been recorded). Tasks wait on pending operations; when
  an operation is published its waiters are cleared and returned as the wake
  set so a scheduler can resume them.

  All retained state is plain immutable data. The internal entry for an
  operation is

      {:waiters #{task-id ...} :outcome encoded-terminal-outcome-or-nil}

  with ``:outcome`` ``nil`` meaning pending. Published payloads are retained as
  immutable canonical projections and restored into fresh values at every
  public read. This prevents a caller-owned byte array from changing an
  already-published result. Every value that crosses the public boundary is
  checked against [[jolt.sim.trace/canonical-value]]; a raw [[Throwable]] is
  accepted only via [[failure]], which first runs
  [[jolt.sim.trace/normalize-error]]."
  (:require [jolt.sim.trace :as trace]))

(def ^:private operation-kinds #{:success :failure :cancellation})

;; The single payload key that must accompany each recognized outcome kind.
(def ^:private payload-key
  {:success :value :failure :error :cancellation :reason})

(def ^:private entry-keys #{:waiters :outcome})

(defn- non-negative-int? [value]
  (and (integer? value) (not (neg? value))))

(defn- invalid! [type detail]
  (throw
   (ex-info (str "jolt.sim.completion: " (name type))
            (assoc detail :type type))))

(defn- validate-operation-id! [operation-id]
  (when-not (non-negative-int? operation-id)
    (invalid! ::invalid-operation-id
              (cond-> {:reason :not-a-non-negative-integer
                       :value-class (str (class operation-id))}
                (integer? operation-id) (assoc :value operation-id))))
  operation-id)

(defn- validate-task-id! [task-id]
  (when-not (non-negative-int? task-id)
    (invalid! ::invalid-task-id
              (cond-> {:reason :not-a-non-negative-integer
                       :value-class (str (class task-id))}
                (integer? task-id) (assoc :value task-id))))
  task-id)

;; A terminal outcome must be an exact-key map shaped exactly like one emitted
;; by [[success]], [[failure]], or [[cancellation]]. canonical-value then
;; confirms the embedded payload is in the trace domain (and rejects records).
(defn- validate-terminal-shape! [outcome]
  (when-not (map? outcome)
    (invalid! ::invalid-outcome
              {:reason :not-a-map
               :value-class (str (class outcome))}))
  (when (record? outcome)
    (invalid! ::invalid-outcome {:reason :record}))
  (let [kind (:kind outcome)
        expected-keys (when (contains? operation-kinds kind)
                        #{:kind (payload-key kind)})]
    (when (or (nil? expected-keys)
              (not= (set (keys outcome)) expected-keys))
      (invalid! ::invalid-outcome
                {:reason :unexpected-keys
                 :kind kind
                 :keys (set (keys outcome))})))
  outcome)

(defn- validate-terminal! [outcome]
  (validate-terminal-shape! outcome)
  ;; Validate the complete public value, including collision-free map keys and
  ;; set members, before it can cross the publication boundary.
  (trace/canonical-value outcome [:outcome])
  outcome)

(defn- validate-stored-terminal! [outcome]
  (validate-terminal-shape! outcome)
  (let [kind (:kind outcome)
        encoded (get outcome (payload-key kind))]
    (when-not (trace/canonical-form? encoded)
      (invalid! ::invalid-registry
                {:reason :stored-outcome-not-canonical
                 :kind kind})))
  outcome)

(defn- encode-terminal [outcome]
  (validate-terminal! outcome)
  (let [kind (:kind outcome)
        key (payload-key kind)]
    {:kind kind
     key (trace/canonical-value (get outcome key) [:outcome key])}))

(defn- decode-terminal [outcome]
  (validate-stored-terminal! outcome)
  (let [kind (:kind outcome)
        key (payload-key kind)]
    {:kind kind
     key (trace/restore-value (get outcome key))}))

(defn- snapshot-value [value path]
  (trace/restore-value (trace/canonical-value value path)))

(defn- pending-entry? [entry]
  (nil? (:outcome entry)))

;; Every registry crossing the boundary must be canonical and must have the
;; exact internal representation produced by this namespace. This prevents a
;; caller-supplied plain map from reaching `conj`, `sort`, or outcome reads with
;; a malformed waiter/outcome shape.
(defn- validate-registry! [registry]
  (when-not (map? registry)
    (invalid! ::invalid-registry
              {:reason :not-a-map
               :value-class (str (class registry))}))
  (trace/canonical-value registry [:registry])
  (doseq [[operation-id entry] registry]
    (validate-operation-id! operation-id)
    (when-not (and (map? entry) (not (record? entry)))
      (invalid! ::invalid-registry
                {:reason :entry-not-a-plain-map
                 :operation-id operation-id}))
    (when-not (= entry-keys (set (keys entry)))
      (invalid! ::invalid-registry
                {:reason :entry-wrong-keys
                 :operation-id operation-id
                 :keys (set (keys entry))}))
    (let [waiters (:waiters entry)
          terminal (:outcome entry)]
      (when-not (set? waiters)
        (invalid! ::invalid-registry
                  {:reason :waiters-not-a-set
                   :operation-id operation-id}))
      (doseq [task-id waiters]
        (validate-task-id! task-id))
      (when (some? terminal)
        (validate-stored-terminal! terminal)
        (when (seq waiters)
          (invalid! ::invalid-registry
                    {:reason :published-entry-retains-waiters
                     :operation-id operation-id})))))
  registry)

(defn- validated-result [result]
  (trace/canonical-value result [:result])
  result)

(defn empty-registry
  "Returns a fresh, empty completion registry."
  []
  {})

(defn success
  "Returns the terminal success outcome carrying `value`.

  `value` must be accepted by [[jolt.sim.trace/canonical-value]], so a raw
  [[Throwable]] is rejected here (use [[failure]] for exceptions)."
  [value]
  {:kind :success :value (snapshot-value value [:success])})

(defn failure
  "Returns the terminal failure outcome carrying `error`.

  `error` is first passed through [[jolt.sim.trace/normalize-error]], which
  replaces any [[Throwable]] (recursively) with plain immutable data; this is
  the only entry point that accepts a host exception. The normalized error
  must then be accepted by [[jolt.sim.trace/canonical-value]]."
  [error]
  (let [normalized (trace/normalize-error error)]
    {:kind :failure :error (snapshot-value normalized [:failure])}))

(defn cancellation
  "Returns the terminal cancellation outcome carrying `reason`.

  `reason` must be accepted by [[jolt.sim.trace/canonical-value]]."
  [reason]
  {:kind :cancellation :reason (snapshot-value reason [:cancellation])})

(defn register
  "Adds a pending entry for `operation-id` to `registry` and returns the next
  registry.

  Throws a typed [[ex-info]] if `registry` is not a canonicalizable map, if
  `operation-id` is not a non-negative integer, or if `operation-id` is
  already registered."
  [registry operation-id]
  (validate-registry! registry)
  (validate-operation-id! operation-id)
  (when (contains? registry operation-id)
    (invalid! ::duplicate-operation {:operation-id operation-id}))
  (let [next (assoc registry operation-id {:waiters #{} :outcome nil})]
    (validate-registry! next)))

(defn register-waiter
  "Registers `task-id` as a waiter on the pending operation `operation-id`.

  While the operation is pending, returns
  `{:registry next :ready? false}` with `task-id` added (deduplicated) to the
  operation's internal waiter set. Once the operation has been published,
  returns `{:registry registry :ready? true :outcome stored}` and leaves the
  registry unchanged.

  Throws a typed [[ex-info]] if `registry`, `operation-id`, or `task-id` is
  invalid, or if `operation-id` is unknown."
  [registry operation-id task-id]
  (validate-registry! registry)
  (validate-operation-id! operation-id)
  (validate-task-id! task-id)
  (when-not (contains? registry operation-id)
    (invalid! ::unknown-operation {:operation-id operation-id}))
  (let [entry (get registry operation-id)]
    (if (pending-entry? entry)
      (let [next (assoc registry operation-id
                        (assoc entry :waiters (conj (:waiters entry) task-id)))]
        (validate-registry! next)
        (validated-result {:registry next :ready? false}))
      (validated-result
       {:registry registry
        :ready? true
        :outcome (decode-terminal (:outcome entry))}))))

(defn outcome
  "Returns the terminal outcome recorded for `operation-id`, or `nil` while the
  operation is still pending.

  Throws a typed [[ex-info]] if `registry` or `operation-id` is invalid, or if
  `operation-id` is unknown."
  [registry operation-id]
  (validate-registry! registry)
  (validate-operation-id! operation-id)
  (when-not (contains? registry operation-id)
    (invalid! ::unknown-operation {:operation-id operation-id}))
  (let [stored (:outcome (get registry operation-id))]
    (when (some? stored)
      (decode-terminal stored))))

(defn publish
  "Atomically, as pure data, publishes `terminal-outcome` for `operation-id`.

  The first publication records `terminal-outcome`, clears the operation's
  waiters, and returns
  `{:registry next :won? true :wake sorted-vector :outcome terminal-outcome}`,
  where `sorted-vector` is the operation's prior waiters in ascending order.

  Later publications leave the registry unchanged and return
  `{:registry registry :won? false :wake [] :outcome existing}`.

  Throws a typed [[ex-info]] if `registry`, `operation-id`, or
  `terminal-outcome` is invalid, or if `operation-id` is unknown."
  [registry operation-id terminal-outcome]
  (validate-registry! registry)
  (validate-operation-id! operation-id)
  (validate-terminal! terminal-outcome)
  (when-not (contains? registry operation-id)
    (invalid! ::unknown-operation {:operation-id operation-id}))
  (let [entry (get registry operation-id)]
    (if (pending-entry? entry)
      (let [wake (into [] (sort (:waiters entry)))
            stored (encode-terminal terminal-outcome)
            public (decode-terminal stored)
            next (assoc registry operation-id
                        {:waiters #{} :outcome stored})]
        (validate-registry! next)
        (validated-result
         {:registry next
          :won? true
          :wake wake
          :outcome public}))
      (validated-result
       {:registry registry
        :won? false
        :wake []
        :outcome (decode-terminal (:outcome entry))}))))
