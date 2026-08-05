(ns jolt.sim.outbox-http-webhook-integration-test
  "Fixed real/hermetic parity for the additional JSON HTTP webhook outbox
  transport. The production sender and receiver server run unchanged in both
  lanes; only the native POSIX and SQLite effects are handled by jolt-sim."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-http-webhook :as fixture]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

(def ^:dynamic *sim-only?* false)

(def ^:private expected-pending-row
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

(def ^:private expected-pending-state
  {:entities {"entity-a" {:version 1 :payload [0 127 128 255]}}
   :request-log
   {"req-1" {:command fixture/default-command
             :result expected-result}}
   :next-outbox-id 2
   :outbox [expected-pending-row]})

(def ^:private expected-delivered-row
  (assoc expected-pending-row :status :delivered))

(def ^:private expected-delivered-state
  (assoc expected-pending-state :outbox [expected-delivered-row]))

(def ^:private expected-durable
  {"outbox-id" 1
   "request-id" "req-1"
   "entity-id" "entity-a"
   "version" 1})

(def ^:private expected-request
  {"type" "outbox_delivery"
   "durable" expected-durable
   "attempt-id" fixture/default-attempt-id
   "payload" [0 127 128 255]})

(def ^:private expected-ack
  {"type" "outbox_delivery_ok"
   "durable" expected-durable
   "attempt-id" fixture/default-attempt-id})

(def ^:private expected-refusal-reason
  {:non-2xx :non-success-status
   :malformed-json :invalid-json
   :trailing-json :invalid-json
   :mismatched-attempt :ack-mismatch
   :mismatched-durable :ack-mismatch})

(defn- run-simulated [scenario]
  (let [mem (memory/world)
        statement-plans
        (if (contains? (set fixture/accepted-scenarios) scenario)
          (plans/delivery-statement-plans)
          (plans/webhook-rejected-statement-plans))
        sqlite-world (sqlite/world mem statement-plans)
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
         #(fixture/exercise scenario))]
    {:result (:result controlled)
     :effect-trace (:effect-trace controlled)
     :memory mem
     :sqlite sqlite-world
     :posix posix-world}))

(defn- assert-clean-controlled-run!
  [{:keys [effect-trace memory sqlite posix]} expected-plan-count]
  (is (= {:plan-index expected-plan-count
          :plan-count expected-plan-count
          :open-dbs 0
          :active-stmts 0}
         (sqlite/summary sqlite)))
  (is (every? #(= :handler (:route %)) effect-trace))
  (is (true? (memory/clean? memory)))
  (is (true? (sqlite/clean? sqlite)))
  (is (true? (posix/clean? posix)))
  (let [stream (posix/capacity-summary posix)
        pipe (posix/pipe-capacity-summary posix)]
    (is (= 8 (:stream-capacity stream)))
    (is (pos? (:stream-capacity-limited-writes stream)))
    (is (= 8 (:max-stream-recv-bytes stream)))
    (is (= 1 (:pipe-capacity pipe)))
    (is (= 1 (:max-pipe-fifo-bytes pipe)))))

(defn- assert-shared-evidence! [result]
  (is (= expected-pending-state (:pending-state result)))
  (is (= [expected-request] (get-in result [:receiver :requests])))
  (is (empty? (get-in result [:receiver :server-errors])))
  (is (= 201 (get-in result [:command :http :status])))
  (is (= "application/json"
         (get-in result [:command :http :content-type])))
  (is (empty? (get-in result [:command :http :server-errors])))
  (is (= {:connection [true false]}
         (get-in result [:command :http :close-results])))
  (is (= {:value fixture/default-command
          :result expected-result
          :emitted [expected-pending-row]}
         (get-in result [:command :evidence :command]))))

(deftest accepted-webhook-marks-only-after-exact-correlated-ack
  (doseq [scenario fixture/accepted-scenarios]
    (testing (name scenario)
      (let [real (when-not *sim-only?* (fixture/exercise scenario))
            simulated (run-simulated scenario)
            result (:result simulated)]
        (testing "ordinary application evidence is byte-for-byte real/hermetic parity"
          (when-not *sim-only?*
            (is (= real result))))
        (assert-shared-evidence! result)
        (testing "the outbound request and exact ack gate one durable mark"
          (is (= expected-request (get-in result [:delivery :request])))
          (is (= {:status 200
                  :content-type "application/json"
                  :ack expected-ack}
                 (get-in result [:delivery :response])))
          (is (= {:row expected-delivered-row :changed? true}
                 (get-in result [:delivery :marking])))
          (is (= expected-delivered-state (:store-state result))))
        (testing "all 24 SQLite plans and every native effect stay controlled"
          (assert-clean-controlled-run! simulated 24))))))

(deftest hostile-webhook-responses-never-mark-the-pending-row
  (doseq [scenario fixture/hostile-scenarios]
    (testing (name scenario)
      (let [real (when-not *sim-only?* (fixture/exercise scenario))
            simulated (run-simulated scenario)
            result (:result simulated)]
        (when-not *sim-only?*
          (is (= real result)))
        (assert-shared-evidence! result)
        (is (= (get expected-refusal-reason scenario)
               (get-in result [:delivery :refused :reason])))
        (is (= expected-pending-state (:store-state result)))
        (is (= :pending (get-in result [:store-state :outbox 0 :status])))
        (testing "the absence of a mark is exact SQLite plan evidence"
          (assert-clean-controlled-run! simulated 18))))))
