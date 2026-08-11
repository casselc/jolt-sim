(ns jolt.sim.flow-effect-session
  "Commit-gated retained effects over one pure cooperative Session.

  Flow branch previews may construct inert canonical intent records. This
  capability owns the only raw Session reference and authorizes external
  publication only after an exact revision-scoped branch commits. One
  borrowed worker service owns command publication and exact reconciliation;
  this namespace never starts, retries, or terminates it.

  The ledger is process-lifetime state. It prevents preview, stale-branch,
  replay, and concurrent-call duplication in one live capability, but does not
  claim parent-crash recovery across Session commit and ledger update."
  (:require [clojure.core.protocols :as protocols]
            [jolt.sim.retained-process :as retained]
            [jolt.sim.session :as session]
            [jolt.sim.trace :as trace]))

(declare snapshot branches journal step! reconcile! reconcile-step close!)

(def ^:private snapshot-operation (Object.))
(def ^:private branches-operation (Object.))
(def ^:private journal-operation (Object.))
(def ^:private step-operation (Object.))
(def ^:private reconcile-operation (Object.))
(def ^:private reconcile-step-operation (Object.))
(def ^:private close-operation (Object.))

(def ^:private worker-keys #{:command! :reconcile! :snapshot})

(defn retained-worker-service
  "Adapts one caller-owned retained-process handle to the closed service seam
  accepted by `attach!`. The returned closures borrow the handle and never
  terminate it."
  [handle]
  {:command! #(retained/command! handle %)
   :reconcile! #(retained/reconcile! handle)
   :snapshot #(retained/snapshot handle)})

(defn- invalid! [message data]
  (throw (ex-info message (assoc data :type ::invalid-config))))

(defn- checked-config [{:keys [sim worker effect-kind] :as config}]
  (when-not (and (map? config)
                 (= #{:sim :worker :effect-kind} (set (keys config))))
    (invalid! "Flow effect Session config has an invalid shape" {}))
  (when-not (map? sim)
    (invalid! "Flow effect Session requires a simulation config" {}))
  (when-not (and (map? worker)
                 (= worker-keys (set (keys worker)))
                 (every? fn? (vals worker)))
    (invalid! "Flow effect Session requires one closed worker service" {}))
  (when-not (and (keyword? effect-kind) (some? (namespace effect-kind)))
    (invalid! "Flow effect kind must be a namespaced keyword"
              {:effect-kind effect-kind}))
  config)

(defn- canonical [value path]
  (trace/restore-value (trace/canonical-value value path)))

(defn- intents [session-snapshot]
  (vec (or (get-in (trace/restore-value (:projection session-snapshot))
                   [:world :effect-intents])
           [])))

(defn- worker-coordinate [worker]
  (try
    (let [value ((:snapshot worker))]
      {:status (:status value)
       :next-sequence (:next-sequence value)
       :uncertain-sequence (:uncertain-sequence value)})
    (catch :default error
      {:status :snapshot-failed
       :error (trace/normalize-error error)})))

(defn- public-record [record]
  (dissoc record :authorization))

(defn- summary* [{:keys [session status seen-intents records pending
                         remaining commits closed? worker]}]
  {:status status
   :closed? closed?
   :session (session/snapshot session)
   :effects {:seen-intents seen-intents
             :records (mapv public-record records)
             :pending (some-> pending public-record)
             :remaining (mapv #(select-keys % [:id :kind :source]) remaining)}
   :commits {:count (count commits)}
   :worker (worker-coordinate worker)})

(defn snapshot
  "Returns the current UI-neutral process-lifetime bridge summary."
  [bridge]
  (bridge snapshot-operation nil))

(defn branches
  "Returns pure deterministic Session branch previews. No worker closure is
  called by this operation."
  [bridge]
  (bridge branches-operation nil))

(defn journal
  "Returns the immutable process-lifetime bridge commit/effect ledger."
  [bridge]
  (bridge journal-operation nil))

(defn- checked-intents [effect-kind before after]
  (when-not (and (<= (count before) (count after))
                 (= before (subvec after 0 (count before))))
    (throw (ex-info "Committed flow intents are not append-only"
                    {:type ::invalid-intent-transition})))
  (let [new-intents (subvec after (count before))]
    (doseq [intent new-intents]
      (when-not (and (map? intent)
                     (= #{:id :kind :payload :source} (set (keys intent)))
                     (= effect-kind (:kind intent)))
        (throw (ex-info "Committed flow intent is unsupported"
                        {:type ::unsupported-intent
                         :expected-kind effect-kind
                         :actual-kind (:kind intent)})))
      (canonical intent [:flow-effect :intent]))
    new-intents))

(defn- valid-receipt? [receipt]
  (and (map? receipt)
       (contains? #{:completed :failed} (:status receipt))
       (integer? (:sequence receipt))
       (not (neg? (:sequence receipt)))
       (if (= :completed (:status receipt))
         (and (contains? receipt :value) (not (contains? receipt :error)))
         (and (contains? receipt :error) (not (contains? receipt :value))))))

(defn- receipt-record [authorized receipt]
  (when-not (valid-receipt? receipt)
    (throw (ex-info "Worker returned a malformed definite receipt"
                    {:type ::invalid-worker-receipt})))
  (canonical
   (-> authorized
       (dissoc :authorization :error :worker)
       (assoc :state :settled
              :sequence (:sequence receipt)
              :status (:status receipt)
              :receipt receipt))
   [:flow-effect :receipt (:id authorized)]))

(defn- error-record [authorized state error worker-coordinate]
  (canonical
   (-> authorized
       (dissoc :authorization)
       (assoc :state state
              :error (trace/normalize-error error)
              :worker worker-coordinate))
   [:flow-effect :error (:id authorized)]))

(defn- replace-record [records id replacement]
  (mapv #(if (= id (:id %)) replacement %) records))

(defn- dispatch-loop! [state]
  (loop []
    (let [{:keys [worker remaining]} @state]
      (if-let [authorized (first remaining)]
        (do
          ;; The authorization is installed before this call and consumed by
          ;; moving the intent into :publishing. No public operation can enter
          ;; while the bridge lock is held.
          (swap! state
                 (fn [current]
                   (-> current
                       (assoc :status :publishing
                              :remaining (subvec (:remaining current) 1)
                              :pending (assoc authorized :state :publishing))
                       (update :records replace-record (:id authorized)
                               (assoc authorized :state :publishing)))))
          (let [outcome
                (try
                  {:receipt ((:command! worker) (:payload authorized))}
                  (catch :default error {:error error}))]
            (if-let [receipt (:receipt outcome)]
              (try
                (let [settled (receipt-record authorized receipt)]
                  (swap! state
                         (fn [current]
                           (-> current
                               (assoc :status :ready :pending nil)
                               (update :records replace-record
                                       (:id authorized) settled))))
                  (recur))
                (catch :default error
                  (let [failed (error-record authorized :failed error
                                             (worker-coordinate worker))]
                    (swap! state
                           (fn [current]
                             (-> current
                                 (assoc :status :failed :pending nil
                                        :remaining [])
                                 (update :records replace-record
                                         (:id authorized) failed)))))))
              (let [coordinate (worker-coordinate worker)
                    uncertain? (and (integer? (:uncertain-sequence coordinate))
                                    (not (neg? (:uncertain-sequence coordinate))))
                    failed (error-record authorized
                                         (if uncertain? :uncertain :failed)
                                         (:error outcome) coordinate)]
                (swap! state
                       (fn [current]
                         (-> current
                             (assoc :status (if uncertain? :uncertain :failed)
                                    :pending (when uncertain? failed)
                                    :remaining (if uncertain?
                                                 (:remaining current)
                                                 []))
                             (update :records replace-record
                                     (:id authorized) failed))))))))
        (swap! state assoc :status :ready :pending nil)))))

(defn- ensure-open-ready! [current]
  (when (:closed? current)
    (throw (ex-info "Flow effect Session is closed"
                    {:type ::rejected :reason :closed})))
  (when-not (= :ready (:status current))
    (throw (ex-info "Flow effect Session is not ready to step"
                    {:type ::rejected :reason (:status current)}))))

(defn- commit-id [revision intent-id]
  [:jolt.sim.flow-effect/commit revision intent-id])

(defn- step-state! [state branch]
  (let [before @state]
    (ensure-open-ready! before)
    (let [session-before (session/snapshot (:session before))
          before-intents (intents session-before)
          after-session (session/step! (:session before) branch)
          after-intents (intents after-session)
          revision (:revision after-session)
          prepared
          (try
            (let [new-intents (checked-intents (:effect-kind before)
                                               before-intents after-intents)
                  tail (peek (session/journal (:session before)))]
              (when-not (and (= revision (:seq tail))
                             (= branch (:branch tail)))
                (throw (ex-info "Committed Session journal tail is incoherent"
                                {:type ::incoherent-commit
                                 :revision revision})))
              (let [authorized
                    (mapv (fn [intent]
                            (canonical
                             {:id (commit-id revision (:id intent))
                              :intent-id (:id intent)
                              :kind (:kind intent)
                              :payload (:payload intent)
                              :source (:source intent)
                              :revision revision
                              :branch branch
                              :state :authorized
                              :authorization :single-use}
                             [:flow-effect :authorization revision]))
                          new-intents)]
                {:authorized authorized
                 :commit
                 (canonical
                  {:event :jolt.sim.flow-effect/committed
                   :revision revision
                   :branch branch
                   :intent-ids (mapv :id authorized)}
                  [:flow-effect :commit revision])}))
            (catch :default error {:error error}))]
      (if-let [error (:error prepared)]
        ;; The Session commit is already authoritative. Translate every later
        ;; validation/coordination failure into an explicit committed failure;
        ;; never throw in a shape that invites the branch to be retried.
        (let [commit
              (canonical
               {:event :jolt.sim.flow-effect/commit-failed
                :revision revision
                :branch branch
                :error (trace/normalize-error error)}
               [:flow-effect :commit-failed revision])]
          (swap! state
                 (fn [current]
                   (-> current
                       (assoc :status :failed
                              :seen-intents (count after-intents)
                              :last-step {:branch branch :revision revision}
                              :remaining [] :pending nil)
                       (update :commits conj commit))))
          {:committed? true
           :branch branch
           :revision revision
           :delivery (select-keys (summary* @state)
                                  [:status :effects :worker])})
        (let [{:keys [authorized commit]} prepared]
          ;; Install every authorization before any external publication.
          (swap! state
                 (fn [current]
                   (-> current
                       (assoc :seen-intents (count after-intents)
                              :last-step {:branch branch :revision revision}
                              :remaining authorized)
                       (update :commits conj commit)
                       (update :records into authorized))))
          (try
            (dispatch-loop! state)
            (catch :default error
              (swap! state assoc :status :failed :remaining [] :pending nil)
              (swap! state update :commits conj
                     (canonical
                      {:event :jolt.sim.flow-effect/dispatch-failed
                       :revision revision
                       :branch branch
                       :error (trace/normalize-error error)}
                      [:flow-effect :dispatch-failed revision]))))
          {:committed? true
           :branch branch
           :revision revision
           :delivery (select-keys (summary* @state)
                                  [:status :effects :worker])})))))

(defn step!
  "Commits one exact Session branch, then publishes newly committed intents at
  most once. A returned `:committed? true` remains authoritative even when
  delivery is uncertain or failed; callers must never retry that branch."
  [bridge branch]
  (bridge step-operation branch))

(defn- settle-reconciled! [state receipt]
  (let [{:keys [pending]} @state
        settled (receipt-record pending receipt)]
    (swap! state
           (fn [current]
             (-> current
                 (assoc :status :ready :pending nil)
                 (update :records replace-record (:id pending) settled))))
    (dispatch-loop! state)))

(defn- reconcile-state! [state]
  (let [{:keys [closed? status worker pending]} @state]
    (when closed?
      (throw (ex-info "Flow effect Session is closed"
                      {:type ::rejected :reason :closed})))
    (when-not (and (= :uncertain status) pending)
      (throw (ex-info "Flow effect Session has no uncertain publication"
                      {:type ::rejected :reason :nothing-to-reconcile})))
    (let [outcome (try
                    {:receipt ((:reconcile! worker))}
                    (catch :default error {:error error}))]
      (if-let [receipt (:receipt outcome)]
        (try
          (settle-reconciled! state receipt)
          (catch :default error
            (let [failed (error-record pending :failed error
                                       (worker-coordinate worker))]
              (swap! state
                     (fn [current]
                       (-> current
                           (assoc :status :failed :pending nil :remaining [])
                           (update :records replace-record (:id pending)
                                   failed)))))))
        (let [coordinate (worker-coordinate worker)
              still-uncertain?
              (and (integer? (:uncertain-sequence coordinate))
                   (= (:uncertain-sequence coordinate)
                      (get-in pending [:worker :uncertain-sequence])))]
          (if still-uncertain?
            (let [uncertain (error-record pending :uncertain
                                          (:error outcome) coordinate)]
              (swap! state
                     (fn [current]
                       (-> current
                           (assoc :status :uncertain :pending uncertain)
                           (update :records replace-record (:id pending)
                                   uncertain)))))
            (let [failed (error-record pending :failed
                                       (:error outcome) coordinate)]
              (swap! state
                     (fn [current]
                       (-> current
                           (assoc :status :failed :pending nil :remaining [])
                           (update :records replace-record (:id pending)
                                   failed))))))))
      (select-keys (summary* @state) [:status :effects :worker]))))

(defn reconcile!
  "Reconciles only the exact retained sequence already marked uncertain. It
  never republishes an intent."
  [bridge]
  (bridge reconcile-operation nil))

(defn- reconcile-step-state [state supplied]
  (when-not (and (map? supplied)
                 (= #{:revision :action} (set (keys supplied)))
                 (integer? (:revision supplied))
                 (not (neg? (:revision supplied)))
                 (vector? (:action supplied))
                 (= 2 (count (:action supplied))))
    (throw (ex-info "Flow effect step coordinate is malformed"
                    {:type ::invalid-step-coordinate})))
  (let [branch (canonical supplied [:flow-effect :reconcile-step])
        revision (:revision branch)
        match (some #(when (= revision (get-in % [:branch :revision])) %)
                    (:commits @state))]
    (cond
      (nil? match) {:status :missing :branch branch}
      (= branch (:branch match))
      {:status :committed
       :branch branch
       :revision (:revision match)
       :intent-ids (:intent-ids match)}
      :else {:status :different
             :branch branch
             :committed-branch (:branch match)
             :revision (:revision match)})))

(defn reconcile-step
  "Reports whether an exact branch already committed. This operation is
  read-only and never steps the Session or contacts the worker."
  [bridge branch]
  (bridge reconcile-step-operation branch))

(defn- close-state! [state]
  (swap! state assoc :closed? true :status :closed)
  (summary* @state))

(defn close!
  "Closes bridge admission idempotently. The borrowed retained worker remains
  entirely caller-owned and is never terminated."
  [bridge]
  (bridge close-operation nil))

(defn attach!
  "Creates an opaque bridge over one pure simulation and one borrowed worker
  service. See the namespace contract."
  [config]
  (let [{:keys [sim worker effect-kind]} (checked-config config)
        owned-session (session/start sim)
        initial-intents (intents (session/snapshot owned-session))
        state (atom {:session owned-session
                     :worker worker
                     :effect-kind effect-kind
                     :status :ready
                     :closed? false
                     :seen-intents (count initial-intents)
                     :records []
                     :pending nil
                     :remaining []
                     :commits []
                     :last-step nil})
        lock (Object.)]
    (reify
      clojure.lang.IFn
      (invoke [_ operation argument]
        (locking lock
          (cond
            (identical? operation snapshot-operation) (summary* @state)
            (identical? operation branches-operation)
            (do (ensure-open-ready! @state)
                (session/branches (:session @state)))
            (identical? operation journal-operation)
            (canonical (into (:commits @state)
                             (map public-record (:records @state)))
                       [:flow-effect :journal])
            (identical? operation step-operation) (step-state! state argument)
            (identical? operation reconcile-operation) (reconcile-state! state)
            (identical? operation reconcile-step-operation)
            (reconcile-step-state state argument)
            (identical? operation close-operation) (close-state! state)
            :else
            (throw (ex-info "Unknown flow effect Session operation"
                            {:type ::invalid-operation})))))

      protocols/Datafiable
      (datafy [this] (snapshot this))

      protocols/Navigable
      (nav [this key value]
        (case key
          :session (if (= value (:session (snapshot this)))
                     (branches this)
                     value)
          :commits (if (= value (:commits (snapshot this)))
                     (journal this)
                     value)
          value)))))
