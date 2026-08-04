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
   outside generation. Real/hermetic parity is deliberately not rerun per
   generated case: the dedicated close/reopen integration gate
   (:outbox-delivery-test) proves the canonical command through this same
   ordinary two-connection path in both modes. Generated payload cases remain
   hermetic-only.

   Every generated case draws a Hegel-owned, shrinkable scenario input --
   command payload octet vector, stream capacity, pipe capacity, an optional
   captured poll EINTR activation ordinal, and one of two discriminating
   first-poll admission plans (receiver reactor before HTTP reactor, or the
   reverse) -- and runs the unchanged outbox-delivery-reopen fixture (a clean
   two-connection close/reopen of one selected file-backed SQLite filename)
   once in a fresh sim-enabled Jolt worker through
   jolt.sim.process-explorer/run-case (protocol-v2 :input). The worker loads
   jolt.sim.fixtures.outbox-delivery-scenarios, builds the shared hermetic
   worlds from the drawn input (including the exact reopen-delivery SQLite
   plan world parameterized with the same payload and selecting the bare
   outbox.db filename into the file-image substrate), wraps the composed
   handlers with jolt.sim.ffi-schedule semantic selector steps over
   occurrence 1 of each reactor's poll role, and returns one canonical
   evidence map. Request and entity IDs stay fixed for this slice; extreme
   1--2 byte HTTP fragmentation is explicitly later work.

     Each completed case checks exact command/store/delivery/ack results derived
     from the drawn input, exact reopen SQLite plan consumption, a small
     immutable file-image continuity persistence projection, handler-only
     routing, fault activation identity, bounded capacity participation, exact
     plan-order coordinator releases with quiesced diagnostics and per-role
     poll label counts, and all-world cleanup. A regression failure carries the
     bounded drawn input so Hegel can replay and shrink it: a non-:completed
     outcome is enriched with :input (and the worker :artifact-dir coordinates
     when retained) around jolt.sim.hegel/require-completed!, and every assertion
    site throws a typed ex-info carrying :hegel/origin and the same input.

    The retry lane at the bottom of this namespace is the two-attempt
    at-least-once witness: the unchanged
    jolt.sim.fixtures.outbox-delivery/exercise-outbox-delivery-retry
    application runs in a fresh worker per case under one scoped recv
    ECONNRESET rule (modeled receiver peer port), an optional captured poll
    EINTR, and a fixed two-step ack-send-before-client-recv admission gate.
    Its assertions discriminate receiver attempts exactly [1 2], one exact
    scoped recv-reset firing, one successful correlated ack on attempt 2
    gating one durable delivery marking (attempt 1 never marks), the
    unchanged committed state after the caught failure, exactly one ordinary
    receiver-side write reset while sending the first attempt's ack (the
    bounded FIFO is smaller than the constant ack frame at every drawn
    capacity), exact
    27-statement SQLite plan consumption, unrenumbered poll fault IDs,
    handler-only routing, and complete cleanup. This lane demonstrates
    duplicate delivery under the scoped reset; it does not claim exactly-once
    behavior, receiver idempotence, process restart, close/reopen durability,
    real-kernel ECONNRESET parity, or unbounded liveness.

   Process-isolated like :tcp-bencode-hegel-test: each case spawns a fresh
   worker, and the parent supplies the worker command through JOLT_SIM_BIN and
   JOLT_SIM_PROJECT_DIR."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.fs :as fs]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.hegel :as sim-hegel]
            [jolt.sim.process-explorer :as process-explorer]))

(def ^:dynamic *process-config* nil)
(def ^:dynamic *case-artifact-export-dir* nil)

(def ^:private required-foreign-symbols
  #{"socket" "connect" "accept" "poll" "send" "recv" "close"
    "sqlite3_open" "sqlite3_close_v2"
    "sqlite3_bind_blob64" "sqlite3_column_blob"})

(def ^:private scenario-sym
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-reopen-with-capacities)

(def ^:private retry-scenario-sym
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-retry-recv-reset)

(def ^:private case-outcome-filename "case-outcome.edn")
(def ^:private delivery-monitor-id :outbox/delivery-invariants)
(def ^:private retry-monitor-id :outbox/retry-invariants)

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

;; The modeled outbox row identity mirrored from the ordinary lane's
;; outbox_id-1 primary key, used by the persistence projection's
;; close-time/final image status assertions. The Hegel parent cannot load the
;; scenario namespace, so the literal is mirrored here exactly as the worker's
;; SQLite plan world builds it.
(def ^:private modeled-outbox-key
  [:jolt.sim.sqlite/row
   :outbox/rows
   [{:type :integer :value 1}]])

;; The retry lane's fixed two-step admission gate, mirrored from the scenario
;; namespace's own ::receiver-ack-send and ::delivery-client-recv literals
;; for the exact release-order assertion. The plan is structural (never
;; drawn): the receiver's first ack-send boundary is always admitted before the
;; delivery client's first recv boundary. This is admission order only.
(def ^:private receiver-ack-send-step-id
  :jolt.sim.fixtures.outbox-delivery-scenarios/receiver-ack-send)
(def ^:private delivery-client-recv-step-id
  :jolt.sim.fixtures.outbox-delivery-scenarios/delivery-client-recv)
(def ^:private expected-retry-release-evidence
  [[:release receiver-ack-send-step-id]
   [:release delivery-client-recv-step-id]])

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
;; The retry lane's generated cases run TWO ordinary delivery connections
;; under the same bounded-capacity fragmentation, roughly doubling the
;; delivery cost of the non-retry lane; the 40000 ms child deadline is
;; infrastructure headroom for that added work, not a semantic relaxation.
;; Semantic assertions and the fixture's own 5 s client deadlines are
;; unchanged.
(def ^:private retry-case-timeout-ms 40000)
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
  the fixture's default-command semantics for this slice. The pending side of
  the delivery boundary is the explicit post-COMMIT reload with the row still
  :pending; the delivered side is the final state after the validated ack
  gates one durable marking, plus the exact mark step (without its duplicate
  :state) the public :marking evidence carries."
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
             :status :pending}
        delivered-row (assoc row :status :delivered)]
    {:command command
     :command-evidence {:value command :result result :emitted [row]}
     :identities {:request-id "req-1"
                  :transaction-id [:outbox/command "req-1"]
                  :outbox-id 1
                  :delivery-id [:outbox/delivery 1]
                  :attempt-id [:outbox/delivery-attempt 1 1]}
     :pending-state {:entities {"entity-a" {:version 1 :payload payload}}
                     :request-log {"req-1" {:command command :result result}}
                     :next-outbox-id 2
                     :outbox [row]}
     :marking {:row delivered-row :changed? true}
     :store-state {:entities {"entity-a" {:version 1 :payload payload}}
                   :request-log {"req-1" {:command command :result result}}
                   :next-outbox-id 2
                   :outbox [delivered-row]}
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

(defn- expected-retry-for
  "Derives the exact two-attempt retry expectations from the drawn payload:
   the non-retry command/store semantics plus the attempt-2 delivery message
   and its correlated ack. The attempt-1 message is the non-retry :message;
   only its observed failure (a caught transport error) differs."
  [payload]
  (let [base (expected-for payload)]
    (assoc base
           :message-2 (assoc (:message base) "attempt" 2)
           :ack-2 {"type" "outbox_delivery_ok" "outbox-id" 1 "attempt" 2})))

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

(defn- retry-input-generator
  "Returns a Hegel generator over the retry scenario input domain: payload,
   stream capacity, pipe capacity, and an optional captured poll EINTR
   ordinal. The scoped recv ECONNRESET rule and the two-step admission gate
   are structural to the retry scenario -- deliberately NOT drawn -- so the
   fault dimension that varies is the independent poll EINTR, keeping the
   schedule/fault distribution discriminating rather than unbounded noise.
   The payload vector shrinks toward empty; the finite capacity/ordinal
   domains shrink toward their smoke values."
  []
  (g/fmap
   (fn [[payload stream-capacity pipe-capacity ordinal]]
     {:payload payload
      :stream-capacity stream-capacity
      :pipe-capacity pipe-capacity
      :poll-eintr-ordinal ordinal})
   (g/tuple (g/vector {:max-size max-payload-octets} (g/octet))
            (g/sampled-from stream-capacity-domain)
            (g/sampled-from pipe-capacity-domain)
            (g/sampled-from poll-eintr-domain))))

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
                  admission persistence clean?]} evidence]
      ;; Evidence is the versioned boundary between the fresh worker and this
      ;; parent property. Reject missing, extra, or malformed fields here so a
      ;; truthy value or a host predicate exception cannot masquerade as a
      ;; passing property or erase the stable Hegel origin.
      (when-not (exact-map-keys?
                 evidence
                 #{:application :http :receiver :routes :sqlite :capacity
                   :fault :admission :persistence :clean?})
        (violation "jolt.sim.outbox-delivery-hegel-test/evidence-keys"
                   input
                   {:keys (when (map? evidence)
                            (vec (sort-by pr-str (keys evidence))))}))
      (when-not (exact-map-keys?
                 application
                 #{:identities :command :pending-state :store-state :marking
                   :delivery})
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
      ;; result and emitted row (still :pending evidence), the semantic
      ;; identities, the explicit post-COMMIT/pre-delivery reloaded pending
      ;; state, the exact ack-gated mark step without its duplicate :state,
      ;; and the final delivered store state with zero :pending rows.
      (when-not (= (:command-evidence expected) (:command application))
        (violation "jolt.sim.outbox-delivery-hegel-test/command"
                   input
                   {:command (:command application)
                    :expected (:command-evidence expected)}))
      (when-not (= (:identities expected) (:identities application))
        (violation "jolt.sim.outbox-delivery-hegel-test/identities"
                   input
                   {:identities (:identities application)}))
      (when-not (= (:pending-state expected) (:pending-state application))
        (violation "jolt.sim.outbox-delivery-hegel-test/pending-state"
                   input
                   {:pending-state (:pending-state application)
                    :expected (:pending-state expected)}))
      (when-not (exact-map-keys? (:marking application) #{:row :changed?})
        (violation "jolt.sim.outbox-delivery-hegel-test/marking-shape"
                   input
                   {:marking (:marking application)}))
      (when-not (= (:marking expected) (:marking application))
        (violation "jolt.sim.outbox-delivery-hegel-test/marking"
                   input
                   {:marking (:marking application)
                    :expected (:marking expected)}))
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
      ;; Exact SQLite plan consumption: all 25 reopen-delivery plans consumed
      ;; (connection 0: schema + command transaction + close; connection 1:
      ;; reopen PRAGMA, reload scans, mark-delivered! transaction, final
      ;; reloads, close); no live connections or statements leaked past the
      ;; flow.
      (when-not (= {:plan-index 25 :plan-count 25 :open-dbs 0 :active-stmts 0}
                   sqlite)
        (violation "jolt.sim.outbox-delivery-hegel-test/sqlite-plans"
                   input
                   {:sqlite sqlite}))
      ;; Persistence: the ordinary close/reopen lane publishes a small
      ;; immutable projection from the SQLite world state plus the controlled
      ;; effect trace. Two sequential modeled connections opened and closed
      ;; the selected file-backed filename; no selected filename stays open;
      ;; the committed pending image recorded at the first close after COMMIT
      ;; equals the second close image with only that modeled outbox row's
      ;; status changed back to pending; the second close image equals the
      ;; final published image (delivered). This proves two sequential modeled
      ;; connections and pending-to-delivered file-image continuity in each
      ;; fresh hermetic worker; it does not prove process restart,
      ;; real-SQLite parity in this lane, power-loss/WAL/torn-write behavior,
      ;; multi-connection concurrency, or exactly-once delivery.
      (when-not (exact-map-keys?
                 persistence
                 #{:filename :connection-count :open-count :close-count
                   :first-close-image :second-close-image :final-image
                   :open-images})
        (violation "jolt.sim.outbox-delivery-hegel-test/persistence-shape"
                   input
                   {:persistence persistence}))
      (let [first-image (:first-close-image persistence)
            second-image (:second-close-image persistence)
            final-image (:final-image persistence)]
        (when-not (= "outbox.db" (:filename persistence))
          (violation "jolt.sim.outbox-delivery-hegel-test/persistence-filename"
                     input
                     {:persistence persistence}))
        (when-not (= 2 (:connection-count persistence))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-connection-count"
           input
           {:persistence persistence}))
        (when-not (= 2 (:open-count persistence))
          (violation "jolt.sim.outbox-delivery-hegel-test/persistence-open-count"
                     input
                     {:persistence persistence}))
        (when-not (= 2 (:close-count persistence))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-close-count"
           input
           {:persistence persistence}))
        (when-not (= #{} (:open-images persistence))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-open-images"
           input
           {:persistence persistence}))
        (when-not (= {:type :text :value "pending"}
                     (get-in first-image [modeled-outbox-key "status"]))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-first-close-status"
           input
           {:persistence persistence}))
        (when-not (= {:type :text :value "delivered"}
                     (get-in second-image [modeled-outbox-key "status"]))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-second-close-status"
           input
           {:persistence persistence}))
        (when-not (= {:type :text :value "delivered"}
                     (get-in final-image [modeled-outbox-key "status"]))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-final-status"
           input
           {:persistence persistence}))
        ;; Marking changes exactly one modeled field: the first close image is
        ;; the second close image with only the modeled outbox row's status
        ;; cell changed back to pending. This catches a vacuous reopen whose
        ;; second connection is seeded from plans or loses another table while
        ;; still returning the expected projected application rows.
        (when-not (= first-image
                     (assoc-in second-image
                               [modeled-outbox-key "status"]
                               {:type :text :value "pending"}))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-marking-diff"
           input
           {:persistence persistence}))
        (when-not (= second-image final-image)
          (violation
           "jolt.sim.outbox-delivery-hegel-test/persistence-final-image"
           input
           {:persistence persistence})))
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

(def ^:private diagnostic-depth-limit 4)
(def ^:private diagnostic-collection-limit 16)

(defn- retainable-value
  "Reduces arbitrary error data to bounded ordinary values without realizing
   lazy or otherwise arbitrary sequences. The failure-retention path must
   never hang or replace the primary error merely while explaining it."
  ([value]
   (retainable-value value 0))
  ([value depth]
   (cond
     (nil? value) nil
     (boolean? value) value
     (number? value) value
     (string? value) value
     (char? value) value
     (keyword? value) value
     (symbol? value)
     (if-let [ns (namespace value)]
       (symbol ns (name value))
       (symbol (name value)))
     (>= depth diagnostic-depth-limit) (str (class value))
     (record? value) (str (class value))
     (map? value)
     (into {}
           (map (fn [[entry-key entry-value]]
                  [(retainable-value entry-key (inc depth))
                   (retainable-value entry-value (inc depth))]))
           (take diagnostic-collection-limit value))
     (set? value)
     (into #{}
           (map #(retainable-value % (inc depth)))
           (take diagnostic-collection-limit value))
     (vector? value)
     (mapv #(retainable-value % (inc depth))
           (take diagnostic-collection-limit value))
     (sequential? value) (str (class value))
     :else (str (class value)))))

(defn- retained-error-detail [error]
  (let [data (or (ex-data error) {})]
    {:class (str (class error))
     :message (or (ex-message error) (str error))
     :data (retainable-value
            (select-keys data
                         [:hegel/origin :type :status :reason :exit :error
                          :actual]))}))

(defn- ordinary-process-outcome
  "Projects process-explorer's supervisor result onto the closed ordinary
   outcome accepted by case-outcome/document. PIDs, diagnostics, and artifact
   paths stay in the surrounding forensic directory rather than entering the
   deterministic document."
  [outcome]
  (case (:status outcome)
    :completed {:status :completed
                :result (:result outcome)
                :exit (:exit outcome)}
    :failed {:status :failed
             :error (:error outcome)
             :exit (:exit outcome)}
    :timeout {:status :timeout
              :reason :deadline
              :exit (:exit outcome)}
    :worker-error {:status :worker-error
                   :error (:error outcome)
                   :exit (get outcome :exit)}
    (throw
     (ex-info
      "process explorer returned an unsupported outcome status"
      {:type :jolt.sim.outbox-delivery-hegel-test/unsupported-outcome
       :status (:status outcome)}))))

(defn- monitor-id-for [scenario]
  (cond
    (= scenario scenario-sym) delivery-monitor-id
    (= scenario retry-scenario-sym) retry-monitor-id
    :else
    (throw
     (ex-info
      "outbox Case/Outcome adapter received an unsupported scenario"
      {:type :jolt.sim.outbox-delivery-hegel-test/unsupported-scenario
       :scenario scenario}))))

(defn- monitor-decision [scenario outcome verdict-error]
  (let [completed? (= :completed (:status outcome))]
    {:id (monitor-id-for scenario)
     :status (cond
               (not completed?) :inconclusive
               verdict-error :violation
               :else :pass)
     :detail (cond
               (not completed?)
               (retainable-value
                (select-keys outcome [:status :reason :exit :error]))

               verdict-error
               (retained-error-detail verdict-error)

               :else nil)
     :index nil}))

(defn- case-document-for [scenario input outcome verdict-error]
  (case-outcome/document
   {:scenario scenario
    :mode :hermetic
    :input input
    :schedule (:schedule outcome)}
   (ordinary-process-outcome outcome)
   [(monitor-decision scenario outcome verdict-error)]))

(defn- case-outcome-path [artifact-dir]
  (str (fs/path artifact-dir case-outcome-filename)))

(defn- export-prefix [scenario lane ordinal]
  (str (if (= scenario retry-scenario-sym)
         "outbox-retry-"
         "outbox-delivery-")
       (name lane) "-" ordinal "-"))

(defn- export-artifact-directory!
  "Copies one complete run directory into a fresh, never-overwritten child of
   the configured stable export root. A partial destination is deliberately
   retained if copying fails."
  [export-root scenario lane ordinal artifact-dir]
  (fs/create-dirs export-root)
  (let [destination
        (str
         (fs/create-temp-dir
          {:dir export-root
           :prefix (export-prefix scenario lane ordinal)}))]
    (try
      (fs/copy-tree artifact-dir destination)
      destination
      (catch :default error
        (throw
         (ex-info
          "failed to copy the complete case artifact directory"
          {:type :jolt.sim.outbox-delivery-hegel-test/case-export-failure
           :artifact-dir artifact-dir
           :export-dir destination}
          error))))))

(defn- persist-case-artifacts!
  [scenario lane ordinal input outcome verdict-error artifact-dir export?]
  (let [document (case-document-for scenario input outcome verdict-error)
        document-path (case-outcome-path artifact-dir)]
    (spit document-path (case-outcome/canonical-edn document))
    {:case-outcome-path document-path
     :export-dir
     (when (and export?
                (string? *case-artifact-export-dir*)
                (seq *case-artifact-export-dir*))
       (export-artifact-directory!
        *case-artifact-export-dir* scenario lane ordinal artifact-dir))}))

(defn- rethrow-with-artifact-error!
  [primary artifact-dir artifact-error]
  (if primary
    (throw
     (ex-info
      (or (ex-message primary) (str primary))
      (assoc (or (ex-data primary) {})
             :artifact-dir artifact-dir
             :case-artifact-error (retained-error-detail artifact-error)
             :partial-export-dir (:export-dir (ex-data artifact-error)))
      primary))
    (throw
     (ex-info
      "failed to persist whole-application Case/Outcome artifacts"
      {:type :jolt.sim.outbox-delivery-hegel-test/case-artifact-failure
       :artifact-dir artifact-dir
       :partial-export-dir (:export-dir (ex-data artifact-error))
       :case-artifact-error (retained-error-detail artifact-error)}
      artifact-error))))

(defn- rethrow-with-persisted-artifacts!
  "Rethrows the parent semantic failure while attaching both the transient
   source and stable export coordinates. The original failure remains the
   cause and supplies the message and all existing ex-data."
  [primary artifact-dir export-dir]
  (if (and (string? export-dir) (seq export-dir))
    (throw
     (ex-info
      (or (ex-message primary) (str primary))
      (assoc (or (ex-data primary) {})
             :artifact-dir artifact-dir
             :export-dir export-dir)
      primary))
    (throw primary)))

(defn- check-case!
  "Runs one fresh case for the drawn `input` against `scenario`, records the
   closed Case/Outcome plus parent monitor decision in the worker artifact
   directory, and asserts the full invariant set with `assert-fn` (called as
   (assert-fn input outcome)). Every non-success keeps its original directory;
   when a stable export root is configured it is copied there before rethrow.
   One successful retry boundary witness is also exported for CI and humans.
   The exceptional supervisor paths where child exit was not observed retain
   and report their original directory without reading or copying it while the
   child might still be alive. Throws on the first violation and returns
   export coordinates on success."
  [scenario assert-fn lane ordinal input timeout-ms]
  (let [base-config
        (merge (process-config)
               {:scenario scenario
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
      (let [assertion-error
            (try
              (assert-fn input outcome)
              (when-not (= :completed (:status outcome))
                (ex-info
                 "non-completed worker outcome escaped the parent assertion"
                 {:type
                  :jolt.sim.outbox-delivery-hegel-test/non-completed-accepted
                  :status (:status outcome)}))
              (catch :default error error))
            export?
            (or assertion-error
                (and (= scenario retry-scenario-sym)
                     (= :boundary lane)
                     (= 1 ordinal)))
            persisted
            (try
              (persist-case-artifacts!
               scenario lane ordinal input outcome assertion-error
               artifact-dir export?)
              (catch :default artifact-error
                (rethrow-with-artifact-error!
                 assertion-error artifact-dir artifact-error)))]
        (when assertion-error
          (rethrow-with-persisted-artifacts!
           assertion-error artifact-dir (:export-dir persisted)))
        ;; Only a passing parent verdict after successful document creation
        ;; (and requested export) authorizes deletion of the transient run
        ;; tree. This is not a crash-durability claim.
        (fs/delete-tree artifact-dir)
        persisted)
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
  ([lane ordinal input]
   (check-case-with-progress!
    scenario-sym assert-case-outcome! lane ordinal input
    (if (= :boundary lane)
      boundary-case-timeout-ms
      case-timeout-ms)))
  ([scenario assert-fn lane ordinal input timeout-ms]
   (let [started (System/nanoTime)]
     (case-progress! :outbox-delivery/case-start lane ordinal input nil)
     (try
       (let [persisted
             (check-case! scenario assert-fn lane ordinal input timeout-ms)]
       (case-progress! :outbox-delivery/case-finish lane ordinal input
                       (cond-> {:elapsed-ms (elapsed-ms started)}
                         (:export-dir persisted)
                         (assoc :export-dir (:export-dir persisted)))))
       (catch :default error
         (case-progress!
          :outbox-delivery/case-failure lane ordinal input
          {:elapsed-ms (elapsed-ms started)
           :class (str (class error))
           :message (or (ex-message error) (str error))
           :data (select-keys
                  (or (ex-data error) {})
                  [:hegel/origin :type :status :reason :exit :error
                   :input :actual :artifact-dir :export-dir
                   :partial-export-dir :case-artifact-error])})
         (throw error))))))

;; ---- Case/Outcome artifact adapter controls ------------------------------

(deftest case-outcome-adapter-covers-supervisor-outcomes
  (let [input {:payload [0 255]
               :stream-capacity 8
               :pipe-capacity 1
               :poll-eintr-ordinal nil}
        cases
        [{:process {:status :completed
                    :schedule nil
                    :result {:clean? true}
                    :exit 0}
          :expected {:status :completed
                     :result {:clean? true}
                     :exit 0}
          :monitor :pass}
         {:process {:status :failed
                    :schedule nil
                    :error {:type :scenario-failure}
                    :exit 0}
          :expected {:status :failed
                     :error {:type :scenario-failure}
                     :exit 0}
          :monitor :inconclusive}
         {:process {:status :timeout
                    :schedule nil
                    :reason :deadline
                    :exit 143}
          :expected {:status :timeout :reason :deadline :exit 143}
          :monitor :inconclusive}
         {:process {:status :worker-error
                    :schedule nil
                    :error {:phase :spawn}}
          :expected {:status :worker-error
                     :error {:phase :spawn}
                     :exit nil}
          :monitor :inconclusive}]]
    (doseq [{:keys [process expected monitor]} cases]
      (let [document (case-document-for scenario-sym input process nil)]
        (is (= {:scenario scenario-sym
                :mode :hermetic
                :input input
                :schedule nil}
               (case-outcome/restore-case document)))
        (is (= expected (case-outcome/restore-outcome document)))
        (is (= monitor
               (:status (first (case-outcome/restore-monitors document)))))))))

(deftest semantic-violation-retains-canonical-case-outcome
  (let [artifact-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-case-outcome-violation-"}))
        export-root
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-case-outcome-violation-export-"}))
        outcome {:status :completed
                 :schedule nil
                 :result {:application {:status :unexpected}}
                 :exit 0
                 :artifact-dir artifact-dir}
        caught (atom nil)]
    (spit (str (fs/path artifact-dir "request.edn")) "request")
    (try
      (binding [*process-config* {}
                *case-artifact-export-dir* export-root]
        (with-redefs
          [process-explorer/run-case (fn [_] outcome)]
          (try
            (check-case!
             scenario-sym
             (fn [input _]
               (violation "artifact/control" input {:observed :bad}))
             :generated 7 {:payload []} 1000)
            (catch :default error
              (reset! caught error)))))
      (is (some? @caught))
      (is (= artifact-dir (:artifact-dir (ex-data @caught))))
      (is (fs/exists? artifact-dir))
      (let [exported (:export-dir (ex-data @caught))
            document
            (case-outcome/read-edn
             (slurp (case-outcome-path artifact-dir)))
            exported-document
            (case-outcome/read-edn
             (slurp (str (fs/path exported case-outcome-filename))))
            monitor (first (case-outcome/restore-monitors document))]
        (is (and (string? exported) (seq exported)))
        (is (fs/exists? exported))
        (is (= document exported-document))
        (is (fs/exists? (str (fs/path exported "request.edn"))))
        (is (= :completed (:status (case-outcome/restore-outcome document))))
        (is (= delivery-monitor-id (:id monitor)))
        (is (= :violation (:status monitor)))
        (is (= "artifact/control"
               (get-in monitor [:detail :data :hegel/origin]))))
      (finally
        (when (fs/exists? artifact-dir)
          (fs/delete-tree artifact-dir))
        (when (fs/exists? export-root)
          (fs/delete-tree export-root))))))

(deftest successful-retry-boundary-exports-before-transient-cleanup
  (let [artifact-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-case-outcome-success-"}))
        export-root
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-case-outcome-export-"}))
        outcome {:status :completed
                 :schedule nil
                 :result {:application {:attempts [1 2]}
                          :clean? {:memory true :sqlite true :posix true}}
                 :exit 0
                 :artifact-dir artifact-dir}]
    (spit (str (fs/path artifact-dir "request.edn")) "request")
    (try
      (let [persisted
            (binding [*process-config* {}
                      *case-artifact-export-dir* export-root]
              (with-redefs
                [process-explorer/run-case (fn [_] outcome)]
                (check-case!
                 retry-scenario-sym (fn [_ _] nil)
                 :boundary 1 {:payload []} 1000)))
            exported (:export-dir persisted)
            document
            (case-outcome/read-edn
             (slurp (str (fs/path exported case-outcome-filename))))
            monitor (first (case-outcome/restore-monitors document))]
        (is (and (string? exported) (seq exported)))
        (is (false? (fs/exists? artifact-dir)))
        (is (fs/exists? (str (fs/path exported "request.edn"))))
        (is (= retry-monitor-id (:id monitor)))
        (is (= :pass (:status monitor)))
        (is (nil? (:detail monitor))))
      (finally
        (when (fs/exists? artifact-dir)
          (fs/delete-tree artifact-dir))
        (when (fs/exists? export-root)
          (fs/delete-tree export-root))))))

(deftest retained-error-details-never-realize-lazy-sequences
  (let [realized? (atom false)
        hostile (map (fn [value]
                       (reset! realized? true)
                       value)
                     (range))
        retained (retainable-value {:hostile hostile})]
    (is (false? @realized?))
    (is (string? (:hostile retained)))
    (is (false? @realized?))))

(deftest supervisor-escape-does-not-copy-a-possibly-live-directory
  (let [artifact-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-supervisor-escape-"}))
        export-root
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-supervisor-export-"}))
        caught (atom nil)]
    (spit (str (fs/path artifact-dir "request.edn")) "request")
    (try
      (binding [*process-config* {}
                *case-artifact-export-dir* export-root]
        (with-redefs
          [process-explorer/run-case
           (fn [_]
             (throw
              (ex-info
               "worker exit unobserved"
               {:type :jolt.sim.process-explorer/worker-exit-unobserved
                :artifact-dir artifact-dir})))]
          (try
            (check-case!
             scenario-sym (fn [_ _] nil)
             :generated 9 {:payload []} 1000)
            (catch :default error
              (reset! caught error)))))
      (let [exported (:export-dir (ex-data @caught))]
        (is (some? @caught))
        (is (= artifact-dir (:artifact-dir (ex-data @caught))))
        (is (fs/exists? artifact-dir))
        (is (nil? exported))
        ;; The worker may still be alive and mutating this tree. Preserve and
        ;; report the original path, but neither read it nor create a snapshot
        ;; that could race or grow without bound.
        (is (empty? (vec (fs/list-dir export-root)))))
      (finally
        (when (fs/exists? artifact-dir)
          (fs/delete-tree artifact-dir))
        (when (fs/exists? export-root)
          (fs/delete-tree export-root))))))

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

;; ---- Two-attempt retry lane (scoped recv ECONNRESET) ----------------------

(defn- assert-retry-case-outcome!
  "Asserts the full two-attempt retry invariant set for one completed worker
   outcome: the unchanged committed command/store semantics, the caught
   attempt-1 reset and unchanged post-failure state (attempt 1 never marks),
   the successful attempt-2 correlated ack gating one durable marking, the
   final delivered store state, receiver attempts exactly [1 2], one exact
   scoped recv-reset firing beside unrenumbered poll fault IDs, exact
   27-statement SQLite plan consumption, exact plan-order admission
   releases, and complete cleanup. Throws on the first violation."
  [input outcome]
  (let [completed (require-completed-carrying-input! outcome input)
        evidence (:result completed)
        payload (:payload input)
        expected (expected-retry-for payload)]
    (when-not (map? evidence)
      (violation "jolt.sim.outbox-delivery-hegel-test/retry-evidence-shape"
                 input
                 {:evidence-class (str (class evidence))}))
    (let [{:keys [application http receiver routes sqlite capacity fault
                  schedule clean?]} evidence]
      (when-not (exact-map-keys?
                 evidence
                 #{:application :http :receiver :routes :sqlite :capacity
                   :fault :schedule :clean?})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-evidence-keys"
                   input
                   {:keys (when (map? evidence)
                            (vec (sort-by pr-str (keys evidence))))}))
      ;; Application: the committed command evidence, the original
      ;; post-COMMIT pending state, the exact mark step, and the final
      ;; delivered store state are byte-identical to the non-retry lane;
      ;; :retry carries the two-attempt witness.
      (when-not (exact-map-keys?
                 application
                 #{:identities :command :pending-state :store-state :marking
                   :retry})
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-application-shape"
         input
         {:application application}))
      (when-not (exact-map-keys?
                 (:command application)
                 #{:value :result :emitted})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-command-shape"
                   input
                   {:command (:command application)}))
      (when-not (= (:command-evidence expected) (:command application))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-command"
                   input
                   {:command (:command application)
                    :expected (:command-evidence expected)}))
      (when-not (= (:identities expected) (:identities application))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-identities"
                   input
                   {:identities (:identities application)}))
      (when-not (= (:pending-state expected) (:pending-state application))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-pending-state"
                   input
                   {:pending-state (:pending-state application)
                    :expected (:pending-state expected)}))
      (when-not (exact-map-keys? (:marking application) #{:row :changed?})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-marking-shape"
                   input
                   {:marking (:marking application)}))
      (when-not (= (:marking expected) (:marking application))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-marking"
                   input
                   {:marking (:marking application)
                    :expected (:marking expected)}))
      (when-not (= (:store-state expected) (:store-state application))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-store-state"
                   input
                   {:store-state (:store-state application)
                    :expected (:store-state expected)}))
      ;; The two-attempt witness: a caught ordinary reset on attempt 1, the
      ;; committed state proven unchanged through the same still-open
      ;; connection, and a successful attempt-2 exchange.
      (let [retry (:retry application)]
        (when-not (exact-map-keys?
                   retry
                   #{:attempt-1 :state-unchanged-after-failure? :delivery})
          (violation "jolt.sim.outbox-delivery-hegel-test/retry-shape"
                     input
                     {:retry retry}))
        (let [attempt-1 (:attempt-1 retry)]
          (when-not (exact-map-keys? attempt-1 #{:error :cleanup-errors})
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-attempt-1-shape"
             input
             {:attempt-1 attempt-1}))
          (let [error (:error attempt-1)]
            (when-not (and (map? error)
                           (string? (:class error))
                           (string? (:message error))
                           (map? (:data error)))
              (violation
               "jolt.sim.outbox-delivery-hegel-test/retry-attempt-1-error"
               input
               {:attempt-1 attempt-1}))
            ;; The caught delivery failure is the injected connection reset,
            ;; classified by the ordinary jolt.net error seam -- never a
            ;; timeout, EOF, or framing failure.
            (when-not (= ":connection-reset"
                         (get (:data error) ":jolt.net/kind"))
              (violation
               "jolt.sim.outbox-delivery-hegel-test/retry-attempt-1-kind"
               input
               {:attempt-1 attempt-1}))
            (when-not (= ":read" (get (:data error) ":jolt.net/op"))
              (violation
               "jolt.sim.outbox-delivery-hegel-test/retry-attempt-1-operation"
               input
               {:attempt-1 attempt-1})))
          (when-not (= [] (:cleanup-errors attempt-1))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-attempt-1-cleanup"
             input
             {:attempt-1 attempt-1})))
        (when-not (true? (:state-unchanged-after-failure? retry))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-state-unchanged"
           input
           {:retry retry}))
        (let [delivery (:delivery retry)]
          (when-not (exact-map-keys?
                     delivery
                     #{:requests :replies :sent-bytes :received-bytes
                       :close-results})
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-delivery-shape"
             input
             {:delivery delivery}))
          (when-not (= [(:message-2 expected)] (:requests delivery))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-delivery-requests"
             input
             {:delivery delivery
              :expected [(:message-2 expected)]}))
          (when-not (= [(:ack-2 expected)] (:replies delivery))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-delivery-ack"
             input
             {:delivery delivery
              :expected [(:ack-2 expected)]}))
          (when-not (positive-integer? (:sent-bytes delivery))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-delivery-sent-bytes"
             input
             {:delivery delivery}))
          (when-not (positive-integer? (:received-bytes delivery))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-received-bytes"
             input
             {:delivery delivery}))
          (when-not (= {:connection [true false]} (:close-results delivery))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-close-results"
             input
             {:delivery delivery}))))
      ;; The receiver records exactly attempts [1 2] in order. Its
      ;; server-errors carry the ordinary consequence of the injected reset
      ;; at the drawn bounded capacities: every supported capacity (8/16/32)
      ;; is smaller than the constant 59-octet ack frame, so the frame cannot
      ;; be completely written without a client read. The client's only
      ;; attempt-1 recv is faulted and it closes without consuming modeled
      ;; bytes. Either the first ack send (if reset wins handler execution) or
      ;; a later send (if a prefix was written first) therefore surfaces one
      ;; ordinary EPIPE classified by jolt.net as a write-side reset. The
      ;; admission evidence proves receiver processing reached the ack-send
      ;; boundary first; it does not identify the failing write.
      (when-not (exact-map-keys? receiver #{:requests :server-errors})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-receiver-shape"
                   input
                   {:receiver receiver}))
      (when-not (= [(:message expected) (:message-2 expected)]
                   (:requests receiver))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-receiver-requests"
         input
         {:receiver receiver
          :expected [(:message expected) (:message-2 expected)]}))
      (let [server-errors (:server-errors receiver)]
        (when-not (and (vector? server-errors)
                       (= 1 (count server-errors)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-receiver-errors"
           input
           {:receiver receiver}))
        (let [error (first server-errors)]
          (when-not (and (map? error)
                         (string? (:class error))
                         (string? (:message error))
                         (= ":write" (get (:data error) ":jolt.net/op"))
                         (= ":connection-reset"
                            (get (:data error) ":jolt.net/kind")))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-receiver-epipe"
             input
             {:receiver receiver}))))
      ;; HTTP, routes, capacity, and cleanup checks mirror the non-retry
      ;; lane exactly: the unchanged application behaves identically outside
      ;; the retry window.
      (when-not (exact-map-keys?
                 http
                 #{:status :content-type :content-length :response
                   :server-errors :close-results})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-http-shape"
                   input
                   {:http http}))
      (when-not (= 200 (:status http))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-http-status"
                   input
                   {:http http}))
      (when-not (= "application/x-bencode" (:content-type http))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-http-content-type"
         input
         {:http http}))
      (when-not (= (:http-response expected) (:response http))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-http-response"
                   input
                   {:http http
                    :expected (:http-response expected)}))
      (when-not (= [] (:server-errors http))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-http-errors"
                   input
                   {:http http}))
      (when-not (= {:connection [true false]} (:close-results http))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-http-close-results"
         input
         {:http http}))
      (when-not (exact-map-keys?
                 routes #{:count :all-handled? :foreign-symbols})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-routes-shape"
                   input
                   {:routes routes}))
      (when-not (positive-integer? (:count routes))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-route-count"
                   input
                   {:routes routes}))
      (when-not (true? (:all-handled? routes))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-all-handled"
                   input
                   {:routes routes}))
      (let [symbols (:foreign-symbols routes)]
        (when-not (and (vector? symbols) (every? string? symbols))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-symbols-shape"
           input
           {:foreign-symbols symbols}))
        (when-not (every? (set symbols) required-foreign-symbols)
          (violation "jolt.sim.outbox-delivery-hegel-test/retry-routes"
                     input
                     {:missing (vec (sort (remove (set symbols)
                                                  required-foreign-symbols)))})))
      ;; Exact SQLite plan consumption: the 27-statement retry plan (schema,
      ;; one committed command, two post-COMMIT load-states over the same
      ;; still-open connection, the mark-delivered! transaction after the
      ;; validated attempt-2 ack, and the final reload scans), fully consumed
      ;; with no live handles.
      (when-not (= {:plan-index 27 :plan-count 27 :open-dbs 0
                    :active-stmts 0}
                   sqlite)
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-sqlite-plans"
                   input
                   {:sqlite sqlite}))
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
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-capacity-shape"
                   input
                   {:capacity capacity}))
      (when-not (= (:stream-capacity input)
                   (:stream-capacity (:stream capacity)))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-stream-capacity"
         input
         {:capacity capacity}))
      (when-not (= (:pipe-capacity input)
                   (:pipe-capacity (:pipe capacity)))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-pipe-capacity"
                   input
                   {:capacity capacity}))
      (when-not (<= 1
                    (:max-stream-recv-bytes (:stream capacity))
                    (:stream-capacity input))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-stream-occupancy"
         input
         {:capacity capacity}))
      (when-not (<= 1
                    (:max-pipe-fifo-bytes (:pipe capacity))
                    (:pipe-capacity input))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-pipe-occupancy"
                   input
                   {:capacity capacity}))
      (when-not (and
                 (nonnegative-integer?
                  (:stream-capacity-limited-writes (:stream capacity)))
                 (nonnegative-integer?
                  (:stream-would-blocks (:stream capacity)))
                 (nonnegative-integer?
                  (:pipe-would-blocks (:pipe capacity))))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-capacity-counters"
         input
         {:capacity capacity}))
      (when (= 8 (:stream-capacity input))
        (when-not (positive-integer?
                   (:stream-capacity-limited-writes (:stream capacity)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-partial-write"
           input
           {:capacity capacity})))
      ;; Fault evidence: exactly one scoped recv-reset firing with the exact
      ;; rule/ordinal/outcome identity and the fired attempt-id scoped by the
      ;; echoed modeled receiver peer port; poll attempt IDs are unaffected
      ;; by recv interposition, so a drawn EINTR keeps its [::poll N]
      ;; identity beside the [::recv K fd P] firing.
      (when-not (exact-map-keys?
                 fault
                 #{:attempts :recv-attempts :firings :fired-attempts
                   :recv-reset-peer-port})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-fault-shape"
                   input
                   {:fault fault}))
      (when-not (and (nonnegative-integer? (:attempts fault))
                     (positive-integer? (:recv-attempts fault))
                     (nonnegative-integer? (:firings fault))
                     (vector? (:fired-attempts fault))
                     (positive-integer? (:recv-reset-peer-port fault)))
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-fault-fields"
                   input
                   {:fault fault}))
      (let [requested-ordinal (:poll-eintr-ordinal input)
            expected-firings (if (some? requested-ordinal) 2 1)]
        (when-not (= expected-firings (:firings fault))
          (violation "jolt.sim.outbox-delivery-hegel-test/retry-firings"
                     input
                     {:fault fault :expected-firings expected-firings}))
        (when (and (some? requested-ordinal)
                   (< (:attempts fault) requested-ordinal))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-ordinal-unreachable"
           input
           {:fault fault})))
      (let [peer-port (:recv-reset-peer-port fault)
            recv-tag :jolt.sim.net.posix-fault/recv
            poll-tag :jolt.sim.net.posix-fault/poll
            fired (:fired-attempts fault)
            recv-fired
            (filterv #(= recv-tag (nth (:attempt-id %) 0 nil)) fired)
            poll-fired
            (filterv #(= poll-tag (nth (:attempt-id %) 0 nil)) fired)]
        (when-not (= 1 (count recv-fired))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-recv-firing-count"
           input
           {:fired-attempts fired}))
        (let [{:keys [attempt-id firing]} (first recv-fired)]
          (when-not (and (vector? attempt-id)
                         (= 4 (count attempt-id))
                         (= recv-tag (nth attempt-id 0))
                         (positive-integer? (nth attempt-id 1))
                         (nonnegative-integer? (nth attempt-id 2))
                         (= peer-port (nth attempt-id 3)))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-recv-attempt-id"
             input
             {:recv-fired recv-fired :peer-port peer-port}))
          (when-not (and (map? firing)
                         (= {:rule-id :outbox-delivery/reset-delivery-recv
                             :rule-firing-ordinal 1
                             :match-ordinal 1
                             :outcome {:kind :captured-error
                                       :errno :econnreset}}
                            (dissoc firing :firing-ordinal))
                         (positive-integer? (:firing-ordinal firing)))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-recv-firing"
             input
             {:recv-fired recv-fired})))
        (let [ordinal (:poll-eintr-ordinal input)]
          (when-not (= (if (some? ordinal) 1 0) (count poll-fired))
            (violation
             "jolt.sim.outbox-delivery-hegel-test/retry-poll-firing-count"
             input
             {:fired-attempts fired :poll-eintr-ordinal ordinal}))
          (when (some? ordinal)
            (let [{:keys [attempt-id firing]} (first poll-fired)]
              ;; Unrenumbered poll identity: the drawn ordinal on the
              ;; historical [::poll N] attempt-id form.
              (when-not (= [poll-tag ordinal] attempt-id)
                (violation
                 "jolt.sim.outbox-delivery-hegel-test/retry-poll-attempt-id"
                 input
                 {:poll-fired poll-fired :poll-eintr-ordinal ordinal}))
              (when-not (and (map? firing)
                             (= {:rule-id :outbox-delivery/interrupt-poll
                                 :rule-firing-ordinal 1
                                 :match-ordinal ordinal
                                 :outcome {:kind :captured-error
                                           :errno :eintr}}
                                (dissoc firing :firing-ordinal))
                             (positive-integer? (:firing-ordinal firing)))
                (violation
                 "jolt.sim.outbox-delivery-hegel-test/retry-poll-firing"
                 input
                 {:poll-fired poll-fired
                  :poll-eintr-ordinal ordinal}))))))
      ;; Retry admission gate: the exact plan-order boundary releases (ack send
      ;; admitted before client recv), a quiesced completed schedule, and bounded
      ;; per-role send/recv label counts proving both roles classified from
      ;; modeled resource facts while unlabeled calls delegated.
      (when-not (exact-map-keys?
                 schedule
                 #{:release-evidence :coordinator-diagnostics :label-counts
                   :routes})
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-schedule-shape"
                   input
                   {:schedule schedule}))
      (when-not (= expected-retry-release-evidence
                   (:release-evidence schedule))
        (violation
         "jolt.sim.outbox-delivery-hegel-test/retry-schedule-releases"
         input
         {:release-evidence (:release-evidence schedule)
          :expected expected-retry-release-evidence}))
      (let [diagnostics (:coordinator-diagnostics schedule)]
        (when-not (exact-map-keys?
                   diagnostics
                   #{:in-flight :due-index :arrival-order :release-order
                     :completion-order :arrived :released :completed
                     :aborted?})
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-diagnostics-shape"
           input
           {:diagnostics diagnostics}))
        (when-not (and (zero? (:in-flight diagnostics))
                       (= 2 (:due-index diagnostics))
                       (false? (:aborted? diagnostics))
                       (= #{receiver-ack-send-step-id
                            delivery-client-recv-step-id}
                          (:arrived diagnostics))
                       (= #{receiver-ack-send-step-id
                            delivery-client-recv-step-id}
                          (:released diagnostics))
                       (= #{receiver-ack-send-step-id
                            delivery-client-recv-step-id}
                          (:completed diagnostics)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-diagnostics"
           input
           {:diagnostics diagnostics})))
      (let [label-counts (:label-counts schedule)]
        (when-not (exact-map-keys?
                   label-counts
                   #{:role/receiver-ack-send :role/delivery-client-recv
                     :unlabeled})
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-label-counts-shape"
           input
           {:label-counts label-counts}))
        (when-not (and (positive-integer?
                        (:role/receiver-ack-send label-counts))
                       (positive-integer?
                        (:role/delivery-client-recv label-counts))
                       (positive-integer? (:unlabeled label-counts)))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-label-counts"
           input
           {:label-counts label-counts})))
      (let [schedule-routes (:routes schedule)]
        (when-not (and (exact-map-keys? schedule-routes #{:send :recv})
                       (exact-map-keys? (:send schedule-routes)
                                        #{:count :all-handled?})
                       (exact-map-keys? (:recv schedule-routes)
                                        #{:count :all-handled?})
                       (positive-integer? (:count (:send schedule-routes)))
                       (true? (:all-handled? (:send schedule-routes)))
                       (positive-integer? (:count (:recv schedule-routes)))
                       (true? (:all-handled? (:recv schedule-routes))))
          (violation
           "jolt.sim.outbox-delivery-hegel-test/retry-schedule-routes"
           input
           {:routes schedule-routes})))
      (when-not (= {:memory true :sqlite true :posix true} clean?)
        (violation "jolt.sim.outbox-delivery-hegel-test/retry-world-cleanup"
                   input
                   {:clean? clean?})))))

;; The deterministic retry boundary witness: the empty payload and the
;; [0 127 128 255] payload (embedded zero, 0x7f, 0x80, 0xff) at the fixed
;; smoke capacities, the second with one captured first-poll EINTR beside
;; the structural recv reset as the smoke-capacity mixed-fault control. Both
;; run outside Hegel generation so the retry boundary semantics hold even if
;; a future generator edit stops drawing them.
(def ^:private retry-boundary-witness-inputs
  [{:payload []
    :stream-capacity 8
    :pipe-capacity 1
    :poll-eintr-ordinal nil}
   {:payload [0 127 128 255]
    :stream-capacity 8
    :pipe-capacity 1
    :poll-eintr-ordinal 1}])

(deftest outbox-delivery-retry-boundary-witness
  (doseq [[index input] (map-indexed vector retry-boundary-witness-inputs)]
    (check-case-with-progress!
     retry-scenario-sym assert-retry-case-outcome!
     :boundary (inc index) input boundary-case-timeout-ms)))

(deftest hegel-outbox-delivery-retry-holds-across-payload-capacities-and-eintr
  (let [seen-stream-capacities (atom #{})
        seen-pipe-capacities (atom #{})
        seen-poll-ordinals (atom #{})
        case-ordinal (atom 0)
        result
        (h/run-test!
         {:test-cases 15
          ;; Each case launches a fresh isolated Jolt worker so generation is
          ;; intentionally slower than Hegel's unit-test health threshold.
          :suppress-health-checks [:too-slow]
          ;; Parent-only sampling verified that this fixed seed exercises
          ;; every declared finite capacity and EINTR ordinal in 15 cases.
          ;; The assertions below keep that coverage from silently drifting.
          :seed 3
          :database ""
          :report-multiple-failures? false
          :verbosity :quiet}
         (fn [_]
           (let [input (h/draw! (retry-input-generator)
                                "retry-scenario-input")]
             (check-case-with-progress!
              retry-scenario-sym assert-retry-case-outcome!
              :generated (swap! case-ordinal inc) input
              retry-case-timeout-ms)
             (swap! seen-stream-capacities conj (:stream-capacity input))
             (swap! seen-pipe-capacities conj (:pipe-capacity input))
             (swap! seen-poll-ordinals conj (:poll-eintr-ordinal input))
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
             (pr-str @seen-poll-ordinals)))))

(def ^:private serial-test-vars
  [#'case-outcome-adapter-covers-supervisor-outcomes
   #'semantic-violation-retains-canonical-case-outcome
   #'successful-retry-boundary-exports-before-transient-cleanup
   #'retained-error-details-never-realize-lazy-sequences
   #'supervisor-escape-does-not-copy-a-possibly-live-directory
   #'outbox-delivery-payload-boundary-witness
   #'hegel-outbox-delivery-holds-across-payload-capacities-eintr-and-plans
   #'outbox-delivery-retry-boundary-witness
   #'hegel-outbox-delivery-retry-holds-across-payload-capacities-and-eintr])

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
        export-dir (System/getenv "JOLT_SIM_CASE_ARTIFACT_DIR")
        temp-dir (System/getenv "JOLT_SIM_CASE_TEMP_DIR")
        export-dir (when (and (string? export-dir) (seq export-dir))
                     export-dir)
        temp-dir (when (and (string? temp-dir) (seq temp-dir)) temp-dir)
        _ (doseq [directory [export-dir temp-dir]
                  :when directory]
            ;; Create both roots before the first worker starts. A hard parent
            ;; failure can then leave the original request/result/log tree in
            ;; the configured temp root even when no stable copy or
            ;; Case/Outcome document was completed.
            (fs/create-dirs directory))
        result
        (binding [*process-config*
                  (cond->
                   {:worker-command [bin "-M:outbox-delivery-explore-worker"]
                    :dir project-dir}
                    temp-dir (assoc :temp-dir temp-dir))
                  *case-artifact-export-dir* export-dir]
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
