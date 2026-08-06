(ns jolt.sim.experiment-executor
  "Minimal execution bridge for opaque compiled experiment plans.

  Compilation remains separate from execution. This namespace validates one
  direct `jolt.sim.experiment/compile-plan` result, forwards its already
  derived runtime configuration unchanged to `runtime/run-controlled`, and
  invokes one trusted zero-argument body. It does not choose schedules,
  generate cases, run probes or checks, interpret manifests, or accept FFI and
  clock overrides from call data."
  (:require [jolt.sim.experiment :as experiment]
            [jolt.sim.runtime :as runtime]))

(def invalid-execution ::invalid-execution)

(def ^:private option-keys #{:drain-timeout-ms})

(defn- fail! [reason]
  (throw (ex-info "Invalid compiled experiment execution"
                  {:type invalid-execution :reason reason})))

(defn- validate-options! [options]
  (when-not (and (map? options)
                 (nil? (meta options))
                 (every? option-keys (keys options)))
    (fail! :invalid-options))
  (when (contains? options :drain-timeout-ms)
    (let [timeout (:drain-timeout-ms options)]
      (when-not (and (integer? timeout) (pos? timeout))
        (fail! :invalid-drain-timeout))))
  options)

(defn execute!
  "Executes trusted `body` under the runtime configuration compiled in plan.

  The two-argument arity accepts no execution overrides. The optional map may
  contain only a positive `:drain-timeout-ms`, which affects scope drainage
  but cannot replace the plan's FFI mode, handlers, or clock. Returns the
  exact `runtime/run-controlled` result map."
  ([plan body]
   (execute! plan {} body))
  ([plan options body]
   (experiment/validate-plan! plan)
   (validate-options! options)
   (when-not (fn? body)
     (fail! :body-not-function))
   (let [runtime-config (:runtime-config (experiment/plan-data plan))]
     (runtime/run-controlled (merge runtime-config options) body))))
