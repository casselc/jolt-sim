(ns outbox-workbench.main
  "Eval-only Ripple workbench for the canonical outbox application.

  This entry point owns nothing but delegation: it statically requires the
  unchanged jolt.sim.fixtures.outbox-json-delivery fixture so the namespace is
  loaded in this process (Jolt AOT/dynamic require needs a static edge), then
  hands every argument to jolt.sim.viewer/-main with the explicit --eval flag
  and the checked-in eval-only config. The capability token still comes from
  JOLT_SIM_VIEWER_TOKEN; the default config listens on port 8788. Pass one
  alternate config path to override the checked-in default."
  (:require [jolt.sim.fixtures.outbox-json-delivery]
            [jolt.sim.fixtures.outbox-json-delivery-live]
            [jolt.sim.viewer :as viewer]))

(def default-config-path
  "The checked-in eval-only Ripple config, relative to this workbench
   directory (the working directory of the documented launch command)."
  "config/ripple-eval.edn")

(defn -main
  "Starts Ripple as the REPL/workbench for the unchanged canonical outbox
   application: jolt.sim.viewer/-main with --eval and one eval-only config.
   The optional single argument replaces the checked-in config path."
  [& [config-path]]
  (viewer/-main "--eval" (or config-path default-config-path)))
