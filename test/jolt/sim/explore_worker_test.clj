(ns jolt.sim.explore-worker-test
  "Pure, versioned protocol-helper tests for the supervised exploration worker.

  These cover only the deterministic, host-independent surfaces of
  `jolt.sim.explore-worker`: request construction and result decoding. No
  process is launched. One bounded local-file case exercises the worker
  entrypoint; the suite remains stable on any image (including an ordinary
  released one)."
  (:require [clojure.test :as test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [jolt.sim.explore-worker :as explore]
            [jolt.sim.trace :as trace]))

(def ^:private protocol-key :jolt.sim.explore/protocol)
(def ^:private status-key :jolt.sim.explore/status)
(def ^:private schedule-key :jolt.sim.explore/schedule)
(def ^:private input-key :jolt.sim.explore/input)
(def ^:private value-key :jolt.sim.explore/value)
(def ^:private error-key :jolt.sim.explore/error)

(def ^:private completed-result-keys
  #{protocol-key status-key schedule-key value-key})

(def ^:private error-result-keys
  #{protocol-key status-key schedule-key error-key})

(defn- caught-data
  "Returns the ex-data of the exception thrown by `f`, or nil if `f` returns."
  [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- completed-document
  "Builds one well-formed protocol-v2 completed result for `value`."
  [schedule value]
  {protocol-key 2
   status-key :completed
   schedule-key schedule
   value-key (trace/canonical-value value)})

(defn- error-document
  "Builds one well-formed protocol-v2 failed/worker-error result for `error`."
  [schedule status error]
  {protocol-key 2
   status-key status
   schedule-key schedule
   error-key (trace/canonical-value (trace/normalize-error error))})

(defn unmarked-scenario [_]
  :must-not-run)

(def ^{:jolt.sim/scenario true}
  marked-without-input-contract
  (fn [& _]
    (throw (ex-info "missing-contract scenario must not run" {}))))

(def ^{:jolt.sim/scenario true :jolt.sim/accepts-input true}
  marked-noncallable
  42)

(defn ^{:jolt.sim/scenario true :jolt.sim/accepts-input true}
  input-contract-collision-scenario
  [_runtime-overrides input]
  (throw
   (ex-info
    "application deliberately collides with the input-rejection keyword"
    {:type :jolt.sim.runtime/scenario-rejects-input
     :input input})))

;;; ---------------------------------------------------------------------------
;;; request-document
;;; ---------------------------------------------------------------------------

(deftest request-document-builds-the-exact-protocol-v2-request
  (let [document (explore/request-document 'jolt.sim.some/scenario [2 0 1])]
    (is (= {protocol-key 2
            :jolt.sim.explore/scenario 'jolt.sim.some/scenario
            schedule-key [2 0 1]
            input-key (trace/canonical-value nil)}
           document))
    ;; Exactly the four declared keys, no more, no less.
    (is (= #{protocol-key
             :jolt.sim.explore/scenario
             schedule-key
             input-key}
           (set (keys document))))))

(deftest request-document-accepts-a-nil-schedule-for-a-no-scheduler-case
  (let [document (explore/request-document 'jolt.sim.some/scenario nil :the-input)]
    (is (nil? (get document schedule-key)))
    (is (= (trace/canonical-value :the-input) (get document input-key)))))

(deftest request-document-rejects-a-non-namespaced-scenario-symbol
  (let [data (caught-data #(explore/request-document 'unqualified [0]))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :invalid-scenario (:reason data)))
    (is (= 'unqualified (:scenario data)))))

(deftest request-document-rejects-an-invalid-schedule
  (let [data (caught-data #(explore/request-document 'ns/scenario [1 2]))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :invalid-schedule (:reason data)))
    (is (= [1 2] (:schedule data)))))

(deftest execute-request-refuses-an-unmarked-resolved-function
  (let [schedule [0]
        document
        (explore/execute-request
         (explore/request-document
          'jolt.sim.explore-worker-test/unmarked-scenario
          schedule))
        outcome (explore/decode-result schedule document)]
    (is (= :must-not-run (unmarked-scenario nil)))
    (is (= :worker-error (:status outcome)))
    (is (= :scenario-resolution (get-in outcome [:error :phase])))
    (is (= :jolt.sim/exception (get-in outcome [:error :kind])))))

(deftest execute-request-refuses-a-marked-var-without-an-input-contract
  (let [document
        (explore/execute-request
         (explore/request-document
          'jolt.sim.explore-worker-test/marked-without-input-contract
          nil))
        outcome (explore/decode-result nil document)]
    (is (= :worker-error (:status outcome)))
    (is (= :scenario-resolution (get-in outcome [:error :phase])))
    (is (= :jolt.sim/exception (get-in outcome [:error :kind])))))

(deftest execute-request-refuses-a-marked-noncallable-var
  (let [document
        (explore/execute-request
         (explore/request-document
          'jolt.sim.explore-worker-test/marked-noncallable
          nil))
        outcome (explore/decode-result nil document)]
    (is (= :worker-error (:status outcome)))
    (is (= :scenario-resolution (get-in outcome [:error :phase])))
    (is (= :jolt.sim/exception (get-in outcome [:error :kind])))))

(deftest worker-entrypoint-distinguishes-request-validation-from-reading
  (let [schedule [0]
        run-dir
        (str (fs/create-temp-dir
              {:prefix "jolt-sim-worker-validation-"}))
        request-path (str (fs/path run-dir "request.edn"))
        result-path (str (fs/path run-dir "result.edn"))]
    (try
      (spit
       request-path
       (trace/canonical-edn
        (assoc
         (explore/request-document
          'jolt.sim.explore-worker-test/unmarked-scenario
          schedule)
         protocol-key 99)))
      (explore/-main request-path result-path)
      (let [outcome
            (explore/decode-result-edn schedule (slurp result-path))]
        (is (= :worker-error (:status outcome)))
        (is (= :request-validation (get-in outcome [:error :phase])))
        (is (= :jolt.sim/exception (get-in outcome [:error :kind]))))
      (finally
        (when (fs/exists? run-dir)
          (fs/delete-tree run-dir))))))

(deftest execute-request-rejects-a-malformed-canonical-input
  (let [schedule [0]
        document
        (explore/execute-request
         (assoc
          (explore/request-document
           'jolt.sim.explore-worker-test/unmarked-scenario
           schedule)
          input-key [:jolt.sim.value/integer "not-an-integer"]))
        outcome (explore/decode-result schedule document)]
    (is (= :worker-error (:status outcome)))
    (is (= :request-validation (get-in outcome [:error :phase])))))

(deftest execute-request-rejects-an-actual-v1-request-by-version
  ;; Protocol v2 has no v1 compatibility. Version is checked before the v2 key
  ;; set so a genuine v1 document is classified as a version mismatch.
  (let [schedule [0]
        v1-shaped {protocol-key 1
                   :jolt.sim.explore/scenario
                   'jolt.sim.explore-worker-test/unmarked-scenario
                   schedule-key schedule}
        validate-var (resolve 'jolt.sim.explore-worker/validate-request!)
        validation-data (caught-data #(@validate-var v1-shaped))
        document (explore/execute-request v1-shaped)
        outcome (explore/decode-result schedule document)]
    (is (= :protocol-version (:reason validation-data)))
    (is (= 2 (:expected validation-data)))
    (is (= 1 (:actual validation-data)))
    (is (= :worker-error (:status outcome)))
    (is (= :request-validation (get-in outcome [:error :phase])))))

(deftest application-cannot-spoof-the-scenario-input-contract
  (let [document
        (explore/execute-request
         (explore/request-document
          'jolt.sim.explore-worker-test/input-contract-collision-scenario
          nil
          {:workload :collision-control}))
        outcome (explore/decode-result nil document)]
    (is (= :failed (:status outcome)))
    (is (= "application deliberately collides with the input-rejection keyword"
           (get-in outcome [:error :message])))
    (is (= :jolt.sim.runtime/scenario-rejects-input
           (get-in outcome [:error :data :type])))
    (is (= {:workload :collision-control}
           (get-in outcome [:error :data :input])))))

;;; ---------------------------------------------------------------------------
;;; decode-result: happy paths for all three statuses
;;; ---------------------------------------------------------------------------

(deftest decode-result-restores-a-completed-outcome
  (let [schedule [0 1]
        value {:answer 42 :label "ok"}
        outcome (explore/decode-result schedule
                                       (completed-document schedule value))]
    (is (= :completed (:status outcome)))
    (is (= schedule (:schedule outcome)))
    (is (= value (:result outcome)))
    (is (nil? (:error outcome)))))

(deftest decode-result-accepts-a-nil-schedule-for-a-no-scheduler-case
  (let [value {:echoed :hello}
        outcome (explore/decode-result nil (completed-document nil value))]
    (is (= :completed (:status outcome)))
    (is (nil? (:schedule outcome)))
    (is (= value (:result outcome)))))

(deftest decode-result-restores-a-failed-outcome
  (let [schedule [0]
        error (ex-info "scenario failed" {:why :boom})
        outcome (explore/decode-result schedule
                                       (error-document schedule :failed error))]
    (is (= :failed (:status outcome)))
    (is (= schedule (:schedule outcome)))
    (is (nil? (:result outcome)))
    ;; The restored error is the normalized, canonical projection of the
    ;; original exception: class/message/data, not the live Throwable.
    (is (= {:kind :jolt.sim/exception
            :class (str (class error))
            :message "scenario failed"
            :data {:why :boom}}
           (:error outcome)))))

(deftest decode-result-restores-a-worker-error-outcome
  (let [schedule [1 0]
        error (ex-info "encoding failed" nil)
        outcome (explore/decode-result schedule
                                       (error-document schedule :worker-error error))]
    (is (= :worker-error (:status outcome)))
    (is (= schedule (:schedule outcome)))
    (is (nil? (:result outcome)))
    (is (= {:kind :jolt.sim/exception
            :class (str (class error))
            :message "encoding failed"
            :data nil}
           (:error outcome)))))

;;; ---------------------------------------------------------------------------
;;; decode-result: byte-array payloads are restored as fresh copies
;;; ---------------------------------------------------------------------------

(deftest restored-byte-arrays-are-fresh-copies
  ;; Each decode allocates a brand-new byte array; decoded results never alias
  ;; one another, so mutating one cannot corrupt another or the source form.
  (let [schedule [0]
        ;; Octets kept in 0..127 so the round-trip comparison is independent of
        ;; whether byte-array elements materialize as signed or unsigned.
        octets [10 20 30 127 0]
        document (completed-document schedule (byte-array octets))
        first-result (:result (explore/decode-result schedule document))
        second-result (:result (explore/decode-result schedule document))]
    (is (bytes? first-result))
    (is (bytes? second-result))
    (is (= octets (vec (seq first-result))))
    (is (= octets (vec (seq second-result))))
    (is (not (identical? first-result second-result)))
    (aset first-result 0 99)
    (is (= 10 (aget second-result 0)))))

;;; ---------------------------------------------------------------------------
;;; decode-result: rejection paths
;;; ---------------------------------------------------------------------------

(deftest decode-result-rejects-a-wrong-protocol-version
  (let [schedule [0]
        document (-> (completed-document schedule nil)
                     (assoc protocol-key 0))
        data (caught-data #(explore/decode-result schedule document))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :protocol-version (:reason data)))
    (is (= 2 (:expected data)))
    (is (= 0 (:actual data)))))

(deftest decode-result-rejects-a-schedule-mismatch
  (let [expected [0 1]
        actual [1 0]
        document (completed-document actual nil)
        data (caught-data #(explore/decode-result expected document))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :schedule-mismatch (:reason data)))
    (is (= expected (:expected data)))
    (is (= actual (:actual data)))))

(deftest decode-result-rejects-an-invalid-status
  (let [schedule [0]
        document {protocol-key 2
                  status-key :pending
                  schedule-key schedule
                  value-key (trace/canonical-value nil)}
        data (caught-data #(explore/decode-result schedule document))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :result-status (:reason data)))
    (is (= :pending (:status data)))))

(deftest decode-result-rejects-an-inexact-completed-key-set
  (testing "an unexpected extra key fails the exact-set check"
    (let [schedule [0]
          document (-> (completed-document schedule nil)
                       (assoc :jolt.sim.explore/unexpected :extra))
          data (caught-data #(explore/decode-result schedule document))]
      (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
      (is (= :result-keys (:reason data)))
      (is (= :completed (:status data)))
      (is (= completed-result-keys (:expected data)))
      (is (= (conj completed-result-keys :jolt.sim.explore/unexpected)
             (:actual data)))))
  (testing "a failed document carrying the value key is rejected"
    (let [schedule [0]
          document {protocol-key 2
                    status-key :failed
                    schedule-key schedule
                    value-key (trace/canonical-value nil)}
          data (caught-data #(explore/decode-result schedule document))]
      (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
      (is (= :result-keys (:reason data)))
      (is (= :failed (:status data)))
      (is (= error-result-keys (:expected data))))))

(deftest decode-result-rejects-a-malformed-canonical-payload
  ;; A value slot tagged as an integer but holding a non-integer string is not
  ;; a shape ever emitted by canonical-value, so canonical-form? rejects it.
  (let [schedule [0]
        payload [:jolt.sim.value/integer "not-an-integer"]
        document {protocol-key 2
                  status-key :completed
                  schedule-key schedule
                  value-key payload}
        data (caught-data #(explore/decode-result schedule document))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :result-payload (:reason data)))
    (is (= :completed (:status data)))
    (is (= payload (:payload data)))))

;;; ---------------------------------------------------------------------------
;;; decode-result-edn: malformed EDN
;;; ---------------------------------------------------------------------------

(deftest decode-result-edn-rejects-malformed-edn-with-narrow-diagnostics
  (let [data (caught-data #(explore/decode-result-edn [0] "(not closed"))]
    (is (= :jolt.sim.explore/worker-protocol-error (:type data)))
    (is (= :malformed-result-edn (:reason data)))
    ;; No arbitrary exception is retained; only the narrow safe-error summary.
    (is (= :jolt.sim/exception (get-in data [:error :kind])))
    (is (= :result-reading (get-in data [:error :phase])))))

;;; ---------------------------------------------------------------------------
;;; Focused gate entry point (self-running, since this namespace is not wired
;;; into jolt.sim.test-main).
;;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [result (test/run-tests 'jolt.sim.explore-worker-test)
        failures (+ (:fail result) (:error result))]
    (println (str (:test result) " tests, "
                  (:pass result) " assertions passed"))
    (flush)
    (System/exit (if (zero? failures) 0 1))))
