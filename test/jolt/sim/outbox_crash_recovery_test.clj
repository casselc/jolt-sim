(ns jolt.sim.outbox-crash-recovery-test
  "Real-SQLite process-boundary witness for the ordinary outbox application.

   A fresh producer worker runs the ordinary bencoded HTTP command through
   SQLite COMMIT, writes a closed EDN checkpoint, then deliberately exits 86
   before its still-live connection can close or the worker protocol can write
   result.edn. The parent requires that exact nonzero exit and retains the
   original request/stdout/stderr tree. A second fresh worker receives only the
   database path, reloads the pending row before any TCP delivery, validates an
   ordinary framed bencode acknowledgement, marks the row delivered, closes,
   and retains its complete worker tree.

   This is a real process-restart and SQLite file-survival witness. It is not a
   SIGKILL, machine-crash, fsync, power-loss, WAL/torn-write, append-only
   journal, or exactly-once proof. Every case directory is retained even on a
   pass. Before recovery can mutate the live database, the parent copies the
   quiescent post-producer database, checkpoint, and every present SQLite
   sidecar into a separate snapshot directory. The snapshot, final database,
   worker protocol documents, and parent observations remain available for
   forensic inspection."
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.explore-worker :as worker]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)
(def ^:dynamic *case-dir* nil)

(def ^:private producer-scenario
  'jolt.sim.fixtures.outbox-crash-recovery/producer-crash)
(def ^:private recovery-scenario
  'jolt.sim.fixtures.outbox-crash-recovery/recover-delivery)
(def ^:private worker-timeout-ms 60000)
(def ^:private kill-grace-ms 500)
(def ^:private deliberate-exit 86)
(def ^:private checkpoint-suffix ".post-commit.edn")

(def ^:private expected-command
  {:request-id "req-1"
   :entity-id "entity-a"
   :payload [0 127 128 255]})

(def ^:private expected-result
  {:status :committed
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :outbox-id 1})

(def ^:private expected-identities
  {:request-id "req-1"
   :transaction-id [:outbox/command "req-1"]
   :outbox-id 1
   :delivery-id [:outbox/delivery 1]
   :attempt-id [:outbox/delivery-attempt 1 1]})

(def ^:private expected-pending-row
  {:outbox-id 1
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :payload [0 127 128 255]
   :status :pending})

(def ^:private expected-delivered-row
  (assoc expected-pending-row :status :delivered))

(def ^:private expected-pending-state
  {:entities {"entity-a" {:version 1 :payload [0 127 128 255]}}
   :request-log {"req-1" {:command expected-command
                           :result expected-result}}
   :next-outbox-id 2
   :outbox [expected-pending-row]})

(def ^:private expected-delivered-state
  (assoc expected-pending-state :outbox [expected-delivered-row]))

(def ^:private expected-message
  {"type" "outbox_delivery"
   "outbox-id" 1
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "payload" [0 127 128 255]
   "attempt" 1})

(def ^:private expected-ack
  {"type" "outbox_delivery_ok" "outbox-id" 1 "attempt" 1})

(def ^:private expected-http-response
  {"type" "command_ok"
   "status" "committed"
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "outbox-id" 1})

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.outbox-crash-recovery-test/missing-environment
         :name name})))
    value))

(defn- nonempty-environment [name]
  (let [value (System/getenv name)]
    (when (and (string? value) (seq value)) value)))

(defn- configured [value label]
  (or value
      (throw
       (ex-info
        (str "outbox crash/recovery test must be run through -main: " label)
        {:type :jolt.sim.outbox-crash-recovery-test/not-configured
         :field label}))))

(defn- exact-map-keys? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(defn- path-in [dir filename]
  (str (fs/path dir filename)))

(defn- append-progress! [case-dir record]
  ;; Best-effort parent breadcrumbs, deliberately not the later proved
  ;; crash-safe append-only journal contract.
  (spit (path-in case-dir "progress.edn")
        (str (pr-str record) "\n")
        :append true))

(defn- write-edn! [path value]
  (spit path (str (pr-str value) "\n")))

(defn- checkpoint-path [db-path]
  (str db-path checkpoint-suffix))

(defn- artifact-path [outcome filename]
  (when-let [dir (:artifact-dir outcome)]
    (path-in dir filename)))

(defn- artifact-exists? [outcome filename]
  (boolean
   (when-let [path (artifact-path outcome filename)]
     (fs/exists? path))))

(defn- file-summary [label path]
  (let [exists? (fs/exists? path)]
    {:label label
     :path path
     :exists? exists?
     :bytes (when exists? (.length (java.io.File. path)))}))

(defn- sqlite-file-summaries [db-path]
  [(file-summary :database db-path)
   (file-summary :wal (str db-path "-wal"))
   (file-summary :shm (str db-path "-shm"))
   (file-summary :rollback-journal (str db-path "-journal"))])

(defn- post-producer-file-specs [snapshot-dir db-path checkpoint]
  (mapv
   (fn [[label source]]
     {:label label
      :source source
      :snapshot (path-in snapshot-dir (str (fs/file-name source)))})
   [[:post-commit-checkpoint checkpoint]
    [:database db-path]
    [:wal (str db-path "-wal")]
    [:shm (str db-path "-shm")]
    [:rollback-journal (str db-path "-journal")]]))

(defn- post-producer-file-summaries [snapshot-dir db-path checkpoint]
  (mapv
   (fn [{:keys [label snapshot]}]
     (file-summary label snapshot))
   (post-producer-file-specs snapshot-dir db-path checkpoint)))

(defn- snapshot-post-producer!
  [case-dir snapshot-dir db-path checkpoint]
  ;; process-explorer has observed and reaped the producer, so these files are
  ;; quiescent. Copy them before the recovery worker can checkpoint, delete a
  ;; sidecar, or change the pending row to delivered.
  (fs/create-dirs snapshot-dir)
  (let [records
        (mapv
         (fn [{:keys [label source snapshot]}]
           (let [source-summary (file-summary label source)]
             (when (:exists? source-summary)
               (fs/copy source snapshot))
             (let [snapshot-summary (file-summary label snapshot)]
               (when-not (= (select-keys source-summary [:exists? :bytes])
                            (select-keys snapshot-summary [:exists? :bytes]))
                 (throw
                  (ex-info
                   "Post-producer forensic snapshot did not match its source"
                   {:type :jolt.sim.outbox-crash-recovery-test/snapshot-mismatch
                    :label label
                    :source source-summary
                    :snapshot snapshot-summary})))
               {:label label
                :source source-summary
                :snapshot snapshot-summary})))
         (post-producer-file-specs snapshot-dir db-path checkpoint))
        record {:snapshot/version 1 :files records}]
    (write-edn! (path-in case-dir "post-producer-snapshot.edn") record)
    (append-progress! case-dir {:phase :post-producer-snapshot
                                :snapshot-dir snapshot-dir
                                :files records})
    record))

(defn- run-worker! [scenario db-path]
  (process-explorer/run-case
   (merge
    (configured *process-config* :process-config)
    {:scenario scenario
     :input {:db-path db-path}
     :timeout-ms worker-timeout-ms
     :kill-grace-ms kill-grace-ms
     ;; Retain even an unexpected completion: a producer regression must not
     ;; erase its result, and the successful recovery tree is evidence.
     :retain-completed-artifacts? true})))

(defn- assert-worker-artifacts!
  [outcome present absent]
  (let [dir (:artifact-dir outcome)]
    (is (and (string? dir) (fs/exists? dir))
        (str "retained worker directory must exist: " (pr-str dir)))
    (doseq [filename present]
      (is (artifact-exists? outcome filename)
          (str "missing retained worker artifact " filename " under " dir)))
    (doseq [filename absent]
      (is (not (artifact-exists? outcome filename))
          (str "worker artifact must remain honestly absent: " filename)))))

(defn- expected-request [scenario db-path]
  (worker/request-document scenario nil {:db-path db-path}))

(deftest committed-outbox-survives-deliberate-process-exit-and-recovers
  (let [case-dir (configured *case-dir* :case-dir)
        db-path (path-in case-dir "outbox.db")
        checkpoint (checkpoint-path db-path)
        snapshot-dir (path-in case-dir "post-producer")
        producer* (volatile! nil)
        snapshot* (volatile! nil)
        recovery* (volatile! nil)]
    (try
      (testing "the producer reaches COMMIT, checkpoints, and exits 86 without a result"
        (let [producer (run-worker! producer-scenario db-path)]
          (vreset! producer* producer)
          (write-edn! (path-in case-dir "producer-outcome.edn") producer)
          (append-progress!
           case-dir
           {:phase :producer-observed
            :status (:status producer)
            :exit (:exit producer)
            :artifact-dir (:artifact-dir producer)})
          (is (= :worker-error (:status producer)))
          (is (= deliberate-exit (:exit producer)))
          (is (= :nonzero-exit (get-in producer [:error :phase])))
          (is (nil? (:schedule producer)))
          (assert-worker-artifacts!
           producer
           ["request.edn" "stdout.log" "stderr.log"]
           ["result.edn"])
          (when (artifact-exists? producer "request.edn")
            (is (= (expected-request producer-scenario db-path)
                   (edn/read-string
                    (slurp (artifact-path producer "request.edn"))))))
          (is (fs/exists? checkpoint)
              "post-COMMIT checkpoint must be closed before exit 86")
          (is (fs/exists? db-path)
              "the real SQLite database must survive the producer process")
          (when (fs/exists? checkpoint)
            (let [record (edn/read-string (slurp checkpoint))
                  evidence (:evidence record)]
              (is (exact-map-keys?
                   record #{:checkpoint/version :phase :evidence}))
              (is (= 1 (:checkpoint/version record)))
              (is (= :post-commit (:phase record)))
              (is (exact-map-keys? evidence #{:application :http}))
              (is (exact-map-keys?
                   (:application evidence)
                   #{:identities :command :committed-state}))
              (is (= expected-identities
                     (get-in evidence [:application :identities])))
              (is (= {:value expected-command
                      :result expected-result
                      :emitted [expected-pending-row]}
                     (get-in evidence [:application :command])))
              (is (= expected-pending-state
                     (get-in evidence [:application :committed-state])))
              (is (exact-map-keys?
                   (:http evidence)
                   #{:status :content-type :content-length :response
                     :server-errors :close-results}))
              (is (= 200 (get-in evidence [:http :status])))
              (is (= "application/x-bencode"
                     (get-in evidence [:http :content-type])))
              (is (= expected-http-response
                     (get-in evidence [:http :response])))
              (is (empty? (get-in evidence [:http :server-errors])))
              (is (= {:connection [true false]}
                     (get-in evidence [:http :close-results])))))))

      (let [producer @producer*
            producer-ready?
            (and (= :worker-error (:status producer))
                 (= deliberate-exit (:exit producer))
                 (= :nonzero-exit (get-in producer [:error :phase]))
                 (fs/exists? checkpoint)
                 (fs/exists? db-path))]
        (testing "a fresh worker receives only the DB path and completes recovery"
          (if-not producer-ready?
            (is false "recovery was not started because producer evidence failed")
            (let [snapshot
                  (snapshot-post-producer!
                   case-dir snapshot-dir db-path checkpoint)
                  _ (vreset! snapshot* snapshot)
                  _ (doseq [{:keys [source snapshot]} (:files snapshot)]
                      (is (= (:exists? source) (:exists? snapshot))
                          "snapshot presence must match the quiescent source")
                      (is (= (:bytes source) (:bytes snapshot))
                          "snapshot byte length must match the quiescent source"))
                  recovery (run-worker! recovery-scenario db-path)
                  evidence (:result recovery)
                  app (:application evidence)
                  delivery (:delivery app)]
              (vreset! recovery* recovery)
              (write-edn! (path-in case-dir "recovery-outcome.edn") recovery)
              (append-progress!
               case-dir
               {:phase :recovery-observed
                :status (:status recovery)
                :exit (:exit recovery)
                :artifact-dir (:artifact-dir recovery)})
              (is (= :completed (:status recovery)))
              (is (= 0 (:exit recovery)))
              (is (nil? (:schedule recovery)))
              (assert-worker-artifacts!
               recovery
               ["request.edn" "result.edn" "stdout.log" "stderr.log"]
               [])
              (when (artifact-exists? recovery "request.edn")
                (is (= (expected-request recovery-scenario db-path)
                       (edn/read-string
                        (slurp (artifact-path recovery "request.edn"))))))
              (is (exact-map-keys? evidence #{:application :receiver}))
              (is (exact-map-keys?
                   app #{:pending-state :store-state :marking :delivery}))
              (is (exact-map-keys?
                   (:receiver evidence) #{:requests :server-errors}))
              (is (exact-map-keys?
                   delivery
                   #{:requests :replies :sent-bytes :received-bytes
                     :close-results}))
              (is (= expected-pending-state (:pending-state app)))
              (is (= expected-delivered-state (:store-state app)))
              (is (= {:row expected-delivered-row :changed? true}
                     (:marking app)))
              (is (= [expected-message] (:requests delivery)))
              (is (= [expected-ack] (:replies delivery)))
              (is (pos? (:sent-bytes delivery)))
              (is (pos? (:received-bytes delivery)))
              (is (= {:connection [true false]} (:close-results delivery)))
              (is (= [expected-message]
                     (get-in evidence [:receiver :requests])))
              (is (empty? (get-in evidence [:receiver :server-errors])))
              (is (fs/exists? db-path)
                  "the recovered and marked database remains retained")))))
      (finally
        ;; This manifest is written whether assertions pass, fail, or throw.
        ;; Existing files are never removed; absence is recorded honestly.
        (let [manifest
              {:manifest/version 2
               :case-dir case-dir
               :post-producer
               {:directory snapshot-dir
                :record
                (file-summary
                 :post-producer-snapshot
                 (path-in case-dir "post-producer-snapshot.edn"))
                :files
                (post-producer-file-summaries
                 snapshot-dir db-path checkpoint)
                :observed @snapshot*}
               :final
               {:checkpoint
                (file-summary :post-commit-checkpoint checkpoint)
                :sqlite-files (sqlite-file-summaries db-path)}
               :producer-artifact-dir (:artifact-dir @producer*)
               :recovery-artifact-dir (:artifact-dir @recovery*)}]
          (try
            (write-edn! (path-in case-dir "forensics.edn") manifest)
            (append-progress! case-dir {:phase :forensics-written
                                        :manifest manifest})
            (catch :default error
              (println "warning: failed to write crash forensics manifest"
                       (or (ex-message error) (str error))))))))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        artifact-root
        (or (nonempty-environment "JOLT_SIM_CRASH_ARTIFACT_DIR")
            (path-in project-dir "target/outbox-crash-artifacts"))
        _ (fs/create-dirs artifact-root)
        case-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-outbox-crash-" :dir artifact-root}))
        worker-root (path-in case-dir "workers")
        _ (fs/create-dirs worker-root)
        _ (append-progress! case-dir {:phase :start :status :running})
        _ (println (str "outbox crash/recovery retained case-dir: " case-dir))
        _ (flush)
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:outbox-crash-worker"]
                   :dir project-dir
                   :temp-dir worker-root}
                  *case-dir* case-dir]
          (test/run-tests 'jolt.sim.outbox-crash-recovery-test))
        counts (select-keys result [:test :pass :fail :error])
        failures (+ (:fail result) (:error result))]
    (append-progress! case-dir {:phase :finish
                                :status (if (zero? failures) :passed :failed)
                                :counts counts})
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (println (str "retained case-dir: " case-dir))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
