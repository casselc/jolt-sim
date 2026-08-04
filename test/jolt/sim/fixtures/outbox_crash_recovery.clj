(ns jolt.sim.fixtures.outbox-crash-recovery
  "Process-explorer worker scenarios for one bounded outbox crash-recovery
  slice: a real-native producer commits one outbox row over an ordinary
  file-backed SQLite connection, writes one post-COMMIT checkpoint, and
  exits the process deliberately (System/exit 86) while that connection
  is still live; a separate real-native recovery worker opens a fresh
  connection to the same file, reloads the committed pending row, and
  delivers it over the ordinary framed TCP/bencode path.

  Both scenarios are real-native workers: they open real JDBC SQLite
  connections and (for recovery) a real TCP receiver. They are not
  controlled simulator cases and accept no runtime overrides. The closed
  worker input is exactly {:db-path <nonempty string>}; the SQLite spec
  is derived as (str \"sqlite:\" db-path) and the checkpoint path as
  (str db-path \".post-commit.edn\"). Neither is accepted as a separate
  input field.

  Honesty boundary. The post-COMMIT checkpoint proves control reached
  application code past COMMIT and that the checkpoint file was closed
  (spit flushes and closes) before the deliberate process exit. It is
  NOT an fsync, append-only journal, WAL, power-loss, torn-write,
  machine-crash, or exactly-once proof. The process boundary is a
  deliberate System/exit 86, not SIGKILL or host failure: SQLite's COMMIT
  had already returned before the checkpoint, so the durable image is
  committed regardless of how the process terminates afterwards. The
  delivery phase is still at-least-once: a crash after the remote
  acknowledgement but before durable marking can redeliver."
  (:require [jolt.sim.fixtures.outbox-delivery :as fixture]))

(def ^:private worker-input-keys #{:db-path})

(def ^:private deliberate-exit-discriminator 86)

(defn- invalid-worker-input [reason data]
  (ex-info
   "invalid outbox-crash-recovery worker input"
   (merge {:type :jolt.sim.fixtures.outbox-crash-recovery/invalid-input
           :reason reason}
          data)))

(defn- validate-worker-input!
  "Validates the closed worker schema before any resource is opened. The
  input MUST be exactly {:db-path <nonempty string>}. Unknown keys,
  missing keys, and non-string or empty db-path values are rejected."
  [input]
  (when-not (map? input)
    (throw (invalid-worker-input :not-a-map {:input input})))
  (let [actual-keys (set (keys input))
        unknown (seq (sort-by pr-str
                              (remove worker-input-keys actual-keys)))
        missing (seq (sort-by pr-str
                              (remove actual-keys worker-input-keys)))]
    (when unknown
      (throw (invalid-worker-input
              :unknown-keys
              {:unknown-keys (vec unknown) :input input})))
    (when missing
      (throw (invalid-worker-input
              :missing-keys
              {:missing-keys (vec missing) :input input}))))
  (let [db-path (:db-path input)]
    (when-not (and (string? db-path) (pos? (count db-path)))
      (throw (invalid-worker-input
              :invalid-db-path
              {:value db-path}))))
  input)

(defn- validate-no-overrides!
  "This real-native worker is not a controlled simulator case and supports
  no runtime overrides. Any nonempty overrides map (for example a drawn
  :future-schedule) is rejected before resources are opened."
  [overrides]
  (when (seq overrides)
    (throw
     (invalid-worker-input
      :unsupported-overrides
      {:overrides (into (sorted-map) overrides)})))
  overrides)

(defn- sqlite-spec-for [db-path]
  (str "sqlite:" db-path))

(defn- checkpoint-path-for [db-path]
  (str db-path ".post-commit.edn"))

(defn- post-commit-checkpoint [evidence]
  {:checkpoint/version 1
   :phase :post-commit
   :evidence evidence})

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} producer-crash
  "Real-native process-explorer scenario: commits one outbox row over an
  ordinary file-backed SQLite connection, writes one post-COMMIT
  checkpoint, and exits the process deliberately while that connection is
  still live.

  Drives
  jolt.sim.fixtures.outbox-delivery/exercise-outbox-commit-before-close
  with the canonical default-command over the derived spec
  (str \"sqlite:\" db-path). The after-commit callback runs INSIDE the
  connection callback after COMMIT and HTTP-server quiescence but BEFORE
  the ordinary connection closes; it:

  - writes one complete newline-terminated EDN checkpoint to the derived
    path (str db-path \".post-commit.edn\"), exactly
    {:checkpoint/version 1 :phase :post-commit :evidence evidence}, via
    spit so the file is closed before proceeding;
  - flushes standard output best-effort;
  - calls (System/exit 86) as the deliberate process boundary.

  The OS therefore kills the process while the ordinary connection is
  still open. This scenario never writes a normal worker result: if
  System/exit ever returns (it must not), a typed
  :jolt.sim.fixtures.outbox-crash-recovery/exit-returned error is thrown
  rather than reporting completion.

  The checkpoint proves control reached post-COMMIT and was closed before
  exit; it is NOT an fsync, WAL, power-loss, torn-write, or
  machine-crash proof. The process boundary is a deliberate System/exit
  86, not SIGKILL or host failure. Accepts the standard
  jolt.sim.explore-worker protocol-v2 (runtime-overrides, input) arity;
  any nonempty overrides are rejected because this is a real-native
  worker, not a controlled simulator case."
  ([input]
   (producer-crash {} input))
  ([overrides input]
   (validate-worker-input! input)
   (validate-no-overrides! overrides)
   (let [db-path (:db-path input)
         spec (sqlite-spec-for db-path)
         checkpoint-path (checkpoint-path-for db-path)]
     (fixture/exercise-outbox-commit-before-close
      spec
      fixture/default-command
      (fn after-commit! [evidence]
        ;; One complete newline-terminated EDN checkpoint, written and
        ;; closed via spit before proceeding. The evidence is the closed
        ;; immutable application+HTTP value from the commit seam.
        (spit checkpoint-path
              (str (pr-str (post-commit-checkpoint evidence)) "\n"))
        ;; Best-effort flush so any buffered standard output is visible
        ;; even though the process is about to terminate.
        (flush)
        ;; The deliberate process boundary. System/exit 86 is the
        ;; discriminator this scenario's recovery lane keys on; the OS
        ;; kills the process here while the ordinary connection is
        ;; still live (the callback runs inside
        ;; with-sqlite-connection, before close).
        (System/exit deliberate-exit-discriminator)
        ;; Unreachable in normal operation: System/exit is terminal. If
        ;; it ever returns, fail closed rather than reporting a normal
        ;; worker result.
        (throw
         (ex-info
          "outbox-crash-recovery: System/exit returned without terminating"
          {:type :jolt.sim.fixtures.outbox-crash-recovery/exit-returned
           :discriminator deliberate-exit-discriminator})))))))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} recover-delivery
  "Real-native process-explorer scenario: the recovery companion to
  producer-crash. Opens a fresh ordinary JDBC SQLite connection to the
  derived spec (str \"sqlite:\" db-path), reloads the committed pending
  outbox row left by a prior producer-crash worker, delivers it over the
  existing ordinary framed TCP/bencode path with exact correlated-ack
  validation, durably marks it delivered, and returns
  jolt.sim.fixtures.outbox-delivery/exercise-outbox-recovery's closed
  immutable evidence:

    {:application {:pending-state ... :store-state ... :marking ...
                   :delivery ...}
     :receiver {:requests ... :server-errors ...}}

  This is still an at-least-once delivery witness: a crash after the
  remote acknowledgement but before durable marking can redeliver. Accepts
  the standard jolt.sim.explore-worker protocol-v2 (runtime-overrides,
  input) arity; any nonempty overrides are rejected."
  ([input]
   (recover-delivery {} input))
  ([overrides input]
   (validate-worker-input! input)
   (validate-no-overrides! overrides)
   (let [db-path (:db-path input)
         spec (sqlite-spec-for db-path)]
     (fixture/exercise-outbox-recovery spec))))
