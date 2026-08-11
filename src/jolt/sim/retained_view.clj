(ns jolt.sim.retained-view
  "UI-neutral inspection and command adapter for one retained Jolt process.

  This namespace owns no process state, journal, transport, or retry policy.
  Reads and mutations delegate to `jolt.sim.retained-process`, then project its
  parent-only snapshot into closed data suitable for a REPL or arbitrary UI.
  Process identifiers, artifact paths, and captured stdout/stderr text remain
  on the supervisor side of this boundary.

  A completed or failed application receipt is a definitive acknowledgment.
  If the following frame read fails, the receipt is returned with a bounded
  `:frame-error`; the operation is never made ambiguous and must not be
  retried. Transport failures before a receipt propagate unchanged."
  (:require [jolt.sim.retained-process :as retained]))

(defn- diagnostic-frame [diagnostic]
  (when diagnostic
    {:bytes (:bytes diagnostic)
     :truncated? (boolean (:truncated? diagnostic))
     :read-error? (some? (:error diagnostic))}))

(defn- diagnostics-frame [diagnostics]
  (when diagnostics
    {:stdout (diagnostic-frame (:stdout diagnostics))
     :stderr (diagnostic-frame (:stderr diagnostics))}))

(defn- receipt-frame [receipt]
  (when receipt
    (case (:status receipt)
      :completed
      {:status :completed
       :sequence (:sequence receipt)
       :value (:value receipt)}

      :failed
      {:status :failed
       :sequence (:sequence receipt)
       :error (:error receipt)}

      ;; A real retained-process receipt has already been validated. Keeping
      ;; this projection closed also prevents a future supervisor extension
      ;; from silently widening the shared workbench contract.
      {:status (:status receipt)
       :sequence (:sequence receipt)})))

(defn- project-frame [snapshot]
  {:jolt.sim.retained-view/type :frame
   :kind :jolt.sim.kind/retained-process-frame
   :protocol (:protocol snapshot)
   :instance-id (:instance-id snapshot)
   :status (:status snapshot)
   :next-sequence (:next-sequence snapshot)
   :uncertain-sequence (:uncertain-sequence snapshot)
   :last-receipt (some-> (:last-receipt snapshot)
                         (select-keys [:status :sequence]))
   :worker {:alive? (boolean (get-in snapshot [:child :alive?]))
            :exit (get-in snapshot [:child :exit])}
   :diagnostics (diagnostics-frame (:diagnostics snapshot))})

(defn- frame-error [phase error]
  (let [data (ex-data error)]
    (cond-> {:type (if (keyword? (:type data))
                     (:type data)
                     ::frame-unavailable)
             :phase phase}
      (keyword? (:reason data)) (assoc :reason (:reason data))
      (keyword? (:status data)) (assoc :status (:status data))
      (integer? (:sequence data)) (assoc :sequence (:sequence data)))))

(defn- frame-after-receipt [handle phase]
  (try
    {:frame (project-frame (retained/snapshot handle))
     :frame-error nil}
    (catch :default error
      {:frame nil
       :frame-error (frame-error phase error)})))

(defn- receipt-result [handle operation receipt]
  (merge
   {:jolt.sim.retained-view/type :command-result
    :kind :jolt.sim.kind/retained-command-result
    :operation operation
    :status (:status receipt)
    ;; Both :completed and :failed are committed application receipts. The
    ;; latter is not a transport ambiguity and remains safe to inspect.
    :committed? true
    :receipt (receipt-frame receipt)}
   (frame-after-receipt handle :post-receipt)))

(defn read-frame
  "Returns a closed, UI-neutral projection of the retained process snapshot.

  The returned map never includes the process handle, PID, absolute artifact
  paths, or captured stdout/stderr text. Supervisor transport failures
  propagate unchanged."
  [handle]
  (project-frame (retained/snapshot handle)))

(defn command-frame!
  "Publishes `command` exactly once and returns its receipt plus a fresh frame.

  Application `:completed` and `:failed` receipts are both definitive and keep
  their exact canonical value/error data. A post-receipt frame failure becomes
  bounded `:frame-error` data; a pre-receipt transport error propagates."
  [handle command]
  (receipt-result handle :command (retained/command! handle command)))

(defn reconcile-frame!
  "Reconciles the one uncertain sequence exactly once and returns its receipt.

  This never republishes a command. Transport ambiguity remains owned by
  `retained-process`; a recovered application receipt is definitive even when
  the subsequent frame cannot be read."
  [handle]
  (receipt-result handle :reconcile (retained/reconcile! handle)))

(defn terminate-frame!
  "Terminates/reaps the retained worker exactly once and projects its result.

  `retained-process/terminate!` already returns the authoritative final
  snapshot, so this function performs no second supervisor read."
  [handle]
  (project-frame (retained/terminate! handle)))
