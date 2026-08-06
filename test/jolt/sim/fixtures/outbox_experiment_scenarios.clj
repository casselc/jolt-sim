(ns jolt.sim.fixtures.outbox-experiment-scenarios
  "Compiled-plan scenario lane for the canonical cancel-before-ack outbox
   experiment: the SAME unchanged ordinary application body as the direct
   jolt.sim.fixtures.outbox-delivery-scenarios lane, executed through
   jolt.sim.experiment-executor/execute! under the opaque plan compiled from
   jolt.sim.fixtures.outbox-experiment-packs/cancel-before-ack-manifest
   (profile :hermetic) instead of under a manually assembled handler
   composition.

   This namespace is deliberately narrow. For the one fixed first-case input
   it builds a fresh per-case trusted bundle
   (outbox-experiment-packs/new-case-bundle), compiles the fixed closed
   manifest through that bundle's registry, and hands the resulting opaque
   plan plus the unchanged zero-argument application body to
   experiment-executor/execute!. It never builds worlds or handler packs of
   its own, never calls jolt.sim.runtime/run-controlled directly, and never
   reads executable fields out of the plan to reconstruct an execution:
   compilation and execution are the trusted experiment/experiment-executor
   path end to end.

   Input contract. The compiled SQLite plan vector's exact binds, the
   modeled POSIX capacities, and the pack-owned contract values are all
   pinned by the trusted bundle, so this lane accepts exactly one closed
   input:

     {:payload [0 127 128 255] :stream-capacity 8 :pipe-capacity 1
      :poll-eintr-ordinal nil}

   Any other input is rejected before a world exists: a different payload or
   capacity would otherwise silently drive the unchanged body against a plan
   whose compiled SQLite binds and declared capacity bounds belong to a
   different case. This lane also drives no future schedule, so a non-empty
   runtime-overrides map is rejected for the same reason rather than being
   silently ignored while the direct lane honors it.

   Evidence. The returned canonical evidence mirrors the direct lane's
   stable projections -- :application, :http, :receiver, :routes, :sqlite,
   :capacity and :clean? -- so the parent parity witness can compare
   exact select-keys equality plus the absolute terminal summaries. This
   lane carries the exact no-fault declaration compiled from the trusted
   registry and manifest; it does not manufacture runtime fault counters.
   The evidence additionally carries the compiled
   lane's own identity and non-vacuity witnesses: :callbacks (each trusted
   pack compiler ran exactly once), :mechanism (the plan's physical
   mechanism-probe reports after execution), :history (bounded
   per-connection ownership counts over the controlled effect trace), and
   :plan (a serializable identity projection of the validated compiled plan
   data). Everything here stays at physical handler-substrate ownership
   scope, exactly as the pack descriptors declare; no causal or semantic
   attribution is claimed."
  (:require [jolt.sim.experiment :as experiment]
            [jolt.sim.experiment-executor :as executor]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
            [jolt.sim.fixtures.outbox-experiment-packs :as packs]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.pack-registry :as pack-registry]
            [jolt.sim.sqlite :as sqlite]))

;; The one closed first-case input. The trusted bundle pins the exact
;; cancel-before-ack SQLite plan vector (whose command BLOB binds are the
;; [0 127 128 255] payload), the modeled stream/pipe capacities (8/1), and
;; the pack contract values to exactly this case, so the input is an
;; acceptance contract, not a generator domain.
(def ^:private fixed-cancel-input
  {:payload [0 127 128 255]
   :stream-capacity 8
   :pipe-capacity 1
   :poll-eintr-ordinal nil})

(defn- invalid-input [reason data]
  (ex-info
   "invalid outbox experiment scenario input"
   (merge {:type :jolt.sim.fixtures.outbox-experiment-scenarios/invalid-input
           :reason reason}
          data)))

(defn- validate-fixed-input!
  "Rejects every input except the one closed first-case input BEFORE any
   world or compiled plan exists. Exact key set and exact values are both
   checked: anything else would run the unchanged body against a plan
   compiled for a different case."
  [input]
  (when-not (map? input)
    (throw (invalid-input :not-a-map {:input input})))
  (when-not (= (set (keys fixed-cancel-input)) (set (keys input)))
    (throw (invalid-input
            :input-keys
            {:input-keys (vec (sort-by pr-str (keys input)))
             :expected-keys
             (vec (sort-by pr-str (keys fixed-cancel-input)))})))
  (when-not (= fixed-cancel-input input)
    (throw (invalid-input :unsupported-input
                          {:input input :fixed fixed-cancel-input})))
  input)

(defn- validate-overrides!
  "Rejects any non-empty runtime-overrides map. This lane drives no future
   schedule, and experiment-executor/execute! accepts no control overrides;
   silently ignoring an override would desynchronize this lane from the
   direct lane, which honors it."
  [overrides]
  (when (seq overrides)
    (throw (invalid-input :unsupported-overrides {:overrides overrides})))
  overrides)

;; Pure projection mirrored from
;; jolt.sim.fixtures.outbox-delivery-scenarios/foreign-symbols so this lane's
;; route evidence is derived by the identical rule without loading the direct
;; lane's scheduler/fault dependencies into the compiled worker.
(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn- plan-projection
  "Returns the serializable identity projection of the validated opaque plan
   data: fixed experiment/profile identity, per-connection pack/mode
   bindings, runtime-config shape, and the empty check/monitor/presentation
   surfaces. It carries no executable field (no handlers, probes,
   projectors, or clocks) and is safe to cross the worker boundary."
  [data]
  {:experiment-id (:experiment-id data)
   :profile-id (:profile-id data)
   :plan-version (:jolt.sim.experiment-plan/version data)
   :connections
   (into {}
         (map (fn [[connection-id compiled]]
                [connection-id
                 (select-keys compiled
                              [:from :to :pack-id :capabilities :mode])]))
         (:connections data))
   :checks-count (count (:checks data))
   :runtime-config
   {:ffi-mode (get-in data [:runtime-config :ffi-mode])
    :ffi-handler-count (count (get-in data [:runtime-config :ffi-handlers]))
    :keys (vec (sort-by pr-str (keys (:runtime-config data))))
    :clock? (contains? (:runtime-config data) :clock)}
   :native-fallback-count
   (reduce + 0
           (map #(count (get-in % [:binding :fields :native-fallback]))
                (vals (:connections data))))
   :mechanism-probes (vec (sort-by pr-str (keys (:mechanism-probes data))))
   :history-projectors
   (vec (sort-by pr-str (keys (:history-projectors data))))
   :monitor-spec-count (count (:monitor-specs data))
   :presentation-keys
   (vec (sort-by pr-str (keys (:presentation-registry data))))})

(defn- mechanism-reports
  "Invokes each compiled plan mechanism probe once. The probe argument is
   this slice's intentionally empty execution evidence (the trusted probes
   ignore it and read the bundle's live world evidence); the bounded
   physical handler-substrate reports are returned keyed by connection id."
  [data]
  (into {}
        (map (fn [[connection-id probe]] [connection-id (probe nil)]))
        (:mechanism-probes data)))

(defn- history-summaries
  "Applies each compiled plan history projector to the controlled effect
   trace and returns bounded per-connection counts plus original route
   indices. The indices let the parent prove exact coverage with neither
   overlap nor omission; raw events never cross the worker boundary."
  [data effect-trace]
  (let [indexed-trace
        (vec (map-indexed (fn [index event]
                            (assoc event ::route-index index))
                          effect-trace))]
    (into {}
          (map (fn [[connection-id projector]]
                 (let [events (:events (projector indexed-trace))]
                   [connection-id
                    {:event-count (count events)
                     :route-indices (mapv ::route-index events)}])))
          (:history-projectors data))))

(defn- callback-counts
  "Reads the trusted compile counters once compilation and execution are
   complete. Every value must be exactly one: compile-plan runs each trusted
   connection compiler once per case, and plan validation, inspection, or
   execution never recompiles."
  [bundle]
  {:compile
   (into {}
         (map (fn [[connection-id counter]] [connection-id @counter]))
         (get-in bundle [:callbacks :compile]))})

(defn- no-fault-policy
  "Projects the exact fault surface declared by the trusted registry and the
   fixed profile used for compilation. This is deliberately a policy witness,
   not fabricated evidence that no hidden implementation could ever inject a
   fault. Every connection must select empty params and its pack must expose an
   empty fault catalog."
  [registry]
  {:profile-id :hermetic
   :connection-params
   (into {}
         (map (fn [[connection-id selection]]
                [connection-id (:params selection)]))
         (get-in packs/cancel-before-ack-manifest
                 [:profiles :hermetic :connections]))
   :pack-fault-catalogs
   (into {}
         (map (fn [[connection-id connection]]
                [connection-id
                 (pack-registry/faults registry (:pack connection))]))
         (:connections packs/cancel-before-ack-manifest))})

(defn- compiled-evidence-for
  "Builds one fresh trusted case bundle, compiles the fixed closed manifest
   through its registry, executes the unchanged zero-argument
   cancel-before-ack application body through experiment-executor/execute!
   under the opaque compiled plan, and returns the canonical evidence map.
   The compile counters are read only after execute! returns, so a surprise
   recompile during validation or execution surfaces as a counter above
   one."
  [input]
  (let [command (assoc fixture/default-command :payload (:payload input))
        bundle (packs/new-case-bundle)
        registry (packs/case-registry bundle)
        plan (experiment/compile-plan
              registry
              packs/cancel-before-ack-manifest
              :hermetic)
        controlled
        (executor/execute!
         plan {:drain-timeout-ms 10000}
         (fn []
           (fixture/exercise-outbox-delivery-cancel-before-ack command)))
        result (:result controlled)
        effect-trace (:effect-trace controlled)
        data (experiment/plan-data plan)]
    {:application-body-id (:application-body-id result)
     :application (:application result)
     :http (:http result)
     :receiver (:receiver result)
     :routes {:count (count effect-trace)
              :all-handled?
              (every? #(= :handler (:route %)) effect-trace)
              :foreign-symbols (foreign-symbols effect-trace)}
     :sqlite (sqlite/summary (:sqlite bundle))
     :capacity {:stream (posix/capacity-summary (:posix bundle))
                :pipe (posix/pipe-capacity-summary (:posix bundle))}
     :fault-policy (no-fault-policy registry)
     :clean? {:memory (memory/clean? (:memory bundle))
              :sqlite (sqlite/clean? (:sqlite bundle))
              :posix (posix/clean? (:posix bundle))}
     :callbacks (callback-counts bundle)
     :mechanism (mechanism-reports data)
     :history (history-summaries data effect-trace)
     :plan (plan-projection data)}))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-cancel-before-ack-compiled
  "Runs the unchanged
   jolt.sim.fixtures.outbox-delivery/exercise-outbox-delivery-cancel-before-ack
   application body through jolt.sim.experiment-executor/execute! under the
   plan compiled from the fixed cancel-before-ack experiment manifest
   (profile :hermetic). Accepts exactly the one closed first-case input

     {:payload [0 127 128 255] :stream-capacity 8 :pipe-capacity 1
      :poll-eintr-ordinal nil}

   and rejects every other input before a world exists, because the trusted
   bundle pins the compiled SQLite plan binds, modeled capacities, and pack
   contract values to that case. The evidence map mirrors the direct lane's
   stable :application/:http/:receiver/:routes/:sqlite/:capacity/:fault/
   :clean? projections and adds the compiled lane's :callbacks, :mechanism,
   :history, and serializable :plan identity witnesses. Accepts the standard
   jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity used
   by jolt.sim.process-explorer/run-case; any non-empty overrides map is
   rejected because this lane drives no schedule."
  ([input]
   (exercise-cancel-before-ack-compiled {} input))
  ([overrides input]
   (validate-fixed-input! input)
   (validate-overrides! overrides)
   (compiled-evidence-for input)))
