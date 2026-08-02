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
      (when-not (pos? (:count routes))
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
