(ns jolt.maelstrom.json-lines-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.maelstrom.transport.json-lines :as json-lines]
            [clojure.data.json :as json]
            [jolt.maelstrom.echo :as echo]
            [jolt.maelstrom.node :as node]))

(defn- ex-data-of [f]
  (try (f) nil (catch :default e (ex-data e))))

(defn- error-of [f]
  (try (f) nil (catch :default e e)))

;; ---- decode-line -------------------------------------------------------------

(deftest decode-line-keywordizes-outer-and-body-keys-only
  (let [line (json/write-str {"src" "c1" "dest" "n1"
                               "body" {"type" "echo" "msg_id" 1
                                       "echo" {"nested" {"deeper" ["a" "b"]}}}})
        envelope (json-lines/decode-line line)]
    (is (= "c1" (:src envelope)))
    (is (= "n1" (:dest envelope)))
    (is (= "echo" (get-in envelope [:body :type])))
    (is (= 1 (get-in envelope [:body :msg_id])))
    (is (= {"nested" {"deeper" ["a" "b"]}} (get-in envelope [:body :echo]))
        "nested payload keys stay strings")))

(deftest decode-line-preserves-unicode-and-astral-characters
  (let [payload "hello, 世界 🌍 😀"
        line (json/write-str {"src" "c1" "dest" "n1"
                               "body" {"type" "echo" "msg_id" 1 "echo" payload}})
        envelope (json-lines/decode-line line)]
    (is (= payload (get-in envelope [:body :echo])))))

(deftest decode-line-preserves-nil-false-and-empty-payloads
  (doseq [payload [nil false "" {} []]]
    (let [line (json/write-str {"src" "c1" "dest" "n1"
                                 "body" {"type" "echo" "msg_id" 1 "echo" payload}})
          envelope (json-lines/decode-line line)]
      (is (contains? (:body envelope) :echo) (pr-str payload))
      (is (= payload (get-in envelope [:body :echo])) (pr-str payload)))))

(deftest decode-line-fails-closed-on-malformed-or-non-object-json
  (doseq [bad-line ["not json" "{" "[1,2,3]" "\"just a string\"" "42"
                    "{}{}" "{} false"]]
    (let [data (ex-data-of #(json-lines/decode-line bad-line))]
      (is (= :jolt.maelstrom.transport.json-lines/decode-failed (:type data))
          bad-line)
      (is (= bad-line (:line data)) bad-line))))

(deftest decode-line-allows-json-whitespace-after-the-object
  (is (= {:src "c1" :dest "n1" :body {:type "echo"}}
         (json-lines/decode-line
          " {\"src\":\"c1\",\"dest\":\"n1\",\"body\":{\"type\":\"echo\"}} \t"))))

(deftest decode-line-preserves-the-codec-failure-as-its-cause
  (let [boom (ex-info "decoder failed" {:marker ::decoder})
        error (with-redefs [json/read-str (fn [& _] (throw boom))]
                (error-of #(json-lines/decode-line "{}")))]
    (is (= :jolt.maelstrom.transport.json-lines/decode-failed
           (:type (ex-data error))))
    (is (identical? boom (ex-cause error)))))

;; ---- encode-line ---------------------------------------------------------------

(deftest encode-line-produces-exactly-one-json-string-with-no-delimiter
  (let [envelope {:src "n1" :dest "c1"
                  :body {:type "echo_ok" :msg_id 1 :in_reply_to 1 :echo "hi"}}
        encoded (json-lines/encode-line envelope)]
    (is (string? encoded))
    (is (not (re-find #"[\r\n]" encoded)))
    (is (= envelope (json/read-str encoded :key-fn keyword)))))

(deftest encode-line-fails-closed-on-unencodable-value
  (let [data (ex-data-of #(json-lines/encode-line
                            {:src "n1" :dest "c1"
                             :body {:type "echo_ok" :bad (fn [])}}))]
    (is (= :jolt.maelstrom.transport.json-lines/encode-failed (:type data)))))

(deftest encode-line-requires-an-envelope-object
  (doseq [value [nil false 1 "text" []]]
    (let [data (ex-data-of #(json-lines/encode-line value))]
      (is (= :jolt.maelstrom.transport.json-lines/encode-failed (:type data)))
      (is (= :not-an-object (:reason data))))))

(deftest encode-line-preserves-the-codec-failure-as-its-cause
  (let [boom (ex-info "encoder failed" {:marker ::encoder})
        error (with-redefs [json/write-str (fn [& _] (throw boom))]
                (error-of #(json-lines/encode-line {})))]
    (is (= :jolt.maelstrom.transport.json-lines/encode-failed
           (:type (ex-data error))))
    (is (identical? boom (ex-cause error)))))

;; ---- line-sender -----------------------------------------------------------

(deftest line-sender-encodes-and-writes-exactly-once
  (let [writes (atom [])
        send! (json-lines/line-sender (fn [line] (swap! writes conj line)))
        envelope {:src "n1" :dest "c1"
                   :body {:type "echo_ok" :msg_id 1 :in_reply_to 1 :echo "hi"}}]
    (send! envelope)
    (is (= 1 (count @writes)))
    (is (= envelope (json/read-str (first @writes) :key-fn keyword)))))

(deftest line-sender-rejects-a-non-function-writer
  (let [data (ex-data-of #(json-lines/line-sender nil))]
    (is (= :jolt.maelstrom.transport.json-lines/invalid-config (:type data)))))

(deftest line-sender-serializes-concurrent-complete-line-writes
  (let [worker-count 8
        ready (atom 0)
        start (promise)
        active-writers (atom 0)
        overlap? (atom false)
        writes (atom [])
        writer (fn [line]
                 (when (> (swap! active-writers inc) 1)
                   (reset! overlap? true))
                 ;; Keep the critical section open long enough to discriminate
                 ;; an implementation that invokes this writer concurrently.
                 (Thread/sleep 5)
                 (swap! writes conj line)
                 (swap! active-writers dec))
        send! (json-lines/line-sender writer)
        workers
        (mapv (fn [msg-id]
                (future
                  (swap! ready inc)
                  @start
                  (send! {:src "n1" :dest "c1"
                          :body {:type "echo_ok"
                                 :msg_id msg-id
                                 :in_reply_to msg-id}})))
              (range worker-count))]
    (loop []
      (when (< @ready worker-count)
        (Thread/yield)
        (recur)))
    (deliver start true)
    (doseq [worker workers] @worker)
    (is (false? @overlap?))
    (is (= worker-count (count @writes)))
    (is (every? map? (map #(json/read-str % :key-fn keyword) @writes)))))

(deftest line-sender-propagates-writer-failures-unchanged
  (let [boom (ex-info "writer exploded" {:marker ::boom})
        send! (json-lines/line-sender (fn [_] (throw boom)))]
    (is (identical? boom
                    (try
                      (send! {:src "n1" :dest "c1"
                               :body {:type "echo_ok" :msg_id 1 :in_reply_to 1 :echo "hi"}})
                      nil
                      (catch :default e e))))))

;; ---- pump! -----------------------------------------------------------------

(deftest pump-processes-lines-sequentially-and-stops-at-nil-eof
  (let [line1 (json/write-str {"src" "c1" "dest" "n1"
                                "body" {"type" "echo" "msg_id" 1 "echo" "a"}})
        line2 (json/write-str {"src" "c1" "dest" "n1"
                                "body" {"type" "echo" "msg_id" 2 "echo" "b"}})
        feed (atom [line1 line2 nil "unreachable"])
        read-line! (fn [] (let [[l & more] @feed] (reset! feed more) l))
        handled (atom [])
        handle! (fn [envelope] (swap! handled conj envelope))]
    (is (nil? (json-lines/pump! read-line! handle!)))
    (is (= [1 2] (map (comp :msg_id :body) @handled)))
    (is (= ["a" "b"] (map (comp :echo :body) @handled)))))

(deftest pump-propagates-decode-failures-unchanged
  (let [feed (atom ["not json"])
        read-line! (fn [] (let [[l & more] @feed] (reset! feed more) l))
        data (ex-data-of #(json-lines/pump! read-line! (fn [_] nil)))]
    (is (= :jolt.maelstrom.transport.json-lines/decode-failed (:type data)))))

(deftest pump-propagates-handle-failures-unchanged
  (let [line (json/write-str {"src" "c1" "dest" "n1"
                               "body" {"type" "echo" "msg_id" 1 "echo" "x"}})
        feed (atom [line])
        read-line! (fn [] (let [[l & more] @feed] (reset! feed more) l))
        boom (ex-info "handler exploded" {:marker ::boom})]
    (is (identical? boom
                    (try
                      (json-lines/pump! read-line! (fn [_] (throw boom)))
                      nil
                      (catch :default e e))))))

;; ---- full wire round trip ---------------------------------------------------

(deftest init-plus-echo-wire-round-trip
  (let [outbound (atom [])
        write-line! (fn [line] (swap! outbound conj line))
        n (node/create-node {:handlers echo/handlers
                              :send! (json-lines/line-sender write-line!)})
        handle! (fn [envelope] (node/handle! n envelope))
        init-line (json/write-str {"src" "c1" "dest" "n1"
                                    "body" {"type" "init" "msg_id" 1
                                            "node_id" "n1" "node_ids" ["n1"]}})
        echo-line (json/write-str {"src" "c1" "dest" "n1"
                                    "body" {"type" "echo" "msg_id" 2 "echo" "please"}})
        feed (atom [init-line echo-line nil])
        read-line! (fn [] (let [[l & more] @feed] (reset! feed more) l))]
    (is (nil? (json-lines/pump! read-line! handle!)))
    (is (= 2 (count @outbound)))
    (let [init-reply (json/read-str (first @outbound) :key-fn keyword)
          echo-reply (json/read-str (second @outbound) :key-fn keyword)]
      (is (= {:src "n1" :dest "c1"
              :body {:type "init_ok" :msg_id 1 :in_reply_to 1}}
             init-reply))
      (is (= "n1" (:src echo-reply)))
      (is (= "c1" (:dest echo-reply)))
      (is (= "echo_ok" (get-in echo-reply [:body :type])))
      (is (= "please" (get-in echo-reply [:body :echo])))
      (is (= 2 (get-in echo-reply [:body :in_reply_to])))
      (is (integer? (get-in echo-reply [:body :msg_id]))))))
