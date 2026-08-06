(ns jolt.sim.process-explorer
  "Sequential process supervisor for ordinary-runtime controlled cases.

  Each candidate schedule runs in a fresh Jolt child through the versioned
  `jolt.sim.explore-worker` file protocol. A child that does not exit before its
  deadline is terminated and reaped; it is reported as `:timeout`, not
  mislabeled as a proven deadlock. Poisoned controller state, blocked future
  gates, raw threads, and other process-global damage therefore cannot leak
  into the next case. It enumerates supplied top-level future-admission plans;
  it is not an explicit-state explorer or model checker.

  This is harness code and must run outside `run-controlled`. It launches real
  operating-system processes through `jolt.process`; it is not a simulated
  application effect."
  (:require [jolt.fs :as fs]
            [jolt.process :as process]
            [jolt.sim.explore-worker :as worker]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.trace :as trace]))

(def ^:private run-keys
  #{:worker-command :scenario :schedule :timeout-ms :startup-timeout-ms
    :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts? :on-run-dir
    :activity-journal?})

(def ^:private case-keys
  #{:worker-command :scenario :schedule :input :timeout-ms
    :startup-timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts? :on-run-dir
    :activity-journal?})

(def ^:private explore-keys
  #{:worker-command :scenario :schedules :timeout-ms :startup-timeout-ms
    :kill-grace-ms
    :dir :extra-env :temp-dir :retain-completed-artifacts? :on-run-dir
    :activity-journal?})

(def ^:private activity-journal-filename "activity.journal")

(def ^:private diagnostic-byte-limit 65536)
(def ^:private wait-poll-ms 10)

(defn- invalid-config [reason data]
  (ex-info
   "jolt-sim process explorer rejected malformed configuration"
   (merge {:type :jolt.sim.process-explorer/invalid-config
           :reason reason}
          data)))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- string-map? [value]
  (and (map? value)
       (every? (fn [[key entry-value]]
                 (and (string? key) (string? entry-value)))
               value)))

(defn- validate-common! [config allowed-keys]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map {:value config})))
  (let [unknown (seq (sort-by pr-str (remove allowed-keys (keys config))))]
    (when unknown
      (throw (invalid-config :unknown-key {:keys (vec unknown)}))))
  (let [command (:worker-command config)]
    (when-not (and (vector? command)
                   (seq command)
                   (every? string? command))
      (throw
       (invalid-config :invalid-worker-command {:value command}))))
  (let [scenario (:scenario config)]
    (when-not (and (symbol? scenario) (some? (namespace scenario)))
      (throw (invalid-config :invalid-scenario {:value scenario}))))
  (when-not (positive-integer? (:timeout-ms config))
    (throw
     (invalid-config :invalid-timeout {:value (:timeout-ms config)})))
  (when (contains? config :startup-timeout-ms)
    (when-not (positive-integer? (:startup-timeout-ms config))
      (throw
       (invalid-config :invalid-startup-timeout
                       {:value (:startup-timeout-ms config)}))))
  (let [kill-grace-ms (get config :kill-grace-ms 250)]
    (when-not (positive-integer? kill-grace-ms)
      (throw
       (invalid-config :invalid-kill-grace {:value kill-grace-ms}))))
  (let [dir (:dir config)]
    (when-not (and (string? dir) (seq dir))
      (throw (invalid-config :invalid-dir {:value dir}))))
  (when (contains? config :extra-env)
    (when-not (string-map? (:extra-env config))
      (throw
       (invalid-config :invalid-extra-env {:value (:extra-env config)}))))
  ;; The activity journal path key is reserved in every child environment,
  ;; enabled or not: the parent owns the fixed trusted
  ;; <run-dir>/activity.journal and a caller or browser must never supply it.
  ;; Rejection happens here, before any run directory is created or child
  ;; spawned.
  (when (contains? (:extra-env config) worker/activity-journal-env-key)
    (throw
     (invalid-config :reserved-activity-env-key
                     {:key worker/activity-journal-env-key})))
  (when (contains? config :temp-dir)
    (let [temp-dir (:temp-dir config)]
      (when-not (and (string? temp-dir) (seq temp-dir))
        (throw (invalid-config :invalid-temp-dir {:value temp-dir})))))
  ;; Fail closed before any run directory is created or child spawned: the
  ;; option must be a boolean when present, and defaults to false when absent.
  (let [retain-completed? (get config :retain-completed-artifacts? false)]
    (when-not (boolean? retain-completed?)
      (throw
       (invalid-config :invalid-retain-completed-artifacts
                       {:value (:retain-completed-artifacts? config)}))))
  ;; Fail closed on the opt-in activity journal: the option must be a boolean
  ;; when present and defaults to false. When enabled, completed artifacts
  ;; must be retained so fast successful journal evidence cannot disappear
  ;; before the caller polls it.
  (let [activity-journal? (get config :activity-journal? false)]
    (when-not (boolean? activity-journal?)
      (throw
       (invalid-config :invalid-activity-journal
                       {:value (:activity-journal? config)})))
    (when (and activity-journal?
               (not (true? (get config :retain-completed-artifacts? false))))
      (throw
       (invalid-config
        :activity-journal-requires-retention
        {:retain-completed-artifacts?
         (:retain-completed-artifacts? config)}))))
  (when (contains? config :on-run-dir)
    (when-not (fn? (:on-run-dir config))
      (throw
       (invalid-config :invalid-on-run-dir {:value (:on-run-dir config)}))))
  config)

(defn- validate-run-config! [config]
  (validate-common! config run-keys)
  (when-not (future-schedule/valid-schedule? (:schedule config))
    (throw
     (invalid-config :invalid-schedule {:value (:schedule config)})))
  (assoc config :kill-grace-ms (get config :kill-grace-ms 250)))

(defn- validate-case-config! [config]
  (validate-common! config case-keys)
  (let [schedule (:schedule config)]
    (when-not (or (nil? schedule)
                  (future-schedule/valid-schedule? schedule))
      (throw
       (invalid-config :invalid-schedule {:value schedule}))))
  ;; A case must fail before creating its temporary directory or launching a
  ;; child when its input cannot cross the canonical worker boundary.
  (when (contains? config :input)
    (try
      (trace/canonical-value (:input config))
      (catch :default error
        (throw
         (invalid-config
          :invalid-input
          {:error (select-keys (ex-data error)
                               [:type :reason :path :value-class])})))))
  (assoc config :kill-grace-ms (get config :kill-grace-ms 250)))

(defn- validate-schedules! [schedules]
  (when-not (and (vector? schedules) (seq schedules))
    (throw (invalid-config :invalid-schedules {:value schedules})))
  (doseq [schedule schedules]
    (when-not (future-schedule/valid-schedule? schedule)
      (throw (invalid-config :invalid-schedule {:value schedule}))))
  (when-not (= (count schedules) (count (set schedules)))
    (throw (invalid-config :duplicate-schedule {:value schedules})))
  (let [sizes (set (map count schedules))]
    (when-not (= 1 (count sizes))
      (throw
       (invalid-config :inconsistent-schedule-size
                       {:sizes (vec (sort sizes))}))))
  schedules)

(defn- error-summary [phase error]
  {:phase phase
   :kind :jolt.sim/exception
   :class (str (class error))
   :message (or (ex-message error) (str error))})

(defn- path-in [dir filename]
  (str (fs/path dir filename)))

(defn- create-run-dir [temp-dir]
  (str
   (fs/create-temp-dir
    (cond-> {:prefix "jolt-sim-explore-"}
      temp-dir (assoc :dir temp-dir)))))

(defn- file-diagnostic [path]
  (try
    (if-not (fs/exists? path)
      {:bytes 0 :truncated? false :text ""}
      (let [stream (java.io.FileInputStream. path)]
        (try
          (let [buffer (byte-array diagnostic-byte-limit)
                filled (loop [offset 0]
                         (if (>= offset diagnostic-byte-limit)
                           offset
                           (let [read-count
                                 (.read stream buffer offset
                                        (- diagnostic-byte-limit offset))]
                             (if (neg? read-count)
                               offset
                               (recur (+ offset read-count))))))
                extra-byte? (and (= filled diagnostic-byte-limit)
                                 (not (neg? (.read stream))))
                observed-length (try
                                  (.length (java.io.File. path))
                                  (catch :default _ filled))
                length (max filled observed-length)]
            {:bytes length
             :truncated? (or extra-byte? (> length filled))
             :text (String. (java.util.Arrays/copyOf buffer filled)
                            "UTF-8")})
          (finally (.close stream)))))
    (catch :default error
      (if-not (fs/exists? path)
        {:bytes 0 :truncated? false :text ""}
        {:bytes nil
         :truncated? false
         :text nil
         :error (error-summary :diagnostic-reading error)}))))

(defn- diagnostics [stdout-path stderr-path]
  {:stdout (file-diagnostic stdout-path)
   :stderr (file-diagnostic stderr-path)})

(defn- monotonic-nanos []
  (System/nanoTime))

(defn- child-alive? [child]
  (.isAlive (:proc child)))

(defn- sleep-ms! [millis]
  (Thread/sleep millis))

(defn- timed-wait! [child timeout-ms]
  ;; Jolt's process record does not currently dispatch IBlockingDeref by arity:
  ;; `(deref child timeout default)` blocks. Do not delegate the deadline to the
  ;; host Process.waitFor shim either: its polling loop subtracts a nominal
  ;; sleep step, so sleep re-activation, scheduling, and liveness-probe overhead
  ;; can stretch a short timeout by many seconds. Poll liveness here against an
  ;; actual monotonic deadline instead.
  (let [deadline (+ (monotonic-nanos) (* timeout-ms 1000000))]
    (loop []
      (if-not (child-alive? child)
        true
        (let [remaining (- deadline (monotonic-nanos))]
          (if (<= remaining 0)
            false
            (do
              (sleep-ms!
               (min wait-poll-ms
                    (max 1 (quot remaining 1000000))))
              (recur))))))))

(defn- wait-for-ready-or-exit!
  "Waits until the paired worker has written its scenario-ready marker, exits,
  or exhausts the startup deadline. Returns exactly :ready, :exited, or
  :deadline. The execution deadline does not begin until :ready is observed."
  [child ready-path timeout-ms]
  (let [deadline (+ (monotonic-nanos) (* timeout-ms 1000000))]
    (loop []
      (cond
        (fs/exists? ready-path) :ready
        (not (child-alive? child)) :exited
        :else
        (let [remaining (- deadline (monotonic-nanos))]
          (if (<= remaining 0)
            :deadline
            (do
              (sleep-ms!
               (min wait-poll-ms
                    (max 1 (quot remaining 1000000))))
              (recur))))))))

(defn- child-pid [child]
  (.pid (:proc child)))

(defn- reaped-exit [child]
  ;; `timed-wait!` has already observed and cached process exit. Reading the
  ;; native exit value is therefore nonblocking and avoids the process record's
  ;; unbounded deref path entirely.
  (try
    (.exitValue (:proc child))
    (catch :default error
      (throw
       (ex-info
        "jolt-sim worker exit could not be observed after wait completion"
        {:type :jolt.sim.process-explorer/worker-exit-unobserved
         :error (error-summary :exit-value error)})))))

(defn- terminate-and-reap!
  "Terminates a timed-out child without any unbounded wait. SIGTERM gets one
  grace interval, then SIGKILL gets one. Failure to observe exit after SIGKILL
  is an infrastructure error, not a normal exploration outcome."
  [child kill-grace-ms]
  (let [term-signal-error
        (try
          (process/destroy child)
          nil
          (catch :default error
            error))
        term-wait
        (try
          {:exited? (boolean (timed-wait! child kill-grace-ms))}
          (catch :default error
            {:exited? false :error error}))]
    (if (:exited? term-wait)
      (reaped-exit child)
      (let [kill-signal-error
            (try
              (.destroyForcibly (:proc child))
              nil
              (catch :default error
                error))
            kill-wait
            (try
              {:exited? (boolean (timed-wait! child kill-grace-ms))}
              (catch :default error
                {:exited? false :error error}))]
        (if (:exited? kill-wait)
          (reaped-exit child)
          (throw
           (ex-info
            "jolt-sim worker exit was not observed after forced termination"
            {:type :jolt.sim.process-explorer/worker-survived-kill
             :termination-errors
             (vec
              (keep identity
                    [(when term-signal-error
                       (error-summary :term-signal term-signal-error))
                     (when-let [error (:error term-wait)]
                       (error-summary :term-wait error))
                     (when kill-signal-error
                       (error-summary :kill-signal kill-signal-error))
                     (when-let [error (:error kill-wait)]
                       (error-summary :kill-wait error))]))})))))))

(defn- process-options [config stdout-path stderr-path activity-path]
  ;; activity-path is nil on the ordinary disabled path; when non-nil it is
  ;; the fixed trusted <run-dir>/activity.journal this parent owns, injected
  ;; into the child environment under the single reserved key.
  {:dir (:dir config)
   :out stdout-path
   :err stderr-path
   ;; jolt.process merges :extra-env into the ambient child environment.
   ;; Always shadow the reserved key so an ambient value cannot silently
   ;; enable journaling on the default-disabled path.
   :extra-env
   (assoc (get config :extra-env {})
          worker/activity-journal-env-key (or activity-path ""))})

(defn- worker-error-outcome
  ([schedule phase error diagnostics]
   (worker-error-outcome schedule phase error diagnostics nil))
  ([schedule phase error diagnostics exit]
   (cond-> {:status :worker-error
            :schedule schedule
            :error (error-summary phase error)
            :diagnostics diagnostics}
     (some? exit) (assoc :exit exit))))

(defn- read-worker-outcome
  [schedule exit result-path stdout-path stderr-path]
  (let [diagnostics (diagnostics stdout-path stderr-path)]
    (cond
      (not (zero? exit))
      (worker-error-outcome
       schedule :nonzero-exit
       (ex-info "exploration worker exited nonzero" {:exit exit})
       diagnostics exit)

      (not (fs/exists? result-path))
      (worker-error-outcome
       schedule :missing-result
       (ex-info "exploration worker wrote no result document" {})
       diagnostics exit)

      :else
      (try
        (assoc (worker/decode-result-edn schedule (slurp result-path))
               :exit exit
               :diagnostics diagnostics)
        (catch :default error
          (worker-error-outcome
           schedule :result-protocol error diagnostics exit))))))

(defn- supervise-child
  [config schedule child result-path ready-path stdout-path stderr-path]
  ;; PID is diagnostic-only. Failure to read it must never abandon an already
  ;; spawned child before the bounded wait/termination/reap path owns it.
  (let [pid-diagnostic
        (try
          {:worker-pid (child-pid child)}
          (catch :default error
            {:worker-pid nil
             :worker-pid-error (error-summary :worker-pid error)}))
        startup-aware? (contains? config :startup-timeout-ms)
        wait-result
        (try
          (if startup-aware?
            (case (wait-for-ready-or-exit!
                   child ready-path (:startup-timeout-ms config))
              :ready
              {:ready? true
               :finished? (boolean (timed-wait! child (:timeout-ms config)))}

              :exited
              {:ready? false :finished? true}

              :deadline
              {:ready? false :startup-timeout? true})
            {:finished? (boolean (timed-wait! child (:timeout-ms config)))})
          (catch :default error
            {:finished? false :error error}))]
    (update
     (cond
       (:error wait-result)
       (let [exit (terminate-and-reap! child (:kill-grace-ms config))]
         (worker-error-outcome
          schedule :process-wait (:error wait-result)
          (diagnostics stdout-path stderr-path)
          exit))

       (:finished? wait-result)
       (let [exit (reaped-exit child)]
         (try
           (read-worker-outcome
            schedule exit result-path stdout-path stderr-path)
           (catch :default error
             (worker-error-outcome
              schedule :process-result error
              (diagnostics stdout-path stderr-path)
              exit))))

       (:startup-timeout? wait-result)
       (let [exit (terminate-and-reap! child (:kill-grace-ms config))]
         (worker-error-outcome
          schedule
          :worker-startup-timeout
          (ex-info
           "exploration worker did not signal scenario readiness before its startup deadline"
           {:startup-timeout-ms (:startup-timeout-ms config)})
          (diagnostics stdout-path stderr-path)
          exit))

       :else
       (let [exit (terminate-and-reap! child (:kill-grace-ms config))]
         {:status :timeout
          :schedule schedule
          :reason :deadline
          :exit exit
          :diagnostics (diagnostics stdout-path stderr-path)}))
     :diagnostics merge pid-diagnostic
     (when startup-aware?
       {:worker-ready? (boolean (:ready? wait-result))}))))

(defn- captured [thunk]
  (try
    {:value (thunk)}
    (catch :default error
      {:error error})))

(defn- retain-run-directory? [error]
  (contains?
   #{:jolt.sim.process-explorer/worker-survived-kill
     :jolt.sim.process-explorer/worker-exit-unobserved}
   (:type (ex-data error))))

(defn- retain-outcome-artifacts? [outcome]
  (not= :completed (:status outcome)))

(defn- retained-exception [error run-dir]
  (ex-info
   (or (ex-message error) (str error))
   (assoc (or (ex-data error) {}) :artifact-dir run-dir)
   error))

(defn- notify-run-dir!
  "Best-effort notification hook: reports the trusted run directory to an
  optional caller-supplied `:on-run-dir` once it exists, before any request is
  written or worker spawned. Its return value and any exception are ignored.
  The observer is trusted and synchronous, so it must normally return promptly;
  tests may deliberately block it to inspect the pre-spawn state."
  [config run-dir]
  (when-let [observer (:on-run-dir config)]
    (try
      (observer run-dir)
      (catch :default _ nil))))

(defn- run-worker!
  "Shared spawn/supervise/cleanup machinery for one fresh worker process,
  driven by an already-validated config plus the exact schedule (nil for a
  no-schedule case) and scenario input to place in the request."
  [config schedule input]
  (let [run-dir (create-run-dir (:temp-dir config))
        _ (notify-run-dir! config run-dir)
        request-path (path-in run-dir "request.edn")
        result-path (path-in run-dir "result.edn")
        ready-path (path-in run-dir "worker-ready.edn")
        stdout-path (path-in run-dir "stdout.log")
        stderr-path (path-in run-dir "stderr.log")
        ;; The parent owns the fixed trusted journal path; it is computed here
        ;; from the fresh run directory and never taken from caller config.
        activity-path
        (when (get config :activity-journal? false)
          (path-in run-dir activity-journal-filename))
        keep-temp? (volatile! false)]
    (try
      (let [outcome
            (try
              (let [request-write
                    (captured
                     #(spit
                       request-path
                       (trace/canonical-edn
                        (worker/request-document
                         (:scenario config) schedule input))))]
                (if-let [error (:error request-write)]
                  (worker-error-outcome
                   schedule :request-writing error
                   (diagnostics stdout-path stderr-path))
                  (let [command
                        (cond->
                         (into (:worker-command config)
                               [request-path result-path])
                          (contains? config :startup-timeout-ms)
                          (conj ready-path))
                        spawn
                        (captured
                         #(process/process
                           command
                           (process-options
                            config stdout-path stderr-path activity-path)))]
                    (if-let [error (:error spawn)]
                      (worker-error-outcome
                       schedule :process-spawn error
                       (diagnostics stdout-path stderr-path))
                      (supervise-child
                       config schedule (:value spawn)
                       result-path
                       (when (contains? config :startup-timeout-ms)
                         ready-path)
                       stdout-path stderr-path)))))
              (catch :default error
                ;; `supervise-child` converts every ordinary post-spawn
                ;; failure into an outcome. Only the two explicit "exit not
                ;; observed" conditions may escape. Any future escaping path
                ;; must first prove the child reaped or be added to
                ;; retain-run-directory?.
                (if (retain-run-directory? error)
                  (do
                    ;; A child whose death was not observed may still retain
                    ;; or mutate its artifacts. Keep them, expose the exact
                    ;; directory on the escaping error, and do not claim an
                    ;; ordinary exploration outcome.
                    (vreset! keep-temp? true)
                    (throw (retained-exception error run-dir)))
                  (throw error))))]
        (if (or (get config :retain-completed-artifacts? false)
                (retain-outcome-artifacts? outcome))
          (do
            (vreset! keep-temp? true)
            (assoc outcome :artifact-dir run-dir))
          outcome))
      (finally
        (when-not @keep-temp?
          (fs/delete-tree run-dir))))))

(defn run-schedule
  "Runs one exact future schedule in a fresh worker process.

  Required config:

    :worker-command  nonempty vector of literal command/argument strings; the
                     request and result paths are appended. Name the Jolt
                     executable directly or use an exec-style wrapper: this
                     host does not reclaim an arbitrary descendant tree.
    :scenario        namespaced symbol naming a `defsim` var
    :schedule        exact permutation accepted by :future-schedule
    :timeout-ms      positive child deadline
    :dir             explicit child working directory

  Optional `:startup-timeout-ms` enables the paired worker-ready handshake and
  bounds bootstrap separately; when present, `:timeout-ms` begins only after
  the marked scenario is resolved and ready to execute. A startup deadline is
  a `:worker-error`, while a post-ready execution deadline remains `:timeout`.
  Omitting it preserves the original single-deadline/two-argument worker path.
  Optional `:extra-env` is a string map, `:kill-grace-ms` defaults to 250,
  `:temp-dir` selects an existing parent for per-run artifacts, and
  `:retain-completed-artifacts?` defaults to false. Optional `:on-run-dir` is a
  one-argument function invoked with the trusted run directory string once it
  exists, before any request is written or worker spawned. Its return value and
  any exception are ignored. It is synchronous and trusted, so production
  observers must return promptly; tests may deliberately block it before spawn.

  Optional `:activity-journal?` (boolean, default false) opts the run into
  the crash-recoverable worker lifecycle activity journal: the child opens a
  `jolt.sim.activity` observer on the fixed trusted
  `<run-dir>/activity.journal` before scenario execution and records the
  scenario-started/completed/failed lifecycle with bounded identifiers only.
  An enabled scenario must carry `:jolt.sim/activity-lifecycle-owned true`;
  `defsim` supplies it because its controlled scope drains owned work before
  return, while plain marked functions must explicitly promise the same.
  The parent owns that path and injects it into the child environment under
  one reserved key; any `:extra-env` collision with the reserved key is
  rejected before launch, so a caller or browser never supplies an activity
  path. Enabling it requires `:retain-completed-artifacts?` true so fast
  successful evidence cannot disappear before polling. Activity open, emit,
  or close failure invalidates the run into a bounded `:worker-error` (an
  application failure remains primary with bounded secondary diagnostics).
  Journal durability is `:process-crash` only: no fsync, power-loss, or
  kernel-crash claim. When disabled (the default) behavior is unchanged and
  no journal is created.

  Returns one `:completed`, `:failed`, `:timeout`, or `:worker-error` map.
  Timeout means only that the child did not exit by the deadline; it is not a
  proof of deadlock. Every non-completed outcome retains its per-run directory
  as `:artifact-dir`; the directory contains the observed `request.edn`,
  `result.edn`, `stdout.log`, and `stderr.log` files, with absent files left
  honestly absent. Completed runs remove their directory unless
  `:retain-completed-artifacts?` is true, in which case they retain it and
  return `:artifact-dir` exactly like non-completed outcomes so a caller can
  evaluate parent-side semantic invariants before deleting it."
  [config]
  (let [config (validate-run-config! config)]
    (run-worker! config (:schedule config) nil)))

(defn run-case
  "Runs one general controlled-execution case in a fresh worker process,
  carrying an optional canonical `:input` value and optional exact `:schedule`.

  Required config is the same as `run-schedule` except `:schedule` is optional;
  nil or absence drives no `:future-schedule` override. `:input` is optional and
  defaults to nil. Supplying both is the common workload/fault/schedule path for
  generated and replayed cases.

  Returns the same `:completed`/`:failed`/`:timeout`/`:worker-error` shape and
  artifact-retention contract as `run-schedule`, including the opt-in
  `:retain-completed-artifacts?` option, the optional `:on-run-dir` observer,
  and the opt-in `:activity-journal?` worker lifecycle journal (which likewise
  requires `:retain-completed-artifacts?` true), echoing the effective
  schedule (including nil)."
  [config]
  (let [config (validate-case-config! config)]
    (run-worker! config (:schedule config) (:input config))))

(defn explore
  "Runs each exact schedule sequentially in input order and returns its ordered
  outcome vector. `:schedules` must be a nonempty vector of unique permutations
  of one common size. Other options are the `run-schedule` options except
  `:schedule`.

  The order may come from `jolt.sim.explore/schedule-plans`, a permanent replay
  witness, or a later Hegel/high-utility sampler; this supervisor does not
  claim its own search strategy. Every non-completed element inherits
  `run-schedule`'s `:artifact-dir` retention contract; with
  `:retain-completed-artifacts?` true, completed elements retain their
  directories too. Callers that intentionally consume expected failures are
  responsible for deleting those directories."
  [config]
  (validate-common! config explore-keys)
  (let [schedules (validate-schedules! (:schedules config))
        base (dissoc config :schedules)]
    (mapv (fn [schedule]
            (run-schedule (assoc base :schedule schedule)))
          schedules)))
