(ns jolt.sim.flow-effect-view
  "UI-neutral inspection and command frames for one flow/effect bridge.

  This is a thin adapter over `jolt.sim.flow-effect-session`. It owns no
  scheduler, effect implementation, worker, or durable state. Frames are plain
  canonical Jolt data suitable for REPL inspection, datafy/nav, tap>, Ripple,
  or another UI. The append-only bridge commit prefix is paged separately from
  mutable effect delivery records, so reconciliation cannot rewrite history.

  The bridge ledger and this view remain process-lifetime capabilities. They do
  not claim parent-crash recovery between a Session commit and ledger update."
  (:require [jolt.sim.flow-effect-session :as effect-session]))

(def ^:private max-coherence-attempts 8)

(def max-journal-page-size
  "Maximum number of immutable flow/effect commit entries in one frame."
  256)

(defn- bridge-ops [bridge]
  {:snapshot #(effect-session/snapshot bridge)
   :previews #(effect-session/branches bridge)
   :journal #(effect-session/journal bridge)
   :step! #(effect-session/step! bridge %)
   :reconcile-effect! #(effect-session/reconcile! bridge)
   :reconcile-step #(effect-session/reconcile-step bridge %)
   :close! #(effect-session/close! bridge)})

(defn- validate-cursor! [cursor]
  (when-not (and (integer? cursor) (not (neg? cursor)))
    (throw
     (ex-info "Flow/effect journal cursor must be a non-negative integer"
              {:type ::invalid-cursor
               :reason :not-a-non-negative-integer
               :cursor cursor}))))

(defn- validate-cursor-bound! [cursor journal-count]
  (when (> cursor journal-count)
    (throw
     (ex-info "Flow/effect journal cursor is ahead of the commit journal"
              {:type ::invalid-cursor
               :reason :ahead-of-journal
               :cursor cursor
               :journal-count journal-count}))))

(defn- journal-page [commits cursor]
  (let [journal-count (count commits)]
    (validate-cursor-bound! cursor journal-count)
    (let [next-cursor (min journal-count (+ cursor max-journal-page-size))]
      {:cursor cursor
       :next-cursor next-cursor
       :count journal-count
       :page-size (- next-cursor cursor)
       :remaining? (< next-cursor journal-count)
       :entries (subvec commits cursor next-cursor)})))

(defn- ready? [snapshot]
  (and (= :ready (:status snapshot))
       (not (:closed? snapshot))))

(defn- command-state [{:keys [status closed?] :as snapshot}]
  {:step? (ready? snapshot)
   :reconcile-effect? (and (not closed?)
                           (= :uncertain status)
                           (some? (get-in snapshot [:effects :pending])))
   ;; Exact step reconciliation is read-only and remains useful after failure
   ;; or close.
   :reconcile-step? true
   :close? (not closed?)})

(defn- flow-effect-state [snapshot]
  {:status (:status snapshot)
   :closed? (:closed? snapshot)
   :ownership {:worker :borrowed}
   :effects (:effects snapshot)
   :worker (:worker snapshot)
   :commands (command-state snapshot)})

(defn- delivery-state [delivery]
  (let [snapshot {:status (:status delivery)
                  :closed? false
                  :effects (:effects delivery)
                  :worker (:worker delivery)}]
    (flow-effect-state snapshot)))

(defn- coherent? [snapshot previews ledger snapshot-again]
  (let [commit-count (get-in snapshot [:commits :count])
        records (get-in snapshot [:effects :records])
        session-snapshot (:session snapshot)
        revision (:revision session-snapshot)
        admission? (ready? snapshot)]
    (and (= snapshot snapshot-again)
         (integer? commit-count)
         (not (neg? commit-count))
         (<= commit-count (count ledger))
         (= records (subvec ledger commit-count))
         (if admission?
           (and (= (:branches session-snapshot) (mapv :branch previews))
                (every? #(= revision (get-in % [:branch :revision])) previews))
           (empty? previews)))))

(defn- build-frame [snapshot previews ledger cursor]
  (let [session-snapshot (:session snapshot)
        commit-count (get-in snapshot [:commits :count])
        admitted? (ready? snapshot)
        commits (subvec ledger 0 commit-count)]
    {:jolt.sim.session-view/type :frame
     :kind :jolt.sim.kind/flow-effect-session-frame
     :revision (:revision session-snapshot)
     :status (:status session-snapshot)
     :projection (:projection session-snapshot)
     :branches (if admitted? (:branches session-snapshot) [])
     :previews previews
     :journal (journal-page commits cursor)
     :flow-effect (flow-effect-state snapshot)}))

(defn- retryable-read-error? [error]
  (= :jolt.sim.flow-effect-session/rejected (:type (ex-data error))))

(defn- coherence-error [attempt]
  (ex-info "Flow/effect frame coherence could not be obtained"
           {:type ::coherence-failed
            :attempts (inc attempt)
            :max-attempts max-coherence-attempts}))

(defn- read-frame* [ops cursor]
  (validate-cursor! cursor)
  (loop [attempt 0]
    (let [outcome
          (try
            (let [snapshot ((:snapshot ops))
                  previews (if (ready? snapshot) ((:previews ops)) [])
                  ledger (vec ((:journal ops)))
                  snapshot-again ((:snapshot ops))]
              {:snapshot snapshot
               :previews previews
               :ledger ledger
               :snapshot-again snapshot-again})
            (catch :default error {:error error}))]
      (cond
        (and (:error outcome) (not (retryable-read-error? (:error outcome))))
        (throw (:error outcome))

        (and (nil? (:error outcome))
             (coherent? (:snapshot outcome) (:previews outcome)
                        (:ledger outcome) (:snapshot-again outcome)))
        (build-frame (:snapshot outcome) (:previews outcome)
                     (:ledger outcome) cursor)

        (>= attempt (dec max-coherence-attempts))
        (throw (coherence-error attempt))

        :else (recur (inc attempt))))))

(defn read-frame
  "Returns one coherent, UI-neutral frame for a flow/effect bridge.

  When effect delivery is uncertain, failed, or closed, inspection remains
  available but `:branches` and `:previews` are empty and `:step?` is false.
  `cursor` pages only immutable commit entries; mutable effect records are
  always exposed separately under `:flow-effect :effects`."
  [bridge cursor]
  (read-frame* (bridge-ops bridge) cursor))

(defn- frame-error [phase error]
  (let [data (ex-data error)
        error-type (:type data)]
    (cond-> {:type (if (keyword? error-type)
                     error-type
                     ::frame-unavailable)
             :phase phase}
      (integer? (:attempts data)) (assoc :attempts (:attempts data))
      (integer? (:max-attempts data))
      (assoc :max-attempts (:max-attempts data)))))

(defn- frame-after-command [ops cursor phase]
  (try
    {:frame (read-frame* ops cursor)
     :frame-error nil}
    (catch :default error
      {:frame nil
       :frame-error (frame-error phase error)})))

(defn- plain-branch [branch]
  {:revision (:revision branch)
   :action [(nth (:action branch) 0) (nth (:action branch) 1)]})

(defn- stale-result [ops cursor error]
  (let [data (ex-data error)
        refreshed (frame-after-command ops cursor :stale-refresh)]
    (merge
     {:jolt.sim.session-view/type :step-result
      :kind :jolt.sim.kind/flow-effect-step-result
      :status :stale
      :committed? false
      :ack nil
      :flow-effect (some-> (:frame refreshed) :flow-effect)
      :stale (select-keys data
                          [:type :expected-revision :actual-revision :branch])}
     refreshed)))

(defn- step-frame* [ops branch cursor]
  ;; Obtain a coherent cursor bound before invoking the mutating operation.
  ;; A concurrent caller can still win afterward, in which case the bridge
  ;; rejects the exact branch as stale.
  (read-frame* ops cursor)
  (try
    (let [result ((:step! ops) branch)
          ack {:branch (plain-branch (:branch result))
               :revision (:revision result)}]
      (merge
       {:jolt.sim.session-view/type :step-result
        :kind :jolt.sim.kind/flow-effect-step-result
        :status :committed
        :committed? true
        :ack ack
        ;; This delivery summary came back with the authoritative commit and
        ;; survives a later frame failure or uncertain publication.
        :flow-effect (delivery-state (:delivery result))}
       (frame-after-command ops cursor :post-commit)))
    (catch :default error
      (if (= :jolt.sim.session/stale-branch (:type (ex-data error)))
        (stale-result ops cursor error)
        (throw error)))))

(defn step-frame!
  "Commits one exact branch and publishes its newly authorized effects once.

  `:committed? true` and the exact `:ack` remain authoritative when delivery
  is uncertain, failed, or the post-commit frame is unavailable. A stale
  branch is data and is never rebased or retried automatically."
  [bridge branch cursor]
  (step-frame* (bridge-ops bridge) branch cursor))

(defn- effect-result-status [record]
  (case (:state record)
    :settled :settled
    :uncertain :uncertain
    :failed :failed
    :failed))

(defn- reconcile-effect-frame* [ops cursor]
  ;; Validate the cursor before the mutating operation, but take the target
  ;; from the bridge's reconciliation receipt.  The bridge captures it while
  ;; holding its own lock, so a concurrent REPL/Ripple caller cannot make this
  ;; result mislabel a later uncertain effect.
  (read-frame* ops cursor)
  (let [delivery ((:reconcile-effect! ops))
        target (:target delivery)
        effect (some #(when (= (:id target) (:id %)) %)
                     (get-in delivery [:effects :records]))]
    (merge
     {:jolt.sim.flow-effect-view/type :effect-reconcile-result
      :kind :jolt.sim.kind/flow-effect-reconcile-result
      :operation :reconcile-effect
      :flow-committed? true
      :target (select-keys target [:intent-id :sequence])
      :status (effect-result-status effect)
      :effect effect}
     ;; Reconciliation may settle an external effect. Preserve that result if
     ;; a later inspection frame cannot be obtained; never reconcile again as
     ;; an implicit refresh strategy.
     (frame-after-command ops cursor :post-effect-reconcile))))

(defn reconcile-effect-frame!
  "Reconciles only the bridge's exact uncertain effect and returns a frame.

  This delegates to the retained worker's reconciliation operation and never
  republishes the command. The original flow transition is explicitly marked
  committed in every returned result."
  [bridge cursor]
  (reconcile-effect-frame* (bridge-ops bridge) cursor))

(defn- reconcile-step-frame* [ops branch cursor]
  (validate-cursor! cursor)
  (let [result ((:reconcile-step ops) branch)
        frame (read-frame* ops cursor)]
    (cond->
     {:jolt.sim.flow-effect-view/type :step-reconcile-result
      :kind :jolt.sim.kind/flow-effect-step-reconcile-result
      :operation :reconcile-step
      :submitted (:branch result)
      :status (:status result)
      :committed? (= :committed (:status result))
      :frame frame}
      (integer? (:revision result)) (assoc :revision (:revision result))
      (contains? result :intent-ids) (assoc :intent-ids (:intent-ids result))
      (contains? result :committed-branch)
      (assoc :committed-branch (:committed-branch result)))))

(defn reconcile-step-frame
  "Reads whether an exact branch committed and returns the current frame.

  This operation is read-only: it never steps the flow or contacts the worker."
  [bridge branch cursor]
  (reconcile-step-frame* (bridge-ops bridge) branch cursor))

(defn- close-frame* [ops cursor]
  ;; Reject a malformed or ahead-of-journal cursor before closing admission.
  ;; Close itself is idempotent, but callers should still receive a normal
  ;; result for every accepted command.
  (read-frame* ops cursor)
  ((:close! ops))
  {:jolt.sim.flow-effect-view/type :close-result
   :kind :jolt.sim.kind/flow-effect-close-result
   :operation :close
   :status :closed
   :worker {:ownership :borrowed :operation :none}
   :frame (read-frame* ops cursor)})

(defn close-frame!
  "Closes bridge admission idempotently and returns its final frame.

  The worker is borrowed: close performs no worker termination operation."
  [bridge cursor]
  (close-frame* (bridge-ops bridge) cursor))
