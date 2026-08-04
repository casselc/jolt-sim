(ns jolt.sim.outbox-delivery-hegel-test
  "Fresh-process Hegel property lane over the UNCHANGED
  jolt.sim.fixtures.outbox-delivery whole application (ordinary HTTP command
  -> committed SQLite outbox -> framed TCP/bencode delivery/ack) under the
  shared hermetic SQLite plus POSIX loopback handler packs.

   Two deterministic boundary cases run first as the payload-semantics witness:
   the empty payload and the [0 127 128 255] payload (embedded zero, 0x7f,
   0x80, and 0xff octets) at the fixed smoke capacities, the second with one
   captured first-poll EINTR. Each witness carries one of the two
   discriminating first-poll admission plans, so both release orders hold
   outside generation. Real/sim parity is deliberately NOT rerun in this lane;
   the existing real-plus-hermetic PR #28 gate (:outbox-delivery-test) remains
   the parity evidence for the unchanged application.

   Every generated case draws a Hegel-owned, shrinkable scenario input --
   command payload octet vector, stream capacity, pipe capacity, an optional
   captured poll EINTR activation ordinal, and one of two discriminating
   first-poll admission plans (receiver reactor before HTTP reactor, or the
   reverse) -- and runs the unchanged fixture once in a fresh sim-enabled Jolt
   worker through jolt.sim.process-explorer/run-case (protocol-v2 :input). The
   worker loads jolt.sim.fixtures.outbox-delivery-scenarios, builds the shared
   hermetic worlds from the drawn input (including the exact SQLite plan world
   parameterized with the same payload), wraps the composed handlers with
   jolt.sim.ffi-schedule semantic selector steps over occurrence 1 of each
   reactor's poll role, and returns one canonical evidence map. Request and
   entity IDs stay fixed for this slice; extreme 1--2 byte HTTP fragmentation
   is explicitly later work.

   Each completed case checks exact command/store/delivery/ack results derived
   from the drawn input, exact SQLite plan consumption, handler-only routing,
   fault activation identity, bounded capacity participation, exact plan-order
   coordinator releases with quiesced diagnostics and per-role poll label
   counts, and all-world cleanup. A regression failure carries the bounded
   drawn input so Hegel can replay and shrink it: a non-:completed outcome is
   enriched with :input (and the worker :artifact-dir coordinates when
   retained) around jolt.sim.hegel/require-completed!, and every assertion
   site throws a typed ex-info carrying :hegel/origin and the same input.

  Process-isolated like :tcp-bencode-hegel-test: each case spawns a fresh
  worker, and the parent supplies the worker command through JOLT_SIM_BIN and
  JOLT_SIM_PROJECT_DIR."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.fs :as fs]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)

(def ^:private required-foreign-symbols
  #{"socket" "connect" "accept" "poll" "send" "recv" "close"
    "sqlite3_open" "sqlite3_close_v2"
    "sqlite3_bind_blob64" "sqlite3_column_blob"})

(def ^:private scenario-sym
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-with-capacities)

;; Ordered, discriminating boundaries rather than every integer in a range.
;; Hegel owns selection and shrinks sampled indexes toward the smoke-capacity/
;; no-fault case while still exercising larger capacities and later poll
;; attempts. The stream floor 8 is the fixed smoke capacity proven against
;; the fixture's 5 second client deadline; 1--2 byte HTTP fragmentation is
;; explicitly later work. These literals deliberately mirror the closed
;; domains in the scenario namespace, which this parent cannot load (the app
;; dependency stack is absent here); the worker child rejects anything
;; outside them before a world exists.
(def ^:private stream-capacity-domain [8 16 32])
(def ^:private pipe-capacity-domain [1 2 4])
(def ^:private poll-eintr-domain [nil 1 2 4 8])
(def ^:private max-payload-octets 32)
;; Exactly two discriminating first-poll admission orders, mirrored from the
;; scenario namespace's closed domain: the receiver reactor's first poll
;; released before the HTTP reactor's first poll, or the reverse. There is no
;; nil/unscheduled value; every case exercises the semantic-selector
;; coordinator. The step ids are the scenario namespace's own ::receiver-poll
;; and ::http-poll literals, mirrored here for the exact release-order
;; assertion (release evidence carries step ids, never selectors).
(def ^:private admission-plan-domain
  [:receiver-poll-then-http-poll :http-poll-then-receiver-poll])
(def ^:private receiver-poll-step-id
  :jolt.sim.fixtures.outbox-delivery-scenarios/receiver-poll)
(def ^:private http-poll-step-id
  :jolt.sim.fixtures.outbox-delivery-scenarios/http-poll)

(defn- expected-release-evidence
  "The exact coordinator release log one drawn plan must produce: [:release
   step-id] pairs in the plan's own order. Release order is plan-driven and
   therefore exact replay evidence; arrival and completion order stay
   diagnostic and are never asserted here."
  [plan]
  (mapv (fn [id] [:release id])
        (case plan
          :receiver-poll-then-http-poll
          [receiver-poll-step-id http-poll-step-id]
          :http-poll-then-receiver-poll
          [http-poll-step-id receiver-poll-step-id])))

;; ---- Worker/case budgets and serial ownership -----------------------------
;; Nominal initial-case ceilings are:
;;
;;   boundary witness   2 * (60000 + 500) = 121000 ms
;;   generated lane    15 * (20000 + 500) = 307500 ms
;;
;; Every process-explorer call owns its child deadline and termination/reap
;; path. Test vars run synchronously: an outer future watchdog followed by
;; System/exit could kill the parent while a worker was still owned, orphaning
;; the child and losing its final artifacts. Whole-process CI bounds remain an
;; infrastructure concern. The 20000 ms warm child deadline matches the
;; http-sqlite lane. A separate 60000 ms boundary deadline covers the first cold
;; child resolving this lane's larger dependency graph (db source + HTTP + TCP
;; + codec) without weakening steady-case bounds.
(def ^:private boundary-case-timeout-ms 60000)
(def ^:private case-timeout-ms 20000)
(def ^:private kill-grace-ms 500)

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- nonnegative-integer? [value]
  (and (integer? value) (not (neg? value))))

(defn- exact-map-keys? [value expected-keys]
  (and (map? value) (= expected-keys (set (keys value)))))

(defn- expected-for
  "Derives the exact ordinary application expectations from the drawn
  payload. Fixed request/entity IDs and first-command/outbox numbering are
  the fixture's default-command semantics for this slice."
  [payload]
  (let [command {:request-id "req-1" :entity-id "entity-a" :payload payload}
        result {:status :committed
                :request-id "req-1"
                :entity-id "entity-a"
                :version 1
                :outbox-id 1}
        row {:outbox-id 1
             :request-id "req-1"
             :entity-id "entity-a"
             :version 1
             :payload payload
             :status :pending}]
    {:command command
     :command-evidence {:value command :result result :emitted [row]}
     :identities {:request-id "req-1"
                  :transaction-id [:outbox/command "req-1"]
                  :outbox-id 1
                  :delivery-id [:outbox/delivery 1]
                  :attempt-id [:outbox/delivery-attempt 1 1]}
     :store-state {:entities {"entity-a" {:version 1 :payload payload}}
                   :request-log {"req-1" {:command command :result result}}
                   :next-outbox-id 2
                   :outbox [row]}
     :message {"type" "outbox_delivery"
               "outbox-id" 1
               "request-id" "req-1"
               "entity-id" "entity-a"
               "version" 1
               "payload" payload
               "attempt" 1}
     :ack {"type" "outbox_delivery_ok" "outbox-id" 1 "attempt" 1}
     :http-response {"type" "command_ok"
                     "status" "committed"
                     "request-id" "req-1"
                     "entity-id" "entity-a"
                     "version" 1
                     "outbox-id" 1}}))

(defn- input-generator
  "Returns a Hegel generator over the scenario input domain. The five draws
   are composed with g/tuple and g/fmap so the whole input shrinks as one
   unit and threads through run-case's :input unchanged. Selection and
   shrinking stay engine-owned: this wrapper performs no selection of its
   own. The payload vector shrinks toward empty; every octet spans the full
   unsigned 0--255 wire domain; the admission plan shrinks toward
   receiver-poll-first."
  []
  (g/fmap
   (fn [[payload stream-capacity pipe-capacity ordinal admission-plan]]
     {:payload payload
      :stream-capacity stream-capacity
      :pipe-capacity pipe-capacity
      :poll-eintr-ordinal ordinal
      :admission-plan admission-plan})
   (g/tuple (g/vector {:max-size max-payload-octets} (g/octet))
            (g/sampled-from stream-capacity-domain)
            (g/sampled-from pipe-capacity-domain)
            (g/sampled-from poll-eintr-domain)
            (g/sampled-from admission-plan-domain))))

(defn- required-environment [name]
  (let [value (System/getenv name)]
    (when-not (and (string? value) (seq value))
      (throw
       (ex-info
        (str "Missing required environment variable " name)
        {:type :jolt.sim.outbox-delivery-hegel-test/missing-environment
         :name name})))
    value))

(defn- process-config []
  (or *process-config*
      (throw
       (ex-info
        "Hegel outbox-delivery tests must be run through -main"
        {:type :jolt.sim.outbox-delivery-hegel-test/not-configured}))))

(defn- require-completed-carrying-input!
  "Wraps jolt.sim.hegel/require-completed! so a non-:completed outcome fails
  fast with the adapter's stable bounded supervisor fields AND the drawn
  scenario input, giving Hegel the exact case data to replay and shrink. The
  underlying :hegel/origin is preserved, and the retained worker
  :artifact-dir coordinates ride along when process-explorer kept the failed
  run's artifacts."
  [outcome input]
  (try
    (sim-hegel/require-completed! outcome)
    (catch :default error
      (let [data (ex-data error)]
        (if (map? data)
          (throw
           (ex-info (ex-message error)
                    (cond-> data
                      (not (contains? data :input))
                      (assoc :input input)
                      (and (map? outcome)
                           (contains? outcome :artifact-dir)
                           (not (contains? data :artifact-dir)))
                      (assoc :artifact-dir (:artifact-dir outcome)))))
          (throw error))))))

(defn- violation
  "Throws the stable typed property failure for one assertion site, carrying
  :hegel/origin, the drawn input, and a small bounded :actual projection of
  the evidence that failed. Hegel replays and shrinks against this ex-data."
  [origin input actual]
  (throw
   (ex-info
    "jolt.sim.outbox-delivery-hegel-test property invariant violated"
    {:hegel/origin origin
     :input input
     :actual actual})))

(defn- assert-case-outcome!
  "Asserts the full invariant set for one completed worker outcome. Throws on
  the first violation and returns nil on success."
  [input outcome]
  (let [completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)
        payload (:payload input)
        expected (expected-for payload)]
    (when-not (map? evidence)
      (violation "jolt.sim.outbox-delivery-hegel-test/evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [{:keys [application http receiver routes sqlite capacity fault
                  admission clean?]} evidence]
      ;; Evidence is the versioned boundary between the fresh worker and this
      ;; parent property. Reject missing, extra, or malformed fields here so a
      ;; truthy value or a host predicate exception cannot masquerade as a
      ;; passing property or erase the stable Hegel origin.
      (when-not (exact-map-keys?
                 evidence
                 #{:application :http :receiver :routes :sqlite :capacity
                   :fault :admission :clean?})
        (violation "jolt.sim.outbox-delivery-hegel-test/evidence-keys"
                   input
                   {:keys (when (map? evidence)
                            (vec (sort-by pr-str (keys evidence))))}))
      (when-not (exact-map-keys?
                 application
                 #{:identities :command :store-state :delivery})
        (violation "jolt.sim.outbox-delivery-hegel-test/application-shape"
                   input
                   {:application application}))
      (when-not (exact-map-keys?
                 (:command application)
                 #{:value :result :emitted})
        (violation "jolt.sim.outbox-delivery-hegel-test/command-shape"
                   input
                   {:command (:command application)}))
      ;; Exact command/store results derived from the drawn input: the command
      ;; observed after the HTTP bencode round trip, its committed transition
      ;; result and emitted row, the semantic identities, and the post-COMMIT
      ;; reloaded store state with the row still :pending.
      (when-not (= (:command-evidence expected) (:command application))
        (violation "jolt.sim.outbox-delivery-hegel-test/command"
                   input
                   {:command (:command application)
                    :expected (:command-evidence expected)}))
      (when-not (= (:identities expected) (:identities application))
        (violation "jolt.sim.outbox-delivery-hegel-test/identities"
                   input
                   {:identities (:identities application)}))
      (when-not (= (:store-state expected) (:store-state application))
        (violation "jolt.sim.outbox-delivery-hegel-test/store-state"
                   input
                   {:store-state (:store-state application)
                    :expected (:store-state expected)}))
      ;; Exact delivery/ack results: one framed bencode delivery of the
      ;; reloaded row and its correlated ack, with an idempotent close.
      (let [delivery (:delivery application)]
        (when-not (exact-map-keys?
                   delivery
                   #{:requests :replies :sent-bytes :received-bytes
                     :close-results})
          (violation "jolt.sim.outbox-delivery-hegel-test/delivery-shape"
                     input
                     {:delivery delivery}))
        (when-not (= [(:message expected)] (:requests delivery))
          (violation "jolt.sim.outbox-delivery-hegel-test/delivery-requests"
                     input
                     {:delivery delivery
                      :expected [(:message expected)]}))
        (when-not (= [(:ack expected)] (:replies delivery))
          (violation "jolt.sim.outbox-delivery-hegel-test/delivery-ack"
                     input
                     {:delivery delivery
                      :expected [(:ack expected)]}))
        (when-not (positive-integer? (:sent-bytes delivery))
          (violation "jolt.sim.outbox-delivery-hegel-test/delivery-sent-bytes"
                     input
                     {:delivery delivery}))
        (when-not (positive-integer? (:received-bytes delivery))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/delivery-received-bytes"
           input
           {:delivery delivery}))
        (when-not (= {:connection [true false]} (:close-results delivery))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/delivery-close-results"
           input
           {:delivery delivery})))
      ;; The receiver observes the same semantic delivery and acknowledges it.
      (when-not (exact-map-keys? receiver #{:requests :server-errors})
        (violation "jolt.sim.outbox-delivery-hegel-test/receiver-shape"
                   input
                   {:receiver receiver}))
      (when-not (= [(:message expected)] (:requests receiver))
        (violation "jolt.sim.outbox-delivery-hegel-test/receiver-requests"
                   input
                   {:receiver receiver
                    :expected [(:message expected)]}))
      (when-not (= [] (:server-errors receiver))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/receiver-server-errors"
         input
         {:receiver receiver}))
      ;; The HTTP response reports the committed command result byte-exactly.
      (when-not (exact-map-keys?
                 http
                 #{:status :content-type :content-length :response
                   :server-errors :close-results})
        (violation "jolt.sim.outbox-delivery-hegel-test/http-shape"
                   input
                   {:http http}))
      (when-not (= 200 (:status http))
        (violation "jolt.sim.outbox-delivery-hegel-test/http-status"
                   input
                   {:http http}))
      (when-not (= "application/x-bencode" (:content-type http))
        (violation "jolt.sim.outbox-delivery-hegel-test/http-content-type"
                   input
                   {:http http}))
      (when-not (= (:http-response expected) (:response http))
        (violation "jolt.sim.outbox-delivery-hegel-test/http-response"
                   input
                   {:http http
                    :expected (:http-response expected)}))
      (when-not (= [] (:server-errors http))
        (violation "jolt.sim.outbox-delivery-hegel-test/http-server-errors"
                   input
                   {:http http}))
      (when-not (= {:connection [true false]} (:close-results http))
        (violation "jolt.sim.outbox-delivery-hegel-test/http-close-results"
                   input
                   {:http http}))
      ;; Effect/route evidence: every intercepted POSIX and SQLite call was
      ;; served by a registered handler; nothing routed to a real socket or
      ;; the real SQLite library, and the fixture actually made native calls
      ;; through the expected symbols.
      (when-not (exact-map-keys?
                 routes #{:count :all-handled? :foreign-symbols})
        (violation "jolt.sim.outbox-delivery-hegel-test/routes-shape"
                   input
                   {:routes routes}))
      (when-not (positive-integer? (:count routes))
        (violation "jolt.sim.outbox-delivery-hegel-test/route-count"
                   input
                   {:routes routes}))
      (when-not (true? (:all-handled? routes))
        (violation "jolt.sim.outbox-delivery-hegel-test/all-handled"
                   input
                   {:routes routes}))
      (let [symbols (:foreign-symbols routes)]
        (when-not (and (vector? symbols) (every? string? symbols))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/foreign-symbols-shape"
           input
           {:foreign-symbols symbols}))
        (when-not (every? (set symbols) required-foreign-symbols)
          (violation "jolt.sim.outbox-delivery-hegel-test/required-routes"
                     input
                     {:missing (vec (sort (remove (set symbols)
                                                  required-foreign-symbols)))})))
      ;; Exact SQLite plan consumption: all 15 delivery plans consumed; no
      ;; live connections or statements leaked past the flow.
      (when-not (= {:plan-index 15 :plan-count 15 :open-dbs 0 :active-stmts 0}
                   sqlite)
        (violation "jolt.sim.outbox-delivery-hegel-test/sqlite-plans"
                   input
                   {:sqlite sqlite}))
      ;; Bounded capacity participation under the drawn bounds: configuration
      ;; echoes, observed occupancy inside the configured bounds, and -- at
      ;; the smoke-proven floor capacity 8 -- at least one capacity-limited
      ;; write, matching the existing smoke gate's evidence. Would-block
      ;; counts depend on host thread timing and are asserted only as
      ;; nonnegative counters.
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
        (violation "jolt.sim.outbox-delivery-hegel-test/capacity-shape"
                   input
                   {:capacity capacity}))
      (when-not (= (:stream-capacity input)
                   (:stream-capacity (:stream capacity)))
        (violation "jolt.sim.outbox-delivery-hegel-test/stream-capacity"
                   input
                   {:capacity capacity}))
      (when-not (= (:pipe-capacity input)
                   (:pipe-capacity (:pipe capacity)))
        (violation "jolt.sim.outbox-delivery-hegel-test/pipe-capacity"
                   input
                   {:capacity capacity}))
      (when-not (<= 1
                    (:max-stream-recv-bytes (:stream capacity))
                    (:stream-capacity input))
        (violation "jolt.sim.outbox-delivery-hegel-test/stream-occupancy"
                   input
                   {:capacity capacity}))
      (when-not (<= 1
                    (:max-pipe-fifo-bytes (:pipe capacity))
                    (:pipe-capacity input))
        (violation "jolt.sim.outbox-delivery-hegel-test/pipe-occupancy"
                   input
                   {:capacity capacity}))
      (when-not (and
                 (nonnegative-integer?
                  (:stream-capacity-limited-writes (:stream capacity)))
                 (nonnegative-integer?
                  (:stream-would-blocks (:stream capacity)))
                 (nonnegative-integer?
                  (:pipe-would-blocks (:pipe capacity))))
        (violation "jolt.sim.outbox-delivery-hegel-test/capacity-counters"
                   input
                   {:capacity capacity}))
      (when (= 8 (:stream-capacity input))
        (when-not (positive-integer?
                   (:stream-capacity-limited-writes (:stream capacity)))
          (violation "jolt.sim.outbox-delivery-hegel-test/stream-partial-write"
                     input
                     {:capacity capacity})))
      ;; Fault evidence: every generated non-nil ordinal must be reachable and
      ;; fire exactly once. Merely drawing an ordinal is not coverage. A nil
      ;; ordinal installs no frontend, so both counts are zero.
      (when-not (exact-map-keys?
                 fault #{:attempts :firings :fired-attempts})
        (violation "jolt.sim.outbox-delivery-hegel-test/fault-shape"
                   input
                   {:fault fault}))
      (when-not (and (nonnegative-integer? (:attempts fault))
                     (nonnegative-integer? (:firings fault))
                     (vector? (:fired-attempts fault)))
        (violation "jolt.sim.outbox-delivery-hegel-test/fault-fields"
                   input
                   {:fault fault}))
      (when-not (#{0 1} (:firings fault))
        (violation "jolt.sim.outbox-delivery-hegel-test/fault-firings"
                   input
                   {:fault fault}))
      (when (and (some? (:poll-eintr-ordinal input))
                 (< (:attempts fault) (:poll-eintr-ordinal input)))
        (violation "jolt.sim.outbox-delivery-hegel-test/fault-ordinal-unreachable"
                   input
                   {:fault fault}))
      (let [requested-ordinal (:poll-eintr-ordinal input)
            expected-firings (if (some? requested-ordinal) 1 0)
            expected-fired-attempts
            (if (= 1 expected-firings)
              [{:attempt-id
                [:jolt.sim.net.posix-fault/poll requested-ordinal]
                :firing
                {:rule-id :outbox-delivery/interrupt-poll
                 :firing-ordinal 1
                 :rule-firing-ordinal 1
                 :match-ordinal requested-ordinal
                 :outcome {:kind :captured-error :errno :eintr}}}]
              [])]
        (when-not (= expected-firings (:firings fault))
          (violation "jolt.sim.outbox-delivery-hegel-test/fault-activation"
                     input
                     {:fault fault :expected-firings expected-firings}))
        (when-not (= expected-fired-attempts (:fired-attempts fault))
          (violation "jolt.sim.outbox-delivery-hegel-test/fault-attempt-identity"
                     input
                     {:fault fault
                      :expected-fired-attempts expected-fired-attempts})))
      ;; First-poll admission evidence: the drawn plan echoed back, the exact
      ;; plan-order coordinator releases, a quiesced completed schedule, and
      ;; bounded per-role poll label counts proving both reactor roles were
      ;; classified from modeled resource facts while unlabeled (client/other)
      ;; polls delegated unchanged. A classification or scheduling regression
      ;; surfaces here as an exact mismatch, never as a weakened invariant.
      (when-not (exact-map-keys?
                 admission
                 #{:plan :release-evidence :coordinator-diagnostics
                   :label-counts :routes})
        (violation "jolt.sim.outbox-delivery-hegel-test/admission-shape"
                   input
                   {:admission admission}))
      (when-not (= (:admission-plan input) (:plan admission))
        (violation "jolt.sim.outbox-delivery-hegel-test/admission-plan"
                   input
                   {:admission admission}))
      (when-not (= (expected-release-evidence (:admission-plan input))
                   (:release-evidence admission))
        (violation "jolt.sim.outbox-delivery-hegel-test/admission-releases"
                   input
                   {:release-evidence (:release-evidence admission)
                    :expected (expected-release-evidence
                               (:admission-plan input))}))
      (let [diagnostics (:coordinator-diagnostics admission)]
        (when-not (exact-map-keys?
                   diagnostics
                   #{:in-flight :due-index :arrival-order :release-order
                     :completion-order :arrived :released :completed
                     :aborted?})
          (violation
           "jolt.sim.outbox-delivery-hegel-test/admission-diagnostics-shape"
           input
           {:diagnostics diagnostics}))
        (when-not (and (zero? (:in-flight diagnostics))
                       (= 2 (:due-index diagnostics))
                       (false? (:aborted? diagnostics))
                       (= #{receiver-poll-step-id http-poll-step-id}
                          (:arrived diagnostics))
                       (= #{receiver-poll-step-id http-poll-step-id}
                          (:released diagnostics))
                       (= #{receiver-poll-step-id http-poll-step-id}
                          (:completed diagnostics)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/admission-diagnostics"
           input
           {:diagnostics diagnostics})))
      (let [label-counts (:label-counts admission)]
        (when-not (exact-map-keys?
                   label-counts
                   #{:role/receiver :role/http :unlabeled})
          (violation
           "jolt.sim.outbox-delivery-hegel-test/admission-label-counts-shape"
           input
           {:label-counts label-counts}))
        (when-not (and (positive-integer? (:role/receiver label-counts))
                       (positive-integer? (:role/http label-counts))
                       (positive-integer? (:unlabeled label-counts)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/admission-label-counts"
           input
           {:label-counts label-counts})))
      (let [poll-routes (:routes admission)]
        (when-not (and (exact-map-keys? poll-routes
                                        #{:count :all-handled?})
                       (positive-integer? (:count poll-routes))
                       (true? (:all-handled? poll-routes)))
          (violation "jolt.sim.outbox-delivery-hegel-test/admission-routes"
                     input
                     {:routes poll-routes})))
      ;; Cleanup: the shared worlds retired every resource; a leaked socket,
      ;; pipe, SQLite handle, or native allocation is a bypass.
      (when-not (= {:memory true :sqlite true :posix true} clean?)
        (violation "jolt.sim.outbox-delivery-hegel-test/world-cleanup"
                   input
                   {:clean? clean?})))))

(defn- check-case!
  "Runs one fresh case for the drawn `input` and asserts the full invariant
  set. Throws on the first violation; returns nil on success. Throwing
  (rather than returning false) is required inside a property passed to a
  custom Hegel runner."
  [input timeout-ms]
  (let [base-config
        (merge (process-config)
               {:scenario scenario-sym
                :timeout-ms timeout-ms
                :kill-grace-ms kill-grace-ms
                ;; Keep even a completed worker's request/result/log directory
                ;; until the parent-side semantic verdict below has passed.
                :retain-completed-artifacts? true})
        outcome (process-explorer/run-case (assoc base-config :input input))
        artifact-dir (:artifact-dir outcome)]
    (try
      ;; process-explorer intentionally bounds diagnostics and the Hegel
      ;; adapter intentionally omits them from its exception data. Emit a
      ;; best-effort bounded projection before that boundary so a gate
      ;; capturing stdout can diagnose ordinary timeouts and interruptions.
      (when-not (= :completed (:status outcome))
        (println
         (pr-str
          {:event :outbox-delivery/non-completed-worker
           :input input
           :outcome (select-keys outcome
                                 [:status :reason :exit :error :diagnostics
                                  :artifact-dir])}))
        (flush))
      (when-not (and (string? artifact-dir) (seq artifact-dir))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/missing-artifact-directory"
         input
         {:status (:status outcome)}))
      (assert-case-outcome! input outcome)
      ;; Only a passing parent verdict authorizes deletion. Any worker,
      ;; protocol, or semantic failure keeps the complete directory.
      (fs/delete-tree artifact-dir)
      (catch :default error
        (let [data (ex-data error)]
          (if (and (string? artifact-dir)
                   (seq artifact-dir)
                   (not= artifact-dir (:artifact-dir data)))
            (throw
             (ex-info (or (ex-message error) (str error))
                      (assoc (or data {}) :artifact-dir artifact-dir)
                      error))
            (throw error)))))))

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
  (let [started (System/nanoTime)
        timeout-ms (if (= :boundary lane)
                     boundary-case-timeout-ms
                     case-timeout-ms)]
    (case-progress! :outbox-delivery/case-start lane ordinal input nil)
    (try
      (check-case! input timeout-ms)
      (case-progress! :outbox-delivery/case-finish lane ordinal input
                      {:elapsed-ms (elapsed-ms started)})
      (catch :default error
        (case-progress!
         :outbox-delivery/case-failure lane ordinal input
         {:elapsed-ms (elapsed-ms started)
          :class (str (class error))
          :message (or (ex-message error) (str error))
          :data (select-keys
                 (or (ex-data error) {})
                 [:hegel/origin :type :status :reason :exit :error
                  :input :actual :artifact-dir])})
        (throw error)))))

;; The deterministic payload-semantics boundary witness: the empty payload
;; and the [0 127 128 255] payload (embedded zero, 0x7f, 0x80, 0xff) at the
;; fixed smoke capacities, the second with one captured first-poll EINTR as
;; the smoke-capacity fault control. The two witnesses carry the two
;; discriminating first-poll admission plans, so both release orders hold
;; even if a future generator edit stops drawing them. These run outside
;; Hegel generation so the boundary semantics hold even if a future generator
;; edit stops drawing them.
(def ^:private boundary-witness-inputs
  [{:payload []
    :stream-capacity 8
    :pipe-capacity 1
    :poll-eintr-ordinal nil
    :admission-plan :receiver-poll-then-http-poll}
   {:payload [0 127 128 255]
    :stream-capacity 8
    :pipe-capacity 1
    :poll-eintr-ordinal 1
    :admission-plan :http-poll-then-receiver-poll}])

(deftest outbox-delivery-payload-boundary-witness
  (doseq [[index input] (map-indexed vector boundary-witness-inputs)]
    (check-case-with-progress! :boundary (inc index) input)))

(deftest hegel-outbox-delivery-holds-across-payload-capacities-eintr-and-plans
  (let [seen-stream-capacities (atom #{})
        seen-pipe-capacities (atom #{})
        seen-poll-ordinals (atom #{})
        seen-admission-plans (atom #{})
        case-ordinal (atom 0)
        result
        (h/run-test!
         {:test-cases 15
          ;; Each case launches a fresh isolated Jolt worker so generation is
          ;; intentionally slower than Hegel's unit-test health threshold.
          :suppress-health-checks [:too-slow]
          ;; Parent-only sampling verified that this fixed seed exercises every
          ;; declared finite capacity, EINTR ordinal, and first-poll admission
          ;; plan in 15 cases. The assertions below keep that coverage from
          ;; silently drifting.
          :seed 3
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [input (h/draw! (input-generator) "scenario-input")]
             (check-case-with-progress!
              :generated (swap! case-ordinal inc) input)
             (swap! seen-stream-capacities conj (:stream-capacity input))
             (swap! seen-pipe-capacities conj (:pipe-capacity input))
             (swap! seen-poll-ordinals conj (:poll-eintr-ordinal input))
             (swap! seen-admission-plans conj (:admission-plan input))
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
    (is (= (set stream-capacity-domain) @seen-stream-capacities)
        (str "Hegel did not exercise the full stream-capacity domain: "
             (pr-str @seen-stream-capacities)))
    (is (= (set pipe-capacity-domain) @seen-pipe-capacities)
        (str "Hegel did not exercise the full pipe-capacity domain: "
             (pr-str @seen-pipe-capacities)))
    (is (= (set poll-eintr-domain) @seen-poll-ordinals)
        (str "Hegel did not exercise the full poll-EINTR domain: "
             (pr-str @seen-poll-ordinals)))
    (is (= (set admission-plan-domain) @seen-admission-plans)
        (str "Hegel did not exercise both first-poll admission plans: "
             (pr-str @seen-admission-plans)))))

(def ^:private serial-test-vars
  [#'outbox-delivery-payload-boundary-witness
   #'hegel-outbox-delivery-holds-across-payload-capacities-eintr-and-plans])

(defn- counter-snapshot []
  {:test (:test @test/counters)
   :pass (test/n-pass)
   :fail (test/n-fail)
   :error (test/n-error)})

(defn- counter-delta [before after]
  (into {}
        (map (fn [key] [key (- (get after key) (get before key))]))
        [:test :pass :fail :error]))

(defn- run-serial-test-var!
  "Runs one test var synchronously. Each process-explorer case owns and reaps
   its worker before this function can advance to the next var."
  [var]
  (let [before (counter-snapshot)]
    (test/test-var var)
    (counter-delta before (counter-snapshot))))

(defn -main [& _]
  (let [bin (required-environment "JOLT_SIM_BIN")
        project-dir (required-environment "JOLT_SIM_PROJECT_DIR")
        result
        (binding [*process-config*
                  {:worker-command [bin "-M:outbox-delivery-explore-worker"]
                   :dir project-dir}]
          (reduce (fn [summary test-var]
                    (merge-with + summary
                                (run-serial-test-var! test-var)))
                  {:test 0 :pass 0 :fail 0 :error 0}
                  serial-test-vars))
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (flush)
    ;; Hegel and the worker launcher may leave non-daemon threads alive. Every
    ;; test var and process-explorer call has returned here, so no worker is
    ;; still owned when the parent exits explicitly.
    (System/exit (if (zero? failures) 0 1))))
