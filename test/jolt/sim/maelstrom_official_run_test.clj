(ns jolt.sim.maelstrom-official-run-test
  (:require [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is]]
            [jolt.sim.maelstrom.official-run :as official-run]))

(def ^:private digest (apply str (repeat 64 "a")))

(defn- operations [count]
  (mapv (fn [index]
          {:type (if (even? index) :invoke :ok)
           :f (if (zero? (mod index 3)) :read :broadcast)
           :process (mod index 4)
           :time (* index 1000)
           :value {:message index}})
        (range count)))

(defn- sample-document
  ([] (sample-document 3))
  ([operation-count]
   (official-run/document
    {:profile :broadcast-partition
     :workload :broadcast
     :parameters {:node-count 5 :rate 10 :topology "line"
                  :nemesis :partition}}
    {:status :passed :exit 0
     :official-valid? true :workload-valid? true
     :checks {:attempt-count 10 :never-read-count 0 :lost-count 0}
     :stats {:reads 12}}
    {:total-count operation-count
     :truncated? false
     :artifact "history.edn"
     :operations (operations operation-count)}
    [{:name "history.edn" :role :history :bytes 2048 :sha256 digest}
     {:name "results.edn" :role :results :bytes 512 :sha256 digest}])))

(defn- thrown-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(deftest round-trips-deterministically
  (let [document (sample-document)
        encoded (official-run/canonical-edn document)
        page (official-run/read-page (official-run/read-edn encoded))]
    (is (= document (official-run/read-edn encoded)))
    (is (= encoded (official-run/canonical-edn (official-run/read-edn encoded))))
    (is (= {:attempt-count 10 :never-read-count 0 :lost-count 0}
           (get-in page [:header :outcome :checks])))
    (is (= :broadcast-partition (get-in page [:header :run :profile])))))

(deftest preserves-unknown-and-failure-outcomes
  (let [document
        (official-run/document
         {:profile :echo :workload :echo :parameters {}}
         {:status :failed :exit 1
          :official-valid? :unknown :workload-valid? nil
          :checks {} :stats {}}
         {:total-count 0 :truncated? false :artifact "history.edn"
          :operations []}
         [{:name "history.edn" :role :history :bytes 0 :sha256 digest}])]
    (is (= :unknown
           (get-in (official-run/read-page document)
                   [:header :outcome :official-valid?])))
    (is (nil? (get-in (official-run/read-page document)
                      [:header :outcome :workload-valid?])))))

(deftest bounded-capture-retains-raw-history-identity
  (let [document
        (official-run/document
         {:profile :broadcast :workload :broadcast :parameters {}}
         {:status :failed :exit 1 :official-valid? false
          :workload-valid? false :checks {:reason :vacuous} :stats {}}
         {:total-count 10000 :truncated? true :artifact "history.edn"
          :operations (operations 3)}
         [{:name "history.edn" :role :history :bytes 900000 :sha256 digest}])
        history (get-in (official-run/read-page document) [:header :history])]
    (is (= 10000 (:total-count history)))
    (is (= 3 (:captured-count history)))
    (is (true? (:truncated? history)))
    (is (= "history.edn" (:artifact history)))))

(deftest malformed-documents-fail-closed
  (let [document (sample-document)]
    (doseq [[reason value]
            [[:wrong-document-shape (assoc document :extra true)]
             [:unsupported-version
              (assoc document :jolt.sim.maelstrom.official-run/version 2)]
             [:invalid-history
              (assoc-in document
                        [:jolt.sim.maelstrom.official-run/history :captured-count]
                        99)]
             [:invalid-artifacts
              (assoc document :jolt.sim.maelstrom.official-run/artifacts
                     [{:name "/tmp/secret" :role :history
                       :bytes 1 :sha256 digest}])]
             [:invalid-artifacts
              (assoc-in document
                        [:jolt.sim.maelstrom.official-run/artifacts 0 :role]
                        :results)]]]
      (is (= reason
             (:reason (thrown-data #(official-run/validate-document! value)))))))
  (is (= :trailing-input
         (:reason (thrown-data #(official-run/read-edn
                                 (str (official-run/canonical-edn
                                       (sample-document)) " :extra"))))))
  (is (= :empty-input
         (:reason (thrown-data #(official-run/read-edn "")))))
  ;; Shape rejection precedes recursive metadata traversal/realization.
  (is (= :invalid-run
         (:reason
          (thrown-data
           #(official-run/validate-document!
             (assoc (sample-document)
                    :jolt.sim.maelstrom.official-run/run (iterate inc 0))))))))

(deftest pages-and-datafy-navigation-are-ui-neutral
  (let [document (sample-document 35)
        first-page (official-run/read-page document)
        second-page (official-run/read-page document 32)
        source (official-run/source document)
        datafied (datafy/datafy source)
        original (:clojure.datafy/obj (meta datafied))
        navigated (datafy/nav original :next-page (:next-page datafied))]
    (is (= 32 (count (:operations first-page))))
    (is (= 3 (count (:operations second-page))))
    (is (map? (:header first-page)))
    (is (nil? (:header second-page)))
    (is (= {:cursor 32} (:next-page first-page)))
    (is (= (:operations second-page) (:operations navigated)))
    (is (= :jolt.sim.kind/maelstrom-operation
           (get-in first-page [:operations 0 :kind])))
    (is (= {:message 0} (get-in first-page [:operations 0 :value])))))

(deftest constructor-and-cursors-reject-unbounded-input
  (is (= :invalid-operation-input
         (:reason
          (thrown-data
           #(official-run/document
             {:profile :echo :workload :echo :parameters {}}
             {:status :failed :exit 1 :official-valid? false
              :workload-valid? false :checks {} :stats {}}
             {:total-count 0 :truncated? false :artifact "history.edn"
              :operations nil}
             [])))))
  (let [document (sample-document)]
    (doseq [cursor [-1 4 :bad]]
      (is (= official-run/invalid-cursor
             (:type (thrown-data #(official-run/read-page document cursor))))))))
