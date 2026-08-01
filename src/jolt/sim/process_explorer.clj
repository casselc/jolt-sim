(ns jolt.sim.process-explorer
  "Sequential process supervisor for ordinary-future schedule exploration.

  Each candidate schedule runs in a fresh Jolt child through the versioned
  `jolt.sim.explore-worker` file protocol. A child that does not exit before its
  deadline is terminated and reaped; it is reported as `:timeout`, not
  mislabeled as a proven deadlock. Poisoned controller state, blocked future
  gates, raw threads, and other process-global damage therefore cannot leak
  into the next schedule.

  This is harness code and must run outside `run-controlled`. It launches real
  operating-system processes through `jolt.process`; it is not a simulated
  application effect."
  (:require [jolt.fs :as fs]
            [jolt.process :as process]
            [jolt.sim.explore-worker :as worker]
            [jolt.sim.future-schedule :as future-schedule]
            [jolt.sim.trace :as trace]))

(def ^:private run-keys
  #{:worker-command :scenario :schedule :timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir})

(def ^:private case-keys
  #{:worker-command :scenario :schedule :input :timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir})

(def ^:private explore-keys
  #{:worker-command :scenario :schedules :timeout-ms :kill-grace-ms
    :dir :extra-env :temp-dir})

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
  (when (contains? config :temp-dir)
    (let [temp-dir (:temp-dir config)]
      (when-not (and (string? temp-dir) (seq temp-dir))
        (throw (invalid-config :invalid-temp-dir {:value temp-dir})))))
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
      (let [length (.length (java.io.File. path))]
        (if (<= length diagnostic-byte-limit)
          {:bytes length :truncated? false :text (slurp path)}
          {:bytes length :truncated? true :text nil})))
    (catch :default error
      {:bytes nil
       :truncated? false
       :text nil
       :error (error-summary :diagnostic-reading error)})))

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

(defn- process-options [config stdout-path stderr-path]
  (cond-> {:dir (:dir config)
           :out stdout-path
           :err stderr-path}
    (contains? config :extra-env)
    (assoc :extra-env (:extra-env config))))

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
  [config schedule child result-path stdout-path stderr-path]
  ;; PID is diagnostic-only. Failure to read it must never abandon an already
  ;; spawned child before the bounded wait/termination/reap path owns it.
  (let [pid-diagnostic
        (try
          {:worker-pid (child-pid child)}
          (catch :default error
            {:worker-pid nil
             :worker-pid-error (error-summary :worker-pid error)}))
        wait-result
        (try
          {:finished? (boolean (timed-wait! child (:timeout-ms config)))}
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

       :else
       (let [exit (terminate-and-reap! child (:kill-grace-ms config))]
         {:status :timeout
          :schedule schedule
          :reason :deadline
          :exit exit
          :diagnostics (diagnostics stdout-path stderr-path)}))
     :diagnostics merge pid-diagnostic)))

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

(defn- run-worker!
  "Shared spawn/supervise/cleanup machinery for one fresh worker process,
  driven by an already-validated config plus the exact schedule (nil for a
  no-schedule case) and scenario input to place in the request."
  [config schedule input]
  (let [run-dir (create-run-dir (:temp-dir config))
        request-path (path-in run-dir "request.edn")
        result-path (path-in run-dir "result.edn")
        stdout-path (path-in run-dir "stdout.log")
        stderr-path (path-in run-dir "stderr.log")
        keep-temp? (volatile! false)]
    (try
      (try
        (let [request-write
              (captured
               #(spit
                 request-path
                 (trace/canonical-edn
                  (worker/request-document (:scenario config) schedule input))))]
          (if-let [error (:error request-write)]
            (worker-error-outcome
             schedule :request-writing error
             (diagnostics stdout-path stderr-path))
            (let [command
                  (into (:worker-command config)
                        [request-path result-path])
                  spawn
                  (captured
                   #(process/process
                     command
                     (process-options config stdout-path stderr-path)))]
              (if-let [error (:error spawn)]
                (worker-error-outcome
                 schedule :process-spawn error
                 (diagnostics stdout-path stderr-path))
                (supervise-child
                 config schedule (:value spawn)
                 result-path stdout-path stderr-path)))))
        (catch :default error
          ;; `supervise-child` converts every ordinary post-spawn failure into
          ;; an outcome. Only the two explicit "exit not observed" conditions
          ;; may escape. Any future escaping path must first prove the child
          ;; reaped or be added to retain-run-directory?.
          (when (retain-run-directory? error)
            ;; A child whose death was not observed may still retain or mutate
            ;; its artifacts; keep them for diagnosis and do not claim an
            ;; ordinary exploration outcome.
            (vreset! keep-temp? true))
          (throw error)))
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

  Optional `:extra-env` is a string map, `:kill-grace-ms` defaults to 250, and
  `:temp-dir` selects an existing parent for per-run artifacts.

  Returns one `:completed`, `:failed`, `:timeout`, or `:worker-error` map.
  Timeout means only that the child did not exit by the deadline; it is not a
  proof of deadlock."
  [config]
  (let [config (validate-run-config! config)]
    (run-worker! config (:schedule config) nil)))

(defn run-case
  "Runs one general exploration case in a fresh worker process, carrying an
  optional canonical `:input` value and optional exact `:schedule`.

  Required config is the same as `run-schedule` except `:schedule` is optional;
  nil or absence drives no `:future-schedule` override. `:input` is optional and
  defaults to nil. Supplying both is the common workload/fault/schedule path for
  generated and replayed cases.

  Returns the same `:completed`/`:failed`/`:timeout`/`:worker-error` shape as
  `run-schedule`, echoing the effective schedule (including nil)."
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
  claim its own search strategy."
  [config]
  (validate-common! config explore-keys)
  (let [schedules (validate-schedules! (:schedules config))
        base (dissoc config :schedules)]
    (mapv (fn [schedule]
            (run-schedule (assoc base :schedule schedule)))
          schedules)))
