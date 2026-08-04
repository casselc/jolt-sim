(ns jolt.sim.outbox-delivery-integration-test-main
  (:require [clojure.test :as test]
            [jolt.fs :as fs]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
            [jolt.sim.outbox-delivery-integration-test :as integration]))

(def ^:private watchdog-timeout-ms 180000)

;; The bare filename selected by the hermetic SQLite file-image substrate.
;; Both the real and hermetic reopen lanes open this same ordinary filename;
;; the real lane qualifies it under a unique case directory, the hermetic lane
;; opens it bare so the simulator's exact filename match selects the image.
(def ^:private reopen-bare-filename "outbox.db")

(defn- nonempty-env [name]
  (let [value (System/getenv name)]
    (when (seq value) value)))

(defn- progress-file []
  (or (nonempty-env "JOLT_SIM_OUTBOX_DELIVERY_PROGRESS_FILE")
      (str (or (nonempty-env "TMPDIR") "/tmp")
           "/jolt-sim-outbox-delivery-"
           (java.util.UUID/randomUUID)
           ".edn")))

(defn- append-progress! [path record]
  ;; Best-effort append-only breadcrumbs for a single test process. This is not
  ;; the later crash-safe journal/WAL contract.
  (spit path (str (pr-str record) "\n") :append true))

(defn- reopen-config [case-dir]
  ;; A closed config map: the unique case directory, the bare selected
  ;; filename, the ordinary JDBC SQLite spec for each lane, and the canonical
  ;; command. Real and hermetic lanes carry different spec strings (a unique
  ;; path for the real file, the bare filename for the simulator's exact
  ;; match) but the application evidence is spec-agnostic, so real/hermetic
  ;; parity still compares equal.
  (let [db-path (str (fs/path case-dir reopen-bare-filename))]
    {:case-dir case-dir
     :bare-filename reopen-bare-filename
     :real-spec (str "sqlite:" db-path)
     :hermetic-spec (str "sqlite:" reopen-bare-filename)
     :command fixture/default-command}))

(defn -main [& args]
  (let [sim-only? (= ["--sim-only"] (vec args))
        mode (if sim-only? :hermetic-only :real-and-hermetic)
        progress (progress-file)
        case-dir (str (fs/create-temp-dir
                       {:prefix "jolt-sim-outbox-reopen-"}))
        config (reopen-config case-dir)]
    (append-progress! progress
                      {:phase :start
                       :mode mode
                       :status :running
                       :counts nil
                       :case-dir case-dir})
    (println (str "outbox-delivery progress: " progress))
    (println (str "outbox-delivery reopen case-dir: " case-dir))
    (flush)
    (binding [integration/*sim-only?* sim-only?
              integration/*reopen-config* config]
      (let [worker
            (future
             (test/run-tests
              'jolt.sim.outbox-delivery-integration-test))
            result (deref worker watchdog-timeout-ms ::timeout)]
        (cond
          (= ::timeout result)
          (do
            (append-progress! progress
                              {:phase :timeout
                               :mode mode
                               :status :timed-out
                               :counts nil
                               :watchdog-timeout-ms watchdog-timeout-ms
                               :case-dir case-dir})
            ;; Retain the case directory on timeout for diagnosis.
            (println
             (str "FAILURE: outbox-delivery test timed out after "
                  watchdog-timeout-ms "ms"))
            (println (str "retained case-dir: " case-dir))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          (pos? (+ (:fail result) (:error result)))
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish
                               :mode mode
                               :status :failed
                               :counts counts
                               :case-dir case-dir})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            ;; Retain the case directory on failure for diagnosis.
            (println (str "retained case-dir: " case-dir))
            (println (str "progress: " progress))
            (flush)
            (System/exit 1))

          :else
          (let [counts (select-keys result [:test :pass :fail :error])]
            (append-progress! progress
                              {:phase :finish
                               :mode mode
                               :status :passed
                               :counts counts
                               :case-dir case-dir})
            (println (str (:test result) " tests, "
                          (:pass result) " assertions passed"))
            (flush)
            ;; Complete pass: delete the unique case directory. A deletion
            ;; failure is reported but does not invert a passing run.
            (try
              (when (fs/exists? case-dir)
                (fs/delete-tree case-dir))
              (catch :default delete-error
                (append-progress! progress
                                  {:phase :case-dir-delete-failure
                                   :mode mode
                                   :status :passed
                                   :case-dir case-dir
                                   :error (str delete-error)})
                (println
                 (str "warning: case-dir delete failed: " case-dir))))
            ;; jolt-http loads core.async, whose non-daemon threads keep the
            ;; process alive after a successful test run.
            (System/exit 0)))))))
