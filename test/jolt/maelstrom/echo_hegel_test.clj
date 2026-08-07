(ns jolt.maelstrom.echo-hegel-test
  "In-process Hegel property lane over the UNCHANGED
  jolt.maelstrom.fixtures.echo-scenario/echo-roundtrip defsim scenario: the
  official Maelstrom init + Echo exchange through the real
  jolt.maelstrom.node / jolt.maelstrom.echo handlers and the
  jolt.maelstrom.transport.memory FIFO/history transport, under the sim
  controller in THIS process. There is no model reimplementation and no
  fresh-worker wrapper: each generated case calls the fixture's own
  (:result (echo-roundtrip {} payload)) directly.

  Hegel owns generation over bounded immutable JSON-shaped payloads: nil,
  booleans, bounded integers, and bounded UTF-8 strings as leaves; vectors
  of leaves; and maps from bounded strings to leaves or vectors of leaves.
  Doubles/NaN, byte arrays, keywords, symbols, and mutable leaves are
  outside the domain by construction (the generator never draws them).

  Each generated case asserts, against the unchanged scenario result:

  - exact n1 node identity;
  - exactly two replies, init_ok then echo_ok, with exact src/dest routing,
    node-assigned msg_id, and in_reply_to correlation;
  - the echo_ok body carries the EXACT drawn payload, with the :echo key
    present even when the payload is nil (presence is asserted separately
    from value equality so a dropped key cannot masquerade as a nil value);
  - empty residual transport queues;
  - jolt.sim.maelstrom.history/check-snapshot returns exactly
    {:status :pass :events 8 :enqueued 4 :delivered 4} -- the four enqueues
    (init, echo, init_ok, echo_ok) and four deliveries of the round trip.

  Sim runtime availability is a harness precondition checked in -main
  BEFORE any Hegel generation starts (exit 2, an infrastructure failure),
  and each deftest fails closed with the echo-sim-test guard shape when run
  outside -main. Availability is never a rejected generated case: no
  property body calls h/assume!.

  Three discriminating expected-failure controls follow the proven
  jolt.sim.ffi-admission-hegel-test /
  jolt.sim.hegel-adapter-test shape (stable :hegel/origin, :status :failed,
  :reproduced? true, not flaky): a deliberately changed Echo payload, a
  corrupted history message-id fed to the offline checker, and a small
  nil-vs-empty property proving the round trip distinguishes a null payload
  from an empty one.

  Evidence label: simulated -- deterministic in-memory transport behavior
  under the sim controller, not host socket behavior."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.maelstrom.fixtures.echo-scenario :refer [echo-roundtrip]]
            [jolt.sim.maelstrom.history :as history]
            [jolt.sim.runtime :as rt]))

;; ---- bounded JSON-shaped payload generator -----------------------------------

(defn- payload-generator
  "Returns a Hegel generator over the bounded JSON-shaped payload domain.
  Leaves are nil, booleans, bounded integers, and bounded UTF-8 strings;
  containers are vectors of leaves and maps from bounded strings to leaves
  or vectors of leaves. Every bound is explicit so payloads stay small and
  immutable; selection and shrinking stay engine-owned."
  []
  (let [leaf (g/one-of [(g/just nil)
                        (g/boolean)
                        (g/integer -1000000 1000000)
                        (g/string {:codec :utf-8 :min-size 0 :max-size 24})])
        leaf-vector (g/vector {:max-size 8} leaf)
        string-key (g/string {:codec :utf-8 :min-size 0 :max-size 12})]
    (g/one-of [leaf
               leaf-vector
               (g/map {:max-size 8} string-key
                      (g/one-of [leaf leaf-vector]))])))

;; ---- invariant set -------------------------------------------------------------

(defn- violation
  "Throws the stable typed property failure for one assertion site, carrying
  :hegel/origin, the drawn payload, and a small bounded :actual projection.
  Throwing (rather than returning false) is required inside a property
  passed to h/run-test!; Hegel replays and shrinks against this ex-data."
  [origin payload actual]
  (throw
   (ex-info
    "jolt.maelstrom.echo-hegel-test property invariant violated"
    {:hegel/origin origin
     :input payload
     :actual actual})))

(defn- expected-replies
  "The exact reply envelopes the unchanged node must produce for one init +
  echo round trip: init_ok (msg_id 1, in_reply_to 1) then echo_ok
  (msg_id 2, in_reply_to 2) carrying the exact drawn payload, both routed
  n1 -> c1. Map equality is entry-based, so {:echo nil} is not equal to a
  body that dropped the key."
  [payload]
  [{:src "n1" :dest "c1"
    :body {:type "init_ok" :msg_id 1 :in_reply_to 1}}
   {:src "n1" :dest "c1"
    :body {:type "echo_ok" :msg_id 2 :in_reply_to 2 :echo payload}}])

(def ^:private expected-history-verdict
  {:status :pass :events 8 :enqueued 4 :delivered 4})

(defn- check-roundtrip!
  "Runs one in-process echo round trip for `payload` through the unchanged
  fixture and asserts the full invariant set. Returns nil on success and
  throws on the first violation. Every failure path is a typed violation
  carrying the stable origin -- malformed result shapes degrade into the
  shape/count violations below rather than a host predicate exception."
  [payload]
  (let [result (:result (echo-roundtrip {} payload))]
    (when-not (and (map? result)
                   (= #{:node-id :replies :transport} (set (keys result))))
      (violation "jolt.maelstrom.echo-hegel-test/result-shape"
                 payload
                 {:keys (when (map? result)
                          (vec (sort-by pr-str (keys result))))}))
    (when-not (= "n1" (:node-id result))
      (violation "jolt.maelstrom.echo-hegel-test/node-id"
                 payload
                 {:node-id (:node-id result)}))
    (let [replies (:replies result)
          expected (expected-replies payload)]
      (when-not (or (sequential? replies) (nil? replies))
        (violation "jolt.maelstrom.echo-hegel-test/replies-not-sequential"
                   payload
                   {:replies replies}))
      (when-not (= 2 (count replies))
        (violation "jolt.maelstrom.echo-hegel-test/reply-count"
                   payload
                   {:reply-count (count replies)
                    :replies replies}))
      (when-not (= (first expected) (first replies))
        (violation "jolt.maelstrom.echo-hegel-test/init-reply"
                   payload
                   {:expected (first expected)
                    :actual (first replies)}))
      ;; Presence first: a reply body that dropped :echo fails here by name
      ;; instead of surfacing as a nil-valued diff at the echo-reply site.
      (when-not (contains? (:body (second replies)) :echo)
        (violation "jolt.maelstrom.echo-hegel-test/echo-presence"
                   payload
                   {:reply (second replies)}))
      (when-not (= (second expected) (second replies))
        (violation "jolt.maelstrom.echo-hegel-test/echo-reply"
                   payload
                   {:expected (second expected)
                    :actual (second replies)})))
    (when-not (= {} (get-in result [:transport :queues]))
      (violation "jolt.maelstrom.echo-hegel-test/residual-queues"
                 payload
                 {:queues (get-in result [:transport :queues])}))
    (let [checked (history/check-snapshot (:transport result))]
      (when-not (= expected-history-verdict checked)
        (violation "jolt.maelstrom.echo-hegel-test/history"
                   payload
                   {:checked checked})))
    nil))

;; ---- deterministic kind witnesses ---------------------------------------------

(def ^:private witness-payloads
  "One deterministic payload per generated kind, plus the empty string,
  empty vector, and empty map boundaries and a map entry whose value is
  nil. These guarantee every leaf/container kind passes at least once
  without relying on Hegel exploration to cover the domain."
  [nil
   true
   -7
   "héllo, 世界 🌍"
   ""
   [nil false 3 "ab"]
   []
   {"a" nil "b" [1 "x"] "empty" ""}
   {}])

(deftest echo-roundtrip-kind-witnesses
  (if (rt/available?)
    (doseq [payload witness-payloads]
      (let [thrown (try
                     (check-roundtrip! payload)
                     nil
                     (catch :default error error))]
        (is (nil? thrown)
            (str "witness payload round trip failed: "
                 (pr-str {:input payload
                          :error (when thrown (ex-message thrown))
                          :data (when thrown (ex-data thrown))})))))
    (is (false? (rt/available?)))))

;; ---- Hegel property -------------------------------------------------------------

(def ^:private hegel-run-opts
  {:test-cases 20
   :seed 1
   :database ""
   :report-multiple-failures? false
   :verbosity :quiet})

(deftest hegel-echo-roundtrip-preserves-bounded-json-payloads
  ;; The real passing property. Each drawn payload runs the unchanged
  ;; scenario once in this process; the full invariant set must hold without
  ;; flakiness. No :too-slow suppression: cases are in-process controller
  ;; runs, not fresh worker spawns.
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (let [payload (h/draw! (payload-generator) "echo-payload")]
               (check-roundtrip! payload)
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
                   :observed-failures (:observed-failures result)})))
    (is (false? (rt/available?)))))

;; ---- discriminating expected-failure controls ------------------------------------

(deftest hegel-control-changed-echo-payload-is-caught
  ;; The property encodes the deliberately wrong expectation that the echo
  ;; reply equals a changed payload. The changed value is a map with a
  ;; namespaced keyword key, which no generated JSON-shaped payload can
  ;; equal, so every case fails with the stable origin: if the real
  ;; echo-reply assertion ever stopped discriminating the payload, this
  ;; control's failure shape is the evidence that it must not.
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (let [payload (h/draw! (payload-generator) "echo-payload")
                   roundtrip (:result (echo-roundtrip {} payload))
                   echo-value (get-in roundtrip [:replies 1 :body :echo])
                   changed {:jolt.maelstrom.echo-hegel-test/changed payload}]
               (when-not (= changed echo-value)
                 (throw
                  (ex-info
                   "echo reply did not equal the deliberately changed payload"
                   {:hegel/origin
                    "jolt.maelstrom.echo-hegel-test/control-changed-payload"
                    :input payload
                    :actual echo-value}))))))]
      (is (false? (:passed? result)))
      (is (= :failed (:status result)))
      (is (= 1 (:n-failures result)))
      (is (= "jolt.maelstrom.echo-hegel-test/control-changed-payload"
             (-> result :failures first :origin)))
      (is (true? (-> result :failures first :reproduced?)))
      (is (false? (:flaky? result))))
    (is (false? (rt/available?)))))

(defn- corrupt-last-message-id
  "Returns the snapshot with the final history event's :message-id replaced
  by a positive integer no enqueue produced. The corrupted event stays
  shape-valid, so check-snapshot reaches its semantic delivery rules."
  [snapshot]
  (update snapshot :history
          (fn [events]
            (let [last-index (dec (count events))]
              (assoc events last-index
                     (assoc (nth events last-index) :message-id 999))))))

(deftest hegel-control-corrupted-history-message-id-is-caught
  ;; The property encodes the deliberately wrong expectation that the
  ;; corrupted snapshot still passes the offline history checker.
  ;; check-snapshot must report :delivery-without-enqueue (message-id 999
  ;; has no enqueue), so every case fails with the stable origin; a checker
  ;; that stopped detecting message-id corruption would turn this control
  ;; green and red the gate.
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (let [payload (h/draw! (payload-generator) "echo-payload")
                   roundtrip (:result (echo-roundtrip {} payload))
                   corrupted (corrupt-last-message-id (:transport roundtrip))
                   checked (history/check-snapshot corrupted)]
               (when-not (= :pass (:status checked))
                 (throw
                  (ex-info
                   "history checker rejected the message-id-corrupted snapshot"
                   {:hegel/origin
                    "jolt.maelstrom.echo-hegel-test/control-history-message-id"
                    :input payload
                    :actual checked}))))))]
      (is (false? (:passed? result)))
      (is (= :failed (:status result)))
      (is (= 1 (:n-failures result)))
      (is (= "jolt.maelstrom.echo-hegel-test/control-history-message-id"
             (-> result :failures first :origin)))
      (is (true? (-> result :failures first :reproduced?)))
      (is (false? (:flaky? result)))
      (is (= :delivery-without-enqueue
             (-> result :final first :exception ex-data :actual :reason))))
    (is (false? (rt/available?)))))

(def ^:private nil-vs-empty-domain
  "The two-element nil-vs-empty domain. nil satisfies the wrong expectation
  below and {} violates it, mirroring the proven two-element
  g/sampled-from failure shape in
  jolt.sim.hegel-adapter-test/only-the-reverse-schedule-fails-and-replays-without-flakiness."
  [nil {}])

(deftest hegel-control-nil-vs-empty-is-caught
  ;; Stable small expected-failure control: the property encodes the
  ;; deliberately wrong expectation that the echoed payload is always nil.
  ;; The nil case passes it and the {} case fails it, so the minimal
  ;; counterexample is {} -- the round trip provably distinguishes a null
  ;; payload from an empty one rather than conflating them.
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (let [payload (h/draw! (g/sampled-from nil-vs-empty-domain)
                                    "echo-payload")
                   roundtrip (:result (echo-roundtrip {} payload))
                   echo-value (get-in roundtrip [:replies 1 :body :echo])]
               (when-not (nil? echo-value)
                 (throw
                  (ex-info
                   "echo reply payload was not nil"
                   {:hegel/origin
                    "jolt.maelstrom.echo-hegel-test/control-nil-vs-empty"
                    :input payload
                    :actual echo-value}))))))]
      (is (false? (:passed? result)))
      (is (= :failed (:status result)))
      (is (= 1 (:n-failures result)))
      (is (= "jolt.maelstrom.echo-hegel-test/control-nil-vs-empty"
             (-> result :failures first :origin)))
      (is (true? (-> result :failures first :reproduced?)))
      (is (false? (:flaky? result)))
      (is (= {} (-> result :final first :exception ex-data :input))))
    (is (false? (rt/available?)))))

;; ---- entry point ------------------------------------------------------------------

(defn -main
  "Runs the witnesses, the passing property, and the three controls
  serially. Sim runtime availability is a harness precondition checked
  before any Hegel generation starts; it exits 2 (infrastructure failure,
  matching the runner's usage/infra exit) rather than entering generation,
  so unavailability can never become a rejected generated case."
  [& _]
  (when-not (rt/available?)
    (println
     (str "jolt.maelstrom.echo-hegel-test requires the sim-enabled Jolt "
          "image; run through script/run-hegel-gates.sh maelstrom-echo-hegel-test"))
    (flush)
    (System/exit 2))
  (let [result (test/run-tests 'jolt.maelstrom.echo-hegel-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (flush)
    ;; Hegel may leave non-daemon threads alive; exit explicitly.
    (System/exit (if (zero? failures) 0 1))))
