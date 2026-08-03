(ns jolt.sim.outbox-delivery-integration-test
  "Focused real/hermetic parity gate for the first ordinary whole-application
  post-COMMIT outbox delivery witness."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-delivery :as fixture]
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
  {"type" "command_ok"
   "status" "committed"
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1
   "outbox-id" 1})

(deftest ordinary-http-commits-outbox-then-delivers-over-framed-tcp
  (let [real-result (when-not *sim-only?*
                      (fixture/exercise-outbox-delivery))
        mem (memory/world)
        sqlite-world (sqlite/world mem (plans/delivery-statement-plans))
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
         fixture/exercise-outbox-delivery)
        result (:result controlled)
        app (:application result)
        delivery (:delivery app)]
    (testing "the unchanged ordinary application has exact real/hermetic parity"
      (when-not *sim-only?*
        (is (= real-result result))))

    (testing "the HTTP command exposes exactly one post-COMMIT pending row"
      (is (= expected-identities (:identities app)))
      (is (= {:value fixture/default-command
              :result expected-result
              :emitted [expected-row]}
             (:command app)))
      (is (= expected-state (:store-state app)))
      (is (= :pending (get-in app [:store-state :outbox 0 :status]))))

    (testing "a later phase delivers the reloaded row in one framed bencode attempt"
      (is (= [expected-message] (:requests delivery)))
      (is (= [expected-ack] (:replies delivery)))
      (is (pos? (:sent-bytes delivery)))
      (is (pos? (:received-bytes delivery)))
      (is (= {:connection [true false]} (:close-results delivery))))

    (testing "the receiver observes the same semantic delivery and acknowledges it"
      (is (= [expected-message] (get-in result [:receiver :requests])))
      (is (empty? (get-in result [:receiver :server-errors]))))

    (testing "the HTTP response reports the committed command result byte-exactly"
      (is (= 200 (get-in result [:http :status])))
      (is (= "application/x-bencode"
             (get-in result [:http :content-type])))
      (is (= expected-http-response (get-in result [:http :response])))
      (is (empty? (get-in result [:http :server-errors])))
      (is (= {:connection [true false]}
             (get-in result [:http :close-results]))))

    (testing "all 15 SQLite plans and all native effects stay inside the model"
      (is (= {:plan-index 15
              :plan-count 15
              :open-dbs 0
              :active-stmts 0}
             (sqlite/summary sqlite-world)))
      (is (every? #(= :handler (:route %)) (:effect-trace controlled))))

    (testing "finite model capacities exercise bounded partial progress"
      (let [stream (posix/capacity-summary posix-world)
            pipe (posix/pipe-capacity-summary posix-world)]
        ;; The stream bound is smaller than the native progress ceiling, so
        ;; capacity itself must split writes without coupling this smoke gate to
        ;; the extreme two-byte fragmentation explored by the later Hegel lane.
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
