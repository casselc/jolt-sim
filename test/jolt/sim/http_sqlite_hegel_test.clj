(ns jolt.sim.http-sqlite-hegel-test
  "Fresh-process Hegel property lane over the UNCHANGED
  jolt.sim.fixtures.http-sqlite application under the shared hermetic POSIX
  loopback plus SQLite handler packs.

  Each generated case draws a Hegel-owned, shrinkable scenario input -- stream
  capacity, pipe capacity, an optional captured poll EINTR activation ordinal,
  and one closed FFI admission plan -- and runs the unchanged fixture once in a
  fresh sim-enabled Jolt worker through jolt.sim.process-explorer/run-case
  (protocol-v2 :input). The worker loads jolt.sim.fixtures.http-sqlite-scenarios,
  builds the shared hermetic worlds from the drawn input, wraps the composed
  canonical handlers with jolt.sim.ffi-schedule over poll occurrence 1 and the
  captured nonblocking connect occurrence 1, and returns one canonical evidence
  map.

  Each completed case checks the ordinary app response status and exact BLOB
  octets (spanning the signed/unsigned byte boundary) plus route, SQLite,
  cleanup, capacity, fault, and admission-plan evidence sufficient to catch a
  model bypass. The admission slice requires exact plan-derived release
  evidence, zero in-flight, non-aborted state, both steps completed, and that
  the selected poll/connect effects were served by the existing modeled
  handlers. A regression failure carries the bounded drawn input so Hegel can
  replay and shrink it: a non-:completed outcome is enriched with :input around
  jolt.sim.hegel/require-completed!, and every assertion site throws a typed
  ex-info carrying :hegel/origin and the same input.

  Process-isolated like :hegel-explore-test: each case spawns a fresh worker,
  and the parent supplies the worker command through JOLT_SIM_BIN and
  JOLT_SIM_PROJECT_DIR."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

(def ^:private expected-blob-values [0 65 127 -128 -1])

(def ^:private required-foreign-symbols
  #{"socket" "poll" "sqlite3_open" "sqlite3_bind_blob64"
    "sqlite3_column_blob" "sqlite3_close_v2"})

(def ^:private scenario-sym
  'jolt.sim.fixtures.http-sqlite-scenarios/exercise-with-capacities)

(def ^:private scenario-ns "jolt.sim.fixtures.http-sqlite-scenarios")

;; Reconstructed step ids matching ::poll / ::connect in the fixture namespace.
;; This parent does not load that namespace (jolt.net/db/jolt-http are absent
;; here); the worker child resolves it under :http-sqlite-explore-worker.
(def ^:private poll-id (keyword scenario-ns "poll"))
(def ^:private connect-id (keyword scenario-ns "connect"))

;; Ordered, discriminating boundaries rather than every integer in a range.
;; Hegel owns selection and shrinks sampled indexes toward the one-byte/no-fault
;; case while still exercising larger powers of two and later poll attempts.
(def ^:private capacity-domain [1 2 4 8])
(def ^:private poll-eintr-domain [nil 1 2 4 8])

;; Exactly the two valid admission plans over poll occurrence 1 and the captured
;; nonblocking connect occurrence 1. Generation is engine-owned via
;; g/sampled-from, which shrinks toward index 0 (:poll-then-connect).
(def ^:private admission-plan-domain [:poll-then-connect :connect-then-poll])

;; ---- Historical BEGIN fail-open control ------------------------------------
;; A permanent, bounded Hegel witness over the test-only historical BEGIN
;; fail-open control (jolt.sim.fixtures.http-sqlite/exercise-http-sqlite-
;; begin-fail-open-control via jolt.sim.fixtures.http-sqlite-scenarios/
;; run-begin-fail-open-control). Every generated case spawns a fresh worker,
;; exactly like the property above. The two input fields are drawn
;; independently, each from a shrinkable two-element domain ordered with its
;; coherent/safe value first: :begin-when shrinks toward :on-success and
;; :report-error? shrinks toward false. Of the four possible inputs, exactly
;; one -- {:begin-when :always :report-error? true} -- makes the historical
;; evaluator's logical readiness (logical depth 0 means "ready/reusable")
;; disagree with the closed connection's physical sqlite3_get_autocommit
;; evidence (still inside a transaction): the historical fail-open bug this
;; control exists to witness.

(def ^:private begin-fail-open-scenario-sym
  'jolt.sim.fixtures.http-sqlite-scenarios/run-begin-fail-open-control)

(def ^:private begin-fail-open-begin-when-domain [:on-success :always])
(def ^:private begin-fail-open-report-error-domain [false true])

(def ^:private expected-begin-fail-open-error-foreign-symbols
  #{"sqlite3_open"
    "sqlite3_close_v2"
    "sqlite3_prepare_v2"
    "sqlite3_step"
    "sqlite3_finalize"
    "sqlite3_column_count"
    "sqlite3_errmsg"})

;; Release evidence is plan-driven (arrival gate release order), so it is an
;; exact replay witness for each plan; arrival and completion order remain
;; diagnostic and are deliberately not asserted as deterministic.
(def ^:private expected-release-evidence
  {:poll-then-connect [[:release poll-id] [:release connect-id]]
   :connect-then-poll [[:release connect-id] [:release poll-id]]})

(def ^:private expected-release-order
  {:poll-then-connect [poll-id connect-id]
   :connect-then-poll [connect-id poll-id]})

(defn- input-generator
  "Returns a Hegel generator over the scenario input domain. The four draws are
  composed with g/tuple and g/fmap so the whole input shrinks as one unit and
  threads through run-case's :input unchanged. Selection and shrinking stay
  engine-owned: this wrapper performs no selection of its own."
  []
  (g/fmap
   (fn [[stream-capacity pipe-capacity ordinal plan]]
     {:stream-capacity stream-capacity
      :pipe-capacity pipe-capacity
      :poll-eintr-ordinal ordinal
      :admission-plan plan})
   (g/tuple (g/sampled-from capacity-domain)
            (g/sampled-from capacity-domain)
            (g/sampled-from poll-eintr-domain)
            (g/sampled-from admission-plan-domain))))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.http-sqlite-hegel-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Hegel http-sqlite tests must be run through -main"
        {:type :jolt.sim.http-sqlite-hegel-test/not-configured}))))

(defn- require-completed-carrying-input!
  "Wraps jolt.sim.hegel/require-completed! so a non-:completed outcome fails
  fast with the adapter's stable bounded supervisor fields AND the drawn
  scenario input, giving Hegel the exact case data to replay and shrink. The
  underlying :hegel/origin is preserved."
  [outcome input]
  (try
    (sim-hegel/require-completed! outcome)
    (catch :default error
      (let [data (ex-data error)]
        (if (and (map? data) (not (contains? data :input)))
          (throw (ex-info (ex-message error) (assoc data :input input)))
          (throw error))))))

(defn- violation
  "Throws the stable typed property failure for one assertion site, carrying
  :hegel/origin, the drawn input, and a small bounded :actual projection of the
  evidence that failed. Hegel replays and shrinks against this ex-data."
  [origin input actual]
  (throw
   (ex-info
    "jolt.sim.http-sqlite-hegel-test property invariant violated"
    {:hegel/origin origin
     :input input
     :actual actual})))

(defn- check-case!
  "Runs one case for the drawn `input` and asserts the full invariant set.
  Throws on the first violation; returns nil on success. Throwing (rather than
  returning false) is required inside a property passed to a custom Hegel
  runner."
  [input]
  (let [base-config
        (merge (process-config)
               {:scenario scenario-sym
                :timeout-ms 20000
                :kill-grace-ms 500})
        outcome (process-explorer/run-case
                 (assoc base-config :input input))
        completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)]
    (when-not (map? evidence)
      (violation "jolt.sim.http-sqlite-hegel-test/evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [{:keys [http routes sqlite capacity fault clean? admission]} evidence]
      ;; Ordinary app response: status 200 and the exact BLOB octets spanning
      ;; the signed/unsigned byte boundary, served as application/octet-stream.
      (when-not (= 200 (:status http))
        (violation "jolt.sim.http-sqlite-hegel-test/status" input {:http http}))
      (when-not (= "application/octet-stream" (:content-type http))
        (violation "jolt.sim.http-sqlite-hegel-test/content-type"
                   input
                   {:http http}))
      (when-not (= (str (count expected-blob-values)) (:content-length http))
        (violation "jolt.sim.http-sqlite-hegel-test/content-length"
                   input
                   {:http http}))
      (when-not (= expected-blob-values (:body-octets http))
        (violation "jolt.sim.http-sqlite-hegel-test/blob-octets"
                   input
                   {:http http}))
      (when-not (empty? (:server-errors http))
        (violation "jolt.sim.http-sqlite-hegel-test/server-errors"
                   input
                   {:http http}))
      ;; Effect/route evidence: every intercepted POSIX and SQLite call was
      ;; served by a registered handler; nothing routed to a real socket or the
      ;; real SQLite library, and the fixture actually made native calls.
      (when-not (and (integer? (:count routes))
                     (pos? (:count routes)))
        (violation "jolt.sim.http-sqlite-hegel-test/route-count"
                   input
                   {:routes routes}))
      (when-not (:all-handled? routes)
        (violation "jolt.sim.http-sqlite-hegel-test/all-handled"
                   input
                   {:routes routes}))
      (when-not (every? (set (:foreign-symbols routes))
                        required-foreign-symbols)
        (violation "jolt.sim.http-sqlite-hegel-test/required-routes"
                   input
                   {:missing (vec (sort (remove (set (:foreign-symbols routes))
                                               required-foreign-symbols)))}))
      ;; SQLite cleanup: all four plans consumed; no live connections or
      ;; statements leaked past the request.
      (when-not (= {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
                   sqlite)
        (violation "jolt.sim.http-sqlite-hegel-test/sqlite-cleanup"
                   input
                   {:sqlite sqlite}))
      ;; Capacity model was honored end to end under the drawn bounds.
      (when-not (= (:stream-capacity input)
                   (:stream-capacity (:stream capacity)))
        (violation "jolt.sim.http-sqlite-hegel-test/stream-capacity"
                   input
                   {:capacity capacity}))
      (when-not (= (:pipe-capacity input)
                   (:pipe-capacity (:pipe capacity)))
        (violation "jolt.sim.http-sqlite-hegel-test/pipe-capacity"
                   input
                   {:capacity capacity}))
      (when-not (<= 1
                    (:max-stream-recv-bytes (:stream capacity))
                    (:stream-capacity input))
        (violation "jolt.sim.http-sqlite-hegel-test/stream-occupancy"
                   input
                   {:capacity capacity}))
      (when-not (<= 1
                    (:max-pipe-fifo-bytes (:pipe capacity))
                    (:pipe-capacity input))
        (violation "jolt.sim.http-sqlite-hegel-test/pipe-occupancy"
                   input
                   {:capacity capacity}))
      ;; Capacity one is the deliberately saturated stream case. Larger
      ;; capacities remain subject to the universal occupancy bounds above,
      ;; but host-thread draining can legitimately avoid these stress events.
      (when (= 1 (:stream-capacity input))
        (when-not (pos? (:stream-capacity-limited-writes (:stream capacity)))
          (violation "jolt.sim.http-sqlite-hegel-test/stream-partial-write"
                     input
                     {:capacity capacity}))
        (when-not (pos? (:stream-would-blocks (:stream capacity)))
          (violation "jolt.sim.http-sqlite-hegel-test/stream-would-block"
                     input
                     {:capacity capacity})))
      ;; Fault evidence: the director fires at most once (:times 1), and when
      ;; an ordinal was drawn the frontend observed at least one poll attempt.
      ;; A nil ordinal installs no frontend, so both counts are zero.
      (when-not (#{0 1} (:firings fault))
        (violation "jolt.sim.http-sqlite-hegel-test/fault-firings"
                   input
                   {:fault fault}))
      (when (and (some? (:poll-eintr-ordinal input))
                 (not (pos? (:attempts fault))))
        (violation "jolt.sim.http-sqlite-hegel-test/fault-attempts"
                   input
                   {:fault fault}))
      (let [requested-ordinal (:poll-eintr-ordinal input)
            expected-firings
            (if (and (some? requested-ordinal)
                     (<= requested-ordinal (:attempts fault)))
              1
              0)
            expected-fired-attempts
            (if (= 1 expected-firings)
              [{:attempt-id
                [:jolt.sim.net.posix-fault/poll requested-ordinal]
                :firing
                {:rule-id :http-sqlite/interrupt-poll
                 :firing-ordinal 1
                 :rule-firing-ordinal 1
                 :match-ordinal requested-ordinal
                 :outcome {:kind :captured-error :errno :eintr}}}]
              [])]
        (when-not (= expected-firings (:firings fault))
          (violation "jolt.sim.http-sqlite-hegel-test/fault-activation"
                     input
                     {:fault fault :expected-firings expected-firings}))
        (when-not (= expected-fired-attempts (:fired-attempts fault))
          (violation "jolt.sim.http-sqlite-hegel-test/fault-attempt-identity"
                     input
                     {:fault fault
                      :expected-fired-attempts expected-fired-attempts})))
      ;; Cleanup: every shared world retired its resources; a leaked socket,
      ;; pipe, addrinfo, SQLite handle, or native allocation is a bypass.
      (when-not (and (:memory clean?) (:sqlite clean?) (:posix clean?))
        (violation "jolt.sim.http-sqlite-hegel-test/world-cleanup"
                   input
                   {:clean? clean?}))
      ;; Admission evidence: the generator always draws a plan, so the worker
      ;; evidence must carry one. Release evidence is plan-driven and therefore
      ;; an exact replay witness; the coordinator must finish quiesced (zero
      ;; in-flight, not aborted, both steps completed) and its release order
      ;; must match the drawn plan. The selected poll/connect effects must be
      ;; served by the existing modeled handlers (:route :handler), not
      ;; blocked or routed native, with both symbols present.
      (when-not (map? admission)
        (violation "jolt.sim.http-sqlite-hegel-test/admission-shape"
                   input
                   {:admission admission}))
      (when-not (= (:admission-plan input) (:plan admission))
        (violation "jolt.sim.http-sqlite-hegel-test/admission-plan"
                   input
                   {:admission admission}))
      (when-not (= (get expected-release-evidence (:admission-plan input))
                   (:release-evidence admission))
        (violation "jolt.sim.http-sqlite-hegel-test/release-evidence"
                   input
                   {:release (:release-evidence admission)
                    :expected (get expected-release-evidence
                                   (:admission-plan input))}))
      (let [diag (:coordinator-diagnostics admission)]
        (when-not (zero? (:in-flight diag))
          (violation "jolt.sim.http-sqlite-hegel-test/admission-in-flight"
                     input
                     {:in-flight (:in-flight diag)}))
        (when-not (false? (:aborted? diag))
          (violation "jolt.sim.http-sqlite-hegel-test/admission-aborted"
                     input
                     {:aborted? (:aborted? diag)}))
        (when-not (= #{poll-id connect-id} (:completed diag))
          (violation "jolt.sim.http-sqlite-hegel-test/admission-completed"
                     input
                     {:completed (:completed diag)}))
        (when-not (= (get expected-release-order (:admission-plan input))
                     (:release-order diag))
          (violation "jolt.sim.http-sqlite-hegel-test/admission-release-order"
                     input
                     {:release-order (:release-order diag)
                      :expected (get expected-release-order
                                      (:admission-plan input))})))
      (let [admission-routes (:routes admission)
            route-symbols (set (map :symbol admission-routes))]
        (when-not (and (seq admission-routes)
                       (every? #(= :handler (:route %)) admission-routes))
          (violation "jolt.sim.http-sqlite-hegel-test/admission-routes"
                     input
                     {:routes admission-routes}))
        (when-not (contains? route-symbols "poll")
          (violation "jolt.sim.http-sqlite-hegel-test/admission-poll"
                     input
                     {:routes admission-routes}))
        (when-not (contains? route-symbols "connect")
          (violation "jolt.sim.http-sqlite-hegel-test/admission-connect"
                     input
                     {:routes admission-routes}))))))

(defn- check-begin-fail-open-case!
  "Runs one fresh-worker case for the drawn begin-fail-open `input` and throws
  only when the historical evaluator's logical readiness (logical depth 0
  means \"ready/reusable\") disagrees with the closed connection's physical
  sqlite3_get_autocommit evidence (autocommit? true means \"not inside a
  transaction\"). Throws on the first violation with the exact drawn input and
  a bounded :actual evidence projection so Hegel can replay and shrink it."
  [input]
  (let [base-config
        (merge (process-config)
               {:scenario begin-fail-open-scenario-sym
                :timeout-ms 20000
                :kill-grace-ms 500})
        outcome (process-explorer/run-case
                 (assoc base-config :input input))
        completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)]
    (when-not (map? evidence)
      (violation "jolt.sim.http-sqlite-hegel-test/begin-fail-open-evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [closed-db-evidence (get-in evidence [:sqlite :closed-db-evidence])
          db-evidence (first closed-db-evidence)
          logical-depth (:logical-depth evidence)
          autocommit? (:autocommit? db-evidence)
          logical-ready? (zero? logical-depth)
          physical-ready? (true? autocommit?)]
      (when-not (= logical-ready? physical-ready?)
        (violation "jolt.sim.http-sqlite-hegel-test/begin-fail-open-coherence"
                   input
                   {:logical-depth logical-depth
                    :autocommit? autocommit?
                    :routes (:routes evidence)
                    :closed-db-count (count closed-db-evidence)
                    :autocommit-evidence (:autocommit-evidence db-evidence)
                    :tx-evidence (:tx-evidence db-evidence)
                    :discarded-transaction (:discarded-transaction db-evidence)
                    :discarded-staging (:discarded-staging db-evidence)
                    :sqlite-summary (:summary (:sqlite evidence))
                    :clean? (:clean? evidence)})))))

(deftest hegel-http-sqlite-holds-across-capacities-eintr-and-admission-order
  (let [seen-plans (atom #{})
        result
        (h/run-test!
         {:test-cases 20
          ;; Each case launches a fresh isolated Jolt worker so generation is
          ;; intentionally slower than Hegel's unit-test health threshold.
          :suppress-health-checks [:too-slow]
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [input (h/draw! (input-generator) "scenario-input")]
             (check-case! input)
             (swap! seen-plans conj (:admission-plan input))
             nil)))]
    (is (true? (:passed? result))
        (pr-str {:status (:status result)
                 :n-failures (:n-failures result)
                 :flaky? (:flaky? result)
                 :failures (:failures result)
                 :final (:final result)}))
    (is (false? (:flaky? result))
        (pr-str {:flaky? (:flaky? result)
                 :observed-failures (:observed-failures result)}))
    (is (= (set admission-plan-domain) @seen-plans)
        (str "Hegel did not exercise both admission plans: "
             (pr-str @seen-plans)))))

(deftest hegel-http-sqlite-begin-fail-open-finds-the-uncertain-begin-witness
  ;; Permanent executable control for the historical SQLite BEGIN fail-open
  ;; decision. Each case spawns a fresh worker, so generation is intentionally
  ;; slower than Hegel's unit-test health threshold -- exactly like the
  ;; property above -- and only :too-slow is suppressed; flakiness and
  ;; generation errors must still fail this test outright.
  (let [result
        (h/run-test!
         {:test-cases 20
          :suppress-health-checks [:too-slow]
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [begin-when (h/draw!
                              (g/sampled-from begin-fail-open-begin-when-domain)
                              "begin-when")
                 report-error? (h/draw!
                                (g/sampled-from
                                 begin-fail-open-report-error-domain)
                                "report-error?")
                 input {:begin-when begin-when :report-error? report-error?}]
             (check-begin-fail-open-case! input)
             nil)))
        failure (first (:failures result))
        final-exception (-> result :final first :exception)
        final-data (ex-data final-exception)
        actual (:actual final-data)]
    (is (false? (:passed? result))
        (pr-str {:status (:status result)
                 :n-failures (:n-failures result)
                 :flaky? (:flaky? result)
                 :failures (:failures result)
                 :final (:final result)}))
    (is (= :failed (:status result)))
    (is (= 1 (:n-failures result)))
    (is (= "jolt.sim.http-sqlite-hegel-test/begin-fail-open-coherence"
           (:origin failure)))
    (is (true? (:reproduced? failure)))
    (is (false? (:flaky? result))
        (pr-str {:flaky? (:flaky? result)
                 :observed-failures (:observed-failures result)}))
    (is (= "jolt.sim.http-sqlite-hegel-test/begin-fail-open-coherence"
           (:hegel/origin final-data)))
    (is (= {:begin-when :always :report-error? true} (:input final-data)))
    (is (= 0 (:logical-depth actual)))
    (is (false? (:autocommit? actual)))
    (let [routes (:routes actual)]
      (is (pos? (:count routes)))
      (is (true? (:all-handled? routes)))
      (is (= expected-begin-fail-open-error-foreign-symbols
             (set (:foreign-symbols routes)))))
    (is (= 1 (:closed-db-count actual)))
    ;; The historical evaluator must not reconcile against
    ;; sqlite3_get_autocommit or issue a recovery rollback.
    (is (= [] (:autocommit-evidence actual)))
    (is (= {:plan-index 2 :plan-count 2 :open-dbs 0 :active-stmts 0}
           (:sqlite-summary actual)))
    (is (= {:memory true :sqlite true} (:clean? actual)))
    (is (= {:begin-event 0 :begin-plan-index 1}
           (:discarded-transaction actual)))
    (is (= {} (:discarded-staging actual)))
    (is (= [{:sequence 0
             :plan-index 1
             :op :begin
             :when :always
             :reported :error
             :applied? true
             :reason nil
             :before-autocommit? true
             :after-autocommit? false}]
           (:tx-evidence actual)))))

;; ---- Begin-recovery / poisoning scenarios (corrected passing property) ----
;; The corrected passing counterpart to the historical fail-open witness
;; above: a bounded Hegel property over the ALREADY-CORRECTED SQLite
;; begin-recovery/poison-application contract
;; (jolt.sim.fixtures.http-sqlite-scenarios/run-recovery-scenario-case),
;; exercised over exactly the four begin-recovery/poisoning scenarios R2-R4
;; and R6. Every generated case spawns a fresh worker, exactly like the
;; properties above, and the worker delegates straight to
;; jolt.sim.fixtures.http-sqlite-scenarios/recovery-simulated-evidence -- the
;; unchanged simulated-mode handler/world construction and run-controlled
;; invocation run-recovery-scenario itself drives. No application, DB, or
;; evidence-construction logic is reimplemented here.
;;
;; This parent process does not load jolt.net/db/jolt-http (see the poll-id/
;; connect-id comment above), so the expected literals below are duplicated
;; from jolt.sim.http-sqlite-integration-test's begin-recovery/poisoning
;; deftests rather than shared by requiring that namespace.

(def ^:private recovery-case-scenario-sym
  'jolt.sim.fixtures.http-sqlite-scenarios/run-recovery-scenario-case)

;; R2, R3, R4, R6 in the same order the task and the integration test present
;; them. g/sampled-from shrinks toward index 0 (:uncertain-begin-recovered).
(def ^:private recovery-case-domain
  [:uncertain-begin-recovered
   :counter-rollback-unverified-poisoned
   :counter-rollback-failed-poisoned
   :preexisting-transaction-poisoned])

(def ^:private expected-recovery-posix-foreign-symbols
  #{"accept" "bind" "close" "connect" "fcntl" "freeaddrinfo"
    "getaddrinfo" "getpeername" "getsockname" "listen" "pipe"
    "poll" "read" "recv" "send" "getsockopt" "setsockopt"
    "socket" "write"})

(def ^:private expected-recovery-empty-http
  {:status 200
   :content-type "application/octet-stream"
   :content-length "0"
   :server-errors []
   :body-octets []})

(def ^:private expected-preexisting-observations
  {:primary {:caught? true
             :message "connection is physically inside a transaction while logical depth is zero; close connection"
             :poisoned? true
             :close-required? true}
   :follow-up {:rejected? true
               :message "connection transaction state is indeterminate; close connection"
               :poisoned? true
               :close-required? true
               :cleanup [{:phase :begin-precondition :sql "BEGIN"}]}})

(def ^:private expected-discarded-transaction
  {:begin-event 0 :begin-plan-index 2})

;; One expectation entry per scenario, grounded exactly in the corresponding
;; jolt.sim.http-sqlite-integration-test deftest, strengthened with the exact
;; live/discarded transaction and staging evidence recorded on close.
(def ^:private recovery-expectations
  {:uncertain-begin-recovered
   {:http {:status 200
           :content-type "application/octet-stream"
           :content-length "5"
           :server-errors []
           :body-octets [0 65 127 -128 -1]}
    :observations {:primary {:caught? true :rc 5
                             :message "sqlite step failed: database is locked"}
                   :body-ran-before-recovery? false
                   :retried? true
                   :retry-body-ran? true}
    :foreign-symbols
    (into expected-recovery-posix-foreign-symbols
          #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
            "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
            "sqlite3_column_count" "sqlite3_column_name"
            "sqlite3_column_type" "sqlite3_bind_int64"
            "sqlite3_bind_blob64" "sqlite3_column_blob"
            "sqlite3_column_bytes" "sqlite3_get_autocommit"
            "sqlite3_changes"})
    :sqlite-summary {:plan-index 8 :plan-count 8 :open-dbs 0 :active-stmts 0}
    :autocommit? true
    :autocommit-evidence [1 0 1 1]
    :tx-evidence [{:sequence 0 :plan-index 2 :op :begin :when :always
                   :reported :error :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 1 :plan-index 3 :op :rollback :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? false :after-autocommit? true}
                  {:sequence 2 :plan-index 4 :op :begin :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 3 :plan-index 6 :op :commit :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? false :after-autocommit? true}]
    :store-evidence [{:sequence 0 :plan-index 5 :op :put :reported :done
                      :key {:type :integer :value 1} :location :staging
                      :present? true}
                     {:sequence 1 :plan-index 7 :op :get :reported :done
                      :key {:type :integer :value 1} :location :committed
                      :present? true}]
    :committed {{:type :integer :value 1}
               {:type :blob :value [0 65 127 128 255]}}
    :tx nil
    :staging nil
    :discarded-transaction nil
    :discarded-staging nil}

   :counter-rollback-unverified-poisoned
   {:http expected-recovery-empty-http
    :observations {:primary {:caught? true :rc 5
                             :message "sqlite step failed: database is locked"}
                   :follow-up {:rejected? true
                               :message "connection transaction state is indeterminate; close connection"
                               :poisoned? true
                               :close-required? true
                               :cleanup [{:phase :begin-rollback-verify
                                         :sql "sqlite3_get_autocommit"}]}}
    :foreign-symbols
    (into expected-recovery-posix-foreign-symbols
          #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
            "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
            "sqlite3_column_count" "sqlite3_get_autocommit"
            "sqlite3_changes"})
    :sqlite-summary {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
    :autocommit? false
    :autocommit-evidence [1 0 0]
    :tx-evidence [{:sequence 0 :plan-index 2 :op :begin :when :always
                   :reported :error :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 1 :plan-index 3 :op :rollback :when :never
                   :reported :done :applied? false :reason :withheld
                   :before-autocommit? false :after-autocommit? false}]
    :store-evidence []
    :tx expected-discarded-transaction
    :staging {}
    :discarded-transaction expected-discarded-transaction
    :discarded-staging {}
    :committed {}}

   :counter-rollback-failed-poisoned
   {:http expected-recovery-empty-http
    :observations {:primary {:caught? true :rc 5
                             :message "sqlite step failed: database is locked"}
                   :follow-up {:rejected? true
                               :message "connection transaction state is indeterminate; close connection"
                               :poisoned? true
                               :close-required? true
                               :cleanup [{:phase :begin-rollback :sql "ROLLBACK"}]}}
    :foreign-symbols
    (into expected-recovery-posix-foreign-symbols
          #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_errmsg"
            "sqlite3_prepare_v2" "sqlite3_step" "sqlite3_finalize"
            "sqlite3_column_count" "sqlite3_get_autocommit"
            "sqlite3_changes"})
    :sqlite-summary {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
    :autocommit? false
    :autocommit-evidence [1 0]
    :tx-evidence [{:sequence 0 :plan-index 2 :op :begin :when :always
                   :reported :error :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}
                  {:sequence 1 :plan-index 3 :op :rollback :when :on-success
                   :reported :error :applied? false :reason :reported-error
                   :before-autocommit? false :after-autocommit? false}]
    :store-evidence []
    :tx expected-discarded-transaction
    :staging {}
    :discarded-transaction expected-discarded-transaction
    :discarded-staging {}
    :committed {}}

   :preexisting-transaction-poisoned
   {:http expected-recovery-empty-http
    :observations expected-preexisting-observations
    :foreign-symbols
    (into expected-recovery-posix-foreign-symbols
          #{"sqlite3_open" "sqlite3_close_v2" "sqlite3_prepare_v2"
            "sqlite3_step" "sqlite3_finalize" "sqlite3_column_count"
            "sqlite3_bind_int64" "sqlite3_bind_blob64"
            "sqlite3_get_autocommit" "sqlite3_changes"})
    :sqlite-summary {:plan-index 4 :plan-count 4 :open-dbs 0 :active-stmts 0}
    :autocommit? false
    :autocommit-evidence [0]
    :tx-evidence [{:sequence 0 :plan-index 2 :op :begin :when :on-success
                   :reported :done :applied? true :reason nil
                   :before-autocommit? true :after-autocommit? false}]
    :store-evidence [{:sequence 0 :plan-index 3 :op :put :reported :done
                      :key {:type :integer :value 1} :location :staging
                      :present? true}]
    :tx expected-discarded-transaction
    :staging {{:type :integer :value 1}
              {:type :blob :value [0 65 127 128 255]}}
    :discarded-transaction expected-discarded-transaction
    :discarded-staging {{:type :integer :value 1}
                        {:type :blob :value [0 65 127 128 255]}}
    :committed {}}})

(defn- check-recovery-case!
  "Runs one fresh-worker case for the drawn {:scenario ...} `input` and
  independently asserts the corrected R2/R3/R4/R6 contract looked up from
  recovery-expectations: the ordinary HTTP outcome, the fixture's primary/
  follow-up observations, exact plan consumption, handled routes and required
  SQLite calls, physical autocommit and full transaction evidence, required
  store/discard evidence, and clean memory/SQLite/POSIX worlds. Every
  assertion site's :hegel/origin is a fixed string independent of the drawn
  scenario; only :input and :actual vary per case."
  [input]
  (let [base-config
        (merge (process-config)
               {:scenario recovery-case-scenario-sym
                :timeout-ms 20000
                :kill-grace-ms 500})
        outcome (process-explorer/run-case
                 (assoc base-config :input input))
        completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)]
    (when-not (map? evidence)
      (violation "jolt.sim.http-sqlite-hegel-test/recovery-evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [expected (get recovery-expectations (:scenario input))
          {:keys [http observations routes sqlite clean?]} evidence
          db (first (get-in evidence [:sqlite :closed-db-evidence]))]
      (when-not (= (:http expected) (dissoc http :raw-length))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-http"
                   input {:http http :expected (:http expected)}))
      (when-not (= (:observations expected) observations)
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-observations"
                   input {:observations observations
                          :expected (:observations expected)}))
      (when-not (true? (:all-handled? routes))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-all-handled"
                   input {:routes routes}))
      ;; Identical runs can perform different numbers of ordinary POSIX
      ;; readiness calls, so the total route count is intentionally not an
      ;; exact invariant. Require non-vacuity here; the exact foreign-symbol
      ;; set and the complete SQLite plan/transaction/store evidence below
      ;; constrain the meaningful work independently.
      (when-not (and (integer? (:count routes))
                     (pos? (:count routes)))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-route-count"
                   input {:routes routes}))
      (when-not (= (:foreign-symbols expected) (set (:foreign-symbols routes)))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-foreign-symbols"
                   input {:foreign-symbols (:foreign-symbols routes)
                          :expected (:foreign-symbols expected)}))
      (when-not (= (:sqlite-summary expected) (:summary sqlite))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-sqlite-summary"
                   input {:summary (:summary sqlite)
                          :expected (:sqlite-summary expected)}))
      (when-not (= 1 (count (:closed-db-evidence sqlite)))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-closed-db-count"
                   input {:count (count (:closed-db-evidence sqlite))}))
      (when-not (= (:autocommit? expected) (:autocommit? db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-autocommit"
                   input {:autocommit? (:autocommit? db)
                          :expected (:autocommit? expected)}))
      (when-not (= (:autocommit-evidence expected) (:autocommit-evidence db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-autocommit-evidence"
                   input {:autocommit-evidence (:autocommit-evidence db)
                          :expected (:autocommit-evidence expected)}))
      (when-not (= (:tx-evidence expected) (:tx-evidence db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-tx-evidence"
                   input {:tx-evidence (:tx-evidence db)
                          :expected (:tx-evidence expected)}))
      (when-not (= (:store-evidence expected) (:store-evidence db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-store-evidence"
                   input {:store-evidence (:store-evidence db)
                          :expected (:store-evidence expected)}))
      (when-not (= (:committed expected) (:committed db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-committed"
                   input {:committed (:committed db)
                          :expected (:committed expected)}))
      (when-not (= (:tx expected) (:tx db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-tx"
                   input {:tx (:tx db) :expected (:tx expected)}))
      (when-not (= (:staging expected) (:staging db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-staging"
                   input {:staging (:staging db)
                          :expected (:staging expected)}))
      (when-not (= (:discarded-transaction expected)
                   (:discarded-transaction db))
        (violation
         "jolt.sim.http-sqlite-hegel-test/recovery-discarded-transaction"
         input {:discarded-transaction (:discarded-transaction db)
                :expected (:discarded-transaction expected)}))
      (when-not (= (:discarded-staging expected) (:discarded-staging db))
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-discarded-staging"
                   input {:discarded-staging (:discarded-staging db)
                          :expected (:discarded-staging expected)}))
      (when-not (= {:memory true :sqlite true :posix true} clean?)
        (violation "jolt.sim.http-sqlite-hegel-test/recovery-world-cleanup"
                   input {:clean? clean?})))))

(deftest hegel-http-sqlite-recovery-scenarios-hold-across-r2-r3-r4-and-r6
  ;; The corrected passing counterpart to
  ;; hegel-http-sqlite-begin-fail-open-finds-the-uncertain-begin-witness
  ;; above: every generated case must pass, and every generated case spawns a
  ;; fresh worker, so only :too-slow is suppressed -- flakiness and
  ;; generation errors must still fail this test outright.
  (let [seen-scenarios (atom #{})
        result
        (h/run-test!
         ;; The finite sampled-from domain executes one case per member under
         ;; the pinned Hegel build. Making the four-case ceiling explicit keeps
         ;; 4 * (20 s worker deadline + 500 ms kill grace) far below the
         ;; unchanged 300 s namespace watchdog; the assertion below proves the
         ;; pinned seed still visited every member.
         {:test-cases 4
          :suppress-health-checks [:too-slow]
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [scenario (h/draw! (g/sampled-from recovery-case-domain)
                                    "recovery-scenario")
                 input {:scenario scenario}]
             (check-recovery-case! input)
             (swap! seen-scenarios conj scenario)
             nil)))]
    (is (true? (:passed? result))
        (pr-str {:status (:status result)
                 :n-failures (:n-failures result)
                 :flaky? (:flaky? result)
                 :failures (:failures result)
                 :final (:final result)}))
    (is (false? (:flaky? result))
        (pr-str {:flaky? (:flaky? result)
                 :observed-failures (:observed-failures result)}))
    (is (= (set recovery-case-domain) @seen-scenarios)
        (str "Hegel did not exercise all four recovery scenarios: "
             (pr-str @seen-scenarios)))))

(def ^:private watchdog-timeout-ms 300000)

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:http-sqlite-explore-worker"]
                   :dir project-dir}]
          (deref (future (test/run-tests
                          'jolt.sim.http-sqlite-hegel-test))
                 watchdog-timeout-ms ::timeout))]
    (cond
      (= ::timeout result)
      (do
        (println (str "FAILURE: http-sqlite-hegel-test timed out after "
                      watchdog-timeout-ms "ms"))
        (flush)
        (System/exit 1))
      :else
      (let [failures (+ (:fail result) (:error result))]
        (println (str (:test result) " tests, "
                      (:pass result) " assertions passed"))
        (flush)
        ;; Hegel and the worker launcher may leave non-daemon threads alive;
        ;; the parent must exit explicitly.
        (System/exit (if (zero? failures) 0 1))))))
