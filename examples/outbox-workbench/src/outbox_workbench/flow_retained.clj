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
  (:require [jolt.sim.flow :as flow]
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
   [:request-id :string]
   [:entity-id :string]
   [:payload [:vector :int]]])

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
  (let [row-status (get-in snapshot [:store-state :outbox 0 :status])
        receiver-count (get-in snapshot [:receiver-requests :count])]
    (cond-> {:operation operation
             :row-status row-status
             :receiver-requests receiver-count}
      (integer? (:status result))
      (assoc :http-status (:status result)))))

(def receipt-observation-schema
  [:map {:closed true}
   [:operation [:enum :submit :deliver]]
   [:row-status [:enum :pending :delivered]]
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
