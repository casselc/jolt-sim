(ns jolt.sim.outbox-json-delivery-integration-test
  "Focused real/hermetic parity gate for the ordinary whole-application
   post-COMMIT outbox delivery witness whose HTTP command phase is the routed
   jolt.example.outbox.http-json facade. One fresh JSON commit runs through
   the real and hermetic jolt-http server infrastructure; the one accepted
   durable SQLite outbox row then flows through the unchanged framed
   TCP/bencode delivery and ack-gated marking lane. The same gate also runs
   fixed two-request exact-replay and conflict workloads through real and
   hermetic HTTP/SQLite/TCP paths; the process-isolated Hegel campaign varies
   payload octets and route-safe identifiers."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-json-delivery :as fixture]
            [jolt.sim.fixtures.outbox-json-delivery-live-scenarios :as live-scenarios]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected-row
  {:outbox-id 1
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :payload [0 127 128 255]
   :status :pending})

(def ^:private expected-result
  {:status :committed
   :request-id "req-1"
   :entity-id "entity-a"
   :version 1
   :outbox-id 1})

(def ^:private expected-state
  {:entities {"entity-a" {:version 1 :payload [0 127 128 255]}}
   :request-log
   {"req-1" {:command fixture/default-command
             :result expected-result}}
   :next-outbox-id 2
   :outbox [expected-row]})

;; The delivered side of the boundary: the same row and state after the
;; validated ack authorizes the guarded pending -> delivered marking.
(def ^:private expected-delivered-row
  (assoc expected-row :status :delivered))

(def ^:private expected-delivered-state
  (assoc expected-state :outbox [expected-delivered-row]))

(def ^:private expected-marking
  {:row expected-delivered-row :changed? true})

(def ^:private expected-identities
  {:request-id "req-1"
   :transaction-id [:outbox/command "req-1"]
   :outbox-id 1
   :delivery-id [:outbox/delivery 1]
   :attempt-id [:outbox/delivery-attempt 1 1]})

(def ^:private expected-message
  {"type" "outbox_delivery"
   "outbox-id" 1
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "payload" [0 127 128 255]
   "attempt" 1})

(def ^:private expected-ack
  {"type" "outbox_delivery_ok"
   "outbox-id" 1
   "attempt" 1})

(def ^:private expected-http-response
  {"status" "committed"
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "outbox-id" 1})

(deftest persistent-live-lifecycle-runs-unchanged-under-hermetic-worlds
  (doseq [[ack-outcome expected-status expected-plans]
          [[:accepted :delivered 42]
           [:hostile :pending 36]]]
    (let [evidence
          (live-scenarios/exercise-live-lifecycle
           {:payload [0 127 128 255] :ack-outcome ack-outcome})
          application (:application evidence)]
      (testing (str "persistent lifecycle with " (name ack-outcome) " ack")
        (is (= [:open :empty]
               ((juxt :status :phase) (:initial application))))
        (is (= 201 (get-in application [:submission :status])))
        (is (= :pending
               (get-in application [:pending :store-state :outbox 0 :status])))
        (is (= expected-status
               (get-in application [:resulting :store-state :outbox 0 :status])))
        (is (= [true false] (:stop-results application)))
        (is (= :stopped (get-in application [:stopped :status])))
        (is (= expected-plans (get-in evidence [:sqlite :plan-index])))
        (is (= expected-plans (get-in evidence [:sqlite :plan-count])))
        (is (true? (get-in evidence [:routes :all-handled?])))
        (is (= {:memory true :sqlite true :posix true} (:clean? evidence)))
        (if (= :accepted ack-outcome)
          (is (= :delivered
                 (get-in application [:delivery :value :status])))
          (is (= :ack-mismatch
                 (get-in application [:delivery :error :reason]))))))))

(deftest json-command-seam-rejects-untransmitted-fields
  (let [request-bytes-for (:request-bytes-for fixture/json-http-seam)
        command (assoc fixture/default-command :ignored true)
        caught (try
                 (request-bytes-for command "127.0.0.1" 1)
                 nil
                 (catch :default error error))]
    (is (= :jolt.sim.fixtures.outbox-json-delivery/invalid-flow
           (:type (ex-data caught))))
    (is (= :invalid-command (:reason (ex-data caught))))))

(deftest ordinary-json-http-commits-outbox-then-delivers-over-framed-tcp
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-outbox-json-delivery))
        mem (memory/world)
        sqlite-world (sqlite/world mem (plans/json-delivery-statement-plans))
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers
          :drain-timeout-ms 10000}
         fixture/exercise-outbox-json-delivery)
        result (:result controlled)
        app (:application result)
        delivery (:delivery app)]
    (testing "the unchanged ordinary application has exact real/hermetic parity"
      (when-not *sim-only?*
        (is (= real-result result))))

    (testing "the JSON HTTP command exposes exactly one post-COMMIT pending row"
      (is (= expected-identities (:identities app)))
      (is (= {:value fixture/default-command
              :result expected-result
              :emitted [expected-row]}
             (:command app)))
      ;; The explicit post-COMMIT/pre-delivery reload: the emitted command row
      ;; remains :pending evidence on this side of the delivery boundary.
      (is (= expected-state (:pending-state app)))
      (is (= :pending (get-in app [:pending-state :outbox 0 :status]))))

    (testing "the validated ack gates one durable delivery marking"
      ;; Exactly the mark step without its duplicate :state.
      (is (= expected-marking (:marking app)))
      ;; The final delivered state equals the reload after mark-delivered!:
      ;; the target row is :delivered and no :pending rows remain.
      (is (= expected-delivered-state (:store-state app)))
      (is (= :delivered (get-in app [:store-state :outbox 0 :status])))
      (is (empty? (filter #(= :pending (:status %))
                          (get-in app [:store-state :outbox])))))

    (testing "a later phase delivers the reloaded row in one framed bencode attempt"
      (is (= [expected-message] (:requests delivery)))
      (is (= [expected-ack] (:replies delivery)))
      (is (pos? (:sent-bytes delivery)))
      (is (pos? (:received-bytes delivery)))
      (is (= {:connection [true false]} (:close-results delivery))))

    (testing "the receiver observes the same semantic delivery and acknowledges it"
      (is (= [expected-message] (get-in result [:receiver :requests])))
      (is (empty? (get-in result [:receiver :server-errors]))))

    (testing "the HTTP response reports the committed command result exactly"
      (is (= 201 (get-in result [:http :status])))
      (is (= "application/json"
             (get-in result [:http :content-type])))
      ;; decode-json-exact rejected trailing data before this value was
      ;; evidence, so map equality here is the exact response decode.
      (is (= expected-http-response (get-in result [:http :response])))
      (is (empty? (get-in result [:http :server-errors])))
      (is (= {:connection [true false]}
             (get-in result [:http :close-results]))))

    (testing "all 24 SQLite plans and all native effects stay inside the model"
      (is (= {:plan-index 24
              :plan-count 24
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (every? #(= :handler (:route %)) (:effect-trace controlled))))

    (testing "finite model capacities exercise bounded partial progress"
      (let [stream (posix/capacity-summary posix-world)
            pipe (posix/pipe-capacity-summary posix-world)]
        ;; The stream bound is smaller than the native progress ceiling, so
        ;; capacity itself must split writes without coupling this smoke gate to
        ;; the extreme one- and two-byte fragmentation reserved for a future
        ;; fragmentation lane.
        (is (= 8 (:stream-capacity stream)))
        (is (pos? (:stream-capacity-limited-writes stream)))
        (is (= 8 (:max-stream-recv-bytes stream)))
        ;; Would-block counts depend on host thread timing. Occupancy proves
        ;; that the finite self-pipe bound participated without asserting an
        ;; exact schedule-sensitive retry count.
        (is (= 1 (:pipe-capacity pipe)))
        (is (= 1 (:max-pipe-fifo-bytes pipe)))))

    (testing "the shared native worlds quiesce cleanly"
      (is (true? (memory/clean? mem)))
      (is (true? (sqlite/clean? sqlite-world)))
      (is (true? (posix/clean? posix-world))))))

(defn- run-two-request-workload
  [workload]
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-outbox-json-replay-conflict workload))
        mem (memory/world)
        sqlite-world
        (sqlite/world mem (plans/json-replay-conflict-statement-plans workload))
        posix-world (posix/world mem (net/target-descriptor)
                                 {:progress-limit 64
                                  :stream-capacity 8
                                  :pipe-capacity 1})
        handlers
        (hp/compose
         (hp/pack :jolt.sim/memory (memory/handlers mem))
         (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
         (hp/pack :jolt.sim/posix (posix/foreign-handlers posix-world)))
        controlled
        (runtime/run-controlled
         {:ffi-handlers handlers
          :drain-timeout-ms 10000}
         #(fixture/exercise-outbox-json-replay-conflict workload))]
    {:real real-result
     :result (:result controlled)
     :effect-trace (:effect-trace controlled)
     :memory mem
     :sqlite sqlite-world
     :posix posix-world}))

(defn- assert-two-request-workload!
  [workload expected-second-status]
  (let [{:keys [real result effect-trace memory sqlite posix]}
        (run-two-request-workload workload)
        app (:application result)
        workload-evidence (:workload result)
        requests (get-in result [:http :requests])]
    (when-not *sim-only?*
      (is (= real result)))
    (is (= expected-state (:state-before-second workload-evidence)))
    (is (= expected-state (:state-after-second workload-evidence)))
    (is (= (:accepted-command workload)
           (:delivery-authorized-command workload-evidence)))
    (is (= [201 expected-second-status] (mapv :status requests)))
    (is (every? #(= "application/json" (:content-type %)) requests))
    (is (every? #(empty? (:server-errors %)) requests))
    (is (every? #(= {:connection [true false]} (:close-results %)) requests))
    (is (= {:value (:accepted-command workload)
            :result expected-result
            :emitted [expected-row]}
           (:command app)))
    (is (= expected-state (:pending-state app)))
    (is (= expected-marking (:marking app)))
    (is (= expected-delivered-state (:store-state app)))
    (is (= [expected-message] (get-in app [:delivery :requests])))
    (is (= [expected-message] (get-in result [:receiver :requests])))
    (is (empty? (get-in result [:receiver :server-errors])))
    (is (= {:plan-index 35
            :plan-count 35
            :open-dbs 0
            :active-stmts 0}
           (sqlite/summary sqlite)))
    (is (every? #(= :handler (:route %)) effect-trace))
    (is (true? (memory/clean? memory)))
    (is (true? (sqlite/clean? sqlite)))
    (is (true? (posix/clean? posix)))
    result))

(deftest routed-json-exact-replay-does-not-duplicate-delivery
  (let [workload {:mode :exact-replay
                  :accepted-command fixture/default-command
                  :second-command fixture/default-command}
        result (assert-two-request-workload! workload 200)
        requests (get-in result [:http :requests])]
    (is (= (:body-octets (first requests))
           (:body-octets (second requests))))
    (is (= {:identities expected-identities
            :command {:value fixture/default-command
                      :result expected-result
                      :emitted []}}
           (get-in result [:workload :second-command-evidence])))))

(deftest routed-json-conflict-cannot-authorize-delivery
  (let [conflicting-command (assoc fixture/default-command :payload [1 2 3])
        workload {:mode :conflict
                  :accepted-command fixture/default-command
                  :second-command conflicting-command}
        result (assert-two-request-workload! workload 409)]
    (is (nil? (get-in result [:workload :second-command-evidence])))
    (is (= {"error" {"type" "request-id-conflict"
                     "reason" "request-id-conflict"
                     "request-id" "req-1"}}
           (get-in result [:http :requests 1 :response])))))
