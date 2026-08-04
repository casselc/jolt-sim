(ns jolt.sim.case-outcome-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.case-outcome :as case-outcome]
            [jolt.sim.trace :as trace]))

(defn- caught-data [f]
  (try
    (f)
    nil
    (catch :default error
      (ex-data error))))

(defn- unsigned-octets [bs]
  ;; Byte array elements are signed; compare content as unsigned octets, the
  ;; same projection jolt.sim.trace/canonical-value stores.
  (mapv #(bit-and (long %) 0xff) (seq bs)))

;; A representative hermetic case for the whole HTTP/SQLite/TCP outbox
;; delivery Hegel lane: the marked scenario wrapper, its closed input schema
;; (payload octets, bounded capacities, optional captured EINTR ordinal, and
;; one of two admission plans), and an exact future-schedule permutation.

(def ^:private outbox-scenario
  'jolt.sim.fixtures.outbox-delivery-scenarios/exercise-with-capacities)

(defn- outbox-input []
  {:payload [83 69 84 32 47 32 72 84 84 80 47 49 46 49]
   :stream-capacity 8
   :pipe-capacity 1
   :poll-eintr-ordinal nil
   :admission-plan :receiver-poll-then-http-poll})

(defn- outbox-case []
  {:scenario outbox-scenario
   :mode :hermetic
   :input (outbox-input)
   :schedule [1 0]})

(defn- outbox-result []
  {:http-status 200
   :outbox-rows 1
   :delivered-octets (byte-array [83 69 84])
   :ack :received})

(defn- completed-outcome []
  {:status :completed :result (outbox-result) :exit 0})

(defn- outbox-monitors []
  [{:id ::trace-grammar :status :pass :detail nil :index nil}
   {:id :outbox/at-least-once-delivery
    :status :pass
    :detail {:deliveries 2 :ack :received}
    :index 41}
   {:id :outbox/no-duplicate-delivery
    :status :inconclusive
    :detail {:assumption :single-reset-observed}
    :index nil}])

(defn- outbox-document []
  (case-outcome/document (outbox-case) (completed-outcome) (outbox-monitors)))

(deftest hermetic-outbox-completed-document-round-trips
  (let [doc (outbox-document)]
    (testing "top-level keys are exactly the namespaced contract keys"
      (is (= #{:jolt.sim.case-outcome/version
               :jolt.sim.case-outcome/case
               :jolt.sim.case-outcome/outcome
               :jolt.sim.case-outcome/monitors}
             (set (keys doc))))
      (is (= case-outcome/version
             (:jolt.sim.case-outcome/version doc))))
    (testing "validate-document! returns the document unchanged"
      (is (= doc (case-outcome/validate-document! doc))))
    (testing "the case stores scenario/mode/schedule directly and input canonical"
      (let [stored (:jolt.sim.case-outcome/case doc)]
        (is (= outbox-scenario (:scenario stored)))
        (is (= :hermetic (:mode stored)))
        (is (= [1 0] (:schedule stored)))
        (is (trace/canonical-form? (:input stored)))
        (is (= (trace/canonical-value (outbox-input)) (:input stored)))))
    (testing "completed outcome stores canonical :value"
      (let [stored (:jolt.sim.case-outcome/outcome doc)]
        (is (= #{:status :value :exit} (set (keys stored))))
        (is (= :completed (:status stored)))
        (is (= 0 (:exit stored)))
        (is (trace/canonical-form? (:value stored)))))
    (testing "monitors keep caller order with canonical :id and :detail"
      (let [stored (:jolt.sim.case-outcome/monitors doc)]
        (is (= 3 (count stored)))
        (is (= [:pass :pass :inconclusive] (mapv :status stored)))
        (is (every? #(trace/canonical-form? (:id %)) stored))
        (is (every? #(trace/canonical-form? (:detail %)) stored))))
    (testing "canonical EDN round-trips byte-stably"
      (let [printed (case-outcome/canonical-edn doc)
            read-back (case-outcome/read-edn printed)]
        (is (= doc read-back))
        (is (= printed (case-outcome/canonical-edn read-back)))))
    (testing "restoration helpers return the ordinary constructor inputs"
      (is (= (outbox-case) (case-outcome/restore-case doc)))
      (let [outcome (case-outcome/restore-outcome doc)]
        (is (= :completed (:status outcome)))
        (is (= 0 (:exit outcome)))
        (is (= 200 (get-in outcome [:result :http-status])))
        (is (= [83 69 84] (vec (:delivered-octets (:result outcome))))))
      (is (= (mapv #(dissoc % :detail) (outbox-monitors))
             (mapv #(dissoc % :detail) (case-outcome/restore-monitors doc))))
      (is (= (outbox-monitors) (case-outcome/restore-monitors doc))))
    (testing "restored ordinary values rebuild an identical document"
      (is (= doc (case-outcome/document (case-outcome/restore-case doc)
                                        (case-outcome/restore-outcome doc)
                                        (case-outcome/restore-monitors doc)))))))

(deftest all-four-outcome-statuses-round-trip
  (let [base (outbox-case)
        ordinary-error
        {:kind :jolt.sim/exception
         :class "class clojure.lang.ExceptionInfo"
         :message "delivery failed"
         :data {:attempt 2}}]
    (testing "completed"
      (let [outcome {:status :completed :result {:delivered 1} :exit 0}
            doc (case-outcome/document base outcome [])
            stored (:jolt.sim.case-outcome/outcome doc)]
        (is (= #{:status :value :exit} (set (keys stored))))
        (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
        (is (= outcome (case-outcome/restore-outcome doc)))))
    (testing "failed"
      (let [outcome {:status :failed :error ordinary-error :exit 1}
            doc (case-outcome/document base outcome [])
            stored (:jolt.sim.case-outcome/outcome doc)]
        (is (= #{:status :error :exit} (set (keys stored))))
        (is (trace/canonical-form? (:error stored)))
        (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
        (is (= outcome (case-outcome/restore-outcome doc)))))
    (testing "timeout stores no value or error field"
      (let [outcome {:status :timeout :reason :deadline :exit 124}
            doc (case-outcome/document base outcome [])
            stored (:jolt.sim.case-outcome/outcome doc)]
        (is (= #{:status :reason :exit} (set (keys stored))))
        (is (= :deadline (:reason stored)))
        (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
        (is (= outcome (case-outcome/restore-outcome doc)))))
    (testing "worker-error accepts a nil or integer exit"
      (doseq [exit [nil 137]]
        (let [outcome {:status :worker-error :error ordinary-error :exit exit}
              doc (case-outcome/document base outcome [])
              stored (:jolt.sim.case-outcome/outcome doc)]
          (is (= #{:status :error :exit} (set (keys stored))))
          (is (trace/canonical-form? (:error stored)))
          (is (= exit (:exit stored)))
          (is (= doc (case-outcome/read-edn (case-outcome/canonical-edn doc))))
          (is (= outcome (case-outcome/restore-outcome doc))))))))

(deftest outcome-field-combinations-fail-closed
  (let [base (outbox-case)]
    (testing "unknown status"
      (let [data (caught-data
                  #(case-outcome/document base {:status :bogus :exit 0} []))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :invalid-outcome-status (:reason data)))))
    (testing "completed requires :result, not :error"
      (let [data (caught-data
                  #(case-outcome/document
                    base {:status :completed :error {} :exit 0} []))]
        (is (= :outcome-wrong-keys (:reason data)))))
    (testing "completed and failed reject a negative exit"
      (doseq [outcome [{:status :completed :result {} :exit -1}
                       {:status :failed :error {} :exit -1}]]
        (let [data (caught-data #(case-outcome/document base outcome []))]
          (is (= :invalid-outcome-exit (:reason data)))
          (is (= (:status outcome) (get-in data [:detail :status]))))))
    (testing "timeout requires reason :deadline"
      (let [data (caught-data
                  #(case-outcome/document
                    base {:status :timeout :reason :other :exit 124} []))]
        (is (= :invalid-outcome-reason (:reason data)))))
    (testing "timeout rejects a nil exit"
      (let [data (caught-data
                  #(case-outcome/document
                    base {:status :timeout :reason :deadline :exit nil} []))]
        (is (= :invalid-outcome-exit (:reason data)))))
    (testing "worker-error rejects a non-integer, non-nil exit"
      (let [data (caught-data
                  #(case-outcome/document
                    base {:status :worker-error :error {} :exit 1.5} []))]
        (is (= :invalid-outcome-exit (:reason data)))))
    (testing "stored malformed canonical payloads are rejected"
      (let [doc (outbox-document)
            bad (assoc doc :jolt.sim.case-outcome/outcome
                       {:status :completed
                        :value [:jolt.sim.value/integer "not-a-number"]
                        :exit 0})
            data (caught-data #(case-outcome/validate-document! bad))]
        (is (= :invalid-outcome-value (:reason data)))))))

(deftest read-edn-rejects-malformed-empty-and-trailing-input
  (let [printed (case-outcome/canonical-edn (outbox-document))]
    (doseq [[label text reason]
            [["unbalanced" "{:jolt.sim.case-outcome/version 1" :unreadable-edn]
             ["empty" "" :unreadable-edn]
             ["whitespace-only" "   \n  " :unreadable-edn]
             ["trailing" (str printed " :trailing") :trailing-edn]
             ["forged-eof-sentinel"
              (str printed " :jolt.sim.case-outcome/end-of-input")
              :trailing-edn]]]
      (testing label
        (let [data (caught-data #(case-outcome/read-edn text))]
          (is (= case-outcome/invalid-document (:type data)))
          (is (= reason (:reason data))))))
    (testing "non-string input"
      (let [data (caught-data #(case-outcome/read-edn 42))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :not-a-string (:reason data)))))))

(deftest unknown-and-missing-keys-fail-closed
  (let [doc (outbox-document)]
    (testing "top level"
      (doseq [mutant [(assoc doc :extra :nope)
                      (dissoc doc :jolt.sim.case-outcome/monitors)]]
        (let [data (caught-data #(case-outcome/validate-document! mutant))]
          (is (= case-outcome/invalid-document (:type data)))
          (is (= :wrong-keys (:reason data))))))
    (testing "case"
      (doseq [mutant [(assoc (outbox-case) :extra :nope)
                      (dissoc (outbox-case) :input)]]
        (let [data (caught-data
                    #(case-outcome/document mutant (completed-outcome) []))]
          (is (= :case-wrong-keys (:reason data)))))
      (let [stored-extra (assoc-in doc [:jolt.sim.case-outcome/case :extra] 1)
            data (caught-data #(case-outcome/validate-document! stored-extra))]
        (is (= :case-wrong-keys (:reason data)))))
    (testing "outcome"
      (let [data (caught-data
                  #(case-outcome/document
                    (outbox-case)
                    {:status :completed :result {} :exit 0 :extra :nope}
                    []))]
        (is (= :outcome-wrong-keys (:reason data)))))
    (testing "monitor"
      (let [data (caught-data
                  #(case-outcome/document
                    (outbox-case) (completed-outcome)
                    [{:id :m :status :pass :detail nil :index nil :extra 1}]))]
        (is (= :monitor-wrong-keys (:reason data)))))))

(deftest unsupported-version-fails-closed
  (let [doc (outbox-document)]
    (doseq [version [2 "1" nil :one]]
      (let [mutant (assoc doc :jolt.sim.case-outcome/version version)
            data (caught-data #(case-outcome/validate-document! mutant))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :unsupported-version (:reason data)))))
    (testing "through read-edn"
      (let [printed (pr-str (assoc doc :jolt.sim.case-outcome/version 2))
            data (caught-data #(case-outcome/read-edn printed))]
        (is (= :unsupported-version (:reason data)))))))

(deftest byte-arrays-are-isolated-at-construction-and-restored-fresh
  (let [input-bytes (byte-array [0 255 128])
        result-bytes (byte-array [1 2 3])
        doc (case-outcome/document
             {:scenario outbox-scenario
              :mode :hermetic
              :input {:payload input-bytes}
              :schedule nil}
             {:status :completed :result {:octets result-bytes} :exit 0}
             [])]
    (testing "mutating the source arrays after construction changes nothing"
      (aset input-bytes 0 99)
      (aset result-bytes 0 99)
      (is (= [0 255 128]
             (unsigned-octets (:payload (:input (case-outcome/restore-case doc))))))
      (is (= [1 2 3]
             (vec (:octets (:result (case-outcome/restore-outcome doc)))))))
    (testing "every restoration allocates fresh arrays"
      (let [first-restore (:octets (:result (case-outcome/restore-outcome doc)))
            second-restore (:octets (:result (case-outcome/restore-outcome doc)))]
        (is (bytes? first-restore))
        (is (not (identical? first-restore second-restore)))
        (is (= (vec first-restore) (vec second-restore)))
        (aset first-restore 0 42)
        (is (= [1 2 3] (vec second-restore)))
        (is (= [1 2 3]
               (vec (:octets (:result (case-outcome/restore-outcome doc))))))))
    (testing "restored byte content survives EDN round-trip"
      (let [read-back (case-outcome/read-edn (case-outcome/canonical-edn doc))]
        (is (= [1 2 3]
               (vec (:octets (:result (case-outcome/restore-outcome read-back))))))))))

(deftest canonical-edn-is-stable-under-map-reordering
  (let [reverse-map (fn [value] (into {} (reverse (seq value))))
        forward (case-outcome/document
                 (outbox-case)
                 (completed-outcome)
                 (outbox-monitors))
        reordered (case-outcome/document
                   (reverse-map (update (outbox-case) :input reverse-map))
                   (reverse-map (update (completed-outcome) :result reverse-map))
                   (mapv reverse-map (outbox-monitors)))]
    (is (= forward reordered))
    (is (= (case-outcome/canonical-edn forward)
           (case-outcome/canonical-edn reordered)))
    (testing "a hand-stored reordered document renders identically"
      (let [restored (into {}
                           (reverse (seq forward)))
            printed (case-outcome/canonical-edn restored)]
        (is (= (case-outcome/canonical-edn forward) printed))))))

(deftest monitor-order-status-index-and-duplicate-rules
  (testing "order is preserved through storage, EDN, and restoration"
    (let [monitors [{:id :first :status :pass :detail nil :index nil}
                    {:id :second :status :violation :detail {:at 3} :index 3}
                    {:id :third :status :inconclusive :detail nil :index 7}]
          doc (case-outcome/document (outbox-case) (completed-outcome) monitors)
          read-back (case-outcome/read-edn (case-outcome/canonical-edn doc))]
      (is (= [:first :second :third]
             (mapv :id (case-outcome/restore-monitors read-back))))
      (is (= monitors (case-outcome/restore-monitors read-back)))))
  (testing "exact duplicate canonical ids fail closed"
    (let [duplicate {:id :same :status :pass :detail nil :index nil}
          data (caught-data
                #(case-outcome/document
                  (outbox-case) (completed-outcome)
                  [duplicate (assoc duplicate :status :violation :index 4)]))]
      (is (= case-outcome/invalid-document (:type data)))
      (is (= :duplicate-monitor-id (:reason data))))
    (let [doc (outbox-document)
          stored (get doc :jolt.sim.case-outcome/monitors)
          mutant (assoc doc :jolt.sim.case-outcome/monitors
                        (conj stored (first stored)))
          data (caught-data #(case-outcome/validate-document! mutant))]
      (is (= :duplicate-monitor-id (:reason data)))))
  (testing "ordinary ids that are = but canonically distinct do not collide"
    (let [doc (case-outcome/document
               (outbox-case) (completed-outcome)
               [{:id (list 1 2) :status :pass :detail nil :index nil}
                {:id [1 2] :status :pass :detail nil :index nil}])]
      (is (= [(list 1 2) [1 2]]
             (mapv :id (case-outcome/restore-monitors doc))))))
  (testing "invalid monitor status"
    (let [data (caught-data
                #(case-outcome/document
                  (outbox-case) (completed-outcome)
                  [{:id :m :status :unknown :detail nil :index nil}]))]
      (is (= :invalid-monitor-status (:reason data)))))
  (testing "invalid monitor index"
    (doseq [index [-1 1.5 "0"]]
      (let [data (caught-data
                  #(case-outcome/document
                    (outbox-case) (completed-outcome)
                    [{:id :m :status :pass :detail nil :index index}]))]
        (is (= :invalid-monitor-index (:reason data)))
        (is (= 0 (get-in data [:detail :position]))))))
  (testing "monitors must be a vector"
    (let [data (caught-data
                #(case-outcome/document
                  (outbox-case) (completed-outcome)
                  (list {:id :m :status :pass :detail nil :index nil})))]
      (is (= :monitors-not-a-vector (:reason data)))))
  (testing "stored malformed canonical id and detail are rejected"
    (let [doc (outbox-document)
          bad-id (assoc-in doc [:jolt.sim.case-outcome/monitors 0 :id]
                           [:jolt.sim.value/bogus])
          bad-detail (assoc-in doc [:jolt.sim.case-outcome/monitors 0 :detail]
                               [:jolt.sim.value/integer "nope"])]
      (is (= :invalid-monitor-id
             (:reason (caught-data #(case-outcome/validate-document! bad-id)))))
      (is (= :invalid-monitor-detail
             (:reason (caught-data
                       #(case-outcome/validate-document! bad-detail))))))))

(deftest case-validation-fails-closed
  (testing "scenario must be a namespaced symbol"
    (doseq [scenario ['unnamespaced "a.b/c" :a.b/c nil]]
      (let [data (caught-data
                  #(case-outcome/document
                    (assoc (outbox-case) :scenario scenario)
                    (completed-outcome) []))]
        (is (= case-outcome/invalid-document (:type data)))
        (is (= :invalid-scenario (:reason data))))))
  (testing "mode must be exactly :real, :hermetic, or :hybrid"
    (doseq [mode [:bogus "hermetic" nil]]
      (let [data (caught-data
                  #(case-outcome/document
                    (assoc (outbox-case) :mode mode)
                    (completed-outcome) []))]
        (is (= :invalid-mode (:reason data)))))
    (doseq [mode [:real :hermetic :hybrid]]
      (is (map? (case-outcome/document
                 (assoc (outbox-case) :mode mode)
                 (completed-outcome) [])))))
  (testing "schedule must be nil or an exact future-schedule permutation"
    (doseq [schedule [[0 0] [1] [0 2] "0 1" #{0 1}]]
      (let [data (caught-data
                  #(case-outcome/document
                    (assoc (outbox-case) :schedule schedule)
                    (completed-outcome) []))]
        (is (= :invalid-schedule (:reason data)))))
    (is (map? (case-outcome/document
               (assoc (outbox-case) :schedule nil)
               (completed-outcome) []))))
  (testing "stored malformed canonical input is rejected"
    (let [doc (outbox-document)
          mutant (assoc-in doc [:jolt.sim.case-outcome/case :input]
                           [:jolt.sim.value/float "1.5x"])
          data (caught-data #(case-outcome/validate-document! mutant))]
      (is (= :invalid-input (:reason data)))))
  (testing "a non-map document or section fails closed"
    (is (= :not-a-map
           (:reason (caught-data #(case-outcome/validate-document! [])))))
    (is (= :case-not-a-map
           (:reason (caught-data
                     #(case-outcome/validate-document!
                       (assoc (outbox-document)
                              :jolt.sim.case-outcome/case []))))))))

(deftest metadata-functions-and-host-objects-fail-closed
  (testing "trace-domain violations surface jolt.sim.trace/unsupported-value"
    (doseq [[label input]
            [["function" (fn [] nil)]
             ["metadata" (with-meta [1 2] {:a 1})]
             ["host object" (atom nil)]]]
      (testing label
        (let [data (caught-data
                    #(case-outcome/document
                      (assoc (outbox-case) :input input)
                      (completed-outcome) []))]
          (is (= trace/unsupported-value (:type data)))))))
  (testing "metadata on structural maps fails as invalid-document"
    (let [data (caught-data
                #(case-outcome/document
                   (with-meta (outbox-case) {:a 1})
                   (completed-outcome) []))]
      (is (= case-outcome/invalid-document (:type data)))
      (is (= :metadata (:reason data))))
    (let [data (caught-data
                #(case-outcome/validate-document!
                  (with-meta (outbox-document) {:a 1})))]
      (is (= :metadata (:reason data)))))
  (testing "error data never retains a raw rejected host object"
    (let [host (atom nil)
          data (caught-data
                #(case-outcome/document
                  (assoc (outbox-case) :scenario host)
                  (completed-outcome) []))]
      (is (= :invalid-scenario (:reason data)))
      (is (string? (:detail data)))
      (is (not (identical? host (:detail data)))))
    (let [host (atom nil)
          data (caught-data #(case-outcome/validate-document! {host :v}))]
      (is (= :wrong-keys (:reason data)))
      (is (every? string? (:detail data))))))

(deftest malformed-lazy-values-are-rejected-without-realization
  (let [realized? (atom false)
        hostile (lazy-seq
                 (reset! realized? true)
                 (throw (ex-info "must not realize rejected diagnostics" {})))
        doc (assoc-in (outbox-document)
                      [:jolt.sim.case-outcome/case :input]
                      hostile)
        data (caught-data #(case-outcome/validate-document! doc))]
    (is (= case-outcome/invalid-document (:type data)))
    (is (= :invalid-input (:reason data)))
    (is (false? @realized?))
    (is (string? (:detail data)))))

(deftest restore-helpers-validate-before-projecting
  (doseq [restore [case-outcome/restore-case
                   case-outcome/restore-outcome
                   case-outcome/restore-monitors]]
    (let [data (caught-data #(restore {:jolt.sim.case-outcome/version 2}))]
      (is (= case-outcome/invalid-document (:type data)))
      (is (= :wrong-keys (:reason data))))
    (let [data (caught-data #(restore "not-a-document"))]
      (is (= :not-a-map (:reason data))))))
