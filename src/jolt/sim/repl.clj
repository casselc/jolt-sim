(ns jolt.sim.repl
  "Interactive last-run convenience over jolt.sim.process-explorer/run-case.

  This is a thin in-process helper for humans and agents driving the
  fresh-process supervisor from a long-lived Jolt nREPL session. Every actual
  run delegates unchanged to jolt.sim.process-explorer/run-case; this namespace
  is not a second controller, scheduler, worker protocol, or monitor.

  API:
    run-case!  delegates exactly once to process-explorer/run-case, records the
               exact submitted config and returned outcome, and returns the
               outcome.
    last-run   returns the recorded {:config .. :outcome ..} map, or nil.
    rerun!     repeats the exact last run-case config in a fresh worker and
               records/returns the new outcome. Throws a typed ex-info
               ({:type :jolt.sim.repl/no-prior-run}) when no case has run yet.
    clear!     removes the recorded run; returns nil.

  Mutable state is private and bounded to the single last run only. There is no
  history, daemon, lock service, shell dispatcher, or timestamping here. If
  run-case throws, the exception propagates and the record is left unchanged.

  REPL workflow (existing Jolt nrepl-server + clj-nrepl-eval):

    1. Serve the project from one process:

         jolt nrepl-server            ; writes .nrepl-port (default 7888)

    2. From an agent or shell, require the helpers once:

         clj-nrepl-eval -p 7888 \"(require '[jolt.sim.repl :as sim-repl])\"

    3. Run a case exactly as you would call process-explorer/run-case, then
       inspect/repeat/discard:

         (sim-repl/run-case!
          {:worker-command [\"/path/to/sim/jolt\" \"-M:sim-worker\"]
           :dir \"/abs/path/to/project\"
           :scenario 'my.scenarios/checkout-race
           :input {:workload [[:checkout :order-7]]}
           :schedule [1 0]
           :timeout-ms 5000})

         (sim-repl/last-run)   ; {:config .. :outcome ..}
         (sim-repl/rerun!)     ; same exact config, fresh worker, new outcome
         (sim-repl/clear!)     ; drop the record

  Coordination rules:
    - Each agent (human or automated) uses its OWN nrepl clone session so its
      REPL bindings and evaluation context stay independent. Clone sessions do
      not isolate namespace vars or atoms such as this project-level record.
    - Reload and state-mutating evals are serialized by TASK OWNERSHIP: only the
      agent that owns the current task runs run-case!/rerun!/clear! or reloads
      this namespace. This namespace adds no internal lock, so concurrent
      mutating evals are unsupported by design.
    - Poisoned-controller, deadlock, and native-crash cases always stay in fresh
      worker processes. run-case!/rerun! delegate to process-explorer/run-case,
      which spawns one fresh OS worker per call and reaps it; never run such a
      case directly in the long-lived nREPL process."
  (:require [jolt.sim.process-explorer :as process-explorer]))

;; The single last run only. nil means no run has been recorded. defonce so a
;; namespace reload from the REPL does not silently drop a recorded run; tests
;; and callers reset it through clear!.
(defonce ^:private last-run-state (atom nil))

(defn- record-run! [config outcome]
  (reset! last-run-state {:config config :outcome outcome}))

(defn run-case!
  "Runs one exploration case in a fresh worker by delegating exactly once to
  jolt.sim.process-explorer/run-case, records the exact submitted config and
  returned outcome as the last run, and returns the outcome unchanged. If
  run-case throws, the record is left unchanged and the exception propagates."
  [config]
  (let [outcome (process-explorer/run-case config)]
    (record-run! config outcome)
    outcome))

(defn last-run
  "Returns the recorded last run as {:config config :outcome outcome}, or nil
  before any run-case! or after clear!."
  []
  @last-run-state)

(defn rerun!
  "Repeats the exact last run-case config in a fresh worker, records and
  returns the new outcome. Throws a typed ex-info
  ({:type :jolt.sim.repl/no-prior-run}) when no prior case has run. If run-case
  throws, the existing record is left unchanged and the exception propagates."
  []
  (let [recorded @last-run-state]
    (when-not (some? recorded)
      (throw
       (ex-info
        "jolt-sim repl has no recorded run-case to repeat"
        {:type :jolt.sim.repl/no-prior-run})))
    (let [config (:config recorded)
          outcome (process-explorer/run-case config)]
      (record-run! config outcome)
      outcome)))

(defn clear!
  "Removes the recorded last run. Returns nil."
  []
  (reset! last-run-state nil)
  nil)
