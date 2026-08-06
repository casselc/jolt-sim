(ns jolt.sim.outbox-experiment-parity-test
  "Fresh-worker parity witness: the compiled cancel-before-ack experiment
   plan versus the existing direct simulated assembly.

   Two SEQUENTIAL fresh workers -- never concurrent, each fully supervised
   and reaped by jolt.sim.process-explorer/run-case before the next is
   spawned -- run the unchanged cancel-before-ack ordinary application under
   the same fixed first-case input:

     direct:    jolt.sim.fixtures.outbox-delivery-scenarios/
                exercise-cancel-before-ack-with-capacities
                (manual world/handler assembly under run-controlled)
     compiled:  jolt.sim.fixtures.outbox-experiment-scenarios/
                exercise-cancel-before-ack-compiled
                (experiment/compile-plan + experiment-executor/execute!)

   The parent compares only stable semantics: the exact
   :application/:http/:receiver outcome projections, the exact 18-statement
   SQLite terminal summary, the zero fault summary, complete world cleanup,
   the configured capacity bounds, handler-only routing, and the established
   required foreign-symbol subset. Incidental route order/counts and
   would-block counts are never compared. The compiled side additionally
   proves non-vacuity: every trusted pack compiler ran exactly once, every
   physical mechanism probe reports activity, the pack-owned history
   partition covers the whole controlled effect trace, and the compiled plan
   carries the fixed experiment identity.

   On any mismatch or incomplete worker, BOTH complete worker directories
   are retained (never only the failing side) and a sibling
   parity-comparison.edn artifact records the input, scenario symbols and
   statuses, the stable projections, the derived per-check observations, the
   serializable compiled plan-data projection, the mechanism reports, and
   the bounded route/history summaries. On a full pass the transient case
   tree is removed. This parent deliberately loads no application namespace:
   the fixed input, the scenario symbols, the expected plan identity, and
   the required foreign-symbol subset are mirrored literals. This is a
   hermetic parity witness over one fixed case; it claims no schedule
   exploration, probe/check execution, Hegel, PostgreSQL, or non-Linux
   evidence."
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is]]
            [jolt.fs :as fs]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)
(def ^:dynamic *case-dir* nil)

(def ^:private direct-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-cancel-before-ack-with-capacities)

(def ^:private compiled-scenario
  'jolt.sim.fixtures.outbox-experiment-scenarios/exercise-cancel-before-ack-compiled)

;; The one closed first-case input, mirrored from
;; jolt.sim.fixtures.outbox-experiment-scenarios/fixed-cancel-input: the
;; trusted pack bundle pins the compiled SQLite plan binds, modeled
;; capacities, and pack contract values to exactly this case, and the
;; compiled worker rejects any other input before a world exists. This
;; parent loads no scenario namespace, so the literal is restated here.
(def ^:private parity-input
  {:payload [0 127 128 255]
   :stream-capacity 8
   :pipe-capacity 1
   :poll-eintr-ordinal nil})

;; Mirrored from jolt.sim.outbox-delivery-hegel-test (which mirrors the
;; ordinary lane's established native-call surface). The parent cannot load
;; the scenario namespaces, so the literal set is restated here.
(def ^:private required-foreign-symbols
  #{"socket" "connect" "accept" "poll" "send" "recv" "close"
    "sqlite3_open" "sqlite3_close_v2"
    "sqlite3_bind_blob64" "sqlite3_column_blob"})

;; Mirrored from the fixed closed manifest and its trusted connection packs:
;; the compiled plan's serializable identity projection must match this
;; exactly. Restated as literals because this parent loads no pack
;; namespace.
(def ^:private expected-experiment-id :outbox.experiment/cancel-before-ack-v1)
(def ^:private expected-application-body-id
  :jolt.sim.fixtures.outbox-delivery/cancel-before-ack-v1)

(def ^:private expected-compiled-connections
  {:command {:from [:client :commands]
             :to [:app :commands]
             :pack-id :outbox.http/command-v1
             :capabilities {:from #{:outbox.http/client-v1}
                            :to #{:outbox.http/server-v1}}
             :mode :simulate}
   :delivery {:from [:app :deliveries]
              :to [:receiver :deliveries]
              :pack-id :outbox.tcp/delivery-v1
              :capabilities {:from #{:outbox.tcp/client-v1}
                             :to #{:outbox.tcp/server-v1}}
              :mode :simulate}
   :store {:from [:app :store]
           :to [:sqlite :database]
           :pack-id :outbox.sqlite/store-v1
           :capabilities {:from #{:outbox.sqlite/client-v1}
                          :to #{:outbox.sqlite/server-v1}}
           :mode :simulate}})

;; A 60000 ms worker deadline covers the cold child resolving this lane's
;; larger dependency graph (db source + HTTP + TCP + codec) without
;; weakening steady-case bounds, matching the established boundary budget.
;; Bootstrap is bounded separately by the parent's 120000 ms startup
;; handshake in -main.
(def ^:private worker-timeout-ms 60000)
(def ^:private kill-grace-ms 500)

(def ^:private outcome-keys [:application :http :receiver])

(def ^:private sqlite-terminal-summary
  {:plan-index 18 :plan-count 18 :open-dbs 0 :active-stmts 0})

(def ^:private zero-fault-summary
  {:attempts 0 :firings 0 :fired-attempts []})

(def ^:private clean-worlds {:memory true :sqlite true :posix true})

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.outbox-experiment-parity-test/missing-environment
         :name name})))
    value))

(defn- nonempty-environment [name]
  (let [value (System/getenv name)]
    (when (and (string? value) (seq value)) value)))

(defn- configured [value label]
  (or value
      (throw
       (ex-info
        (str "outbox experiment parity test must be run through -main: "
             label)
        {:type :jolt.sim.outbox-experiment-parity-test/not-configured
         :field label}))))

(defn- path-in [dir filename]
  (str (fs/path dir filename)))

(defn- write-edn! [path value]
  (spit path (str (pr-str value) "\n")))

(defn- run-worker!
  "Runs one case in one fresh worker and returns its outcome. Strictly
   sequential: process-explorer supervises, waits on, and reaps this child
   before the caller can spawn the next. :retain-completed-artifacts? true
   keeps BOTH complete request/result/log trees until the parent verdict
   below, so a mismatch or incomplete worker never leaves only the failing
   side's directory behind."
  [scenario input]
  (process-explorer/run-case
   (cond->
    (merge (configured *process-config* :process-config)
           {:scenario scenario
            :input input
            :timeout-ms worker-timeout-ms
            :kill-grace-ms kill-grace-ms
            :retain-completed-artifacts? true})
     (= compiled-scenario scenario)
     (assoc :activity-journal? true))))

(defn- report-non-completed! [side outcome]
  ;; Best-effort bounded projection before the assertion boundary so a gate
  ;; capturing stdout can diagnose ordinary timeouts and interruptions.
  (when-not (= :completed (:status outcome))
    (println
     (pr-str {:event :outbox-experiment-parity/non-completed-worker
              :side side
              :outcome (select-keys outcome
                                    [:status :reason :exit :error
                                     :activity :diagnostics :artifact-dir])}))
    (flush)))

(defn- stable-projection
  "The stable semantic surface of one worker's evidence: everything the
   parity verdict may inspect. Incidental route counts and would-block
   counts live inside :routes/:capacity but are never equality-compared."
  [evidence]
  (when (map? evidence)
    (select-keys evidence
                 [:application-body-id :application :http :receiver
                  :sqlite :fault :fault-policy :clean?
                  :capacity :routes])))

(defn- check [id match? details]
  {:id id :match? (boolean match?) :details details})

(defn- capacity-within-bounds?
  "The configured capacity bounds predicate for one side: exact configured
   capacity echoes, occupancy within the configured bounds, nonnegative
   counters, and the smoke stream capacity's provably nonzero partial-write
   count. Would-block counts themselves are never compared."
  [capacity]
  (and (map? capacity)
       (= (:stream-capacity parity-input)
          (get-in capacity [:stream :stream-capacity]))
       (= (:pipe-capacity parity-input)
          (get-in capacity [:pipe :pipe-capacity]))
       (<= 1
           (or (get-in capacity [:stream :max-stream-recv-bytes]) 0)
           (:stream-capacity parity-input))
       (<= 1
           (or (get-in capacity [:pipe :max-pipe-fifo-bytes]) 0)
           (:pipe-capacity parity-input))
       (every? #(and (integer? %) (not (neg? %)))
               [(get-in capacity [:stream :stream-would-blocks])
                (get-in capacity [:stream :stream-capacity-limited-writes])
                (get-in capacity [:pipe :pipe-would-blocks])])
       ;; The fixed smoke stream capacity provably forces partial writes in
       ;; the ordinary application; a zero here would mean the capacity
       ;; model was bypassed.
       (pos? (or (get-in capacity [:stream :stream-capacity-limited-writes])
                 0))))

(defn- handled-routes-with-required-symbols? [evidence]
  (let [routes (:routes evidence)
        symbols (:foreign-symbols routes)]
    (and (true? (:all-handled? routes))
         (coll? symbols)
         (every? (set symbols) required-foreign-symbols))))

(defn- mechanism-active? [evidence]
  (let [reports (:mechanism evidence)]
    (and (map? reports)
         (= #{:command :delivery :store} (set (keys reports)))
         (every? #(true? (:activity? %)) (vals reports)))))

(defn- history-covers-routes? [evidence]
  (let [history (:history evidence)
        values (vals history)
        indices (vec (mapcat :route-indices values))
        total (get-in evidence [:routes :count])]
    (and (map? history)
         (= #{:command :delivery :store} (set (keys history)))
         (every? #(and (integer? (:event-count %))
                       (pos? (:event-count %))
                       (= (:event-count %) (count (:route-indices %)))
                       (every? (fn [index]
                                 (and (integer? index) (not (neg? index))))
                               (:route-indices %)))
                 values)
         (integer? total)
         (= (vec (range total)) (vec (sort indices)))
         (= total (count (set indices))))))

(defn- expected-plan-identity? [plan]
  (and (map? plan)
       (= expected-experiment-id (:experiment-id plan))
       (= :hermetic (:profile-id plan))
       (= 1 (:plan-version plan))
       (= expected-compiled-connections (:connections plan))
       (= 0 (:checks-count plan))
       (= :hermetic (get-in plan [:runtime-config :ffi-mode]))
       (= [:ffi-handlers :ffi-mode]
          (get-in plan [:runtime-config :keys]))
       (false? (get-in plan [:runtime-config :clock?]))
       (pos? (or (get-in plan [:runtime-config :ffi-handler-count]) 0))
       (zero? (:native-fallback-count plan))
       (= [:command :delivery :store] (:mechanism-probes plan))
       (= [:command :delivery :store] (:history-projectors plan))
       (= 0 (:monitor-spec-count plan))
       (= [] (:presentation-keys plan))))

(def ^:private expected-compiled-activity-tags
  [:jolt.sim.explore/scenario-started
   :jolt.example.outbox/experiment-compiled
   :jolt.example.outbox/application-started
   :jolt.example.outbox/cancel-before-ack-observed
   :jolt.example.outbox/application-completed
   :jolt.sim.explore/scenario-completed])

(defn- compiled-activity-complete?
  "The compiled lane must expose one complete, ordered semantic journal while
  retaining the exact ordinary application result used by every other parity
  check. The direct lane stays uninstrumented in this first witness."
  [activity-envelope compiled-evidence]
  (and (map? activity-envelope)
       (= 1 (:version activity-envelope))
       (nil? (:observer-status activity-envelope))
       (false? (:remaining? activity-envelope))
       (= :complete (get-in activity-envelope [:recovery :status]))
       (nil? (get-in activity-envelope [:recovery :reason]))
       (false? (get-in activity-envelope
                       [:recovery :image-truncated?]))
       (= (:accepted-count activity-envelope)
          (:next-cursor activity-envelope)
          (count (:events activity-envelope)))
       (= (vec (range (:accepted-count activity-envelope)))
          (mapv :sequence (:events activity-envelope)))
       (= expected-compiled-activity-tags
          (mapv (comp first :event) (:events activity-envelope)))
       (= expected-application-body-id
          (get-in activity-envelope [:events 2 :event 3
                                     :application-body-id]))
       (= {:application-body-id (:application-body-id compiled-evidence)
           :cancel (get-in compiled-evidence [:application :cancel])
           :http-status (get-in compiled-evidence [:http :status])
           :request-count
           (count (get-in compiled-evidence [:receiver :requests]))
           :store-state (get-in compiled-evidence [:application :store-state])}
          (get-in activity-envelope [:events 3 :event 3]))
       (= {:application-body-id (:application-body-id compiled-evidence)}
          (get-in activity-envelope [:events 4 :event 3]))))

(defn- build-comparison
  "Derives the complete parity comparison as plain data: scenario symbols
   and statuses, both stable projections, the compiled side's plan/
   mechanism/callback/history/route observations, and the per-check derived
   observations. Total over nil or non-map outcomes so the failure artifact
   can always be written."
  [input direct compiled]
  (let [direct-evidence (let [evidence (:result direct)]
                          (when (map? evidence) evidence))
        compiled-evidence (let [evidence (:result compiled)]
                            (when (map? evidence) evidence))
        direct-outcome (select-keys direct-evidence outcome-keys)
        compiled-outcome (select-keys compiled-evidence outcome-keys)
        checks
        [(check :workers-completed
                (and (= :completed (:status direct))
                     (= :completed (:status compiled))
                     (= 0 (:exit direct))
                     (= 0 (:exit compiled)))
                {:direct-status (:status direct)
                 :compiled-status (:status compiled)
                 :direct-exit (:exit direct)
                 :compiled-exit (:exit compiled)})
         (check :ordinary-application-body-identity
                (= expected-application-body-id
                   (:application-body-id direct-evidence)
                   (:application-body-id compiled-evidence))
                {:expected expected-application-body-id
                 :direct (:application-body-id direct-evidence)
                 :compiled (:application-body-id compiled-evidence)})
         (check :outcome-projections-equal
                (= direct-outcome compiled-outcome)
                {:application-equal?
                 (= (:application direct-outcome)
                    (:application compiled-outcome))
                 :http-equal?
                 (= (:http direct-outcome) (:http compiled-outcome))
                 :receiver-equal?
                 (= (:receiver direct-outcome)
                    (:receiver compiled-outcome))})
         (check :sqlite-terminal-summary
                (= sqlite-terminal-summary
                   (:sqlite direct-evidence)
                   (:sqlite compiled-evidence))
                {:direct (:sqlite direct-evidence)
                 :compiled (:sqlite compiled-evidence)})
         (check :direct-zero-fault-and-compiled-no-fault-policy
                (and (= zero-fault-summary (:fault direct-evidence))
                     (= {:profile-id :hermetic
                         :connection-params
                         {:command {} :delivery {} :store {}}
                         :pack-fault-catalogs
                         {:command {} :delivery {} :store {}}}
                        (:fault-policy compiled-evidence))
                     (= [:ffi-handlers :ffi-mode]
                        (get-in compiled-evidence
                                [:plan :runtime-config :keys]))
                     (zero? (get-in compiled-evidence
                                    [:plan :native-fallback-count])))
                {:direct (:fault direct-evidence)
                 :compiled-policy (:fault-policy compiled-evidence)
                 :runtime-config-keys
                 (get-in compiled-evidence [:plan :runtime-config :keys])
                 :native-fallback-count
                 (get-in compiled-evidence [:plan :native-fallback-count])})
         (check :worlds-clean
                (= clean-worlds
                   (:clean? direct-evidence)
                   (:clean? compiled-evidence))
                {:direct (:clean? direct-evidence)
                 :compiled (:clean? compiled-evidence)})
         (check :capacity-within-bounds
                (and (capacity-within-bounds? (:capacity direct-evidence))
                     (capacity-within-bounds? (:capacity compiled-evidence)))
                {:direct (:capacity direct-evidence)
                 :compiled (:capacity compiled-evidence)})
         (check :routes-handled-with-required-symbols
                (and (handled-routes-with-required-symbols?
                      direct-evidence)
                     (handled-routes-with-required-symbols?
                      compiled-evidence))
                {:direct-all-handled?
                 (get-in direct-evidence [:routes :all-handled?])
                 :compiled-all-handled?
                 (get-in compiled-evidence [:routes :all-handled?])
                 :direct-missing
                 (vec (sort (remove
                             (set (get-in direct-evidence
                                          [:routes :foreign-symbols]))
                             required-foreign-symbols)))
                 :compiled-missing
                 (vec (sort (remove
                             (set (get-in compiled-evidence
                                          [:routes :foreign-symbols]))
                             required-foreign-symbols)))})
         (check :compiled-callbacks-exactly-once
                (= {:command 1 :delivery 1 :store 1}
                   (get-in compiled-evidence [:callbacks :compile]))
                {:callbacks (:callbacks compiled-evidence)})
         (check :compiled-mechanism-active
                (mechanism-active? compiled-evidence)
                {:mechanism (:mechanism compiled-evidence)})
         (check :compiled-history-covers-routes
                (history-covers-routes? compiled-evidence)
                {:history (:history compiled-evidence)
                 :route-count (get-in compiled-evidence [:routes :count])})
         (check :compiled-semantic-activity-complete
                (compiled-activity-complete? (:activity compiled)
                                             compiled-evidence)
                {:activity (:activity compiled)})
         (check :compiled-plan-identity
                (expected-plan-identity? (:plan compiled-evidence))
                {:plan (:plan compiled-evidence)})]]
    {:parity/version 1
     :input input
     :scenarios {:direct {:symbol direct-scenario
                          :status (:status direct)
                          :exit (:exit direct)
                          :reason (:reason direct)
                          :error (:error direct)
                          :diagnostics (:diagnostics direct)
                          :activity (:activity direct)
                          :artifact-dir (:artifact-dir direct)}
                 :compiled {:symbol compiled-scenario
                            :status (:status compiled)
                            :exit (:exit compiled)
                            :reason (:reason compiled)
                            :error (:error compiled)
                            :diagnostics (:diagnostics compiled)
                            :activity (:activity compiled)
                            :artifact-dir (:artifact-dir compiled)}}
     :stable {:direct (stable-projection direct-evidence)
              :compiled (stable-projection compiled-evidence)}
     :compiled-observations {:plan (:plan compiled-evidence)
                             :mechanism (:mechanism compiled-evidence)
                             :callbacks (:callbacks compiled-evidence)
                             :history (:history compiled-evidence)
                             :routes (:routes compiled-evidence)}
     :checks (vec checks)
     :parity? (every? :match? checks)}))

(defn- assert-parity! [comparison]
  (doseq [{:keys [id match? details]} (:checks comparison)]
    (is match?
        (str "outbox experiment parity check " id " failed: "
             (pr-str details)))))

(defn- comparison-artifact [input direct compiled comparison]
  (or comparison
      (try
        (build-comparison input direct compiled)
        (catch :default error
          {:parity/version 1
           :input input
           :artifact-error {:class (str (class error))
                            :message (or (ex-message error) (str error))}}))))

(defn- run-parity-case!
  "Runs the two sequential fresh workers, derives the parity comparison, and
   applies the retention contract: on parity, removes the transient case
   tree; on any mismatch or incomplete worker, retains BOTH complete worker
   directories (never only the failing side) and writes the sibling
   parity-comparison.edn artifact. Returns the comparison. Assertion-free so
   synthetic controls can exercise the same machinery without leaking inner
   assertion counts."
  [case-dir input]
  (let [direct* (volatile! nil)
        compiled* (volatile! nil)
        comparison* (volatile! nil)
        passed?* (volatile! false)]
    (try
      ;; Strictly sequential: each fresh worker is fully supervised and
      ;; reaped by run-case before the next is spawned.
      (let [direct (run-worker! direct-scenario input)]
        (vreset! direct* direct)
        (report-non-completed! :direct direct)
        (let [compiled (run-worker! compiled-scenario input)]
          (vreset! compiled* compiled)
          (report-non-completed! :compiled compiled)
          (let [comparison (build-comparison input direct compiled)]
            (vreset! comparison* comparison)
            (vreset! passed?* (:parity? comparison))
            comparison)))
      (finally
        (if @passed?*
          ;; Only a passing parent verdict authorizes deletion of the
          ;; transient case tree; the comparison artifact is written only on
          ;; failure, so a green run leaves no stale evidence behind.
          (fs/delete-tree case-dir)
          (let [artifact-path (path-in case-dir "parity-comparison.edn")]
            (try
              (write-edn! artifact-path
                          (comparison-artifact input @direct* @compiled*
                                               @comparison*))
              (println (str "outbox experiment parity comparison artifact: "
                            artifact-path))
              (catch :default error
                (println
                 (str "warning: failed to write parity comparison artifact: "
                      (or (ex-message error) (str error))))))
            (println (str "outbox experiment parity retained case-dir: "
                          case-dir))
            (flush)))))))

(deftest compiled-plan-matches-direct-simulated-assembly
  (assert-parity!
   (run-parity-case! (configured *case-dir* :case-dir) parity-input)))

(deftest mismatch-retains-both-worker-trees-and-writes-comparison-artifact
  ;; Parent-only control for the failure contract, with process-explorer
  ;; stubbed so no workers are spawned: a completed direct run beside a
  ;; failed compiled run must retain BOTH complete worker directories (never
  ;; only the failing side), run strictly sequentially in direct-then-
  ;; compiled order, and write the sibling parity-comparison.edn carrying
  ;; the input, scenario symbols/statuses, stable projections, and derived
  ;; observations. The synthetic tree is retained and printed even on an
  ;; assertion failure because this test is itself forensic evidence about
  ;; the retention machinery; it is not application evidence.
  (let [case-dir
        (str (fs/create-temp-dir {:prefix "jolt-sim-parity-retention-"}))
        worker-root (path-in case-dir "workers")
        direct-dir (path-in worker-root "direct")
        compiled-dir (path-in worker-root "compiled")
        _ (doseq [dir [direct-dir compiled-dir]]
            (fs/create-dirs dir)
            (spit (path-in dir "request.edn") "request"))
        direct {:status :completed
                :exit 0
                :artifact-dir direct-dir
                :result {:application {:command {:value :kept}}
                         :http {:status 200}
                         :receiver {:requests []}
                         :sqlite sqlite-terminal-summary
                         :fault zero-fault-summary
                         :clean? clean-worlds
                         :capacity {:stream {:stream-capacity 8
                                             :stream-would-blocks 0
                                             :stream-capacity-limited-writes 1
                                             :max-stream-recv-bytes 1}
                                    :pipe {:pipe-capacity 1
                                           :pipe-would-blocks 0
                                           :max-pipe-fifo-bytes 1}}
                         :routes {:count 11
                                  :all-handled? true
                                  :foreign-symbols
                                  (vec (sort required-foreign-symbols))}}}
        compiled {:status :failed
                  :exit 0
                  :artifact-dir compiled-dir
                  :error {:kind :jolt.sim/exception
                          :class "clojure.lang.ExceptionInfo"
                          :message "synthetic compiled failure"}}
        calls (atom [])]
    (try
      (binding [*process-config* {}]
        (with-redefs [process-explorer/run-case
                      (fn [config]
                        (swap! calls conj (:scenario config))
                        (if (= direct-scenario (:scenario config))
                          direct
                          compiled))]
          (is (false? (:parity? (run-parity-case! case-dir parity-input)))
              "a failed compiled worker must fail the derived parity verdict")))
      (is (= [direct-scenario compiled-scenario] @calls)
          "workers must run sequentially in direct-then-compiled order")
      (is (fs/exists? direct-dir)
          "the passing side's complete worker tree is also retained")
      (is (fs/exists? compiled-dir))
      (is (fs/exists? (path-in direct-dir "request.edn")))
      (is (fs/exists? (path-in compiled-dir "request.edn")))
      (let [artifact-path (path-in case-dir "parity-comparison.edn")]
        (is (fs/exists? artifact-path))
        (when (fs/exists? artifact-path)
          (let [artifact (edn/read-string (slurp artifact-path))]
            (is (= 1 (:parity/version artifact)))
            (is (= parity-input (:input artifact)))
            (is (= direct-scenario
                   (get-in artifact [:scenarios :direct :symbol])))
            (is (= compiled-scenario
                   (get-in artifact [:scenarios :compiled :symbol])))
            (is (= :completed (get-in artifact [:scenarios :direct :status])))
            (is (= :failed (get-in artifact [:scenarios :compiled :status])))
            (is (= direct-dir
                   (get-in artifact [:scenarios :direct :artifact-dir])))
            (is (= compiled-dir
                   (get-in artifact [:scenarios :compiled :artifact-dir])))
            (is (map? (get-in artifact [:stable :direct])))
            (is (contains? artifact :compiled-observations))
            (is (vector? (:checks artifact)))
            (is (false? (:parity? artifact)))
            (is (contains? (set (map :id (remove :match? (:checks artifact))))
                           :workers-completed)))))
      (finally
        ;; This is itself a failure-contract control. Preserve its complete
        ;; synthetic tree even when an `is` assertion fails; deleting in
        ;; finally would erase the only evidence of a harness regression.
        (when (fs/exists? case-dir)
          (println (str "outbox parity retention-control case-dir: " case-dir))
          (flush))))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        artifact-root
        (or (nonempty-environment "JOLT_SIM_EXPERIMENT_PARITY_ARTIFACT_DIR")
            (path-in project-dir "target/outbox-experiment-parity"))
        _ (fs/create-dirs artifact-root)
        case-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-outbox-experiment-parity-"
               :dir artifact-root}))
        worker-root (path-in case-dir "workers")
        _ (fs/create-dirs worker-root)
        _ (println (str "outbox experiment parity case-dir: " case-dir))
        _ (flush)
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:outbox-delivery-explore-worker"]
                   :dir project-dir
                   :temp-dir worker-root
                   ;; The first cold child resolves this lane's full
                   ;; db/HTTP/TCP/codec graph; match the established startup
                   ;; bound so bootstrap never eats the execution deadline.
                   :startup-timeout-ms 120000}
                  *case-dir* case-dir]
          (test/run-tests 'jolt.sim.outbox-experiment-parity-test))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (println (if (zero? failures)
               (str "case-dir removed after pass: " case-dir)
               (str "retained case-dir: " case-dir)))
    (flush)
    ;; process-explorer and the worker launcher may leave non-daemon threads
    ;; alive; every worker is reaped before this explicit exit.
    (System/exit (if (zero? failures) 0 1))))
