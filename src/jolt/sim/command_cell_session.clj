(ns jolt.sim.command-cell-session
  "UI-neutral control of one exact, previewable command cell at a time.

  Canonical descriptors are data. Trusted compilers, retained-worker services,
  receipt projectors, workbench capture, and tap functions remain process-local
  capabilities. A selected input is validated against its cell's closed schema
  before its trusted compiler runs. The resulting flow/effect bridge remains the
  sole owner of branch commitment, one-shot publication, and reconciliation.

  The outer revision prevents a branch from an earlier prepared cell from being
  used after replacement. The inner branch is the exact revision/action pair
  issued by jolt.sim.session. Preview never calls a worker service. A definite
  external result permits another cell; an uncertain result must first be
  reconciled. Workbench capture remains serialized with the authoritative
  mutation so later evidence cannot overtake it. Bounded tap events run after
  that lock is released and cannot obscure the result."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.flow-effect-session :as effect-session]
            [jolt.sim.flow-effect-view :as effect-view]
            [jolt.sim.schema :as schema]
            [jolt.sim.trace :as trace]
            [jolt.sim.workbench-session :as workbench-session]))

(declare catalog snapshot branches prepare! step! reconcile! close!)

(def rejected :jolt.sim.command-cell-session/rejected)

(def ^:private descriptor-keys
  #{:effect-kind :input-schema :output-schema :projector :suggested-kind})
(def ^:private config-keys
  #{:descriptors :trusted :evidence-stream-id})
(def ^:private maximum-evidence-stream-id-length 96)
(def ^:private maximum-workbench-item-id-length 128)
(def ^:private evidence-events
  #{:prepare :commit :reconcile :projected-receipt})
(def ^:private trusted-required-keys
  #{:compilers :workers :projectors :workbench})
(def ^:private trusted-keys (conj trusted-required-keys :tap!))
(def ^:private worker-keys #{:command! :reconcile! :snapshot})
(def ^:private workbench-keys #{:append-item!})

(def ^:private catalog-operation (Object.))
(def ^:private snapshot-operation (Object.))
(def ^:private branches-operation (Object.))
(def ^:private prepare-operation (Object.))
(def ^:private step-operation (Object.))
(def ^:private reconcile-operation (Object.))
(def ^:private close-operation (Object.))
(def ^:private navigate-operation (Object.))

(defn workbench-service
  "Borrows one WorkbenchSession as the append-only evidence sink."
  [session]
  {:append-item! #(workbench-session/append-item! session %)})

(defn- reject! [reason detail]
  (throw (ex-info "Command cell Session rejected an operation"
                  {:type rejected :reason reason :detail detail})))

(defn- exact-map? [value expected]
  (and (map? value)
       (= (count value) (count expected))
       (every? #(contains? value %) expected)))

(defn- namespaced-keyword? [value]
  (and (keyword? value) (some? (namespace value))))

(defn- evidence-item-id [evidence-stream-id event]
  (str "command-cell/" evidence-stream-id "/" (name event)))

(defn- bounded-stream-id? [value]
  (and (string? value)
       (pos? (count value))
       (<= (count value) maximum-evidence-stream-id-length)
       (every? #(<= (count (evidence-item-id value %))
                    maximum-workbench-item-id-length)
               evidence-events)))

(defn- canonical [value path]
  (trace/restore-value (trace/canonical-value value path)))

(defn- bounded-error [error]
  (let [data (ex-data error)]
    {:type (if (keyword? (:type data)) (:type data) :jolt.sim/error)
     :reason (if (keyword? (:reason data)) (:reason data) :unknown)}))

(defn- validate-worker! [cell-id worker]
  (when-not (and (exact-map? worker worker-keys)
                 (every? fn? (vals worker)))
    (reject! :invalid-worker {:cell-id cell-id})))

(defn- validate-descriptor! [cell-id descriptor]
  (when-not (namespaced-keyword? cell-id)
    (reject! :invalid-cell-id cell-id))
  (when-not (exact-map? descriptor descriptor-keys)
    (reject! :invalid-descriptor-shape
             {:cell-id cell-id
              :keys (when (map? descriptor) (set (keys descriptor)))}))
  (let [descriptor (canonical descriptor
                              [:command-cell :descriptor cell-id])]
    (when-not (namespaced-keyword? (:effect-kind descriptor))
      (reject! :invalid-effect-kind {:cell-id cell-id}))
    (when-not (namespaced-keyword? (:projector descriptor))
      (reject! :invalid-projector-id {:cell-id cell-id}))
    (when-not (or (nil? (:suggested-kind descriptor))
                  (namespaced-keyword? (:suggested-kind descriptor)))
      (reject! :invalid-suggested-kind {:cell-id cell-id}))
    (let [context {:kind :command-cell :pack-id cell-id}]
      (assoc descriptor
             :compiled-input
             (schema/compile! (assoc context :field :input)
                              (:input-schema descriptor))
             :compiled-output
             (schema/compile! (assoc context :field :output)
                              (:output-schema descriptor))))))

(defn- checked-config [config]
  (when-not (exact-map? config config-keys)
    (reject! :invalid-config-shape
             (when (map? config) (set (keys config)))))
  (let [{:keys [descriptors trusted evidence-stream-id]} config]
    (when-not (bounded-stream-id? evidence-stream-id)
      (reject! :invalid-evidence-stream-id
               {:maximum-stream-length maximum-evidence-stream-id-length
                :maximum-item-id-length maximum-workbench-item-id-length}))
    (when-not (and (map? descriptors) (seq descriptors))
      (reject! :invalid-descriptors nil))
    (when-not (and (map? trusted)
                   (every? #(contains? trusted %) trusted-required-keys)
                   (every? trusted-keys (keys trusted)))
      (reject! :invalid-trusted-registry
               (when (map? trusted) (set (keys trusted)))))
    (let [{:keys [compilers workers projectors workbench tap!]} trusted]
      (when-not (and (map? compilers) (map? workers) (map? projectors))
        (reject! :invalid-trusted-registry nil))
      (when-not (and (exact-map? workbench workbench-keys)
                     (fn? (:append-item! workbench)))
        (reject! :invalid-workbench-service nil))
      (when-not (or (nil? tap!) (fn? tap!))
        (reject! :invalid-tap nil))
      (let [compiled
            (into {}
                  (map (fn [[cell-id descriptor]]
                         [cell-id (validate-descriptor! cell-id descriptor)]))
                  descriptors)
            ids (set (keys compiled))]
        (when-not (= ids (set (keys compilers)) (set (keys workers)))
          (reject! :cell-registry-mismatch
                   {:descriptors ids
                    :compilers (set (keys compilers))
                    :workers (set (keys workers))}))
        (doseq [cell-id ids]
          (when-not (fn? (get compilers cell-id))
            (reject! :invalid-compiler {:cell-id cell-id}))
          (validate-worker! cell-id (get workers cell-id))
          (let [projector-id (get-in compiled [cell-id :projector])]
            (when-not (fn? (get projectors projector-id))
              (reject! :missing-projector
                       {:cell-id cell-id :projector projector-id}))))
        {:descriptors compiled
         :catalog
         (mapv (fn [cell-id]
                 (assoc (select-keys (get compiled cell-id) descriptor-keys)
                        :id cell-id))
               (sort-by pr-str ids))
         :compilers compilers
         :workers workers
         :projectors projectors
         :workbench workbench
         :tap! (or tap! #(tap> %))
         :evidence-stream-id evidence-stream-id}))))

(defn- active-summary [active]
  (when active
    (cond-> {:cell-id (:cell-id active)
             :prepared-revision (:prepared-revision active)
             :phase (:phase active)
             :input (:input active)}
      (:last-coordinate active)
      (assoc :last-coordinate (:last-coordinate active))
      (:delivery active) (assoc :delivery (:delivery active))
      (:projection active) (assoc :projection (:projection active)))))

(defn- snapshot-state
  [{:keys [revision closed? active catalog evidence-stream-id]}]
  {:kind :jolt.sim.kind/command-cell-session
   :evidence-stream-id evidence-stream-id
   :revision revision
   :closed? closed?
   :catalog {:count (count catalog)}
   :active (when active
             {:revision revision
              :cell-id (:cell-id active)
              :phase (:phase active)})
   :cell (active-summary active)})

(defn catalog
  "Returns stable canonical descriptors. No trusted capability is exposed."
  [session]
  (session catalog-operation nil))

(defn snapshot
  "Returns a cheap canonical summary without inspecting the worker."
  [session]
  (session snapshot-operation nil))

(defn- checked-outer-revision! [state supplied]
  (when-not (and (integer? supplied) (not (neg? supplied)))
    (reject! :invalid-revision supplied))
  (when-not (= supplied (:revision state))
    (reject! :stale-revision
             {:expected supplied :actual (:revision state)})))

(defn- prepared? [active]
  (= :prepared (:phase active)))

(defn- branches-state [state]
  (let [{:keys [revision closed? active evidence-stream-id]} state]
    (when closed? (reject! :closed nil))
    (when-not (prepared? active)
      (reject! :not-prepared (some-> active :phase)))
    (mapv (fn [preview]
            (assoc preview
                   :evidence-stream-id evidence-stream-id
                   :coordinate
                   {:revision revision :branch (:branch preview)}))
          (effect-session/branches (:bridge active)))))

(defn branches
  "Returns pure previews with an exact outer revision and inner branch.

  Neither this operation nor Session branch evaluation calls any worker
  closure."
  [session]
  (session branches-operation nil))

(defn- evidence-item
  [evidence-stream-id event revision cell-id value descriptor]
  {:id (evidence-item-id evidence-stream-id event)
   :source-revision revision
   :value value
   :schema-id (keyword "jolt.sim.command-cell" (name event))
   :suggested-kind (when (= :projected-receipt event)
                     (:suggested-kind descriptor))
   :provenance
   {:producer :jolt.sim.command-cell-session/evidence
    :coordinate {:evidence-stream-id evidence-stream-id
                 :event event :revision revision :cell-id cell-id}}})

(defn- capture-items! [workbench items]
  (reduce
   (fn [errors item]
     (try
       ((:append-item! workbench) item)
       errors
       (catch :default error
         (conj errors
               {:item-id (:id item)
                :source-revision (:source-revision item)
                :error (bounded-error error)}))))
   []
   items))

(defn- publish! [tap! event]
  (try (tap! (canonical event [:command-cell :tap]))
       nil
       (catch :default error (bounded-error error))))

(defn- capture-result [{:keys [workbench]} core]
  (let [capture-errors (capture-items! workbench (:items core))
        result (cond-> (:result core)
                 (seq capture-errors) (assoc :capture-errors capture-errors))]
    result))

(defn- finish-result [{:keys [tap!]} operation result]
  (let [tap-error (publish! tap!
                            (cond->
                             {:event :jolt.sim.command-cell/operation
                              :operation operation
                              :evidence-stream-id
                              (:evidence-stream-id result)
                              :revision (:revision result)
                              :cell-id (:cell-id result)
                              :status (:status result)
                              :capture-error-count
                              (count (:capture-errors result))}
                             (:committed? result)
                             (assoc :committed? (:committed? result))))]
    (cond-> result tap-error (assoc :tap-error tap-error))))

(defn- serialized-mutation!
  "Runs mutation plus evidence capture under one non-reentrant boundary.

  `locking` alone is insufficient because it is reentrant. Workbench append
  publishes its own tap before returning, so a callback that attempts another
  mutation must fail explicitly instead of entering the same Session again."
  [state lock services operation thunk]
  (let [result
        (locking lock
          (when (:mutating? @state)
            (reject! :reentrant-mutation {:operation operation}))
          (swap! state assoc :mutating? true)
          (try
            (capture-result services (thunk))
            (finally
              (swap! state assoc :mutating? false))))]
    ;; tap is observational and runs only after mutation and evidence order are
    ;; definite and the serialization lock has been released.
    (finish-result services operation result)))

(defn- prepare-state! [state request descriptors compilers workers]
  (when-not (exact-map? request #{:revision :cell-id :input})
    (reject! :invalid-prepare-shape
             (when (map? request) (set (keys request)))))
  (let [before @state
        _ (checked-outer-revision! before (:revision request))
        _ (when (:closed? before) (reject! :closed nil))
        active (:active before)
        evidence-stream-id (:evidence-stream-id before)]
    (when (= :uncertain (:phase active))
      (reject! :uncertain {:cell-id (:cell-id active)}))
    (when (= :prepared (:phase active))
      (reject! :active-cell {:cell-id (:cell-id active)}))
    (let [cell-id (:cell-id request)
          descriptor (get descriptors cell-id)]
      (when-not descriptor (reject! :unknown-cell cell-id))
      ;; The exact selected-cell boundary is proved before trusted compile code
      ;; can observe the value.
      (schema/validate! (:compiled-input descriptor) (:input request)
                        {:kind :command-cell :pack-id cell-id :field :input})
      (let [input (canonical (:input request)
                             [:command-cell :prepare cell-id :input])
            sim (try
                  ((get compilers cell-id) input)
                  (catch :default error
                    (throw (ex-info "Command cell compiler failed"
                                    {:type rejected
                                     :reason :compiler-failed
                                     :detail {:cell-id cell-id}}
                                    error))))
            bridge (effect-session/attach!
                    {:sim sim
                     :worker (get workers cell-id)
                     :effect-kind (:effect-kind descriptor)})
            revision (inc (:revision before))
            active {:cell-id cell-id
                    :descriptor descriptor
                    :bridge bridge
                    :input input
                    :prepared-revision revision
                    :phase :prepared}]
        (when-let [prior (:bridge (:active before))]
          (effect-session/close! prior))
        (reset! state (assoc before :revision revision :active active))
        (let [result {:kind :jolt.sim.kind/command-cell-prepare-result
                      :operation :prepare
                      :evidence-stream-id evidence-stream-id
                      :revision revision
                      :cell-id cell-id
                      :status :prepared}]
          {:result result
           :items [(evidence-item evidence-stream-id :prepare revision cell-id
                                  (assoc result :input input) descriptor)]})))))

(defn prepare!
  "Validates and prepares one exact cell at the caller-observed revision.

  A prepared cell cannot be replaced. A definite prior result can be replaced;
  an uncertain one must be reconciled first. Compilation and preview perform no
  worker operation."
  [session request]
  (session prepare-operation request))

(defn- checked-step-coordinate! [state coordinate]
  (when-not (exact-map? coordinate #{:revision :branch})
    (reject! :invalid-step-coordinate
             (when (map? coordinate) (set (keys coordinate)))))
  (checked-outer-revision! state (:revision coordinate))
  (when-not (and (map? (:branch coordinate))
                 (= #{:revision :action} (set (keys (:branch coordinate)))))
    (reject! :invalid-inner-branch nil)))

(defn- completed-record-state [delivery]
  (let [records (get-in delivery [:effects :records])]
    (cond
      (not= 1 (count records))
      {:status :failed
       :error {:type rejected :reason :invalid-effect-count}}

      (and (= :settled (:state (first records)))
           (= :completed (:status (first records))))
      {:status :completed :record (first records)}

      :else {:status :not-completed})))

(defn- project-completed [active delivery projectors]
  (let [{:keys [status record] :as record-state}
        (completed-record-state delivery)]
    (case status
      :failed record-state
      :completed
      (let [descriptor (:descriptor active)
            projector (get projectors (:projector descriptor))]
        (try
          (let [projected (projector (get-in record [:receipt :value]))]
            (schema/validate! (:compiled-output descriptor) projected
                              {:kind :command-cell
                               :pack-id (:cell-id active)
                               :field :output})
            {:status :projected
             :value (canonical projected
                               [:command-cell :projected (:cell-id active)])})
          (catch :default error
            {:status :failed :error (bounded-error error)})))
      nil)))

(defn- terminal-phase [status]
  (if (= :uncertain status) :uncertain :definite))

(defn- step-state! [state coordinate projectors]
  (let [before @state
        _ (when (:closed? before) (reject! :closed nil))
        _ (checked-step-coordinate! before coordinate)
        active (:active before)
        evidence-stream-id (:evidence-stream-id before)]
    (when-not (prepared? active)
      (reject! :not-prepared (some-> active :phase)))
    ;; The view delegates exact commitment/publication to the existing bridge
    ;; and preserves its authoritative acknowledgment if later framing fails.
    (let [view-result (effect-view/step-frame!
                       (:bridge active) (:branch coordinate) 0)
          committed? (:committed? view-result)]
      (when-not committed?
        (reject! :inner-branch-stale (:status view-result)))
      (let [delivery (:flow-effect view-result)
            projection (project-completed active delivery projectors)
            revision (inc (:revision before))
            next-active (assoc active
                               :phase (terminal-phase (:status delivery))
                               :last-coordinate coordinate
                               :delivery delivery
                               :projection projection)
            result (cond->
                    {:kind :jolt.sim.kind/command-cell-step-result
                     :operation :step
                     :evidence-stream-id evidence-stream-id
                     :revision revision
                     :cell-id (:cell-id active)
                     :status (:status delivery)
                     :committed? true
                     :coordinate coordinate
                     :delivery delivery}
                    projection (assoc :projection projection))
            projected-item
            (when (= :projected (:status projection))
              (evidence-item evidence-stream-id :projected-receipt
                             revision (:cell-id active)
                             (:value projection) (:descriptor active)))]
        (reset! state (assoc before :revision revision :active next-active))
        {:result result
         :items (cond-> [(evidence-item evidence-stream-id :commit
                                       revision (:cell-id active)
                                       result (:descriptor active))]
                  projected-item (conj projected-item))}))))

(defn step!
  "Commits one exact outer/inner coordinate and publishes at most once."
  [session coordinate]
  (session step-operation coordinate))

(defn- reconcile-state! [state projectors]
  (let [before @state
        active (:active before)
        evidence-stream-id (:evidence-stream-id before)]
    (when (:closed? before) (reject! :closed nil))
    (when-not (= :uncertain (:phase active))
      (reject! :nothing-to-reconcile (some-> active :phase)))
    (let [view-result (effect-view/reconcile-effect-frame! (:bridge active) 0)
          delivery (:flow-effect view-result)
          projection (project-completed active delivery projectors)
          revision (inc (:revision before))
          next-active (assoc active
                             :phase (terminal-phase (:status delivery))
                             :delivery delivery
                             :projection projection)
          result (cond->
                  {:kind :jolt.sim.kind/command-cell-reconcile-result
                   :operation :reconcile
                   :evidence-stream-id evidence-stream-id
                   :revision revision
                   :cell-id (:cell-id active)
                   :status (:status delivery)
                   :committed? true
                   :target (:target view-result)
                   :delivery delivery}
                  projection (assoc :projection projection))
          projected-item
          (when (= :projected (:status projection))
            (evidence-item evidence-stream-id :projected-receipt
                           revision (:cell-id active)
                           (:value projection) (:descriptor active)))]
      (reset! state (assoc before :revision revision :active next-active))
      {:result result
       :items (cond-> [(evidence-item evidence-stream-id :reconcile
                                     revision (:cell-id active)
                                     result (:descriptor active))]
                projected-item (conj projected-item))})))

(defn reconcile!
  "Reconciles the exact uncertain worker sequence without republishing it."
  [session]
  (session reconcile-operation nil))

(defn- close-state! [state]
  (let [before @state]
    (if (:closed? before)
      {:kind :jolt.sim.kind/command-cell-close-result
       :operation :close
       :evidence-stream-id (:evidence-stream-id before)
       :revision (:revision before)
       :cell-id (get-in before [:active :cell-id])
       :status :closed}
      (do
        (when-let [bridge (get-in before [:active :bridge])]
          (effect-session/close! bridge))
        (let [revision (inc (:revision before))]
          (reset! state (assoc before :closed? true :revision revision))
          {:kind :jolt.sim.kind/command-cell-close-result
           :operation :close
           :evidence-stream-id (:evidence-stream-id before)
           :revision revision
           :cell-id (get-in before [:active :cell-id])
           :status :closed})))))

(defn close!
  "Closes cell admission idempotently. Borrowed workers are never terminated."
  [session]
  (session close-operation nil))

(defn- navigate-state [state key token]
  (case key
    :catalog
    (do (when-not (= token {:count (count (:catalog state))})
          (reject! :invalid-navigation-token key))
        (:catalog state))

    :active
    (do (when-not (= token (get (snapshot-state state) :active))
          (reject! :stale-navigation key))
        {:cell (active-summary (:active state))
         :evidence-stream-id (:evidence-stream-id state)
         :branches (if (and (not (:closed? state))
                            (prepared? (:active state)))
                     (branches-state state)
                     [])})

    token))

(defn start
  "Starts a CommandCellSession over canonical descriptors and trusted code.

  `:descriptors` is keyed by namespaced cell ID. Every descriptor has exact
  keys `:effect-kind`, `:input-schema`, `:output-schema`, `:projector`, and
  `:suggested-kind`. `:trusted` contains exact per-cell `:compilers` and
  `:workers`, projector functions keyed by descriptor projector ID, a borrowed
  workbench service, and optional `:tap!`. `:evidence-stream-id` is a bounded
  caller-owned incarnation ID used in every evidence item. A fresh process or
  resumed application must supply a fresh ID unless it deliberately restores
  and coordinates the prior Session itself; this core does not allocate or
  infer resume identities. Reusing an ID may therefore produce explicit
  Workbench capture errors without obscuring the command result."
  [config]
  (let [{:keys [descriptors catalog compilers workers projectors workbench tap!
                evidence-stream-id]}
        (checked-config config)
        state (atom {:revision 0
                     :closed? false
                     :mutating? false
                     :active nil
                     :catalog catalog
                     :evidence-stream-id evidence-stream-id})
        lock (Object.)
        services {:workbench workbench :tap! tap!}]
    (reify
      clojure.lang.IFn
      (invoke [_ operation argument]
        (cond
          (identical? operation catalog-operation) catalog
          (identical? operation snapshot-operation)
          (locking lock (snapshot-state @state))
          (identical? operation branches-operation)
          (locking lock (branches-state @state))
          (identical? operation prepare-operation)
          (serialized-mutation!
           state lock services :prepare
           #(prepare-state! state argument descriptors compilers workers))
          (identical? operation step-operation)
          (serialized-mutation!
           state lock services :step
           #(step-state! state argument projectors))
          (identical? operation reconcile-operation)
          (serialized-mutation!
           state lock services :reconcile
           #(reconcile-state! state projectors))
          (identical? operation close-operation)
          (serialized-mutation!
           state lock services :close
           #(let [result (close-state! state)]
              {:result result :items []}))
          (identical? operation navigate-operation)
          (locking lock (navigate-state @state (first argument)
                                        (second argument)))
          :else (reject! :invalid-operation nil)))

      protocols/Datafiable
      (datafy [this] (snapshot this))

      protocols/Navigable
      (nav [this key value]
        (this navigate-operation [key value])))))
