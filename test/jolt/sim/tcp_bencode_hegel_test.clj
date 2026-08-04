(ns jolt.sim.tcp-bencode-hegel-test
  "Fresh-process Hegel property lane over the UNCHANGED
  jolt.sim.fixtures.tcp-bencode length-framed request/reply TCP application
  under the shared hermetic POSIX loopback handler pack.

  A single deterministic case runs first as the real/sim parity witness: a
  forced one-byte stream/pipe capacity plus a captured first-poll EINTR, with
  `:check-parity?` true so the worker also exercises the fixture once against
  real loopback sockets and returns both results for comparison. Every later
  case draws a Hegel-owned, shrinkable scenario input -- stream capacity, pipe
  capacity, an optional captured poll EINTR activation ordinal, and one
  discriminating UTF-8 request text -- and runs the unchanged fixture once in
  a fresh sim-enabled Jolt worker through jolt.sim.process-explorer/run-case
  (protocol-v2 :input). The worker loads
  jolt.sim.fixtures.tcp-bencode-scenarios, builds the shared hermetic world
  from the drawn input, and returns one canonical evidence map.

  Each completed case checks exact correlated echo_ok replies (both pipelined
  requests, spanning empty, ASCII, and Unicode text boundaries), full codec
  consumption via those exact replies, the absence of server errors, route,
  capacity, fault, and cleanup evidence sufficient to catch a model bypass.
  This first slice intentionally omits broad ffi-schedule interleaving
  search; capacity fragmentation, pipelining, real/sim parity, and captured
  EINTR are the feature boundary.

  A regression failure carries the bounded drawn input so Hegel can replay
  and shrink it: a non-:completed outcome is enriched with :input around
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

(def ^:private required-foreign-symbols
  #{"socket" "connect" "accept" "poll" "send" "recv" "close"})

(def ^:private scenario-sym
  'jolt.sim.fixtures.tcp-bencode-scenarios/exercise-with-capacities)

;; Ordered, discriminating boundaries rather than every integer in a range.
;; Hegel owns selection and shrinks sampled indexes toward the one-byte/no-
;; fault/empty-text case while still exercising larger capacities, later poll
;; attempts, and Unicode request text.
(def ^:private capacity-domain [1 2 4 8])
(def ^:private poll-eintr-domain [nil 1 2 4 8])
(def ^:private request-text-domain
  ["" "hello" "cafe society" "cafe au lait Ω≈ç" "日本語テキスト" "🎉🎊✨ mixed 中文"])

;; ---- Worker/case budgets vs. independent per-property watchdogs -----------
;; This source candidate originally put the parity witness and both Hegel
;; properties behind one cumulative watchdog. That left almost no room for a
;; late failure's shrink and final replay. Each test var now has its own parent
;; watchdog below and runs serially. Nominal initial-case ceilings are:
;;
;;   parity witness       1 * (30000 + 500) =  30500 ms /  60000 ms watchdog
;;   finite-domain lane  15 * (20000 + 500) = 307500 ms / 450000 ms watchdog
;;   UTF-8 lane           5 * (20000 + 500) = 102500 ms / 300000 ms watchdog
;;
;; The remaining per-property time belongs to Hegel shrinking/final replay.
;; Hegel does not expose a separate shrink-time budget, so the parent watchdog
;; is an infrastructure bound, not a falsification result. The 20000 ms child
;; deadline matches the other cold process-backed gates. Under the isolated
;; exact-pin probe, ordinary cases completed in 6.3--7.7 seconds and the three
;; cases that crossed the old 8000 ms boundary completed within 10.9 seconds.
;; Hosted Linux later completed 34 cases around 9--10 seconds before one empty-
;; output worker exhausted the obsolete 15000 ms allowance.
(def ^:private witness-timeout-ms 30000)
(def ^:private case-timeout-ms 20000)
(def ^:private kill-grace-ms 500)

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- nonnegative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- exact-map-keys? [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- expected-replies [request-text]
  [{"type" "echo_ok" "seq" 1 "text" ""}
   {"type" "echo_ok" "seq" 2 "text" request-text}])

(defn- input-generator
  "Returns a Hegel generator over the scenario input domain. The four draws
  are composed with g/tuple and g/fmap so the whole input shrinks as one
  unit and threads through run-case's :input unchanged. Selection and
  shrinking stay engine-owned: this wrapper performs no selection of its
  own. Every generated case is an ordinary (non-witness) case."
  []
  (g/fmap
   (fn [[stream-capacity pipe-capacity ordinal text]]
     {:stream-capacity stream-capacity
      :pipe-capacity pipe-capacity
      :poll-eintr-ordinal ordinal
      :request-text text
      :check-parity? false})
   (g/tuple (g/sampled-from capacity-domain)
            (g/sampled-from capacity-domain)
            (g/sampled-from poll-eintr-domain)
            (g/sampled-from request-text-domain))))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.tcp-bencode-hegel-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Hegel tcp-bencode tests must be run through -main"
        {:type :jolt.sim.tcp-bencode-hegel-test/not-configured}))))

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
  :hegel/origin, the drawn input, and a small bounded :actual projection of
  the evidence that failed. Hegel replays and shrinks against this ex-data."
  [origin input actual]
  (throw
   (ex-info
    "jolt.sim.tcp-bencode-hegel-test property invariant violated"
    {:hegel/origin origin
     :input input
     :actual actual})))

(defn- assert-case-outcome!
  "Asserts the full invariant set for one completed worker outcome. Throws on
  the first violation and returns nil on success."
  [input outcome]
  (let [completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)]
    (when-not (map? evidence)
      (violation "jolt.sim.tcp-bencode-hegel-test/evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [{:keys [tcp routes capacity fault clean? parity]} evidence
          expected (expected-replies (:request-text input))
          expected-evidence-keys
          (cond-> #{:tcp :routes :capacity :fault :clean?}
            (:check-parity? input) (conj :parity))]
      ;; Evidence is the versioned boundary between the fresh worker and this
      ;; parent property. Reject missing, extra, or malformed fields here so a
      ;; truthy value or a host predicate exception cannot masquerade as a
      ;; passing property or erase the stable Hegel origin.
      (when-not (exact-map-keys? evidence expected-evidence-keys)
        (violation "jolt.sim.tcp-bencode-hegel-test/evidence-keys"
                   input
                   {:keys (when (map? evidence)
                            (vec (sort-by pr-str (keys evidence))))
                    :expected (vec (sort-by pr-str expected-evidence-keys))}))
      (when-not (exact-map-keys?
                 tcp
                 #{:sent-bytes :received-bytes :replies :server-errors
                   :close-results})
        (violation "jolt.sim.tcp-bencode-hegel-test/tcp-shape"
                   input
                   {:tcp tcp}))
      (when-not (exact-map-keys?
                 routes #{:count :all-handled? :foreign-symbols})
        (violation "jolt.sim.tcp-bencode-hegel-test/routes-shape"
                   input
                   {:routes routes}))
      (when-not (and (exact-map-keys? capacity #{:stream :pipe})
                     (exact-map-keys?
                      (:stream capacity)
                      #{:stream-capacity :stream-would-blocks
                        :stream-capacity-limited-writes
                        :max-stream-recv-bytes})
                     (exact-map-keys?
                      (:pipe capacity)
                      #{:pipe-capacity :pipe-would-blocks
                        :max-pipe-fifo-bytes}))
        (violation "jolt.sim.tcp-bencode-hegel-test/capacity-shape"
                   input
                   {:capacity capacity}))
      (when-not (exact-map-keys?
                 fault #{:attempts :firings :fired-attempts})
        (violation "jolt.sim.tcp-bencode-hegel-test/fault-shape"
                   input
                   {:fault fault}))
      ;; Ordinary app response: exact correlated echo_ok replies for both
      ;; pipelined requests, spanning the empty/ASCII/Unicode text boundaries,
      ;; which also proves the bencode decoder fully consumed each declared
      ;; frame body.
      (when-not (= expected (:replies tcp))
        (violation "jolt.sim.tcp-bencode-hegel-test/replies"
                   input
                   {:tcp tcp :expected expected}))
      (when-not (= [] (:server-errors tcp))
        (violation "jolt.sim.tcp-bencode-hegel-test/server-errors"
                   input
                   {:tcp tcp}))
      (when-not (= {:connection [true false]} (:close-results tcp))
        (violation "jolt.sim.tcp-bencode-hegel-test/close-results"
                   input
                   {:tcp tcp}))
      (when-not (positive-integer? (:sent-bytes tcp))
        (violation "jolt.sim.tcp-bencode-hegel-test/sent-bytes"
                   input
                   {:tcp tcp}))
      (when-not (positive-integer? (:received-bytes tcp))
        (violation "jolt.sim.tcp-bencode-hegel-test/received-bytes"
                   input
                   {:tcp tcp}))
      ;; Effect/route evidence: every intercepted POSIX call was served by a
      ;; registered handler; nothing routed to a real socket, and the fixture
      ;; actually made native calls through the expected symbols.
      (when-not (positive-integer? (:count routes))
        (violation "jolt.sim.tcp-bencode-hegel-test/route-count"
                   input
                   {:routes routes}))
      (when-not (true? (:all-handled? routes))
        (violation "jolt.sim.tcp-bencode-hegel-test/all-handled"
                   input
                   {:routes routes}))
      (let [symbols (:foreign-symbols routes)]
        (when-not (and (vector? symbols) (every? string? symbols))
          (violation "jolt.sim.tcp-bencode-hegel-test/foreign-symbols-shape"
                     input
                     {:foreign-symbols symbols}))
        (when-not (every? (set symbols) required-foreign-symbols)
          (violation "jolt.sim.tcp-bencode-hegel-test/required-routes"
                     input
                     {:missing (vec (sort (remove (set symbols)
                                                 required-foreign-symbols)))})))
      ;; Capacity model was honored end to end under the drawn bounds.
      (when-not (= (:stream-capacity input)
                   (:stream-capacity (:stream capacity)))
        (violation "jolt.sim.tcp-bencode-hegel-test/stream-capacity"
                   input
                   {:capacity capacity}))
      (when-not (= (:pipe-capacity input)
                   (:pipe-capacity (:pipe capacity)))
        (violation "jolt.sim.tcp-bencode-hegel-test/pipe-capacity"
                   input
                   {:capacity capacity}))
      (when-not (= (:stream-capacity input)
                   (:max-stream-recv-bytes (:stream capacity)))
        (violation "jolt.sim.tcp-bencode-hegel-test/stream-occupancy"
                   input
                   {:capacity capacity}))
      (let [pipe-occupancy (:max-pipe-fifo-bytes (:pipe capacity))]
        (when-not (and (integer? pipe-occupancy)
                       (<= 1 pipe-occupancy (:pipe-capacity input)))
          (violation "jolt.sim.tcp-bencode-hegel-test/pipe-occupancy"
                     input
                     {:capacity capacity})))
      (when-not (and
                 (nonnegative-integer?
                  (:stream-capacity-limited-writes (:stream capacity)))
                 (nonnegative-integer?
                  (:pipe-would-blocks (:pipe capacity))))
        (violation "jolt.sim.tcp-bencode-hegel-test/capacity-counters"
                   input
                   {:capacity capacity}))
      ;; Every generated stream capacity must be exercised at its bound and
      ;; must produce real backpressure. Capacity one additionally guarantees
      ;; a capacity-limited write because frames can cross only one byte at a
      ;; time. Pipe capacity is configuration coverage in this slice; a later
      ;; wake-coalescing workload must pressure every larger pipe bound.
      (when-not (positive-integer?
                 (:stream-would-blocks (:stream capacity)))
        (violation "jolt.sim.tcp-bencode-hegel-test/stream-would-block"
                   input
                   {:capacity capacity}))
      (when (= 1 (:stream-capacity input))
        (when-not (positive-integer?
                   (:stream-capacity-limited-writes (:stream capacity)))
          (violation "jolt.sim.tcp-bencode-hegel-test/stream-partial-write"
                     input
                     {:capacity capacity})))
      ;; Fault evidence: every generated non-nil ordinal must be reachable and
      ;; fire exactly once. Merely drawing an ordinal is not coverage. A nil
      ;; ordinal installs no frontend, so both counts are zero.
      (when-not (and (nonnegative-integer? (:attempts fault))
                     (nonnegative-integer? (:firings fault))
                     (vector? (:fired-attempts fault)))
        (violation "jolt.sim.tcp-bencode-hegel-test/fault-fields"
                   input
                   {:fault fault}))
      (when-not (#{0 1} (:firings fault))
        (violation "jolt.sim.tcp-bencode-hegel-test/fault-firings"
                   input
                   {:fault fault}))
      (when (and (some? (:poll-eintr-ordinal input))
                 (< (:attempts fault) (:poll-eintr-ordinal input)))
        (violation "jolt.sim.tcp-bencode-hegel-test/fault-ordinal-unreachable"
                   input
                   {:fault fault}))
      (let [requested-ordinal (:poll-eintr-ordinal input)
            expected-firings (if (some? requested-ordinal) 1 0)
            expected-fired-attempts
            (if (= 1 expected-firings)
              [{:attempt-id
                [:jolt.sim.net.posix-fault/poll requested-ordinal]
                :firing
                {:rule-id :tcp-bencode/interrupt-poll
                 :firing-ordinal 1
                 :rule-firing-ordinal 1
                 :match-ordinal requested-ordinal
                 :outcome {:kind :captured-error :errno :eintr}}}]
              [])]
        (when-not (= expected-firings (:firings fault))
          (violation "jolt.sim.tcp-bencode-hegel-test/fault-activation"
                     input
                     {:fault fault :expected-firings expected-firings}))
        (when-not (= expected-fired-attempts (:fired-attempts fault))
          (violation "jolt.sim.tcp-bencode-hegel-test/fault-attempt-identity"
                     input
                     {:fault fault
                      :expected-fired-attempts expected-fired-attempts})))
      ;; Cleanup: the shared world retired every resource; a leaked socket,
      ;; pipe, or native allocation is a bypass.
      (when-not (= {:memory true :posix true} clean?)
        (violation "jolt.sim.tcp-bencode-hegel-test/world-cleanup"
                   input
                   {:clean? clean?}))
      ;; Real/sim parity: only the one witness case draws :check-parity? true.
      ;; The real run must produce the same exact correlated replies as the
      ;; simulated run, with no server errors and an idempotent close.
      (when (:check-parity? input)
        (when-not (exact-map-keys?
                   parity
                   #{:real-replies :real-server-errors :real-close-results})
          (violation "jolt.sim.tcp-bencode-hegel-test/parity-shape"
                     input
                     {:parity parity}))
        (when-not (= expected (:real-replies parity))
          (violation "jolt.sim.tcp-bencode-hegel-test/parity-real-replies"
                     input
                     {:parity parity :expected expected}))
        (when-not (= (:replies tcp) (:real-replies parity))
          (violation "jolt.sim.tcp-bencode-hegel-test/parity-real-vs-sim"
                     input
                     {:tcp tcp :parity parity}))
        (when-not (= [] (:real-server-errors parity))
          (violation "jolt.sim.tcp-bencode-hegel-test/parity-real-server-errors"
                     input
                     {:parity parity}))
        (when-not (= {:connection [true false]} (:real-close-results parity))
          (violation "jolt.sim.tcp-bencode-hegel-test/parity-real-close-results"
                     input
                     {:parity parity})))
      (when (and (not (:check-parity? input)) (some? parity))
        (violation "jolt.sim.tcp-bencode-hegel-test/unexpected-parity"
                   input
                   {:parity parity})))))

(defn- check-case!
  "Runs one fresh case for the drawn `input` and asserts the full invariant
  set. Throws on the first violation; returns nil on success. Throwing
  (rather than returning false) is required inside a property passed to a
  custom Hegel runner."
  [input]
  (let [base-config
        (merge (process-config)
               {:scenario scenario-sym
                ;; The first parity witness performs both real and simulated
                ;; application runs after a cold child resolves the complete
                ;; TCP/bytes/bencode dependency graph. Keep ordinary generated
                ;; cases at the tighter bound once that cache is warm.
                :timeout-ms (if (:check-parity? input)
                              witness-timeout-ms
                              case-timeout-ms)
                :kill-grace-ms kill-grace-ms})
        outcome (process-explorer/run-case (assoc base-config :input input))]
    ;; process-explorer intentionally bounds diagnostics and the Hegel adapter
    ;; intentionally omits them from its exception data. Emit a best-effort
    ;; bounded projection before that boundary so a gate capturing stdout can
    ;; diagnose ordinary timeouts and interruptions. This console record is not
    ;; the crash-safe append-only journal planned for a later slice.
    (when-not (= :completed (:status outcome))
      (println
       (pr-str
        {:event :tcp-bencode/non-completed-worker
         :input input
         :outcome (select-keys outcome
                               [:status :reason :exit :error :diagnostics])}))
      (flush))
    (assert-case-outcome! input outcome)))

(defn- elapsed-ms [started-nanos]
  (quot (- (System/nanoTime) started-nanos) 1000000))

(defn- case-progress!
  [event lane ordinal input details]
  (println
   (pr-str
    {:event event
     :lane lane
     :ordinal ordinal
     :input input
     :details details}))
  (flush))

(defn- check-case-with-progress!
  "Runs one case while emitting bounded start/finish/failure records. These
  best-effort console records are diagnostic only; when stdout reaches the
  gate, they expose the last drawn input and stable Hegel origin even if the
  parent is interrupted before Hegel returns a final result. They make no
  crash-safe or append-only durability claim."
  [lane ordinal input]
  (let [started (System/nanoTime)]
    (case-progress! :tcp-bencode/case-start lane ordinal input nil)
    (try
      (check-case! input)
      (case-progress! :tcp-bencode/case-finish lane ordinal input
                      {:elapsed-ms (elapsed-ms started)})
      (catch :default error
        (case-progress!
         :tcp-bencode/case-failure lane ordinal input
         {:elapsed-ms (elapsed-ms started)
          :class (str (class error))
          :message (or (ex-message error) (str error))
          :data (select-keys
                 (or (ex-data error) {})
                 [:hegel/origin :type :status :reason :exit :error
                  :input :actual])})
        (throw error)))))

;; The one deterministic real/sim parity witness: forced one-byte stream and
;; pipe capacity plus a captured first-poll EINTR, so the single witness case
;; also exercises capacity fragmentation and captured EINTR end to end.
(def ^:private parity-witness-input
  {:stream-capacity 1
   :pipe-capacity 1
   :poll-eintr-ordinal 1
   :request-text "cafe au lait Ω≈ç"
   :check-parity? true})

(deftest tcp-bencode-real-sim-parity-witness
  (check-case-with-progress! :parity 1 parity-witness-input))

(deftest hegel-tcp-bencode-holds-across-capacities-eintr-and-request-domain
  (let [seen-stream-capacities (atom #{})
        seen-pipe-capacities (atom #{})
        seen-poll-ordinals (atom #{})
        seen-texts (atom #{})
        case-ordinal (atom 0)
        result
        (h/run-test!
         {:test-cases 15
          ;; Each case launches a fresh isolated Jolt worker so generation is
          ;; intentionally slower than Hegel's unit-test health threshold.
          :suppress-health-checks [:too-slow]
          :seed 1
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [input (h/draw! (input-generator) "scenario-input")]
             (check-case-with-progress!
              :finite-domain (swap! case-ordinal inc) input)
             (swap! seen-stream-capacities conj (:stream-capacity input))
             (swap! seen-pipe-capacities conj (:pipe-capacity input))
             (swap! seen-poll-ordinals conj (:poll-eintr-ordinal input))
             (swap! seen-texts conj (:request-text input))
             nil)))]
    (is (true? (:passed? result))
        (pr-str {:status (:status result)
                 :seed (:seed result)
                 :n-failures (:n-failures result)
                 :flaky? (:flaky? result)
                 :failures (:failures result)
                 :final (:final result)}))
    (is (false? (:flaky? result))
        (pr-str {:seed (:seed result)
                 :flaky? (:flaky? result)
                 :observed-failures (:observed-failures result)}))
    (is (= (set capacity-domain) @seen-stream-capacities)
        (str "Hegel did not exercise the full stream-capacity domain: "
             (pr-str @seen-stream-capacities)))
    (is (= (set capacity-domain) @seen-pipe-capacities)
        (str "Hegel did not exercise the full pipe-capacity domain: "
             (pr-str @seen-pipe-capacities)))
    (is (= (set poll-eintr-domain) @seen-poll-ordinals)
        (str "Hegel did not exercise the full poll-EINTR domain: "
             (pr-str @seen-poll-ordinals)))
    (is (= (set request-text-domain) @seen-texts)
        (str "Hegel did not exercise the full request-text domain: "
             (pr-str @seen-texts)))))

;; Keep the fixed discriminating-domain regression above, then let Hegel own a
;; small true UTF-8 text domain as a separate shrinkable lane. It is not a
;; substitute for broad multi-seed CI, but it avoids limiting this feature to
;; six hand-picked strings.
(deftest hegel-tcp-bencode-shrinks-utf8-text
  (let [case-ordinal (atom 0)
        result
        (h/run-test!
         {:test-cases 5
          :suppress-health-checks [:too-slow]
          :seed 2
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [text
                 (h/draw!
                  (g/string {:codec :utf-8 :min-size 0 :max-size 32})
                  "request-text")]
             (check-case-with-progress!
              :utf8-text
              (swap! case-ordinal inc)
              {:stream-capacity 4
               :pipe-capacity 1
               :poll-eintr-ordinal 1
               :request-text text
               :check-parity? false}))))]
    (is (true? (:passed? result))
        (pr-str (assoc result :seed (:seed result))))
    (is (false? (:flaky? result))
        (pr-str (assoc result :seed (:seed result))))))

(def ^:private bounded-test-vars
  [{:name "tcp-bencode-real-sim-parity-witness"
    :var #'tcp-bencode-real-sim-parity-witness
    :watchdog-ms 60000}
   {:name "hegel-tcp-bencode-holds-across-capacities-eintr-and-request-domain"
    :var #'hegel-tcp-bencode-holds-across-capacities-eintr-and-request-domain
    :watchdog-ms 450000}
   {:name "hegel-tcp-bencode-shrinks-utf8-text"
    :var #'hegel-tcp-bencode-shrinks-utf8-text
    :watchdog-ms 300000}])

(defn- counter-snapshot []
  {:test (:test @test/counters)
   :pass (test/n-pass)
   :fail (test/n-fail)
   :error (test/n-error)})

(defn- counter-delta [before after]
  (into {}
        (map (fn [key] [key (- (get after key) (get before key))]))
        [:test :pass :fail :error]))

(defn- run-bounded-test-var!
  "Runs one test var behind its own parent watchdog. A timeout is an
  infrastructure failure, not a Hegel counterexample; exit immediately so a
  still-running property cannot overlap the next fresh-worker lane."
  [{:keys [name var watchdog-ms]}]
  (let [before (counter-snapshot)
        result (deref (future (test/test-var var))
                      watchdog-ms ::timeout)]
    (when (= ::timeout result)
      (println (str "FAILURE: " name " exceeded its "
                    watchdog-ms
                    "ms infrastructure watchdog; no Hegel result claimed"))
      (flush)
      (System/exit 1))
    (counter-delta before (counter-snapshot))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:tcp-bencode-explore-worker"]
                   :dir project-dir
                   :startup-timeout-ms 120000}]
          (reduce (fn [summary bounded-test]
                    (merge-with + summary
                                (run-bounded-test-var! bounded-test)))
                  {:test 0 :pass 0 :fail 0 :error 0}
                  bounded-test-vars))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (flush)
    ;; Hegel and the worker launcher may leave non-daemon threads alive; the
    ;; parent must exit explicitly after all three bounded lanes run serially.
    (System/exit (if (zero? failures) 0 1))))
