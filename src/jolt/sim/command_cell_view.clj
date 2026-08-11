(ns jolt.sim.command-cell-view
  "Closed UI-neutral frames for one CommandCellSession.

  This adapter never receives a retained-process handle, worker closure,
  compiler, projector, or Workbench service. It reads and commands only the
  opaque CommandCellSession capability. Post-operation framing is advisory:
  once the core returns a definite commit, uncertainty, reconciliation, or
  close result, a presentation failure is retained as bounded secondary data
  and cannot turn that result into a retry-looking exception."
  (:require [clojure.datafy :as datafy]
            [jolt.sim.command-cell-session :as command-cell-session]
            [jolt.sim.trace :as trace]))

(def ^:private maximum-coherence-attempts 3)

(defn- canonical [value path]
  (trace/restore-value (trace/canonical-value value path)))

(defn- bounded-error [error phase]
  (let [data (ex-data error)]
    {:type (if (keyword? (:type data)) (:type data) :jolt.sim/error)
     :reason (if (keyword? (:reason data)) (:reason data) :unknown)
     :phase phase}))

(defn catalog-frame
  "Returns the stable data-only cell catalog."
  [session]
  (let [snapshot (command-cell-session/snapshot session)
        catalog (command-cell-session/catalog session)]
    (canonical
     {:jolt.sim.command-cell-view/type :catalog-frame
      :kind :jolt.sim.kind/command-cell-catalog
      :version 1
      :evidence-stream-id (:evidence-stream-id snapshot)
      :count (count catalog)
      :cells catalog}
     [:command-cell-view :catalog])))

(defn read-frame
  "Returns one coherent snapshot and its current pure branch previews.

  The active navigation token binds the preview to the same outer revision as
  the snapshot. A concurrent REPL mutation is retried a bounded number of
  times; no worker service is called by this operation."
  [session]
  (loop [attempt 1]
    (let [snapshot (datafy/datafy session)
          active-token (:active snapshot)
          outcome
          (try
            {:active (when active-token
                       (datafy/nav session :active active-token))}
            (catch :default error {:error error}))]
      (if-let [error (:error outcome)]
        (if (and (< attempt maximum-coherence-attempts)
                 (= :stale-navigation (:reason (ex-data error))))
          (recur (inc attempt))
          (throw
           (ex-info "Command cell frame could not be read coherently"
                    {:type ::coherence-failed
                     :attempts attempt
                     :max-attempts maximum-coherence-attempts}
                    error)))
        (canonical
         {:jolt.sim.command-cell-view/type :frame
          :kind :jolt.sim.kind/command-cell-frame
          :version 1
          :evidence-stream-id (:evidence-stream-id snapshot)
          :revision (:revision snapshot)
          :closed? (:closed? snapshot)
          :catalog (:catalog snapshot)
          :active active-token
          :cell (:cell snapshot)
          :branches (if active-token (:branches (:active outcome)) [])}
         [:command-cell-view :frame])))))

(defn- operation-frame [session operation result]
  (let [[frame frame-error]
        (try
          [(read-frame session) nil]
          (catch :default error
            [nil (bounded-error error :post-operation-frame)]))]
    ;; The core result is already authoritative. Do not re-encode it here:
    ;; an unsupported application value must not obscure a returned commit or
    ;; uncertainty. Each consumer may present it best-effort from this point.
    (cond->
     {:jolt.sim.command-cell-view/type :operation-result
      :kind :jolt.sim.kind/command-cell-operation-result
      :version 1
      :operation operation
      :evidence-stream-id (:evidence-stream-id result)
      :revision (:revision result)
      :cell-id (:cell-id result)
      :status (:status result)
      :result result}
      frame (assoc :frame frame)
      frame-error (assoc :frame-error frame-error))))

(defn prepare-frame!
  "Prepares one exact cell and returns its result plus a refreshed frame."
  [session request]
  (operation-frame session :prepare
                   (command-cell-session/prepare! session request)))

(defn step-frame!
  "Commits one exact outer/inner coordinate at most once."
  [session coordinate]
  (operation-frame session :step
                   (command-cell-session/step! session coordinate)))

(defn reconcile-frame!
  "Reconciles an uncertain publication without republishing it."
  [session]
  (operation-frame session :reconcile
                   (command-cell-session/reconcile! session)))

(defn close-frame!
  "Closes command-cell admission; the borrowed worker remains caller-owned."
  [session]
  (operation-frame session :close
                   (command-cell-session/close! session)))
