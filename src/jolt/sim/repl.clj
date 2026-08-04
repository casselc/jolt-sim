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
    replay-document!
               validates a Case/Outcome document, restores its scenario,
               input, and schedule into one fresh-worker run while accepting
               only ambient worker settings from the caller, and records and
               returns the replay outcome unchanged.
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

         clj-nrepl-eval -p 7888 \"(do (require 'jolt.sim.repl :reload) (require '[jolt.sim.repl :as sim-repl]))\"

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

       A retained Case/Outcome value or its canonical EDN can be replayed with
       ambient process settings supplied separately:

         (sim-repl/replay-document!
          retained-document
          {:worker-command ["/path/to/sim/jolt" "-M:sim-worker"]
           :dir "/abs/path/to/project"
           :timeout-ms 5000})

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
  (:require [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.process-explorer :as process-explorer]))

;; The single last run only. nil means no run has been recorded. defonce so a
;; namespace reload from the REPL does not silently drop a recorded run; tests
;; and callers reset it through clear!.
(defonce ^:private last-run-state (atom nil))

(def ^:private replay-coordinate-keys
  ;; :mode is a Case/Outcome provenance coordinate. process-explorer/run-case
  ;; does not expose it as a runtime override: the marked scenario owns its
  ;; declared run-controlled mode. Still reject it here so ambient config can
  ;; never silently pretend to override the retained document.
  #{:scenario :mode :input :schedule})

(defn- replay-error [reason data]
  (ex-info
   "jolt-sim repl rejected malformed replay configuration"
   (merge {:type :jolt.sim.repl/invalid-replay
           :reason reason}
          data)))

(defn- supplied-document [value]
  (if (string? value)
    (case-outcome/read-edn value)
    (case-outcome/validate-document! value)))

(defn- replay-config [runtime-config restored-case]
  (when-not (map? runtime-config)
    (throw
     (replay-error :runtime-config-not-a-map
                   {:value-class (str (class runtime-config))})))
  (let [collisions
        (->> (keys runtime-config)
             (filter replay-coordinate-keys)
             (sort-by pr-str)
             (vec))]
    (when (seq collisions)
      (throw
       (replay-error :replay-coordinate-collision {:keys collisions}))))
  ;; Mode is deliberately absent. The document validator establishes its
  ;; exact provenance value, but the existing worker API can replay only the
  ;; scenario/input/schedule coordinates; the scenario itself owns mode.
  (merge runtime-config
         (select-keys restored-case [:scenario :input :schedule])))

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

(defn replay-document!
  "Replays one validated Case/Outcome document in a fresh worker.

  `document` may be an already-read document map or one EDN string accepted by
  jolt.sim.case-outcome/read-edn. `runtime-config` supplies only ambient
  process-explorer settings such as :worker-command, :dir, deadlines,
  environment, and artifact retention. Any supplied :scenario, :mode, :input,
  or :schedule key is rejected before delegation; those are owned by the
  retained case.

  The exact restored :scenario, :input, and :schedule are submitted in one
  call to process-explorer/run-case. The restored :mode remains validated
  provenance: the current process explorer has no mode override, and the
  marked scenario owns its declared controller mode. This helper therefore
  neither guesses a mode mapping nor claims to enforce one.

  Records {:config submitted-config :outcome returned-outcome} using the same
  last-run semantics as run-case!, and returns the outcome unchanged. In
  particular, :failed, :timeout, and :worker-error remain non-success outcomes.
  If document/config validation or run-case throws, the previous last run is
  left unchanged and the exception propagates."
  [document runtime-config]
  (let [document (supplied-document document)
        restored-case (case-outcome/restore-case document)
        config (replay-config runtime-config restored-case)
        outcome (process-explorer/run-case config)]
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
