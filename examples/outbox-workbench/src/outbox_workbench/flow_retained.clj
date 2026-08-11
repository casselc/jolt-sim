(ns outbox-workbench.flow-retained
  "One bounded real outbox flow/effect vertical over the retained worker.

  This example-local namespace owns two ordinary pure finite flows and the
  retained-worker launch contract, and nothing else. It reimplements no HTTP,
  SQLite, TCP, bencode, or outbox logic: the unchanged retained outbox adapter
  (jolt.sim.fixtures.outbox-json-delivery-retained/run!) owns the real
  application lifecycle, and jolt.sim.flow-effect-session owns the commit-gated
  publication boundary.

  `command-flow` compiles a minimal finite jolt.sim.flow where one pure cell
  emits exactly one :example.outbox/command intent whose payload is the
  supplied command, passed unchanged. The flow-effect-session bridge publishes
  that payload to the retained worker's command! seam, so {:op :submit
  :command ...} and {:op :deliver} reach the unchanged adapter verbatim.

  `receipt-flow` is the ordinary pure continuation: a small pure adapter first
  projects the definite worker receipt onto declared application facts, then a
  finite flow consumes those facts as ordinary accumulated data. No effect is
  smuggled into a preview handler."
  (:require [jolt.sim.command-cell-session :as command-cell-session]
            [jolt.sim.flow :as flow]
            [jolt.sim.kernel :as kernel]))

;; ---- retained worker launch contract --------------------------------------
;;
;; The exact config/image contract proven by retained_workbench_test_main: the
;; child resolves the root repository's real HTTP/SQLite/TCP dependency graph
;; through the :outbox-json-delivery-test:retained-outbox-worker alias.

(defn retained-config
  "Builds the exact retained outbox child configuration from explicit
  executable and repository-root coordinates, mirroring
  outbox-workbench.retained-main/retained-config."
  [jolt-bin project-dir]
  {:worker-command
   [jolt-bin "-M:outbox-json-delivery-test:retained-outbox-worker"]
   :adapter
   'jolt.sim.fixtures.outbox-json-delivery-retained/run!
   :input {}
   :dir project-dir
   :temp-dir (or (System/getenv "JOLT_SIM_RETAINED_ARTIFACT_DIR")
                 (or (System/getenv "TMPDIR") "/tmp"))
   :extra-env {"JOLT_AOT_CACHE" "0"}
   :startup-timeout-ms 60000
   :command-timeout-ms 60000
   :kill-grace-ms 1000})

;; ---- command flow ----------------------------------------------------------

(def outbox-command-schema
  "The closed boundary of one canonical outbox command payload, matching
  jolt.sim.fixtures.outbox-delivery/default-command."
  [:map {:closed true}
   [:request-id [:string {:min 1}]]
   [:entity-id [:string {:min 1}]]
   [:payload [:vector [:int {:min 0 :max 255}]]]])

(def submit-command-schema
  "Exact closed input boundary for the submit command cell."
  [:map {:closed true}
   [:op [:= :submit]]
   [:command outbox-command-schema]])

(def deliver-command-schema
  "Exact closed input boundary for the independent deliver command cell."
  [:map {:closed true}
   [:op [:= :deliver]]])

(def command-schema
  "The closed structural boundary for one retained outbox command.

  Malli's inspectable flow subset cannot express keys conditional on :op, so
  `validate-command!` supplies the dependent-key invariant: :submit requires
  exactly :op/:command and :deliver requires exactly :op."
  [:map {:closed true}
   [:op [:enum :submit :deliver]]
   [:command {:optional true} outbox-command-schema]])

(def ^:private command-keys
  {:submit #{:op :command}
   :deliver #{:op}})

(defn validate-command!
  "Returns an exact outbox command or throws before an intent can exist.

  This is the operation-dependent part of the declared command contract. It
  complements `command-schema`; it does not contact or inspect a worker."
  [command]
  (let [operation (:op command)
        expected (get command-keys operation)
        actual (when (map? command) (set (keys command)))]
    (when-not (and expected (= expected actual))
      (throw (ex-info "Outbox flow command has an invalid operation shape"
                      {:type ::invalid-command
                       :operation operation
                       :expected-keys expected
                       :actual-keys actual})))
    command))

(def command-specs
  {:emit-command
   {:handler :emit-command
    :schema {:input command-schema :output command-schema}
    :emits #{:example.outbox/command}}})

(def command-handlers
  {:emit-command
   (fn [_ state data]
     (let [command (validate-command! data)]
       {:state (inc (or state 0))
        :data command
        :intents [{:kind :example.outbox/command
                   :payload command}]}))})

(defn command-flow
  "Compiles a minimal finite flow that emits exactly one :example.outbox/command
  intent whose payload is `command`, passed unchanged to the retained adapter."
  [command]
  (validate-command! command)
  (flow/compile-workflow
   {:cells {:emit :emit-command}
    :edges []
    :start :emit
    :input command
    :resources {}}
   command-specs
   command-handlers))

;; ---- receipt-continuation flow ---------------------------------------------

(defn receipt-observation
  "Purely projects one definite worker receipt value onto the facts consumed by
  the continuation flow.

  The adapter deliberately selects a small closed shape because the retained
  receipt contains richer nested evidence than this example needs. It performs
  no I/O and does not reinterpret HTTP, SQLite, TCP, or bencode semantics."
  [{:keys [operation result snapshot]}]
  (let [row-status (or (get-in snapshot [:store-state :outbox 0 :status])
                       :empty)
        receiver-count (get-in snapshot [:receiver-requests :count])]
    (cond-> {:operation operation
             :row-status row-status
             :receiver-requests receiver-count}
      (integer? (:status result))
      (assoc :http-status (:status result)))))

(def receipt-observation-schema
  [:map {:closed true}
   [:operation [:enum :submit :deliver]]
   [:row-status [:enum :empty :pending :delivered]]
   [:receiver-requests :int]
   [:http-status {:optional true} :int]])

(def receipt-specs
  {:observe-receipt
   {:handler :observe-receipt
    :schema {:input receipt-observation-schema
             :output [:map {:closed true} [:observed :boolean]]}}})

(def receipt-handlers
  {:observe-receipt
   (fn [_ state _]
     {:state (inc (or state 0))
      :data {:observed true}})})

(defn receipt-flow
  "Compiles an ordinary finite continuation flow over one definite retained
  receipt. The receipt is projected to a closed data boundary before entering
  the simulator; the flow handler remains deterministic and effect-free."
  [receipt-value]
  (flow/compile-workflow
   {:cells {:observe :observe-receipt}
    :edges []
    :start :observe
    :input (receipt-observation receipt-value)
    :resources {}}
   receipt-specs
   receipt-handlers))

(defn observe
  "Runs the pure finite receipt continuation to its terminal accumulated data."
  [receipt-value]
  (get-in (kernel/run (receipt-flow receipt-value)) [:tasks 0 :result]))

;; ---- generic CommandCellSession adapter -----------------------------------

(def command-cell-descriptors
  "Data-only catalog entries for exact submit and independent deliver cells."
  {:example.outbox/submit
   {:effect-kind :example.outbox/command
    :input-schema submit-command-schema
    :output-schema receipt-observation-schema
    :projector :example.outbox/receipt-observation
    ;; Existing Outbox presenters consume full bridge/effect results.
    :suggested-kind nil}
   :example.outbox/deliver
   {:effect-kind :example.outbox/command
    :input-schema deliver-command-schema
    :output-schema receipt-observation-schema
    :projector :example.outbox/receipt-observation
    :suggested-kind nil}})

(defn start-command-cell-session
  "Starts the generic command-cell owner over one borrowed Outbox worker.

  Config is exact. The worker and WorkbenchSession remain caller-owned; this
  factory only binds the existing command flow and receipt projector to their
  generic UI-neutral contracts."
  [{:keys [worker workbench evidence-stream-id] :as config}]
  (when-not (and (map? config)
                 (= #{:worker :workbench :evidence-stream-id}
                    (set (keys config))))
    (throw (ex-info "Outbox command-cell config has the wrong shape"
                    {:type ::invalid-command-cell-config})))
  (let [cell-ids (keys command-cell-descriptors)]
    (command-cell-session/start
     {:evidence-stream-id evidence-stream-id
      :descriptors command-cell-descriptors
      :trusted
      {:compilers (zipmap cell-ids (repeat command-flow))
       :workers (zipmap cell-ids (repeat worker))
       :projectors {:example.outbox/receipt-observation receipt-observation}
       :workbench (command-cell-session/workbench-service workbench)}})))
