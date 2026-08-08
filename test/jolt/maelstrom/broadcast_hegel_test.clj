(ns jolt.maelstrom.broadcast-hegel-test
  "Paired-regime Hegel coverage for the unchanged three-node Broadcast defsim.

  Every generated signed-64-bit message runs both the healthy line topology
  and the fixed n2--n3 partition/heal regime. The fault therefore cannot be
  omitted by generation or shrunk away: Hegel owns one shared integer draw,
  while the two exact scenario inputs are derived from it. Checks consume only
  the fixture's public observations, application snapshots, client replies,
  and the existing offline memory-transport checker; there is no shadow flood
  or retry model.

  Evidence label: simulated -- deterministic in-memory transport behavior
  under the sim controller, not host socket behavior."
  (:require [clojure.test :as test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jolt.maelstrom.fixtures.broadcast-scenario :as fixture]
            [jolt.sim.maelstrom.history :as history]
            [jolt.sim.runtime :as rt]))

(def ^:private partition-links [["n2" "n3"]])

(def ^:private observation-keys
  #{:input :topology :phases :retry :drops :client-replies :transport})

(def ^:private official-client-replies
  [["n1" "init_ok" 1] ["n2" "init_ok" 2] ["n3" "init_ok" 3]
   ["n1" "topology_ok" 10] ["n2" "topology_ok" 11]
   ["n3" "topology_ok" 12] ["n1" "broadcast_ok" 20]
   ["n3" "read_ok" 30]])

(defn- paired-inputs
  [message]
  {:message message
   :healthy {:message message :partition-links []}
   :partitioned {:message message :partition-links partition-links}})

(defn- paired-input-generator
  "One full signed-64-bit draw shared by both fixed regimes. g/fmap preserves
  the integer shrink span; regime structure contains no draw to erase."
  []
  (g/fmap paired-inputs (g/integer)))

(defn- violation
  [origin input actual]
  (throw
   (ex-info
    "jolt.maelstrom.broadcast-hegel-test invariant violated"
    {:hegel/origin origin
     :input input
     :actual actual})))

(defn- require-equal!
  [origin input expected actual]
  (when-not (= expected actual)
    (violation origin input {:expected expected :actual actual})))

(defn- reply-triple
  [reply]
  [(:src reply)
   (get-in reply [:body :type])
   (get-in reply [:body :in_reply_to])])

(defn- expected-snapshots
  [message]
  {"n1" {:node_id "n1" :neighbors ["n2"]
         :messages [message] :pending []}
   "n2" {:node_id "n2" :neighbors ["n1" "n3"]
         :messages [message] :pending []}
   "n3" {:node_id "n3" :neighbors ["n2"]
         :messages [message] :pending []}})

(defn- peer-broadcast-enqueues
  [observation src dest]
  (filterv #(and (= :enqueue (:op %))
                 (= src (get-in % [:envelope :src]))
                 (= dest (get-in % [:envelope :dest]))
                 (= "broadcast" (get-in % [:envelope :body :type])))
           (get-in observation [:transport :history])))

(defn- check-common!
  [pair scenario-input observation]
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/result-shape"
                  pair observation-keys
                  (when (map? observation) (set (keys observation))))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/input"
                  pair scenario-input (:input observation))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/topology"
                  pair fixture/line-topology (:topology observation))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/client-replies"
                  pair official-client-replies
                  (mapv reply-triple (:client-replies observation)))
  (when-not (every? #(and (integer? (get-in % [:body :msg_id]))
                          (pos? (get-in % [:body :msg_id])))
                    (:client-replies observation))
    (violation "jolt.maelstrom.broadcast-hegel-test/client-msg-ids"
               pair
               {:msg-ids (mapv #(get-in % [:body :msg_id])
                               (:client-replies observation))}))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/read-source"
                  pair "n3" (:src (last (:client-replies observation))))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/read-messages"
                  pair [(:message pair)]
                  (get-in (last (:client-replies observation))
                          [:body :messages]))
  (require-equal! "jolt.maelstrom.broadcast-hegel-test/residual-queues"
                  pair {} (get-in observation [:transport :queues]))
  (let [checked (history/check-snapshot (:transport observation))]
    (when-not (= :pass (:status checked))
      (violation "jolt.maelstrom.broadcast-hegel-test/history"
                 pair checked)))
  (when (some #(= "error" (get-in % [:envelope :body :type]))
              (get-in observation [:transport :history]))
    (violation "jolt.maelstrom.broadcast-hegel-test/error-envelope"
               pair {:error-envelope? true}))
  (when (seq (peer-broadcast-enqueues observation "n1" "n3"))
    (violation "jolt.maelstrom.broadcast-hegel-test/topology-bypass"
               pair {:n1-to-n3-count
                     (count (peer-broadcast-enqueues observation "n1" "n3"))})))

(defn- check-healthy!
  [pair observation]
  (let [message (:message pair)
        expected (expected-snapshots message)]
    (check-common! pair (:healthy pair) observation)
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/healthy-pre-heal"
                    pair expected (get-in observation [:phases :pre-heal]))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/healthy-final"
                    pair expected (get-in observation [:phases :final]))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/healthy-drops"
                    pair {:records [] :dropped-total 0} (:drops observation))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/healthy-retry"
                    pair {"n1" [] "n2" [] "n3" []} (:retry observation))))

(defn- check-partitioned!
  [pair observation]
  (let [message (:message pair)
        pre-heal (get-in observation [:phases :pre-heal])
        pending (get-in pre-heal ["n2" :pending])
        pending-pair (first pending)
        pending-id (:msg_id pending-pair)
        dropped-records (get-in observation [:drops :records])
        n2-to-n3 (peer-broadcast-enqueues observation "n2" "n3")]
    (check-common! pair (:partitioned pair) observation)
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/partition-pre-messages"
                    pair
                    {"n1" [message] "n2" [message] "n3" []}
                    (into {} (map (fn [id]
                                   [id (get-in pre-heal [id :messages])])
                                 fixture/cluster-ids)))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/partition-pre-pending"
                    pair
                    {"n1" []
                     "n2" [{:message message :neighbor "n3"
                            :msg_id pending-id :status :awaiting-reply}]
                     "n3" []}
                    (into {} (map (fn [id]
                                   [id (get-in pre-heal [id :pending])])
                                 fixture/cluster-ids)))
    (when-not (and (= 1 (count pending))
                   (integer? pending-id)
                   (pos? pending-id))
      (violation "jolt.maelstrom.broadcast-hegel-test/pending-id"
                 pair {:pending pending}))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/partition-drop-count"
                    pair 1 (get-in observation [:drops :dropped-total]))
    (require-equal!
     "jolt.maelstrom.broadcast-hegel-test/partition-drop"
     pair
     [{:src "n2" :dest "n3"
       :body {:type "broadcast" :message message :msg_id pending-id}}]
     dropped-records)
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/retry-evidence"
                    pair
                    {"n1" [] "n2" [pending-pair] "n3" []}
                    (:retry observation))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/retry-count"
                    pair 1 (count n2-to-n3))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/retry-msg-id"
                    pair pending-id
                    (get-in (first n2-to-n3) [:envelope :body :msg_id]))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/partition-final"
                    pair (expected-snapshots message)
                    (get-in observation [:phases :final]))))

(defn- run-and-check-pair!
  [pair]
  (let [healthy (:result
                 (fixture/broadcast-partition-heal {} (:healthy pair)))
        partitioned (:result
                     (fixture/broadcast-partition-heal {}
                                                       (:partitioned pair)))]
    (check-healthy! pair healthy)
    (check-partitioned! pair partitioned)
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/terminal-equivalence"
                    pair
                    (get-in healthy [:phases :final])
                    (get-in partitioned [:phases :final]))
    (require-equal! "jolt.maelstrom.broadcast-hegel-test/read-equivalence"
                    pair
                    (get-in (last (:client-replies healthy)) [:body :messages])
                    (get-in (last (:client-replies partitioned))
                            [:body :messages]))
    {:healthy healthy :partitioned partitioned}))

(def ^:private signed-64-witnesses
  [-9223372036854775808 -1 0 1 42 9223372036854775807])

(deftest paired-regime-signed-64-boundary-witnesses
  (if (rt/available?)
    (doseq [message signed-64-witnesses]
      (let [error (try
                    (run-and-check-pair! (paired-inputs message))
                    nil
                    (catch :default thrown thrown))]
        (is (nil? error)
            (pr-str {:message message
                     :error (when error (ex-message error))
                     :data (when error (ex-data error))}))))
    (is (true? (rt/available?))
        "signed-64 Broadcast witnesses require the sim-enabled Jolt image")))

(def ^:private hegel-run-opts
  {:test-cases 40
   :seed 1
   :database ""
   :report-multiple-failures? false
   :verbosity :quiet})

(deftest hegel-broadcast-pairs-healthy-and-partition-recovery
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (run-and-check-pair!
              (h/draw! (paired-input-generator) "broadcast-paired-input"))
             nil))]
      (is (true? (:passed? result))
          (pr-str (select-keys result
                               [:status :seed :n-failures :flaky? :failures
                                :final :observed-failures])))
      (is (false? (:flaky? result)))
      (is (pos? (:valid-test-cases result)))
      (is (= 0 (:invalid-test-cases result)))
      (is (= 0 (:overrun-test-cases result))))
    (is (true? (rt/available?))
        "Broadcast Hegel generation requires the sim-enabled Jolt image")))

(deftest hegel-control-partition-cannot-shrink-away
  ;; The deliberately wrong expectation says the partitioned pre-heal state is
  ;; already the healthy pre-heal state. Every generated case first passes the
  ;; real invariant set, then fails here. The shared integer shrinks to zero;
  ;; both exact regime inputs remain present in the minimized evidence.
  (if (rt/available?)
    (let [result
          (h/run-test!
           hegel-run-opts
           (fn [_]
             (let [pair (h/draw! (paired-input-generator)
                                 "broadcast-paired-input")
                   {:keys [healthy partitioned]} (run-and-check-pair! pair)
                   healthy-pre (get-in healthy [:phases :pre-heal])
                   partitioned-pre (get-in partitioned [:phases :pre-heal])]
               (when-not (= healthy-pre partitioned-pre)
                 (throw
                  (ex-info
                   "partitioned pre-heal state did not equal the deliberately expected healthy state"
                   {:hegel/origin
                    "jolt.maelstrom.broadcast-hegel-test/control-partition-visible"
                    :input pair
                    :actual {:healthy-n3
                             (get-in healthy-pre ["n3" :messages])
                             :partitioned-n3
                             (get-in partitioned-pre ["n3" :messages])}}))))))]
      (is (false? (:passed? result)))
      (is (= :failed (:status result)))
      (is (= 1 (:n-failures result)))
      (is (= "jolt.maelstrom.broadcast-hegel-test/control-partition-visible"
             (-> result :failures first :origin)))
      (is (true? (-> result :failures first :reproduced?)))
      (is (false? (:flaky? result)))
      (is (= (paired-inputs 0)
             (-> result :final first :exception ex-data :input))))
    (is (true? (rt/available?))
        "Broadcast Hegel non-vacuity control requires the sim-enabled Jolt image")))

(defn -main
  [& _]
  (when-not (rt/available?)
    (println
     (str "jolt.maelstrom.broadcast-hegel-test requires the sim-enabled Jolt "
          "image; run through script/run-hegel-gates.sh "
          "maelstrom-broadcast-hegel-test"))
    (flush)
    (System/exit 2))
  (let [result (test/run-tests 'jolt.maelstrom.broadcast-hegel-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed, "
                  (:fail result) " failures, "
                  (:error result) " errors"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
