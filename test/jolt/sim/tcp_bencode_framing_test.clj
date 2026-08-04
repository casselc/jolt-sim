(ns jolt.sim.tcp-bencode-framing-test
  "Independent wire-vector and fragmentation checks for the ordinary
  TCP/bencode example. These tests do not use the fixture's encoder to build
  their decoding oracle."
  (:require [clojure.test :as test :refer [deftest is]]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.tcp-bencode :as fixture]
            [jolt.sim.fixtures.tcp-bencode-scenarios :as scenarios]
            [teensyp.buffer :as buf]))

(def ^:private try-read-frame-var
  (resolve 'jolt.sim.fixtures.tcp-bencode/try-read-frame))

(def ^:private frame-bytes-var
  (resolve 'jolt.sim.fixtures.tcp-bencode/frame-bytes))

(defn- unsigned-bytes [value]
  (mapv #(bit-and (long %) 0xff) value))

(defn- ascii-bytes [value]
  (.getBytes ^String value "UTF-8"))

(defn- caught-data [thunk]
  (try
    {:returned (thunk)}
    (catch :default error
      (ex-data error))))

(def ^:private golden-body
  ;; Independent literal bencode for
  ;; {"seq" 1, "text" "", "type" "echo"}.
  (ascii-bytes "d3:seqi1e4:text0:4:type4:echoe"))

(def ^:private golden-frame
  (byte-array
   (concat [0 0 0 30] (unsigned-bytes golden-body))))

(deftest frame-prefix-is-four-byte-network-order
  (let [payload (byte-array [0 -1 127 -128])
        framed (frame-bytes-var payload)]
    (is (= [0 0 0 4 0 255 127 128]
           (unsigned-bytes framed)))))

(deftest independent-golden-frame-decodes-exactly
  (let [buffer (buf/wrap golden-frame)
        result (try-read-frame-var buffer)]
    (is (= :ok (:status result)))
    (is (= {"seq" 1 "text" "" "type" "echo"}
           (:value result)))
    (is (= (alength golden-frame) (buf/position buffer)))))

(deftest every-incomplete-wire-boundary-preserves-parser-position
  (doseq [split (range (alength golden-frame))]
    (let [prefix (java.util.Arrays/copyOfRange
                  golden-frame 0 split)
          buffer (buf/wrap prefix)
          result (try-read-frame-var buffer)]
      (is (= :need-more (:status result)) (pr-str split))
      (is (zero? (buf/position buffer)) (pr-str split)))))

(deftest parser-drains-two-complete-pipelined-golden-frames
  (let [twice (byte-array (* 2 (alength golden-frame)))]
    (System/arraycopy golden-frame 0 twice 0 (alength golden-frame))
    (System/arraycopy golden-frame 0 twice (alength golden-frame)
                      (alength golden-frame))
    (let [buffer (buf/wrap twice)
          first-result (try-read-frame-var buffer)
          second-result (try-read-frame-var buffer)]
      (is (= :ok (:status first-result)))
      (is (= :ok (:status second-result)))
      (is (= (:value first-result) (:value second-result)))
      (is (= (alength twice) (buf/position buffer))))))

(deftest malformed-length-framed-bodies-fail-closed
  (let [zero-body (buf/wrap (byte-array [0 0 0 0]))
        trailing (buf/wrap
                  (byte-array
                   (concat [0 0 0 4]
                           (unsigned-bytes (ascii-bytes "i1ee")))))
        invalid (buf/wrap
                 (byte-array
                  (concat [0 0 0 1]
                          (unsigned-bytes (ascii-bytes "x")))))
        oversized (buf/wrap (byte-array [0 0 2 1]))]
    (is (= {:status :invalid :reason :incomplete-value}
           (try-read-frame-var zero-body)))
    (is (= {:status :invalid :reason :trailing-bytes}
           (try-read-frame-var trailing)))
    (is (= :invalid (:status (try-read-frame-var invalid))))
    (is (= {:status :invalid :reason :frame-too-long}
           (try-read-frame-var oversized)))
    (is (zero? (buf/position oversized)))))

(deftest malformed-scenario-inputs-fail-before-real-or-simulated-effects
  (let [valid {:stream-capacity 1
               :pipe-capacity 1
               :poll-eintr-ordinal nil
               :request-text "hello"
               :check-parity? false}
        cases [[:not-a-map nil]
               [:unknown-keys (assoc valid :unexpected true)]
               [:missing-keys (dissoc valid :request-text)]
               [:invalid-stream-capacity (assoc valid :stream-capacity 3)]
               [:invalid-pipe-capacity (assoc valid :pipe-capacity 0)]
               [:invalid-poll-eintr-ordinal
                (assoc valid :poll-eintr-ordinal 3)]
               [:invalid-request-text (assoc valid :request-text 42)]
               [:request-text-too-long
                (assoc valid :request-text (apply str (repeat 33 "x")))]
               [:invalid-check-parity (assoc valid :check-parity? :yes)]]
        real-calls (atom 0)
        world-calls (atom 0)
        results
        (with-redefs
          [fixture/exercise-tcp-bencode
           (fn [& _]
             (swap! real-calls inc)
             {:unexpected :real-effect})
           memory/world
           (fn []
             (swap! world-calls inc)
             {:unexpected :simulated-effect})]
          (mapv (fn [[_ input]]
                  (caught-data
                   #(scenarios/exercise-with-capacities {} input)))
                cases))]
    (doseq [[[expected-reason _] actual] (map vector cases results)]
      (is (= :jolt.sim.fixtures.tcp-bencode-scenarios/invalid-input
             (:type actual))
          (pr-str {:expected-reason expected-reason :actual actual}))
      (is (= expected-reason (:reason actual))
          (pr-str {:expected-reason expected-reason :actual actual})))
    (is (zero? @real-calls)
        "invalid scenario input reached the real TCP fixture")
    (is (zero? @world-calls)
        "invalid scenario input created a simulated memory world")))

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.tcp-bencode-framing-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
